# 펀치스트립(punchstrip) — 마비노기 MML MCP Bridge

AI 모델이 **MabiIcco**(마비노기 MML 편집기)를 쓸 수 있게 하는 MCP 서버. AI에게 몇 인 악보로 만들지, 자브키엘을 사용할지 지시하고 만들고 싶은 곡의 MIDI와 원곡 음원(스템 분리된 음원이면 더 좋습니다)을 전달하고 마비꼬용 mmi 파일을 받으세요.

- **mabiicco** (Java, 도구 39개) — MabiIcco를 창 없이 움직이는 **브리지**. 악보(.mmi) 만들기·열기·저장, 트랙·음 편집, MML 글자 수·랭크 한도 확인, 참고 MIDI 읽기와 비교, 오프라인 WAV 렌더, 합주용 마무리.
- **mabi-ear** (Python, 도구 18개) — 렌더한 소리와 원곡 녹음을 비교하는 **귀**. 정렬, 크로마, 온셋, 풍성함, 밸런스, 보고서, 스템에서 타격·음 뽑기, 듣기용 발췌, 악보 리드의 음 단위 원곡 대조, 노래와의 일치.

punchstrip 0.7.1 = 브리지 0.7.0 + mabi-ear 0.5.0.

> **MabiIcco 필수.** punchstrip에는 MabiIcco의 코드가 포함되어 있지 않습니다. 설치된 MabiIcco의 엔진(`MabiIcco.jar`)을 불러와 사용하며 **MabiIcco가 설치된 곳에서만 작동합니다.** MabiIcco는 원 제작자 たんらる 님의 프로그램이고 공식 배포처는 <https://github.com/fourthline/mmlTools> 입니다.

## 퀵 스타트

사용하는 에이전트에게 아래 문장을 붙여넣으세요.

```text
이 PC에 punchstrip(https://github.com/noillthrive/punchstrip)을 설치해 주세요. 설치 순서 문서 https://raw.githubusercontent.com/noillthrive/punchstrip/main/INSTALL.md 를 읽고 처음부터 차례대로 실행하고 MabiIcco 폴더처럼 직접 찾지 못한 정보만 물어보세요. mabiicco와 mabi-ear 두 MCP 서버를 모두 등록한 뒤, smoke-test.cmd의 마지막 줄과 두 서버의 도구 수(mabiicco 39개, mabi-ear 18개)를 알려 주세요.
```

클로드 코드처럼 PC에서 명령을 실행할 수 있는 에이전트에게 맡기고, 설치가 끝나면 에이전트를 다시 시작해야 서버가 보입니다.

## 무엇을 위한 도구인가

### 작곡 초심자를 위해
마비꼬가 설치되어 있고, 좋아하는 곡의 MIDI를 가지고 있고, 원곡 음원을 가지고 있다면 사용하는 에이전트에게 합주용 작곡 파일(mmi)을 만들어달라고 요청해보세요. 이 도구가 작곡에 관심을 가지게 되는 계기가 되면 좋겠습니다.

### 전문 코더를 위해
편곡에는 손이 많이 가지만 귀가 필요 없는 일이 많습니다. MIDI나 스템에서 초안을 잡고, 글자 수를 세어 한도에 맞추고, 실제로 울리는 옥타브가 서로 다른 악기 사이에서 옮겨 적고, 렌더해서 원곡과 비교하는 일처럼 punchstrip은 이런 기계적인 일 대략 80%를 AI에 맡겨 편곡자가 귀가 필요한 나머지에 시간을 쓰게 하고자 합니다.

두 경우 모두 마지막 판단은 사용자께서 직접 해 주세요.

## 필요한 것

- **마비노기와 MabiIcco**: 둘 다 설치된 환경을 기준으로 한다. MabiIcco는 <https://github.com/fourthline/mmlTools> 의 Releases에서 받는다. 마비노기의 악기 음원 `mp3\MSXspirit01.dls`~`MSXspirit05.dls`는 소리 렌더에 쓴다. 악보 편집과 저장은 음원 없이 된다.
- **64비트 Java 17 이상**. MabiIcco에 딸린 `runtime\`에는 `java.exe`가 없어서 따로 설치한다.
- **Python 3.11**과 `py` 런처. mabi-ear의 패키지가 3.11에 맞춰 고정되어 있다.
- **Windows 10/11** 64비트.
- **MCP 클라이언트**.

## 직접 설치하려면

전체 순서와 명령은 `INSTALL.md`에 있습니다. 에이전트도 같은 문서를 따릅니다. 핵심 순서는 다음과 같습니다.

1. Java 17 이상과 Python 3.11을 설치한다.
2. [Releases](https://github.com/noillthrive/punchstrip/releases)의 `punchstrip-<버전>-win.zip`을 쓰기 가능한 영문 경로에 푼다. 예: `C:\mabi-mcp`.
3. 환경 변수 `MABIICCO_APP`에 MabiIcco의 `app` 폴더를 넣는다. `MabiIcco.jar`와 `lib\`가 들어 있는 폴더다. 게임 음원 자동 검색이 실패할 때만 `MABI_DLS`에 마비노기의 `mp3` 폴더를 넣는다.
4. `C:\mabi-mcp\smoke-test.cmd`를 실행해 마지막 줄 `PASS`를 확인한다.
5. `C:\mabi-mcp\analysis\run-ear.cmd --help`를 한 번 실행해 mabi-ear의 파이썬 환경을 만든다.
6. MCP 클라이언트에 `bridge\run-bridge.cmd`와 `analysis\run-ear.cmd`를 stdio 서버로 등록한다.

저장소에서 직접 빌드하는 방법은 `BUILD.md`.

## 시그니처 사운드

곡을 다 만들면 AI가 `score_finalize`를 한 번 불러 다음과 같은 작업을 수행합니다.

- **머리 마디와 "시–레–솔–미"(B–D–G–E)**: 곡 앞에 온마디 1마디를 기본으로 넣고, 그 안에서 모든 트랙이 같은 짧은 구간을 연주합니다. 시·레·솔은 짧게, 미는 길게. 구간 길이는 4/4·보통 빠르기에서 정확히 반 마디이며, 빠르기에 따라 32분·16분·8분음표 단위로 바뀝니다. 음높이는 악기에 따라서만 정해져서, 같은 악기면 어느 악보에서나 같은 음이 들립니다. **이 도구를 부른 뒤에는 마디 번호가 머리 마디 수만큼 커진다.** 응답의 `measures_inserted`가 그 수다. 빠른 곡이나 짧은 박자에서는 2마디가 되기도 합니다.
- **끝 맞춤**: 먼저 끝나는 파트 끝에 세기 0인 소리 없는 짧은 음을 하나 넣어 **모든 연주자가 같은 순간에 끝나게** 합니다.

**시그니처·끝 맞춤 빼기**: "시그니처는 빼 주세요" → `signature: false`, "끝 맞춤은 하지 마세요" → `end_padding: false`로 뺍니다. 미리 보기는 `dry_run: true`로 합니다.

**넣은 내용 되돌리기**: 응답의 `snapshot_id`로 `restore`합니다. 또는 MabiIcco에서 머리 마디 `measures_inserted`개를 앞에서부터 지웁니다. 템포는 그대로 남습니다. 도구가 같은 템포를 원래 첫 박에도 적어 두기 때문입니다. 이 템포 복사는 트랙마다 3~7자를 쓰고, 자리가 모자라면 그 복사만 빼고 경고 `tempo_copy_omitted`로 알립니다. 그때는 머리 마디를 지운 뒤 알려 준 시작 템포를 다시 넣습니다. 끝 맞춤은 그것을 넣은 트랙마다 맨 끝의 세기 0 음을 지우면 빠집니다.

**넣은 이유**: 이 도구로 만든 악보를 알아볼 수 있게 하기 위해, **이 도구로 다른 사람의 편곡을 무단으로 수정해 배포하는 것을 금합니다.**

## 한계와 주의

- **확인한 범위**: Windows, Java 17.0.19, Python 3.11.9, MabiIcco 1.5.11 및 1.5.12, 클로드 코드에서 확인했습니다. JUnit·pytest와 합성 자료를 쓴 자동 시험은 악보 편집·글자 수·비교·렌더·마무리·분석의 동작을 확인하며 게임 안에서는 만든 사람이 합주로 두 가지를 확인했습니다. 그 밖의 게임 안 동작은 합주에서 확인해주세요.
- **글자 수·랭크 한도**는 MabiIcco의 표를 따르며 `score_stats`의 `rank` 기본값은 1랭크입니다. 노래 파트가 있는 트랙에는 노래 칸 포함 한도, 없는 트랙에는 노래 칸 제외 한도 1600/1200/900을 기본으로 적용합니다.
- **마이크·노래·코러스**: 마이크와 타입 V 노래 악기 80~94·120·121은 노래 칸만 연주하며 그런 트랙의 노래 제외 기준을 `song_rule: "song_excluded"`로 지정하면 경고 `song_rule_voice`가 납니다. 다른 트랙의 노래 칸을 울리는 `song_program`은 코러스 100·110만 받습니다. 마이크·노래가 소리 내는 적은 음의 범위는 좁습니다. 범위 밖 음은 `silent_note` 경고가 나고, 자세한 범위는 `bridge/README.md` "들리는 음"에 있습니다.
- **렌더**: 소리 렌더와 3MLE `.mml` 읽기, 기본 설정의 `instruments`에는 마비노기 DLS가 필요합니다. `instruments`에 `dls:false`를 주면 내장 표를 씁니다. 렌더는 JDK 내장 신시사이저로 하므로 MabiIcco 창의 WAV 내보내기와 샘플 단위로는 다릅니다. 구체적인 차이는 자동 게인, 6.8 ms보다 짧은 음, 모바일 타악기의 무작위 음에서 나며, 자세한 내용은 `BUILD.md`에 있습니다. 악기에 따라 적은 음보다 1~2옥타브 높게 울리므로, 원곡 음높이를 옮길 때는 `pitch_mode: "sounding"`을 쓰세요.
- **mabi-ear**: 유사도는 원곡과 닮은 정도를 재고 분리된 스템 기반으로 판단하는 스템 도구에는 직접 분리한 스템이 필요합니다. `lead_check`를 **전체 믹스**로 돌리면 반주의 화음 음이 노래보다 세게 잡히는 곳에서는 멀쩡한 음도 틀림으로 나옵니다. 측정한 한 곡에서 맞다고 본 음의 10 % 가까이였고, 보컬 스템으로는 1 % 안팎이었습니다. 그래서 믹스 결과는 의심 목록으로 쓰고, **스템**과 노래 전사가 있으면 그것으로 확인하세요. `phone_version`·`excerpt_ab`는 출력 경로를 생략하면 `MABIBRIDGE_OUT`과 상관없이 입력 파일 옆에 씁니다.
- **설치 폴더**는 쓰기 가능한 영문 경로여야 하며, jar를 다시 빌드하면 떠 있는 서버를 다시 띄웁니다.

## 라이선스

punchstrip은 비상업 사용 및 수정, 배포를 허용하며 PolyForm Noncommercial License 1.0.0을 따릅니다. 전문은 `LICENSE`에 있습니다. 사본을 나눌 때는 라이선스와 함께 아래 한 줄을 그대로 넣어주세요.

```text
Required Notice: Copyright noillthrive (https://github.com/noillthrive/punchstrip)
```

MabiIcco의 동작을 그대로 따른 세 파일의 해당 부분은 원 제작자 たんらる 님의 작업을 따르며, 목록과 설명은 `NOTICE`에 있습니다. MabiIcco와 그 바이너리, 게임 음원은 이 저장소에 들어 있지 않고 각 권리자의 것입니다. 함께 들어 있는 제3자 라이브러리는 Gson뿐이며 `NOTICE`에 적혀 있습니다. JUnit·Hamcrest는 시험용 의존성으로 `lib/test/README.md`에 따라 따로 준비하세요.

## 만든 사람
- LT 크라우리제
- Anthropic Claude

## 스페셜 땡스
- MabiIcco 원 제작자 たんらる 님 (https://github.com/fourthline/mmlTools)
- MD 리세드리카 님 (https://www.mabicompose.com/)

- LT Cooper
- LT 베어섭난민
- LT 남븝 님
- 마도카 작곡 게시판의 존경하는 코더님들

## English summary

**punchstrip** lets an AI model use **MabiIcco**, the MML editor for the game Mabinogi, through two MCP servers: **mabiicco** (Java, 39 tools: headless score editing, MML size and rank checks, reference-MIDI comparison, offline WAV rendering, ensemble finishing) and **mabi-ear** (Python 3.11, 18 tools: aligning and comparing renders with the original recording, stem extraction helpers, listening excerpts, a note-by-note check of the score's lead, agreement with the singing). Tell the AI how many players the score is for and whether to use 자브키엘, give it a MIDI of the song and the original recording (separated stems work even better), and get back an `.mmi` file for MabiIcco.

- **It contains no MabiIcco code and requires an installed MabiIcco** (1.5.11 and 1.5.12 are confirmed to work). Get MabiIcco from its author: <https://github.com/fourthline/mmlTools>. Without it the bridge stops with a message saying so.
- **Requirements:** Windows 10/11 x64, Java 17+, Python 3.11 with the `py` launcher, MabiIcco, Mabinogi installed (for the `MSXspirit0*.dls` sound banks), an MCP client (tested with Claude Code).
- **Install:** give the Quick Start prompt above (퀵 스타트) to an agent that can run commands, or follow `INSTALL.md` yourself: unzip the latest `punchstrip-<version>-win.zip` to a writable ASCII path such as `C:\mabi-mcp`; set `MABIICCO_APP` to MabiIcco's `app` folder (and `MABI_DLS` to Mabinogi's `mp3` folder only if the sound banks are not found); run `smoke-test.cmd` (prints PASS/FAIL); run `analysis\run-ear.cmd --help` once; register `bridge\run-bridge.cmd` and `analysis\run-ear.cmd` as stdio MCP servers.
- **For beginners:** if you have MabiIcco, a MIDI of a song you love and the original recording, ask your agent to make an ensemble score file (`.mmi`). We hope this tool gets you interested in composing.
- **For experienced arrangers:** arranging involves a lot of work that needs hands but not ears: drafting from MIDI or stems, counting and fitting characters, transposing between instruments that sound in different octaves, rendering, comparing with the original. punchstrip hands roughly 80% of that mechanical work to the AI so that you can spend your time on the part that needs ears.
- In both cases, the final judgement is yours.
- **Signature sound:** when a score is finished, `score_finalize` adds a short lead-in "B–D–G–E" played by every track, plus silent end notes so that all players finish at the same moment. Skip them with `signature: false` and `end_padding: false`. The signature is there so that scores made with this tool can be recognised. **Using this tool to modify and distribute someone else's arrangement without permission is prohibited.**
- **License:** punchstrip allows noncommercial use, modification and distribution under the PolyForm Noncommercial License 1.0.0 (SPDX `PolyForm-Noncommercial-1.0.0`, full text in `LICENSE`). Copies carry the licence and the line `Required Notice: Copyright noillthrive (https://github.com/noillthrive/punchstrip)`. The notes on the three files that follow MabiIcco's behaviour are in `NOTICE`. MabiIcco, its binaries and the game's sound files are not part of this repository.

## 도구 목록

자세한 입력·출력은 mabiicco의 `bridge/README.md`와 mabi-ear의 `analysis/README.md`에 있습니다.

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
| `excerpt_ab` | 렌더와 원곡을 같은 마디로 잘라 듣기 비교용 파일로(`out_dir`를 안 주면 **렌더 WAV 옆** `변형\폰용\` 폴더에 쓴다, `phone: false`면 `변형\`) |
| `downbeat_check` | 1마디 시작 위상(다운비트) 확인 |
| `lead_check` | 악보 리드 음마다 원곡(믹스 또는 노래 스템)에서 같은 음이 들리는지: 일치·틀림(들린 음)·보류 |
| `vocal_agreement` | 노래 전사 파일(GAME CSV)과 악보 리드의 양방향 일치(구간별, 다른 곳 목록) |

## 들어 있지 않은 것

| 빠진 것 | 이유 | 대신 |
|---|---|---|
| MabiIcco(코드와 바이너리 `MabiIcco.jar`, 함께 오는 라이브러리) | 원 제작자의 프로그램 | 본인 MabiIcco 설치(`MABIICCO_APP`), <https://github.com/fourthline/mmlTools> |
| 마비노기 DLS 음원 | 게임 회사의 에셋 | 본인 마비노기 설치의 `mp3` 폴더(`MABI_DLS`) |
| 곡·악보·렌더·스템 | 다른 사람의 작품 | 시험은 합성 데이터로 합니다 |
