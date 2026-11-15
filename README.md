# 펀치스트립(punchstrip) — 마비노기 MML MCP Bridge

AI 모델이 **MabiIcco**(마비노기 MML 편집기)를 쓸 수 있게 하는 MCP 서버 두 개다.

- **mabiicco** (Java, 도구 39개) — MabiIcco를 창 없이 움직이는 **브리지**. 악보(.mmi) 만들기·열기·저장, 트랙·음 편집, MML 글자 수·랭크 한도 확인, 참고 MIDI 읽기와 비교, 오프라인 WAV 렌더, 합주용 마무리(조율 시그니처·끝 맞춤).
- **mabi-ear** (Python, 도구 18개) — 렌더한 소리와 원곡 녹음을 비교하는 **귀**. 정렬, 크로마, 온셋, 풍성함, 밸런스, 보고서, 스템에서 타격·음 뽑기, 듣기용 발췌, 악보 리드의 음 단위 원곡 대조, 노래 전사와의 일치.

판: punchstrip 0.7.0 = 브리지 0.7.0 + mabi-ear 0.5.0.

> **MabiIcco가 꼭 있어야 한다.** punchstrip에는 MabiIcco의 코드가 한 줄도 들어 있지 않다. 설치된 MabiIcco의 엔진(`MabiIcco.jar`)을 불러 쓰는 도구라서 **MabiIcco가 설치된 곳에서만 돈다.** MabiIcco는 원 제작자 たんらる 님의 프로그램이고 공식 배포처는 <https://github.com/fourthline/mmlTools> 이다.

## English summary

**punchstrip** lets an AI model use **MabiIcco**, the MML editor for the game Mabinogi, through two MCP servers: **mabiicco** (Java, 39 tools: headless score editing, MML size and rank checks, reference-MIDI comparison, offline WAV rendering, ensemble finishing with a tuning signature and silent end notes) and **mabi-ear** (Python 3.11, 18 tools: aligning and comparing renders with the original recording, stem extraction helpers, listening excerpts, a note-by-note check of the score's lead, agreement with a singing transcription).

- **It contains no MabiIcco code and requires an installed MabiIcco** (1.5.12 is known to work; 1.5.11 was reported working by a tester). Get MabiIcco from its author: <https://github.com/fourthline/mmlTools>. Without it the bridge stops with a message saying so.
- **Requirements:** Windows 10/11 x64, Java 17+, Python 3.11 with the `py` launcher, MabiIcco, Mabinogi installed (for the `MSXspirit0*.dls` sound banks), an MCP client (tested with Claude Code).
- **Install:** unzip `punchstrip-0.7.0-win.zip` to a writable ASCII path such as `C:\mabi-mcp`; set `MABIICCO_APP` to MabiIcco's `app` folder and `MABI_DLS` to Mabinogi's `mp3` folder; run `smoke-test.cmd` (prints PASS/FAIL); run `analysis\run-ear.cmd --help` once; register `bridge\run-bridge.cmd` and `analysis\run-ear.cmd` as stdio MCP servers.
- **What it is for:** it automates roughly the mechanical 80 % of arranging (drafting from MIDI or stems, counting and fitting characters, transposing between instruments, rendering, comparing) so that arrangers can spend their time on the part that needs ears. The final judgement is a person listening; it does not replace arrangers.
- **Signature:** `score_finalize` (and only it) adds a short lead-in "B–D–G–E" that doubles as the ensemble tuning passage, plus silent end notes so that all players finish together. Skip it with `signature: false`. It marks scores made with this tool; if your score is derived from someone else's arrangement, please credit the original arranger.
- **License:** MIT (`LICENSE`), with notes on the three files that follow MabiIcco's behaviour. MabiIcco, its binaries and the game's sound files are not part of this repository.

## 무엇을 위한 도구인가

편곡에는 손이 많이 가지만 귀가 필요 없는 일이 많다. MIDI나 스템에서 초안 잡기, 글자 수 세고 한도에 맞추기, 악기 사이 옮겨 적기(악기마다 실제로 울리는 옥타브가 다르다), 렌더해서 원곡과 비교하기. punchstrip은 이런 **기계적인 일 대략 80 %**를 AI가 대신 하게 해서, 편곡자가 **귀가 필요한 나머지**에 시간을 쓰게 하려는 도구다. 도구가 내는 수치는 "원곡과 닮았나"이지 "좋게 들리나"가 아니다. **마지막 판단은 사람이 듣고 한다.** 이 도구는 편곡자를 대신하지 않는다.

## 설치 (처음 하는 사람용)

차례대로 한다. 1~4는 punchstrip과 상관없이 먼저 갖춰 둘 것들이다.

### 1. 마비노기 설치

소리 렌더에 게임의 악기 음원 `mp3\MSXspirit01.dls`~`MSXspirit05.dls`가 필요하다(보통 `C:\Nexon\Mabinogi\mp3`). 악보 편집·저장만 할 거라면 음원 없이도 된다.

### 2. MabiIcco 설치와 DLS 연결

1. <https://github.com/fourthline/mmlTools> 의 Releases에서 MabiIcco를 받아 설치한다(확인한 판: **1.5.12**. 1.5.11에서도 된다는 사용자 보고가 있다. 다른 판은 확인하지 않았다).
2. MabiIcco를 한 번 실행해 **설정에서 마비노기의 DLS 음원 파일을 연결**하고, MabiIcco에서 소리가 나는지 확인한다(MabiIcco 사용법은 원작자 안내를 따른다).
3. 설치 폴더 안의 **`app` 폴더**(그 안에 `MabiIcco.jar`와 `lib\`가 있는 폴더) 위치를 적어 둔다. 예: `C:\Tools\MabiIcco-1.5.12\app`.

punchstrip은 MabiIcco의 설정 파일을 읽지도 쓰지도 않는다(그래서 사용자의 MabiIcco 설정은 그대로 남는다). DLS 위치는 아래 `MABI_DLS`로 따로 알려 주거나, 흔한 위치(`C:\Nexon\Mabinogi\mp3` 등)라면 스스로 찾는다.

### 3. Java 17 이상

JDK 또는 JRE 17 이상(64비트)을 설치하고 `JAVA_HOME`을 설정하거나 `java`가 PATH에 있게 한다. MabiIcco 설치판의 `runtime\`에는 `java.exe`가 없어서 따로 설치해야 한다. 확인한 판: Java 17.0.19. Java 21 이상은 시험하지 않았다.

### 4. Python 3.11 (mabi-ear용)

python.org의 Python 3.11 설치판("py launcher" 포함). mabi-ear 패키지 버전이 3.11용으로 고정되어 있다. 처음 실행할 때 인터넷으로 약 120 MB를 받고, 디스크는 약 620 MB를 쓴다(1분 안팎). 확인한 판: 3.11.9.

### 5. punchstrip 압축 풀기

`punchstrip-0.7.0-win.zip`을 예를 들어 `C:\mabi-mcp`에 푼다.

- **쓰기 가능한 폴더**여야 한다(실행기가 그 안에 `bridge\runtime\`, `output\`, `analysis\.venv\`, `analysis\.cache\`, `analysis\mabiear\__pycache__\`를 만든다. `MABIBRIDGE_OUT`을 줘도 이것들은 옮겨지지 않는다).
- **영문·숫자만 있는 경로**를 권한다(한글·일본어가 섞인 경로는 Windows 자바 실행기가 명령줄을 깨뜨릴 수 있다). 경로에 `%`는 쓰지 않는다.
- 지울 때는 폴더째 지우면 된다(사용자 홈에는 아무것도 쓰지 않는다).

```
C:\mabi-mcp\
  MabiIccoBridge.jar        브리지 클래스만 (MabiIcco 엔진은 들어 있지 않음)
  lib\gson\                 Gson 2.14.0 + LICENSE
  bridge\run-bridge.cmd     mabiicco 서버 실행기 (bridge\README.md = 도구 설명서)
  analysis\                 mabi-ear (파이썬 소스, requirements.txt, run-ear.cmd, README.md = 도구 설명서)
  smoke-test.cmd            설치 확인
  README.md  BUILD.md  LICENSE  NOTICE
```

저장소에서 직접 빌드하려면 `BUILD.md`(JDK 17 + Ant 1.10, `ant dist -Dmabiicco.app=<MabiIcco의 app 폴더>`).

### 6. 환경 변수

| 변수 | 뜻 | 예 |
|---|---|---|
| `MABIICCO_APP` | **필수.** 설치된 MabiIcco의 `app` 폴더(그 안에 `MabiIcco.jar`와 `lib\`가 있는 폴더) | `C:\Tools\MabiIcco-1.5.12\app` |
| `MABI_DLS` | 마비노기 `mp3` 폴더(또는 `.dls` 파일을 쉼표로). 없으면 `C:\Nexon\Mabinogi\mp3` 등 흔한 위치를 찾아본다 | `D:\Nexon\Mabinogi\mp3` |
| `JAVA_HOME` | Java 17+ 설치 폴더(없으면 PATH의 `java`) | `C:\Program Files\Java\jdk-17` |
| `MABIBRIDGE_OUT` | 선택. 출력 바탕 폴더(**절대 경로**, 상대 경로면 두 서버 모두 시작하지 않는다 — 브리지 종료 코드 2, mabi-ear는 `[run-ear] ERROR` 줄과 종료 코드 1). 렌더는 `<여기>\render`, 분석은 `<여기>\analysis`. 없으면 프로젝트 파일(아래)의 값, 그것도 없으면 `C:\mabi-mcp\output\render`·`output\analysis` | `D:\mabi-out` |
| `MABIBRIDGE_PROJECT` | 선택. 프로젝트 파일의 경로(아래), 또는 `none`(찾지 않음) | `D:\곡\mabibridge-project.properties` |

cmd에서 영구로 넣기(새 창부터 적용):

```bat
setx MABIICCO_APP "C:\Tools\MabiIcco-1.5.12\app"
setx MABI_DLS "C:\Nexon\Mabinogi\mp3"
```

`MABIICCO_APP`이 없거나 그 폴더에 `MabiIcco.jar`·`lib\flatlaf\`·`lib\commons-compress\`가 없으면 브리지는 시작하지 않고 이렇게 알린다(종료 코드 5):

```
[run-bridge] ERROR: MabiIcco is required: MABIICCO_APP is not set.
[run-bridge]        This tool contains no MabiIcco code and works only with an installed MabiIcco (1.5.12 is known to work).
[run-bridge]        Get MabiIcco from its author: https://github.com/fourthline/mmlTools (Releases), install it, then set
[run-bridge]        MABIICCO_APP to its "app" folder, the folder that contains MabiIcco.jar and lib\, for example:
[run-bridge]          set "MABIICCO_APP=C:\Tools\MabiIcco-1.5.12\app"
```

### 7. 설치 확인 (스모크 시험)

새 cmd 창에서:

```bat
C:\mabi-mcp\smoke-test.cmd
```

`--version`, `--selfcheck`(클래스·자료·DLS 찾기·1초 메모리 렌더), MCP 대화 한 번(악보 만들기 → 음 넣기 → `render_wav` → 저장)을 돌리고 마지막 줄에 `PASS` 또는 `FAIL`을 찍는다. 기록과 렌더 WAV는 `C:\mabi-mcp\output\smoke\`. DLS를 못 찾으면 `--selfcheck`가 `FAIL`과 함께 `MABI_DLS` 설정 방법을 알려 준다(음원 없이 나머지만 보려면 `bridge\run-bridge.cmd --selfcheck --no-dls`).

mabi-ear는 처음 한 번 터미널에서 띄워 패키지를 받아 둔다(몇 분 걸린다. MCP 클라이언트가 처음 띄우면 시간 초과가 날 수 있다):

```bat
C:\mabi-mcp\analysis\run-ear.cmd --help
```

`analysis\.venv`가 만들어지고 사용법이 나오면 된다. 중간에 끊겼거나 망가진 venv는 다음 실행 때 다시 만든다. 다 깔린 뒤에는 `analysis\.cache\pip`(약 120 MB)을 지워도 된다. 이미 파이썬 3.11 환경에 `requirements.txt`를 깔아 두었다면 `MABIEAR_PYTHON=<그 python.exe>`로 그것을 쓸 수 있다.

### 8. MCP 클라이언트에 두 서버 등록

클로드 코드(Claude Code)에서는:

```bat
claude mcp add --transport stdio mabiicco --env MABIICCO_APP=C:\Tools\MabiIcco-1.5.12\app --env MABI_DLS=C:\Nexon\Mabinogi\mp3 -- cmd /c C:\mabi-mcp\bridge\run-bridge.cmd
claude mcp add --transport stdio mabi-ear -- cmd /c C:\mabi-mcp\analysis\run-ear.cmd
```

(**서버 이름(`mabiicco`)을 `--env`보다 앞에** 쓴다 — `--env`는 값을 여러 개 받아서, 이름이 뒤에 오면 이름까지 환경 변수로 읽고 "Invalid environment variable format"으로 실패한다. `setx`로 환경 변수를 넣었다면 `--env …`는 빼도 된다. 모든 프로젝트에서 쓰려면 서버 이름 앞에 `--scope user`를 더한다(예: `claude mcp add --transport stdio --scope user mabiicco --env … -- cmd /c …`). mabi-ear에 이미 있는 파이썬 환경을 쓰게 하려면 같은 식으로 `mabi-ear --env MABIEAR_PYTHON=<그 python.exe> -- cmd /c …`.) 확인: `claude mcp list`에서 둘 다 연결됨(connected), 클로드 코드 안 `/mcp`에서 도구 39개와 18개.

다른 MCP 클라이언트는 stdio 서버 두 개를 같은 명령으로 등록한다: `cmd /c C:\mabi-mcp\bridge\run-bridge.cmd`(환경 변수 `MABIICCO_APP`, `MABI_DLS`)와 `cmd /c C:\mabi-mcp\analysis\run-ear.cmd`. 시험한 클라이언트는 클로드 코드뿐이다.

처음 시켜 볼 것: "mabiicco로 새 악보를 만들고 피아노 트랙에 도레미파를 넣고 render_wav로 렌더한 뒤, mabi-ear audio_info로 그 WAV를 보고, `C:\mabi-mcp\output\첫곡.mmi`로 저장해 줘."

### 프로젝트 파일 (선택)

작업 폴더마다 기본 출력 위치와 "절대 쓰지 말아야 할 폴더"를 정하고 싶으면 `mabibridge-project.properties`(UTF-8)를 둔다. 두 서버가 같은 규칙으로 찾는다: `MABIBRIDGE_PROJECT`, 아니면 서버 자신의 폴더(jar·패키지)에서 위로 올라가며 처음 만나는 파일, 아니면 작업 폴더에서 위로. 없으면 위의 기본값이고 보호 폴더는 `--protect`로 준 것뿐이다. 서버 시작 로그와 `--selfcheck`가 쓰고 있는 파일을 찍는다.

```properties
# key = value, 경로는 이 파일 기준 상대 또는 절대, 역슬래시 그대로
render.dir   = 렌더
analysis.dir = 분석
protect      = 원본 악보 ; 원곡
protect.1    = D:\받은 악보
```

`protect`(`;`로 여러 개)와 `protect.<아무 이름>`(한 줄에 하나)에 적은 폴더 안에는 두 서버 모두 저장·렌더·분석 파일·스냅샷·로그를 쓰지 않는다(`PROTECTED_PATH`; 스냅샷·로그 폴더가 그 안이면 브리지가 시작을 거부한다). 기본 출력 폴더가 보호 폴더 안이면 두 서버 모두 경고만 하고 계속 돈다(그 경우 `out_dir` 없는 호출이 거절된다).

## 도구 목록

자세한 입력·출력은 `bridge/README.md`(mabiicco)와 `analysis/README.md`(mabi-ear).

### mabiicco (39)

| 도구 | 하는 일 |
|---|---|
| `project_new` | 새 악보(제목·박자·템포) |
| `project_open` | .mmi 열기(읽기만) |
| `project_save_as` | 새 .mmi로 저장(이미 있는 파일·보호 폴더는 거부) |
| `project_info` | 열린 악보 요약(트랙·템포·박자·길이) |
| `project_set` | 제목·작곡자 바꾸기 |
| `project_set_time_signatures` | 박자 목록 통째로 바꾸기 |
| `project_set_tempos` | 템포 목록 통째로 바꾸기(32~255) |
| `track_add` | 트랙 추가(악기, 노래 악기) |
| `track_set` | 트랙 이름·악기·팬 등 바꾸기 |
| `track_list` | 트랙 목록과 글자 수 |
| `instruments` | 악기표(번호·음역·파트·세기 규칙, 기본은 DLS 기준; `dls:false`면 DLS 없이 내장 표) |
| `reference_load` | 참고 MIDI 열기(깨진 파일이면 위치 진단) |
| `reference_info` | 참고 MIDI 요약(트랙·박자·템포·역할 힌트) |
| `reference_notes` | 참고 MIDI 음 목록(마디 범위) |
| `reference_chords` | 참고 MIDI 박마다 화음 |
| `reference_voice_draft` | 참고 트랙을 1~3성부 초안으로(읽기만) |
| `reference_regrid` | 참고 MIDI를 고정 템포 격자로 다시 놓기 |
| `reference_cleanup` | 참고 트랙 정리(양자화·옥타브 접기·중복·짧은 음) |
| `score_notes` | 악보 음 읽기 |
| `score_put_notes` | 음 쓰기(적은 음 또는 들리는 음 기준) |
| `score_delete_notes` | 음 지우기 |
| `score_replace_range` | 마디 범위를 새 음으로 바꾸기 |
| `score_copy_range` | 마디 범위 복사·옮김(이조·세기 조절) |
| `score_mml` | 트랙의 MML 글 보기 |
| `score_stats` | 글자 수·랭크 한도·경고 |
| `score_transform` | 일괄 변환 7종(자동 스냅샷) |
| `score_fit_suggest` | 글자 수 줄이는 방법 제안(읽기만) |
| `score_split_sheets` | 한도에 맞게 여러 장으로 나눠 저장 |
| `score_finalize` | 다 만든 악보 마무리: 머리 마디 + 조율 시그니처, 세기 0 음으로 끝 맞춤(아래 "시그니처") |
| `score_merge_tracks` | 같은 악기 트랙을 한 트랙의 멜로디·화음1·화음2로 합치기 |
| `track_import_mml` | `MML@…;` 글로 트랙 더하기 |
| `snapshot` | 지금 상태 저장 |
| `restore` | 스냅샷으로 되돌리기 |
| `snapshot_list` | 스냅샷 목록 |
| `compare_set_mapping` | 비교할 참고 트랙과 악보 트랙의 짝 정하기 |
| `compare` | 참고 MIDI와 악보를 마디별로 비교(음·화음·음역) |
| `compare_suggest_mapping` | 트랙 짝 제안 |
| `render_wav` | 오프라인 WAV(44.1 kHz) + 메타 JSON 렌더 |
| `score_profile` | 편곡 모양 수치(음역·세기·밀도·보이싱·역할) |

### mabi-ear (18)

| 도구 | 하는 일 |
|---|---|
| `audio_info` | 오디오 파일(WAV/FLAC/MP3) 길이·샘플레이트·채널·peak/RMS |
| `align` | 렌더와 원곡의 시간 맞추기(어긋남·템포 비율·마디 대응; 아는 시작·템포는 고정 가능) |
| `chroma_compare` | 마디별 음높이 분포(크로마) 비교 |
| `onset_compare` | 마디별 박·타격 시점 비교 |
| `richness_compare` | 풍성함 비교(마디마다 음 시작 수 비율 등) |
| `balance` | 트랙·파트별로 나눈 렌더의 마디별 소리 크기와 리드 여유 |
| `compare_renders` | 렌더 두 개를 마디끼리 비교(크로마·소리 크기 차) |
| `report` | 위 결과를 마크다운 보고서로 |
| `stem_drum_hits` | 드럼 스템에서 타격을 격자 위로 뽑기 |
| `stem_bass_notes` | 베이스 스템에서 음 뽑기 |
| `stem_melody_notes` | 노래·리드 스템에서 멜로디 음 뽑기(`method: "ridge"` = 악기 솔로용, 배음으로 옥타브를 정하고 샌 반주를 뺌) |
| `stem_chords` | 스템에서 박별 화음 뽑기 |
| `structure_repeats` | 반복 구조(같은 마디 묶음) 찾기 |
| `phone_version` | 휴대폰으로 듣기 좋은 모노 WAV 만들기(`out`을 안 주면 **입력 WAV 옆** `폰용\` 폴더에 쓴다) |
| `excerpt_ab` | 렌더와 원곡을 같은 마디로 잘라 듣기 비교용 파일로(`out_dir`를 안 주면 **렌더 WAV 옆** `변형\` 폴더에 쓴다) |
| `downbeat_check` | 1마디 시작 위상(다운비트) 확인 |
| `lead_check` | 악보 리드 음마다 원곡(믹스 또는 노래 스템)에서 같은 음이 들리는지: 일치·틀림(들린 음)·보류 |
| `vocal_agreement` | 노래 전사 파일(GAME CSV)과 악보 리드의 양방향 일치(구간별, 다른 곳 목록) |

## 시그니처: `score_finalize`가 넣는 것

곡을 다 만들면 AI가 `score_finalize`를 한 번 부른다(서버 안내문에 그렇게 적혀 있다). **시그니처와 끝 맞춤은 이 도구만 넣는다.** 다른 도구는 넣지 않으며, 있는 음은 지우거나 바꾸지 않는다.

**넣는 것**

- **머리 마디와 "시–레–솔–미"(B–D–G–E)**: 곡 앞에 온마디(보통 1마디)를 넣고, 그 안에서 모든 트랙이 같은 짧은 구간을 연주한다 — 시·레·솔을 짧게, 미를 길게. 구간 길이는 반 마디쯤이다(4/4·보통 빠르기에서 정확히 반 마디. 빠르기에 따라 32분·16분·8분음표 단위로 바뀐다). 이 구간이 그대로 **합주 조율 구간**이 된다: 연주를 시작할 때 모두가 같은 소리를 듣고, 누군가의 연주가 실패했으면 곡이 시작되기 전에 알 수 있다. 음높이는 악기만으로 정해져서 어느 악보에서나 같은 소리다. 드럼은 스네어 세 번과 크래시(모두 세기 15), 노래·마이크는 노래 칸(마이크는 목소리 샘플이 커서 세 단계 낮은 세기로). 글자 수가 모자란 트랙은 건너뛰고, 머리 마디의 쉼표만으로 한도를 넘는 칸이 생기면 아무것도 넣지 않는다. **이 도구 뒤에는 마디 번호가 머리 마디 수만큼 커진다**(응답 `measures_inserted`, 빠른 곡이나 짧은 박자면 2마디일 수 있다).
- **끝 맞춤(소리 없는 끝 음)**: 먼저 끝나는 파트 끝에 소리 없는 짧은 음(세기 0)을 하나 넣어 **모든 연주자가 같은 순간에 끝나게** 한다(먼저 끝난 사람이 합주에서 먼저 빠지지 않게). 소리는 나지 않는다.

**넣지 않으려면**: "시그니처는 빼 줘" → `signature: false`, "끝 맞춤은 하지 마" → `end_padding: false`. 미리 보기만 하려면 `dry_run: true`.

**이미 넣은 것을 빼려면**: 응답의 `snapshot_id`로 `restore`하거나, MabiIcco에서 넣은 머리 마디(`measures_inserted`개)를 앞에서부터 지운다 — 템포도 그대로 남는다(도구가 같은 템포를 원래 첫 박에도 적어 두기 때문. 트랙마다 3~7자라 글자 수에 들어가고, 자리가 없으면 그 복사만 빼고 경고 `tempo_copy_omitted`로 알린다. 그때는 머리 마디를 지운 뒤 알려 준 시작 템포를 다시 넣는다). 끝 맞춤을 빼려면 끝 맞춤을 넣은 트랙마다 맨 끝의 소리 없는 음(세기 0)을 지운다. 두 번 불러도 두 번 넣지 않는다.

**왜 넣나**: 이 도구로 만든 악보를 알아볼 수 있게 하려는 것이고, 다른 편곡자의 악보를 손봐서 제 것처럼 내놓는 일에 대한 최소한의 표시다. **다른 사람의 편곡을 바탕으로 만든 악보라면 원 편곡자를 꼭 밝혀 주기 바란다.**

끝 맞춤은 게임 안 합주에서 확인했다(먼저 끝나는 파트의 연주자가 끝까지 합주 상태로 남는다). 시그니처의 세기(마이크는 낮게, 스네어는 크래시와 같게)는 게임에서 들어 보고 정했다.

## 한계와 주의

- Windows 전용(`.cmd` 실행기). Java 21 이상, 다른 OS는 시험하지 않았다. 확인한 MabiIcco 판은 1.5.12다(1.5.11은 사용자 보고).
- **글자 수·랭크 한도**는 MabiIcco의 표를 따른다(`score_stats`의 `rank`, 기본 1랭크). 노래 파트가 있는 트랙은 노래 칸 포함 한도, 없는 트랙은 노래 칸 제외 한도(1600/1200/900)가 기본이다. 게임 안 합주 확인은 사람이 해야 한다.
- **마이크·노래·코러스**: 마이크와 노래 악기(타입 V, 80~94·120·121)는 노래 칸만 연주한다 — 그 트랙에 노래 제외 기준(`song_rule: "song_excluded"`)을 주면 경고(`song_rule_voice`)가 난다. 다른 트랙의 노래 칸을 울리는 `song_program`은 코러스(100·110)만 받는다. 마이크·노래는 소리 나는 적은 음의 창이 좁다(창 밖 음은 `silent_note` 경고, 자세한 범위는 `bridge/README.md` "들리는 음").
- **렌더**: 소리 렌더·`instruments`(기본값)·3MLE `.mml` 읽기에는 마비노기 DLS가 필요하다(`instruments`는 `dls:false`로 DLS 없이도 된다). 렌더는 JDK 내장 신시사이저의 오프라인 렌더라 MabiIcco 창의 WAV 내보내기와 샘플 단위로 같지 않다(자동 게인, 6.8 ms보다 짧은 음, 모바일 타악기의 무작위 음 — `BUILD.md`). 악기에 따라 적은 음보다 1~2옥타브 높게 울린다(원곡 음높이를 옮길 때는 `pitch_mode: "sounding"`).
- **귀(mabi-ear)**: 유사도는 "원곡과 닮았나"이지 "좋게 들리나"가 아니다. 스템 도구에는 직접 분리한 스템이 필요하다. `lead_check`를 **전체 믹스**로 돌리면 반주의 화음 음이 노래보다 세게 잡히는 곳에서 멀쩡한 음을 틀림으로 낼 수 있어(측정한 한 곡에서 맞다고 본 음의 10 % 가까이, 보컬 스템으로는 1 % 안팎), 믹스 결과는 의심 목록으로만 쓰고 **스템**과(있으면) 노래 전사로 확인한다. `phone_version`·`excerpt_ab`는 출력 경로를 안 주면 `MABIBRIDGE_OUT`과 무관하게 입력 파일 옆에 쓴다.
- **확인 범위**: 자동 시험(JUnit·pytest, 합성 자료)이 도구의 동작(악보 편집·글자 수·비교·렌더·마무리·분석)을 확인한다. 그 밖에 게임에서 확인한 것은 둘뿐이다 — 끝 맞춤(먼저 끝나는 파트의 연주자도 끝까지 합주 상태로 남는다)과 시그니처의 세기로, 만든 사람이 게임 안 합주에서 확인했다. 그 밖의 게임 안 동작, 다른 PC, 다른 판의 MabiIcco, 다른 MCP 클라이언트는 확인하지 않았다.
- 설치 폴더는 쓰기 가능해야 하고 영문 경로를 권한다(위). jar를 다시 빌드하면 떠 있는 서버를 다시 띄워야 한다.

## 들어 있지 않은 것

| 빠진 것 | 이유 | 대신 |
|---|---|---|
| MabiIcco(코드와 바이너리 `MabiIcco.jar`, 함께 오는 라이브러리) | 원 제작자의 프로그램이다 | 자기 MabiIcco 설치(`MABIICCO_APP`), <https://github.com/fourthline/mmlTools> |
| 마비노기 DLS 음원 | 게임 회사의 자산 | 자기 마비노기 설치의 `mp3` 폴더(`MABI_DLS`) |
| 곡·악보·렌더·스템 | 다른 사람의 작품 | 시험은 합성 데이터로 돈다 |

## 라이선스

MIT(`LICENSE`). MabiIcco의 동작을 그대로 따른 세 파일의 해당 부분은 원 제작자 たんらる 님의 작업을 따른 것이고, 목록과 설명이 `LICENSE`에 있다. MabiIcco, 그 바이너리, 게임의 음원은 이 저장소에 들어 있지 않으며 각 권리자의 것이다. 함께 들어 있는 제3자 라이브러리는 Gson뿐이다(`NOTICE`). JUnit·Hamcrest는 시험을 돌릴 때만 필요하고 들어 있지 않다(`lib/test/README.md`).

## 만든 사람
- LT 크라우리제

## 스페셜 땡스
- MabiIcco 원 제작자 たんらる 님 (https://github.com/fourthline/mmlTools)
- MD 리세드리카 (https://www.mabicompose.com/) - MML 연구소에서 많이 배웠습니다.

- LT Cooper
- LT 베어섭난민
- LT 남븝
- 마도카 작곡 게시판의 존경하는 선배 코더님들
