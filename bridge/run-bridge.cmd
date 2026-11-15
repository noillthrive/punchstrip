@echo off
rem punchstrip: launcher of the mabiicco MCP bridge (stdio MCP server "mabiicco").
rem stdout is the MCP channel: this script writes its own messages to stderr only.
rem user.home is isolated under bridge\runtime\home so the real %USERPROFILE%\.mabiicco.config is never read or written.
rem The install folder must be writable (bridge\runtime\ and output\ are created inside it).
rem
rem The bridge contains no MabiIcco code and runs only on top of an installed MabiIcco:
rem   MABIICCO_APP (required) = the "app" folder of the MabiIcco install (1.5.12 is known to work); it supplies
rem   MabiIcco.jar, lib\flatlaf and lib\commons-compress. This folder supplies MabiIccoBridge.jar and lib\gson.
rem Other environment variables: JAVA_HOME (else java on PATH), MABI_DLS (Mabinogi DLS folder or file list),
rem MABIBRIDGE_OUT (absolute output base; renders go to <it>\render), MABIBRIDGE_PROJECT (project file or "none"),
rem MABIBRIDGE_SKIP_JAVA_CHECK=1. Paths containing "%" are not supported.
setlocal EnableExtensions DisableDelayedExpansion
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "JAR=%ROOT%\MabiIccoBridge.jar"
if not exist "%JAR%" goto nojar
set "HOMEDIR=%ROOT%\bridge\runtime\home"
if not exist "%HOMEDIR%" mkdir "%HOMEDIR%" >nul 2>&1

rem ---- Java 17 or newer
if defined JAVA_HOME goto javahome
where java >nul 2>&1
if errorlevel 1 goto nojava
set "JAVA_EXE=java"
goto javaver
:javahome
set "JAVA_EXE=%JAVA_HOME:"=%\bin\java.exe"
if not exist "%JAVA_EXE%" goto badjavahome
:javaver
if "%MABIBRIDGE_SKIP_JAVA_CHECK%"=="1" goto classpath
rem read "java -version" straight from the pipe (no temp file: two launchers may start in the same second).
rem "call" keeps the quoted java path intact inside for /f even when it contains spaces, ( ) or &.
set "JV="
for /f "tokens=1-3" %%a in ('call "%JAVA_EXE%" -version 2^>^&1') do if "%%b"=="version" if not defined JV set "JV=%%~c"
if not defined JV goto nojavaver
set "JMAJ=0"
set "JMIN=0"
if defined JV for /f "tokens=1,2 delims=.-+_" %%a in ("%JV%") do (set "JMAJ=%%a" & set "JMIN=%%b")
if "%JMAJ%"=="1" set "JMAJ=%JMIN%"
set "JMAJN=0"
set /a JMAJN=JMAJ+0 >nul 2>&1
if %JMAJN% LSS 17 goto oldjava

:classpath
if not defined MABIICCO_APP goto noapp
set "APP=%MABIICCO_APP:"=%"
if not defined APP goto noapp
if "%APP:~-1%"=="\" set "APP=%APP:~0,-1%"
if not exist "%APP%\MabiIcco.jar" goto badapp
if not exist "%APP%\lib\flatlaf\" goto badapp
if not exist "%APP%\lib\commons-compress\" goto badapp
set "TARGET=%APP%\MabiIcco.jar;%APP%\lib\flatlaf\*;%APP%\lib\commons-compress\*;%JAR%;%ROOT%\lib\gson\*"

"%JAVA_EXE%" --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED -Djava.awt.headless=true -Dmabiicco.test_mode -Dmabiicco.ticktable=tickTable.dat "-Duser.home=%HOMEDIR%" -Xmx1024m -cp "%TARGET%" mabibridge.Main %*
exit /b %ERRORLEVEL%

:nojar
>&2 echo [run-bridge] ERROR: the bridge jar was not found: "%JAR%"
>&2 echo [run-bridge]        Unpack the release zip again, or build the jar with Ant (see BUILD.md).
exit /b 3
:nojava
>&2 echo [run-bridge] ERROR: java was not found. Install a Java 17 or newer JDK/JRE (64-bit), then set JAVA_HOME or put java on PATH.
exit /b 4
:badjavahome
>&2 echo [run-bridge] ERROR: JAVA_HOME does not contain bin\java.exe: "%JAVA_HOME:"=%"
exit /b 4
:nojavaver
>&2 echo [run-bridge] ERROR: could not read the version from "%JAVA_EXE%" -version. Check the Java install,
>&2 echo [run-bridge]        or set MABIBRIDGE_SKIP_JAVA_CHECK=1 to skip this check.
exit /b 4
:oldjava
>&2 echo [run-bridge] ERROR: Java 17 or newer is required; "%JAVA_EXE%" reports version "%JV%". Install Java 17+ and set JAVA_HOME.
>&2 echo [run-bridge]        Set MABIBRIDGE_SKIP_JAVA_CHECK=1 to skip this check.
exit /b 4
:noapp
>&2 echo [run-bridge] ERROR: MabiIcco is required: MABIICCO_APP is not set.
goto needapp
:badapp
>&2 echo [run-bridge] ERROR: MabiIcco is required: MABIICCO_APP="%APP%" does not contain MabiIcco.jar, lib\flatlaf\ and lib\commons-compress\.
:needapp
>&2 echo [run-bridge]        This tool contains no MabiIcco code and works only with an installed MabiIcco (1.5.12 is known to work).
>&2 echo [run-bridge]        Get MabiIcco from its author: https://github.com/fourthline/mmlTools (Releases), install it, then set
>&2 echo [run-bridge]        MABIICCO_APP to its "app" folder, the folder that contains MabiIcco.jar and lib\, for example:
>&2 echo [run-bridge]          set "MABIICCO_APP=C:\Tools\MabiIcco-1.5.12\app"
exit /b 5
