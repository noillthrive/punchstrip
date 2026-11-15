package mabibridge.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP 도구 목록. 도구마다 이름, 한국어 설명, 입력 스키마(JSON Schema object), 처리기.
 * {@link #standard()}가 도구 23개와 render_wav·compare_suggest_mapping·score_profile·project_set,
 * score_transform·score_fit_suggest·score_split_sheets, reference_voice_draft를 등록한다(31개).
 */
public final class ToolRegistry {

	/** 도구 처리기. 반환값은 Gson으로 직렬화되어 결과 text가 된다. 실패는 예외로(EngineException 권장). */
	@FunctionalInterface
	public interface Handler {
		Object call(ServerState state, Args args) throws Exception;
	}

	/** 도구 하나. */
	public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
		/** 스키마의 properties 이름들. */
		public Set<String> propertyNames() {
			JsonObject p = inputSchema.getAsJsonObject("properties");
			return p == null ? Set.of() : p.keySet();
		}

		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("description", description);
			o.add("inputSchema", inputSchema.deepCopy());
			return o;
		}
	}

	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public void add(String name, String description, JsonObject inputSchema, Handler handler) {
		if (tools.containsKey(name)) {
			throw new IllegalArgumentException("도구 이름 중복: " + name);
		}
		tools.put(name, new Tool(name, description, inputSchema, handler));
	}

	public Tool get(String name) {
		return tools.get(name);
	}

	public List<Tool> tools() {
		return Collections.unmodifiableList(new ArrayList<>(tools.values()));
	}

	public int size() {
		return tools.size();
	}

	/** tools/list의 tools 배열. */
	public JsonArray listJson() {
		JsonArray a = new JsonArray();
		for (Tool t : tools.values()) {
			a.add(t.toJson());
		}
		return a;
	}

	/** 도구 23개 + compare_suggest_mapping(a4)·render_wav·score_profile·project_set
	 * + score_transform·score_fit_suggest·score_split_sheets + reference_voice_draft
	 * + project_set_time_signatures·project_set_tempos + score_copy_range
	 * + reference_regrid·reference_cleanup + score_merge_tracks·track_import_mml + score_finalize = 39개. */
	public static ToolRegistry standard() {
		ToolRegistry r = new ToolRegistry();
		ProjectTools.register(r);
		ReferenceTools.register(r);
		ReferenceRegridTools.register(r);
		ScoreTools.register(r);
		TrackTools.register(r);
		FitTools.register(r);
		FinalizeTools.register(r);
		CompareTools.register(r);
		RenderTools.register(r);
		ProfileTools.register(r);
		return r;
	}

	// ======================================================================
	// 스키마 도우미
	// ======================================================================

	/** 속성 목록 만들기. */
	public static final class Props {
		private final JsonObject props = new JsonObject();

		public Props add(String name, JsonObject schema) {
			props.add(name, schema);
			return this;
		}

		/** type: object 스키마. required는 속성 이름 중에서. */
		public JsonObject object(String... required) {
			JsonObject o = new JsonObject();
			o.addProperty("type", "object");
			o.add("properties", props);
			JsonArray req = new JsonArray();
			for (String r : required) {
				if (!props.has(r)) {
					throw new IllegalArgumentException("required에 없는 속성: " + r);
				}
				req.add(r);
			}
			o.add("required", req);
			o.addProperty("additionalProperties", false);
			return o;
		}
	}

	public static Props props() {
		return new Props();
	}

	/** 인자 없는 도구. */
	public static JsonObject noArgs() {
		return props().object();
	}

	static JsonObject typed(String type, String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("description", description);
		return o;
	}

	public static JsonObject integer(String description) {
		return typed("integer", description);
	}

	public static JsonObject integer(String description, Integer min, Integer max) {
		JsonObject o = integer(description);
		if (min != null) {
			o.addProperty("minimum", min);
		}
		if (max != null) {
			o.addProperty("maximum", max);
		}
		return o;
	}

	public static JsonObject string(String description) {
		return typed("string", description);
	}

	public static JsonObject stringEnum(String description, String... values) {
		JsonObject o = string(description);
		JsonArray e = new JsonArray();
		for (String v : values) {
			e.add(v);
		}
		o.add("enum", e);
		return o;
	}

	public static JsonObject bool(String description) {
		return typed("boolean", description);
	}

	public static JsonObject array(String description, JsonObject items) {
		JsonObject o = typed("array", description);
		o.add("items", items);
		return o;
	}

	public static JsonObject intArray(String description) {
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		return array(description, items);
	}

	/** 정수 또는 문자열(type 배열). */
	public static JsonObject intOrString(String description) {
		JsonObject o = new JsonObject();
		JsonArray t = new JsonArray();
		t.add("integer");
		t.add("string");
		o.add("type", t);
		o.addProperty("description", description);
		return o;
	}

	/** 입력 음표 스키마. */
	public static JsonObject noteSchema() {
		return props()
				.add("tick", integer("시작 틱(MML 틱, 4분음표 = 96, 6의 배수 또는 마디 시작 기준 16의 배수 = 셋잇단 격자). pos와 둘 중 하나만", 0, null))
				.add("pos", string("시작 위치 \"마디:박:틱\"(마디·박 1부터, 예 \"28:3:48\"). tick과 둘 중 하나만"))
				.add("len", intOrString("길이: 정수 틱(6의 배수 또는 16의 배수 — 4분 셋잇단 64, 8분 셋잇단 32) 또는 MML 길이 \"4\", \"8.\", \"4&16\", 셋잇단 \"6\"·\"12\""))
				.add("pitch", intOrString("음높이: MIDI 번호(60 = C4) 또는 이름 \"C4\", \"F#4\", \"Bb3\""))
				.add("vel", integer("세기 0~15(기본 8)", 0, 15))
				.object("len", "pitch");
	}

	public static JsonObject notesArray(String description) {
		return array(description, noteSchema());
	}
}
