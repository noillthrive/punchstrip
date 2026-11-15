"""Defaults that must work on another PC (no project file, any install path) and the shared project file (0.4.2)."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import pytest

from mabiear import io, server
from mabiear.io import EarError

ANALYSIS = Path(__file__).resolve().parents[1]


def write_project(folder: Path, text: str) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    f = folder / io.PROJECT_FILE
    f.write_text(text, encoding="utf-8")
    return f


def test_default_out_dir_standalone(tmp_path):
    install = tmp_path / "mabi-mcp"
    assert io.default_out_dir("", None, install) == install / "output" / "analysis"


def test_default_out_dir_env_wins(tmp_path):
    proj = io.Project(tmp_path / "p", None, tmp_path / "pa", [], [])
    assert io.default_out_dir(str(tmp_path / "o u t"), proj, tmp_path) == tmp_path / "o u t" / "analysis"
    assert io.default_out_dir("", proj, tmp_path) == tmp_path / "pa"


def test_relative_out_env_is_rejected(tmp_path):
    with pytest.raises(ValueError, match="MABIBRIDGE_OUT"):
        io.default_out_dir("relative\\out", None, tmp_path)


def test_relative_out_env_stops_the_server(tmp_path):
    env = dict(os.environ, MABIBRIDGE_OUT="relative-out", MABIBRIDGE_PROJECT="none")
    env.pop("PYTHONPATH", None)
    r = subprocess.run([sys.executable, "-I", "-X", "utf8", str(ANALYSIS / "run_ear.py"), "--log",
                        str(tmp_path / "ear.log")], capture_output=True, env=env, timeout=120, stdin=subprocess.DEVNULL)
    assert r.returncode != 0
    assert b"MABIBRIDGE_OUT must be an absolute path" in r.stderr
    assert r.stdout == b""  # stdout stays clean (MCP channel)


def test_project_file_parsing(tmp_path):
    other = tmp_path.parent / (tmp_path.name + "-abs")
    f = write_project(tmp_path, "\ufeff# comment\nprotect = app ; songs\n\nprotect.2 =   refs\\a b  \n"
                                f"! also a comment\nprotect.4 = {other}\nrender.dir = out/render\n"
                                f"analysis.dir = {other / 'ana'}\ncolour = red\nnot a pair\n")
    p = io.load_project(f)
    assert p.protect == [tmp_path / "app", tmp_path / "songs", tmp_path / "refs" / "a b", other]
    assert p.render_dir == tmp_path / "out" / "render"
    assert p.analysis_dir == other / "ana"
    assert len(p.warnings) == 2


def test_project_lookup_order(tmp_path):
    a, b = tmp_path / "a", tmp_path / "b"
    code, cwd = a / "x" / "y", b / "z"
    code.mkdir(parents=True)
    cwd.mkdir(parents=True)
    code_outer = io.locate_project(None, code, code)  # a file further up, e.g. when the tree sits inside a project
    write_project(b, "protect = q\n")
    assert io.locate_project(None, code, cwd) == (code_outer or b / io.PROJECT_FILE)
    write_project(a, "protect = p\n")
    assert io.locate_project(None, code, cwd) == a / io.PROJECT_FILE
    assert io.locate_project("none", code, cwd) is None
    assert io.locate_project(str(tmp_path / "f.properties"), code, cwd) == tmp_path / "f.properties"


def test_lookup_does_not_depend_on_the_working_directory(tmp_path):
    """A file above the install (package) folder is found from any working directory (no real project file needed)."""
    install = tmp_path / "proj" / "tools" / "mabi-mcp" / "analysis" / "mabiear"
    install.mkdir(parents=True)
    f = write_project(tmp_path / "proj", "protect = keep\n")
    elsewhere = tmp_path / "other" / "cwd"
    elsewhere.mkdir(parents=True)
    write_project(tmp_path / "other", "protect = wrong\n")  # a file above the cwd must not win
    assert io.locate_project(None, install, elsewhere) == f
    assert io.locate_project(None, install, install) == f
    assert io.locate_project(None, install, Path(os.path.abspath(os.sep))) == f


def test_protected_roots_come_from_project_only(monkeypatch, tmp_path):
    monkeypatch.setattr(io.CONFIG, "project", None)
    monkeypatch.setattr(io.CONFIG, "extra_protect", [tmp_path / "x"])
    assert io.protected_roots() == [tmp_path / "x"]


def test_protected_check_follows_junctions(monkeypatch, tmp_path):
    guarded = tmp_path / "guarded"
    guarded.mkdir()
    link = tmp_path / "link"
    r = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(guarded)], capture_output=True)
    if r.returncode != 0 or not link.exists():
        pytest.skip("cannot create a junction here")
    monkeypatch.setattr(io.CONFIG, "project", io.Project(tmp_path / "p", None, None, [guarded], []))
    monkeypatch.setattr(io.CONFIG, "extra_protect", [])
    assert io.is_within(link / "sub" / "out", guarded)
    with pytest.raises(EarError) as e:
        io.check_out_dir(str(link / "sub"), [])
    assert e.value.code == "PROTECTED_PATH"


def test_publish_without_overwrite_never_replaces(monkeypatch, tmp_path):
    target = tmp_path / "t.json"
    tmp = io.temp_name(target)
    tmp.write_text("new", encoding="utf-8")
    target.write_text("someone else's", encoding="utf-8")  # appeared in between

    def no_link(a, b):
        raise OSError("hard links not supported here")

    monkeypatch.setattr(os, "link", no_link)
    with pytest.raises(EarError) as e:
        io.publish(tmp, target, overwrite=False)
    assert e.value.code == "EXISTS"
    assert target.read_text(encoding="utf-8") == "someone else's"
    target.unlink()
    io.publish(tmp, target, overwrite=False)  # exclusive create + copy
    assert target.read_text(encoding="utf-8") == "new"


def test_temp_names_are_unique(tmp_path):
    t = tmp_path / "x.wav"
    assert io.temp_name(t) != io.temp_name(t)


def test_protected_default_out_dir_only_warns(monkeypatch, tmp_path, caplog):
    guarded = tmp_path / "guarded"
    monkeypatch.setattr(io.CONFIG, "project", io.Project(tmp_path / "p", None, guarded / "ana", [guarded], []))
    monkeypatch.setattr(io.CONFIG, "out_dir", guarded / "ana")
    monkeypatch.setattr(io.CONFIG, "out_dir_error", None)
    args = argparse.Namespace(out_dir=None, protect=[], log=None)
    server.configure(args)  # no SystemExit (same as the bridge)
    assert any("inside the protected folder" in r.getMessage() for r in caplog.records)


def test_log_inside_protected_folder_is_refused(tmp_path):
    guarded = tmp_path / "guarded"
    guarded.mkdir()
    env = dict(os.environ, MABIBRIDGE_PROJECT=str(write_project(tmp_path / "proj", f"protect = {guarded}\n")))
    env.pop("PYTHONPATH", None)
    r = subprocess.run([sys.executable, "-I", "-X", "utf8", str(ANALYSIS / "run_ear.py"), "--out-dir",
                        str(tmp_path / "out"), "--log", str(guarded / "ear.log")], capture_output=True, env=env,
                       timeout=120, stdin=subprocess.DEVNULL)
    assert r.returncode != 0 and b"--log is inside a protected folder" in r.stderr
    assert not (guarded / "ear.log").exists()


def test_no_private_layout_names_in_code():
    for f in (ANALYSIS / "mabiear").glob("*.py"):
        src = f.read_text(encoding="utf-8")
        for word in ("F:\\", "F:/"):
            assert word not in src, (f.name, word)


def test_run_ear_help_isolated():
    """run_ear.py works in isolated mode (-I: no PYTHONPATH, no cwd on sys.path) and prints the argparse help."""
    env = dict(os.environ)
    env.pop("PYTHONPATH", None)
    r = subprocess.run([sys.executable, "-I", "-X", "utf8", str(ANALYSIS / "run_ear.py"), "--help"],
                       capture_output=True, cwd=str(Path(sys.executable).parent), env=env, timeout=120)
    assert r.returncode == 0, r.stderr.decode("utf-8", "replace")
    assert b"--out-dir" in r.stdout
