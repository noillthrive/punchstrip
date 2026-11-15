package mabibridge.compare;

import java.util.List;
import java.util.Map;

/**
 * {@code compare_suggest_mapping} 결과. 저장하지 않는다. Gson으로 그대로 직렬화한다
 * (MCP 쪽에서 snake_case로 바뀌어 나간다).
 *
 * @param range            [from, to] 마디(참조 마디)
 * @param matchParts       짝을 맞춘 방식(언제나 "any": 악보 트랙의 모든 파트)
 * @param refTracks        후보로 본 참조 트랙(범위 안에 길이 있는 드럼 아닌 음이 하나라도 있는 트랙)
 * @param tracks           악보 트랙마다 상위 3개 제안
 * @param suggestedMapping 1위 제안으로 만든 {@code compare_set_mapping}용 대응표(그대로 넣을 수 있다. 제안이 없는 트랙은 빠짐).
 *                         항목마다 pitch_mode·measure_offset도 적는다
 * @param notesSkipped     빠진 것과 그 이유
 * @param pitchMode        "sounding"(들리는 음) | "written"(적은 음)
 * @param measureOffsetUsed 쓴 마디 차(악보 마디 = 참조 마디 + 이 값)
 * @param measureOffsetAuto auto로 찾았는가
 * @param measureOffsetScores auto일 때 차마다 맞은 음 수(옥타브 무시). 아니면 null
 * @param scoreRange       악보 쪽 마디 범위
 * @param octaveTolerant   음이름이 같고 옥타브만 다른 음도 일치로 셌는가
 * @param text             사람이 읽는 한국어 요약
 */
public record MappingSuggestion(List<Integer> range, String matchParts, List<Integer> refTracks,
		List<TrackSuggestion> tracks, Map<String, Map<String, Object>> suggestedMapping, List<String> notesSkipped,
		String pitchMode, int measureOffsetUsed, boolean measureOffsetAuto, Map<String, Integer> measureOffsetScores,
		List<Integer> scoreRange, boolean octaveTolerant, String text) {

	/**
	 * 악보 트랙 하나의 제안.
	 *
	 * @param scoreNotes  범위 안 악보 음 수(모든 파트)
	 * @param drum        타악기 트랙인가(그러면 제안하지 않는다)
	 * @param suggestions 상위 3개(① small_sample 아니고 score &gt; 0 → ② small_sample이고 score &gt; 0
	 *                    → ③ small_sample 아니고 score ≤ 0 → ④ small_sample이고 score ≤ 0,
	 *                    묶음 안은 score 높은 순. 맞은 음이 0인 참조 트랙은 넣지 않음)
	 * @param note        제안이 없을 때 이유
	 * @param sounding    트랙 악기의 들리는 음 요약("+1옥타브" 등)
	 * @param smallSampleThreshold 이 트랙에 쓴 small_sample 기준 수(참조 음 수가 이보다 작으면 small_sample)
	 *                    = min(max(8, ceil(score_notes × 10%)), score_notes). 후보를 매기지 않은 트랙(타악기·음높이 비교 안 함·악보 음 없음)은 null
	 */
	public record TrackSuggestion(int track, String name, int program, String instrument, boolean drum, int scoreNotes,
			List<Candidate> suggestions, String note, String sounding, Integer smallSampleThreshold) {}

	/**
	 * 참조 트랙 후보 하나.
	 *
	 * @param refTrack          참조 트랙 번호(1부터)
	 * @param name              참조 트랙 이름
	 * @param ratio             matched ÷ total(소수 셋째 자리 반올림)
	 * @param matched           악보 트랙의 어느 파트와 같은 음·시작 차 ≤ 6틱으로 짝이 된 참조 음 수(1:1). octave_tolerant면
	 *                          음이름만 같은 짝도 센다
	 * @param total             범위 안 참조 음 수(길이 0 음은 뺌, 같은 시작·같은 음은 하나)
	 * @param byPart            matched를 악보 파트별로 나눈 수(0인 파트는 뺌)
	 * @param ignoredZeroLength 뺀 길이 0 음 수
	 * @param octaveShift       악보의 비교 음(sounding이면 들리는 음)이 원곡보다 몇 옥타브 높은지(−2~+2). 비교 음을
	 *                          옥타브 단위로 옮겨 엄격하게 맞춰 보고 가장 잘 맞는 차를 고른다(같으면 0에 가까운 쪽). 0이면 지금 그대로가
	 *                          가장 잘 맞음 = sounding이면 들리는 음이 원곡과 같다. +1이면 악보가 1옥타브 높게 울림
	 * @param octaveShiftRatio  그 이동에서의 일치율
	 * @param octaveShiftMatched 그 이동에서 맞은 음 수
	 * @param octaveShifted     matched 중 옥타브만 다른 짝(octave_tolerant일 때만 0이 아닐 수 있음)
	 * @param score             순위 점수 = matched − 0.5 × (total − matched). 맞은 음은 더하고 빠진(안 맞은) 참조 음은 반만 뺀다
	 * @param smallSample       total이 small_sample 기준 수(min(max(8, ceil(악보 트랙 음 수 × 10%)), 악보 트랙 음 수))보다
	 *                          작은가. 그러면 일치율이 높아도 score &gt; 0인 small_sample 아닌 후보 뒤로 보낸다
	 */
	public record Candidate(int refTrack, String name, double ratio, int matched, int total,
			Map<String, Integer> byPart, int ignoredZeroLength, int octaveShift, double octaveShiftRatio,
			int octaveShiftMatched, int octaveShifted, double score, boolean smallSample) {}
}
