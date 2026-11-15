# punchstrip 설치 순서

이 문서는 AI 에이전트가 Windows PC에서 위에서부터 차례로 실행하는 설치 순서다. 사람이 따라 할 때도 순서는 같다. 단계마다 명령, 기대하는 결과, 실패했을 때 할 일을 적었다.

**에이전트 규칙**

- 명령은 모두 **PowerShell**에서 실행한다. 셸이 bash뿐이면 여러 줄짜리 명령을 `.ps1` 파일로 저장해 `powershell -NoProfile -ExecutionPolicy Bypass -File <파일>`로 실행한다. `.ps1` 파일은 BOM이 있는 UTF-8로 저장한다. Windows PowerShell 5.1은 BOM 없는 파일을 CP949로 읽어서 파일 안의 한글 경로가 깨진다. bash에서 `cmd /c ...`를 직접 쓰면 `/c`가 경로 `C:/`로 바뀌어 등록이 깨진다.
- 프로그램을 설치하기 전에는 무엇을 설치하는지 사용자에게 알리고 허락을 받는다.
- 사용자에게는 스스로 찾을 수 없는 정보만 묻는다. 예: MabiIcco를 어디에 풀었는지.
- 이 문서의 `C:\mabi-mcp`는 설치 폴더의 기본값이다. 다른 폴더를 쓰면 모든 명령에서 바꾼다. `<app 폴더>`는 1번에서 찾은 값으로 바꾼다.

## 1. MabiIcco의 `app` 폴더 찾기

이 절차는 마비노기와 MabiIcco가 설치된 PC에서 시작한다. MabiIcco는 <https://github.com/fourthline/mmlTools> 의 Releases에서 받는 프로그램이고, 압축을 푼 폴더 안에 `MabiIcco.exe`와 `app\`이 있다. punchstrip에 필요한 것은 그 **`app` 폴더**다. 그 안에 `MabiIcco.jar`와 `lib\`가 있다.

```powershell
$roots = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('MyDocuments'), "$env:USERPROFILE\Downloads") + (Get-PSDrive -PSProvider FileSystem | ForEach-Object { $_.Root })
$roots | Where-Object { $_ -and (Test-Path $_) } | ForEach-Object { Get-ChildItem -Path $_ -Filter MabiIcco.jar -File -Recurse -Depth 5 -ErrorAction SilentlyContinue } | Where-Object { (Test-Path (Join-Path $_.DirectoryName 'lib\flatlaf')) -and (Test-Path (Join-Path $_.DirectoryName '..\MabiIcco.exe')) } | ForEach-Object { $_.DirectoryName } | Sort-Object -Unique
```

- **기대 결과**: `...\MabiIcco-1.5.12\app` 같은 폴더가 한 줄 이상 나온다. 드라이브 크기에 따라 수십 초 걸린다.
- **여러 개 나오면**: 버전 번호가 가장 높은 것을 고르고 사용자에게 확인한다. 확인한 버전은 1.5.12이고, 1.5.11에서도 돈다는 사용자 보고가 있다.
- **검색 결과가 비면**: 사용자에게 MabiIcco를 푼 폴더를 묻는다. 설치 전이라면 위 링크에서 받아 풀도록 안내하고, 다 되면 다시 이 단계부터 한다.

## 2. Java 17 이상

```powershell
java -version
```

- **기대 결과**: 첫 줄에 17 이상의 버전 번호. 예: `openjdk version "17.0.19" 2026-04-21 LTS`.
- **`java`가 없거나 17보다 낮으면** 사용자의 허락을 받아 설치한다.

```powershell
winget install --id Microsoft.OpenJDK.17 --exact --accept-package-agreements --accept-source-agreements
```

- `Microsoft.OpenJDK.17` 대신 `EclipseAdoptium.Temurin.17.JDK`도 된다. 설치한 뒤에는 새 셸에서 바뀐 PATH가 보인다. 지금 셸에서 이어 가려면 PATH를 다시 읽고 확인한다.

```powershell
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
java -version
```

- **버전이 계속 17 미만이면**: 예전 Java가 PATH 앞쪽에 있는 경우다. 새로 설치한 JDK 폴더를 찾아 `JAVA_HOME`으로 지정한다. 실행기는 `JAVA_HOME`이 있으면 그 Java를 쓴다.

```powershell
Get-ChildItem 'C:\Program Files\*\jdk-17*\bin\java.exe' -ErrorAction SilentlyContinue | ForEach-Object { $_.Directory.Parent.FullName }
setx JAVA_HOME "<위에서 나온 JDK 폴더>"
$env:JAVA_HOME = '<위에서 나온 JDK 폴더>'
```

- **`winget`이 없으면**: 사용자에게 <https://adoptium.net/> 에서 Java 17 설치 프로그램을 받아 설치해 달라고 하고, 끝나면 이 단계를 다시 한다.

## 3. Python 3.11과 `py` 런처

```powershell
py -3.11 --version
```

- **기대 결과**: `Python 3.11.9`처럼 3.11로 시작하는 버전 번호. 3.12 같은 다른 버전이 함께 깔려 있어도 된다. mabi-ear는 3.11만 쓴다.
- **Python 3.11 설치**: 위 명령이 버전 번호 대신 오류를 내면 사용자의 허락을 받아 설치한다.

```powershell
winget install --id Python.Python.3.11 --exact --accept-package-agreements --accept-source-agreements
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
py -3.11 --version
```

- **`py` 런처 설치**: 위 확인에서 오류가 계속 나면 사용자에게 <https://www.python.org/downloads/windows/> 의 Python 3.11 설치 프로그램을 "py launcher"를 켠 채로 설치해 달라고 한다.

## 4. 최신 릴리스 받아 풀기

설치 폴더는 **쓰기 가능하고 영문·숫자로만 된 경로**여야 한다. 실행기가 그 안에 `bridge\runtime\`, `output\`, `analysis\.venv\`, `analysis\.cache\`, `analysis\mabiear\__pycache__\`를 만든다. 한글·일본어가 섞인 경로는 Windows 자바 실행기가 명령줄을 깨뜨리는 일이 있어 피한다. `%`가 든 경로도 피한다. 기본값은 `C:\mabi-mcp`다.

```powershell
& {
  $dest = 'C:\mabi-mcp'
  $ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $rel = Invoke-RestMethod -UseBasicParsing -Headers @{ 'User-Agent' = 'punchstrip-install' } -Uri 'https://api.github.com/repos/noillthrive/punchstrip/releases/latest'
  $asset = $rel.assets | Where-Object { $_.name -like 'punchstrip-*-win.zip' } | Select-Object -First 1
  if (-not $asset) { throw 'The latest release has no punchstrip-*-win.zip' }
  $zip = Join-Path $env:TEMP $asset.name
  Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $dest -Force
  "$($rel.tag_name) $($asset.name) -> $dest"
  Test-Path (Join-Path $dest 'smoke-test.cmd')
}
```

`& { ... }`로 감싸서 `$ErrorActionPreference = 'Stop'`이 이 블록 안에서만 적용된다.

- **기대 결과**: `v0.7.1 punchstrip-0.7.1-win.zip -> C:\mabi-mcp`처럼 버전과 폴더가 나오고 마지막 줄이 `True`. 폴더 맨 위에 `MabiIccoBridge.jar`, `smoke-test.cmd`, `bridge\`, `analysis\`, `lib\`가 생긴다.
- **`gh`가 있으면** 이렇게 받아도 된다.

```powershell
gh release download -R noillthrive/punchstrip --pattern "punchstrip-*-win.zip" --dir $env:TEMP --clobber
Expand-Archive -Path (Get-ChildItem "$env:TEMP\punchstrip-*-win.zip" | Sort-Object LastWriteTime | Select-Object -Last 1).FullName -DestinationPath 'C:\mabi-mcp' -Force
```

- **다운로드 실패**: 인터넷 연결과 GitHub 접속을 확인한다. 사용자에게 <https://github.com/noillthrive/punchstrip/releases> 에서 zip을 받아 달라고 하고 `Expand-Archive`만 실행한다.
- **새 버전으로 바꿀 때**: MCP 클라이언트를 먼저 끄고 같은 명령으로 덮어 푼다. `output\`과 `analysis\.venv\`는 그대로 남는다.

## 5. 환경 변수

| 변수 | 뜻 | 예 |
|---|---|---|
| `MABIICCO_APP` | **필수.** 1번에서 찾은 MabiIcco의 `app` 폴더 | `C:\Tools\MabiIcco-1.5.12\app` |
| `MABI_DLS` | 6번에서 게임 음원을 못 찾을 때만. 마비노기 `mp3` 폴더(또는 `.dls` 파일을 쉼표로). 없으면 `C:\Nexon\Mabinogi\mp3` 등 흔한 위치를 찾아본다 | `D:\Nexon\Mabinogi\mp3` |

```powershell
setx MABIICCO_APP "<app 폴더>"
$env:MABIICCO_APP = '<app 폴더>'
```

- `setx`는 사용자 환경 변수에 영구로 넣는다. 새로 연 셸과 새로 시작한 프로그램부터 보인다. 지금 셸에는 두 번째 줄로 넣는다.
- **기대 결과**: `setx`가 성공 메시지를 한 줄 찍는다.
- 다른 선택 변수인 `JAVA_HOME`, `MABIBRIDGE_OUT`, `MABIBRIDGE_PROJECT`는 맨 아래 선택 설정 부록에 있다.

## 6. 스모크 시험으로 설치 확인

```powershell
$env:MABIICCO_APP = '<app 폴더>'
cmd /c C:\mabi-mcp\smoke-test.cmd
```

`--version`, 클래스·자료·DLS 찾기와 1초 메모리 렌더를 확인하는 `--selfcheck`, 악보 만들기 → 음 넣기 → `render_wav` → 저장으로 이어지는 MCP 대화 한 번을 차례로 돌린다. 기록과 렌더 WAV는 `C:\mabi-mcp\output\smoke\`에 남는다.

- **기대 결과**: 마지막 줄이 `PASS  - logs in output\smoke`다. 성공과 실패는 이 마지막 줄로 판단한다. mabiicco 서버의 도구 수는 `[3/3]` 표의 3번 줄에 있고, 그 줄에 `39`가 들어 있다. PowerShell이 받은 출력에서 한글이 깨져 보이면 UTF-8 기록 파일 `C:\mabi-mcp\output\smoke\session.log`에서 `| 3 |`로 시작하는 줄을 읽는다.
- **`[run-bridge] ERROR: MabiIcco is required: ...`**: `MABIICCO_APP`이 비었거나 그 폴더에 `MabiIcco.jar`·`lib\flatlaf\`·`lib\commons-compress\`가 없다. 1번으로 돌아가 폴더를 다시 확인한다.
- **`[run-bridge] ERROR: java was not found`, `Java 17 or newer is required` 또는 `[run-bridge] ERROR: JAVA_HOME does not contain bin\java.exe`**: 2번으로 돌아간다. 마지막 메시지는 `JAVA_HOME`이 JDK 폴더가 아닌 곳을 가리킬 때 나온다.
- **`[selfcheck] FAIL render: no Mabinogi DLS file found`**: 게임 음원을 못 찾은 것이다. 마비노기의 `mp3` 폴더를 찾아 `MABI_DLS`로 넣고 다시 돌린다.

```powershell
Get-PSDrive -PSProvider FileSystem | ForEach-Object { Get-ChildItem -Path $_.Root -Filter MSXspirit01.dls -File -Recurse -Depth 6 -ErrorAction SilentlyContinue } | ForEach-Object { $_.DirectoryName } | Sort-Object -Unique
setx MABI_DLS "<mp3 폴더>"
$env:MABI_DLS = '<mp3 폴더>'
cmd /c C:\mabi-mcp\smoke-test.cmd
```

- **검색 결과가 비면**: 사용자에게 마비노기 설치 폴더를 묻는다. 음원을 빼고 나머지만 확인하려면 `cmd /c C:\mabi-mcp\bridge\run-bridge.cmd --selfcheck --no-dls`를 쓴다. 이 설정에서는 소리 렌더를 제외한 악보 편집과 저장이 된다.
- **`the bridge jar was not found`**: 4번의 압축 풀기를 다시 한다.

## 7. mabi-ear 첫 실행

처음 한 번은 터미널에서 띄워 파이썬 환경을 만들어 둔다. MCP 클라이언트가 처음 띄우면 이 준비가 길어 시간 초과로 끊기기도 한다.

```powershell
cmd /c C:\mabi-mcp\analysis\run-ear.cmd --help
```

- **걸리는 시간과 용량**: 인터넷으로 약 120 MB를 받고 디스크를 약 620 MB 쓴다. 1분 안팎이며 회선에 따라 몇 분 걸린다.
- **기대 결과**: `[run-ear] creating a Python 3.11 virtual environment`, `[run-ear] installing the pinned packages ...` 뒤에 `usage: mabiear.server [-h] [--out-dir OUT_DIR] [--protect PROTECT] [--log LOG]`가 나온다. 두 번째부터는 사용법만 바로 나온다.
- **도구 수 확인**: 아래 명령의 `[OK] tools/list` 줄에 `18개`가 나온다. mabi-ear 서버의 도구 수가 이것이다.

```powershell
C:\mabi-mcp\analysis\.venv\Scripts\python.exe -I -X utf8 C:\mabi-mcp\analysis\scripts\pipe_check.py C:\mabi-mcp\analysis\run-ear.cmd --out C:\mabi-mcp\output\ear_tools.json
```

- **`[run-ear] ERROR: Python 3.11 was not found`**: 종료 코드 9다. 3번으로 돌아간다.
- **`[run-ear] ERROR: pip install -r requirements.txt failed`**: 대개 인터넷 문제다. 원인을 고치고 같은 명령을 다시 실행한다. 중간에 끊긴 환경은 다음 실행 때 다시 만든다.
- 다 깔린 뒤에는 약 120 MB인 `C:\mabi-mcp\analysis\.cache\pip`을 지워도 된다. Python 3.11 환경에 `requirements.txt`를 깔아 두었다면 `MABIEAR_PYTHON=<그 python.exe>`로 `.venv` 없이 그 환경을 쓴다.

## 8. MCP 서버 두 개 등록

### 클로드 코드(Claude Code)

모든 폴더에서 쓰도록 사용자 범위인 `--scope user`로 등록한다. 지금 프로젝트에서만 쓰려면 `--scope user`를 뺀다.

```powershell
claude mcp add --transport stdio --scope user mabiicco --env "MABIICCO_APP=<app 폴더>" '--' cmd /c C:\mabi-mcp\bridge\run-bridge.cmd
claude mcp add --transport stdio --scope user mabi-ear '--' cmd /c C:\mabi-mcp\analysis\run-ear.cmd
claude mcp list
```

- **서버 이름 `mabiicco`는 `--env`보다 앞에** 쓴다. `--env`는 값을 여러 개 받아서, 이름이 뒤에 오면 이름까지 환경 변수로 읽고 "Invalid environment variable format"으로 실패한다.
- `'--'`는 PowerShell에서 따옴표로 감싼다. `claude`가 PowerShell 스크립트인 `claude.ps1`로 설치된 경우 따옴표 없는 `--`는 PowerShell이 지운다. cmd에서는 그냥 `--`로 쓴다.
- 6번에서 `MABI_DLS`를 넣었다면 첫 줄의 `--env "MABIICCO_APP=<app 폴더>"` 뒤에 `--env "MABI_DLS=<mp3 폴더>"`를 더한다. `MABIEAR_PYTHON`을 쓰려면 둘째 줄에 같은 식으로 `mabi-ear --env "MABIEAR_PYTHON=<그 python.exe>" '--' cmd /c ...`.
- **기대 결과**: `claude mcp list`에 `mabiicco`와 `mabi-ear`가 나오고 둘 다 `Connected`로 표시된다. 클로드 코드를 다시 시작한 뒤 `/mcp`에서 도구 39개와 18개가 보인다.
- **이름이 이미 있다고 나오면**: 예전 등록을 `claude mcp remove mabiicco --scope user`로 지운다. `mabi-ear`도 같은 방식으로 지운 뒤 다시 등록한다.
- **`Failed to connect`가 나오면**: 6번과 7번을 다시 확인한다. Java나 Python을 이번에 새로 설치했다면 클로드 코드를 다시 시작한 뒤에 연결된다.

### 다른 MCP 클라이언트

시험한 클라이언트는 클로드 코드뿐이다. 다른 MCP 클라이언트에는 stdio 서버 두 개를 이 명령으로 등록한다.

- mabiicco: `cmd /c C:\mabi-mcp\bridge\run-bridge.cmd`, 환경 변수 `MABIICCO_APP`과 필요할 때 `MABI_DLS`
- mabi-ear: `cmd /c C:\mabi-mcp\analysis\run-ear.cmd`

설정 파일에 `mcpServers`를 쓰는 클라이언트는 아래처럼 등록한다. 경로의 역슬래시는 JSON에서 두 번 쓴다.

```json
{
  "mcpServers": {
    "mabiicco": {
      "command": "cmd",
      "args": ["/c", "C:\\mabi-mcp\\bridge\\run-bridge.cmd"],
      "env": { "MABIICCO_APP": "C:\\Tools\\MabiIcco-1.5.12\\app" }
    },
    "mabi-ear": {
      "command": "cmd",
      "args": ["/c", "C:\\mabi-mcp\\analysis\\run-ear.cmd"]
    }
  }
}
```

## 9. 마무리

- 사용자에게 MCP 클라이언트를 **다시 시작**하라고 알린다. 클로드 코드에서는 세션을 다시 시작한다. 그러면 새로 넣은 환경 변수와 새로 설치한 Java·Python이 반영된다.
- 사용자에게 알릴 것: 6번 마지막 줄인 `PASS` 또는 `FAIL`, 두 서버의 도구 수인 mabiicco 39개와 mabi-ear 18개, 설치 폴더, `MABIICCO_APP`과 `MABI_DLS`의 값.
- 처음 시켜 볼 것: "mabiicco로 새 악보를 만들고 피아노 트랙에 도레미파를 넣고 render_wav로 렌더한 뒤, mabi-ear audio_info로 그 WAV를 보고, `C:\mabi-mcp\output\첫곡.mmi`로 저장해 줘."

### 지우기

먼저 두 서버의 등록을 지운다.

```powershell
claude mcp remove mabiicco --scope user
claude mcp remove mabi-ear --scope user
```

그다음 MCP 클라이언트를 모두 닫아 두 서버를 멈춘다. 서버가 떠 있으면 폴더 안 파일이 잠겨 있어서 지우기가 중간에 실패한다. 이 작업을 클로드 코드 안에서 하고 있다면 사용자에게 클로드 코드를 닫고 새 PowerShell 창에서 아래 명령을 실행해 달라고 한다.

```powershell
Remove-Item -Recurse -Force C:\mabi-mcp
Remove-Item "$env:TEMP\punchstrip-*-win.zip"
```

punchstrip은 사용자 홈에 아무것도 쓰지 않고 MabiIcco의 설정 파일에도 손대지 않는다. 기본으로 쓰는 파일은 모두 설치 폴더 안에 있어서 폴더를 지우면 끝난다. 다른 곳에 둔 출력, 곧 `MABIBRIDGE_OUT`과 프로젝트 파일이 정한 폴더, 입력 파일 옆에 쓴 `폰용\`·`변형\` 폴더는 그대로 남는다. `setx`로 넣은 변수는 Windows 설정의 "환경 변수"에서 지운다.

## 부록: 선택 설정

| 변수 | 뜻 | 예 |
|---|---|---|
| `JAVA_HOME` | Java 17+ 설치 폴더(없으면 PATH의 `java`) | `C:\Program Files\Java\jdk-17` |
| `MABIBRIDGE_OUT` | 선택. 출력 바탕 폴더(**절대 경로**, 상대 경로면 두 서버 모두 시작하지 않는다 — 브리지 종료 코드 2, mabi-ear는 `[run-ear] ERROR` 줄과 종료 코드 1). 렌더는 `<여기>\render`, 분석은 `<여기>\analysis`. 없으면 프로젝트 파일(아래)의 값, 그것도 없으면 `C:\mabi-mcp\output\render`·`output\analysis` | `D:\mabi-out` |
| `MABIBRIDGE_PROJECT` | 선택. 프로젝트 파일의 경로(아래), 또는 `none`(찾지 않음) | `D:\곡\mabibridge-project.properties` |

`MABIBRIDGE_OUT`을 줘도 설치 폴더 안의 `bridge\runtime\`, `analysis\.venv\`, `analysis\.cache\`는 그 자리에 남는다.

### 프로젝트 파일

작업 폴더마다 기본 출력 위치와 보호 폴더를 정하려면 UTF-8로 `mabibridge-project.properties`를 둔다. 두 서버는 `MABIBRIDGE_PROJECT`, 서버 자신의 jar·패키지 폴더에서 위로 올라가며 처음 만나는 파일, 작업 폴더에서 위로 올라가며 처음 만나는 파일 순으로 찾는다. 파일이 없으면 위의 기본값을 쓰고, 보호 폴더는 `--protect`로 준 것뿐이다. 서버 시작 로그와 `--selfcheck`가 쓰고 있는 파일을 찍는다.

```properties
# key = value, 경로는 이 파일 기준 상대 또는 절대, 역슬래시 그대로
render.dir   = 렌더
analysis.dir = 분석
protect      = 원본 악보 ; 원곡
protect.1    = D:\받은 악보
```

`protect`에는 `;`로 구분해 여러 폴더를 적고, `protect.<아무 이름>`에는 한 줄에 하나씩 적는다. 두 서버는 이 폴더 안에 저장·렌더·분석 파일·스냅샷·로그를 쓰지 않고, 그런 요청은 `PROTECTED_PATH`로 거절한다. 스냅샷·로그 폴더가 보호 폴더 안이면 브리지가 시작을 거부한다. 기본 출력 폴더가 보호 폴더 안이면 두 서버 모두 경고와 함께 계속 돌며, `out_dir`를 지정한 호출만 받는다.
