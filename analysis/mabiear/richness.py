"""풍성함(richness_compare): 렌더가 원곡보다 얼마나 '비어' 들리는지를 마디별 숫자로.

마디마다
 (a) 음 시작(온셋) 수: 렌더 마디 [start, end) 안의 온셋 수와, 정렬표(align_json)의 원곡 마디 구간 안 온셋 수.
     검출은 onset.detect(librosa.onset.onset_detect, backtrack=False, hop 512)를 그대로 쓴다.
     onset_ratio = 렌더 / 원곡(원곡이 0이면 null).
 (b) 저·중·고 대역 에너지(dB): 마디 구간의 Welch 파워 스펙트럼을 low_hz(기본 250Hz)·high_hz(기본 2000Hz)에서
     나눠 대역마다 평균 제곱 세기(dB). band_db_diff = 렌더 - 원곡.
     원곡 녹음은 마스터링으로 전체가 크게 들어가 있어 그 차이가 모든 대역에 얹힌다. 그래서 구간 전체의
     세기 차(level_offset_db = 모든 마디 전대역 차의 중앙값)를 뺀 band_db_rel(대역 모양 차)도 함께 적는다.
 (c) 요약: 마디 평균 onset_ratio, 전체 온셋 합 비율, 대역별 평균 차, 가장 '빈' 마디 top_n
     (onset_ratio 오름차순, 같으면 대역 모양 차 평균이 낮은 순).

원곡 녹음은 드럼·보컬이 있지만 마스터링으로 빽빽해 온셋이 덜 잡히기도 해서 onset_ratio 가 1을 넘을 수 있다
(실측 v3 렌더 1.45, v2 0.87). 절대값보다 마디 사이 순위와 버전 사이 변화를 본다.
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from . import io
from .chroma import HOP, orig_window
from .io import EarError, r
from .onset import detect

log = logging.getLogger("mabiear.richness")

BAND_NAMES = ("low", "mid", "high")
NPERSEG = 2048


def band_db(y: np.ndarray, sr: int, t0: float, t1: float, edges: tuple[float, float]) -> dict[str, float]:
    """구간 [t0, t1) 의 대역별 평균 제곱 세기(dB, 사인파 진폭 1 = -3dB). 대역 = [0, lo), [lo, hi), [hi, sr/2]."""
    from scipy.signal import welch

    a, b = max(0, int(t0 * sr)), max(0, int(t1 * sr))
    s = y[a:max(a + 1, b)].astype(np.float64)
    if s.size < 16:
        return {k: -120.0 for k in BAND_NAMES}
    f, p = welch(s, fs=sr, nperseg=min(NPERSEG, s.size), scaling="density")
    df = f[1] - f[0] if f.size > 1 else 1.0
    lo, hi = edges
    sel = {"low": f < lo, "mid": (f >= lo) & (f < hi), "high": f >= hi}
    return {k: io.db(float(np.sqrt(max(0.0, p[m].sum() * df)))) for k, m in sel.items()}


def run_richness_compare(render_wav: Path, original: Path, align: dict, measures: list[dict], low_hz: float,
                         high_hz: float, top_n: int) -> dict:
    amap = {int(m["measure"]): m for m in align["measures"]}
    use = [m for m in measures if m["measure"] in amap]
    if not use:
        raise EarError("BAD_ARGUMENT", "align_json에 고른 마디가 없습니다.",
                       {"measures": [m["measure"] for m in measures][:20]})
    edges = (low_hz, high_hz)
    yr, sr = io.load_mono(render_wav)
    lo, hi = orig_window(amap, use)
    yo, _ = io.load_mono(original, offset=lo, duration=hi - lo)
    on_r, _, _ = detect(yr, sr)
    on_o, _, _ = detect(yo, sr)
    on_o = on_o + lo  # 원곡 절대 초
    rows = []
    for m in use:
        am = amap[m["measure"]]
        s, e = m["start_sec"], m["end_sec"]
        o0, o1 = float(am["orig_start_sec"]), float(am["orig_end_sec"])
        nr = int(np.sum((on_r >= s) & (on_r < e)))
        no = int(np.sum((on_o >= o0) & (on_o < o1)))
        br = band_db(yr, sr, s, e, edges)
        bo = band_db(yo, sr, o0 - lo, o1 - lo, edges)
        full_r = io.db(float(np.sqrt(sum(10 ** (v / 10) for v in br.values()))))
        full_o = io.db(float(np.sqrt(sum(10 ** (v / 10) for v in bo.values()))))
        rows.append({"measure": m["measure"], "render_onsets": nr, "orig_onsets": no,
                     "onset_ratio": r(nr / no, 3) if no else None,
                     "render_band_db": {k: r(v, 2) for k, v in br.items()},
                     "orig_band_db": {k: r(v, 2) for k, v in bo.items()},
                     "band_db_diff": {k: r(br[k] - bo[k], 2) for k in BAND_NAMES},
                     "full_db_diff": r(full_r - full_o, 2),
                     "render_sec": [r(s), r(e)], "orig_sec": [r(o0), r(o1)]})
    offset = float(np.median([x["full_db_diff"] for x in rows]))
    for x in rows:
        x["band_db_rel"] = {k: r(x["band_db_diff"][k] - offset, 2) for k in BAND_NAMES}
    ratios = [x["onset_ratio"] for x in rows if x["onset_ratio"] is not None]
    tot_r = sum(x["render_onsets"] for x in rows)
    tot_o = sum(x["orig_onsets"] for x in rows)

    def key(x):
        ratio = x["onset_ratio"] if x["onset_ratio"] is not None else float("inf")
        return ratio, float(np.mean(list(x["band_db_rel"].values())))

    ranked = sorted(rows, key=key)
    emptiest = [{"measure": x["measure"], "onset_ratio": x["onset_ratio"], "render_onsets": x["render_onsets"],
                 "orig_onsets": x["orig_onsets"], "band_db_rel": x["band_db_rel"]} for x in ranked[:top_n]]
    band_mean = {k: r(float(np.mean([x["band_db_diff"][k] for x in rows])), 2) for k in BAND_NAMES}
    band_rel_mean = {k: r(float(np.mean([x["band_db_rel"][k] for x in rows])), 2) for k in BAND_NAMES}
    summary = {"measures": len(rows), "mean_onset_ratio": r(float(np.mean(ratios)), 3) if ratios else None,
               "total_onset_ratio": r(tot_r / tot_o, 3) if tot_o else None,
               "render_onsets": tot_r, "orig_onsets": tot_o,
               "level_offset_db": r(offset, 2), "band_db_diff_mean": band_mean, "band_db_rel_mean": band_rel_mean,
               "emptiest": emptiest}
    return {"measures": rows, "summary": summary, "emptiest": emptiest,
            "params": {"onset_detector": "librosa.onset.onset_detect(backtrack=False) (onset.detect 재사용)",
                       "hop": HOP, "sr": sr, "mono": True,
                       "bands_hz": {"low": [0, low_hz], "mid": [low_hz, high_hz], "high": [high_hz, sr / 2]},
                       "band_energy": f"scipy.signal.welch(nperseg={NPERSEG}) 대역 합, 평균 제곱 dB(사인파 진폭 1 = -3dB)",
                       "onset_ratio": "렌더 마디 온셋 수 / 원곡 마디(정렬표 구간) 온셋 수",
                       "band_db_diff": "렌더 - 원곡(dB)",
                       "band_db_rel": "band_db_diff - level_offset_db(구간 전체 세기 차를 뺀 대역 모양 차)",
                       "emptiest_rank": "onset_ratio 오름차순, 같으면 band_db_rel 평균 오름차순",
                       "orig_window_sec": [r(lo), r(hi)]}}
