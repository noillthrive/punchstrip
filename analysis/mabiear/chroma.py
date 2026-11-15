"""크로마(12음 세기) 특징과 마디·박 단위 비교(chroma_compare).

기본값: librosa.feature.chroma_cqt, hop 512, 22050Hz 모노. 프레임마다 L2 정규화.
거의 무음인 프레임(파일 최대 RMS보다 50dB 넘게 작거나 -100dBFS 미만)은 12음이 같은
'평평한' 벡터로 둔다(무음끼리는 서로 닮고, 음이 있는 프레임과는 중간 정도로 다르게).
"""
from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Any

import numpy as np

from . import io
from .io import EarError, PITCH_NAMES, r

log = logging.getLogger("mabiear.chroma")

HOP = 512
SILENCE_REL_DB = -50.0
SILENCE_ABS = 1e-5
UNIFORM = np.full(12, 1.0 / np.sqrt(12.0))
CHUNK_SEC = 30.0      # 긴 신호의 크로마는 30초 블록으로 나눠 계산(메모리 제한)
CHUNK_PAD_SEC = 2.0   # 블록 앞뒤 겹침(가장자리 효과 제거)

_feat_cache: dict[tuple, Any] = {}
_feat_order: list[tuple] = []
_feat_lock = threading.Lock()


class Chroma:
    """chroma 특징 묶음. unit: L2 정규화(무음=평평), raw: 프레임 최대=1(무음=0), times: 프레임 중심(초)."""

    def __init__(self, unit: np.ndarray, raw: np.ndarray, times: np.ndarray, rms: np.ndarray, sr: int):
        self.unit = unit
        self.raw = raw
        self.times = times
        self.rms = rms
        self.sr = sr

    @property
    def n(self) -> int:
        return self.unit.shape[1]


def compute(y: np.ndarray, sr: int, t0: float = 0.0) -> Chroma:
    import librosa

    if y.size < HOP * 4:
        y = np.pad(y, (0, HOP * 4 - y.size))
    raw = _chroma_chunked(y, sr)  # 프레임마다 최대 1
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=HOP)[0]
    n = min(raw.shape[1], rms.size)
    raw = raw[:, :n].astype(np.float64)
    rms = rms[:n].astype(np.float64)
    thr = max(SILENCE_ABS, float(rms.max(initial=0.0)) * 10 ** (SILENCE_REL_DB / 20.0))
    silent = rms < thr
    raw[:, silent] = 0.0
    norms = np.linalg.norm(raw, axis=0)
    unit = np.empty_like(raw)
    ok = norms > 1e-9
    unit[:, ok] = raw[:, ok] / norms[ok]
    unit[:, ~ok] = UNIFORM[:, None]
    times = t0 + np.arange(n) * HOP / float(sr)
    return Chroma(unit, raw, times, rms, sr)


def _chroma_chunked(y: np.ndarray, sr: int) -> np.ndarray:
    """긴 신호는 CHUNK_SEC 단위(앞뒤 CHUNK_PAD_SEC 겹침)로 나눠 chroma_cqt 를 구해 이어 붙인다.

    librosa 의 CQT 는 신호 전체 STFT 를 한 번에 만들어 216초 곡이면 1GB 넘게 잡는다. 블록 시작을
    hop 의 배수로 두어 프레임 격자가 전체 계산과 같게 하고, 겹친 가장자리 프레임은 버린다.
    """
    import librosa

    n_frames = 1 + y.size // HOP
    block = int(CHUNK_SEC * sr) // HOP * HOP
    pad = int(CHUNK_PAD_SEC * sr) // HOP * HOP
    if y.size <= block + 2 * pad:
        return librosa.feature.chroma_cqt(y=y, sr=sr, hop_length=HOP)[:, :n_frames]
    out = np.zeros((12, n_frames), dtype=np.float32)
    for s0 in range(0, y.size, block):
        a = max(0, s0 - pad)
        b = min(y.size, s0 + block + pad)
        c = librosa.feature.chroma_cqt(y=y[a:b], sr=sr, hop_length=HOP)
        f0 = s0 // HOP                     # 이 블록이 맡는 전체 프레임 범위
        f1 = min(n_frames, (s0 + block) // HOP)
        off = (s0 - a) // HOP              # 블록 안에서 f0 의 위치
        k = min(f1 - f0, c.shape[1] - off)
        out[:, f0:f0 + k] = c[:, off:off + k]
    return out


def for_file(path: Path, offset: float = 0.0, duration: float | None = None) -> Chroma:
    """파일(또는 그 일부)의 크로마. 같은 파일·구간은 프로세스 안에서 재사용한다."""
    st = path.stat()
    key = (str(path).lower(), st.st_mtime_ns, st.st_size, round(offset, 4),
           None if duration is None else round(duration, 4))
    with _feat_lock:
        if key in _feat_cache:
            return _feat_cache[key]
    t = time.perf_counter()
    y, sr = io.load_mono(path, offset=offset, duration=duration)
    c = compute(y, sr, t0=offset)
    log.info("chroma %s (%.1fs~, %d frames) %.2fs", path.name, offset, c.n, time.perf_counter() - t)
    with _feat_lock:
        _feat_cache[key] = c
        _feat_order.append(key)
        while len(_feat_order) > 6:
            _feat_cache.pop(_feat_order.pop(0), None)
    return c


def seg_index(times: np.ndarray, t0: float, t1: float) -> np.ndarray:
    idx = np.nonzero((times >= t0) & (times < t1))[0]
    if idx.size == 0 and times.size:
        mid = 0.5 * (t0 + t1)
        idx = np.array([int(np.argmin(np.abs(times - mid)))])
    return idx


def seg_unit(c: Chroma, t0: float, t1: float) -> np.ndarray:
    idx = seg_index(c.times, t0, t1)
    v = c.unit[:, idx].mean(axis=1)
    nv = np.linalg.norm(v)
    return v / nv if nv > 1e-12 else UNIFORM.copy()


def seg_profile(c: Chroma, t0: float, t1: float) -> np.ndarray:
    """구간 평균 raw 크로마를 최대 1로 맞춘 12음 세기(무음이면 0)."""
    idx = seg_index(c.times, t0, t1)
    v = c.raw[:, idx].mean(axis=1)
    mx = v.max(initial=0.0)
    return v / mx if mx > 1e-9 else np.zeros(12)


def cos(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.clip(np.dot(a, b), -1.0, 1.0))


def note_diff(orig_prof: np.ndarray, rend_prof: np.ndarray, hi: float, lo: float) -> tuple[list[str], list[str]]:
    missing = [PITCH_NAMES[i] for i in range(12) if orig_prof[i] >= hi and rend_prof[i] < lo]
    added = [PITCH_NAMES[i] for i in range(12) if rend_prof[i] >= hi and orig_prof[i] < lo]
    return missing, added


ALIGN_SCHEMA = "mabiear.align/1"


def load_align(path: Path) -> dict:
    a = io.read_json(path, "align_json")
    if a.get("schema") != ALIGN_SCHEMA or not isinstance(a.get("measures"), list):
        raise EarError("BAD_ARGUMENT", f"align_json이 '{ALIGN_SCHEMA}' 형식이 아닙니다.",
                       {"path": str(path), "schema": a.get("schema")})
    return a


def orig_window(amap: dict[int, dict], measures: list[dict], margin: float = 1.0) -> tuple[float, float]:
    starts = [amap[m["measure"]]["orig_start_sec"] for m in measures if m["measure"] in amap]
    ends = [amap[m["measure"]]["orig_end_sec"] for m in measures if m["measure"] in amap]
    lo = max(0.0, min(starts) - margin)
    hi = max(ends) + margin
    return lo, hi


def run_chroma_compare(render_wav: Path, meta: dict, original: Path, align: dict, measures: list[dict],
                       missing_hi: float, missing_lo: float, top_n: int) -> dict:
    amap = {int(m["measure"]): m for m in align["measures"]}
    use = [m for m in measures if m["measure"] in amap]
    if not use:
        raise EarError("BAD_ARGUMENT", "align_json에 고른 마디가 없습니다.",
                       {"measures": [m["measure"] for m in measures][:20]})
    cr = for_file(render_wav)
    lo, hi = orig_window(amap, use)
    co = for_file(original, offset=lo, duration=hi - lo)
    rows = []
    for m in use:
        am = amap[m["measure"]]
        rsegs = io.beat_segments(m)
        ob = list(am.get("beats_orig") or [am["orig_start_sec"]])
        if len(ob) != len(m["beats"]):
            ob = [am["orig_start_sec"]]
            rsegs = [(m["start_sec"], m["end_sec"])]
        osegs = [(ob[i], (ob + [am["orig_end_sec"]])[i + 1]) for i in range(len(ob))]
        beats = []
        for bi, ((r0, r1), (o0, o1)) in enumerate(zip(rsegs, osegs), start=1):
            if o1 <= o0:
                o1 = o0 + 1e-3
            beats.append({"beat": bi, "render": [r(r0), r(r1)], "orig": [r(o0), r(o1)],
                          "cos": r(cos(seg_unit(cr, r0, r1), seg_unit(co, o0, o1)), 4)})
        mcos = cos(seg_unit(cr, m["start_sec"], m["end_sec"]), seg_unit(co, am["orig_start_sec"], am["orig_end_sec"]))
        rp = seg_profile(cr, m["start_sec"], m["end_sec"])
        op = seg_profile(co, am["orig_start_sec"], am["orig_end_sec"])
        missing, added = note_diff(op, rp, missing_hi, missing_lo)
        score = float(np.mean([b["cos"] for b in beats])) if beats else mcos
        rows.append({"measure": m["measure"], "score": r(score, 4), "measure_cos": r(mcos, 4),
                     "missing": missing, "added": added, "beats": beats,
                     "render_profile": [r(v, 3) for v in rp], "orig_profile": [r(v, 3) for v in op]})
    ranked = sorted(rows, key=lambda x: (x["score"] if x["score"] is not None else 2.0))
    bottom = [{"measure": x["measure"], "score": x["score"], "missing": x["missing"], "added": x["added"]}
              for x in ranked[:top_n]]
    mean = float(np.mean([x["score"] for x in rows]))
    return {"measures": rows, "mean_score": r(mean, 4), "bottom": bottom,
            "params": {"feature": "librosa.feature.chroma_cqt", "hop": HOP, "sr": io.ANALYSIS_SR,
                       "score": "박 단위 평균 크로마(L2 정규화)의 코사인 유사도를 마디 안에서 평균",
                       "missing_hi": missing_hi, "missing_lo": missing_lo,
                       "orig_window_sec": [r(lo), r(hi)]}}


def run_compare_renders(a_wav: Path, a_meta: dict, b_wav: Path, b_meta: dict, a_meas: list[dict],
                        missing_hi: float, missing_lo: float, top_n: int, b_offset: int = 0) -> dict:
    """A의 마디 n 을 B의 마디 n + b_offset 과 짝짓는다(짝 없는 A 마디는 건너뛰고 skipped_measures 에 적는다)."""
    bmap = {m["measure"]: m for m in b_meta["measures"]}
    common = [m for m in a_meas if m["measure"] + b_offset in bmap]
    if not common:
        if b_offset == 0:
            msg = "두 메타에 같은 마디 번호가 없습니다."
        else:
            msg = f"A 마디 + {b_offset}에 해당하는 B 마디가 없습니다(b_measure_offset {b_offset})."
        raise EarError("BAD_ARGUMENT", msg,
                       {"a": [m["measure"] for m in a_meas][:20], "b": list(bmap)[:20], "b_measure_offset": b_offset})
    ca = for_file(a_wav)
    cb = for_file(b_wav)
    ya, sr = io.load_mono(a_wav)
    yb, _ = io.load_mono(b_wav)

    def seg_db(y: np.ndarray, t0: float, t1: float) -> float:
        s = y[int(t0 * sr): max(int(t0 * sr) + 1, int(t1 * sr))]
        return io.db(float(np.sqrt(np.mean(s.astype(np.float64) ** 2))) if s.size else 0.0)

    rows = []
    for ma in common:
        mb = bmap[ma["measure"] + b_offset]
        sa, sb = io.beat_segments(ma), io.beat_segments(mb)
        if len(sa) == len(sb) and sa:
            bc = [cos(seg_unit(ca, *x), seg_unit(cb, *y)) for x, y in zip(sa, sb)]
            score = float(np.mean(bc))
        else:
            bc = []
            score = cos(seg_unit(ca, ma["start_sec"], ma["end_sec"]), seg_unit(cb, mb["start_sec"], mb["end_sec"]))
        pa = seg_profile(ca, ma["start_sec"], ma["end_sec"])
        pb = seg_profile(cb, mb["start_sec"], mb["end_sec"])
        only_a, only_b = note_diff(pa, pb, missing_hi, missing_lo)
        da, dbb = seg_db(ya, ma["start_sec"], ma["end_sec"]), seg_db(yb, mb["start_sec"], mb["end_sec"])
        rows.append({"measure": ma["measure"], "b_measure": mb["measure"], "score": r(score, 4),
                     "beat_cos": [r(v, 4) for v in bc],
                     "a_rms_dbfs": r(da, 2), "b_rms_dbfs": r(dbb, 2), "rms_diff_db": r(dbb - da, 2),
                     "only_a": only_a, "only_b": only_b})
    ranked = sorted(rows, key=lambda x: x["score"])
    skipped = [m["measure"] for m in a_meas if m["measure"] + b_offset not in bmap]  # A 마디 번호
    return {"measures": rows, "mean_score": r(float(np.mean([x["score"] for x in rows])), 4),
            "bottom": [{"measure": x["measure"], "b_measure": x["b_measure"], "score": x["score"],
                        "rms_diff_db": x["rms_diff_db"], "only_a": x["only_a"], "only_b": x["only_b"]}
                       for x in ranked[:top_n]],
            "b_measure_offset": b_offset,
            "skipped_measures": skipped, "skipped_count": len(skipped),
            "params": {"feature": "librosa.feature.chroma_cqt", "hop": HOP, "sr": io.ANALYSIS_SR,
                       "rms": "모노(채널 평균) 마디 RMS, dBFS. rms_diff_db = b - a",
                       "pairing": "A 마디 n ↔ B 마디 n + b_measure_offset (measure = A 번호, b_measure = B 번호)",
                       "missing_hi": missing_hi, "missing_lo": missing_lo}}
