"""Vocal / lead stem -> monophonic melody notes on the MabiIcco grid (``stem_melody_notes``).

Rules only, librosa/numpy/scipy. Timeline as in ``stems.py``: tool measure ``measure_offset`` starts at
``first_downbeat_sec``, quarter = 96 ticks, one tempo and one time signature for the whole stem.

Frame track (``method``):

- ``salience``: CQT of the mono stem (22.05 kHz, hop 256 = 11.6 ms, MIDI 36-108, 3 bins per semitone), log-compressed
  and whitened (minus its median over half an octave of bins, floored at 0; without it broadband consonants and
  cymbal bleed make the top of the range win). Harmonic salience ``sal(b) = sum_h 0.84^(h-1) * W(b + 36 log2 h)``,
  h = 1..8, times ``0.55 + 0.45 * min(1, W(b) / W(b + octave))`` (a candidate without energy of its own under a strong
  octave is probably a sub-harmonic; a missing fundamental with strong odd partials still wins, which is right for
  high-passed vocals). A Viterbi pass over the bins in ``[range_low, range_high]`` plus one unvoiced state keeps the
  dominant line: steps of up to 2/3 semitone per frame are free, any leap costs e^-6, an octave leap (11-13 semitones)
  another e^-4, so a doubled or stacked vocal does not make the line hop between octaves. Voicing per frame = logistic
  of the stem level (re its 95th percentile, midpoint -28 dB) x logistic of the salience peakiness (peak / median of
  the band, midpoint 2.2).
- ``pyin``: librosa pyin (frame 2048, hop 256) between the range limits; its voiced flag is the voicing (the bass method; weak on stacked vocals - the survey measured a median voiced probability of 0.03 on loud frames).
- ``auto`` (default): the salience track plus pyin as a second opinion: per note the share of confident pyin frames
  (voiced probability >= 0.5) at the same pitch (``pyin_agree``, +-0.05 confidence) and the flag
  ``pyin_other_octave`` when >= 60 % of them sit an octave away. pyin never moves a note: on the evaluation stems its
  octave was wrong more often than the salience octave.

Notes: tuning offset = circular mean of the fractional frame pitch. Voiced runs are cut where the (5-frame median)
pitch leaves the current note by >= 0.6 semitone for >= 6 frames (70 ms; vibrato does not cut) and at onsets inside a steady pitch (spectral-flux peaks or a re-attack after a level dip of >= 6 dB: repeated
syllables). Pitch = rounded median. Register pass: a note more than 9 semitones
from the duration-weighted median of the confident notes whose salience one octave toward that median is >= 0.5 of its
own is moved by that octave (``source`` "+register", flagged). Quantised to the grid (16th = 24 ticks, 32nd = 12),
same-start notes keep the more confident one, overlaps are trimmed (one line), same-pitch notes separated by at most
``merge_gap_ticks`` (one grid step), without an onset at the second one and without a level dip of >= 6 dB between
them (a re-sung syllable) are merged, notes shorter than
``min_note_ticks`` (one grid step) are dropped.

``octave_ambiguous``: the salience one octave up or down is >= 0.8 of the chosen pitch's (``other_octave_strong``),
pyin disagrees by an octave (``pyin_other_octave``) or the register pass moved the note (``register_moved``).
Confidence 0..1 = 0.25 + 0.35 voicing + 0.2 stability (share of frames within half a semitone) + 0.2 salience margin
over the best non-octave pitch, -0.1 when octave-ambiguous.
"""
from __future__ import annotations

import logging
import math
from collections import Counter
from pathlib import Path

from . import io
from .io import EarError, r
from .stems import Grid, quant

log = logging.getLogger("mabiear.melody")

SCHEMA_MELODY = "mabiear.stem_melody/1"
SR = 22050
HOP = 256
BPS = 3                     # CQT bins per semitone
CQT_LO = 36                 # MIDI of CQT bin 1 (bin 0 is a padding bin 1/3 semitone lower)
CQT_SEMIS = 72              # C2 .. C8
N_HARM = 8
HARM_W = 0.84
METHODS = ("salience", "pyin", "auto")

MELODY_NOTES = [
    "One line only: the dominant (loudest-harmonic) voice. Harmony vocals, doubled octaves and chops are folded into "
    "that line or dropped; the octave of a stacked vocal can be wrong for a whole phrase (octave_ambiguous marks the "
    "notes where the other octave is nearly as strong).",
    "Bleed from other instruments in a separated stem (guitar, synth leads) can be tracked as melody where the voice "
    "rests; check notes with low confidence.",
    "Fast melismas (runs shorter than about 70 ms per note) are merged into their neighbours; portamento lands on the "
    "pitch held longest.",
    "Positions come from the given bpm / first_downbeat_sec (no phase search); a constant offset of the vocal (sung "
    "slightly early or late) shows as grid_error_ticks of one sign.",
]


# ---------------------------------------------------------------- frame pitch track

def _cqt(y, sr):
    import librosa
    import numpy as np

    return np.abs(librosa.cqt(y, sr=sr, hop_length=HOP, fmin=float(librosa.midi_to_hz(CQT_LO - 1.0 / BPS)),
                              n_bins=CQT_SEMIS * BPS + 2, bins_per_octave=12 * BPS))


def whiten(C, octaves: float = 1.0):
    """Log-compressed CQT minus its spectral envelope (median over +-octaves/2 across bins), floored at 0.

    Broadband sound (consonants, cymbal bleed) raises the wide high CQT bins; whitening keeps only the peaks that
    stand out of their neighbourhood, so the band edge does not win the salience."""
    import numpy as np
    from scipy.ndimage import median_filter

    L = np.log1p(1000.0 * C / (np.max(C) + 1e-12))
    w = int(round(12 * BPS * octaves)) | 1
    env = median_filter(L, size=(w, 1), mode="nearest")
    return np.maximum(0.0, L - env)


def salience(C, sub_penalty: float = 0.45):
    """Harmonic salience over CQT bins (same shape as C, C = compressed magnitude)."""
    import numpy as np

    nb = C.shape[0]
    sal = np.zeros_like(C)
    for h in range(1, N_HARM + 1):
        k = int(round(12 * BPS * math.log2(h)))
        if k >= nb:
            break
        sal[:nb - k] += HARM_W ** (h - 1) * C[k:]
    if sub_penalty > 0:
        # a candidate whose own bin is much weaker than the bin an octave up is probably a sub-harmonic
        k12 = 12 * BPS
        up = np.zeros_like(C)
        up[:nb - k12] = C[k12:]
        weak = np.clip(C / (up + 1e-9), 0, 1)
        sal *= (1 - sub_penalty) + sub_penalty * weak
    return sal


def viterbi_track(sal_band, voicing, jump_cost=6.0, octave_cost=4.0, step_bins=2, sharpen=3.0,
                  p_stay_voiced=0.985):
    """Dominant line through the band (bins x frames) with an unvoiced state. Returns state per frame (-1 unvoiced)."""
    import librosa
    import numpy as np

    nb, nf = sal_band.shape
    mx = sal_band.max(0, keepdims=True) + 1e-9
    em = (sal_band / mx) ** sharpen
    em = em / (em.sum(0, keepdims=True) + 1e-9)
    prob = np.vstack([em * voicing[None, :], 1.0 - voicing[None, :]])
    prob = np.maximum(prob, 1e-12)
    d = np.abs(np.arange(nb)[:, None] - np.arange(nb)[None, :])
    t = np.where(d <= step_bins, 1.0, math.exp(-jump_cost))
    octave = (d >= 11 * BPS) & (d <= 13 * BPS)
    t = np.where(octave, t * math.exp(-octave_cost), t)
    T = np.zeros((nb + 1, nb + 1))
    T[:nb, :nb] = t / t.sum(1, keepdims=True) * p_stay_voiced
    T[:nb, nb] = 1 - p_stay_voiced
    T[nb, :nb] = 0.02 / nb
    T[nb, nb] = 0.98
    path = librosa.sequence.viterbi(prob, T).astype(np.int64)
    return np.where(path == nb, -1, path)


def track(y, sr, lo, hi, method, params: dict | None = None):
    """Frame track: dict with t (sec), midi (float, nan unvoiced), voicing (0..1), band salience, pyin, meta."""
    import librosa
    import numpy as np

    pr = {"level_db": 28.0, "peaky": 2.2, "sub_penalty": 0.45, "jump_cost": 6.0, "octave_cost": 4.0,
          "sharpen": 3.0, "whiten": 0.5, **(params or {})}
    C = _cqt(y, sr)
    Cc = whiten(C, pr["whiten"]) if pr["whiten"] else np.sqrt(C)
    sal_all = salience(Cc, pr["sub_penalty"])
    nf = C.shape[1]
    t = librosa.frames_to_time(np.arange(nf), sr=sr, hop_length=HOP)
    b_lo = int(round((lo - CQT_LO) * BPS)) + 1
    b_hi = int(round((hi - CQT_LO) * BPS)) + 1
    band = sal_all[b_lo:b_hi + 1]
    bins_midi = CQT_LO + (np.arange(b_lo, b_hi + 1) - 1) / BPS
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=HOP)[0][:nf]
    if rms.size < nf:
        rms = np.pad(rms, (0, nf - rms.size))
    edb = 20 * np.log10(rms + 1e-9)
    ref = float(np.percentile(edb, 95))
    pk = band.max(0) / (np.median(band, 0) + 1e-9)
    v_e = 1 / (1 + np.exp(-(edb - (ref - pr["level_db"])) / 3.0))
    v_p = 1 / (1 + np.exp(-(pk - pr["peaky"]) / 0.4))
    voicing = np.clip(v_e * v_p, 0.001, 0.999)
    meta = {"frames": int(nf), "level_ref_db": ref}
    pyin = None
    if method in ("pyin", "auto"):
        f0, vflag, vp = librosa.pyin(y, fmin=float(librosa.midi_to_hz(lo - 0.5)),
                                     fmax=float(librosa.midi_to_hz(hi + 0.5)), sr=sr, frame_length=2048,
                                     hop_length=HOP)
        n = min(len(f0), nf)
        pm = np.full(nf, np.nan)
        pv = np.zeros(nf)
        pm[:n] = np.where(np.isfinite(f0[:n]), librosa.hz_to_midi(np.nan_to_num(f0[:n], nan=1.0)), np.nan)
        pv[:n] = np.nan_to_num(vp[:n])
        pyin = {"midi": pm, "vprob": pv, "voiced": np.zeros(nf, bool)}
        pyin["voiced"][:n] = np.asarray(vflag[:n], bool)
        loud = edb > ref - 20
        meta["pyin_voiced_pct"] = float(100 * np.mean(pyin["voiced"]))
        meta["pyin_median_vprob_loud"] = float(np.median(pv[loud])) if np.any(loud) else 0.0
    if method == "pyin":
        midi = np.where(pyin["voiced"], pyin["midi"], np.nan)
        voic = np.where(pyin["voiced"], np.maximum(pyin["vprob"], 0.5), 0.0)
        src = "pyin"
    else:
        st = viterbi_track(band, voicing, pr["jump_cost"], pr["octave_cost"], sharpen=pr["sharpen"])
        midi = np.full(nf, np.nan)
        ok = st >= 0
        idx = np.clip(st, 1, band.shape[0] - 2)
        cols = np.arange(nf)
        a, b, c = band[idx - 1, cols], band[idx, cols], band[idx + 1, cols]
        den = a - 2 * b + c
        off = np.clip(np.where(np.abs(den) > 1e-12, 0.5 * (a - c) / np.where(den == 0, 1, den), 0.0), -0.5, 0.5)
        midi[ok] = bins_midi[idx[ok]] + off[ok] / BPS
        voic = np.where(ok, voicing, 0.0)
        src = "salience"
    return {"t": t, "midi": midi, "voicing": voic, "band": band, "bins_midi": bins_midi, "edb": edb, "ref_db": ref,
            "pyin": pyin, "source": src, "meta": meta, "loud": edb > ref - 30}


# ---------------------------------------------------------------- segmentation

def onsets(y, sr, nf, delta=0.12, edb=None, dip_db=6.0):
    """Onset frames: spectral-flux peaks, plus re-attacks after a level dip of >= dip_db (repeated syllables)."""
    import librosa
    import numpy as np

    env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=HOP, aggregate=np.median, lag=2, max_size=3)
    env = env[:nf] if env.size >= nf else np.pad(env, (0, nf - env.size))
    nrm = env / (np.percentile(env, 99) + 1e-9)
    pk = librosa.util.peak_pick(nrm, pre_max=3, post_max=3, pre_avg=10, post_avg=10, delta=delta, wait=5)
    on = np.zeros(nf, bool)
    on[pk] = True
    if edb is not None and dip_db > 0:
        from scipy.ndimage import maximum_filter1d, minimum_filter1d

        e = edb[:nf]
        w = 6
        is_min = (e == minimum_filter1d(e, size=5)) & (e > np.percentile(e, 95) - 40)
        before = maximum_filter1d(e, size=w, origin=(w - 1) // 2)    # max over the w frames up to i
        after = maximum_filter1d(e, size=w, origin=-(w // 2))        # max over the w frames from i
        dips = np.where(is_min & (before - e >= dip_db) & (after - e >= dip_db))[0]
        for i in dips:
            j = min(nf - 1, i + 1 + int(np.argmax(e[i + 1:i + 1 + w] >= e[i] + dip_db / 2)))
            on[j] = True
    return on


def segment(tr, ons, min_frames=6, change_semi=0.6, change_frames=6):
    """Cut the frame track into raw notes (frames f0..f1 exclusive). Returns (notes, tuning in semitones)."""
    import numpy as np
    from scipy.ndimage import median_filter

    midi = tr["midi"]
    nf = midi.size
    ok = np.isfinite(midi)
    if ok.sum() < 3:
        return [], 0.0
    frac = midi[ok] - np.round(midi[ok])
    tuning = float(np.angle(np.mean(np.exp(2j * np.pi * frac))) / (2 * np.pi))
    m = midi - tuning
    ms = np.full(nf, np.nan)
    ms[ok] = median_filter(np.nan_to_num(m, nan=0.0), size=5)[ok]
    cuts = []
    i = 0
    while i < nf:
        if not ok[i]:
            i += 1
            continue
        j = i
        while j < nf and ok[j]:
            j += 1
        st = i
        cur = float(np.median(ms[st:min(j, st + change_frames)]))
        k = st + 1
        while k < j:
            if abs(ms[k] - cur) >= change_semi:
                w = ms[k:min(j, k + change_frames)]
                if w.size >= change_frames and np.all(np.abs(w - cur) >= change_semi) and np.ptp(w) < 1.2:
                    if k - st >= min_frames:
                        cuts.append((st, k))
                    st = k
                    cur = float(np.median(w))
                    k += 1
                    continue
            elif ons[k] and k - st >= min_frames and j - k >= min_frames:
                cuts.append((st, k))
                st = k
            if k - st >= change_frames:
                cur = float(np.median(ms[max(st, k - 3 * change_frames):k + 1]))
            k += 1
        if j - st >= min_frames:
            cuts.append((st, j))
        i = j
    out = []
    for a, b in cuts:
        seg = ms[a:b]
        p = float(np.median(seg))
        pr = int(round(p))
        out.append({"f0": a, "f1": b, "pitch": pr, "pitch_float": p,
                    "stable": float(np.mean(np.abs(seg - pr) < 0.5)), "voicing": float(np.mean(tr["voicing"][a:b])),
                    "onset": bool(ons[a] or ons[max(0, a - 1)] or ons[min(nf - 1, a + 1)])})
    return out, tuning


def _sal_at(tr, pitch, a, b, tuning):
    import numpy as np

    sel = np.abs(tr["bins_midi"] - (pitch + tuning)) <= 0.5
    if not sel.any():
        return 0.0
    return float(np.mean(tr["band"][sel][:, a:b].max(0)))


def note_checks(tr, n, tuning, method):
    """Octave check, pyin second opinion and confidence for one raw note (mutates n)."""
    import numpy as np

    a, b = n["f0"], n["f1"]
    p = n["pitch"]
    s0 = _sal_at(tr, p, a, b, tuning)
    s_up = _sal_at(tr, p + 12, a, b, tuning)
    s_dn = _sal_at(tr, p - 12, a, b, tuning)
    reasons = []
    if max(s_up, s_dn) >= 0.8 * max(s0, 1e-9):
        reasons.append("other_octave_strong")
    bm = tr["bins_midi"]
    col = tr["band"][:, a:b].mean(1)
    rel = np.abs(((bm - (p + tuning)) + 6) % 12 - 6)
    own = col[np.abs(bm - (p + tuning)) <= 0.5]
    other = col[rel > 1.5]
    margin = float(own.max() / (other.max() + 1e-9)) if (other.size and own.size) else 2.0
    src = tr["source"]
    agree = None
    py = tr["pyin"]
    if method == "auto" and py is not None:
        sel = (py["vprob"][a:b] >= 0.5) & np.isfinite(py["midi"][a:b])
        if sel.sum() >= 3:
            d = py["midi"][a:b][sel] - tuning - p
            agree = float(np.mean(np.abs(d) < 0.7))
            octv = np.abs(np.abs(d) - 12) < 0.7
            if octv.mean() >= 0.6:
                # pyin's octave is a second opinion only: on the evaluation stems it moved more notes to a wrong
                # (lower) octave than to the right one, so the note keeps the salience pitch and is flagged
                reasons.append("pyin_other_octave")
    conf = 0.25 + 0.35 * n["voicing"] + 0.2 * n["stable"] + 0.2 * min(1.0, max(0.0, margin - 1.0))
    if agree is not None:
        conf += 0.1 * (agree - 0.5)
    if reasons:
        conf -= 0.1
    n["confidence"] = float(max(0.05, min(1.0, conf)))
    n["octave_ambiguous"] = bool(reasons)
    n["octave_reasons"] = reasons
    n["source"] = src
    n["pyin_agree"] = agree
    n["sal"] = (s_dn, s0, s_up)


def register_pass(raw, semi=9.0, ratio=0.5, conf_min=0.6):
    """Move notes that sit far from the line's register by an octave when that octave is nearly as salient.

    Register = duration-weighted median pitch of the confident, unambiguous notes (all notes if none). A note more than ``semi`` semitones below (above) it
    whose salience one octave up (down) is >= ``ratio`` of its own is moved (source gets "+register"). Returns the
    number of moved notes."""
    import numpy as np

    if not raw or semi <= 0:
        return 0
    base = [n for n in raw if n["confidence"] >= conf_min and not n["octave_reasons"]] or raw
    w = np.array([max(1, n["f1"] - n["f0"]) for n in base], float)
    p = np.array([n["pitch"] for n in base], float)
    o = np.argsort(p)
    cw = np.cumsum(w[o])
    med = float(p[o][np.searchsorted(cw, cw[-1] / 2)])
    moved = 0
    for n in raw:
        s_dn, s0, s_up = n["sal"]
        if n["pitch"] < med - semi and s_up >= ratio * s0:
            n["pitch"] += 12
        elif n["pitch"] > med + semi and s_dn >= ratio * s0:
            n["pitch"] -= 12
        else:
            continue
        moved += 1
        n["source"] += "+register"
        n["octave_ambiguous"] = True
        if "register_moved" not in n["octave_reasons"]:
            n["octave_reasons"] = n["octave_reasons"] + ["register_moved"]
    return moved


def _dip_db(edb, a, b):
    """How far the stem level falls between frame a and b below the lower of the levels just around them (dB)."""
    import numpy as np

    lo = max(0, a - 3)
    hi = min(edb.size, b + 4)
    if b <= a or hi - lo < 3:
        return 0.0
    around = min(float(np.max(edb[lo:a + 1])), float(np.max(edb[b:hi])))
    return around - float(np.min(edb[a:b + 1]))


# ---------------------------------------------------------------- tool body

def analyse_melody(path: Path, bpm: float, first: float, num: int, den: int, grid_kind: int, measure_offset: int,
                   range_low: int, range_high: int, min_note_ticks: int | None, method: str, sections: list[dict],
                   min_confidence: float = 0.0, merge_gap_ticks: int | None = None, params: dict | None = None,
                   _y=None) -> dict:
    import numpy as np

    y = _y if _y is not None else io.load_mono(path, sr=SR)[0]
    if y.size < SR // 4:
        raise EarError("BAD_ARGUMENT", "스템 오디오가 너무 짧습니다(0.25초 미만).", {"path": str(path)})
    dur = y.size / SR
    if first >= dur:
        raise EarError("BAD_ARGUMENT", "first_downbeat_sec가 스템 길이보다 깁니다.",
                       {"first_downbeat_sec": first, "duration_sec": r(dur, 3)})
    g = Grid(bpm, first, num, den, measure_offset)
    gt = 12 if grid_kind == 32 else 24
    mnt = gt if min_note_ticks is None else min_note_ticks
    mgap = gt if merge_gap_ticks is None else merge_gap_ticks
    pr = params or {}
    tr = track(y, SR, range_low, range_high, method, pr)
    nf = tr["t"].size
    ons = onsets(y, SR, nf, pr.get("onset_delta", 0.12), tr["edb"], pr.get("dip_db", 6.0))
    raw, tuning = segment(tr, ons, pr.get("min_frames", 6), pr.get("change_semi", 0.6), pr.get("change_frames", 6))
    for n in raw:
        note_checks(tr, n, tuning, method)
    reg = register_pass(raw, pr.get("register_semi", 9.0), pr.get("register_ratio", 0.5), pr.get("register_conf", 0.6))
    t = tr["t"]
    placed = []
    dropped_before = 0
    for n in raw:
        t0 = float(t[n["f0"]])
        t1 = float(t[min(n["f1"], nf - 1)])
        tk0, tk1 = g.sec2tick(t0), g.sec2tick(t1)
        if tk0 < g.tick0 - gt / 2:
            dropped_before += 1
            continue
        q0 = max(g.tick0, quant(tk0, gt))
        placed.append({**n, "tick": q0, "end": quant(tk1, gt), "sec": t0, "sec_end": t1, "grid_error": tk0 - q0})
    by = {}
    for x in placed:
        k = x["tick"]
        if k not in by or (x["confidence"], x["end"]) > (by[k]["confidence"], by[k]["end"]):
            by[k] = x
    res: list[dict] = []
    merged = 0
    for x in sorted(by.values(), key=lambda x: x["tick"]):
        if res:
            pv = res[-1]
            dip = _dip_db(tr["edb"], pv["f1"], x["f0"]) if x["f0"] > pv["f1"] else 0.0
            if x["pitch"] == pv["pitch"] and x["tick"] - pv["end"] <= mgap and not x["onset"] and dip < 6.0:
                pv["end"] = max(pv["end"], x["end"])
                pv["f1"] = x["f1"]
                pv["sec_end"] = x["sec_end"]
                pv["confidence"] = max(pv["confidence"], x["confidence"])
                pv["merged"] = pv.get("merged", 0) + 1
                pv["octave_ambiguous"] = pv["octave_ambiguous"] or x["octave_ambiguous"]
                pv["octave_reasons"] = sorted(set(pv["octave_reasons"]) | set(x["octave_reasons"]))
                merged += 1
                continue
            if x["tick"] < pv["end"]:
                pv["end"] = x["tick"]
        res.append(x)
    short = sum(1 for x in res if x["end"] - x["tick"] < mnt)
    res = [x for x in res if x["end"] - x["tick"] >= mnt]
    notes = []
    for x in res:
        p, m, b, tib = g.pos(x["tick"])
        notes.append({"pos": p, "tick": int(x["tick"]), "measure": m, "beat": b, "tick_in_beat": tib,
                      "length_ticks": int(x["end"] - x["tick"]), "pitch": int(x["pitch"]),
                      "name": io.PITCH_NAMES[x["pitch"] % 12] + str(x["pitch"] // 12 - 1),
                      "confidence": r(x["confidence"], 2), "source": x["source"],
                      "octave_ambiguous": x["octave_ambiguous"], "octave_reasons": x["octave_reasons"],
                      "pyin_agree": r(x["pyin_agree"], 2) if x["pyin_agree"] is not None else None,
                      "stable": r(x["stable"], 2), "sec": r(x["sec"], 3), "sec_end": r(x["sec_end"], 3),
                      "grid_error_ticks": r(x["grid_error"], 1), "merged": x.get("merged", 0)})
    n_before = len(notes)
    notes = [n for n in notes if n["confidence"] >= min_confidence]
    after = t >= first
    voiced = np.isfinite(tr["midi"])
    loud = tr["loud"] & after
    span_ticks = max(1.0, g.sec2tick(dur) - g.tick0)
    cover = {"voiced_pct": r(100 * float(np.mean(voiced[after])) if after.any() else 0.0, 1),
             "voiced_of_loud_pct": r(100 * float(np.mean(voiced[loud])) if loud.any() else 0.0, 1),
             "loud_pct": r(100 * float(np.mean(loud[after])) if after.any() else 0.0, 1),
             "note_time_pct": r(100 * sum(n["length_ticks"] for n in notes) / span_ticks, 1)}
    by_m = Counter(n["measure"] for n in notes)
    last_m = g.measure_of(g.sec2tick(dur))
    per_measure = [{"measure": m, "notes": by_m.get(m, 0)} for m in range(measure_offset, last_m + 1)]
    secs = sections
    auto = not secs
    if auto:
        secs = [{"name": f"m{a}-{min(a + 7, last_m)}", "from_measure": a, "to_measure": min(a + 7, last_m)}
                for a in range(measure_offset, last_m + 1, 8)]
    sec_out = []
    for s in secs:
        ns = [n for n in notes if s["from_measure"] <= n["measure"] <= s["to_measure"]]
        sec_out.append({"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"],
                        "notes": len(ns), "octave_ambiguous": sum(1 for n in ns if n["octave_ambiguous"]),
                        "low_confidence": sum(1 for n in ns if n["confidence"] < 0.5),
                        "range": ([min(n["pitch"] for n in ns), max(n["pitch"] for n in ns)] if ns else None)})
    pitches = [n["pitch"] for n in notes]
    placements = [{"tick": n["tick"], "len": n["length_ticks"], "pitch": n["pitch"],
                   "vel": 10 if n["confidence"] >= 0.5 else 8, "pos": n["pos"], "confidence": n["confidence"]}
                  for n in notes]
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                     "ticks_per_measure": g.tpm, "measure_offset": measure_offset, "grid": grid_kind,
                     "grid_ticks": gt, "min_note_ticks": mnt, "merge_gap_ticks": mgap},
            "track": {"method": method, "sr": SR, "hop": HOP, "bins_per_semitone": BPS, "harmonics": N_HARM,
                      "range_low": range_low, "range_high": range_high, "tuning_cents": r(100 * tuning, 1),
                      **{k: (r(v, 3) if isinstance(v, float) else v) for k, v in tr["meta"].items()}},
            "notes": notes, "placements": placements,
            "placements_note": "score_put_notes notes with pitch_mode \"sounding\"; fold octaves into the lead "
                               "instrument's range first (flute 60-95 usually needs +12 for a sung line)",
            "coverage": cover,
            "range": ({"low": min(pitches), "high": max(pitches), "p5": int(np.percentile(pitches, 5)),
                       "p50": int(np.percentile(pitches, 50)), "p95": int(np.percentile(pitches, 95))}
                      if pitches else None),
            "sections": sec_out, "sections_auto": auto, "per_measure": per_measure,
            "counts": {"notes": len(notes), "octave_ambiguous": sum(1 for n in notes if n["octave_ambiguous"]),
                       "pyin_octave_moves": sum(1 for n in notes if "pyin_octave" in n["source"]),
                       "register_moves": reg,
                       "merged": merged, "dropped_short": short, "dropped_before_start": dropped_before,
                       "dropped_low_confidence": n_before - len(notes), "raw_segments": len(raw)},
            "limits": MELODY_NOTES}
