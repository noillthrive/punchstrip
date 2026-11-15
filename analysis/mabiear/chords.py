"""Harmonic stems -> beat and measure chords on the MabiIcco grid (``stem_chords``).

Rules only (chroma templates), librosa/numpy/scipy. Timeline as in ``stems.py``.

Signal: the given stems are summed (``paths``; for a mix give the mix and put the vocal/drum stems in
``subtract_paths``: same length and timeline, they are subtracted sample by sample - UVR stems of one separation run
add up to the mix). Mono 22.05 kHz. Chroma = librosa ``chroma_cqt`` (hop 512, 36 bins/octave, from C1) of the
harmonic part (HPSS margin 2), averaged per beat between the grid's beat times (beat = 1/den note), each beat
normalised to max 1. Bass chroma per beat: the whitened harmonic salience of ``melody.py`` on a CQT of the
``bass_stem_path`` (MIDI 24-60, 3 bins per semitone), only the strongest candidate per frame, folded to 12 pitch
classes; without a bass stem, the same on the harmonic sum for MIDI 28-52 (weak: the root then mostly comes from the
chroma - give the bass stem as ``bass_stem_path``, not in ``paths``).

Templates (``qualities``, default all): maj (0 4 7), min (0 3 7), dim (0 3 6), aug (0 4 8), sus2 (0 2 7), sus4 (0 5 7),
7 (0 4 7 10), maj7 (0 4 7 11), m7 (0 3 7 10). Weights root 1, third/fifth 0.8, others 0.6; score = cosine with the
beat chroma minus a quality price (7ths 0.03, sus 0.04, dim/aug 0.05: a 4-note or suspended template fits any extra
melody tone). Key prior: Krumhansl-Kessler on the whole song's chroma (or the given ``key``); a chord whose tones are
all in the key's scale (minor: natural + raised 7th) gets +0.04, on the tonic/dominant/subdominant +0.02 more. Bass
prior: + 0.4 x bass chroma at the root (0..1). A beat 30 dB under the 95th-percentile beat level is "N" (no chord).

Beat sequence: Viterbi over the template scores with a change cost (0.08); measure chord = the chord with the highest
summed score over the measure's beats (the downbeat counts 1.5x). Confidence 0..1 = (best - second best with a
different root) / 0.15, clipped. ``bass_agree``: the bass stem's strongest pitch class in that measure / beat equals
the root (``inversion`` names the bass pitch class when it is another chord tone).

Voicing suggestion (``voicing_size`` 3|4 notes in ``voicing_range``): close position; the first measure is centred in
the range, later ones move the least from the previous voicing (sum of semitone moves, ties to the lower voicing).
The 4-note voicing doubles the root an octave up when the chord has 3 tones.
"""
from __future__ import annotations

import logging
from pathlib import Path

from . import io
from .io import EarError, r
from .stems import Grid

log = logging.getLogger("mabiear.chords")

SCHEMA_CHORDS = "mabiear.stem_chords/1"
SR = 22050
HOP = 512
QUALITIES = {"maj": (0, 4, 7), "min": (0, 3, 7), "dim": (0, 3, 6), "aug": (0, 4, 8), "sus2": (0, 2, 7),
             "sus4": (0, 5, 7), "7": (0, 4, 7, 10), "maj7": (0, 4, 7, 11), "m7": (0, 3, 7, 10)}
SUFFIX = {"maj": "", "min": "m", "dim": "dim", "aug": "aug", "sus2": "sus2", "sus4": "sus4", "7": "7", "maj7": "maj7",
          "m7": "m7"}
KK_MAJ = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
KK_MIN = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
SCALE = {"major": (0, 2, 4, 5, 7, 9, 11), "minor": (0, 2, 3, 5, 7, 8, 10, 11)}
CHANGE_COST = 0.08
BASS_W = 0.4
KEY_IN = 0.04
KEY_FUNC = 0.02
# simpler chords first: a 4-note or suspended template fits any extra melody tone, so it pays a small price
QUAL_PEN = {"maj": 0.0, "min": 0.0, "7": 0.03, "m7": 0.03, "maj7": 0.03, "sus2": 0.04, "sus4": 0.04, "dim": 0.05,
            "aug": 0.05}

CHORD_NOTES = [
    "Chroma templates hear pitch classes, not voicings: a melody note held over a chord is part of the chord "
    "(e.g. C with a sustained D can read as sus2 or Dm7-ish); passing chords shorter than a beat are smoothed away.",
    "Without a bass stem the root comes from the lowest octave of the harmonic stems, which is weak when the bass "
    "plays in another stem; slash chords (inversions) are only reported with a bass stem.",
    "Relative major/minor (C vs Am) and 6th chords (Am7 = C6) share most tones: the key prior and the bass decide; "
    "check measures with low confidence by ear.",
]


def parse_key(key):
    """'auto' / None -> None; 'F minor', 'F#m', 'Bb major', 'C' -> (tonic pc, 'major'|'minor')."""
    if key in (None, "auto"):
        return None
    if not isinstance(key, str) or not key.strip():
        raise EarError("BAD_ARGUMENT", "'key'는 'auto' 또는 \"F minor\"·\"C major\"·\"F#m\" 같은 문자열입니다.", {"key": key})
    k = key.strip().replace("♯", "#").replace("♭", "b")
    mode = "major"
    low = k.lower()
    for suf, md in ((" minor", "minor"), (" major", "major"), ("min", "minor"), ("maj", "major"), ("m", "minor")):
        if low.endswith(suf):
            mode = md
            k = k[: len(k) - len(suf)].strip()
            break
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    if not k or k[0].upper() not in names:
        raise EarError("BAD_ARGUMENT", f"조 이름을 읽을 수 없습니다: {key}", {"key": key})
    pc = names[k[0].upper()]
    for ch in k[1:]:
        if ch == "#":
            pc += 1
        elif ch == "b":
            pc -= 1
        else:
            raise EarError("BAD_ARGUMENT", f"조 이름을 읽을 수 없습니다: {key}", {"key": key})
    return pc % 12, mode


def key_name(k) -> str:
    return f"{io.PITCH_NAMES[k[0]]} {k[1]}"


def estimate_key(profile):
    import numpy as np

    best = []
    for mode, prof in (("major", KK_MAJ), ("minor", KK_MIN)):
        for t in range(12):
            c = float(np.corrcoef(profile, np.roll(prof, t))[0, 1])
            best.append((c, t, mode))
    best.sort(reverse=True)
    return (best[0][1], best[0][2]), best[0][0], (best[1][1], best[1][2]), best[1][0]


def chord_label(root: int, q: str) -> str:
    return io.PITCH_NAMES[root] + SUFFIX[q]


def templates(quals):
    import numpy as np

    T, lab = [], []
    for q in quals:
        iv = QUALITIES[q]
        for root in range(12):
            v = np.zeros(12)
            for i, x in enumerate(iv):
                v[(root + x) % 12] = 1.0 if i == 0 else (0.8 if x in (3, 4, 6, 7, 8) and i in (1, 2) else 0.6)
            T.append(v / np.linalg.norm(v))
            lab.append((root, q))
    return np.array(T), lab


def key_bonus(lab, key):
    import numpy as np

    out = np.zeros(len(lab))
    if key is None:
        return out
    tonic, mode = key
    sc = {(tonic + x) % 12 for x in SCALE[mode]}
    func = {tonic, (tonic + 7) % 12, (tonic + 5) % 12}
    for i, (root, q) in enumerate(lab):
        tones = {(root + x) % 12 for x in QUALITIES[q]}
        if tones <= sc:
            out[i] += KEY_IN
            if root in func:
                out[i] += KEY_FUNC
    return out


def viterbi_chords(S, change_cost=CHANGE_COST):
    """S: beats x chords scores (higher better). Returns best chord index per beat."""
    import numpy as np

    nb, nc = S.shape
    if nb == 0:
        return np.zeros(0, int)
    D = S[0].copy()
    back = np.zeros((nb, nc), int)
    for i in range(1, nb):
        best_prev = int(np.argmax(D))
        stay = D
        move = D[best_prev] - change_cost
        take_move = move > stay
        back[i] = np.where(take_move, best_prev, np.arange(nc))
        D = np.where(take_move, move, stay) + S[i]
    path = np.zeros(nb, int)
    path[-1] = int(np.argmax(D))
    for i in range(nb - 1, 0, -1):
        path[i - 1] = back[i, path[i]]
    return path


def voicing(root, q, lo, hi, size, prev=None):
    """Close-position voicing of the chord in [lo, hi] (MIDI), least movement from prev (list) when given."""
    pcs = [(root + x) % 12 for x in QUALITIES[q]]
    if size == 3 and len(pcs) == 4:
        pcs = [pcs[0], pcs[1], pcs[3]]          # 7th chords in 3 notes: root, third, seventh (fifth dropped)
    cands = []
    for inv in range(len(pcs)):
        order = pcs[inv:] + pcs[:inv]
        for base in range(lo, hi + 1):
            if base % 12 != order[0]:
                continue
            v = [base]
            for pc in order[1:]:
                n = v[-1] + 1
                while n % 12 != pc:
                    n += 1
                v.append(n)
            if size == 4 and len(v) == 3:
                v.append(v[0] + 12)
            if v[-1] <= hi:
                cands.append(sorted(v))
    if not cands:
        return None
    if prev:
        return min(cands, key=lambda v: (sum(abs(a - b) for a, b in zip(v, prev)) if len(v) == len(prev)
                                         else abs(sum(v) / len(v) - sum(prev) / len(prev)) * len(v), v[0]))
    mid = (lo + hi) / 2
    return min(cands, key=lambda v: (abs(sum(v) / len(v) - mid), v[0]))


def _load_sum(paths, subtract):
    import numpy as np

    ys = [io.load_mono(p, sr=SR)[0] for p in paths]
    n = min(y.size for y in ys)
    y = np.sum([x[:n] for x in ys], axis=0)
    for p in subtract:
        s = io.load_mono(p, sr=SR)[0]
        m = min(n, s.size)
        y[:m] -= s[:m]
    return y.astype(np.float32)


def _beat_chroma(C, t, beats):
    import numpy as np

    out = np.zeros((len(beats) - 1, C.shape[0]))
    for k in range(len(beats) - 1):
        sel = (t >= beats[k]) & (t < beats[k + 1])
        if sel.any():
            out[k] = C[:, sel].mean(1)
    return out


def analyse_chords(paths: list[Path], subtract: list[Path], bass_path: Path | None, bpm: float, first: float, num: int,
                   den: int, measure_offset: int, key, quals: list[str], vrange: tuple[int, int], vsize: int,
                   per_beat: bool, params: dict | None = None) -> dict:
    import librosa
    import numpy as np

    pr = {"bass_w": BASS_W, "change_cost": CHANGE_COST, "qual_pen": QUAL_PEN, "bass_mode": "salience",
          **(params or {})}
    y = _load_sum(paths, subtract)
    if y.size < SR:
        raise EarError("BAD_ARGUMENT", "오디오가 너무 짧습니다(1초 미만).", {"paths": [str(p) for p in paths]})
    dur = y.size / SR
    if first >= dur:
        raise EarError("BAD_ARGUMENT", "first_downbeat_sec가 오디오 길이보다 깁니다.",
                       {"first_downbeat_sec": first, "duration_sec": r(dur, 3)})
    g = Grid(bpm, first, num, den, measure_offset)
    beat_sec = g.beat_ticks / 96 * g.quarter_sec
    n_beats = int((dur - first) // beat_sec)
    n_beats -= n_beats % num
    if n_beats < num:
        raise EarError("BAD_ARGUMENT", "한 마디도 들어가지 않습니다(first_downbeat_sec 뒤가 너무 짧음).", {})
    beats = first + beat_sec * np.arange(n_beats + 1)
    yh = librosa.effects.harmonic(y, margin=2.0)
    ch = librosa.feature.chroma_cqt(y=yh, sr=SR, hop_length=HOP, bins_per_octave=36, n_octaves=7)
    tch = librosa.frames_to_time(np.arange(ch.shape[1]), sr=SR, hop_length=HOP)
    rms = librosa.feature.rms(y=y, hop_length=HOP)[0]
    edb_f = 20 * np.log10(rms + 1e-9)
    edb = np.array([edb_f[(tch >= beats[k]) & (tch < beats[k + 1])].mean() if np.any((tch >= beats[k]) & (tch < beats[k + 1]))
                    else -120.0 for k in range(n_beats)])
    silent = edb < np.percentile(edb, 95) - 30
    B = _beat_chroma(ch, tch, beats)
    Bn = B / (B.max(1, keepdims=True) + 1e-9)
    # bass chroma
    if bass_path is not None:
        yb = io.load_mono(bass_path, sr=SR)[0]
        lo_b, nb_b = 24, 36
        bass_src = "bass_stem"
    else:
        yb = y
        lo_b, nb_b = 28, 24
        bass_src = "harmonic_low"
    if pr["bass_mode"] == "salience":
        from .melody import BPS, salience, whiten

        # CQT from lo_b over 5 octaves (harmonics of the bass range), whitened harmonic salience as in melody.py
        Cm = np.abs(librosa.cqt(yb, sr=SR, hop_length=HOP, fmin=float(librosa.midi_to_hz(lo_b)), n_bins=60 * BPS,
                                bins_per_octave=12 * BPS))
        sal = salience(whiten(Cm, 0.5))
        band = sal[:nb_b * BPS]
        tb = librosa.frames_to_time(np.arange(band.shape[1]), sr=SR, hop_length=HOP)
        # keep the strongest bass candidate per frame (a bass line is one note at a time)
        top = np.zeros_like(band)
        idx = np.argmax(band, 0)
        top[idx, np.arange(band.shape[1])] = band.max(0)
        bc = np.zeros((12, band.shape[1]))
        for i in range(band.shape[0]):
            bc[int(round(lo_b + i / BPS)) % 12] += top[i]
    else:
        Cb = np.abs(librosa.cqt(yb, sr=SR, hop_length=HOP, fmin=float(librosa.midi_to_hz(lo_b)), n_bins=nb_b))
        tb = librosa.frames_to_time(np.arange(Cb.shape[1]), sr=SR, hop_length=HOP)
        bc = np.zeros((12, Cb.shape[1]))
        for i in range(nb_b):
            bc[(lo_b + i) % 12] += Cb[i]
    BB = _beat_chroma(bc, tb, beats)
    BBn = BB / (BB.max(1, keepdims=True) + 1e-9)
    # key
    prof = B[~silent].sum(0) if np.any(~silent) else B.sum(0)
    k_est, k_r, k2, k2_r = estimate_key(prof)
    kk = key if key is not None else k_est
    T, lab = templates(quals)
    Bu = Bn / (np.linalg.norm(Bn, axis=1, keepdims=True) + 1e-9)
    qpen = np.array([pr["qual_pen"].get(q, 0.0) for _, q in lab])
    S = Bu @ T.T + key_bonus(lab, kk)[None, :] + pr["bass_w"] * BBn[:, [rt for rt, _ in lab]] - qpen[None, :]
    S[silent] = -1.0
    path = viterbi_chords(S, pr["change_cost"])
    roots = np.array([rt for rt, _ in lab])

    def conf_of(scores, idx):
        other = scores[roots != roots[idx]]
        return float(np.clip((scores[idx] - other.max()) / 0.15, 0, 1)) if other.size else 1.0

    def bass_info(bvec, root, q):
        if bass_path is None or bvec.max() <= 0:
            return None, None
        bp = int(np.argmax(bvec))
        tones = {(root + x) % 12 for x in QUALITIES[q]}
        inv = io.PITCH_NAMES[bp] if (bp != root and bp in tones) else None
        return bp == root, inv

    beats_out = []
    for i in range(n_beats):
        tk = g.tick0 + i * g.beat_ticks
        pos, m, b, _ = g.pos(tk)
        if silent[i]:
            beats_out.append({"pos": pos, "measure": m, "beat": b, "chord": "N", "root": None, "quality": None,
                              "confidence": 0.0})
            continue
        j = int(path[i])
        rt, q = lab[j]
        ag, inv = bass_info(BBn[i], rt, q)
        beats_out.append({"pos": pos, "measure": m, "beat": b, "chord": chord_label(rt, q) + (f"/{inv}" if inv else ""),
                          "root": io.PITCH_NAMES[rt], "quality": q, "confidence": r(conf_of(S[i], j), 2),
                          "bass_agree": ag, "bass": io.PITCH_NAMES[int(np.argmax(BBn[i]))] if BB[i].max() > 0 else None})
    measures = []
    prev_v = None
    n_meas = n_beats // num
    for mi in range(n_meas):
        sl = slice(mi * num, (mi + 1) * num)
        w = np.ones(num)
        w[0] = 1.5
        ok = ~silent[sl]
        m_no = measure_offset + mi
        if not ok.any():
            measures.append({"measure": m_no, "chord": "N", "root": None, "quality": None, "confidence": 0.0,
                             "beats": ["N"] * num, "voicing": None})
            continue
        tot = (S[sl][ok] * w[ok][:, None]).sum(0)
        j = int(np.argmax(tot))
        rt, q = lab[j]
        share = float(np.mean([path[mi * num + k] == j for k in range(num) if ok[k]]))
        bvec = (BBn[sl][ok] * w[ok][:, None]).sum(0)
        ag, inv = bass_info(bvec, rt, q)
        v = voicing(rt, q, vrange[0], vrange[1], vsize, prev_v)
        prev_v = v or prev_v
        seq = [beats_out[mi * num + k]["chord"] for k in range(num)]
        alt = [int(x) for x in np.argsort(-tot)[:4] if int(x) != j][:2]
        measures.append({"measure": m_no, "chord": chord_label(rt, q) + (f"/{inv}" if inv else ""),
                         "root": io.PITCH_NAMES[rt], "root_pc": rt, "quality": q,
                         "confidence": r(conf_of(tot / w[ok].sum(), j), 2), "beat_share": r(share, 2),
                         "bass_agree": ag, "inversion": inv,
                         "bass": io.PITCH_NAMES[int(np.argmax(bvec))] if bvec.max() > 0 else None,
                         "bass_pc": int(np.argmax(bvec)) if bvec.max() > 0 else None, "beats": seq,
                         "alternatives": [chord_label(*lab[a]) for a in alt],
                         "voicing": v, "voicing_names": [io.PITCH_NAMES[x % 12] + str(x // 12 - 1) for x in v] if v else None})
    agree = [m["bass_agree"] for m in measures if m.get("bass_agree") is not None]
    qc = {}
    for m in measures:
        if m["quality"]:
            qc[m["quality"]] = qc.get(m["quality"], 0) + 1
    tpm = g.tpm
    placements = []
    for m in measures:
        if m.get("voicing"):
            t0 = (m["measure"] - 1) * tpm
            for p in m["voicing"]:
                placements.append({"tick": t0, "len": tpm, "pitch": p, "vel": 8, "pos": f"{m['measure']}:1:0"})
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                     "ticks_per_measure": tpm, "measure_offset": measure_offset, "beats": n_beats,
                     "measures": n_meas},
            "key": {"used": key_name(kk), "given": key is not None, "estimated": key_name(k_est),
                    "estimated_r": r(k_r, 3), "second": key_name(k2), "second_r": r(k2_r, 3)},
            "method": {"chroma": "chroma_cqt of the HPSS harmonic part, beat means", "bass": bass_src,
                       "qualities": quals, "change_cost": CHANGE_COST, "bass_weight": BASS_W,
                       "key_bonus": [KEY_IN, KEY_FUNC], "voicing_range": list(vrange), "voicing_size": vsize},
            "measures": measures, "beats": beats_out if per_beat else None,
            "placements": placements,
            "placements_note": "score_put_notes notes (pitch_mode \"sounding\"): one whole-measure chord per measure "
                               "from the voicing suggestion - a sketch for chord parts, not a rhythm",
            "counts": {"measures": n_meas, "no_chord_measures": sum(1 for m in measures if m["chord"] == "N"),
                       "low_confidence_measures": sum(1 for m in measures if m["chord"] != "N" and m["confidence"] < 0.3),
                       "qualities": qc,
                       "bass_agree_pct": r(100 * sum(agree) / len(agree), 1) if agree else None},
            "limits": CHORD_NOTES}
