package mabibridge.mcp;

import java.util.List;

/**
 * compare 대응표의 한 줄. 키(악보 트랙 번호, 1부터)는 바깥 Map이 가진다.
 *
 * @param melodyFrom 이 악보 트랙의 멜로디 파트와 비교할 참조 트랙 번호들(1부터). 비어 있으면 멜로디 검사를 하지 않는다
 * @param chordsFrom 화음 검사에 쓸 참조 트랙 번호들. null이면 "all"(드럼 제외 전체)
 * @param matchParts 짝을 맞출 악보 파트: {@value #MATCH_MELODY}(기본, 멜로디 파트만) 또는 {@value #MATCH_ANY}
 *        (그 트랙의 melody·chord1·chord2·song 어느 파트든). null이면 {@value #MATCH_MELODY}
 * @param pitchMode  (선택): 이 트랙의 비교 방식 "sounding" | "written". null이면 compare 호출 값(없으면 기본 sounding)
 * @param measureOffset (선택): 이 트랙의 마디 차(악보 마디 = 참조 마디 + 이 값). null이면 호출 값(없으면 0)
 */
public record MappingEntry(List<Integer> melodyFrom, List<Integer> chordsFrom, String matchParts, String pitchMode,
		Integer measureOffset) {

	public static final String MATCH_MELODY = "melody";
	public static final String MATCH_ANY = "any";
	public static final String PITCH_SOUNDING = "sounding";
	public static final String PITCH_WRITTEN = "written";

	public MappingEntry {
		melodyFrom = (melodyFrom == null) ? List.of() : List.copyOf(melodyFrom);
		chordsFrom = (chordsFrom == null) ? null : List.copyOf(chordsFrom);
		matchParts = (matchParts == null || matchParts.isBlank()) ? MATCH_MELODY : matchParts;
		if (!MATCH_MELODY.equals(matchParts) && !MATCH_ANY.equals(matchParts)) {
			throw new IllegalArgumentException("match_parts는 \"melody\" 또는 \"any\": " + matchParts);
		}
		if (pitchMode != null && !PITCH_SOUNDING.equals(pitchMode) && !PITCH_WRITTEN.equals(pitchMode)) {
			throw new IllegalArgumentException("pitch_mode는 \"sounding\" 또는 \"written\": " + pitchMode);
		}
	}

	/** 형태(pitch_mode·measure_offset 없음). */
	public MappingEntry(List<Integer> melodyFrom, List<Integer> chordsFrom, String matchParts) {
		this(melodyFrom, chordsFrom, matchParts, null, null);
	}

	/** 0.6 이전 형태의 선택 값이 없으면 예전과 같은 문자열(로그·시험이 그대로 읽게). */
	@Override
	public String toString() {
		String s = "MappingEntry[melodyFrom=" + melodyFrom + ", chordsFrom=" + chordsFrom + ", matchParts=" + matchParts;
		if (pitchMode != null) {
			s += ", pitchMode=" + pitchMode;
		}
		if (measureOffset != null) {
			s += ", measureOffset=" + measureOffset;
		}
		return s + "]";
	}

	/** match_parts 없이(= "melody"). 호출 형태. */
	public MappingEntry(List<Integer> melodyFrom, List<Integer> chordsFrom) {
		this(melodyFrom, chordsFrom, MATCH_MELODY);
	}

	/** chordsFrom이 "all"(드럼 제외 전체)인가. */
	public boolean chordsFromAll() {
		return chordsFrom == null;
	}
}
