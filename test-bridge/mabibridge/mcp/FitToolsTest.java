package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;

/** MCP 도구: score_stats의 rank·rank_limits·warnings, score_transform·score_fit_suggest·score_split_sheets 인자와 오류. */
public class FitToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "fit-mcp-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(java.util.List.of(tmp.resolve("none.dls"))));
		state = new ServerState(engine, null);
		tools = ToolRegistry.standard();
	}

	private Object[] call(String tool, JsonObject args) {
		JsonObject p = new JsonObject();
		p.addProperty("name", tool);
		p.add("arguments", args == null ? new JsonObject() : args);
		JsonObject m = new JsonObject();
		m.addProperty("jsonrpc", "2.0");
		m.addProperty("id", 1);
		m.addProperty("method", "tools/call");
		m.add("params", p);
		McpServer s = new McpServer(state, tools, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "t");
		JsonObject r = s.handleMessage(m).getAsJsonObject("result");
		boolean err = r.has("isError") && r.get("isError").getAsBoolean();
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return new Object[] { err, Json.parseStrict(text).getAsJsonObject() };
	}

	private JsonObject ok(String tool, JsonObject args) {
		Object[] b = call(tool, args);
		assertFalse(tool + ": " + b[1], (Boolean) b[0]);
		return (JsonObject) b[1];
	}

	private void err(String tool, JsonObject args, String code) {
		Object[] b = call(tool, args);
		assertTrue(tool + " 오류여야 함: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), code, ((JsonObject) b[1]).get("error").getAsString());
	}

	private static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v instanceof Number n) {
				o.addProperty((String) kv[i], n);
			} else if (v instanceof Boolean b) {
				o.addProperty((String) kv[i], b);
			} else if (v instanceof JsonObject j) {
				o.add((String) kv[i], j);
			} else if (v instanceof JsonArray a) {
				o.add((String) kv[i], a);
			} else {
				o.addProperty((String) kv[i], (String) v);
			}
		}
		return o;
	}

	private void setupClash() {
		ok("project_new", obj("title", "fit"));
		ok("track_add", obj("name", "a", "program", 0));
		JsonArray m = new JsonArray();
		m.add(obj("tick", 0, "len", 96, "pitch", "C4"));
		ok("score_put_notes", obj("track", 1, "part", "melody", "notes", m));
		JsonArray c = new JsonArray();
		c.add(obj("tick", 0, "len", 96, "pitch", "C4"));
		JsonObject r = ok("score_put_notes", obj("track", 1, "part", "chord1", "notes", c));
		assertTrue(r.toString(), r.getAsJsonArray("warnings").toString().contains("unison_clash"));
	}

	@Test
	public void statsRankAndWarnings() {
		setupClash();
		JsonObject st = ok("score_stats", obj("track", 1, "rank", "F", "song_rule", "song_included"));
		assertTrue(st.getAsJsonObject("limits").has("song_excluded")); // 예전 필드 그대로
		JsonObject rl = st.getAsJsonObject("rank_limits");
		assertEquals("F", rl.get("rank").getAsString());
		assertEquals(400, rl.getAsJsonObject("parts").getAsJsonObject("melody").get("limit").getAsInt());
		assertEquals(0, rl.getAsJsonObject("parts").getAsJsonObject("melody").get("over_by").getAsInt());
		JsonArray w = st.getAsJsonArray("warnings");
		assertEquals(1, w.size());
		assertEquals("unison_clash", w.get(0).getAsJsonObject().get("code").getAsString());
		assertEquals("same_start", w.get(0).getAsJsonObject().getAsJsonArray("examples").get(0).getAsJsonObject().get("type")
				.getAsString());
		assertEquals(0, st.getAsJsonArray("score_warnings").size());
		JsonObject all = ok("score_stats", obj("rank", "1"));
		assertTrue(all.getAsJsonArray("tracks").get(0).getAsJsonObject().has("rank_limits"));
		assertTrue(all.has("score_warnings"));
		JsonObject def = ok("score_stats", obj("track", 1));
		assertEquals("1", def.getAsJsonObject("rank_limits").get("rank").getAsString());
		assertEquals(1600, def.getAsJsonObject("rank_limits").getAsJsonObject("parts").getAsJsonObject("melody").get("limit")
				.getAsInt());
		err("score_stats", obj("track", 1, "rank", "Z"), "BAD_ARGUMENT");
	}

	@Test
	public void transformFitSplitArguments() {
		setupClash();
		JsonObject q = ok("score_transform", obj("track", 1, "op", "velocity_quantize", "params", obj("step", 3,
				"keep_if_single_level", false)));
		assertEquals(2, q.get("notes_affected").getAsInt()); // v8 → v9
		ok("restore", obj("id", q.get("snapshot_id").getAsInt()));
		JsonObject t = ok("score_transform", obj("track", 1, "op", "velocity_offset", "params", obj("delta", 2)));
		assertEquals(2, t.get("notes_affected").getAsInt());
		assertTrue(t.get("snapshot_id").getAsInt() >= 1);
		assertTrue(t.getAsJsonObject("chars_after").has("melody"));
		JsonObject sl = ok("snapshot_list", null);
		assertEquals(2, sl.getAsJsonArray("snapshots").size());
		ok("restore", obj("id", t.get("snapshot_id").getAsInt()));
		err("score_transform", obj("track", 1, "op", "nope"), "BAD_ARGUMENT");
		err("score_transform", obj("track", 1, "op", "velocity_offset", "params", obj("delta", 2, "x", 1)), "BAD_ARGUMENT");
		err("score_transform", obj("track", 1, "op", "length_grid", "params", obj("ticks", 7)), "BAD_ARGUMENT");
		err("score_transform", obj("track", 1, "op", "length_scale", "params", obj("ratio", "fast")), "BAD_ARGUMENT");
		err("score_transform", obj("track", 2, "op", "velocity_offset", "params", obj("delta", 1)), "NO_TRACK");
		int snaps = ok("snapshot_list", null).getAsJsonArray("snapshots").size();
		err("score_transform", obj("track", 1, "op", "velocity_offset", "params", obj("delta", "2")), "BAD_ARGUMENT");
		err("score_transform", obj("track", 1, "op", "length_scale", "params", obj("ratio", "0.75")), "BAD_ARGUMENT");
		err("score_transform", obj("track", 1, "op", "velocity_quantize", "params", obj("step", 3, "keep_if_single_level",
				"true")), "BAD_ARGUMENT");
		assertEquals("거부된 호출은 스냅샷을 남기지 않음", snaps, ok("snapshot_list", null).getAsJsonArray("snapshots").size());
		JsonObject rng = ok("score_transform", obj("track", 1, "op", "velocity_offset", "params", obj("delta", 0)));
		assertEquals(1, rng.get("from_measure").getAsInt());
		assertEquals(1, rng.get("to_measure").getAsInt());

		JsonObject f = ok("score_fit_suggest", obj("track", 1));
		assertTrue(f.getAsJsonObject("plan").get("fits").getAsBoolean());
		assertEquals(7, f.getAsJsonArray("ladder").size());

		JsonObject sp = ok("score_split_sheets", obj("track", 1));
		assertEquals(1, sp.get("sheet_count").getAsInt());
		// 상대 out_dir는 서버 작업 폴더 기준(시험에서는 저장소 루트 → 시험용 tmp 폴더 아래)
		Path relTarget = tmp.resolve("rel");
		String rel = Path.of("").toAbsolutePath().relativize(relTarget.toAbsolutePath()).toString();
		assertFalse(rel, Path.of(rel).isAbsolute());
		JsonObject relSaved = ok("score_split_sheets", obj("track", 1, "out_dir", rel, "name", "r"));
		String relPath = relSaved.getAsJsonArray("sheets").get(0).getAsJsonObject().get("path").getAsString();
		assertEquals(relTarget.toAbsolutePath().normalize().resolve("r_장1.mmi").toString(), relPath);
		assertTrue(Files.isRegularFile(Path.of(relPath)));
		err("score_split_sheets", obj("track", 1, "name", "a/b", "out_dir", tmp.resolve("s").toString()), "BAD_ARGUMENT");
		err("score_split_sheets", obj("track", 1, "max_sheets", 0), "BAD_ARGUMENT");
		JsonObject saved = ok("score_split_sheets", obj("track", 1, "out_dir", tmp.resolve("s").toString(), "name", "x"));
		assertTrue(saved.getAsJsonArray("sheets").get(0).getAsJsonObject().get("path").getAsString().endsWith("x_장1.mmi"));
		err("score_split_sheets", obj("track", 1, "out_dir", tmp.resolve("s").toString(), "name", "x"), "FILE_EXISTS");
	}
}
