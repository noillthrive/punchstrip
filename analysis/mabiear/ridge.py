"""Lead transcription from a stem with the octave decided by harmonic evidence (``stem_melody_notes``,
``method: "ridge"``).

For instrumental solos (a synth or guitar lead in an Other / Guitar stem) where the plain salience method put some
notes an octave low and turned accompaniment bleed into notes. Rules only:

1. Pitch line: the ``salience`` frame track and note cutting of ``stem_melody_notes`` (CQT harmonic salience, Viterbi
   with octave-leap cost, cuts at pitch changes and onsets) give raw notes with their seconds.
2. Harmonic evidence: the stem's STFT (4096 / hop 128 at 22.05 kHz) flattened by its median over 31 bins; the
   *excess* level of a position = whitened level - log 2 (about 0 between partials). Per note (its steady part, after
   the first quarter) and per octave candidate c of the note's pitch class inside the range: odd = mean excess at
   harmonics 1, 3, 5, 7, even = at 2, 4, 6, 8. A sub-octave candidate has empty odd harmonics (they fall between the
   real partials); the real fundamental and the octaves above it have both, so a candidate **passes** when
   odd / even >= ``odd_even_min`` (0.6) and its harmonic level (odd + even) is >= 0.5 of the best candidate's.
3. Register: the duration-weighted median pitch of the first-pass notes with confidence >= 0.6. Among the passing
   candidates the one nearest to the register wins (a real tone an octave below the lead — accompaniment bleed — also
   passes, so "lowest passing" would pick the bleed). ``octave_ambiguous`` when another candidate passes and is
   nearer to the register than 7 semitones, or none passes.
4. Bleed: a note >= 10 semitones from the register with no passing candidate near it is bleed when the previous note's
   pitch still sounds through it (its excess >= 0.6 of its own level: the lead is holding) -> the previous note is
   extended; or when its harmonic level is below 0.4 of the median note -> dropped (``dropped_bleed``).
5. Grid: onsets and ends to the grid (16th = 24 ticks, 32nd = 12; ``triplets`` = the nearer of the 16th and the
   eighth-triplet grid, 24 / 32 ticks), one line, same-pitch neighbours merged unless a spectral-flux onset of the stem
   falls within 30 ms of the second start (a re-attack), notes shorter than ``min_note_ticks`` dropped.
6. Confidence = first-pass confidence + 0.15 x clip(octave margin / 0.5) - 0.15 when octave-ambiguous.
"""
from __future__ import annotations

import logging
import math
from collections import Counter
from pathlib import Path

from . import io
from .io import EarError, r
from .stems import Grid, quant

log = logging.getLogger("mabiear.ridge")

NFFT = 4096
HOP = 128
SR = 22050
WHITEN_BINS = 31
LN2 = math.log(2.0)

RIDGE_LIMITS = [
    "One line only (the strongest voice of the stem, from the salience track); a doubled or harmonised lead keeps "
    "the stronger voice.",
    "Bends and slides land on the pitch held longest; very fast runs (< 70 ms per note) merge into their neighbours.",
    "The octave is chosen among the candidates whose harmonics are complete, nearest to the line's register: a solo "
    "that jumps more than an octave away from its usual register for one note may be folded back (octave_ambiguous).",
    "Positions come from the given bpm / first_downbeat_sec (no phase search).",
]


def stft_excess(path: Path, t0: float, t1: float):
    """Whitened STFT (3-bin max, float16) and the flux onset frames of a stem between t0 and t1."""
    import librosa
    import numpy as np
    from scipy.ndimage import maximum_filter1d, median_filter

    from .leadcheck import load_stereo

    x, start = load_stereo(path, max(0.0, t0), t1)
    y = x.mean(axis=1)
    A = np.abs(librosa.stft(y, n_fft=NFFT, hop_length=HOP, center=True))
    df = SR / NFFT
    top = int(min(A.shape[0], math.ceil(9000.0 / df)))
    A = A[:top]
    eps = 1e-6 * float(A.max() + 1e-12) + 1e-9
    W = np.log1p(A / (median_filter(A, size=(WHITEN_BINS, 1), mode="nearest") + eps))
    W = maximum_filter1d(W, size=3, axis=0).astype(np.float16)
    env = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP, aggregate=np.median, lag=2, max_size=3)
    nf = A.shape[1]
    env = env[:nf] if env.size >= nf else np.pad(env, (0, nf - env.size))
    nrm = env / (np.percentile(env, 99) + 1e-9)
    w = 5
    pk = librosa.util.peak_pick(nrm, pre_max=w, post_max=w, pre_avg=4 * w, post_avg=4 * w, delta=0.15, wait=2 * w)
    t = start + np.arange(nf) * HOP / SR
    return {"W": W, "t": t, "df": df, "onset_sec": t[pk]}


def harm_level(sp, a: int, b: int, m: float, harmonics) -> float:
    """Mean excess whitened level at the given harmonics of MIDI m over frames [a, b)."""
    import numpy as np

    W, df = sp["W"], sp["df"]
    vals = []
    for h in harmonics:
        k = h * 440.0 * 2 ** ((m - 69) / 12) / df
        if k >= W.shape[0] - 2:
            continue
        lo = int(math.floor(k))
        fr = k - lo
        v = (1 - fr) * W[lo, a:b].astype(np.float32) + fr * W[lo + 1, a:b].astype(np.float32)
        vals.append(float(np.mean(np.maximum(v - LN2, 0.0))) if v.size else 0.0)
    return float(np.mean(vals)) if vals else 0.0


def octave_table(sp, a: int, b: int, pitch: int, tuning: float, lo: int, hi: int) -> dict:
    out = {}
    for o in (-24, -12, 0, 12, 24):
        c = pitch + o
        if not lo <= c <= hi:
            continue
        odd = harm_level(sp, a, b, c + tuning, (1, 3, 5, 7))
        even = harm_level(sp, a, b, c + tuning, (2, 4, 6, 8))
        out[c] = {"odd": odd, "even": even, "ratio": odd / (even + 1e-6), "level": odd + even}
    return out


def choose_octave(tab: dict, register: float, odd_even_min: float, solo_ratio: float):
    """(pitch, margin, reasons, single). The lowest candidate with complete harmonics L is a real tone; the octave
    above it always 'passes' too (its partials are L's even partials), so it only counts as a second tone when L's
    odd / even ratio is below ``solo_ratio`` (L's even partials are boosted by another tone). One tone -> L; two ->
    the one nearer to the register (``both_octaves_sound``). margin = chosen ratio - ratio one octave below."""
    if not tab:
        return None, 0.0, ["no_candidate"], True
    best_level = max(v["level"] for v in tab.values())
    passing = sorted(c for c, v in tab.items() if v["ratio"] >= odd_even_min and v["level"] >= 0.5 * best_level)
    if not passing:
        c = max(tab, key=lambda k: tab[k]["level"])
        return c, 0.0, ["no_full_harmonics"], True
    low = passing[0]
    reasons = []
    single = tab[low]["ratio"] >= solo_ratio or len(passing) == 1
    if single:
        c = low
    else:
        c = min(passing, key=lambda k: (abs(k - register), k))
        reasons.append("both_octaves_sound")
    lower = tab.get(c - 12)
    margin = tab[c]["ratio"] - (lower["ratio"] if lower else 0.0)
    return c, float(margin), reasons, single


def analyse_ridge(path: Path, bpm: float, first: float, num: int, den: int, grid_kind: int, measure_offset: int,
                  range_low: int, range_high: int, min_note_ticks: int | None, sections: list[dict],
                  min_confidence: float = 0.0, triplets: bool = False, from_sec: float | None = None,
                  to_sec: float | None = None, params: dict | None = None) -> dict:
    import numpy as np

    from .melody import analyse_melody

    pr = {"odd_even_min": 0.6, "solo_ratio": 0.9, "bleed_semis": 9, "hold_ratio": 0.6, "weak_ratio": 0.4,
          "onset_tol_sec": 0.03, "drop_low_bleed": True, "register_pct": 0.7,
          **(params or {})}
    g = Grid(bpm, first, num, den, measure_offset)
    gt = 12 if grid_kind == 32 else 24
    mnt = gt if min_note_ticks is None else min_note_ticks
    first_pass = analyse_melody(path, bpm, first, num, den, grid_kind, measure_offset, range_low, range_high,
                                max(6, gt // 2), "salience", [], 0.0, gt)
    notes0 = [n for n in first_pass["notes"]
              if (from_sec is None or n["sec_end"] > from_sec) and (to_sec is None or n["sec"] < to_sec)]
    info = io.probe(path)
    if not notes0:
        raise EarError("BAD_ARGUMENT", "고른 구간에서 음을 찾지 못했습니다.", {"from_sec": from_sec, "to_sec": to_sec})
    s0 = max(0.0, min(n["sec"] for n in notes0) - 0.5)
    s1 = min(info["duration_sec"], max(n["sec_end"] for n in notes0) + 0.5)
    sp = stft_excess(path, s0, s1)
    t = sp["t"]
    tuning = first_pass["track"]["tuning_cents"] / 100.0
    conf_notes = [n for n in notes0 if n["confidence"] >= 0.6] or notes0
    w = np.array([max(1, n["length_ticks"]) for n in conf_notes], float)
    p = np.array([n["pitch"] for n in conf_notes], float)
    o = np.argsort(p)
    cw = np.cumsum(w[o])
    register = float(p[o][min(len(o) - 1, np.searchsorted(cw, pr["register_pct"] * cw[-1]))])
    levels = []
    work = []
    for n in notes0:
        a = int(np.searchsorted(t, n["sec"]))
        b = int(np.searchsorted(t, n["sec_end"]))
        a2 = a + max(1, (b - a) // 4)
        if b - a2 < 2:
            a2, b = a, max(b, a + 2)
        tab = octave_table(sp, a2, b, n["pitch"], tuning, range_low, range_high)
        c, margin, reasons, single = choose_octave(tab, register, pr["odd_even_min"], pr["solo_ratio"])
        lev = tab.get(c, {"level": 0.0})["level"] if c is not None else 0.0
        levels.append(lev)
        work.append({**n, "a": a2, "b": b, "pitch0": n["pitch"], "pitch": c if c is not None else n["pitch"],
                     "octave_margin": margin, "reasons": reasons, "level": lev, "single": single,
                     "table": {str(k): {kk: r(vv, 3) for kk, vv in v.items()} for k, v in tab.items()}})
    med_level = float(np.median(levels)) if levels else 0.0
    kept, dropped_bleed, held, dropped = [], 0, 0, []
    for x in work:
        far = x["pitch"] <= register - pr["bleed_semis"]
        if far:
            prev = kept[-1] if kept else None
            holding = False
            if prev is not None:
                own = prev["level"]
                cont = (harm_level(sp, x["a"], x["b"], prev["pitch"] + tuning, (1, 3, 5, 7))
                        + harm_level(sp, x["a"], x["b"], prev["pitch"] + tuning, (2, 4, 6, 8)))
                holding = own > 0 and cont >= pr["hold_ratio"] * own and x["sec"] - prev["sec_end"] < 0.25
            if holding:
                prev["sec_end"] = x["sec_end"]
                prev["held_over"] = prev.get("held_over", 0) + 1
                held += 1
                continue
            low_bleed = x["single"] and x["pitch"] <= register - pr["bleed_semis"] and pr["drop_low_bleed"]
            if low_bleed:
                # the lead may still sound under the bleed: best complete-harmonic tone within the register window
                cands = []
                for m in range(int(register) - 8, int(register) + 9):
                    if not range_low <= m <= range_high:
                        continue
                    odd = harm_level(sp, x["a"], x["b"], m + tuning, (1, 3, 5, 7))
                    even = harm_level(sp, x["a"], x["b"], m + tuning, (2, 4, 6, 8))
                    # partials shared with the bleed tone (a 12th, an octave, a 19th) do not count as a new tone
                    if (m - x["pitch"]) % 12 in (0, 7):
                        continue
                    if odd / (even + 1e-6) >= pr["odd_even_min"] and odd + even >= 0.5 * med_level:
                        cands.append((odd + even, m))
                if cands:
                    lev, m = max(cands)
                    x["reasons"] = x["reasons"] + ["replaced_bleed_%s" % (io.PITCH_NAMES[x["pitch"] % 12]
                                                                          + str(x["pitch"] // 12 - 1))]
                    x["pitch"], x["level"] = m, lev
                    x["confidence"] = min(x["confidence"], 0.6)
                    kept.append(x)
                    continue
            if x["level"] < pr["weak_ratio"] * med_level or low_bleed:
                dropped_bleed += 1
                dropped.append({"sec": r(x["sec"], 3), "sec_end": r(x["sec_end"], 3), "pitch": x["pitch"],
                                "name": io.PITCH_NAMES[x["pitch"] % 12] + str(x["pitch"] // 12 - 1),
                                "why": "one tone far below the line's register (bleed)" if low_bleed else "weak"})
                continue
            x["reasons"] = x["reasons"] + ["far_from_register"]
        kept.append(x)
    onsets = np.asarray(sp["onset_sec"])

    def snap(tk):
        if triplets:
            q24, q32 = quant(tk, 24), quant(tk, 32)
            return q24 if abs(tk - q24) <= abs(tk - q32) else q32
        return quant(tk, gt)

    placed = []
    for x in kept:
        tk0, tk1 = g.sec2tick(x["sec"]), g.sec2tick(x["sec_end"])
        q0 = max(g.tick0, snap(tk0))
        q1 = max(q0, snap(tk1))
        re = bool(onsets.size and np.min(np.abs(onsets - x["sec"])) <= pr["onset_tol_sec"])
        amb = bool({"both_octaves_sound", "no_full_harmonics", "far_from_register"} & set(x["reasons"]))
        conf = x["confidence"] + 0.15 * min(1.0, max(0.0, x["octave_margin"]) / 0.5) - (0.15 if amb else 0.0)
        placed.append({**x, "tick": q0, "end": q1, "grid_error": tk0 - q0, "reattack": re,
                       "confidence": float(max(0.05, min(1.0, conf))), "octave_ambiguous": amb})
    by = {}
    for x in placed:
        k = x["tick"]
        if k not in by or x["confidence"] > by[k]["confidence"]:
            by[k] = x
    res, merged = [], 0
    for x in sorted(by.values(), key=lambda z: z["tick"]):
        if res:
            pv = res[-1]
            if x["pitch"] == pv["pitch"] and not x["reattack"] and x["tick"] - pv["end"] <= gt:
                pv["end"] = max(pv["end"], x["end"])
                pv["sec_end"] = x["sec_end"]
                pv["confidence"] = max(pv["confidence"], x["confidence"])
                merged += 1
                continue
            if x["tick"] < pv["end"]:
                pv["end"] = x["tick"]
        res.append(x)
    short = sum(1 for x in res if x["end"] - x["tick"] < mnt)
    res = [x for x in res if x["end"] - x["tick"] >= mnt]
    notes = []
    for x in res:
        p_, m, bt, tib = g.pos(x["tick"])
        notes.append({"pos": p_, "tick": int(x["tick"]), "measure": m, "beat": bt, "tick_in_beat": tib,
                      "length_ticks": int(x["end"] - x["tick"]), "pitch": int(x["pitch"]),
                      "name": io.PITCH_NAMES[x["pitch"] % 12] + str(x["pitch"] // 12 - 1),
                      "confidence": r(x["confidence"], 2), "source": "ridge",
                      "octave_ambiguous": x["octave_ambiguous"], "octave_reasons": sorted(set(x["reasons"])),
                      "octave_margin": r(x["octave_margin"], 2), "first_pass_pitch": int(x["pitch0"]),
                      "octave_table": x["table"], "reattack": x["reattack"], "held_over": x.get("held_over", 0),
                      "pyin_agree": None, "stable": x.get("stable"), "sec": r(x["sec"], 3),
                      "sec_end": r(x["sec_end"], 3), "grid_error_ticks": r(x["grid_error"], 1),
                      "triplet": bool(x["tick"] % 24 != 0), "merged": 0})
    n_before = len(notes)
    notes = [n for n in notes if n["confidence"] >= min_confidence]
    span0 = max(g.tick0, g.sec2tick(s0))
    span_ticks = max(1.0, g.sec2tick(s1) - span0)
    cover = {"voiced_pct": first_pass["coverage"]["voiced_pct"],
             "voiced_of_loud_pct": first_pass["coverage"]["voiced_of_loud_pct"],
             "loud_pct": first_pass["coverage"]["loud_pct"],
             "note_time_pct": r(100 * sum(n["length_ticks"] for n in notes) / span_ticks, 1)}
    first_m = g.measure_of(span0)
    last_m = g.measure_of(g.sec2tick(s1))
    by_m = Counter(n["measure"] for n in notes)
    per_measure = [{"measure": m, "notes": by_m.get(m, 0)} for m in range(first_m, last_m + 1)]
    secs = sections
    auto = not secs
    if auto:
        secs = [{"name": f"m{a_}-{min(a_ + 7, last_m)}", "from_measure": a_, "to_measure": min(a_ + 7, last_m)}
                for a_ in range(first_m, last_m + 1, 8)]
    sec_out = []
    for s in secs:
        ns = [n for n in notes if s["from_measure"] <= n["measure"] <= s["to_measure"]]
        sec_out.append({"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"],
                        "notes": len(ns), "octave_ambiguous": sum(1 for n in ns if n["octave_ambiguous"]),
                        "low_confidence": sum(1 for n in ns if n["confidence"] < 0.5),
                        "triplet_onsets": sum(1 for n in ns if n["triplet"]),
                        "range": ([min(n["pitch"] for n in ns), max(n["pitch"] for n in ns)] if ns else None)})
    pitches = [n["pitch"] for n in notes]
    placements = [{"tick": n["tick"], "len": n["length_ticks"], "pitch": n["pitch"],
                   "vel": 10 if n["confidence"] >= 0.5 else 8, "pos": n["pos"], "confidence": n["confidence"]}
                  for n in notes]
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                     "ticks_per_measure": g.tpm, "measure_offset": measure_offset, "grid": grid_kind,
                     "grid_ticks": gt, "triplets": triplets, "min_note_ticks": mnt, "merge_gap_ticks": gt},
            "track": {**first_pass["track"], "method": "ridge", "first_pass": "salience", "stft_n_fft": NFFT,
                      "stft_hop": HOP, "register_midi": register, "span_sec": [r(s0, 3), r(s1, 3)],
                      "params": pr},
            "notes": notes, "placements": placements,
            "placements_note": "score_put_notes notes with pitch_mode \"sounding\"",
            "coverage": cover,
            "range": ({"low": min(pitches), "high": max(pitches), "p5": int(np.percentile(pitches, 5)),
                       "p50": int(np.percentile(pitches, 50)), "p95": int(np.percentile(pitches, 95))}
                      if pitches else None),
            "sections": sec_out, "sections_auto": auto, "per_measure": per_measure,
            "counts": {"notes": len(notes), "octave_ambiguous": sum(1 for n in notes if n["octave_ambiguous"]),
                       "octave_moves": sum(1 for n in notes if n["pitch"] != n["first_pass_pitch"]),
                       "register_moves": 0, "reattacks": sum(1 for n in notes if n["reattack"]),
                       "triplet_onsets": sum(1 for n in notes if n["triplet"]), "held_over": held,
                       "dropped_bleed": dropped_bleed, "merged": merged, "dropped_short": short,
                       "dropped_low_confidence": n_before - len(notes), "first_pass_notes": len(notes0)},
            "dropped_bleed": dropped,
            "limits": RIDGE_LIMITS}
