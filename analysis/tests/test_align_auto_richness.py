"""align mapping='auto', richness_compare, balance K-가중."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

import synth
from mabiear import server
from mabiear.io import EarError
from test_stdio import _serve

CH8 = [["C4", "E4", "G4"], ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["G4", "B3", "D4"],
       ["E4", "G4", "B3"], ["D4", "F3", "A3"], ["C4", "E4", "A4"], ["G2", "B3", "D4"]]
CEG = ["C4", "E4", "G4"]
ACE = ["A3", "C4", "E4"]


def song_bpms(chords, bpms, lead=1.5, tail=1.0):
    """마디마다 다른 템포로 화음을 4박씩."""
    parts = [synth.silence(lead)] + [synth.chord_beats(c, 4, 60.0 / b) for c, b in zip(chords, bpms)]
    return np.concatenate(parts + [synth.silence(tail)])


@pytest.fixture
def render8(tmp_path: Path):
    d = tmp_path / "in"
    rend = synth.write_wav(d / "r8.wav", synth.song(CH8, 120, tail=1.0))
    meta = synth.write_json(d / "r8.json", synth.meta_for(120, 8, wav=str(rend)))
    return d, rend, meta


def _doc(res) -> dict:
    return json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))


# ---------------------------------------------------------------- ① align auto

def test_align_auto_fixed_tempo_picks_linear(tmp_path, render8):
    d, rend, meta = render8
    orig = synth.write_wav(d / "o_fixed.wav", song_bpms(CH8, [126] * 8))  # 템포 고정(렌더보다 5% 빠름)
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert s["mapping_requested"] == "auto" and s["mapping_used"] == "linear", s
    assert s["path_deviation_beats"]["median"] <= 0.5 and s["path_deviation_beats"]["max"] <= 1.0
    assert "직선 정렬 선택(경로 편차 중앙" in res["summary_text"]
    doc = _doc(res)
    assert doc["mapping_requested"] == "auto" and doc["mapping_used"] == "linear"
    assert set(doc["path_deviation_sec"]) >= {"median", "max"} and doc["beat_sec"] == pytest.approx(0.5 * 120 / 126, abs=0.01)
    md = doc["mapping_decision"]
    assert md["thresholds_beats"] == {"median": 0.5, "max": 1.0} and md["boundaries_used"] >= 3
    # 직선을 쓰면 마디 시작 = offset + ratio x 렌더 시각, 저장 경로도 직선 위
    a, b = doc["offset_sec"], doc["tempo_ratio"]
    for m in doc["measures"]:
        assert m["orig_start_sec"] == pytest.approx(a + b * m["render_start_sec"], abs=2e-3)
    true = 1.5 + np.arange(8) * 4 * 60 / 126
    assert np.max(np.abs(np.array([m["orig_start_sec"] for m in doc["measures"]]) - true)) < 0.03


def test_align_auto_tempo_change_picks_path(tmp_path, render8):
    """원곡이 5마디부터 점점 느려진다(120 -> 90BPM): 직선으로는 경계가 크게 어긋나므로 경로를 고른다."""
    d, rend, meta = render8
    bpms = [120, 120, 120, 120, 112, 104, 96, 90]
    true_start5 = 1.5 + 4 * 4 * 0.5
    orig = synth.write_wav(d / "o_rit.wav", song_bpms(CH8, bpms))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"))
    s = res["summary"]
    assert s["mapping_used"] == "path", s
    assert s["path_deviation_beats"]["max"] > 1.0
    at = s["path_deviation_sec"]["max_deviation_at_sec"]  # 원곡 초: 느려지는 뒤쪽(5마디 이후)
    assert at > true_start5, s["path_deviation_sec"]
    assert "경로 정렬 선택" in res["summary_text"]
    true = 1.5 + np.concatenate([[0], np.cumsum([4 * 60 / x for x in bpms])])[:8]
    got = np.array([m["orig_start_sec"] for m in s["measures"]])
    assert np.max(np.abs(got - true)) < 0.06, (got - true)
    # 같은 파일을 linear 로 지정하면 그대로 직선(경계가 더 많이 틀림), path 로 지정하면 auto 와 같은 결과
    lin = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"), mapping="linear",
                            name="lin")
    assert lin["summary"]["mapping_requested"] == "linear" and lin["summary"]["mapping_used"] == "linear"
    got_l = np.array([m["orig_start_sec"] for m in lin["summary"]["measures"]])
    assert np.max(np.abs(got_l - true)) > 0.2
    pth = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"), mapping="path",
                            name="pth")
    assert pth["summary"]["mapping_used"] == "path"
    assert [m["orig_start_sec"] for m in pth["summary"]["measures"]] == list(got)


def test_align_auto_thresholds_and_invalid(tmp_path, render8):
    d, rend, meta = render8
    orig = synth.write_wav(d / "o_rit2.wav", song_bpms(CH8, [120, 120, 120, 120, 112, 104, 96, 90]))
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"),
                            auto_median_beats=8.0, auto_max_beats=8.0)  # 기준을 아주 느슨하게 -> 직선
    assert res["summary"]["mapping_used"] == "linear"
    for kw in ({"mapping": "dtw"}, {"auto_median_beats": -1}, {"auto_max_beats": "1"}):
        with pytest.raises(EarError) as e:
            server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path / "out"), name="bad", **kw)
        assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- ② richness_compare

@pytest.fixture
def rich_case(tmp_path: Path):
    """원곡: 화음 + 저음(E2/A1), 박마다 다시 침. 렌더 full: 같은 화음(저음 없음), 렌더 sparse: 1마디는 같고
    2마디는 2박에 한 번만 침(음 시작 절반)."""
    d = tmp_path / "in"
    orig = synth.song([CEG, ACE], 120, lead=1.5, tail=1.0)
    bass = synth.song([["E2"], ["A1"]], 120, lead=1.5, tail=1.0)
    o = synth.write_wav(d / "orig.wav", orig + bass)
    full = synth.write_wav(d / "full.wav", synth.song([CEG, ACE], 120, tail=1.0))
    sparse_y = np.concatenate([synth.chord_beats(CEG, 4, 0.5), synth.chord_beats(ACE, 2, 1.0), synth.silence(1.0)])
    sparse = synth.write_wav(d / "sparse.wav", sparse_y)
    meta = synth.write_json(d / "r.json", synth.meta_for(120, 2))
    out = tmp_path / "out"
    al = server.tool_align(str(full), str(meta), str(o), out_dir=str(out), mapping="linear")
    return {"orig": o, "full": full, "sparse": sparse, "meta": meta, "out": out, "align": al["out_path"],
            "tmp": tmp_path}


def test_richness_fields_and_ratio(rich_case):
    c = rich_case
    res = server.tool_richness_compare(str(c["full"]), str(c["meta"]), str(c["orig"]), c["align"],
                                       out_dir=str(c["out"]))
    assert Path(res["out_path"]).name == "full_richness.json"
    doc = _doc(res)
    assert doc["schema"] == "mabiear.richness/1" and doc["tool"] == "richness_compare"
    assert len(doc["measures"]) == 2
    for m in doc["measures"]:
        assert {"render_onsets", "orig_onsets", "onset_ratio", "band_db_diff", "band_db_rel", "render_band_db",
                "orig_band_db"} <= set(m)
        assert set(m["band_db_diff"]) == {"low", "mid", "high"}
        assert m["orig_onsets"] > 0 and m["onset_ratio"] == pytest.approx(m["render_onsets"] / m["orig_onsets"],
                                                                        abs=1e-3)
        for k in ("low", "mid", "high"):
            assert m["band_db_diff"][k] == pytest.approx(m["render_band_db"][k] - m["orig_band_db"][k], abs=0.02)
        # 원곡에만 저음이 있다 -> 저역 모양 차가 중역보다 작다(2마디는 A3 220Hz 도 저역이라 차이가 작다)
        assert m["band_db_rel"]["low"] < m["band_db_rel"]["mid"] - (6 if m["measure"] == 1 else 1.5), m
    sm = doc["summary"]
    assert sm["measures"] == 2 and sm["mean_onset_ratio"] == pytest.approx(
        np.mean([m["onset_ratio"] for m in doc["measures"]]), abs=1e-3)
    assert doc["params"]["bands_hz"]["low"] == [0, 250.0] and doc["params"]["bands_hz"]["mid"] == [250.0, 2000.0]
    assert "음 시작 수 렌더/원곡" in res["summary_text"] and "가장 빈 마디" in res["summary_text"]


def test_richness_sparse_measure_is_emptiest(rich_case):
    c = rich_case
    full = _doc(server.tool_richness_compare(str(c["full"]), str(c["meta"]), str(c["orig"]), c["align"],
                                             out_dir=str(c["out"])))
    res = server.tool_richness_compare(str(c["sparse"]), str(c["meta"]), str(c["orig"]), c["align"],
                                       out_dir=str(c["out"]), top_n=1, low_hz=200.0, high_hz=3000.0)
    sp = _doc(res)
    m2f = [m for m in full["measures"] if m["measure"] == 2][0]
    m2s = [m for m in sp["measures"] if m["measure"] == 2][0]
    assert m2s["render_onsets"] < m2f["render_onsets"] and m2s["onset_ratio"] < m2f["onset_ratio"]
    assert [x["measure"] for x in sp["emptiest"]] == [2]
    assert sp["params"]["bands_hz"]["mid"] == [200.0, 3000.0]
    # report 에 richness 절과 열
    rep = server.tool_report(richness_json=res["out_path"], align_json=c["align"], out_dir=str(c["tmp"] / "rep"))
    md = Path(rep["out_path"]).read_text(encoding="utf-8")
    assert "\n## 풍성함\n" in md and "음 시작 렌더/원곡" in md and "유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다" in md
    sec = md.split("\n## 풍성함\n", 1)[1].split("\n## ", 1)[0]
    assert "가장 빈 마디" in sec and "1. 2마디" in sec
    assert "풍성함" not in md.split("## 하위 마디", 1)[1].split("\n## ", 1)[0]  # 하위 마디 절 안에는 없다
    assert "마디·박 경계 옮기기: 직선 정렬(mapping='linear' 지정)" in md
    assert rep["summary"]["richness_emptiest"] == [2]
    for kw in ({"low_hz": 3000.0, "high_hz": 2000.0}, {"top_n": 0}, {"low_hz": "250"}):
        with pytest.raises(EarError) as e:
            server.tool_richness_compare(str(c["full"]), str(c["meta"]), str(c["orig"]), c["align"],
                                         out_dir=str(c["out"]), name="bad", **kw)
        assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- ③ K-가중

def test_k_weighting_matches_bs1770_at_48k():
    from mabiear.balance import k_weighting_sos
    ref = np.array([[1.53512485958697, -2.69169618940638, 1.19839281085285, 1, -1.69065929318241, 0.73248077421585],
                    [1, -2, 1, 1, -1.99004745483398, 0.99007225036621]])
    assert np.max(np.abs(k_weighting_sos(48000) - ref)) < 1e-9


@pytest.mark.parametrize("sr", [22050, 44100, 48000])
def test_k_weighting_100hz_quieter_than_1khz(sr):
    from mabiear.balance import k_db, k_weight
    t = np.arange(int(3 * sr)) / sr
    lo = 0.25 * np.sin(2 * np.pi * 100 * t)
    hi = 0.25 * np.sin(2 * np.pi * 1000 * t)
    assert np.sqrt(np.mean(lo ** 2)) == pytest.approx(np.sqrt(np.mean(hi ** 2)), rel=1e-3)  # 같은 RMS
    k_lo = k_db(k_weight(lo, sr), sr, 1.0, 3.0)
    k_hi = k_db(k_weight(hi, sr), sr, 1.0, 3.0)
    assert k_lo < k_hi - 1.0, (k_lo, k_hi)
    rms_db = 20 * np.log10(0.25 / np.sqrt(2))
    assert k_hi == pytest.approx(rms_db, abs=0.1)  # 1kHz 에서는 RMS dBFS 와 거의 같다
    # 100Hz: RLB 고역통과 약 -1.1dB, 그리고 -0.691 상수 -> RMS 보다 약 1.8dB 작다
    assert k_lo == pytest.approx(rms_db - 1.8, abs=0.2)


def _two_part_meta(tmp_path: Path):
    """저음 파트(55~98Hz)와 고음 파트(440Hz 근처)를 RMS 가 같게."""
    d = tmp_path / "in"
    bass = synth.song([["A1"], ["G2"]], 120)
    lead = synth.song([["A4"], ["G4"]], 120)
    bass = bass * (np.sqrt(np.mean(lead.astype(np.float64) ** 2)) / np.sqrt(np.mean(bass.astype(np.float64) ** 2)))
    files = []
    for i, (nm, y) in enumerate((("Bass", bass), ("Lead", lead)), start=1):
        p = synth.write_wav(d / f"p{i}.wav", y.astype(np.float32))
        files.append({"path": str(p), "tracks": [i], "parts": "all"})
    meta = synth.meta_for(120, 2, files=files, split="track")
    meta["tracks"] = [{"track": 1, "name": "Bass"}, {"track": 2, "name": "Lead"}]
    return synth.write_json(d / "split.json", meta)


def test_balance_k_db_fields_and_weighting(tmp_path):
    meta = _two_part_meta(tmp_path)
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    doc = _doc(res)
    assert doc["weighting_used"] == "k" and res["summary"]["weighting_used"] == "k"
    assert doc["params"]["weighting"] == "k"
    for m in doc["measures"]:
        assert "mix_k_db" in m
        p = {x["label"]: x for x in m["parts"]}
        for x in p.values():
            assert {"rms_dbfs", "k_db", "rel_mix_db", "rel_mix_k_db"} <= set(x)
        assert abs(p["T1 Bass"]["rms_dbfs"] - p["T2 Lead"]["rms_dbfs"]) < 0.5  # RMS 는 같고
        assert p["T1 Bass"]["k_db"] < p["T2 Lead"]["k_db"] - 1.0  # K-가중은 저음을 작게
    assert "K-가중" in res["summary_text"]
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), weighting="rms", overwrite=True)
    assert _doc(res)["weighting_used"] == "rms" and "RMS로 비교" in res["summary_text"]
    with pytest.raises(EarError) as e:
        server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), weighting="a", overwrite=True)
    assert e.value.code == "BAD_ARGUMENT"


def test_balance_buried_uses_chosen_weighting(tmp_path):
    """저음 파트가 RMS 로는 기준 바로 위, K-가중으로는 기준 아래: weighting 에 따라 묻힘 판정이 갈린다."""
    d = tmp_path / "in"
    chords = synth.song([CEG, ACE], 120)
    bass = synth.song([["A1"], ["A1"]], 120)
    # 2파트 relative 기준 = -(10log10(2) + 12) = -15.01dB. 저음을 RMS 로 믹스보다 약 -14.5dB 가 되게 맞춘다.
    def rms(y):
        return float(np.sqrt(np.mean(np.asarray(y, dtype=np.float64) ** 2)))
    g = 1.0
    for _ in range(30):
        mix = chords + bass * g
        rel = 20 * np.log10(rms(bass * g) / rms(mix))
        g *= 10 ** ((-14.5 - rel) / 20)
    files = []
    for i, y in enumerate((chords, (bass * g).astype(np.float32)), start=1):
        p = synth.write_wav(d / f"q{i}.wav", y)
        files.append({"path": str(p), "tracks": [i], "parts": "all"})
    meta = synth.meta_for(120, 2, files=files, split="track")
    meta["tracks"] = [{"track": 1, "name": "Chords"}, {"track": 2, "name": "Kick"}]
    mp = synth.write_json(d / "split.json", meta)
    rk = server.tool_balance(str(mp), out_dir=str(tmp_path / "out"))
    rr = server.tool_balance(str(mp), out_dir=str(tmp_path / "out"), weighting="rms", overwrite=True)
    assert rr["summary"]["counts"]["buried"] == 0, rr["summary"]
    assert rk["summary"]["counts"]["buried_by_part"] == {"T2 Kick": 2}, rk["summary"]
    w = rk["summary"]["warnings"][0]
    assert w["weighting"] == "k" and w["rel_mix_db_used"] < w["threshold_db"] and "K-가중" in w["text"]
    assert w["rel_mix_db_used"] == w["rel_mix_k_db"] and w["rel_mix_db"] > w["threshold_db"]  # RMS 로는 기준 위
    rw = rr["summary"]["warnings"]
    assert not [x for x in rw if x["type"] == "buried"]


# ---------------------------------------------------------------- ④ stdio

def test_stdio_richness_compare(tmp_path):
    d = tmp_path / "in"
    orig = synth.write_wav(d / "orig.wav", synth.song([CEG, ACE], 120, lead=1.5, tail=1.0))
    rend = synth.write_wav(d / "render.wav", synth.song([CEG, ACE], 126, tail=1.0))
    meta = synth.write_json(d / "render.json", synth.meta_for(126, 2, wav=str(rend)))
    out = tmp_path / "out"
    base = {"render_wav": str(rend), "render_meta": str(meta), "original_audio": str(orig)}
    calls = [{"name": "align", "arguments": dict(base)},
             {"name": "richness_compare", "arguments": {**base, "align_json": str(out / "render_align.json"),
                                                        "top_n": 1}},
             {"name": "richness_compare", "arguments": {**base, "align_json": str(out / "render_align.json"),
                                                        "low_hz": "250", "name": "bad"}},
             {"name": "report", "arguments": {"richness_json": str(out / "render_richness.json")}}]
    res = _serve(tmp_path, calls)
    is_err, body = res[1]
    assert is_err is False and body["summary"]["mapping_used"] == "linear", body
    is_err, body = res[2]
    assert is_err is False and body["ok"] is True, body
    assert body["summary"]["measures"] == 2 and len(body["summary"]["emptiest"]) == 1
    assert Path(body["out_path"]).name == "render_richness.json"
    is_err, body = res[3]
    assert is_err is True and body["error"] == "BAD_ARGUMENT"
    is_err, body = res[4]
    assert is_err is False and "음 시작 수 렌더/원곡" in body["summary_text"], body


# ---------------------------------------------------------------- scripts/pipe_check.py 인자 순서

def _pipe_check():
    import importlib.util
    p = Path(__file__).resolve().parents[1] / "scripts" / "pipe_check.py"
    spec = importlib.util.spec_from_file_location("pipe_check_for_test", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.parametrize("argv", [
    ["run-ear.cmd", "--call", "audio_info", "@a.json", "--out", "o.json", "--", "--out-dir", "X", "--log", "Y"],
    ["--call", "audio_info", "@a.json", "--out", "o.json", "--", "run-ear.cmd", "--out-dir", "X", "--log", "Y"],
    ["--call", "audio_info", "@a.json", "run-ear.cmd", "--out", "o.json", "--", "--out-dir", "X", "--log", "Y"],
])
def test_pipe_check_argument_orders(argv):
    a = _pipe_check().parse_cli(argv)
    assert a.launcher == "run-ear.cmd" and a.call == [["audio_info", "@a.json"]] and a.out == "o.json"
    assert a.server_args == ["--out-dir", "X", "--log", "Y"]


def test_pipe_check_without_server_args():
    a = _pipe_check().parse_cli(["run-ear.cmd", "--call", "align", "@b.json"])
    assert a.launcher == "run-ear.cmd" and a.server_args == []
    with pytest.raises(SystemExit):
        _pipe_check().parse_cli(["--call", "align", "@b.json"])
