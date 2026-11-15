package mabibridge.compare;

import java.util.List;

/**
 * 쓰기 도구 응답에 붙는 짧은 compare 요약.
 *
 * @param skipped  멜로디(·화음) 비교를 건너뛰었는가. 참조가 없거나 매핑이 없을 때 true
 * @param reason   건너뛴 이유("참조 미로드" | "매핑 없음" | "바뀐 마디 없음"). 건너뛰지 않았으면 null
 * @param measures 요약에 넣은 마디들(바뀐 마디, 악보 마디)
 * @param summary  그 마디들의 합계(3~6항목은 skipped여도 들어 있다). 바뀐 마디가 없으면 null
 * @param text     두 문장 이내의 한국어 요약
 * @param pitchMode 쓴 비교 방식("sounding" | "written"). 매핑 항목에 적힌 값이 있으면 그것, 없으면 sounding
 * @param measureOffsetUsed 쓴 마디 차(악보 마디 = 참조 마디 + 이 값, 매핑 항목의 공통 값 또는 0)
 */
public record CompareSummary(boolean skipped, String reason, List<Integer> measures, CompareReport.Summary summary,
		String text, String pitchMode, int measureOffsetUsed) {

	public static final String REASON_NO_REFERENCE = "참조 미로드";
	public static final String REASON_NO_MAPPING = "매핑 없음";
	public static final String REASON_NO_CHANGE = "바뀐 마디 없음";

	/** 형태(written, 마디 차 0). */
	public CompareSummary(boolean skipped, String reason, List<Integer> measures, CompareReport.Summary summary,
			String text) {
		this(skipped, reason, measures, summary, text, Mapping.PITCH_WRITTEN, 0);
	}
}
