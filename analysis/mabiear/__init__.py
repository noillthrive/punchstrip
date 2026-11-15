"""mabi-ear: MabiIcco 렌더와 원곡 녹음을 파일로 받아 분석하는 MCP 서버.

이 패키지는 import 될 때 캐시 환경변수를 저장소 안 ``analysis/.cache`` 로 맞춘다.
(run-ear.cmd 도 같은 값을 설정하지만, 다른 방법으로 띄워도 사용자 홈에 캐시가
생기지 않게 하기 위함.) 무거운 라이브러리(numpy, librosa)는 여기서 부르지 않는다.
"""
from __future__ import annotations

import os
from pathlib import Path

__version__ = "0.5.0"

ANALYSIS_DIR = Path(__file__).resolve().parent.parent
CACHE_DIR = ANALYSIS_DIR / ".cache"


def _set_cache_env() -> None:
    defaults = {
        "NUMBA_CACHE_DIR": CACHE_DIR / "numba",
        "LIBROSA_CACHE_DIR": CACHE_DIR / "librosa",
        "LIBROSA_DATA_DIR": CACHE_DIR / "librosa_data",
        "MPLCONFIGDIR": CACHE_DIR / "mpl",
        "PIP_CACHE_DIR": CACHE_DIR / "pip",
    }
    for key, path in defaults.items():
        if not os.environ.get(key):
            os.environ[key] = str(path)
    # librosa 디스크 캐시(joblib)는 끈다: 가장 낮은 캐시 단계가 2라서 1이면 아무것도 저장하지 않는다.
    # (큰 배열 해시 비용을 피하고, 캐시 폴더가 커지지 않게)
    os.environ.setdefault("LIBROSA_CACHE_LEVEL", "1")
    os.environ.setdefault("LIBROSA_CACHE_VERBOSE", "0")
    # numpy 의 OpenBLAS 는 코어 수만큼 스레드 버퍼를 미리 잡아(16코어에서 약 500MB 커밋) 메모리 한도를 넘기 쉽다.
    # 이 분석은 BLAS 를 거의 쓰지 않으므로 1 스레드로 둔다(numpy 를 부르기 전에 설정해야 한다).
    for key in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS"):
        os.environ.setdefault(key, "1")


_set_cache_env()
