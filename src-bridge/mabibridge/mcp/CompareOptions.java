package mabibridge.mcp;

/**
 * compare 계열 도구의 선택 인자. null인 필드는 "도구 호출에서 주지 않음"이다: 그러면 매핑 항목에 저장한 값(있으면),
 * 그것도 없으면 기본값(pitch_mode "sounding", measure_offset 0, octave_tolerant false)을 쓴다.
 *
 * @param pitchMode         "sounding" | "written" | null
 * @param measureOffset     악보 마디 = 참조 마디 + 이 값 | null
 * @param measureOffsetAuto true면 −autoOffsetMax~+autoOffsetMax(기본 ±4) 중 맞은 음이 가장 많은 값을 찾는다(measureOffset 무시)
 * @param octaveTolerant    음이름이 같고 옥타브만 다른 음도 짝으로 인정(null이면 false)
 * @param autoOffsetMax     auto가 찾는 범위(1~64). null이면 4
 */
public record CompareOptions(String pitchMode, Integer measureOffset, boolean measureOffsetAuto, Boolean octaveTolerant,
		Integer autoOffsetMax) {

	/** 아무것도 주지 않음(쓰기 도구의 compare_summary, 인자 없는 compare). */
	public static final CompareOptions NONE = new CompareOptions(null, null, false, null, null);

	/** 이전 형태(auto_offset_max 없음). */
	public CompareOptions(String pitchMode, Integer measureOffset, boolean measureOffsetAuto, Boolean octaveTolerant) {
		this(pitchMode, measureOffset, measureOffsetAuto, octaveTolerant, null);
	}

	public boolean tolerant() {
		return Boolean.TRUE.equals(octaveTolerant);
	}
}
