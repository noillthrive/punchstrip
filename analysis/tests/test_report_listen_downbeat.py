"""report with section-level warnings, phone_version, excerpt_ab, downbeat_check (synthetic audio)."""
from __future__ import annotations

import json

import numpy as np
import pytest
import soundfile as sf

import synth
from mabiear import io, report, server
from mabiear.io import EarError


def _err(fn, code="BAD_ARGUMENT"):
    with pytest.raises(EarError) as e:
        fn()
    assert e.value.code == code, (e.value.code, e.value.message)
    return e.value


# ---------------------------------------------------------------- 1. report crash

def _balance_doc():
    return {"schema": "mabiear.balance/1", "tool": "balance", "params": {"buried_mode": "relative"},
            "weighting_used": "k", "counts": {"buried": 1, "low_crowding": 1, "buried_by_part": {"T2": 1},
                                              "lead_margin_low": 2},
            "measures": [{"measure": 1}, {"measure": 2}],
            "lead_margin": {"lead_part": ["T1"], "margin_band": [2.0, 3.5],
                            "sections": [{"name": "V1", "from_measure": 10, "to_measure": 17, "lead_margin_db": 1.95},
                                         {"name": "Ch", "from_measure": 22, "to_measure": 25, "lead_margin_db": 1.2}]},
            "warnings": [
                {"type": "buried", "measure": 2, "part": "T2", "rel_mix_db_used": -20.0},
                {"type": "low_crowding", "measure": 1, "beat": 3},
                {"type": "lead_margin_low", "section": "V1", "from_measure": 10, "to_measure": 17,
                 "lead_margin_db": 1.95, "main_competitor": "T8", "band": [2.0, 3.5]},
                {"type": "lead_margin_low", "section": "Ch", "from": 22, "to": 25, "lead_margin_db": 1.2,
                 "main_competitor": "T3", "band": [2.0, 3.5]},
                {"type": "something_new", "text": "곡 전체 경고"},  # no measure, no range
            ]}


def test_report_section_warnings_do_not_crash():
    md, summary = report.build("t", {"balance": _balance_doc()}, {"balance": "x"})
    assert "리드 여유 낮음: V1(10~17마디) 2.0dB" in md or "리드 여유 낮음: V1(10~17마디) 1.9" in md
    assert "Ch(22~25마디)" in md and "가장 센 경쟁 T3" in md
    assert "something_new: 곡 전체 경고" in md
    assert "묻힘 T2(-20.0dB)" in md and "저음뭉침 3박" in md
    assert summary["balance_range_warnings"] == 3


def test_report_tool_with_section_warnings(tmp_path):
    p = synth.write_json(tmp_path / "in" / "x_balance.json", _balance_doc())
    b = synth.write_json(tmp_path / "in" / "y_balance.json", _balance_doc())
    out = tmp_path / "out" / "r.md"
    res = server.tool_report(balance_json=str(p), b_balance_json=str(b), out_path=str(out))
    assert out.is_file() and res["summary"]["b_balance_range_warnings"] == 3
    assert "## 밸런스 (B)" in out.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 2. phone_version

@pytest.fixture()
def stereo_wav(tmp_path):
    sr = 44100
    t = np.arange(int(2.0 * sr)) / sr
    y = np.stack([0.3 * np.sin(2 * np.pi * 440 * t), 0.3 * np.sin(2 * np.pi * 440 * t)], axis=1).astype(np.float32)
    p = tmp_path / "src" / "곡 v1.wav"
    p.parent.mkdir(parents=True)
    sf.write(str(p), y, sr, subtype="PCM_24")
    return p


def test_phone_version_default_path_and_format(stereo_wav):
    res = server.tool_phone_version(str(stereo_wav))
    out = stereo_wav.parent / "폰용" / "곡 v1_폰용.wav"
    assert res["out_path"] == str(out) and out.is_file()
    info = sf.info(str(out))
    assert (info.samplerate, info.channels, info.subtype) == (22050, 1, "PCM_16")
    y, _ = sf.read(str(out))
    assert abs(np.max(np.abs(y)) - 0.98) < 1e-3
    s = res["summary"]
    assert s["duration_sec"] == pytest.approx(2.0, abs=1e-3) and s["size_bytes"] == out.stat().st_size
    assert s["gain_db"] > 9 and s["input"]["channels"] == 2
    _err(lambda: server.tool_phone_version(str(stereo_wav)), "EXISTS")
    server.tool_phone_version(str(stereo_wav), overwrite=True)


def test_phone_version_normalize_false_and_args(stereo_wav, tmp_path):
    out = tmp_path / "o" / "a.wav"
    res = server.tool_phone_version(str(stereo_wav), out=str(out), normalize=False, sample_rate=16000)
    assert sf.info(str(out)).samplerate == 16000 and res["summary"]["peak"] == pytest.approx(0.3, abs=2e-3)
    loud = tmp_path / "src2" / "loud.wav"
    synth.write_wav(loud, (0.999 * np.sin(np.arange(22050) / 5)).astype(np.float32))
    r2 = server.tool_phone_version(str(loud), out=str(tmp_path / "o" / "b.wav"), normalize=False)
    assert r2["summary"]["peak"] == pytest.approx(0.98, abs=1e-3)  # only scaled down
    _err(lambda: server.tool_phone_version(str(stereo_wav), out=str(stereo_wav), overwrite=True), "PROTECTED_PATH")
    _err(lambda: server.tool_phone_version(str(stereo_wav), out=str(tmp_path / "o" / "c.flac")))
    _err(lambda: server.tool_phone_version(str(stereo_wav), out="rel.wav"))
    _err(lambda: server.tool_phone_version(str(stereo_wav), out=str(tmp_path / "d.wav"), peak=1.2))
    _err(lambda: server.tool_phone_version(str(stereo_wav), out=str(tmp_path / "e.wav"), sample_rate=22050.0))
    old = io.CONFIG.extra_protect
    io.CONFIG.extra_protect = [tmp_path / "o"]
    try:
        _err(lambda: server.tool_phone_version(str(stereo_wav), out=str(tmp_path / "o" / "z.wav")), "PROTECTED_PATH")
    finally:
        io.CONFIG.extra_protect = old


# ---------------------------------------------------------------- 3. excerpt_ab

BPM = 120.0  # measure = 2.0 s
CHORDS = [["C4", "E4", "G4"], ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["G4", "B3", "D4"], ["C4", "E4", "G4"]]


@pytest.fixture()
def ab_files(tmp_path):
    render = synth.write_wav(tmp_path / "render" / "r.wav", synth.song(CHORDS, BPM, tail=0.5))
    orig = synth.write_wav(tmp_path / "orig" / "o.wav", synth.song(CHORDS, BPM, lead=0.3, tail=0.5))
    meta = synth.write_json(tmp_path / "render" / "r.json", synth.meta_for(BPM, len(CHORDS)))
    return render, orig, meta


def _corr(a, b):
    x, _ = sf.read(str(a))
    y, _ = sf.read(str(b))
    assert x.size == y.size
    return float(np.corrcoef(x, y)[0, 1])


def test_excerpt_ab_grid(ab_files, tmp_path):
    render, orig, _ = ab_files
    out = tmp_path / "ex"
    res = server.tool_excerpt_ab(str(render), str(orig), [2, 3], grid={"bpm": 120, "measure1_sec": 0.3},
                                 out_dir=str(out), name_prefix="v9", label="리드A")
    a = out / "v9_2-3_리드A_A_우리_폰용.wav"
    b = out / "v9_2-3_B_원곡_폰용.wav"
    assert a.is_file() and b.is_file()
    fs = {f["side"]: f for f in res["summary"]["files"]}
    assert fs["B"]["start_sec"] == pytest.approx(0.3 + 2.0 - 1.0) and fs["A"]["start_sec"] == pytest.approx(1.0)
    assert fs["A"]["duration_sec"] == fs["B"]["duration_sec"] == pytest.approx(1.0 + 4.0 + 0.5)
    for p in (a, b):
        info = sf.info(str(p))
        assert (info.samplerate, info.channels, info.subtype) == (22050, 1, "PCM_16")
        assert abs(np.max(np.abs(sf.read(str(p))[0])) - 0.98) < 1e-3
    assert _corr(a, b) > 0.999
    assert res["summary"]["render_timeline"] == "render_offset_sec"


def test_excerpt_ab_padding_meta_and_write_b(ab_files, tmp_path):
    render, orig, meta = ab_files
    out = tmp_path / "ex2"
    res = server.tool_excerpt_ab(str(render), str(orig), [[1, 1], [5, 5]], grid={"bpm": 120, "measure1_sec": 0.3},
                                 render_meta=str(meta), out_dir=str(out), name_prefix="p", tail_sec=2.0)
    fs = res["summary"]["files"]
    first_a = next(f for f in fs if f["side"] == "A" and f["measures"] == [1, 1])
    first_b = next(f for f in fs if f["side"] == "B" and f["measures"] == [1, 1])
    assert first_a["pad_start_sec"] == pytest.approx(1.0) and first_b["pad_start_sec"] == pytest.approx(0.7)
    last = [f for f in fs if f["measures"] == [5, 5]]
    assert all(f["pad_end_sec"] > 1.0 for f in last)  # tail runs past the end -> silence
    assert res["summary"]["render_timeline"] == "render_meta"
    assert _corr(out / "p_1-1_A_우리_폰용.wav", out / "p_1-1_B_원곡_폰용.wav") > 0.999
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [1, 1], grid={"bpm": 120, "measure1_sec": 0.3},
                                        out_dir=str(out), name_prefix="p"), "EXISTS")
    r2 = server.tool_excerpt_ab(str(render), str(orig), [1, 1], grid={"bpm": 120, "measure1_sec": 0.3},
                                out_dir=str(out), name_prefix="p", label="L2", write_b=False)
    assert [f["side"] for f in r2["summary"]["files"]] == ["A"]
    # phone false keeps the format (22.05 kHz PCM_16 mono synth input)
    r3 = server.tool_excerpt_ab(str(render), str(orig), [2, 2], grid={"bpm": 120, "measure1_sec": 0.3},
                                out_dir=str(out), name_prefix="raw", phone=False)
    assert (out / "raw_2-2_B_원곡.wav").is_file() and r3["summary"]["phone"] is False


def test_excerpt_ab_align_json(ab_files, tmp_path):
    render, orig, _ = ab_files
    ms = [{"measure": i + 1, "render_start_sec": 2.0 * i, "render_end_sec": 2.0 * (i + 1),
           "orig_start_sec": 0.3 + 2.0 * i, "orig_end_sec": 0.3 + 2.0 * (i + 1)} for i in range(5)]
    aj = synth.write_json(tmp_path / "an" / "x_align.json", {"schema": "mabiear.align/1", "tempo_ratio": 1.0,
                                                              "inputs": {"render_wav": str(render)}, "measures": ms})
    out = tmp_path / "ex3"
    res = server.tool_excerpt_ab(str(render), str(orig), [3, 4], align_json=str(aj), out_dir=str(out), name_prefix="al")
    assert res["summary"]["render_timeline"] == "align_json" and res["summary"]["tempo_ratio"] == 1.0
    assert _corr(out / "al_3-4_A_우리_폰용.wav", out / "al_3-4_B_원곡_폰용.wav") > 0.999
    assert res["summary"]["warnings"] == []
    grid = {"bpm": 120, "measure1_sec": 0.3}
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [3, 4], align_json=str(aj), grid=grid,
                                        out_dir=str(out)))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [3, 4], align_json=str(aj), render_offset_sec=0.0,
                                        out_dir=str(out)))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [3, 4], out_dir=str(out)))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [9, 9], align_json=str(aj), out_dir=str(out)))


def test_excerpt_ab_bad_args(ab_files, tmp_path):
    render, orig, meta = ab_files
    g = {"bpm": 120, "measure1_sec": 0.3}
    d = str(tmp_path / "ex4")
    for bad in ([3, 2], [[1, 2], [1, 2]], [0, 1], "1-2", [[1, 2, 3]], [1.0, 2.0]):
        _err(lambda bad=bad: server.tool_excerpt_ab(str(render), str(orig), bad, grid=g, out_dir=d))
    for gb in ({"bpm": 120}, {"bpm": "120", "measure1_sec": 0.3}, {"bpm": 120, "measure1_sec": 0.3, "x": 1},
               {"bpm": 120, "measure1_sec": 0.3, "beats_per_measure": 4.0}):
        _err(lambda gb=gb: server.tool_excerpt_ab(str(render), str(orig), [1, 2], grid=gb, out_dir=d))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [1, 2], grid=g, render_meta=str(meta),
                                        render_offset_sec=0.0, out_dir=d))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [1, 2], grid=g, label="a/b", out_dir=d))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [1, 2], grid=g, out_dir="rel"))
    _err(lambda: server.tool_excerpt_ab(str(render), str(orig), [1, 9], grid=g, render_meta=str(meta), out_dir=d))


# ---------------------------------------------------------------- 4. downbeat_check

@pytest.fixture(scope="module")
def drums(tmp_path_factory):
    y, _ = synth.drum_stem(bpm=120.0, first=0.5, measures=16, crashes=())  # kick 1+3, snare 2+4, 8th hats
    return synth.write_wav(tmp_path_factory.mktemp("db") / "drums.wav", y, synth.DRUM_SR)


def test_downbeat_candidates_low_band(drums):
    res = server.tool_downbeat_check(str(drums), 120, candidates=[0.5, 0.75])
    R = res["result"]
    a, b = R["candidates"]
    assert a["share"] == 1.0 and a["downbeats_active"] == 16 and a["hits"] == 16
    assert b["share"] == 0.0 and b["hits"] == 0
    assert abs(a["median_offset_ms"]) < 15
    assert R["best"]["measure1_sec"] == 0.5 and R["confidence"] == "high"
    assert R["half_measure_twin"]["share"] == 1.0 and R["notes"]  # kick on 1 and 3: twin flagged
    assert R["beat_phase"]["offbeat_share"] == 0.0
    assert R["baseline"]["mean_share"] < 0.3
    assert sum(R["phase_histogram"]["counts"]) == R["peaks"]["strong"]


def test_downbeat_search_and_full_band(drums, tmp_path):
    R = server.tool_downbeat_check(str(drums), 120, search={"around_sec": 0.5, "step": "eighth"})["result"]
    assert len(R["candidates"]) == 8 and R["grid"]["mode"] == "search"
    assert R["best"]["phase_sec"] in (0.5, 1.5) and R["best"]["share"] == 1.0
    assert R["second"]["share"] <= 0.1 and R["confidence"] == "high"
    # full band: the snare backbeat is the loudest onset, so the full-band best sits on beat 2/4
    F = server.tool_downbeat_check(str(drums), 120, candidates=[0.5, 1.0], band="full")["result"]
    assert F["best"]["measure1_sec"] == 1.0
    out = tmp_path / "db.json"
    r = server.tool_downbeat_check(str(drums), 120, candidates=[0.5], out_json=str(out))
    d = json.loads(out.read_text(encoding="utf-8"))
    assert d["schema"] == "mabiear.downbeat/1" and r["summary"]["confidence"] in ("high", "medium", "low")


def test_downbeat_bad_args(drums):
    p = str(drums)
    _err(lambda: server.tool_downbeat_check(p, 120))
    _err(lambda: server.tool_downbeat_check(p, 120, candidates=[0.5], search={"around_sec": 0.5}))
    _err(lambda: server.tool_downbeat_check(p, 120, candidates=[]))
    _err(lambda: server.tool_downbeat_check(p, 120, candidates=["0.5"]))
    _err(lambda: server.tool_downbeat_check(p, 120, search={"around_sec": 0.5, "step": "quarter"}))
    _err(lambda: server.tool_downbeat_check(p, 120, search={"around": 0.5}))
    _err(lambda: server.tool_downbeat_check(p, 120, candidates=[0.5], band="mid"))
    _err(lambda: server.tool_downbeat_check(p, 120, candidates=[0.5], window_ms=1))


def test_raw_arg_checks_new_tools():
    server.build_server()  # registers ARG_NAMES / ARG_KINDS
    _err(lambda: server.check_raw_args("downbeat_check", {"audio": "x", "bpm": "100"}))
    _err(lambda: server.check_raw_args("downbeat_check", {"audio": "x", "bpm": 100, "candidates": "[0.6]"}))
    _err(lambda: server.check_raw_args("excerpt_ab", {"render_wav": "x", "lead_in": 1}))
    _err(lambda: server.check_raw_args("phone_version", {"wav": "x", "sample_rate": 22050.0}))
    server.check_raw_args("excerpt_ab", {"render_wav": "x", "original_audio": "y", "measures": [1, 2],
                                         "grid": {"bpm": 100, "measure1_sec": 0.3}})
