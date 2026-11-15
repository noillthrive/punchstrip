package mabibridge.compare;

import java.util.List;
import java.util.Map;

import mabibridge.engine.NoteView;

/**
 * compare 결과. Gson으로 그대로 직렬화한다. 필드 이름은 도구 응답 snake_case의 camelCase판이다
 * (MCP 쪽에서 {@code FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES}로 바꿔 내보낸다).
 *
 * <p>틱은 MML 틱(4분음표 = 96), 위치는 "마디:박:틱", 음 이름은 "C4"(MIDI 60) 형식이다.</p>
 *
 * @param range         [from, to] 마디
 * @param mappingUsed   실제로 쓴 대응표(악보 트랙 → {melodyFrom, chordsFrom: 목록 또는 "all"})
 * @param chordsFrom    화음 비교에 쓴 참조 트랙들(null = 드럼 제외 전체, 참조가 없으면 null)
 * @param scoreTracks   검사한 악보 트랙들
 * @param dlsLoaded     음역 검사에 DLS 음역을 썼는가
 * @param summary       범위 전체 합계
 * @param melodyTracks  멜로디를 비교한 악보 트랙별 합계
 * @param measures      마디별 상세
 * @param rangeChecks   트랙(악기)별 음역 검사 기준
 * @param chars         트랙별 글자 수(두 기준)
 * @param notesSkipped  건너뛴 검사와 그 이유
 * @param pitchMode     "sounding"(악보 음을 악기별 들리는 음으로 바꿔 비교) 또는 "written"(적은 음 그대로)
 * @param measureOffsetUsed 쓴 마디 차(악보 마디 = 참조 마디 + 이 값)
 * @param measureOffsetAuto measure_offset "auto"로 찾았는가
 * @param measureOffsetScores auto일 때 차마다 맞은 음 수(옥타브 무시, 음이름·시작 기준). auto가 아니면 null
 * @param scoreRange    악보 쪽 마디 범위 [from + offset, to + offset]. range는 참조 마디
 * @param octaveTolerant 음이름이 같고 옥타브만 다른 음도 짝으로 인정했는가
 * @param pitchCompareSkipped 음높이 비교를 하지 않은 악보 트랙(고정음·음높이 없음·효과음 악기, sounding일 때).
 *                      타악기 트랙(D·P·M)은 비교 방식과 무관하게 kind "drum"으로 넣는다
 * @param soundingWarnings 무음 구간에 적힌 음(silent_note), 구간 악기의 두 세트를 섞어 쓴 트랙(timbre_mix)
 * @param text          사람이 읽는 한국어 요약 문단
 */
public record CompareReport(List<Integer> range, Map<String, MappingView> mappingUsed, List<Integer> chordsFrom,
		List<Integer> scoreTracks, boolean dlsLoaded, Summary summary, List<MelodyTrack> melodyTracks,
		List<MeasureDetail> measures, List<RangeCheck> rangeChecks, List<CharsTrack> chars, List<String> notesSkipped,
		String pitchMode, int measureOffsetUsed, boolean measureOffsetAuto, Map<String, Integer> measureOffsetScores,
		List<Integer> scoreRange, boolean octaveTolerant, List<PitchSkip> pitchCompareSkipped,
		List<SoundingWarning> soundingWarnings, String text) {

	/**
	 * 대응표 한 줄. chordsFrom은 번호 목록 또는 문자열 "all". matchParts는 "melody" 또는 "any".
	 * pitchMode·measureOffset은 의 항목별 값(저장하지 않았으면 null).
	 */
	public record MappingView(List<Integer> melodyFrom, Object chordsFrom, String matchParts, String pitchMode,
			Integer measureOffset) {}

	/** 음높이 비교를 하지 않은 악보 트랙. kind는 fixed·unpitched·sfx, 타악기는 drum. */
	public record PitchSkip(int track, int program, String instrument, String kind, String reason) {}

	/**
	 * 들리는 음 경고.
	 *
	 * @param code      "silent_note"(무음 구간에 적힌 음) 또는 "timbre_mix"(구간 악기에서 default·alt 세트를 함께 씀, 정보성)
	 * @param count     silent_note: 음 수 / timbre_mix: 적은 쪽 세트의 음 수
	 * @param positions 위치 예시(최대 10개, "마디:박:틱 음이름", 악보 위치)
	 * @param timbres   timbre_mix: 세트별 음 수(silent_note는 null)
	 */
	public record SoundingWarning(String code, int track, int program, String instrument, int count,
			List<String> positions, Map<String, Integer> timbres, String message) {}

	/**
	 * 합계.
	 *
	 * @param melody            멜로디 판정 합계(멜로디 검사를 하지 않았으면 모두 0)
	 * @param chordBeats        화음을 비교한 박 수(참조가 없으면 0)
	 * @param chordMissingBeats 원곡 음이름이 악보에 하나라도 빠진 박 수
	 * @param chordExtraBeats   악보에만 있는 음이름이 있는 박 수(정보)
	 * @param overlaps          파트 안 겹침 쌍 수
	 * @param crossings         성부 교차 수
	 * @param rangeViolations   음역 밖 음표 수
	 * @param charsOver         두 기준에서 초과한 "트랙:파트" 목록
	 * @param ignoredZeroLength 멜로디 짝짓기에서 뺀 원곡의 길이 0 음 수(원본 길이 src_len = 0, 울리지 않는 음.)
	 * @param chordIgnoredZeroLength 화음 비교에서 뺀 길이 0 음 수(박마다 센 것의 합)
	 * @param union             참조 음마다 매핑의 어느 악보 트랙에서든 맞았는지로 센 합산(중복 제거) 일치.
	 *                          멜로디 비교를 하지 않았으면 null. {@code melody}는 매핑 항목마다 따로 센 합이라 같은 참조 트랙을
	 *                          여러 악보 트랙에 매핑하면 분모가 그만큼 늘어난다
	 */
	public record Summary(MelodyCounts melody, int chordBeats, int chordMissingBeats, int chordExtraBeats, int overlaps,
			int crossings, int rangeViolations, CharsOver charsOver, int ignoredZeroLength, int chordIgnoredZeroLength,
			int silentNotes, Union union) {

		/** 이전 형태(union null). */
		public Summary(MelodyCounts melody, int chordBeats, int chordMissingBeats, int chordExtraBeats, int overlaps,
				int crossings, int rangeViolations, CharsOver charsOver, int ignoredZeroLength, int chordIgnoredZeroLength,
				int silentNotes) {
			this(melody, chordBeats, chordMissingBeats, chordExtraBeats, overlaps, crossings, rangeViolations, charsOver,
					ignoredZeroLength, chordIgnoredZeroLength, silentNotes, null);
		}

		/** 이전 형태(silentNotes 0). */
		public Summary(MelodyCounts melody, int chordBeats, int chordMissingBeats, int chordExtraBeats, int overlaps,
				int crossings, int rangeViolations, CharsOver charsOver, int ignoredZeroLength, int chordIgnoredZeroLength) {
			this(melody, chordBeats, chordMissingBeats, chordExtraBeats, overlaps, crossings, rangeViolations, charsOver,
					ignoredZeroLength, chordIgnoredZeroLength, 0, null);
		}

		Summary withUnion(Union u) {
			return new Summary(melody, chordBeats, chordMissingBeats, chordExtraBeats, overlaps, crossings,
					rangeViolations, charsOver, ignoredZeroLength, chordIgnoredZeroLength, silentNotes, u);
		}
	}

	/**
	 * 합산(중복 제거) 일치. 참조 음 하나는 매핑 항목 여럿에 들어 있어도 한 번만 세고, 그중 어느 항목(악보 트랙)에서든
	 * {@code matched}(옥타브 허용이면 옥타브만 다른 짝 포함, 항목별 판정과 같은 규칙)였으면 맞은 것으로 센다.
	 *
	 * @param tracks     참조 트랙마다(참조 트랙 번호 순). 같은 트랙의 같은 시작·같은 음은 하나
	 * @param total      범위 안 참조 음 수(길이 0 음 제외). 서로 다른 참조 트랙의 같은 시작·같은 음도 하나로 본다
	 *                   (melody_from에 여러 트랙을 줄 때와 같은 규칙)
	 * @param matchedAny 그중 어느 악보 트랙에서든 맞은 음 수
	 * @param ratio      matchedAny ÷ total(소수 셋째 자리 반올림, total 0이면 null)
	 * @param entries    센 매핑 항목(악보 트랙) 수
	 */
	public record Union(List<UnionTrack> tracks, int total, int matchedAny, Double ratio, int entries) {}

	/**
	 * 합산 일치의 참조 트랙 하나.
	 *
	 * @param scoreTracks 이 참조 트랙을 melody_from에 둔 악보 트랙들
	 */
	public record UnionTrack(int refTrack, int total, int matchedAny, Double ratio, List<Integer> scoreTracks) {}

	/**
	 * 멜로디 판정 개수. matched+wrong+shifted+missing = 참조 음 수. octaveShifted는 matched 중 octave_tolerant로
	 * 인정한 것(음이름은 같고 옥타브만 다름)의 수다.
	 */
	public record MelodyCounts(int matched, int wrong, int shifted, int missing, int extra, int octaveShifted) {

		public MelodyCounts(int matched, int wrong, int shifted, int missing, int extra) {
			this(matched, wrong, shifted, missing, extra, 0);
		}

		public int refTotal() {
			return matched + wrong + shifted + missing;
		}

		public int issues() {
			return wrong + shifted + missing + extra;
		}

		MelodyCounts plus(MelodyCounts o) {
			return new MelodyCounts(matched + o.matched, wrong + o.wrong, shifted + o.shifted, missing + o.missing,
					extra + o.extra, octaveShifted + o.octaveShifted);
		}

		static final MelodyCounts ZERO = new MelodyCounts(0, 0, 0, 0, 0, 0);
	}

	/** 글자 수 초과("1:chord2" 형식). */
	public record CharsOver(List<String> songExcluded, List<String> songIncluded) {}

	/**
	 * 멜로디를 비교한 악보 트랙 하나의 합계.
	 *
	 * @param refNotes    센 참조 음 수(길이 0 음은 빼고)
	 * @param scoreNotes  범위 안 악보 멜로디 파트 음 수
	 * @param wrongOctave wrong 중 음이름은 같고 옥타브만 다른 것의 수(정보)
	 * @param matchParts  "melody"(멜로디 파트만) 또는 "any"(모든 파트와 짝)
	 * @param ignoredZeroLength 짝짓기에서 뺀 원곡의 길이 0 음 수
	 * @param matchedByPart matched를 악보 파트별로 나눈 수(키 melody·chord1·chord2·song, 0인 파트는 뺌)
	 * @param pitchMode     이 트랙에 쓴 비교 방식("sounding" | "written")
	 * @param measureOffset 이 트랙에 쓴 마디 차(악보 = 참조 + 이 값)
	 * @param silentNotes   무음 구간에 적혀 비교에서 뺀 악보 음 수(sounding일 때만 뺀다)
	 * @param octaveShiftMain 음이름이 같고 시작 차 6틱 이하인 짝(일치·옥타브 이동·옥타브만 다른 wrong)에서 가장 많은 음높이 차
	 *        (악보 − 참조, 반음. 0이면 옥타브 이동 없음). 그런 짝이 없으면 null
	 * @param octaveShiftCounts 그 차별 짝 수(키 "0", "+12", "-24" …)
	 */
	public record MelodyTrack(int track, List<Integer> melodyFrom, int refNotes, int scoreNotes, MelodyCounts counts,
			int wrongOctave, String matchParts, int ignoredZeroLength, Map<String, Integer> matchedByPart,
			String pitchMode, int measureOffset, int silentNotes, Integer octaveShiftMain,
			Map<String, Integer> octaveShiftCounts) {}

	/**
	 * 마디 하나의 상세.
	 *
	 * @param melody    악보 트랙별 멜로디 판정(일치하지 않은 음만 items에 넣는다)
	 * @param chords    박마다 화음 비교(참조가 없으면 빈 목록)
	 * @param crossings 성부 교차(시작 순)
	 * @param range     음역 밖 음표
	 * @param overlaps  파트 안 겹침
	 */
	public record MeasureDetail(int measure, List<MelodyMeasure> melody, List<ChordBeat> chords,
			List<Crossing> crossings, List<RangeViolation> range, List<Overlap> overlaps, int scoreMeasure,
			int silentNotes) {}

	/**
	 * 한 마디·한 악보 트랙의 멜로디 판정. items는 matched가 아닌 것만(matched는 개수만, 파트별 수는 matchedByPart).
	 *
	 * @param ignoredZeroLength 이 마디에서 뺀 원곡의 길이 0 음 수
	 * @param matchedByPart     matched를 악보 파트별로 나눈 수(0인 파트는 뺌)
	 */
	public record MelodyMeasure(int track, MelodyCounts counts, List<MelodyItem> items, int ignoredZeroLength,
			Map<String, Integer> matchedByPart) {}

	/**
	 * 멜로디 판정 하나.
	 *
	 * @param status   "wrong" | "shifted" | "missing" | "extra"
	 * @param pos      참조 음 위치(extra면 악보 음 위치)
	 * @param refTrack 참조 트랙
	 * @param tickDiff 악보 시작 − 참조 시작(틱)
	 * @param lenDiff  악보 길이 − 참조 길이(틱, 정보로만)
	 * @param octaveOnly wrong일 때만: 음이름은 같고 옥타브만 다른가
	 * @param part     짝이 된(또는 extra인) 악보 음의 파트(melody·chord1·chord2·song). 악보 음이 없으면 null
	 * @param scoreWritten 악보 음의 적은 음 이름(score·scorePitch는 비교에 쓴 음 = sounding이면 들리는 음)
	 * @param scoreWrittenPitch 악보 음의 적은 음 MIDI 번호
	 */
	public record MelodyItem(String status, String pos, Integer refTrack, String ref, Integer refPitch, Long refTick,
			Long refLen, String score, Integer scorePitch, Long scoreTick, Long scoreLen, String scorePos,
			Long tickDiff, Long lenDiff, Boolean octaveOnly, String part, String scoreWritten,
			Integer scoreWrittenPitch) {}

	/**
	 * 박 하나의 화음 비교.
	 *
	 * @param pos     "마디:박:0"
	 * @param ref     참조에서 울리는 음이름(C→B 순)
	 * @param score   악보에서 울리는 음이름
	 * @param missing 참조에만 있는 음이름
	 * @param extra   악보에만 있는 음이름
	 * @param ignoredZeroLength 이 박에서 뺀 원곡의 길이 0 음 수(울리지 않으므로 ref에 넣지 않음)
	 */
	public record ChordBeat(String pos, List<String> ref, List<String> score, List<String> missing,
			List<String> extra, int ignoredZeroLength) {}

	/**
	 * 성부 교차 하나(같은 트랙에서 위 파트 음이 아래 파트 음보다 낮게 동시에 울림).
	 *
	 * @param type "melody<chord1" 또는 "chord1<chord2"
	 * @param pos  두 음이 처음 함께 울리는 위치(범위 안으로 자름)
	 */
	public record Crossing(int track, String type, String pos, long tick, String melody, String chord1,
			String chord2) {}

	/** 음역 밖 음표. low/high는 음 이름(한쪽이 없으면 null). */
	public record RangeViolation(int track, String part, String pos, long tick, int pitch, String name, int program,
			String low, String high, String rangeSource) {}

	/** 파트 안 겹침 쌍({@code first.tick + first.len > second.tick}). */
	public record Overlap(int track, String part, String pos, NoteView first, NoteView second) {}

	/**
	 * 트랙·악기 하나의 음역 검사 기준.
	 *
	 * @param parts       이 악기로 검사한 파트들
	 * @param checked     검사했는가(음역이 없으면 false)
	 * @param rangeSource "dls" 또는 "properties"
	 * @param note        검사하지 않았으면 이유
	 */
	public record RangeCheck(int track, List<String> parts, int program, String instrument, String low, String high,
			String rangeSource, boolean checked, int violations, String note) {}

	/**
	 * 트랙 하나의 글자 수와 두 기준의 초과 파트.
	 *
	 * @param songNotAllowed 노래 파트가 있어 노래 제외 기준으로는 실을 수 없는가(글자 수 초과와는 다른 판정)
	 */
	public record CharsTrack(int track, Map<String, Integer> chars, List<String> songExcludedOver,
			List<String> songIncludedOver, boolean songNotAllowed, String defaultRule) {}
}
