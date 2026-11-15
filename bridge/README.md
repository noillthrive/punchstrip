# punchstrip — mabiicco 브리지, 도구 39개 (브리지 0.7.0)

`run-bridge.cmd`를 인자 없이 실행하면 표준입출력(stdio) MCP 서버가 뜬다. 설치·등록(환경 변수 `MABIICCO_APP`·`MABI_DLS`, 프로젝트 파일, `smoke-test.cmd`, `claude mcp add`)은 `INSTALL.md`, 빌드는 `BUILD.md`, 소리 분석 서버 mabi-ear의 도구는 `analysis/README.md`. 아래 예의 경로 `C:\mabi-mcp\…`는 압축을 푼 곳의 예다.

> **클로드에게**: 마비노기 악기는 악보에 적은 음과 실제로 울리는 음이 다르다(피아노는 1옥타브, 바이올린은 2옥타브 위로 울린다 — 아래 "들리는 음" 절). **원곡 음높이를 옮겨 적을 때는 `score_put_notes`·`score_replace_range`에 `pitch_mode: "sounding"`을 쓴다.** 그러면 원곡 MIDI의 음 번호를 그대로 주어도 서버가 악기에 맞는 적을 음으로 바꿔 넣는다. `compare`는 기본이 들리는 음 기준이다.

> **서버 재시작**: jar를 다시 만든 뒤에는 떠 있는 MCP 서버를 **다시 띄운다**(클로드 코드 `/mcp`에서 다시 연결). 옛 서버는 이미 읽은 클래스는 옛 jar 것, 처음 쓰는 클래스는 새 jar에서 읽어 서로 맞지 않아 `NoClassDefFoundError`로 실패할 수 있다. 빌드하는 동안 서버를 쓰려면 jar 사본을 `McpClient --cp …`/`--jar …`로 띄운다(아래 "요청 파일 드라이버 McpClient").

> **요청 파일 예**: `bridge/examples/`의 합성 예 3개(`quickstart`, `timesig_tempo`, `fit_merge_import`; 요청 `.jsonl`과 응답 `.out.txt`). 아래 "요청 파일 드라이버 McpClient"로 돌린다. 곡이나 다른 사람의 악보는 넣지 않았다.

## 표기 규칙 (모든 도구 공통)

- **틱(tick)**: 4분음표 = 96. `tick`과 `len`은 **격자 위**여야 한다: 6의 배수(64분음표 격자) 또는 16의 배수(셋잇단 격자, — `tick`은 그 마디 시작 기준). 아니면 거부하고, `"align": true`면 가장 가까운 6의 배수로 맞춘다. 아래 "틱 격자".
- **위치(pos)**: `"마디:박:틱"`, 마디·박은 1부터. 예 `"28:3:48"`.
- **길이(len)**: 정수 틱 또는 MML 길이 문자열 `"4"`, `"8."`, `"4&16"`.
- **음높이(pitch)**: MIDI 번호(60 = C4) 또는 이름 `"C4"`, `"F#4"`, `"Bb3"`. 응답에는 `pitch`와 `name` 둘 다. `pitch`는 **적은 음**(악보에 적힌 음)이다.
- **들리는 음**: 악보 음표를 돌려주는 모든 응답(`score_notes`, 쓰기 응답의 `affected`, `OVERLAP`·`OUT_OF_RANGE` 오류의 음표, compare의 `overlaps`)의 음표마다 `sounding_pitch`(실제로 울리는 MIDI 번호)·`sounding_name`·`silent`가 붙는다. 노래 파트는 트랙의 노래 악기(`song_program`) 기준. 무음 구간에 적힌 음이면 `sounding_pitch`·`sounding_name`이 `null`이고 `silent: true`, 음높이가 없는 악기(드럼·북·심벌즈·새소리)는 둘 다 `null`이고 `silent: false`.
- **세기(vel)**: 0~15, 기본 8.
- **트랙**: 1부터. **파트**: `melody`(멜로디), `chord1`(화음1), `chord2`(화음2), `song`(노래).
- **경로**: 절대 경로를 쓴다. 상대 경로는 서버를 띄운 폴더 기준이라 헷갈린다.
- **오류**: 결과의 `isError: true`, 본문 `{"error": "<코드>", "message": "...", "details": {...}}`. 스키마에 없는 인자는 `BAD_ARGUMENT`로 거부한다. 새 코드: `PITCH_NOT_PRODUCIBLE`(pitch_mode sounding으로 준 음을 그 악기로 낼 수 없음).
- **쓰기 도구 공통 응답** (`score_put_notes`, `score_delete_notes`, `score_replace_range`): `ok`, `changed_measures`, `stats`(그 트랙의 글자 수), `compare_summary`(compare 모듈이 없거나 참조가 없으면 `null`), `compare_summary_note`(null인 이유), `added`, `deleted`, `aligned`, `affected`, `warnings`. `score_put_notes`·`score_replace_range`는 쓴 뒤 그 트랙에 같은 음 겹침이 있으면 `warnings`에 `"unison_clash: …"` 한 줄(개수·처음 위치 3곳)을 넣는다(아래 "글자 수 한도 맞추기"). `score_put_notes`·`score_replace_range`는 의 `pitch_mode`·`pitch_converted`도(아래 "악보 편집").

## 프로젝트

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `project_new` | 새 악보를 만든다(열린 악보는 닫힘). `tempo`는 32~255 — 120이 아니면 틱 0 템포로 적고(저장 파일 `tempo=0T<bpm>`, 트랙 MML 첫머리 `t<bpm>`), **120(또는 생략)은 기본값이라 적지 않는다**(글자가 들지 않고 같게 연주됨, 템포 목록은 비고 `tempo=` 줄이 빈다); 어느 쪽이든 `project_info`의 `tempo_at_start`(틱 0에서 실제로 쓰는 템포)와 `tempo_at_start_default`(틱 0 템포가 없어 기본 120인가)가 알린다. 박자 목록 `time_signatures`(아래 "박자·템포 바꾸기")도 받는다. `time_signature`와 함께 주면 `BAD_ARGUMENT` | `{"title": "새 곡", "time_signature": "4/4", "tempo": 100}`, `{"title": "못갖춘마디", "time_signatures": [{"measure": 1, "signature": "1/16"}, {"measure": 2, "signature": "4/4"}]}` |
| `project_open` | .mmi를 연다(읽기만) | `{"path": "C:\\mabi-mcp\\output\\편곡1.mmi"}` |
| `project_save_as` | 새 .mmi로 저장(기존 파일·보호 폴더는 거부) | `{"path": "C:\\mabi-mcp\\output\\편곡2.mmi"}` |
| `project_info` | 악보 요약 + 참조 로드 여부, 매핑, 스냅샷 수. `tempos`는 적힌 템포 목록(비면 120), `tempo_at_start`·`tempo_at_start_default`는 틱 0의 실제 템포와 그것이 기본값인지. `time_signature`는 틱 0에서 실제로 쓰는 박자, `base_time`은 파일 머리말 `time=`, `time_signature_overridden`은 `[time-signature]`의 틱 0 항목이 머리말을 덮었는지(예: `time=1/4` + `0=4/4` → `"4/4"`, `"1/4"`, true. 예전에는 `"1/4"`를 보였다). 같은 필드가 `project_new`·`project_open`·`project_set*` 응답에도 | `{}` |
| `project_set` | 제목·작곡자를 바꾼다. 준 값만 바뀌고 둘 중 하나는 있어야 함, 빈 문자열은 지움, 줄바꿈·제어 문자 불가(`BAD_ARGUMENT`). 파일은 저장하지 않으니 `project_save_as`로 저장. 응답은 `project_info` | `{"title": "새 곡 v3"}`, `{"title": "새 곡", "author": "편곡자"}` |
| `project_set_time_signatures` | 박자 목록을 통째로 바꾼다. 음표·템포는 틱 그대로, `pos`만 새 박자로 다시 읽힌다. 응답은 `project_info` | `{"time_signatures": [{"measure": 1, "signature": "1/16"}, {"measure": 2, "signature": "4/4"}, {"measure": 91, "signature": "5/8"}, {"measure": 92, "signature": "4/4"}]}` |
| `project_set_tempos` | 템포 목록을 통째로 바꾼다. 항목마다 `tick` 또는 `pos`와 정수 `bpm`, 틱 0 항목 필수. 응답은 `project_info` | `{"tempos": [{"tick": 0, "bpm": 150}, {"pos": "10:1:0", "bpm": 160}, {"tick": 6888, "bpm": 150}]}` |

## 박자·템포 바꾸기

원곡에 못갖춘마디(곡 첫머리의 짧은 마디)나 5/8·3/4 같은 박자 바뀜, 템포 바뀜이 있으면 악보도 같은 박자·템포 목록을 가져야 `pos`·마디 번호·`compare`가 참조 MIDI와 맞는다. **순서: 박자 먼저(`project_set_time_signatures` 또는 `project_new`의 `time_signatures`), 그다음 템포(`project_set_tempos`), 그다음 음표.** 둘 다 열린 악보만 바꾸고 파일은 `project_save_as`로 저장한다(`.mmi`의 `time=`·`[time-signature]`·`tempo=`에 들어가고 다시 열면 그대로).

**`project_set_time_signatures`** — `time_signatures`: `[{"measure": 마디, "signature": "분자/분모"}, …]`, 목록 전체를 바꾼다.

- 마디 순서대로, **첫 항목은 1마디**(틱 0), 한 마디에 하나, 바로 앞 항목과 같은 박자는 빼고 준다. 마디는 1~10000.
- 마디 번호는 **앞 항목들의 박자로 센 이 악보의 마디**(1부터)다. 참조 MIDI의 `reference_info`가 알려 주는 박자 목록(`measure`·`signature`)을 그대로 옮기면 된다. 예(합성 목록): `1/16@1, 4/4@2, 5/8@11, 4/4@12, 3/4@13, 4/4@20` → 10:1:0 = 틱 3096, 11:1:0 = 3480, 12:1:0 = 3720, 13:1:0 = 4104, 20:1:0 = 6120.
- 박자는 MabiIcco가 받는 것만: 분자 1~32 또는 64, 분모 1·2·4·8·16·32·64. 박 하나는 분모 음표(5/8이면 8분음표 = 48틱, 1/16이면 24틱)이고 `pos`의 박도 그 단위로 센다(5/8 마디는 1~5박).
- **음표·템포·마커는 절대 틱 그대로 남는다.** 이미 넣은 음표의 `pos`는 새 박자로 다시 읽힌다(예: 4/4 하나일 때 10:1:24이던 음이 위 목록에서는 11:1:0). 그래서 박자는 음표를 넣기 전에 놓는다.
- 저장한 `.mmi`에서는 첫 항목이 기본 박자 `time=1/16`(`project_info`의 `time_signature`)이 되고, 나머지는 `[time-signature]` 절에 **틱을 열쇠로** 한 줄씩 들어간다(위 목록이면 `24=4/4`, `3480=5/8`, `3720=4/4`, …). MabiIcco 화면의 눈금자는 마디를 0부터 보여 주지만(도구의 11마디 = 눈금자 10) 표시만 다를 뿐 파일 내용은 같다.
- 틀리면 `BAD_ARGUMENT`, `details`에 `index`(0부터)·`entry`(그 항목)·`reason`. 악보는 바뀌지 않는다. 항목의 수는 JSON 정수만(`"1"`·`1.5` 거부), 모르는 키도 거부.

**`project_set_tempos`** — `tempos`: `[{"tick": 틱 | "pos": "마디:박:틱", "bpm": 정수}, …]`, 목록 전체를 바꾼다.

- 항목마다 `tick`과 `pos` 중 **하나만**. `pos`는 **지금 박자 목록**으로 푼다(박자를 나중에 바꾸면 템포는 틱 그대로 남는다).
- **틱 0 항목이 꼭 있어야 한다**(곡 시작 템포). 틱 순서대로, 한 틱에 하나(`pos`로 푼 틱이 다른 항목과 같아도 거부). 틱은 음표와 같은 격자(6의 배수 또는 마디 시작 기준 16의 배수, 아래 "틱 격자"). **`bpm`은 32~255 정수**: MabiIcco 템포 대화상자가 받는 범위이고, 마비노기 쪽 해석기(`core/MelodyParser`)는 이 범위 밖의 `t`를 무시한다(MabiIcco 자료형 자체는 1~1000까지 담지만 게임에서는 조용히 무시되므로 거부한다). 밖이면 `BAD_ARGUMENT`이고 `details`에 `allowed_bpm: [32, 255]`. `project_new`의 `tempo`도 같은 범위.
- 틱 0 규칙 때문에 첫 템포가 늦게 오는 악보(예: 첫 템포가 768틱에 있는 악보)는 `project_open`으로 읽을 수는 있어도 그 `tempos` 목록을 그대로 다시 보낼 수는 없고, `[]`로 목록을 비울 수도 없다. 다시 보낼 때는 맨 앞에 `{"tick": 0, "bpm": <첫 템포의 bpm>}`(또는 원하는 시작 템포, 마비노기 기본은 120)을 직접 더한다.
- 템포는 악보 전역 목록 하나다. MabiIcco가 MML을 만들 때 **트랙마다** 이 목록을 복사해 `t` 명령을 넣으므로 모든 트랙의 MML에 들어간다: 멜로디·화음1·화음2 중 그 틱에 먼저 닿는 한 파트에 한 번, 노래 파트에는 따로 한 번. 멜로디 음이 템포 위치를 걸치면 같은 트랙의 다른 화음 파트(쉼표 자리)에 넣는다(예 출력에서 확인). **그 트랙의 마지막 음이 끝난 뒤의 템포는 그 트랙에 적지 않는다**(MabiIcco 규칙, 연주에는 영향 없음).
- 쓴 뒤 `score_stats`의 `tempo_mid_note`(템포 위치가 음 울림 안)·`tempo_end_differs`(끝 템포 ≠ 처음 템포)로 확인한다. `render_wav`와 `project_info`의 `total_sec`도 새 템포를 따른다.
- 틀리면 `BAD_ARGUMENT`, `details`에 `index`·`entry`·`reason`. 악보는 바뀌지 않는다.

**`project_new`의 `time_signatures`**: 위와 같은 형식·규칙. `time_signature`(박자 하나)와 함께 주면 `BAD_ARGUMENT`이고 열린 악보는 그대로다. `tempo`(틱 0의 BPM)와는 함께 쓸 수 있다.

**한계**: 마디 중간의 박자 변경은 놓을 수 없다(MabiIcco 박자는 마디 단위). 참조 MIDI에 그런 변경이 있으면 `reference_load`가 경고("마디 중간에 있음 — 그 앞 마디를 짧은 마디로 셈")를 내고 그 앞 마디를 짧게 세므로, 악보에서는 같은 마디 구조를 만들 수 없다(그 지점부터 마디 번호가 어긋날 수 있음).

## 틱 격자 — 64분음표와 셋잇단

MabiIcco는 MML 길이 `l1`~`l64`를 모두 쓴다(길이 n = 384 ÷ n틱, 소수는 버림, 점은 1.5배). 그래서 셋잇단도 그대로 적힌다: **2분 셋잇단 `l3` = 128틱, 4분 셋잇단 `l6` = 64틱, 8분 셋잇단 `l12` = 32틱, 16분 셋잇단 `l24` = 16틱.** 엔진은 MML을 만들 때마다 만든 MML을 다시 읽어 음표가 같은지 스스로 확인한다(`MMLTrack.generate`, 다르면 `MML_GENERATE_FAILED`).

**브리지가 받는 값**(`score_put_notes`·`score_replace_range`의 `tick`·`pos`·`len`, `project_set_tempos`의 `tick`·`pos`):

- `tick`: 6의 배수(64분음표 격자) **또는** 그 마디 시작에서 센 틱이 16의 배수(셋잇단 격자). 마디 시작 기준인 이유: 못갖춘마디가 있으면 마디 시작이 16의 배수가 아니다(1/16 마디 뒤 2마디 시작 = 틱 24, 4분 셋잇단은 24·88·152). 4/4·3/4·x/8 마디에서는 8분음표 위치가 모두 16의 배수이므로, 8분음표 자리에서 시작하는 2분·4분·8분·16분 셋잇단이 모두 들어간다. `pos`는 `"2:1:64"`·`"2:2:32"`처럼 박 안의 틱으로 준다.
- `len`: 6의 배수 또는 16의 배수. MML 길이 문자열 `"6"`(64)·`"12"`(32)·`"3"`(128)·`"24"`(16)도 된다.
- 둘 다 아니면(예: 4/4 마디 기준 40·50·100틱) 예전처럼 `BAD_NOTE`/`BAD_ARGUMENT`, `"align": true`면 가장 가까운 6의 배수로 맞춘다.

**확인한 것**(`test-bridge/mabibridge/engine/TripletGridTest`, 요청 파일 예 `c5_triplet`): 4분 셋잇단 C D E + F 2분음표 두 마디 → `MML@l6cdef2cdef2`, 8분·16분·2분 셋잇단 → `l12<cde l24fga … l3cde`, 1/16 못갖춘마디 뒤 셋잇단 → `r16l6>cdef2…`, 템포를 셋잇단 위치(틱 88)에 두기. 모두 `project_save_as` → `project_open` 뒤 틱·MML이 같다.

**엔진이 쓸 수 없는 것**: **6틱보다 짧은 길이**(음이든 쉼표든, 가장 짧은 MML 길이 `l64` = 6틱). 셋잇단 격자와 64분음표 격자를 섞으면 음 사이에 2·4틱 쉼표가 생길 수 있다(예: 셋잇단 음이 틱 152에서 끝나고 다음 음이 156에서 시작). 그러면 엔진이 MML을 만들지 못해 **`MML_GENERATE_FAILED`**("Undefined tick table: 4 — 6틱보다 짧은 음·쉼표는 MML로 쓸 수 없음 …", `details.errors[]`에 위치)이고 악보는 바뀌지 않는다. 셋잇단 뒤의 음은 셋잇단 격자 끝(박 경계 등)에 붙이거나 6틱 이상 띄운다. 6의 배수도 16의 배수도 아닌 7·10·70틱 같은 길이도 엔진은 `l54`·`l38`·`c8&c17`로 쓸 수 있지만(384 ÷ n 버림), 실수로 들어온 값을 잡으려고 브리지는 받지 않는다.

**그대로인 것**: `score_transform`의 틱 인자와 `length_scale` 반올림은 6틱 격자다(셋잇단 음에 `length_scale`·`length_grid`를 쓰면 6의 배수로 바뀐다). `reference_notes`의 `tick`·`len`은 6틱 반올림 값이다(위 "격자 힌트").

## 트랙·악기

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `track_add` | 트랙을 더한다(최대 24개). 응답 `track`이 새 번호. `song_program`(다른 트랙 악기의 노래 칸을 울릴 악기)은 MabiIcco처럼 **코러스(타입 C, 100·110)만** 받는다 — 노래·마이크(타입 V, 80~94·120·121)는 `BAD_ARGUMENT`(그 트랙의 `program`으로 둔다) | `{"name": "멜로디", "program": 0, "panpot": 64}` |
| `track_set` | 트랙 설정을 바꾼다(준 값만) | `{"track": 1, "program": 18, "song_rule": "song_included"}` |
| `track_list` | 트랙 요약 목록 | `{}` |
| `track_import_mml` | `MML@멜로디,화음1,화음2[,노래];` 글(또는 자브키엘 악보집 `sheets`)로 트랙을 더한다. MabiIcco 자신의 해석기, 글의 `t`는 악보 전역 템포로. 아래 "MML 글 가져오기" | `{"mml": "MML@t90o5l4cdef,o4l2ce,o3l1c;", "program": 21, "name": "피아노"}`, `{"sheets": ["MML@v10o5l16cdef,o3l4c;", "MML@gab>c,eg;"], "program": 20, "insert_at": 2}` |
| `instruments` | 악기 목록과 음역, 악기마다 들리는 음(`sounding`)·들리는 음역(`sounding_range`). 처음엔 음원(DLS)을 읽어 몇 초 걸림 | `{"type": "N"}` 또는 `{"dls": false}` |

**세기 규칙 `velocity_rule`**: `instruments`의 악기마다와 트랙 요약마다 `velocity_rule`·`velocity_max`. MabiIcco `InstType.convertVelocityMML2Midi` 그대로: 일반·드럼·노래·코러스·모바일 타악기(타입 N·D·V·C·M)는 `"x8"`(v를 15에서 자르고 ×8, v15 = MIDI 120, `velocity_max` 15), **타악기 P(스네어드럼·큰북·작은북·심벌즈 65~68, 핸드벨 69~76)와 실로폰 K(77)는 `"x11"`**(v를 11에서 자르고 ×11, v11 = 121, `velocity_max` 11), 타입 0은 `"none"`. 그래서 실로폰 v11 ≈ 일반 v15, v9 ≈ v12, v7 ≈ v10 — 실로폰을 리드보다 "3 낮게" 적어도 실제로는 같거나 더 크다. 세기는 MIDI 값으로 비교한다.

**드럼 키 표 `drum_keys`**: `instruments`의 드럼(27) 항목에만 `drum_keys`(15개, 키 순서)·`drum_level_rule`·`drum_level_reference`·`drum_in_game`. 키마다 `key`, `id`(kick, low_tom, snare, rim, side_stick, ghost, mid_tom, high_tom, open_hat, pedal_hat, closed_hat, crash, crash_choke, ride, ride_cup), `class`(kick·tom·snare·hat·crash·ride), `name_en`·`name_ko`, `dls_name`(DLS 샘플 이름), `mml`(o2f 등), `level_db_vs_kick`(같은 세기에서 킥 41 대비 K-가중 400 ms 소리 크기, dB: 로우 탐 43 +2.2, 미들 탐 52 +1.3, 하이 탐 53 +0.4, 림샷 47 +0.2, 스네어 45 −0.2, 크래시 60 −4.6, 사이드 스틱 48 −7.8, 고스트 50 −8.6, 오픈 하이햇 55 −10.8, 페달 하이햇 57 −13.1, 닫힌 하이햇 59 −14.0, 라이드 64 −16.8, 크래시 초크 62·라이드 컵 65는 `null` = 재지 않음), `peak_dbfs_v12`·`k400_db_v12`(v12 한 타 측정값), `suggested_vel`(킥 0·스네어 −2·탐 −3 dB 등을 목표로 한 보정 세기), `in_game_note`. 세기 v의 크기는 `level_db_vs_kick + 40·log10(v/12)`(v6 → v12 +12 dB, v12 → v15 +3.9 dB, 20·log이 아님). 하이햇·라이드는 v15로도 킥보다 10 dB 넘게 작다. **킥 41은 인게임에서 v15로도 작다**(게임 믹스·스피커가 30~120 Hz를 거의 내지 않음): 로우 탐 43(v9, 1·3박)이나 고스트 50을 겹친다. 우리 관례: 59 = 닫힌 하이햇(다른 사람식), 57 = 페달 하이햇(DLS 이름은 반대로 FootHihat·ClosedHihat). 표는 한 파일 `src-bridge/mabibridge/engine/drum27_keys.json`(jar에 들어감)에서만 읽고, mabi-ear `stem_drum_hits`도 같은 파일을 쓴다.

**트랙 요약**(`track_add`의 `summary`, `track_set`, `track_list`, `project_info.tracks`)에 필드 `sounding`(트랙 악기의 짧은 요약: `"+1옥타브"`, `"+2옥타브"`, `"적은 대로"`, `"구간별"`, `"고정음 C4"`, `"음높이 없음"`, `"효과음"`, `"미확인(적은 대로 가정)"`)과 `song_sounding`(노래 악기가 있으면 그 요약, 없으면 `null`).

**`instruments`의 악기마다**: `sounding` = `{kind, offset_semitones, summary, regions[{from, to, offset, timbre}], silent[{from, to}], fixed_pitch, fixed_name, verified, detail}`(`kind`: `offset`·`regions`·`fixed`·`unpitched`·`sfx`·`unknown`; `from`·`to`는 적은 음, `null`은 열린 끝), `sounding_range` = 연주 가능한 적은 음역(`range`)을 들리는 음으로 옮긴 범위의 목록 `[{timbre, written_low, written_high, low, high, low_name, high_name}]`(구간 악기는 세트마다 하나, 음높이 없는 악기(`kind` `unpitched`·`sfx`)는 빈 목록). 음역을 모르는 악기(`range`가 null — DLS에 없는 30~34 "10th" 악기 등)는 들리는 음 규칙은 있으므로 **끝값이 모두 null인 항목 하나**(`[{timbre: "default", written_low: null, written_high: null, low: null, high: null, low_name: null, high_name: null}]`)가 나온다. 빈 목록은 "음높이 없음", null 항목은 "음역 모름"으로 읽는다. 응답 끝에 `sounding_source`(표의 출처).

## 참조 MIDI

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `reference_load` | 참고용 MIDI를 연다. 깨진 파일이면 트랙·오프셋을 알려 줌 | `{"path": "C:\\music\\reference.mid"}` |
| `reference_info` | 연 참조의 요약(다시 읽지 않음). 트랙마다 `melody_score`·`components`·`melody_rank`·`role_guess`(아래 "참조 힌트") | `{}` |
| `reference_notes` | 참조 음표를 마디 범위로(기본 최대 400개, 넘으면 `truncated`). 음마다 `pedal_end`·`pedal`, 박마다 `grid_hints`(선택 `triplet_ratio`·`straight_tol`) | `{"tracks": [1, 2], "from_measure": 27, "to_measure": 34, "max": 400}`, `{"tracks": [14], "from_measure": 27, "to_measure": 34, "triplet_ratio": 0.6}` |
| `reference_chords` | 박마다 울리는 음이름 집합(드럼 제외). `grid_hints` | `{"from_measure": 27, "to_measure": 34}` |
| `reference_voice_draft` | 참조 트랙들의 음을 1~3성부(멜로디·화음1·화음2) 초안으로 나눈다(읽기만). `voices[].notes`는 `score_put_notes`에 `pitch_mode: "sounding"`으로 그대로 들어감 | `{"tracks": [18, 19], "from_measure": 27, "to_measure": 34, "voices": 3}` |
| `reference_regrid` | 음(과 다른 이벤트)을 벽시계 시각에서 고정 격자로 다시 놓고 템포 지도를 버린다. 원본은 남음, `reset: true`로 되돌림 | `{"bpm": 100, "first_downbeat_sec": 0.608, "offset_sec": 0.306}`, `{"reset": true}` |
| `reference_cleanup` | 참조 트랙 하나 정리(양자화 → 옥타브 접기 → 중복·겹침 → 윗줄 또는 성부 수 → 짧은 음). `dry_run` | `{"track": 2, "quantize": 16, "max_polyphony": 1, "min_length_ticks": 12, "fold_to_range": [28, 55]}` |

### 참조 MIDI 다시 놓기·정리

AI 전사 MIDI는 마디마다 템포 이벤트가 있어도 오디오를 따르지 않는 일이 많다(곡은 고정 템포인데 MIDI 템포 지도는 몇 % 흔들림). 그러면 MIDI 마디·박 위치가 오디오 격자에서 1~2 16분 흔들린다. `reference_regrid`는 음마다 **MIDI 자신의 템포 지도로 잰 초** `sec = secondsAt(srcTick) + offset_sec`(오디오 시각 = MIDI 시각 + `offset_sec`)를 고정 격자 `tick = tick0 + (sec − first_downbeat_sec) / (60/bpm) × 96`에 놓는다(`tick0` = `measure_offset` 마디의 시작 틱). 음 끝도 같은 식. 시작이 0틱보다 6틱 넘게 앞인 음은 버리고(`dropped_before_start`), 그 사이면 0틱(`snapped_to_zero`). 음 아닌 이벤트(트랙 이름·프로그램·CC·포트·조표·마커)도 같은 식으로 옮기고(앞이면 0틱), 템포·박자는 `bpm`·`time_signature` 하나로 바꾼다. 결과는 4분음표 = 96틱 SMF로 다시 써서 다시 읽은 참조이므로 `reference_notes`·`reference_chords`·`reference_voice_draft`·`compare`가 그대로 쓴다(다시 읽을 때 MabiIcco 가져오기 규칙대로 6틱에 반올림). 양자화는 하지 않는다.

- 인자: `bpm`(20~400)·`first_downbeat_sec`(필수, reset이 아니면), `time_signature`("4/4"), `measure_offset`(1), `offset_sec`(0, −60~60; 렌더-원곡 정렬로 잰 MIDI 대비 오디오 늦음), `source`("wallclock"만), `reset`(true면 원본으로, 다른 인자 금지). 숫자는 JSON 수만(`"100"`은 `BAD_ARGUMENT`).
- 언제나 **원본에서** 다시 만든다(앞서 한 `reference_cleanup`은 버리고 `discarded_cleanups`로 알림). 원본 바이트는 서버 상태에 남고 `reference_load`가 새 파일을 열 때만 바뀐다.
- 응답: `regridded`, `params`, `notes_in`·`notes_written`·`dropped_before_start`·`snapped_to_zero`·`overlaps_trimmed`(같은 트랙·채널·음높이가 겹치면 앞 음을 자름), `source_tempo_events`, `source_measure_count`, `measure_count`, `tracks[{number, name, note_count, drum}]`, `stats`, `text`.
- `stats`(틱, 4분 = 96): `onset_offset_from_16th`(음 시작의 가장 가까운 16분까지 거리 — `before_own_grid`는 MIDI 자기 격자 `srcTick×96/해상도`, `after_fixed_grid`는 새 격자, 둘 다 6틱 반올림 전, 중앙값·p90·최대, `within_6_ticks_pct_before/after`, 트랙별 `tracks[]`), `note_position_shift`(음마다 새 위치 − 자기 위치; `constant_shift_ticks` = 중앙값, `wander_before` = 그 차에서 중앙값을 뺀 흔들림), `barline_wander`(원본 마디 시작을 새 격자에 놓았을 때 자기 자리와의 차의 흔들림: `wander_before` 중앙값·p90·최대, `wander_before_sixteenths_max`; 다시 놓은 뒤는 마디줄이 고정 격자 그 자체라 `wander_after` 0).

`reference_cleanup`: `track`(필수) 한 트랙의 음만 바꾼 새 참조(다른 트랙·이벤트 그대로). 순서 (1) `quantize` 16|32: 시작·끝을 가장 가까운 칸(24|12틱), 끝이 시작 이하면 한 칸 (2) `fold_to_range` [lo, hi](11 이상 차이): 밖의 음을 옥타브씩 안으로 (3) 같은 채널·음높이: 시작 차 ≤ `dedupe_ticks`(기본 0)면 하나로(끝은 긴 쪽, 세기는 큰 쪽), 겹치면 앞 음 끝을 뒤 음 시작으로 (4) `top_line: true`(시작 묶음마다 가장 높은 음, 더 높은 음이 울리는 동안 시작하는 낮은 음은 버림, 다음 줄 음에서 앞 음을 자름) 또는 `max_polyphony` N(1~16, 시작 틱마다 울리는 음이 N개를 넘으면 높은 N개만 — 넘친 새 음은 버리고 이어지던 음은 그 틱에서 자름; `top_line`과 함께 쓰면 `BAD_ARGUMENT`) (5) `min_length_ticks`(기본 0)보다 짧은 음 버림. `dry_run: true`면 수만 세고 바꾸지 않는다. 응답 `counts{notes_before, quantized_changed, folded, duplicates_merged, overlaps_trimmed, top_line_dropped/truncated 또는 polyphony_dropped/truncated, too_short_dropped, notes_after}`, `max_simultaneous_before/after`, `options`, `applied`, `derived`. 정리는 지금 참조(다시 놓은 것이든 원본이든)에 쌓이고 `derived.cleanups`에 차례로 기록된다.

`reference_load`·`reference_info`의 `derived`: `regridded`, `regrid`(값과 개수, 아니면 null), `cleanups[]`, `raw_kept`, `raw{file, resolution, tempo_events, measure_count, seconds, restore}`.

## 참조 힌트 — 멜로디 점수·격자·페달·성부 초안

편곡을 시작할 때 손으로 하던 일(어느 참조 트랙이 멜로디인지, 셋잇단이 어디 있는지, 페달로 이어지는 음이 실제로 얼마나 울리는지, 빽빽한 화음을 세 파트로 어떻게 나눌지)을 돕는 **읽기 전용 힌트**다. 악보와 참조를 바꾸지 않는다. 모두 단순 규칙이고 근거 수치를 같이 돌려주므로, 그대로 믿지 말고 확인해서 쓴다.

**멜로디 점수(`reference_load`·`reference_info`의 트랙마다)**: `melody_score`(0~1, 셋째 자리 반올림), `components`(`height`·`monophony`·`coverage`·`name_keyword`, 모두 0~1), `melody_rank`(드럼이 아니고 음이 있는 트랙 중 순위, 1 = 가장 높음, 그 밖은 `null`), `role_guess`, `role_reason`(한국어 이유), `role_evidence`(`notes_used`, `top_line_notes`, `mean_pitch`, `top_line_mean_pitch`, `mean_len_beats`, `polyphony_ratio`, `mean_group_size`, `note_monophony`, `measures_with_notes`, `instrument`, `matched_keyword`). 응답 끝 `track_hints`에 `melody_ranking`(트랙 번호, 점수 순), `roles`(역할별 트랙 번호), `rule`(가중치·열쇠말·규칙 글).

- **맨 윗선**: 같은 원본 틱에 시작한 음 묶음마다 가장 높은 음 하나. 보컬 트랙에 3도 화음을 붙여 적은 곡도 멜로디 줄로 보려고 높이·단선율을 맨 윗선으로 잰다.
- `height` = (맨 윗선 평균 음 − 48) / 36, 0~1로 자름(48 = C3 → 0, 84 = C6 → 1). 참조 MIDI 음은 원래 들리는 음이다.
- `monophony` = 맨 윗선 음 중 맨 윗선의 다른 음과 겹치지 않는 음의 비율(1/32음표 = 원본 해상도/8 이하로 겹친 것은 겹침으로 안 봄). 모든 음 기준 값은 `role_evidence.note_monophony`(참고용).
- `coverage` = 곡 전체 마디 중 이 트랙 음이 하나라도 시작하는 마디의 비율.
- `name_keyword` = 트랙 이름이나 악기 이름(GM, 첫 음 시점 프로그램)에 열쇠말이 있으면 1. 어디에 있어도 되는 것: `vocal` `voice` `melody` `lead` `main` `female` `women` `woman` `singer` `soprano` `보컬` `멜로디` `노래` `ボーカル` `メロディ` `歌`. 낱말 전체가 같아야 하는 짧은 것: `vo` `vox` `men` `man` `male` `sing`(대소문자 무시).
- `melody_score` = 0.35 × height + 0.30 × monophony + 0.20 × coverage + 0.15 × name_keyword. 드럼 트랙과 울리는 음이 없는 트랙은 0. 길이 0 음(원본 길이 0틱, 실제로 울리지 않음)은 모든 계산에서 뺀다.

**역할 `role_guess`**(위에서부터 처음 맞는 것):

| 순서 | 역할 | 규칙 |
|---|---|---|
| 1 | `drums` | 드럼 채널(10)에서 소리를 냄 |
| 2 | `unknown` | 울리는 음이 없거나, 음이 있는 마디가 곡의 5%(최소 2마디)보다 적음 |
| 3 | `bass` | 평균 음 < 48(C3)이고 단선율 > 0.8 |
| 4 | `melody` | 남은 트랙 중 묶음 평균(`mean_group_size`, 같은 시각에 시작하는 음 수의 평균) < 2.5(멜로디 + 화성 한 줄까지, 3음 블록 화음은 아님)이고 `melody_score` 1위이며 0.5 이상. 한 트랙만 |
| 5 | `pad` | 시작점(서로 다른 시작 틱)의 절반 넘게 그 순간 2음 이상 울리고, 평균 길이 ≥ 1박(4분음표) |
| 6 | `chords` | 시작점의 절반 넘게 2음 이상 |
| 7 | `unknown` | 그 밖 |

쉬운 말로: 드럼은 드럼, 낮고 한 음씩이면 베이스, 남은 것 중 가장 높고·한 줄이고·곡 내내 나오고·이름이 노래 같은 트랙이 멜로디, 여러 음을 같이 길게 누르면 패드, 같이 짧게 치면 화음이다. 1위와 2위의 점수 차가 작으면 순위는 쉽게 바뀐다 — `melody_ranking` 상위 몇 개를 `reference_notes`로 직접 들여다볼 것.

**페달(`reference_notes`의 음마다)**: `pedal_end`(96틱 기준 울림 끝, 배타)와 `pedal`(페달 때문에 note-off보다 길게 울리면 true). 서스테인 페달(CC64)은 같은 (포트, 채널)의 음에 걸린다(어느 트랙이 보냈든). note-off **바로 전**(같은 틱의 페달 이벤트는 note-off 뒤로 봄) 페달 값이 64 이상이면, 울림은 note-off 뒤 첫 해제(값 < 64)와 같은 (포트, 채널)·같은 음의 다음 시작 중 이른 쪽에서 끝난다(해제가 없으면 곡 끝). 페달이 없으면 `pedal_end = tick + len`이고, 항상 `pedal_end ≥ tick + len`. 응답에 `pedal_notes`(페달로 늘어난 음 수). 페달 이벤트는 지금처럼 `warnings.dropped_events`의 `CC#64(서스테인)`로도 센다(악보로는 가져오지 않음).

**격자 힌트(`reference_notes`·`reference_chords`)**: `grid_hints` = 범위의 박마다 `{measure, beat, pos, onsets, grid, straight_err, triplet_err}`. `onsets`는 그 박 안의 서로 다른 시작 시각 수(같은 시각의 화음은 하나). 오차는 시작들이 격자의 가장 가까운 점까지의 평균 거리(96틱 기준, 박 끝 = 다음 박 시작도 격자 점): `straight_err`는 1/4박 격자(4분음표 박이면 24틱), `triplet_err`는 1/3박 격자(32틱). **6틱 반올림 전의 정확한 값**(원본 틱 × 96 / 해상도)을 쓴다(반올림하면 셋잇단 32·64틱이 30·66틱이 되어 흐려진다). 판정: 시작이 없으면 `none`, `triplet_err < triplet_ratio × straight_err`이고 `straight_err > straight_tol`이면 `triplet`, 아니면 `straight`. 선택 인자 `triplet_ratio`(기본 0.6, 0.05~1), `straight_tol`(기본 3.6틱 = 24틱의 15%, 0~24). 응답에 `grid_rule`(쓴 값과 설명), `grid_summary`(`straight`·`triplet`·`none` 개수와 `triplet_beats` 위치 목록). `reference_notes`는 고른 음 전부(`max`로 자르기 전)를, `reference_chords`는 범위에서 시작하는 드럼 아닌 음을 본다. 셋잇단으로 나온 박은 `score_put_notes`에 셋잇단 틱 그대로(8분 셋잇단 32, 4분 셋잇단 64, 그 마디 시작 기준) 넣을 수 있다(아래 "틱 격자"). `reference_notes`의 `tick`·`len`은 6틱으로 반올림한 값이라(32 → 30, 64 → 66) 셋잇단 박에서는 마디 시작 + 박 시작 + 32·64로 다시 계산해 넣는다.

**성부 초안 `reference_voice_draft`**: 입력 `tracks`(필수, 1개 이상), `from_measure`·`to_measure`(필수), `voices`(1~3, 기본 3), `home_pitches`(성부마다 처음 기준음 MIDI, 길이 = voices, 기본 `[72, 60, 48]`·2성부 `[72, 48]`·1성부 `[72]`), `max_notes`(기본 2000, 1~20000), `use_pedal`(true면 길이를 `pedal_end`까지로, 기본 false). 고른 트랙의 범위 음(시작 마디 기준, 들리는 MIDI 음, 96틱)을 시작 틱으로 묶어:

1. 같은 시작·같은 음이 여러 트랙에 있으면 하나만(긴 길이, 큰 세기) — 나머지는 `dropped`(`duplicate`). 드럼 채널 음(`skipped_drum`)과 길이 0 음(`skipped_zero_length`)은 넣지 않는다.
2. 묶음의 가장 높은 음 → 1성부. 2음 이상이고 성부가 2개 이상이면 가장 낮은 음 → 마지막 성부. 음이 하나뿐인 묶음은 1성부로 간다.
3. 나머지 음 → 비어 있는 가운데 성부 중 그 성부의 앞 음(없으면 기준음)과 가장 가까운 곳. 음이 성부보다 많으면 묶음에 **새 음이름**(옥타브 무시)을 더하는 음을 먼저 고르고(같으면 가까운 음), 남는 음은 `dropped`(`same_pitch_class` = 같은 음이름이 이미 있음, `no_free_voice` = 새 음이름이지만 빈 성부 없음, `voices_1` = 성부 1개라 가장 높은 음만).
4. 한 성부 안에서 음이 다음 시작을 넘으면 그 시작에서 끊는다(`trimmed` = 끊은 음 수). 그래서 성부 안에는 겹침이 없다.
5. 세기 `vel` = round(velocity / 127 × 15), 1~15로 자름.
6. 음 수가 `max_notes`를 넘으면 늦은 묶음부터 통째로 잘라 `truncated: true`, `truncated_from_tick`. 자르기는 4(끊기)보다 먼저 하므로 남긴 음은 잘린 음 때문에 끊기지 않는다. **잘랐을 때 `dropped`·`dropped_count`·`trimmed`·`groups`·`input_notes`·`skipped_*`와 `summary_text`는 돌려준 범위(`truncated_from_tick` 앞)만 센다.**

**응답**: `range`, `tracks`, `voices_requested`, `home_pitches`, `pitch_mode_hint: "sounding"`, `length_source`(`note_off`|`pedal_end`), `summary_text`(예 "3성부 초안: 멜로디 35음, 화음1 4음, 화음2 30음, 버린 음 2, 끊은 음 2"), `total`, `voices[]` = `{index, part_suggestion(melody|chord1|chord2), count, lowest, highest, notes[{tick, len, pitch, vel}], positions[], source_tracks[]}`, `dropped_count`, `dropped[{tick, pos, pitch, name, track, reason, reason_text}]`, `trimmed`, `truncated`, `truncated_from_tick`, `groups`(시작 틱 묶음 수), `input_notes`, `skipped_drum`, `skipped_zero_length`, `note_format`. **`voices[i].notes`는 `score_put_notes`가 받는 형식 그대로**라 검토 뒤 `{"track": N, "part": "<part_suggestion>", "pitch_mode": "sounding", "notes": <voices[i].notes>}`로 넣으면 된다. `score_put_notes`는 `tick`과 `pos`를 같이 받지 않으므로 위치 글은 같은 순서의 `positions[i]`에, 원래 참조 트랙은 `source_tracks[i]`에 따로 둔다. 틱은 참조 기준이라 악보가 원곡과 마디가 어긋나면(`compare`의 `measure_offset`) 그만큼 옮겨서 넣는다.

**마디 범위와 정수 인자(후속, `reference_notes`·`reference_chords`·`reference_voice_draft` 공통)**: `to_measure`가 곡 끝(`reference_info`의 `measure_count`)을 넘으면 마지막 마디로 줄이고, 응답 `range`에 줄인 범위를, `note`에 `"to_measure를 79로 줄임(곡 끝, 준 값 200)"` 꼴의 한 줄을 적는다(줄이지 않았으면 `note: null`). `from_measure`가 곡 끝 뒤면 `BAD_ARGUMENT`. `from_measure`·`to_measure`·`max`·`tracks[]`·`voices`·`max_notes`·`home_pitches[]`는 **JSON 수만** 받는다(문자열 `"3"`·`["18"]`은 `BAD_ARGUMENT`, `2.0`처럼 정수 모양 소수는 받음, `2.5`는 거부).

**오류**: 참조가 없으면 `NO_REFERENCE`. `tracks` 없음·빈 배열·없는 트랙 번호, 마디 범위가 틀림(시작이 곡 끝 뒤 포함), 정수 인자가 JSON 수가 아님, `voices` 1~3 밖, `home_pitches` 길이 ≠ voices 또는 0~127 밖, `max_notes` 범위 밖, `use_pedal`이 true/false가 아님, `triplet_ratio`·`straight_tol`이 숫자가 아니거나 범위 밖, 모르는 인자는 `BAD_ARGUMENT`.

**한계**: 멜로디 점수와 역할은 트랙 전체 평균이라 한 트랙이 멜로디와 반주를 오가면 섞여 보이고, 멜로디는 한 트랙만 고른다. 격자는 박 단위라 박을 넘는 셋잇단(2분 셋잇단 등)은 못 본다. 성부 초안은 시작 틱이 같은 음만 묶으므로, 앞에서 이어지는 긴 음은 다음 묶음과 상관없이 끊기고, 음이 하나뿐인 묶음(아래 성부만 새로 치는 곳 포함)은 1성부로 가서 멜로디에 낮은 음이 섞일 수 있다. 악기 음역은 보지 않으므로 넣을 때 `PITCH_NOT_PRODUCIBLE`이 날 수 있다.

## 악보 편집

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `score_notes` | 한 트랙·파트의 음표를 마디 범위로. `part: "all"`이면 `{"track", "part": "all", "range", "count"(합), "parts": {"melody": [...], "chord1": [...], "chord2": [...], "song": [...]}}` — 트랙 악기가 쓰는 파트(류트 등 N 타입은 melody·chord1·chord2, 실로폰 등은 melody), 노래 악기(`song_program` ≥ 0)가 있으면 song, 그리고 음이 들어 있는 파트. 파트 하나를 주면 예전과 같다(`notes`) | `{"track": 1, "part": "melody", "from_measure": 27, "to_measure": 28}`, `{"track": 1, "part": "all", "from_measure": 27, "to_measure": 28}` |
| `score_put_notes` | 음표를 넣는다. strict는 겹치면 전부 거부, merge는 엔진 규칙으로 정리. `dry_run: true`면 넣어 본 칸별 글자 수(`stats.chars`, `chars_before`, `rank_limits` — `rank`·`song_rule`)만 보고 악보는 그대로(스냅샷 없음, `dirty` 그대로) | `{"track": 1, "part": "melody", "notes": [{"pos": "27:1:0", "len": "8", "pitch": "E5"}, {"tick": 10080, "len": 48, "pitch": 74, "vel": 10}], "mode": "strict"}` |
| `score_delete_notes` | 마디 범위 또는 시작 틱 목록으로 지운다 | `{"track": 1, "part": "chord2", "from_measure": 30, "to_measure": 31}` |
| `score_replace_range` | 범위 안에서 준 파트만 비우고 새로 채운다(가장 많이 씀) | `{"track": 1, "from_measure": 27, "to_measure": 27, "parts": {"melody": [{"pos": "27:1:0", "len": "2", "pitch": "E5"}], "chord1": []}}` |
| `score_copy_range` | 마디 범위를 다른 마디로 복사(트랙 모두 또는 `tracks`, 파트 모두). `transpose`(드럼·타악기는 그대로), `velocity_delta`, `mode` replace\|merge, `out_of_range` drop\|fold, `dry_run`. 자동 스냅샷(아래) | `{"from_measure": 18, "to_measure": 21, "dest_measure": 43}`, `{"from_measure": 30, "to_measure": 33, "dest_measure": 43, "tracks": [1, 2, 3], "transpose": 2, "velocity_delta": -1, "dry_run": true}` |
| `score_merge_tracks` | 같은 악기 트랙들을 한 트랙(멜로디·화음1·화음2)으로 합친다. `strategy` by_voice(기본)\|keep_parts\|priority(`order`), `max_len_ticks`, `dry_run`, `keep_sources`, `rank`·`song_rule`. 자동 스냅샷. 아래 "트랙 합치기" | `{"tracks": [6, 8]}`, `{"tracks": [6, 8], "strategy": "priority", "order": [[8, "melody"], [6, "melody"], [6, "chord1"]], "dry_run": true}` |
| `score_mml` | 마비노기용 MML과 파트별 글자 수(track 생략 = 전체) | `{"track": 1}` |
| `score_stats` | 글자 수와 두 기준(노래 제외/포함)의 한도·초과, 들리는 음역. 고른 랭크의 파트별 한도·초과 글자(`rank_limits`)와 정적 검사(`warnings`: 같은 음 겹침·템포 위치) | `{"track": 1}`, `{"track": 3, "rank": "Practice", "song_rule": "song_included"}` |
| `score_finalize` | 다 만든 악보 마무리: 앞에 머리 마디 + 조율용 시그니처, 끝을 세기 0 음으로 맞춤. 뒤에는 마디 번호가 머리 마디 수만큼 커진다. 아래 "합주 마무리" | `{}`, `{"dry_run": true}`, `{"signature": false}` |

**`pitch_mode`(`score_put_notes`·`score_replace_range`)**: `"written"`(기본, 지금까지와 같음) — `pitch`는 적을 음. `"sounding"` — `pitch`는 **들릴 음**이고, 서버가 트랙 악기(노래 파트는 노래 악기)의 표로 적을 음으로 바꿔 넣는다. 예: 피아노 트랙에 `{"pitch": 72}`(C5) → 적은 음 60(C4). 구간 악기(전자 기타 20·가야금 28·베이스 29)는 `timbre: "default"`(기본) | `"alt"`로 세트를 고른다(두 세트는 같은 들리는 음역을 덮는다. 예: 베이스 들리는 E2(40) → default 28 / alt 76). 바꾼 음이 악기 음역(DLS를 읽을 수 있으면 DLS 기준, 아니면 properties 기준) 밖이거나 무음 구간이거나 고정음·음높이 없음 악기면 아무것도 넣지 않고 **`PITCH_NOT_PRODUCIBLE`**(details: `track`, `part`, `index`, `tick`·`pos`(넣은 그대로: `pos`로 준 음은 `tick`이 null, `tick`으로 준 음은 `pos`가 null), `sounding_pitch`·`sounding_name`, `written_pitch`(구간 밖이면 null), `program`, `instrument`, `timbre`, `sounding`(describe), `producible`(낼 수 있는 들리는 음역, 세트별), `range_source`). `timbre`는 sounding일 때만, 그 악기의 세트 이름만 받는다(아니면 `BAD_ARGUMENT`). 응답에 `pitch_mode`, `pitch_converted`(바꾼 음 수, written이면 0), sounding이면 `timbre`·`range_source`.

**`score_copy_range`**: 입력 `from_measure`·`to_measure`(원본, 포함, 도구 번호 1부터)·`dest_measure`(대상 시작) 필수. `tracks`(정수 배열 또는 `"all"`, 기본 모든 트랙), `transpose`(−48~48 반음, 기본 0), `velocity_delta`(−15~15, 결과는 0~15로 자름), `mode`(`"replace"` 기본 \| `"merge"`), `out_of_range`(`"drop"` 기본 \| `"fold"`), `dry_run`(기본 false), `rank`·`song_rule`(`score_stats`와 같음, 판정만).
- 원본 범위에서 **시작하는** 음을 모든 파트(멜로디·화음1·화음2·노래)에서 값으로 먼저 읽고, 대상 위치 = 원본 + (대상 시작 틱 − 원본 시작 틱)에 넣는다. 원본·대상 범위가 겹쳐도 된다. 대상 범위 끝을 넘는 음은 끝에서 자른다(`truncated_at_end`). 원본 마디와 대상 마디의 길이(박자)가 하나라도 다르면 아무것도 바꾸지 않고 `BAD_ARGUMENT`(details `mismatch`). 템포는 복사하지 않는다(원본 안에 템포 변경이 있으면 경고 `tempo_not_copied`).
- `transpose`는 옮길 수 있는 악기(타입 N·K·V·C, MabiIcco `InstType.allowTranspose`)에만. **드럼 D·타악기 P·모바일 M은 옮기지 않고**(`transposed: false`, `transpose_note`) 위치·세기만 바꾼다. 노래 파트는 노래 악기 기준. 옮긴 적은 음이 그 악기 음역(DLS를 읽었으면 DLS, 아니면 properties, 둘 다 없으면 MIDI 11~119) 밖이면 `drop`은 빼고(`dropped`, 처음 10개 `dropped_notes`), `fold`는 옥타브로 접어 넣는다(`folded`). 무음 구간(베이스 56~61)에 떨어진 음은 넣고 `silent`로 센다.
- `replace`는 대상 범위의 고른 트랙을 모든 파트에서 먼저 비운다(대상 범위에서 시작하는 음은 지움 `cleared`, 앞에서 들어오는 음은 대상 시작에서 자름 `truncated_before` — `score_replace_range`와 같은 규칙). `merge`는 비우지 않고 엔진 규칙(겹친 앞 음은 잘리고 뒤 음은 지워짐)으로 넣고 잘리거나 지워진 기존 음 수를 `merge_conflicts`로 알린다.
- 바꾸기 전에 스냅샷을 자동으로 남기고 `snapshot_id`(`restore`로 되돌림)를 준다(`score_transform`과 같음). `dry_run: true`면 실제로 바꿔 수와 글자 수를 센 뒤 원래 상태로 되돌린다(스냅샷 없음, `dirty` 그대로, `compare_summary` 없음).
- 응답: `dry_run`, `from_measure`, `to_measure`, `dest_measure`, `dest_to_measure`, `shift_ticks`, `mode`, `transpose`, `velocity_delta`, `out_of_range`, `tracks[{track, name, program, instrument, instrument_type, transposed, transpose_note, copied, copied_by_part, dropped, folded, velocity_clamped, truncated_at_end, silent, cleared, truncated_before, merge_conflicts, chars_before, chars_after, rank_limits, dropped_notes}]`, `totals`(같은 수의 합), `snapshot_id`·`snapshot`, `changed_measures`, `warnings`(`rank_over: 트랙 n 한도 초과: …`, 같은 음 겹침 `unison_clash`, `dropped_out_of_range`, `silent_note`, `merge_conflicts`, `tempo_not_copied`, 복사할 음이 없음), `compare_summary`(쓰기 도구 공통).

**쓰기 경고 `silent_note`**: 무음인 적은 음(베이스 56~61 = G#3~C#4와 14 미만·103 초과, 노래·마이크·코러스는 소리 나는 창 밖 — 아래 "들리는 음")에 적은 음이 있으면 막지 않고 `warnings`에 `"silent_note: …"` 한 줄(개수·위치)을 넣는다.

**`score_stats`의 트랙마다**(쓰기 응답의 `stats`도): `sounding`(요약), `sounding_pitch_min`·`sounding_name_min`·`sounding_pitch_max`·`sounding_name_max`(모든 파트 음의 들리는 음역, 노래 파트는 노래 악기 기준, 음높이 있는 음이 없으면 `null`), `silent_notes`(무음 구간에 적힌 음 수).

## 글자 수 한도 맞추기

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `score_transform` | 한 트랙의 파트들·마디 범위에 일괄 변환 하나를 적용한다(범위 안에서 시작하는 음만). 적용 전에 스냅샷을 자동으로 남기고 `snapshot_id`를 돌려준다(`restore`로 되돌림) | `{"track": 2, "op": "length_scale", "params": {"ratio": 0.75}}`, `{"track": 3, "parts": ["melody"], "from_measure": 27, "to_measure": 34, "op": "velocity_quantize", "params": {"step": 2}}` |
| `score_fit_suggest` | 한도를 넘은 파트를 줄이는 방법을 제안한다(읽기만). 후보마다 정확한 글자 수 전/후와 그것만으로 한도에 드는지, 글자를 줄이는 단계만 누적한 계획(`plan`)과 그대로 보낼 `score_transform` 인자(`plan.calls`) | `{"track": 3, "rank": "Practice"}` |
| `score_split_sheets` | 한도를 넘는 트랙을 여러 장(악보)으로 나눈다(열린 악보는 읽기만). 장마다 0틱부터 다시 만든 MML, 걸친 음, `out_dir`면 `<name>_장N.mmi` | `{"track": 3, "rank": "1", "out_dir": "C:/mabi-mcp/output/sheets", "name": "song_t3"}` |

**순서**: ① `score_stats`(`rank`)로 파트마다 얼마나 넘는지(`rank_limits.parts.<파트>.over_by`) 본다 → ② `score_fit_suggest`로 무엇을 줄이면 얼마나 줄어드는지 본다(악보는 그대로) → ③ 받아들일 수 있으면 `plan.calls`를 차례로 `score_transform`으로 보내고(각 호출이 스냅샷을 남김), 음악이 너무 상하면 ④ `score_split_sheets`로 장을 나눈다. 줄인 뒤에는 `render_wav`로 들어 보고, 마음에 안 들면 `restore`.

**`score_stats`의 모양(적어 둠)**: `track`을 주면 그 트랙의 **평평한 객체**(`track`, `chars`, `limits`, `rank_limits`, `warnings` … 가 맨 위, 그리고 `score_warnings`)이고, `track`을 생략하면 `{"tracks": [트랙마다 같은 객체], "score_warnings": [...]}`이다. 그래서 `rank_limits`는 트랙을 줄 때 `.rank_limits`, 전체일 때 `.tracks[i].rank_limits`로 읽는다.

**랭크(`rank`)**: JSON 정수도 받는다(`"rank": 1` = `"1"`, `1.0`도 됨, `1.5`·불리언은 `BAD_ARGUMENT`). `score_stats`·`score_fit_suggest`·`score_split_sheets` 공통. mmlTools `ComposeRank`가 읽는 랭크 표(`properties/rank.properties`, `ResourceLoader`)를 같은 방법으로 읽는다. 이름은 `ComposeRank.getRank()` 글자 그대로 `"1"`(기본, 가장 높음)~`"9"`, `"A"`~`"F"`, 연습 랭크(지역 설정에 따라 이름이 `"연습"` 또는 `"Practice"`, 둘 다 받음). 리소스 키 `"RANK_1"` 꼴도 받는다. 기준(`song_rule`)을 안 주면 트랙의 기본 기준: `track_set`의 `song_rule`, 없으면 **노래·마이크·코러스 악기(타입 V·C, 노래 칸만 씀) 트랙은 `song_included`**(노래 칸 한도가 걸리는 유일한 기준, 0.7.0), 그 밖은 프로젝트 기준(기본 `song_excluded`). 인자로 주면 그것이 이긴다. 그래서 마이크 트랙의 노래 칸은 기본으로 노래 한도(1랭크 1200자)로 판정되고 넘으면 `score_split_sheets`가 장을 나눈다. 한도: `song_included`는 표 그대로(노래 한도 = 멜로디 한도), `song_excluded`는 각 파트 + 멜로디/3(1랭크 1600/1200/900), 노래 파트는 실을 수 없어 `limit: null`·`song_not_allowed`. 판정 글자 수는 지금까지의 두 기준과 같다(멜로디가 비고 화음이 있으면 엔진이 넣는 빈 멜로디 문자열 길이).

**`score_stats` 트랙마다 더해진 필드**(예전 `chars`·`limits`·`rank_text` 등은 그대로): `rank_limits` = `{rank, rank_key, song_rule, parts: {melody|chord1|chord2|song: {chars, limit, over_by}}, over, fits, song_not_allowed}`(`over_by`는 넘지 않으면 0), `warnings` = 그 트랙 검사(`unison_clash`·`tempo_mid_note`)의 목록(없으면 빈 목록). 응답 맨 위에는 악보 전체 검사 `score_warnings`(`tempo_end_differs`, 없으면 빈 목록)가 한 번 붙는다(트랙 하나를 물으면 그 트랙 객체 맨 위, 모든 트랙이면 `tracks` 옆). 항목:

- `unison_clash` — `{code, track, kind, count, same_start, retrigger, examples[{tick, pos, pitch, name, parts, type}](최대 10개), message}`. 한 트랙 안에서 다른 두 파트가 **같은 적은 음**을 같은 틱에 시작하거나(`same_start`), 한쪽이 울리는 동안 다른 쪽이 같은 음을 시작한다(`retrigger`, `pos`·`parts[1]`이 뒤에 시작한 음). 마비노기는 악기 하나가 그 트랙의 파트를 모두 연주해서 **뒤 음이 앞 음을 끊는다**. **브리지 렌더(`render_wav`)는 파트를 다른 MIDI 채널로 울려서 이 끊김을 재현하지 못한다** — 렌더로 들리지 않으니 이 경고로 본다. 타악기 트랙(타입 D·P·M)도 세고 `kind: "drum"`(그 밖은 `"pitched"`). 노래 파트는 노래 악기(`song_program`)가 따로 있으면 다른 악기라 빼고, 노래 제외 트랙이면 뺀다.
- `tempo_mid_note` — `{code, track, count, items[{tick, pos, bpm, track, part, note}](최대 20개), message}`. 템포 변경 틱이 이 트랙 어떤 파트 음의 울림 안(시작 < 틱 < 끝)에 떨어진다. `note`는 다른 음표 응답과 같은 형식(`sounding_pitch`·`sounding_name`·`silent` 포함, 노래 파트는 노래 악기 기준).
- `tempo_end_differs`(`score_warnings`) — `{code, first_bpm, last_bpm, last_tick, last_pos, message}`. 마지막 템포 변경의 BPM이 처음 템포(0틱)와 다르다(장을 이어 연주할 때 다음 장 시작 템포를 확인). 악보 전체 검사라 트랙마다 되풀이하지 않는다.

**`score_transform`의 op와 `params`**(틱 인자는 6의 배수. 범위 밖 값·모르는 op·모르는 인자는 `BAD_ARGUMENT`. **형식도 엄격하다**: 정수·수 인자는 JSON 수만(`{"delta": "2"}`처럼 문자열로 주면 거부, 정수 인자에 `1.5`도 거부), `keep_if_single_level`은 JSON `true`/`false`만, `mode`는 문자열만 받는다. 거부된 호출은 스냅샷을 남기지 않는다):

| op | params | 하는 일 |
|---|---|---|
| `velocity_offset` | `delta` −15~15 | 세기에 더한다. 0~15로 자른다 |
| `velocity_quantize` | `step` 2~15, `keep_if_single_level` true(기본)·false | 세기를 **가장 가까운** step의 배수로(거리가 같으면 낮은 쪽, 0~15로 자름. 예 step 3: v8 → 9, v7 → 6 / step 4: v6 → 4). 세기 종류가 줄어 `v` 명령이 준다. `keep_if_single_level`이면 그 파트(범위 안)가 세기 하나만 쓸 때 아무것도 바꾸지 않는다(모두 v8인 파트를 v9로 바꾸면 `v` 명령만 하나 는다) |
| `velocity_min_change` | `delta` 1~15 | 앞 음(바뀐 뒤 값)과 세기 차이가 delta보다 **작으면** 앞 세기로 바꾼다. `delta: 2`면 ±1 변화가 없어진다(`delta: 1`은 아무것도 바꾸지 않음) |
| `length_scale` | `ratio` 0.1~4, `min_len_ticks` 6~384(기본 6) | 음 길이 × ratio를 6틱 단위로 반올림. min_len 아래로 줄이지 않고(원래 더 짧던 음은 늘리지 않음), 다음 음 시작을 넘지 않는다. 0.75면 끊어 치는 느낌(쉼표가 늘어 글자 수는 보통 는다) |
| `min_note_len` | `ticks` 6~384, `mode` `"delete"`·`"extend"` | ticks보다 짧은 음을 지우거나, 다음 음까지 자리가 있으면 ticks로 늘린다(자리가 없으면 그대로) |
| `length_grid` | `ticks` 6~384 | 음 끝을 그 마디 시작 기준 ticks 격자의 가장 가까운 점에 맞춘다(붙임줄·점음표 줄이기). 두 격자 점 한가운데면 **뒤 점**(반은 올림, 예 ticks 24에서 끝이 12 → 24). 시작보다 앞이면 다음 격자, 다음 음을 넘으면 그 앞 격자, 그래도 없으면 그대로 |
| `drop_below_vel` | `v` 0~14 | 세기 ≤ v인 음을 지운다 |

"다음 음"은 같은 파트에서 바로 뒤에 시작하는 음(범위 밖이어도). 미세 조정 음(tuning note)은 길이 연산에서 건드리지 않는다. `length_scale`의 반올림도 반은 올림(9틱 → 12)이다. 틱 인자의 범위는 모두 6~384(`min_len_ticks`, `min_note_len.ticks`, `length_grid.ticks`), 세기 인자는 `velocity_offset.delta` −15~15, `velocity_quantize.step` 2~15, `velocity_min_change.delta` 1~15, `drop_below_vel.v` 0~14, `length_scale.ratio` 0.1~4. 입력: `track`, `parts`(기본 모두), `from_measure`·`to_measure`(기본 처음·끝), `op`, `params`. **응답**: `ok`, `track`, `op`, `params`(기본값을 채운 값), `parts`, `from_measure`·`to_measure`(실제로 쓴 범위: 안 주면 1과 이 트랙에서 음이 시작하는 마지막 마디), `notes_affected`, `by_part`, `chars_before`·`chars_after`(실제 MML 파트 글자 수, MabiIcco 생성기로 다시 만듦)·`saved`, `snapshot_id`·`snapshot`, `check`(적용 후 1랭크·트랙 기준 판정), `examples`(바뀐 음 최대 10개 `{part, tick, pos, name, change, from, to}`, change는 `velocity`·`length`·`deleted`), `warnings`(적용 후 `unison_clash`, 바뀐 음이 없으면 그렇다고). 검증 오류에서는 스냅샷을 남기지 않는다.

**`score_fit_suggest`**: 입력 `track`, `rank`, `song_rule`, `parts`(볼 파트, 기본 넘은 파트 모두). 넘은 파트마다 후보 사다리 **velocity_min_change 2 → velocity_quantize 2 → length_grid 12 → min_note_len 12 delete → velocity_quantize 3 → drop_below_vel 2 → length_grid 24**(음악이 덜 상하는 것부터)를 트랙 하나짜리 **사본**에 하나씩(그 파트에만) 적용하고 MML을 다시 만들어 본다. 응답: `before`(지금 판정), `over`, `parts[{part, chars, limit, over_by, candidates[{op, params, chars_before, chars_after, saved, notes_affected, fits_alone(이것만으로 그 파트가 한도 안), examples(3개, "마디:박:틱 음 변화")}]}]`, `plan{fits, steps[{op, params, parts, notes_affected, chars, over_parts_chars, fits, over_by_parts, over_total}], skipped[{op, params, notes_affected, over_parts_chars_before, over_parts_chars_after, reason}], chars, over_by_parts, over_total, remaining_over_by, calls, note}`. 필드: `chars` = 판정 글자 수(파트별), `over_parts_chars` = 처음에 넘었던 파트들의 글자 수 **합**(단계를 남길지 정하는 값), `over_by_parts` = 그때 한도를 넘은 파트 → 넘은 글자 수(넘은 파트만, 한도 안이면 빈 객체), `over_total` = `over_by_parts`의 합(한도 안이면 0), `remaining_over_by` = 계획 끝의 `over_total`과 같은 값(예전 이름, 처음에는 가장 많이 넘은 한 파트의 값이었음). 요약문(`text`·`note`)은 넘은 파트를 모두 적는다("한도 초과: melody 579자, chord1 336자(합 915자, …)"). 계획은 사다리를 차례로 넘은 파트 모두에 시험 적용해 **`over_parts_chars`가 줄 때만** 그 단계를 남기고 누적한다. 그대로이거나 늘면 그 단계의 효과를 버리고 `skipped`에 이유(`바뀐 음 없음`·`글자 수가 줄지 않음`·`글자 수가 늚`)와 함께 적고 다음 단계로 간다. 트랙 전체가 한도에 들면 멈춘다. `calls`는 남긴 단계 그대로의 `score_transform` 인자. 끝까지 안 되면 `fits: false`와 남은 `over_by_parts`·`over_total`), `ladder`, `text`. `score_transform`과 같은 구현·같은 생성기라 `calls`를 보내면 글자 수가 그대로 맞는다. 열린 악보는 바꾸지 않는다.

**`score_split_sheets`**: 입력 `track`, `rank`, `song_rule`, `max_sheets`(1~64, 기본 8), `carry`(기본 true), `out_dir`(절대 경로 또는 **서버 작업 폴더 기준 상대 경로** — McpClient와 `run-bridge.cmd`는 설치 폴더에서 띄우므로 `"output/sheets"`는 `<설치>/output/sheets`. 클로드 코드가 다른 폴더에서 띄웠다면 절대 경로를 쓴다. 응답의 `sheets[].path`는 절대 경로), `name`(기본 `<제목>_t<트랙>`, 경로 글자 불가), `overwrite`(기본 false). 1마디부터 욕심껏: 마디를 하나씩 늘려 모든 파트가 한도 안인 가장 긴 범위를 찾고, 그 끝에서 범위 길이의 절반까지 거슬러 가며 **어떤 파트 음도 걸치지 않는 마디 경계**가 있으면 거기서 자른다. 없으면 가장 긴 범위 끝에서 자르고 걸친 음을 `cut_notes[{part, tick, pos, pitch, name, len, kept_len, carried_len}]`로 알린다(앞 장은 경계까지로 줄이고, `carry`면 남은 길이를 다음 장 0틱에서 다시 친다). 장마다 그 범위만 담은 트랙 하나짜리 사본을 **0틱부터** 만들어(자른 자리의 템포를 0틱에 둠 — 120이면 적지 않음 —, 범위 안 템포·박자 변경은 그만큼 당김) MabiIcco 생성기로 MML을 만드므로 `t`·`v`·`o`·`l`은 장 처음에 다시 적힌다. 응답: `track`, `name`, `rank`, `song_rule`, `last_measure`, `carry`, `sheet_count`, `complete`, `remaining_from_measure`(max_sheets가 모자랄 때), `sheets[{index, from_measure, to_measure, from_tick, to_tick, start_bpm, mml, chars{melody, chord1, chord2, song}, check, fits, crossing_cut, cut_notes, path}]`, `totals`, `warnings`(1마디만으로도 넘는 장 등), `text`. 오류: `BAD_ARGUMENT`(음표 없음, name, max_sheets), `PROTECTED_PATH`, `FILE_EXISTS`(하나라도 있으면 아무것도 쓰지 않음), `IO_ERROR`.

**한계**: 줄이는 연산은 글자 수만 보고 음악을 판단하지 않는다(줄인 뒤 들어 볼 것). 계획은 사다리 순서대로 한 번씩만 시험하고(글자를 줄이는 단계만 남김) 다른 순서·조합은 찾지 않는다. 장 나누기는 온마디 단위이고 1마디부터 시작한다. 장 사본은 마커·마디선 종류를 옮기지 않는다.

## 트랙 합치기 `score_merge_tracks`

마비노기에서 트랙 하나 = 악기 하나, 파트는 멜로디·화음1·화음2(노래 파트는 노래 악기용) 세 개까지다. 같은 악기(`program`이 모두 같아야 함) 트랙 두 개 이상을 한 트랙에 담는다. **멜로디·화음1·화음2만 옮기고 노래 파트는 옮기지 않는다**(대상 트랙의 노래 파트는 그대로; 다른 원본 트랙에 노래 음이 있으면 그 트랙이 지워지면서 사라지므로 `BAD_ARGUMENT`, `keep_sources: true`면 경고만).

입력: `tracks`(필수, 2개 이상, 서로 다름), `target`(결과를 담을 트랙, `tracks` 중 하나, 기본 첫째 — 이름·음량·좌우·노래 설정은 이 트랙 것), `strategy`, `order`(priority 전용), `max_len_ticks`(합치기 전 음 길이 상한, 6의 배수), `rank`·`song_rule`(판정, `score_stats`와 같음, 기본 랭크 1·트랙 기준 → 악기 트랙 1600/1200/900자), `dry_run`(기본 false), `keep_sources`(기본 false).

- **`by_voice`(기본)**: 모든 원본 파트의 음을 모은다(같은 시작 틱·같은 음은 하나로 — 긴 쪽, 같으면 센 쪽을 남김, `duplicates_merged`). 시작 틱마다 그 틱의 음을 높은 음부터, 앞 음이 끝난(비어 있는) 성부에 melody → chord1 → chord2 순으로 넣는다. 비어 있는 성부보다 음이 많으면 **바깥 음부터**(맨 위, 맨 아래, 둘째 위, …) 남기고 안쪽 음을 뺀다(`reason` `more_than_free_voices`, 성부가 모두 울리는 중이면 `no_free_voice`). 그래서 "가장 높은 성부 → 멜로디"는 그 틱에 멜로디 성부가 비어 있을 때만이다(긴 음이 멜로디에 걸려 있으면 새 높은 음이 화음1로 간다).
- **`keep_parts`**: `tracks` 순서대로, 파트는 melody → chord1 → chord2 순으로 음이 있는 파트를 대상의 다음 빈 파트에 그대로 옮긴다. 음이 있는 파트가 3개를 넘으면 `BAD_ARGUMENT`(`details.non_empty_parts`), 악보는 그대로.
- **`priority`**: `order` = `[[트랙, 파트], …]`(파트는 melody·chord1·chord2, 트랙은 `tracks` 안, 중복 불가). 첫째 → melody, 둘째 → chord1, 셋째 → chord2 그대로. 넷째부터는 음마다 그 음 길이 전체가 비어 있는 첫 성부(melody → chord2)에 넣고, 없으면 뺀다(`no_free_voice`). 앞 쌍에 이미 넣은 음과 시작 틱·음높이가 같은 음은 하나로 친다(`duplicates_merged` — 게임에서는 한 음이 다른 음을 끊으므로). `order`에 없는 파트의 음은 뺀다(`not_in_order`).
- 한 성부 안에서 1~5틱 쉼표(MML로 쓸 수 없음 — 가장 짧은 길이 6틱)는 앞 음을 늘려 메운다(`gaps_closed`).
- 원본 트랙(대상 말고)은 지운다. 뒤 트랙 번호가 당겨지고(`renumbered`: `{"옛 번호": 새 번호 | null(지워짐)}`), compare 매핑 키와 트랙별 `song_rule`도 따라 바뀐다(`mapping_after`). `keep_sources: true`면 원본은 그대로 두고 대상만 바꾼다(복사).
- 바꾸기 전에 스냅샷을 자동으로 남긴다(`snapshot_id`, `restore`로 원본 트랙까지 되돌림). `dry_run: true`면 실제로 바꿔 세어 본 뒤 되돌린다(스냅샷 없음).
- 응답: `strategy`, `tracks`, `target`, `result_track`(지운 뒤 번호), `name`, `program`, `instrument`, `sources[{track, name, volume, panpot, notes, chars}]`, `parts{melody·chord1·chord2·song: {notes, chars, limit, over_by}}`, `fits`, `over`, `rank_limits`, `mapping[{from_track, from_part, to_part, notes}]`, `notes_in`, `notes_out`, `dropped_count`, `dropped_by_source{"T6 chord1": n}`, `dropped`(처음 20개 `{tick, pos, len, pitch, name, vel, from_track, from_part, reason}`), `duplicates_merged`, `length_capped`, `gaps_closed`, `max_len_ticks`, `keep_sources`, `removed_tracks`, `renumbered`, `snapshot_id`·`snapshot`, `warnings`(`rank_over`, 같은 음 겹침 `unison_clash`, `dropped`, `gaps_closed`, `volume_differs`(트랙 음량이 다르면 — 결과는 대상 음량), `song_not_merged`). `compare_summary`는 없다.
- 오류: `BAD_ARGUMENT`(트랙 1개·중복, 다른 악기, `target`이 `tracks` 밖, priority에 `order` 없음·다른 전략에 `order`, `order` 형식·노래 파트, `max_len_ticks`가 6의 배수가 아님, 원본 노래 파트, keep_parts 4파트 이상), `NO_TRACK`, `MML_GENERATE_FAILED`(되돌림).

## MML 글 가져오기 `track_import_mml`

다른 사람·전문가 악보는 트랙마다 `MML@멜로디,화음1,화음2[,노래];` 글로 온다. 글을 MabiIcco 자신의 해석기(`MMLTrack.setMML`, MML 가져오기 대화상자와 같음)로 읽어 트랙을 더한다.

입력: `mml`(한 장) 또는 `sheets`(자브키엘 악보집의 장들, 순서대로, 64장까지) — 둘 다 주면 `mml`이 1장. `program`(필수), `name`(기본 `Track<n>`), `song_program`(기본 −1), `panpot`, `volume`, `insert_at`(1부터, 기본 맨 뒤, 그 자리 뒤 트랙 번호가 하나씩 밀림 → `renumbered`·`mapping_after`), `rank`·`song_rule`(판정만).

- **악보집**: 장마다 파트끼리 이어 붙인다(MabiIcco 악보집 가져오기 `MMLText.join`과 같음). MML은 앞에서부터 읽는 글이라, 뒤 장은 앞 장이 남긴 `o`·`l`·`v`·`t` 상태를 이어받는다(장 경계가 토큰 한가운데여도 붙여서 읽음). 그래서 2장짜리 `["MML@v10o5l16cdef,o3l4c;", "MML@gab>c,eg;"]` = `"MML@v10o5l16cdefgab>c,o3l4ceg;"`(음·글자 수 같음).
- 공백·줄바꿈은 지운다(`whitespace_removed`). `MML@`로 시작하지 않음, MML에 없는 글자(`a`~`g`, `n`, `r`, `t`, `o`, `l`, `v`, `<`, `>`, `&`, 숫자, `.`, `+`, `#`, `-`만 됨 — `details.bad_chars`), 파트 5개 이상, `;` 뒤에 글자(여러 트랙을 한 글로 줌), 음이 하나도 없음 → `BAD_ARGUMENT`, 악보는 그대로.
- 4번째(노래) 파트에 음이 있는데 악기가 노래·코러스(V/C)가 아니고 `song_program`(코러스 C 악기)도 없으면 `BAD_ARGUMENT`. 악기가 쓰지 않는 파트(실로폰의 화음 등)는 경고 `part_not_played`.
- **템포**: 글의 `t`는 MabiIcco 가져오기처럼 **악보 전역 템포 목록**에 들어간다(같은 틱은 바뀜, 모든 트랙에 적용). `tempos`(글 안 템포), `score_tempos_before`·`score_tempos_after`, `tempo_changed`, 경고 `tempo_merged`. 글이 0틱 템포 없이 시작하면 처음은 120(게임 기본)이다 — 예: 5마디에서 처음 `t94`가 나오면 1~4마디는 120.
- `parse_warnings`: MabiIcco 해석기(`MMLEventParser`)가 stderr에만 찍고 무시하거나 건너뛰는 토큰(`v` 0~15 밖, 숫자 없는 `o`·`l`·`t`·`v`, 템포 1~1000 밖·32~255 밖, 해석 실패한 음)을 `{part, sheet, index(그 장의 파트 안 글자 위치), token, message}`로(처음 30개, 수는 `parse_warning_count`). 건너뛴 음은 시간을 진행시키지 않는다.
- 더하기 전에 스냅샷을 자동으로 남긴다(`snapshot_id`).
- 응답: `track`(새 번호), `name`, `program`, `instrument`, `instrument_type`, `song_program`, `sheets`, `sheet_chars`(장마다 파트 글자 수), `parts{melody·chord1·chord2·song: {notes, input_chars, chars}}`(`chars` = MabiIcco가 다시 만든 MML 글자 수 — 줄여 쓰기 때문에 `input_chars`와 다를 수 있음), `notes`, `rank_limits`, `fits`, `tempos`, `score_tempos_before/after`, `tempo_changed`, `parse_warning_count`, `parse_warnings`, `whitespace_removed`, `renumbered`, `snapshot_id`·`snapshot`, `warnings`(`tempo_merged`, `rank_over`, `unison_clash`, `part_not_played`, `whitespace_removed`, `parse_warnings`).

## 합주 마무리 `score_finalize` — 시그니처와 끝 맞춤

곡을 다 만든 뒤 **한 번** 부른다. **시그니처와 끝 맞춤은 이 도구만 넣는다**(다른 도구는 넣지 않는다). 있는 음은 지우거나 바꾸지 않는다. 사용자가 원하지 않으면 `signature: false` 또는 `end_padding: false`.

| 입력 | 기본 | 뜻 |
|---|---|---|
| `signature` | true | 머리 마디와 시그니처 |
| `end_padding` | true | 끝을 세기 0 음으로 맞춤 |
| `velocity` | 12 | 시그니처 음 세기 1~15, 일반 악기(세기 규칙 x8) 기준. 세기 규칙이 다른 악기(x11: 실로폰 77 등)는 같은 MIDI 세기에 가장 가까운 값으로 바꿔 적는다(v12 → v9, 응답 `tracks[].velocities`·`velocity_rule`). 그 뒤 악기별 보정 `velocity_offset.<번호>`(시그니처 표 `signature_home.properties`: 마이크 121·120은 −3 — 목소리 샘플이 같은 세기의 악기보다 훨씬 커서 세 단계 낮게 적는다, 기본 v12 → v9; 드럼 27은 +3 — 스네어 세 번도 크래시처럼 v15로)를 더하고 1~15로 자른다(`tracks[].velocity_offset`). 드럼 크래시는 언제나 15 |
| `on_existing_tuning` | `"skip"` | 틱 0에 MabiIcco 조율음(같은 음 붙임줄 4개 이상)이 있을 때: `skip`(시그니처 안 넣음) \| `prepend`(그 앞에 넣음) |
| `dry_run` | false | 같은 보고만 하고 악보는 그대로(저장하면 바이트까지 같음) |
| `rank`, `song_rule` | `"1"`, 트랙 기준 | 글자 수 판정(`score_stats`와 같음) |

**시그니처란**: 사람이 만든 합주 악보는 곡 앞에 짧은 "조율" 구간을 둔다 — 연주가 시작되기 전에 모두가 같은 소리를 듣고, 누군가의 연주가 실패했으면 곡이 시작되기 전에 알 수 있게. 이 도구는 고정된 짧은 구간 **"bridge"**를 모든 트랙에 넣는다: 들리는 음 시·레·솔(B·D·G)을 짧게, 그다음 미(E)를 길게.

- **단위 u**(틱 0 템포 — 템포가 적혀 있지 않으면 기본 120, 응답 `signature.bpm`·`bpm_default`): 85 미만 32분음표(12틱), 170 미만 16분음표(24틱), 그 이상 8분음표(48틱). 0·u·2u에 B·D·G(길이 u), 3u에 E(길이 5u) — 구간은 8u에서 끝난다.
- **머리 마디**: 틱 0 박자의 온마디 n개(n × 마디 ≥ 8u + 96틱인 가장 작은 n ≥ 1; 4/4·120이면 1마디, 2/4면 2마디, 4/4·180이면 2마디)를 앞에 넣는다. 모든 음·템포 변경·박자 변경·마커가 그만큼 뒤로 밀린다(MabiIcco 자신의 마디 넣기 `MMLScore.addTicks`, 음의 위치·길이는 그대로 — 셋잇단 격자처럼 6틱 격자 밖의 위치도 그대로). 틱 0 템포·박자는 머리 마디에도 그대로 쓰인다. **그래서 이 도구 뒤에는 마디 번호가 n만큼 커진다**(응답 `signature.measures_inserted` — 언제나 1은 아니다). 틱 0 템포가 적혀 있으면 **같은 템포를 원래 첫 틱(머리 마디 끝)에도 둔다**: MabiIcco의 마디 지우기는 지운 범위 안의 템포를 함께 지우므로, 이것이 있어야 머리 마디를 지운 뒤 원래 템포가 틱 0에 남는다. 그 `t` 명령 값은 트랙마다 3~7자(잰 값)이고 글자 수 판정에 들어가며 `signature.lead_in_tempo{status, tick, bpm, chars_total, tracks[{track, chars, by_slot}], no_room, why}`로 알린다(템포가 기본 120이라 적혀 있지 않으면 넣지 않음). **머리 마디의 쉼표는 들어가는데 이 복사 때문에 한도를 넘는 칸이 생기면 복사만 빼고**(`status: "omitted"`, `no_room[{track, slot, chars, limit, over_by}]`) 머리 마디와 시그니처는 넣는다. 그때는 경고 `tempo_copy_omitted`가 붙는다: 나중에 MabiIcco에서 머리 마디를 지우면 시작 템포가 기본 120이 되므로 알려 준 값을 다시 넣어야 한다. 박자는 MabiIcco 마디 지우기가 틱 0 박자를 지우지 않으므로 따로 둘 것이 없다.
- **트랙마다**: 음높이 악기는 B·D·G를 한 옥타브 안에서 올라가고, E는 G보다 장6도 위(낼 수 없으면 G보다 단3도 아래). 옥타브는 **악기만으로** 정한다(트랙의 음과 무관해서 어느 악보에서나 같은 소리): 네 음을 모두 낼 수 있고(들리는 음 모드와 같은 표·음역 — 베이스 무음 구간 같은 무음 음은 고르지 않음) 네 음의 평균이 그 악기의 시그니처 기준음(`src-bridge/mabibridge/engine/signature_home.properties`, 악기 번호 → 들리는 MIDI 음)에 가장 가까운 것(같으면 높은 쪽, 표에 없는 악기는 낼 수 있는 들리는 음역의 가운데). 표의 처음 값은 손으로 만든 기준 구간과 같게 정했다: 플룻 B5 D6 G6 E6, 피아노 B4 D5 G5 E6, 류트 B3 D4 G4 E5, 베이스 B1 D2 G2 E3, 여성 마이크 121 B4 D5 G5 E5(남성 마이크 120은 C#5까지만 울려 D5를 못 내므로 가장 가까운 B3 D4 G4 E4). 노래·마이크·코러스 트랙은 같은 음을 노래 칸에. 드럼(27)은 스네어(45) 세 번(세기 `velocity` + 드럼 보정 +3, 기본 15) + 크래시(60, 세기 15) — 키는 드럼 키 표(`drum27_keys.json`)에서. 고정음·음높이 없는 악기(빈 병, 핸드벨, 타악기 P 등)는 건너뛰고 이유를 적는다. 칸은 멜로디가 한도에 들면 멜로디, 아니면 그 트랙의 다른 칸.
- **글자 수**: 머리 마디도 비지 않은 칸마다 글자(쉼표)를 더한다. **머리 마디의 쉼표만으로(템포 복사 없이도) 한도를 넘게 되는 칸이 하나라도 있으면 시그니처를 하나도 넣지 않고**(`status: "skipped"`, `lead_in_over[{track, slot, chars, limit, over_by}]`, 악보 그대로) 이유를 알린다. 아니면 들어가는 트랙마다 넣고, 자리가 없는 트랙은 건너뛰며 알린다(`status: "partial"`). 이미 한도를 넘던 칸은 막지 않고 `warnings`의 `already_over`로 알린다. 어느 트랙에도 못 넣으면 머리 마디도 넣지 않는다.
- **다시 부르면**: 이 도구의 시그니처를 알아보고(`status: "already_present"`) 다시 넣지 않는다. 시그니처가 없는 트랙(앞서 건너뛴 트랙, 뒤에 더한 트랙)은 `reason`에 이름으로, `tracks[]`에 `skipped`로 알린다.
- **`skipped`는 시그니처만**: 시그니처가 `skipped`·`existing_tuning`이어도(조율음이 있거나 자리가 없음) 끝 맞춤은 그대로 한다(`end_padding: false`가 아니면).
- **빼고 싶으면**: `signature: false`로 부르거나, 이미 넣었으면 그 전 스냅샷으로 `restore`(응답 `snapshot_id`). 저장한 뒤라면 MabiIcco에서 **넣은 머리 마디 수(`measures_inserted`)만큼 1마디를 지운다**(머리 마디에는 시그니처 말고 아무것도 없다). 위의 템포 복사 덕분에 템포·박자·마커·음은 finalize 전과 같아진다(시험 `FinalizeRemovalTest`가 저장 바이트까지 같음을 확인).

**끝 맞춤**: 사람 악보는 모든 파트가 같은 순간에 끝나도록 짧은 **무음 음(세기 0)**을 둔다 — 먼저 끝난 연주자가 합주 상태에서 먼저 빠지지 않게. T_end = 모든 트랙에서 가장 늦은 음 끝. 음이 있는 트랙의 마지막 음 끝이 T_end보다 6틱 넘게 이르면 세기 0 음 하나(길이 6틱, T_end에 끝남, 그 트랙 마지막 음의 높이)를 한도에 들고 글자가 가장 적게 드는 칸에 넣는다(노래·마이크·코러스는 노래 칸). 그 앞 쉼표가 1~5틱이라 MML로 쓸 수 없으면 마지막 음 바로 뒤에서 시작해 T_end까지(7~11틱). 넣을 칸이 없으면 건너뛰고 알린다. 세기 0 음은 MML(`v0`)·저장·다시 열기·`score_split_sheets`에서 그대로 남고, 다른 음의 세기는 바꾸지 않는다(그 칸의 마지막 음이라 뒤에 오는 음이 없다). 렌더에서는 소리가 없다. 다시 부르면 그 음을 알아보고 `already_present`로 알린다. **빼고 싶으면** 끝 맞춤을 넣은 트랙(`padding.tracks[].status: "added"`, 칸 `slot`)마다 그 칸의 마지막 음(세기 0, 위치 `pos`)을 지운다(`score_delete_notes`의 `ticks`에 `tick`).

**응답**: `ok`, `dry_run`, `changed`(이번 호출이 악보를 바꿨는지 — dry_run이면 언제나 false), `would_change`(실제로 부르면 바뀌는지 — dry_run이 아니면 `changed`와 같음), `rank`, `signature{status(added·partial·skipped·already_present·existing_tuning·disabled), reason, bpm, bpm_default, unit_ticks, unit, measures_inserted, shift_ticks, measure_signature, passage_ticks, lead_in_over, lead_in_tempo, existing, tracks[{track, name, program, instrument, kind(pitched·voice·drums), status, slot, sounding(음 이름 4개 또는 snare·crash), written, velocities, velocity_rule, timbre, target_pitch, target_source(home_table·range_middle), chars_added, reason(자리가 없으면 "melody에 넣으면 melody 1612/1600자(12자 초과); …"처럼 칸마다), detail}]}`, `padding{status(done = 이번에 넣음·skipped = 넣은 것 없이 자리가 없는 트랙만·already_present = 전에 넣은 것이 있음·nothing_to_do·no_notes·disabled), end_tick, end_pos, tracks[{track, name, status(added·skipped·already_present·not_needed·no_notes), end_tick, slot, tick, pos, len, pitch, pitch_name, chars_added, reason, cost_by_slot}]}`, `chars[{track, name, song_rule, before, after, added, fits_before, fits_after, over_after}]`(칸별 실제 MML 글자 수), `snapshot_id`·`snapshot`(dry_run이 아니면 바꾸기 전에 자동으로 남김), `warnings`(`already_over`, `existing_tuning`, `song_rule_voice` — 마이크·노래·코러스 트랙에 `song_rule: "song_excluded"`를 주면: 그 악기는 노래 칸만 연주해서 끌 수 없음; 같은 경고가 `score_stats`·`score_split_sheets`·`score_fit_suggest`·`score_copy_range`에도), `text`.

## 스냅샷

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `snapshot` | 지금 상태를 저장(메모리 + `runtime/snapshots/*.mmi`) | `{"label": "27~34 편곡 전"}` |
| `restore` | 스냅샷으로 되돌린다 | `{"id": 1}` |
| `snapshot_list` | 스냅샷 목록 | `{}` |

**스냅샷 파일이 가는 곳**: `--snapshots <폴더>`를 주지 않으면 **브리지 클래스를 읽은 jar가 있는 폴더** 기준
`<jar 폴더>/bridge/runtime/snapshots/`(`Main.baseDir()`: jar의 부모 폴더, 클래스 폴더로 돌리면 그 부모 —
작업 폴더와 무관). 설치 폴더의 `MabiIccoBridge.jar`면 `<설치>/bridge/runtime/snapshots/`, **jar 사본**
(예: `bridge/runtime/copy1/MabiIccoBridge_copy1.jar`)이면 `bridge/runtime/copy1/bridge/runtime/snapshots/`이 된다(로그 기본값
`…/bridge/runtime/log/bridge.log`도 같다). 렌더 기본 폴더도 jar 폴더 기준(`MABIBRIDGE_OUT`이 없으면 프로젝트 파일의 `render.dir`, 그것도 없으면 `<jar 폴더>/output/render`)이라 사본에서는
엉뚱한 곳을 가리킨다. 사본에서는 `--snapshots`·`--render-dir`(또는 `render_wav`의 `out_dir`)를 직접 준다. 서버 시작 줄
(stderr, `McpClient`의 `.err`)의 "스냅샷 …"에 실제 폴더가 찍힌다.

## 비교 (compare 모듈 `mabibridge.compare`가 연결되어야 동작, 없으면 `NOT_WIRED`)

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `compare_set_mapping` | 악보 트랙 ↔ 참조 트랙 대응표를 통째로 바꾼다 | `{"mapping": {"1": {"melody_from": [2], "chords_from": "all"}, "2": {"melody_from": [18], "match_parts": "any"}, "3": {"chords_from": [5, 6]}}}` |
| `compare` | 참조와 마디 범위로 비교(멜로디·화음·겹침·교차·음역·글자 수). 기본은 들리는 음 기준 | `{"from_measure": 27, "to_measure": 34, "tracks": [1, 2, 3]}`, `{"from_measure": 25, "to_measure": 32, "measure_offset": 2}` |
| `compare_suggest_mapping` | 매핑을 모르는 악보에서 악보 트랙마다 참조 트랙 후보 상위 3개를 제안한다(저장하지 않음) | `{"from_measure": 25, "to_measure": 32, "measure_offset": "auto", "octave_tolerant": true}` |

**compare·compare_suggest_mapping의 선택 인자**:

| 인자 | 뜻 | 기본 |
|---|---|---|
| `pitch_mode` | `"sounding"`: 악보 음을 악기별 들리는 음으로 바꿔 원곡(참조 MIDI는 원래 들리는 음)과 비교한다. 무음 구간 음은 비교에서 빼고 `silent_notes`로 센다. 고정음·음높이 없음·효과음 악기(빈 병·핸드벨·드럼·북·심벌즈·새소리) 트랙은 음높이 비교(멜로디 일치·화음 커버리지)를 하지 않고 `pitch_compare_skipped`에 이유를 적는다(타악기 타입 D·P·M 트랙은 pitch_mode와 무관하게 언제나 `kind: "drum"`으로 적힌다). `"written"`: 적은 음 그대로(예전 동작) | **`"sounding"`**(바뀐 유일한 기본값). 인자가 없으면 매핑 항목의 `pitch_mode`(모든 항목이 같은 값일 때), 그것도 없으면 sounding |
| `measure_offset` | 정수: **악보 마디 = 참조 마디 + 이 값**(악보가 원곡보다 2마디 늦게 시작하면 2). `from_measure`·`to_measure`는 **참조 마디**이고 악보 쪽은 offset을 더해 자른다. `"auto"`: −4~+4(`auto_offset_max`로 ±n) 중 맞은 음이 가장 많은 값(옥타브 무시: 음이름이 같고 시작 차 6틱 이하인 1:1 짝 수, 매핑의 멜로디 트랙들 합 — 매핑이 없으면 악보 트랙마다 가장 잘 맞는 참조 트랙의 합; 같으면 0에 가까운 값)을 찾는다. 악보 쪽 시작 마디가 1보다 작아지면 `BAD_ARGUMENT` | 0(인자가 없으면 매핑 항목의 공통 `measure_offset`, 없으면 0) |
| `octave_tolerant` | true면 음이름이 같고 12의 배수만 다른 음(시작 차 6틱 이하)도 짝으로 인정해 `matched`로 세고, 그 수를 `octave_shifted`로 따로 적는다(틀린 음과 옥타브만 다른 음을 구분) | false |
| `auto_offset_max` | `measure_offset: "auto"`가 찾는 범위 ±n(1~64 JSON 정수). auto가 아닐 때 주면 `BAD_ARGUMENT`. 원곡 구간을 수십 마디 잘라낸 악보는 크게 준다(예: 64). **찾는 값은 범위 전체에 하나**다(구간마다 다른 차는 찾지 않음 — 잘린 악보는 구간마다 범위를 나눠 따로 부른다). 차 하나마다 짝짓기를 한 번 하므로 걸리는 시간은 후보 수(최대 2n + 1)에 비례한다 | 4 |

**auto 알림(`notes_skipped`와 compare의 요약문 "주의:")**: (1) 모든 후보 점수가 0이면 "measure_offset auto: −n~+n의 모든 후보에서 맞는 음이 0개라 0으로 비교함(…)"(예전에는 말없이 0). (2) 고른 값이 ±n 끝이면 "찾는 범위(±n)의 끝이라 더 먼 마디 차가 맞을 수 있음". (3) 고른 차의 악보 쪽 범위가 악보의 마지막 음 마디를 넘으면 "악보 쪽 범위 a~b마디가 악보의 마지막 음 마디(m)를 넘음".

호출에서 준 값이 매핑 항목의 값보다 앞선다. 응답 추가 필드: `pitch_mode`, `measure_offset_used`, `measure_offset_auto`, `measure_offset_scores`(auto일 때 차마다 점수, 아니면 null), `score_range`(악보 쪽 마디 범위), `octave_tolerant`, `pitch_compare_skipped[]`(`track`, `program`, `instrument`, `kind`(`fixed`·`unpitched`·`sfx`, 타악기는 `drum`), `reason`), `sounding_warnings[]`, `summary.silent_notes`, `summary.melody.octave_shifted`, `melody_tracks[]`의 `pitch_mode`·`measure_offset`·`silent_notes`·`octave_shift_main`(음이름이 같고 시작 차 6틱 이하인 짝에서 가장 많은 음높이 차 = 악보 − 원곡, 반음; 옥타브만 다른 wrong도 센다)·`octave_shift_counts`(차별 짝 수, 키 `"0"`, `"+12"`, `"-24"` …), `measures[]`의 `score_measure`(그 참조 마디에 해당하는 악보 마디)·`silent_notes`, 판정 항목의 `score_written`·`score_written_pitch`(`score`·`score_pitch`는 비교에 쓴 음 = sounding이면 들리는 음). `measures[].measure`와 `range`는 참조 마디이고, 요약문(`text`)은 마디 차가 있으면 "참조 25~32마디(악보 27~34마디)", "참조 27마디(악보 29마디)"처럼 두 번호를 함께 쓴다. 판정 항목의 `pos`는 참조 위치, `score_pos`는 악보 위치. 겹침·교차·음역 검사는 언제나 **적은 음** 기준이다(음역은 적은 음으로 정해져 있으므로).

**`sounding_warnings`(compare)**: `{code, track, program, instrument, count, positions(최대 10개, "마디:박:틱 적은 음"), timbres, message}`. `code`는 `"silent_note"`(무음 구간에 적혀 소리가 나지 않는 음, `count` = 음 수) 또는 `"timbre_mix"`(전자 기타·가야금·베이스 트랙에서 default·alt 두 세트를 함께 씀 — 음색이 다르니 알리는 정보, 의도일 수 있음. `timbres` = 세트별 음 수, `count`·`positions` = 적은 쪽). 비교 방식과 상관없이 악보 사실로 낸다.

**쓰기 응답의 `compare_summary`**: `pitch_mode`·`measure_offset_used`가 붙는다(`summary.union`도, 아래 "합산(중복 제거) 일치"). 호출 인자가 없으므로 매핑 항목의 `pitch_mode`·`measure_offset`(모든 항목이 같은 값일 때), 없으면 sounding·0을 쓴다. `measures`는 악보 마디이고, 마디 차를 빼서 참조 마디로 바꿔 비교한다(참조 1마디보다 앞이 되는 마디는 뺀다). `summary.silent_notes`, `summary.melody.octave_shifted`도 있다.

**매핑 항목**: `melody_from`(멜로디를 비교할 참조 트랙들), `chords_from`(화음 기준 참조 트랙들 또는 `"all"`, 생략하면 `"all"`), 선택 `pitch_mode`(`"sounding"`|`"written"`)·`measure_offset`(−64~64 정수, `"auto"`는 안 됨)(compare 호출에 그 인자가 없을 때와 쓰기 응답의 compare_summary에 쓰임, 저장했을 때만 `mapping_used`·`project_info.mapping`에 나옴), `match_parts`: `"melody"`(기본)는 악보 트랙의 멜로디 파트와만 짝을 맞추고, `"any"`는 그 트랙의 melody·chord1·chord2·song 어느 파트든 같은 음·같은 시작(6틱 이내)이면 `matched`로 센다. 한 원곡 트랙(예: 스트링 화음)을 여러 파트에 나눠 담았을 때 쓴다. `"any"`여도 짝은 1:1이고, 멜로디가 아닌 파트의 음은 `wrong`·`shifted`·`extra`로 세지 않는다(다른 원곡 트랙을 담았을 수 있으므로). 결과의 `mapping_used`·`project_info.mapping`에 `match_parts`가 늘 적힌다.

**compare 판정 규칙**:
- 멜로디 일치: 참조 음마다 ① 같은 음·시작 차 ≤ 6틱 → `matched`, (octave_tolerant면 ①' 음이름 같고 옥타브만 다름·시작 차 ≤ 6틱 → `matched`이자 `octave_shifted`), ② 시작 차 ≤ 6틱·다른 음 → `wrong`, ③ 같은 음·시작 차 7~96틱 → `shifted`, 그 밖 → `missing`. 짝 없는 악보 멜로디 음은 `extra`. "같은 음"은 pitch_mode에 따라 들리는 음 또는 적은 음.
- **길이 0 음 제외**: 원곡 MIDI에서 켜자마자 꺼지는 음(원본 길이 `src_len` = 0, 실제로 울리지 않음)은 멜로디 짝짓기와 화음 비교에서 빼고 따로 센다. 여러 참조 트랙의 같은 시작·같은 음은 하나로 보므로, 같은 자리에 길이 0 음이 두 개 있으면 1로 센다.
- 화음 구성음: 박마다 원곡에서 울리는 음이름(옥타브 무시)과 악보 전체(타악기 트랙 제외)에서 울리는 음이름을 비교.

**compare 응답의 추가 필드**: `summary.ignored_zero_length`(멜로디 짝짓기에서 뺀 길이 0 음 수), `summary.chord_ignored_zero_length`(화음 비교에서 뺀 수, 박마다 센 합), `melody_tracks[].match_parts`·`ignored_zero_length`·`matched_by_part`(matched를 악보 파트별로, 예 `{"melody": 30, "chord1": 4, "chord2": 1}`), `measures[].melody[].ignored_zero_length`·`matched_by_part`, 판정 항목의 `part`(짝이 된 악보 음의 파트), `measures[].chords[].ignored_zero_length`. 요약문(`text`)은 화음 문장을 "27~34마디(32박) 모두 …"처럼 범위와 박 수를 함께 쓰고, 뺀 길이 0 음 수와 멜로디 아닌 파트에서 맞은 음 수를 적는다. 쓰기 도구의 `compare_summary.summary`에도 `ignored_zero_length`·`chord_ignored_zero_length`가 있다.

**합산(중복 제거) 일치 `summary.union`**: `summary.melody`는 매핑 항목(악보 트랙)마다 따로 센 합이라, 원곡 한 트랙(예: 리드)을 구간별로 여러 악보 트랙에 나눠 담거나 겹침(더블링) 트랙에도 매핑하면 같은 참조 음을 항목 수만큼 세어 분모가 늘어난다. `summary.union`은 참조 음을 한 번씩만 세고, 그 음을 `melody_from`에 둔 악보 트랙 중 **어느 하나에서든** `matched`(항목별 판정과 같은 규칙, `octave_tolerant`면 옥타브만 다른 짝 포함)였으면 맞은 것으로 센다.

- 형식: `{"tracks": [{"ref_track", "total", "matched_any", "ratio", "score_tracks"}], "total", "matched_any", "ratio", "entries"}`. `tracks`는 참조 트랙 번호 순(`score_tracks` = 그 참조 트랙을 `melody_from`에 둔 악보 트랙), `ratio` = matched_any ÷ total(소수 셋째 자리, total 0이면 `null`), `entries` = 센 매핑 항목 수. 길이 0 음은 빼고(항목별과 같음), 참조 마디 범위 안의 음만.
- 맨 위 `total`·`matched_any`는 **서로 다른 참조 트랙의 같은 시작·같은 음도 하나로** 센다(`melody_from`에 트랙 여럿을 줄 때와 같은 규칙). 그래서 `tracks[].total`의 합보다 작을 수 있다(피아노 왼손·오른손이 같은 음을 함께 치는 곳 등).
- 멜로디 비교를 하지 않았으면(참조 없음, 매핑의 `melody_from` 없음, 모든 항목이 음높이 비교 제외) `null`. 기존 `summary.melody`·`melody_tracks[]`의 수는 그대로다.
- 요약문(`text`): 멜로디 항목이 둘 이상이면 "합산(중복 제거) 일치: 원곡 N음 중 M음(P%)이 매핑한 악보 트랙 중 어디서든 일치(참조 트랙끼리 같은 시작·같은 음인 K음은 한 번만 셈) — 참조 1번 a/b(악보 트랙 1·2·3), …" 한 문장.
- 쓰기 도구의 `compare_summary.summary.union`도 같은 형식이고 바뀐 마디(참조 마디로 바꾼 것)만 센다. 두 문장 요약에는 합산 수가 항목별 수와 다를 때 "합산(중복 제거) 일치 19/19(100.0%)"를 덧붙인다.

**`compare_suggest_mapping`**: 입력 `from_measure`, `to_measure`(필수, 참조 마디), `tracks`(선택, 악보 트랙), 선택 인자 `pitch_mode`·`measure_offset`·`octave_tolerant`(위 표, 매핑 항목 값은 쓰지 않음). 악보 트랙마다 참조 트랙 전부(범위 안에 길이 있는 드럼 아닌 음이 있는 트랙)와 `match_parts: "any"` 규칙으로 짝을 맞춰 일치율 = 맞은(`matched`) 참조 음 ÷ 참조 음 수(길이 0 음 제외)를 계산한다. 참조 트랙 하나씩 따로 맞춘다(트랙끼리 악보 음을 다투지 않음). 응답: `range`, `match_parts`(`"any"`), `ref_tracks`(후보로 본 참조 트랙), `tracks[]`(`track`, `name`, `program`, `instrument`, `drum`, `score_notes`(범위 안 모든 파트 음 수), `suggestions[]`(아래 순위로 최대 3개, 맞은 음이 0인 트랙은 뺌: `ref_track`, `name`, `ratio`(소수 셋째 자리), `matched`, `total`, `by_part`, `ignored_zero_length`, `score`·`small_sample`), `note`(제안이 없을 때 이유), `small_sample_threshold`(그 트랙에 쓴 small_sample 기준 수, 후보를 매기지 않은 트랙은 `null`)), `suggested_mapping`(1위로 만든 대응표, 그대로 `compare_set_mapping`의 `mapping`에 넣을 수 있음), `notes_skipped`, `text`. 타악기 악보 트랙은 제안하지 않는다(sounding이면 고정음·음높이 없음·효과음 악기도). 추가: 응답 `pitch_mode`, `measure_offset_used`, `measure_offset_auto`, `measure_offset_scores`, `score_range`, `octave_tolerant`; `tracks[].sounding`(트랙 악기 요약); 후보마다 `octave_shift`(악보의 비교 음이 원곡보다 몇 옥타브 높은지, −2~+2. 비교 음을 옥타브 단위로 옮겨 엄격하게(같은 음) 맞춰 보고 가장 잘 맞는 차, 같으면 0에 가까운 쪽. **sounding에서 0이면 들리는 음이 원곡과 같다**)·`octave_shift_ratio`·`octave_shift_matched`(그 차에서의 일치율·맞은 수)·`octave_shifted`(matched 중 옥타브만 다른 짝, octave_tolerant일 때); `suggested_mapping` 항목에 `pitch_mode`·`measure_offset`. 그대로는 하나도 안 맞고 옥타브를 옮겨야만 맞는 악보 트랙은 그 후보들을 옮겨서 맞은 수(`octave_shift_matched`)로 계산한 같은 점수 순으로 보여 주고 `note`에 그렇다고 적는다. **옥타브를 옮겨 적은(또는 옮겨 울리는) 편곡에서 트랙 짝을 찾을 때는 `octave_tolerant: true`**를 쓴다(일치율에 옥타브만 다른 짝도 들어가 제 짝이 1위가 된다). 오류: `NO_PROJECT`, `NO_REFERENCE`, `NO_TRACK`, `BAD_ARGUMENT`, `NOT_WIRED`.

**제안 순위**: 일치율(`ratio`)만으로 매기면 참조 음이 1~5개뿐인 짧은 트랙이 `1/1 = 100%`로 1위가 되어, 진짜 짝인 긴 트랙(예: 88음 중 87음 일치)이 2위로 밀린다. 그래서 후보마다
- `score` = `matched − 0.5 × (total − matched)`(맞은 음은 1점씩 더하고, 안 맞은 참조 음은 0.5점씩 뺀다)
- `small_sample` = `total < small_sample_threshold`, 기준 수 `small_sample_threshold` = `min(max(8, ceil(score_notes × 10%)), score_notes)`(악보 트랙 음이 8개보다 적으면 그 수가 상한이라, 악보 음을 모두 덮는 참조 트랙은 small_sample이 아니다. 예전에는 상한이 없어 5음짜리 플루트 트랙의 진짜 짝 `5/5`가 small_sample로 밀리고 `1/16`(score −6.5)이 1위가 됐다)

를 매기고, 정렬은 **네 묶음 ① `small_sample`이 아니고 `score > 0` → ② `small_sample`이고 `score > 0` → ③ `small_sample`이 아니고 `score ≤ 0` → ④ `small_sample`이고 `score ≤ 0`**, 묶음 안은 **`score` 높은 순 → `ratio` 높은 순 → `matched` 많은 순 → 참조 번호 작은 순**이다. 옥타브를 옮겨야만 맞는 트랙(위)은 `score`의 `matched` 자리에 `octave_shift_matched`를 넣어 묶음과 순서를 같은 규칙으로 정한다(보고하는 `score`는 언제나 `matched` 기준). `ratio`는 그대로 보고한다. `small_sample` 후보는 `text`에 "참조 음이 적어 순위 뒤로"라고 적는다.

**실로폰(77, 타입 K)**: 음높이가 있는 타악기라(들리는 음 +2옥타브) 일반 악기처럼 멜로디 비교·매핑 제안·`measure_offset: "auto"`·화음 커버리지·음역 검사에 넣는다(파트는 멜로디 하나뿐이라 성부 교차는 생기지 않는다). `tracks[].drum`은 false. 타악기로 빼는 타입은 드럼 D·타악기 P·모바일 타악기 M이다.

**성부 교차는 정보**: 위 파트 음이 아래 파트보다 낮아지는 곳은 전문 편곡에서도 흔하다. `summary.crossings`와 `measures[].crossings`는 그대로 세지만, 요약문(`text`)의 "문제가 많은 마디" 순위와 마디별 문제 목록에서는 빼고, "참고: 성부 교차 N곳(위 성부 음이 아래 성부보다 낮아지는 곳, 전문 편곡에서도 흔함)"으로 따로 한 문장 쓴다(없으면 "참고: 성부 교차 없음"). 상세 형식(하나로 맞춤): `참고: 성부 교차 N곳(위 성부 음이 아래 성부보다 낮아지는 곳, 전문 편곡에서도 흔함): 트랙 T K곳(처음 위치 설명[, 위치 설명…])[, 트랙 …][ 외 M개 트랙]` — 교차가 있는 트랙이 하나면 그 트랙 예시를 시작 순으로 3개까지, 여럿이면 많은 순 3트랙까지 트랙마다 첫 예시 하나. 위치 설명은 `마디:박:틱 멜로디 E4가 화음1 G4보다 낮음` 또는 `마디:박:틱 화음1 C4가 화음2 D4보다 낮음`. 교차 위치(`measures[].crossings[].pos`와 문장 속 "처음 27:4:78" 같은 위치)는 참조가 아니라 **악보 위치** 기준이다(마디 차가 있어도 악보 마디 번호. 교차가 담기는 `measures[]` 항목은 참조 마디). 쓰기 응답의 `compare_summary.text`도 문제 목록(음역 이탈·겹침·글자 수 등)에서 교차를 빼고, 교차가 있을 때만 둘째 문장 끝에 " — 참고: 성부 교차 N곳(전문 편곡에서도 흔함)"을 붙인다(두 문장 이내 유지).

**한계**: 마디 차는 곡 전체에서 하나(트랙마다 다른 값은 매핑 항목으로만)이고 박자 구성이 다르면 어긋날 수 있다. `auto`는 기본 −4~+4만 본다(`auto_offset_max`로 ±64까지). 구간별로 다른 마디 차(`measure_offsets: [{from, to, offset}]`)는 아직 없다. 성부 교차는 적은 음 기준이다.

## 들리는 음 — 적은 음과 실제로 울리는 음

마비노기 악기는 악보에 적은 음과 실제로 울리는 음이 악기마다 다르다. 브리지는 이 표(`src-bridge/mabibridge/engine/sounding_pitch.properties`)로 쓰기(`pitch_mode`)·읽기(`sounding_*`)·비교(`compare`의 sounding)를 한다. 숫자는 적은 음의 MIDI 번호(MML `o4c` = 60).

| 들리는 음 | 악기 번호 |
|---|---|
| +2옥타브 | 22 바이올린, 23 첼로, 25 튜닝 바이올린, 26 튜닝 첼로, 77 실로폰, 90~94 여성의 목소리, 110 환상의 코러스(여성), 121 밀키웨이 마이크(여성) |
| +1옥타브 | 19 리라, 21 피아노, 24 하프, 53 축제 휘슬, 54 축제 플루트, 55 튜닝 플루트, 56 튜닝 휘슬, 80~84 남성의 음성, 100 환상의 코러스(남성), 120 밀키웨이 마이크(남성) |
| 적은 대로 | 0 류트, 1 우클렐레, 2 만돌린, 3 휘슬, 4 론카도라, 5 플룻, 6 샬루모, 18 피시스 튜바, 50 축제 류트, 51 축제 우쿨렐레, 52 축제 만돌린 |
| 구간별 | 20 전자 기타·28 가야금: 적은 음 63(D#4) 이하 +2옥타브(`default`, 맑은 음), 64(E4) 이상 −2옥타브(`alt`, 튜닝음 세트, 음색 다름) / 29 베이스: 55(G3) 이하 +1옥타브(`default`), 56~61(G#3~C#4) **무음**, 62(D4) 이상 −3옥타브(`alt`) |
| 고정음 | 7~13 빈 병(7 C4, 8 D4, 9 E4, 10 F4, 11 G4, 12 B3, 13 A3), 69~76 핸드벨(C5~C6) — 적은 음과 무관하게 한 음 |
| 음높이 없음 | 27 드럼, 65 스네어드럼, 66 큰북, 67 작은북, 68 심벌즈 |
| 효과음 | 14~17 새소리 카드 |
| 미확인 | 표에 없는 번호(모바일 `x.2`, 30~34): 적은 대로라고 가정하고 `verified: false` |

**소리 나는 적은 음 창**(0.7.0, 표의 `audible.<번호>=lo..hi`): 렌더로 적은 음 11~119를 하나씩 쳐 보아 무음이던 음. 베이스 29는 14~103 밖도 무음(56~61에 더해), 남성 음성 80~84·마이크 120은 29~61, 여성 목소리 90~94·마이크 121은 24~57, 코러스 100은 26~58, 110은 31~60 밖이 무음이다. 창 밖에 적은 음은 `pitch_mode` written이어도 음표마다 `silent: true`, `silent_notes`에 세고, 쓰면 `silent_note` 경고, `sounding`이면 `PITCH_NOT_PRODUCIBLE`. `instruments`의 `sounding`에 `audible_written{from, to, from_name, to_name}`(창이 없는 악기는 null)과 창 밖 구간이 `silent`에 더해진다. 일반 악기는 넣지 않았다(예: 플룻 5는 DLS 음역 60~95 밖 27~100도 렌더에서 울린다). 시험 `SilentRangeRenderTest`가 같은 렌더로 표를 다시 확인한다(DLS 없으면 SKIP).

출처: 악기마다 render_wav로 렌더한 WAV의 기본 주파수 측정과 MSXspirit01~05.dls 샘플 분석. 두 조사가 같은 결론이고 측정일은 2026-11-01(창은 2026-11-15). 렌더(Gervill + 마비노기 DLS) 기준이며 게임 클라이언트 소리로 확인하지는 않았다. 표를 고치는 방법은 `BUILD.md` "8. 들리는 음 표".

**클로드용 권고**: (1) 원곡 MIDI의 음을 옮겨 적을 때는 `pitch_mode: "sounding"`으로 넣는다(원곡 음 번호 그대로). (2) 적을 음을 직접 고를 때는 `instruments`의 `sounding_range`로 들리는 음역을 확인한다. (3) 베이스 56~61은 소리가 나지 않으니 쓰지 않는다(`silent_note` 경고). (4) 전자 기타·가야금·베이스는 두 세트의 음색이 달라 한 트랙에서 섞으면 `timbre_mix`로 알려 준다. (5) 남의 편곡을 분석할 때는 `compare_suggest_mapping`에 `measure_offset: "auto"`와 `octave_tolerant: true`를 함께 주고, 후보의 `octave_shift`로 그 트랙이 원곡보다 몇 옥타브 높게 울리는지 본다.

## 렌더

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `render_wav` | 지금 악보(또는 일부)를 오디오 장치 없이 WAV로 렌더하고 옆에 메타 JSON을 쓴다 | `{"from_measure": 27, "to_measure": 34, "split": "track", "name": "song_v2_27-34", "out_dir": "C:/mabi-mcp/output/render"}` |

**입력**(모두 선택):

| 인자 | 뜻 | 기본 |
|---|---|---|
| `from_measure`, `to_measure` | 마디 범위(1부터, 끝 포함) | 1 ~ 음표가 있는 마지막 마디 |
| `tracks` | 트랙 번호 배열 | 전체 |
| `parts` | `melody`·`chord1`·`chord2`·`song` 배열 | 전체 |
| `split` | `none`(파일 하나) · `track`(트랙마다) · `part`(트랙×파트마다) | `none` |
| `tail_sec` | 기준 끝 뒤 꼬리(여운)의 상한, 0~60초. 소리가 다 사라지면 더 일찍 끝 | 5.0 |
| `name` | 파일 이름 앞머리(경로·확장자 없이) | `<제목>_m<from>-<to>_<yyyyMMdd-HHmmss>`(곡 전체면 `_all_`) |
| `out_dir` | 출력 폴더 절대 경로 | 서버의 `--render-dir`(기본 `<MABIBRIDGE_OUT>\render`, 없으면 프로젝트 파일의 `render.dir`, 그것도 없으면 `<jar 폴더>\output\render`) |
| `overwrite` | 같은 이름 파일을 덮어쓸지 | false |

- 파일 이름: `<name>.wav`(none), `<name>__t02.wav`(track), `<name>__t02_chord1.wav`(part). 각 WAV 옆에 확장자만 `.json`인 메타.
- 범위 규칙 `start_in_range`: from 마디 시작 ≤ 음 시작 < to 마디 끝인 음만 넣고, 그 음의 끝(노트오프)은 범위를 넘어도 넣는다. 범위 앞에서 시작해 걸친 음은 넣지 않는다. 렌더 시각 0 = from 마디 시작.
- 길이: **기준 끝**(범위 끝과 마지막 노트오프 중 늦은 쪽)까지는 반드시 렌더하고, 그 뒤 활성 보이스가 없고 블록이 전부 0이면 멈춘다. `tail_sec`가 상한.
- 트랙 단위 뮤트/솔로는 무시한다. `split`이면 파일마다 신시사이저를 새로 연다(잔향·보이스 뺏기가 섞이지 않게).
- 포맷 44.1kHz · 16bit · 스테레오. 정규화·페이드 없음.

**응답**: `ok`, `split`, `out_dir`, `range`(`from_measure`, `to_measure`, `from_tick`, `to_tick`, `note_rule`, `note_start_offset_tick`, `track_mute_ignored`, `whole_song`, `range_sec`), `measures`(마디 수), `files[]`(`path`, `meta_path`, `tracks`, `parts`, `duration_sec`, `peak_dbfs`, `rms_dbfs`, `tail_sec_actual`, `notes`, `render_ms`), `skipped[]`(음표가 없어 건너뛴 트랙), `warnings[]`, `render_ms`, `audio_sec`, `speed_x_realtime`.

**메타 JSON**(`schema: "mabibridge.render/1"`): `rendered_at`, `engine`, `project{title, source_path}`, `dls_files`, `audio{sample_rate, bits, channels, duration_sec, peak_dbfs, tail_sec_actual, rms_dbfs, frames, tail_sec_limit, end_base_sec, hit_tail_limit}`, `synth{class, midi_channels, large_mode, max_polyphony, load_default_soundbank, interpolation, control_rate, reverb, chorus, auto_gain_control, light_reverb, jitter_correction, latency, device_id}`, `range{...}`, `tempo_map[{tick, bpm}]`, `time_signatures[{tick, sig}]`, `measures[{measure, tick, start_sec, end_sec, sig, beats[]}]`(박 수는 그 마디의 박자표를 따른다, 시각은 렌더 시각), `tracks[{track, name, program, program_name, instrument_type, song_program, panpot, volume, parts{<파트>: {notes, channel}}}]`(그 파일에 든 트랙·파트만), `split`, `files[{path, tracks, parts}]`(같은 렌더 묶음의 모든 파일), `limitations[]`, `warnings[]`. 무음이면 `peak_dbfs`·`rms_dbfs`는 null.

**오류**: `NO_PROJECT`, `NO_TRACK`, `BAD_ARGUMENT`(split·tail_sec·name·상대 경로 out_dir 등), `PROTECTED_PATH`(out_dir 또는 --render-dir이 보호 폴더 안), `FILE_EXISTS`(overwrite false인데 WAV나 JSON이 이미 있음. 렌더 전에 검사), `NOTHING_TO_RENDER`(고른 범위·트랙·파트에 음표 없음), `DLS_UNAVAILABLE`, `SYNTH_UNAVAILABLE`(신시사이저 열기 실패), `RENDER_FAILED`, `IO_ERROR`. 스택은 stderr.

**한계**: 자동 게인 때문에 split 파일의 합 ≠ 전체 파일(밸런스는 전체 파일 기준), 6.8ms보다 짧은 음은 안 울릴 수 있음, GUI WAV와 샘플 단위로 같지 않음, 모바일 타악기(타입 M)는 렌더마다 다를 수 있음, 악기마다 적은 음과 울리는 음이 다름(피아노·하프 +1옥타브, 바이올린 +2옥타브 등 — "들리는 음" 절). 자세한 것은 `BUILD.md` "7. 오프라인 WAV 렌더".

## 편곡 프로필

| 도구 | 설명 | 입력 예 |
|---|---|---|
| `score_profile` | 악보 하나의 "편곡 모양"을 수치로 뽑는다(원곡 MIDI 불필요). `.mmi`·3MLE `.mml`을 **읽기만** 하거나(현재 프로젝트는 그대로) 현재 프로젝트를 본다 | `{"path": "C:/scores/arrangement.mmi", "out_path": "C:/mabi-mcp/output/profile/arrangement.profile.json"}`, `{"from_measure": 29, "to_measure": 36}` |

**입력**(모두 선택): `path`(`.mmi` 또는 3MLE `.mml` 절대 경로. 보호 폴더 안 파일도 읽기는 된다. 생략 = 현재 프로젝트), `from_measure`·`to_measure`(생략 = 1 ~ 음표가 있는 마지막 마디), `out_path`(전체 JSON을 쓸 `.json` 절대 경로), `overwrite`(기본 false).

**응답**: `ok`, `out_path`(썼을 때만), `schema`(`"mabibridge.profile/1"`), `summary_text`(한국어 한 단락), `summary`(`file`, `range`, `tracks`, `tracks_with_notes`, `instruments[]`(트랙·이름·program·악기·역할·태그·음 수·적은/들리는 음역 글), `roles`, `role_text`, `chars_ratio_mean`·`chars_ratio_max`·`chars_over_rank1`, `low_parts_max`·`low_beats_ge3`, `track_polyphony_max`, `octave_correction[]`(요약), `silent_notes`, `timbre_mix_tracks`, `warnings`), 그리고 `out_path`가 없으면 `profile`(전체). 오류: `NO_PROJECT`, `BAD_ARGUMENT`(범위·상대 경로 out_path·모르는 인자), `BAD_PATH`(확장자), `FILE_NOT_FOUND`, `PARSE_FAILED`, `IO_ERROR`, `DLS_UNAVAILABLE`(`.mml`은 원본 파서가 음원 악기표를 쓰므로 DLS가 필요), `PROTECTED_PATH`(out_path가 보호 폴더 안), `FILE_EXISTS`.

**전체 JSON `mabibridge.profile/1`**:

- `file`: `path`, `name`, `format`(`mmi`/`mml`/`project`), `title`, `author`, `encoding`(3MLE의 Encoding=), `time_signature`(처음 박자. 0틱 박자 변경이 기본 박자보다 앞섬), `base_time`(파일의 time=), `time_signatures[]`, `tempos[{tick, pos, bpm}]`, `initial_tempo`, `measures`, `total_ticks`, `duration_sec`, `generated`(MabiIcco 규칙으로 MML을 다시 만들었는지).
- `range`: `from_measure`, `to_measure`, `from_tick`, `to_tick`, `measures`, `beats`, `whole_song`, `note_rule: "start_in_range"`(시작 틱이 범위 안인 음만 센다. 쉬는 마디·동시 발음·저음 겹침·보이싱은 범위 앞에서 시작해 걸친 음도 울리는 것으로 본다).
- `char_limits`: RANK_1(멜로디 1200, 화음1 800, 화음2 500, 노래 1200)과 노래 제외 기준(1600/1200/900). 글자 수는 **곡 전체** 기준(`scope: "whole_song"`).
- **글자 수 기준**: 파트마다 `chars_ratio`(노래 포함 한도 대비, 예전 그대로)·`chars_ratio_song_excluded`와 함께 `chars_ratio_default`(트랙의 `chars_rule` 기준)를 둔다. `tracks[].chars_rule`은 `score_stats` 기본과 같게 **노래 파트에 음이 있으면 `song_included`, 아니면 `song_excluded`**(1600/1200/900). `ensemble.chars_usage`(`mean_ratio`·`max_ratio`·`over_rank1`, `rule: "track_default"`)와 `summary`의 `chars_ratio_mean`·`chars_ratio_max`·`chars_over_rank1`(`chars_rule: "track_default"`), 요약문, `ProfileCli`의 `profiles.tsv`·`tracks.tsv` `chars_ratio_*` 열도 이 기준이다. 예전에는 노래 포함 한도로만 재서 `score_stats`로는 1랭크에 드는 악보가 159%로 보였다.
- `thresholds`: 저음 기준(들리는 음 < 48 = C3), 짧은 음(≤ 12틱), 화음 역할 기준(동시 발음 2.0), 베이스 기준(중앙값 ≤ 48).
- `tracks[]`: `track`, `name`, `program`, `instrument`, `instrument_type`, `sounding`(요약), `sounding_kind`, `song_program`, `song_program_name`, `song_sounding`, `panpot`, `volume`, `notes`, `written_range`·`sounding_range`(모든 파트, `{count, min, max, median, *_name}`, 들리는 쪽은 `silent`도), `velocity`(`mean`, `sd`, `histogram[16]`), `density`(마디당 음 수 `mean`·`mean_active`(음이 있는 마디만)·`max`·`max_measure`), `rest_measure_ratio`(울리는 음이 없는 마디 비율), `polyphony`(박마다 그 박 안의 최대 동시 발음 파트 수: `mean_active`·`mean`·`max`·`max_at`·`beats_active`·`beats`), `voicing`(`melody_chord1`·`chord1_chord2`·`melody_chord2`: 두 파트의 음 시작 시점마다 **들리는 음** 간격 = 위 파트 − 아래 파트, `samples`·`median`·`mean_abs`·`crossings`(음수 = 성부 교차)·`unisons`·`distribution`), `melody_range_width`(`written`·`sounding`, 반음), `low_register`(들리는 음 < 48이 울리는 박 수·비율), `timbre_sets`(구간 악기 20·28·29만: `default`·`alt`·`silent` 음 수), `role`(아래), `parts`.
- `tracks[].parts.<melody|chord1|chord2|song>`: `notes`, `notes_whole_song`, `program`(그 파트를 내는 악기, 노래는 노래 악기), `chars`·`chars_limit`·`chars_ratio`(RANK_1 대비), `chars_limit_song_excluded`·`chars_ratio_song_excluded`(노래 파트 제외), `chars_source`(3MLE 원문의 글자 수, 그 밖은 null), `written`, `sounding`(+ `silent`, `unpitched`, `timbre_sets`), `velocity`, `length`(`mean_ticks`, `short_ratio`), `density`, `rest_measure_ratio`. 음이 없는 파트는 수치가 null.
- `tracks[].role`: `role`(`melody`·`chord`·`bass`·`drums`·`song`·`sfx`·`empty`), `tags`(`lead` 주선율 추정, `low` 베이스가 아닌데 중앙값 ≤ 48, `song_part` 노래 파트가 있음), `reason`(근거 문장), `evidence`(타입, 중앙값, 동시 발음 평균, 밀도, 음 수). 규칙(단순): 범위에 음이 없으면 empty → 드럼(27·65~68)·타입 D·P(핸드벨 포함)면 drums → 효과음(14~17) sfx → 타입 V·C 노래 악기면 song → 나머지 음높이 악기 중 들리는 음 중앙값이 가장 낮고 ≤ 48인 트랙 하나가 bass → 동시 발음 평균 ≥ 2.0이면 chord, 아니면 melody. melody 중 밀도가 최대의 절반 이상인 트랙에서 들리는 음 중앙값이 가장 높은 것에 `lead`.
- `ensemble`: `track_count`, `tracks_with_notes`, `programs`, `instruments`, `program_counts`, `roles`(역할별 트랙 번호), `lead`, `role_text`, `low_register`(박마다 들리는 음 < 48이 울리는 (트랙, 파트) 수: `max_parts`·`max_at`·`mean_active`·`beats_with_low`·`beats_ge2`·`beats_ge3`·`histogram`), `polyphony_total`(트랙별 박 최대 동시 발음의 합), `octave_correction[]`, `chars_usage`(음이 있는 파트의 RANK_1 대비 비율 `mean_ratio`·`max_ratio`·`max_at`·`over_rank1`), `silent_notes`, `timbre_mix_tracks`.
- `ensemble.octave_correction[]`(편곡자가 들리는 음을 맞추려 옮겨 적었는지 볼 원재료, 트랙마다 주 악기 파트 한 줄 + 노래 악기가 다르면 노래 파트 한 줄): `track`, `parts`(`main`/`song`), `program`, `instrument`, `kind`, `sounding`, `notes`, `written_median`(+`_name`), `sounding_median`(+`_name`), `diff`(들리는 − 적은), `table_offset`(표의 오프셋, 구간 악기는 많이 쓴 세트의 오프셋), `matches_table`, `timbre_sets`, `dominant_set`, `estimate`·`estimate_text`(대강의 추정: 적은 대로 울리는 악기 `not_needed` / 위로 울리는 악기에서 들리는 음 중앙값 ≥ 86(D6)이면 `not_shifted_likely`, 적은 음 중앙값 ≤ 59(B3)이고 들리는 음 ≤ 84(C6)이면 `shifted_likely`, 그 밖 `unclear` / 구간 악기는 `set_default`·`set_alt`·`set_mix` / 고정음·음높이 없음 `n/a` / 표에 없는 악기 `unknown`). **표 오프셋은 들리는 음 표 그대로이므로 `diff`는 한 세트만 쓰면 항상 표와 같다.** 옮겨 적었는지는 `estimate`와 음역을 함께 보고 판단한다(근거가 약한 추정).
- `sounding_source`, `warnings`(MML 다시 만들기 실패, 3MLE 마커 등).

**3MLE `.mml`**: 원본 파서 `jp.fourthline.mmlTools.parser.MMLFile`(GUI의 열기와 같음)로 읽고 GUI처럼 MML을 다시 만든다. 그 파서는 트랙을 만들 때 DLS 악기표를 쓰므로 DLS가 필요하다. 파서가 `[Settings]`를 Shift_JIS로 읽어 한국어 제목이 깨지므로 제목·작성자는 파일의 `Encoding=`(ks_c_5601-1987 → CP949)으로 다시 읽는다. `[ChannelN]` 구역은 파일에 적힌 순서대로 `[3MLE EXTENSION]`의 트랙에 붙는다(원본 파서 규칙). `chars`는 MabiIcco가 다시 만든 MML 기준이고, 원문 글자 수는 `chars_source`.

**한계**: 역할은 단순 규칙이라 한 트랙이 멜로디와 반주를 오가면 평균으로 판정된다(예: 피아노가 오른손 멜로디 + 화음이면 chord). 베이스는 한 트랙만 고른다. 동시 발음은 박 단위 최대값이다. `estimate`는 음역 기준 추정일 뿐 원곡과 비교한 것이 아니다(원곡이 있으면 `compare_suggest_mapping`의 `octave_shift`를 본다).

일괄 실행(파일·폴더 여러 개 → `profiles.tsv`·`tracks.tsv`)은 `BUILD.md` "개발용 CLI"의 `ProfileCli`.

## 개발용 스모크 시험

```bat
java -Dmabiicco.test_mode -Duser.home=bridge\runtime\home -cp "MabiIccoBridge.jar;lib\gson\*" mabibridge.mcp.McpSmoke
```

브리지를 자식 프로세스(`cmd /c bridge\run-bridge.cmd`)로 띄워 initialize부터 ping까지(`render_wav` 포함, `-Dmabibridge.smoke.render=false`로 끔) 실제로 주고받고 통과/실패 표를 찍는다. 작업 폴더는 `-Dmabibridge.smoke.out=<폴더>`(기본 `bridge/runtime/smoke`), 참조 MIDI 단계는 `-Dmabibridge.smoke.midi=<file.mid>`를 줄 때만. 설치 확인용 묶음은 루트의 `smoke-test.cmd`. 자식 stdout에 JSON-RPC 아닌 줄이나 CRLF가 하나라도 있으면 실패다. `--direct`를 주면 run-bridge.cmd 없이 같은 JVM 플래그와 클래스 경로(설치된 MabiIcco `MABIICCO_APP` + `MabiIccoBridge.jar` + Gson)로 직접 띄운다. 결과물은 `bridge/runtime/smoke/<시각>/`.

## 요청 파일 드라이버 McpClient

```bat
set CP=%MABIICCO_APP%\MabiIcco.jar;%MABIICCO_APP%\lib\flatlaf\*;%MABIICCO_APP%\lib\commons-compress\*;MabiIccoBridge.jar;lib\gson\*
java -Dmabiicco.test_mode -Duser.home=bridge\runtime\home -cp "%CP%" mabibridge.mcp.McpClient bridge\examples\quickstart.jsonl --out output\quickstart.out.txt
```

(설치 폴더에서 실행한다.)

`McpClient <요청.jsonl | -> [--out 응답.txt] [--err 파일] [--timeout 초] [--jar 브리지.jar | --cp a;b] [--server-args 인자...]`. 브리지를 자식 프로세스로 띄우고(run-bridge.cmd와 같은 JVM 플래그와 클래스 경로, 작업 폴더 = 설치 폴더) `initialize`·`notifications/initialized`를 먼저 보낸 뒤, 요청 파일(UTF-8, 한 줄에 JSON 하나, 빈 줄과 `#`·`//` 줄은 건너뜀)을 한 줄씩 동기로 보낸다. 줄 형식은 단축형 `{"tool": "score_put_notes", "args": {...}}`(id 자동) 또는 완전한 JSON-RPC 요청(id가 없으면 알림). 출력은 요청마다 `=== [번호] 도구 — ok|isError` 머리줄과 결과 본문 JSON(껍데기를 벗긴 `content[0].text`), 끝에 "요청 n개, 오류 m개". 오류가 나도 끝까지 간다. `--out`을 주면 같은 내용을 UTF-8 파일에도 쓰고, 자식 stderr는 `--err`(기본 `<--out 파일>.err`, `--out`이 없으면 `bridge/runtime/client/<시각>.err`). 종료 코드 0 모두 성공, 1 오류 있음, 2 실행 실패. 인자 경로는 ASCII로(요청 파일 안의 경로는 UTF-8 그대로 된다).

**자식의 클래스 경로**:

- 기본: 설치된 MabiIcco(환경 변수 `MABIICCO_APP` = 그 `app` 폴더)의 `MabiIcco.jar;lib\flatlaf\*;lib\commons-compress\*` + 설치 폴더의 `MabiIccoBridge.jar;lib\gson\*` → `java -cp … mabibridge.Main`. `MABIICCO_APP`이 없거나 그 폴더에 `MabiIcco.jar`·`lib\flatlaf\`·`lib\commons-compress\`가 없으면 자식을 띄우지 않고 "MabiIcco is required: …"(MabiIcco가 필요하다는 것과 받는 곳)를 찍고 종료 코드 2.
- `--jar <경로>`: 설치 폴더의 `MabiIccoBridge.jar` 대신 그 브리지 jar(얼린 사본 등)를 넣는다. MabiIcco는 여전히 `MABIICCO_APP`에서.
- `--cp <a;b;…>`: 클래스 경로를 통째로 준다(`java -cp <a;b;…> mabibridge.Main`, `MABIICCO_APP`은 보지 않는다). 브리지 jar, MabiIcco jar, gson·flatlaf·commons-compress를 모두 적는다.
- 둘 다 주면, 또는 없는 파일이면 자식을 띄우지 않고 종료 코드 2. 경로는 드라이버를 띄운 폴더 기준으로 절대 경로로 바꾼다. 둘 중 하나를 주면 출력 첫 줄에 `# 자식: -cp … mabibridge.Main`이 찍힌다.
- 설치 폴더(자식 작업 폴더, `-Duser.home=<설치>/bridge/runtime/home`, 기본 `--err` 폴더)는 드라이버 클래스를 읽은 폴더이고, 그 폴더에 `bridge/run-bridge.cmd`가 없으면(설치 안 `bridge/runtime/…`의 사본) 위로 올라가며 찾는다.

**얼린 사본으로 돌리기**: jar를 다시 빌드하는 동안에도 분석을 돌리려면 브리지 jar 사본을 만들어 드라이버와 자식 모두 그 사본으로 띄운다(설치 폴더에서, cmd):

```bat
mkdir bridge\runtime\copy2\frozen
copy MabiIccoBridge.jar bridge\runtime\copy2\frozen\MabiIccoBridge_copy2.jar
set CP=%MABIICCO_APP%\MabiIcco.jar;%MABIICCO_APP%\lib\flatlaf\*;%MABIICCO_APP%\lib\commons-compress\*;bridge\runtime\copy2\frozen\MabiIccoBridge_copy2.jar;lib\gson\*
java --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED -Djava.awt.headless=true -Dmabiicco.test_mode -Dmabiicco.ticktable=tickTable.dat "-Duser.home=%CD%\bridge\runtime\home" -Xmx1024m -cp "%CP%" mabibridge.mcp.McpClient - --out bridge\runtime\copy2\quickstart.out.txt --jar bridge\runtime\copy2\frozen\MabiIccoBridge_copy2.jar < bridge\examples\quickstart.jsonl
```

출력 첫 줄 `# 자식: -cp …MabiIccoBridge_copy2.jar;… mabibridge.Main`으로 자식이 사본으로 떴는지 확인한다. **사본 클래스 경로 주의**: java의 폴더 와일드카드 `lib\*`는 **그 폴더 바로 아래 jar만** 펼치고 하위 폴더에는 닿지 않는다. `--cp`로 직접 줄 때는 `lib\gson\*`(그리고 MabiIcco의 `lib\flatlaf\*;lib\commons-compress\*`)를 따로 적는다 — 없으면 `NoClassDefFoundError: com/google/gson/...`. `--cp`의 `폴더\*` 항목은 폴더가 있는지만 보고 그대로 넘긴다. 사본은 빌드가 다시 쓰지 않으므로 jar를 다시 만드는 동안에도 사본으로 띄운 드라이버·서버는 그대로 돈다. 대신 떠 있는 서버(사본이 아닌 jar로 띄운 것)는 빌드 뒤 다시 띄워야 한다(위 "서버 재시작").

