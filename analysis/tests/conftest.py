"""pytest 설정: 임시 폴더와 캐시를 저장소 안 analysis/.cache 아래로(사용자 홈·시스템 임시 폴더를 쓰지 않음)."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ANALYSIS = Path(__file__).resolve().parents[1]
CACHE = ANALYSIS / ".cache"
for _k, _sub in (("NUMBA_CACHE_DIR", "numba"), ("LIBROSA_CACHE_DIR", "librosa"), ("MPLCONFIGDIR", "mpl"),
                 ("TMP", "tmp"), ("TEMP", "tmp"), ("TMPDIR", "tmp")):
    os.environ[_k] = str(CACHE / _sub)
(CACHE / "tmp").mkdir(parents=True, exist_ok=True)
os.environ["LIBROSA_CACHE_LEVEL"] = "1"
sys.path.insert(0, str(Path(__file__).resolve().parent))  # synth.py


@pytest.hookimpl(tryfirst=True)
def pytest_configure(config):
    if not config.option.basetemp:
        config.option.basetemp = str(CACHE / "pytest-tmp")
