package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/** (b)~(f) McpServer를 프로세스 없이 메모리 스트림으로 돌린다. */
public class McpServerTest {

	private ServerState state;
	private ToolRegistry tools;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "mcp-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		state = new ServerState(engine, null);
		tools = ToolRegistry.standard();
	}

	/** 줄들을 입력으로 서버를 끝까지 돌리고 출력 줄들을 돌려준다. */
	private List<String> runLines(String... lines) {
		StringBuilder sb = new StringBuilder();
		for (String l : lines) {
			sb.append(l).append('\n');
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new McpServer(state, tools, new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), out, "test")
				.run();
		String s = out.toString(StandardCharsets.UTF_8);
		assertFalse("CR 금지", s.indexOf('\r') >= 0);
		List<String> res = new ArrayList<>();
		for (String l : s.split("\n")) {
			if (!l.isEmpty()) {
				res.add(l);
			}
		}
		assertTrue("출력은 \\n으로 끝나야 함", s.isEmpty() || s.endsWith("\n"));
		return res;
	}

	private static String req(int id, String method, JsonObject params) {
		JsonObject m = new JsonObject();
		m.addProperty("jsonrpc", "2.0");
		m.addProperty("id", id);
		m.addProperty("method", method);
		if (params != null) {
			m.add("params", params);
		}
		return JsonRpc.serialize(m);
	}

	private static String call(int id, String tool, JsonObject args) {
		JsonObject p = new JsonObject();
		p.addProperty("name", tool);
		p.add("arguments", args == null ? new JsonObject() : args);
		return req(id, "tools/call", p);
	}

	static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			o.add((String) kv[i], Json.tree(kv[i + 1]));
		}
		return o;
	}

	private static JsonObject parse(String line) {
		return Json.parseStrict(line).getAsJsonObject();
	}

	/** tools/call 응답 → [isError, 본문 JSON]. */
	private static Object[] toolBody(JsonObject resp) {
		JsonObject r = resp.getAsJsonObject("result");
		assertNotNull("result 없음: " + resp, r);
		JsonArray content = r.getAsJsonArray("content");
		assertEquals(1, content.size());
		JsonObject c = content.get(0).getAsJsonObject();
		assertEquals("text", c.get("type").getAsString());
		String text = c.get("text").getAsString();
		assertTrue("2칸 들여쓰기", text.contains("\n  \""));
		return new Object[] { r.get("isError").getAsBoolean(), parse(text) };
	}

	/** 직접 부르기(프로세스·스트림 없이). */
	private Object[] direct(String tool, JsonObject args) {
		McpServer s = new McpServer(state, tools, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "t");
		JsonObject resp = s.handleMessage(Json.parseStrict(call(1, tool, args)).getAsJsonObject());
		return toolBody(resp);
	}

	private static JsonArray melodyNotes() {
		JsonArray notes = new JsonArray();
		String[] names = { "C4", "D4", "E4", "F4" };
		for (int i = 0; i < 4; i++) {
			notes.add(obj("tick", i * 96, "len", "4", "pitch", names[i]));
		}
		return notes;
	}

	// ------------------------------------------------------------------ (b)

	@Test
	public void protocolRoundTrip() {
		JsonObject put = obj("track", 1, "part", "melody");
		put.add("notes", melodyNotes());
		List<String> out = runLines(
				req(1, "initialize", obj("protocolVersion", "2025-06-18")),
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
				req(2, "tools/list", null),
				call(3, "project_new", obj("title", "t")),
				call(4, "track_add", obj("name", "a", "program", 0)),
				call(5, "score_put_notes", put),
				call(6, "score_mml", obj("track", 1)),
				"",
				req(7, "no/such", null),
				"{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":",
				"{\"jsonrpc\":\"2.0\",\"method\":\"no/such/notification\"}",
				req(9, "ping", null),
				req(10, "resources/list", null),
				req(11, "prompts/list", null));
		assertEquals("알림 2개와 빈 줄은 응답 없음", 11, out.size());

		JsonObject init = parse(out.get(0));
		assertEquals("2.0", init.get("jsonrpc").getAsString());
		assertEquals(1, init.get("id").getAsInt());
		JsonObject ir = init.getAsJsonObject("result");
		assertEquals("2025-06-18", ir.get("protocolVersion").getAsString());
		assertTrue(ir.getAsJsonObject("capabilities").getAsJsonObject("tools").entrySet().isEmpty());
		assertEquals("mabiicco-bridge", ir.getAsJsonObject("serverInfo").get("name").getAsString());
		assertEquals("test", ir.getAsJsonObject("serverInfo").get("version").getAsString());

		JsonObject list = parse(out.get(1));
		assertEquals(2, list.get("id").getAsInt());
		assertEquals(39, list.getAsJsonObject("result").getAsJsonArray("tools").size());

		for (int i = 2; i <= 5; i++) {
			JsonObject r = parse(out.get(i));
			assertEquals(i + 1, r.get("id").getAsInt());
			assertFalse(out.get(i), (Boolean) toolBody(r)[0]);
		}
		JsonObject mml = (JsonObject) toolBody(parse(out.get(5)))[1];
		assertEquals("MML@cdef,,;", mml.get("mml").getAsString());

		JsonObject unknown = parse(out.get(6));
		assertEquals(7, unknown.get("id").getAsInt());
		assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt());

		JsonObject broken = parse(out.get(7));
		assertTrue(broken.get("id").isJsonNull());
		assertEquals(-32700, broken.getAsJsonObject("error").get("code").getAsInt());

		JsonObject ping = parse(out.get(8));
		assertEquals(9, ping.get("id").getAsInt());
		assertTrue(ping.getAsJsonObject("result").entrySet().isEmpty());
		assertEquals(0, parse(out.get(9)).getAsJsonObject("result").getAsJsonArray("resources").size());
		assertEquals(0, parse(out.get(10)).getAsJsonObject("result").getAsJsonArray("prompts").size());
	}

	@Test
	public void protocolVersionNegotiation() {
		List<String> out = runLines(
				req(1, "initialize", obj("protocolVersion", "2024-11-05")),
				req(2, "initialize", obj("protocolVersion", "2025-03-26")),
				req(3, "initialize", obj("protocolVersion", "1999-01-01")),
				req(4, "initialize", null));
		assertEquals("2024-11-05", parse(out.get(0)).getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals("2025-03-26", parse(out.get(1)).getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals("2025-06-18", parse(out.get(2)).getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals("2025-06-18", parse(out.get(3)).getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	@Test
	public void invalidRequestsAndBatch() {
		List<String> out = runLines(
				"42",
				"[]",
				"{\"id\":1,\"method\":\"ping\"}",
				"[" + req(2, "ping", null) + ",{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}," + req(3, "ping", null) + "]",
				"[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]",
				"{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}",
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\"}}");
		assertEquals(5, out.size());
		assertEquals(-32600, parse(out.get(0)).getAsJsonObject("error").get("code").getAsInt());
		assertEquals(-32600, parse(out.get(1)).getAsJsonObject("error").get("code").getAsInt());
		JsonObject noVer = parse(out.get(2));
		assertEquals(1, noVer.get("id").getAsInt());
		assertEquals(-32600, noVer.getAsJsonObject("error").get("code").getAsInt());
		JsonArray batch = Json.parseStrict(out.get(3)).getAsJsonArray();
		assertEquals(2, batch.size());
		assertEquals(2, batch.get(0).getAsJsonObject().get("id").getAsInt());
		assertEquals(3, batch.get(1).getAsJsonObject().get("id").getAsInt());
		JsonObject unknownTool = parse(out.get(4));
		assertEquals(5, unknownTool.get("id").getAsInt());
		assertEquals(-32602, unknownTool.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void compareNotWiredThenServerKeepsAnswering() {
		List<String> out = runLines(
				call(1, "compare", obj("from_measure", 1, "to_measure", 2)),
				call(2, "compare_set_mapping", obj("mapping", new JsonObject())),
				req(3, "ping", null));
		assertEquals(3, out.size());
		for (int i = 0; i < 2; i++) {
			Object[] b = toolBody(parse(out.get(i)));
			assertTrue((Boolean) b[0]);
			assertEquals("NOT_WIRED", ((JsonObject) b[1]).get("error").getAsString());
			assertEquals("compare 모듈이 아직 연결되지 않았습니다", ((JsonObject) b[1]).get("message").getAsString());
		}
		assertEquals(3, parse(out.get(2)).get("id").getAsInt());
		assertTrue(parse(out.get(2)).has("result"));
	}

	@Test
	public void engineErrorsBecomeIsError() {
		Object[] b = direct("project_info", null);
		assertTrue((Boolean) b[0]);
		assertEquals("NO_PROJECT", ((JsonObject) b[1]).get("error").getAsString());
		assertTrue(((JsonObject) b[1]).has("details"));

		direct("project_new", null);
		b = direct("track_add", obj("name", "a", "program", 0, "volumee", 3));
		assertTrue((Boolean) b[0]);
		assertEquals("BAD_ARGUMENT", ((JsonObject) b[1]).get("error").getAsString());
		assertTrue(((JsonObject) b[1]).get("message").getAsString().contains("volumee"));

		b = direct("track_add", obj("name", "a"));
		assertEquals("BAD_ARGUMENT", ((JsonObject) b[1]).get("error").getAsString());

		direct("track_add", obj("name", "a", "program", 0));
		JsonArray notes = new JsonArray();
		notes.add(obj("tick", 3, "len", 96, "pitch", 60));
		JsonObject put = obj("track", 1, "part", "melody");
		put.add("notes", notes);
		b = direct("score_put_notes", put);
		assertTrue((Boolean) b[0]);
		assertEquals("BAD_NOTE", ((JsonObject) b[1]).get("error").getAsString());

		b = direct("reference_info", null);
		assertEquals("NO_REFERENCE", ((JsonObject) b[1]).get("error").getAsString());
	}

	// ------------------------------------------------------------------ (c)

	@Test
	public void writeToolsHaveCommonResponse() {
		direct("project_new", null);
		direct("track_add", obj("name", "a", "program", 0));
		JsonObject put = obj("track", 1, "part", "melody");
		put.add("notes", melodyNotes());
		checkCommon(direct("score_put_notes", put));

		JsonObject rep = obj("track", 1, "from_measure", 2, "to_measure", 2);
		JsonObject parts = new JsonObject();
		JsonArray ch = new JsonArray();
		ch.add(obj("pos", "2:1:0", "len", "2", "pitch", "G3"));
		parts.add("chord1", ch);
		rep.add("parts", parts);
		JsonObject r = checkCommon(direct("score_replace_range", rep));
		assertEquals(List.of(2), Json.COMPACT.fromJson(r.get("changed_measures"), List.class).stream()
				.map(x -> ((Number) x).intValue()).toList());

		JsonObject del = obj("track", 1, "part", "melody");
		JsonArray ticks = new JsonArray();
		ticks.add(0);
		del.add("ticks", ticks);
		r = checkCommon(direct("score_delete_notes", del));
		assertEquals(1, r.get("deleted").getAsInt());

		Object[] bad = direct("score_delete_notes", obj("track", 1, "part", "melody"));
		assertTrue((Boolean) bad[0]);
	}

	private static JsonObject checkCommon(Object[] b) {
		assertFalse(String.valueOf(b[1]), (Boolean) b[0]);
		JsonObject r = (JsonObject) b[1];
		assertTrue(r.get("ok").getAsBoolean());
		assertTrue(r.has("changed_measures") && r.get("changed_measures").isJsonArray());
		assertTrue(r.has("stats") && r.getAsJsonObject("stats").has("limits"));
		assertTrue(r.has("compare_summary"));
		assertTrue(r.get("compare_summary").isJsonNull());
		assertEquals(ServerState.NOTE_NOT_WIRED, r.get("compare_summary_note").getAsString());
		return r;
	}

	// ------------------------------------------------------------------ (d)

	// ------------------------------------------------------------------ (e)

	@Test
	public void schemasAreValidObjects() {
		assertEquals(39, tools.size());
		Set<String> names = new HashSet<>();
		for (ToolRegistry.Tool t : tools.tools()) {
			assertTrue("이름 중복 " + t.name(), names.add(t.name()));
			assertTrue(t.name(), t.name().matches("[a-z_]{1,64}"));
			assertTrue(t.name() + " 설명", t.description() != null && t.description().length() > 10);
			checkSchema(t.name(), t.inputSchema());
		}
		for (String n : new String[] { "project_new", "project_open", "project_save_as", "project_info", "track_add",
				"track_set", "track_list", "instruments", "reference_load", "reference_info", "reference_notes",
				"reference_chords", "score_notes", "score_put_notes", "score_delete_notes", "score_replace_range",
				"score_mml", "score_stats", "snapshot", "restore", "snapshot_list", "compare_set_mapping", "compare",
				"compare_suggest_mapping", "render_wav", "score_profile", "project_set", "score_transform", "score_fit_suggest",
				"score_split_sheets", "reference_voice_draft", "project_set_time_signatures", "project_set_tempos", "score_copy_range",
				"reference_regrid", "reference_cleanup", "score_merge_tracks", "track_import_mml" }) {
			assertTrue("도구 없음: " + n, names.contains(n));
		}
	}

	private static void checkSchema(String where, JsonObject s) {
		assertEquals(where, "object", s.get("type").getAsString());
		JsonObject props = s.getAsJsonObject("properties");
		assertNotNull(where + " properties", props);
		JsonArray req = s.getAsJsonArray("required");
		if (req != null) {
			for (JsonElement r : req) {
				assertTrue(where + ": required " + r + "가 properties에 없음", props.has(r.getAsString()));
			}
		}
		for (Map.Entry<String, JsonElement> e : props.entrySet()) {
			String w = where + "." + e.getKey();
			JsonObject p = e.getValue().getAsJsonObject();
			assertTrue(w + " description", p.has("description") && !p.get("description").getAsString().isBlank());
			assertTrue(w + " type 또는 anyOf", p.has("type") || p.has("anyOf"));
			if (p.has("enum")) {
				assertTrue(w + " enum", p.getAsJsonArray("enum").size() > 0);
			}
			if (p.has("type") && p.get("type").isJsonPrimitive()) {
				String type = p.get("type").getAsString();
				assertTrue(w + " type " + type, Set.of("object", "array", "string", "integer", "number", "boolean")
						.contains(type));
				if ("object".equals(type) && p.has("properties")) {
					checkSchema(w, p);
				}
				if ("array".equals(type)) {
					assertTrue(w + " items", p.has("items") && p.get("items").isJsonObject());
					JsonObject items = p.getAsJsonObject("items");
					if ("object".equals(items.get("type").getAsString())) {
						checkSchemaItems(w + "[]", items);
					}
				}
			}
		}
	}

	private static void checkSchemaItems(String where, JsonObject s) {
		JsonObject props = s.getAsJsonObject("properties");
		assertNotNull(where, props);
		for (JsonElement r : s.getAsJsonArray("required")) {
			assertTrue(where + ": required " + r, props.has(r.getAsString()));
		}
	}

	@Test
	public void instrumentsWithoutDls() {
		JsonObject r = (JsonObject) direct("instruments", obj("dls", false, "type", "V"))[1];
		assertTrue(r.get("count").getAsInt() > 0);
		for (JsonElement e : r.getAsJsonArray("instruments")) {
			assertEquals("V", e.getAsJsonObject().get("type").getAsString());
			assertTrue(e.getAsJsonObject().has("allowed_parts"));
		}
	}

	// ------------------------------------------------------------------ (f)

	@Test
	public void statsShowSongNotAllowed() {
		direct("project_new", null);
		direct("track_add", obj("name", "a", "program", 0));
		JsonArray notes = new JsonArray();
		notes.add(obj("tick", 0, "len", 96, "pitch", "E4"));
		JsonObject put = obj("track", 1, "part", "song");
		put.add("notes", notes);
		JsonObject r = (JsonObject) direct("score_put_notes", put)[1];
		JsonObject ex = r.getAsJsonObject("stats").getAsJsonObject("limits").getAsJsonObject("song_excluded");
		assertTrue(ex.get("song_not_allowed").getAsBoolean());
		assertEquals(0, ex.getAsJsonArray("over").size());

		JsonObject st = (JsonObject) direct("score_stats", obj("track", 1))[1];
		assertTrue(st.getAsJsonObject("limits").getAsJsonObject("song_excluded").has("song_not_allowed"));
		JsonObject all = (JsonObject) direct("score_stats", null)[1];
		assertEquals(1, all.getAsJsonArray("tracks").size());
	}

	/** project_set은 준 값만 바꾸고 project_info를 돌려준다. 저장한 파일을 다시 열면 그대로다. */
	@Test
	public void projectSetTitleAuthor() throws IOException {
		direct("project_new", obj("title", "제목 v2", "author", "편곡자"));
		JsonObject a = (JsonObject) direct("project_set", obj("title", "제목 v3"))[1];
		assertEquals("제목 v3", a.get("title").getAsString());
		assertEquals("편곡자", a.get("author").getAsString());
		assertTrue(a.get("dirty").getAsBoolean());
		assertTrue(a.has("reference") && a.has("mapping"));

		JsonObject b = (JsonObject) direct("project_set", obj("author", ""))[1];
		assertEquals("제목 v3", b.get("title").getAsString());
		assertEquals("", b.get("author").getAsString());
		JsonObject c = (JsonObject) direct("project_set", obj("title", "t3", "author", "편곡 A"))[1];
		assertEquals("t3", c.get("title").getAsString());
		assertEquals("편곡 A", c.get("author").getAsString());
		assertEquals("t3", ((JsonObject) direct("project_info", null)[1]).get("title").getAsString());

		Object[] none = direct("project_set", obj());
		assertTrue((Boolean) none[0]);
		assertEquals("BAD_ARGUMENT", ((JsonObject) none[1]).get("error").getAsString());
		Object[] nl = direct("project_set", obj("title", "a\nb"));
		assertTrue((Boolean) nl[0]);
		assertEquals("BAD_ARGUMENT", ((JsonObject) nl[1]).get("error").getAsString());
		Object[] num = direct("project_set", obj("title", 3));
		assertTrue((Boolean) num[0]);
		assertEquals("t3", ((JsonObject) direct("project_info", null)[1]).get("title").getAsString());

		Path dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "set-");
		Path f = dir.resolve("v3.mmi");
		assertFalse((Boolean) direct("project_save_as", obj("path", f.toString()))[0]);
		JsonObject o = (JsonObject) direct("project_open", obj("path", f.toString()))[1];
		assertEquals("t3", o.get("title").getAsString());
		assertEquals("편곡 A", o.get("author").getAsString());
	}

	@Test
	public void saveSnapshotRestore() throws IOException {
		direct("project_new", null);
		direct("track_add", obj("name", "a", "program", 0));
		JsonObject snap = (JsonObject) direct("snapshot", obj("label", "빈 트랙"))[1];
		int id = snap.get("id").getAsInt();
		JsonObject put = obj("track", 1, "part", "melody");
		put.add("notes", melodyNotes());
		direct("score_put_notes", put);
		JsonObject info = (JsonObject) direct("restore", obj("id", id))[1];
		assertEquals(0, info.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonObject("note_counts")
				.get("melody").getAsInt());
		assertEquals(1, ((JsonObject) direct("snapshot_list", null)[1]).getAsJsonArray("snapshots").size());

		Path dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "save-");
		Path f = dir.resolve("새 파일.mmi");
		JsonObject s = (JsonObject) direct("project_save_as", obj("path", f.toString()))[1];
		assertTrue(s.get("ok").getAsBoolean());
		assertTrue(Files.isRegularFile(f));
		Object[] again = direct("project_save_as", obj("path", f.toString()));
		assertTrue((Boolean) again[0]);
		assertEquals("FILE_EXISTS", ((JsonObject) again[1]).get("error").getAsString());
		Object[] open = direct("project_open", obj("path", f.toString()));
		assertFalse((Boolean) open[0]);
	}
}
