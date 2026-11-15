package mabibridge.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 브리지 엔진의 오류. 코드 문자열 + 한국어 메시지 + 세부 정보 맵.
 *
 * <p>코드는 도구 공통 오류 코드를 쓴다. MCP 계층은 이 예외를
 * {@code { "error": code, "message": message, "details": details }}로 바꿔 돌려준다.</p>
 */
public class EngineException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public static final String NO_PROJECT = "NO_PROJECT";
	public static final String NO_REFERENCE = "NO_REFERENCE";
	public static final String BAD_NOTE = "BAD_NOTE";
	public static final String OVERLAP = "OVERLAP";
	public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
	public static final String FILE_EXISTS = "FILE_EXISTS";
	public static final String PROTECTED_PATH = "PROTECTED_PATH";
	public static final String MML_GENERATE_FAILED = "MML_GENERATE_FAILED";
	public static final String DLS_UNAVAILABLE = "DLS_UNAVAILABLE";
	/** 읽은 DLS 파일에 고른 트랙의 악기가 없음(다섯 음원 중 일부만 준 경우). */
	public static final String DLS_INCOMPLETE = "DLS_INCOMPLETE";
	/** 추가 코드: 인자 형식·범위 오류. */
	public static final String BAD_ARGUMENT = "BAD_ARGUMENT";
	/** 추가 코드: 경로 형식 오류(확장자 등). */
	public static final String BAD_PATH = "BAD_PATH";
	/** 추가 코드: 열 파일이 없음. */
	public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
	/** 추가 코드: 파일 읽기·쓰기 실패. */
	public static final String IO_ERROR = "IO_ERROR";
	/** 추가 코드: .mmi 해석 실패. */
	public static final String PARSE_FAILED = "PARSE_FAILED";
	/** 추가 코드: 트랙 24개 초과. */
	public static final String TOO_MANY_TRACKS = "TOO_MANY_TRACKS";
	/** 추가 코드: 없는 트랙 번호. */
	public static final String NO_TRACK = "NO_TRACK";
	/** 추가 코드: 없는 스냅샷 id. */
	public static final String NO_SNAPSHOT = "NO_SNAPSHOT";
	/** pitch_mode "sounding"으로 준 들리는 음을 그 악기로 낼 수 없음(음역 밖·무음 구간·고정·무음높이 악기). */
	public static final String PITCH_NOT_PRODUCIBLE = "PITCH_NOT_PRODUCIBLE";

	private final String code;
	private final Map<String, Object> details;

	public EngineException(String code, String message) {
		this(code, message, null, null);
	}

	public EngineException(String code, String message, Map<String, Object> details) {
		this(code, message, details, null);
	}

	public EngineException(String code, String message, Map<String, Object> details, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.details = (details == null) ? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	public String code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}

	@Override
	public String toString() {
		return code + ": " + getMessage() + (details.isEmpty() ? "" : " " + details);
	}

	/** 순서를 지키는 details 맵을 짧게 만드는 도우미. 인자는 키, 값, 키, 값 … */
	static Map<String, Object> details(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}
}
