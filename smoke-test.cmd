@echo off
rem punchstrip: smoke test for the mabiicco MCP bridge (run it from a normal cmd window, not from Claude).
rem   1. bridge\run-bridge.cmd --version
rem   2. bridge\run-bridge.cmd --selfcheck          (classes, resources, DLS files, a 1-second render in memory)
rem   3. McpSmoke: starts bridge\run-bridge.cmd as an MCP server and talks to it over stdio
rem      (initialize, tools/list, project_new, track_add, score_put_notes, render_wav, project_save_as, ...),
rem      writing into output\smoke\<time>\
rem Uses the same environment as the server: MABIICCO_APP, MABI_DLS, JAVA_HOME. Prints PASS or FAIL at the end.
rem The install folder must be writable. Paths containing "%" are not supported.
setlocal EnableExtensions DisableDelayedExpansion
for %%I in ("%~dp0.") do set "ROOT=%%~fI"
set "OUT=%ROOT%\output\smoke"
if not exist "%OUT%" mkdir "%OUT%" >nul 2>&1
set "FAILED=0"
rem show the UTF-8 logs readably, restore the console code page at the end
set "OLDCP="
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLDCP=%%c"
if defined OLDCP set "OLDCP=%OLDCP: =%"
if defined OLDCP set "OLDCP=%OLDCP:.=%"
chcp 65001 >nul

echo [1/3] run-bridge.cmd --version
call "%ROOT%\bridge\run-bridge.cmd" --version
if errorlevel 1 goto fail1
goto step2
:fail1
set "FAILED=1"
echo       FAIL: --version did not run, see the [run-bridge] message above
goto done

:step2
echo.
echo [2/3] run-bridge.cmd --selfcheck, full log in output\smoke\selfcheck.log
call "%ROOT%\bridge\run-bridge.cmd" --selfcheck > "%OUT%\selfcheck.log" 2>&1
set "SC=%ERRORLEVEL%"
findstr /b /c:"[selfcheck] OK" /c:"[selfcheck] FAIL" /c:"[selfcheck] SKIP" /c:"[selfcheck] WARN" /c:"[selfcheck] PASS" /c:"[selfcheck]      hint" /c:"[selfcheck] info DLS" /c:"[selfcheck] info project" /c:"[run-bridge]" "%OUT%\selfcheck.log"
if not "%SC%"=="0" set "FAILED=1"

echo.
echo [3/3] scripted MCP session with a render, McpSmoke, output in output\smoke\^<time^>
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME:"=%\bin\java.exe"
"%JAVA_EXE%" -Dmabiicco.test_mode "-Duser.home=%ROOT%\bridge\runtime\home" "-Dmabibridge.smoke.out=%OUT%" -cp "%ROOT%\MabiIccoBridge.jar;%ROOT%\lib\gson\*" mabibridge.mcp.McpSmoke > "%OUT%\session.log" 2>&1
set "MS=%ERRORLEVEL%"
findstr /c:"| " /c:"# " "%OUT%\session.log"
if not "%MS%"=="0" set "FAILED=1"

:done
echo.
if defined OLDCP chcp %OLDCP% >nul
if "%FAILED%"=="0" goto pass
echo FAIL  - logs in output\smoke; see README.md (install check)
exit /b 1
:pass
echo PASS  - logs in output\smoke
exit /b 0
