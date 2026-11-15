package mabibridge.engine;

import java.util.List;

/**
 * 쓰기 도구의 공통 응답. {@code compareSummary}는 compare 모듈의 몫이라 지금은 항상 null이고
 * {@code compareSummaryNote}에 이유를 적는다.
 *
 * @param track           1부터
 * @param parts           바뀐 파트 이름들
 * @param changedMeasures 바뀐 마디 번호(오름차순)
 * @param stats           해당 트랙의 글자 수 통계
 * @param added           넣은 음표 수
 * @param deleted         지운 음표 수(merge로 엔진이 지운 것 포함)
 * @param aligned         align=true로 맞춘 기록
 * @param affected        잘리거나 지워진 기존 음표
 * @param warnings        막지는 않지만 알려 줄 것(예: 악기가 쓰지 않는 파트)
 */
public record EditResult(boolean ok, int track, List<String> parts, List<Integer> changedMeasures, TrackStats stats,
		Object compareSummary, String compareSummaryNote, int added, int deleted, List<AlignedNote> aligned,
		List<AffectedNote> affected, List<String> warnings) {

	/** align으로 맞춘 음표. field는 "tick" 또는 "len", index는 입력 목록 안의 위치(0부터). */
	public record AlignedNote(String part, int index, String field, long from, long to) {}

	/**
	 * 기존 음표에 생긴 변화. change는 "truncated"(뒷부분이 잘림), "removed"(엔진 자동 정리로 지워짐),
	 * "deleted"(지우기 요청으로 지움), "cleared"(replace_range가 비움).
	 */
	public record AffectedNote(String part, String change, NoteView before, NoteView after) {}
}
