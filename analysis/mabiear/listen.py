"""listening files: phone_version (one file -> phone WAV) and excerpt_ab (A = render, B = original, same timeline).

Phone format = mono PCM_16 at 22.05 kHz, peak-normalised to 0.98 (the hand-made "_폰용" files of 2026-11-08/09).
Output rule (differs from the JSON tools): these tools write *new* audio files whose names never equal an input
(suffix "_폰용", "_A_우리", "_B_원곡"), so they may write next to / below the input folder (the project's
`렌더/<곡>/변형/폰용/` layout). Protected folders (project file, --protect) are still refused (PROTECTED_PATH), a target equal
to an input is refused, and an existing target needs overwrite: true (EXISTS).
"""
from __future__ import annotations

import logging
import math
import os
from pathlib import Path
from typing import Any

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.listen")

PHONE_SR = 22050
PHONE_PEAK = 0.98
WAV_SUBTYPES = ("PCM_16", "PCM_24", "PCM_32", "FLOAT", "DOUBLE")


# ---------------------------------------------------------------- paths

def check_dir(d: Path, arg: str) -> Path:
    for root in io.protected_roots():
        if io.is_within(d, root):
            raise EarError("PROTECTED_PATH", f"보호 폴더 안에는 쓸 수 없습니다: {d}", {arg: str(d), "protected": str(root)})
    return d


def check_target(value: Any, arg: str, inputs: list[Path], overwrite: bool) -> Path:
    """An output WAV path: absolute, .wav, outside protected roots, not an input, EXISTS unless overwrite."""
    if isinstance(value, Path):
        value = str(value)
    if not isinstance(value, str) or not value.strip():
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 절대 경로 문자열이어야 합니다.", {arg: value})
    io.check_path_syntax(value.strip(), arg)
    p = Path(value.strip())
    if not p.is_absolute() or p.suffix.lower() != ".wav":
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 .wav로 끝나는 절대 경로여야 합니다.", {arg: value})
    check_dir(p.parent, arg)
    if any(io._norm(p) == io._norm(i) for i in inputs):
        raise EarError("PROTECTED_PATH", "출력 파일이 입력 파일과 같습니다.", {"path": str(p)})
    if p.exists() and not overwrite:
        raise EarError("EXISTS", f"이미 파일이 있습니다(덮어쓰려면 overwrite: true): {p}", {"path": str(p)})
    return p


def out_dir_arg(value: Any, default: Path) -> Path:
    if value in (None, ""):
        return check_dir(default, "out_dir")
    if not isinstance(value, str):
        raise EarError("BAD_ARGUMENT", "'out_dir'는 절대 경로 문자열이어야 합니다.", {"out_dir": value})
    io.check_path_syntax(value.strip(), "out_dir")
    d = Path(value.strip())
    if not d.is_absolute():
        raise EarError("BAD_ARGUMENT", "'out_dir'는 절대 경로여야 합니다.", {"out_dir": value})
    return check_dir(d, "out_dir")


def write_wav(target: Path, y, sr: int, subtype: str, overwrite: bool) -> None:
    """Atomic WAV write (temp file + replace/link), same EXISTS semantics as io.write_output."""
    sf = io._sf()
    tmp = io.temp_name(target, ".wav")
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        sf.write(str(tmp), y, sr, subtype=subtype, format="WAV")
    except (OSError, ValueError) as e:
        if tmp.exists():
            tmp.unlink()
        if io.is_path_syntax_error(e):
            raise EarError("BAD_ARGUMENT", f"출력 경로의 문법이 잘못됐습니다: {target}", {"path": str(target)})
        raise
    try:
        io.publish(tmp, target, overwrite)
    finally:
        if tmp.exists():
            tmp.unlink()
    log.info("wrote %s", target)


# ---------------------------------------------------------------- audio

def read_native(path: Path):
    """(frames x channels float32, sr, subtype)."""
    sf = io._sf()
    try:
        y, sr = sf.read(str(path), dtype="float32", always_2d=True)
        sub = sf.info(str(path)).subtype
    except Exception as e:
        raise EarError("AUDIO_READ_FAILED", f"오디오를 읽지 못했습니다: {path}", {"path": str(path), "reason": str(e)})
    return y, int(sr), sub


def to_phone_mono(y2d, sr: int, target_sr: int):
    import numpy as np

    y = y2d.mean(axis=1).astype(np.float32)
    if sr != target_sr and y.size:
        import librosa
        y = librosa.resample(y, orig_sr=sr, target_sr=target_sr, res_type="soxr_hq").astype(np.float32)
    return y


def scale_peak(y, peak: float, normalize: bool):
    """normalize: scale to exactly `peak` (up or down). Otherwise only scale down when above `peak`. Returns (y, gain)."""
    import numpy as np

    m = float(np.max(np.abs(y))) if y.size else 0.0
    if m <= 1e-12:
        return y, 1.0
    g = peak / m if (normalize or m > peak) else 1.0
    return (y * g).astype(np.float32), g


def cut(y, sr: int, start_sec: float, dur_sec: float):
    """Cut [start, start+dur) from y (1-D or frames x ch), zero-padding outside. Returns (seg, pad_start, pad_end)."""
    import numpy as np

    n = int(round(dur_sec * sr))
    x = start_sec * sr  # truncate toward zero (+1e-6 against float noise), as the hand-made 2026-11-08 excerpts
    s = int(math.copysign(math.floor(abs(x) + 1e-6), x))
    out = np.zeros((n,) + tuple(y.shape[1:]), dtype=np.float32)
    a, b = max(0, s), min(y.shape[0], s + n)
    if b > a:
        out[a - s:b - s] = y[a:b]
    pad_start = max(0, min(n, -s))
    pad_end = max(0, min(n, s + n - y.shape[0]))
    return out, pad_start / sr, pad_end / sr


def _stats(y, sr: int, path: Path) -> dict:
    import numpy as np

    pk = float(np.max(np.abs(y))) if y.size else 0.0
    return {"duration_sec": r(y.shape[0] / sr, 3), "peak": r(pk, 4), "peak_dbfs": r(io.db(pk), 2),
            "size_bytes": path.stat().st_size, "sample_rate": sr}


# ---------------------------------------------------------------- phone_version

def run_phone_version(src: Path, target: Path, sample_rate: int, peak: float, normalize: bool, overwrite: bool) -> dict:
    y2, sr, sub = read_native(src)
    in_peak = float(abs(y2).max()) if y2.size else 0.0
    y = to_phone_mono(y2, sr, sample_rate)
    y, g = scale_peak(y, peak, normalize)
    write_wav(target, y, sample_rate, "PCM_16", overwrite)
    st = _stats(y, sample_rate, target)
    return {"out_path": str(target), **st, "channels": 1, "subtype": "PCM_16", "gain_db": r(20 * math.log10(g), 2),
            "input": {"path": str(src), "sample_rate": sr, "channels": int(y2.shape[1]), "subtype": sub,
                      "duration_sec": r(y2.shape[0] / sr, 3), "peak": r(in_peak, 4)}}


# ---------------------------------------------------------------- excerpt_ab

def parse_measures(v: Any) -> list[tuple[int, int]]:
    def is_int(x):
        return isinstance(x, int) and not isinstance(x, bool)

    if isinstance(v, list) and len(v) == 2 and all(is_int(x) for x in v):
        v = [v]
    ok = isinstance(v, list) and 1 <= len(v) <= 64 and all(
        isinstance(p, list) and len(p) == 2 and all(is_int(x) for x in p) and 1 <= p[0] <= p[1] for p in v)
    if not ok:
        raise EarError("BAD_ARGUMENT", "'measures'는 [첫 마디, 끝 마디](1부터, 첫 ≤ 끝) 하나 또는 그 목록(64개까지)입니다.",
                       {"measures": v})
    return [(p[0], p[1]) for p in v]


def parse_grid(g: Any) -> dict:
    allowed = {"bpm", "measure1_sec", "beats_per_measure"}
    if not isinstance(g, dict) or set(g) - allowed or not {"bpm", "measure1_sec"} <= set(g):
        raise EarError("BAD_ARGUMENT", "'grid'는 {\"bpm\": 숫자, \"measure1_sec\": 숫자, \"beats_per_measure\": 정수(기본 4)}"
                       "입니다.", {"grid": g})

    def num(x, k, lo, hi):
        if isinstance(x, bool) or not isinstance(x, (int, float)) or not (lo <= float(x) <= hi):
            raise EarError("BAD_ARGUMENT", f"grid.{k}가 잘못됐습니다({lo}~{hi}).", {"grid": g})
        return float(x)

    bpm = num(g["bpm"], "bpm", 20.0, 400.0)
    m1 = num(g["measure1_sec"], "measure1_sec", -36000.0, 36000.0)
    bp = g.get("beats_per_measure", 4)
    if isinstance(bp, bool) or not isinstance(bp, int) or not (1 <= bp <= 16):
        raise EarError("BAD_ARGUMENT", "grid.beats_per_measure는 1~16 정수입니다.", {"grid": g})
    return {"bpm": bpm, "measure1_sec": m1, "beats_per_measure": bp, "measure_sec": 60.0 / bpm * bp}


def plan_windows(pairs, lead_in: float, tail: float, grid: dict | None, render_offset: float | None,
                 meta: dict | None, align: dict | None) -> tuple[list[dict], str]:
    """Per pair: B start/span on the original timeline, A start/span on the render timeline, common length.

    Returns (windows, render_timeline) where render_timeline names the source of the A times.
    """
    out = []
    if align is not None:
        by = {int(m["measure"]): m for m in align.get("measures") or []}
        if not by:
            raise EarError("BAD_ARGUMENT", "align_json에 measures 표가 없습니다.", {})
        for a, b in pairs:
            if a not in by or b not in by:
                raise EarError("BAD_ARGUMENT", f"align_json에 {a}~{b}마디가 없습니다.",
                               {"measures": [a, b], "available": [min(by), max(by)]})
            ma, mb = by[a], by[b]
            bs = float(ma["orig_start_sec"])
            as_ = float(ma["render_start_sec"])
            out.append({"from": a, "to": b, "b_measure_sec": bs, "b_span_sec": float(mb["orig_end_sec"]) - bs,
                        "a_measure_sec": as_, "a_span_sec": float(mb["render_end_sec"]) - as_})
        timeline = "align_json"
    else:
        ml = grid["measure_sec"]
        mt = {m["measure"]: m for m in meta["measures"]} if meta is not None and render_offset is None else None
        timeline = "render_meta" if mt is not None else "render_offset_sec"
        for a, b in pairs:
            bs, bspan = grid["measure1_sec"] + (a - 1) * ml, (b - a + 1) * ml
            if mt is not None:
                if a not in mt or b not in mt:
                    raise EarError("BAD_ARGUMENT", f"render_meta에 {a}~{b}마디가 없습니다.",
                                   {"measures": [a, b], "available": [min(mt), max(mt)]})
                as_, aspan = mt[a]["start_sec"], mt[b]["end_sec"] - mt[a]["start_sec"]
            else:
                as_, aspan = (render_offset or 0.0) + (a - 1) * ml, (b - a + 1) * ml
            out.append({"from": a, "to": b, "b_measure_sec": bs, "b_span_sec": bspan, "a_measure_sec": as_,
                        "a_span_sec": aspan})
    for w in out:
        w["length_sec"] = lead_in + max(w["a_span_sec"], w["b_span_sec"]) + tail
        w["b_start_sec"] = w["b_measure_sec"] - lead_in
        w["a_start_sec"] = w["a_measure_sec"] - lead_in
        w["drift_sec"] = w["b_span_sec"] - w["a_span_sec"]
    return out, timeline


def excerpt_names(prefix: str, a: int, b: int, label: str | None, phone: bool) -> tuple[str, str]:
    suf = "_폰용.wav" if phone else ".wav"
    lab = f"_{label}" if label else ""
    return f"{prefix}_{a}-{b}{lab}_A_우리{suf}", f"{prefix}_{a}-{b}_B_원곡{suf}"


def _prep(path: Path, phone: bool):
    y2, sr, sub = read_native(path)
    return y2, sr, ("PCM_16" if phone else (sub if sub in WAV_SUBTYPES else "PCM_16"))


def run_excerpt_ab(render: Path, original: Path, windows: list[dict], targets: list[tuple[Path, Path | None]],
                   phone: bool, overwrite: bool) -> list[dict]:
    """Cut at the native rate (sample = round(start * native sr)), then (phone) mix to mono, resample, normalise."""
    import numpy as np

    ya, sra, suba = _prep(render, phone)
    yb, srb, subb = _prep(original, phone)
    files = []
    for w, (ta, tb) in zip(windows, targets):
        n_len = w["length_sec"]
        for side, y, sr, sub, mstart, t in (("A", ya, sra, suba, w["a_measure_sec"], ta),
                                            ("B", yb, srb, subb, w["b_measure_sec"], tb)):
            if t is None:
                continue
            start = w["a_start_sec"] if side == "A" else w["b_start_sec"]
            seg, ps, pe = cut(y, sr, start, n_len)
            out_sr = sr
            if phone:
                seg = to_phone_mono(seg, sr, PHONE_SR)
                out_sr = PHONE_SR
                n = int(round(n_len * PHONE_SR))
                seg = seg[:n] if seg.size >= n else np.concatenate([seg, np.zeros(n - seg.size, np.float32)])
                seg, _ = scale_peak(seg, PHONE_PEAK, True)
            write_wav(t, seg, out_sr, sub, overwrite)
            files.append({"side": side, "measures": [w["from"], w["to"]], "path": str(t), "start_sec": r(start, 4),
                          "measure_start_sec": r(mstart, 4), "pad_start_sec": r(ps, 3), "pad_end_sec": r(pe, 3),
                          **_stats(seg, out_sr, t)})
    return files
