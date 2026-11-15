"""MCP 서버를 표준입출력 파이프로 띄워 initialize -> tools/list -> tools/call 을 보내고 확인한다.

사용 예(PowerShell 이나 cmd 에서, analysis 폴더 기준):
  .venv\\Scripts\\python.exe -I -X utf8 scripts\\pipe_check.py run-ear.cmd ^
      --call audio_info "{\\"path\\": \\"C:/music/song.flac\\"}" --out .cache\\trial\\pipe_result.json ^
      -- --out-dir C:\\mabi-mcp\\output\\analysis --log C:\\mabi-mcp\\output\\ear.log

인자 순서: 첫 '--' 앞은 이 스크립트 인자(실행기 run-ear.cmd 와 --call/--out/--stderr/--timeout, 순서 자유),
'--' 뒤는 모두 서버 인자다. 실행기를 '--' 바로 뒤에 써도 된다:
  pipe_check.py --call ... --out ... -- run-ear.cmd --out-dir X --log Y

- 서버 stdout 의 모든 줄이 JSON-RPC 2.0 인지 검사한다(아니면 non_jsonrpc_lines 에 남는다).
- 서버 stderr 는 --stderr 파일(기본 .cache/trial/pipe_stderr.log)로.
- 결과(응답, 걸린 시간)는 --out JSON 파일과 stderr 요약으로 낸다(stdout 은 쓰지 않는다).
- --call 의 인자 JSON 대신 @파일경로 를 주면 그 파일(UTF-8 JSON)을 읽는다(한글 경로를 명령줄 따옴표 없이 넘길 때).
"""
from __future__ import annotations

import argparse
import json
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent


def parse_cli(argv: list[str]) -> argparse.Namespace:
    """첫 '--' 앞 = 이 스크립트 인자(실행기 위치 인자와 옵션, 순서 자유), 뒤 = 서버 인자.

    실행기를 '--' 앞에 주지 않았으면 '--' 뒤 첫 항목을 실행기로 본다. argparse 는 위치 인자 뒤에 옵션이 오고 다시
    nargs='*' 가 오면 "unrecognized arguments" 로 실패하므로 '--' 에서 직접 나눈다.
    """
    if "--" in argv:
        i = argv.index("--")
        head, tail = argv[:i], argv[i + 1:]
    else:
        head, tail = argv, []
    ap = argparse.ArgumentParser(prog="pipe_check.py",
                                 usage="%(prog)s [launcher] [--call TOOL ARGS_JSON]... [--out F] [--stderr F] "
                                       "[--timeout S] [-- [launcher] server_args...]")
    ap.add_argument("launcher", nargs="?", help="run-ear.cmd 경로(또는 .exe/.py 가 아닌 명령). 없으면 '--' 뒤 첫 항목")
    ap.add_argument("--call", nargs=2, action="append", default=[], metavar=("TOOL", "ARGS_JSON"))
    ap.add_argument("--out", default=str(HERE / ".cache" / "trial" / "pipe_result.json"))
    ap.add_argument("--stderr", default=str(HERE / ".cache" / "trial" / "pipe_stderr.log"))
    ap.add_argument("--timeout", type=float, default=180.0)
    a = ap.parse_args(head)
    if a.launcher is None:
        if not tail:
            ap.error("실행기(run-ear.cmd 등)가 없습니다")
        a.launcher = tail.pop(0)
    a.server_args = tail
    return a


def main() -> int:
    a = parse_cli(sys.argv[1:])

    launcher = Path(a.launcher).resolve()
    cmd = (["cmd", "/c", str(launcher)] if launcher.suffix.lower() in (".cmd", ".bat") else [str(launcher)])
    cmd += a.server_args
    Path(a.stderr).parent.mkdir(parents=True, exist_ok=True)
    err_f = open(a.stderr, "wb")
    t0 = time.perf_counter()
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=err_f)
    lines: "queue.Queue[tuple[float, bytes]]" = queue.Queue()
    raw: list[str] = []

    def reader():
        for line in proc.stdout:  # type: ignore[union-attr]
            lines.put((time.perf_counter(), line))
        lines.put((time.perf_counter(), b""))

    threading.Thread(target=reader, daemon=True).start()
    bad: list[str] = []
    responses: dict = {}

    def send(obj):
        proc.stdin.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))  # type: ignore[union-attr]
        proc.stdin.flush()  # type: ignore[union-attr]

    def wait_for(id_):
        deadline = time.perf_counter() + a.timeout
        while time.perf_counter() < deadline:
            try:
                ts, line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line == b"":
                return None, ts
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            raw.append(text)
            try:
                msg = json.loads(text)
                if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0":
                    raise ValueError
            except ValueError:
                bad.append(text)
                continue
            if msg.get("id") == id_:
                return msg, ts
        return None, time.perf_counter()

    result: dict = {"cmd": cmd, "steps": []}
    s = time.perf_counter()
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
          "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                     "clientInfo": {"name": "pipe-check", "version": "0"}}})
    msg, ts = wait_for(1)
    result["steps"].append({"method": "initialize", "ok": bool(msg and "result" in msg),
                            "sec_since_spawn": round(ts - t0, 3), "response": msg})
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    s = time.perf_counter()
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    msg, ts = wait_for(2)
    names = [t["name"] for t in (msg or {}).get("result", {}).get("tools", [])]
    result["steps"].append({"method": "tools/list", "ok": bool(names), "sec": round(ts - s, 3), "tools": names})
    for i, (tool, args) in enumerate(a.call, start=3):
        if args.startswith("@"):
            args = Path(args[1:]).read_text(encoding="utf-8")
        s = time.perf_counter()
        send({"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": {"name": tool, "arguments": json.loads(args)}})
        msg, ts = wait_for(i)
        res = (msg or {}).get("result") or {}
        body = None
        if res.get("content"):
            try:
                body = json.loads(res["content"][0]["text"])
            except (ValueError, KeyError, TypeError):
                body = res["content"][0].get("text")
        result["steps"].append({"method": f"tools/call {tool}", "ok": bool(msg) and not res.get("isError"),
                                "isError": res.get("isError"), "sec": round(ts - s, 3), "body": body})
    proc.stdin.close()  # type: ignore[union-attr]
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
    # 남은 줄도 검사
    while True:
        try:
            _, line = lines.get(timeout=1)
        except queue.Empty:
            break
        if line == b"":
            break
        text = line.decode("utf-8", errors="replace").rstrip("\r\n")
        raw.append(text)
        try:
            if json.loads(text).get("jsonrpc") != "2.0":
                bad.append(text)
        except (ValueError, AttributeError):
            bad.append(text)
    err_f.close()
    result["stdout_lines"] = len(raw)
    result["non_jsonrpc_lines"] = bad
    result["exit_code"] = proc.returncode
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    w = sys.stderr.write
    for st in result["steps"]:
        extra = ""
        if st["method"] == "tools/list":
            extra = f" {len(st['tools'])}개: {', '.join(st['tools'])}"
        elif st["method"].startswith("tools/call") and isinstance(st.get("body"), dict):
            extra = " " + str(st["body"].get("summary_text") or st["body"].get("message") or "").replace("\n", " / ")
        t = st.get("sec_since_spawn", st.get("sec"))
        w(f"[{'OK' if st['ok'] else 'NG'}] {st['method']} {t}s{extra}\n")
    w(f"stdout {len(raw)}줄, JSON-RPC 아닌 줄 {len(bad)}개, 종료 코드 {proc.returncode}, 결과 {a.out}\n")
    ok = all(st["ok"] for st in result["steps"]) and not bad
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
