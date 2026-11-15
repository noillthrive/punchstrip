@echo off
rem mabi-ear MCP server launcher (Python, stdio).
rem stdout is the MCP channel: nothing but JSON-RPC may be written there, so every message below goes to stderr.
rem All caches/temp go under analysis\.cache so nothing is written to the user home. The folder must be writable.
rem
rem Python: analysis\.venv, created on the first run with "py -3.11" and filled with "pip install -r requirements.txt"
rem (about 120 MB download, about 620 MB on disk, about a minute; internet needed). An unfinished or broken venv is
rem rebuilt on the next start. Set MABIEAR_PYTHON to the python.exe of an existing Python 3.11 environment that already
rem has requirements.txt installed to skip the venv. Paths containing "%" are not supported.
setlocal EnableExtensions DisableDelayedExpansion
set "HERE=%~dp0"
set "CACHE=%HERE%.cache"
if not exist "%CACHE%\tmp" mkdir "%CACHE%\tmp" >nul 2>&1
set "PIP_CACHE_DIR=%CACHE%\pip"
set "PIP_DISABLE_PIP_VERSION_CHECK=1"
set "NUMBA_CACHE_DIR=%CACHE%\numba"
set "LIBROSA_CACHE_DIR=%CACHE%\librosa"
set "LIBROSA_CACHE_LEVEL=1"
set "LIBROSA_DATA_DIR=%CACHE%\librosa_data"
set "MPLCONFIGDIR=%CACHE%\mpl"
set "TMP=%CACHE%\tmp"
set "TEMP=%CACHE%\tmp"
set "TMPDIR=%CACHE%\tmp"
set "PYTHONUTF8=1"
rem one BLAS thread: avoids ~500MB of per-thread buffers on 16 cores (analysis barely uses BLAS)
set "OPENBLAS_NUM_THREADS=1"
set "OMP_NUM_THREADS=1"
set "MKL_NUM_THREADS=1"

if defined MABIEAR_PYTHON goto custom
set "VENV=%HERE%.venv"
set "PY=%HERE%.venv\Scripts\python.exe"
set "STAMP=%HERE%.venv\mabiear-deps.ok"
if exist "%STAMP%" if exist "%PY%" goto run

rem ---- first run, or an install that did not finish: (re)build the venv, then install the pinned packages
py -3.11 -c "import sys" >nul 2>&1
if errorlevel 1 goto nopy
if not exist "%PY%" goto mkvenv
call :checkvenv
if not errorlevel 1 goto havevenv
>&2 echo [run-ear] the existing virtual environment is incomplete, rebuilding it: "%VENV%"
:mkvenv
if exist "%VENV%" rmdir /s /q "%VENV%" >nul 2>&1
if exist "%VENV%" goto venvfail
>&2 echo [run-ear] creating a Python 3.11 virtual environment: "%VENV%"
py -3.11 -m venv "%VENV%" 1>&2
if errorlevel 1 goto venvfail
if not exist "%PY%" goto venvfail
call :checkvenv
if errorlevel 1 goto venvfail
:havevenv
rem venv-level pip config: never write the version-check file under the user home
>"%VENV%\pip.ini" echo [global]
>>"%VENV%\pip.ini" echo disable-pip-version-check = true
>&2 echo [run-ear] installing the pinned packages from requirements.txt (about 120 MB download, about a minute) ...
"%PY%" -m pip install -r "%HERE%requirements.txt" 1>&2
if errorlevel 1 goto pipfail
>"%STAMP%" echo ok
goto run

:custom
set "PY=%MABIEAR_PYTHON:"=%"
if not exist "%PY%" goto custombad

:run
"%PY%" -I -X utf8 "%HERE%run_ear.py" %*
exit /b %ERRORLEVEL%

rem ---- the venv's python must be 3.11 and able to import pip (exit 0), otherwise exit 1
:checkvenv
rem read the answer from the pipe (no shared temp file); "call" keeps the quoted path intact
set "VOK="
for /f "delims=" %%v in ('call "%PY%" -c "import sys, pip; print('mabiear-venv-ok', *sys.version_info[:2])" 2^>nul') do if "%%v"=="mabiear-venv-ok 3 11" set "VOK=1"
if defined VOK exit /b 0
exit /b 1

:nopy
>&2 echo [run-ear] ERROR: Python 3.11 was not found, "py -3.11" failed. Install Python 3.11 for Windows from python.org
>&2 echo [run-ear]        with the "py launcher" option, then run this again. Other versions do not work: the packages are pinned for 3.11.
exit /b 9
:venvfail
>&2 echo [run-ear] ERROR: could not create a working virtual environment: "%VENV%"
>&2 echo [run-ear]        Check that the folder is writable and that "py -3.11 -m venv" works; the next start tries again.
exit /b 1
:pipfail
>&2 echo [run-ear] ERROR: pip install -r requirements.txt failed, see the messages above. Internet is needed on the first run.
>&2 echo [run-ear]        Fix the cause and run again; the install is retried until it finishes.
exit /b 1
:custombad
>&2 echo [run-ear] ERROR: MABIEAR_PYTHON points to a file that does not exist: "%PY%"
exit /b 9
