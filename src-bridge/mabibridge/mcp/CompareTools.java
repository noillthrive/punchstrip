package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.EngineException;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * compare 계열 도구. 실제 비교는 {@link CompareHook}이 한다.
 * 연결 지점이 비어 있으면 세 도구 모두 {@code NOT_WIRED}를 돌려준다.
 */
final class CompareTools {

	private CompareTools() {}

	static void register(ToolRegistry r) {
		JsonObject mapping = new JsonObject();
		mapping.addProperty("type", "object");
		mapping.addProperty("description",
				"악보 트랙 번호(문자열 키 \"1\") → {\"melody_from\": [참조 트랙...], \"chords_from\": [참조 트랙...] 또는 \"all\"}. "
						+ "chords_from을 생략하면 \"all\"(드럼 제외 전체). match_parts는 \"melody\"(기본) 또는 \"any\". "
						+ "선택 pitch_mode·measure_offset은 compare 호출에 그 인자가 없을 때 쓴다. 빈 객체 {}는 매핑을 지운다");
		JsonObject entry = new JsonObject();
		entry.addProperty("type", "object");
		JsonObject ep = new JsonObject();
		ep.add("melody_from", intArray("이 트랙의 멜로디와 비교할 참조 트랙 번호들"));
		JsonObject chords = new JsonObject();
		chords.addProperty("description", "화음 검사에 쓸 참조 트랙 번호들 또는 \"all\"");
		JsonArray any = new JsonArray();
		JsonObject a1 = new JsonObject();
		a1.addProperty("type", "array");
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		a1.add("items", items);
		JsonObject a2 = new JsonObject();
		a2.addProperty("type", "string");
		JsonArray en = new JsonArray();
		en.add("all");
		a2.add("enum", en);
		any.add(a1);
		any.add(a2);
		chords.add("anyOf", any);
		ep.add("chords_from", chords);
		ep.add("match_parts", stringEnum("짝을 맞출 악보 파트. \"melody\"(기본)는 멜로디 파트만, \"any\"는 이 트랙의 "
				+ "melody·chord1·chord2·song 어느 파트든(한 원곡 트랙을 여러 파트에 나눠 담았을 때)", "melody", "any"));
		ep.add("pitch_mode", stringEnum("(선택) 이 트랙의 비교 방식. compare 호출에 pitch_mode가 없을 때 쓴다(쓰기 도구의 "
				+ "compare_summary도). 없으면 sounding", "sounding", "written"));
		ep.add("measure_offset", integer("(선택) 이 트랙의 마디 차(악보 마디 = 참조 마디 + 이 값). compare 호출에 measure_offset이 "
				+ "없을 때 쓴다(쓰기 도구의 compare_summary도). 없으면 0", -64, 64));
		entry.add("properties", ep);
		entry.addProperty("additionalProperties", false);
		mapping.add("additionalProperties", entry);

		r.add("compare_set_mapping",
				"compare가 쓸 대응표(악보 트랙 ↔ 참조 트랙)를 통째로 바꾼다. 멜로디 검사는 매핑이 있는 트랙만 한다.",
				props().add("mapping", mapping).object("mapping"),
				CompareTools::setMapping);

		r.add("compare",
				"악보를 참조 MIDI와 마디 범위로 비교한다(멜로디 일치, 화음 구성음 누락, 겹침, 음역 이탈, 글자 수, 참고로 성부 교차). "
						+ "기본은 들리는 음 기준(pitch_mode \"sounding\": 악기마다 적은 음과 실제로 울리는 음의 차이를 반영). 악보가 원곡보다 "
						+ "몇 마디 늦게 시작하면 measure_offset(또는 \"auto\"). 결과 끝의 text가 사람이 읽는 요약이다. 처음 부르면 음원(DLS)을 읽어 몇 초 걸린다.",
				withOptions(props()
						.add("from_measure", integer("시작 마디(1부터, 참조 MIDI의 마디. 악보 쪽은 measure_offset을 더한 마디)", 1, null))
						.add("to_measure", integer("끝 마디(포함, 참조 마디)", 1, null))
						.add("tracks", intArray("검사할 악보 트랙 번호들. 생략하면 모든 트랙")))
						.object("from_measure", "to_measure"),
				CompareTools::compare);

		r.add("compare_suggest_mapping",
				"매핑을 모르는 악보(남의 편곡 등)에서, 악보 트랙마다 원곡(참조 MIDI) 어느 트랙을 따르는지 제안한다. "
						+ "트랙의 어느 파트든 같은 음·같은 시작이면 일치로 세어(match_parts \"any\") 상위 3개를 돌려준다. "
						+ "순위는 score(맞은 음 − 0.5 × 안 맞은 참조 음) 높은 순이고, 참조 음이 max(8, 악보 트랙 음 수의 10%)보다 적은 후보는 "
						+ "small_sample: true로 뒤로 보낸다. ratio(맞은 참조 음 ÷ 참조 음 수, 길이 0 음 제외)도 함께 준다. 후보마다 악보가 원곡보다 몇 옥타브 높게 울리는지(octave_shift)도 알려 준다. "
						+ "남의 편곡은 measure_offset: \"auto\"로 마디 어긋남을 찾는다. 저장하지 않는다. 쓰려면 suggested_mapping을 "
						+ "compare_set_mapping에 넣는다.",
				withOptions(props()
						.add("from_measure", integer("시작 마디(1부터, 참조 마디)", 1, null))
						.add("to_measure", integer("끝 마디(포함, 참조 마디)", 1, null))
						.add("tracks", intArray("제안할 악보 트랙 번호들. 생략하면 모든 트랙")))
						.object("from_measure", "to_measure"),
				CompareTools::suggestMapping);
	}

	/** compare·compare_suggest_mapping 공통 선택 인자. */
	private static ToolRegistry.Props withOptions(ToolRegistry.Props p) {
		return p.add("pitch_mode", stringEnum("비교 방식. \"sounding\"(기본): 악보 음을 악기별 들리는 음으로 바꿔 원곡과 비교"
				+ "(피아노 +1옥타브, 바이올린 +2옥타브 등. 무음 구간 음은 빼고, 고정음·음높이 없음·효과음 악기는 음높이 비교 안 함). "
				+ "\"written\": 적은 음 그대로(예전 동작). 생략하면 매핑 항목의 값, 그것도 없으면 sounding", "sounding", "written"))
				.add("measure_offset", ToolRegistry.intOrString("악보 마디 = 참조 마디 + 이 값(정수, 기본 0). 악보가 원곡보다 2마디 늦게 "
						+ "시작하면 2. \"auto\"면 −auto_offset_max~+auto_offset_max(기본 ±4) 중 맞은 음이 가장 많은 값(옥타브 무시)을 찾아 "
						+ "measure_offset_used로 알려 준다(범위 전체에 값 하나). 생략하면 매핑 항목의 값, 그것도 없으면 0"))
				.add("auto_offset_max", integer(" measure_offset \"auto\"가 찾는 범위 ±n(1~64, 기본 4). 구간을 잘라낸 악보처럼 "
						+ "원곡과 수십 마디 어긋나면 늘린다. auto일 때만", 1, 64))
				.add("octave_tolerant", bool("true면 음이름이 같고 옥타브만 다른 음도 짝으로 인정하고 octave_shifted 수와 트랙별 "
						+ "주된 이동량(octave_shift_main)을 따로 보고한다(틀린 음과 옥타브만 다른 음을 구분). 기본 false"));
	}

	/** 도구 인자 → 선택 인자. measure_offset은 정수 또는 "auto". from + offset이 1보다 작으면 BAD_ARGUMENT. */
	static CompareOptions options(Args a, int from) {
		String mode = a.optString("pitch_mode");
		if (mode != null && !MappingEntry.PITCH_SOUNDING.equals(mode) && !MappingEntry.PITCH_WRITTEN.equals(mode)) {
			throw Args.bad("pitch_mode는 \"sounding\" 또는 \"written\": " + mode, "pitch_mode", mode);
		}
		Integer off = null;
		boolean auto = false;
		JsonElement mo = a.raw("measure_offset");
		if (mo != null) {
			if (mo.isJsonPrimitive() && mo.getAsJsonPrimitive().isString()
					&& "auto".equalsIgnoreCase(mo.getAsString().trim())) {
				auto = true;
			} else {
				off = Args.toInt(mo, "measure_offset");
				if (off < -64 || off > 64) {
					throw Args.bad("measure_offset은 −64~64 또는 \"auto\": " + off, "measure_offset", off);
				}
				if (from + off < 1) {
					throw Args.bad("악보 쪽 시작 마디(from_measure + measure_offset = " + (from + off) + ")가 1보다 작음",
							"from_measure", from, "measure_offset", off);
				}
			}
		}
		Boolean tol = a.has("octave_tolerant") ? a.optBool("octave_tolerant", false) : null;
		Integer max = null;
		JsonElement am = a.raw("auto_offset_max");
		if (am != null) {
			if (!am.isJsonPrimitive() || !am.getAsJsonPrimitive().isNumber()) {
				throw Args.bad("auto_offset_max는 1~64 정수(JSON 수): " + am, "auto_offset_max", am.toString());
			}
			max = Args.toInt(am, "auto_offset_max");
			if (max < 1 || max > 64) {
				throw Args.bad("auto_offset_max는 1~64 정수: " + max, "auto_offset_max", max);
			}
			if (!auto) {
				throw Args.bad("auto_offset_max는 measure_offset \"auto\"일 때만 쓴다", "auto_offset_max", max);
			}
		}
		return new CompareOptions(mode, off, auto, tol, max);
	}

	private static Object setMapping(ServerState s, Args a) {
		s.requireCompareHook();
		JsonObject m = a.reqObject("mapping");
		ReferenceMidi ref = s.reference();
		Map<Integer, MappingEntry> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : m.entrySet()) {
			int scoreTrack;
			try {
				scoreTrack = Integer.parseInt(e.getKey().trim());
			} catch (NumberFormatException ex) {
				throw Args.bad("mapping 키는 악보 트랙 번호(\"1\" 등): " + e.getKey(), "key", e.getKey());
			}
			if (scoreTrack < 1) {
				throw Args.bad("악보 트랙 번호는 1 이상: " + scoreTrack, "key", e.getKey());
			}
			if (!e.getValue().isJsonObject()) {
				throw Args.bad("mapping." + e.getKey() + "는 객체 {melody_from, chords_from}", "key", e.getKey());
			}
			Args v = new Args(e.getValue().getAsJsonObject());
			v.checkKnown(Set.of("melody_from", "chords_from", "match_parts", "pitch_mode", "measure_offset"));
			List<Integer> melody = refTracks(ref, v.optIntList("melody_from"), e.getKey() + ".melody_from");
			List<Integer> chords;
			JsonElement c = v.raw("chords_from");
			if (c == null || (c.isJsonPrimitive() && "all".equals(c.getAsString()))) {
				chords = null;
			} else if (c.isJsonArray()) {
				chords = refTracks(ref, v.optIntList("chords_from"), e.getKey() + ".chords_from");
			} else {
				throw Args.bad("chords_from은 참조 트랙 번호 배열 또는 \"all\"", "key", e.getKey());
			}
			String matchParts = v.optString("match_parts");
			if (matchParts != null && !MappingEntry.MATCH_MELODY.equals(matchParts)
					&& !MappingEntry.MATCH_ANY.equals(matchParts)) {
				throw Args.bad("match_parts는 \"melody\" 또는 \"any\": " + matchParts, "key", e.getKey());
			}
			String pitchMode = v.optString("pitch_mode");
			if (pitchMode != null && !MappingEntry.PITCH_SOUNDING.equals(pitchMode)
					&& !MappingEntry.PITCH_WRITTEN.equals(pitchMode)) {
				throw Args.bad("pitch_mode는 \"sounding\" 또는 \"written\": " + pitchMode, "key", e.getKey());
			}
			Integer offset = v.optInt("measure_offset");
			if (offset != null && (offset < -64 || offset > 64)) {
				throw Args.bad("measure_offset은 −64~64 정수(항목에는 \"auto\"를 쓸 수 없음): " + offset, "key", e.getKey());
			}
			out.put(scoreTrack, new MappingEntry(melody, chords, matchParts, pitchMode, offset));
		}
		s.setMapping(out);
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.add("mapping", s.mappingJson());
		if (ref == null) {
			o.addProperty("note", "참조 MIDI가 아직 없어 참조 트랙 번호는 검사하지 않았음");
		}
		return o;
	}

	private static List<Integer> refTracks(ReferenceMidi ref, List<Integer> l, String where) {
		if (l == null) {
			return List.of();
		}
		List<Integer> out = new ArrayList<>(new LinkedHashSet<>(l));
		if (ref != null) {
			for (int t : out) {
				if (t < 1 || t > ref.trackCount()) {
					throw Args.bad(where + ": 참조 트랙 " + t + "번이 없음(1~" + ref.trackCount() + ")", "track", t);
				}
			}
		}
		return out;
	}

	private static Object compare(ServerState s, Args a) {
		CompareHook hook = s.requireCompareHook();
		Project p = s.project();
		ReferenceMidi ref = s.requireReference();
		int from = a.reqInt("from_measure");
		int to = a.reqInt("to_measure");
		ReferenceTools.checkRange(from, to);
		Set<Integer> tracks = new LinkedHashSet<>();
		List<Integer> req = a.optIntList("tracks");
		if (req == null || req.isEmpty()) {
			for (int i = 1; i <= p.trackCount(); i++) {
				tracks.add(i);
			}
		} else {
			for (int t : req) {
				if (t < 1 || t > p.trackCount()) {
					throw new EngineException(EngineException.NO_TRACK, "트랙 " + t + "번이 없음(1~" + p.trackCount() + ")",
							Map.of("track", t, "track_count", p.trackCount()));
				}
				tracks.add(t);
			}
		}
		// 음역 검사에 DLS가 필요하다. 실패하면 compare가 properties 음역으로 대신한다.
		try {
			s.engine().ensureDls();
		} catch (EngineException e) {
			System.err.println("[mcp] compare: DLS를 읽지 못함(properties 음역으로 대신): " + e.getMessage());
		}
		CompareOptions opt = options(a, from);
		try {
			return hook.compare(p, ref, s.mapping(), from, to, java.util.Collections.unmodifiableSet(tracks), opt);
		} catch (IllegalArgumentException e) {
			throw Args.bad(e.getMessage());
		}
	}

	private static Object suggestMapping(ServerState s, Args a) {
		CompareHook hook = s.requireCompareHook();
		Project p = s.project();
		ReferenceMidi ref = s.requireReference();
		int from = a.reqInt("from_measure");
		int to = a.reqInt("to_measure");
		ReferenceTools.checkRange(from, to);
		CompareOptions opt = options(a, from);
		try {
			return hook.suggestMapping(p, ref, from, to, java.util.Collections.unmodifiableSet(scoreTracks(p, a)), opt);
		} catch (IllegalArgumentException e) {
			throw Args.bad(e.getMessage());
		}
	}

	/** 도구 인자 tracks(생략하면 모든 트랙). 없는 번호는 NO_TRACK. */
	private static Set<Integer> scoreTracks(Project p, Args a) {
		Set<Integer> tracks = new LinkedHashSet<>();
		List<Integer> req = a.optIntList("tracks");
		if (req == null || req.isEmpty()) {
			for (int i = 1; i <= p.trackCount(); i++) {
				tracks.add(i);
			}
		} else {
			for (int t : req) {
				if (t < 1 || t > p.trackCount()) {
					throw new EngineException(EngineException.NO_TRACK, "트랙 " + t + "번이 없음(1~" + p.trackCount() + ")",
							Map.of("track", t, "track_count", p.trackCount()));
				}
				tracks.add(t);
			}
		}
		return tracks;
	}
}
