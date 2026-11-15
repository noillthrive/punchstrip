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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;

/** score_copy_range — 옮김(드럼은 그대로)·음역(drop/fold)·replace/merge·dry_run·스냅샷·박자 검사·인자 오류. */
public class CopyRangeToolsTest {

	private ServerState state;
	private ToolRegistry tools;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "copyrange-");
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
			} else if (v instanceof JsonElement j) {
				o.add((String) kv[i], j);
			} else {
				o.addProperty((String) kv[i], (String) v);
			}
		}
		return o;
	}

	private static JsonArray arr(JsonElement... es) {
		JsonArray a = new JsonArray();
		for (JsonElement e : es) {
			a.add(e);
		}
		return a;
	}

	private static JsonArray ints(int... v) {
		JsonArray a = new JsonArray();
		for (int x : v) {
			a.add(x);
		}
		return a;
	}

	private void put(int track, String part, JsonArray notes) {
		ok("score_put_notes", obj("track", track, "part", part, "notes", notes));
	}

	private JsonArray notes(int track, String part, int from, int to) {
		return ok("score_notes", obj("track", track, "part", part, "from_measure", from, "to_measure", to))
				.getAsJsonArray("notes");
	}

	/** T1 플룻(5, properties 음역 C4~B6 = 60~95), T2 드럼(27), T3 피아노(21). 1~2마디에 음. */
	private void setup() {
		ok("project_new", obj("title", "copy"));
		ok("track_add", obj("name", "flute", "program", 5));
		ok("track_add", obj("name", "drum", "program", 27));
		ok("track_add", obj("name", "piano", "program", 21));
		put(1, "melody", arr(obj("pos", "1:1:0", "len", 96, "pitch", 72, "vel", 10), obj("pos", "1:3:0", "len", 96, "pitch", 94,
				"vel", 14), obj("pos", "2:1:0", "len", 384, "pitch", 76)));
		put(2, "melody", arr(obj("pos", "1:1:0", "len", 24, "pitch", 41, "vel", 15), obj("pos", "1:3:0", "len", 24, "pitch", 45),
				obj("pos", "2:1:0", "len", 24, "pitch", 41)));
		put(3, "melody", arr(obj("pos", "1:1:0", "len", 192, "pitch", 60)));
		put(3, "chord1", arr(obj("pos", "1:1:0", "len", 192, "pitch", 64)));
	}

	private JsonObject trackOf(JsonObject r, int t) {
		for (JsonElement e : r.getAsJsonArray("tracks")) {
			if (e.getAsJsonObject().get("track").getAsInt() == t) {
				return e.getAsJsonObject();
			}
		}
		throw new AssertionError("트랙 " + t + " 없음: " + r);
	}

	@Test
	public void transposedCopyLeavesDrumsAndDropsOutOfRange() {
		setup();
		int snapsBefore = ok("snapshot_list", null).getAsJsonArray("snapshots").size();
		JsonObject r = ok("score_copy_range", obj("from_measure", 1, "to_measure", 2, "dest_measure", 5, "transpose", 2,
				"velocity_delta", 3));
		assertEquals(5, r.get("dest_measure").getAsInt());
		assertEquals(6, r.get("dest_to_measure").getAsInt());
		assertEquals(1536, r.get("shift_ticks").getAsLong());
		assertFalse(r.get("snapshot_id").isJsonNull());
		assertTrue(r.has("compare_summary"));
		assertEquals(snapsBefore + 1, ok("snapshot_list", null).getAsJsonArray("snapshots").size());
		// 플룻: 72→74, 94→96(음역 95 밖 → 빠짐), 76→78; 세기 10→13, 8→11
		JsonArray f = notes(1, "melody", 5, 6);
		assertEquals(2, f.size());
		assertEquals("5:1:0", f.get(0).getAsJsonObject().get("pos").getAsString());
		assertEquals(74, f.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(13, f.get(0).getAsJsonObject().get("vel").getAsInt());
		assertEquals(78, f.get(1).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(11, f.get(1).getAsJsonObject().get("vel").getAsInt());
		JsonObject t1 = trackOf(r, 1);
		assertTrue(t1.get("transposed").getAsBoolean());
		assertEquals(1, t1.get("dropped").getAsInt());
		assertEquals(2, t1.get("copied").getAsInt());
		assertEquals(96, t1.getAsJsonArray("dropped_notes").get(0).getAsJsonObject().get("transposed_pitch").getAsInt());
		// 드럼: 같은 키, 위치만 옮김. 세기 15+3 → 15(잘림)
		JsonArray d = notes(2, "melody", 5, 6);
		assertEquals(3, d.size());
		assertEquals(41, d.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(45, d.get(1).getAsJsonObject().get("pitch").getAsInt());
		assertEquals("5:3:0", d.get(1).getAsJsonObject().get("pos").getAsString());
		assertEquals(15, d.get(0).getAsJsonObject().get("vel").getAsInt());
		JsonObject t2 = trackOf(r, 2);
		assertFalse(t2.get("transposed").getAsBoolean());
		assertTrue(t2.get("transpose_note").getAsString().contains("옮기지 않음"));
		assertEquals(1, t2.get("velocity_clamped").getAsInt());
		// 피아노: 두 파트 모두
		JsonObject t3 = trackOf(r, 3);
		assertEquals(1, t3.getAsJsonObject("copied_by_part").get("chord1").getAsInt());
		assertEquals(66, notes(3, "chord1", 5, 5).get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(7, r.getAsJsonObject("totals").get("copied").getAsInt());
		assertTrue(r.getAsJsonArray("warnings").toString().contains("dropped_out_of_range"));
		assertTrue(t1.has("rank_limits") && t1.has("chars_after"));
		// 원본은 그대로
		assertEquals(3, notes(1, "melody", 1, 2).size());
		// restore로 되돌림
		ok("restore", obj("id", r.get("snapshot_id").getAsInt()));
		assertEquals(0, notes(1, "melody", 5, 6).size());
	}

	@Test
	public void foldAndTruncateAtEnd() {
		setup();
		JsonObject r = ok("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 3, "transpose", 2,
				"out_of_range", "fold", "tracks", ints(1)));
		JsonObject t1 = trackOf(r, 1);
		assertEquals(0, t1.get("dropped").getAsInt());
		assertEquals(1, t1.get("folded").getAsInt());
		assertEquals(1, r.getAsJsonArray("tracks").size());
		JsonArray f = notes(1, "melody", 3, 3);
		assertEquals(84, f.get(1).getAsJsonObject().get("pitch").getAsInt()); // 96 → 84
		assertEquals(0, notes(2, "melody", 3, 3).size()); // 드럼 트랙은 고르지 않음
		// 원본 끝을 넘는 음(2:3:0 길이 384)은 대상 끝에서 자른다
		put(3, "melody", arr(obj("pos", "2:3:0", "len", 384, "pitch", 62)));
		JsonObject r2 = ok("score_copy_range", obj("from_measure", 2, "to_measure", 2, "dest_measure", 8, "tracks", ints(3)));
		assertEquals(1, trackOf(r2, 3).get("truncated_at_end").getAsInt());
		assertEquals(192, notes(3, "melody", 8, 8).get(0).getAsJsonObject().get("len").getAsInt());
	}

	@Test
	public void replaceClearsMergeKeeps() {
		setup();
		put(1, "melody", arr(obj("pos", "4:2:0", "len", 96, "pitch", 80), obj("pos", "3:4:0", "len", 192, "pitch", 81)));
		JsonObject m = ok("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 4, "tracks", ints(1),
				"mode", "merge"));
		JsonObject t1 = trackOf(m, 1);
		assertEquals(0, t1.get("cleared").getAsInt());
		assertTrue(t1.toString(), t1.get("merge_conflicts").getAsInt() >= 1); // 3:4:0의 음이 4:1:0 복사에 잘림
		ok("restore", obj("id", m.get("snapshot_id").getAsInt()));
		JsonObject rp = ok("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 4, "tracks", ints(1)));
		JsonObject t = trackOf(rp, 1);
		assertEquals(1, t.get("cleared").getAsInt()); // 4:2:0
		assertEquals(1, t.get("truncated_before").getAsInt()); // 3:4:0 → 4:1:0에서 자름
		assertEquals(0, t.get("merge_conflicts").getAsInt());
		JsonArray f = notes(1, "melody", 4, 4);
		assertEquals(2, f.size());
		assertEquals(72, f.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(96, notes(1, "melody", 3, 3).get(0).getAsJsonObject().get("len").getAsInt());
	}

	@Test
	public void dryRunChangesNothing() {
		setup();
		boolean dirty = ok("project_info", null).get("dirty").getAsBoolean();
		int snaps = ok("snapshot_list", null).getAsJsonArray("snapshots").size();
		String mml = ok("score_mml", null).toString();
		JsonObject r = ok("score_copy_range", obj("from_measure", 1, "to_measure", 2, "dest_measure", 3, "transpose", -12,
				"dry_run", true));
		assertTrue(r.get("dry_run").getAsBoolean());
		assertTrue(r.get("snapshot_id").isJsonNull());
		assertFalse(r.has("compare_summary"));
		assertEquals(8, r.getAsJsonObject("totals").get("copied").getAsInt() + r.getAsJsonObject("totals").get("dropped")
				.getAsInt());
		assertTrue(trackOf(r, 1).getAsJsonObject("chars_after").get("melody").getAsInt() > trackOf(r, 1)
				.getAsJsonObject("chars_before").get("melody").getAsInt());
		assertEquals(0, notes(1, "melody", 3, 4).size());
		assertEquals(mml, ok("score_mml", null).toString());
		assertEquals(snaps, ok("snapshot_list", null).getAsJsonArray("snapshots").size());
		assertEquals(dirty, ok("project_info", null).get("dirty").getAsBoolean());
	}

	@Test
	public void overlappingRangesReadSourceFirst() {
		setup();
		// 원본 1~2 → 2(겹침): 원본을 먼저 값으로 읽으므로 1마디 내용이 2마디에, 2마디 내용이 3마디에
		ok("score_copy_range", obj("from_measure", 1, "to_measure", 2, "dest_measure", 2, "tracks", ints(2)));
		JsonArray d = notes(2, "melody", 2, 3);
		assertEquals(3, d.size());
		assertEquals("2:1:0", d.get(0).getAsJsonObject().get("pos").getAsString());
		assertEquals("2:3:0", d.get(1).getAsJsonObject().get("pos").getAsString());
		assertEquals("3:1:0", d.get(2).getAsJsonObject().get("pos").getAsString());
	}

	@Test
	public void errors() {
		setup();
		err("score_copy_range", obj("from_measure", 2, "to_measure", 1, "dest_measure", 5), "BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "tracks", "some"), "BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "tracks", ints(9)), "NO_TRACK");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "tracks", new JsonArray()),
				"BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "transpose", 49), "BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "transpose", 1.5), "BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "velocity_delta", 16),
				"BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "mode", "insert"), "BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "out_of_range", "clamp"),
				"BAD_ARGUMENT");
		err("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5, "rank", "Z"), "BAD_ARGUMENT");
		JsonArray sigs = arr(obj("measure", 1, "signature", "4/4"), obj("measure", 5, "signature", "3/4"));
		ok("project_set_time_signatures", obj("time_signatures", sigs));
		Object[] b = call("score_copy_range", obj("from_measure", 1, "to_measure", 1, "dest_measure", 5));
		assertTrue((Boolean) b[0]);
		assertTrue(b[1].toString(), b[1].toString().contains("mismatch"));
		// 같은 길이(3/4끼리)는 된다
		JsonObject r = ok("score_copy_range", obj("from_measure", 5, "to_measure", 5, "dest_measure", 6));
		assertTrue(r.getAsJsonArray("warnings").toString().contains("복사할 음이 없음"));
	}
}
