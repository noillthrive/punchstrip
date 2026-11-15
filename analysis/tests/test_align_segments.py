"""align 비율 쏠림(search_sec이 렌더보다 짧을 때)·ratio_at_bound, auto 판정의 튐 무시, 구간별 정렬·건너뜀 찾기."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

import synth
from mabiear import server
from mabiear.io import EarError

CH8 = [["C4", "E4", "G4"], ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["G4", "B3", "D4"],
       ["E4", "G4", "B3"], ["D4", "F3", "A3"], ["C4", "E4", "A4"], ["G2", "B3", "D4"]]
TRUE_RATIO = 120 / 126  # 원곡 126BPM, 렌더 120BPM → 원곡 시간 ÷ 렌더 시간


@pytest.fixture
def long_orig(tmp_path: Path):
    """렌더: 8마디 120BPM(16초 + 꼬리 1초). 원곡: 1.5초 무음 + 같은 8마디를 두 번 126BPM."""
    d = tmp_path / "in"
    rend = synth.write_wav(d / "r8.wav", synth.song(CH8, 120, tail=1.0))
    meta = synth.write_json(d / "r8.json", synth.meta_for(120, 8, wav=str(rend)))
    y = np.concatenate([synth.silence(1.5)] + [synth.chord_beats(c, 4, 60 / 126) for c in CH8 + CH8]
                       + [synth.silence(1.0)])
    orig = synth.write_wav(d / "o16.wav", y)
    return d, rend, meta, orig


@pytest.mark.parametrize("window", [[0.0, 10.0], [0.0, 6.0], [3.0, 12.0]])
def test_align_short_search_window_ratio_not_at_bound(tmp_path, long_orig, window):
    """search_sec이 렌더(약 15초)보다 짧아도 비율이 범위 끝(0.9)으로 쏠리지 않는다(전: 0.94·0.90·0.902)."""
    d, rend, meta, orig = long_orig
    res = server.tool_align(str(rend), str(meta), str(orig), search_sec=window, out_dir=str(tmp_path / "out"),
                            name="w%d_%d" % tuple(int(v) for v in window))
    s = res["summary"]
    assert abs(s["tempo_ratio"] - TRUE_RATIO) < 0.004, s
    assert abs(s["offset_sec"] - 1.5) < 0.05, s
    assert s["ratio_at_bound"] is False
    assert any("search_sec 구간" in w and "렌더의 일부만" in w for w in s["warnings"]), s
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert doc["ratio_at_bound"] is False and doc["global_search"]["window_shorter_than_render"] is True


def test_align_full_window_unchanged(tmp_path, long_orig):
    """구간이 렌더보다 길면 예전 점수 그대로(창이 짧다는 경고 없음)."""
    d, rend, meta, orig = long_orig
    res = server.tool_align(str(rend), str(meta), str(orig), search_sec=[0.0, 20.0], out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert abs(s["tempo_ratio"] - TRUE_RATIO) < 0.004 and s["ratio_at_bound"] is False, s
    assert not any("search_sec 구간" in w for w in s["warnings"])
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    assert doc["global_search"]["window_shorter_than_render"] is False


def test_align_ratio_at_bound_flag(tmp_path, long_orig):
    """실제 비율 0.952인데 범위를 [1.0, 1.1]로 막으면 아래 끝 1.0에 걸려 ratio_at_bound: true와 경고."""
    d, rend, meta, orig = long_orig
    res = server.tool_align(str(rend), str(meta), str(orig), search_sec=[0.0, 20.0], tempo_ratio_range=[1.0, 1.1],
                            out_dir=str(tmp_path / "out"), name="bound")
    s = res["summary"]
    assert abs(s["tempo_ratio"] - 1.0) < 0.003, s
    assert s["ratio_at_bound"] is True
    w = [x for x in s["warnings"] if "끝에 걸렸다" in x]
    assert w and "아래 끝 1.0" in w[0] and "ratio_at_bound" in w[0], s
    assert "경고:" in res["summary_text"]
    # 자유 DTW에는 범위가 없어 false
    free = server.tool_align(str(rend), str(meta), str(orig), search_sec=[0.0, 20.0], tempo_ratio_range=None,
                             out_dir=str(tmp_path / "out"), name="free")
    assert free["summary"]["ratio_at_bound"] is False


# ---------------------------------------------------------------- auto 판정: 짧은 튐은 직선 유지

def test_align_auto_ignores_short_spike(tmp_path):
    """렌더 16마디(8마디째 무음), 원곡은 같은 곡을 126BPM 고정으로. 무음 마디에서 DTW 경로가 한 번 튀어도(최대 > 1박)
    앞뒤가 직선으로 돌아오는 짧은 튐이면 직선(전에는 렌더 전체가 경로)."""
    d = tmp_path / "in"
    ch16 = CH8 + CH8[::-1]
    parts = [synth.chord_beats(c, 4, 0.5) if i != 7 else synth.silence(2.0) for i, c in enumerate(ch16)]
    rend = synth.write_wav(d / "r16.wav", np.concatenate(parts + [synth.silence(1.0)]))
    meta = synth.write_json(d / "r16.json", synth.meta_for(120, 16, wav=str(rend)))
    orig = synth.write_wav(d / "o16.wav", np.concatenate([synth.silence(1.5)] + [synth.chord_beats(c, 4, 60 / 126)
                                                                                 for c in ch16] + [synth.silence(1.0)]))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert s["path_deviation_beats"]["max"] > 1.0, s  # 튐은 있다
    assert s["mapping_used"] == "linear", s
    assert "짧은 튐뿐이고" in s["mapping_reason"], s
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    runs = doc["mapping_decision"]["over_max"]["runs"]
    assert runs and all(x["spike"] for x in runs), runs
    assert doc["mapping_decision"]["over_max"]["p90_beats"] <= 0.4
    true = 1.5 + np.arange(16) * 4 * 60 / 126
    got = np.array([m["orig_start_sec"] for m in doc["measures"]])
    assert np.max(np.abs(got - true)) < 0.05


def test_align_mapping_reason_explicit(tmp_path, long_orig):
    d, rend, meta, orig = long_orig
    res = server.tool_align(str(rend), str(meta), str(orig), search_sec=[0.0, 20.0], mapping="path",
                            out_dir=str(tmp_path / "out"))
    assert res["summary"]["mapping_used"] == "path" and res["summary"]["mapping_reason"] == "mapping='path' 지정"


# ---------------------------------------------------------------- 구간별 정렬(segments)과 건너뜀 찾기

CH12 = CH8 + [["A4", "C4", "E4"], ["B3", "D4", "G4"], ["F3", "C4", "A4"], ["E2", "B3", "E4"]]
EXTRA = [["C3", "G2", "E4"], ["A1", "E2", "C4"], ["D4", "A4", "F3"], ["B3", "E4", "G2"]]
MB = 4 * 60 / 126  # 원곡 한 마디(초)


@pytest.fixture
def cut_case(tmp_path: Path):
    """렌더 12마디 120BPM. 원곡: 1초 무음 + 렌더 1~4마디 + 원곡에만 있는 4마디 + 렌더 5~12마디(126BPM).
    렌더 5마디부터 원곡이 4마디(약 7.6초) 늦다(구간을 잘라낸 편곡)."""
    d = tmp_path / "in"
    rend = synth.write_wav(d / "r12.wav", synth.song(CH12, 120, tail=1.0))
    meta = synth.write_json(d / "r12.json", synth.meta_for(120, 12, wav=str(rend)))
    seq = CH12[:4] + EXTRA + CH12[4:]
    orig = synth.write_wav(d / "o_cut.wav", np.concatenate([synth.silence(1.0)] + [synth.chord_beats(c, 4, 60 / 126)
                                                                                   for c in seq] + [synth.silence(1.0)]))
    return d, rend, meta, orig


def test_align_reports_jump_candidates_without_segments(tmp_path, cut_case):
    d, rend, meta, orig = cut_case
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert s["segments"] is None and s["orig_gaps"] == []
    c = s["segment_candidates"]
    assert len(c) == 1 and c[0]["measures"] == [1, 4], c
    assert abs(c[0]["shift_sec"] + 4 * MB) < 0.1 and abs(c[0]["orig_from_sec"] - 1.0) < 0.1, c
    assert any("segments로 구간별 정렬" in w for w in s["warnings"]), s["warnings"]


def test_align_segments_piecewise_and_compare(tmp_path, cut_case):
    d, rend, meta, orig = cut_case
    out = tmp_path / "out"
    segs = [{"render_from_sec": 0.0, "render_to_sec": 8.0}, {"render_from_sec": 8.0, "render_to_sec": 24.0}]
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(out), segments=segs, name="seg")
    s = res["summary"]
    assert s["mapping_used"] == "segments" and "segments 2개" in s["mapping_reason"], s
    s0, s1 = s["segments"]
    assert abs(s0["orig_from_sec"] - 1.0) < 0.05 and s0["ratio_fixed"] is True and s0["confidence"] == "높음", s0
    assert abs(s1["orig_from_sec"] - (1.0 + 8 * MB)) < 0.05 and s1["ratio_fixed"] is False, s1
    assert abs(s1["tempo_ratio"] - TRUE_RATIO) < 0.004
    true = 1.0 + np.concatenate([np.arange(4), np.arange(8, 16)]) * MB
    got = np.array([m["orig_start_sec"] for m in s["measures"]])
    assert np.max(np.abs(got - true)) < 0.05, got - true
    gaps = s["orig_gaps"]
    assert len(gaps) == 1 and abs(gaps[0][0] - (1.0 + 4 * MB)) < 0.1 and abs(gaps[0][1] - (1.0 + 8 * MB)) < 0.1, gaps
    assert "구간 0.0~8.0초" in res["summary_text"]
    # chroma_compare·onset_compare가 구간별 결과를 그대로 쓴다: 전역 직선만 쓴 결과보다 1~4마디가 좋아진다
    glob = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(out), name="glob")
    cs = server.tool_chroma_compare(str(rend), str(meta), str(orig), res["out_path"], out_dir=str(out), name="seg")
    cg = server.tool_chroma_compare(str(rend), str(meta), str(orig), glob["out_path"], out_dir=str(out), name="glob")

    def first4(r):
        rows = json.loads(Path(r["out_path"]).read_text(encoding="utf-8"))["measures"]
        return float(np.mean([x["score"] for x in rows if x["measure"] <= 4]))
    assert first4(cs) > 0.95 and first4(cs) > first4(cg) + 0.05, (first4(cs), first4(cg))
    on = server.tool_onset_compare(str(rend), str(meta), str(orig), res["out_path"], out_dir=str(out), name="seg")
    rows = json.loads(Path(on["out_path"]).read_text(encoding="utf-8"))["measures"]
    f = [x["f"] for x in rows if x.get("f") is not None]
    assert f and float(np.mean(f)) > 0.8, rows


@pytest.mark.parametrize("bad", [[], [{"render_from_sec": 0}], [{"render_from_sec": 0, "render_to_sec": 1}],
                                 [{"render_from_sec": 0, "render_to_sec": 8, "x": 1}],
                                 [{"render_from_sec": 5, "render_to_sec": 9}, {"render_from_sec": 6, "render_to_sec": 12}],
                                 [{"render_from_sec": "0", "render_to_sec": 8}]])
def test_align_segments_invalid(tmp_path, cut_case, bad):
    d, rend, meta, orig = cut_case
    with pytest.raises(EarError) as e:
        server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"), segments=bad, name="bad")
    assert e.value.code == "BAD_ARGUMENT"
    with pytest.raises(EarError):
        server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"), tempo_ratio_range=None,
                          segments=[{"render_from_sec": 0, "render_to_sec": 8}], name="bad2")


# ---------------------------------------------------------------- 2차: 검증에서 나온 결함

def test_align_short_window_no_false_candidates(tmp_path, long_orig):
    """search_sec이 렌더보다 짧아도 원곡 밖·구간 밖을 가리키는 건너뜀 후보를 내지 않는다, 경고 숫자는 옮긴 범위 기준."""
    d, rend, meta, orig = long_orig
    for w in ([0.0, 6.0], [3.0, 12.0]):
        s = server.tool_align(str(rend), str(meta), str(orig), search_sec=w, out_dir=str(tmp_path / "out"),
                              name="d2_%d" % int(w[0]))["summary"]
        assert s["segment_candidates"] == [], s["segment_candidates"]
        msg = [x for x in s["warnings"] if "search_sec 구간" in x]
        assert len(msg) == 1 and "원곡 1.5~16.7초" in msg[0] and f"{w[0]:.1f}~{w[1]:.1f}초" in msg[0], msg
    # 구간(17.5초)이 옮긴 렌더 마디 범위(1.5~16.7초)를 덮으면 경고 없음
    s = server.tool_align(str(rend), str(meta), str(orig), search_sec=[0.0, 17.5], out_dir=str(tmp_path / "out"),
                          name="d3")["summary"]
    assert not any("search_sec 구간" in x for x in s["warnings"]), s["warnings"]


def test_align_auto_tempo_jump_picks_path(tmp_path):
    """원곡이 5마디부터 104 → 136BPM으로 바뀜: 편차가 곳곳에 퍼져(90번째 백분위 > 0.4박) 경로."""
    from test_align_auto_richness import song_bpms
    d = tmp_path / "in"
    rend = synth.write_wav(d / "r8.wav", synth.song(CH8, 120, tail=1.0))
    meta = synth.write_json(d / "r8.json", synth.meta_for(120, 8, wav=str(rend)))
    orig = synth.write_wav(d / "o_acc.wav", song_bpms(CH8, [104] * 4 + [136] * 4))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert s["mapping_used"] == "path", s
    assert "퍼져 있어" in s["mapping_reason"] or "중앙값" in s["mapping_reason"], s["mapping_reason"]
    assert "렌더 끝까지" not in s["mapping_reason"]
