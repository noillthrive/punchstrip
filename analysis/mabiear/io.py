"""파일 입출력, 경로 보호, 오류 형식, 메타 JSON 읽기, 오디오 읽기.

이 모듈은 numpy/soundfile/librosa 를 함수 안에서만 부른다(서버 시작을 가볍게).
"""
from __future__ import annotations

import json
import logging
import math
import os
import threading
from pathlib import Path
from typing import Any, Iterable

from . import ANALYSIS_DIR

log = logging.getLogger("mabiear.io")

META_SCHEMA = "mabibridge.render/1"
ANALYSIS_SR = 22050  # 분석용 샘플레이트(모노로 합친 뒤 리샘플)
PITCH_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# analysis/ -> the repository or unpacked install folder
REPO_ROOT = ANALYSIS_DIR.parent

OUT_ENV = "MABIBRIDGE_OUT"

# Optional project file, the same file and the same lookup as the bridge (mabibridge.engine.ProjectConfig):
# env MABIBRIDGE_PROJECT (a file path, or "none"), else the nearest mabibridge-project.properties above this package,
# else the nearest one above the working directory. "key = value" lines, UTF-8, # or ! comments, no escapes (backslashes
# are literal); paths absolute or relative to the file's folder. Keys: render.dir (bridge), analysis.dir (this server),
# protect (several paths separated by ";") and protect.<anything> (one path each).
PROJECT_FILE = "mabibridge-project.properties"
PROJECT_ENV = "MABIBRIDGE_PROJECT"


class Project:
    def __init__(self, file: Path, render_dir: Path | None, analysis_dir: Path | None, protect: list[Path],
                 warnings: list[str]):
        self.file, self.render_dir, self.analysis_dir = file, render_dir, analysis_dir
        self.protect, self.warnings = protect, warnings


def _unquote(v: str) -> str:
    return v[1:-1] if len(v) >= 2 and v.startswith('"') and v.endswith('"') else v


def locate_project(env: str | None, code_dir: Path | None, cwd: Path | None) -> Path | None:
    """The project file to use, or None. An explicit path is returned even if it does not exist (load warns)."""
    if env is not None and env.strip():
        t = _unquote(env.strip())
        if t.lower() == "none":
            return None
        return Path(os.path.abspath(t))
    for start in (code_dir, cwd):
        if start is None:
            continue
        p = Path(os.path.abspath(start))
        for d in [p, *p.parents]:
            f = d / PROJECT_FILE
            if f.is_file():
                return f
    return None


def load_project(f: Path) -> Project:
    warn: list[str] = []
    protect: list[Path] = []
    render = analysis = None
    base = Path(os.path.abspath(f)).parent
    try:
        text = f.read_text(encoding="utf-8-sig")
    except (OSError, ValueError) as e:
        return Project(f, None, None, [], [f"project file not readable: {f}: {e}"])

    def res(v: str) -> Path | None:
        v = _unquote(v.strip())
        if not v:
            return None
        p = Path(v)
        return Path(os.path.normpath(p if p.is_absolute() else base / p))

    for n, line in enumerate(text.splitlines(), 1):
        t = line.strip()
        if not t or t.startswith(("#", "!")):
            continue
        if "=" not in t or t.index("=") == 0:
            warn.append(f"{PROJECT_FILE}:{n}: no key = value, ignored")
            continue
        key, value = t.split("=", 1)
        key, value = key.strip().lower(), value.strip()
        if key == "render.dir":
            render = res(value)
        elif key == "analysis.dir":
            analysis = res(value)
        elif key == "protect" or key.startswith("protect."):
            parts = value.split(";") if key == "protect" else [value]
            for part in parts:
                q = res(part)
                if q is not None and q not in protect:
                    protect.append(q)
        else:
            warn.append(f"{PROJECT_FILE}:{n}: unknown key {key}, ignored")
    return Project(f, render, analysis, protect, warn)


def project_found_via(p: "Project | None") -> str:
    """How the project file was found (same wording as the bridge)."""
    if p is None:
        return "none"
    if os.environ.get(PROJECT_ENV, "").strip():
        return "MABIBRIDGE_PROJECT"
    pkg = os.path.normcase(os.path.abspath(ANALYSIS_DIR / "mabiear"))
    d = os.path.normcase(os.path.abspath(p.file.parent))
    return "above the install folder" if pkg == d or pkg.startswith(d.rstrip("\\/") + os.sep) else "above the working directory"


def find_project() -> Project | None:
    f = locate_project(os.environ.get(PROJECT_ENV), ANALYSIS_DIR / "mabiear", Path.cwd())
    return None if f is None else load_project(f)


# Bad argument code shared with the bridge. mabi-ear 0.2.0 and older returned "INVALID_ARGS"; error bodies now
# carry error = "BAD_ARGUMENT" and error_alias = "INVALID_ARGS" so old callers that test either name still work.
BAD_ARGUMENT = "BAD_ARGUMENT"
LEGACY_BAD_ARGUMENT = "INVALID_ARGS"


class EarError(Exception):
    """도구 실패. server 가 MCP isError 응답 {ok:false, error, message, details} 로 바꾼다."""

    def __init__(self, code: str, message: str, details: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}

    def to_dict(self) -> dict:
        d = {"ok": False, "error": self.code, "message": self.message, "details": self.details}
        if self.code == BAD_ARGUMENT:
            d["error_alias"] = LEGACY_BAD_ARGUMENT  # mabi-ear <= 0.2.0 called this INVALID_ARGS
        return d


# ---------------------------------------------------------------- 설정(서버 실행 인자)

class _Config:
    def __init__(self) -> None:
        self.project: Project | None = find_project()
        self.out_dir_error: str | None = None
        try:
            self.out_dir: Path = default_out_dir()
        except ValueError as e:  # relative MABIBRIDGE_OUT: server.configure() stops with this message
            self.out_dir_error = str(e)
            self.out_dir = REPO_ROOT / "output" / "analysis"
        self.extra_protect: list[Path] = []


def default_out_dir(env: str | None = None, project: "Project | None | bool" = True, repo_root: Path | None = None) -> Path:
    """Default output folder: <MABIBRIDGE_OUT>/analysis (must be absolute, else ValueError); the project file's
    analysis.dir; else <install>/output/analysis (next to the bridge's output/render). project=True = look it up."""
    env = os.environ.get(OUT_ENV) if env is None else env
    if project is True:
        project = find_project()
    root = REPO_ROOT if repo_root is None else repo_root
    if env and env.strip():
        v = _unquote(env.strip())
        if not Path(v).is_absolute():
            raise ValueError(f"{OUT_ENV} must be an absolute path (for example C:\\mabi-out), got: {env}")
        return Path(v) / "analysis"
    if project is not None and project is not False and project.analysis_dir is not None:
        return project.analysis_dir
    return root / "output" / "analysis"


CONFIG = _Config()


def protected_roots() -> list[Path]:
    roots: list[Path] = list(CONFIG.project.protect) if CONFIG.project else []
    roots += CONFIG.extra_protect
    return roots


# ---------------------------------------------------------------- 경로 도우미

def _norm(p: Path | str) -> str:
    """Real path (junctions and symlinks of the existing part resolved, like the bridge), case-folded."""
    return os.path.normcase(os.path.realpath(os.path.abspath(str(p)))).rstrip("\\/")


def is_within(child: Path | str, parent: Path | str) -> bool:
    c, p = _norm(child), _norm(parent)
    return c == p or c.startswith(p + os.sep)


_WIN_BAD_CHARS = set('<>"|?*')
_WINERROR_INVALID_NAME = 123  # 파일 이름, 디렉터리 이름 또는 볼륨 레이블 구문이 잘못됨


def is_path_syntax_error(e: BaseException) -> bool:
    """경로 문법 때문에 난 OS 오류인가(Windows WinError 123, EINVAL, 경로 안 NUL 문자)."""
    import errno

    if isinstance(e, ValueError):
        return "null character" in str(e) or "null byte" in str(e)
    if isinstance(e, OSError):
        return getattr(e, "winerror", None) == _WINERROR_INVALID_NAME or e.errno == errno.EINVAL
    return False


def check_path_syntax(value: str, arg: str) -> None:
    """경로 문자열의 문법을 먼저 본다(분석을 돌리기 전에). 틀리면 BAD_ARGUMENT.

    제어문자(코드 0~31)는 어디서나, Windows에서는 < > " | ? * 와 드라이브 뒤가 아닌 ':'도 거절한다.
    마지막으로 os.path.abspath 가 OSError/ValueError 를 내도 BAD_ARGUMENT.
    """
    bad = sorted({f"U+{ord(ch):04X}" for ch in value if ord(ch) < 32})
    if os.name == "nt":
        body = value[4:] if value.startswith("\\\\?\\") else value
        bad += sorted({ch for ch in body if ch in _WIN_BAD_CHARS})
        rest = body[2:] if len(body) >= 2 and body[1] == ":" else body
        if ":" in rest:
            bad.append(":")
    if bad:
        raise EarError("BAD_ARGUMENT", f"'{arg}' 경로에 쓸 수 없는 문자가 있습니다: {' '.join(bad)}",
                       {"arg": arg, "path": value, "bad_chars": bad})
    try:
        os.path.abspath(value)
    except (OSError, ValueError) as e:
        raise EarError("BAD_ARGUMENT", f"'{arg}' 경로의 문법이 잘못됐습니다.",
                       {"arg": arg, "path": value, "reason": str(e)})


def input_file(value: Any, arg: str) -> Path:
    """입력 파일 경로 인자를 확인한다. 없으면 FILE_NOT_FOUND."""
    if not isinstance(value, str) or not value.strip():
        raise EarError("BAD_ARGUMENT", f"'{arg}'에 파일 경로(문자열)가 필요합니다.", {"arg": arg})
    check_path_syntax(value.strip(), arg)
    p = Path(value.strip())
    if not p.is_absolute():
        raise EarError("BAD_ARGUMENT", f"'{arg}'는 절대 경로여야 합니다.", {"arg": arg, "path": value})
    if not p.is_file():
        raise EarError("FILE_NOT_FOUND", f"파일이 없습니다: {value}", {"arg": arg, "path": value})
    return p


def check_out_dir(out_dir: str | None, inputs: Iterable[Path]) -> Path:
    if out_dir is None or (isinstance(out_dir, str) and not out_dir.strip()):
        d = CONFIG.out_dir
    else:
        if not isinstance(out_dir, str):
            raise EarError("BAD_ARGUMENT", "'out_dir'는 문자열이어야 합니다.", {"arg": "out_dir"})
        check_path_syntax(out_dir.strip(), "out_dir")
        d = Path(out_dir.strip())
        if not d.is_absolute():
            raise EarError("BAD_ARGUMENT", "'out_dir'는 절대 경로여야 합니다.", {"out_dir": out_dir})
    for root in protected_roots():
        if is_within(d, root):
            raise EarError("PROTECTED_PATH", f"보호 폴더 안에는 쓸 수 없습니다: {d}",
                           {"out_dir": str(d), "protected": str(root)})
    for inp in inputs:
        if is_within(d, inp.parent):
            raise EarError("PROTECTED_PATH", f"입력 파일이 있는 폴더 안에는 쓸 수 없습니다: {d}",
                           {"out_dir": str(d), "input": str(inp)})
    return d


def check_name(name: Any, fallback_from: Path) -> str:
    if name is None or (isinstance(name, str) and not name.strip()):
        stem = fallback_from.name
        for suf in (".meta.json", ".json", ".wav", ".flac", ".mp3"):
            if stem.lower().endswith(suf):
                stem = stem[: -len(suf)]
                break
        else:
            stem = Path(stem).stem
        return stem
    if not isinstance(name, str):
        raise EarError("BAD_ARGUMENT", "'name'은 문자열이어야 합니다.", {"arg": "name"})
    n = name.strip()
    bad = set('\\/:*?"<>|')
    if any(ch in bad or ord(ch) < 32 for ch in n) or n in (".", "..") or len(n) > 120:
        raise EarError("BAD_ARGUMENT", "'name'에는 경로 구분자나 금지 문자를 쓸 수 없습니다(120자 안).", {"name": name})
    return n


def plan_output(out_dir: str | None, name: str, suffix: str, overwrite: bool, inputs: Iterable[Path],
                derived: Iterable[Path] = ()) -> Path:
    """출력 파일 경로를 정하고 보호·중복을 확인한다(파일은 아직 만들지 않는다).

    inputs: 원본 입력(렌더 WAV·메타·원곡). 출력 폴더가 이 파일들의 폴더 안이면 PROTECTED_PATH.
    derived: 이 서버가 만든 분석 JSON(align_json 등). 같은 출력 폴더에 있어도 되지만 덮어쓰지는 않는다.
    """
    inputs = list(inputs)
    derived = list(derived)
    d = check_out_dir(out_dir, inputs)
    target = d / f"{name}{suffix}"
    for inp in inputs + derived:
        if _norm(target) == _norm(inp):
            raise EarError("PROTECTED_PATH", "출력 파일이 입력 파일과 같습니다.", {"path": str(target)})
    if target.exists() and not overwrite:
        raise EarError("EXISTS", f"이미 파일이 있습니다(덮어쓰려면 overwrite: true): {target}",
                       {"path": str(target)})
    return target


def write_output(target: Path, text: str, overwrite: bool) -> None:
    tmp = temp_name(target)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with open(tmp, "x", encoding="utf-8", newline="\n") as f:
            f.write(text)
    except (OSError, ValueError) as e:
        if is_path_syntax_error(e):
            raise EarError("BAD_ARGUMENT", f"출력 경로의 문법이 잘못됐습니다: {target}",
                           {"path": str(target), "reason": str(e)})
        raise
    try:
        publish(tmp, target, overwrite)
    finally:
        if tmp.exists():
            tmp.unlink()
    log.info("wrote %s", target)


def temp_name(target: Path, suffix: str = "") -> Path:
    """A temp file name next to target, unique per process and call."""
    import uuid
    return target.with_name(f".{target.name}.{os.getpid()}.{uuid.uuid4().hex[:12]}.tmp{suffix}")


def publish(tmp: Path, target: Path, overwrite: bool) -> None:
    """Move a finished temp file to target. overwrite=False never replaces a file that appeared in the meantime:
    a hard link (atomic, fails if target exists), or where links are unavailable an exclusive create ("xb") + copy."""
    if overwrite:
        os.replace(tmp, target)
        return
    try:
        os.link(tmp, target)
        return
    except FileExistsError:
        raise EarError("EXISTS", f"이미 파일이 있습니다: {target}", {"path": str(target)})
    except OSError:
        pass
    import shutil
    try:
        with open(target, "xb") as dst, open(tmp, "rb") as src:
            shutil.copyfileobj(src, dst, 1 << 20)
    except FileExistsError:
        raise EarError("EXISTS", f"이미 파일이 있습니다: {target}", {"path": str(target)})


def _clean(obj: Any) -> Any:
    """JSON 으로 쓸 수 있게 numpy 값과 NaN 을 정리한다."""
    try:
        import numpy as np  # 이미 불렸을 때만 비용 없음
    except Exception:  # pragma: no cover
        np = None
    if isinstance(obj, dict):
        return {str(k): _clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_clean(v) for v in obj]
    if np is not None:
        if isinstance(obj, np.ndarray):
            return _clean(obj.tolist())
        if isinstance(obj, np.generic):
            obj = obj.item()
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    return obj


def dumps(obj: Any) -> str:
    return json.dumps(_clean(obj), ensure_ascii=False, indent=1)


def write_json(target: Path, data: dict, overwrite: bool) -> None:
    write_output(target, dumps(data) + "\n", overwrite)


def r(x: float | None, nd: int = 3) -> float | None:
    if x is None:
        return None
    x = float(x)
    if math.isnan(x) or math.isinf(x):
        return None
    return round(x, nd)


# ---------------------------------------------------------------- JSON 입력

def read_json(path: Path, arg: str) -> dict:
    try:
        with open(path, "r", encoding="utf-8-sig") as f:
            data = json.load(f)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise EarError("BAD_ARGUMENT", f"JSON을 읽지 못했습니다: {path}", {"arg": arg, "reason": str(e)})
    if not isinstance(data, dict):
        raise EarError("BAD_ARGUMENT", f"JSON 최상위가 객체가 아닙니다: {path}", {"arg": arg})
    return data


def load_meta(path: Path, arg: str = "render_meta") -> dict:
    """mabibridge.render/1 메타 JSON 을 읽어 measures 표를 검사·정리한다."""
    try:
        with open(path, "r", encoding="utf-8-sig") as f:
            meta = json.load(f)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise EarError("META_INVALID", f"메타 JSON을 읽지 못했습니다: {path}", {"arg": arg, "reason": str(e)})
    if not isinstance(meta, dict) or meta.get("schema") != META_SCHEMA:
        got = meta.get("schema") if isinstance(meta, dict) else None
        raise EarError("META_INVALID", f"메타 스키마가 '{META_SCHEMA}'가 아닙니다.",
                       {"arg": arg, "path": str(path), "schema": got})
    ms = meta.get("measures")
    if not isinstance(ms, list) or not ms:
        raise EarError("META_INVALID", "메타에 'measures' 표가 없거나 비어 있습니다.", {"arg": arg, "path": str(path)})
    out = []
    for i, m in enumerate(ms):
        try:
            num = int(m["measure"])
            s = float(m["start_sec"])
            e = float(m["end_sec"])
        except (KeyError, TypeError, ValueError):
            raise EarError("META_INVALID", f"measures[{i}]에 measure/start_sec/end_sec가 없습니다.",
                           {"arg": arg, "index": i})
        if not (e > s) or s < 0:
            raise EarError("META_INVALID", f"measures[{i}]의 시간이 잘못됐습니다(start {s}, end {e}).",
                           {"arg": arg, "index": i})
        beats_raw = m.get("beats")
        beats: list[float]
        if isinstance(beats_raw, list) and beats_raw and all(isinstance(b, (int, float)) for b in beats_raw):
            beats = [float(b) for b in beats_raw]
            # 박이 마디 기준 상대 시각으로 적힌 경우(첫 박 0, 마디 시작 > 0) 절대 시각으로 바꾼다
            if s > 0.01 and abs(beats[0]) < 1e-6:
                beats = [b + s for b in beats]
            beats = [b for b in beats if s - 1e-3 <= b < e - 1e-3] or [s]
        else:
            beats = [s]
        out.append({"measure": num, "start_sec": s, "end_sec": e, "beats": beats})
    out.sort(key=lambda m: m["start_sec"])
    meta = dict(meta)
    meta["measures"] = out
    return meta


def select_measures(meta: dict, from_measure: Any, to_measure: Any) -> list[dict]:
    for arg, v in (("from_measure", from_measure), ("to_measure", to_measure)):
        if v is not None and (not isinstance(v, int) or isinstance(v, bool)):
            raise EarError("BAD_ARGUMENT", f"'{arg}'는 정수(마디 번호, 1부터)여야 합니다.", {arg: v})
    lo = from_measure if from_measure is not None else -10**9
    hi = to_measure if to_measure is not None else 10**9
    if lo > hi:
        raise EarError("BAD_ARGUMENT", "from_measure가 to_measure보다 큽니다.", {"from": lo, "to": hi})
    sel = [m for m in meta["measures"] if lo <= m["measure"] <= hi]
    if not sel:
        raise EarError("BAD_ARGUMENT", "고른 범위에 마디가 없습니다.",
                       {"from_measure": from_measure, "to_measure": to_measure,
                        "available": [meta["measures"][0]["measure"], meta["measures"][-1]["measure"]]})
    return sel


def beat_segments(m: dict) -> list[tuple[float, float]]:
    b = list(m["beats"]) + [m["end_sec"]]
    return [(b[i], b[i + 1]) for i in range(len(b) - 1) if b[i + 1] > b[i]]


# ---------------------------------------------------------------- 오디오

_audio_cache: dict[tuple, Any] = {}
_audio_order: list[tuple] = []
_audio_lock = threading.Lock()
_AUDIO_CACHE_MAX = 6


def _sf():
    try:
        import soundfile as sf
    except Exception as e:  # pragma: no cover
        raise EarError("INTERNAL", "soundfile을 불러오지 못했습니다.", {"reason": str(e)})
    return sf


def probe(path: Path) -> dict:
    sf = _sf()
    try:
        info = sf.info(str(path))
    except Exception as e:
        raise EarError("AUDIO_READ_FAILED", f"오디오를 읽지 못했습니다: {path}",
                       {"path": str(path), "reason": str(e)})
    return {"sample_rate": int(info.samplerate), "channels": int(info.channels), "frames": int(info.frames),
            "duration_sec": info.frames / float(info.samplerate), "format": info.format, "subtype": info.subtype}


def load_mono(path: Path, sr: int = ANALYSIS_SR, offset: float = 0.0, duration: float | None = None):
    """오디오를 모노(채널 평균)로 합치고 sr 로 리샘플한다. 반환 (y float32, sr)."""
    import numpy as np

    st = path.stat()
    key = (_norm(path), st.st_mtime_ns, st.st_size, sr, round(offset, 4), None if duration is None else round(duration, 4))
    with _audio_lock:
        if key in _audio_cache:
            return _audio_cache[key], sr
    sf = _sf()
    info = probe(path)
    native = info["sample_rate"]
    start = max(0, int(round(offset * native)))
    stop = None if duration is None else min(info["frames"], start + int(round(duration * native)))
    try:
        data, native_sr = sf.read(str(path), dtype="float32", always_2d=True, start=start, stop=stop)
    except Exception as e:
        raise EarError("AUDIO_READ_FAILED", f"오디오를 읽지 못했습니다: {path}", {"path": str(path), "reason": str(e)})
    y = data.mean(axis=1).astype(np.float32)
    if native_sr != sr and y.size:
        import librosa
        y = librosa.resample(y, orig_sr=native_sr, target_sr=sr, res_type="soxr_hq").astype(np.float32)
    with _audio_lock:
        _audio_cache[key] = y
        _audio_order.append(key)
        while len(_audio_order) > _AUDIO_CACHE_MAX:
            old = _audio_order.pop(0)
            _audio_cache.pop(old, None)
    return y, sr


def db(x: float, floor: float = -120.0) -> float:
    if x <= 0:
        return floor
    return max(floor, 20.0 * math.log10(x))


def audio_stats(path: Path) -> dict:
    """원래 샘플레이트·채널 그대로 peak/RMS(dBFS). 블록 단위로 읽어 메모리를 아낀다."""
    import numpy as np

    sf = _sf()
    info = probe(path)
    peak = 0.0
    sumsq = 0.0
    n = 0
    ch_peak = np.zeros(info["channels"])
    ch_sumsq = np.zeros(info["channels"])
    try:
        for block in sf.blocks(str(path), blocksize=262144, dtype="float32", always_2d=True):
            a = np.abs(block)
            ch_peak = np.maximum(ch_peak, a.max(axis=0))
            ch_sumsq += (block.astype(np.float64) ** 2).sum(axis=0)
            n += block.shape[0]
    except Exception as e:
        raise EarError("AUDIO_READ_FAILED", f"오디오를 읽지 못했습니다: {path}", {"path": str(path), "reason": str(e)})
    if n:
        peak = float(ch_peak.max())
        sumsq = float(ch_sumsq.sum())
    rms = math.sqrt(sumsq / max(1, n * info["channels"]))
    info.update({
        "peak_dbfs": r(db(peak), 2),
        "rms_dbfs": r(db(rms), 2),
        "channel_peak_dbfs": [r(db(float(v)), 2) for v in ch_peak],
        "channel_rms_dbfs": [r(db(math.sqrt(float(v) / max(1, n))), 2) for v in ch_sumsq],
    })
    return info
