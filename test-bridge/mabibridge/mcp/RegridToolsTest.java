package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;

/** reference_regrid(벽시계 시각 → 고정 격자, 원본 보존·되돌리기)과 reference_cleanup(단계별 정리). */
public class RegridToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "regrid-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(List.of(tmp.resolve("none.dls"))));
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

	private static JsonArray ints(int... v) {
		JsonArray a = new JsonArray();
		for (int x : v) {
			a.add(x);
		}
		return a;
	}

	// ------------------------------------------------------------------ 합성 SMF

	private static MetaMessage tempo(double bpm) throws Exception {
		int us = (int) Math.round(60_000_000.0 / bpm);
		return new MetaMessage(0x51, new byte[] { (byte) (us >> 16), (byte) (us >> 8), (byte) us }, 3);
	}

	private static void note(Track t, int ch, int pitch, long on, long off) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, 100), on));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), off));
	}

	/**
	 * 480 ppq, 템포 1마디 120 · 2마디 60 · 3마디부터 90 BPM. 트랙 2(피아노, 프로그램 1): 0.0 s C4, 0.5 s E4(4분),
	 * 3.0 s G4(2마디 2박, 4분 = 1 s), 6.667 s C5(3마디 2박). 트랙 3(채널 10): 0, 2.0, 6.0 s에 킥 36.
	 */
	private Path variableTempoMidi() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track c = seq.createTrack();
		c.add(new MidiEvent(tempo(120), 0));
		c.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		c.add(new MidiEvent(tempo(60), 1920));
		c.add(new MidiEvent(tempo(90), 3840));
		Track p = seq.createTrack();
		p.add(new MidiEvent(new MetaMessage(0x03, "Piano".getBytes("UTF-8"), 5), 0));
		p.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 1, 0), 0));
		note(p, 0, 60, 0, 480);
		note(p, 0, 64, 480, 960);
		note(p, 0, 67, 2400, 2880);
		note(p, 0, 72, 4320, 4800);
		Track d = seq.createTrack();
		note(d, 9, 36, 0, 120);
		note(d, 9, 36, 1920, 2040);
		note(d, 9, 36, 3840, 3960);
		Path f = tmp.resolve("variable_tempo.mid");
		MidiSystem.write(seq, 1, f.toFile());
		return f;
	}

	/** 96 ppq, 120 BPM, 트랙 2에 지저분한 음: 격자 밖, 거의 겹친 중복, 접으면 겹치는 음, 짧은 음, 화음 4성, 음역 밖. */
	private Path messyMidi() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 96);
		Track c = seq.createTrack();
		c.add(new MidiEvent(tempo(120), 0));
		Track p = seq.createTrack();
		note(p, 0, 60, 2, 10);      // C4 0..12 (96-tick import grid) -> quantized 0..24
		note(p, 0, 60, 11, 90);     // C4 12..90 -> 24..96: a duplicate with dedupe_ticks 24
		note(p, 0, 64, 100, 300);   // E4 96..312 after quantize
		note(p, 0, 76, 190, 380);   // E5 192..384, folded into [48, 72] -> E4: overlaps the E4 above
		note(p, 0, 70, 384, 388);   // A#4: one 16th after quantize, shorter than min_length_ticks 30
		note(p, 0, 48, 480, 576);   // chord of 4 at 480
		note(p, 0, 52, 480, 576);
		note(p, 0, 55, 480, 576);
		note(p, 0, 59, 480, 576);
		note(p, 0, 100, 672, 768);  // above range -> folds 100 -> 88 -> 76 -> 64
		Path f = tmp.resolve("messy.mid");
		MidiSystem.write(seq, 1, f.toFile());
		return f;
	}

	private List<long[]> notes(int track) {
		JsonObject r = ok("reference_notes", obj("from_measure", 1, "to_measure", 50, "tracks", ints(track), "max", 1000));
		List<long[]> l = new ArrayList<>();
		for (JsonElement e : r.getAsJsonArray("notes")) {
			JsonObject n = e.getAsJsonObject();
			l.add(new long[] { n.get("tick").getAsLong(), n.get("len").getAsLong(), n.get("pitch").getAsLong() });
		}
		return l;
	}

	// ------------------------------------------------------------------ regrid

	@Test
	public void regridPlacesWallClockOnFixedGrid() throws Exception {
		Path f = variableTempoMidi();
		JsonObject load = ok("reference_load", obj("path", f.toString()));
		assertFalse(load.getAsJsonObject("derived").get("regridded").getAsBoolean());
		assertEquals(3, load.getAsJsonArray("tempos").size());
		// own grid before: G4 at 2:2:0 = tick96 480, C5 at 3:2:0 = 864
		assertEquals(480, notes(2).get(2)[0]);

		JsonObject r = ok("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0));
		assertTrue(r.get("regridded").getAsBoolean());
		assertEquals(7, r.get("notes_written").getAsInt());
		assertEquals(3, r.get("source_tempo_events").getAsInt());
		// fixed 120 BPM: tick = sec * 192
		List<long[]> p = notes(2);
		assertEquals(4, p.size());
		assertEquals(0, p.get(0)[0]);
		assertEquals(96, p.get(1)[0]);
		assertEquals(96, p.get(1)[1]);
		assertEquals(576, p.get(2)[0]);   // 3.0 s
		assertEquals(192, p.get(2)[1]);   // 1 s long at 60 BPM
		// 6.667 s = 1280, 2/3 s = 128 ticks; reading the SMF back applies MabiIcco's 6-tick import rounding
		assertEquals(1278, p.get(3)[0]);
		assertEquals(132, p.get(3)[1]);
		List<long[]> d = notes(3);
		assertEquals(384, d.get(1)[0]);   // 2.0 s
		assertEquals(1152, d.get(2)[0]);  // 6.0 s

		JsonObject info = ok("reference_info", null);
		JsonObject der = info.getAsJsonObject("derived");
		assertTrue(der.get("regridded").getAsBoolean());
		assertEquals(120.0, der.getAsJsonObject("regrid").getAsJsonObject("params").get("bpm").getAsDouble(), 0);
		assertEquals(3, der.getAsJsonObject("raw").get("tempo_events").getAsInt());
		assertEquals(1, info.getAsJsonArray("tempos").size());
		assertEquals(120.0, info.getAsJsonArray("tempos").get(0).getAsJsonObject().get("bpm").getAsDouble(), 0.01);
		// program change and track name survive
		JsonObject t2 = info.getAsJsonArray("tracks").get(1).getAsJsonObject();
		assertEquals("Piano", t2.get("name").getAsString());
		assertEquals(1, t2.get("mabi_import_program").getAsInt());

		JsonObject st = r.getAsJsonObject("stats");
		JsonObject bl = st.getAsJsonObject("barline_wander");
		assertEquals(0, bl.get("wander_after").getAsInt());
		assertTrue(bl.getAsJsonObject("wander_before").get("max").getAsDouble() > 100);
		assertEquals(3, st.getAsJsonArray("tracks").size() + 1); // conductor has no notes
		JsonObject on = st.getAsJsonObject("onset_offset_from_16th");
		assertEquals(85.7, on.get("within_6_ticks_pct_after").getAsDouble(), 0.01); // C5 at 1280 = 8 ticks off

		// reset brings the raw reference back
		JsonObject back = ok("reference_regrid", obj("reset", true));
		assertTrue(back.get("was_regridded").getAsBoolean());
		assertEquals(480, notes(2).get(2)[0]);
		assertFalse(ok("reference_info", null).getAsJsonObject("derived").get("regridded").getAsBoolean());
	}

	@Test
	public void regridOffsetMeasureOffsetAndDroppedPickup() throws Exception {
		ok("reference_load", obj("path", variableTempoMidi().toString()));
		// audio = midi + 0.5 s; measure 2 starts at audio 1.5 s -> tick0 384
		JsonObject r = ok("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 1.5, "measure_offset", 2,
				"offset_sec", 0.5));
		assertEquals(0, r.get("dropped_before_start").getAsInt());
		List<long[]> p = notes(2);
		assertEquals(192, p.get(0)[0]);              // audio 0.5 s = 1 s before 1.5 s -> 384 - 192
		assertEquals(384 + 192 * 2, p.get(2)[0]);    // audio 3.5 s
		// a later downbeat drops the first notes (more than 6 ticks before tick 0)
		JsonObject r2 = ok("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.75));
		assertEquals(3, r2.get("dropped_before_start").getAsInt()); // C4 0.0 s, E4 0.5 s, kick 0.0 s
		assertEquals(2, notes(2).size());
	}

	@Test
	public void regridArgumentErrors() throws Exception {
		err("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0), "NO_REFERENCE");
		ok("reference_load", obj("path", variableTempoMidi().toString()));
		err("reference_regrid", obj("bpm", "120", "first_downbeat_sec", 0.0), "BAD_ARGUMENT");
		err("reference_regrid", obj("first_downbeat_sec", 0.0), "BAD_ARGUMENT");
		err("reference_regrid", obj("bpm", 10, "first_downbeat_sec", 0.0), "BAD_ARGUMENT");
		err("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0, "time_signature", "4/5"), "BAD_ARGUMENT");
		err("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0, "source", "beats"), "BAD_ARGUMENT");
		err("reference_regrid", obj("reset", true, "bpm", 120), "BAD_ARGUMENT");
		err("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0, "tempo", 120), "BAD_ARGUMENT");
		ok("reference_regrid", obj("bpm", 100, "first_downbeat_sec", 0.0, "time_signature", "3/4"));
		JsonObject info = ok("reference_info", null);
		assertEquals(3, info.getAsJsonArray("time_signatures").get(0).getAsJsonObject().get("numerator").getAsInt());
	}

	// ------------------------------------------------------------------ cleanup

	@Test
	public void cleanupStepsAndCounts() throws Exception {
		ok("reference_load", obj("path", messyMidi().toString()));
		JsonObject dry = ok("reference_cleanup", obj("track", 2, "quantize", 16, "dedupe_ticks", 24, "min_length_ticks", 30,
				"fold_to_range", ints(48, 72), "max_polyphony", 2, "dry_run", true));
		assertFalse(dry.get("applied").getAsBoolean());
		assertEquals(10, notes(2).size()); // unchanged
		JsonObject c = dry.getAsJsonObject("counts");
		assertEquals(10, c.get("notes_before").getAsInt());
		assertEquals(1, c.get("duplicates_merged").getAsInt());   // the two C4
		assertEquals(2, c.get("folded").getAsInt());              // E5 -> E4, 100 -> 64
		assertEquals(1, c.get("overlaps_trimmed").getAsInt());    // E4 96..312 cut at the folded E4 (192)
		assertEquals(2, c.get("polyphony_dropped").getAsInt());   // 4-note chord -> top 2
		assertEquals(1, c.get("too_short_dropped").getAsInt());   // A#4, 24 ticks
		assertEquals(6, c.get("notes_after").getAsInt());
		assertEquals(4, dry.get("max_simultaneous_before").getAsInt());
		assertEquals(2, dry.get("max_simultaneous_after").getAsInt());

		JsonObject app = ok("reference_cleanup", obj("track", 2, "quantize", 16, "dedupe_ticks", 24,
				"min_length_ticks", 30, "fold_to_range", ints(48, 72), "max_polyphony", 2));
		assertTrue(app.get("applied").getAsBoolean());
		List<long[]> n = notes(2);
		assertEquals(6, n.size());
		for (long[] x : n) {
			assertEquals(0, x[0] % 24);
			assertTrue(x[2] >= 48 && x[2] <= 72);
		}
		assertEquals(0, n.get(0)[0]);
		assertEquals(96, n.get(0)[1]);
		assertEquals(60, n.get(0)[2]);
		JsonObject der = app.getAsJsonObject("derived");
		assertEquals(1, der.getAsJsonArray("cleanups").size());
		assertFalse(der.get("regridded").getAsBoolean());
		// regrid starts again from the raw file and discards the cleanup
		JsonObject r = ok("reference_regrid", obj("bpm", 120, "first_downbeat_sec", 0.0));
		assertEquals(1, r.get("discarded_cleanups").getAsInt());
		assertEquals(10, notes(2).size());
	}

	@Test
	public void cleanupTopLine() throws Exception {
		ok("reference_load", obj("path", messyMidi().toString()));
		JsonObject r = ok("reference_cleanup", obj("track", 2, "quantize", 16, "top_line", true));
		assertEquals(1, r.get("max_simultaneous_after").getAsInt());
		List<long[]> n = notes(2);
		long[] at480 = n.stream().filter(x -> x[0] == 480).findFirst().orElseThrow();
		assertEquals(59, at480[2]); // highest of the chord
		assertEquals(3, r.getAsJsonObject("counts").get("top_line_dropped").getAsInt());
	}

	@Test
	public void cleanupArgumentErrors() throws Exception {
		ok("reference_load", obj("path", messyMidi().toString()));
		err("reference_cleanup", obj("track", 9), "BAD_ARGUMENT");
		err("reference_cleanup", obj("track", 2, "quantize", 24), "BAD_ARGUMENT");
		err("reference_cleanup", obj("track", 2, "top_line", true, "max_polyphony", 2), "BAD_ARGUMENT");
		err("reference_cleanup", obj("track", 2, "fold_to_range", ints(60, 65)), "BAD_ARGUMENT");
		err("reference_cleanup", obj("track", "2"), "BAD_ARGUMENT");
		err("reference_cleanup", obj("track", 2, "dedupe", 3), "BAD_ARGUMENT");
	}
}
