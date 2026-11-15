"""렌더(마디 표 있음)와 원곡 녹음의 크로마 DTW 정렬.

기본(tempo_ratio_range=(0.9, 1.1)): 비율을 그 범위로 묶는다.
  A) global_search: 비율 격자 x 모든 오프셋 전수 탐색(FFT 상호상관)으로 전역 직선(a, b)
  B) refine_linear: 범위 안에서 warp_score 를 2단 격자로 최대화(오프셋은 마디 표 가운데 기준)
  C) banded_dtw: 그 직선 주위 ±1초 띠 안에서만 DTW 경로 -> 아래 6)의 마디·박 옮기기에 쓴다
  비율이 범위 끝에 걸리거나 다른 위치 점수가 거의 같으면 confidence.warnings 에 적는다.
tempo_ratio_range=None: 아래 1)~5) 예전 자유 DTW(드럼 없는 성긴 렌더에서 경로가 접혀 비율을 틀릴 수 있다).

1) 두 쪽 크로마(chroma_cqt, hop 512, 22050Hz 모노)
2) 프레임 수가 3000을 넘으면 두 쪽을 같은 배수 k로 시간 방향 평균해 줄인다(DTW 행렬 3000x3000 이하)
3) librosa.sequence.dtw(metric='cosine', subseq=True): 렌더가 원곡의 어느 구간인지 찾는다
4) k>1이었으면 찾은 구간 앞뒤 3초만 다시 더 촘촘하게 DTW(정밀화)
5) 경로를 직선 적합(원곡 시각 = a + b x 렌더 시각) -> 시작 오프셋 a, 템포 비율 b
   이어서 직선 변형 유사도(렌더 크로마와, 직선으로 옮긴 원곡 크로마의 평균 코사인)가 가장 큰
   (a, b)를 경로 적합값 근처에서 찾아 더 좋으면 그 값을 쓴다(일정한 화음이 길게 이어지면
   DTW 경로가 블록 안에서 계단처럼 흔들려 직선 적합이 치우치는 것을 바로잡기 위함).
6) 마디·박 경계는 DTW 경로(mapping='path') 또는 직선(mapping='linear')으로 원곡 시각으로 옮긴다.
   경로는 직선과의 편차를 1초 이동 중앙값으로 다듬어 쓴다(경로 끝의 CQT 가장자리 효과 제거).
   저장하는 path 도 다듬은 경로다(직선을 쓰면 직선 위 점).
7) mapping='auto'(기본): 마디·박 경계마다 경로와 직선의 편차 |path - line|(원곡 초)를 재서
   중앙값 <= auto_median_beats 박이고, 최대(1마디 폭 이동 중앙값으로 튀는 점을 누른 뒤) <= auto_max_beats 박이면
   직선, 아니면 경로를 쓴다(path_deviation_decision). 템포가 고정된 곡에서 DTW 경로가 마디 경계를 끌어당기는
   것을 막고, 원곡 템포가 실제로 바뀌는 곳에서만 경로를 쓴다.
"""
from __future__ import annotations

import logging
import math
import time
from pathlib import Path

import numpy as np

from . import io
from .chroma import Chroma, HOP, for_file
from .io import EarError, r

log = logging.getLogger("mabiear.align")

MAX_FRAMES = 3000
REFINE_MARGIN_SEC = 3.0
SMOOTH_SEC = 1.0  # 경로 편차 이동 중앙값 폭
MIN_SEC = 0.5     # 이보다 짧은 오디오는 정렬하지 않는다
SCHEMA = "mabiear.align/1"
MAPPINGS = ("auto", "path", "linear")
AUTO_MEDIAN_BEATS = 0.5  # auto: 경계 편차 중앙값 기준(박)
AUTO_MAX_BEATS = 1.0     # auto: 경계 편차 최대 기준(박, 1마디 이동 중앙값 뒤)
EDGE_SEC = 1.0           # auto 편차 통계에서 뺄 경로 양 끝 폭(CQT 가장자리 효과, SMOOTH_SEC 와 같은 폭)
AUTO_SUSTAIN_MEASURES = 2  # auto: 넘는 경계가 이 마디 수(x 마디당 박 수 경계) 이상 이어지면 '튐'이 아님
AUTO_P90_BEATS = 0.4       # auto: 경계 편차의 90번째 백분위가 이 박 수를 넘으면 편차가 퍼진 것(템포가 바뀜)으로 봄


def group(c: np.ndarray, k: int) -> np.ndarray:
    if k <= 1:
        return c
    n = c.shape[1] // k * k
    rest = c.shape[1] - n
    g = c[:, :n].reshape(12, -1, k).mean(axis=2)
    if rest:
        g = np.concatenate([g, c[:, n:].mean(axis=1, keepdims=True)], axis=1)
    nv = np.linalg.norm(g, axis=0)
    nv[nv < 1e-12] = 1.0
    return g / nv


def group_times(times: np.ndarray, k: int) -> np.ndarray:
    if k <= 1:
        return times
    n = times.size // k * k
    t = times[:n].reshape(-1, k).mean(axis=1)
    if times.size > n:
        t = np.concatenate([t, [times[n:].mean()]])
    return t


def dtw(X: np.ndarray, Y: np.ndarray):
    import librosa

    subseq = X.shape[1] <= Y.shape[1]
    D, wp = librosa.sequence.dtw(X=X, Y=Y, metric="cosine", subseq=subseq, backtrack=True)
    wp = np.asarray(wp)[::-1]
    # 경로 비용 = 경로가 지나는 칸의 코사인 거리 평균
    cost = 1.0 - np.sum(X[:, wp[:, 0]] * Y[:, wp[:, 1]], axis=0)
    return wp, cost, subseq


def baseline_cost(X: np.ndarray, Y: np.ndarray) -> float:
    """아무렇게나 맞췄을 때의 평균 코사인 거리(신뢰도 비교용). 큰 행렬은 표본으로."""
    rng = np.random.default_rng(0)
    xi = rng.integers(0, X.shape[1], size=min(4000, X.shape[1] * 4))
    yi = rng.integers(0, Y.shape[1], size=xi.size)
    return float(np.mean(1.0 - np.sum(X[:, xi] * Y[:, yi], axis=0)))


def dedupe(x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """x 가 같은 점들은 y 평균으로 합친다(x 오름차순)."""
    ux, inv = np.unique(x, return_inverse=True)
    sums = np.bincount(inv, weights=y)
    cnt = np.bincount(inv)
    return ux, sums / cnt


def warp_score(Xr: np.ndarray, tr: np.ndarray, Yo: np.ndarray, to: np.ndarray, a: float, b: float) -> float:
    """렌더 프레임 t 를 원곡 a + b t 로 옮겼을 때 평균 코사인 유사도(범위 밖 프레임은 0).

    원곡 구간(search_sec)이 렌더(× b)보다 짧으면 렌더 프레임 수로 나누지 않고 구간 안에 든 프레임의 평균을 쓴다
    (든 프레임이 들어갈 수 있는 수의 MIN_OVERLAP 미만이면 0). 렌더 프레임 수로 나누면 b 가 작을수록 렌더가 줄어 구간에
    더 많이 들어가 점수가 커져 비율이 범위 아래 끝으로 쏠렸다. 구간이 렌더보다 길면(보통) 예전과 같다.
    """
    dt = (to[-1] - to[0]) / max(1, to.size - 1)
    pos = (a + b * tr - to[0]) / dt
    valid = (pos >= 0) & (pos <= Yo.shape[1] - 1)
    if not valid.any():
        return 0.0
    p = pos[valid]
    i0 = np.floor(p).astype(int)
    i1 = np.minimum(i0 + 1, Yo.shape[1] - 1)
    f = p - i0
    w = Yo[:, i0] * (1 - f) + Yo[:, i1] * f
    nw = np.linalg.norm(w, axis=0)
    nw[nw < 1e-12] = 1.0
    sim = np.sum(Xr[:, valid] * w, axis=0) / nw
    cap = fit_frames(tr, to, b)
    if cap >= tr.size:
        return float(sim.sum() / tr.size)
    n_valid = int(valid.sum())
    if n_valid < MIN_OVERLAP * cap:
        return 0.0
    return float(sim.sum() / n_valid)


MIN_OVERLAP = 0.5  # 원곡 구간이 렌더보다 짧을 때 점수를 매기는 최소 겹침(들어갈 수 있는 프레임 수에 대한 비율)
WINDOW_OUT_TOL = 0.5  # 2차: 옮긴 렌더 마디 범위가 search_sec 구간을 이보다(초) 많이 벗어나면 경고


def fit_frames(tr: np.ndarray, to: np.ndarray, b: float) -> float:
    """비율 b 에서 원곡 구간 [to[0], to[-1]] 에 들어갈 수 있는 렌더 프레임 수(렌더 프레임 수 이하)."""
    if tr.size < 2 or b <= 0:
        return float(max(1, tr.size))
    dtr = (tr[-1] - tr[0]) / (tr.size - 1)
    cap = (to[-1] - to[0]) / (b * dtr) + 1.0 if dtr > 0 else float(tr.size)
    return float(max(1.0, min(tr.size, cap)))


FREE_REFINE = ((0.5, 0.02, 0.03, 0.0025), (0.04, 0.002, 0.003, 0.00025))
# 전역 탐색 뒤 다듬기: 전역 격자(오프셋 = 원곡 1프레임 약 23ms, 비율 RATIO_STEP)보다 조금 넓게
RANGE_REFINE = ((0.05, 0.005, 0.005, 0.0005), (0.006, 0.001, 0.0006, 0.00005))


def refine_linear(Xr, tr, Yo, to, a0, b0, stages=FREE_REFINE, b_lo: float = 0.3,
                  b_hi: float = math.inf, tc: float = 0.0) -> tuple[float, float, float]:
    """(a, b) 격자를 좁혀 가며 warp_score 최대. b는 [b_lo, b_hi] 안만(b_lo=0.3은 예전 동작).

    tc: 격자의 기준 시각. 오프셋을 '렌더 tc초가 원곡 몇 초인가'(ac = a + b·tc)로 두고 찾으면 긴 렌더에서
    a와 b가 서로 얽히지 않는다(tc=0이면 예전 방식). 반환 (점수, a, b).
    """
    best = (warp_score(Xr, tr, Yo, to, a0, b0), a0 + b0 * tc, b0)
    for da, sa, db_, sb in stages:
        _, cac, cb = best
        for b in np.arange(cb - db_, cb + db_ + 1e-12, sb):
            if b <= b_lo - 1e-12 or b > b_hi + 1e-12 or b <= 0.3:
                continue
            for ac in np.arange(cac - da, cac + da + 1e-12, sa):
                s = warp_score(Xr, tr, Yo, to, ac - b * tc, b)
                if s > best[0] + 1e-9:
                    best = (s, float(ac), float(b))
    s, ac, b = best
    return s, ac - b * tc, b


# ---------------------------------------------------------------- 비율 범위 제한 정렬

DEFAULT_RATIO_RANGE = (0.9, 1.1)
RATIO_STEP = 0.005   # 전역 탐색의 비율 격자
ALT_MAX = 3          # 반복 후보(다른 위치)를 이만큼까지 적는다
REPEAT_RATIO = 0.95  # 다른 위치 점수가 최고점의 이 비율 이상이면 반복 경고 + 신뢰도 한 단계 낮춤
BAND_SEC = 1.0       # 띠 DTW: 전역 직선에서 원곡 쪽으로 ±BAND_SEC 안만
BAND_PENALTY = 1e3   # 띠 밖 칸의 비용(코사인 거리는 0~2)
EDGE_TOL = RATIO_STEP / 2  # 비율이 범위 끝에서 이만큼 안이면 '끝에 걸림'


def parse_ratio_range(v) -> tuple[float, float] | None:
    if v is None:
        return None
    if (not isinstance(v, (list, tuple)) or len(v) != 2
            or not all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v)):
        raise EarError("BAD_ARGUMENT", "'tempo_ratio_range'는 [최소, 최대] 두 숫자이거나 null이어야 합니다.",
                       {"tempo_ratio_range": v})
    lo, hi = float(v[0]), float(v[1])
    if not (0.5 <= lo < hi <= 2.0):
        raise EarError("BAD_ARGUMENT", "'tempo_ratio_range'는 0.5 ≤ 최소 < 최대 ≤ 2.0 이어야 합니다.",
                       {"tempo_ratio_range": v})
    return lo, hi


def global_search(Xr: np.ndarray, tr: np.ndarray, Yo: np.ndarray, to: np.ndarray, t0: float, t1: float,
                  lo: float, hi: float) -> dict:
    """렌더 [t0, t1] 구간 크로마를 비율 b만큼 늘려 원곡 크로마 위를 미는 전수 탐색.

    b 격자(lo~hi, RATIO_STEP)마다 렌더 크로마를 원곡 프레임 간격으로 다시 뽑고(선형 보간 뒤 L2 정규화),
    12음 각각을 FFT 상호상관해 모든 오프셋의 코사인 합을 한 번에 구한다. 점수 = 코사인 합 / 렌더 프레임 수
    (원곡 밖으로 나간 프레임은 0). 반환 a = 렌더 0초가 원곡 몇 초인지.

    원곡 구간이 렌더(× b, L 프레임)보다 짧으면 점수 = 겹친 프레임의 평균 코사인(겹침이 원곡 프레임 수의 MIN_OVERLAP
    미만인 오프셋은 뺀다). 예전처럼 L 로 나누면 b 가 작을수록(L 이 작을수록) 점수가 커져 비율이 범위 아래 끝으로 쏠렸고,
    원곡 프레임 수로 나누면 반대로 렌더를 늘려 원곡의 다른 소리(앞 무음 등)까지 덮는 쪽으로 쏠린다. L ≤ 원곡 프레임 수면
    예전과 같다. 반환 window_shorter = 어떤 비율에서든 렌더가 원곡 구간보다 길었는가.
    """
    dt = float((to[-1] - to[0]) / max(1, to.size - 1))
    n_o = Yo.shape[1]
    sel = (tr >= t0) & (tr <= t1)
    if sel.sum() < 4:
        sel = np.ones_like(tr, dtype=bool)
    Xs, ts = Xr[:, sel], tr[sel]
    ratios = np.arange(lo, hi + 1e-9, RATIO_STEP)
    if ratios[-1] < hi - 1e-9:
        ratios = np.append(ratios, hi)
    best = {"score": -1.0}
    per_ratio = []
    shorter = False
    Yf_cache: dict[int, np.ndarray] = {}
    for b in ratios:
        L = max(4, int(np.floor((ts[-1] - ts[0]) * b / dt)) + 1)
        tq = ts[0] + np.arange(L) * dt / b
        R = np.empty((12, L))
        for c in range(12):
            R[c] = np.interp(tq, ts, Xs[c])
        nr = np.linalg.norm(R, axis=0)
        nr[nr < 1e-12] = 1.0
        R /= nr
        nfft = 1 << int(np.ceil(np.log2(n_o + L)))
        if nfft not in Yf_cache:
            Yf_cache[nfft] = np.fft.rfft(Yo, n=nfft, axis=1)
        Rf = np.fft.rfft(R[:, ::-1], n=nfft, axis=1)
        full = np.fft.irfft((Yf_cache[nfft] * Rf).sum(axis=0), n=nfft)
        # full[L-1+s] = sum_j R[:, j]·Y[:, s+j], s = -(L-1) .. n_o-1
        if L > n_o:
            shorter = True
            s_all = np.arange(n_o + L - 1) - (L - 1)
            ov = np.minimum(n_o, s_all + L) - np.maximum(0, s_all)
            sc = np.where(ov >= MIN_OVERLAP * n_o, full[: n_o + L - 1] / np.maximum(ov, 1), -1.0)
        else:
            sc = full[: n_o + L - 1] / L
        i = int(np.argmax(sc))
        s = i - (L - 1)
        a = float(to[0] + s * dt - b * ts[0])
        per_ratio.append((float(b), float(sc[i])))
        if sc[i] > best["score"]:
            # 같은 비율에서 2초 넘게 떨어진 다음 봉우리(애매함 판단용)
            far = np.abs(np.arange(sc.size) - i) * dt > 2.0
            second = float(sc[far].max()) if far.any() else None
            # other placements (each >= 2 s from the ones already taken) scoring >= 0.85 of the best: repeats
            alts = []
            taken = [i]
            for jj in np.argsort(sc)[::-1][:4000]:
                if sc[jj] < 0.85 * sc[i] or len(alts) >= ALT_MAX:
                    break
                if all(abs(int(jj) - k) * dt > 2.0 for k in taken):
                    taken.append(int(jj))
                    alts.append({"offset_sec": float(to[0] + (int(jj) - (L - 1)) * dt - b * ts[0]),
                                 "score": float(sc[jj])})
            best = {"score": float(sc[i]), "a": a, "b": float(b), "second_peak": second, "alternatives": alts}
    best["ratios_tried"] = int(ratios.size)
    best["window_shorter"] = bool(shorter)
    best["score_by_ratio"] = [[r(b, 4), r(v, 4)] for b, v in per_ratio]
    return best


def banded_dtw(Xr: np.ndarray, tr: np.ndarray, Yo: np.ndarray, to: np.ndarray, a: float, b: float,
               band: float = BAND_SEC):
    """직선 원곡 = a + b x 렌더 주위 ±band초 띠 안에서만 DTW 경로를 찾는다(띠 밖 칸은 큰 비용).

    렌더 프레임 중 직선 위치가 원곡 범위(±band) 밖인 것은 뺀다. 반환 (wp[렌더idx, 원곡idx], cost, info).
    """
    import librosa

    line = a + b * tr
    keep = np.nonzero((line >= to[0] - band) & (line <= to[-1] + band))[0]
    if keep.size < 4:
        raise EarError("ALIGN_FAILED", "정렬 직선(찾았거나 고정한 시작·비율)이 원곡 범위 밖입니다.",
                       {"offset_sec": r(a), "tempo_ratio": r(b, 4)})
    lo_t, hi_t = line[keep[0]] - band - 0.5, line[keep[-1]] + band + 0.5
    osel = np.nonzero((to >= lo_t) & (to <= hi_t))[0]
    X = Xr[:, keep]
    Y = Yo[:, osel]
    C = 1.0 - X.T @ Y
    dist = np.abs(to[osel][None, :] - line[keep][:, None])
    C[dist > band] = BAND_PENALTY
    m_real = C.shape[1]
    if C.shape[0] >= m_real:
        # librosa 의 subseq DTW 는 행(렌더)이 열(원곡)보다 많으면 범위 밖 열 번호를 돌려준다.
        # 띠 밖과 같은 비용의 열을 덧대 열을 행보다 많게 한다(경로는 덧댄 열을 쓰지 않는다).
        C = np.concatenate([C, np.full((C.shape[0], C.shape[0] - m_real + 2), BAND_PENALTY)], axis=1)
    D, wp = librosa.sequence.dtw(C=C, subseq=True, backtrack=True)
    wp = np.asarray(wp)[::-1]
    wp = wp[(wp[:, 1] < m_real) & (wp[:, 0] < C.shape[0])]
    if wp.shape[0] < 2:
        raise EarError("ALIGN_FAILED", "띠 DTW 경로가 너무 짧습니다.", {"path_points": int(wp.shape[0])})
    cost = C[wp[:, 0], wp[:, 1]]
    if np.any(cost >= BAND_PENALTY):
        log.warning("banded dtw path left the band at %d cells", int(np.sum(cost >= BAND_PENALTY)))
        cost = np.minimum(cost, 2.0)
    wp_full = np.stack([keep[wp[:, 0]], osel[wp[:, 1]]], axis=1)
    return wp_full, cost, {"band_sec": band, "render_frames": int(keep.size), "orig_frames": int(osel.size)}


def parse_search(search_sec, orig_dur: float) -> tuple[float, float]:
    if search_sec is None:
        return 0.0, orig_dur
    if (not isinstance(search_sec, (list, tuple)) or len(search_sec) != 2
            or not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in search_sec)):
        raise EarError("BAD_ARGUMENT", "'search_sec'는 [시작초, 끝초] 두 숫자여야 합니다.", {"search_sec": search_sec})
    a, b = float(search_sec[0]), float(search_sec[1])
    if not (math.isfinite(a) and math.isfinite(b)):
        raise EarError("BAD_ARGUMENT", "'search_sec'는 유한한 숫자여야 합니다(NaN·무한대 안 됨).", {"search_sec": str(search_sec)})
    b = min(b, orig_dur)
    if a < 0 or b - a < 1.0:
        raise EarError("BAD_ARGUMENT", "'search_sec' 구간이 잘못됐습니다(0 이상, 1초 이상).",
                       {"search_sec": search_sec, "orig_duration_sec": r(orig_dur)})
    return a, b


def render_beat_sec(meta: dict) -> tuple[float, str]:
    """렌더 박 길이(초). 메타 마디 표의 박 간격 중앙값(렌더 메타의 템포로 계산된 값), 없으면 tempo_map 첫 템포."""
    gaps = []
    for m in meta.get("measures") or []:
        b = list(m.get("beats") or []) + [m["end_sec"]]
        if len(b) >= 3:  # 박이 둘 이상 적힌 마디만
            gaps += [b[i + 1] - b[i] for i in range(len(b) - 1) if b[i + 1] > b[i]]
    if gaps:
        return float(np.median(gaps)), "메타 measures[].beats 간격 중앙값(렌더 초)"
    tm = meta.get("tempo_map")
    if (isinstance(tm, list) and tm and isinstance(tm[0], dict) and isinstance(tm[0].get("bpm"), (int, float))
            and tm[0]["bpm"] > 0):
        return 60.0 / float(tm[0]["bpm"]), "tempo_map 첫 템포"
    return 0.5, "기본값 0.5초(메타에 박·템포 없음)"


def path_deviation_decision(meta: dict, path_map, a: float, b: float, ux: np.ndarray, median_beats: float,
                            max_beats: float) -> dict:
    """마디·박 경계마다 경로(path_map)와 직선 a + b t 의 편차를 재 직선/경로 중 하나를 고른다.

    - 경계 = 메타 마디의 박 시각과 마지막 마디 끝(렌더 초, 같은 시각은 하나로).
    - 경로 양 끝 EDGE_SEC 안(과 경로 밖에서 늘려 쓴 곳)의 경계는 통계에서 뺀다(CQT 가장자리 효과로 경로가
      한 점에 몰리기 쉬움). 남는 경계가 3개 미만이면 모두 쓴다.
    - median = |편차| 중앙값. max = 편차를 1마디 폭(박 수 + 1개 경계) 이동 중앙값으로 다듬은 뒤의 |최대|
      (DTW 경로의 한두 박짜리 튐은 누르고, 원곡 템포가 실제로 바뀌어 여러 박 이어지는 편차는 남긴다).
      max_raw = 다듬기 전 |최대|(참고값).
    - 박 길이(beat_sec) = 렌더 박 길이 x 템포 비율(원곡 초).
    """
    from scipy.ndimage import median_filter

    ms = meta["measures"]
    t = sorted({round(float(x), 6) for m in ms for x in m["beats"]} | {round(float(ms[-1]["end_sec"]), 6)})
    t = np.asarray(t, dtype=float)
    dev = np.asarray(path_map(t), dtype=float) - (a + b * t)
    inner = (t >= ux[0] + EDGE_SEC) & (t <= ux[-1] - EDGE_SEC)
    use = inner if inner.sum() >= 3 else np.ones_like(inner)
    tu, du = t[use], dev[use]
    beats_per_measure = int(np.median([len(m["beats"]) for m in ms])) if ms else 4
    win = max(1, beats_per_measure + 1) | 1
    win = min(win, max(1, (du.size // 2) * 2 - 1))
    dsm = median_filter(du, size=win, mode="mirror") if du.size > 2 and win > 1 else du
    rb, src = render_beat_sec(meta)
    beat = rb * b
    med = float(np.median(np.abs(du))) if du.size else 0.0
    mx = float(np.max(np.abs(dsm))) if dsm.size else 0.0
    i_raw = int(np.argmax(np.abs(du))) if du.size else 0
    mx_raw = float(abs(du[i_raw])) if du.size else 0.0
    i_sm = int(np.argmax(np.abs(dsm))) if dsm.size else 0
    med_b, mx_b = med / beat, mx / beat
    # 최대 편차가 기준을 넘어도 그 묶음들이 짧고(AUTO_SUSTAIN_MEASURES마디 미만) 나머지 경계가 대부분 직선 가까이 있으면
    # (경계 편차의 90번째 백분위 <= AUTO_P90_BEATS박) 직선을 지킨다. 긴 고정 템포 렌더에서 빈 마디 등 한 마디짜리 튐 몇 개
    # 때문에 렌더 전체가 경로로 바뀌던 것을 막는다. 템포가 실제로 바뀌면 편차가 여러 곳에 퍼져 90번째 백분위가 커진다.
    # (1차의 '앞뒤 1마디가 직선으로 돌아옴' 검사는 1마디 이동 중앙값이 튐 둘레를 넓혀 실제 녹음에서 거의 늘 실패해 뺐다.)
    over = np.abs(dsm) > max_beats * beat if dsm.size else np.zeros(0, dtype=bool)
    frac = float(over.mean()) if over.size else 0.0
    p90_b = float(np.percentile(np.abs(dsm), 90)) / beat if dsm.size else 0.0
    sustain_n = AUTO_SUSTAIN_MEASURES * max(1, beats_per_measure)
    runs = []
    i = 0
    while i < over.size:
        if over[i]:
            j = i
            while j + 1 < over.size and over[j + 1]:
                j += 1
            side = "start" if i == 0 else ("end" if j == over.size - 1 else None)
            runs.append({"from_render_sec": r(float(tu[i]), 3), "to_render_sec": r(float(tu[j]), 3),
                         "boundaries": j - i + 1, "spike": (j - i + 1) < sustain_n, "touches_edge": side})
            i = j + 1
        else:
            i += 1
    spikes = sum(x["boundaries"] for x in runs if x["spike"])
    long_runs = [x for x in runs if not x["spike"]]
    const = constant_tempo(meta)
    if med_b > median_beats:
        linear_ok = False
        reason = f"경계 편차 중앙값 {med_b:.2f}박 > {median_beats:g}박이라 경로"
    elif mx_b <= max_beats:
        linear_ok = True
        reason = f"경계 편차 중앙값 {med_b:.2f}박·최대 {mx_b:.2f}박이 기준 안이라 직선"
    else:
        spread = p90_b > AUTO_P90_BEATS
        linear_ok = not long_runs and not spread
        detail = (f"최대 {mx_b:.2f}박 > {max_beats:g}박인 경계 {int(over.sum())}/{over.size}곳, 묶음 {len(runs)}개"
                  f"(가장 긴 것 {max(x['boundaries'] for x in runs)}경계, 기준 {sustain_n}경계 = {AUTO_SUSTAIN_MEASURES}마디), "
                  f"편차 90번째 백분위 {p90_b:.2f}박(기준 {AUTO_P90_BEATS:g}박)")
        if linear_ok:
            reason = f"{detail}: 짧은 튐뿐이고 경계 90%가 직선 가까이 있어 직선 유지"
        elif long_runs:
            d0 = long_runs[0]
            reason = (f"{detail}: {d0['from_render_sec']}~{d0['to_render_sec']}초 렌더에서 편차가 "
                      f"{AUTO_SUSTAIN_MEASURES}마디 넘게 이어져 경로")
        else:
            reason = f"{detail}: 직선에서 벗어난 경계가 곳곳에 퍼져 있어(원곡 템포가 바뀌거나 구간이 다름) 경로"
    return {
        "mapping_used": "linear" if linear_ok else "path",
        "mapping_reason": reason,
        "over_max": {"boundaries": int(over.sum()), "fraction": r(frac, 3), "runs": runs[:20],
                     "spike_boundaries": int(spikes), "sustain_boundaries": int(sustain_n),
                     "p90_beats": r(p90_b, 3), "p90_threshold_beats": AUTO_P90_BEATS,
                     "constant_render_tempo": const},
        "path_deviation_sec": {"median": r(med, 4), "max": r(mx, 4), "max_raw": r(mx_raw, 4),
                               "max_at_render_sec": r(float(tu[i_sm]), 3) if tu.size else None,
                               "max_deviation_at_sec": r(float(a + b * tu[i_sm]), 3) if tu.size else None,
                               "max_raw_at_render_sec": r(float(tu[i_raw]), 3) if tu.size else None},
        "path_deviation_beats": {"median": r(med_b, 3), "max": r(mx_b, 3), "max_raw": r(mx_raw / beat, 3)},
        "beat_sec": r(beat, 4), "beat_sec_render": r(rb, 4), "beat_source": src,
        "thresholds_beats": {"median": median_beats, "max": max_beats},
        "boundaries": int(t.size), "boundaries_used": int(tu.size), "edge_excluded": int(t.size - tu.size),
        "smoothing_boundaries": int(win),
        "rule": (f"경계 편차 중앙값 > {median_beats:g}박이면 경로. 아니고 최대(1마디 이동 중앙값 뒤) <= {max_beats:g}박이면 직선. "
                 f"최대가 넘으면 넘는 묶음이 {AUTO_SUSTAIN_MEASURES}마디 넘게 이어지거나 경계 편차의 90번째 백분위가 "
                 f"{AUTO_P90_BEATS:g}박을 넘을 때만 경로. 경로 양 끝 {EDGE_SEC:g}초 안 경계는 뺌"),
    }


def constant_tempo(meta: dict) -> bool:
    """렌더 메타의 템포가 하나뿐인가(tempo_map 의 bpm 이 모두 같음)."""
    tm = meta.get("tempo_map")
    if not isinstance(tm, list) or not tm:
        return False
    bpms = {round(float(x.get("bpm")), 3) for x in tm if isinstance(x, dict) and isinstance(x.get("bpm"), (int, float))}
    return len(bpms) == 1


# ---------------------------------------------------------------- 구간별 정렬

MAX_SEGMENTS = 32
SEG_MIN_SEC = 2.0          # 구간 최소 길이(렌더 초)
SEG_RATIO_MIN_SEC = 10.0   # 이보다 짧은 구간은 비율을 전역 값으로 고정하고 오프셋만 찾는다
LOCAL_WINDOW_MEASURES = 4  # 건너뜀 찾기: 창 길이(마디)
LOCAL_STEP_MEASURES = 2    # 건너뜀 찾기: 창 간격(마디)
JUMP_MIN_SEC = 2.0         # 건너뜀 찾기: 창의 최적 위치가 전역 직선에서 이만큼(원곡 초) 넘게 떨어지면
JUMP_MIN_GAIN = 0.05       # 그리고 그 위치 점수가 전역 직선 위 점수보다 이만큼 넘게 높으면 후보
IN_TOL = 0.25              # 건너뜀 찾기: 창이 원곡 구간 안에 드는지 볼 때 허용하는 넘침(초, 크로마 프레임 가장자리)
JUMP_SAME_SEC = 1.0        # 이웃 창의 떨어진 정도가 이 안이면 같은 후보 구간으로 묶는다
END_EPS = 1e-6             # 마디 끝을 옮길 때 끝 바로 앞 시각을 쓴다(구간 경계)
GAP_STEP = 0.05            # orig_gaps: 렌더 시각 격자(초)
GAP_MIN_SEC = 0.5          # orig_gaps: 격자 한 칸에 원곡 시각이 이보다 많이 뛰면 그 사이를 건너뛴 원곡으로 본다


def parse_segments(v, render_dur: float):
    """segments: [{"render_from_sec": a, "render_to_sec": b}, …] 또는 None. 시각 순서, 겹치지 않음, 각 SEG_MIN_SEC 이상."""
    if v is None:
        return None
    if not isinstance(v, list) or not v or len(v) > MAX_SEGMENTS:
        raise EarError("BAD_ARGUMENT", f"'segments'는 1~{MAX_SEGMENTS}개 항목의 배열이어야 합니다.", {"segments": v})
    out = []
    for i, s in enumerate(v):
        ok = (isinstance(s, dict) and set(s) == {"render_from_sec", "render_to_sec"}
              and all(isinstance(s[k], (int, float)) and not isinstance(s[k], bool) for k in s))
        if not ok:
            raise EarError("BAD_ARGUMENT", "segments 항목은 {\"render_from_sec\": 수, \"render_to_sec\": 수}만 받습니다.",
                           {"index": i, "entry": s})
        a, b = float(s["render_from_sec"]), float(s["render_to_sec"])
        if a < 0 or b - a < SEG_MIN_SEC or a > render_dur:
            raise EarError("BAD_ARGUMENT", f"segments[{i}] 구간이 잘못됐습니다(0 이상, 렌더 안, {SEG_MIN_SEC:g}초 이상).",
                           {"index": i, "entry": s, "render_sec": r(render_dur)})
        if out and a < out[-1][1] - 1e-9:
            raise EarError("BAD_ARGUMENT", f"segments[{i}]가 앞 구간과 겹치거나 순서가 틀렸습니다.", {"index": i, "entry": s})
        out.append((a, min(b, render_dur)))
    return out


def segment_fit(cr: Chroma, co: Chroma, t0: float, t1: float, lo: float, hi: float, b_global: float) -> dict:
    """렌더 [t0, t1] 만으로 원곡 전체(search_sec 구간)에서 직선(a, b)을 찾는다. 짧은 구간은 비율을 b_global 로 고정."""
    fixed = (t1 - t0) * b_global < SEG_RATIO_MIN_SEC
    blo, bhi = (b_global, b_global) if fixed else (lo, hi)
    g = global_search(cr.unit, cr.times, co.unit, co.times, t0, t1, blo, bhi)
    sel = (cr.times >= t0) & (cr.times <= t1)
    if fixed:
        s, a, b = refine_linear(cr.unit[:, sel], cr.times[sel], co.unit, co.times, g["a"], g["b"],
                                RANGE_REFINE, b_global, b_global, tc=0.5 * (t0 + t1))
    else:
        s, a, b = refine_linear(cr.unit[:, sel], cr.times[sel], co.unit, co.times, g["a"], g["b"],
                                RANGE_REFINE, lo, hi, tc=0.5 * (t0 + t1))
    level = "높음" if s >= 0.85 else ("보통" if s >= 0.7 else "낮음")
    amb = g["second_peak"] is not None and g["score"] > 0 and g["second_peak"] >= 0.97 * g["score"]
    return {"render_from_sec": r(t0, 3), "render_to_sec": r(t1, 3), "offset_sec": r(a, 4), "tempo_ratio": r(b, 5),
            "ratio_fixed": fixed, "orig_from_sec": r(a + b * t0, 3), "orig_to_sec": r(a + b * t1, 3),
            "warp_score": r(s, 4), "second_peak_score": r(g["second_peak"], 4), "ambiguous": bool(amb),
            "confidence": level, "_a": a, "_b": b}


def local_offsets(cr: Chroma, co: Chroma, ms: list[dict], a: float, b: float) -> tuple[list[dict], list[dict]]:
    """건너뜀 찾기: LOCAL_WINDOW_MEASURES마디 창을 LOCAL_STEP_MEASURES마디씩 옮기며 전역 비율 b 그대로 원곡에서 가장 잘
    맞는 위치를 찾는다. 창의 최적 위치가 전역 직선에서 JUMP_MIN_SEC 넘게 떨어지고 점수가 직선 위보다 JUMP_MIN_GAIN 넘게 높으면
    후보로 보고, 이웃한 후보 창(떨어진 정도가 JUMP_SAME_SEC 안)을 묶어 구간 후보로 돌려준다. 반환 (창 목록, 구간 후보)."""
    n = len(ms)
    rows: list[dict] = []
    if n < 2 * LOCAL_WINDOW_MEASURES:
        return rows, []
    starts = list(range(0, n - LOCAL_WINDOW_MEASURES + 1, LOCAL_STEP_MEASURES))
    if starts[-1] != n - LOCAL_WINDOW_MEASURES:
        starts.append(n - LOCAL_WINDOW_MEASURES)
    for i in starts:
        t0, t1 = ms[i]["start_sec"], ms[i + LOCAL_WINDOW_MEASURES - 1]["end_sec"]
        g = global_search(cr.unit, cr.times, co.unit, co.times, t0, t1, b, b)
        sel = (cr.times >= t0) & (cr.times <= t1)
        on_line = warp_score(cr.unit[:, sel], cr.times[sel], co.unit, co.times, a, b)
        best = warp_score(cr.unit[:, sel], cr.times[sel], co.unit, co.times, g["a"], b)
        shift = g["a"] - a
        # 2차: 전역 직선 위치와 찾은 위치가 둘 다 원곡 구간(search_sec 안, 오디오 안)에 통째로 들어갈 때만 비교한다.
        # 반쯤 밖에 걸리면 직선 위 점수가 0에 가까워져 아무 곳이나 '더 잘 맞는' 거짓 후보가 됐다.
        lo_o, hi_o = float(co.times[0]), float(co.times[-1])
        line_in = a + b * t0 >= lo_o - IN_TOL and a + b * t1 <= hi_o + IN_TOL
        best_in = g["a"] + b * t0 >= lo_o - IN_TOL and g["a"] + b * t1 <= hi_o + IN_TOL
        flag = line_in and best_in and abs(shift) > JUMP_MIN_SEC and best - on_line > JUMP_MIN_GAIN
        rows.append({"measures": [ms[i]["measure"], ms[i + LOCAL_WINDOW_MEASURES - 1]["measure"]],
                     "render_from_sec": r(t0, 3), "render_to_sec": r(t1, 3), "shift_sec": r(shift, 3),
                     "score_on_line": r(on_line, 4), "score_best": r(best, 4), "jump": bool(flag),
                     "inside_window": bool(line_in and best_in)})
    cands: list[dict] = []
    for w in rows:
        if not w["jump"]:
            continue
        c = cands[-1] if cands else None
        if (c is not None and w["render_from_sec"] <= c["render_to_sec"] + 1e-6
                and abs(w["shift_sec"] - c["shift_sec"]) <= JUMP_SAME_SEC):
            c["render_to_sec"] = w["render_to_sec"]
            c["measures"][1] = w["measures"][1]
            c["windows"] += 1
            c["score_best"] = max(c["score_best"], w["score_best"])
        else:
            cands.append({"measures": list(w["measures"]), "render_from_sec": w["render_from_sec"],
                          "render_to_sec": w["render_to_sec"], "shift_sec": w["shift_sec"],
                          "orig_from_sec": r(a + b * w["render_from_sec"] + w["shift_sec"], 3),
                          "score_on_line": w["score_on_line"], "score_best": w["score_best"], "windows": 1})
    return rows, cands


def render_tempo_bpm(meta: dict) -> float | None:
    """Constant render tempo (BPM) from the meta tempo_map, None when the map has several different tempos."""
    tm = meta.get("tempo_map") or []
    bpms = {round(float(x.get("bpm")), 6) for x in tm if isinstance(x, dict) and isinstance(x.get("bpm"), (int, float))}
    return float(next(iter(bpms))) if len(bpms) == 1 else None


def parse_fixed(meta: dict, offset_sec=None, first_downbeat_sec=None, measure_offset=None, tempo_ratio=None,
                orig_bpm=None) -> dict | None:
    """What the caller fixes in the line orig = a + b * render (None when nothing is fixed).

    Returns {"anchor": (render_sec, orig_sec) | None, "b": float | None, "given": {...}}. offset_sec fixes the anchor
    (0, offset_sec); first_downbeat_sec fixes (start of render measure measure_offset, first_downbeat_sec), the same
    convention as the stem tools (first_downbeat_sec = start of tool measure measure_offset); measure_offset defaults to
    the first measure of the meta. tempo_ratio fixes b; orig_bpm fixes b = render bpm / orig_bpm (constant render tempo)."""
    given = {k: v for k, v in (("offset_sec", offset_sec), ("first_downbeat_sec", first_downbeat_sec),
                               ("measure_offset", measure_offset), ("tempo_ratio", tempo_ratio),
                               ("orig_bpm", orig_bpm)) if v is not None}
    if not given:
        return None
    if offset_sec is not None and first_downbeat_sec is not None:
        raise EarError("BAD_ARGUMENT", "'offset_sec'와 'first_downbeat_sec'는 함께 줄 수 없습니다(둘 다 시작 위치).", given)
    if tempo_ratio is not None and orig_bpm is not None:
        raise EarError("BAD_ARGUMENT", "'tempo_ratio'와 'orig_bpm'은 함께 줄 수 없습니다(둘 다 템포 비율).", given)
    if measure_offset is not None and first_downbeat_sec is None:
        raise EarError("BAD_ARGUMENT", "'measure_offset'은 'first_downbeat_sec'와 함께만 씁니다.", given)
    anchor = None
    if offset_sec is not None:
        anchor = (0.0, float(offset_sec))
    elif first_downbeat_sec is not None:
        ms = meta["measures"]
        mo = ms[0]["measure"] if measure_offset is None else measure_offset
        hit = [m for m in ms if m["measure"] == mo]
        if not hit:
            raise EarError("BAD_ARGUMENT", f"렌더 메타에 {mo}마디가 없습니다(measure_offset).",
                           {"measure_offset": mo, "available": [ms[0]["measure"], ms[-1]["measure"]]})
        anchor = (float(hit[0]["start_sec"]), float(first_downbeat_sec))
        given["measure_offset_used"] = mo
    b = None
    if tempo_ratio is not None:
        b = float(tempo_ratio)
    elif orig_bpm is not None:
        rb = render_tempo_bpm(meta)
        if rb is None:
            raise EarError("BAD_ARGUMENT", "'orig_bpm'은 렌더 템포가 하나일 때만 씁니다(tempo_ratio를 직접 주세요).",
                           {"tempo_map": meta.get("tempo_map")})
        b = rb / float(orig_bpm)
        given["render_bpm"] = rb
    if b is not None and not (0.5 <= b <= 2.0):
        raise EarError("BAD_ARGUMENT", "고정한 템포 비율이 0.5~2 밖입니다.", {"tempo_ratio": b, **given})
    return {"anchor": anchor, "b": b, "given": given}


def fixed_line(cr: Chroma, co: Chroma, span0: float, span1: float, fixed: dict, rng) -> tuple[float, float, str]:
    """Line (a, b) honouring the fixed parts; the free part is searched like the ratio-limited path."""
    fsel = (cr.times >= span0) & (cr.times <= span1)
    if fsel.sum() < 4:
        fsel = np.ones_like(cr.times, dtype=bool)
    X, tx = cr.unit[:, fsel], cr.times[fsel]
    anchor, b = fixed["anchor"], fixed["b"]
    if anchor is not None and b is not None:
        return anchor[1] - b * anchor[0], b, "fixed", None
    if b is not None:  # offset only: global search at one ratio, then refine the offset
        g = global_search(cr.unit, cr.times, co.unit, co.times, span0, span1, b, b)
        stages = ((0.05, 0.005, 0.0, 1.0), (0.006, 0.0005, 0.0, 1.0))
        _, a, _ = refine_linear(X, tx, co.unit, co.times, g["a"], b, stages, 0.3, math.inf, tc=0.5 * (span0 + span1))
        return a, b, "fixed_ratio+offset_search", g
    lo, hi = rng if rng is not None else DEFAULT_RATIO_RANGE
    ar, ao = anchor
    best = (-1.0, 1.0)
    for step, half in ((RATIO_STEP, None), (0.0005, RATIO_STEP), (0.00005, 0.0005)):
        c = best[1]
        grid = np.arange(lo, hi + 1e-9, step) if half is None else np.arange(c - half, c + half + 1e-12, step)
        for bb in grid:
            if bb < lo - 1e-12 or bb > hi + 1e-12:
                continue
            s = warp_score(X, tx, co.unit, co.times, ao - bb * ar, float(bb))
            if s > best[0] + 1e-9:
                best = (s, float(bb))
    return ao - best[1] * ar, best[1], "fixed_offset+ratio_search", None


def run_align(render_wav: Path, meta: dict, original: Path, search_sec, mapping: str,
              tempo_ratio_range=DEFAULT_RATIO_RANGE, auto_median_beats: float = AUTO_MEDIAN_BEATS,
              auto_max_beats: float = AUTO_MAX_BEATS, segments=None, fixed: dict | None = None) -> dict:
    """tempo_ratio_range=(lo, hi): 비율을 그 범위로 제한(전역 탐색 + 띠 DTW). None: 예전 자유 DTW.

    mapping: 'auto'(경로 편차로 직선/경로 자동 선택), 'path', 'linear'.
    segments: [{"render_from_sec", "render_to_sec"}, …]면 구간마다 원곡 전체에서 따로 직선을 찾아(segments[]) 그 구간의
    마디·박·경로를 그 직선으로 옮긴다(mapping_used "segments", 구간 밖은 전역 결과). 비율 범위 제한일 때만.
    fixed: parse_fixed() 결과. 시작 위치·템포 비율 중 준 것은 그대로 쓰고 나머지만 찾는다. 둘 다 주면 탐색 없이 그 직선이고
    mapping 'auto'는 직선(linear)으로 정한다('path'를 주면 그 직선 ±1초 띠 DTW 경로). 언제나 띠 DTW 방식(자유 DTW 아님).
    """
    t_start = time.perf_counter()
    if mapping not in MAPPINGS:
        raise EarError("BAD_ARGUMENT", "'mapping'은 'auto', 'path', 'linear' 중 하나입니다.", {"mapping": mapping})
    rng = parse_ratio_range(None if tempo_ratio_range is None else list(tempo_ratio_range))
    oinfo = io.probe(original)
    rinfo = io.probe(render_wav)
    if rinfo["duration_sec"] < MIN_SEC or oinfo["duration_sec"] < MIN_SEC:
        raise EarError("ALIGN_FAILED", f"오디오가 너무 짧아 정렬할 수 없습니다({MIN_SEC}초 미만).",
                       {"render_sec": r(rinfo["duration_sec"]), "orig_sec": r(oinfo["duration_sec"])})
    s0, s1 = parse_search(search_sec, oinfo["duration_sec"])
    segs = parse_segments(segments, rinfo["duration_sec"])
    if segs is not None and fixed is not None:
        raise EarError("BAD_ARGUMENT", "'segments'와 고정 인자(offset_sec·first_downbeat_sec·tempo_ratio·orig_bpm)는 함께 "
                       "쓸 수 없습니다.", {"fixed": fixed["given"]})
    if segs is not None and rng is None:
        raise EarError("BAD_ARGUMENT", "'segments'는 tempo_ratio_range가 있을 때(비율 범위 제한)만 씁니다.",
                       {"tempo_ratio_range": None})
    cr: Chroma = for_file(render_wav)
    co: Chroma = for_file(original, offset=s0, duration=s1 - s0)
    t_feat = time.perf_counter() - t_start
    if cr.n < 8 or co.n < 8:
        raise EarError("ALIGN_FAILED", "오디오가 너무 짧아 정렬할 수 없습니다.", {"render_frames": cr.n, "orig_frames": co.n})

    ms = meta["measures"]
    span0, span1 = ms[0]["start_sec"], ms[-1]["end_sec"]
    gsearch = None
    warnings_: list[str] = []
    ratio_at_bound = False
    repeat_ambiguous = False
    alternatives = []
    if fixed is None and rng is None:
        # ---- 자유 DTW(tempo_ratio_range: null)
        # 1차: 프레임이 많으면 묶는다
        k = max(1, math.ceil(max(cr.n, co.n) / MAX_FRAMES))
        Xr, tr = group(cr.unit, k), group_times(cr.times, k)
        Yo, to = group(co.unit, k), group_times(co.times, k)
        try:
            wp, cost, subseq = dtw(Xr, Yo)
        except Exception as e:
            raise EarError("ALIGN_FAILED", "DTW 계산에 실패했습니다.", {"reason": repr(e)})
        stages = [{"k": k, "render_frames": int(Xr.shape[1]), "orig_frames": int(Yo.shape[1]), "subseq": subseq}]
        base = baseline_cost(Xr, Yo)

        # 2차: 찾은 구간만 더 촘촘하게
        if k > 1:
            o_lo = to[wp[0, 1]] - REFINE_MARGIN_SEC
            o_hi = to[wp[-1, 1]] + REFINE_MARGIN_SEC
            sel = np.nonzero((co.times >= o_lo) & (co.times <= o_hi))[0]
            k2 = max(1, math.ceil(max(cr.n, sel.size) / MAX_FRAMES))
            if k2 < k and sel.size >= 8:
                Xr2, tr2 = group(cr.unit, k2), group_times(cr.times, k2)
                Yo2, to2 = group(co.unit[:, sel], k2), group_times(co.times[sel], k2)
                try:
                    wp2, cost2, subseq2 = dtw(Xr2, Yo2)
                    Xr, tr, Yo, to, wp, cost, k = Xr2, tr2, Yo2, to2, wp2, cost2, k2
                    stages.append({"k": k2, "render_frames": int(Xr.shape[1]), "orig_frames": int(Yo.shape[1]),
                                   "subseq": subseq2, "orig_window_sec": [r(o_lo), r(o_hi)]})
                except Exception as e:  # 정밀화 실패는 1차 결과로 계속
                    log.warning("refine dtw failed: %r", e)

        pr = tr[wp[:, 0]]
        po = to[wp[:, 1]]
        ux, uy = dedupe(pr, po)
        inside = (ux >= span0 - 1e-6) & (ux <= span1 + 1e-6)
        if inside.sum() < 4:
            inside = np.ones_like(ux, dtype=bool)
        if inside.sum() < 2:
            raise EarError("ALIGN_FAILED", "정렬 경로가 너무 짧습니다.", {"path_points": int(ux.size)})
        b_fit, a_fit = np.polyfit(ux[inside], uy[inside], 1)
        if not (0.5 <= b_fit <= 2.0) or not np.isfinite(a_fit):
            raise EarError("ALIGN_FAILED", "정렬 결과의 템포 비율이 0.5~2 밖입니다(다른 곡이거나 구간이 맞지 않음).",
                           {"tempo_ratio": r(b_fit, 4), "offset_sec": r(a_fit)})
        # 직선 변형 유사도로 다듬기(마디 표 범위의 렌더 프레임만)
        rsel = (tr >= span0) & (tr <= span1)
        if rsel.sum() < 4:
            rsel = np.ones_like(tr, dtype=bool)
        s_fit = warp_score(Xr[:, rsel], tr[rsel], Yo, to, float(a_fit), float(b_fit))
        s_ref, a_ref, b_ref = refine_linear(Xr[:, rsel], tr[rsel], Yo, to, float(a_fit), float(b_fit))
        if s_ref > s_fit and 0.5 <= b_ref <= 2.0:
            a, b, method = a_ref, b_ref, "path_fit+warp_refine"
        else:
            a, b, method = float(a_fit), float(b_fit), "path_fit"
    else:
        # ---- 비율 범위 제한: 전역 직선 탐색(비율 격자 x 모든 오프셋) -> 다듬기 -> 직선 주위 띠 DTW
        b_lo, b_hi = rng if rng is not None else DEFAULT_RATIO_RANGE
        fsel = (cr.times >= span0) & (cr.times <= span1)
        if fsel.sum() < 4:
            fsel = np.ones_like(cr.times, dtype=bool)
        if fixed is not None:
            a, b, method, gfix = fixed_line(cr, co, span0, span1, fixed, (b_lo, b_hi))
            s_ref = warp_score(cr.unit[:, fsel], cr.times[fsel], co.unit, co.times, a, b)
            g = gfix if gfix is not None else {"second_peak": None, "score": s_ref, "alternatives": []}
        else:
            g = global_search(cr.unit, cr.times, co.unit, co.times, span0, span1, b_lo, b_hi)
            s_ref, a, b = refine_linear(cr.unit[:, fsel], cr.times[fsel], co.unit, co.times, g["a"], g["b"],
                                        RANGE_REFINE, b_lo, b_hi, tc=0.5 * (span0 + span1))
            method = "global_search+warp_refine"
            gsearch = {"tempo_ratio_range": [b_lo, b_hi], "ratio_step": RATIO_STEP, "ratios_tried": g["ratios_tried"],
                       "offset_sec": r(g["a"], 4), "tempo_ratio": r(g["b"], 4), "score": r(g["score"], 4),
                       "second_peak_score": r(g["second_peak"], 4), "score_by_ratio": g["score_by_ratio"],
                       "window_shorter_than_render": g["window_shorter"],
                       "measures_outside_window_sec": r(max(0.0, s0 - (a + b * span0)) + max(0.0, (a + b * span1) - s1),
                                                        3),
                       "score_normalization": ("코사인 합 ÷ 렌더 프레임 수(원곡 구간이 렌더보다 길 때). 짧으면 겹친 프레임 평균"
                                               f"(겹침이 원곡 구간의 {MIN_OVERLAP:g} 미만인 위치는 뺌)")}
        k = max(1, math.ceil(max(cr.n, co.n) / MAX_FRAMES))
        Xr, tr = group(cr.unit, k), group_times(cr.times, k)
        Yo, to = group(co.unit, k), group_times(co.times, k)
        try:
            wp, cost, binfo = banded_dtw(Xr, tr, Yo, to, a, b)
        except EarError:
            raise
        except Exception as e:
            raise EarError("ALIGN_FAILED", "띠 DTW 계산에 실패했습니다.", {"reason": repr(e)})
        stages = [{"k": k, "mode": "fixed" if fixed is not None else "tempo_ratio_range", "subseq": True, **binfo}]
        base = baseline_cost(Xr, Yo)
        pr = tr[wp[:, 0]]
        po = to[wp[:, 1]]
        ux, uy = dedupe(pr, po)
        inside = (ux >= span0 - 1e-6) & (ux <= span1 + 1e-6)
        if inside.sum() < 4:
            inside = np.ones_like(ux, dtype=bool)
        if inside.sum() < 2:
            raise EarError("ALIGN_FAILED", "정렬 경로가 너무 짧습니다.", {"path_points": int(ux.size)})
        b_fit, a_fit = np.polyfit(ux[inside], uy[inside], 1)  # 참고값(직선은 전역 탐색 결과를 쓴다)
        s_fit = warp_score(cr.unit[:, fsel], cr.times[fsel], co.unit, co.times, float(a_fit), float(b_fit))
        if (fixed is None or fixed["b"] is None) and (b - b_lo < EDGE_TOL or b_hi - b < EDGE_TOL):
            ratio_at_bound = True
            side = "아래" if b - b_lo < EDGE_TOL else "위"
            bound = b_lo if b - b_lo < EDGE_TOL else b_hi
            warnings_.append(f"템포 비율 {b:.4f}가 tempo_ratio_range [{b_lo}, {b_hi}]의 끝에 걸렸다({side} 끝 {bound}, "
                             "ratio_at_bound). 실제 비율이 범위 밖일 수 있으니 범위를 넓혀(또는 null로) 다시 확인한다")
        # 2차: 고른 직선으로 옮긴 렌더 마디 범위가 실제로 원곡 구간(search_sec)을 벗어날 때만 알린다
        o_lo, o_hi = a + b * span0, a + b * span1
        out_sec = max(0.0, s0 - o_lo) + max(0.0, o_hi - s1)
        window_outside = search_sec is not None and out_sec > WINDOW_OUT_TOL  # search_sec을 줬을 때만(곡 전체면 알리지 않음)
        if window_outside:
            warnings_.append(f"렌더 마디 범위가 원곡 {o_lo:.1f}~{o_hi:.1f}초({o_hi - o_lo:.1f}초)로 옮겨져 search_sec 구간 "
                             f"{s0:.1f}~{s1:.1f}초({s1 - s0:.1f}초)를 {out_sec:.1f}초 벗어난다. 렌더의 일부만 원곡과 맞췄고, 구간 밖 "
                             "마디의 원곡 시각은 직선으로 늘린 값이다")
        alternatives = [{"offset_sec": r(x["offset_sec"], 4), "score": r(x["score"], 4),
                         "score_ratio": r(x["score"] / g["score"], 3) if g["score"] > 0 else None,
                         "first_measure_orig_sec": r(x["offset_sec"] + b * span0, 3)}
                        for x in g.get("alternatives") or []]
        repeat_alts = [x for x in alternatives if x["score_ratio"] is not None and x["score_ratio"] >= REPEAT_RATIO]
        if repeat_alts:
            repeat_ambiguous = True
            warnings_.append("다른 위치도 거의 같은 점수다(반복 구간일 수 있음): 고른 첫 마디 원곡 "
                             f"{a + b * span0:.2f}초(점수 {g['score']:.3f}), 다른 후보 "
                             + ", ".join(f"{x['first_measure_orig_sec']:.2f}초({x['score']:.3f})" for x in repeat_alts)
                             + ". search_sec으로 원곡 구간을 좁혀 다시 정렬한다(신뢰도 한 단계 낮춤)")
    t_dtw = time.perf_counter() - t_start - t_feat

    # 경로가 직선에서 벗어난 정도(편차)를 1초 이동 중앙값으로 다듬는다. 경로 양 끝은 CQT 가장자리 효과로
    # 몇 프레임이 한 점에 몰리기 쉬워(실측 약 0.1초) 그대로 쓰면 첫·끝 마디 경계가 틀어진다.
    from scipy.ndimage import median_filter

    dev = uy - (a + b * ux)
    dt_path = float(np.median(np.diff(ux))) if ux.size > 1 else 1.0
    win = int(round(SMOOTH_SEC / max(dt_path, 1e-6))) | 1
    dev_s = median_filter(dev, size=min(win, max(1, (ux.size // 2) * 2 - 1)), mode="mirror") if ux.size > 2 else dev
    # 서로 다른 줄이 되지 않게 단조 증가 유지
    uy_s = np.maximum.accumulate(a + b * ux + dev_s)

    def path_map(t):
        t = np.asarray(t, dtype=float)
        v = np.interp(t, ux, uy_s)
        lo, hi = t < ux[0], t > ux[-1]
        v = np.where(lo, uy_s[0] + (t - ux[0]) * b, v)
        v = np.where(hi, uy_s[-1] + (t - ux[-1]) * b, v)
        return v

    # 경로가 직선에서 얼마나 벗어나는지(모든 mapping 에서 기록), auto 면 이것으로 고른다
    decision = path_deviation_decision(meta, path_map, a, b, ux, auto_median_beats, auto_max_beats)
    fully_fixed = fixed is not None and fixed["anchor"] is not None and fixed["b"] is not None
    mapping_used = decision["mapping_used"] if mapping == "auto" else mapping
    if fully_fixed and mapping == "auto":
        mapping_used = "linear"
    decision["mapping_used"] = mapping_used
    base_used = mapping_used  # segments 를 쓸 때 구간 밖에 쓰는 방식
    pdb = decision["path_deviation_beats"]
    kind = "직선" if mapping_used == "linear" else "경로"
    if fully_fixed and mapping == "auto":
        decision["mapping_reason"] = "시작 위치와 템포 비율을 모두 고정(직선을 그대로 씀)"
        decision["text"] = (f"고정 직선(시작·비율 지정). 참고: 경로 편차 중앙 {pdb['median']:.2f}박, 최대 {pdb['max']:.2f}박")
    elif mapping == "auto":
        decision["text"] = (f"{kind} 정렬 선택(경로 편차 중앙 {pdb['median']:.2f}박, 최대 {pdb['max']:.2f}박; "
                            f"기준 {auto_median_beats:g}/{auto_max_beats:g}박)")
        if pdb["median"] <= auto_median_beats and pdb["max"] > auto_max_beats:
            decision["text"] += " — " + decision["mapping_reason"]
    else:
        decision["mapping_reason"] = f"mapping='{mapping}' 지정"
        decision["text"] = (f"{kind} 정렬(mapping='{mapping}' 지정). 경로 편차 중앙 {pdb['median']:.2f}박, "
                            f"최대 {pdb['max']:.2f}박")

    # 건너뜀 찾기(비율 범위 제한일 때, 마디 8개 이상)와 구간별 정렬
    local_rows, jump_cands = ([], [])
    if rng is not None:
        local_rows, jump_cands = local_offsets(cr, co, ms, a, b)
        for c in jump_cands[:3]:
            warnings_.append(f"렌더 {c['measures'][0]}~{c['measures'][1]}마디({c['render_from_sec']:.1f}~{c['render_to_sec']:.1f}초)는 "
                             f"전역 직선보다 원곡 {c['shift_sec']:+.1f}초 떨어진 곳(원곡 {c['orig_from_sec']:.1f}초~)과 더 잘 맞는다"
                             f"(점수 {c['score_on_line']:.2f} → {c['score_best']:.2f}). 구간을 잘라냈거나 옮긴 편곡일 수 있으니 "
                             "segments로 구간별 정렬을 확인한다")
        if len(jump_cands) > 3:
            warnings_.append(f"그 밖에 건너뜀 후보 구간 {len(jump_cands) - 3}개(segment_candidates)")
    seg_out = None
    if segs is not None:
        seg_out = [segment_fit(cr, co, x0, x1, rng[0], rng[1], b) for x0, x1 in segs]
        for k, sg in enumerate(seg_out):
            if sg["confidence"] == "낮음" or sg["ambiguous"]:
                warnings_.append(f"segments[{k}]({sg['render_from_sec']:.1f}~{sg['render_to_sec']:.1f}초)의 정렬을 믿기 어렵다"
                                 f"(warp_score {sg['warp_score']:.2f}" + (", 다른 위치 점수가 거의 같음" if sg["ambiguous"] else "")
                                 + "). 구간을 넓히거나 search_sec으로 원곡 쪽을 좁힌다")
        mapping_used = "segments"
        decision["mapping_used"] = "segments"
        decision["mapping_reason"] = (f"segments {len(seg_out)}개 지정: 구간마다 따로 찾은 직선, 구간 밖은 전역 "
                                      + ("직선" if base_used == "linear" else "경로"))
        decision["text"] = f"구간별 정렬({len(seg_out)}구간, 구간 밖은 전역 {'직선' if base_used == 'linear' else '경로'})"
    base_map_linear = base_used == "linear"

    def to_orig(t):
        t = np.asarray(t, dtype=float)
        if mapping_used == "linear":
            return a + b * t
        if mapping_used == "segments":
            v = (a + b * t) if base_map_linear else path_map(t)
            for sg in seg_out:
                inside = (t >= float(sg["render_from_sec"])) & (t < float(sg["render_to_sec"]))
                v = np.where(inside, sg["_a"] + sg["_b"] * t, v)
            return v
        return path_map(t)

    mean_cost = float(cost.mean())
    ratio = mean_cost / base if base > 1e-9 else 1.0
    if mean_cost < 0.15 and ratio < 0.5:
        level, text = "높음", "경로 비용이 낮고 아무렇게나 맞춘 것보다 훨씬 작다"
    elif mean_cost < 0.3 and ratio < 0.75:
        level, text = "보통", "경로 비용이 중간이다. 마디 몇 개는 들어서 확인하는 것이 좋다"
    else:
        level, text = "낮음", "경로 비용이 높다. 다른 구간이거나 편곡이 원곡과 많이 달라 정렬을 믿기 어렵다"
    if rng is not None and ratio_at_bound and level != "낮음":
        level = "보통" if level == "높음" else "낮음"  # 범위 끝에 걸리면 한 단계 낮춘다
        text += " (템포 비율이 범위 끝에 걸려 한 단계 낮춤)"
    if repeat_ambiguous and level != "낮음":
        level = "보통" if level == "높음" else "낮음"
        text += " (다른 위치가 거의 같은 점수라 한 단계 낮춤)"

    out_measures = []
    for m in ms:
        o0 = float(to_orig([m["start_sec"]])[0])
        # 구간 경계에 걸린 마디 끝은 앞 구간 직선으로(구간은 [시작, 끝) 반열림)
        o1 = float(to_orig([m["end_sec"] - END_EPS])[0]) + b * END_EPS
        ob = [float(v) for v in to_orig(m["beats"])]
        dur_r = m["end_sec"] - m["start_sec"]
        out_measures.append({
            "measure": m["measure"], "render_start_sec": r(m["start_sec"]), "render_end_sec": r(m["end_sec"]),
            "orig_start_sec": r(o0), "orig_end_sec": r(o1),
            "beats_render": [r(v) for v in m["beats"]], "beats_orig": [r(v) for v in ob],
            "local_tempo_ratio": r((o1 - o0) / dur_r, 4) if dur_r > 0 else None,
            "deviation_from_line_sec": r(o0 - (a + b * m["start_sec"])),
        })
    # 경로(렌더초, 원곡초)를 3000점 이하로 줄여 저장 -> chroma/onset 이 재사용
    step = max(1, math.ceil(ux.size / 3000))
    if mapping_used == "segments":
        # 구간 경계 바로 앞뒤 점을 넣어 경로가 구간 사이에서 한 번에 뛰게 한다
        xs = list(ux[::step])
        for sg in seg_out:
            xs += [float(sg["render_from_sec"]), float(sg["render_to_sec"])]
        xs = np.unique(np.asarray(xs, dtype=float))
        path_x, path_y = xs, np.asarray(to_orig(xs), dtype=float)
    else:
        path_x = ux[::step]
        path_y = ((a + b * ux) if mapping_used == "linear" else uy_s)[::step]
    path = [[r(x), r(y)] for x, y in zip(path_x, path_y)]
    if mapping_used != "segments" and path[-1][0] != r(ux[-1]):
        last_y = (a + b * ux[-1]) if mapping_used == "linear" else uy_s[-1]
        path.append([r(ux[-1]), r(last_y)])
    orig_gaps = []
    if seg_out:
        # 원곡에서 어느 렌더 시각에도 대응하지 않는 사이(옮긴 원곡 시각이 앞으로 뛴 곳). onset_compare 가 그 원곡 온셋을 뺀다
        grid = np.arange(0.0, float(ms[-1]["end_sec"]) + GAP_STEP, GAP_STEP)
        og = np.asarray(to_orig(grid), dtype=float)
        for i in np.nonzero(np.diff(og) > GAP_MIN_SEC)[0]:
            orig_gaps.append([r(float(og[i])), r(float(og[i + 1]))])
    elapsed = time.perf_counter() - t_start
    return {
        "offset_sec": r(a, 4), "tempo_ratio": r(b, 5), "fit_method": method, "ratio_at_bound": ratio_at_bound,
        "mapping_requested": mapping, "mapping_used": mapping_used,
        "path_deviation_sec": decision["path_deviation_sec"], "beat_sec": decision["beat_sec"],
        "mapping_decision": decision,
        "path_fit": {"offset_sec": r(a_fit, 4), "tempo_ratio": r(b_fit, 5), "warp_score": r(s_fit, 4)},
        "warp_score": r(max(s_ref, s_fit) if rng is None else s_ref, 4),
        "global_search": gsearch,
        "alternatives": alternatives, "repeat_ambiguous": repeat_ambiguous,
        "fixed": None if fixed is None else {
            "given": fixed["given"], "anchor_render_sec": None if fixed["anchor"] is None else r(fixed["anchor"][0], 6),
            "anchor_orig_sec": None if fixed["anchor"] is None else r(fixed["anchor"][1], 6),
            "tempo_ratio": None if fixed["b"] is None else r(fixed["b"], 6),
            "searched": [k for k, v in (("offset", fixed["anchor"]), ("tempo_ratio", fixed["b"])) if v is None]},
        "segments": None if seg_out is None else [{k: v for k, v in sg.items() if not k.startswith("_")}
                                                   for sg in seg_out],
        "segments_applied": seg_out is not None,
        "orig_gaps": orig_gaps,
        "segment_candidates": jump_cands,
        "local_offsets": local_rows,
        "first_measure_orig_sec": out_measures[0]["orig_start_sec"],
        "confidence": {"level": level, "mean_path_cost": r(mean_cost, 4), "baseline_cost": r(base, 4),
                       "cost_ratio": r(ratio, 3), "text": text, "warnings": warnings_,
                       "explain": "경로 비용 = 정렬 경로 위 크로마 코사인 거리(0=같음, 1=직교)의 평균. "
                                  "baseline = 아무 프레임끼리 짝지었을 때의 평균."},
        "measures": out_measures,
        "path": path,
        "params": {"feature": "librosa.feature.chroma_cqt", "hop": HOP, "sr": io.ANALYSIS_SR, "mono": True,
                   "dtw": ("librosa.sequence.dtw(metric='cosine', subseq=True)" if rng is None and fixed is None else
                           f"librosa.sequence.dtw(C=코사인 거리, subseq=True), 전역 직선 ±{BAND_SEC:.1f}초 띠 밖은 "
                           f"비용 {BAND_PENALTY:g}"),
                   "tempo_ratio_range": None if rng is None else [rng[0], rng[1]],
                   "max_frames": MAX_FRAMES,
                   "stages": stages, "search_sec": [r(s0), r(s1)], "mapping": mapping_used,
                   "mapping_requested": mapping,
                   "auto_median_beats": auto_median_beats, "auto_max_beats": auto_max_beats,
                   "path_smoothing": f"직선 편차 {SMOOTH_SEC:.1f}초 이동 중앙값(경로 끝 가장자리 효과 보정)",
                   "tempo_ratio_meaning": "원곡 시간 / 렌더 시간(1보다 크면 렌더가 원곡보다 빠름)",
                   "offset_meaning": "렌더 0초가 원곡의 몇 초인지(직선 적합)"},
        "timing_sec": {"features": r(t_feat, 2), "dtw": r(t_dtw, 2), "total": r(elapsed, 2)},
        "orig_info": {"duration_sec": r(oinfo["duration_sec"]), "sample_rate": oinfo["sample_rate"],
                      "channels": oinfo["channels"]},
    }


def path_arrays(align: dict) -> tuple[np.ndarray, np.ndarray]:
    p = np.asarray(align.get("path") or [], dtype=float)
    if p.ndim != 2 or p.shape[0] < 2:
        ms = align["measures"]
        p = np.array([[m["render_start_sec"], m["orig_start_sec"]] for m in ms]
                     + [[ms[-1]["render_end_sec"], ms[-1]["orig_end_sec"]]], dtype=float)
    return p[:, 0], p[:, 1]


def orig_to_render(align: dict, t_orig: np.ndarray) -> np.ndarray:
    """원곡 시각 → 렌더 시각(정렬 경로의 역). 구간별 정렬의 orig_gaps(어느 렌더 시각에도 대응하지 않는 원곡) 안은 NaN."""
    pr, po = path_arrays(align)
    uo, ur = dedupe(po, pr)
    b = float(align.get("tempo_ratio") or 1.0)
    t = np.asarray(t_orig, dtype=float)
    v = np.interp(t, uo, ur)
    v = np.where(t < uo[0], ur[0] + (t - uo[0]) / b, v)
    v = np.where(t > uo[-1], ur[-1] + (t - uo[-1]) / b, v)
    for g in align.get("orig_gaps") or []:
        v = np.where((t > float(g[0])) & (t < float(g[1])), np.nan, v)
    return v
