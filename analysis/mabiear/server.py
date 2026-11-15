"""mabi-ear MCP 서버(stdio).

실행: python -I -X utf8 -m mabiear.server [--out-dir <폴더>] [--protect <폴더>]... [--log <파일>]
stdout 은 MCP(JSON-RPC) 전용이다. 이 패키지 어디에서도 print() 를 쓰지 않고, 로그와 경고는
logging 으로 stderr 와 analysis/.cache/ear.log 에 쓴다. numpy/librosa 는 첫 도구 호출 때 불린다.
"""

import time

_T0 = time.perf_counter()

import argparse  # noqa: E402
import types  # noqa: E402
import typing  # noqa: E402
import json  # noqa: E402
import logging  # noqa: E402
import sys  # noqa: E402
import threading  # noqa: E402
import warnings  # noqa: E402
from datetime import datetime  # noqa: E402
from pathlib import Path  # noqa: E402
from typing import Any, Callable  # noqa: E402

from . import CACHE_DIR, __version__  # noqa: E402
from . import io  # noqa: E402
from .io import EarError, r  # noqa: E402

log = logging.getLogger("mabiear")
_tool_lock = threading.Lock()  # 도구는 한 번에 하나씩(단일 스레드 처리)

SERVER_NAME = "mabi-ear"


def setup_logging(log_path: Path | None) -> None:
    root = logging.getLogger()
    if getattr(root, "_mabiear_configured", False):
        return
    root.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    h = logging.StreamHandler(sys.stderr)
    h.setFormatter(fmt)
    root.addHandler(h)
    if log_path is not None:
        try:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            fh = logging.FileHandler(log_path, encoding="utf-8")
            fh.setFormatter(fmt)
            root.addHandler(fh)
        except OSError as e:  # 로그 파일을 못 열어도 서버는 뜬다
            log.warning("log file unavailable: %s", e)
    logging.captureWarnings(True)  # warnings -> logging(stderr/파일), stdout 으로 새지 않게
    warnings.simplefilter("default")
    for noisy in ("numba", "matplotlib", "httpx", "httpcore"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    root._mabiear_configured = True  # type: ignore[attr-defined]


# ---------------------------------------------------------------- 응답

def _result(payload: dict, is_error: bool):
    from mcp_types import CallToolResult, TextContent

    return CallToolResult(content=[TextContent(type="text", text=io.dumps(payload))], is_error=is_error)


def _err(code: str, message: str, details: dict | None = None):
    return _result(EarError(code, message, details).to_dict(), True)


def _run(tool: str, fn: Callable[[], dict]):
    t = time.perf_counter()
    with _tool_lock:
        try:
            out = fn()
            out = {"ok": True, **out}
            log.info("tool %s ok %.2fs", tool, time.perf_counter() - t)
            return _result(out, False)
        except EarError as e:
            log.info("tool %s error %s: %s", tool, e.code, e.message)
            return _result(e.to_dict(), True)
        except MemoryError:
            log.exception("tool %s out of memory", tool)
            return _err("INTERNAL", "메모리가 부족합니다. 더 짧은 구간(search_sec)으로 다시 시도하세요.", {"tool": tool})
        except Exception as e:  # 도구는 서버를 죽이지 않는다
            if io.is_path_syntax_error(e):  # 미리 못 거른 경로 문법 오류(WinError 123, NUL 등)
                log.info("tool %s path syntax error: %s", tool, e)
                return _err("BAD_ARGUMENT", "경로의 문법이 잘못됐습니다(쓸 수 없는 문자 등).",
                            {"tool": tool, "reason": str(e)[:500]})
            log.exception("tool %s internal error", tool)
            return _err("INTERNAL", f"내부 오류: {type(e).__name__}", {"tool": tool, "reason": str(e)[:500]})


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _doc(schema: str, tool: str, inputs: dict, body: dict, summary_text: str) -> dict:
    return {"schema": schema, "tool": tool, "engine": f"mabi-ear {__version__}", "created_at": _now(),
            "caution": "유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다.",
            "inputs": inputs, "summary_text": summary_text, **body}


def _bool(v: Any, arg: str) -> bool:
    if isinstance(v, bool):
        return v
    raise EarError("BAD_ARGUMENT", f"'{arg}'는 true/false 여야 합니다.", {arg: v})


def _num(v: Any, arg: str, lo: float | None = None, hi: float | None = None) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 숫자여야 합니다.", {arg: v})
    v = float(v)
    if v != v or v in (float("inf"), float("-inf")):
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 유한한 숫자여야 합니다(NaN·무한대 안 됨).", {arg: str(v)})
    if (lo is not None and v < lo) or (hi is not None and v > hi):
        raise EarError("BAD_ARGUMENT", f"'{arg}'가 범위({lo}~{hi}) 밖입니다.", {arg: v})
    return v


def _int(v: Any, arg: str, lo: int, hi: int) -> int:
    if isinstance(v, bool) or not isinstance(v, int) or not (lo <= v <= hi):
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 {lo}~{hi} 정수여야 합니다.", {arg: v})
    return v


# ---------------------------------------------------------------- 도구 구현(동기)

def tool_audio_info(path, save=False, out_dir=None, name=None, overwrite=False) -> dict:
    p = io.input_file(path, "path")
    save, overwrite = _bool(save, "save"), _bool(overwrite, "overwrite")
    target = None
    if save:
        target = io.plan_output(out_dir, io.check_name(name, p), "_info.json", overwrite, [p])
    info = io.audio_stats(p)
    info["duration_sec"] = r(info["duration_sec"], 3)
    text = (f"{p.name}: {info['duration_sec']:.2f}초, {info['sample_rate']}Hz, {info['channels']}채널"
            f" ({info['format']}/{info['subtype']})\n"
            f"peak {info['peak_dbfs']}dBFS, RMS {info['rms_dbfs']}dBFS")
    if target is not None:
        io.write_json(target, _doc("mabiear.info/1", "audio_info", {"path": str(p)}, {"info": info}, text), overwrite)
    return {"out_path": str(target) if target else None, "summary_text": text, "summary": info}


def tool_align(render_wav, render_meta, original_audio, search_sec=None, mapping="auto",
               out_dir=None, name=None, overwrite=False, tempo_ratio_range=(0.9, 1.1),
               auto_median_beats=0.5, auto_max_beats=1.0, segments=None, offset_sec=None, first_downbeat_sec=None,
               measure_offset=None, tempo_ratio=None, orig_bpm=None) -> dict:
    from .align import MAPPINGS, SCHEMA, parse_fixed, parse_ratio_range, run_align

    rw = io.input_file(render_wav, "render_wav")
    rm = io.input_file(render_meta, "render_meta")
    oa = io.input_file(original_audio, "original_audio")
    overwrite = _bool(overwrite, "overwrite")
    if mapping not in MAPPINGS:
        raise EarError("BAD_ARGUMENT", "'mapping'은 'auto', 'path', 'linear' 중 하나입니다.", {"mapping": mapping})
    amed = _num(auto_median_beats, "auto_median_beats", 0.0, 16.0)
    amax = _num(auto_max_beats, "auto_max_beats", 0.0, 16.0)
    rng = parse_ratio_range(None if tempo_ratio_range is None else
                            (list(tempo_ratio_range) if isinstance(tempo_ratio_range, tuple) else tempo_ratio_range))
    offs = None if offset_sec is None else _num(offset_sec, "offset_sec", -36000.0, 36000.0)
    fds = None if first_downbeat_sec is None else _num(first_downbeat_sec, "first_downbeat_sec", -36000.0, 36000.0)
    mof = None if measure_offset is None else _int(measure_offset, "measure_offset", 1, 100000)
    tr_ = None if tempo_ratio is None else _num(tempo_ratio, "tempo_ratio", 0.5, 2.0)
    obpm = None if orig_bpm is None else _num(orig_bpm, "orig_bpm", 20.0, 400.0)
    target = io.plan_output(out_dir, io.check_name(name, rw), "_align.json", overwrite, [rw, rm, oa])
    meta = io.load_meta(rm)
    fixed = parse_fixed(meta, offs, fds, mof, tr_, obpm)
    res = run_align(rw, meta, oa, search_sec, mapping, rng, amed, amax, segments, fixed)
    c = res["confidence"]
    m0, m1 = res["measures"][0], res["measures"][-1]
    mode = "자유 DTW" if rng is None else f"비율 범위 {rng[0]}~{rng[1]}"
    if fixed is not None:
        fx = res["fixed"]
        mode = ("고정: " + ", ".join(f"{k} {v}" for k, v in fixed["given"].items())
                + (f"; 찾은 것: {', '.join(fx['searched'])}" if fx["searched"] else ""))
    text = (f"렌더 0초 = 원곡 {res['offset_sec']:.3f}초, 템포 비율 {res['tempo_ratio']:.4f}(원곡÷렌더, {mode})\n"
            f"{m0['measure']}마디 시작 = 원곡 {m0['orig_start_sec']:.2f}초, {m1['measure']}마디 끝 = 원곡 {m1['orig_end_sec']:.2f}초\n"
            f"{res['mapping_decision']['text']}\n"
            + "".join(f"구간 {sg['render_from_sec']:.1f}~{sg['render_to_sec']:.1f}초: 원곡 {sg['orig_from_sec']:.2f}~"
                      f"{sg['orig_to_sec']:.2f}초, 비율 {sg['tempo_ratio']:.4f}{'(전역 고정)' if sg['ratio_fixed'] else ''}, "
                      f"신뢰도 {sg['confidence']}(warp {sg['warp_score']:.3f})\n" for sg in (res.get("segments") or []))
            + f"정렬 신뢰도 {c['level']}(경로 비용 {c['mean_path_cost']:.3f} / 무작위 {c['baseline_cost']:.3f}), "
            f"{res['timing_sec']['total']:.1f}초 걸림")
    for w in c.get("warnings") or []:
        text += f"\n경고: {w}"
    inputs = {"render_wav": str(rw), "render_meta": str(rm), "original_audio": str(oa), "search_sec": search_sec,
              "tempo_ratio_range": None if rng is None else list(rng), "mapping": mapping,
              "auto_median_beats": amed, "auto_max_beats": amax, "segments": segments, "offset_sec": offs,
              "first_downbeat_sec": fds, "measure_offset": mof, "tempo_ratio": tr_, "orig_bpm": obpm}
    io.write_json(target, _doc(SCHEMA, "align", inputs, res, text), overwrite)
    summary = {k: res[k] for k in ("offset_sec", "tempo_ratio", "first_measure_orig_sec", "fit_method", "warp_score",
                                   "ratio_at_bound", "fixed", "alternatives", "repeat_ambiguous")}
    summary["confidence"] = c["level"]
    summary["warnings"] = c.get("warnings") or []
    summary["tempo_ratio_range"] = None if rng is None else list(rng)
    summary["mapping_requested"] = res["mapping_requested"]
    summary["mapping_used"] = res["mapping_used"]
    summary["mapping_reason"] = res["mapping_decision"].get("mapping_reason")
    summary["segments"] = res["segments"]
    summary["segment_candidates"] = res["segment_candidates"]
    summary["orig_gaps"] = res["orig_gaps"]
    summary["path_deviation_sec"] = res["path_deviation_sec"]
    summary["path_deviation_beats"] = res["mapping_decision"]["path_deviation_beats"]
    summary["beat_sec"] = res["beat_sec"]
    summary["mean_path_cost"] = c["mean_path_cost"]
    summary["measures"] = [{"measure": m["measure"], "orig_start_sec": m["orig_start_sec"],
                            "orig_end_sec": m["orig_end_sec"]} for m in res["measures"][:40]]
    summary["elapsed_sec"] = res["timing_sec"]["total"]
    return {"out_path": str(target), "summary_text": text, "summary": summary}


def _common_compare_inputs(render_wav, render_meta, original_audio, align_json):
    rw = io.input_file(render_wav, "render_wav")
    rm = io.input_file(render_meta, "render_meta")
    oa = io.input_file(original_audio, "original_audio")
    aj = io.input_file(align_json, "align_json")
    return rw, rm, oa, aj


def tool_chroma_compare(render_wav, render_meta, original_audio, align_json, from_measure=None, to_measure=None,
                        missing_hi=0.5, missing_lo=0.25, top_n=5, out_dir=None, name=None, overwrite=False) -> dict:
    from .chroma import load_align, run_chroma_compare

    rw, rm, oa, aj = _common_compare_inputs(render_wav, render_meta, original_audio, align_json)
    overwrite = _bool(overwrite, "overwrite")
    hi = _num(missing_hi, "missing_hi", 0.0, 1.0)
    lo = _num(missing_lo, "missing_lo", 0.0, 1.0)
    top_n = _int(top_n, "top_n", 1, 100)
    target = io.plan_output(out_dir, io.check_name(name, rw), "_chroma.json", overwrite, [rw, rm, oa], [aj])
    meta = io.load_meta(rm)
    al = load_align(aj)
    sel = io.select_measures(meta, from_measure, to_measure)
    res = run_chroma_compare(rw, meta, oa, al, sel, hi, lo, top_n)
    worst = res["bottom"][0]
    lines = [f"{len(res['measures'])}마디 크로마 유사도 평균 {res['mean_score']:.3f}(1=같은 음 구성)",
             "가장 낮은 마디: " + ", ".join(f"{b['measure']}({b['score']:.2f})" for b in res["bottom"]),
             f"{worst['measure']}마디 빠진 음: {' '.join(worst['missing']) or '없음'}, 더한 음: {' '.join(worst['added']) or '없음'}"]
    text = "\n".join(lines)
    inputs = {"render_wav": str(rw), "render_meta": str(rm), "original_audio": str(oa), "align_json": str(aj),
              "from_measure": from_measure, "to_measure": to_measure}
    io.write_json(target, _doc("mabiear.chroma/1", "chroma_compare", inputs, res, text), overwrite)
    return {"out_path": str(target), "summary_text": text,
            "summary": {"mean_score": res["mean_score"], "bottom": res["bottom"], "measures": len(res["measures"])}}


def tool_onset_compare(render_wav, render_meta, original_audio, align_json, from_measure=None, to_measure=None,
                       tolerance_ms=50.0, top_n=5, out_dir=None, name=None, overwrite=False) -> dict:
    from .chroma import load_align
    from .onset import run_onset_compare

    rw, rm, oa, aj = _common_compare_inputs(render_wav, render_meta, original_audio, align_json)
    overwrite = _bool(overwrite, "overwrite")
    tol = _num(tolerance_ms, "tolerance_ms", 1.0, 1000.0)
    top_n = _int(top_n, "top_n", 1, 100)
    target = io.plan_output(out_dir, io.check_name(name, rw), "_onset.json", overwrite, [rw, rm, oa], [aj])
    meta = io.load_meta(rm)
    al = load_align(aj)
    sel = io.select_measures(meta, from_measure, to_measure)
    res = run_onset_compare(rw, oa, al, sel, tol, top_n)
    mf = res["mean_f"]
    text = (f"{len(res['measures'])}마디 온셋 F-점수 평균 {mf if mf is None else f'{mf:.3f}'} (허용 {tol:.0f}ms)\n"
            "가장 낮은 마디: " + (", ".join(f"{b['measure']}({b['f']:.2f})" for b in res["bottom"]) or "없음") + "\n"
            f"어긋난 박 {len(res['mismatched_beats'])}개")
    inputs = {"render_wav": str(rw), "render_meta": str(rm), "original_audio": str(oa), "align_json": str(aj),
              "from_measure": from_measure, "to_measure": to_measure}
    io.write_json(target, _doc("mabiear.onset/1", "onset_compare", inputs, res, text), overwrite)
    return {"out_path": str(target), "summary_text": text,
            "summary": {"mean_f": mf, "bottom": res["bottom"], "mismatched_beats": len(res["mismatched_beats"]),
                        "mismatched_first": res["mismatched_beats"][:10]}}


def _ratio_text(v) -> str:
    return "-" if v is None else f"{v:.2f}"


def tool_richness_compare(render_wav, render_meta, original_audio, align_json, from_measure=None, to_measure=None,
                          name=None, out_dir=None, overwrite=False, top_n=3, low_hz=250.0, high_hz=2000.0) -> dict:
    from .chroma import load_align
    from .richness import run_richness_compare

    rw, rm, oa, aj = _common_compare_inputs(render_wav, render_meta, original_audio, align_json)
    overwrite = _bool(overwrite, "overwrite")
    top_n = _int(top_n, "top_n", 1, 100)
    lo = _num(low_hz, "low_hz", 20.0, 10000.0)
    hi = _num(high_hz, "high_hz", 20.0, 10000.0)
    if not lo < hi:
        raise EarError("BAD_ARGUMENT", "'low_hz'는 'high_hz'보다 작아야 합니다.", {"low_hz": lo, "high_hz": hi})
    target = io.plan_output(out_dir, io.check_name(name, rw), "_richness.json", overwrite, [rw, rm, oa], [aj])
    meta = io.load_meta(rm)
    al = load_align(aj)
    sel = io.select_measures(meta, from_measure, to_measure)
    res = run_richness_compare(rw, oa, al, sel, lo, hi, top_n)
    sm = res["summary"]
    mr, tr = sm["mean_onset_ratio"], sm["total_onset_ratio"]
    rel = sm["band_db_rel_mean"]
    lines = [f"{sm['measures']}마디 음 시작 수 렌더/원곡 마디 평균 {_ratio_text(mr)}"
             f"(합 {sm['render_onsets']}/{sm['orig_onsets']}개 = {_ratio_text(tr)})",
             f"대역 세기 차(렌더−원곡, 전체 세기 차 {sm['level_offset_db']:+.1f}dB를 뺀 값): "
             f"저(<{lo:g}Hz) {rel['low']:+.1f}dB, 중 {rel['mid']:+.1f}dB, 고(≥{hi:g}Hz) {rel['high']:+.1f}dB",
             "가장 빈 마디: " + ", ".join(
                 f"{x['measure']}({_ratio_text(x['onset_ratio'])}, {x['render_onsets']}/{x['orig_onsets']}개)"
                 for x in res["emptiest"])]
    text = "\n".join(lines)
    inputs = {"render_wav": str(rw), "render_meta": str(rm), "original_audio": str(oa), "align_json": str(aj),
              "from_measure": from_measure, "to_measure": to_measure, "low_hz": lo, "high_hz": hi}
    io.write_json(target, _doc("mabiear.richness/1", "richness_compare", inputs, res, text), overwrite)
    return {"out_path": str(target), "summary_text": text, "summary": sm}


def tool_balance(render_meta, mix_wav=None, from_measure=None, to_measure=None, buried_db=12.0, silence_db=-60.0,
                 low_hz=200.0, low_min_db=-40.0, low_rel_db=6.0, out_dir=None, name=None, overwrite=False,
                 buried_mode="relative", buried_margin_db=12.0, low_min_parts=3,
                 buried_include_unpitched=False, weighting="k", lead_part=None, margin_band=None,
                 sections=None) -> dict:
    from .balance import BURIED_MODES, WEIGHTINGS, check_margin_band, resolve_files, run_balance

    rm = io.input_file(render_meta, "render_meta")
    mw = io.input_file(mix_wav, "mix_wav") if mix_wav not in (None, "") else None
    overwrite = _bool(overwrite, "overwrite")
    p = dict(buried_db=_num(buried_db, "buried_db", 0, 60), silence_db=_num(silence_db, "silence_db", -120, 0),
             low_hz=_num(low_hz, "low_hz", 20, 2000), low_min_db=_num(low_min_db, "low_min_db", -120, 0),
             low_rel_db=_num(low_rel_db, "low_rel_db", 0, 60),
             buried_margin_db=_num(buried_margin_db, "buried_margin_db", 0, 60),
             low_min_parts=_int(low_min_parts, "low_min_parts", 1, 64))
    if buried_mode not in BURIED_MODES:
        raise EarError("BAD_ARGUMENT", "'buried_mode'는 'relative' 또는 'fixed' 여야 합니다.",
                       {"buried_mode": buried_mode})
    p["buried_mode"] = buried_mode
    p["buried_include_unpitched"] = _bool(buried_include_unpitched, "buried_include_unpitched")
    if weighting not in WEIGHTINGS:
        raise EarError("BAD_ARGUMENT", "'weighting'은 'k' 또는 'rms' 여야 합니다.", {"weighting": weighting})
    p["weighting"] = weighting
    check_margin_band(margin_band)
    p["lead_part"], p["margin_band"], p["sections"] = lead_part, margin_band, sections
    meta = io.load_meta(rm)
    files = resolve_files(meta, rm)
    inputs_paths = [rm] + [f["path"] for f in files] + ([mw] if mw else [])
    inputs_paths += [f["path"].with_suffix(".json") for f in files if f["path"].with_suffix(".json").is_file()]
    target = io.plan_output(out_dir, io.check_name(name, rm), "_balance.json", overwrite, inputs_paths)
    sel = io.select_measures(meta, from_measure, to_measure)
    res = run_balance(meta, rm, mw, sel, **p)
    cnt = res["counts"]
    rule = (f"소리 내는 파트 N개일 때 믹스보다 10·log10(N)+{p['buried_margin_db']:g}dB 이상 작으면 묻힘"
            if buried_mode == "relative" else f"믹스보다 {p['buried_db']:g}dB 이상 작으면 묻힘")
    rule += " (K-가중 소리 크기로 비교)" if weighting == "k" else " (RMS로 비교)"
    ratio = cnt.get("buried_ratio")
    lines = [f"{len(res['parts'])}개 파트, {len(sel)}마디: 묻힘 경고 {cnt['buried']}건"
             + (f"(소리 내는 파트·마디 {cnt['active_part_measures']}개 중 {ratio * 100:.0f}%)" if ratio is not None else "")
             + f", 저음 뭉침 {cnt['low_crowding']}건",
             f"기준: {rule}. 저음 뭉침은 음높이 있는 파트 {p['low_min_parts']}개 이상"
             f"(드럼·타악기·노래 제외, 센 파트 {len(cnt['low_crowding_parts_counted'])}개)"]
    skipped = res["params"]["buried_skipped_parts"]
    if skipped:
        lines.append(f"묻힘 판정에서 뺀 드럼·타악기 {len(skipped)}개: " + ", ".join(skipped[:8])
                     + (" …" if len(skipped) > 8 else "") + " (buried_include_unpitched: true면 포함)")
    if cnt["buried_by_part"]:
        lines.append("묻힌 파트: " + ", ".join(f"{k}({v}마디)" for k, v in
                                           sorted(cnt["buried_by_part"].items(), key=lambda kv: -kv[1])[:5]))
    for w in [w for w in res["warnings"] if w["type"] != "lead_margin_low"][:2]:
        lines.append(w["text"])
    lm = res.get("lead_margin")
    if lm:
        lines.append(f"리드 여유({', '.join(lm['lead_part'])}, 기준 {lm['margin_band'][0]:g}~{lm['margin_band'][1]:g}dB): "
                     + ", ".join(f"{x['name']} {x['lead_margin_db']:+.1f}" if x["lead_margin_db"] is not None
                                 else f"{x['name']} -" for x in lm["sections"][:12]))
        for w in lm["warnings"][:3]:
            lines.append(w["text"])
    text = "\n".join(lines)
    inputs = {"render_meta": str(rm), "mix_wav": str(mw) if mw else None,
              "from_measure": from_measure, "to_measure": to_measure, "buried_mode": buried_mode,
              "buried_include_unpitched": p["buried_include_unpitched"], "weighting": weighting,
              "lead_part": lead_part, "margin_band": margin_band, "sections": sections}
    io.write_json(target, _doc("mabiear.balance/1", "balance", inputs, res, text), overwrite)
    return {"out_path": str(target), "summary_text": text,
            "summary": {"counts": cnt, "warnings": res["warnings"][:20], "mix": res["mix"],
                        "weighting_used": res["weighting_used"], "parts": [x["label"] for x in res["parts"]],
                        "lead_margin": ({k: lm[k] for k in ("lead_part", "margin_band", "competitors", "sections")}
                                        if lm else None)}}


def ab_label(a: int, b: int | None, offset: int) -> str:
    """'27마디' 또는 오프셋이 있으면 '27마디(B 29마디)'."""
    return f"{a}마디" if not offset or b is None else f"{a}마디(B {b}마디)"


def tool_compare_renders(a_wav, a_meta, b_wav, b_meta, from_measure=None, to_measure=None, top_n=5,
                         out_dir=None, name=None, overwrite=False, b_measure_offset=0) -> dict:
    from .chroma import run_compare_renders

    aw = io.input_file(a_wav, "a_wav")
    am = io.input_file(a_meta, "a_meta")
    bw = io.input_file(b_wav, "b_wav")
    bm = io.input_file(b_meta, "b_meta")
    overwrite = _bool(overwrite, "overwrite")
    top_n = _int(top_n, "top_n", 1, 100)
    off = _int(0 if b_measure_offset is None else b_measure_offset, "b_measure_offset", -100000, 100000)
    nm = io.check_name(name, aw) if name else f"{io.check_name(None, aw)}_vs_{io.check_name(None, bw)}"[:120]
    target = io.plan_output(out_dir, nm, "_ab.json", overwrite, [aw, am, bw, bm])
    ma = io.load_meta(am, "a_meta")
    mb = io.load_meta(bm, "b_meta")
    sel = io.select_measures(ma, from_measure, to_measure)
    res = run_compare_renders(aw, ma, bw, mb, sel, 0.5, 0.25, top_n, off)
    rows = res["measures"]
    lines = [f"{len(rows)}마디 A/B 크로마 유사도 평균 {res['mean_score']:.3f}"]
    if off:
        lines.append(f"짝: A {rows[0]['measure']}~{rows[-1]['measure']}마디 ↔ B {rows[0]['b_measure']}~"
                     f"{rows[-1]['b_measure']}마디(b_measure_offset {off:+d})")
    lines.append("가장 다른 마디: " + ", ".join(
        f"{ab_label(b['measure'], b['b_measure'], off)} {b['score']:.2f}({b['rms_diff_db']:+.1f}dB)"
        for b in res["bottom"]))
    if res["skipped_count"]:
        sk = res["skipped_measures"]
        lines.append(f"B에 짝이 없어 건너뛴 A 마디 {res['skipped_count']}개: "
                     + ", ".join(str(x) for x in sk[:10]) + (" …" if len(sk) > 10 else ""))
    text = "\n".join(lines)
    inputs = {"a_wav": str(aw), "a_meta": str(am), "b_wav": str(bw), "b_meta": str(bm),
              "from_measure": from_measure, "to_measure": to_measure, "b_measure_offset": off}
    io.write_json(target, _doc("mabiear.ab/1", "compare_renders", inputs, res, text), overwrite)
    return {"out_path": str(target), "summary_text": text,
            "summary": {"mean_score": res["mean_score"], "bottom": res["bottom"], "measures": len(rows),
                        "b_measure_offset": off, "skipped_measures": res["skipped_measures"],
                        "skipped_count": res["skipped_count"]}}


def tool_report(align_json=None, chroma_json=None, onset_json=None, balance_json=None, ab_json=None, title=None,
                out_path=None, out_dir=None, name=None, overwrite=False, b_chroma_json=None, b_onset_json=None,
                b_balance_json=None, b_measure_offset=None, richness_json=None) -> dict:
    from . import report

    overwrite = _bool(overwrite, "overwrite")
    given = {"align": align_json, "chroma": chroma_json, "onset": onset_json, "balance": balance_json, "ab": ab_json,
             "b_chroma": b_chroma_json, "b_onset": b_onset_json, "b_balance": b_balance_json,
             "richness": richness_json}
    paths = {k: io.input_file(v, f"{k}_json") for k, v in given.items() if v not in (None, "")}
    if not paths:
        raise EarError("BAD_ARGUMENT", "분석 JSON 경로를 하나 이상 주세요(align_json, chroma_json, ...).", {})
    docs = {}
    for k, p in paths.items():
        d = io.read_json(p, f"{k}_json")
        if d.get("schema") != report.SCHEMAS[k]:
            raise EarError("BAD_ARGUMENT", f"{k}_json의 형식이 '{report.SCHEMAS[k]}'가 아닙니다.",
                           {"path": str(p), "schema": d.get("schema")})
        docs[k] = d
    if title is not None and not isinstance(title, str):
        raise EarError("BAD_ARGUMENT", "'title'은 문자열이어야 합니다.", {})
    # 마디 짝: 인자로 주면 그 값, 아니면 ab_json에 적힌 값(없으면 0). 둘이 다르면 거절
    ab_off = int((docs.get("ab") or {}).get("b_measure_offset") or 0)
    if b_measure_offset is None:
        off = ab_off
    else:
        off = _int(b_measure_offset, "b_measure_offset", -100000, 100000)
        if "ab" in docs and off != ab_off:
            raise EarError("BAD_ARGUMENT", f"b_measure_offset({off})가 ab_json의 값({ab_off})과 다릅니다.",
                           {"b_measure_offset": off, "ab_json_offset": ab_off})
    first = next(iter(paths.values()))
    if out_path not in (None, ""):
        if isinstance(out_path, str):
            io.check_path_syntax(out_path, "out_path")
        if not isinstance(out_path, str) or not Path(out_path).is_absolute():
            raise EarError("BAD_ARGUMENT", "'out_path'는 절대 경로여야 합니다.", {"out_path": out_path})
        op = Path(out_path)
        io.check_out_dir(str(op.parent), [])
        if any(io._norm(op) == io._norm(p) for p in paths.values()):
            raise EarError("PROTECTED_PATH", "출력 파일이 입력 JSON과 같습니다.", {"path": str(op)})
        if op.exists() and not overwrite:
            raise EarError("EXISTS", f"이미 파일이 있습니다(덮어쓰려면 overwrite: true): {op}", {"path": str(op)})
        target = op
    else:
        base = io.check_name(name, first)
        for suf in ("_align", "_chroma", "_onset", "_balance", "_ab", "_info", "_richness"):
            if name in (None, "") and base.endswith(suf):
                base = base[: -len(suf)]
        target = io.plan_output(out_dir, base, "_report.md", overwrite, [], paths.values())
    md, summary = report.build(title or "소리 분석 보고서", docs, {k: str(v) for k, v in paths.items()}, b_offset=off)
    io.write_output(target, md, overwrite)
    text = "보고서를 썼습니다: " + target.name
    if "chroma_mean" in summary:
        text += f"\n크로마 유사도 평균 {summary['chroma_mean']:.3f}, 하위 마디 {summary['chroma_bottom']}"
    if summary.get("onset_mean_f") is not None:
        text += f"\n온셋 F 평균 {summary['onset_mean_f']:.3f}"
    if summary.get("richness_mean_onset_ratio") is not None:
        text += (f"\n음 시작 수 렌더/원곡 평균 {summary['richness_mean_onset_ratio']:.2f}, "
                 f"가장 빈 마디 {summary['richness_emptiest']}")
    return {"out_path": str(target), "summary_text": text, "summary": summary}


# ---------------------------------------------------------------- 스템 단서

def _out_json(out_json, overwrite: bool, inputs: list) -> Path | None:
    """out_json(절대 경로, 선택) 확인. 보호 폴더·입력 폴더 안·입력과 같은 파일이면 PROTECTED_PATH, 있으면 EXISTS."""
    if out_json in (None, ""):
        return None
    if not isinstance(out_json, str):
        raise EarError("BAD_ARGUMENT", "'out_json'은 절대 경로 문자열이어야 합니다.", {"out_json": out_json})
    io.check_path_syntax(out_json, "out_json")
    op = Path(out_json)
    if not op.is_absolute() or op.suffix.lower() != ".json":
        raise EarError("BAD_ARGUMENT", "'out_json'은 .json으로 끝나는 절대 경로여야 합니다.", {"out_json": out_json})
    io.check_out_dir(str(op.parent), inputs)
    if any(io._norm(op) == io._norm(p) for p in inputs):
        raise EarError("PROTECTED_PATH", "출력 파일이 입력 파일과 같습니다.", {"path": str(op)})
    if op.exists() and not overwrite:
        raise EarError("EXISTS", f"이미 파일이 있습니다(덮어쓰려면 overwrite: true): {op}", {"path": str(op)})
    return op


def _stem_common(bpm, first_downbeat_sec, time_signature, grid, measure_offset):
    from .stems import parse_time_signature

    bpm = _num(bpm, "bpm", 20.0, 400.0)
    first = _num(first_downbeat_sec, "first_downbeat_sec", 0.0, 36000.0)
    num, den = parse_time_signature(time_signature)
    grid = _int(grid, "grid", 16, 32)
    if grid not in (16, 32):
        raise EarError("BAD_ARGUMENT", "'grid'는 16 또는 32입니다.", {"grid": grid})
    mo = _int(measure_offset, "measure_offset", 1, 10000)
    return bpm, first, num, den, grid, mo


def tool_stem_drum_hits(drum_stem_path, bpm, first_downbeat_sec, time_signature="4/4", grid=16, measure_offset=1,
                        sections=None, min_confidence=0.0, low_tom_layer=False, phase_search_ms=60.0,
                        out_json=None, overwrite=False) -> dict:
    from .stems import SCHEMA_DRUMS, analyse_drums, check_sections

    p = io.input_file(drum_stem_path, "drum_stem_path")
    bpm, first, num, den, grid, mo = _stem_common(bpm, first_downbeat_sec, time_signature, grid, measure_offset)
    secs = check_sections(sections)
    mc = _num(min_confidence, "min_confidence", 0.0, 1.0)
    ltl = _bool(low_tom_layer, "low_tom_layer")
    ph = _num(phase_search_ms, "phase_search_ms", 0.0, 200.0)
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, [p])
    res = analyse_drums(p, bpm, first, num, den, grid, mo, secs, mc, ltl, ph)
    c = res["counts"]
    order = ("kick", "snare", "ghost", "side_stick", "closed_hat", "open_hat", "crash", "ride", "low_tom", "mid_tom",
             "high_tom", "unknown")
    tc = res["tempo_check"]
    text = (f"{p.name}: 타격 {len(res['hits'])}개 (" + ", ".join(f"{k} {c[k]}" for k in order if c.get(k)) + ")\n"
            + f"크래시 {len(res['crashes'])}개: " + ", ".join(x["pos"] for x in res["crashes"][:30])
            + (" …" if len(res["crashes"]) > 30 else "") + "\n"
            + f"위상 {res['grid']['phase_sec'] * 1000:+.0f}ms, 템포 확인 {tc['comb_bpm']} BPM"
            + f"(준 값 {bpm:g}, {tc['comb_off_pct']:+.2f}%), 배치 음 {len(res['placements'])}개")
    if tc["warning"]:
        text += "\n경고: " + tc["warning"]
    inputs = {"drum_stem_path": str(p), "bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
              "grid": grid, "measure_offset": mo, "sections": secs, "min_confidence": mc, "low_tom_layer": ltl,
              "phase_search_ms": ph}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_DRUMS, "stem_drum_hits", inputs, res, text), overwrite)
        summary = {k: res[k] for k in ("grid", "tempo_check", "levels", "counts", "sections", "crashes", "dropped",
                                       "low_tom_layer_added", "notes")}
        summary["hits"] = len(res["hits"])
        summary["placements"] = len(res["placements"])
        return {"out_path": str(target), "summary_text": text, "summary": summary}
    return {"out_path": None, "summary_text": text, "result": res}


def tool_stem_bass_notes(bass_stem_path, bpm, first_downbeat_sec, time_signature="4/4", grid=16, measure_offset=1,
                         min_note_ticks=12, range_low=23, range_high=71, min_confidence=0.0, out_json=None,
                         overwrite=False) -> dict:
    from .stems import SCHEMA_BASS, analyse_bass

    p = io.input_file(bass_stem_path, "bass_stem_path")
    bpm, first, num, den, grid, mo = _stem_common(bpm, first_downbeat_sec, time_signature, grid, measure_offset)
    mnt = _int(min_note_ticks, "min_note_ticks", 6, 384)
    lo = _int(range_low, "range_low", 12, 96)
    hi = _int(range_high, "range_high", 12, 96)
    if lo >= hi:
        raise EarError("BAD_ARGUMENT", "range_low는 range_high보다 작아야 합니다.", {"range_low": lo, "range_high": hi})
    mc = _num(min_confidence, "min_confidence", 0.0, 1.0)
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, [p])
    res = analyse_bass(p, bpm, first, num, den, grid, mo, mnt, lo, hi, mc)
    c = res["counts"]
    text = (f"{p.name}: 음 {c['notes']}개(옥타브 애매 {c['octave_ambiguous']}개), "
            + f"마디 근음 추정 {len(res['measure_roots'])}마디\n"
            + "처음 음: " + ", ".join(f"{n['pos']} {n['name']} {n['len']}t" for n in res["notes"][:8]))
    inputs = {"bass_stem_path": str(p), "bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
              "grid": grid, "measure_offset": mo, "min_note_ticks": mnt, "range_low": lo, "range_high": hi,
              "min_confidence": mc}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_BASS, "stem_bass_notes", inputs, res, text), overwrite)
        summary = {k: res[k] for k in ("grid", "pyin", "counts", "limits")}
        summary["measure_roots"] = res["measure_roots"][:64]
        return {"out_path": str(target), "summary_text": text, "summary": summary}
    return {"out_path": None, "summary_text": text, "result": res}


# ---------------------------------------------------------------- 전사(멜로디·화음·반복 구조)

def tool_stem_melody_notes(stem_path, bpm, first_downbeat_sec, time_signature="4/4", grid=16, range_low=45,
                           range_high=86, min_note_ticks=None, method="auto", measure_offset=1, sections=None,
                           min_confidence=0.0, merge_gap_ticks=None, out_json=None, overwrite=False, triplets=False,
                           from_sec=None, to_sec=None) -> dict:
    from .melody import SCHEMA_MELODY, analyse_melody
    from .stems import check_sections

    METHODS = ("salience", "pyin", "auto", "ridge")
    if method not in METHODS:
        raise EarError("BAD_ARGUMENT", "'method'는 'salience', 'pyin', 'auto', 'ridge' 중 하나입니다.", {"method": method})
    triplets = _bool(triplets, "triplets")
    fs = None if from_sec is None else _num(from_sec, "from_sec", 0.0, 36000.0)
    ts = None if to_sec is None else _num(to_sec, "to_sec", 0.0, 36000.0)
    if fs is not None and ts is not None and ts - fs < 0.5:
        raise EarError("BAD_ARGUMENT", "to_sec는 from_sec보다 0.5초 이상 뒤여야 합니다.", {"from_sec": fs, "to_sec": ts})
    if method != "ridge" and (triplets or fs is not None or ts is not None):
        raise EarError("BAD_ARGUMENT", "'triplets'·'from_sec'·'to_sec'는 method 'ridge'에서만 씁니다.",
                       {"method": method})

    p = io.input_file(stem_path, "stem_path")
    bpm, first, num, den, grid, mo = _stem_common(bpm, first_downbeat_sec, time_signature, grid, measure_offset)
    lo = _int(range_low, "range_low", 24, 100)
    hi = _int(range_high, "range_high", 24, 100)
    if hi - lo < 12:
        raise EarError("BAD_ARGUMENT", "range_high는 range_low보다 12 이상 커야 합니다(한 옥타브 이상).",
                       {"range_low": lo, "range_high": hi})
    mnt = None if min_note_ticks is None else _int(min_note_ticks, "min_note_ticks", 6, 384)
    mgap = None if merge_gap_ticks is None else _int(merge_gap_ticks, "merge_gap_ticks", 0, 96)
    if method not in METHODS:
        raise EarError("BAD_ARGUMENT", "'method'는 'salience', 'pyin', 'auto', 'ridge' 중 하나입니다.", {"method": method})
    secs = check_sections(sections)
    mc = _num(min_confidence, "min_confidence", 0.0, 1.0)
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, [p])
    if method == "ridge":
        from .ridge import analyse_ridge

        res = analyse_ridge(p, bpm, first, num, den, grid, mo, lo, hi, mnt, secs, mc, triplets, fs, ts)
    else:
        res = analyse_melody(p, bpm, first, num, den, grid, mo, lo, hi, mnt, method, secs, mc, mgap)
    c, cv = res["counts"], res["coverage"]
    rg = res["range"]
    text = (f"{p.name}: 음 {c['notes']}개(옥타브 애매 {c['octave_ambiguous']}개, 옥타브 옮김 {c['register_moves']}개), "
            f"방법 {method}, 유성 {cv['voiced_pct']}%(큰 소리 구간의 {cv['voiced_of_loud_pct']}%), "
            f"음이 차지한 시간 {cv['note_time_pct']}%\n"
            + (f"음역 {io.PITCH_NAMES[rg['low'] % 12]}{rg['low'] // 12 - 1}~{io.PITCH_NAMES[rg['high'] % 12]}"
               f"{rg['high'] // 12 - 1} (p5 {rg['p5']}, 중앙 {rg['p50']}, p95 {rg['p95']})\n" if rg else "음 없음\n")
            + "처음 음: " + ", ".join(f"{n['pos']} {n['name']} {n['length_ticks']}t" for n in res["notes"][:8]))
    inputs = {"stem_path": str(p), "bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
              "grid": grid, "range_low": lo, "range_high": hi, "min_note_ticks": mnt, "method": method,
              "measure_offset": mo, "sections": secs, "min_confidence": mc, "merge_gap_ticks": mgap,
              "triplets": triplets, "from_sec": fs, "to_sec": ts}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_MELODY, "stem_melody_notes", inputs, res, text), overwrite)
        summary = {k: res[k] for k in ("grid", "track", "coverage", "range", "counts", "sections", "limits")}
        summary["first_notes"] = res["notes"][:16]
        return {"out_path": str(target), "summary_text": text, "summary": summary}
    return {"out_path": None, "summary_text": text, "result": res}


def _not_an_input(targets, inputs) -> None:
    for t in targets:
        for p in inputs:
            if p is not None and io._norm(t) == io._norm(Path(p)):
                raise EarError("PROTECTED_PATH", "출력 파일이 입력 파일과 같습니다.", {"path": str(t)})


def _score_notes_and_map(notes_json, pitch_mode, velocities, tick_shift, from_measure, to_measure, bpm,
                         first_downbeat_sec, time_signature, measure_offset, render_meta, align_json):
    """Common inputs of lead_check / vocal_agreement: (notes path, notes, notes info, tick map, input paths)."""
    from .scorenotes import build_map, check_velocities, load_notes

    nj = io.input_file(notes_json, "notes_json")
    if pitch_mode not in ("sounding", "written"):
        raise EarError("BAD_ARGUMENT", "'pitch_mode'는 'sounding' 또는 'written'입니다.", {"pitch_mode": pitch_mode})
    vel = check_velocities(velocities)
    ts = _int(tick_shift, "tick_shift", -3840, 3840)
    tmap = build_map(bpm, first_downbeat_sec, time_signature, measure_offset, render_meta, align_json)
    notes, ninfo = load_notes(nj, "notes_json", pitch_mode, vel, ts)
    for arg, v in (("from_measure", from_measure), ("to_measure", to_measure)):
        if v is not None:
            _int(v, arg, -100000, 100000)
    if from_measure is not None or to_measure is not None:
        lo = -10**9 if from_measure is None else from_measure
        hi = 10**9 if to_measure is None else to_measure
        notes = [n for n in notes if lo <= tmap.pos(n["tick"])[1] <= hi]
        ninfo["notes_used"] = len(notes)
        ninfo["measure_range"] = [from_measure, to_measure]
        if not notes:
            raise EarError("BAD_ARGUMENT", "고른 마디 범위에 음이 없습니다.", {"from_measure": from_measure,
                                                                    "to_measure": to_measure})
    # folders of original inputs are protected (render meta); analysis JSONs (notes list, align result) may sit in the
    # output folder like align_json for chroma_compare, they are never overwritten (other file names)
    paths = [io.input_file(render_meta, "render_meta")] if render_meta is not None else []
    return nj, notes, ninfo, tmap, paths


def tool_lead_check(original_audio, notes_json, source="mix", bpm=None, first_downbeat_sec=None,
                    time_signature="4/4", measure_offset=None, render_meta=None, align_json=None,
                    pitch_mode="sounding", velocities=None, tick_shift=0, from_measure=None, to_measure=None,
                    side_k="auto", range_low=None, range_high=None, octave_shift=None, pyin=None, repet=False,
                    sections=None, out_dir=None, name=None, overwrite=False, transcription=None,
                    transcription_key="auto", transcription_min_note_sec=0.12) -> dict:
    from .leadcheck import SCHEMA_LEAD, SOURCES, markdown, run_lead_check
    from .stems import check_sections

    oa = io.input_file(original_audio, "original_audio")
    if source not in SOURCES:
        raise EarError("BAD_ARGUMENT", "'source'는 'mix'(전체 믹스) 또는 'stem'(노래·리드 스템)입니다.", {"source": source})
    nj, notes, ninfo, tmap, paths = _score_notes_and_map(notes_json, pitch_mode, velocities, tick_shift, from_measure,
                                                        to_measure, bpm, first_downbeat_sec, time_signature,
                                                        measure_offset, render_meta, align_json)
    if side_k != "auto":
        side_k = _num(side_k, "side_k", 0.0, 2.0)
    lo = None if range_low is None else _int(range_low, "range_low", 24, 100)
    hi = None if range_high is None else _int(range_high, "range_high", 24, 108)
    if lo is not None and hi is not None and hi - lo < 12:
        raise EarError("BAD_ARGUMENT", "range_high는 range_low보다 12 이상 커야 합니다.", {"range_low": lo, "range_high": hi})
    osh = None if octave_shift is None else _int(octave_shift, "octave_shift", -36, 36)
    if osh is not None and osh % 12:
        raise EarError("BAD_ARGUMENT", "'octave_shift'는 12의 배수(반음)입니다.", {"octave_shift": osh})
    use_pyin = (source == "stem") if pyin is None else _bool(pyin, "pyin")
    rep = _bool(repet, "repet")
    secs = check_sections(sections)
    overwrite = _bool(overwrite, "overwrite")
    nm = io.check_name(name, nj)
    target = io.plan_output(out_dir, nm, "_leadcheck.json", overwrite, [oa] + paths)
    md_target = io.plan_output(out_dir, nm, "_leadcheck.md", overwrite, [oa] + paths)
    _not_an_input([target, md_target], [nj, align_json])
    tr = None
    if transcription is not None:
        from .scorenotes import estimate_key, key_profile_pcs
        from .vocal import load_transcription, trusted_notes

        tp = io.input_file(transcription, "transcription")
        if not isinstance(transcription_key, str):
            raise EarError("BAD_ARGUMENT", "'transcription_key'는 'auto', 'none' 또는 조 이름입니다.",
                           {"transcription_key": transcription_key})
        mns = _num(transcription_min_note_sec, "transcription_min_note_sec", 0.0, 2.0)
        if transcription_key == "auto":
            kname, kpcs, _ = estimate_key(notes)
        elif transcription_key == "none":
            kname, kpcs = "none", None
        else:
            kname, kpcs = transcription_key, key_profile_pcs(transcription_key)
        kept, tinfo = trusted_notes(load_transcription(tp), kpcs, mns)
        tr = {"notes": kept, "path": str(tp), "key": kname, **tinfo}
    res = run_lead_check(oa, notes, tmap, source, side_k, lo, hi, secs, use_pyin, None, osh, rep,
                         transcription=tr, side_k_given=side_k != "auto")
    c = res["summary"]["counts"]
    an = res["analysis"]
    text = (f"리드 {c['notes']}음: 일치 {c['agree']} · 틀림 {c['wrong']} · 보류 {c['uncertain']} "
            f"(음이름 비교, 원곡 {an['mode_used']}" + (f", side_k {an['side_k']}" if an["side_k"] is not None else "")
            + f", 원곡 리드 = 우리 음 {an['octave_shift']:+d}반음, 조율 {an['tuning_cents']:+.0f}센트)\n"
            + ("틀림: " + ", ".join(f"{w['pos']} {w['name']}→{w['heard_note']}" for w in res["wrong"][:20])
               + (" …" if len(res["wrong"]) > 20 else "") + "\n" if res["wrong"] else "")
            + ("보류: " + ", ".join(u["pos"] for u in res["uncertain"][:20]) + (" …" if len(res["uncertain"]) > 20 else "")
               + "\n" if res["uncertain"] else "")
            + (f"전사 대조: 겹친 음 {res['summary']['transcription']['notes_with_transcription']}, 틀림→보류 "
               f"{res['summary']['transcription']['wrong_to_uncertain']}, 보류→틀림 "
               f"{res['summary']['transcription']['uncertain_to_wrong']}\n" if res["summary"]["transcription"] else "")
            + f"{res['timing_sec']['total']:.1f}초 걸림"
            + "".join(f"\n경고: {w}" for w in res["warnings"]))
    inputs = {"original_audio": str(oa), "notes_json": str(nj), "source": source, "time_map": tmap.info,
              "time_map_kind": tmap.kind, "pitch_mode": pitch_mode, "velocities": velocities,
              "tick_shift": tick_shift, "from_measure": from_measure, "to_measure": to_measure, "side_k": side_k,
              "range_low": lo, "range_high": hi, "octave_shift": osh, "pyin": use_pyin, "repet": rep,
              "sections": secs, "notes": ninfo, "transcription": transcription,
              "transcription_key": transcription_key if transcription is not None else None}
    doc = _doc(SCHEMA_LEAD, "lead_check", inputs, res, text)
    io.write_json(target, doc, overwrite)
    io.write_output(md_target, markdown(doc), overwrite)
    summary = {"counts": c, "transcription": res["summary"]["transcription"], "warnings": res["warnings"], "sections": res["summary"]["sections"], "wrong": res["wrong"],
               "uncertain": res["uncertain"][:40], "analysis": {k: an[k] for k in (
                   "mode_used", "side_k", "side_k_fit_scores", "octave_shift", "tuning_cents", "voiced_pct", "pyin",
                   "time_offset")},
               "md_path": str(md_target), "elapsed_sec": res["timing_sec"]["total"]}
    return {"out_path": str(target), "summary_text": text, "summary": summary}


def tool_vocal_agreement(transcription_path, notes_json, bpm=None, first_downbeat_sec=None, time_signature="4/4",
                         measure_offset=None, render_meta=None, align_json=None, pitch_mode="sounding",
                         velocities=None, tick_shift=0, from_measure=None, to_measure=None, sections=None,
                         key="auto", min_note_sec=0.12, slide_cents=40.0, head_sec=1.0, onset_tolerance_ticks=24,
                         exclude_measures=None, out_dir=None, name=None, overwrite=False) -> dict:
    from .leadcheck import auto_sections
    from .scorenotes import estimate_key, key_profile_pcs
    from .stems import check_sections
    from .vocal import SCHEMA_VOCAL, load_transcription, markdown, run_vocal_agreement

    tp = io.input_file(transcription_path, "transcription_path")
    nj, notes, ninfo, tmap, paths = _score_notes_and_map(notes_json, pitch_mode, velocities, tick_shift, from_measure,
                                                        to_measure, bpm, first_downbeat_sec, time_signature,
                                                        measure_offset, render_meta, align_json)
    mns = _num(min_note_sec, "min_note_sec", 0.0, 2.0)
    sc = _num(slide_cents, "slide_cents", 0.0, 50.0)
    hs = _num(head_sec, "head_sec", 0.05, 10.0)
    tol = _int(onset_tolerance_ticks, "onset_tolerance_ticks", 0, 384)
    excl = []
    if exclude_measures is not None:
        if not isinstance(exclude_measures, list):
            raise EarError("BAD_ARGUMENT", "'exclude_measures'는 [[첫, 끝], …] 목록입니다.", {"exclude_measures": exclude_measures})
        for i, e in enumerate(exclude_measures):
            if (not isinstance(e, list) or len(e) != 2 or not all(isinstance(x, int) and not isinstance(x, bool) for x in e)
                    or e[0] > e[1]):
                raise EarError("BAD_ARGUMENT", f"exclude_measures[{i}]는 [첫, 끝] 정수 쌍입니다.", {"entry": e})
            excl.append([e[0], e[1]])
    if not isinstance(key, str):
        raise EarError("BAD_ARGUMENT", "'key'는 'auto', 'none' 또는 \"D major\" 같은 조 이름입니다.", {"key": key})
    if key == "auto":
        kname, kpcs, kr = estimate_key(notes)
        kname = f"{kname} (악보 리드에서 추정, r {kr})"
    elif key == "none":
        kname, kpcs = "none", None
    else:
        kpcs = key_profile_pcs(key)
        kname = key
    secs = check_sections(sections)
    overwrite = _bool(overwrite, "overwrite")
    nm = io.check_name(name, nj)
    target = io.plan_output(out_dir, nm, "_vocal.json", overwrite, [tp] + paths)
    md_target = io.plan_output(out_dir, nm, "_vocal.md", overwrite, [tp] + paths)
    _not_an_input([target, md_target], [nj, align_json])
    trans = load_transcription(tp)
    if not secs:
        secs = auto_sections([{"measure": tmap.pos(n["tick"])[1]} for n in notes])
    res = run_vocal_agreement(trans, notes, tmap, secs, kpcs, kname, mns, sc, hs, tol, excl)
    t = res["total"]
    text = (f"악보→녹음 {t['agree']}/{t['voice_heard']}음 같은 음이름({t['score_to_recording_pct']}%, 목소리 안 들린 음 "
            f"{t['score_notes'] - t['voice_heard']}개 제외), 녹음→악보 {t['covered']}/{t['sung_notes']}"
            f"({t['recording_to_score_pct']}%)\n"
            f"전사 {t['transcribed_notes']}음 중 짧은 음 {t['ignored_short']}·조 밖 슬라이드 {t['ignored_out_of_key_slides']} 뺌"
            f" (조 {kname}), 악보 옥타브 − 노래 옥타브 {t['octave_relation_score_minus_sung']}, 시작 차 중앙 "
            f"{t['onset_offset_ms_median']}ms")
    inputs = {"transcription_path": str(tp), "notes_json": str(nj), "time_map": tmap.info, "time_map_kind": tmap.kind,
              "pitch_mode": pitch_mode, "velocities": velocities, "tick_shift": tick_shift, "key": key,
              "sections": secs, "exclude_measures": excl, "notes": ninfo}
    doc = _doc(SCHEMA_VOCAL, "vocal_agreement", inputs, res, text)
    io.write_json(target, doc, overwrite)
    io.write_output(md_target, markdown(doc), overwrite)
    summary = {"total": t, "sections": res["sections"], "score_disagreements": res["score_disagreements"][:40],
               "recording_not_in_score": res["recording_not_in_score"][:40], "key": res["key"],
               "md_path": str(md_target)}
    return {"out_path": str(target), "summary_text": text, "summary": summary}


def _path_list(v, arg: str, min_n: int = 1, max_n: int = 12) -> list:
    if v is None:
        v = []
    if isinstance(v, str):
        v = [v]
    if not isinstance(v, list) or not (min_n <= len(v) <= max_n):
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 절대 경로 문자열 {min_n}~{max_n}개의 목록이어야 합니다.", {arg: v})
    return [io.input_file(x, f"{arg}[{i}]") for i, x in enumerate(v)]


def tool_stem_chords(paths, bpm, first_downbeat_sec, time_signature="4/4", measure_offset=1, subtract_paths=None,
                     bass_stem_path=None, key="auto", qualities=None, voicing_range=None, voicing_size=3,
                     per_beat=True, out_json=None, overwrite=False) -> dict:
    from .chords import QUALITIES, SCHEMA_CHORDS, analyse_chords, key_name, parse_key

    ps = _path_list(paths, "paths")
    subs = _path_list(subtract_paths, "subtract_paths", 0, 12)
    bp = None if bass_stem_path in (None, "") else io.input_file(bass_stem_path, "bass_stem_path")
    bpm, first, num, den, _, mo = _stem_common(bpm, first_downbeat_sec, time_signature, 16, measure_offset)
    k = parse_key(key)
    if qualities is None:
        quals = list(QUALITIES)
    elif isinstance(qualities, list) and qualities and all(isinstance(q, str) and q in QUALITIES for q in qualities):
        quals = list(dict.fromkeys(qualities))
    else:
        raise EarError("BAD_ARGUMENT", "'qualities'는 " + ", ".join(QUALITIES) + " 중 하나 이상의 목록입니다.",
                       {"qualities": qualities})
    vr = [48, 72] if voicing_range is None else voicing_range
    if (not isinstance(vr, list) or len(vr) != 2 or any(isinstance(x, bool) or not isinstance(x, int) for x in vr)
            or not (24 <= vr[0] < vr[1] <= 108) or vr[1] - vr[0] < 12):
        raise EarError("BAD_ARGUMENT", "'voicing_range'는 [낮은 MIDI, 높은 MIDI](24~108, 12 이상 차이)입니다.",
                       {"voicing_range": voicing_range})
    vs = _int(voicing_size, "voicing_size", 3, 4)
    pb = _bool(per_beat, "per_beat")
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, ps + subs + ([bp] if bp else []))
    res = analyse_chords(ps, subs, bp, bpm, first, num, den, mo, k, quals, (vr[0], vr[1]), vs, pb)
    c = res["counts"]
    seq = " | ".join(f"{m['measure']} {m['chord']}" for m in res["measures"][:16])
    text = (f"화음 {c['measures']}마디(코드 없음 {c['no_chord_measures']}, 신뢰 낮음 {c['low_confidence_measures']}), "
            f"조 {res['key']['used']}({'준 값' if res['key']['given'] else '추정'}, 추정 {res['key']['estimated']} "
            f"r={res['key']['estimated_r']})"
            + (f", 베이스 근음 일치 {c['bass_agree_pct']}%" if c["bass_agree_pct"] is not None else "") + "\n" + seq
            + (" …" if len(res["measures"]) > 16 else ""))
    inputs = {"paths": [str(x) for x in ps], "subtract_paths": [str(x) for x in subs],
              "bass_stem_path": str(bp) if bp else None, "bpm": bpm, "first_downbeat_sec": first,
              "time_signature": f"{num}/{den}", "measure_offset": mo, "key": key_name(k) if k else "auto",
              "qualities": quals, "voicing_range": vr, "voicing_size": vs, "per_beat": pb}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_CHORDS, "stem_chords", inputs, res, text), overwrite)
        summary = {kk: res[kk] for kk in ("grid", "key", "method", "counts", "limits")}
        summary["measures"] = [{x: m.get(x) for x in ("measure", "chord", "confidence", "bass_agree", "voicing")}
                               for m in res["measures"][:64]]
        return {"out_path": str(target), "summary_text": text, "summary": summary}
    return {"out_path": None, "summary_text": text, "result": res}


def tool_structure_repeats(paths, bpm, first_downbeat_sec, time_signature="4/4", measure_offset=1, n_measures=None,
                           min_run_measures=4, thresholds="auto", source_measures=None, out_json=None,
                           overwrite=False) -> dict:
    from .structure import SCHEMA_STRUCTURE, analyse_structure, check_thresholds

    ps = _path_list(paths, "paths")
    bpm, first, num, den, _, mo = _stem_common(bpm, first_downbeat_sec, time_signature, 16, measure_offset)
    nm = None if n_measures is None else _int(n_measures, "n_measures", 2, 5000)
    mr = _int(min_run_measures, "min_run_measures", 2, 32)
    th = check_thresholds(thresholds)
    sm = None
    if source_measures is not None:
        if (not isinstance(source_measures, list) or len(source_measures) != 2
                or any(isinstance(x, bool) or not isinstance(x, int) for x in source_measures)
                or not (mo <= source_measures[0] <= source_measures[1])):
            raise EarError("BAD_ARGUMENT", "'source_measures'는 [첫 마디, 끝 마디](measure_offset 이상, 첫 ≤ 끝)입니다.",
                           {"source_measures": source_measures})
        sm = [source_measures[0], source_measures[1]]
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, ps)
    res = analyse_structure(ps, bpm, first, num, den, mo, nm, mr, th, sm)
    t, cv = res["thresholds"], res["coverage"]
    lines = [f"{e['target'][0]}-{e['target'][1]} ← {e['source'][0]}-{e['source'][1]} {e['kind']}"
             + (f" +{e['transpose']}" if e["transpose"] else "") + f" ({e['similarity']})" for e in res["copy_map"]]
    text = (f"{res['grid']['measures']}마디, 문턱 chroma {t['chroma']}·mfcc {t['mfcc']}({t['mode']}), 복사로 채울 수 있는 "
            f"마디 {cv['copy_target_measures']}개({cv['copy_target_pct']}%: exact {cv['by_kind']['exact']}, harmony "
            f"{cv['by_kind']['harmony']}, transposed {cv['by_kind']['transposed']})\n" + "\n".join(lines[:24])
            + ("\n…" if len(lines) > 24 else ""))
    inputs = {"paths": [str(x) for x in ps], "bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
              "measure_offset": mo, "n_measures": nm, "min_run_measures": mr,
              "thresholds": thresholds if th is not None else "auto", "source_measures": sm}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_STRUCTURE, "structure_repeats", inputs, res, text), overwrite)
        summary = {k: res[k] for k in ("grid", "thresholds", "copy_map", "coverage", "limits")}
        summary["measure_hints"] = res["measure_hints"][:40]
        return {"out_path": str(target), "summary_text": text, "summary": summary}
    return {"out_path": None, "summary_text": text, "result": res}


# ---------------------------------------------------------------- 듣기 파일·다운비트

def tool_phone_version(wav, out=None, sample_rate=22050, peak=0.98, normalize=True, overwrite=False) -> dict:
    from .listen import check_target, run_phone_version

    src = io.input_file(wav, "wav")
    sr = _int(sample_rate, "sample_rate", 8000, 192000)
    pk = _num(peak, "peak", 0.01, 1.0)
    nz = _bool(normalize, "normalize")
    overwrite = _bool(overwrite, "overwrite")
    if out in (None, ""):
        out = str(src.parent / "폰용" / f"{io.check_name(None, src)}_폰용.wav")
    target = check_target(out, "out", [src], overwrite)
    res = run_phone_version(src, target, sr, pk, nz, overwrite)
    inp = res["input"]
    text = (f"{target.name}: {res['duration_sec']:.2f}초, {sr}Hz 모노 PCM_16, peak {res['peak']:.3f}"
            f"({res['peak_dbfs']:+.2f}dBFS, 이득 {res['gain_db']:+.2f}dB), {res['size_bytes'] / 1e6:.2f}MB\n"
            f"입력 {inp['sample_rate']}Hz {inp['channels']}채널 {inp['subtype']}, peak {inp['peak']:.3f}")
    return {"out_path": res["out_path"], "summary_text": text, "summary": res}


def tool_excerpt_ab(render_wav, original_audio, measures, grid=None, render_offset_sec=None, render_meta=None,
                    align_json=None, lead_in_sec=1.0, tail_sec=0.5, out_dir=None, name_prefix=None, label=None,
                    phone=True, write_b=True, overwrite=False) -> dict:
    from .chroma import load_align
    from .listen import (check_target, excerpt_names, out_dir_arg, parse_grid, parse_measures, plan_windows,
                         run_excerpt_ab)

    rw = io.input_file(render_wav, "render_wav")
    oa = io.input_file(original_audio, "original_audio")
    pairs = parse_measures(measures)
    if len(set(pairs)) != len(pairs):
        raise EarError("BAD_ARGUMENT", "'measures'에 같은 구간이 두 번 있습니다.", {"measures": measures})
    lead = _num(lead_in_sec, "lead_in_sec", 0.0, 30.0)
    tail = _num(tail_sec, "tail_sec", 0.0, 30.0)
    phone, write_b, overwrite = _bool(phone, "phone"), _bool(write_b, "write_b"), _bool(overwrite, "overwrite")
    has_align = align_json not in (None, "")
    has_meta = render_meta not in (None, "")
    if has_align and (grid is not None or render_offset_sec is not None or has_meta):
        raise EarError("BAD_ARGUMENT", "align_json을 주면 grid·render_offset_sec·render_meta는 주지 않습니다(둘 중 한 길만).",
                       {"align_json": align_json, "grid": grid, "render_offset_sec": render_offset_sec,
                        "render_meta": render_meta})
    if not has_align and grid is None:
        raise EarError("BAD_ARGUMENT", "grid({bpm, measure1_sec, beats_per_measure}) 또는 align_json 중 하나가 필요합니다.",
                       {})
    if render_offset_sec is not None and has_meta:
        raise EarError("BAD_ARGUMENT", "render_offset_sec와 render_meta는 함께 주지 않습니다(render_meta의 마디 표를 쓰거나 "
                       "render_offset_sec로 직접 주기).", {})
    inputs = [rw, oa]
    al = meta = g = None
    roff = None
    warns = []
    if has_align:
        aj = io.input_file(align_json, "align_json")
        al = load_align(aj)
        inputs.append(aj)
        ins = al.get("inputs") or {}
        for k, given in (("render_wav", rw), ("original_audio", oa)):
            v = ins.get(k)
            if v and io._norm(v) != io._norm(given):
                warns.append(f"align_json의 {k}({Path(v).name})가 준 {k}({given.name})와 다릅니다.")
    else:
        g = parse_grid(grid)
        if render_offset_sec is not None:
            roff = _num(render_offset_sec, "render_offset_sec", -36000.0, 36000.0)
        if has_meta:
            rm = io.input_file(render_meta, "render_meta")
            meta = io.load_meta(rm)
            inputs.append(rm)
    windows, timeline = plan_windows(pairs, lead, tail, g, roff, meta, al)
    prefix = io.check_name(name_prefix, rw)
    lab = None if label in (None, "") else io.check_name(label, rw)
    default_dir = rw.parent / "변형" / "폰용" if phone else rw.parent / "변형"
    d = out_dir_arg(out_dir, default_dir)
    targets = []
    for w in windows:
        na, nb = excerpt_names(prefix, w["from"], w["to"], lab, phone)
        ta = check_target(str(d / na), "out_dir", inputs, overwrite)
        tb = check_target(str(d / nb), "out_dir", inputs, overwrite) if write_b else None
        targets.append((ta, tb))
    files = run_excerpt_ab(rw, oa, windows, targets, phone, overwrite)
    ratio = al.get("tempo_ratio") if al else None
    fmt = "폰용 22.05kHz 모노 PCM_16 peak 0.98" if phone else "원래 형식"
    lines = [f"{len(windows)}구간 A/B 발췌({fmt}), 앞 여유 {lead:g}초, 뒤 {tail:g}초, 렌더 시각 = {timeline}"
             + (f", 템포 비율 {ratio}" if ratio is not None else "")]
    for w in windows:
        drift = f" (원곡·렌더 구간 길이 차 {w['drift_sec']:+.3f}초)" if abs(w["drift_sec"]) > 0.0005 else ""
        lines.append(f"{w['from']}~{w['to']}마디: B 원곡 {w['b_start_sec']:.3f}초부터, A 렌더 {w['a_start_sec']:.3f}초부터, "
                     f"길이 {w['length_sec']:.2f}초{drift}")
    pads = [f for f in files if f["pad_start_sec"] or f["pad_end_sec"]]
    if pads:
        lines.append("무음으로 채운 파일: " + ", ".join(
            f"{Path(f['path']).name}(앞 {f['pad_start_sec']}초, 뒤 {f['pad_end_sec']}초)" for f in pads[:6]))
    for msg in warns:
        lines.append("경고: " + msg)
    text = "\n".join(lines)
    wins = [{k: (r(v, 4) if isinstance(v, float) else v) for k, v in w.items()} for w in windows]
    summary = {"out_dir": str(d), "render_timeline": timeline, "tempo_ratio": ratio, "lead_in_sec": lead,
               "tail_sec": tail, "phone": phone, "grid": g, "render_offset_sec": roff, "windows": wins,
               "files": files, "warnings": warns}
    return {"out_path": str(d), "summary_text": text, "summary": summary}


def tool_downbeat_check(audio, bpm, beats_per_measure=4, candidates=None, search=None, window_ms=60.0, band="low",
                        out_json=None, overwrite=False) -> dict:
    from .downbeat import BANDS, SCHEMA_DOWNBEAT, analyse_downbeat, check_search

    p = io.input_file(audio, "audio")
    bpm = _num(bpm, "bpm", 20.0, 400.0)
    bp = _int(beats_per_measure, "beats_per_measure", 1, 16)
    win = _num(window_ms, "window_ms", 5.0, 300.0)
    if band not in BANDS:
        raise EarError("BAD_ARGUMENT", "'band'는 'low' 또는 'full'입니다.", {"band": band})
    if (candidates is None) == (search is None):
        raise EarError("BAD_ARGUMENT", "candidates(목록)와 search 중 하나만 주세요.",
                       {"candidates": candidates, "search": search})
    cands = srch = None
    if candidates is not None:
        if (not isinstance(candidates, list) or not (1 <= len(candidates) <= 64)
                or any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in candidates)):
            raise EarError("BAD_ARGUMENT", "'candidates'는 1마디 시작 시각(초) 숫자 1~64개의 목록입니다.",
                           {"candidates": candidates})
        cands = [float(x) for x in candidates]
    else:
        srch = check_search(search, bp)
    overwrite = _bool(overwrite, "overwrite")
    target = _out_json(out_json, overwrite, [p])
    res = analyse_downbeat(p, bpm, bp, cands, srch, win, band)
    b, bph, pk, bl = res["best"], res["beat_phase"], res["peaks"], res["baseline"]
    lines = [f"{p.name}: 강한 피크 {pk['strong']}개(전체 {pk['all']}), band {band}, ±{win:g}ms, "
             f"아무 위상 share 평균 {bl['mean_share']}(p90 {bl['p90_share']})"]
    for c in res["candidates"]:
        lines.append(f"1마디 {c['measure1_sec']}초: 다운비트 {c['downbeats_hit']}/{c['downbeats_active']} = share "
                     f"{c['share']}, 피크 {c['hits']}개, 1·3박 share {c.get('beats_13_share')}, 모든 박 share "
                     f"{c.get('beat_share')}, 중앙 어긋남 {c['median_offset_ms']}ms")
    lines.append(f"best {b['measure1_sec']}초(share {b['share']}), 신뢰도 {res['confidence']}; 박 위상 {bph['phase_sec']}초 "
                 f"share {bph['beat_share']}, 반 박 뒤 {bph['offbeat_share']}")
    lines += res["notes"]
    text = "\n".join(lines)
    inputs = {"audio": str(p), "bpm": bpm, "beats_per_measure": bp, "candidates": cands, "search": srch,
              "window_ms": win, "band": band}
    if target is not None:
        io.write_json(target, _doc(SCHEMA_DOWNBEAT, "downbeat_check", inputs, res, text), overwrite)
        keys = ("best", "second", "confidence", "beat_phase", "baseline", "peaks", "half_measure_twin", "notes")
        return {"out_path": str(target), "summary_text": text, "summary": {k: res[k] for k in keys}}
    return {"out_path": None, "summary_text": text, "result": res}


# ---------------------------------------------------------------- MCP 인자 형식(느슨한 변환 막기)

# 도구 이름 -> {인자: (종류, None 허용)}. 종류: int, num, bool, list_num. str 인자는 적지 않는다.
ARG_KINDS: dict[str, dict[str, tuple[str, bool]]] = {}
# 도구 이름 -> 받을 수 있는 인자 이름(모르는 인자는 BAD_ARGUMENT, 예전에는 SDK가 조용히 버렸다)
ARG_NAMES: dict[str, set[str]] = {}
_KIND_TEXT = {"int": "정수", "num": "숫자", "bool": "true/false", "list_num": "숫자 목록"}


def _kind_of(ann: Any) -> tuple[str, bool] | None:
    args = typing.get_args(ann) if isinstance(ann, types.UnionType) or typing.get_origin(ann) is typing.Union else ()
    optional = type(None) in args
    base = [a for a in args if a is not type(None)] if args else [ann]
    if len(base) != 1:
        return None
    b = base[0]
    if b is bool:
        return "bool", optional
    if b is int:
        return "int", optional
    if b is float:
        return "num", optional
    if typing.get_origin(b) is list and typing.get_args(b) in ((float,), (int,)):
        return "list_num", optional
    return None


def _typed(fn: Callable) -> Callable:
    """MCP 도구 함수의 인자 형식을 기록한다(함수는 그대로 돌려줌).

    MCP SDK 는 인자를 pydantic 으로 느슨하게 바꾼다("2" -> 2, true -> 1, 2.0 -> 2, 그리고 '[0.9,1.1]' 같은 JSON 문자열을
    목록으로 미리 풀기). 그래서 SDK 에 넘기기 전에 원래 JSON 값의 형식을 직접 본다(check_raw_args).
    """
    import inspect

    kinds = {}
    for name, prm in inspect.signature(fn).parameters.items():
        k = _kind_of(prm.annotation)
        if k is not None:
            kinds[name] = k
    ARG_KINDS[fn.__name__] = kinds
    ARG_NAMES[fn.__name__] = set(inspect.signature(fn).parameters)
    return fn


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v == v and v not in (float("inf"), float("-inf"))


def check_raw_args(tool: str, args: dict | None) -> None:
    """원래 JSON 인자 검사: 모르는 인자 이름과 형식. 틀리면 EarError(BAD_ARGUMENT). 모르는 도구는 SDK에 맡긴다."""
    names = ARG_NAMES.get(tool)
    if names is not None and isinstance(args, dict):
        unknown = sorted(k for k in args if k not in names)
        if unknown:
            raise EarError("BAD_ARGUMENT", f"모르는 인자: {', '.join(unknown)} (쓸 수 있는 인자: {', '.join(sorted(names))})",
                           {"unknown": unknown, "allowed": sorted(names), "tool": tool})
    kinds = ARG_KINDS.get(tool) or {}
    for k, v in (args or {}).items():
        if k not in kinds:
            continue
        kind, optional = kinds[k]
        if v is None:
            if optional:
                continue
            ok = False
        elif kind == "int":
            ok = isinstance(v, int) and not isinstance(v, bool)
        elif kind == "num":
            ok = _is_num(v)
        elif kind == "bool":
            ok = isinstance(v, bool)
        else:
            ok = isinstance(v, list) and all(_is_num(x) for x in v)
        if not ok:
            raise EarError("BAD_ARGUMENT",
                           f"'{k}'는 {_KIND_TEXT[kind]}{'(또는 null)' if optional else ''}여야 합니다"
                           f"(받은 값의 형식: {type(v).__name__}). 문자열로 감싸지 마세요.",
                           {k: v, "tool": tool})


# ---------------------------------------------------------------- MCP 등록

def build_server():
    from mcp.server.mcpserver import MCPServer
    from mcp_types import CallToolResult

    class EarServer(MCPServer):
        async def _handle_call_tool(self, ctx, params):  # 프레임워크 오류(인자 검증 등)도 같은 형식으로
            try:
                check_raw_args(params.name, params.arguments)
            except EarError as e:
                log.info("tool %s error %s: %s", params.name, e.code, e.message)
                return _result(e.to_dict(), True)
            res = await super()._handle_call_tool(ctx, params)
            if isinstance(res, CallToolResult) and res.is_error:
                text = ""
                if res.content and hasattr(res.content[0], "text"):
                    text = res.content[0].text
                try:
                    d = json.loads(text)
                    if isinstance(d, dict) and d.get("ok") is False:
                        return res
                except (ValueError, TypeError):
                    pass
                low = text.lower()
                code = "BAD_ARGUMENT" if ("validation" in low or "unknown tool" in low or "argument" in low) else "INTERNAL"
                msg = "인자가 잘못됐습니다." if code == "BAD_ARGUMENT" else "내부 오류"
                return _err(code, msg, {"reason": text[:800], "tool": getattr(params, "name", None)})
            return res

    mcp = EarServer(name=SERVER_NAME, version=__version__,
                    instructions="MabiIcco 렌더 WAV(+메타 JSON)와 원곡 녹음을 파일 경로로 받아 정렬·크로마·온셋·풍성함·"
                                 "밸런스를 분석하고 JSON/마크다운 파일로 쓴다. 순서: align -> chroma_compare/onset_compare/"
                                 "richness_compare -> report. "
                                 "원곡 스템(드럼·베이스)은 stem_drum_hits·stem_bass_notes로, 노래·리드 스템의 멜로디는 stem_melody_notes로, 화음은 stem_chords로, 반복 구조는 structure_repeats로 "
                                 "MabiIcco 격자 위 타격·음을 뽑는다. "
                                 "듣기 비교용 파일은 phone_version(폰용 WAV)·excerpt_ab(렌더 A와 원곡 B를 같은 마디로 자름)으로, 마디선 위상은 downbeat_check로 확인한다. "
                                 "악보 리드가 원곡과 음 단위로 맞는지는 lead_check(원곡 믹스 또는 노래 스템, 음마다 agree/wrong/uncertain)로, "
                                 "노래 전사 파일(GAME CSV)과의 양방향 일치는 vocal_agreement로 본다. "
                                 "유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다.")

    @mcp.tool(description="오디오 파일(WAV/FLAC/MP3) 길이·샘플레이트·채널·peak/RMS(dBFS). save:true면 <name>_info.json도 쓴다.")
    @_typed
    def audio_info(path: str, save: bool = False, out_dir: str | None = None, name: str | None = None,
                   overwrite: bool = False) -> CallToolResult:
        return _run("audio_info", lambda: tool_audio_info(path, save, out_dir, name, overwrite))

    @mcp.tool(description="렌더 WAV(+메타 JSON의 마디 표)를 원곡 녹음에 크로마 DTW로 정렬. 마디·박 경계의 원곡 시각, "
                          "시작 오프셋(렌더 0초=원곡 몇 초), 템포 비율(원곡÷렌더), 신뢰도. search_sec=[a,b]면 원곡 그 구간만. "
                          "tempo_ratio_range=[최소,최대](기본 [0.9,1.1]): 비율을 그 범위로 제한해 전역 직선 탐색 뒤 "
                          "직선 ±1초 띠 안에서만 DTW(끝에 걸리면 경고). null이면 예전 자유 DTW. "
                          "마디·박 경계 옮기기 mapping: 'auto'(기본) = 경계마다 DTW 경로와 직선의 편차를 재서 중앙값 ≤ "
                          "auto_median_beats(0.5)박이고 최대(1마디 이동 중앙값 뒤) ≤ auto_max_beats(1.0)박이면 직선, 아니면 "
                          "경로(템포 고정 곡에서 경로가 경계를 끌어당기는 것을 막음). 'path'(DTW 경로)·'linear'(직선)는 그대로. "
                          "출력에 mapping_used·mapping_reason·path_deviation_sec·beat_sec. "
                          "segments=[{render_from_sec, render_to_sec}, …]: 구간마다 원곡 전체에서 따로 직선을 찾아 그 구간의 "
                          "마디·박·경로를 옮긴다(구간을 잘라낸 편곡용, chroma/onset_compare가 그대로 씀). 따로 주지 않아도 "
                          "4마디 창마다 원곡의 다른 곳과 더 잘 맞는 곳을 segment_candidates·경고로 알린다. 다른 위치가 거의 같은 점수면(반복 구간) alternatives·repeat_ambiguous·경고(search_sec으로 좁히라는 말), 신뢰도 한 단계 낮춤. "
                          "아는 것을 고정할 수 있다(템포가 일정한 곡): offset_sec(렌더 0초 = 원곡 몇 초) 또는 first_downbeat_sec"
                          "(렌더 measure_offset마디 — 생략하면 메타 첫 마디 — 시작 = 원곡 몇 초, 스템 도구와 같은 약속), tempo_ratio"
                          "(원곡÷렌더) 또는 orig_bpm(원곡 BPM, 렌더 템포 ÷ 이것 = 비율). 준 것은 그대로 쓰고 나머지만 찾는다. 시작과 "
                          "비율을 모두 주면 탐색 없이 그 직선(mapping auto는 linear로 정함, 'path'면 그 직선 ±1초 띠 DTW). "
                          "출력 fixed{given, searched}. 출력 <name>_align.json")
    @_typed
    def align(render_wav: str, render_meta: str, original_audio: str, search_sec: list[float] | None = None,
              mapping: str = "auto", out_dir: str | None = None, name: str | None = None,
              overwrite: bool = False, tempo_ratio_range: list[float] | None = [0.9, 1.1],  # noqa: B006
              auto_median_beats: float = 0.5, auto_max_beats: float = 1.0,
              segments: list[dict] | None = None, offset_sec: float | None = None,
              first_downbeat_sec: float | None = None, measure_offset: int | None = None,
              tempo_ratio: float | None = None, orig_bpm: float | None = None) -> CallToolResult:
        return _run("align", lambda: tool_align(render_wav, render_meta, original_audio, search_sec, mapping,
                                                out_dir, name, overwrite, tempo_ratio_range, auto_median_beats,
                                                auto_max_beats, segments, offset_sec, first_downbeat_sec,
                                                measure_offset, tempo_ratio, orig_bpm))

    @mcp.tool(description="정렬 결과(align_json)로 마디·박 단위 12음 크로마 코사인 유사도를 렌더와 원곡에서 비교. 마디별 점수, "
                          "하위 마디, 빠진 음(원곡≥missing_hi, 렌더<missing_lo)·더한 음. 출력 <name>_chroma.json")
    @_typed
    def chroma_compare(render_wav: str, render_meta: str, original_audio: str, align_json: str,
                       from_measure: int | None = None, to_measure: int | None = None, missing_hi: float = 0.5,
                       missing_lo: float = 0.25, top_n: int = 5, out_dir: str | None = None, name: str | None = None,
                       overwrite: bool = False) -> CallToolResult:
        return _run("chroma_compare", lambda: tool_chroma_compare(
            render_wav, render_meta, original_audio, align_json, from_measure, to_measure, missing_hi, missing_lo,
            top_n, out_dir, name, overwrite))

    @mcp.tool(description="온셋(음 시작) 비교: 원곡 온셋을 정렬표로 렌더 시각으로 옮겨 마디별 허용 오차(기본 50ms) F-점수, "
                          "어긋난 박 목록, 온셋 강도 곡선 상관. 출력 <name>_onset.json")
    @_typed
    def onset_compare(render_wav: str, render_meta: str, original_audio: str, align_json: str,
                      from_measure: int | None = None, to_measure: int | None = None, tolerance_ms: float = 50.0,
                      top_n: int = 5, out_dir: str | None = None, name: str | None = None,
                      overwrite: bool = False) -> CallToolResult:
        return _run("onset_compare", lambda: tool_onset_compare(
            render_wav, render_meta, original_audio, align_json, from_measure, to_measure, tolerance_ms, top_n,
            out_dir, name, overwrite))

    @mcp.tool(description="풍성함 비교: 정렬표(align_json)로 마디마다 (a) 음 시작(온셋) 수 렌더/원곡과 비율 onset_ratio, "
                          "(b) 저(<low_hz 250)·중·고(≥high_hz 2000Hz) 대역 세기 차(렌더−원곡 dB, 전체 세기 차를 뺀 "
                          "band_db_rel 포함), (c) 요약(마디 평균 비율, 가장 빈 마디 top_n). 비율의 절대값은 원곡 녹음(드럼·보컬·"
                          "마스터링)에 따라 1을 넘기도 하니 마디 사이 순위와 버전 사이 변화를 본다. 출력 <name>_richness.json")
    @_typed
    def richness_compare(render_wav: str, render_meta: str, original_audio: str, align_json: str,
                         from_measure: int | None = None, to_measure: int | None = None, name: str | None = None,
                         out_dir: str | None = None, overwrite: bool = False, top_n: int = 3,
                         low_hz: float = 250.0, high_hz: float = 2000.0) -> CallToolResult:
        return _run("richness_compare", lambda: tool_richness_compare(
            render_wav, render_meta, original_audio, align_json, from_measure, to_measure, name, out_dir, overwrite,
            top_n, low_hz, high_hz))

    @mcp.tool(description="split이 track/part인 렌더 메타의 파일마다 마디별 RMS(dBFS)·K-가중 소리 크기(k_db, ITU-R BS.1770, "
                          "저음을 귀처럼 작게 잼)·스펙트럼 중심. 묻힘 판정 값은 weighting='k'(기본) 또는 'rms'. '묻힘': 그 마디에서 "
                          "쉬지 않는 파트 수 N에 대해 믹스(mix_wav 또는 파트 합)보다 10·log10(N)+buried_margin_db(기본 12) dB "
                          "이상 작은 파트(buried_mode='relative', 기본; 4파트 -18, 8파트 -21, 13파트 -23dB). "
                          "buried_mode='fixed'면 예전처럼 buried_db(12) 고정. 드럼·타악기(27, 65~68) 파트는 묻힘 판정에서 뺀다"
                          "(buried_include_unpitched=true면 포함; 실로폰 77·핸드벨은 음높이 있는 파트). '저음 뭉침': 드럼·타악기·노래를 뺀 "
                          "음높이 있는 파트 low_min_parts(기본 3)개 이상이 같은 박에 low_hz(200Hz) 아래에서 셈. "
                          "리드 여유: lead_part(트랙 번호 또는 파트 라벨 \"T1\"·\"T1/melody\")를 주면 마디·구간"
                          "(sections=[{name, from_measure, to_measure} 또는 {name, start_sec, end_sec}])마다 lead_margin_db = 리드 "
                          "K-가중 − 가장 센 다른 음높이 파트, margin_band [2.0, 3.5] 안인지(in_band), 아래면 lead_margin_low 경고. "
                          "출력 <name>_balance.json")
    @_typed
    def balance(render_meta: str, mix_wav: str | None = None, from_measure: int | None = None,
                to_measure: int | None = None, buried_mode: str = "relative", buried_margin_db: float = 12.0,
                buried_db: float = 12.0, silence_db: float = -60.0,
                low_hz: float = 200.0, low_min_db: float = -40.0, low_rel_db: float = 6.0, low_min_parts: int = 3,
                buried_include_unpitched: bool = False, weighting: str = "k",
                out_dir: str | None = None, name: str | None = None, overwrite: bool = False,
                lead_part: int | str | None = None, margin_band: list[float] | None = None,
                sections: list[dict] | None = None) -> CallToolResult:
        return _run("balance", lambda: tool_balance(render_meta, mix_wav, from_measure, to_measure, buried_db,
                                                    silence_db, low_hz, low_min_db, low_rel_db, out_dir, name,
                                                    overwrite, buried_mode, buried_margin_db, low_min_parts,
                                                    buried_include_unpitched, weighting, lead_part, margin_band,
                                                    sections))

    @mcp.tool(description="두 렌더(A, B)를 마디끼리(DTW 없이) 비교: 마디별 크로마 유사도와 RMS 차(B−A dB). "
                          "기본은 같은 마디 번호끼리, b_measure_offset=k면 A의 n마디 ↔ B의 n+k마디(예: A 27~34, "
                          "B 29~36이면 2). from/to_measure는 A 번호. 짝 없는 마디는 건너뛰고 개수를 적음. "
                          "출력 <name>_ab.json(기본 name = <A>_vs_<B>)")
    @_typed
    def compare_renders(a_wav: str, a_meta: str, b_wav: str, b_meta: str, from_measure: int | None = None,
                        to_measure: int | None = None, top_n: int = 5, out_dir: str | None = None,
                        name: str | None = None, overwrite: bool = False,
                        b_measure_offset: int = 0) -> CallToolResult:
        return _run("compare_renders", lambda: tool_compare_renders(a_wav, a_meta, b_wav, b_meta, from_measure,
                                                                    to_measure, top_n, out_dir, name, overwrite,
                                                                    b_measure_offset))

    @mcp.tool(description="분석 JSON(align/chroma/onset/richness/balance/ab 중 하나 이상)을 묶어 한국어 마크다운 보고서. "
                          "richness_json을 주면 풍성함 절(음 시작 비율·대역 차·가장 빈 마디)과 표의 '음 시작 렌더/원곡' 열. 마디별 표, "
                          "하위 마디 목록, 주의 문장 포함. b_chroma_json/b_onset_json/b_balance_json(B 렌더 쪽 결과)을 "
                          "주면 마디별 표에 A·B를 나란히 둔다. 마디 짝은 b_measure_offset(생략 = ab_json의 값, 없으면 0): "
                          "A n마디 ↔ B n+offset마디, 표에는 'A번호 (B 번호)'. out_path(절대 경로) 또는 out_dir/<name>_report.md")
    @_typed
    def report(align_json: str | None = None, chroma_json: str | None = None, onset_json: str | None = None,
               balance_json: str | None = None, ab_json: str | None = None, title: str | None = None,
               out_path: str | None = None, out_dir: str | None = None, name: str | None = None,
               overwrite: bool = False, b_chroma_json: str | None = None, b_onset_json: str | None = None,
               b_balance_json: str | None = None, b_measure_offset: int | None = None,
               richness_json: str | None = None) -> CallToolResult:
        return _run("report", lambda: tool_report(align_json, chroma_json, onset_json, balance_json, ab_json, title,
                                                  out_path, out_dir, name, overwrite, b_chroma_json, b_onset_json,
                                                  b_balance_json, b_measure_offset, richness_json))

    @mcp.tool(description="드럼 스템(WAV, 예: UVR/Demucs의 *_(Drums).wav)의 타격을 찾아 킥·스네어·고스트·사이드 스틱·닫힌/오픈 하이햇·"
                          "크래시·라이드·탐(로우/미들/하이)으로 나누고 MabiIcco 격자(bpm, first_downbeat_sec = measure_offset 마디 "
                          "시작, 4분음표 = 96틱)에 놓는다. 타격마다 pos \"마디:박:틱\"·grid_error_ticks·confidence 0~1·"
                          "level_db(스템 기준 K-가중)·rel_kick_db·드럼 27 키와 세기(키 표의 킥 대비 크기로 맞춤, 최대 15). "
                          "placements = 드럼 트랙 melody 파트에 바로 넣을 score_put_notes 음표(low_tom_layer: true면 1·3박 킥에 "
                          "로우 탐 43 v9를 겹침 — 인게임에서 킥이 작음). sections=[{name, from_measure, to_measure}]별 개수, "
                          "crashes 목록, tempo_check(준 bpm과 1% 넘게 다르면 경고), notes(한계: 라이드↔오픈 하이햇·쉐이커↔하이햇 "
                          "구분 불확실). out_json(절대 경로)을 주면 전체를 그 파일에 쓰고 요약만 돌려준다")
    @_typed
    def stem_drum_hits(drum_stem_path: str, bpm: float, first_downbeat_sec: float, time_signature: str = "4/4",
                       grid: int = 16, measure_offset: int = 1, sections: list[dict] | None = None,
                       min_confidence: float = 0.0, low_tom_layer: bool = False, phase_search_ms: float = 60.0,
                       out_json: str | None = None, overwrite: bool = False) -> CallToolResult:
        return _run("stem_drum_hits", lambda: tool_stem_drum_hits(
            drum_stem_path, bpm, first_downbeat_sec, time_signature, grid, measure_offset, sections, min_confidence,
            low_tom_layer, phase_search_ms, out_json, overwrite))

    @mcp.tool(description="베이스 스템(WAV)의 음을 pyin으로 찾아(들리는 MIDI 음, range_low~range_high, 기본 23~71) MabiIcco 격자"
                          "(bpm, first_downbeat_sec, grid 16|32)에 놓고 같은 음 이어짐을 합친다. 음마다 pos·len(틱)·pitch·"
                          "confidence·octave_ambiguous(옥타브 착오 가능: pyin 옥타브 튐, 기본음 약함, 한 옥타브 아래 에너지). "
                          "measure_roots = 마디별 근음 추정, placements = score_put_notes 음표(pitch_mode \"sounding\"). "
                          "min_note_ticks(기본 12)보다 짧은 음은 뺀다. out_json(절대 경로)을 주면 전체를 그 파일에 쓰고 요약만")
    @_typed
    def stem_bass_notes(bass_stem_path: str, bpm: float, first_downbeat_sec: float, time_signature: str = "4/4",
                        grid: int = 16, measure_offset: int = 1, min_note_ticks: int = 12, range_low: int = 23,
                        range_high: int = 71, min_confidence: float = 0.0, out_json: str | None = None,
                        overwrite: bool = False) -> CallToolResult:
        return _run("stem_bass_notes", lambda: tool_stem_bass_notes(
            bass_stem_path, bpm, first_downbeat_sec, time_signature, grid, measure_offset, min_note_ticks,
            range_low, range_high, min_confidence, out_json, overwrite))

    @mcp.tool(description="노래·리드 스템(WAV, 예: *_(Vocals).wav)의 멜로디를 한 줄(단선율)로 뽑아 MabiIcco 격자(bpm, "
                          "first_downbeat_sec = measure_offset 마디 시작, 4분음표 = 96틱, grid 16|32)에 놓는다. 방법 method: "
                          "'salience'(CQT 배음 합 + 비터비, 겹친 보컬·옥타브 더블에서 가장 센 줄을 고르고 옥타브 뜀을 벌점), "
                          "'pyin', 'auto'(기본: salience + pyin을 둘째 의견으로 confidence·옥타브 표시, 음을 옮기지는 않음 — "
                          "pyin 때문에 3분 곡에 20~30초 더 걸림). 음마다 pos·tick·length_ticks·pitch(들리는 MIDI)·confidence 0~1·"
                          "source·octave_ambiguous(+octave_reasons). range_low~range_high(기본 45~86) 밖은 찾지 않는다. "
                          "같은 음이 merge_gap_ticks(기본 한 칸) 안에서 온셋 없이 이어지면 합치고 min_note_ticks(기본 한 칸)보다 "
                          "짧은 음은 뺀다. coverage(유성 %, 큰 소리 구간 중 유성 %, 음 시간 %), sections=[{name, from_measure, "
                          "to_measure}](없으면 8마디씩)별 음 수, per_measure, placements(score_put_notes, pitch_mode "
                          "\"sounding\"; 리드 악기 음역으로 옥타브를 옮겨서 쓸 것). 'ridge'(악기 솔로용): salience 음 줄 + "
                          "STFT 배음 증거로 옥타브를 다시 정함(홀수 배음이 비면 아래 옥타브가 아님, 두 옥타브가 다 울리면 줄의 "
                          "음역에 가까운 쪽) + 음역보다 9반음 넘게 낮은 한 음(스템에 샌 반주)은 빼거나 그 자리의 리드 음으로 바꿈, "
                          "같은 음은 온셋이 있을 때만 다시 침(reattack). triplets true면 16분과 8분 셋잇단(32틱) 중 가까운 격자, "
                          "from_sec·to_sec로 구간 제한(ridge만). out_json(절대 경로)을 주면 전체를 그 파일에")
    @_typed
    def stem_melody_notes(stem_path: str, bpm: float, first_downbeat_sec: float, time_signature: str = "4/4",
                          grid: int = 16, range_low: int = 45, range_high: int = 86, min_note_ticks: int | None = None,
                          method: str = "auto", measure_offset: int = 1, sections: list[dict] | None = None,
                          min_confidence: float = 0.0, merge_gap_ticks: int | None = None,
                          out_json: str | None = None, overwrite: bool = False, triplets: bool = False,
                          from_sec: float | None = None, to_sec: float | None = None) -> CallToolResult:
        return _run("stem_melody_notes", lambda: tool_stem_melody_notes(
            stem_path, bpm, first_downbeat_sec, time_signature, grid, range_low, range_high, min_note_ticks, method,
            measure_offset, sections, min_confidence, merge_gap_ticks, out_json, overwrite, triplets, from_sec,
            to_sec))

    @mcp.tool(description="화음 스템(paths: Other·Piano·Guitar 등 WAV 목록을 더함; 믹스를 쓰면 subtract_paths에 Vocals·Drums "
                          "스템을 주어 빼기)에서 박·마디마다 화음 추정: 근음, 종류(qualities 기본 maj·min·dim·aug·sus2·sus4·7·maj7·m7), "
                          "confidence 0~1, 대안 2개. 크로마 템플릿 + 조 가중(key 'auto' = Krumhansl 추정 또는 \"F minor\"·\"C\"·"
                          "\"F#m\") + 베이스 가중(bass_stem_path를 주면 근음 일치 bass_agree와 자리바꿈 /E 표시), 박 사이 비터비 평활. "
                          "마디마다 MabiIcco용 보이싱 제안 voicing(voicing_size 3|4음, voicing_range [48, 72] 안 밀집 배치, 앞 마디에서 "
                          "가장 적게 움직임)과 placements(마디 길이 화음, score_put_notes). bpm·first_downbeat_sec·measure_offset 격자는 "
                          "다른 스템 도구와 같다. per_beat false면 박 목록을 뺀다. out_json(절대 경로)을 주면 전체를 그 파일에")
    @_typed
    def stem_chords(paths: list[str], bpm: float, first_downbeat_sec: float, time_signature: str = "4/4",
                    measure_offset: int = 1, subtract_paths: list[str] | None = None,
                    bass_stem_path: str | None = None, key: str = "auto", qualities: list[str] | None = None,
                    voicing_range: list[int] | None = None, voicing_size: int = 3, per_beat: bool = True,
                    out_json: str | None = None, overwrite: bool = False) -> CallToolResult:
        return _run("stem_chords", lambda: tool_stem_chords(
            paths, bpm, first_downbeat_sec, time_signature, measure_offset, subtract_paths, bass_stem_path, key,
            qualities, voicing_range, voicing_size, per_beat, out_json, overwrite))

    @mcp.tool(description="반복 구조: 믹스 하나 또는 스템 목록(paths; 이름에 drum이 든 스템은 크로마에서 뺌)을 고정 마디 격자"
                          "(bpm, first_downbeat_sec, measure_offset)로 잘라 마디마다 크로마(박 평균, 12 전조)와 MFCC(음색)의 자기 "
                          "유사도를 재고, 대각선으로 min_run_measures(기본 4)마디 이상 이어지는 반복을 찾는다: exact(크로마와 음색 "
                          "모두), harmony(화음만 같음), transposed(r반음 옮긴 것). 문턱 thresholds 'auto'(기본: 곡 자신의 유사도 분포 "
                          "기준 — 고정 0.85는 곡마다 맞지 않음) 또는 {chroma, mfcc}. copy_map = 복사 제안 [{target [a, b], source [c, d], "
                          "kind, transpose, similarity}](exact > harmony > transposed, 긴 반복 우선; source_measures [첫, 끝]으로 원본 "
                          "범위를 제한), measure_hints = 지도에 없는 마디의 한 마디짜리 최선 짝(한 마디 루프·짧은 전조 조각), runs, "
                          "per_measure, coverage. n_measures로 마디 수를 자를 수 있다. out_json(절대 경로)을 주면 전체를 그 파일에")
    @_typed
    def structure_repeats(paths: list[str], bpm: float, first_downbeat_sec: float, time_signature: str = "4/4",
                          measure_offset: int = 1, n_measures: int | None = None, min_run_measures: int = 4,
                          thresholds: str | dict | None = "auto", source_measures: list[int] | None = None,
                          out_json: str | None = None, overwrite: bool = False) -> CallToolResult:
        return _run("structure_repeats", lambda: tool_structure_repeats(
            paths, bpm, first_downbeat_sec, time_signature, measure_offset, n_measures, min_run_measures, thresholds,
            source_measures, out_json, overwrite))

    @mcp.tool(description="폰용 파일: WAV 하나를 모노 PCM_16(sample_rate 기본 22050), peak(기본 0.98)로 맞춰 쓴다"
                          "(normalize false면 peak보다 클 때만 줄임, 1.0 넘게 키우지 않음). out(절대 경로 .wav)을 안 주면 "
                          "<입력 폴더>/폰용/<이름>_폰용.wav. 보호 폴더는 거절, 입력 폴더 아래는 허용. 돌려줌: 경로·길이·peak·크기")
    @_typed
    def phone_version(wav: str, out: str | None = None, sample_rate: int = 22050, peak: float = 0.98,
                      normalize: bool = True, overwrite: bool = False) -> CallToolResult:
        return _run("phone_version", lambda: tool_phone_version(wav, out, sample_rate, peak, normalize, overwrite))

    @mcp.tool(description="A/B 듣기 발췌: A = 렌더, B = 원곡을 같은 마디 구간으로 잘라 같은 길이(앞 lead_in_sec 1.0 + "
                          "구간 + 뒤 tail_sec 0.5초, 끝을 넘으면 무음)로 쓴다. measures = [첫, 끝](1부터, 끝 포함) 또는 그 목록. "
                          "원곡 시각은 grid={bpm, measure1_sec, beats_per_measure 4}: B 시작 = measure1_sec + (첫-1)·마디 길이 - "
                          "lead_in. 렌더 시각은 render_meta의 마디 표(주면) 또는 render_offset_sec(기본 0) + (첫-1)·마디 길이. "
                          "또는 align_json 하나로(마디 표의 orig_start_sec·render_start_sec; grid·render_offset_sec·render_meta와 "
                          "함께 주면 거절). 파일 이름 <name_prefix>_<첫>-<끝>[_<label>]_A_우리_폰용.wav, "
                          "<name_prefix>_<첫>-<끝>_B_원곡_폰용.wav(phone false면 _폰용 없이 원래 형식). out_dir 기본 "
                          "<렌더 폴더>/변형/폰용. write_b false면 A만. 돌려줌: 파일 목록과 시작 초")
    @_typed
    def excerpt_ab(render_wav: str, original_audio: str, measures: list, grid: dict | None = None,
                   render_offset_sec: float | None = None, render_meta: str | None = None,
                   align_json: str | None = None, lead_in_sec: float = 1.0, tail_sec: float = 0.5,
                   out_dir: str | None = None, name_prefix: str | None = None, label: str | None = None,
                   phone: bool = True, write_b: bool = True, overwrite: bool = False) -> CallToolResult:
        return _run("excerpt_ab", lambda: tool_excerpt_ab(
            render_wav, original_audio, measures, grid, render_offset_sec, render_meta, align_json, lead_in_sec,
            tail_sec, out_dir, name_prefix, label, phone, write_b, overwrite))

    @mcp.tool(description="다운비트 위상 확인: 드럼 스템(또는 믹스)에서 온셋 피크를 찾아, 1마디 시작 후보(candidates = 초 "
                          "목록, 또는 search={around_sec, step 'eighth'|'sixteenth'|'beat'} = 한 마디 안의 모든 위상)마다 예측 "
                          "다운비트 ±window_ms(60) 안의 강한 피크 수 hits, 다운비트 적중 비율 share(소리 나는 마디만), 1·3박 "
                          "share, 모든 박 share, 중앙 어긋남 ms. band 'low'(기본: 150Hz 아래 세기 오름 = 킥) 또는 'full'(전 대역 "
                          "스펙트럼 플럭스, 스네어 백비트가 이기기 쉬움). 아무 위상 기준(baseline), best·second(반 마디 쌍둥이는 "
                          "따로 half_measure_twin), confidence(high = share ≥ 0.8이고 둘째의 1.5배 이상), beat_phase(박 위상 대 "
                          "반 박 뒤), phase_histogram. out_json(절대 경로)을 주면 전체를 그 파일에")
    @_typed
    def downbeat_check(audio: str, bpm: float, beats_per_measure: int = 4, candidates: list[float] | None = None,
                       search: dict | None = None, window_ms: float = 60.0, band: str = "low",
                       out_json: str | None = None, overwrite: bool = False) -> CallToolResult:
        return _run("downbeat_check", lambda: tool_downbeat_check(audio, bpm, beats_per_measure, candidates, search,
                                                                  window_ms, band, out_json, overwrite))

    @mcp.tool(description="악보 리드 음마다 원곡 대조(음 단위): 원곡(original_audio; source 'mix' = 전체 믹스, 스테레오면 "
                          "가운데 강조 max(mid − side_k·side, 0), side_k 'auto'(기본) = 0/0.3/0.5/0.8/1.0 중 악보 음이 가장 많이 이기는 값에서 0.05 안의 가장 작은 값; "
                          "'stem' = 노래·리드 스템, 가운데 강조 없음, pYIN 둘째 의견 기본 켬)에서 그 음이 울리는 동안 원곡에 있는 "
                          "음이름을 찾아 agree / wrong(들린 음 heard_note) / uncertain(소리 약함·슬라이드·두 탐색이 엇갈림)으로 "
                          "판정하고 확신 0~1을 단다. 옥타브는 무시(원곡 리드 = 우리 음 + octave_shift, 생략하면 자동). 방법: STFT "
                          "4096/128, 주파수 중앙값 평탄화, 배음 1~8 합(S1)과 기본음 뺀 2~10 합(S2), 비터비 능선, 음마다 그 옥타브 "
                          "창(12음) 안의 상대 세기. 시간 지도의 작은 오차(±120ms, 구마다 ±40ms 더)는 원곡에서 고쳐 analysis.time_offset·음마다 time_offset_ms로 알리고, 판정은 음의 가운데에서 한다. 믹스의 '틀림'은 confidence 0.6 이하(의심 목록), 들린 음에는 heard_confidence·heard_alternatives. 음 목록 notes_json = 브리지 score_notes 응답을 저장한 파일(여러 트랙이면 그 목록; "
                          "pitch_mode 'sounding' 기본, velocities [12]·tick_shift·from/to_measure로 고름). 시간 지도: bpm + "
                          "first_downbeat_sec(+ time_signature, measure_offset = 그 시각이 도구 몇 마디인가, 스템 도구와 같음) 또는 "
                          "render_meta + align_json(align 결과의 마디·박을 따름). sections=[{name, from_measure, to_measure}]"
                          "(없으면 8마디씩)별 개수. transcription = 노래 전사 파일(vocal_agreement와 같은 GAME CSV/TXT, 같은 믿음 규칙; "
                          "transcription_key 'auto'|'none'|조 이름): 음마다 transcription_pitch를 적고, 전사가 악보 음을 읽는 '틀림'은 "
                          "보류(transcription_disagrees), 전사와 도구가 같은 다른 음을 듣는 '보류'는 틀림(transcription_confirms). "
                          "출력 <name>_leadcheck.json(음마다 근거 evidence)과 <name>_leadcheck.md(표)")
    @_typed
    def lead_check(original_audio: str, notes_json: str, source: str = "mix", bpm: float | None = None,
                   first_downbeat_sec: float | None = None, time_signature: str = "4/4",
                   measure_offset: int | None = None, render_meta: str | None = None, align_json: str | None = None,
                   pitch_mode: str = "sounding", velocities: list[int] | None = None, tick_shift: int = 0,
                   from_measure: int | None = None, to_measure: int | None = None, side_k: float | str = "auto",
                   range_low: int | None = None, range_high: int | None = None, octave_shift: int | None = None,
                   pyin: bool | None = None, repet: bool = False, sections: list[dict] | None = None,
                   out_dir: str | None = None, name: str | None = None, overwrite: bool = False,
                   transcription: str | None = None, transcription_key: str = "auto",
                   transcription_min_note_sec: float = 0.12) -> CallToolResult:
        return _run("lead_check", lambda: tool_lead_check(
            original_audio, notes_json, source, bpm, first_downbeat_sec, time_signature, measure_offset, render_meta,
            align_json, pitch_mode, velocities, tick_shift, from_measure, to_measure, side_k, range_low, range_high,
            octave_shift, pyin, repet, sections, out_dir, name, overwrite, transcription, transcription_key,
            transcription_min_note_sec))

    @mcp.tool(description="노래 전사(GAME 등 외부 노래→음 도구가 쓴 파일: CSV onset,offset,pitch 또는 같은 세 칸의 탭 구분 TXT; "
                          "초·실수 MIDI)와 악보 리드 음의 양방향 일치. 악보→녹음: 리드 음마다 첫 head_sec(1초) 안에 겹치는 전사 "
                          "음 중 같은 음이름이 있나(목소리가 없으면 세지 않음). 녹음→악보: 전사 음마다 그 시작을 같은 음이름의 리드 음이 "
                          "덮나(음 시작 onset_tolerance_ticks 24 전부터 끝까지). 믿음 규칙: min_note_sec(0.12초)보다 짧은 전사 음과 "
                          "조(key 'auto' = 악보 리드로 추정, 'D major'처럼 지정, 'none' = 규칙 끔) 밖이면서 slide_cents(40) 넘게 "
                          "어긋난 음은 슬라이드로 뺌. exclude_measures [[첫, 끝]]는 녹음→악보에서 뺌. 구간별(sections, 없으면 8마디씩) "
                          "수치, 다른 곳 목록(두 읽기 모두), 옥타브 관계, 시작 차 중앙값. 음 목록·시간 지도는 lead_check와 같다"
                          "(notes_json, velocities, tick_shift, bpm + first_downbeat_sec 또는 render_meta + align_json). "
                          "출력 <name>_vocal.json, <name>_vocal.md. 전사 도구는 한 줄(단선율)이라 화음 구간은 다른 목소리를 들을 수 있다")
    @_typed
    def vocal_agreement(transcription_path: str, notes_json: str, bpm: float | None = None,
                        first_downbeat_sec: float | None = None, time_signature: str = "4/4",
                        measure_offset: int | None = None, render_meta: str | None = None,
                        align_json: str | None = None, pitch_mode: str = "sounding",
                        velocities: list[int] | None = None, tick_shift: int = 0, from_measure: int | None = None,
                        to_measure: int | None = None, sections: list[dict] | None = None, key: str = "auto",
                        min_note_sec: float = 0.12, slide_cents: float = 40.0, head_sec: float = 1.0,
                        onset_tolerance_ticks: int = 24, exclude_measures: list[list[int]] | None = None,
                        out_dir: str | None = None, name: str | None = None,
                        overwrite: bool = False) -> CallToolResult:
        return _run("vocal_agreement", lambda: tool_vocal_agreement(
            transcription_path, notes_json, bpm, first_downbeat_sec, time_signature, measure_offset, render_meta,
            align_json, pitch_mode, velocities, tick_shift, from_measure, to_measure, sections, key, min_note_sec,
            slide_cents, head_sec, onset_tolerance_ticks, exclude_measures, out_dir, name, overwrite))

    return mcp


def parse_args(argv: list[str]) -> argparse.Namespace:
    ap = argparse.ArgumentParser(prog="mabiear.server", description="mabi-ear MCP 서버(stdio)")
    ap.add_argument("--out-dir", help="기본 출력 폴더(절대 경로). 기본: <MABIBRIDGE_OUT>/analysis(절대 경로), "
                    "프로젝트 파일의 analysis.dir, 아니면 <설치>/output/analysis")
    ap.add_argument("--protect", action="append", default=[], help="추가 보호 폴더(반복 가능)")
    ap.add_argument("--log", help="로그 파일. 기본 analysis/.cache/ear.log")
    return ap.parse_args(argv)


def configure(args: argparse.Namespace) -> None:
    if io.CONFIG.out_dir_error:
        raise SystemExit("[run-ear] ERROR: " + str(io.CONFIG.out_dir_error))
    if args.out_dir:
        d = Path(args.out_dir)
        if not d.is_absolute():
            raise SystemExit("[run-ear] ERROR: --out-dir must be absolute")
        io.CONFIG.out_dir = d
    io.CONFIG.extra_protect = [Path(p) for p in args.protect]
    for w in (io.CONFIG.project.warnings if io.CONFIG.project else []):
        log.warning("%s", w)
    log.info("project file: %s", f"{io.CONFIG.project.file} (found {io.project_found_via(io.CONFIG.project)})"
             if io.CONFIG.project else "none (nothing is protected except --protect)")
    for root in io.protected_roots():
        if io.is_within(io.CONFIG.out_dir, root):
            # same as the bridge: warn and keep running; tool calls without out_dir then get PROTECTED_PATH
            log.warning("default output folder %s is inside the protected folder %s: calls without out_dir will be "
                        "refused (PROTECTED_PATH)", io.CONFIG.out_dir, root)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.log:
        for root in io.protected_roots() + [Path(p) for p in args.protect]:
            if io.is_within(args.log, root):
                raise SystemExit(f"[run-ear] ERROR: --log is inside a protected folder: {root}")
    setup_logging(Path(args.log) if args.log else CACHE_DIR / "ear.log")
    configure(args)
    server = build_server()
    log.info("mabi-ear %s ready in %.2fs (out_dir=%s, package=%s)", __version__, time.perf_counter() - _T0,
             io.CONFIG.out_dir, Path(__file__).resolve().parent)
    server.run("stdio")


if __name__ == "__main__":
    main()
