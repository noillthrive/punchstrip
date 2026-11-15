"""downbeat_check: which barline phase carries the downbeat (kick evidence on a drum stem or a mix).

Method
1. Onset curve. band "low" (default, kick): 4th-order Butterworth low-pass at 150 Hz (zero phase), RMS envelope
   (frame 512 = 23 ms, hop 64 = 2.9 ms at 22.05 kHz), curve = positive rise of the envelope over 3 hops (8.7 ms).
   band "full": librosa spectral-flux onset strength (n_fft 2048, hop 128) on the whole signal. Divided by p99.5.
   (A mel-flux restricted to < 300 Hz was tried first: on a real drum stem its peaks were spread evenly over all
   16th positions, i.e. it did not isolate the kick.)
2. Peaks: librosa.util.peak_pick (±30 ms local max, ±100 ms mean + delta 0.05, at least 70 ms apart). "Strong" peaks
   are those with strength ≥ 0.5 × the 90th percentile of all peak strengths.
3. For each candidate measure-1 time c the predicted downbeats are c + k·measure_sec for every integer k inside the
   audio. Only "active" downbeats count: the measure starting there has RMS within 30 dB of the loud level (95th
   percentile of 50 ms frames). hits = strong peaks within ±window of a predicted downbeat; downbeats_hit = active
   downbeats with ≥ 1 strong peak in the window; share = downbeats_hit / active downbeats. The same on beats 1 and 3
   (beats_13_*, beats_per_measure ≥ 3) and on every beat (beat_share: is c on the beat grid at all?).
   median_offset_ms = median (peak − predicted downbeat) of the hits.
4. Baseline: share at 64 evenly spaced phases across one measure (mean and 90th percentile).
5. best = highest share (ties: more hits, then higher mean strength). Kick evidence cannot tell beat 1 from beat 3
   (kick on 1 and 3), so the candidate half a measure away ("half_measure_twin") is reported separately and is not
   used as "second". confidence: high = best share ≥ 0.8 and ≥ 1.5 × second; medium = ≥ 1.25 × second and
   ≥ 1.5 × baseline mean; else low. beat_phase compares the best phase with the phase half a beat later (on- vs
   off-beat), which stays informative when the downbeat itself is ambiguous (four-on-the-floor kick).
"""
from __future__ import annotations

import math
from pathlib import Path

from . import io
from .io import EarError, r

SCHEMA_DOWNBEAT = "mabiear.downbeat/1"
BANDS = ("low", "full")
STEPS = {"beat": 1.0, "eighth": 0.5, "sixteenth": 0.25}
HOP_LOW = 64  # 2.9 ms at 22.05 kHz
LAG_LOW = 3  # rise over 8.7 ms
HOP_FULL = 128
LOW_HZ = 150.0
STRONG_REL = 0.5


def check_search(search, beats_per_measure: int) -> dict:
    if not isinstance(search, dict) or set(search) - {"around_sec", "step"} or "around_sec" not in search:
        raise EarError("BAD_ARGUMENT", "'search'는 {\"around_sec\": 숫자, \"step\": \"eighth\"|\"sixteenth\"|\"beat\"}입니다.",
                       {"search": search})
    a = search["around_sec"]
    if isinstance(a, bool) or not isinstance(a, (int, float)) or not (-36000 <= a <= 36000):
        raise EarError("BAD_ARGUMENT", "search.around_sec는 숫자입니다.", {"search": search})
    st = search.get("step", "eighth")
    if st not in STEPS:
        raise EarError("BAD_ARGUMENT", "search.step은 'eighth', 'sixteenth', 'beat' 중 하나입니다.", {"search": search})
    return {"around_sec": float(a), "step": st}


def search_candidates(around: float, step: str, bpm: float, beats: int) -> list[float]:
    q = 60.0 / bpm
    st = q * STEPS[step]
    n = int(round(beats / STEPS[step]))
    return [around + j * st for j in range(-(n // 2), n - n // 2)]


def onset_curve(y, sr: int, band: str):
    """(curve, hop). low: rise of the RMS envelope below 150 Hz; full: librosa spectral flux. Divided by p99.5."""
    import librosa
    import numpy as np
    from scipy.signal import butter, sosfiltfilt

    if band == "low":
        sos = butter(4, LOW_HZ, btype="lowpass", fs=sr, output="sos")
        yl = sosfiltfilt(sos, y.astype(np.float64))
        a = librosa.feature.rms(y=yl, frame_length=512, hop_length=HOP_LOW, center=True)[0]
        env = np.concatenate([np.zeros(LAG_LOW), np.maximum(0.0, a[LAG_LOW:] - a[:-LAG_LOW])])
        hop = HOP_LOW
    else:
        env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=HOP_FULL, n_fft=2048)
        hop = HOP_FULL
    scale = float(np.percentile(env, 99.5)) if env.size else 0.0
    return (env / scale if scale > 1e-12 else env), hop


def pick_peaks(env, sr: int, hop: int):
    import librosa
    import numpy as np

    fr = sr / hop
    w = lambda ms: max(1, int(round(ms / 1000 * fr)))  # noqa: E731
    idx = librosa.util.peak_pick(env, pre_max=w(30), post_max=w(30), pre_avg=w(100), post_avg=w(100), delta=0.05,
                                 wait=w(70))
    t = idx * hop / sr
    s = env[idx] if idx.size else np.zeros(0)
    return t, s


def _active(y, sr: int, starts, ml: float, dur: float):
    import numpy as np

    hop = int(0.05 * sr)
    if y.size < hop:
        return np.ones(len(starts), dtype=bool)
    fr = np.sqrt(np.mean(y[: y.size // hop * hop].reshape(-1, hop).astype(np.float64) ** 2, axis=1))
    loud = float(np.percentile(fr, 95))
    thr = loud * 10 ** (-30 / 20)
    out = []
    for s in starts:
        a, b = max(0, int(s / 0.05)), min(fr.size, int(math.ceil(min(dur, s + ml) / 0.05)))
        out.append(b > a and float(np.sqrt(np.mean(fr[a:b] ** 2))) >= thr)
    return np.array(out, dtype=bool)


def _grid(c: float, period: float, dur: float):
    import numpy as np

    k0 = math.ceil((0.0 - c) / period - 1e-9)
    k1 = math.floor((dur - c) / period + 1e-9)
    return c + np.arange(k0, k1 + 1) * period if k1 >= k0 else np.zeros(0)


def score_phase(c: float, ml: float, beats: int, q: float, peaks_t, peaks_s, y, sr, dur, win: float,
                act_cache: dict) -> dict:
    import numpy as np

    D = _grid(c, ml, dur)
    key = (round((c % ml), 6), "m")
    if key not in act_cache:
        act_cache[key] = _active(y, sr, D, ml, dur)
    act = act_cache[key]
    if peaks_t.size and D.size:
        dist = peaks_t[:, None] - D[None, :]
        near = np.abs(dist) <= win
        hit_peak = near.any(axis=1)
        db_hit = near.any(axis=0)
        offs = [float(dist[i][near[i]][np.argmin(np.abs(dist[i][near[i]]))]) for i in np.where(hit_peak)[0]]
    else:
        hit_peak, db_hit, offs = np.zeros(peaks_t.size, bool), np.zeros(D.size, bool), []
    n_act = int(act.sum())
    rec = {"measure1_sec": r(c, 4), "phase_sec": r(c % ml, 4), "hits": int(hit_peak.sum()),
           "downbeats_active": n_act, "downbeats_hit": int((db_hit & act).sum()),
           "share": r(float((db_hit & act).sum()) / n_act, 3) if n_act else None,
           "median_offset_ms": r(1000 * float(np.median(offs)), 1) if offs else None,
           "mean_strength": r(float(peaks_s[hit_peak].mean()), 3) if hit_peak.any() else None}
    B = _grid(c, q, dur)
    kb = (round((c % q), 6), "b")
    if kb not in act_cache:
        act_cache[kb] = _active(y, sr, B, q, dur)
    actb = act_cache[kb]
    if peaks_t.size and B.size:
        bh = (np.abs(peaks_t[:, None] - B[None, :]) <= win).any(axis=0)
        rec["beat_share"] = r(float((bh & actb).sum()) / int(actb.sum()), 3) if actb.sum() else None
    else:
        rec["beat_share"] = None
    if beats >= 3:
        P = np.concatenate([D, D + 2 * q])
        act13 = np.concatenate([act, act]) & (P <= dur)
        if peaks_t.size and P.size:
            n13 = np.abs(peaks_t[:, None] - P[None, :]) <= win
            rec["beats_13_hits"] = int(n13.any(axis=1).sum())
            n_a = int(act13.sum())
            rec["beats_13_share"] = r(float((n13.any(axis=0) & act13).sum()) / n_a, 3) if n_a else None
        else:
            rec["beats_13_hits"], rec["beats_13_share"] = 0, None
    return rec


def analyse_downbeat(path: Path, bpm: float, beats: int, candidates: list[float] | None, search: dict | None,
                     window_ms: float, band: str) -> dict:
    import numpy as np

    y, sr = io.load_mono(path)
    dur = y.size / sr
    q = 60.0 / bpm
    ml = q * beats
    win = window_ms / 1000.0
    if search is not None:
        cands = search_candidates(search["around_sec"], search["step"], bpm, beats)
    else:
        cands = list(candidates)
    env, hop = onset_curve(y, sr, band)
    pt, ps = pick_peaks(env, sr, hop)
    strong_thr = STRONG_REL * float(np.percentile(ps, 90)) if ps.size else 0.0
    sel = ps >= strong_thr
    st, ss = pt[sel], ps[sel]
    cache: dict = {}
    rows = [score_phase(c, ml, beats, q, st, ss, y, sr, dur, win, cache) for c in cands]
    for row, c in zip(rows, cands):  # all peaks (not only strong), for reference
        row["hits_all_peaks"] = int((np.abs(((pt - c + ml / 2) % ml) - ml / 2) <= win).sum()) if pt.size else 0
    base = [score_phase(c, ml, beats, q, st, ss, y, sr, dur, win, cache)["share"] or 0.0
            for c in (cands[0] + ml * j / 64 for j in range(64))] if cands else [0.0]
    b_mean, b_p90 = float(np.mean(base)), float(np.percentile(base, 90))

    def key(i):
        x = rows[i]
        return (x["share"] or 0.0, x["hits"], x["mean_strength"] or 0.0)

    order = sorted(range(len(rows)), key=key, reverse=True)
    bi = order[0]
    best = rows[bi]
    cb = cands[bi]

    def is_twin(c):
        d = abs(((c - cb) % ml) - ml / 2)
        return beats % 2 == 0 and d <= win

    rest = [i for i in order[1:] if not is_twin(cands[i])]
    second = rows[rest[0]] if rest else None
    twin = score_phase(cb + ml / 2, ml, beats, q, st, ss, y, sr, dur, win, cache) if beats % 2 == 0 else None
    bs = best["share"] or 0.0
    ss2 = (second["share"] or 0.0) if second else 0.0
    if bs >= 0.8 and bs >= 1.5 * ss2:
        conf = "high"
    elif bs >= 1.25 * ss2 and bs >= 1.5 * b_mean:
        conf = "medium"
    else:
        conf = "low"
    step16 = q / 4
    nb = int(round(ml / step16))
    hist = np.zeros(nb, dtype=int)
    if st.size:
        k = np.round(((st - cb) % ml) / step16).astype(int) % nb
        np.add.at(hist, k, 1)
    phase_hist = {"step": "sixteenth", "origin_sec": r(cb, 4), "counts": hist.tolist(),
                  "text": "best 위상에서 16분음표 칸마다 센 강한 피크 수(0 = 1박, 4 = 2박, …)"}
    off = score_phase(cb + q / 2, ml, beats, q, st, ss, y, sr, dur, win, cache)
    bsh, osh = best.get("beat_share") or 0.0, off.get("beat_share") or 0.0
    beat_phase = {"phase_sec": r(cb % q, 4), "beat_share": r(bsh, 3), "offbeat_phase_sec": r((cb + q / 2) % q, 4),
                  "offbeat_share": r(osh, 3), "on_vs_off": r(bsh / osh, 2) if osh > 0 else None}
    twin_close = twin is not None and (twin["share"] or 0.0) >= 0.8 * bs
    notes = []
    if twin_close:
        notes.append(f"반 마디 뒤 위상({twin['measure1_sec']}초)도 share {twin['share']}로 비슷하다: 킥만으로는 1박과 3박을 "
                     "가르지 못한다(크래시·곡 구조·노래 시작으로 확인).")
    return {"audio": {"path": str(path), "duration_sec": r(dur, 3), "sr": sr},
            "grid": {"bpm": bpm, "beats_per_measure": beats, "measure_sec": r(ml, 4), "window_ms": window_ms,
                     "band": band, "mode": "search" if search is not None else "candidates",
                     "search": search},
            "peaks": {"all": int(pt.size), "strong": int(st.size), "strong_threshold": r(strong_thr, 3)},
            "candidates": rows, "best": best, "second": second, "half_measure_twin": twin,
            "beat_phase": beat_phase, "phase_histogram": phase_hist,
            "baseline": {"phases": 64, "mean_share": r(b_mean, 3), "p90_share": r(b_p90, 3)},
            "confidence": conf, "notes": notes,
            "method": ("onset curve (low: rise of the RMS envelope below 150 Hz; full: spectral flux), peak_pick, strong = ≥ 0.5 × p90 "
                       "peak strength; share = active downbeats with a strong peak within ±window / active downbeats; "
                       "baseline = 64 evenly spaced phases; second excludes the half-measure twin")}
