"""mabi-ear entry point used by run-ear.cmd.

Runs ``mabiear.server`` from the package folder next to this file, so no editable install (``pip install -e .``)
is needed and an interpreter started with ``-I`` (isolated mode) still finds the right copy of the package.
Messages go to stderr only: stdout is the MCP channel.
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def _check() -> None:
    if sys.version_info[:2] != (3, 11):
        sys.stderr.write("[run-ear] ERROR: mabi-ear needs Python 3.11 (pinned wheels); this is %d.%d at %s\n"
                         % (sys.version_info[0], sys.version_info[1], sys.executable))
        raise SystemExit(2)
    if not HERE.isascii():
        sys.stderr.write("[run-ear] warning: the install path has non-ASCII characters (%s). If start-up or pip "
                         "fails, unpack to an ASCII path such as C:\\mabi-mcp\n" % HERE)


def main() -> None:
    _check()
    if HERE not in sys.path:
        sys.path.insert(0, HERE)
    from mabiear.server import main as server_main

    server_main()


if __name__ == "__main__":
    main()
