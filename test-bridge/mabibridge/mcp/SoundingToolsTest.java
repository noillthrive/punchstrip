package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.compare.CompareHookImpl;
import mabibridge.engine.BridgeEngine;

/**
 * MCP 도구: 쓰기의 pitch_mode·timbre, 읽기의 sounding 필드, PITCH_NOT_PRODUCIBLE, silent_note 경고,
 * compare 인자(pitch_mode·measure_offset·octave_tolerant)와 매핑 항목의 선택 값. DLS 경로를 없는 파일로 줘서
 * 음역은 properties 기준(시험이 DLS에 기대지 않게).
 */
public class SoundingToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "sounding-mcp-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(java.util.List.of(tmp.resolve("none.dls"))));
		state = new ServerState(engine, new CompareHookImpl(engine));
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

	private static JsonArray notes(Object... posPitch) {
		JsonArray a = new JsonArray();
		for (int i = 0; i + 1 < posPitch.length; i += 2) {
			a.add(obj("pos", posPitch[i], "len", "4", "pitch", posPitch[i + 1]));
		}
		return a;
	}

	private JsonObject put(int track, String part, JsonArray notes, Object... extra) {
		JsonObject o = obj("track", track, "part", part);
		o.add("notes", notes);
		for (int i = 0; i + 1 < extra.length; i += 2) {
			o.add((String) extra[i], Json.tree(extra[i + 1]));
		}
		return o;
	}

	private void newProject() {
		ok("project_new", null);
		ok("track_add", obj("name", "piano", "program", 21)); // 1
		ok("track_add", obj("name", "bass", "program", 29)); // 2
		ok("track_add", obj("name", "bottle", "program", 7)); // 3
	}

	@Test
	public void writeSoundingPitch() {
		newProject();
		// 피아노: 들리는 C5(72), E5 → 적은 C4(60), E4
		JsonObject r = ok("score_put_notes", put(1, "melody", notes("1:1:0", 72, "1:2:0", "E5"), "pitch_mode", "sounding"));
		assertEquals("sounding", r.get("pitch_mode").getAsString());
		assertEquals(2, r.get("pitch_converted").getAsInt());
		JsonObject n = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1));
		JsonObject n0 = n.getAsJsonArray("notes").get(0).getAsJsonObject();
		assertEquals(60, n0.get("pitch").getAsInt());
		assertEquals(72, n0.get("sounding_pitch").getAsInt());
		assertEquals("C5", n0.get("sounding_name").getAsString());
		assertFalse(n0.get("silent").getAsBoolean());
		assertEquals(64, n.getAsJsonArray("notes").get(1).getAsJsonObject().get("pitch").getAsInt());

		// 베이스: 들리는 E2(40) → default 28, alt 76
		ok("score_put_notes", put(2, "melody", notes("1:1:0", 40), "pitch_mode", "sounding"));
		JsonObject alt = ok("score_put_notes", put(2, "chord1", notes("1:1:0", 40), "pitch_mode", "sounding", "timbre", "alt"));
		assertEquals("alt", alt.get("timbre").getAsString());
		assertEquals(28, ok("score_notes", obj("track", 2, "part", "melody", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes").get(0).getAsJsonObject().get("pitch").getAsInt());
		JsonObject c1 = ok("score_notes", obj("track", 2, "part", "chord1", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes").get(0).getAsJsonObject();
		assertEquals(76, c1.get("pitch").getAsInt());
		assertEquals(40, c1.get("sounding_pitch").getAsInt());

		// 기본(written)은 지금까지와 같다
		JsonObject w = ok("score_put_notes", put(1, "melody", notes("2:1:0", 72)));
		assertEquals("written", w.get("pitch_mode").getAsString());
		assertEquals(0, w.get("pitch_converted").getAsInt());
		assertEquals(72, ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 2, "to_measure", 2))
				.getAsJsonArray("notes").get(0).getAsJsonObject().get("pitch").getAsInt());

		// score_replace_range도
		JsonObject rep = obj("track", 1, "from_measure", 3, "to_measure", 3, "pitch_mode", "sounding");
		JsonObject parts = new JsonObject();
		parts.add("melody", notes("3:1:0", 84));
		parts.add("chord1", notes("3:1:0", 76));
		rep.add("parts", parts);
		JsonObject rr = ok("score_replace_range", rep);
		assertEquals(2, rr.get("pitch_converted").getAsInt());
		assertEquals(72, ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 3, "to_measure", 3))
				.getAsJsonArray("notes").get(0).getAsJsonObject().get("pitch").getAsInt());
	}

	@Test
	public void pitchNotProducible() {
		newProject();
		// 고정음 악기
		JsonObject e = err("score_put_notes", put(3, "melody", notes("1:1:0", 60), "pitch_mode", "sounding"),
				"PITCH_NOT_PRODUCIBLE");
		JsonObject d = e.getAsJsonObject("details");
		assertEquals(3, d.get("track").getAsInt());
		assertEquals("1:1:0", d.get("pos").getAsString());
		assertEquals(60, d.get("sounding_pitch").getAsInt());
		assertEquals(7, d.get("program").getAsInt());
		assertTrue(d.has("producible"));
		// 베이스 default로 들리는 70 → 적을 음 58은 무음 구간
		JsonObject e2 = err("score_put_notes", put(2, "melody", notes("1:1:0", 70), "pitch_mode", "sounding"),
				"PITCH_NOT_PRODUCIBLE");
		assertEquals(2, e2.getAsJsonObject("details").getAsJsonArray("producible").size());
		// 아무것도 넣지 않았다(한 음이라도 안 되면 전부 거부)
		JsonObject e3 = err("score_put_notes", put(1, "melody", notes("1:1:0", 72, "1:2:0", 200), "pitch_mode", "sounding"),
				"PITCH_NOT_PRODUCIBLE");
		assertTrue(e3.toString(), e3.get("message").getAsString().contains("음표 1번"));
		assertEquals(0, ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes").size());
		// timbre는 sounding일 때만, 구간 악기가 아니면 default만
		err("score_put_notes", put(2, "melody", notes("1:1:0", 40), "timbre", "alt"), "BAD_ARGUMENT");
		err("score_put_notes", put(1, "melody", notes("1:1:0", 72), "pitch_mode", "sounding", "timbre", "alt"), "BAD_ARGUMENT");
		err("score_put_notes", put(1, "melody", notes("1:1:0", 72), "pitch_mode", "loud"), "BAD_ARGUMENT");
	}

	@Test
	public void silentNoteWarningAndStats() {
		newProject();
		JsonObject r = ok("score_put_notes", put(2, "melody", notes("1:1:0", 40, "1:2:0", 59)));
		boolean found = false;
		for (JsonElement w : r.getAsJsonArray("warnings")) {
			found |= w.getAsString().startsWith("silent_note:");
		}
		assertTrue(r.getAsJsonArray("warnings").toString(), found);
		JsonObject n1 = ok("score_notes", obj("track", 2, "part", "melody", "from_measure", 1, "to_measure", 1))
				.getAsJsonArray("notes").get(1).getAsJsonObject();
		assertTrue(n1.get("silent").getAsBoolean());
		assertTrue(n1.get("sounding_pitch").isJsonNull());
		JsonObject st = ok("score_stats", obj("track", 2));
		assertEquals("구간별", st.get("sounding").getAsString());
		assertEquals(1, st.get("silent_notes").getAsInt());
		assertEquals(52, st.get("sounding_pitch_min").getAsInt());
		assertEquals("E3", st.get("sounding_name_min").getAsString());
		assertEquals(52, st.get("sounding_pitch_max").getAsInt());
		// 쓰기 응답의 stats에도
		assertEquals(1, r.getAsJsonObject("stats").get("silent_notes").getAsInt());
	}

	@Test
	public void readFields() {
		JsonObject ins = ok("instruments", obj("dls", false));
		boolean piano = false;
		boolean bass = false;
		for (JsonElement e : ins.getAsJsonArray("instruments")) {
			JsonObject i = e.getAsJsonObject();
			assertTrue(i.toString(), i.has("sounding") && i.has("sounding_range"));
			int p = i.get("program").getAsInt();
			if (p == 21) {
				piano = true;
				assertEquals(12, i.getAsJsonObject("sounding").get("offset_semitones").getAsInt());
				assertEquals("offset", i.getAsJsonObject("sounding").get("kind").getAsString());
			}
			if (p == 29) {
				bass = true;
				assertEquals(2, i.getAsJsonObject("sounding").getAsJsonArray("regions").size());
				assertEquals(3, i.getAsJsonObject("sounding").getAsJsonArray("silent").size()); // below 14, 56..61, above 103
				assertEquals(2, i.getAsJsonArray("sounding_range").size());
			}
		}
		assertTrue(piano && bass);
		ok("project_new", null);
		JsonObject t = ok("track_add", obj("name", "v", "program", 22));
		assertEquals("+2옥타브", t.getAsJsonObject("summary").get("sounding").getAsString());
		JsonObject t2 = ok("track_add", obj("name", "c", "program", 0, "song_program", 110));
		assertEquals("+2옥타브", t2.getAsJsonObject("summary").get("song_sounding").getAsString());
		assertEquals("적은 대로", t2.getAsJsonObject("summary").get("sounding").getAsString());
		JsonObject info = ok("project_info", null);
		assertEquals("+2옥타브", info.getAsJsonArray("tracks").get(0).getAsJsonObject().get("sounding").getAsString());
		JsonObject set = ok("track_set", obj("track", 1, "program", 21));
		assertEquals("+1옥타브", set.get("sounding").getAsString());
	}

	/** compare 인자와 매핑 항목의 pitch_mode·measure_offset. */
	@Test
	public void compareArguments() throws Exception {
		Path mid = tmp.resolve("ref.mid");
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));
		t1.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		int[] mel = { 60, 62, 64, 65 };
		for (int i = 0; i < 4; i++) {
			t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, mel[i], 100), i * 480L));
			t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, mel[i], 0), i * 480L + 480));
		}
		MidiSystem.write(seq, 1, mid.toFile());
		ok("reference_load", obj("path", mid.toString()));
		newProject();
		// 피아노 트랙에 원곡 음을 1마디 늦게, 1옥타브 낮춰 적음 → 들리는 음은 원곡과 같다
		ok("score_put_notes", put(1, "melody", notes("2:1:0", "C3", "2:2:0", "D3", "2:3:0", "E3", "2:4:0", "F3")));
		JsonObject mapping = new JsonObject();
		JsonArray mf = new JsonArray();
		mf.add(1);
		JsonObject e1 = obj("chords_from", "all");
		e1.add("melody_from", mf);
		mapping.add("1", e1);
		ok("compare_set_mapping", obj("mapping", mapping));

		JsonObject a = ok("compare", obj("from_measure", 1, "to_measure", 1, "measure_offset", "auto"));
		assertEquals("sounding", a.get("pitch_mode").getAsString());
		assertEquals(1, a.get("measure_offset_used").getAsInt());
		assertTrue(a.get("measure_offset_auto").getAsBoolean());
		assertEquals(4, a.getAsJsonObject("summary").getAsJsonObject("melody").get("matched").getAsInt());
		JsonObject w = ok("compare", obj("from_measure", 1, "to_measure", 1, "measure_offset", 1, "pitch_mode", "written"));
		assertEquals(4, w.getAsJsonObject("summary").getAsJsonObject("melody").get("wrong").getAsInt());
		JsonObject wt = ok("compare", obj("from_measure", 1, "to_measure", 1, "measure_offset", 1, "pitch_mode", "written",
				"octave_tolerant", true));
		assertEquals(4, wt.getAsJsonObject("summary").getAsJsonObject("melody").get("octave_shifted").getAsInt());
		assertEquals(-12, wt.getAsJsonArray("melody_tracks").get(0).getAsJsonObject().get("octave_shift_main").getAsInt());
		err("compare", obj("from_measure", 1, "to_measure", 1, "measure_offset", -1), "BAD_ARGUMENT");
		err("compare", obj("from_measure", 1, "to_measure", 1, "pitch_mode", "loud"), "BAD_ARGUMENT");
		err("compare", obj("from_measure", 1, "to_measure", 1, "measure_offset", "later"), "BAD_ARGUMENT");

		// 매핑 항목에 measure_offset을 저장하면 인자 없는 compare와 쓰기 응답의 compare_summary가 그 값을 쓴다
		JsonObject e2 = obj("chords_from", "all", "measure_offset", 1, "pitch_mode", "sounding");
		e2.add("melody_from", mf);
		JsonObject mapping2 = new JsonObject();
		mapping2.add("1", e2);
		JsonObject sm = ok("compare_set_mapping", obj("mapping", mapping2));
		assertEquals(1, sm.getAsJsonObject("mapping").getAsJsonObject("1").get("measure_offset").getAsInt());
		assertEquals("sounding", sm.getAsJsonObject("mapping").getAsJsonObject("1").get("pitch_mode").getAsString());
		JsonObject c = ok("compare", obj("from_measure", 1, "to_measure", 1));
		assertEquals(1, c.get("measure_offset_used").getAsInt());
		assertEquals(4, c.getAsJsonObject("summary").getAsJsonObject("melody").get("matched").getAsInt());
		JsonObject wr = ok("score_put_notes", put(1, "chord1", notes("2:1:0", "G2")));
		JsonObject cs = wr.getAsJsonObject("compare_summary");
		assertEquals(1, cs.get("measure_offset_used").getAsInt());
		assertEquals("sounding", cs.get("pitch_mode").getAsString());
		assertEquals(4, cs.getAsJsonObject("summary").getAsJsonObject("melody").get("matched").getAsInt());
		// 항목에 auto는 안 된다
		JsonObject e3 = obj("measure_offset", "auto");
		e3.add("melody_from", mf);
		JsonObject mapping3 = new JsonObject();
		mapping3.add("1", e3);
		err("compare_set_mapping", obj("mapping", mapping3), "BAD_ARGUMENT");

		JsonObject sug = ok("compare_suggest_mapping", obj("from_measure", 1, "to_measure", 1, "measure_offset", "auto"));
		assertEquals(1, sug.get("measure_offset_used").getAsInt());
		JsonObject cand = sug.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonArray("suggestions").get(0)
				.getAsJsonObject();
		assertEquals(1, cand.get("ref_track").getAsInt());
		assertEquals(1.0, cand.get("ratio").getAsDouble(), 1e-9);
		assertEquals(0, cand.get("octave_shift").getAsInt());
		assertEquals(1, sug.getAsJsonObject("suggested_mapping").getAsJsonObject("1").get("measure_offset").getAsInt());
	}
}
