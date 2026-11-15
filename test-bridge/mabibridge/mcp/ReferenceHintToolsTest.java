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
import mabibridge.midi.ReferenceHintsTest;

/** MCP 도구: reference_info 힌트, reference_notes·reference_chords 격자·페달, reference_voice_draft와 인자 검사. */
public class ReferenceHintToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "refhint-mcp-");
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

	private JsonObject err(String tool, JsonObject args, String code) {
		Object[] b = call(tool, args);
		assertTrue(tool + " 오류여야 함: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), code, ((JsonObject) b[1]).get("error").getAsString());
		return (JsonObject) b[1];
	}

	private static JsonObject obj(Object... kv) {
		return McpServerTest.obj(kv);
	}

	private static JsonArray ints(int... v) {
		JsonArray a = new JsonArray();
		for (int x : v) {
			a.add(x);
		}
		return a;
	}

	private void loadEnsemble() throws Exception {
		Path mid = tmp.resolve("ensemble.mid");
		Files.write(mid, ReferenceHintsTest.ensembleBytes());
		ok("reference_load", obj("path", mid.toString()));
	}

	// (e) 참조가 없을 때와 인자 검사
	@Test
	public void argumentValidation() throws Exception {
		JsonObject vd = obj("from_measure", 1, "to_measure", 2);
		vd.add("tracks", ints(2));
		err("reference_voice_draft", vd, "NO_REFERENCE");
		err("reference_notes", obj("from_measure", 1, "to_measure", 2), "NO_REFERENCE");
		err("reference_chords", obj("from_measure", 1, "to_measure", 2), "NO_REFERENCE");
		err("reference_info", null, "NO_REFERENCE");

		loadEnsemble();
		err("reference_voice_draft", obj("from_measure", 1, "to_measure", 2), "BAD_ARGUMENT"); // tracks 없음
		JsonObject empty = obj("from_measure", 1, "to_measure", 2);
		empty.add("tracks", new JsonArray());
		err("reference_voice_draft", empty, "BAD_ARGUMENT");
		JsonObject badTrack = obj("from_measure", 1, "to_measure", 2);
		badTrack.add("tracks", ints(99));
		err("reference_voice_draft", badTrack, "BAD_ARGUMENT");
		JsonObject range = obj("from_measure", 3, "to_measure", 2);
		range.add("tracks", ints(2));
		err("reference_voice_draft", range, "BAD_ARGUMENT");
		for (Object[] kv : new Object[][] { { "voices", 0 }, { "voices", 4 }, { "max_notes", 0 }, { "use_pedal", "yes" },
				{ "unknown_arg", 1 } }) {
			JsonObject a = obj("from_measure", 1, "to_measure", 2, (String) kv[0], kv[1]);
			a.add("tracks", ints(2));
			err("reference_voice_draft", a, "BAD_ARGUMENT");
		}
		JsonObject homes = obj("from_measure", 1, "to_measure", 2, "voices", 3);
		homes.add("tracks", ints(2));
		homes.add("home_pitches", ints(72, 60));
		err("reference_voice_draft", homes, "BAD_ARGUMENT");
		JsonObject homes2 = obj("from_measure", 1, "to_measure", 2, "voices", 2);
		homes2.add("tracks", ints(2));
		homes2.add("home_pitches", ints(72, 200));
		err("reference_voice_draft", homes2, "BAD_ARGUMENT");

		for (Object[] kv : new Object[][] { { "triplet_ratio", 0.0 }, { "triplet_ratio", 1.5 }, { "triplet_ratio", "x" },
				{ "straight_tol", -1 }, { "straight_tol", 30 } }) {
			err("reference_notes", obj("from_measure", 1, "to_measure", 1, (String) kv[0], kv[1]), "BAD_ARGUMENT");
			err("reference_chords", obj("from_measure", 1, "to_measure", 1, (String) kv[0], kv[1]), "BAD_ARGUMENT");
		}
		ok("reference_notes", obj("from_measure", 1, "to_measure", 1, "triplet_ratio", 0.5, "straight_tol", 2));
	}

	/** 정수 인자는 JSON 수만(문자열 "3"·["2"] 거부, 2.0은 받음, 2.5 거부). */
	@Test
	public void strictIntegerArguments() throws Exception {
		loadEnsemble();
		JsonArray strTracks = new JsonArray();
		strTracks.add("2");
		for (Object[] kv : new Object[][] { { "voices", "3" }, { "max_notes", "10" }, { "from_measure", "1" },
				{ "to_measure", "2" }, { "voices", 2.5 } }) {
			JsonObject a = obj("from_measure", 1, "to_measure", 2);
			a.add(kv[0].toString(), Json.tree(kv[1]));
			a.add("tracks", ints(2));
			err("reference_voice_draft", a, "BAD_ARGUMENT");
		}
		JsonObject st = obj("from_measure", 1, "to_measure", 2);
		st.add("tracks", strTracks);
		err("reference_voice_draft", st, "BAD_ARGUMENT");
		JsonObject sh = obj("from_measure", 1, "to_measure", 2, "voices", 2);
		sh.add("tracks", ints(2));
		JsonArray strHomes = new JsonArray();
		strHomes.add("72");
		strHomes.add(48);
		sh.add("home_pitches", strHomes);
		err("reference_voice_draft", sh, "BAD_ARGUMENT");
		JsonObject f = obj("from_measure", 1.0, "to_measure", 2.0, "voices", 2.0, "max_notes", 100.0);
		f.add("tracks", ints(2));
		assertEquals(2, ok("reference_voice_draft", f).getAsJsonArray("voices").size());

		for (String tool : new String[] { "reference_notes", "reference_chords" }) {
			err(tool, obj("from_measure", "1", "to_measure", 1), "BAD_ARGUMENT");
			err(tool, obj("from_measure", 1, "to_measure", "1"), "BAD_ARGUMENT");
			JsonObject t = obj("from_measure", 1, "to_measure", 1);
			t.add("tracks", strTracks);
			err(tool, t, "BAD_ARGUMENT");
			ok(tool, obj("from_measure", 1.0, "to_measure", 1));
		}
		err("reference_notes", obj("from_measure", 1, "to_measure", 1, "max", "5"), "BAD_ARGUMENT");
	}

	/** to_measure가 곡 끝(8마디) 뒤면 마지막 마디로 줄이고 note에 적는다. from이 곡 끝 뒤면 BAD_ARGUMENT. */
	@Test
	public void toMeasureClampedToSongEnd() throws Exception {
		loadEnsemble();
		for (String tool : new String[] { "reference_notes", "reference_chords", "reference_voice_draft" }) {
			JsonObject a = obj("from_measure", 7, "to_measure", 200);
			JsonObject b = obj("from_measure", 9, "to_measure", 200);
			JsonObject c = obj("from_measure", 7, "to_measure", 8);
			if (tool.equals("reference_voice_draft")) {
				a.add("tracks", ints(2));
				b.add("tracks", ints(2));
				c.add("tracks", ints(2));
			}
			JsonObject r = ok(tool, a);
			assertEquals(tool, 7, r.getAsJsonArray("range").get(0).getAsInt());
			assertEquals(tool, 8, r.getAsJsonArray("range").get(1).getAsInt());
			assertTrue(tool, r.get("note").getAsString().startsWith("to_measure를 8로 줄임"));
			JsonObject same = ok(tool, c);
			assertTrue(tool, same.get("note").isJsonNull());
			same.remove("note");
			r.remove("note");
			assertEquals(tool, same.toString(), r.toString());
			err(tool, b, "BAD_ARGUMENT");
		}
	}

	@Test
	public void infoNotesChordsHints() throws Exception {
		loadEnsemble();
		JsonObject info = ok("reference_info", null);
		JsonArray tr = info.getAsJsonArray("tracks");
		JsonObject vocal = tr.get(1).getAsJsonObject();
		assertEquals("melody", vocal.get("role_guess").getAsString());
		assertEquals(1, vocal.get("melody_rank").getAsInt());
		assertTrue(vocal.get("melody_score").getAsDouble() > 0.8);
		for (String k : new String[] { "height", "monophony", "coverage", "name_keyword" }) {
			assertTrue(k, vocal.getAsJsonObject("components").has(k));
		}
		assertEquals("bass", tr.get(2).getAsJsonObject().get("role_guess").getAsString());
		assertEquals("chords", tr.get(3).getAsJsonObject().get("role_guess").getAsString());
		assertEquals("pad", tr.get(4).getAsJsonObject().get("role_guess").getAsString());
		assertEquals("drums", tr.get(5).getAsJsonObject().get("role_guess").getAsString());
		JsonObject th = info.getAsJsonObject("track_hints");
		assertEquals(2, th.getAsJsonArray("melody_ranking").get(0).getAsInt());
		assertEquals(0.35, th.getAsJsonObject("rule").getAsJsonObject("weights").get("height").getAsDouble(), 0);

		JsonObject n = ok("reference_notes", obj("from_measure", 1, "to_measure", 2));
		for (JsonElement e : n.getAsJsonArray("notes")) {
			JsonObject x = e.getAsJsonObject();
			assertEquals(x.get("tick").getAsLong() + x.get("len").getAsLong(), x.get("pedal_end").getAsLong());
			assertFalse(x.get("pedal").getAsBoolean());
		}
		assertEquals(8, n.getAsJsonArray("grid_hints").size());
		assertEquals(8, n.getAsJsonObject("grid_summary").get("straight").getAsInt());
		JsonObject g0 = n.getAsJsonArray("grid_hints").get(0).getAsJsonObject();
		for (String k : new String[] { "measure", "beat", "onsets", "grid", "straight_err", "triplet_err" }) {
			assertTrue(k, g0.has(k));
		}
		JsonObject c = ok("reference_chords", obj("from_measure", 1, "to_measure", 1));
		assertEquals(4, c.getAsJsonArray("grid_hints").size());
	}

	/** 초안의 음표는 score_put_notes(pitch_mode sounding)에 그대로 들어가고, 초안은 악보·참조를 바꾸지 않는다. */
	@Test
	public void voiceDraftPassThrough() throws Exception {
		loadEnsemble();
		ok("project_new", null);
		ok("track_add", obj("name", "piano", "program", 21));
		JsonObject before = ok("score_stats", obj("track", 1));
		JsonObject a = obj("from_measure", 1, "to_measure", 2, "voices", 3);
		a.add("tracks", ints(2, 4));
		JsonObject d = ok("reference_voice_draft", a);
		assertTrue(d.get("summary_text").getAsString().startsWith("3성부 초안: 멜로디 8음"));
		JsonArray vs = d.getAsJsonArray("voices");
		assertEquals(3, vs.size());
		assertEquals(8, vs.get(0).getAsJsonObject().get("count").getAsInt()); // 보컬 4분음표 × 2마디
		assertEquals(8, vs.get(1).getAsJsonObject().get("count").getAsInt()); // E4
		assertEquals(8, vs.get(2).getAsJsonObject().get("count").getAsInt()); // C4
		assertEquals(8, d.get("dropped_count").getAsInt()); // G4
		assertEquals(ok("score_stats", obj("track", 1)).toString(), before.toString());
		String[] parts = { "melody", "chord1", "chord2" };
		for (int i = 0; i < 3; i++) {
			JsonObject v = vs.get(i).getAsJsonObject();
			assertEquals(parts[i], v.get("part_suggestion").getAsString());
			assertEquals(v.getAsJsonArray("notes").size(), v.getAsJsonArray("positions").size());
			JsonObject put = obj("track", 1, "part", parts[i], "pitch_mode", "sounding");
			put.add("notes", v.getAsJsonArray("notes"));
			JsonObject r = ok("score_put_notes", put);
			assertEquals(8, r.get("added").getAsInt());
		}
		// 피아노(+1옥타브): 들리는 C5(72) → 적은 C4(60)
		JsonObject sn = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1));
		JsonObject first = sn.getAsJsonArray("notes").get(0).getAsJsonObject();
		assertEquals(60, first.get("pitch").getAsInt());
		assertEquals(72, first.get("sounding_pitch").getAsInt());
	}
}
