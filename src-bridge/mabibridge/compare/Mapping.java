package mabibridge.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * compare가 쓰는 대응표. 악보 트랙(1부터) → 그 트랙이 따라야 할 참조 트랙들.
 *
 * <p>비어 있으면 멜로디 검사는 건너뛰고, 화음은 참조 전체(드럼 제외)와 비교한다.</p>
 *
 * @param tracks 악보 트랙 번호 → 항목. 번호 순서로 보관한다.
 */
public record Mapping(Map<Integer, Mapping.Entry> tracks) {

	/** match_parts 값: 악보 멜로디 파트만(기본, 동작). */
	public static final String MATCH_MELODY = "melody";
	/** match_parts 값: 악보 트랙의 melody·chord1·chord2·song 어느 파트든. */
	public static final String MATCH_ANY = "any";
	/** pitch_mode 값: 악보 음을 악기별 들리는 음으로 바꿔 비교(compare 도구의 기본). */
	public static final String PITCH_SOUNDING = "sounding";
	/** pitch_mode 값: 적은 음 그대로 비교(예전 동작). */
	public static final String PITCH_WRITTEN = "written";

	/**
	 * 대응 항목 하나.
	 *
	 * @param melodyFrom 멜로디를 비교할 참조 트랙들(null 또는 비어 있으면 멜로디 검사 안 함)
	 * @param chordsFrom 화음을 비교할 참조 트랙들. null이면 전체(드럼 제외)
	 * @param matchParts {@value #MATCH_MELODY}(기본) 또는 {@value #MATCH_ANY}. null이면 {@value #MATCH_MELODY}
	 * @param pitchMode  이 트랙의 비교 방식({@value #PITCH_SOUNDING}·{@value #PITCH_WRITTEN}). null이면 호출 값·기본값
	 * @param measureOffset 이 트랙의 마디 차(악보 = 참조 + 이 값). null이면 호출 값·기본값
	 */
	public record Entry(List<Integer> melodyFrom, List<Integer> chordsFrom, String matchParts, String pitchMode,
			Integer measureOffset) {
		public Entry {
			melodyFrom = (melodyFrom == null) ? null : List.copyOf(melodyFrom);
			chordsFrom = (chordsFrom == null) ? null : List.copyOf(chordsFrom);
			matchParts = normalizeMatchParts(matchParts);
			pitchMode = (pitchMode == null) ? null : normalizePitchMode(pitchMode);
		}

		/** 형태(pitch_mode·measure_offset 없음). */
		public Entry(List<Integer> melodyFrom, List<Integer> chordsFrom, String matchParts) {
			this(melodyFrom, chordsFrom, matchParts, null, null);
		}

		/** match_parts 없이(= "melody"). 호출 형태. */
		public Entry(List<Integer> melodyFrom, List<Integer> chordsFrom) {
			this(melodyFrom, chordsFrom, MATCH_MELODY);
		}

		/** 악보 트랙의 모든 파트와 짝을 맞추는가. */
		public boolean matchAny() {
			return MATCH_ANY.equals(matchParts);
		}

		public boolean hasMelody() {
			return melodyFrom != null && !melodyFrom.isEmpty();
		}
	}

	public Mapping {
		tracks = (tracks == null) ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tracks));
	}

	/**
	 * match_parts 값을 확인한다. null·빈 문자열은 {@value #MATCH_MELODY}.
	 *
	 * @throws IllegalArgumentException "melody"·"any"가 아닐 때
	 */
	public static String normalizeMatchParts(String v) {
		if (v == null || v.isBlank()) {
			return MATCH_MELODY;
		}
		String t = v.trim().toLowerCase(java.util.Locale.ROOT);
		if (!MATCH_MELODY.equals(t) && !MATCH_ANY.equals(t)) {
			throw new IllegalArgumentException("match_parts는 \"melody\" 또는 \"any\"여야 함: " + v);
		}
		return t;
	}

	/**
	 * pitch_mode 값을 확인한다. null·빈 문자열은 {@value #PITCH_SOUNDING}.
	 *
	 * @throws IllegalArgumentException "sounding"·"written"이 아닐 때
	 */
	public static String normalizePitchMode(String v) {
		if (v == null || v.isBlank()) {
			return PITCH_SOUNDING;
		}
		String t = v.trim().toLowerCase(java.util.Locale.ROOT);
		if (!PITCH_SOUNDING.equals(t) && !PITCH_WRITTEN.equals(t)) {
			throw new IllegalArgumentException("pitch_mode는 \"sounding\" 또는 \"written\"이어야 함: " + v);
		}
		return t;
	}

	/** 빈 대응표. */
	public static Mapping empty() {
		return new Mapping(Map.of());
	}

	/** 악보 트랙 하나짜리 대응표. */
	public static Mapping of(int scoreTrack, List<Integer> melodyFrom, List<Integer> chordsFrom) {
		return new Mapping(Map.of(scoreTrack, new Entry(melodyFrom, chordsFrom)));
	}

	public boolean isEmpty() {
		return tracks.isEmpty();
	}

	/** 멜로디 검사 대상이 하나라도 있는가. */
	public boolean hasMelody() {
		for (Entry e : tracks.values()) {
			if (e.hasMelody()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * JSON을 읽는다.
	 * {@code { "1": { "melody_from": [3], "chords_from": [3, 4] | "all", "match_parts": "melody" | "any" } }}.
	 * {@code melodyFrom}·{@code chordsFrom}(camelCase)도 받는다. 키가 없거나 "all"/null이면 chordsFrom = null(전체).
	 *
	 * @throws IllegalArgumentException 형식이 틀렸을 때(한국어 메시지)
	 */
	public static Mapping fromJson(String json) {
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("매핑 JSON을 해석할 수 없음: " + e.getMessage(), e);
		}
		if (root == null || root.isJsonNull()) {
			return empty();
		}
		if (!root.isJsonObject()) {
			throw new IllegalArgumentException("매핑은 {\"악보 트랙\": {\"melody_from\": [...], \"chords_from\": [...] | \"all\"}} 형식이어야 함");
		}
		Map<Integer, Entry> m = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
			int track;
			try {
				track = Integer.parseInt(e.getKey().trim());
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("매핑의 키는 악보 트랙 번호여야 함: " + e.getKey(), ex);
			}
			if (!e.getValue().isJsonObject()) {
				throw new IllegalArgumentException("매핑 트랙 " + track + "의 값은 객체여야 함");
			}
			JsonObject o = e.getValue().getAsJsonObject();
			List<Integer> mel = intList(o, track, "melody_from", "melodyFrom");
			List<Integer> chords = intList(o, track, "chords_from", "chordsFrom");
			JsonElement mp = o.has("match_parts") ? o.get("match_parts") : o.get("matchParts");
			String matchParts = (mp == null || mp.isJsonNull()) ? null : mp.getAsString();
			JsonElement pm = o.has("pitch_mode") ? o.get("pitch_mode") : o.get("pitchMode");
			String pitchMode = (pm == null || pm.isJsonNull()) ? null : pm.getAsString();
			JsonElement mo = o.has("measure_offset") ? o.get("measure_offset") : o.get("measureOffset");
			Integer offset = (mo == null || mo.isJsonNull()) ? null : mo.getAsInt();
			m.put(track, new Entry(mel, chords, matchParts, pitchMode, offset));
		}
		return new Mapping(m);
	}

	private static List<Integer> intList(JsonObject o, int track, String snake, String camel) {
		JsonElement v = o.has(snake) ? o.get(snake) : o.get(camel);
		if (v == null || v.isJsonNull()) {
			return null;
		}
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && "all".equalsIgnoreCase(v.getAsString().trim())) {
			return null;
		}
		List<Integer> l = new ArrayList<>();
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
			l.add(v.getAsInt());
			return l;
		}
		if (!v.isJsonArray()) {
			throw new IllegalArgumentException("매핑 트랙 " + track + "의 " + snake + "는 번호 목록 또는 \"all\"이어야 함");
		}
		JsonArray a = v.getAsJsonArray();
		for (JsonElement x : a) {
			try {
				l.add(x.getAsInt());
			} catch (RuntimeException ex) {
				throw new IllegalArgumentException("매핑 트랙 " + track + "의 " + snake + "에 번호가 아닌 값: " + x, ex);
			}
		}
		return l;
	}
}
