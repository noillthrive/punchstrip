package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/** score_merge_tracks (by_voice / keep_parts / priority, drops, dry_run) and track_import_mml (sheets, errors). */
public class TrackToolsTest {

	private ServerState state;
	private ToolRegistry tools;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "tracktools-");
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
		assertTrue(tool + " should fail: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), code, ((JsonObject) b[1]).get("error").getAsString());
		return (JsonObject) b[1];
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

	private static JsonArray arr(Object... es) {
		JsonArray a = new JsonArray();
		for (Object e : es) {
			if (e instanceof JsonElement j) {
				a.add(j);
			} else if (e instanceof Number n) {
				a.add(n);
			} else {
				a.add((String) e);
			}
		}
		return a;
	}

	private void put(int track, String part, JsonArray notes) {
		ok("score_put_notes", obj("track", track, "part", part, "notes", notes));
	}

	private static JsonObject n(String pos, int len, int pitch) {
		return obj("pos", pos, "len", len, "pitch", pitch);
	}

	private int trackCount() {
		return ok("track_list", null).getAsJsonArray("tracks").size();
	}

	private JsonObject part(JsonObject r, String p) {
		return r.getAsJsonObject("parts").getAsJsonObject(p);
	}

	/** T1 power chords (root in melody, fifth in chord1), T2 lead above: two guitars (20). */
	private void twoGuitars() {
		ok("project_new", obj("title", "merge"));
		ok("track_add", obj("name", "power", "program", 20));
		ok("track_add", obj("name", "lead", "program", 20));
		put(1, "melody", arr(n("1:1:0", 192, 40), n("1:3:0", 192, 43), n("2:1:0", 384, 45)));
		put(1, "chord1", arr(n("1:1:0", 192, 47), n("1:3:0", 192, 50), n("2:1:0", 384, 52)));
		put(2, "melody", arr(n("1:1:0", 96, 64), n("1:2:0", 96, 67), n("1:3:0", 96, 69), n("1:4:0", 96, 71),
				n("2:1:0", 192, 72), n("2:3:0", 192, 71)));
	}

	@Test
	public void mergeByVoiceTwoGuitarsGivesThreeParts() {
		twoGuitars();
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(1, 2)));
		assertEquals("by_voice", r.get("strategy").getAsString());
		assertEquals(1, r.get("result_track").getAsInt());
		assertEquals(1, trackCount());
		assertEquals(0, r.get("dropped_count").getAsInt());
		assertEquals(12, r.get("notes_in").getAsInt());
		assertEquals(12, r.get("notes_out").getAsInt());
		// highest voice -> melody (the lead), next -> chord1 (fifths), lowest -> chord2 (roots)
		assertEquals(6, part(r, "melody").get("notes").getAsInt());
		assertEquals(3, part(r, "chord1").get("notes").getAsInt());
		assertEquals(3, part(r, "chord2").get("notes").getAsInt());
		assertTrue(r.get("fits").getAsBoolean());
		assertTrue(part(r, "melody").get("chars").getAsInt() > 0);
		assertEquals(1600, part(r, "melody").get("limit").getAsInt());
		assertNotNull(r.get("snapshot_id").getAsString());
		JsonArray mel = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		assertEquals(64, mel.get(0).getAsJsonObject().get("pitch").getAsInt());
		JsonArray c2 = ok("score_notes", obj("track", 1, "part", "chord2", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		assertEquals(40, c2.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals("[2]", r.getAsJsonArray("removed_tracks").toString());
		// mapping: lead melody -> melody 6
		boolean found = false;
		for (JsonElement e : r.getAsJsonArray("mapping")) {
			JsonObject m = e.getAsJsonObject();
			if (m.get("from_track").getAsInt() == 2 && "melody".equals(m.get("to_part").getAsString())) {
				assertEquals(6, m.get("notes").getAsInt());
				found = true;
			}
		}
		assertTrue(found);
		// snapshot restores both tracks
		ok("restore", obj("id", r.get("snapshot_id").getAsInt()));
		assertEquals(2, trackCount());
	}

	@Test
	public void mergeByVoiceDropsFourthVoiceAndReportsIt() {
		ok("project_new", obj("title", "drop"));
		ok("track_add", obj("name", "a", "program", 20));
		ok("track_add", obj("name", "b", "program", 20));
		put(1, "melody", arr(n("1:1:0", 96, 60), n("2:1:0", 96, 60)));
		put(1, "chord1", arr(n("1:1:0", 96, 64)));
		put(1, "chord2", arr(n("1:1:0", 96, 67)));
		put(2, "melody", arr(n("1:1:0", 96, 72), n("1:2:0", 96, 74)));
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(1, 2)));
		assertEquals(1, r.get("dropped_count").getAsInt());
		JsonObject d = r.getAsJsonArray("dropped").get(0).getAsJsonObject();
		// outer notes kept (72 top, 60 bottom, then 67); the inner 64 is dropped
		assertEquals(64, d.get("pitch").getAsInt());
		assertEquals("1:1:0", d.get("pos").getAsString());
		assertEquals("more_than_free_voices", d.get("reason").getAsString());
		assertEquals(1, d.get("from_track").getAsInt());
		assertEquals("chord1", d.get("from_part").getAsString());
		assertEquals(1, r.getAsJsonObject("dropped_by_source").get("T1 chord1").getAsInt());
		assertEquals(3, part(r, "melody").get("notes").getAsInt()); // 72, 74 (melody free again at 1:2), 60 at m2
		assertTrue(r.getAsJsonArray("warnings").toString().contains("dropped"));
	}

	@Test
	public void keepPartsFailsOnFourPartsAndChangesNothing() {
		ok("project_new", obj("title", "keep"));
		ok("track_add", obj("name", "a", "program", 0));
		ok("track_add", obj("name", "b", "program", 0));
		put(1, "melody", arr(n("1:1:0", 96, 60)));
		put(1, "chord1", arr(n("1:1:0", 96, 64)));
		put(1, "chord2", arr(n("1:1:0", 96, 67)));
		put(2, "melody", arr(n("1:2:0", 96, 72)));
		String before = ok("score_mml", null).toString();
		JsonObject e = err("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "keep_parts"), "BAD_ARGUMENT");
		assertEquals(4, e.getAsJsonObject("details").getAsJsonArray("non_empty_parts").size());
		assertEquals(2, trackCount());
		assertEquals(before, ok("score_mml", null).toString());
	}

	@Test
	public void keepPartsMovesPartsInOrder() {
		ok("project_new", obj("title", "keep2"));
		ok("track_add", obj("name", "a", "program", 0));
		ok("track_add", obj("name", "b", "program", 0));
		ok("track_add", obj("name", "other", "program", 21));
		put(1, "melody", arr(n("1:1:0", 96, 60)));
		put(2, "melody", arr(n("1:1:0", 96, 72)));
		put(2, "chord1", arr(n("1:1:0", 96, 76)));
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(2, 1), "target", 1, "strategy", "keep_parts"));
		// order = tracks: T2 melody -> melody, T2 chord1 -> chord1, T1 melody -> chord2
		JsonArray c2 = ok("score_notes", obj("track", 1, "part", "chord2", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes");
		assertEquals(60, c2.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(1, r.get("result_track").getAsInt());
		assertEquals(2, trackCount());
		assertEquals(2, r.getAsJsonObject("renumbered").get("3").getAsInt());
		assertTrue(r.getAsJsonObject("renumbered").get("2").isJsonNull());
		assertEquals("other", ok("track_list", null).getAsJsonArray("tracks").get(1).getAsJsonObject().get("name")
				.getAsString());
	}

	@Test
	public void dryRunIsNoOp() {
		twoGuitars();
		String before = ok("score_mml", null).toString();
		int snaps = ok("snapshot_list", null).getAsJsonArray("snapshots").size();
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(1, 2), "dry_run", true));
		assertTrue(r.get("dry_run").getAsBoolean());
		assertEquals(6, part(r, "melody").get("notes").getAsInt());
		assertTrue(r.get("snapshot_id").isJsonNull());
		assertEquals(2, trackCount());
		assertEquals(before, ok("score_mml", null).toString());
		assertEquals(snaps, ok("snapshot_list", null).getAsJsonArray("snapshots").size());
	}

	@Test
	public void priorityOrderAndKeepSources() {
		twoGuitars();
		JsonArray order = arr(arr(2, "melody"), arr(1, "melody"), arr(1, "chord1"));
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority", "order", order,
				"keep_sources", true, "max_len_ticks", 96));
		assertEquals(2, trackCount());
		assertEquals(6, part(r, "melody").get("notes").getAsInt());
		assertEquals(3, part(r, "chord1").get("notes").getAsInt());
		assertEquals(3, part(r, "chord2").get("notes").getAsInt());
		assertEquals(8, r.get("length_capped").getAsInt()); // T1 six notes + T2 two half notes
		JsonArray mel = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		assertEquals(64, mel.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(96, ok("score_notes", obj("track", 1, "part", "chord2", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes").get(0).getAsJsonObject().get("len").getAsInt());
		// source track 2 untouched
		assertEquals(6, ok("score_notes", obj("track", 2, "part", "melody", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes").size());
		// a part not in order is dropped (not_in_order)
		twoGuitars();
		JsonObject r2 = ok("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority", "order",
				arr(arr(2, "melody"), arr(1, "melody")), "dry_run", true));
		assertEquals(3, r2.get("dropped_count").getAsInt());
		assertEquals("not_in_order", r2.getAsJsonArray("dropped").get(0).getAsJsonObject().get("reason").getAsString());
	}

	@Test
	public void priorityMergesSameOnsetDuplicates() {
		ok("project_new", obj("title", "dup"));
		ok("track_add", obj("name", "a", "program", 20));
		ok("track_add", obj("name", "b", "program", 20));
		put(1, "melody", arr(n("1:1:0", 96, 60), n("1:3:0", 96, 55)));
		put(2, "melody", arr(n("1:1:0", 96, 60), n("1:2:0", 96, 62)));
		JsonObject r = ok("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority", "order",
				arr(arr(2, "melody"), arr(1, "melody"))));
		assertEquals(1, r.get("duplicates_merged").getAsInt());
		assertEquals(0, r.get("dropped_count").getAsInt());
		assertEquals(2, part(r, "melody").get("notes").getAsInt());
		assertEquals(1, part(r, "chord1").get("notes").getAsInt());
		assertFalse(r.getAsJsonArray("warnings").toString().contains("unison_clash"));
	}

	@Test
	public void mergeArgumentErrors() {
		twoGuitars();
		ok("track_add", obj("name", "piano", "program", 21));
		err("score_merge_tracks", obj("tracks", arr(1)), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 3)), "BAD_ARGUMENT"); // different programs
		err("score_merge_tracks", obj("tracks", arr(1, 1)), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "target", 3), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 9)), "NO_TRACK");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority"), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "order", arr(arr(1, "melody"))), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority", "order", arr(arr(3, "melody"))),
				"BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "priority", "order", arr(arr(1, "song"))),
				"BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "max_len_ticks", 50), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "strategy", "zip"), "BAD_ARGUMENT");
		err("score_merge_tracks", obj("tracks", arr(1, 2), "bogus", 1), "BAD_ARGUMENT");
		assertEquals(3, trackCount());
	}

	// ------------------------------------------------------------------ track_import_mml

	private JsonArray allNotes(int track) {
		JsonObject parts = ok("score_notes", obj("track", track, "part", "all", "from_measure", 1, "to_measure", 50))
				.getAsJsonObject("parts");
		JsonArray a = new JsonArray();
		for (String p : new String[] { "melody", "chord1", "chord2" }) {
			for (JsonElement e : parts.getAsJsonArray(p)) {
				JsonObject o = e.getAsJsonObject();
				a.add(p + ":" + o.get("tick") + ":" + o.get("len") + ":" + o.get("pitch") + ":" + o.get("vel"));
			}
		}
		return a;
	}

	@Test
	public void importSingleMml() {
		ok("project_new", obj("title", "imp"));
		JsonObject r = ok("track_import_mml", obj("mml", "MML@l8cdefgab>c;", "program", 0, "name", "lute"));
		assertEquals(1, r.get("track").getAsInt());
		assertEquals(8, r.get("notes").getAsInt());
		assertEquals(8, part(r, "melody").get("notes").getAsInt());
		assertEquals(11, part(r, "melody").get("input_chars").getAsInt());
		assertTrue(part(r, "melody").get("chars").getAsInt() > 0);
		assertEquals(0, r.get("parse_warning_count").getAsInt());
		assertFalse(r.get("tempo_changed").getAsBoolean());
		JsonArray mel = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		assertEquals(60, mel.get(0).getAsJsonObject().get("pitch").getAsInt());
		assertEquals(48, mel.get(0).getAsJsonObject().get("len").getAsInt());
		assertEquals(72, mel.get(7).getAsJsonObject().get("pitch").getAsInt());
		assertEquals("lute", ok("track_list", null).getAsJsonArray("tracks").get(0).getAsJsonObject().get("name")
				.getAsString());
	}

	@Test
	public void importThreePartsWithTempoAndInsertAt() {
		ok("project_new", obj("title", "imp3"));
		ok("track_add", obj("name", "first", "program", 21));
		JsonObject r = ok("track_import_mml", obj("mml", "MML@t90o5l4cdef,o4l2ce,o3l1c;", "program", 0, "insert_at", 1,
				"panpot", 40));
		assertEquals(1, r.get("track").getAsInt());
		assertEquals(4, part(r, "melody").get("notes").getAsInt());
		assertEquals(2, part(r, "chord1").get("notes").getAsInt());
		assertEquals(1, part(r, "chord2").get("notes").getAsInt());
		assertEquals(7, r.get("notes").getAsInt());
		assertTrue(r.get("tempo_changed").getAsBoolean());
		assertEquals(90, r.getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(2, r.getAsJsonObject("renumbered").get("1").getAsInt());
		JsonArray tl = ok("track_list", null).getAsJsonArray("tracks");
		assertEquals("first", tl.get(1).getAsJsonObject().get("name").getAsString());
		assertEquals(40, tl.get(0).getAsJsonObject().get("panpot").getAsInt());
		assertEquals(90, ok("project_info", null).getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
	}

	@Test
	public void twoSheetsEqualConcatenatedString() {
		ok("project_new", obj("title", "book"));
		// sheet 2 relies on o5 / l16 / v10 set in sheet 1 (and o3 l4 in chord1)
		JsonObject r = ok("track_import_mml", obj("sheets", arr("MML@v10o5l16cdef,o3l4c;", "MML@gab>c,eg;"), "program", 0));
		assertEquals(2, r.get("sheets").getAsInt());
		JsonObject one = ok("track_import_mml", obj("mml", "MML@v10o5l16cdefgab>c,o3l4ceg;", "program", 0));
		assertEquals(allNotes(1).toString(), allNotes(2).toString());
		assertEquals(8, part(r, "melody").get("notes").getAsInt());
		assertEquals(3, part(r, "chord1").get("notes").getAsInt());
		assertEquals(part(one, "melody").get("chars"), part(r, "melody").get("chars"));
		assertEquals(part(one, "chord1").get("input_chars"), part(r, "chord1").get("input_chars"));
		// mml + sheets = mml is sheet 1
		JsonObject both = ok("track_import_mml", obj("mml", "MML@v10o5l16cdef,o3l4c;", "sheets", arr("MML@gab>c,eg;"),
				"program", 0));
		assertEquals(2, both.get("sheets").getAsInt());
		assertEquals(allNotes(1).toString(), allNotes(3).toString());
		JsonObject vel = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes").get(7).getAsJsonObject();
		assertEquals(10, vel.get("vel").getAsInt());
		assertEquals(84, vel.get("pitch").getAsInt());
	}

	@Test
	public void badTextIsRejected() {
		ok("project_new", obj("title", "bad"));
		err("track_import_mml", obj("mml", "hello", "program", 0), "BAD_ARGUMENT");
		JsonObject e = err("track_import_mml", obj("mml", "MML@hello world;", "program", 0), "BAD_ARGUMENT");
		assertEquals("h", e.getAsJsonObject("details").getAsJsonArray("bad_chars").get(0).getAsJsonObject().get("char")
				.getAsString());
		err("track_import_mml", obj("mml", "MML@c,d,e,f,g;", "program", 0), "BAD_ARGUMENT");
		err("track_import_mml", obj("mml", "MML@r1r1,r2;", "program", 0), "BAD_ARGUMENT");
		err("track_import_mml", obj("mml", "MML@c;MML@d;", "program", 0), "BAD_ARGUMENT");
		err("track_import_mml", obj("program", 0), "BAD_ARGUMENT");
		err("track_import_mml", obj("mml", "MML@c;", "program", 999), "BAD_ARGUMENT");
		err("track_import_mml", obj("mml", "MML@c;", "program", 0, "insert_at", 3), "BAD_ARGUMENT");
		err("track_import_mml", obj("sheets", arr("MML@c;", 3), "program", 0), "BAD_ARGUMENT");
		// song part on a non-song instrument
		JsonObject s = err("track_import_mml", obj("mml", "MML@c,,,d;", "program", 0), "BAD_ARGUMENT");
		assertTrue(s.get("message").getAsString().contains("song_program"));
		assertEquals(0, trackCount());
		// with a chorus instrument it is accepted
		int chorus = -1;
		for (JsonElement i : ok("instruments", obj("dls", false, "type", "C")).getAsJsonArray("instruments")) {
			chorus = i.getAsJsonObject().get("program").getAsInt();
			break;
		}
		JsonObject okSong = ok("track_import_mml", obj("mml", "MML@c,,,d;", "program", 0, "song_program", chorus));
		assertEquals(1, part(okSong, "song").get("notes").getAsInt());
	}

	@Test
	public void parseWarningsAndWhitespace() {
		ok("project_new", obj("title", "warn"));
		JsonObject r = ok("track_import_mml", obj("mml", "MML@c v20 d\ne o;", "program", 0));
		assertEquals(3, r.get("notes").getAsInt());
		assertEquals(4, r.get("whitespace_removed").getAsInt());
		assertEquals(2, r.get("parse_warning_count").getAsInt()); // v20 and "o" without a number
		JsonObject w = r.getAsJsonArray("parse_warnings").get(0).getAsJsonObject();
		assertEquals("melody", w.get("part").getAsString());
		assertEquals(1, w.get("sheet").getAsInt());
		assertEquals("v20", w.get("token").getAsString());
	}
}
