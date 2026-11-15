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
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.compare.CompareHookImpl;
import mabibridge.engine.BridgeEngine;

/**
 * project_set_time_signatures·project_set_tempos(와 project_new time_signatures).
 * 합성 박자 목록(1/16@1, 4/4@2, 5/8@11, 4/4@12, 3/4@13, 4/4@20, 3/4@32, 4/4@33)으로
 * 마디 → 틱, pos 왕복, 참조 MIDI와 마디 번호 일치, 템포 MML·저장 왕복·렌더 길이를 본다.
 */
public class TimeSigTempoToolsTest {


	/** {measure, signature} — 못갖춘마디 1/16, 5/8·3/4 마디가 섞인 목록(실제 참조 MIDI의 reference_info와 같은 모양). */
	static final Object[][] MIXED_SIGS = { { 1, "1/16" }, { 2, "4/4" }, { 11, "5/8" }, { 12, "4/4" }, { 13, "3/4" },
			{ 20, "4/4" }, { 32, "3/4" }, { 33, "4/4" } };
	/** 위 목록의 각 항목 시작 틱(96틱 기준). */
	static final int[] MIXED_TICKS = { 0, 24, 3480, 3720, 4104, 6120, 10728, 11016 };

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;
	private BridgeEngine engine;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "timesig-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(List.of(tmp.resolve("none.dls"))));
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

	static JsonArray sigs(Object[][] list) {
		JsonArray a = new JsonArray();
		for (Object[] e : list) {
			a.add(obj("measure", e[0], "signature", e[1]));
		}
		return a;
	}

	private static JsonObject withArray(JsonObject o, String key, JsonArray a) {
		o.add(key, a);
		return o;
	}

	private JsonObject setSigs(Object[][] list) {
		return ok("project_set_time_signatures", withArray(new JsonObject(), "time_signatures", sigs(list)));
	}

	private JsonObject setTempos(JsonArray tempos) {
		return ok("project_set_tempos", withArray(new JsonObject(), "tempos", tempos));
	}

	private static JsonObject tempo(Object where, int bpm) {
		return (where instanceof String) ? obj("pos", where, "bpm", bpm) : obj("tick", where, "bpm", bpm);
	}

	private static JsonArray arr(JsonObject... os) {
		JsonArray a = new JsonArray();
		for (JsonObject o : os) {
			a.add(o);
		}
		return a;
	}

	private JsonObject putNotes(int track, String part, String... posPitch) {
		JsonArray notes = new JsonArray();
		for (int i = 0; i + 1 < posPitch.length; i += 2) {
			notes.add(obj("pos", posPitch[i], "len", 24, "pitch", posPitch[i + 1]));
		}
		return ok("score_put_notes", withArray(obj("track", track, "part", part), "notes", notes));
	}

	private static void assertSigs(JsonObject info, Object[][] list, int[] ticks) {
		JsonArray ts = info.getAsJsonArray("time_signatures");
		assertEquals(String.valueOf(ts), list.length, ts.size());
		for (int i = 0; i < list.length; i++) {
			JsonObject t = ts.get(i).getAsJsonObject();
			assertEquals(String.valueOf(t), ticks[i], t.get("tick").getAsInt());
			assertEquals(String.valueOf(t), ((Integer) list[i][0]).intValue(), t.get("measure").getAsInt());
			assertEquals(String.valueOf(t), list[i][1], t.get("signature").getAsString());
		}
	}

	/** (3) 섞인 박자 목록: 마디 → 틱이 참조 MIDI 사실과 같고, 5/8·3/4 마디 안 pos가 score_put_notes → score_notes로 왕복한다. */
	@Test
	public void mixedMeterMeasureTicksAndPosRoundTrip() {
		ok("project_new", obj("title", "mixed"));
		ok("track_add", obj("name", "a", "program", 0));
		JsonObject info = setSigs(MIXED_SIGS);
		assertSigs(info, MIXED_SIGS, MIXED_TICKS);
		assertEquals("1/16", info.get("time_signature").getAsString());
		assertTrue(info.get("dirty").getAsBoolean());

		// 마디 시작 → 틱 (10:1:0 = 3096, 11 = 3480, 12 = 3720, 13 = 4104, 20 = 6120)
		String[] starts = { "1:1:0", "2:1:0", "10:1:0", "11:1:0", "12:1:0", "13:1:0", "20:1:0", "32:1:0", "33:1:0" };
		long[] startTicks = { 0, 24, 3096, 3480, 3720, 4104, 6120, 10728, 11016 };
		// 5/8 마디(11, 박 = 48틱) 안과 3/4 마디(15, 32) 안
		String[] inside = { "11:3:24", "11:5:24", "15:3:48", "32:3:72" };
		long[] insideTicks = { 3480 + 2 * 48 + 24, 3480 + 4 * 48 + 24, 4104 + 2 * 288 + 2 * 96 + 48, 10728 + 2 * 96 + 72 };
		List<String> all = new ArrayList<>();
		List<Long> allTicks = new ArrayList<>();
		for (int i = 0; i < starts.length; i++) {
			all.add(starts[i]);
			allTicks.add(startTicks[i]);
		}
		for (int i = 0; i < inside.length; i++) {
			all.add(inside[i]);
			allTicks.add(insideTicks[i]);
		}
		List<String> pp = new ArrayList<>();
		for (String p : all) {
			pp.add(p);
			pp.add("C4");
		}
		// 1마디(1/16 = 24틱)에 24틱 음 하나가 꼭 맞는다
		putNotes(1, "melody", pp.toArray(String[]::new));
		JsonObject sn = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 120));
		JsonArray notes = sn.getAsJsonArray("notes");
		assertEquals(all.size(), notes.size());
		java.util.Map<Long, String> byTick = new java.util.HashMap<>();
		for (JsonElement e : notes) {
			byTick.put(e.getAsJsonObject().get("tick").getAsLong(), e.getAsJsonObject().get("pos").getAsString());
		}
		for (int i = 0; i < all.size(); i++) {
			assertEquals(all.get(i), all.get(i), byTick.get(allTicks.get(i)));
		}
		// 마디 범위도 새 박자로 센다: 11마디(5/8)에는 3개
		JsonObject m91 = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 11, "to_measure", 11));
		assertEquals(3, m91.getAsJsonArray("notes").size());

		// 5/8 마디에 6박째는 없다, 3/4 마디에 4박째는 없다
		JsonArray bad = arr(obj("pos", "11:6:0", "len", 24, "pitch", "C4"));
		err("score_put_notes", withArray(obj("track", 1, "part", "chord1"), "notes", bad), "BAD_NOTE");
		JsonArray bad2 = arr(obj("pos", "13:4:0", "len", 24, "pitch", "C4"));
		err("score_put_notes", withArray(obj("track", 1, "part", "chord1"), "notes", bad2), "BAD_NOTE");

		// 박자를 4/4 하나로 되돌리면 음표 틱은 그대로, pos만 다시 읽힌다(틱 3480: 11:1:0 → 10:1:24)
		JsonObject back = setSigs(new Object[][] { { 1, "4/4" } });
		assertEquals(1, back.getAsJsonArray("time_signatures").size());
		JsonObject sn2 = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 200));
		assertEquals(all.size(), sn2.getAsJsonArray("notes").size());
		boolean found = false;
		for (JsonElement e : sn2.getAsJsonArray("notes")) {
			JsonObject n = e.getAsJsonObject();
			if (n.get("tick").getAsLong() == 3480) {
				assertEquals("10:1:24", n.get("pos").getAsString()); // 4/4: 9 * 384 = 3456, +24
			}
			found |= n.get("tick").getAsLong() == 3480;
		}
		assertTrue(found);
	}

	/** (3) 참조 MIDI(해상도 480, 같은 박자 목록)와 악보의 마디 번호가 reference_notes·score_notes·compare에서 같다. */
	@Test
	public void referenceMeasuresAgree() throws Exception {
		Path mid = tmp.resolve("mixed_sigs.mid");
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t0 = seq.createTrack();
		// {src tick, 분자, 분모 지수}
		long[][] ts = { { 0, 1, 4 }, { 120, 4, 2 }, { 17400, 5, 3 }, { 18600, 4, 2 }, { 20520, 3, 2 }, { 30600, 4, 2 },
				{ 53640, 3, 2 }, { 55080, 4, 2 } };
		for (long[] t : ts) {
			t0.add(new MidiEvent(new MetaMessage(0x58, new byte[] { (byte) t[1], (byte) t[2], 24, 8 }, 4), t[0]));
		}
		t0.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x06, 0x1A, (byte) 0x80 }, 3), 0)); // 150 BPM
		Track t1 = seq.createTrack();
		// src tick = 96틱 × 5. 10:1:0, 11:3:24, 11:5:24, 15:3:48, 20:1:0, 32:3:72
		long[] at96 = { 3096, 3600, 3696, 4920, 6120, 10992 };
		int[] pitch = { 60, 62, 64, 65, 67, 69 };
		for (int i = 0; i < at96.length; i++) {
			t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch[i], 100), at96[i] * 5));
			t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch[i], 0), at96[i] * 5 + 120));
		}
		MidiSystem.write(seq, 1, mid.toFile());
		JsonObject ref = ok("reference_load", obj("path", mid.toString()));
		assertNotNull(ref);

		ok("project_new", withArray(obj("title", "ref"), "time_signatures", sigs(MIXED_SIGS)));
		ok("track_add", obj("name", "a", "program", 0));
		JsonObject rn = ok("reference_notes", obj("from_measure", 10, "to_measure", 32));
		JsonArray rnotes = rn.getAsJsonArray("notes");
		assertEquals(at96.length, rnotes.size());
		JsonArray put = new JsonArray();
		for (JsonElement e : rnotes) {
			JsonObject n = e.getAsJsonObject();
			put.add(obj("pos", n.get("pos").getAsString(), "len", 24, "pitch", n.get("pitch").getAsInt()));
		}
		ok("score_put_notes", withArray(obj("track", 1, "part", "melody"), "notes", put));
		JsonArray snotes = ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 10, "to_measure", 32))
				.getAsJsonArray("notes");
		assertEquals(rnotes.size(), snotes.size());
		String[] expected = { "10:1:0", "11:3:24", "11:5:24", "15:3:48", "20:1:0", "32:3:72" };
		for (int i = 0; i < rnotes.size(); i++) {
			JsonObject r = rnotes.get(i).getAsJsonObject();
			JsonObject s = snotes.get(i).getAsJsonObject();
			assertEquals(expected[i], r.get("pos").getAsString());
			assertEquals(r.get("pos").getAsString(), s.get("pos").getAsString());
			assertEquals(r.get("tick").getAsLong(), s.get("tick").getAsLong());
			assertEquals(at96[i], s.get("tick").getAsLong());
			assertEquals(r.get("measure").getAsInt(), Integer.parseInt(s.get("pos").getAsString().split(":")[0]));
		}

		JsonObject mapping = new JsonObject();
		JsonArray mf = new JsonArray();
		mf.add(rnotes.get(0).getAsJsonObject().get("track").getAsInt()); // 참조 트랙 2(트랙 1은 박자·템포만)
		mapping.add("1", withArray(obj("chords_from", "all"), "melody_from", mf));
		ok("compare_set_mapping", obj("mapping", mapping));
		JsonObject c = ok("compare", obj("from_measure", 10, "to_measure", 32, "pitch_mode", "written"));
		JsonObject mel = c.getAsJsonObject("summary").getAsJsonObject("melody");
		assertEquals(String.valueOf(mel), at96.length, mel.get("matched").getAsInt());
		assertEquals(0, mel.get("wrong").getAsInt() + mel.get("shifted").getAsInt() + mel.get("missing").getAsInt()
				+ mel.get("extra").getAsInt());
		assertEquals(0, c.get("measure_offset_used").getAsInt());
	}

	/** 템포 목록: pos는 지금 박자로 풀고, MML의 t 명령·tempo_mid_note·저장 왕복이 새 목록을 따른다. */
	@Test
	public void temposMmlStatsAndSaveRoundTrip() throws IOException {
		ok("project_new", obj("title", "tempo"));
		ok("track_add", obj("name", "a", "program", 0));
		ok("track_add", obj("name", "b", "program", 0));
		ok("track_add", obj("name", "c", "program", 0));
		setSigs(MIXED_SIGS);
		// 트랙 1: 9:4:0 2분음표가 10:1:0을 걸친다(tempo_mid_note). 트랙 2: 화음만, 트랙 3: 노래만. 모두 22마디 뒤까지
		JsonArray n1 = arr(obj("pos", "9:4:0", "len", "2", "pitch", "C5"), obj("pos", "23:1:0", "len", "4", "pitch", "C5"));
		ok("score_put_notes", withArray(obj("track", 1, "part", "melody"), "notes", n1));
		JsonArray n2 = arr(obj("pos", "2:1:0", "len", "1", "pitch", "C4"), obj("pos", "23:1:0", "len", "4", "pitch", "E4"));
		ok("score_put_notes", withArray(obj("track", 2, "part", "chord1"), "notes", n2));
		ok("score_put_notes", withArray(obj("track", 3, "part", "song"), "notes", n2.deepCopy()));

		JsonObject info = setTempos(arr(tempo(0, 150), tempo("10:1:0", 160), tempo(6888, 150)));
		JsonArray tl = info.getAsJsonArray("tempos");
		assertEquals(3, tl.size());
		int[][] want = { { 0, 150 }, { 3096, 160 }, { 6888, 150 } };
		String[] wantPos = { "1:1:0", "10:1:0", "22:1:0" };
		for (int i = 0; i < 3; i++) {
			JsonObject t = tl.get(i).getAsJsonObject();
			assertEquals(want[i][0], t.get("tick").getAsInt());
			assertEquals(want[i][1], t.get("bpm").getAsInt());
			assertEquals(wantPos[i], t.get("pos").getAsString());
		}
		assertSigs(info, MIXED_SIGS, MIXED_TICKS); // 박자는 그대로

		// 모든 트랙의 MML에 세 템포가 다 들어간다(멜로디·화음 파트 중 한 곳에 한 번, 노래 파트는 따로)
		JsonObject mml = ok("score_mml", new JsonObject());
		JsonArray tracks = mml.getAsJsonArray("tracks");
		assertEquals(3, tracks.size());
		for (JsonElement e : tracks) {
			String m = e.getAsJsonObject().get("mml").getAsString();
			assertTrue(m, m.contains("t150"));
			assertTrue(m, m.contains("t160"));
			assertEquals(m, 2, m.split("t150", -1).length - 1);
			assertEquals(m, 1, m.split("t160", -1).length - 1);
		}

		// tempo_mid_note: 10:1:0의 t160이 트랙 1 멜로디 9:4:0 음 안
		JsonObject st = ok("score_stats", obj("track", 1));
		boolean mid = false;
		for (JsonElement w : st.getAsJsonArray("warnings")) {
			JsonObject wo = w.getAsJsonObject();
			if ("tempo_mid_note".equals(wo.get("code").getAsString())) {
				mid = true;
				JsonObject item = wo.getAsJsonArray("items").get(0).getAsJsonObject();
				assertEquals(3096, item.get("tick").getAsInt());
				assertEquals("10:1:0", item.get("pos").getAsString());
				assertEquals(160, item.get("bpm").getAsInt());
			}
		}
		assertTrue(String.valueOf(st), mid);
		JsonObject st2 = ok("score_stats", obj("track", 2));
		for (JsonElement w : st2.getAsJsonArray("warnings")) {
			assertFalse("tempo_mid_note".equals(w.getAsJsonObject().get("code").getAsString()));
		}

		// 저장 → 열기 왕복
		Path f = tmp.resolve("2j.mmi");
		ok("project_save_as", obj("path", f.toString()));
		String text = Files.readString(f);
		assertTrue(text, text.contains("tempo=0T150,3096T160,6888T150"));
		assertTrue(text, text.contains("time=1/16"));
		JsonObject o = ok("project_open", obj("path", f.toString()));
		assertSigs(o, MIXED_SIGS, MIXED_TICKS);
		assertEquals(tl, o.getAsJsonArray("tempos"));
		assertEquals(mml.getAsJsonArray("tracks").get(0).getAsJsonObject().get("mml"),
				ok("score_mml", obj("track", 1)).get("mml"));
	}

	/** 거부: 박자·템포 규칙 위반은 BAD_ARGUMENT(details에 index·entry·reason), 악보는 그대로. */
	@Test
	public void rejectedArguments() {
		ok("project_new", obj("title", "bad"));
		ok("track_add", obj("name", "a", "program", 0));
		setSigs(new Object[][] { { 1, "4/4" }, { 3, "3/4" } });
		JsonObject before = ok("project_info", null);

		Object[][][] badSigs = {
				{ { 2, "4/4" } }, // 첫 항목이 1마디가 아님
				{ { 1, "4/4" }, { 3, "3/4" }, { 3, "4/4" } }, // 같은 마디 두 번
				{ { 1, "4/4" }, { 5, "3/4" }, { 3, "4/4" } }, // 순서
				{ { 1, "5/7" } }, // 분모
				{ { 1, "33/4" } }, // 분자
				{ { 1, "0/4" } },
				{ { 1, "4-4" } },
				{ { 1, "4/4" }, { 3, "4/4" } }, // 앞과 같은 박자
				{ { 1, "4/4" }, { 0, "3/4" } },
				{ { 1, "4/4" }, { 10001, "3/4" } },
		};
		for (Object[][] b : badSigs) {
			JsonObject e = err("project_set_time_signatures", withArray(new JsonObject(), "time_signatures", sigs(b)),
					"BAD_ARGUMENT");
			JsonObject d = e.getAsJsonObject("details");
			assertTrue(String.valueOf(e), d.has("index") && d.has("entry") && d.has("reason"));
		}
		JsonObject first = err("project_set_time_signatures",
				withArray(new JsonObject(), "time_signatures", sigs(new Object[][] { { 1, "4/4" }, { 91, "5/7" } })),
				"BAD_ARGUMENT");
		assertEquals(1, first.getAsJsonObject("details").get("index").getAsInt());
		assertEquals("5/7", first.getAsJsonObject("details").getAsJsonObject("entry").get("signature").getAsString());
		// 형식: 빈 배열, 문자열 마디, 모르는 키, 배열 아님
		err("project_set_time_signatures", withArray(new JsonObject(), "time_signatures", new JsonArray()), "BAD_ARGUMENT");
		err("project_set_time_signatures", withArray(new JsonObject(), "time_signatures", arr(obj("measure", "1", "signature", "4/4"))),
				"BAD_ARGUMENT");
		err("project_set_time_signatures", withArray(new JsonObject(), "time_signatures", arr(obj("measure", 1.5, "signature", "4/4"))),
				"BAD_ARGUMENT");
		err("project_set_time_signatures",
				withArray(new JsonObject(), "time_signatures", arr(obj("measure", 1, "signature", "4/4", "tick", 0))), "BAD_ARGUMENT");
		err("project_set_time_signatures", obj("time_signatures", "4/4"), "BAD_ARGUMENT");
		err("project_set_time_signatures", new JsonObject(), "BAD_ARGUMENT");

		JsonArray[] badTempos = {
				arr(tempo(96, 120)), // 틱 0 없음
				arr(tempo(0, 120), tempo(384, 130), tempo(384, 140)), // 한 틱에 둘
				arr(tempo(0, 120), tempo(768, 130), tempo(384, 140)), // 순서
				arr(tempo(0, 120), tempo(384, 130), tempo("2:1:0", 140)), // pos로 푼 틱(384)이 앞 항목과 같음
				arr(tempo(0, 0)),
				arr(tempo(0, 1001)),
				arr(tempo(0, 31)), // 마비노기 범위 32~255 밖
				arr(tempo(0, 120), tempo(96, 256)),
				arr(tempo(0, 1)),
				arr(tempo(0, 1000)),
				arr(tempo(0, 120), tempo(100, 130)), // 6의 배수 아님
				arr(tempo(0, 120), tempo("3:4:0", 130)), // 3/4 마디에 4박 없음
				arr(tempo(0, 120), tempo("x", 130)),
				arr(obj("tick", 0, "pos", "1:1:0", "bpm", 120)),
				arr(obj("bpm", 120)),
				arr(obj("tick", 0, "bpm", "120")),
				arr(obj("tick", 0, "bpm", 120.5)),
				arr(obj("tick", "0", "bpm", 120)),
				arr(obj("tick", 0, "bpm", 120, "len", 1)),
				arr(obj("tick", -6, "bpm", 120)),
		};
		for (JsonArray b : badTempos) {
			JsonObject e = err("project_set_tempos", withArray(new JsonObject(), "tempos", b), "BAD_ARGUMENT");
			JsonObject d = e.getAsJsonObject("details");
			assertTrue(String.valueOf(e), d.has("index") && d.has("reason"));
		}
		err("project_set_tempos", withArray(new JsonObject(), "tempos", new JsonArray()), "BAD_ARGUMENT");
		JsonObject dup = err("project_set_tempos", withArray(new JsonObject(), "tempos", badTempos[1]), "BAD_ARGUMENT");
		assertEquals(2, dup.getAsJsonObject("details").get("index").getAsInt());

		// project_new: time_signature와 time_signatures를 함께 주면 거부(열린 악보는 그대로)
		err("project_new", withArray(obj("time_signature", "4/4"), "time_signatures", sigs(MIXED_SIGS)), "BAD_ARGUMENT");
		err("project_new", withArray(new JsonObject(), "time_signatures", sigs(new Object[][] { { 2, "4/4" } })),
				"BAD_ARGUMENT");

		JsonObject after = ok("project_info", null);
		assertEquals(before.get("time_signatures"), after.get("time_signatures"));
		assertEquals(before.get("tempos"), after.get("tempos"));
		assertEquals("bad", after.get("title").getAsString());

		// project_new time_signatures + tempo
		JsonObject nw = ok("project_new", withArray(obj("title", "n", "tempo", 150), "time_signatures", sigs(MIXED_SIGS)));
		assertSigs(nw, MIXED_SIGS, MIXED_TICKS);
		assertEquals(150, nw.getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
		assertFalse(nw.get("dirty").getAsBoolean());
	}

	/** bpm은 32~255(MabiIcco 템포 대화상자·마비노기 MelodyParser 범위): 31·256(과 1·1000)은 거부, 32·255는 받는다. project_new의 tempo도 같다. */
	@Test
	public void tempoRangeMabinogi() {
		ok("project_new", obj("title", "range"));
		ok("track_add", obj("name", "a", "program", 0));
		JsonObject before = ok("project_info", null);
		for (int bpm : new int[] { 31, 256, 1, 1000 }) {
			JsonObject e = err("project_set_tempos", withArray(new JsonObject(), "tempos", arr(tempo(0, 120), tempo(96, bpm))),
					"BAD_ARGUMENT");
			JsonObject d = e.getAsJsonObject("details");
			assertEquals(1, d.get("index").getAsInt());
			assertEquals(bpm, d.getAsJsonObject("entry").get("bpm").getAsInt());
			assertTrue(d.has("reason"));
			assertEquals("[32,255]", d.get("allowed_bpm").toString());
			err("project_set_tempos", withArray(new JsonObject(), "tempos", arr(tempo(0, bpm))), "BAD_ARGUMENT");
		}
		for (int bpm : new int[] { 31, 256, 1, 1000 }) {
			JsonObject pn = err("project_new", obj("title", "x", "tempo", bpm), "BAD_ARGUMENT");
			assertEquals("[32,255]", pn.getAsJsonObject("details").get("allowed_bpm").toString());
		}
		JsonObject after = ok("project_info", null);
		assertEquals(before.get("tempos"), after.get("tempos"));
		assertEquals("range", after.get("title").getAsString());

		JsonObject edge = setTempos(arr(tempo(0, 32), tempo(96, 255)));
		assertEquals(32, edge.getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(255, edge.getAsJsonArray("tempos").get(1).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(32, ok("project_new", obj("tempo", 32)).getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(255, ok("project_new", obj("tempo", 255)).getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsInt());
	}

	/** render_wav가 새 템포 목록을 따른다: 4/4 2마디를 120과 240으로 렌더하면 범위 길이가 4초 → 2초. DLS가 없으면 [SKIP]. */
	@Test
	public void renderFollowsTempo() throws IOException {
		boolean dls = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!dls) {
			System.err.println("[SKIP] TimeSigTempoToolsTest.renderFollowsTempo: DLS 파일 없음");
			Assume.assumeTrue(false);
		}
		BridgeEngine real = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snap2")));
		state = new ServerState(real, null);
		ok("project_new", obj("title", "render"));
		ok("track_add", obj("name", "p", "program", 0));
		JsonArray n = new JsonArray();
		for (int i = 0; i < 8; i++) {
			n.add(obj("tick", i * 96, "len", "4", "pitch", 60 + i));
		}
		ok("score_put_notes", withArray(obj("track", 1, "part", "melody"), "notes", n));
		double[] sec = new double[3];
		double[] dur = new double[3];
		JsonArray[] maps = { arr(tempo(0, 120)), arr(tempo(0, 240)), arr(tempo(0, 120), tempo("2:1:0", 240)) };
		for (int i = 0; i < maps.length; i++) {
			setTempos(maps[i]);
			JsonObject r = ok("render_wav", obj("from_measure", 1, "to_measure", 2, "tail_sec", 0.5, "name", "tempo" + i,
					"out_dir", tmp.resolve("render").toString()));
			sec[i] = r.getAsJsonObject("range").get("range_sec").getAsDouble();
			dur[i] = r.getAsJsonArray("files").get(0).getAsJsonObject().get("duration_sec").getAsDouble();
		}
		assertEquals(4.0, sec[0], 0.01);
		assertEquals(2.0, sec[1], 0.01);
		assertEquals(3.0, sec[2], 0.01);
		assertTrue(dur[0] + " " + dur[1], dur[1] < dur[0] - 1.5);
		assertTrue(dur[0] + " " + dur[2], dur[2] < dur[0] - 0.5 && dur[2] > dur[1] + 0.5);
	}
}
