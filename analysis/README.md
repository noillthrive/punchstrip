# mabi-ear — 소리 분석 MCP 서버

MabiIcco 브리지(Java)가 만든 **렌더 WAV + 메타 JSON**과 **원곡 녹음**을 파일 경로로 받아
정렬(DTW)·크로마 유사도·온셋·풍성함·밸런스를 계산하고 JSON/마크다운 파일로 쓴다. Java 서버와는
파일로만 주고받고 서로 부르지 않는다.

> 유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다.

## 1. 설치와 실행

설치 전체(다른 PC, 등록, 스모크 시험)는 맨 위 `README.md`. 여기서는 mabi-ear만 적는다. Python 3.11(`py -3.11`, 확인 3.11.9).
가상환경·캐시·임시 폴더는 모두 `analysis\.venv`, `analysis\.cache` 안에만 생긴다(git 제외, 사용자 홈에 쓰지 않음).

**자동(권장)**: `run-ear.cmd`를 처음 실행하면 `py -3.11`이 있는지 보고, `.venv`를 만들고 `requirements.txt`(실행용 고정 버전)를
설치한 뒤 서버를 띄운다. 설치가 끝까지 성공하면 `.venv\mabiear-deps.ok`를 남기고, 중간에 실패하면 다음 실행에서 다시 설치한다.
설치 출력과 오류 안내는 stderr로 간다(stdout은 MCP 전용). 인터넷이 필요하고 약 0.5 GB를 받으므로, 클로드 코드에 등록하기 전에
터미널에서 `run-ear.cmd --help`로 한 번 띄워 둔다. 0.4.1부터 패키지를 편집 설치(`pip install -e .`)하지 않는다: `run-ear.cmd`는
`python -I -X utf8 run_ear.py`로 띄우고, `run_ear.py`가 옆의 `mabiear` 폴더를 읽는다(그래서 옛 편집 설치의 판 정보가 낡아도 상관없다).

- 이미 3.11 환경에 `requirements.txt`를 깔아 두었다면 `MABIEAR_PYTHON=<그 python.exe>`로 그것을 쓴다(venv를 만들지 않는다).
- 시험(pytest)용은 `requirements-dev.txt`(= `requirements.txt` + pytest와 그 의존성).

**수동**(cmd, `analysis` 폴더에서):

```bat
set PIP_CACHE_DIR=%CD%\.cache\pip
set TMP=%CD%\.cache\tmp& set TEMP=%CD%\.cache\tmp& set TMPDIR=%CD%\.cache\tmp
set PYTHONUTF8=1
mkdir .cache\tmp
py -3.11 -m venv .venv
(echo [global]& echo disable-pip-version-check = true)> .venv\pip.ini
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
echo ok> .venv\mabiear-deps.ok
```

- **pip은 언제나 `PIP_CACHE_DIR`를 설정한 채로** 부른다(`pip list`·`pip freeze`도). 설정하지 않으면 pip이
  `%LOCALAPPDATA%\pip\cache`(사용자 홈)에 버전 확인 파일과 HTTP 캐시를 만든다. `.venv\pip.ini`의
  `disable-pip-version-check`는 그중 버전 확인만 막는다(run-ear.cmd가 venv를 만들 때 같이 만든다).
- 경로에 한글이 있으면 `PYTHONUTF8=1`이 없을 때 `pip freeze` 등이 실패한다. 설치 경로는 영문을 권한다(`run_ear.py`가 경고).

**서버 실행**: `run-ear.cmd [--out-dir <절대경로>] [--protect <폴더>]... [--log <파일>]`
(안에서 `<python> -I -X utf8 run_ear.py`). `-I`라서 현재 폴더·`PYTHONPATH`와 무관하다.

| 실행 인자 | 기본값 |
|---|---|
| `--out-dir` | 환경 변수 `MABIBRIDGE_OUT`이 있으면 `<그 폴더>\analysis`, 그다음 프로젝트 파일(아래)의 `analysis.dir`, 아니면 `<설치>\output\analysis`(브리지의 `output\render` 옆, `io.default_out_dir`). 쓸 때 없으면 만든다 |
| `--protect` | 기본 보호 폴더에 더함(반복 가능) |
| `--log` | `analysis/.cache/ear.log` (stderr에도 같은 로그, 시작 줄에 출력 폴더와 패키지 위치) |

`run-ear.cmd`가 설정하는 환경변수: `NUMBA_CACHE_DIR`, `LIBROSA_CACHE_DIR`(디스크 캐시는
`LIBROSA_CACHE_LEVEL=1`로 끔), `LIBROSA_DATA_DIR`, `MPLCONFIGDIR`, `PIP_CACHE_DIR`, `TMP`/`TEMP`/`TMPDIR`
→ 모두 `analysis\.cache\…`. `PYTHONUTF8=1`, `OPENBLAS_NUM_THREADS=1`(아래 4절). 다른 방법으로 띄워도
`mabiear/__init__.py`가 비어 있는 캐시 변수를 같은 값으로 채운다.

**등록**(PowerShell 또는 cmd, `<설치>`는 압축을 푼 폴더 또는 저장소):

```bat
claude mcp add --transport stdio mabi-ear -- cmd /c <설치>\analysis\run-ear.cmd
```

드럼 27 키 표는 패키지 자료 `mabiear/data/drum27_keys.json`(브리지 `src-bridge/mabibridge/engine/drum27_keys.json`의 사본, 시험이 같은지 본다).

## 2. 공통 규칙

- 입력은 모두 **절대 경로**(한글·한자 경로 그대로 됨). 입력 파일은 절대 고치지 않는다.
- 도구마다 선택 인자 `out_dir`(절대 경로, 생략 = `--out-dir`), `name`(생략 = 렌더 WAV 파일 이름 앞머리),
  `overwrite`(기본 false).
- 출력 이름: `<name>_align.json`, `_chroma.json`, `_onset.json`, `_richness.json`, `_balance.json`, `_ab.json`,
  `_report.md`, `_leadcheck.json`+`_leadcheck.md`(lead_check), `_vocal.json`+`_vocal.md`(vocal_agreement)
  (`audio_info`는 `save:true`일 때만 `_info.json`).
- 성공 응답: `{"ok": true, "out_path": "...", "summary_text": "<한국어 2~5줄>", "summary": {...}}`
- 스템 도구(`stem_drum_hits`·`stem_bass_notes`·`stem_melody_notes`·`stem_chords`·`structure_repeats`)는 `out_dir`·`name` 대신 `out_json`(.json으로 끝나는 절대 경로, 선택)과
- `report`는 구간 단위 경고(`lead_margin_low`: `from_measure`·`to_measure`만 있음)를 마디 표 대신 밸런스 절에 범위로 적는다(이전엔 `KeyError: 'measure'`로 죽었음). 새 도구 셋은 분석이 아니라 **기계 작업**(폰용 변환·A/B 발췌·마디선 위상 검사)이라 에이전트 없이 요청 파일로 돌리는 용도.
  `overwrite`. `out_json`이 없으면 파일을 쓰지 않고 전체 결과를 응답 `result`에 담는다(`out_path: null`). 있으면 그 파일에
  `mabiear.stem_drums/1`·`mabiear.stem_bass/1`·`mabiear.stem_melody/1`·`mabiear.stem_chords/1`·`mabiear.structure/1` 문서를 쓰고 응답에는 `summary`만. 보호 폴더·입력 스템 폴더 안은 `PROTECTED_PATH`.
- 실패 응답: MCP `isError: true` + `{"ok": false, "error": "<코드>", "message": "<한국어>", "details": {...}}`
- **인자 오류 코드**: 브리지와 같은 `BAD_ARGUMENT`. 0.2.0까지는 `INVALID_ARGS`였으므로 응답에
  `"error_alias": "INVALID_ARGS"`를 함께 넣는다(옛 이름을 보는 호출도 그대로 동작). 새 코드는 `error`만 본다.
- **모르는 인자 이름은 오류**: 도구 서명에 없는 인자(`{"sav": true}`, 오타 `b_offset` 등)는 분석 전에 `BAD_ARGUMENT`,
  `details.unknown`(받은 모르는 이름들)·`details.allowed`(받을 수 있는 이름). 전에는 MCP SDK가 조용히 버렸다.

| 오류 코드 | 언제 |
|---|---|
| `BAD_ARGUMENT` (옛 이름 `INVALID_ARGS`, `error_alias`) | 모르는 인자 이름, 인자 형식·범위가 틀림(**느슨한 변환 안 함**: 정수 인자에 `"2"`·`true`·`2.0`, 숫자 인자에 `"12"`, true/false 인자에 `"true"`, 목록 인자에 `"[0.9,1.1]"` 같은 문자열은 MCP SDK가 바꾸기 전에 거절), 상대 경로, **경로 문법 오류**(제어문자 U+0000~U+001F, Windows에서 `< > " \| ? *`, 드라이브 뒤가 아닌 `:`, 쓰는 중에 난 WinError 123·NUL 오류 — 분석 전에 거절), balance에 split 없는 메타, 분석 JSON 형식이 다름 |
| `FILE_NOT_FOUND` | 입력 파일(또는 메타 files[]의 파트 WAV)이 없음 |
| `AUDIO_READ_FAILED` | soundfile이 못 읽음(m4a, 깨진 파일 등) |
| `META_INVALID` | 메타 `schema`가 `mabibridge.render/1`이 아니거나 `measures`가 없음/잘못됨 |
| `ALIGN_FAILED` | 0.5초 미만 오디오, DTW 실패, 템포 비율이 0.5~2 밖 |
| `PROTECTED_PATH` | 출력 폴더가 보호 폴더 안이거나 **원본 입력 파일(WAV·메타·원곡)의 폴더 안** |
| `EXISTS` | `overwrite:false`인데 출력 파일이 있음(파일은 그대로 둔다) |
| `INTERNAL` | 그 밖의 예외(로그에 traceback) |

보호 폴더(출력 금지): 프로젝트 파일 `mabibridge-project.properties`의 `protect` 항목(브리지와 같은 파일·같은 찾기 규칙,
맨 위 `README.md`)과 `--protect`. 경로는 정션·심볼릭 링크를 푼 실제 경로로 비교한다. 분석 JSON(align_json 등)은 같은 출력 폴더에 있어도 되지만 덮어쓰지 않는다.

## 3. 도구 18개 (버전 0.5.0)

| 도구 | 입력(필수 / 선택) | 출력 파일 · 요약 |
|---|---|---|
| `audio_info` | `path` / `save` | 길이, 샘플레이트, 채널, peak·RMS dBFS(채널별 포함). 원래 샘플레이트 그대로 블록 단위로 읽음 |
| `align` | `render_wav`, `render_meta`, `original_audio` / `search_sec:[a,b]`, `mapping:"auto"\|"path"\|"linear"`(기본 `auto`), `auto_median_beats`(0.5), `auto_max_beats`(1.0), `tempo_ratio_range:[최소,최대]\|null`(기본 `[0.9, 1.1]`, 0.5 ≤ 최소 < 최대 ≤ 2.0), `segments:[{render_from_sec, render_to_sec}, …]`(구간별 정렬), 고정(0.5.0): `offset_sec` 또는 `first_downbeat_sec`(+`measure_offset`), `tempo_ratio` 또는 `orig_bpm`(아래 "아는 것 고정") | `_align.json`: `fixed{given, anchor_render_sec, anchor_orig_sec, tempo_ratio, searched}`(고정했을 때), `offset_sec`(렌더 0초 = 원곡 몇 초), `tempo_ratio`(원곡 시간 ÷ 렌더 시간, 1보다 크면 렌더가 빠름), `ratio_at_bound`(비율이 `tempo_ratio_range` 끝에 걸렸으면 true, 요약에도), `first_measure_orig_sec`, `mapping_requested`·`mapping_used`, `path_deviation_sec{median, max, max_raw, max_at_render_sec, max_deviation_at_sec(최대 편차 위치, 원곡 초), max_raw_at_render_sec}`, `beat_sec`, `mapping_decision`(판정 근거·`text`·`mapping_reason`·`over_max`), `mapping_reason`(요약에도), `segments[]`·`orig_gaps`·`segment_candidates[]`·`local_offsets[]`(아래 "구간별 정렬"), `confidence{level, mean_path_cost, baseline_cost, warnings}`, `global_search`(범위 제한일 때), `measures[]{orig_start_sec, orig_end_sec, beats_orig, local_tempo_ratio, deviation_from_line_sec}`, `path`(렌더초, 원곡초. 직선을 쓰면 직선 위 점) |
| `chroma_compare` | 위 3개 + `align_json` / `from_measure`, `to_measure`, `missing_hi`(0.5), `missing_lo`(0.25), `top_n`(5) | `_chroma.json`: 마디별 `score`(박 코사인 평균), `measure_cos`, 박별 `cos`, `missing`·`added` 음이름, 12음 세기 profile, `bottom`(하위 마디), `mean_score` |
| `onset_compare` | 위와 같음 / `tolerance_ms`(50), `top_n` | `_onset.json`: 마디별 `f`·`precision`·`recall`·`envelope_corr`, `mismatched_beats[]{measure, beat, orig_only_render_sec, render_only_sec}`, `bottom`, `mean_f` |
| `richness_compare` | `render_wav`, `render_meta`, `original_audio`, `align_json` / `from_measure`, `to_measure`, `low_hz`(250), `high_hz`(2000), `top_n`(3) | `_richness.json`: 마디별 `render_onsets`·`orig_onsets`·`onset_ratio`(렌더/원곡), `render_band_db`·`orig_band_db`·`band_db_diff`(렌더−원곡)·`band_db_rel`(전체 세기 차를 뺀 대역 모양 차) `{low, mid, high}`, `full_db_diff`(세 대역 합의 전대역 세기 차, 렌더−원곡 dB), `render_sec`(렌더 마디 [시작, 끝] 초), `orig_sec`(정렬표의 원곡 마디 [시작, 끝] 초), `summary{measures(마디 수), mean_onset_ratio, total_onset_ratio(= render_onsets / orig_onsets), render_onsets·orig_onsets(구간 합), level_offset_db(마디별 full_db_diff의 중앙값), band_db_diff_mean, band_db_rel_mean, emptiest}`, `emptiest`(가장 빈 마디 top_n) |
| `balance` | `render_meta`(split `track`/`part`) / `mix_wav`, `weighting`(`"k"`\|`"rms"`, 기본 `k`), `from/to_measure`, `buried_mode`(`"relative"`\|`"fixed"`, 기본 `relative`), `buried_margin_db`(12, relative용), `buried_db`(12, fixed용), `silence_db`(-60), `low_hz`(200), `low_min_db`(-40), `low_rel_db`(6), `low_min_parts`(3), `buried_include_unpitched`(false), `lead_part`(트랙 번호 또는 라벨 `"T1"`·`"T1/melody"`·`"T1 만돌린"`·트랙 이름), `margin_band`([2.0, 3.5]), `sections`(`[{name, from_measure, to_measure}]` 또는 `[{name, start_sec, end_sec}]`, `lead_part`와 함께만) | `_balance.json`: `weighting_used`, `parts[]`(`programs`, `instrument_types`, `pitched`, `unpitched`), 마디별 `active_parts`(쉬지 않는 파트 수 N)·`threshold_db`(그 마디 묻힘 기준)·`mix_rms_dbfs`·`mix_k_db`·파트별 `rms_dbfs`, `rel_mix_db`(RMS), `k_db`, `rel_mix_k_db`(K-가중), `centroid_hz`, `silent`, `warnings[]`(`buried`에 `rel_mix_db_used`(판정에 쓴 값: weighting k면 K-가중, rms면 RMS)·`weighting`·`rel_mix_db`(RMS, 파트 기록과 같은 뜻)·`rel_mix_k_db`·`rms_dbfs`·`k_db`·`threshold_db`·`active_parts`, `low_crowding`), `counts`(`active_part_measures`, `buried_ratio`, `low_crowding_parts_counted`, `lead_margin_low` 포함), `lead_margin{lead_part, margin_band, competitors, rule, sections[{name, from_measure, to_measure, measures, lead_margin_db, median_db, min_db, min_measure, measures_below_band, in_band, status(low\|ok\|high\|no_data), main_competitor}], measures[{measure, lead_k_db, top_other, top_other_k_db, margin_db, in_band}], warnings}`(`lead_part`가 없으면 null) |
| `stem_drum_hits` | `drum_stem_path`, `bpm`, `first_downbeat_sec` / `time_signature`("4/4"), `grid`(16\|32, 기본 16), `measure_offset`(1 = 첫 다운비트가 도구 몇 마디인가), `sections:[{name, from_measure, to_measure}]`, `min_confidence`(0), `low_tom_layer`(false), `phase_search_ms`(60, 0이면 끔), `out_json`, `overwrite` | `grid`(bpm·위상 `phase_sec`·마디 틱), `tempo_check{given_bpm, comb_bpm, comb_off_pct, beat_track_bpm, beat_track_off_pct, warning}`(1% 넘게 다르면 경고), `levels{stem_top_k_db, kick_ref_db, kick_ref_source}`, `counts`(종류별), `sections[{name, from_measure, to_measure, counts, hits}]`, `crashes[{pos, tick, measure, beat, confidence, rel_kick_db, section, pickup}]`, `hits[{pos, tick, measure, beat, tick_in_beat, section, class, confidence(0~1), confidence_label, sec, grid("16th"\|"32nd"), grid_error_ticks, level_db, level_source, rel_kick_db, rel_db_class, band, jump_db, decay_ms, centroid_hz, f0_hz, flatness, key, key_name, velocity, velocity_capped}]`, `placements[{tick, len 24, pitch = 드럼 키, vel, pos, class, confidence}]`(드럼 27 트랙 melody 파트에 그대로 `score_put_notes`), `low_tom_layer_added`, `shaker_beats`, `dropped`(교차 대역 정리·격자 중복·min_confidence), `notes`(한계) |
| `stem_bass_notes` | `bass_stem_path`, `bpm`, `first_downbeat_sec` / `time_signature`, `grid`(16\|32), `measure_offset`, `min_note_ticks`(12), `range_low`(23)·`range_high`(71)(들리는 MIDI 음), `min_confidence`(0), `out_json`, `overwrite` | `notes[{pos, tick, measure, beat, tick_in_beat, len(틱), pitch(들리는 음), name, confidence, confidence_label, octave_ambiguous, octave_reasons, strength_db, stable, voiced, reattacks, sec, grid_error_ticks}]`, `measure_roots[{measure, root, root_pc, share, lowest, lowest_pitch, notes, pitch_classes}]`, `placements[{tick, len, pitch, vel, pos, confidence}]`(`score_put_notes`에 `pitch_mode: "sounding"`), `pyin`, `counts`, `limits` |
| `stem_melody_notes` | `stem_path`, `bpm`, `first_downbeat_sec` / `time_signature`, `grid`(16\|32), `range_low`(45)·`range_high`(86)(들리는 MIDI, 12 이상 차이), `min_note_ticks`(기본 한 칸), `method`(`"auto"` 기본\|`"salience"`\|`"pyin"`\|`"ridge"`), `triplets`·`from_sec`·`to_sec`(ridge만), `measure_offset`, `sections`, `min_confidence`(0), `merge_gap_ticks`(기본 한 칸), `out_json`, `overwrite` | `notes[{pos, tick, measure, beat, tick_in_beat, length_ticks, pitch, name, confidence, source, octave_ambiguous, octave_reasons, pyin_agree, stable, sec, sec_end, grid_error_ticks, merged}]`(한 줄, 겹침 없음), `placements`(score_put_notes, 세기 10/8), `coverage{voiced_pct, voiced_of_loud_pct, loud_pct, note_time_pct}`, `range{low, high, p5, p50, p95}`, `sections[{name, from/to_measure, notes, octave_ambiguous, low_confidence, range}]`(없으면 8마디씩, `sections_auto`), `per_measure`, `track{method, tuning_cents, pyin_voiced_pct, …}`, `counts{notes, octave_ambiguous, register_moves, merged, dropped_short, dropped_before_start, …}`, `limits` |
| `stem_chords` | `paths`(WAV 1~12개를 더함), `bpm`, `first_downbeat_sec` / `subtract_paths`(믹스에서 뺄 스템, 같은 길이·시간축), `bass_stem_path`, `key`(`"auto"` 또는 `"F minor"`·`"C"`·`"F#m"`), `qualities`(기본 maj·min·dim·aug·sus2·sus4·7·maj7·m7), `voicing_range`([48, 72]), `voicing_size`(3\|4), `per_beat`(true), `time_signature`, `measure_offset`, `out_json`, `overwrite` | `measures[{measure, chord("Am", "C/E"), root, root_pc, quality, confidence, beat_share, bass_agree, inversion, bass, beats[], alternatives[2], voicing[MIDI], voicing_names}]`, `beats[{pos, chord, root, quality, confidence, bass_agree, bass}]`, `key{used, given, estimated, estimated_r, second}`, `placements`(마디 길이 화음), `counts{measures, no_chord_measures, low_confidence_measures, qualities, bass_agree_pct}`, `method`, `limits` |
| `structure_repeats` | `paths`(믹스 하나 또는 스템들; 이름에 drum이 든 스템은 크로마에서 뺌), `bpm`, `first_downbeat_sec` / `time_signature`, `measure_offset`, `n_measures`, `min_run_measures`(4, 2~32), `thresholds`(`"auto"` 또는 `{chroma, mfcc}`), `source_measures`([첫, 끝]), `out_json`, `overwrite` | `copy_map[{target[a, b], source[c, d], kind(exact\|harmony\|transposed), transpose, measures, similarity}]`, `measure_hints[{measure, source, transpose, similarity, earlier}]`, `runs[]`(최대 400, `runs_total`), `thresholds{mode, chroma, mfcc, min_run_measures, distribution}`, `coverage{measures, active, copy_target_measures, copy_target_pct, by_kind}`, `per_measure[{measure, active, source, kind, transpose}]`, `limits` |
| `phone_version` | `wav`, `out`(기본 `<입력 폴더>/폰용/<이름>_폰용.wav`), `sample_rate`(22050), `peak`(0.98), `normalize`(true: 피크를 정확히 peak로, false: 넘칠 때만 줄임), `overwrite` | 휴대폰용 모노 PCM_16 파일 경로, 길이, 피크, 크기 |
| `excerpt_ab` | `render_wav`·`original_audio`, `measures`([시작, 끝] 또는 쌍 목록 ≤ 64), `grid`{bpm, measure1_sec, beats_per_measure} + `render_offset_sec`/`render_meta` **또는** `align_json`(둘 중 하나), `lead_in_sec`(1.0), `tail_sec`(0.5), `out_dir`(기본 `<렌더 폴더>/변형/폰용`), `name_prefix`, `label`, `phone`(true), `write_b`(true), `overwrite` | 같은 타임라인·같은 길이의 A(우리 렌더)/B(원곡) 발췌 파일 목록(`<prefix>_<from>-<to>[_<label>]_A_우리_폰용.wav`, `…_B_원곡_폰용.wav`)과 시작 초 — 수작업 발췌와 샘플 단위 일치 |
| `downbeat_check` | `audio`(드럼 스템 또는 믹스), `bpm`, `beats_per_measure`(4), `candidates`(마디 1 시작 초 목록) **또는** `search`{around_sec, step eighth\|sixteenth\|beat}, `window_ms`(60), `band`(low = 킥 \| full), `out_json`, `overwrite` | 후보마다 예측 첫 박 ±창 안의 킥 수·비율·중앙 오차, 박 1·3 비율, 무작위 위상 기준선, `best`, `confidence`(high/low). 모든 박에 킥이 있으면 박 위상만 확정되고 첫 박은 미결 |
| `lead_check` | `original_audio`, `notes_json` + 시간 지도(`bpm`+`first_downbeat_sec`(+`time_signature`, `measure_offset`) **또는** `render_meta`+`align_json`) / `source`(`"mix"` 기본\|`"stem"`), `pitch_mode`(`"sounding"`), `velocities`, `tick_shift`(0), `from_measure`·`to_measure`, `side_k`(`"auto"` 또는 0~2), `range_low`·`range_high`(생략 = 음들에서), `octave_shift`(12의 배수, 생략 = 자동), `pyin`(생략 = stem일 때 켬), `repet`(false), `sections`, `transcription`(노래 전사 파일, 선택)·`transcription_key`(`"auto"`)·`transcription_min_note_sec`(0.12), `out_dir`, `name`, `overwrite` | `_leadcheck.json`: 음마다 `{index, pos, tick, measure, sec, sec_end, time_offset_ms, name, verdict(agree\|wrong\|uncertain), heard_pc, heard_name, heard_midi, heard_note, heard_confidence, heard_alternatives[], octave_relation, confidence, reasons[], transcription_pitch, transcription_agrees, evidence{voiced, level_rel_db, ridge_share_expected, s1_rel_expected, s2_rel_expected, s1_top, s2_top, ridge_top, steady_*, attack_ridge_top, pyin_*}}`, `summary{counts, transcription{notes_with_transcription, wrong_to_uncertain, uncertain_to_wrong, kept, ignored_short, ignored_out_of_key_slides, key}, sections[{name, from/to_measure, notes, agree, wrong, uncertain, wrong_at, uncertain_at}]}`, `wrong[]`, `uncertain[]`, `warnings[]`, `analysis{mode_used, side_k, side_k_fit_scores, octave_shift, tuning_cents, time_offset{global_offset_ms, phrases[], fit_at_global, fit_at_zero, at_search_edge}, ridge_band_midi, voiced_pct, …}`, `timing_sec`; `_leadcheck.md` = 구간 표 + 음마다 표 |
| `vocal_agreement` | `transcription_path`(GAME CSV `onset,offset,pitch` 또는 탭 TXT), `notes_json` + 시간 지도(lead_check와 같음) / `pitch_mode`, `velocities`, `tick_shift`, `from/to_measure`, `sections`, `key`(`"auto"`\|`"none"`\|`"D major"`), `min_note_sec`(0.12), `slide_cents`(40), `head_sec`(1.0), `onset_tolerance_ticks`(24), `exclude_measures`([[첫, 끝]], 녹음→악보에서 뺌), `out_dir`, `name`, `overwrite` | `_vocal.json`: `total{score_notes, voice_heard, agree, score_to_recording_pct, sung_notes, covered, recording_to_score_pct, transcribed_notes, ignored_short, ignored_out_of_key_slides, onset_offset_ms_median, octave_relation_score_minus_sung}`, `sections[]`(같은 수를 구간마다), `score_disagreements[]`(악보 음·노래 음), `recording_not_in_score[]`(노래 음·그 자리의 악보 음), `score_no_voice[]`, `key`, `rules`; `_vocal.md` |
| `compare_renders` | `a_wav`, `a_meta`, `b_wav`, `b_meta` / `from/to_measure`(A 번호), `top_n`, `b_measure_offset`(0) | `_ab.json`(기본 name `<A>_vs_<B>`): A의 n마디 ↔ B의 n+`b_measure_offset`마디끼리 박 코사인 평균 `score`, `rms_diff_db`(B−A), `only_a`·`only_b` 음이름. `measures[]`·`bottom[]`에 `measure`(A 번호)와 `b_measure`(B 번호), `b_measure_offset`, B에 짝이 없어 건너뛴 A 마디 `skipped_measures`·`skipped_count`. 오프셋이 있으면 요약문에 "27마디(B 29마디)"처럼 두 번호 |
| `report` | (`align_json`, `chroma_json`, `onset_json`, `richness_json`, `balance_json`, `ab_json`, `b_chroma_json`, `b_onset_json`, `b_balance_json` 중 하나 이상) / `title`, `out_path`(절대), `b_measure_offset` | `_report.md`: 주의 문장, 정렬 요약(경고, 직선/경로 판정 포함), 마디별 표(유사도·빠진 음·더한 음·온셋 F·음 시작 렌더/원곡·밸런스 경고·A/B), 하위 마디 목록, `## 풍성함` 절(`richness_json`: 음 시작 수·대역 모양 차·가장 빈 마디), 밸런스 절(가중 방식). `b_*_json`(B 렌더를 원곡과 비교한 결과)을 주면 표에 A·B 열을 나란히 둔다 |

**마디 오프셋**(`b_measure_offset` = k): 두 렌더의 마디 번호가 다를 때(예: A가 27~34마디, B가 29~36마디면 k = 2)
A의 n마디를 B의 n+k마디와 짝짓는다. `from_measure`/`to_measure`는 A 번호. `report`는 k를 인자로 받거나,
생략하면 `ab_json`에 적힌 값을 쓴다(둘 다 있는데 다르면 `BAD_ARGUMENT`). 표의 마디 칸은 `27 (B 29)`, 하위 마디
목록은 A 쪽 `27마디(B 29마디)`, B 쪽 `B 29마디(A 27마디)`로 적는다. B 쪽 결과의 마디 x는 A x−k 줄에 놓는다.

기준값의 뜻
- **빠진 음**: 마디 평균 크로마를 최대 1로 맞췄을 때 원곡 ≥ `missing_hi`(0.5)이고 렌더 < `missing_lo`(0.25)인
  음이름. **더한 음**은 반대.
- **묻힘**: 마디 소리 크기 차(파트 − 믹스, 믹스는 `mix_wav`, 없으면 파트 합)가 그 마디 기준 `threshold_db` 이하.
  비교 값은 `weighting`으로 고른다: `"k"`(기본) = K-가중 `rel_mix_k_db`, `"rms"` = 예전 RMS `rel_mix_db`.
  마디 RMS가 `silence_db`(-60dBFS) 미만인 파트는 쉬는 것으로 보고 경고하지 않는다.
  - `buried_mode: "relative"`(기본): 그 마디에서 쉬지 않는 파트 수 N으로 기준 = −(10·log10(N) + `buried_margin_db`)
    dB. `buried_margin_db` 12면 1파트 −12, 4파트 −18, 8파트 −21, 13파트 −23. 크기가 비슷한 파트 N개는 각각
    믹스보다 약 10·log10(N) dB 작으므로, 파트가 많을수록 기준을 낮춘다.
  - `buried_mode: "fixed"`: 예전 방식, 기준 = −`buried_db`(12) 고정.
  - **무음높이 파트는 기본으로 묻힘 판정에서 뺀다**: 드럼(program 27)·타악기(65~68 스네어드럼·큰북·작은북·심벌즈)·
    `instrument_type` D·M(그리고 핸드벨이 아닌 P). **실로폰 77(K)과 핸드벨 69~76(P, 고정음)은 음높이 있는 파트**다
    (예전에는 타악기로 빼서, 인게임에서 리드를 덮는 실로폰이 묻힘·경쟁 판정에 나오지 않았다).
    마디별 RMS·`rel_mix_db`는 그대로 적고 `unpitched: true`로 표시하며, 뺀 파트는 `params.buried_skipped_parts`.
    N에는 센다(믹스에 들어 있으므로). `buried_include_unpitched: true`면 예전처럼 판정. 노래(V·C)는 그대로 판정한다.
- **저음 뭉침**: **음높이 있는 파트만** 센다. 드럼(program 27)·타악기(65~68)·`instrument_type` D(드럼)·M(무작위 타악기)·
  핸드벨이 아닌 P·노래(V·C, 또는 `parts`가 `song`뿐인 파일)는 뺀다(실로폰 K·핸드벨은 센다 — +2옥타브·C5 이상이라
  저역에는 거의 걸리지 않는다). 트랙 정보는 렌더 메타 `tracks[]`,
  거기 없으면 파트 WAV 옆 같은 이름 `.json` 메타(브리지의 트랙별 메타)에서 읽고, 둘 다 없으면 음높이 있는 것으로 센다.
  박마다 `low_hz`(200Hz) 저역통과(버터워스 4차, 앞뒤 필터) RMS가 `low_min_db`(-40dBFS) 이상이고 (센 파트 중) 그 박에서
  가장 센 파트와 `low_rel_db`(6dB) 안인 파트가 `low_min_parts`(3)개 이상. 예전 조건은 `low_min_parts: 2`(드럼은 계속 제외).
- **리드 여유(`lead_part`)**: 마디마다 리드의 K-가중 소리 크기(리드 파일이 여럿이면 — split part에서 `"T1"` — 힘을
  더해 하나로) − 같은 마디에서 가장 센 **다른 음높이 파트**의 K-가중 소리 크기. 경쟁자는 무음높이(드럼·북·심벌즈)를 뺀 모든 파트로
  노래·실로폰·핸드벨과 같은 트랙의 다른 파트(`"T1/melody"`일 때 `T1/chord1`)도 든다. 쉬는 파트(마디 RMS < `silence_db`)는 뺀다.
  구간 값은 마디 값의 평균이고 `margin_band`(기본 [2.0, 3.5] dB) 아래면 `status: "low"`·경고 `lead_margin_low`(인게임에서 리드
  묻힘 위험), 안이면 `in_band: true`, 위면 `"high"`. 인게임 볼륨은 연주자와의 거리로 정해지고 팬이 없으므로 모노 합으로 잰다.
  `sections`가 없으면 고른 범위 전체가 구간 하나(`"all"`), `start_sec`·`end_sec` 구간은 가운데가 그 안인 마디.
- **스템 단서(`stem_drum_hits`)**: 앞선 시제품 스크립트의 규칙을 옮겼다(학습 없음). 드럼 스템을 모노 44.1 kHz,
  STFT 2048/256으로 대역 세기 low 30–150, lowmid 80–300, body 150–400, noise 1.5–5k, high 6–16k, cym 3–16k Hz. 대역 플럭스
  봉우리를 대역 묶음마다 따로 찾아(같은 16분에 킥과 하이햇이 함께 남음) 나눈다: 저역 → 킥(기본)·탐(80–300 Hz가 가장 세고 −10 dB까지
  150 ms 이상, body가 저역보다 크지 않음; f0 < 110 로우·< 170 미들·그 위 하이), body+noise → 스네어(noise 점프 ≥ 5 dB, body ≥ 3 dB,
  감쇠 ≥ 50 ms)·사이드 스틱(짧고 body 약함)·고스트(스네어 모양인데 구간 스네어 90번째 백분위보다 10 dB 이상 작음; 구간은
  `sections`, 없으면 8마디씩), 고역 → 크래시(3–16k 감쇠 ≥ 450 ms·점프 ≥ 9 dB)·라이드(긴 감쇠·작은 점프·평탄도 < 0.2, 언제나
  낮은 신뢰도)·닫힌 하이햇(6–16k 감쇠 < 120 ms)·오픈 하이햇(120–450 ms). 하나 더함: 6–16k가 450 ms 넘게 울리고
  점프 ≥ 9 dB인데 3–16k가 빨리 떨어진 것(스네어와 함께 친 크래시)도 크래시. 교차 대역 정리로 저역의 스네어 몸통, 중·고역의
  킥 클릭, 스네어 줄을 오픈 하이햇으로 본 것을 뺀다(`dropped`). 위상: 16분 격자 빗살 합을 ±`phase_search_ms`(2 ms 간격)에서
  최대로. 템포 확인: 같은 빗살을 비율 0.97–1.03(0.0005 간격)에서 찾은 `comb_bpm`과 librosa 템포. 격자: `grid` 16이면 24틱에
  맞추고 남은 차가 7틱을 넘거나 같은 종류가 같은 칸에 겹치면 12틱(32분), 32면 언제나 12틱. 세기: 소리 크기는 스템을 K-가중해
  타격 시작부터 100 ms(다른 대역 묶음 타격과 30 ms 안에 겹치면 자기 대역 크기 + 홀로 친 같은 종류의 (전대역 − 대역) 중앙값),
  스템 99.5번째 백분위 기준(`level_db`), `rel_kick_db` = 그 값 − 홀로 친 킥의 90번째 백분위. 키 세기 v = 15·10^((rel_kick_db −
  level_db_vs_kick)/40)을 반올림해 1–15(키 표 `src-bridge/mabibridge/engine/drum27_keys.json` — 브리지 `instruments`의
  `drum_keys`와 같은 파일). `placements`는 구간 기준으로 −14 dB보다 작은 타격·낮은 신뢰도이면서 −8 dB보다 작은 타격을 뺀다.
- **스템 단서(`stem_bass_notes`)**: 베이스 스템 모노 22050 Hz, pyin(2048/256, `range_low`~`range_high`), 30–500 Hz 대역
  플럭스 온셋. 음은 온셋(또는 소리 시작)부터 대역 세기가 봉우리보다 12 dB 떨어지거나, 음높이가 0.8반음 넘게 60 ms 바뀌거나,
  다음 온셋까지. 음높이는 유성 프레임 반올림 MIDI의 중앙값. 격자에 맞추고(16 → 24틱, 32 → 12틱) 같은 시작은 긴 쪽을,
  12틱 안에 이어지는 같은 음은 하나로 합친다(`reattacks`). 옥타브 애매: 음 안 pyin 프레임 15% 이상이 ±12, 기본음이 2배음보다
  10 dB 넘게 약함, 한 옥타브 아래 대역이 기본음과 6 dB 안. 마디 근음: 마디 안 길이 × 세기, 첫 박 음 ×2, 가장 낮은 음 ×1.5로
  음이름을 더해 가장 큰 것.
- **멜로디(`stem_melody_notes`)**: 노래 스템 모노 22050 Hz, CQT(hop 256 = 11.6 ms, MIDI 36~108, 반음에 3칸)를 로그 압축하고
  반 옥타브 중앙값 빼기로 희게 함(빼지 않으면 자음·심벌 번짐이 음역 꼭대기를 이김). 배음 합 `sal = Σ 0.84^(h−1)·W(배음 h)`, h 1~8, 그
  칸에 제 에너지가 없고 한 옥타브 위가 세면 깎음(0.55배까지; 하이패스로 기본음이 약한 보컬은 홀수 배음이 살아 있어 그대로 이김).
  비터비(무성 상태 하나): 프레임당 2/3반음까지는 공짜, 도약은 e^−6, 옥타브 도약(11~13반음)은 e^−4 더 — 겹친 보컬·옥타브 더블에서 가장
  센 줄을 지킨다. 유성 = 스템 크기(95번째 백분위 −28 dB 중심)와 배음 합 뾰족함(봉우리/중앙값, 2.2 중심) 로지스틱의 곱. `auto`는 pyin을
  둘째 의견으로만(같은 음 프레임 비율 `pyin_agree` → confidence ±0.05, 옥타브가 다르면 `pyin_other_octave` 표시; 음은 옮기지 않음 —
  평가 스템에서 pyin 옥타브가 더 자주 틀렸다). 음 나누기: 무성에서, 5프레임 중앙값 음높이가 0.6반음 넘게 6프레임(70 ms) 바뀔 때(비브라토는
  안 끊음), 같은 음높이 안의 온셋(스펙트럼 플럭스 봉우리 또는 6 dB 넘는 세기 골 뒤 재어택). 음높이 = 조율(소수 부분의 원형 평균)을 뺀 중앙값.
  음역 정리: 확실한 음들의 길이 가중 중앙값에서 9반음 넘게 떨어진 음은 그쪽 옥타브 배음 합이 자기의 0.5배 이상이면 옮김(`register_moved`).
  격자: 시작·끝을 칸에, 같은 시작은 confidence 큰 쪽, 겹치면 앞 음을 자름, 같은 음이 한 칸 안에서 온셋·세기 골 없이 이어지면 합침, 한 칸보다
  짧으면 뺌. `octave_ambiguous`: 한 옥타브 위·아래 배음 합이 0.8배 이상(`other_octave_strong`), pyin이 옥타브 다름, 음역 정리로 옮김.
- **화음(`stem_chords`)**: 스템 합(또는 믹스 − `subtract_paths`) 모노 22050 Hz, HPSS 화성 부분(margin 2)의 `chroma_cqt`(hop 512,
  36칸/옥타브) 박 평균, 박마다 최대 1. 템플릿 9종(근음 1, 3도·5도 0.8, 나머지 0.6) 코사인 − 종류 값(7화음 0.03, sus 0.04, dim·aug 0.05).
  조 가중: Krumhansl 추정(또는 `key`) 음계 안 화음 +0.04, 그중 으뜸·딸림·버금딸림 +0.02. 베이스 가중 +0.4 × 베이스 크로마(근음):
  `bass_stem_path`의 희게 한 배음 합(MIDI 24~60, 프레임마다 가장 센 후보 하나) — 없으면 화성 합의 MIDI 28~52(약함: 베이스 스템을 `paths`가
  아니라 `bass_stem_path`로 줄 것). 박 30 dB 아래는 "N". 박 사이 비터비(바꿈 값 0.08), 마디 화음 = 박 점수 합(첫 박 1.5배) 최대.
  confidence = (최선 − 근음이 다른 차선)/0.15. 보이싱: 밀집 배치, 첫 마디는 범위 가운데, 다음부터 앞 보이싱에서 가장 적게 움직임
  (3음 7화음은 5음을 뺌, 4음 3화음은 근음을 옥타브 위로 더함).
- **반복 구조(`structure_repeats`)**: 22050 Hz, hop 512. 크로마 = 드럼 아닌 파일 합(파일 하나면 HPSS 화성 부분)의 `chroma_cqt` 박
  평균(박마다 L2), 음색 = 전체 합의 MFCC 20 마디 평균(곡 전체로 z-점수, L2). `S_r(i, j)` = 박 코사인 평균(j를 r반음 올려 i와 비교),
  `S_mfcc`. 자동 문턱: 2마디 이상 떨어진 소리 있는 마디 쌍의 `S_0` 분포로 chroma = 중앙값 + 0.45 × (p99 − 중앙값)(0.80~0.97),
  mfcc = 중앙값 + 0.68 × (p99 − 중앙값)(0.30~0.80). 모든 마디가 비슷한 루프 곡에서는 두 문턱이 저절로 높아진다. 대각선(마디 차 ≥ 2)에서 `S_0 ≥ chroma`가 `min_run_measures` 이상 이어지면 반복, 그 안에서
  `S_mfcc ≥ mfcc`도 이어지면 exact, 나머지 harmony; r ≠ 0에서 `S_r ≥ chroma`이고 `S_0`보다 0.05 크면 transposed. 복사 지도: 마디마다
  exact > harmony > transposed, 긴 반복, 높은 유사도, 앞 원본 순으로 하나, 원본이 한 마디씩 나아가면 한 항목으로 묶음.
- **아는 것 고정(`align`, 0.5.0)**: 직선 원곡 = a + b × 렌더에서 준 것은 그대로 쓰고 나머지만 찾는다. `offset_sec` = a(렌더 0초가
  원곡 몇 초), `first_downbeat_sec` = 렌더 `measure_offset`마디(생략하면 메타의 첫 마디) 시작의 원곡 시각(스템 도구와 같은 약속),
  `tempo_ratio` = b, `orig_bpm` = 원곡 BPM(렌더 템포가 하나일 때 b = 렌더 BPM ÷ orig_bpm). 비율만 주면 그 비율 하나로 전역 탐색 뒤
  오프셋만 다듬고(`fit_method: fixed_ratio+offset_search`), 시작만 주면 그 점을 지나는 직선의 비율을 `tempo_ratio_range` 안에서
  찾고(`fixed_offset+ratio_search`), 둘 다 주면 탐색 없이 그 직선(`fixed`; `mapping: auto`면 `linear`, `path`면 그 직선 ±1초 띠
  DTW). 언제나 띠 DTW 방식이고 `segments`와는 함께 쓰지 않는다. 반복 구간이 있는 곡은 `search_sec`으로 원곡을 좁힌다(시험: 8마디
  렌더가 비율만 고정하면 뒤 후렴 188.5초에 붙었고, `search_sec [55, 85]`로 65.16초 — 실제 65.21초). 전역 탐색은 고른 위치에서 2초
  넘게 떨어진 다른 위치 중 점수가 최고의 0.85 이상인 것을 3개까지 `alternatives[{offset_sec, score, score_ratio,
  first_measure_orig_sec}]`로 적고, 0.95 이상이 있으면 `repeat_ambiguous: true`, 경고(후보 시각과 `search_sec`으로 좁히라는 말),
  신뢰도 한 단계 낮춤(그 곡: 188.54초 0.929 대 65.16초 0.928).
- **음 단위 리드 대조(`lead_check`)**: 우리 렌더를 받아 적지 않고 악보 음을 그대로 쓴다. 원곡 22050 Hz, STFT 4096/128. 믹스는
  max(|mid| − k·|side|, 0)(k = `side_k`; `"auto"`는 0/0.3/0.5/0.8/1.0마다 악보 음 중 옥타브 창에서 이기는(q ≥ 0.85) 음의 비율을
  재고, 최고값에서 0.05 안에 드는 것 중 **가장 작은 k**(같으면 언제나 작은 쪽, 큰 k는 분명히 나아야 고름) — 시험 믹스에서 0.8은
  0.3~0.5보다 크게 나빴다), 스템은 모노. 주파수 31칸 중앙값으로 평탄화(log1p), 0.2반음 격자에서 배음 1~8 합(S1)과
  기본음을 뺀 2~10 합(S2). 원곡 리드의 옥타브 관계(−36~+24반음)와 조율(±0.5반음)은 악보 음이 가장 두드러지는 쌍으로 정하고, 음마다
  그 옥타브 창 [음 + 관계 − 6, + 5](음이름마다 후보 하나)에서 q[c] = (S1/S1 최대 + S2/S2 최대)/2를 잰다. 같은 창의 능선(무성 상태가
  있는 DP)이 그 음이름에 머문 비율도 본다. **시간 보정**: 시간 지도는 조금 틀릴 수 있으므로(시험 곡에서 준 첫 박이 실제보다 약 40ms 늦었다) 원곡에서 직접 고친다 —
  보정 d(−120~+120ms, 10ms 간격)마다 음의 판정 창을 d만큼 옮겨 기대 음이름의 대비(12음 후보의 중앙값 대비 S1·S2)를 재고, 음들의
  상위 80% 평균이 가장 큰 d(같으면 0에 가까운 쪽)가 전체 보정, 다시 구(0.4초 넘는 쉼 또는 3초마다 끊음, 4음 이상)마다 전체 보정
  ±40ms 안에서 같은 방법(전체 값 쪽으로 10ms당 0.008 끌림; 전체 보정은 0 쪽으로 0.002) — 조금 틀린 템포의 밀림도 구마다 따라간다. 결과 `analysis.time_offset
  {global_offset_ms, phrases[{first_note, last_note, offset_ms}], fit_at_global, fit_at_zero}`, 음마다 `time_offset_ms`(+ = 원곡이
  지도보다 늦음). 분석 창이 186ms라 이른 지도는 덜 고쳐진다(합성 시험: 60ms 이른 지도 → +10ms, 늦은 지도 → −80ms). 판정 창은 음의
  **가운데**(시작 15%·최소 30ms 뒤 ~ 끝 20%·최소 30ms 앞)라 남은 오차가 어느 쪽이든 옆 음으로 넘어가지 않는다. 판정은 그 창의 뒤쪽
  (첫 35%, 최대 120ms 뒤) 부분 우선: q ≥ 0.85(또는 능선 50% 이상이고
  q ≥ 0.78)면 일치, q < 0.78이고 다른 음이름이 0.1 넘게 높으면 틀림(들린 음 = 능선이 30% 넘게 머물고 q ≥ 0.85인 음이름, 아니면
  q가 가장 높은 것), 그 사이·소리 약함(능선 유성 프레임이 max(6, 25%)보다 적음)·능선만 다른 음(`ridge_other`)은 보류. 앞부분만 우리
  음이고 뒤가 다르면 `slide`, 다른 음에서 들어와 우리 음에 머물면 `scoop`(일치). pYIN(스템 기본)은 보류를 70% 이상으로 정하거나
  반대되는 판정을 보류로 내린다. 전체 믹스는 경고를 단다(아래 6절). **노래 전사와 교차 확인(`transcription`)**: `vocal_agreement`와 같은
  파일·믿음 규칙. 음마다 첫 1초(시간 보정 뒤)에 가장 많이 겹치는 전사 음 하나가 `transcription_pitch`이고, 그 음이 그 구간의
  min(30%, 60ms) 이상을 덮을 때만 판정에 쓴다(옆 음 끝이 살짝 걸친 것은 `transcription_covers: false`). 그 음이 악보 음이름이면
  '틀림'은 보류(`transcription_disagrees`). 그 음이 도구의 들린 음과 같은 다른 음이름이고 도구의 `heard_confidence` ≥ 0.6이며 이유가
  소리 약함뿐이 아닐 때만 '보류'가 틀림(`transcription_confirms`) — 약한 소리를 전사 하나로 틀림으로 올리지 않는다;
  바뀐 수는 `summary.transcription`. 주지 않으면 결과는 그대로다. 시간 보정이 ±120ms 끝에 닿으면 `time_offset.at_search_edge`와
  경고(align이나 first_downbeat_sec로 다시), `side_k`를 0.5보다 크게 직접 주면 경고.
- **함께 쓰는 법(노래 리드)**: 노래·리드 **스템 + 노래 전사**를 먼저 쓴다 — `vocal_agreement`로 구간별 일치를 보고, `lead_check`에
  `source: "stem"`과 `transcription`을 주어 음 단위로 확인한다. 전체 믹스만 있으면 `lead_check` 결과는 **의심 목록**이다(confidence
  0.6 이하): 귀로 확인할 자리로 쓰고, 그 자리의 음은 `heard_note`보다 `heard_alternatives`까지 함께 읽는다(반주 화음 음이 들린 음으로
  잡히기 쉽다). `time_offset`이 크거나 끝에 닿았으면 시간 지도부터 고친다. **확신**: 들린 음은 두 탐색(S1·S2)과 능선이
  몇 개나 같은 음이름을 가리키는지로 `heard_confidence`(3개 0.9, 2개 0.6, 1개 0.35, 없음 0.2)와 `heard_alternatives`(다른 쪽이 가리킨
  음이름)를 단다. 믹스의 '틀림'은 의심 목록이라 confidence를 0.6(셋이 다 같지 않으면 0.45), heard_confidence를 0.6 이하로 묶고,
  스템에서도 셋이 다 같지 않으면 0.7 이하.
- **리드 받아 적기 `stem_melody_notes` `method: "ridge"`**: salience 방법의 음 줄을 그대로 쓰고, 음마다 STFT 배음 증거로 옥타브를
  다시 정한다. 후보(같은 음이름, 범위 안 옥타브)마다 뒤 3/4 구간의 평탄화 수준 − log 2(부분음 사이 ≈ 0)를 홀수 배음(1·3·5·7)과
  짝수 배음(2·4·6·8)에서 평균, 홀수/짝수 ≥ 0.6이고 수준 ≥ 최고의 0.5면 통과. 가장 낮은 통과 후보 L이 실제 소리이고, L의 비율이 0.9
  이상이면 L 하나(위 옥타브는 L의 짝수 배음일 뿐), 아니면 두 옥타브가 다 울린다고 보고 줄의 음역(길이 가중 70번째 백분위)에 가까운
  쪽(`both_octaves_sound`, octave_ambiguous). 한 음만 울리는데 음역보다 9반음 넘게 낮으면 스템에 샌 반주로 보고, 앞 음이 그대로
  울리면 앞 음을 늘리고, 아니면 그 자리에서 음역 ±8반음 안 배음이 온전한 가장 센 음으로 바꾸거나(`replaced_bleed_…`) 뺀다
  (`dropped_bleed[]`). 같은 음은 스템 온셋이 30ms 안에 있을 때만 다시 친다(`reattack`). `triplets`면 16분과 8분 셋잇단(32틱) 중
  가까운 격자.
- **노래 전사와 대조(`vocal_agreement`)**: mabi-ear는 전사 도구(GAME)를 돌리지 않고 그 출력 파일만 읽는다. 믿음 규칙 — 0.12초보다
  짧은 음과 조 밖이면서 반음에서 40센트 넘게 어긋난 음은 슬라이드라 뺀다. 악보→녹음: 리드 음의 첫 1초(짧으면 음 전체)에 겹치는
  전사 음 중 같은 음이름이 있으면 일치(겹치는 음이 없으면 '목소리 없음'으로 세지 않음). 녹음→악보: 전사 음의 시작을 같은 음이름
  리드 음이 덮으면(그 음 시작 24틱 전부터 끝까지) 일치. 전사는 한 줄이라 화음 구간에서는 다른 목소리를 들을 수 있다.
  **`exclude_measures`**: 가사 없는 구간(허밍·보컬리즈처럼 악보 리드가 일부러 따라가지 않는 노래)이 있으면 그 마디를
  `[[첫, 끝]]`으로 빼야 녹음→악보 수치가 리드의 정확도를 뜻한다. 한 예(곡 하나의 측정값일 뿐): 6/8 곡에서 그런 구간 15마디를 뺐을 때 악보→녹음 472/482,
  녹음→악보 471/479(`key: "none"`), 믿음 규칙을 켜면(`key: "auto"`) 녹음→악보 471/476. 빼지 않으면 그 구간의 노래 음이 모두
  '악보에 없음'으로 세어진다.
- **음 목록과 시간 지도(lead_check·vocal_agreement)**: `notes_json` = 브리지 `score_notes` 응답을 저장한 파일(`part: "all"`의
  `parts`, 여러 트랙이면 응답의 목록, 또는 `{tick, len, pitch}` 목록). `sounding_pitch`가 있으면 그것(`pitch_mode: "written"`이면
  적은 음), `silent: true`는 뺀다. 시간 지도는 격자(`bpm`·`first_downbeat_sec`·`measure_offset`·`time_signature`, 스템 도구와 같음)
  또는 `render_meta`(마디마다 틱) + `align_json`(마디·박의 원곡 시각, 박 사이는 직선). 음 목록·align JSON은 출력 폴더에 있어도 된다.
- **정렬 신뢰도**: `mean_path_cost`(경로 위 크로마 코사인 거리 평균, 0=같음) < 0.15이고 무작위 짝(baseline)의
  절반 미만이면 "높음", < 0.3이고 0.75배 미만이면 "보통", 아니면 "낮음".
- **보정 근거**: 사람이 만든 편곡 여러 개의 렌더에서, 예전 기준(12dB 고정, 두 파트 이상)은 쉬지 않는 파트·마디의 3분의 1
  남짓을 "묻힘"으로 알렸다. 파트 수에 맞춘 기준과 드럼·타악기 제외(지금 기본값)로 그런 거짓 경고가 크게 줄었다.

## 4. 분석 기본값

- 오디오 읽기: `soundfile`(libsndfile 1.2.2). WAV·FLAC·**MP3 읽힘**(확인함), **m4a는 미지원**(`AUDIO_READ_FAILED`).
  분석용으로 **채널 평균 모노 → 22050Hz 리샘플**(soxr_hq). `audio_info`만 원래 샘플레이트·채널.
- 크로마: `librosa.feature.chroma_cqt`, hop 512(약 23ms), 프레임마다 L2 정규화. 파일 최대 RMS보다 50dB 넘게
  작은(또는 -100dBFS 미만) 프레임은 12음이 같은 '평평한' 벡터로 둔다. 신호가 34초를 넘으면 30초 블록(앞뒤 2초
  겹침)으로 나눠 계산해 이어 붙인다(한 번에 계산한 것과 프레임 코사인 0.99 이상).
- **마디·박 경계 옮기기(`mapping`)**: 정렬로 얻은 직선(원곡 = `offset_sec` + `tempo_ratio` × 렌더)과 DTW 경로 중
  하나로 렌더 경계를 원곡 시각으로 옮긴다. 템포가 고정된 곡에서 경로를 쓰면 화음이 길게 이어지는 곳에서 경로가
  마디 경계를 끌어당긴다.
  - `"auto"`(기본): 경로를 구한 뒤 마디·박 경계마다 |경로 − 직선|(원곡 초)를 잰다. 경로 양 끝 1초 안 경계는
    CQT 가장자리 효과 때문에 뺀다(남는 경계가 3개 미만이면 모두 씀). **중앙값** = |편차| 중앙값,
    **최대** = 편차를 1마디 폭(박 수 + 1개 경계) 이동 중앙값으로 다듬은 뒤의 |최대|(한두 박짜리 경로 튐은 누르고
    여러 박 이어지는 편차는 남긴다; 다듬기 전 값은 `max_raw`). 박 길이 `beat_sec` = 렌더 메타 마디 표의 박 간격
    중앙값(렌더 템포) × `tempo_ratio`. 중앙값 ≤ `auto_median_beats`(0.5)박 **이고** 최대 ≤ `auto_max_beats`(1.0)박이면
    직선(`linear`). 중앙값이 넘으면 경로(`path`). 최대만 넘으면 두 가지를 본다 —
    ① 기준(1박)을 넘는 경계 묶음 중 2마디(`AUTO_SUSTAIN_MEASURES` × 마디당 박 수 경계) 넘게 이어지는 것이 있는가,
    ② 경계 편차(1마디 이동 중앙값 뒤)의 **90번째 백분위**가 `AUTO_P90_BEATS`(0.4박)를 넘는가(편차가 곡 곳곳에 퍼짐).
    둘 중 하나면 경로, 아니면(짧은 튐 몇 개뿐) 직선을 지킨다. 이유는 `mapping_reason`(요약에도)과
    `mapping_decision.over_max{boundaries, fraction, runs[{from_render_sec, to_render_sec, boundaries, spike, touches_edge("start"|"end"|null)}], spike_boundaries, sustain_boundaries, p90_beats, p90_threshold_beats, constant_render_tempo}`.
    합성 시험: 16마디 중 무음 마디 하나에서 경로가 2박 튀어도 직선, 120→90 점점 느려짐·104→136 템포 바뀜은 경로.
    몇 개의 렌더로 정한 기준이라 경계 근처(90번째 백분위 0.3~0.5박)면 `mapping`을 직접 주는 것이 안전하다.
    요약문 예: "직선 정렬 선택(경로 편차 중앙 0.12박, 최대 0.40박; 기준 0.5/1박)"(최대만 넘어 직선이면 뒤에 이유).
  - `"path"`·`"linear"`: 지정한 대로(편차는 똑같이 재서 기록만 한다).
- **구간별 정렬(`segments`)**: 구간을 잘라낸 편곡(원곡 몇십 마디를 건너뛴 악보 등)은 직선 하나로 맞지 않는다.
  `segments: [{"render_from_sec": a, "render_to_sec": b}, …]`(시각 순서, 겹치지 않음, 각 2초 이상, 32개까지, 비율 범위 제한일 때만)를
  주면 구간마다 그 렌더 구간만으로 원곡 전체(`search_sec` 구간)에서 전역 탐색 + 다듬기를 따로 해 `segments[]`
  = `{render_from_sec, render_to_sec, offset_sec, tempo_ratio, ratio_fixed, orig_from_sec, orig_to_sec, warp_score, second_peak_score, ambiguous, confidence}`를
  돌려준다. 원곡에서 10초(× 비율)보다 짧은 구간은 비율을 전역 값으로 고정하고 오프셋만 찾는다(`ratio_fixed: true`). 구간 안
  마디·박·경로는 그 구간의 직선으로, 구간 밖은 전역 결과(직선 또는 경로)로 옮기고 `mapping_used: "segments"`. 구간은 [시작, 끝)
  반열림이고 경계에 걸린 마디 끝은 앞 구간으로 옮긴다. 옮긴 원곡 시각이 앞으로 뛴 곳(어느 렌더 시각에도 대응하지 않는 원곡)은
  `orig_gaps: [[원곡 시작, 끝], …]`이고, `onset_compare`는 그 안의 원곡 온셋을 뺀다. `chroma_compare`·`onset_compare`·
  `richness_compare`는 align_json의 마디·경로를 그대로 쓰므로 따로 바꿀 것이 없다. 구간 신뢰도가 낮거나(`warp_score` < 0.7)
  다른 위치 점수가 거의 같으면 경고.
- **건너뜀 찾기(`segment_candidates`)**: `segments`를 주지 않아도(비율 범위 제한, 마디 8개 이상) 4마디 창을 2마디씩 옮기며
  전역 비율 그대로 원곡에서 가장 잘 맞는 위치를 찾는다(`local_offsets[]` = 창마다 `{measures, render_from_sec, render_to_sec, shift_sec, score_on_line, score_best, jump}`).
  최적 위치가 전역 직선에서 원곡 2초 넘게 떨어지고 그 점수가 직선 위 점수보다 0.05 넘게 높으면 후보로 보고(직선 위치와
  찾은 위치가 둘 다 원곡 구간 — `search_sec` 안, 오디오 안 — 에 통째로 들 때만, 창마다 `inside_window`), 이웃 후보 창을
  묶어 `segment_candidates[]` = `{measures, render_from_sec, render_to_sec, shift_sec, orig_from_sec, score_on_line, score_best, windows}`와
  경고("렌더 1~4마디(0.0~8.0초)는 전역 직선보다 원곡 −7.6초 떨어진 곳(원곡 1.0초~)과 더 잘 맞는다(점수 0.85 → 1.00) …")를 낸다.
  후보의 `render_from_sec`·`render_to_sec`를 그대로 `segments`에 넣어 다시 정렬하면 된다(합성 시험: 렌더 12마디, 원곡에서 5마디 앞에
  4마디가 더 있음 → 후보 1~4마디, segments로 1~4마디 크로마 0.86 → 1.00). 띠 DTW 경로는 전역 직선 ±1초 안만 보므로 경로 자체에는
  큰 뜀이 나타나지 않는다 — 그래서 경로 대신 창마다 따로 찾는다. 창 길이·간격은 고정(4·2마디)이고 비율은 전역 값이라, 템포가 크게
  다른 구간이나 4마디보다 짧은 건너뜀은 못 볼 수 있다.
- 정렬(직선 구하기)은 두 방식이 있다. 경로는 직선 편차를 1초 이동 중앙값으로 다듬어 쓴다.
  - **비율 범위 제한(기본, `tempo_ratio_range: [0.9, 1.1]`)**: 렌더 템포는 메타로 정확히 아므로 비율을 좁게 묶는다.
    1. **전역 탐색**: 비율 격자(최소~최대, 0.005 간격, 기본 41개)마다 마디 표 범위의 렌더 크로마를 비율만큼 늘려
       원곡 프레임 간격으로 다시 뽑고(선형 보간 + L2 정규화), 12음 각각 FFT 상호상관으로 **모든 오프셋**의 코사인
       합을 구한다. 점수 = 코사인 합 ÷ 렌더 프레임 수(원곡 밖은 0). 최고점의 (오프셋, 비율)을 고른다(묶지 않음,
       원곡 프레임 1개 = 약 23ms 해상도).
    2. **다듬기**: 직선 변형 유사도(warp_score)를 범위 안에서 2단 격자로 최대화. 오프셋은 마디 표 가운데
       시각 기준으로 찾아 긴 렌더에서 오프셋과 비율이 얽히지 않게 한다. 이 직선이 `offset_sec`·`tempo_ratio`.
    3. **띠 DTW**: 그 직선에서 원곡 쪽 ±1초 띠 안에서만 경로를 찾는다(`librosa.sequence.dtw(C=코사인 거리,
       subseq=True)`, 띠 밖 칸은 비용 1000). 묶기 규칙은 아래와 같다. `path_fit`은 이 경로의 직선 적합(참고값).
    - JSON의 `global_search`에 전역 탐색 결과(비율별 최고 점수 `score_by_ratio`, 2초 넘게 떨어진 다음 봉우리
      `second_peak_score`)를 남긴다. `fit_method` = `global_search+warp_refine`.
  - **자유 DTW(`tempo_ratio_range: null`, 예전 방식)**: `librosa.sequence.dtw(metric='cosine', subseq=True)`
    (렌더가 원곡 구간보다 길면 subseq 끔). k>1이면 찾은 원곡 구간 앞뒤 3초만 다시 더 작은 k로 DTW. 오프셋·템포
    비율은 마디 표 범위 경로의 직선 적합 뒤 warp_score가 커지는 쪽으로 (a, b)를 2단 격자 탐색.
    드럼 없는 성긴 렌더에서는 경로가 접혀 비율을 크게 틀릴 수 있다(아래 6절).
  - **묶기 규칙**(DTW 행렬 크기 제한): 두 쪽 중 긴 쪽 프레임 수가 3000을 넘으면 `k = ceil(max/3000)`개씩 시간 방향
    평균(두 쪽 같은 k).
- **정렬 경고**(`confidence.warnings`, 요약문 "경고:" 줄, 보고서 정렬 절): 비율이 범위 끝(0.0025 안)에 걸리면
  "끝에 걸렸다(아래 끝 0.9, ratio_at_bound)"(실제 비율이 범위 밖일 수 있음 — 범위를 넓히거나 null로 다시) +
  **`ratio_at_bound: true`** + **신뢰도 한 단계 낮춤**. 2초 넘게 떨어진 다른 위치의 전역 점수가 최고점의 97%
  이상이면 "반복 구간일 수 있음"(search_sec으로 좁혀 확인). `search_sec`을 줬고 고른 직선으로 옮긴 렌더 마디 범위가
  그 구간을 0.5초 넘게 벗어나면 "렌더 마디 범위가 원곡 1.5~16.7초(15.3초)로 옮겨져 search_sec 구간 0.0~6.0초(6.0초)를
  10.7초 벗어난다 …"(구간 밖 마디의 원곡 시각은 직선으로 늘린 값). `global_search.measures_outside_window_sec`에 벗어난 초(곡 전체 정렬에서도 기록만 함).
- **짧은 search_sec의 점수**: 전역 탐색 점수는 "코사인 합 ÷ 렌더 프레임 수"였다. 원곡 구간이 렌더보다
  짧으면 비율 b를 줄일수록 렌더가 줄어 구간 안에 더 많이 들어가 점수가 커지므로, 비율이 **조용히 범위 아래 끝(0.9)**
  으로 갔다(합성 시험: 정답 0.952에서 구간 [0,10] → 0.94, [0,6] → 0.900, [3,12] → 0.902). 원곡 프레임 수로 나누면
  반대로 렌더를 늘려 원곡의 다른 소리(앞 무음)까지 덮는 쪽(1.1)으로 쏠렸다. 지금은 렌더(× b)가 원곡 구간보다 길 때만
  **겹친 프레임의 평균 코사인**을 쓰고, 겹침이 원곡 구간의 50%보다 작은 위치는 뺀다(다듬기의 warp_score도 같은 규칙).
  구간이 렌더보다 길면 예전과 같다. `global_search.window_shorter_than_render`, `score_normalization`에 적힌다.
- 온셋: `librosa.onset.onset_detect(backtrack=False)`, hop 512. 원곡 온셋을 정렬 경로로 렌더 시각으로 옮겨
  가까운 쌍부터 1:1 짝짓기.
- RMS·스펙트럼 중심: 모노 RMS(dBFS, 진폭 1 사인파 = -3dBFS), `librosa.feature.spectral_centroid`(RMS 가중 평균).
- **K-가중 소리 크기(`k_db`)**: ITU-R BS.1770의 K-가중 필터(1단 고역 쉘프 약 1.7kHz 위 +4dB, 2단 RLB 고역통과
  약 38Hz) 2단 biquad. 아날로그 원형(libebur128과 같은 f0·Q·이득 값)을 미리 휜 주파수로 `scipy.signal.bilinear`해
  분석 샘플레이트(22050Hz)에서 계수를 계산하고 `sosfilt`(앞으로만)로 건다. 48kHz에서 BS.1770 표의 계수와 1e-9 안으로
  같다. `k_db` = −0.691 + 10·log10(마디 평균 제곱), 모노, 게이팅 없음(마디 단위). 1kHz 사인파에서 RMS dBFS와 거의
  같고(+0.04dB), 같은 RMS의 100Hz 사인파는 약 1.8dB, 50Hz는 약 4.6dB 작게 잰다. 쉬는 파트 판정(`silence_db`)과
  저음 뭉침은 그대로 RMS다.
  드럼·베이스 트랙에서 RMS와 K-가중 값의 차는 2dB 안팎이라 "킥이 가장 작게 들린다"는 귀의 판단을 다 설명하지는 못한다(아래 6절).
- **풍성함(`richness_compare`)**: 마디마다 (a) 음 시작 수 = `onset_compare`와 같은 검출(`onset.detect`)로 렌더는
  렌더 마디 [시작, 끝), 원곡은 정렬표의 원곡 마디 [시작, 끝) 안 온셋 수, `onset_ratio` = 렌더/원곡.
  (b) 대역 세기 = 마디 구간 `scipy.signal.welch`(nperseg 2048) 파워를 저(< `low_hz` 250Hz)·중·고(≥ `high_hz`
  2000Hz)로 나눈 평균 제곱 dB, `band_db_diff` = 렌더 − 원곡. 원곡은 마스터링으로 전체가 크므로 구간 전체 세기 차
  (`level_offset_db` = 마디별 전대역 차의 중앙값)를 뺀 `band_db_rel`(대역 모양 차)도 적는다. (c) 가장 빈 마디 =
  `onset_ratio` 오름차순(같으면 `band_db_rel` 평균 오름차순).
- 메타 `beats`는 렌더 절대 초로 읽는다. 마디 시작이 0보다 큰데 첫 박이 0이면 마디 기준 상대값으로 보고 더한다.

## 5. 성능

- 서버 시작 → `initialize` 응답: 약 0.75초(무거운 import는 첫 도구 호출 때). 아래 숫자는 16코어 PC에서 잰 값.
- 첫 `align`(약 20초 렌더 대 약 3분 원곡): numba 캐시가 있으면 약 3.7초, 캐시가 비었으면(처음 설치 직후) 약 14초.
- 곡 전체 대 곡 전체(약 3분 대 약 3분): 약 5초, 최대 작업 집합 약 360MB, 최대 커밋 약 340MB(자유 DTW 때 측정.
  범위 제한 방식은 첫 호출 약 5초, 크로마 캐시 뒤 약 1.9초, 메모리는 다시 재지 않음).
- 범위 제한 정렬(약 20초 렌더 대 약 3분 원곡): 첫 호출 약 3.2초(대부분 원곡 크로마), 같은 서버의 다음 렌더 약 0.25초.

## 6. 알려진 한계

- **auto 판정의 한계**: 원곡 템포가 구간 가운데에서 갑자기 바뀌어도 직선이 그 사이를 가르면 편차가 기준 안에 들 수
  있다. 합성 시험(렌더 120BPM 8마디, 원곡 앞 4마디 120·뒤 4마디 100BPM)에서 중앙 0.35박·최대 0.63박 → 직선을 골라
  마디 시작이 최대 0.45초 틀렸다(경로는 0.04초 안). 원곡이 점점 느려지는 경우(120→90)와 104→136으로 빨라지는
  경우는 경로를 골랐다. 120→108BPM(중앙 0.21박)도 직선이며 최대 0.25초 틀린다. 템포가 바뀌는 곡이면
  `mapping: "path"`로 지정하거나 `auto_median_beats`를 낮춘다(0.3이면 120→100 예는 경로).
- **긴 렌더는 한 곳의 편차로 렌더 전체가 경로로 바뀔 수 있다**: 판정은 렌더 하나에 하나다. 2마디 미만의 짧은 튐만 있고
  편차의 90번째 백분위가 0.4박 이하면 직선을 지키지만(위 auto 규칙), 길게 이어지는 편차는 렌더 전체를 경로로 돌린다.
  템포가 고정된 곡임을 알면 긴 렌더는 `mapping: "linear"`를 직접 주거나, 구간별로 나눠 align 한다. 어디서 벗어났는지는
  `path_deviation_sec.max_deviation_at_sec`(원곡 초)·`max_at_render_sec`(렌더 초)로 본다.
- **음 시작 수는 원곡 쪽이 적게 잡히기 쉽다**: 마스터링된 원곡은 소리가 빽빽해 온셋 강도 곡선의 봉우리가 덜
  뚜렷하다. 깨끗한 렌더가 원곡보다 많게(비율 1 초과) 나오는 일이 흔하다. 비율의 절대값보다 마디 사이 순위와 버전 사이
  차이를 본다. 고역(≥2000Hz) 차는 원곡의 심벌·보컬 치찰음 영향이 크다.
- **K-가중은 주파수 모양만 반영한다**: 짧게 끊기는 타격음(킥·스틱)은 마디 평균 세기가 같아도 더 작게 들리고,
  마비노기 드럼 소리 자체의 크기 차이도 있다. 이런 시간 쪽 효과는 `k_db`에 들어 있지 않다.
- **자유 DTW는 드럼 없는 성긴 렌더에서 경로가 접힐 수 있다**: 그래서 정렬의 기본은 템포 비율 범위를 제한한 방식이다.
- **트랙 하나만 넣은 렌더는 범위 제한으로도 다른 위치에 붙을 수 있다**: 곡 안 반복 구간과 성긴 음 때문이고, 경고나 낮은
  신뢰도 없이 틀리는 경우도 있다. `search_sec`으로 좁히면 나아지지만 늘 맞지는 않는다. 트랙별 파일은 전체 믹스로 정렬한
  align_json을 쓰는 것이 안전하다.
- 일정한 화음이 길게 이어지면 DTW 경로가 블록 안에서 계단처럼 흔들려 마디 경계가 수십 ms 틀어질 수 있다
  (합성 시험에서 마디 1 시작 32ms). 화음이 바뀌는 곳은 정확하다. 오프셋·템포 비율은 직선 다듬기로 보정한다.
- 오디오 가장자리(렌더 시작·끝)는 CQT·온셋 창 때문에 첫 마디 온셋 F가 낮게 나오기 쉽다.
- 온셋 허용 50ms는 정렬 오차와 hop(23ms)을 합친 값보다 크지 않다. 정렬 신뢰도가 "보통" 이하이면 온셋 F를
  리듬 판단에 쓰지 않는다.
- 묻힘 기준은 파트 수 N에 맞춰 낮추고 드럼·타악기는 기본으로 판정에서 뺀다. 그래서 드럼이 정말 안 들리는
  경우는 경고가 나오지 않는다 — 필요하면 `buried_include_unpitched: true`로 본다.
- 반주가 섞인 원곡 녹음과 악기 하나의 렌더를 비교하면 크로마 유사도는 원래 낮다(보컬·드럼·효과음). 절대값보다
  마디 사이 순위를 본다.
- mp3는 인코더 지연(수십 ms)이 있을 수 있다. 오프셋에 그대로 반영된다.
- `OPENBLAS_NUM_THREADS=1`: numpy의 OpenBLAS가 코어 수만큼 버퍼를 잡아 16코어에서 커밋이 약 500MB 늘어나는 것을
  막는다. 이 분석은 BLAS를 거의 쓰지 않아 속도 차이는 없었다.
- 도구는 한 번에 하나씩 실행한다(서버 안 잠금). 긴 분석 중 다른 호출은 기다린다.
- `phone_version`(출력 `out`을 안 주면 입력 WAV 옆 `폰용\`)과 `excerpt_ab`(`out_dir`를 안 주면 렌더 WAV 옆 `변형\`)는
  `--out-dir`·`MABIBRIDGE_OUT`이 아니라 입력 옆에 쓴다. 보호 폴더 규칙은 그대로 적용된다.
- **스템 단서의 한계**: 라이드와 오픈 하이햇은 둘 다 긴 고역 감쇠라 잘 갈리지 않는다(라이드는 언제나 낮은 신뢰도).
  쉐이커 같은 촘촘한 질감은 조용한 하이햇 여러 개로 나온다(`shaker_beats`로 표시). 탐은 80–300 Hz가 뚜렷하고 길 때만 —
  킥·스네어로 된 필은 탐으로 나누지 않는다. 울리는 크래시 밑의 하이햇, 크래시 바로 뒤의 크래시는 놓칠 수 있다. 다른 악기가
  스템에 샌 소리(오르골·피아노 어택)는 조용한 하이햇·사이드 스틱으로 나온다. 소리 크기는 스템 기준 상대값이고 키 표는 400 ms·
  v12 측정이라 세기 제안은 대략이다. 베이스: 같은 음높이로 붙어 이어지는 음은 사이 온셋이 약하면 앞 음에 붙는다(합성 시험에서
  쉼 없이 이어 친 G1 → F#1의 F#1 시작이 24틱 늦게 잡힘). 슬라이드·고스트 음·`min_note_ticks`보다 짧은 음은 빠진다. pyin은 곡
  전체(3분)에 약 30초 걸린다.
- **lead_check의 한계(0.5.0 평가)**: 8마디 심은 음 시험(원곡 믹스와 보컬 스템 둘, 시간 지도 ±60ms·템포 ±0.5%와 그 조합까지)에서는
  틀린 음을 5~6개 찾고 멀쩡한 음을 틀림으로 한 적이 없었지만, 한 예로 밴드 곡 한 곡 전체를 **전체 믹스**로 보면 맞다고 본 음의 10% 가까이를 틀림으로 냈고(confidence는 모두 0.6 이하) — 반주의
  화음 음이 노래보다 세게 잡히는 곳이다 — 같은 곡을 **보컬 스템**으로 보면 1% 안팎이었다(곡 하나의 측정값이며 곡마다 다르다). 믹스
  결과는 의심 목록으로만 쓰고 스템과(있으면) 노래 전사로 확인한다.
  음이름만 보고 리듬·화음·밸런스는 보지 않는다. 화음 코러스·더블링 구간에서는 더 센 목소리를 듣는다.
- **ridge 받아 적기의 한계**: 악기 솔로 8마디(참고로 쓴 다른 채보 50음과 대조)에서 같은 음 42(salience 41), 옥타브 틀림 1(3), 더한 음 3(6), 빠진 음
  6(2). 샌 반주를 빼다 그 자리의 약한 리드 음도 빠질 수 있고, 셋잇단 격자(`triplets`)는 이 솔로에서 오히려 나빴다(기본 끔).
- **전사 도구의 한계**: `stem_melody_notes`는 한 줄만 낸다. 화음 코러스·하모니·보컬 찹은 가장 센 줄로 접히거나 빠지고, 겹친
  보컬은 한 소절 통째로 옥타브가 틀릴 수 있다(`octave_ambiguous`를 본다). 노래가 쉬는 곳에서는 스템에 샌 다른 악기를 멜로디로 잡는다
  (confidence가 낮다). 70 ms보다 짧은 멜리스마는 이웃 음에 붙는다. 위치는 준 bpm·다운비트 그대로(위상 찾기 없음) — 노래가 일정하게
  늦으면 `grid_error_ticks`가 한쪽으로 쏠린다. 결과는 사람이 듣고 고칠 초안이다. `stem_chords`는 음이름 집합을 들으므로
  지속되는 멜로디 음이 화음에 들어가고(sus2·7화음으로 읽힘), 박보다 짧은 경과 화음은 평활에 묻힌다. 관계조(C/Am)·6화음(Am7 = C6)은
  조·베이스 가중이 가른다. 근음 정확도는 베이스 스템에 크게 기대고, 근음 아래로 걷는 자리바꿈 화음(예: Cmaj7/B)에서 틀리기 쉽다.
  도구의 `bass_agree`는 베이스 스템의 박별 크로마를 마디에서 더한 것(첫 박 1.5배)의 가장 센 음이름이 근음과 같은가 — 첫 박 쪽으로
  기운 마디 대표 베이스 음이다. `structure_repeats`는 고정 마디 격자 위에서만 맞다(반 마디 늦게 시작하는 프레이즈·템포 변화는
  대각선을 끊는다). 한 마디 루프와 짧은 전조 조각은 run이 아니라 `measure_hints`로만 나온다. 이미 옮긴 범위를
  `source_measures`로 주면 복사 지도가 그 범위를 원본으로 삼는다.

## 7. 시험

```bat
rem analysis 폴더에서, requirements-dev.txt를 깐 파이썬으로
rem 합성 신호 pytest(임시 폴더는 .cache\pytest-tmp). test_align_auto_richness.py = auto 정렬·richness·K-가중·stdio richness_compare. test_align_segments.py = 짧은 search_sec·ratio_at_bound·auto 튐·구간별 정렬·건너뜀 찾기. test_stems_drum_bass.py = 합성 드럼·베이스 스템(stem_drum_hits·stem_bass_notes, 드럼 키 표)·balance 실로폰 분류·리드 여유. test_melody_chords_structure.py = 합성 노래 스템(stem_melody_notes)·합성 화음 진행(stem_chords)·합성 곡 형식(structure_repeats)·모르는 인자 이름과 BAD_ARGUMENT 별칭. test_report_listen_downbeat.py = 구간 경고 보고서·phone_version·excerpt_ab·downbeat_check. test_portable.py = 다른 PC 기본값(출력 폴더·프로젝트 파일·보호 폴더·run_ear.py -I). test_tools.py·test_stdio.py = 도구 공통 규칙과 stdio 대화. test_ear050.py = align 고정 인자, lead_check(심은 틀린 음·맞는 줄·약한 구간·가운데/옆 믹스·render_meta+align 시간 지도), ridge 옥타브·샌 반주, vocal_agreement 양방향(합성 소리만)
.venv\Scripts\python.exe -X utf8 -m pytest -q
```

`pipe_check.py` 인자: 첫 `--` 앞은 이 스크립트 인자(실행기 `run-ear.cmd`와 `--call`·`--out`·`--stderr`·`--timeout`,
순서 자유), `--` 뒤는 서버 인자다. 예: `pipe_check.py run-ear.cmd --call ... -- --out-dir X --log Y`.
실행기를 `--` 바로 뒤에 써도 된다(`pipe_check.py --call ... --out ... -- run-ear.cmd --out-dir X --log Y`).

**요청 파일 드라이버**: `scripts\ear_requests.py <요청.jsonl> --out <응답.txt>`는 브리지 `McpClient`와 같은 요청 파일 형식
(한 줄에 `{"tool": "align", "args": {...}}`)을 `pipe_check.py`로 `run-ear.cmd`(실제 stdio 서버)에 보내고, 요청마다
`=== [번호] 도구 — ok|isError`와 결과 JSON을 응답 파일에 쓴다(서버 stderr는 `<응답>.err`, 인자 파일·원래 결과는
`.cache\ear_requests\`). 
