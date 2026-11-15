"""mabi-ear 요청 파일 드라이버(브리지의 McpClient와 같은 요청 파일 형식).

  .venv\Scripts\python.exe -I -X utf8 scripts\ear_requests.py <요청.jsonl> --out <응답.txt>

요청 파일: UTF-8, 한 줄에 {"tool": "align", "args": {...}} 하나. 빈 줄과 #·// 줄은 건너뛴다.
pipe_check.py로 run-ear.cmd(실제 stdio MCP 서버)를 띄워 initialize -> tools/list -> tools/call을 차례로 보내고,
요청마다 "=== [번호] 도구 — ok|isError" 머리줄과 결과 본문 JSON을 --out 파일(UTF-8)에 쓴다. 서버 stderr는
<--out>.err, 도구 인자 파일과 pipe_check의 원래 결과 JSON은 analysis/.cache/ear_requests/<--out 이름>/.
종료 코드는 pipe_check와 같다(0 모두 성공).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser(prog="ear_requests.py")
    ap.add_argument("requests")
    ap.add_argument("--out", required=True)
    ap.add_argument("--timeout", type=float, default=600.0)
    a = ap.parse_args()
    out = Path(a.out).resolve()
    work = HERE / ".cache" / "ear_requests" / out.name
    work.mkdir(parents=True, exist_ok=True)
    calls: list[tuple[str, Path]] = []
    for line in Path(a.requests).read_text(encoding="utf-8-sig").splitlines():
        t = line.strip()
        if not t or t.startswith("#") or t.startswith("//"):
            continue
        o = json.loads(t)
        if set(o) - {"tool", "args"} or "tool" not in o:
            raise SystemExit(f"요청 줄은 {{\"tool\": …, \"args\": …}}만: {t[:120]}")
        f = work / f"{len(calls) + 1:02d}_{o['tool']}.json"
        f.write_text(json.dumps(o.get("args") or {}, ensure_ascii=False), encoding="utf-8")
        calls.append((o["tool"], f))
    res_json = work / "pipe_result.json"
    cmd = [sys.executable, "-I", "-X", "utf8", str(HERE / "scripts" / "pipe_check.py"), str(HERE / "run-ear.cmd"),
           "--out", str(res_json), "--stderr", str(out) + ".err", "--timeout", str(a.timeout)]
    for tool, f in calls:
        cmd += ["--call", tool, "@" + str(f)]
    rc = subprocess.call(cmd)
    res = json.loads(res_json.read_text(encoding="utf-8"))
    lines = []
    init = res["steps"][0].get("response") or {}
    si = (init.get("result") or {}).get("serverInfo") or {}
    lines.append(f"# 서버 {si.get('name')} {si.get('version')}, 도구 {len(res['steps'][1].get('tools') or [])}개")
    errors = 0
    for i, st in enumerate(res["steps"][2:], start=1):
        tool = st["method"].removeprefix("tools/call ")
        bad = not st["ok"]
        errors += bad
        lines.append(f"=== [{i}] {tool} — {'isError' if bad else 'ok'}")
        body = st.get("body")
        lines.append(json.dumps(body, ensure_ascii=False, indent=2) if not isinstance(body, str) else body)
        lines.append("")
    lines.append(f"요청 {len(calls)}개, 오류 {errors}개")
    lines.append(f"# 서버 종료 코드 {res['exit_code']}, JSON-RPC 아닌 줄 {len(res['non_jsonrpc_lines'])}개")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return rc


if __name__ == "__main__":
    sys.exit(main())
