package mabibridge.mcp;

import static mabibridge.mcp.McpTestCalls.arr;
import static mabibridge.mcp.McpTestCalls.note;
import static mabibridge.mcp.McpTestCalls.obj;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.Finalize;
import mabibridge.engine.SoundingPitch;

/**
 * score_finalize: unit and lead-in rules, the shift of notes / tempos / time signatures, signature per instrument kind,
 * skip paths, existing tuning, idempotency, dry_run and end padding. Runs without the Mabinogi DLS (properties ranges
 * plus the bridge's sounding-pitch table).
 */
public class FinalizeToolsTest {

	private McpTestCalls m;

	@Before
	public void setUp() throws Exception {
		m = new McpTestCalls("finalize", false);
	}

	private JsonObject fin(Object... kv) {
		return m.ok("score_finalize", obj(kv));
	}

	private JsonArray notesOf(int track, String part, int from, int to) {
		return m.ok("score_notes", obj("track", track, "part", part, "from_measure", from, "to_measure", to))
				.getAsJsonArray("notes");
	}

	private static JsonObject sigTrack(JsonObject r, int track) {
		return r.getAsJsonObject("signature").getAsJsonArray("tracks").get(track - 1).getAsJsonObject();
	}

	private static List<Integer> ints(JsonArray a) {
		List<Integer> l = new ArrayList<>();
		for (JsonElement e : a) {
			l.add(e.getAsInt());
		}
		return l;
	}

	private static List<String> strs(JsonArray a) {
		List<String> l = new ArrayList<>();
		for (JsonElement e : a) {
			l.add(e.getAsString());
		}
		return l;
	}

	/** One lute track with a note at tick 0 (the simplest score). */
	private void lute(String title, Integer tempo, String sig) {
		JsonObject a = obj("title", title);
		if (tempo != null) {
			a.addProperty("tempo", tempo);
		}
		if (sig != null) {
			a.addProperty("time_signature", sig);
		}
		m.ok("project_new", a);
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.put(1, "melody", note(0, 96, 60), note(96, 96, 64));
	}

	// ------------------------------------------------------------------ unit and lead-in

	@Test
	public void unitFollowsTheTickZeroTempo() {
		assertEquals(12, Finalize.unitTicks(84));
		assertEquals(24, Finalize.unitTicks(85));
		assertEquals(24, Finalize.unitTicks(169));
		assertEquals(48, Finalize.unitTicks(170));
		int[][] cases = { { 84, 12, 1 }, { 85, 24, 1 }, { 169, 24, 1 }, { 170, 48, 2 } };
		for (int[] c : cases) {
			lute("u" + c[0], c[0], "4/4");
			JsonObject r = fin("end_padding", false);
			JsonObject s = r.getAsJsonObject("signature");
			assertEquals("tempo " + c[0], c[1], s.get("unit_ticks").getAsInt());
			assertEquals("tempo " + c[0], c[2], s.get("measures_inserted").getAsInt());
			assertEquals(c[0], s.get("bpm").getAsInt());
			// passage on the unit grid: 0, u, 2u (len u), 3u (len 5u)
			JsonArray n = notesOf(1, "melody", 1, 1);
			assertEquals(4, n.size());
			for (int i = 0; i < 4; i++) {
				assertEquals(i * c[1], n.get(i).getAsJsonObject().get("tick").getAsInt());
				assertEquals(i == 3 ? 5 * c[1] : c[1], n.get(i).getAsJsonObject().get("len").getAsInt());
				assertEquals(Finalize.DEFAULT_VELOCITY, n.get(i).getAsJsonObject().get("vel").getAsInt());
			}
			// the tick-0 tempo covers the lead-in, and the same tempo also sits at the first original tick
			JsonArray tempos = m.ok("project_info", null).getAsJsonArray("tempos");
			assertEquals(2, tempos.size());
			assertEquals(c[0], tempos.get(0).getAsJsonObject().get("bpm").getAsInt());
			assertEquals(0, tempos.get(0).getAsJsonObject().get("tick").getAsInt());
			assertEquals(c[0], tempos.get(1).getAsJsonObject().get("bpm").getAsInt());
			assertEquals(c[2] * 384, tempos.get(1).getAsJsonObject().get("tick").getAsInt());
			JsonObject lt = s.getAsJsonObject("lead_in_tempo");
			assertEquals(c[2] * 384, lt.get("tick").getAsInt());
			assertTrue(lt.toString(), lt.get("chars_total").getAsInt() > 0);
		}
	}

	@Test
	public void leadInMeasuresPerMeter() {
		// meter → measure ticks, measures inserted at tempo 120 (u = 24, needs 8u + 96 = 288 ticks)
		Object[][] cases = { { "4/4", 384, 1 }, { "3/4", 288, 1 }, { "6/8", 288, 1 }, { "2/4", 192, 2 } };
		for (Object[] c : cases) {
			lute("m" + c[0], 120, (String) c[0]);
			JsonObject r = fin("end_padding", false);
			JsonObject s = r.getAsJsonObject("signature");
			int measure = (Integer) c[1];
			int n = (Integer) c[2];
			assertEquals(c[0].toString(), n, s.get("measures_inserted").getAsInt());
			assertEquals(c[0].toString(), n * measure, s.get("shift_ticks").getAsInt());
			assertEquals(c[0], s.get("measure_signature").getAsString());
			JsonArray orig = notesOf(1, "melody", n + 1, n + 1);
			assertEquals(c[0].toString(), (long) n * measure, orig.get(0).getAsJsonObject().get("tick").getAsLong());
			assertEquals(60, orig.get(0).getAsJsonObject().get("pitch").getAsInt());
			assertEquals((n + 1) + ":1:0", orig.get(0).getAsJsonObject().get("pos").getAsString());
		}
		assertEquals(1, Finalize.leadInMeasures(288, 24));
		assertEquals(2, Finalize.leadInMeasures(287, 24));
		assertEquals(2, Finalize.leadInMeasures(96, 12));
		assertEquals(3, Finalize.leadInMeasures(96, 24));
	}

	/** Tempo and time-signature changes later on move with the music; the tick-0 tempo and meter cover the lead-in. */
	@Test
	public void laterTempoAndMeterChangesShift() {
		m.ok("project_new", obj("title", "changes", "tempo", 100));
		m.ok("project_set_time_signatures", obj("time_signatures", arr(obj("measure", 1, "signature", "4/4"),
				obj("measure", 3, "signature", "3/4"))));
		m.ok("project_set_tempos", obj("tempos", arr(obj("tick", 0, "bpm", 100), obj("pos", "2:1:0", "bpm", 140))));
		m.ok("track_add", obj("name", "lute", "program", 0));
		// off the 6-grid on purpose: an 8th-triplet note at 2:1:32 (tick 416) keeps its exact offset
		m.put(1, "melody", note(0, 96, 60), obj("pos", "2:1:32", "len", 32, "pitch", 62), obj("pos", "3:1:0", "len", 288,
				"pitch", 64));
		JsonObject r = fin("end_padding", false);
		assertEquals(1, r.getAsJsonObject("signature").get("measures_inserted").getAsInt());
		JsonObject info = m.ok("project_info", null);
		JsonArray tempos = info.getAsJsonArray("tempos");
		assertEquals(3, tempos.size()); // 0: 100 (lead-in), 384: 100 (copy for removal), 768: 140
		assertEquals(0, tempos.get(0).getAsJsonObject().get("tick").getAsInt());
		assertEquals(100, tempos.get(0).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(384, tempos.get(1).getAsJsonObject().get("tick").getAsInt());
		assertEquals(100, tempos.get(1).getAsJsonObject().get("bpm").getAsInt());
		assertEquals(384 + 384, tempos.get(2).getAsJsonObject().get("tick").getAsInt());
		assertEquals("3:1:0", tempos.get(2).getAsJsonObject().get("pos").getAsString());
		JsonArray sigs = info.getAsJsonArray("time_signatures");
		assertEquals(2, sigs.size());
		assertEquals("4/4", sigs.get(0).getAsJsonObject().get("signature").getAsString());
		assertEquals(4, sigs.get(1).getAsJsonObject().get("measure").getAsInt());
		assertEquals(384 * 3, sigs.get(1).getAsJsonObject().get("tick").getAsInt());
		assertEquals("3/4", sigs.get(1).getAsJsonObject().get("signature").getAsString());
		JsonArray n = notesOf(1, "melody", 2, 4);
		assertEquals(384, n.get(0).getAsJsonObject().get("tick").getAsInt());
		assertEquals(416 + 384, n.get(1).getAsJsonObject().get("tick").getAsInt());
		assertEquals(32, n.get(1).getAsJsonObject().get("len").getAsInt());
		assertEquals("4:1:0", n.get(2).getAsJsonObject().get("pos").getAsString());
		assertEquals(288, n.get(2).getAsJsonObject().get("len").getAsInt());
	}

	// ------------------------------------------------------------------ instruments

	/** Written notes (tick, len, pitch, vel) of measure 1 of a slot, as strings for comparison. */
	private List<String> passage(int track, String part) {
		List<String> l = new ArrayList<>();
		for (JsonElement e : notesOf(track, part, 1, 1)) {
			JsonObject n = e.getAsJsonObject();
			l.add(n.get("tick").getAsInt() + "/" + n.get("len").getAsInt() + "/" + n.get("pitch").getAsInt() + "/"
					+ n.get("vel").getAsInt());
		}
		return l;
	}

	private static final String[][] SIX = { { "flute", "5", "melody" }, { "piano", "21", "melody" },
			{ "lute", "0", "melody" }, { "bass", "29", "melody" }, { "drums", "27", "melody" }, { "mic", "121", "song" } };

	/**
	 * The six instruments of the hand-built reference passage (fixture_signature_reference.mmi), each track with notes
	 * in an arbitrary register: score_finalize writes exactly the reference notes. Bass never on a silent written pitch.
	 * The microphone is written three steps lower than the reference (v12 -> v9, shipped velocity_offset.121=-3) and the
	 * drum snares at v15 like the crash (velocity_offset.27=3); pitches and lengths are the reference ones.
	 */
	@Test
	public void signatureEqualsTheReferencePassage() {
		m.ok("project_open", obj("path", Path.of("test-bridge/mabibridge/mcp/fixture_signature_reference.mmi")
				.toAbsolutePath().toString()));
		List<List<String>> reference = new ArrayList<>();
		for (int t = 1; t <= SIX.length; t++) {
			reference.add(passage(t, SIX[t - 1][2]));
			assertEquals(SIX[t - 1][0], 4, reference.get(t - 1).size());
		}
		List<String> micRef = new ArrayList<>();
		for (String n : reference.get(5)) {
			assertTrue(n, n.endsWith("/12"));
			micRef.add(n.substring(0, n.length() - "/12".length()) + "/9");
		}
		reference.set(5, micRef);
		// the drum snares are written at v15 like the crash (shipped velocity_offset.27=3); keys and lengths unchanged
		List<String> drumRef = new ArrayList<>();
		for (String n : reference.get(4)) {
			drumRef.add(n.endsWith("/12") ? n.substring(0, n.length() - "/12".length()) + "/15" : n);
		}
		assertTrue(drumRef.toString(), drumRef.stream().allMatch(n -> n.endsWith("/15")));
		reference.set(4, drumRef);
		m.ok("project_new", obj("title", "six", "tempo", 120));
		for (String[] s : SIX) {
			m.ok("track_add", obj("name", s[0], "program", Integer.parseInt(s[1])));
		}
		m.put(1, "melody", note(0, 192, 62), note(192, 192, 64), note(384, 384, 65));            // flute low
		m.put(2, "melody", note(0, 384, 96), note(384, 384, 93));                                 // piano very high
		m.put(2, "chord1", note(0, 384, 88));
		m.put(3, "melody", note(0, 192, 40), note(192, 192, 43), note(384, 288, 45));             // lute low
		m.put(4, "melody", note(0, 192, 66), note(192, 192, 70), note(384, 192, 64));             // bass alt set
		m.put(5, "melody", note(0, 48, 41), note(96, 48, 45), note(384, 96, 60, 15));
		m.put(6, "song", note(0, 192, 26), note(192, 192, 30), note(384, 336, 28));                // mic low
		JsonObject r = fin();
		assertEquals("added", r.getAsJsonObject("signature").get("status").getAsString());
		for (int t = 1; t <= SIX.length; t++) {
			assertEquals(SIX[t - 1][0], reference.get(t - 1), passage(t, SIX[t - 1][2]));
			assertEquals(SIX[t - 1][2], sigTrack(r, t).get("slot").getAsString());
			if (t != 5) {
				assertEquals("home_table", sigTrack(r, t).get("target_source").getAsString());
			}
		}
		assertEquals(List.of("B5", "D6", "G6", "E6"), strs(sigTrack(r, 1).getAsJsonArray("sounding")));
		assertEquals(List.of("B4", "D5", "G5", "E6"), strs(sigTrack(r, 2).getAsJsonArray("sounding")));
		assertEquals(List.of("B3", "D4", "G4", "E5"), strs(sigTrack(r, 3).getAsJsonArray("sounding")));
		assertEquals(List.of("B1", "D2", "G2", "E3"), strs(sigTrack(r, 4).getAsJsonArray("sounding")));
		assertEquals(List.of("B4", "D5", "G5", "E5"), strs(sigTrack(r, 6).getAsJsonArray("sounding")));
		assertEquals(List.of(9, 9, 9, 9), ints(sigTrack(r, 6).getAsJsonArray("velocities")));
		assertEquals(-3, sigTrack(r, 6).get("velocity_offset").getAsInt());
		for (int t = 1; t <= 4; t++) {
			assertEquals(SIX[t - 1][0], List.of(12, 12, 12, 12), ints(sigTrack(r, t).getAsJsonArray("velocities")));
		}
		for (int w : ints(sigTrack(r, 4).getAsJsonArray("written"))) {
			assertNotEquals("bass written " + w, SoundingPitch.SILENT, SoundingPitch.soundingOf(29, w));
		}
		assertEquals("drums", sigTrack(r, 5).get("kind").getAsString());
		assertEquals(List.of(15, 15, 15, 15), ints(sigTrack(r, 5).getAsJsonArray("velocities")));
		assertEquals(3, sigTrack(r, 5).get("velocity_offset").getAsInt());
		// the bass and microphone MML of the passage is the reference text (tempo 120 is the default: no t120)
		JsonArray mml = m.ok("score_mml", null).getAsJsonArray("tracks");
		assertTrue(mml.get(3).toString(), mml.get(3).getAsJsonObject().get("mml").getAsString().startsWith("MML@v12l16o0b>dg>e4&e"));
		assertTrue(mml.get(5).toString(), mml.get(5).getAsJsonObject().getAsJsonObject("parts").get("song").getAsString()
				.startsWith("v9l16<c-dge4&e"));
	}

	/** The male microphone (120) sounds only up to C#5, so its passage is the closest producible one: B3 D4 G4 E4. */
	@Test
	public void maleMicrophoneTakesTheClosestProduciblePassage() {
		m.ok("project_new", obj("title", "mic120"));
		m.ok("track_add", obj("name", "mic", "program", 120));
		JsonObject r = fin("end_padding", false);
		assertEquals("song", sigTrack(r, 1).get("slot").getAsString());
		assertEquals(List.of("B3", "D4", "G4", "E4"), strs(sigTrack(r, 1).getAsJsonArray("sounding")));
		assertEquals(List.of(9, 9, 9, 9), ints(sigTrack(r, 1).getAsJsonArray("velocities"))); // offset -3 as 121
		for (int w : ints(sigTrack(r, 1).getAsJsonArray("written"))) {
			assertNotEquals("written " + w, SoundingPitch.SILENT, SoundingPitch.soundingOf(120, w));
		}
	}

	/** Same instrument, very different registers (or no notes): the same signature pitches in every score. */
	@Test
	public void signatureDependsOnTheInstrumentOnly() {
		int[][] programs = { { 0 }, { 21 }, { 29 }, { 121 }, { 2 }, { 6 } }; // 2 and 6 have no home entry
		for (int[] pr : programs) {
			int prog = pr[0];
			String part = prog == 121 ? "song" : "melody";
			List<List<String>> seen = new ArrayList<>();
			int[][] registers = { {}, { 24, 26, 28 }, { 90, 93, 95 } };
			for (int[] reg : registers) {
				m.ok("project_new", obj("title", "reg"));
				m.ok("track_add", obj("name", "t", "program", prog));
				for (int i = 0; i < reg.length; i++) {
					m.ok("score_put_notes", obj("track", 1, "part", part, "align", true, "notes", arr(note(i * 96L, 96, reg[i]))));
				}
				JsonObject r = fin("end_padding", false);
				assertEquals("program " + prog, "added", sigTrack(r, 1).get("status").getAsString());
				seen.add(strs(sigTrack(r, 1).getAsJsonArray("sounding")));
			}
			assertEquals("program " + prog + " " + seen, seen.get(0), seen.get(1));
			assertEquals("program " + prog + " " + seen, seen.get(0), seen.get(2));
		}
		// an instrument without an entry uses the middle of its producible range (chalumeau 6: 36..71 → 53.5)
		m.ok("project_new", obj("title", "chal"));
		m.ok("track_add", obj("name", "c", "program", 6));
		JsonObject r = fin("end_padding", false);
		assertEquals("range_middle", sigTrack(r, 1).get("target_source").getAsString());
		assertEquals(53.5, sigTrack(r, 1).get("target_pitch").getAsDouble(), 0.0);
		assertEquals(List.of("B2", "D3", "G3", "E4"), strs(sigTrack(r, 1).getAsJsonArray("sounding")));
	}

	/** The flute's home octave cannot take the upper E (E7 is above its range), so the E a minor third below. */
	@Test
	public void rangeForcesTheLowerE() {
		m.ok("project_new", obj("title", "flute"));
		m.ok("track_add", obj("name", "flute", "program", 5));
		JsonObject r = fin("end_padding", false);
		assertEquals(List.of("B5", "D6", "G6", "E6"), strs(sigTrack(r, 1).getAsJsonArray("sounding")));
		assertEquals(List.of(83, 86, 91, 88), ints(sigTrack(r, 1).getAsJsonArray("written")));
	}

	/** Markers move with the music (the lead-in is a pure shift). */
	@Test
	public void markerIsShifted() throws Exception {
		Path f = m.tmp.resolve("marker.mmi");
		Files.writeString(f, "[mml-score]\nversion=1\ntitle=marker\nauthor=\ntime=3/4\ntempo=0T100\n"
				+ "mml-track=MML@t100cde,,;\nname=lute\nprogram=0\nsongProgram=-1\npanpot=64\nvisible=true\n"
				+ "[marker]\n0=intro\n192=verse\n", java.nio.charset.StandardCharsets.UTF_8);
		m.ok("project_open", obj("path", f.toString()));
		JsonObject r = fin("end_padding", false);
		assertEquals(1, r.getAsJsonObject("signature").get("measures_inserted").getAsInt());
		assertEquals(288, r.getAsJsonObject("signature").get("shift_ticks").getAsInt());
		Path out = m.tmp.resolve("marker_after.mmi");
		m.ok("project_save_as", obj("path", out.toString()));
		String text = Files.readString(out, java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(text, text.contains("[marker]\n288=intro\n480=verse") || text.contains("[marker]\r\n288=intro\r\n480=verse"));
		assertTrue(text, text.contains("tempo=0T100"));
	}

	/** A track with a start offset (MabiIcco's start position): no lead-in, the reason names the track. */
	@Test
	public void startOffsetBlocksTheSignature() throws Exception {
		Path f = m.tmp.resolve("offset.mmi");
		Files.writeString(f, "[mml-score]\nversion=1\ntitle=offset\nauthor=\ntime=4/4\ntempo=\nstartOffset=96\n"
				+ "mml-track=MML@cde,,;\nname=lute\nprogram=0\nsongProgram=-1\npanpot=64\nvisible=true\n",
				java.nio.charset.StandardCharsets.UTF_8);
		m.ok("project_open", obj("path", f.toString()));
		Path before = m.tmp.resolve("offset_before.mmi");
		m.ok("project_save_as", obj("path", before.toString()));
		JsonObject r = fin("end_padding", false);
		JsonObject s = r.getAsJsonObject("signature");
		assertEquals("skipped", s.get("status").getAsString());
		assertTrue(s.get("reason").getAsString(), s.get("reason").getAsString().contains("트랙 1"));
		assertTrue(s.get("reason").getAsString(), s.get("reason").getAsString().contains("startOffset 96"));
		assertFalse(r.get("changed").getAsBoolean());
		Path after = m.tmp.resolve("offset_after.mmi");
		m.ok("project_save_as", obj("path", after.toString()));
		assertArrayEquals(Files.readAllBytes(before), Files.readAllBytes(after));
	}

	/** Unpitched or fixed-pitch instruments are skipped with a reason; the others still get the passage. */
	@Test
	public void fixedPitchInstrumentIsSkipped() {
		m.ok("project_new", obj("title", "bottle"));
		m.ok("track_add", obj("name", "bottle", "program", 7));
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.put(1, "melody", note(0, 96, 60));
		m.put(2, "melody", note(0, 96, 60));
		JsonObject r = fin("end_padding", false);
		assertEquals("partial", r.getAsJsonObject("signature").get("status").getAsString());
		assertEquals("skipped", sigTrack(r, 1).get("status").getAsString());
		assertTrue(sigTrack(r, 1).get("reason").getAsString().contains("음높이"));
		assertEquals("added", sigTrack(r, 2).get("status").getAsString());
	}

	// ------------------------------------------------------------------ character budget

	private static final int[] LUTE = { 60, 64, 67, 72, 62, 65, 69, 71, 59, 55 };

	/** Practice rank, song_included: melody 200, chord1 100, chord2 0. A full lute is skipped, the empty piano gets it. */
	@Test
	public void trackWithoutRoomIsSkipped() {
		m.ok("project_new", obj("title", "room"));
		m.ok("track_add", obj("name", "full", "program", 0));
		m.ok("track_add", obj("name", "piano", "program", 21));
		m.fill(1, "melody", 0, 194, LUTE);
		m.fill(1, "chord1", 0, 94, LUTE);
		m.put(2, "melody", note(0, 96, 60));
		JsonObject r = fin("rank", "Practice", "song_rule", "song_included", "end_padding", false);
		JsonObject s = r.getAsJsonObject("signature");
		assertEquals(s.toString(), "partial", s.get("status").getAsString());
		assertEquals("skipped", sigTrack(r, 1).get("status").getAsString());
		assertTrue(sigTrack(r, 1).get("reason").getAsString(), sigTrack(r, 1).get("reason").getAsString().contains("자리"));
		assertEquals("added", sigTrack(r, 2).get("status").getAsString());
		// the lead-in still moved the full track: its first note is in measure 2 and every slot still fits
		assertEquals(384, notesOf(1, "melody", 2, 2).get(0).getAsJsonObject().get("tick").getAsInt());
		JsonObject c1 = r.getAsJsonArray("chars").get(0).getAsJsonObject();
		assertTrue(c1.toString(), c1.get("fits_after").getAsBoolean());
		assertTrue(c1.getAsJsonObject("added").get("melody").getAsInt() > 0);
	}

	/** A slot exactly at its limit cannot take the lead-in: no signature at all, the score is unchanged. */
	@Test
	public void leadInThatDoesNotFitAddsNothing() throws Exception {
		m.ok("project_new", obj("title", "tight"));
		m.ok("track_add", obj("name", "full", "program", 0));
		m.ok("track_add", obj("name", "piano", "program", 21));
		m.fill(1, "melody", 0, 200, LUTE);
		m.put(2, "melody", note(0, 96, 60));
		Path before = m.tmp.resolve("tight_before.mmi");
		m.ok("project_save_as", obj("path", before.toString()));
		JsonObject r = fin("rank", "Practice", "song_rule", "song_included", "end_padding", false);
		JsonObject s = r.getAsJsonObject("signature");
		assertEquals("skipped", s.get("status").getAsString());
		JsonObject over = s.getAsJsonArray("lead_in_over").get(0).getAsJsonObject();
		assertEquals(1, over.get("track").getAsInt());
		assertEquals("melody", over.get("slot").getAsString());
		assertTrue(over.get("over_by").getAsInt() > 0);
		assertTrue(s.get("reason").getAsString().contains("트랙 1 melody"));
		assertFalse(r.get("changed").getAsBoolean());
		Path after = m.tmp.resolve("tight_after.mmi");
		m.ok("project_save_as", obj("path", after.toString()));
		assertArrayEquals(Files.readAllBytes(before), Files.readAllBytes(after));
	}

	// ------------------------------------------------------------------ existing passages, idempotency, dry_run

	@Test
	public void existingTuningNote() {
		m.ok("project_new", obj("title", "tuning"));
		m.ok("track_import_mml", obj("mml", "MML@a16&a16&a16&a16r4.c2;", "program", 0));
		JsonObject r = fin("end_padding", false);
		assertEquals("existing_tuning", r.getAsJsonObject("signature").get("status").getAsString());
		assertEquals(1, r.getAsJsonObject("signature").getAsJsonArray("existing").size());
		assertFalse(r.get("changed").getAsBoolean());
		JsonObject p = fin("end_padding", false, "on_existing_tuning", "prepend");
		assertEquals("added", p.getAsJsonObject("signature").get("status").getAsString());
		String mml = m.ok("score_mml", obj("track", 1)).get("mml").getAsString();
		assertTrue(mml, mml.contains("a&a&a&a")); // the tuning note keeps its tie chain (l16 in effect)
		JsonArray moved = notesOf(1, "melody", 2, 2);
		assertEquals(384, moved.get(0).getAsJsonObject().get("tick").getAsInt());
		m.err("score_finalize", obj("on_existing_tuning", "replace"), "BAD_ARGUMENT");
		m.err("score_finalize", obj("velocity", 0), "BAD_ARGUMENT");
	}

	@Test
	public void secondCallAddsNothing() throws Exception {
		lute("twice", 120, "4/4");
		m.ok("track_add", obj("name", "piano", "program", 21));
		m.put(2, "melody", note(0, 384, 60));
		JsonObject first = fin();
		assertTrue(first.get("changed").getAsBoolean());
		Path a = m.tmp.resolve("twice_1.mmi");
		m.ok("project_save_as", obj("path", a.toString()));
		JsonObject second = fin();
		assertEquals("already_present", second.getAsJsonObject("signature").get("status").getAsString());
		assertEquals("already_present", second.getAsJsonObject("padding").get("status").getAsString());
		assertEquals("already_present", second.getAsJsonObject("padding").getAsJsonArray("tracks").get(0).getAsJsonObject()
				.get("status").getAsString());
		assertFalse(second.get("changed").getAsBoolean());
		Path b = m.tmp.resolve("twice_2.mmi");
		m.ok("project_save_as", obj("path", b.toString()));
		assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
		// also after save and re-open
		m.ok("project_open", obj("path", a.toString()));
		assertFalse(fin().get("changed").getAsBoolean());
	}

	@Test
	public void dryRunLeavesTheProjectByteIdentical() throws Exception {
		lute("dry", 96, "3/4");
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.put(2, "song", note(0, 96, 48));
		Path a = m.tmp.resolve("dry_before.mmi");
		m.ok("project_save_as", obj("path", a.toString()));
		boolean dirty = m.ok("project_info", null).get("dirty").getAsBoolean();
		JsonObject d = fin("dry_run", true);
		assertTrue(d.get("dry_run").getAsBoolean());
		assertFalse("dry_run changes nothing", d.get("changed").getAsBoolean());
		assertTrue("but a real call would", d.get("would_change").getAsBoolean());
		assertEquals("added", d.getAsJsonObject("signature").get("status").getAsString());
		assertTrue(d.get("snapshot_id").isJsonNull());
		assertEquals(dirty, m.ok("project_info", null).get("dirty").getAsBoolean());
		Path b = m.tmp.resolve("dry_after.mmi");
		m.ok("project_save_as", obj("path", b.toString()));
		assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
		// the real call reports the same
		JsonObject real = fin();
		assertEquals(d.getAsJsonObject("signature").toString(), real.getAsJsonObject("signature").toString());
		assertEquals(d.getAsJsonObject("padding").toString(), real.getAsJsonObject("padding").toString());
		assertEquals(d.getAsJsonArray("chars").toString(), real.getAsJsonArray("chars").toString());
		assertFalse(real.get("snapshot_id").isJsonNull());
		assertTrue(real.get("changed").getAsBoolean());
		assertTrue(real.get("would_change").getAsBoolean());
	}

	/** A call that changes nothing (second call, or dry_run on a finished score) writes no snapshot. */
	@Test
	public void unchangedCallWritesNoSnapshot() {
		lute("snap", 120, "4/4");
		m.ok("track_add", obj("name", "piano", "program", 21));
		m.put(2, "melody", note(0, 384, 60));
		JsonObject first = fin();
		assertTrue(first.get("changed").getAsBoolean());
		assertFalse(first.get("snapshot_id").isJsonNull());
		int snapshots = m.ok("snapshot_list", null).getAsJsonArray("snapshots").size();
		JsonObject second = fin();
		assertFalse(second.get("changed").getAsBoolean());
		assertFalse(second.get("would_change").getAsBoolean());
		assertTrue(second.get("snapshot_id").isJsonNull());
		JsonObject dry = fin("dry_run", true);
		assertFalse(dry.get("changed").getAsBoolean());
		assertFalse(dry.get("would_change").getAsBoolean());
		assertEquals("no new snapshot", snapshots, m.ok("snapshot_list", null).getAsJsonArray("snapshots").size());
		// the snapshot of the first call holds the state before it: restoring removes the lead-in again
		m.ok("restore", obj("id", first.get("snapshot_id").getAsInt()));
		assertTrue(fin("dry_run", true).get("would_change").getAsBoolean());
	}

	// ------------------------------------------------------------------ end padding

	@Test
	public void endPaddingCases() throws Exception {
		m.ok("project_new", obj("title", "pad", "tempo", 120));
		m.ok("track_add", obj("name", "long", "program", 0));     // ends last (T_end = 768)
		m.ok("track_add", obj("name", "short", "program", 0));    // ends at 384: padded
		m.ok("track_add", obj("name", "near", "program", 0));     // ends at 762: within 6 ticks, not needed
		m.ok("track_add", obj("name", "empty", "program", 0));    // no notes
		m.ok("track_add", obj("name", "mic", "program", 121));    // song slot
		m.ok("track_add", obj("name", "tight", "program", 0));    // ends at 756: rest before the pad would be 6 → normal
		m.ok("track_add", obj("name", "odd", "program", 0));      // ends at 750+... see below
		m.put(1, "melody", note(0, 768, 60));
		m.put(2, "melody", note(0, 192, 60, 12), note(192, 192, 64, 9));
		m.put(2, "chord1", note(0, 384, 55, 7));
		m.put(3, "melody", note(0, 762, 62));
		m.put(5, "song", note(0, 384, 48));
		m.put(6, "melody", note(0, 756, 64));
		m.put(7, "melody", note(0, 744, 65), note(744, 12, 67)); // ends at 756 too
		JsonArray before2 = notesOf(2, "melody", 1, 2);
		JsonObject r = fin("signature", false);
		JsonObject pd = r.getAsJsonObject("padding");
		assertEquals("done", pd.get("status").getAsString());
		assertEquals(768, pd.get("end_tick").getAsInt());
		JsonArray t = pd.getAsJsonArray("tracks");
		assertEquals("not_needed", t.get(0).getAsJsonObject().get("status").getAsString());
		JsonObject p2 = t.get(1).getAsJsonObject();
		assertEquals("added", p2.get("status").getAsString());
		assertEquals(762, p2.get("tick").getAsInt());
		assertEquals(6, p2.get("len").getAsInt());
		assertEquals(64, p2.get("pitch").getAsInt()); // the note that ends last
		// cheapest slot: chord1's cost is not larger than melody's (both in cost_by_slot, the minimum is chosen)
		JsonObject cost = p2.getAsJsonObject("cost_by_slot");
		int min = Integer.MAX_VALUE;
		for (String k : cost.keySet()) {
			min = Math.min(min, cost.get(k).getAsInt());
		}
		assertEquals(min, cost.get(p2.get("slot").getAsString()).getAsInt());
		assertEquals(min, p2.get("chars_added").getAsInt());
		assertEquals("not_needed", t.get(2).getAsJsonObject().get("status").getAsString());
		assertEquals("no_notes", t.get(3).getAsJsonObject().get("status").getAsString());
		assertEquals("song", t.get(4).getAsJsonObject().get("slot").getAsString());
		assertEquals("added", t.get(5).getAsJsonObject().get("status").getAsString());
		assertEquals(762, t.get(5).getAsJsonObject().get("tick").getAsInt());
		// the velocity-0 note exists, the other velocities are unchanged
		String slot2 = p2.get("slot").getAsString();
		JsonArray after2 = notesOf(2, slot2, 1, 2);
		JsonObject pad = after2.get(after2.size() - 1).getAsJsonObject();
		assertEquals(0, pad.get("vel").getAsInt());
		assertEquals(762, pad.get("tick").getAsInt());
		if ("melody".equals(slot2)) {
			for (int i = 0; i < before2.size(); i++) {
				assertEquals(before2.get(i).toString(), after2.get(i).toString());
			}
		} else {
			assertEquals(before2.toString(), notesOf(2, "melody", 1, 2).toString());
			assertEquals(7, notesOf(2, "chord1", 1, 2).get(0).getAsJsonObject().get("vel").getAsInt());
		}
		// survives MML, save, re-open and score_split_sheets
		String mml = m.ok("score_mml", obj("track", 2)).get("mml").getAsString();
		assertTrue(mml, mml.contains("v0"));
		Path f = m.tmp.resolve("pad.mmi");
		m.ok("project_save_as", obj("path", f.toString()));
		m.ok("project_open", obj("path", f.toString()));
		JsonArray reopened = notesOf(2, slot2, 1, 2);
		assertEquals(0, reopened.get(reopened.size() - 1).getAsJsonObject().get("vel").getAsInt());
		assertEquals(762, reopened.get(reopened.size() - 1).getAsJsonObject().get("tick").getAsInt());
		JsonObject sheets = m.ok("score_split_sheets", obj("track", 2));
		String sheetMml = sheets.getAsJsonArray("sheets").get(0).getAsJsonObject().get("mml").getAsString();
		assertTrue(sheetMml, sheetMml.contains("v0"));
		// a second call adds nothing
		assertEquals("already_present", fin("signature", false).getAsJsonObject("padding").get("status").getAsString());
	}

	/** A rest shorter than 6 ticks cannot be written: the silent note then starts right after the last note. */
	@Test
	public void endPaddingShortRest() {
		m.ok("project_new", obj("title", "short rest"));
		m.ok("track_add", obj("name", "a", "program", 0));
		m.ok("track_add", obj("name", "b", "program", 0));
		m.ok("track_add", obj("name", "c", "program", 0));
		// a ends on the triplet grid at 784 (3:1:16); b ends at 774, 10 ticks early: a 4-tick rest + 6 cannot be written
		m.put(1, "melody", note(0, 784, 60));
		m.put(2, "melody", note(0, 774, 62));
		// c ends at 768: the silent note starts at 778 (off the 6-grid, after a 10-tick rest), exactly 6 long
		m.put(3, "melody", note(0, 768, 64));
		JsonArray t = fin("signature", false).getAsJsonObject("padding").getAsJsonArray("tracks");
		JsonObject p = t.get(1).getAsJsonObject();
		assertEquals("added", p.get("status").getAsString());
		assertEquals(774, p.get("tick").getAsInt());
		assertEquals(10, p.get("len").getAsInt());
		JsonObject q = t.get(2).getAsJsonObject();
		assertEquals("added", q.get("status").getAsString());
		assertEquals(778, q.get("tick").getAsInt());
		assertEquals(6, q.get("len").getAsInt());
		for (int tr = 2; tr <= 3; tr++) {
			JsonArray n = notesOf(tr, "melody", 1, 3);
			assertEquals(0, n.get(1).getAsJsonObject().get("vel").getAsInt());
			assertEquals(784, n.get(1).getAsJsonObject().get("tick").getAsInt() + n.get(1).getAsJsonObject().get("len").getAsInt());
		}
	}

	/** No slot with room: the padding is skipped and reported. */
	@Test
	public void endPaddingWithoutRoomIsSkipped() {
		m.ok("project_new", obj("title", "nopad"));
		m.ok("track_add", obj("name", "long", "program", 0));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.put(1, "melody", note(0, 384 * 40, 60));
		m.fill(2, "song", 0, 200, new int[] { 48, 52, 55, 50, 53, 57, 47, 45, 43, 41 });
		JsonObject p = fin("signature", false, "rank", "Practice").getAsJsonObject("padding").getAsJsonArray("tracks").get(1)
				.getAsJsonObject();
		assertEquals("skipped", p.get("status").getAsString());
		assertTrue(p.get("reason").getAsString(), p.get("reason").getAsString().contains("자리"));
	}

	/** Xylophone 77 (velocity rule x11): v12 on an ordinary instrument is MIDI 96, so the passage is written at v9 (99). */
	@Test
	public void velocityFollowsTheInstrumentScale() {
		assertEquals(9, Finalize.scaledVelocity("x11", 12));
		assertEquals(12, Finalize.scaledVelocity("x8", 12));
		assertEquals(11, Finalize.scaledVelocity("x11", 15));
		assertEquals(1, Finalize.scaledVelocity("x11", 1));
		m.ok("project_new", obj("title", "xylo"));
		m.ok("track_add", obj("name", "xylo", "program", 77));
		m.ok("track_add", obj("name", "lute", "program", 0));
		JsonObject r = fin("end_padding", false);
		assertEquals("added", sigTrack(r, 1).get("status").getAsString());
		assertEquals("x11", sigTrack(r, 1).get("velocity_rule").getAsString());
		assertEquals(List.of(9, 9, 9, 9), ints(sigTrack(r, 1).getAsJsonArray("velocities")));
		for (JsonElement e : notesOf(1, "melody", 1, 1)) {
			assertEquals(9, e.getAsJsonObject().get("vel").getAsInt());
		}
		assertEquals(List.of(12, 12, 12, 12), ints(sigTrack(r, 2).getAsJsonArray("velocities")));
	}

	/** Second call after a skipped padding: "skipped", not "done"; the reason names each slot and its overrun. */
	@Test
	public void secondCallWithSkippedTracksIsNotDone() {
		m.ok("project_new", obj("title", "nopad2"));
		m.ok("track_add", obj("name", "long", "program", 0));
		m.ok("track_add", obj("name", "full", "program", 0));
		m.ok("track_add", obj("name", "short", "program", 0));
		m.put(1, "melody", note(0, 384 * 40, 60));
		m.fill(2, "melody", 0, 266, LUTE);
		m.fill(2, "chord1", 0, 166, LUTE);
		m.fill(2, "chord2", 0, 66, LUTE);
		m.put(3, "melody", note(0, 96, 60));
		JsonObject first = fin("signature", false, "rank", "Practice").getAsJsonObject("padding");
		assertEquals("done", first.get("status").getAsString());
		JsonObject full = first.getAsJsonArray("tracks").get(1).getAsJsonObject();
		assertEquals("skipped", full.get("status").getAsString());
		String why = full.get("reason").getAsString();
		assertTrue(why, why.contains("melody에 넣으면 melody ") && why.contains("/266자(") && why.contains("자 초과)"));
		assertTrue(why, why.contains("chord1에 넣으면") && why.contains("chord2에 넣으면"));
		JsonObject second = fin("signature", false, "rank", "Practice");
		JsonObject pd = second.getAsJsonObject("padding");
		assertEquals("skipped", pd.get("status").getAsString());
		assertEquals("already_present", pd.getAsJsonArray("tracks").get(2).getAsJsonObject().get("status").getAsString());
		assertEquals("skipped", pd.getAsJsonArray("tracks").get(1).getAsJsonObject().get("status").getAsString());
		assertFalse(second.get("changed").getAsBoolean());
		assertFalse(second.get("text").getAsString(), second.get("text").getAsString().contains("새로 넣음"));
	}

	/** An explicit song_excluded on a microphone track warns that its song part cannot be turned off. */
	@Test
	public void explicitSongExcludedOnAMicrophoneWarns() {
		m.ok("project_new", obj("title", "micrule"));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.put(1, "song", note(0, 96, 48));
		m.put(2, "melody", note(0, 96, 60));
		JsonObject st = m.ok("score_stats", obj("track", 1, "song_rule", "song_excluded"));
		assertTrue(st.toString(), st.getAsJsonArray("warnings").toString().contains("song_rule_voice"));
		assertFalse(m.ok("score_stats", obj("track", 1)).getAsJsonArray("warnings").toString().contains("song_rule_voice"));
		assertFalse(m.ok("score_stats", obj("track", 2, "song_rule", "song_excluded")).getAsJsonArray("warnings").toString()
				.contains("song_rule_voice"));
		assertTrue(m.ok("score_split_sheets", obj("track", 1, "song_rule", "song_excluded")).getAsJsonArray("warnings")
				.toString().contains("song_rule_voice"));
		assertTrue(m.ok("score_fit_suggest", obj("track", 1, "song_rule", "song_excluded")).getAsJsonArray("warnings")
				.toString().contains("song_rule_voice"));
		assertTrue(fin("song_rule", "song_excluded", "dry_run", true).getAsJsonArray("warnings").toString()
				.contains("song_rule_voice"));
	}

	/** A signature skipped for an existing tuning note still gets the end padding. */
	@Test
	public void paddingStillAppliedWhenTheSignatureIsSkipped() {
		m.ok("project_new", obj("title", "tuning pad"));
		m.ok("track_import_mml", obj("mml", "MML@a16&a16&a16&a16r4.c1;", "program", 0));
		m.ok("track_add", obj("name", "short", "program", 0));
		m.put(2, "melody", note(0, 96, 60));
		JsonObject r = fin();
		assertEquals("existing_tuning", r.getAsJsonObject("signature").get("status").getAsString());
		assertEquals("done", r.getAsJsonObject("padding").get("status").getAsString());
		assertEquals("added", r.getAsJsonObject("padding").getAsJsonArray("tracks").get(1).getAsJsonObject().get("status")
				.getAsString());
	}

	@Test
	public void toolIsListedAndDescribed() {
		ToolRegistry.Tool t = m.tools.get("score_finalize");
		assertTrue(t.description().contains("마디 번호"));
		assertTrue(t.propertyNames().containsAll(List.of("signature", "end_padding", "velocity", "on_existing_tuning",
				"dry_run", "rank", "song_rule")));
		assertTrue(McpServer.INSTRUCTIONS.contains("score_finalize"));
	}
}
