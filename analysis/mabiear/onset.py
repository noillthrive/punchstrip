"""온셋(음이 시작하는 순간) 비교(onset_compare).

두 쪽 온셋을 librosa.onset.onset_detect(backtrack=False, hop 512)로 구하고, 원곡 온셋을
정렬표(align_json 의 경로)로 렌더 시각으로 옮긴 뒤 마디마다 허용 오차(기본 50ms) 안에서
1:1로 짝지어 정밀도·재현율·F-점수를 낸다. 온셋 강도 곡선의 마디별 상관도 함께 적는다.
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from . import io
from .align import orig_to_render
from .chroma import HOP, orig_window
from .io import EarError, r

log = logging.getLogger("mabiear.onset")


def detect(y: np.ndarray, sr: int):
    import librosa

    env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=HOP)
    on = librosa.onset.onset_detect(onset_envelope=env, sr=sr, hop_length=HOP, backtrack=False, units="time")
    times = librosa.times_like(env, sr=sr, hop_length=HOP)
    return np.asarray(on, dtype=float), env.astype(float), times


def match(a: np.ndarray, b: np.ndarray, tol: float) -> list[tuple[int, int]]:
    """가까운 쌍부터 1:1로 짝짓기(허용 오차 tol 초 안)."""
    pairs = []
    for i, x in enumerate(a):
        for j, y in enumerate(b):
            d = abs(x - y)
            if d <= tol:
                pairs.append((d, i, j))
    pairs.sort()
    ua, ub, out = set(), set(), []
    for _, i, j in pairs:
        if i not in ua and j not in ub:
            ua.add(i)
            ub.add(j)
            out.append((i, j))
    return out


def run_onset_compare(render_wav: Path, original: Path, align: dict, measures: list[dict],
                      tol_ms: float, top_n: int) -> dict:
    amap = {int(m["measure"]): m for m in align["measures"]}
    use = [m for m in measures if m["measure"] in amap]
    if not use:
        raise EarError("BAD_ARGUMENT", "align_json에 고른 마디가 없습니다.", {})
    tol = tol_ms / 1000.0
    yr, sr = io.load_mono(render_wav)
    lo, hi = orig_window(amap, use)
    yo, _ = io.load_mono(original, offset=lo, duration=hi - lo)
    on_r, env_r, t_r = detect(yr, sr)
    on_o, env_o, t_o = detect(yo, sr)
    on_o_abs = on_o + lo
    on_o_r = orig_to_render(align, on_o_abs)  # 원곡 온셋을 렌더 시각으로
    rows, misbeats = [], []
    for m in use:
        s, e = m["start_sec"], m["end_sec"]
        R = on_r[(on_r >= s) & (on_r < e)]
        oi = np.nonzero((on_o_r >= s) & (on_o_r < e))[0]
        O = on_o_r[oi]
        pairs = match(R, O, tol)
        nm = len(pairs)
        prec = nm / len(R) if len(R) else None
        rec = nm / len(O) if len(O) else None
        if len(R) == 0 and len(O) == 0:
            f = None
        elif prec is None or rec is None or (prec + rec) == 0:
            f = 0.0
        else:
            f = 2 * prec * rec / (prec + rec)
        mr = {i for i, _ in pairs}
        mo = {j for _, j in pairs}
        un_r = [float(R[i]) for i in range(len(R)) if i not in mr]
        un_o = [float(O[j]) for j in range(len(O)) if j not in mo]
        # 박별로 어긋난 온셋 모으기
        segs = io.beat_segments(m)
        for bi, (b0, b1) in enumerate(segs, start=1):
            ro = [t for t in un_o if b0 <= t < b1]
            rr = [t for t in un_r if b0 <= t < b1]
            if ro or rr:
                misbeats.append({"measure": m["measure"], "beat": bi,
                                 "orig_only_render_sec": [r(t) for t in ro],
                                 "render_only_sec": [r(t) for t in rr]})
        # 온셋 강도 곡선 상관(원곡 곡선을 렌더 시각으로 옮겨 같은 격자에서)
        grid = np.arange(s, e, HOP / float(sr))
        corr = None
        if grid.size >= 4:
            er = np.interp(grid, t_r, env_r)
            am = amap[m["measure"]]
            og = np.interp(grid, [s, e], [am["orig_start_sec"], am["orig_end_sec"]]) - lo
            eo = np.interp(og, t_o, env_o)
            if er.std() > 1e-9 and eo.std() > 1e-9:
                corr = float(np.corrcoef(er, eo)[0, 1])
        rows.append({"measure": m["measure"], "f": r(f, 4), "precision": r(prec, 4), "recall": r(rec, 4),
                     "render_onsets": int(len(R)), "orig_onsets": int(len(O)), "matched": nm,
                     "envelope_corr": r(corr, 4),
                     "render_only_sec": [r(t) for t in un_r], "orig_only_render_sec": [r(t) for t in un_o]})
    scored = [x for x in rows if x["f"] is not None]
    ranked = sorted(scored, key=lambda x: x["f"])
    mean_f = float(np.mean([x["f"] for x in scored])) if scored else None
    return {"measures": rows, "mean_f": r(mean_f, 4),
            "bottom": [{"measure": x["measure"], "f": x["f"], "render_onsets": x["render_onsets"],
                        "orig_onsets": x["orig_onsets"]} for x in ranked[:top_n]],
            "mismatched_beats": misbeats,
            "params": {"detector": "librosa.onset.onset_detect(backtrack=False)", "hop": HOP, "sr": sr,
                       "tolerance_ms": tol_ms, "matching": "가까운 쌍부터 1:1",
                       "times": "모든 시각은 렌더 기준 초(원곡 온셋은 정렬 경로로 옮김)",
                       "orig_window_sec": [r(lo), r(hi)]}}
