# BUILD — punchstrip 빌드와 시험

설치와 MCP 클라이언트 등록은 `README.md`. 이 문서는 저장소에서 빌드·시험하는 사람을 위한 것이다.

punchstrip에는 MabiIcco의 코드가 들어 있지 않다. 브리지는 **설치된 MabiIcco**(그 `app` 폴더, 아래 `<app>`)의 `MabiIcco.jar`에 대해 컴파일하고, 그것과 함께 실행한다. MabiIcco를 직접 빌드하는 방법은 다루지 않는다.

## 1. 전제

- JDK 17 이상(64비트). 바이트코드 목표 17, record를 쓰므로 17 이상이 필요하다. Java 21 이상은 시험하지 않았다.
- Apache Ant 1.10.
- 시험(`ant test`)만: JUnit 4.12와 Hamcrest Core 1.3. **들어 있지 않다** — `lib/test/junit-4.12.jar`, `lib/test/hamcrest-core-1.3.jar`로 직접 둔다(받는 곳과 SHA-256은 `lib/test/README.md`). `ant jar`·`ant smoke`·`ant dist`에는 필요 없다.
- 설치된 MabiIcco의 `app` 폴더(`MabiIcco.jar`, `lib\flatlaf\`, `lib\commons-compress\`가 있는 폴더). 확인한 판: 1.5.12(1.5.11에서도 된다는 사용자 보고가 있다).
- mabi-ear: Python 3.11(`py` 실행기), `analysis/requirements.txt`(실행), `analysis/requirements-dev.txt`(시험).
- 경로에 공백이나 한글이 있으면 명령에서 따옴표로 감싼다.

## 2. 빌드

```bat
ant jar   -Dmabiicco.app=C:\Tools\MabiIcco-1.5.12\app
ant test  -Dmabiicco.app=C:\Tools\MabiIcco-1.5.12\app
ant smoke -Dmabiicco.app=C:\Tools\MabiIcco-1.5.12\app
ant dist  -Dmabiicco.app=C:\Tools\MabiIcco-1.5.12\app
ant clean
```

- `-Dmabiicco.app`를 주지 않으면 환경 변수 `MABIICCO_APP`을 쓴다. 둘 다 없거나 그 폴더에 `MabiIcco.jar`·`lib\flatlaf\`·`lib\commons-compress\`가 없으면 빌드가 "MabiIcco is required"로 멈추고 받는 곳을 알려 준다.
- `jar`: `src-bridge/`를 컴파일해 `build/MabiIccoBridge.jar`(브리지 클래스와 자료 파일 3개 — `sounding_pitch.properties`, `drum27_keys.json`, `signature_home.properties` — 뿐, MabiIcco 엔진 없음)를 만들고 저장소 루트에 사본을 둔다(`bridge\run-bridge.cmd`·`smoke-test.cmd`가 루트의 jar를 쓴다. 릴리스 zip과 같은 배치).
- `test`: 아래 6절(시험용 jar 두 개가 없으면 없는 파일 이름과 `lib/test/README.md`를 알리고 바로 멈춘다). `smoke`: `MABIICCO_APP`을 설치 폴더로 두고 `smoke-test.cmd`를 돌린다.
- `dist`: `dist/punchstrip-0.7.0-win.zip`. zip 맨 위에 `MabiIccoBridge.jar`, `lib/gson/`, `lib/test/README.md`(시험용 jar 안내만), `bridge/`(실행기, 문서, 예 3쌍), `analysis/`(mabi-ear 소스·실행기·requirements·README, 드라이버 스크립트 2개), `smoke-test.cmd`, `README.md`, `BUILD.md`, `LICENSE`, `NOTICE`. 시험, MabiIcco, DLS 음원, 파이썬 환경은 넣지 않는다.
- 실행할 때는 `MABIICCO_APP=<app>`를 준다. `run-bridge.cmd`가 `-cp "<app>\MabiIcco.jar;<app>\lib\flatlaf\*;<app>\lib\commons-compress\*;MabiIccoBridge.jar;lib\gson\*" mabibridge.Main`으로 띄운다.
- 브리지는 MabiIcco의 JNA와 `LauncherSupport`를 쓰지 않는다(MabiIcco 창 실행기 전용). 브리지 0.6.1에서 시험 전체와 MCP 대화 한 번의 클래스 로드 기록(`-Xlog:class+load`)을 보았을 때 `com.sun.jna.*`·`LauncherSupport`는 한 번도 로드되지 않았다(그 뒤 판에서는 다시 재지 않았다).
- 브리지 컴파일은 `release="17"` 대신 `source/target=17`을 쓴다(`--release`와 `--add-exports java.desktop/...`는 함께 쓸 수 없다).

## 3. 실행 플래그 (`bridge\run-bridge.cmd`가 붙인다)

| 플래그 | 이유 |
|---|---|
| `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` | MabiIcco와 렌더가 JDK 내부 신시사이저(Gervill)를 직접 쓴다. 없으면 DLS·렌더를 처음 쓸 때 접근 오류 |
| `-Dmabiicco.test_mode` | **필수.** MabiIcco가 설정 파일 `.mabiicco.config`를 읽고 쓰지 않게 한다. 브리지는 이것이 없으면 시작을 거부한다 |
| `-Duser.home=bridge\runtime\home` | 이중 안전장치. 혹시 무언가 `user.home` 아래에 쓰더라도 실제 홈이 아니라 설치 폴더 안에 쓴다 |
| `-Djava.awt.headless=true` | 창을 띄우지 않는다 |
| `-Dmabiicco.ticktable=tickTable.dat` | MabiIcco jar 안 틱 표를 읽어 시작을 빠르게(MabiIcco 실행 설정과 같음) |
| `-Xmx1024m` | DLS 로드와 렌더 |

MabiIcco 코드를 돌리는 **모든** 명령(시험용 `java` 실행 포함)에 `-Dmabiicco.test_mode`와 격리된 `-Duser.home`을 함께 붙인다.

브리지 표준출력(stdout)은 MCP 통신 전용이다. `Main`이 시작하자마자 진짜 stdout을 따로 잡고 `System.out`을 stderr로 돌려, 엔진 로그가 통신을 깨뜨리지 않는다. 실행기의 오류 메시지도 모두 stderr로 간다.

`run-bridge.cmd`는 시작 전에 `java`(JAVA_HOME 또는 PATH)와 Java 17 이상(`java -version`)을 확인하고, `MABIICCO_APP`이 없거나 그 폴더에 `MabiIcco.jar`·`lib\flatlaf\`·`lib\commons-compress\`가 없으면 "MabiIcco is required"와 받는 곳(<https://github.com/fourthline/mmlTools>)을 적고 끝난다(종료 코드 2 시작 인자 오류 — 모르는 인자, 상대 경로인 `MABIBRIDGE_OUT` 등 —, 3 브리지 jar 없음, 4 Java, 5 MabiIcco). `MABIBRIDGE_SKIP_JAVA_CHECK=1`이면 버전 확인을 건너뛴다. 실행기를 거치지 않고 띄워 MabiIcco 클래스가 클래스 경로에 없을 때도 브리지(`serve`, `--selfcheck`)가 같은 안내를 찍고 종료 코드 5로 멈춘다(`--version`은 된다).

## 4. 실행 인자와 기본 위치

```text
MabiIccoBridge --version
MabiIccoBridge --selfcheck [--dls a.dls,b.dls | --dls 폴더] [--no-dls]
MabiIccoBridge [serve] [--dls …] [--protect 폴더]... [--snapshots 폴더] [--log 파일] [--render-dir 폴더]
```

| 항목 | 기본값 |
|---|---|
| DLS | `--dls` → 환경 변수 `MABI_DLS`(쉼표·세미콜론 목록, 또는 `MSXspirit0*.dls`가 든 폴더) → 흔한 설치 폴더(`C:\Nexon\Mabinogi\mp3`, `C:\Program Files (x86)\Nexon\Mabinogi\mp3`, Steam 폴더, `D:`·`E:\Nexon\Mabinogi\mp3` 등, `mabibridge.engine.DlsLocator`) → 못 찾으면 `C:\Nexon\Mabinogi\mp3\MSXspirit01~05.dls`(없음으로 보고). DLS는 처음 쓸 때 읽는다 |
| 렌더 출력(`render_wav`의 `out_dir` 생략 시) | `--render-dir` → `%MABIBRIDGE_OUT%\render`(절대 경로여야 함, 상대면 시작 거부) → 프로젝트 파일의 `render.dir` → `<jar 폴더>\output\render` |
| 스냅샷 | `<jar 폴더>\bridge\runtime\snapshots` |
| 로그 | `<jar 폴더>\bridge\runtime\log\bridge.log`(stderr와 같은 내용, UTF-8 이어쓰기) |
| 보호 폴더(이 안에는 저장·렌더·스냅샷·로그를 쓰지 않음, `PROTECTED_PATH`) | `--protect`로 준 폴더 + 프로젝트 파일의 `protect` 항목(프로젝트 파일이 없으면 `--protect`로 준 폴더만 보호한다). 스냅샷·로그 폴더가 보호 폴더 안이면 시작을 거부하고, 기본 렌더 폴더가 보호 폴더 안이면 경고만 한다 |
| 프로젝트 파일 `mabibridge-project.properties` | `-Dmabibridge.project` 또는 `MABIBRIDGE_PROJECT`(파일 경로, `none`이면 찾지 않음) → 먼저 설치 폴더(jar 또는 클래스 폴더)에서 위로 올라가며 처음 만나는 파일 → 그다음 작업 폴더에서 위로. 어느 것을 찾았는지 `--selfcheck`와 시작 로그가 `(found above the install folder | above the working directory)`로 찍는다. 형식: UTF-8, `key = value`, `#`·`!` 주석, 이스케이프 없음(역슬래시 그대로), 경로는 파일 기준 상대 또는 절대. 열쇠 `render.dir`, `analysis.dir`, `protect`(`;` 목록), `protect.<이름>`(하나씩). 코드 `mabibridge.engine.ProjectConfig`, mabi-ear는 같은 규칙의 `mabiear.io.locate_project` |

`--version`·`--selfcheck`·시작 로그는 `MabiIccoBridge 0.7.0 (MabiIcco 1.5.12 found; built and tested against MabiIcco 1.5.12)`처럼 찍는다: 앞은 클래스 경로에서 찾은 MabiIcco jar의 판(manifest `MabiIcco-Version`, 없으면 `version unknown`), 뒤는 이 브리지를 빌드하고 시험한 MabiIcco 판(고정값). 렌더 메타 JSON의 `engine`도 같은 두 값을 적는다.

`--selfcheck`는 클래스마다 어느 jar에서 읽었는지, 자료 파일, 기본 위치, 찾은 DLS 파일을 찍고 1초짜리 메모리 렌더를 한다. DLS가 없으면 `FAIL`과 설정 안내(`--no-dls`면 렌더만 SKIP).

명령줄의 경로는 ASCII를 기대한다(Windows 자바 실행기가 ANSI 코드 페이지로 바꾸며 다른 글자를 깨뜨릴 수 있다). 도구 인자는 JSON(UTF-8)이라 한글·일본어 경로도 된다. 설치 경로에 ASCII 아닌 글자가 있으면 시작할 때 경고한다.

mabi-ear(`analysis\run-ear.cmd`, `python -I -X utf8 run_ear.py`)의 기본 출력 폴더: `--out-dir` → `%MABIBRIDGE_OUT%\analysis`(절대 경로) → 프로젝트 파일의 `analysis.dir` → `<설치>\output\analysis`. 보호 폴더는 브리지와 같은 프로젝트 파일 + `--protect`, 경로 비교는 정션·심볼릭 링크를 푼 실제 경로로 한다(브리지와 같음). `--log`가 보호 폴더 안이면 시작하지 않는다(시작 오류는 `[run-ear] ERROR: …` 한 줄, 종료 코드 1). 캐시·임시 파일은 모두 `analysis\.cache` 아래(사용자 홈에 쓰지 않음). 처음 실행 때 `analysis\.venv`를 `py -3.11`로 만들고 `requirements.txt`를 설치하며(약 120 MB 내려받기, 디스크 약 620 MB, 1분 안팎), 끝까지 성공하면 `.venv\mabiear-deps.ok`를 남긴다. venv의 파이썬이 3.11이 아니거나 pip을 못 읽으면 지우고 다시 만든다. `MABIEAR_PYTHON=<python.exe>`면 그 파이썬을 쓴다. 패키지는 편집 설치 없이 `run_ear.py`가 옆 폴더에서 읽는다.

## 5. 서버 다시 띄우기

`ant jar`는 jar를 그 자리에서 다시 쓴다. 이미 떠 있는 MCP 서버는 옛 jar로 시작해 클래스를 처음 쓸 때 jar에서 읽으므로, 다시 빌드한 뒤 **바뀐 클래스를 처음 쓰는 순간 `NoClassDefFoundError`**로 실패할 수 있다. 빌드한 뒤에는 서버를 다시 띄운다(MCP 클라이언트에서 다시 연결, 클로드 코드는 `/mcp`). 빌드하는 동안에도 서버를 쓰려면 jar 사본을 다른 폴더에 두고 `McpClient --jar <사본>`으로 띄운다(`bridge/README.md` "요청 파일 드라이버 McpClient").

## 6. 시험

- `ant test -Dmabiicco.app=<app>`: `test-bridge/**/*Test.java`(JUnit 4, 직접 둔 `lib/test/junit-4.12.jar`·`lib/test/hamcrest-core-1.3.jar` — `lib/test/README.md`)를 한 번 포크한 JVM에서. 플래그는 3절과 같고 `user.home`·`java.io.tmpdir`는 `build/test/home`·`tmp`. 결과는 콘솔 요약과 `build/test/reports/`. 시험은 모두 합성 자료만 쓴다.
- DLS가 없으면 렌더·DLS 시험은 건너뛴다(stderr `[SKIP]`).
- 선택: 자기 자료(악보·MIDI)로 돌리는 시험을 `test-bridge-local/`에 두면 `ant test`가 함께 컴파일하고 돌린다(`.gitignore`가 제외한다. 자료 위치는 `-Dmabibridge.testdata.map=<파일>` 또는 환경 변수 `MABIBRIDGE_TESTDATA`로 넘긴다).
- pytest(mabi-ear): `analysis`에서 `requirements-dev.txt`를 깐 파이썬 3.11로 `python -X utf8 -m pytest -q`. 요구 파일은 ASCII라 UTF-8 모드 없이도 `pip install -r`이 된다.
- 스모크: `smoke-test.cmd`(설치 확인, `README.md`) 또는 `ant smoke`. 또는 `java -Dmabiicco.test_mode -Duser.home=bridge\runtime\home -cp "MabiIccoBridge.jar;lib\gson\*" mabibridge.mcp.McpSmoke` — 브리지를 `bridge\run-bridge.cmd`로 자식 프로세스로 띄워 initialize부터 렌더·저장·ping까지 주고받고, 자식 stdout에 JSON-RPC 아닌 줄이나 CRLF가 하나도 없는지 본다. `-Dmabibridge.smoke.midi=<file.mid>`면 참조 MIDI 단계도 돈다.

## 7. 오프라인 WAV 렌더 `render_wav`

오디오 장치 없이 JDK 내장 신시사이저(Gervill)의 오프라인 스트림으로 WAV(44.1 kHz/16 bit/스테레오)와 메타 JSON(`mabibridge.render/1`)을 만든다. 코드는 `src-bridge/mabibridge/render/`. MabiIcco 창의 WAV 내보내기는 실제 재생 장치가 필요하고 실시간 속도라 쓰지 않는다.

- 순서: DLS 읽기 → `MabiDLS.createSequenceOnSepChannel(score)`로 이벤트 → `new SoftSynthesizer().openStream(fmt, info)` → 악기 적재·채널 설정(MabiIcco의 동작을 따름, `LICENSE` 참고) → 이벤트를 `SoftShortMessage` + µs 시각으로 보냄 → 스트림을 300프레임(약 6.8 ms)씩 읽음 → WAV + 메타 JSON.
- 신시사이저 설정 키 14개를 모두 명시한다. 빠진 키는 Java Preferences(Windows는 HKCU 레지스트리)의 값이 덮어쓰기 때문이다. 그 노드는 읽기만 한다. `load default soundbank=false`(true면 `user.home/.gervill/`에 쓰는 경로가 있다).
- 채널 16 이상 메시지를 시각과 함께 보낼 때 `ShortMessage`의 clone이 채널을 잃는 문제가 있어 `SoftShortMessage`를 쓴다(JUnit `RenderTest`가 잡는다).

알려진 한계(메타 JSON `limitations`에도 적힌다):

1. 자동 게인이 켜져 있어 트랙·파트별 파일을 더한 것은 전체 파일과 같지 않을 수 있다. 밸런스는 전체 파일을 기준으로 본다.
2. 노트오프는 약 6.8 ms 단위로 처리된다. 그보다 짧은 음은 소리가 안 날 수 있다.
3. MabiIcco 창의 WAV 내보내기와 샘플 단위로 같지 않다(앞 무음 길이, 이벤트 시각 양자화).
4. 모바일 타악기(타입 M)는 엔진이 음을 무작위로 골라 렌더마다 다를 수 있다.
5. 모든 음은 엔진 규칙대로 1틱 늦게 시작하고 1틱 짧게 끝난다. 트랙 뮤트/솔로는 무시하고 요청의 `tracks`·`parts`만 따른다.
6. 길이는 마지막 마디의 끝과 마지막 노트오프 중 늦은 쪽까지 렌더하고, 소리가 다 사라지면 멈춘다.
7. 악기에 따라 적은 음보다 높게 울린다(피아노 +1옥타브, 바이올린 +2옥타브 등, 8절).

## 8. 들리는 음 표 `sounding_pitch.properties`

마비노기 악기는 적은 음과 실제로 울리는 음이 악기마다 다르다. 브리지는 이 차이를 `src-bridge/mabibridge/engine/sounding_pitch.properties`(UTF-8) 한 파일로 다룬다. 빌드가 `src-bridge/**/*.properties`·`*.json`을 jar에 복사한다. 형식은 파일 머리말: `<악기 번호>=offset:<반음>` | `regions:<from>..<to>:<반음>:<세트>;…` | `fixed:<MIDI>` | `unpitched` | `sfx`. 값은 렌더 WAV의 기본 주파수 측정과 DLS 샘플 분석에서 왔다. 고칠 때는 `test-bridge/mabibridge/engine/SoundingPitchTest.java`의 기대값도 맞춘다.

드럼 27의 키 표 `src-bridge/mabibridge/engine/drum27_keys.json`은 mabi-ear도 쓴다. mabi-ear에는 같은 파일의 사본이 `analysis/mabiear/data/drum27_keys.json`으로 들어 있고, 시험(`analysis/tests/test_stems_drum_bass.py`)이 두 파일이 같은지 본다 — 한쪽을 고치면 다른 쪽에도 복사한다.

`score_finalize`의 시그니처 표 `src-bridge/mabibridge/engine/signature_home.properties`(악기별 시그니처 기준음과 세기 보정 `velocity_offset.<번호>`)도 jar에 들어간다. 고칠 때는 `test-bridge/mabibridge/mcp/FinalizeToolsTest.java`·`test-bridge/mabibridge/engine/FinalizeBudgetTest.java`의 기대값도 맞춘다.

## 9. 개발용 CLI

모두 3절의 플래그를 붙여 `-cp` 클래스 경로(`<app>`의 jar들 + `MabiIccoBridge.jar` + `lib\gson\*`)로 돌린다.

- `mabibridge.engine.EngineCli info|roundtrip|demo|instruments [--dls]` — 엔진 확인.
- `mabibridge.engine.ProfileCli --out <폴더 | -> [--from N] [--to M] [--dls …] <파일 또는 폴더 | ->...` — `score_profile`과 같은 계산을 여러 파일에. 폴더는 `.mmi`·`.mml`을 재귀로 찾는다. 경로 자리의 `-`는 표준입력에서 UTF-8 경로를 한 줄씩 읽는다(한글 경로용). 결과는 파일마다 `<이름>.profile.json`과 `profiles.tsv`·`tracks.tsv`. 종료 코드 0 전부 성공, 1 일부 실패, 2 사용법 오류.
- `mabibridge.mcp.McpClient <요청.jsonl> [--out 응답.txt] [--jar … | --cp …]` — 요청 파일(한 줄에 `{"tool": …, "args": {…}}`)을 보내고 응답을 사람이 읽는 형식으로 찍는다. 자식 서버는 기본으로 `MABIICCO_APP`의 MabiIcco와 함께 뜬다. 예: `bridge/examples/`.
- `analysis/scripts/ear_requests.py` — mabi-ear용 같은 형식의 드라이버(`pipe_check.py`로 `run-ear.cmd`를 띄움).
