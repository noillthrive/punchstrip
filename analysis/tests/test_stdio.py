"""서버를 실제 프로세스로 띄워 stdio(JSON-RPC)로 주고받는다. stdout 에 JSON-RPC 말고는 없어야 한다."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import synth

# run-ear.cmd's entry point: imports the mabiear package next to it even in isolated mode (-I), so this test always
# exercises the tree it lives in (not an editable install elsewhere).
RUN_EAR = Path(__file__).resolve().parents[1] / "run_ear.py"

EXPECTED = ["audio_info", "align", "chroma_compare", "onset_compare", "richness_compare", "balance", "compare_renders",
            "report", "stem_drum_hits", "stem_bass_notes", "stem_melody_notes", "stem_chords",
            "structure_repeats", "phone_version", "excerpt_ab", "downbeat_check", "lead_check", "vocal_agreement"]


def rpc(proc, obj):
    proc.stdin.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    proc.stdin.flush()


def test_stdio_roundtrip(tmp_path: Path):
    wav = synth.write_wav(tmp_path / "in" / "소리 愛.wav", synth.song([["C4", "E4", "G4"]], 120))
    out = tmp_path / "out"
    env = dict(os.environ)
    t0 = time.perf_counter()
    proc = subprocess.Popen([sys.executable, "-I", "-X", "utf8", str(RUN_EAR), "--out-dir", str(out),
                             "--log", str(tmp_path / "ear.log")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        rpc(proc, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                   "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                              "clientInfo": {"name": "pytest", "version": "0"}}})
        first = proc.stdout.readline()
        startup = time.perf_counter() - t0
        rpc(proc, {"jsonrpc": "2.0", "method": "notifications/initialized"})
        rpc(proc, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        rpc(proc, {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                   "params": {"name": "audio_info", "arguments": {"path": str(wav)}}})
        rpc(proc, {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                   "params": {"name": "chroma_compare", "arguments": {"render_wav": str(wav), "render_meta": str(wav),
                                                                      "original_audio": str(wav), "align_json": str(wav),
                                                                      "from_measure": "열"}}})
        rpc(proc, {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                   "params": {"name": "audio_info", "arguments": {"path": str(tmp_path / "없음.wav")}}})
        lines = [first] + [proc.stdout.readline() for _ in range(4)]
    finally:
        proc.stdin.close()
        rest, err = proc.communicate(timeout=30)
    msgs = []
    for raw in lines + rest.splitlines(keepends=True):
        if not raw.strip():
            continue
        m = json.loads(raw.decode("utf-8"))  # JSON 이 아니면 여기서 실패
        assert m["jsonrpc"] == "2.0"
        msgs.append(m)
    by_id = {m.get("id"): m for m in msgs}
    assert by_id[1]["result"]["serverInfo"]["name"] == "mabi-ear"
    assert startup < 10, startup
    assert [t["name"] for t in by_id[2]["result"]["tools"]] == EXPECTED
    r3 = by_id[3]["result"]
    assert r3["isError"] is False
    body = json.loads(r3["content"][0]["text"])
    assert body["ok"] is True and body["summary"]["sample_rate"] == synth.SR
    r4 = by_id[4]["result"]
    assert r4["isError"] is True
    b4 = json.loads(r4["content"][0]["text"])
    assert b4["ok"] is False and b4["error"] == "BAD_ARGUMENT"
    b5 = json.loads(by_id[5]["result"]["content"][0]["text"])
    assert by_id[5]["result"]["isError"] is True and b5["error"] == "FILE_NOT_FOUND"
    assert not out.exists()  # audio_info 는 save 없이 파일을 쓰지 않는다
    assert b"ready" in err  # 로그는 stderr 로
    pkg = str(RUN_EAR.parent / "mabiear").encode("utf-8")
    assert pkg in err, err[-400:]  # the package of this tree was loaded


def _serve(tmp_path: Path, calls: list[dict]) -> dict:
    """서버를 띄워 initialize 뒤 tools/call 들을 보내고 {id: 결과 본문(dict), ...}과 isError를 돌려준다."""
    proc = subprocess.Popen([sys.executable, "-I", "-X", "utf8", str(RUN_EAR), "--out-dir",
                             str(tmp_path / "out"), "--log", str(tmp_path / "ear.log")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(os.environ))
    try:
        rpc(proc, {"jsonrpc": "2.0", "id": 0, "method": "initialize",
                   "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                              "clientInfo": {"name": "pytest", "version": "0"}}})
        proc.stdout.readline()
        rpc(proc, {"jsonrpc": "2.0", "method": "notifications/initialized"})
        for i, c in enumerate(calls, start=1):
            rpc(proc, {"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": c})
        lines = [proc.stdout.readline() for _ in calls]
    finally:
        proc.stdin.close()
        rest, _err = proc.communicate(timeout=60)
    out = {}
    for raw in lines + rest.splitlines(keepends=True):
        if raw.strip():
            m = json.loads(raw.decode("utf-8"))
            if "id" in m and m["id"]:
                res = m["result"]
                out[m["id"]] = (res["isError"], json.loads(res["content"][0]["text"]))
    return out


def test_stdio_rejects_loose_int_and_list_args(tmp_path: Path):
    """MCP 파이프를 거치면 SDK(pydantic)가 "2"·true·2.0 을 정수로, '[0.9,1.1]'을 목록으로 바꿔 버린다 → 서버가 먼저 거절."""
    a_wav = synth.write_wav(tmp_path / "in" / "a.wav", synth.song([["C4", "E4", "G4"], ["A3", "C4", "E4"]], 120))
    b_wav = synth.write_wav(tmp_path / "in" / "b.wav", synth.song([["A3", "C4"], ["F3", "A3"], ["C4", "E4", "G4"],
                                                                   ["A3", "C4", "E4"]], 120))
    a_meta = synth.write_json(tmp_path / "in" / "a.json", synth.meta_for(120, 2, wav=str(a_wav)))
    b_meta = synth.write_json(tmp_path / "in" / "b.json", synth.meta_for(120, 4, wav=str(b_wav)))
    base = {"a_wav": str(a_wav), "a_meta": str(a_meta), "b_wav": str(b_wav), "b_meta": str(b_meta)}
    bad = [("compare_renders", {**base, "b_measure_offset": "2"}),
           ("compare_renders", {**base, "b_measure_offset": True}),
           ("compare_renders", {**base, "b_measure_offset": 2.0}),
           ("compare_renders", {**base, "top_n": "3"}),
           ("compare_renders", {**base, "from_measure": "1"}),
           ("compare_renders", {**base, "overwrite": "true"}),
           ("report", {"ab_json": str(a_meta), "b_measure_offset": "2"}),
           ("align", {"render_wav": str(a_wav), "render_meta": str(a_meta), "original_audio": str(b_wav),
                      "tempo_ratio_range": "[0.9,1.1]"}),
           ("align", {"render_wav": str(a_wav), "render_meta": str(a_meta), "original_audio": str(b_wav),
                      "search_sec": ["0", "5"]}),
           ("balance", {"render_meta": str(a_meta), "low_min_parts": "3"}),
           ("balance", {"render_meta": str(a_meta), "buried_db": "12"}),
           ("compare_renders", {**base, "b_offset": 2}),           # unknown argument names are errors
           ("audio_info", {"path": str(a_wav), "sav": True})]
    good = [("compare_renders", {**base, "b_measure_offset": 2, "name": "ok2"}),
            ("compare_renders", {**base, "b_measure_offset": 0, "top_n": 1, "from_measure": 1, "to_measure": 2,
                                 "overwrite": False, "name": "ok0"})]
    calls = [{"name": n, "arguments": a} for n, a in bad + good]
    res = _serve(tmp_path, calls)
    for i, (n, a) in enumerate(bad, start=1):
        is_err, body = res[i]
        assert is_err is True and body["ok"] is False and body["error"] == "BAD_ARGUMENT", (n, a, body)
        assert body["error_alias"] == "INVALID_ARGS", body
    assert res[len(bad) - 1][1]["details"]["unknown"] == ["b_offset"]
    assert "b_measure_offset" in res[len(bad) - 1][1]["details"]["allowed"]
    ok2 = res[len(bad) + 1]
    assert ok2[0] is False and ok2[1]["ok"] is True, ok2
    assert ok2[1]["summary"]["b_measure_offset"] == 2 and ok2[1]["summary"]["measures"] == 2
    ok0 = res[len(bad) + 2]
    assert ok0[0] is False and ok0[1]["summary"]["b_measure_offset"] == 0, ok0
    assert not (tmp_path / "out" / "a_align.json").exists()
