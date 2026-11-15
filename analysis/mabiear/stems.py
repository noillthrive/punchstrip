"""Stem clues: drum-stem hits and bass-stem notes on the MabiIcco grid.

Ported from an earlier prototype script of this project (rules only, no learning).

Timeline: the tool measure ``measure_offset`` (default 1) starts at ``first_downbeat_sec`` (+ the estimated phase),
quarter = 96 ticks, ``bpm`` is quarter notes per minute (MabiIcco ``t``), one time signature for the whole stem.

Drums (``stem_drum_hits``): STFT 2048/256 of the mono drum stem at 44.1 kHz. Band powers low 30-150, lowmid 80-300,
body 150-400, noise 1.5-5k, high 6-16k, cymbal 3-16k. Band-limited spectral flux per band group with adaptive peak
picking gives three event lists (so a kick and a hat on the same 16th are both kept):

- low-band onset -> kick, or tom when 80-300 Hz dominates, the low band takes >= 150 ms to fall 10 dB and the body
  band is not above the low band;
- body+noise onset -> snare (noise jump >= 5 dB, body jump >= 3 dB, noise decay >= 50 ms), side stick (noise jump,
  decay < 50 ms, weak body), ghost (snare shape, >= 10 dB under the section's 90th-percentile snare);
- high-band onset -> crash (3-16k decay >= 450 ms and jump >= 9 dB), ride (long decay, small jump, flatness < 0.2;
  low confidence), closed hat (6-16k decay < 120 ms), open hat (120-450 ms), unknown otherwise.

Cross-band clean-up drops the snare body seen in the low band, the kick click seen in the mid/high band and the snare
wires seen as an open hat. Hits are quantised to the 16th grid (24 ticks), or to the 32nd grid (12 ticks) when the
residual exceeds 7 ticks or two same-class hits collide (``grid: 16``); ``grid: 32`` always uses 12 ticks.

Levels: every hit gets a K-weighted level (BS.1770 filter, 100 ms from the onset) relative to the stem's
99.5th-percentile 100 ms level (``level_db``). A hit that starts within 30 ms of a hit of another band group takes its
own band's level plus the median (full - band) offset of isolated hits of its class instead (``level_source``
"band_est"). ``rel_kick_db`` = level_db minus the 90th percentile of isolated kick levels. The suggested drum-27 key
velocity solves ``level_db_vs_kick(key) + 40*log10(v/15) = rel_kick_db`` (table ``drum27_keys.json`` in the bridge),
rounded and clamped to 1..15 (``capped`` when the stem hit is louder than the key can be).

Bass (``stem_bass_notes``): mono bass stem at 22050 Hz, pyin (frame 2048, hop 256) between ``range_low`` and
``range_high``; onsets from band flux; a note runs from an onset/voiced start until the band envelope falls 12 dB
under the note peak, the pitch moves >= 0.8 semitone for 60 ms, or the next onset. Pitch = median rounded MIDI of the
voiced frames (sounding pitch). Notes are quantised to the grid, same-pitch touching notes are merged.
"""
from __future__ import annotations

import json
import logging
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.stems")

SCHEMA_DRUMS = "mabiear.stem_drums/1"
SCHEMA_BASS = "mabiear.stem_bass/1"
TPQ = 96
DRUM_SR = 44100
NFFT = 2048
HOP = 256
BASS_SR = 22050
# The drum-27 key table is shipped as package data (mabiear/data/drum27_keys.json, a copy of the bridge's
# src-bridge/mabibridge/engine/drum27_keys.json; tests/test_stems_drum_bass.py checks that the two are identical). The source-tree
# copy is the fallback for an old checkout without the package data.
DRUM_TABLE_PACKAGE = Path(__file__).resolve().parent / "data" / "drum27_keys.json"
DRUM_TABLE_BRIDGE = io.REPO_ROOT / "src-bridge" / "mabibridge" / "engine" / "drum27_keys.json"
DRUM_TABLE_PATH = DRUM_TABLE_PACKAGE if DRUM_TABLE_PACKAGE.is_file() else DRUM_TABLE_BRIDGE

# class -> drum-27 key id in the table
CLASS_KEY_ID = {"kick": "kick", "snare": "snare", "ghost": "ghost", "side_stick": "side_stick",
                "closed_hat": "closed_hat", "open_hat": "open_hat", "crash": "crash", "ride": "ride",
                "low_tom": "low_tom", "mid_tom": "mid_tom", "high_tom": "high_tom"}
CLASSES = ("kick", "snare", "ghost", "side_stick", "closed_hat", "open_hat", "crash", "ride", "low_tom", "mid_tom",
           "high_tom", "unknown")
GROUP = {"kick": "low", "low_tom": "low", "mid_tom": "low", "high_tom": "low", "snare": "mid", "ghost": "mid",
         "side_stick": "mid", "closed_hat": "high", "open_hat": "high", "crash": "high", "ride": "high",
         "unknown": "high"}
CONF_VALUE = {"high": 0.85, "medium": 0.6, "low": 0.3}
LEVEL_WIN_SEC = 0.100

DRUM_NOTES = [
    "Ride vs open hat is unreliable: both are long high-band decays; ride needs low flatness and is always low "
    "confidence.",
    "Shaker vs hat is unreliable: a dense shaker texture shows up as many quiet closed/open hats; beats marked in "
    "shaker_beats (>= 4 high-band onsets, no 10 dB dip, flatness >= 0.25) are textures, not single hits.",
    "Toms are reported only when 80-300 Hz dominates with a long decay; kick/snare fills are not split into toms.",
    "Crashes under dense cymbals or right after another crash can be missed; crashes often fall on the last beat "
    "before a section (pickup: true).",
    "Bleed: kick clicks in the mid/high band and snare wires in the high band are dropped by the cross-band rules "
    "(counts in dropped); other instruments bleeding into the stem (music box, piano attacks) appear as quiet hats or "
    "side sticks.",
    "Levels are K-weighted 100 ms windows relative to the stem, not absolute; the key table is 400 ms K-weighted at v12, "
    "so suggested velocities are approximate. The kick 41 is quiet in game: low_tom_layer adds 43 v9 on beats 1 and 3.",
]
BASS_NOTES = [
    "Pitches are sounding MIDI numbers (use score_put_notes pitch_mode \"sounding\" on a bass 29 track).",
    "octave_ambiguous: pyin jumped by an octave inside the note, the fundamental is >= 10 dB under the 2nd harmonic "
    "(the ear may take the octave above), or the sub-octave carries energy within 6 dB (the note may be an octave "
    "lower). Check those by ear.",
    "Slides, ghost notes and notes shorter than min_note_ticks are dropped; chords on the bass stem give the lowest "
    "stable pitch only.",
]


# ---------------------------------------------------------------- table, grid

_table_cache: dict | None = None


def drum_table() -> dict:
    """The drum-27 key table shared with the bridge (package data, see DRUM_TABLE_PATH)."""
    global _table_cache
    if _table_cache is None:
        try:
            _table_cache = json.loads(DRUM_TABLE_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise EarError("INTERNAL", "드럼 키 표(drum27_keys.json)를 읽지 못했습니다.",
                           {"path": str(DRUM_TABLE_PATH), "reason": str(e)})
    return _table_cache


def key_by_id() -> dict[str, dict]:
    return {k["id"]: k for k in drum_table()["keys"]}


def velocity_for(key: dict, rel_kick_db: float) -> tuple[int, bool]:
    """(velocity 1..15, capped) so that the key's level matches rel_kick_db (dB vs the kick at v15)."""
    lv = key.get("level_db_vs_kick")
    vmax = drum_table().get("velocity_max", 15)
    if lv is None:
        return int(key.get("suggested_vel") or vmax), False
    v = vmax * 10 ** ((rel_kick_db - lv) / 40.0)
    capped = v > vmax + 0.5
    return int(min(vmax, max(1, round(v)))), capped


_SIG = re.compile(r"^\s*(\d{1,2})\s*/\s*(\d{1,2})\s*$")


def parse_time_signature(ts) -> tuple[int, int]:
    if not isinstance(ts, str):
        raise EarError("BAD_ARGUMENT", "'time_signature'는 \"4/4\" 같은 문자열이어야 합니다.", {"time_signature": ts})
    m = _SIG.match(ts)
    if not m or int(m.group(2)) not in (1, 2, 4, 8, 16, 32, 64) or not 1 <= int(m.group(1)) <= 64:
        raise EarError("BAD_ARGUMENT", "'time_signature'는 \"분자/분모\"(분자 1~64, 분모 1·2·4·8·16·32·64)입니다.",
                       {"time_signature": ts})
    return int(m.group(1)), int(m.group(2))


class Grid:
    """Seconds <-> MabiIcco ticks for one stem: tick 0 = the start of measure 1 (tool numbering)."""

    def __init__(self, bpm: float, first_downbeat_sec: float, num: int, den: int, measure_offset: int,
                 phase: float = 0.0):
        self.bpm = bpm
        self.first = first_downbeat_sec
        self.num, self.den = num, den
        self.beat_ticks = 4 * TPQ // den
        self.tpm = num * self.beat_ticks
        self.offset = measure_offset
        self.phase = phase
        self.quarter_sec = 60.0 / bpm

    @property
    def tick0(self) -> int:
        """Absolute tick of the first downbeat (start of measure measure_offset)."""
        return (self.offset - 1) * self.tpm

    def tick2sec(self, t: float) -> float:
        return self.first + self.phase + (t - self.tick0) / TPQ * self.quarter_sec

    def sec2tick(self, s: float) -> float:
        return self.tick0 + (s - self.first - self.phase) / self.quarter_sec * TPQ

    def pos(self, t: int) -> tuple[str, int, int, int]:
        """("m:b:t", measure, beat, tick_in_beat) of an absolute tick."""
        t = int(round(t))
        m = t // self.tpm + 1
        rr = t % self.tpm
        b = rr // self.beat_ticks + 1
        return f"{m}:{b}:{rr % self.beat_ticks}", m, b, rr % self.beat_ticks

    def measure_of(self, t: float) -> int:
        return int(max(0, t) // self.tpm) + 1


def quant(tk: float, grid: int) -> int:
    return int(round(tk / grid) * grid)


# ---------------------------------------------------------------- args

def check_sections(sections) -> list[dict]:
    if sections is None:
        return []
    if not isinstance(sections, list):
        raise EarError("BAD_ARGUMENT", "'sections'는 [{name, from_measure, to_measure}, …] 목록이어야 합니다.", {})
    out = []
    for i, s in enumerate(sections):
        if not isinstance(s, dict) or set(s) - {"name", "from_measure", "to_measure"}:
            raise EarError("BAD_ARGUMENT", f"sections[{i}]는 {{name, from_measure, to_measure}} 객체여야 합니다.",
                           {"index": i, "entry": s})
        nm = s.get("name")
        a, b = s.get("from_measure"), s.get("to_measure")
        if not isinstance(nm, str) or not nm.strip():
            raise EarError("BAD_ARGUMENT", f"sections[{i}].name은 비어 있지 않은 문자열이어야 합니다.", {"index": i})
        for k, v in (("from_measure", a), ("to_measure", b)):
            if isinstance(v, bool) or not isinstance(v, int) or v < 1:
                raise EarError("BAD_ARGUMENT", f"sections[{i}].{k}는 1 이상 정수여야 합니다.", {"index": i, k: v})
        if a > b:
            raise EarError("BAD_ARGUMENT", f"sections[{i}]: from_measure > to_measure", {"index": i})
        out.append({"name": nm.strip(), "from_measure": a, "to_measure": b})
    return out


def section_of(sections: list[dict], m: int) -> str | None:
    for s in sections:
        if s["from_measure"] <= m <= s["to_measure"]:
            return s["name"]
    return None


# ---------------------------------------------------------------- spectra

def _db(x):
    import numpy as np

    return 10 * np.log10(np.maximum(x, 1e-12))


class Spec:
    def __init__(self, y, sr: int, n_fft: int = NFFT, hop: int = HOP):
        import librosa
        import numpy as np

        self.sr, self.hop = sr, hop
        self.S = np.abs(librosa.stft(y, n_fft=n_fft, hop_length=hop)) ** 2
        self.f = librosa.fft_frequencies(sr=sr, n_fft=n_fft)
        self.t = librosa.frames_to_time(np.arange(self.S.shape[1]), sr=sr, hop_length=hop)
        self.full = self.S[(self.f >= 30) & (self.f <= 16000)].sum(0)

    def band(self, lo, hi):
        sel = (self.f >= lo) & (self.f < hi)
        if not sel.any():
            sel = self.f >= self.f[-1]
        return self.S[sel].sum(0)

    def flux(self, lo, hi):
        import numpy as np

        sel = (self.f >= lo) & (self.f < hi)
        if not sel.any():
            return np.zeros(self.S.shape[1])
        L = _db(self.S[sel])
        return np.maximum(0, np.diff(L, axis=1, prepend=L[:, :1])).mean(0)

    def fr(self, s: float) -> int:
        import numpy as np

        return int(np.clip(round(s * self.sr / self.hop), 0, self.S.shape[1] - 1))


def _peaks(x, delta=0.06, wait=8):
    import librosa
    import numpy as np

    if x.size < 30 or not np.any(x > 0):
        return np.array([], dtype=int)
    x = x / (np.percentile(x, 99.5) + 1e-9)
    return librosa.util.peak_pick(x, pre_max=3, post_max=3, pre_avg=12, post_avg=12, delta=delta, wait=wait)


def _decay_ms(env_db, i, stop, sr, hop, drop=10.0):
    import numpy as np

    pk = i + int(np.argmax(env_db[i:i + 6]))
    p = env_db[pk]
    j = pk
    while j < min(stop, len(env_db) - 1) and env_db[j] > p - drop:
        j += 1
    return (j - pk) * hop / sr * 1000, pk


def _load(path: Path, sr: int):
    y, _ = io.load_mono(path, sr=sr)
    if y.size < sr // 4:
        raise EarError("BAD_ARGUMENT", "스템 오디오가 너무 짧습니다(0.25초 미만).", {"path": str(path)})
    return y


# ---------------------------------------------------------------- tempo / phase

def comb_search(env, sp: Spec, grid: Grid, bpm_ratio_range=(0.97, 1.03), ratio_step=0.0005,
                phase_ms=60.0, phase_step_ms=2.0):
    """16th-grid comb sum of an onset envelope over phase (and tempo ratio). Returns dict."""
    import numpy as np

    env = env / (env.max() + 1e-9)
    envm = np.maximum.reduce([np.roll(env, -1), env, np.roll(env, 1)])
    n_frames = env.size
    dur = sp.t[-1] if sp.t.size else 0.0
    step16 = grid.quarter_sec / 4
    k = np.arange(0, max(1, int((dur - grid.first) / step16)))
    phases = np.arange(-phase_ms, phase_ms + 1e-9, phase_step_ms) / 1000.0 if phase_ms > 0 else np.array([0.0])

    def score(ratio, ph):
        t = grid.first + ph + ratio * k * step16
        idx = np.round(t * sp.sr / sp.hop).astype(int)
        idx = idx[(idx >= 0) & (idx < n_frames)]
        return float(envm[idx].sum()) / max(1, idx.size)

    base = [(score(1.0, ph), ph) for ph in phases]
    best_phase = max(base)[1]
    ratios = np.arange(bpm_ratio_range[0], bpm_ratio_range[1] + 1e-9, ratio_step)
    coarse_ph = np.arange(-phase_ms, phase_ms + 1e-9, max(phase_step_ms, 4.0)) / 1000.0 if phase_ms > 0 else [0.0]
    best = (-1.0, 1.0, 0.0)
    for ra in ratios:
        for ph in coarse_ph:
            s = score(ra, ph)
            if s > best[0]:
                best = (s, float(ra), float(ph))
    return {"phase_sec": float(best_phase), "score_at_given_bpm": max(base)[0], "best_ratio": best[1],
            "best_score": best[0]}


def tempo_check(y, sr, env, sp: Spec, grid: Grid, phase_ms: float) -> tuple[dict, float]:
    import librosa
    import numpy as np

    cs = comb_search(env, sp, grid, phase_ms=phase_ms)
    try:
        oenv = librosa.onset.onset_strength(y=y, sr=sr, hop_length=512)
        tb = librosa.feature.tempo(onset_envelope=oenv, sr=sr, hop_length=512, start_bpm=grid.bpm)
        beat_track_bpm = float(np.atleast_1d(tb)[0])
    except Exception as e:  # pragma: no cover - librosa version differences
        log.info("beat tempo failed: %s", e)
        beat_track_bpm = None
    # beat tracking can lock to half/double tempo: compare with the nearest of bpm/2, bpm, 2*bpm
    bt_ratio = None
    if beat_track_bpm:
        cands = [grid.bpm / 2, grid.bpm, grid.bpm * 2]
        near = min(cands, key=lambda c: abs(beat_track_bpm / c - 1))
        bt_ratio = beat_track_bpm / near
    comb_bpm = grid.bpm / cs["best_ratio"]
    off = comb_bpm / grid.bpm - 1
    warn = abs(off) > 0.01
    res = {"given_bpm": grid.bpm, "comb_bpm": r(comb_bpm, 3), "comb_off_pct": r(off * 100, 2),
           "beat_track_bpm": r(beat_track_bpm, 2), "beat_track_off_pct": r((bt_ratio - 1) * 100, 2) if bt_ratio else None,
           "warning": (f"스템의 템포가 준 bpm과 {off * 100:+.2f}% 다릅니다(16분 격자 빗살 맞춤 {comb_bpm:.2f} BPM). "
                       "bpm·first_downbeat_sec을 확인하세요.") if warn else None,
           "method": "comb_bpm: 16th-grid comb sum of the full-band onset flux, tempo ratio 0.97-1.03 (step 0.0005) "
                     "x phase; beat_track_bpm: librosa.feature.tempo (coarse, nearest of bpm/2, bpm, 2*bpm)"}
    return res, cs["phase_sec"]


# ---------------------------------------------------------------- drums

def _k_levels(y, sr, times, win=LEVEL_WIN_SEC):
    import numpy as np

    from .balance import K_OFFSET_DB, k_weight

    yk = k_weight(y, sr) ** 2
    c = np.concatenate([[0.0], np.cumsum(yk)])
    n = yk.size

    def lvl(t0):
        a = int(max(0, (t0 - 0.005) * sr))
        b = int(min(n, a + win * sr))
        if b <= a:
            return -120.0
        ms = (c[b] - c[a]) / (b - a)
        return K_OFFSET_DB + 10 * math.log10(ms) if ms > 0 else -120.0

    # stem reference: 99.5th percentile of 100 ms windows (hop 25 ms)
    starts = np.arange(0, max(1, n - int(win * sr)), int(0.025 * sr))
    ends = np.minimum(n, starts + int(win * sr))
    ms = (c[ends] - c[starts]) / np.maximum(1, ends - starts)
    top = float(K_OFFSET_DB + 10 * np.log10(np.percentile(ms[ms > 0], 99.5))) if np.any(ms > 0) else -120.0
    return [lvl(t) for t in times], top


def analyse_drums(path: Path, bpm: float, first: float, num: int, den: int, grid_kind: int, measure_offset: int,
                  sections: list[dict], min_conf: float, low_tom_layer: bool, phase_ms: float) -> dict:
    import librosa
    import numpy as np

    y = _load(path, DRUM_SR)
    sr = DRUM_SR
    sp = Spec(y, sr)
    g = Grid(bpm, first, num, den, measure_offset)
    if first >= sp.t[-1]:
        raise EarError("BAD_ARGUMENT", "first_downbeat_sec가 스템 길이보다 깁니다.",
                       {"first_downbeat_sec": first, "duration_sec": r(float(sp.t[-1]), 3)})
    tempo, phase = tempo_check(y, sr, sp.flux(30, 16000), sp, g, phase_ms)
    g.phase = phase
    B = {k: _db(sp.band(*rg)) for k, rg in dict(low=(30, 150), lowmid=(80, 300), body=(150, 400),
                                                 noise=(1500, 5000), high=(6000, 16000), cym=(3000, 16000)).items()}
    hi_sel = sp.f >= 4000
    flat = librosa.feature.spectral_flatness(S=np.sqrt(sp.S[hi_sel]))[0] if hi_sel.any() else np.zeros(sp.S.shape[1])
    cen = librosa.feature.spectral_centroid(S=np.sqrt(sp.S), freq=sp.f)[0]
    top = {k: float(np.percentile(v, 99.5)) for k, v in B.items()}
    nfr = sp.S.shape[1]
    fps = sr / HOP
    hits: list[dict] = []

    def pre(env, i, w=6):
        return float(np.median(env[max(0, i - w - 3):max(1, i - 2)]))

    # low band: kick / tom
    pl = _peaks(sp.flux(30, 150), delta=0.08, wait=10)
    for k, i in enumerate(pl):
        stop = pl[k + 1] if k + 1 < len(pl) else nfr - 1
        dms, pk = _decay_ms(B["low"], i, stop, sr, HOP)
        lvl = B["low"][pk]
        jump = lvl - pre(B["low"], i)
        if lvl < top["low"] - 40 or jump < 4:
            continue
        fsel = (sp.f >= 35) & (sp.f <= 300)
        f0 = float(sp.f[fsel][np.argmax(sp.S[fsel, pk:pk + 12].mean(1))])
        if 80 <= f0 <= 300 and dms >= 150 and B["body"][pk] < lvl + 3:
            cls, conf, margin = "tom", ("medium" if dms >= 200 else "low"), (dms - 150) / 100
        else:
            cls = "kick"
            conf = "high" if (dms < 150 and jump >= 8) else ("medium" if jump >= 6 else "low")
            margin = (jump - 6) / 6
        hits.append(dict(sec=float(sp.t[pk]), band="low", cls=cls, conf=conf, margin=margin, band_db=float(lvl),
                         jump_db=float(jump), decay_ms=round(dms), f0_hz=round(f0), centroid_hz=round(float(cen[pk]))))
    # body + noise: snare / side stick
    pm = _peaks(sp.flux(150, 400) + sp.flux(1500, 5000), delta=0.08, wait=10)
    for k, i in enumerate(pm):
        stop = pm[k + 1] if k + 1 < len(pm) else nfr - 1
        dn, pk = _decay_ms(B["noise"], i, stop, sr, HOP)
        jb = B["body"][pk] - pre(B["body"], i)
        jn = B["noise"][pk] - pre(B["noise"], i)
        if B["noise"][pk] < top["noise"] - 40 or max(jb, jn) < 4:
            continue
        bn = B["body"][pk] - B["noise"][pk]
        if jn >= 5 and jb >= 3 and dn >= 50:
            cls, conf, margin = "snare", ("high" if (jn >= 8 and jb >= 5) else "medium"), (min(jn - 8, jb - 5)) / 6
        elif jn >= 4 and dn < 50 and (jb < 3 or bn < -6):
            cls, conf, margin = "side_stick", ("medium" if jn >= 7 else "low"), (jn - 7) / 6
        elif jn >= 4:
            cls, conf, margin = "snare", "low", (jn - 5) / 6
        else:
            continue
        hits.append(dict(sec=float(sp.t[pk]), band="mid", cls=cls, conf=conf, margin=margin,
                         band_db=float(B["noise"][pk]), body_db=float(B["body"][pk]), jump_db=float(max(jb, jn)),
                         jump_body=float(jb), jump_noise=float(jn), decay_ms=round(dn),
                         centroid_hz=round(float(cen[pk]))))
    # high band: crash / ride / hats
    phh = _peaks(sp.flux(6000, 16000), delta=0.05, wait=6)
    for k, i in enumerate(phh):
        stop = phh[k + 1] if k + 1 < len(phh) else nfr - 1
        dh, pk = _decay_ms(B["high"], i, min(stop, i + int(2.5 * fps)), sr, HOP)
        dc, _ = _decay_ms(B["cym"], i, i + int(3.0 * fps), sr, HOP)
        jh = B["high"][pk] - pre(B["high"], i)
        jc = B["cym"][pk] - pre(B["cym"], i)
        if B["high"][pk] < top["high"] - 40 or jh < 3:
            continue
        if dc >= 450 and jc >= 9:
            cls, conf, margin = "crash", ("high" if (dc >= 800 and jc >= 12) else "medium"), (jc - 12) / 6
        elif dc >= 450 and jc >= 4 and flat[pk] < 0.2:
            cls, conf, margin = "ride", "low", 0.0
        elif dh < 120:
            cls, conf, margin = "closed_hat", ("high" if jh >= 6 else "medium"), (jh - 6) / 6
        elif dh < 450:
            cls, conf, margin = "open_hat", ("medium" if jh >= 6 else "low"), (jh - 6) / 6
        elif jh >= 9:
            # (not in the prototype): 6-16k rings >= 450 ms after a big jump but the 3-16k band fell fast
            # (a crash on top of a snare, whose 3-6k noise decays first) -> crash
            cls, conf, margin = "crash", "medium", (jh - 12) / 6
        else:
            cls, conf, margin = "unknown", "low", -1.0
        hits.append(dict(sec=float(sp.t[pk]), band="high", cls=cls, conf=conf, margin=margin,
                         band_db=float(B["high"][pk]), jump_db=float(jh), jump_cym=float(jc), decay_ms=round(dh),
                         cym_decay_ms=round(dc), flatness=round(float(flat[pk]), 3),
                         centroid_hz=round(float(cen[pk]))))
    for h in hits:
        h["tick_f"] = g.sec2tick(h["sec"])
        h["measure"] = g.measure_of(h["tick_f"])
    # automatic sections for the ghost rule when none are given: blocks of 8 measures
    rule_secs = sections or [{"name": f"m{a}-{a + 7}", "from_measure": a, "to_measure": a + 7}
                             for a in range(1, max([h["measure"] for h in hits] + [1]) + 8, 8)]
    for h in hits:
        h["section"] = section_of(rule_secs, h["measure"])

    # cross-band clean-up (as in the prototype)
    def near(arr, s, w=0.025):
        return len(arr) and float(np.min(np.abs(arr - s))) <= w

    kicks = np.array(sorted(h["sec"] for h in hits if h["cls"] == "kick" and h["f0_hz"] < 120))
    msec = np.array(sorted(h["sec"] for h in hits if h["band"] == "mid" and h["jump_body"] >= 6))
    for h in hits:
        if h["cls"] == "kick" and h["f0_hz"] >= 120 and near(msec, h["sec"]):
            h["drop"] = "snare_body_in_low_band"
    for s in rule_secs:
        sn = [h["band_db"] for h in hits if h["cls"] == "snare" and h["section"] == s["name"] and not near(kicks, h["sec"])]
        if not sn:
            sn = [h["band_db"] for h in hits if h["cls"] == "snare" and h["section"] == s["name"]]
        if len(sn) >= 2:
            ref = float(np.percentile(sn, 90))
            for h in hits:
                if h["section"] != s["name"] or h.get("drop"):
                    continue
                if h["band"] == "mid" and near(kicks, h["sec"]) and h["band_db"] < ref - 6:
                    h["drop"] = "kick_click_in_mid_band"
                elif h["cls"] == "snare" and h["band_db"] < ref - 10:
                    h["cls"] = "ghost"
                    if h["conf"] == "high":
                        h["conf"] = "medium"
    for h in hits:
        if h.get("drop"):
            continue
        if (h["band"] == "mid" and near(kicks, h["sec"]) and h["cls"] in ("snare", "ghost", "side_stick")
                and h["band_db"] < top["noise"] - 15 and h["jump_noise"] < h["jump_body"] - 8):
            h["drop"] = "kick_click_in_mid_band"
        if h["band"] == "high" and near(kicks, h["sec"]) and h["cls"] == "closed_hat" and h["band_db"] < top["high"] - 18:
            h["drop"] = "kick_click_in_high_band"
    snr = np.array(sorted(h["sec"] for h in hits if h["cls"] == "snare" and not h.get("drop")))
    for h in hits:
        if h["band"] == "high" and h["cls"] == "open_hat" and not h.get("drop") and near(snr, h["sec"]):
            h["drop"] = "snare_wires_in_high_band"
    dropped = Counter(h["drop"] for h in hits if h.get("drop"))
    hits = [h for h in hits if not h.get("drop")]
    for h in hits:
        if h["cls"] == "tom":
            h["cls"] = "low_tom" if h["f0_hz"] < 110 else ("mid_tom" if h["f0_hz"] < 170 else "high_tom")
    hits = [h for h in hits if h["tick_f"] >= g.tick0 - 12]
    hits.sort(key=lambda h: h["sec"])
    # quantise
    for h in hits:
        if grid_kind == 32:
            q, gname = quant(h["tick_f"], 12), "32nd"
        else:
            q, gname = quant(h["tick_f"], 24), "16th"
            if abs(h["tick_f"] - q) > 7:
                q, gname = quant(h["tick_f"], 12), "32nd"
        h["tick"], h["grid"] = max(g.tick0, q), gname
    seen = {}
    for h in hits:
        key = (h["cls"], h["tick"])
        if key in seen:
            q = quant(h["tick_f"], 12)
            if (h["cls"], q) not in seen and q >= g.tick0:
                h["tick"], h["grid"] = q, "32nd"
            else:
                h["dup"] = True
        seen[(h["cls"], h["tick"])] = h
    n_dup = sum(1 for h in hits if h.get("dup"))
    hits = [h for h in hits if not h.get("dup")]
    # levels
    lv, stem_top = _k_levels(y, sr, [h["sec"] for h in hits])
    secs = np.array([h["sec"] for h in hits])
    groups = [GROUP[h["cls"]] for h in hits]
    for i, h in enumerate(hits):
        close = np.where(np.abs(secs - h["sec"]) <= 0.030)[0]
        h["isolated"] = all(groups[j] == groups[i] for j in close)
        h["full_db"] = lv[i] - stem_top
    off_by_cls: dict[str, list[float]] = defaultdict(list)
    off_by_band: dict[str, list[float]] = defaultdict(list)
    btop = {"low": top["low"], "mid": top["noise"], "high": top["high"]}
    for h in hits:
        h["band_rel"] = h["band_db"] - btop[h["band"]]
        if h["isolated"]:
            off_by_cls[h["cls"]].append(h["full_db"] - h["band_rel"])
            off_by_band[h["band"]].append(h["full_db"] - h["band_rel"])
    for h in hits:
        if h["isolated"]:
            h["level_db"], h["level_source"] = h["full_db"], "full"
        else:
            o = off_by_cls.get(h["cls"]) or off_by_band.get(h["band"])
            if o:
                h["level_db"], h["level_source"] = min(h["full_db"], h["band_rel"] + float(np.median(o))), "band_est"
            else:
                h["level_db"], h["level_source"] = h["full_db"], "full_overlapped"
    iso_k = [h["level_db"] for h in hits if h["cls"] == "kick" and h["isolated"]]
    any_k = [h["level_db"] for h in hits if h["cls"] == "kick"]
    kick_ref = float(np.percentile(iso_k or any_k, 90)) if (iso_k or any_k) else 0.0
    kick_ref_src = "isolated_kicks_p90" if iso_k else ("kicks_p90" if any_k else "stem_top(no kicks)")
    keys = key_by_id()
    clsref = defaultdict(list)
    for h in hits:
        clsref[(h["section"], h["cls"])].append(h["band_db"])
    out_hits = []
    for h in hits:
        p, m, b, tib = g.pos(h["tick"])
        conf = max(0.05, min(1.0, CONF_VALUE[h["conf"]] + 0.1 * max(-1.0, min(1.0, h["margin"]))))
        rel_kick = h["level_db"] - kick_ref
        kid = CLASS_KEY_ID.get(h["cls"])
        key = keys.get(kid) if kid else None
        vel, capped = velocity_for(key, rel_kick) if key else (None, False)
        o = {"pos": p, "tick": h["tick"], "measure": m, "beat": b, "tick_in_beat": tib,
             "section": section_of(sections, m) if sections else None,
             "class": h["cls"], "confidence": r(conf, 2), "confidence_label": h["conf"],
             "sec": r(h["sec"], 3), "grid": h["grid"], "grid_error_ticks": r(h["tick_f"] - h["tick"], 1),
             "level_db": r(h["level_db"], 1), "level_source": h["level_source"], "rel_kick_db": r(rel_kick, 1),
             "rel_db_class": r(h["band_db"] - float(np.percentile(clsref[(h["section"], h["cls"])], 90)), 1),
             "band": h["band"], "jump_db": r(h["jump_db"], 1), "decay_ms": h["decay_ms"],
             "centroid_hz": h["centroid_hz"],
             "key": key["key"] if key else None, "key_name": key["name_en"] if key else None,
             "velocity": vel, "velocity_capped": capped}
        if "f0_hz" in h:
            o["f0_hz"] = h["f0_hz"]
        if "flatness" in h:
            o["flatness"] = h["flatness"]
        out_hits.append(o)
    total_before_conf = len(out_hits)
    out_hits = [h for h in out_hits if h["confidence"] >= min_conf]
    # placements (score_put_notes notes for a drum-27 track, melody part)
    pd_ = {}
    for h in out_hits:
        if h["key"] is None:
            continue
        if h["rel_db_class"] < -14 or (h["confidence_label"] == "low" and h["rel_db_class"] < -8):
            continue
        k = (h["tick"], h["key"])
        if k not in pd_ or pd_[k]["vel"] < h["velocity"]:
            pd_[k] = {"tick": h["tick"], "len": 24, "pitch": h["key"], "vel": h["velocity"], "pos": h["pos"],
                      "class": h["class"], "confidence": h["confidence"]}
    layered = 0
    if low_tom_layer:
        lt = keys["low_tom"]["key"]
        for h in out_hits:
            if h["class"] == "kick" and h["tick_in_beat"] == 0 and h["beat"] in (1, 3) and (h["tick"], lt) not in pd_:
                pd_[(h["tick"], lt)] = {"tick": h["tick"], "len": 24, "pitch": lt, "vel": 9, "pos": h["pos"],
                                        "class": "low_tom_layer", "confidence": h["confidence"]}
                layered += 1
    placements = sorted(pd_.values(), key=lambda x: (x["tick"], x["pitch"]))
    # shaker texture beats
    hb = B["high"]
    hi_on = np.array([h["sec"] for h in out_hits if h["band"] == "high"])
    shaker = []
    last_m = max([h["measure"] for h in out_hits] + [measure_offset])
    for m in range(measure_offset, last_m + 1):
        for bt in range(num):
            t0 = g.tick2sec((m - 1) * g.tpm + bt * g.beat_ticks)
            t1 = g.tick2sec((m - 1) * g.tpm + (bt + 1) * g.beat_ticks)
            a, b = sp.fr(t0), sp.fr(t1)
            seg = hb[a:b]
            if len(seg) < 4:
                continue
            n_on = int(((hi_on >= t0) & (hi_on < t1)).sum())
            dip = float(np.percentile(seg, 90) - np.percentile(seg, 5))
            fl = float(np.median(flat[a:b]))
            lvl = float(np.percentile(seg, 90) - top["high"])
            if n_on >= 4 and dip < 10 and fl >= 0.25 and lvl > -35:
                shaker.append({"measure": m, "beat": bt + 1, "high_onsets": n_on, "flatness": r(fl, 3)})
    counts = dict(Counter(h["class"] for h in out_hits))
    by_section = []
    for s in sections:
        hs = [h for h in out_hits if s["from_measure"] <= h["measure"] <= s["to_measure"]]
        by_section.append({**s, "counts": dict(Counter(h["class"] for h in hs)), "hits": len(hs)})
    crashes = [{"pos": h["pos"], "tick": h["tick"], "measure": h["measure"], "beat": h["beat"],
                "confidence": h["confidence"], "rel_kick_db": h["rel_kick_db"], "section": h["section"],
                "pickup": h["beat"] == num and num > 1} for h in out_hits if h["class"] == "crash"]
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "phase_sec": r(phase, 3),
                     "time_signature": f"{num}/{den}", "ticks_per_measure": g.tpm, "measure_offset": measure_offset,
                     "grid": grid_kind, "phase_search_ms": phase_ms},
            "tempo_check": tempo, "levels": {"stem_top_k_db": r(stem_top, 1), "kick_ref_db": r(kick_ref, 1),
                                              "kick_ref_source": kick_ref_src, "window_sec": LEVEL_WIN_SEC},
            "counts": counts, "sections": by_section, "crashes": crashes, "hits": out_hits,
            "placements": placements, "placements_note": "score_put_notes notes for a drum (27) track, part melody "
                                                         "(pitch = key, len 24); low-confidence quiet hits left out",
            "low_tom_layer_added": layered, "shaker_beats": shaker,
            "dropped": {**dict(dropped), "duplicate_on_grid": n_dup,
                        "below_min_confidence": total_before_conf - len(out_hits)},
            "notes": DRUM_NOTES}


# ---------------------------------------------------------------- bass

def _bass_notes_raw(y, sr, f0, vp, t, hop, fmin_band, fmax_band, min_ms=60, vthr=0.25, floor_db=45):
    import librosa
    import numpy as np

    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=hop)) ** 2
    f = librosa.fft_frequencies(sr=sr, n_fft=2048)
    sel = (f >= fmin_band) & (f <= fmax_band)
    env = _db(S[sel].sum(0))
    n = min(len(env), len(f0))
    env, f0, vp = env[:n], f0[:n], vp[:n]
    L = _db(S[sel])
    fl = np.maximum(0, np.diff(L, axis=1, prepend=L[:, :1])).mean(0)[:n]
    on = librosa.util.peak_pick(fl / (np.percentile(fl, 99.5) + 1e-9), pre_max=3, post_max=3, pre_avg=10,
                                post_avg=10, delta=0.07, wait=6)
    onset = np.zeros(n, bool)
    onset[on] = True
    midi = np.where(np.isfinite(f0) & (vp >= vthr), librosa.hz_to_midi(np.nan_to_num(f0, nan=1.0)), np.nan)
    top = np.percentile(env, 99.5)
    notes = []
    i = 0
    minf = max(2, int(min_ms / 1000 * sr / hop))
    while i < n:
        if np.isnan(midi[i]) or env[i] < top - floor_db:
            i += 1
            continue
        w = midi[i:i + minf + 1]
        if not np.any(np.isfinite(w)):
            i += 1
            continue
        p = int(round(np.nanmedian(w)))
        st = i
        for k in range(i, max(-1, i - 16), -1):
            if onset[k]:
                st = k
                break
        k = st
        while k > 0 and st - k < 25 and env[k - 1] < env[k] - 0.2:
            k -= 1
        st = k
        pk = env[st:st + 10].max()
        j = i + 1
        miss = 0
        while j < n:
            if onset[j] and j - st > minf:
                break
            if env[j] < pk - 12:
                break
            if np.isnan(midi[j]):
                miss += 1
                if miss > minf:
                    break
            else:
                miss = 0
                nxt = midi[j:j + minf]
                if abs(midi[j] - p) >= 0.8 and np.all(np.abs(nxt[np.isfinite(nxt)] - p) >= 0.8):
                    break
            j += 1
        if j - st >= minf:
            seg = midi[st:j]
            seg = seg[np.isfinite(seg)]
            if len(seg):
                pp = int(round(np.median(seg)))
                notes.append(dict(t0=float(t[st]), t1=float(t[min(j, n - 1)]), pitch=pp, peak_db=float(pk - top),
                                  stable=float(np.mean(np.abs(seg - pp) < 0.5)), voiced=float(np.mean(vp[st:j])),
                                  octave_jump=float(np.mean(np.abs(np.abs(seg - pp) - 12) < 0.6)), onset=bool(onset[st])))
        i = max(j, i + 1)
    return notes


def _octave_check(Sb, fb, tb, note) -> tuple[bool, list[str]]:
    """Spectral octave-ambiguity flags for one note (STFT power Sb with bins fb, frame times tb)."""
    import librosa
    import numpy as np

    a = int(np.searchsorted(tb, note["t0"] + 0.03))
    b = max(a + 1, int(np.searchsorted(tb, min(note["t1"], note["t0"] + 0.5))))
    col = Sb[:, a:b].mean(1)
    f0 = float(librosa.midi_to_hz(note["pitch"]))

    def e(fc):
        sel = (fb >= fc * 2 ** (-0.6 / 12)) & (fb <= fc * 2 ** (0.6 / 12))
        return float(10 * np.log10(col[sel].sum() + 1e-12)) if sel.any() else -120.0

    reasons = []
    if note.get("octave_jump", 0) >= 0.15:
        reasons.append("pyin_octave_jumps")
    e1, e2, eh = e(f0), e(2 * f0), e(f0 / 2)
    if e1 < e2 - 10:
        reasons.append("weak_fundamental")
    if f0 / 2 >= 20 and eh > e1 - 6:
        reasons.append("sub_octave_energy")
    return bool(reasons), reasons


def analyse_bass(path: Path, bpm: float, first: float, num: int, den: int, grid_kind: int, measure_offset: int,
                 min_note_ticks: int, range_low: int, range_high: int, min_conf: float) -> dict:
    import librosa
    import numpy as np

    y = _load(path, BASS_SR)
    sr = BASS_SR
    g = Grid(bpm, first, num, den, measure_offset)
    dur = y.size / sr
    if first >= dur:
        raise EarError("BAD_ARGUMENT", "first_downbeat_sec가 스템 길이보다 깁니다.",
                       {"first_downbeat_sec": first, "duration_sec": r(dur, 3)})
    fmin = max(25.0, float(librosa.midi_to_hz(range_low - 0.5)))
    fmax = min(sr / 4, float(librosa.midi_to_hz(range_high + 0.5)))
    f0, _, vp = librosa.pyin(y, fmin=fmin, fmax=fmax, sr=sr, frame_length=2048, hop_length=256)
    t = librosa.frames_to_time(np.arange(len(f0)), sr=sr, hop_length=256)
    raw = _bass_notes_raw(y, sr, f0, vp, t, 256, fmin, max(fmax, 500.0))
    Sb = np.abs(librosa.stft(y, n_fft=8192, hop_length=256)) ** 2
    fb = librosa.fft_frequencies(sr=sr, n_fft=8192)
    tb = librosa.frames_to_time(np.arange(Sb.shape[1]), sr=sr, hop_length=256)
    gt = 12 if grid_kind == 32 else 24
    placed = []
    for x in raw:
        if not range_low <= x["pitch"] <= range_high:
            continue
        tk = g.sec2tick(x["t0"])
        tk1 = g.sec2tick(x["t1"])
        if tk < g.tick0 - 12:
            continue
        q = max(g.tick0, quant(tk, gt))
        ln = max(12, int(round((tk1 - tk) / 12) * 12))
        conf_l = ("high" if (x["stable"] >= 0.8 and x["voiced"] >= 0.5 and x["peak_db"] > -25)
                  else ("medium" if x["stable"] >= 0.6 else "low"))
        amb, why = _octave_check(Sb, fb, tb, x)
        placed.append({"tick": q, "len": ln, "pitch": x["pitch"], "strength_db": x["peak_db"], "conf": conf_l,
                       "stable": x["stable"], "voiced": x["voiced"], "sec": x["t0"], "grid_error": tk - q,
                       "amb": amb, "why": why})
    # merge: dedupe same-start notes (keep the longer), then same-pitch notes that touch
    by = {}
    for x in placed:
        if x["tick"] not in by or x["len"] > by[x["tick"]]["len"]:
            by[x["tick"]] = x
    res: list[dict] = []
    for x in sorted(by.values(), key=lambda x: x["tick"]):
        if res and res[-1]["pitch"] == x["pitch"] and x["tick"] - (res[-1]["tick"] + res[-1]["len"]) <= 12:
            rr = res[-1]
            rr["len"] = max(rr["len"], x["tick"] + x["len"] - rr["tick"])
            rr["reattacks"] = rr.get("reattacks", 0) + 1
            rr["strength_db"] = max(rr["strength_db"], x["strength_db"])
            if x["conf"] == "high":
                rr["conf"] = "high"
            rr["amb"] = rr["amb"] or x["amb"]
            rr["why"] = sorted(set(rr["why"]) | set(x["why"]))
        elif res and x["tick"] < res[-1]["tick"] + res[-1]["len"]:
            res[-1]["len"] = max(12, x["tick"] - res[-1]["tick"])
            res.append(x)
        else:
            res.append(x)
    short = sum(1 for x in res if x["len"] < min_note_ticks)
    res = [x for x in res if x["len"] >= min_note_ticks]
    notes = []
    for x in res:
        p, m, b, tib = g.pos(x["tick"])
        conf = CONF_VALUE[x["conf"]] + 0.1 * max(-1.0, min(1.0, (x["stable"] - 0.8) * 5))
        notes.append({"pos": p, "tick": x["tick"], "measure": m, "beat": b, "tick_in_beat": tib, "len": x["len"],
                      "pitch": x["pitch"], "name": io.PITCH_NAMES[x["pitch"] % 12] + str(x["pitch"] // 12 - 1),
                      "confidence": r(max(0.05, min(1.0, conf)), 2), "confidence_label": x["conf"],
                      "octave_ambiguous": x["amb"], "octave_reasons": x["why"],
                      "strength_db": r(x["strength_db"], 1), "stable": r(x["stable"], 2), "voiced": r(x["voiced"], 2),
                      "reattacks": x.get("reattacks", 0), "sec": r(x["sec"], 3),
                      "grid_error_ticks": r(x["grid_error"], 1)})
    n_before = len(notes)
    notes = [n for n in notes if n["confidence"] >= min_conf]
    # per-measure root guesses: pitch classes weighted by duration in the measure x amplitude, x2 for a note on the
    # downbeat, x1.5 for the lowest pitch of the measure
    roots = []
    by_m = defaultdict(list)
    for n in notes:
        by_m[n["measure"]].append(n)
    for m in sorted(by_m):
        w = Counter()
        low = min(by_m[m], key=lambda n: n["pitch"])
        for n in by_m[m]:
            end_in = min(n["tick"] + n["len"], m * g.tpm) - n["tick"]
            f = (2.0 if n["tick"] % g.tpm == 0 else 1.0) * (1.5 if n["pitch"] == low["pitch"] else 1.0)
            w[n["pitch"] % 12] += max(12, end_in) * 10 ** (n["strength_db"] / 20) * f
        tot = sum(w.values())
        pc, top_w = w.most_common(1)[0]
        roots.append({"measure": m, "root": io.PITCH_NAMES[pc], "root_pc": pc, "share": r(top_w / tot, 2),
                      "lowest": low["name"], "lowest_pitch": low["pitch"], "notes": len(by_m[m]),
                      "pitch_classes": {io.PITCH_NAMES[k]: r(v / tot, 2) for k, v in w.most_common(4)}})
    placements = [{"tick": n["tick"], "len": n["len"], "pitch": n["pitch"],
                   "vel": int(min(15, max(6, round(13 * 10 ** (min(0.0, n["strength_db"] + 6) / 40))))),
                   "pos": n["pos"], "confidence": n["confidence"]} for n in notes]
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                     "ticks_per_measure": g.tpm, "measure_offset": measure_offset, "grid": grid_kind},
            "pyin": {"fmin_hz": r(fmin, 2), "fmax_hz": r(fmax, 2), "sr": sr, "frame": 2048, "hop": 256,
                     "range_low": range_low, "range_high": range_high},
            "notes": notes, "measure_roots": roots, "placements": placements,
            "placements_note": "score_put_notes notes with pitch_mode \"sounding\" (bass 29 or another bass track); "
                               "vel is a rough guess from the stem level",
            "counts": {"notes": len(notes), "octave_ambiguous": sum(1 for n in notes if n["octave_ambiguous"]),
                       "dropped_short": short, "dropped_low_confidence": n_before - len(notes)},
            "limits": BASS_NOTES}
