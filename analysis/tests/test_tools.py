"""합성 신호로 도구 7개를 시험한다(외부 파일 불필요)."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

import synth
from mabiear import io, server
from mabiear.io import EarError

CEG = ["C4", "E4", "G4"]
ACE = ["A3", "C4", "E4"]
AC = ["A3", "C4"]


@pytest.fixture
def case(tmp_path: Path):
    """원곡: 1.5초 무음 + C-E-G 4박 + A-C-E 4박(120BPM) + 1초 무음.
    렌더: 같은 내용을 5% 빠르게(126BPM), 0초부터, 꼬리 1초. 메타는 손으로 쓴 2마디."""
    d_in = tmp_path / "in"
    out = tmp_path / "out"
    orig = synth.write_wav(d_in / "orig.wav", synth.song([CEG, ACE], 120, lead=1.5, tail=1.0))
    rend = synth.write_wav(d_in / "render.wav", synth.song([CEG, ACE], 126, tail=1.0))
    meta = synth.write_json(d_in / "render.json", synth.meta_for(126, 2, wav=str(rend)))
    return {"in": d_in, "out": out, "orig": orig, "render": rend, "meta": meta, "tmp": tmp_path}


def do_align(c, render=None, meta=None, **kw):
    return server.tool_align(str(render or c["render"]), str(meta or c["meta"]), str(c["orig"]),
                             out_dir=str(c["out"]), **kw)


# ---------------------------------------------------------------- 2-가 정렬

def test_align_offset_and_tempo(case):
    res = do_align(case)
    s = res["summary"]
    assert abs(s["offset_sec"] - 1.5) < 0.05, s
    assert abs(s["tempo_ratio"] - 1.05) / 1.05 < 0.01, s
    ms = s["measures"]
    assert abs(ms[0]["orig_start_sec"] - 1.5) < 0.05
    assert abs(ms[1]["orig_start_sec"] - 3.5) < 0.05
    assert abs(ms[1]["orig_end_sec"] - 5.5) < 0.05
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.align/1"
    assert len(doc["measures"]) == 2 and len(doc["measures"][0]["beats_orig"]) == 4
    assert Path(res["out_path"]).name == "render_align.json"


def test_align_render_starts_late(tmp_path):
    """반대 방향: 렌더 앞에 1.5초 무음(마디 1이 렌더 1.5초에 시작), 원곡은 0초부터."""
    d = tmp_path / "in"
    orig = synth.write_wav(d / "orig.wav", synth.song([CEG, ACE], 120, tail=1.0))
    rend = synth.write_wav(d / "late.wav", synth.song([CEG, ACE], 126, lead=1.5, tail=1.0))
    meta = synth.write_json(d / "late.json", synth.meta_for(126, 2, start=1.5))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert abs(s["first_measure_orig_sec"] - 0.0) < 0.05, s
    assert abs(s["offset_sec"] - (-1.5 * 1.05)) < 0.05, s
    assert abs(s["tempo_ratio"] - 1.05) / 1.05 < 0.01, s


def test_align_search_window(case):
    res = do_align(case, search_sec=[1.0, 6.5], name="win")
    s = res["summary"]
    assert abs(s["offset_sec"] - 1.5) < 0.05
    assert Path(res["out_path"]).name == "win_align.json"


def test_align_ratio_range_default_and_free(case):
    res = do_align(case)
    s = res["summary"]
    assert s["tempo_ratio_range"] == [0.9, 1.1] and s["fit_method"] == "global_search+warp_refine"
    assert s["warnings"] == [] and s["confidence"] == "높음", s
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert doc["global_search"]["ratios_tried"] == 41 and doc["params"]["tempo_ratio_range"] == [0.9, 1.1]
    # null: 예전 자유 DTW
    res = do_align(case, tempo_ratio_range=None, name="free")
    s = res["summary"]
    assert s["tempo_ratio_range"] is None and s["fit_method"].startswith("path_fit")
    assert abs(s["offset_sec"] - 1.5) < 0.05 and abs(s["tempo_ratio"] - 1.05) / 1.05 < 0.01


def test_align_ratio_range_edge_warning(case):
    """실제 비율 1.05인데 범위를 [0.9, 1.0]으로 막으면 끝(1.0)에 걸렸다는 경고와 신뢰도 한 단계 낮춤."""
    res = do_align(case, tempo_ratio_range=[0.9, 1.0], name="edge")
    s = res["summary"]
    assert abs(s["tempo_ratio"] - 1.0) < 0.003, s
    assert any("끝에 걸렸다" in w for w in s["warnings"]), s
    assert s["confidence"] != "높음" and "경고:" in res["summary_text"]
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert doc["confidence"]["warnings"] == s["warnings"]
    # 보고서에도 경고 줄
    rep = server.tool_report(align_json=res["out_path"], out_dir=str(case["tmp"] / "rep"))
    assert "- 경고: " in Path(rep["out_path"]).read_text(encoding="utf-8")


@pytest.mark.parametrize("bad", [[1.2, 1.1], [0.1, 1.0], [0.9, 2.5], [1.0], "0.9,1.1", [True, 1.1]])
def test_align_ratio_range_invalid(case, bad):
    r = server._run("align", lambda: do_align(case, tempo_ratio_range=bad))
    assert err_of(r)["error"] == "BAD_ARGUMENT"


# ---------------------------------------------------------------- 2-나 크로마

def test_chroma_missing_E_in_measure_2(case):
    rend2 = synth.write_wav(case["in"] / "noE.wav", synth.song([CEG, AC], 126, tail=1.0))
    al = do_align(case, render=rend2)
    res = server.tool_chroma_compare(str(rend2), str(case["meta"]), str(case["orig"]), al["out_path"],
                                     out_dir=str(case["out"]))
    bottom = res["summary"]["bottom"]
    assert bottom[0]["measure"] == 2, bottom
    assert "E" in bottom[0]["missing"], bottom
    assert bottom[0]["score"] < bottom[1]["score"]
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    m1 = [m for m in doc["measures"] if m["measure"] == 1][0]
    assert m1["score"] > 0.95 and m1["missing"] == []


def test_chroma_identical_is_high(case):
    al = do_align(case)
    res = server.tool_chroma_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                                     out_dir=str(case["out"]))
    assert res["summary"]["mean_score"] > 0.95


# ---------------------------------------------------------------- 온셋

def test_onset_compare(case):
    al = do_align(case)
    res = server.tool_onset_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                                    out_dir=str(case["out"]))
    assert res["summary"]["mean_f"] is not None and res["summary"]["mean_f"] >= 0.75, res["summary"]
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert {m["measure"] for m in doc["measures"]} == {1, 2}


def test_onset_detects_missing_beat(case):
    """렌더 2마디 3박을 쉬게 하면 그 박이 어긋난 박 목록에 나온다."""
    y = synth.song([CEG, ACE], 126, tail=1.0)
    beat = 60 / 126
    a = int((4 * beat + 2 * beat) * synth.SR)
    y[a:a + int(beat * synth.SR)] = 0
    r2 = synth.write_wav(case["in"] / "rest.wav", y)
    al = do_align(case, render=r2)
    res = server.tool_onset_compare(str(r2), str(case["meta"]), str(case["orig"]), al["out_path"],
                                    out_dir=str(case["out"]))
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    hits = [b for b in doc["mismatched_beats"] if b["measure"] == 2 and b["orig_only_render_sec"]]
    assert hits, doc["mismatched_beats"]
    assert res["summary"]["bottom"][0]["measure"] == 2


# ---------------------------------------------------------------- 2-다 밸런스

def balance_case(tmp_path: Path, parts: dict, info: dict | None = None, side_meta: bool = False):
    """info: {이름: {"program": .., "instrument_type": ..}}. side_meta=True면 렌더 메타 tracks[]에는 1번 트랙만 두고
    나머지 트랙 정보는 WAV 옆 같은 이름 .json(브리지의 트랙별 메타처럼)에 둔다."""
    d = tmp_path / "in"
    files, tracks = [], []
    for i, (label, y) in enumerate(parts.items(), start=1):
        p = synth.write_wav(d / f"part{i}.wav", y)
        files.append({"path": str(p), "tracks": [i], "parts": "all"})
        t = {"track": i, "name": label, **((info or {}).get(label) or {})}
        tracks.append(t)
        if side_meta:
            sm = synth.meta_for(120, 2, files=[{"path": str(p), "tracks": [i], "parts": "all"}], split="track")
            sm["tracks"] = [t]
            synth.write_json(p.with_suffix(".json"), sm)
    meta = synth.meta_for(120, 2, files=files, split="track")
    meta["tracks"] = tracks[:1] if side_meta else tracks
    return synth.write_json(d / "split.json", meta)


def balance_doc(res) -> dict:
    return json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))


def test_balance_buried_part(tmp_path):
    chords = synth.song([CEG, ACE], 120)
    bass = synth.song([["E2"], ["A1"]], 120)
    melody = synth.song([["A4"], ["G4"]], 120) * 10 ** (-20 / 20)  # -20dB
    meta = balance_case(tmp_path, {"Chords": chords, "Bass": bass, "Melody": melody})
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    cnt = res["summary"]["counts"]
    assert cnt["buried_by_part"] == {"T3 Melody": 2}, res["summary"]
    assert cnt["low_crowding"] == 0, res["summary"]
    assert "Melody" in res["summary_text"]


def test_balance_low_crowding(tmp_path):
    chords = synth.song([CEG, ACE], 120)
    bass1 = synth.song([["E2"], ["A1"]], 120)
    bass2 = synth.song([["G2"], ["E2"]], 120)
    meta = balance_case(tmp_path, {"Chords": chords, "Bass": bass1, "Cello": bass2})
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), low_min_parts=2)  # 예전 조건(둘 이상)
    ws = [w for w in res["summary"]["warnings"] if w["type"] == "low_crowding"]
    assert len(ws) == 8, ws  # 2마디 x 4박
    assert set(ws[0]["parts"]) == {"T2 Bass", "T3 Cello"}
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), overwrite=True)  # 기본 3파트 이상
    assert res["summary"]["counts"]["low_crowding"] == 0, res["summary"]


LOW3 = {"Bass": [["E2"], ["A1"]], "Cello": [["G2"], ["E2"]], "Tuba": [["C3"], ["G2"]]}


def test_balance_low_crowding_three_pitched(tmp_path):
    parts = {"Chords": synth.song([CEG, ACE], 120), **{k: synth.song(v, 120) for k, v in LOW3.items()}}
    meta = balance_case(tmp_path, parts, info={k: {"program": 23, "instrument_type": "N"} for k in parts})
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    ws = [w for w in res["summary"]["warnings"] if w["type"] == "low_crowding"]
    assert len(ws) == 8, ws
    assert set(ws[0]["parts"]) == {"T2 Bass", "T3 Cello", "T4 Tuba"}
    doc = balance_doc(res)
    assert doc["params"]["low_min_parts"] == 3
    assert all(p["pitched"] is True for p in doc["parts"])


@pytest.mark.parametrize("drum", [{"program": 27, "instrument_type": "D"},  # 드럼
                                  {"program": 66, "instrument_type": "P"},  # 타악기(65~76)
                                  {"program": 27}])                          # 종류 없이 program만
@pytest.mark.parametrize("side_meta", [False, True])
def test_balance_low_crowding_excludes_drums(tmp_path, drum, side_meta):
    """저음 파트 셋 중 하나가 드럼·타악기면 음높이 있는 파트는 둘 → 기본(3개)에서 경고 없음.
    low_min_parts=2 로 낮춰도 드럼은 세지 않는다(남은 둘만 겹침으로 나옴)."""
    parts = {"Chords": synth.song([CEG, ACE], 120), **{k: synth.song(v, 120) for k, v in LOW3.items()}}
    info = {k: {"program": 23, "instrument_type": "N"} for k in parts}
    info["Tuba"] = drum
    meta = balance_case(tmp_path, parts, info=info, side_meta=side_meta)
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    assert res["summary"]["counts"]["low_crowding"] == 0, res["summary"]
    doc = balance_doc(res)
    tuba = [p for p in doc["parts"] if p["tracks"] == [4]][0]
    assert tuba["pitched"] is False and tuba["programs"] == [drum["program"]]
    assert tuba["label"] not in doc["counts"]["low_crowding_parts_counted"]
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), low_min_parts=2, overwrite=True)
    ws = [w for w in res["summary"]["warnings"] if w["type"] == "low_crowding"]
    assert ws and all(tuba["label"] not in w["parts"] for w in ws), ws


def test_balance_buried_threshold_values():
    from mabiear.balance import buried_threshold
    assert buried_threshold("relative", 1, 12, 12) == pytest.approx(-12.0)
    assert buried_threshold("relative", 4, 12, 12) == pytest.approx(-18.02, abs=0.01)
    assert buried_threshold("relative", 8, 12, 12) == pytest.approx(-21.03, abs=0.01)
    assert buried_threshold("relative", 13, 12, 12) == pytest.approx(-23.14, abs=0.01)
    assert buried_threshold("relative", 4, 12, 6) == pytest.approx(-12.02, abs=0.01)
    assert buried_threshold("fixed", 13, 12, 6) == -12.0


def test_balance_relative_threshold_follows_active_parts(tmp_path):
    """4번째 파트가 1마디만 소리 내면 N이 1마디 4, 2마디 3 → 마디별 기준이 달라지고, 경고는 그 마디 기준을 따른다."""
    beat = 0.5
    extra = np.concatenate([synth.chord_beats(["F3"], 4, beat), synth.silence(4 * beat)])
    parts = {"Chords": synth.song([CEG, ACE], 120), "Bass": synth.song([["E2"], ["A1"]], 120),
             "Melody": synth.song([["A4"], ["G4"]], 120) * 10 ** (-20 / 20), "Extra": extra}
    meta = balance_case(tmp_path, parts)
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    doc = balance_doc(res)
    assert doc["params"]["buried_mode"] == "relative"
    m1, m2 = doc["measures"]
    assert (m1["active_parts"], m2["active_parts"]) == (4, 3)
    assert m1["threshold_db"] == pytest.approx(-18.02, abs=0.01)
    assert m2["threshold_db"] == pytest.approx(-16.77, abs=0.01)
    buried = {(w["measure"], w["part"]) for w in doc["warnings"] if w["type"] == "buried"}
    for m in doc["measures"]:
        for p in m["parts"]:
            want = (not p["silent"]) and p["rel_mix_db"] <= m["threshold_db"]
            assert ((m["measure"], p["label"]) in buried) == want, (m["measure"], p)
    for w in doc["warnings"]:
        if w["type"] == "buried":
            mm = m1 if w["measure"] == 1 else m2
            assert w["threshold_db"] == mm["threshold_db"] and w["active_parts"] == mm["active_parts"]
    assert (2, "T4 Extra") not in buried  # 2마디에서 쉬는 파트
    assert doc["counts"]["active_part_measures"] == 7


def test_balance_fixed_mode_compat(tmp_path):
    """믹스보다 약 14dB 작은 파트: 상대 기준(N=2 → -15dB)에서는 묻힘 아님, 고정 12dB에서는 묻힘."""
    chords = synth.song([CEG, ACE], 120)
    quiet = synth.song([["A4"], ["G4"]], 120) * 10 ** (-9 / 20)
    meta = balance_case(tmp_path, {"Chords": chords, "Quiet": quiet})
    rel = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    d = balance_doc(rel)
    vals = [p["rel_mix_db"] for m in d["measures"] for p in m["parts"] if p["label"] == "T2 Quiet"]
    assert all(-15.0 < v < -12.0 for v in vals), vals
    assert rel["summary"]["counts"]["buried"] == 0
    fx = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), buried_mode="fixed", overwrite=True)
    assert fx["summary"]["counts"]["buried_by_part"] == {"T2 Quiet": 2}
    d = balance_doc(fx)
    assert d["params"]["buried_mode"] == "fixed"
    assert all(m["threshold_db"] == -12.0 for m in d["measures"])
    fx = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), buried_mode="fixed", buried_db=15,
                             overwrite=True)
    assert fx["summary"]["counts"]["buried"] == 0


@pytest.mark.parametrize("drum", [{"program": 27, "instrument_type": "D"}, {"program": 68, "instrument_type": "P"}])
def test_balance_unpitched_not_buried_by_default(tmp_path, drum):
    """드럼·타악기 파트가 믹스보다 -30dB 근처여도 기본에서는 묻힘 경고 없음(값은 남김), true면 경고."""
    parts = {"Chords": synth.song([CEG, ACE], 120), "Bass": synth.song([["E2"], ["A1"]], 120),
             "Drum": synth.song([["A4"], ["G4"]], 120) * 10 ** (-26 / 20),
             "Voice": synth.song([["C4"], ["A3"]], 120) * 10 ** (-26 / 20)}
    info = {"Chords": {"program": 21, "instrument_type": "N"}, "Bass": {"program": 29, "instrument_type": "N"},
            "Drum": drum, "Voice": {"program": 110, "instrument_type": "V"}}
    meta = balance_case(tmp_path, parts, info=info)
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    doc = balance_doc(res)
    rels = [p["rel_mix_db"] for m in doc["measures"] for p in m["parts"] if p["label"] == "T3 Drum"]
    assert all(v <= -28 for v in rels), rels
    assert all(p["unpitched"] for m in doc["measures"] for p in m["parts"] if p["label"] == "T3 Drum")
    assert res["summary"]["counts"]["buried_by_part"] == {"T4 Voice": 2}, res["summary"]  # 노래는 그대로 판정
    assert doc["params"]["buried_skipped_parts"] == ["T3 Drum"]
    assert all(m["active_parts"] == 4 for m in doc["measures"])  # N 에는 드럼도 센다
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), buried_include_unpitched=True,
                              overwrite=True)
    assert res["summary"]["counts"]["buried_by_part"] == {"T3 Drum": 2, "T4 Voice": 2}, res["summary"]


@pytest.mark.parametrize("kw", [{"buried_mode": "auto"}, {"buried_margin_db": -1}, {"low_min_parts": 0},
                                {"low_min_parts": 2.5}, {"buried_include_unpitched": "true"}])
def test_balance_invalid_new_args(tmp_path, kw):
    meta = balance_case(tmp_path, {"Chords": synth.song([CEG, ACE], 120)})
    with pytest.raises(EarError) as e:
        server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), **kw)
    assert e.value.code == "BAD_ARGUMENT"


def test_balance_with_mix_wav(tmp_path):
    chords = synth.song([CEG, ACE], 120)
    quiet = synth.song([["A4"], ["G4"]], 120) * 0.05
    meta = balance_case(tmp_path, {"Chords": chords, "Quiet": quiet})
    mix = synth.write_wav(tmp_path / "mix" / "mix.wav", chords + quiet)
    res = server.tool_balance(str(meta), mix_wav=str(mix), out_dir=str(tmp_path / "out"))
    assert res["summary"]["mix"] == "mix_wav"
    assert res["summary"]["counts"]["buried_by_part"] == {"T2 Quiet": 2}


def test_balance_requires_split(case):
    with pytest.raises(EarError) as e:
        server.tool_balance(str(case["meta"]), out_dir=str(case["out"]))
    assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- compare_renders, report, audio_info

def test_compare_renders(case):
    b = synth.write_wav(case["in"] / "noE.wav", synth.song([CEG, AC], 126, tail=1.0))
    b_meta = synth.write_json(case["in"] / "noE.json", synth.meta_for(126, 2, wav=str(b)))
    res = server.tool_compare_renders(str(case["render"]), str(case["meta"]), str(b), str(b_meta),
                                      out_dir=str(case["out"]))
    bottom = res["summary"]["bottom"]
    assert bottom[0]["measure"] == 2 and "E" in bottom[0]["only_a"]
    assert Path(res["out_path"]).name == "render_vs_noE_ab.json"
    # 오프셋 없음: B 번호 = A 번호, 요약에 B 번호를 따로 쓰지 않는다
    assert bottom[0]["b_measure"] == 2 and res["summary"]["b_measure_offset"] == 0
    assert "(B " not in res["summary_text"] and res["summary"]["skipped_count"] == 0


def offset_b(case, first: int = 3):
    """B: A와 같은 내용(1마디 C-E-G, 2마디 A-C, E 빠짐)이지만 마디 번호가 first부터."""
    b = synth.write_wav(case["in"] / "late_b.wav", synth.song([CEG, AC], 126, tail=1.0))
    bm = synth.write_json(case["in"] / "late_b.json", synth.meta_for(126, 2, first=first, wav=str(b)))
    return b, bm


def test_compare_renders_measure_offset(case):
    b, bm = offset_b(case)  # B 마디 3, 4
    # 오프셋 없이: 같은 번호가 없어 거절
    r = server._run("compare_renders", lambda: server.tool_compare_renders(
        str(case["render"]), str(case["meta"]), str(b), str(bm), out_dir=str(case["out"])))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    res = server.tool_compare_renders(str(case["render"]), str(case["meta"]), str(b), str(bm),
                                      out_dir=str(case["out"]), b_measure_offset=2)
    s = res["summary"]
    assert s["measures"] == 2 and s["skipped_count"] == 0 and s["b_measure_offset"] == 2
    assert s["bottom"][0]["measure"] == 2 and s["bottom"][0]["b_measure"] == 4
    assert "E" in s["bottom"][0]["only_a"]
    assert s["bottom"][1]["score"] > 0.95  # A 1마디 ↔ B 3마디: 같은 화음
    assert "2마디(B 4마디)" in res["summary_text"] and "B 3~4마디" in res["summary_text"]
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert [(m["measure"], m["b_measure"]) for m in doc["measures"]] == [(1, 3), (2, 4)]
    assert doc["inputs"]["b_measure_offset"] == 2 and doc["b_measure_offset"] == 2
    # from/to_measure는 A 번호
    res2 = server.tool_compare_renders(str(case["render"]), str(case["meta"]), str(b), str(bm),
                                       from_measure=2, to_measure=2, out_dir=str(case["out"]), name="only2",
                                       b_measure_offset=2)
    assert res2["summary"]["measures"] == 1 and res2["summary"]["bottom"][0]["b_measure"] == 4


def test_compare_renders_unpaired_measures(case):
    b, bm = offset_b(case)  # B 마디 3, 4
    # 오프셋 3: A 1 ↔ B 4, A 2 ↔ B 5(없음) -> 건너뜀 1개
    res = server.tool_compare_renders(str(case["render"]), str(case["meta"]), str(b), str(bm),
                                      out_dir=str(case["out"]), b_measure_offset=3)
    s = res["summary"]
    assert s["measures"] == 1 and s["skipped_measures"] == [2] and s["skipped_count"] == 1
    assert "건너뛴 A 마디 1개" in res["summary_text"]
    # 짝이 하나도 없으면 BAD_ARGUMENT
    r = server._run("compare_renders", lambda: server.tool_compare_renders(
        str(case["render"]), str(case["meta"]), str(b), str(bm), out_dir=str(case["out"]), name="none",
        b_measure_offset=10))
    e = err_of(r)
    assert e["error"] == "BAD_ARGUMENT" and e["details"]["b_measure_offset"] == 10
    # 정수가 아니면 BAD_ARGUMENT
    r = server._run("compare_renders", lambda: server.tool_compare_renders(
        str(case["render"]), str(case["meta"]), str(b), str(bm), out_dir=str(case["out"]), name="bad",
        b_measure_offset=1.5))
    assert err_of(r)["error"] == "BAD_ARGUMENT"


def test_report_measure_offset_and_b_side(case):
    b, bm = offset_b(case)
    ab = server.tool_compare_renders(str(case["render"]), str(case["meta"]), str(b), str(bm),
                                     out_dir=str(case["out"]), b_measure_offset=2)
    al = do_align(case)
    ch = server.tool_chroma_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                                    out_dir=str(case["out"]))
    bal = do_align(case, render=b, meta=bm)
    bch = server.tool_chroma_compare(str(b), str(bm), str(case["orig"]), bal["out_path"], out_dir=str(case["out"]))
    rep = case["tmp"] / "rep"
    res = server.tool_report(ab_json=ab["out_path"], out_dir=str(rep))
    md = Path(res["out_path"]).read_text(encoding="utf-8")
    assert "| 2 (B 4) |" in md and "2마디(B 4마디)" in md and res["summary"]["b_measure_offset"] == 2
    # A·B 크로마를 나란히(오프셋은 ab_json에서)
    res = server.tool_report(chroma_json=ch["out_path"], b_chroma_json=bch["out_path"], ab_json=ab["out_path"],
                             out_dir=str(rep), name="ab_side")
    md = Path(res["out_path"]).read_text(encoding="utf-8")
    assert "A 유사도" in md and "B 유사도" in md and "B 빠진 음" in md
    line = [x for x in md.splitlines() if x.startswith("| 2 (B 4) |")][0]
    cells = [c.strip() for c in line.strip("|").split("|")]
    # 마디 | A 유사도 | A 빠진 | A 더한 | B 유사도 | B 빠진 | B 더한 | A/B | B−A
    assert cells[2] == "-" and "E" in cells[5].split(), cells  # A는 E가 있고 B(4마디)는 E가 빠짐
    assert "B 4마디(A 2마디)" in md
    assert res["summary"]["b_chroma_bottom"][0] == 4
    # ab_json 없이 오프셋 직접
    res = server.tool_report(chroma_json=ch["out_path"], b_chroma_json=bch["out_path"], b_measure_offset=2,
                             out_dir=str(rep), name="direct")
    assert "| 2 (B 4) |" in Path(res["out_path"]).read_text(encoding="utf-8")
    # ab_json의 값과 다르면 거절
    r = server._run("report", lambda: server.tool_report(ab_json=ab["out_path"], b_measure_offset=1,
                                                         out_dir=str(rep), name="clash"))
    assert err_of(r)["error"] == "BAD_ARGUMENT"


def test_report(case):
    al = do_align(case)
    ch = server.tool_chroma_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                                    out_dir=str(case["out"]))
    on = server.tool_onset_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                                   out_dir=str(case["out"]))
    res = server.tool_report(align_json=al["out_path"], chroma_json=ch["out_path"], onset_json=on["out_path"],
                             title="합성 시험", out_dir=str(case["tmp"] / "rep"))
    md = Path(res["out_path"]).read_text(encoding="utf-8")
    assert "유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다" in md
    assert "| 마디 |" in md and "| 2 |" in md
    assert Path(res["out_path"]).name == "render_report.md"
    # out_path 직접 지정
    op = case["tmp"] / "rep2" / "직접.md"
    res2 = server.tool_report(chroma_json=ch["out_path"], out_path=str(op))
    assert Path(res2["out_path"]) == op and op.is_file()


def test_audio_info(case):
    res = server.tool_audio_info(str(case["orig"]))
    s = res["summary"]
    assert s["sample_rate"] == synth.SR and s["channels"] == 1
    assert abs(s["duration_sec"] - (1.5 + 4.0 + 1.0)) < 0.01
    assert res["out_path"] is None


def test_audio_info_stereo_44k(tmp_path):
    y = synth.song([CEG], 120, sr=44100)
    p = synth.write_wav(tmp_path / "in" / "st.wav", np.stack([y, y * 0.5], axis=1), sr=44100)
    s = server.tool_audio_info(str(p))["summary"]
    assert s["sample_rate"] == 44100 and s["channels"] == 2 and abs(s["duration_sec"] - 2.0) < 0.01


def test_unicode_paths(tmp_path):
    d = tmp_path / "한글_日本語 폴더"
    orig = synth.write_wav(d / "원곡 愛.wav", synth.song([CEG, ACE], 120, lead=1.5, tail=1.0))
    rend = synth.write_wav(d / "렌더 v2.wav", synth.song([CEG, ACE], 126, tail=1.0))
    meta = synth.write_json(d / "렌더 v2.json", synth.meta_for(126, 2, wav=str(rend)))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "출력"))
    assert Path(res["out_path"]).name == "렌더 v2_align.json"
    assert abs(res["summary"]["offset_sec"] - 1.5) < 0.05


# ---------------------------------------------------------------- 오류 코드

def err_of(result) -> dict:
    assert result.is_error
    return json.loads(result.content[0].text)


def test_error_file_not_found(case):
    r = server._run("align", lambda: server.tool_align(str(case["render"]), str(case["meta"]),
                                                       str(case["in"] / "없는파일.flac"), out_dir=str(case["out"])))
    e = err_of(r)
    assert e["ok"] is False and e["error"] == "FILE_NOT_FOUND" and e["message"] and "details" in e


def test_error_meta_invalid(case):
    bad = synth.write_json(case["in"] / "bad.json", synth.meta_for(126, 2, schema="something/else"))
    nom = synth.meta_for(126, 2)
    del nom["measures"]
    nom_p = synth.write_json(case["in"] / "nomeasures.json", nom)
    for p in (bad, nom_p):
        r = server._run("align", lambda: server.tool_align(str(case["render"]), str(p), str(case["orig"]),
                                                           out_dir=str(case["out"]), name=p.stem))
        assert err_of(r)["error"] == "META_INVALID"


def test_error_exists_and_overwrite(case):
    first = do_align(case)
    target = Path(first["out_path"])
    before = target.read_bytes()
    r = server._run("align", lambda: do_align(case))
    e = err_of(r)
    assert e["error"] == "EXISTS" and Path(e["details"]["path"]) == target
    assert target.read_bytes() == before  # 거절되면 파일은 그대로
    res = do_align(case, overwrite=True)
    assert Path(res["out_path"]) == target and target.stat().st_size > 0


def test_error_protected_path(case, tmp_path):
    # 입력 파일 폴더 안
    r = server._run("align", lambda: server.tool_align(str(case["render"]), str(case["meta"]), str(case["orig"]),
                                                       out_dir=str(case["in"] / "sub")))
    assert err_of(r)["error"] == "PROTECTED_PATH"
    # 프로젝트 파일의 보호 폴더: 쓰기 전에 거절하므로 폴더가 생기지 않는다
    guarded = tmp_path / "project_guarded"
    old_project = io.CONFIG.project
    io.CONFIG.project = io.Project(tmp_path / "p", None, None, [guarded], [])
    app = guarded / "분석_시험"
    r = server._run("align", lambda: server.tool_align(str(case["render"]), str(case["meta"]), str(case["orig"]),
                                                       out_dir=str(app)))
    assert err_of(r)["error"] == "PROTECTED_PATH"
    assert not app.exists()
    # 실행 인자 --protect 로 더한 폴더
    extra = tmp_path / "guarded"
    old = io.CONFIG.extra_protect
    io.CONFIG.extra_protect = [extra]
    try:
        r = server._run("audio_info", lambda: server.tool_audio_info(str(case["orig"]), save=True,
                                                                     out_dir=str(extra / "x")))
        assert err_of(r)["error"] == "PROTECTED_PATH"
    finally:
        io.CONFIG.extra_protect = old
        io.CONFIG.project = old_project


def test_error_invalid_args(case):
    r = server._run("align", lambda: server.tool_align("relative/path.wav", str(case["meta"]), str(case["orig"]),
                                                       out_dir=str(case["out"])))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    r = server._run("align", lambda: do_align(case, search_sec=[5, 2]))
    assert err_of(r)["error"] == "BAD_ARGUMENT"


@pytest.mark.parametrize("bad", ["o\x01x", "o\x00x", "o\tx", "o?x", "o|x", "o<x", "o:x"])
def test_error_bad_path_syntax_out_dir(case, bad):
    od = str(case["tmp"] / bad)
    r = server._run("audio_info", lambda: server.tool_audio_info(str(case["orig"]), save=True, out_dir=od))
    e = err_of(r)
    assert e["error"] == "BAD_ARGUMENT", e
    r = server._run("align", lambda: server.tool_align(str(case["render"]), str(case["meta"]), str(case["orig"]),
                                                       out_dir=od))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    assert not (case["tmp"] / "out").exists()  # 분석 전에 거절(출력 없음)


def test_error_bad_path_syntax_other(case):
    # 입력 파일 경로
    r = server._run("audio_info", lambda: server.tool_audio_info(str(case["in"] / "a\x01.wav")))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    # report out_path
    al = do_align(case)
    r = server._run("report", lambda: server.tool_report(align_json=al["out_path"],
                                                         out_path=str(case["tmp"] / "r\x02" / "x.md")))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    r = server._run("report", lambda: server.tool_report(align_json=al["out_path"],
                                                         out_path=str(case["tmp"] / "x|y.md")))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    # name에 제어문자
    r = server._run("align", lambda: do_align(case, name="a\x03b"))
    assert err_of(r)["error"] == "BAD_ARGUMENT"
    # 쓰기 단계에서 난 경로 문법 오류(WinError 123 / NUL)도 BAD_ARGUMENT
    with pytest.raises(EarError) as ei:
        io.write_output(case["tmp"] / "w\x00x" / "a.json", "{}", False)
    assert ei.value.code == "BAD_ARGUMENT"

    def raise_winerror():
        raise OSError(22, "구문이 잘못되었습니다", "X:\\bad", 123)

    assert err_of(server._run("x", raise_winerror))["error"] == "BAD_ARGUMENT"
    assert err_of(server._run("x", lambda: 1 / 0))["error"] == "INTERNAL"


def test_error_audio_read_failed(case):
    junk = case["in"] / "junk.wav"
    junk.write_bytes(b"RIFF....not a wav at all" * 10)
    r = server._run("audio_info", lambda: server.tool_audio_info(str(junk)))
    assert err_of(r)["error"] == "AUDIO_READ_FAILED"


def test_error_align_failed(tmp_path):
    d = tmp_path / "in"
    tiny = synth.write_wav(d / "tiny.wav", synth.silence(0.05))
    orig = synth.write_wav(d / "o.wav", synth.silence(0.05))
    meta = synth.write_json(d / "tiny.json", synth.meta_for(120, 1))
    r = server._run("align", lambda: server.tool_align(str(tiny), str(meta), str(orig), out_dir=str(tmp_path / "o")))
    assert err_of(r)["error"] == "ALIGN_FAILED"


def test_inputs_unchanged(case):
    paths = [case["orig"], case["render"], case["meta"]]
    before = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in paths}
    al = do_align(case)
    server.tool_chroma_compare(str(case["render"]), str(case["meta"]), str(case["orig"]), al["out_path"],
                               out_dir=str(case["out"]))
    for p in paths:
        assert (p.read_bytes(), p.stat().st_mtime_ns) == before[p]
    assert sorted(x.name for x in case["in"].iterdir()) == ["orig.wav", "render.json", "render.wav"]
