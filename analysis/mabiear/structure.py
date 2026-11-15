"""Repeat structure of a song on a fixed measure grid (``structure_repeats``).

Port of two earlier survey scripts of this project (structure and harmony-copy detection; rules
only). Grid: measure m (tool number, from ``measure_offset``) starts at ``first_downbeat_sec + (m - offset) x measure``,
one tempo, one time signature.

Features (22.05 kHz mono, hop 512): chroma signal = the sum of the given files except drum stems (file name contains
"drum", case-insensitive; with a single mix: its HPSS harmonic part); texture signal = the sum of all files.
Chroma: librosa ``chroma_cqt`` averaged per beat, each beat L2-normalised -> beats x 12 per measure. MFCC(20) mean per
measure, z-scored over the song, L2-normalised.

Similarity: ``S_r(i, j)`` = mean over the beats of cos(chroma_i[b], roll(chroma_j[b], r)) - measure i is measure j
transposed up by r semitones. ``S_mfcc(i, j)`` = cosine of the MFCC vectors.

Thresholds (``thresholds: "auto"``, default): relative to the song's own distribution of ``S_0`` over measure pairs at
least 2 apart (both measures active): ``chroma = median + 0.45 x (p99 - median)``, clipped to [0.80, 0.97];
``mfcc = median + 0.68 x (p99 - median)`` of ``S_mfcc``, clipped to [0.30, 0.80] (about 0.6 on a typical song, the survey's value). On such a song (median 0.75) this gives
about 0.86 (the survey's fixed 0.85); on a song whose measures all sound alike (median 0.83), about 0.90. Or give
``{"chroma": x, "mfcc": y}``.

Runs: on every diagonal (lag = i - j >= 2) the longest stretches of consecutive measures with ``S_0 >= chroma`` of at
least ``min_run_measures``; inside one, the stretches that also have ``S_mfcc >= mfcc`` for ``min_run_measures`` are
``exact`` (same notes and texture), the rest ``harmony`` (same chords, other texture/arrangement). ``transposed``: for
r != 0 the stretches with ``S_r >= chroma`` and ``S_r >= S_0 + 0.05``. Silent measures (mix 35 dB under its 90th
percentile) never match.

Copy map: each target measure takes one source, preferring exact over harmony over transposed, then the longer run,
then the higher similarity, then the earlier source; sources can be limited to ``source_measures`` [lo, hi] (e.g. the
span already transcribed; targets inside that span are then not mapped). Consecutive targets whose source moves on by one measure with the same kind and
transposition form one entry ``{target [a, b], source [c, d], kind, transpose, similarity}`` - "copy c..d to a..b".
``measure_hints``: for active measures the map leaves open, the best single-measure match (any lag and transposition,
>= the chroma threshold; a transposed match must beat the untransposed one by 0.05) - one-bar loops and short
transposed fragments that cannot form a run of ``min_run_measures`` on one diagonal.
"""
from __future__ import annotations

import logging
import re
from pathlib import Path

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.structure")

SCHEMA_STRUCTURE = "mabiear.structure/1"
SR = 22050
HOP = 512
AUTO_K = 0.45
AUTO_K_MFCC = 0.68
KIND_ORDER = {"exact": 0, "harmony": 1, "transposed": 2}
_DRUM = re.compile(r"drum", re.I)

STRUCTURE_NOTES = [
    "Similarity is per measure on a fixed grid: a phrase that starts half a measure late, or a tempo change, breaks "
    "the diagonal (check bpm / first_downbeat_sec first).",
    "harmony = same chord colours with another texture; the melody may differ (a new verse over the same loop). exact "
    "needs both chroma and MFCC, so a copy with a different vocal or instrument entry shows as harmony.",
    "Songs built on one loop (every measure alike) have high similarity everywhere: the auto thresholds then rise and "
    "short runs are kept only where the song really repeats; check suggested copies by ear.",
]


def check_thresholds(t):
    if t in (None, "auto"):
        return None
    if not isinstance(t, dict) or set(t) - {"chroma", "mfcc"} or "chroma" not in t:
        raise EarError("BAD_ARGUMENT", "'thresholds'는 \"auto\" 또는 {\"chroma\": 0~1, \"mfcc\": -1~1}입니다.",
                       {"thresholds": t})
    c = t["chroma"]
    m = t.get("mfcc", 0.6)
    for k, v, lo in (("chroma", c, 0.0), ("mfcc", m, -1.0)):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not lo <= v <= 1.0:
            raise EarError("BAD_ARGUMENT", f"thresholds.{k}는 {lo}~1 숫자입니다.", {k: v})
    return float(c), float(m)


def _beat_chroma(ch, t, beats):
    import numpy as np

    out = np.zeros((len(beats) - 1, 12))
    for k in range(len(beats) - 1):
        sel = (t >= beats[k]) & (t < beats[k + 1])
        if sel.any():
            out[k] = ch[:, sel].mean(1)
    return out


def features(paths: list[Path], bpm: float, first: float, beats_per_measure: int, beat_sec: float,
             n_measures: int | None):
    import librosa
    import numpy as np

    ys = [io.load_mono(p, sr=SR)[0] for p in paths]
    n = min(y.size for y in ys)
    ys = [y[:n] for y in ys]
    dur = n / SR
    msec = beats_per_measure * beat_sec
    avail = int(np.floor((dur - first) / msec + 1e-6))
    if avail < 2:
        raise EarError("BAD_ARGUMENT", "마디가 2개도 들어가지 않습니다(first_downbeat_sec·bpm 확인).",
                       {"duration_sec": r(dur, 2), "measure_sec": r(msec, 3)})
    nm = avail if n_measures is None else min(n_measures, avail)
    beats = first + beat_sec * np.arange(nm * beats_per_measure + 1)
    mix = np.sum(ys, axis=0)
    harm_parts = [y for p, y in zip(paths, ys) if not _DRUM.search(p.name)]
    if len(paths) == 1:
        harm = librosa.effects.harmonic(mix, margin=2.0)
        chroma_src = "hpss_harmonic_of_mix"
    elif harm_parts:
        harm = np.sum(harm_parts, axis=0)
        chroma_src = "non_drum_stems"
    else:
        harm = mix
        chroma_src = "all_files"
    ch = librosa.feature.chroma_cqt(y=harm, sr=SR, hop_length=HOP)
    tc = librosa.frames_to_time(np.arange(ch.shape[1]), sr=SR, hop_length=HOP)
    B = _beat_chroma(ch, tc, beats).reshape(nm, beats_per_measure, 12)
    Cn = B / (np.linalg.norm(B, axis=2, keepdims=True) + 1e-9)
    mf = librosa.feature.mfcc(y=mix, sr=SR, hop_length=HOP, n_mfcc=20)
    tf = librosa.frames_to_time(np.arange(mf.shape[1]), sr=SR, hop_length=HOP)
    M = np.zeros((nm, 20))
    db = np.zeros(nm)
    for m in range(nm):
        sel = (tf >= beats[m * beats_per_measure]) & (tf < beats[(m + 1) * beats_per_measure])
        if sel.any():
            M[m] = mf[:, sel].mean(1)
        a, b = int(beats[m * beats_per_measure] * SR), int(beats[(m + 1) * beats_per_measure] * SR)
        seg = mix[a:b]
        db[m] = 20 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-9) if seg.size else -120.0
    Mz = (M - M.mean(0)) / (M.std(0) + 1e-9)
    Mz = Mz / (np.linalg.norm(Mz, axis=1, keepdims=True) + 1e-9)
    Smf = Mz @ Mz.T
    S = np.zeros((12, nm, nm))
    for k in range(12):
        S[k] = np.einsum("ibk,jbk->ij", Cn, np.roll(Cn, k, axis=2)) / beats_per_measure
    active = db > np.percentile(db, 90) - 35
    return {"S": S, "Smf": Smf, "active": active, "n": nm, "available": avail, "duration_sec": dur,
            "chroma_source": chroma_src, "mix_db": db, "chroma": B.sum(1)}


def auto_thresholds(S0, Smf, active):
    import numpy as np

    n = S0.shape[0]
    iu = np.triu_indices(n, 2)
    ok = active[iu[0]] & active[iu[1]]
    if ok.sum() < 10:
        return 0.85, 0.6, {}
    v = S0[iu][ok]
    w = Smf[iu][ok]
    med, p99 = float(np.median(v)), float(np.percentile(v, 99))
    mmed, mp99 = float(np.median(w)), float(np.percentile(w, 99))
    c = float(np.clip(med + AUTO_K * (p99 - med), 0.80, 0.97))
    m = float(np.clip(mmed + AUTO_K_MFCC * (mp99 - mmed), 0.30, 0.80))
    dist = {"chroma": {"median": r(med, 3), "p90": r(float(np.percentile(v, 90)), 3),
                       "p95": r(float(np.percentile(v, 95)), 3), "p99": r(p99, 3)},
            "mfcc": {"median": r(mmed, 3), "p90": r(float(np.percentile(w, 90)), 3), "p99": r(mp99, 3)},
            "pairs": int(ok.sum()), "rule": f"chroma: median + {AUTO_K} x (p99 - median) in [0.80, 0.97]; "
                                             f"mfcc: median + {AUTO_K_MFCC} x (p99 - median) in [0.30, 0.80]"}
    return c, m, dist


def _stretches(mask, min_len):
    """[(start, length)] of True stretches of a 1-D bool array with length >= min_len."""
    out = []
    i = 0
    n = len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            if j - i >= min_len:
                out.append((i, j - i))
            i = j
        else:
            i += 1
    return out


def find_runs(S, Smf, active, thr_c, thr_m, min_run):
    """All repeat runs: dicts with target/source 0-based start, length, kind, transpose, sim, mfcc."""
    import numpy as np

    n = S.shape[1]
    runs = []
    for lag in range(2, n):
        idx = np.arange(lag, n)            # target i, source i - lag
        src = idx - lag
        act = active[idx] & active[src]
        s0 = S[0][idx, src]
        sm = Smf[idx, src]
        cm = (s0 >= thr_c) & act
        for st, ln in _stretches(cm, min_run):
            sub = (sm[st:st + ln] >= thr_m)
            ex = _stretches(sub, min_run)
            covered = np.zeros(ln, bool)
            for a, b in ex:
                covered[a:a + b] = True
                runs.append({"t": int(idx[st + a]), "s": int(src[st + a]), "len": b, "kind": "exact", "transpose": 0,
                             "sim": float(s0[st + a:st + a + b].mean()), "mfcc": float(sm[st + a:st + a + b].mean())})
            for a, b in _stretches(~covered, 1):
                if True:  # part of a chroma run of >= min_run measures
                    runs.append({"t": int(idx[st + a]), "s": int(src[st + a]), "len": b, "kind": "harmony",
                                 "transpose": 0, "sim": float(s0[st + a:st + a + b].mean()),
                                 "mfcc": float(sm[st + a:st + a + b].mean())})
        for k in range(1, 12):
            sk = S[k][idx, src]
            tm = (sk >= thr_c) & (sk >= s0 + 0.05) & act
            for st, ln in _stretches(tm, min_run):
                runs.append({"t": int(idx[st]), "s": int(src[st]), "len": ln, "kind": "transposed", "transpose": k,
                             "sim": float(sk[st:st + ln].mean()), "mfcc": float(sm[st:st + ln].mean())})
    return runs


def copy_map(runs, n, source_lo=None, source_hi=None):
    """Best source per target measure, then merged entries (0-based in, 0-based out)."""
    best = [None] * n
    for ru in runs:
        for q in range(ru["len"]):
            t, s = ru["t"] + q, ru["s"] + q
            if source_lo is not None and not (source_lo <= s <= source_hi):
                continue
            if source_lo is not None and source_lo <= t <= source_hi:
                continue
            key = (KIND_ORDER[ru["kind"]], -ru["len"], -ru["sim"], s)
            if best[t] is None or key < best[t][0]:
                best[t] = (key, s, ru)
    entries = []
    for t in range(n):
        if best[t] is None:
            continue
        _, s, ru = best[t]
        e = entries[-1] if entries else None
        if (e is not None and e["t1"] == t - 1 and e["s1"] == s - 1 and e["kind"] == ru["kind"]
                and e["transpose"] == ru["transpose"]):
            e["t1"], e["s1"] = t, s
            e["sims"].append(ru["sim"])
        else:
            entries.append({"t0": t, "t1": t, "s0": s, "s1": s, "kind": ru["kind"], "transpose": ru["transpose"],
                            "sims": [ru["sim"]]})
    return entries


def analyse_structure(paths: list[Path], bpm: float, first: float, num: int, den: int, measure_offset: int,
                      n_measures: int | None, min_run: int, thresholds, source_measures) -> dict:
    import numpy as np

    beat_sec = (4.0 / den) * 60.0 / bpm
    F = features(paths, bpm, first, num, beat_sec, n_measures)
    S, Smf, active, n = F["S"], F["Smf"], F["active"], F["n"]
    if thresholds is None:
        thr_c, thr_m, dist = auto_thresholds(S[0], Smf, active)
        mode = "auto"
    else:
        thr_c, thr_m = thresholds
        _, _, dist = auto_thresholds(S[0], Smf, active)
        mode = "given"
    runs = find_runs(S, Smf, active, thr_c, thr_m, min_run)
    off = measure_offset
    lo = hi = None
    if source_measures is not None:
        lo, hi = source_measures[0] - off, source_measures[1] - off
    ent = copy_map(runs, n, lo, hi)

    def m(i):
        return int(i + off)

    runs_out = sorted(({"target": [m(x["t"]), m(x["t"] + x["len"] - 1)], "source": [m(x["s"]), m(x["s"] + x["len"] - 1)],
                        "kind": x["kind"], "transpose": x["transpose"], "measures": x["len"],
                        "similarity": r(x["sim"], 3), "mfcc": r(x["mfcc"], 3)} for x in runs),
                      key=lambda x: (KIND_ORDER[x["kind"]], -x["measures"], x["target"][0]))
    cmap = [{"target": [m(e["t0"]), m(e["t1"])], "source": [m(e["s0"]), m(e["s1"])], "kind": e["kind"],
             "transpose": e["transpose"], "measures": e["t1"] - e["t0"] + 1,
             "similarity": r(float(np.mean(e["sims"])), 3)} for e in ent]
    covered = {k: sum(e["measures"] for e in cmap if e["kind"] == k) for k in KIND_ORDER}
    # single-measure matches (any lag, any transposition) for measures the copy map leaves open: loops shorter than
    # min_run (e.g. a one-bar riff repeated) and transposed fragments do not form a diagonal run
    in_map = {e["target"][0] + q for e in cmap for q in range(e["measures"])}
    hints = []
    for i in range(n):
        if m(i) in in_map or not active[i] or (lo is not None and lo <= i <= hi):
            continue
        cand = None
        for k in range(12):
            for j in range(n):
                if abs(i - j) < 2 or not active[j]:
                    continue
                if lo is not None and not (lo <= j <= hi):
                    continue
                v = float(S[k][i, j])
                if v < thr_c or (k and v < float(S[0][i, j]) + 0.05):
                    continue
                key = (v - (0.0 if k == 0 else 0.02), -abs(i - j))
                if cand is None or key > cand[0]:
                    cand = (key, j, k, v)
        if cand is not None:
            hints.append({"measure": m(i), "source": m(cand[1]), "transpose": cand[2], "similarity": r(cand[3], 3),
                          "earlier": cand[1] < i})
    per_measure = []
    srcof = {}
    for e in cmap:
        for q in range(e["measures"]):
            srcof[e["target"][0] + q] = (e["source"][0] + q, e["kind"], e["transpose"])
    for i in range(n):
        s = srcof.get(m(i))
        per_measure.append({"measure": m(i), "active": bool(active[i]),
                            "source": s[0] if s else None, "kind": s[1] if s else None,
                            "transpose": s[2] if s else None})
    return {"grid": {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                     "measure_offset": off, "measures": n, "measures_available": F["available"],
                     "measure_sec": r(num * beat_sec, 4), "duration_sec": r(F["duration_sec"], 2)},
            "features": {"chroma": F["chroma_source"], "files": [str(p) for p in paths], "sr": SR, "hop": HOP},
            "thresholds": {"mode": mode, "chroma": r(thr_c, 3), "mfcc": r(thr_m, 3), "min_run_measures": min_run,
                           "distribution": dist},
            "source_measures": source_measures,
            "copy_map": cmap, "measure_hints": hints, "runs": runs_out[:400], "runs_total": len(runs_out),
            "coverage": {"measures": n, "active": int(active.sum()), "copy_target_measures": len(srcof),
                         "copy_target_pct": r(100 * len(srcof) / max(1, n), 1), "by_kind": covered},
            "per_measure": per_measure, "limits": STRUCTURE_NOTES}
