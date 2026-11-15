package mabibridge.mcp;

import static mabibridge.mcp.McpTestCalls.arr;
import static mabibridge.mcp.McpTestCalls.note;
import static mabibridge.mcp.McpTestCalls.obj;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 0.7.0 fixes: default song rule of voice / microphone tracks, chorus-only song_program, silent flags of microphone
 * notes outside the sounding window, "exactly at the limit = one sheet", project_new tempo kept in the saved file.
 */
public class SongSlotRulesToolsTest {

	private McpTestCalls m;

	@Before
	public void setUp() throws Exception {
		m = new McpTestCalls("songslot", false);
	}

	private static final int[] MIC_PITCHES = { 48, 52, 55, 50, 53, 57, 47, 45, 43, 41 };
	private static final int[] LUTE_PITCHES = { 60, 64, 67, 72, 62, 65, 69, 71, 59, 55 };

	/** Microphone (121) and voice (90) tracks default to song_included, other tracks keep song_excluded; explicit wins. */
	@Test
	public void voiceTracksDefaultToSongIncluded() {
		m.ok("project_new", obj("title", "rule"));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.ok("track_add", obj("name", "voice", "program", 90));
		m.ok("track_add", obj("name", "chorus", "program", 110));
		m.put(1, "song", note(0, 96, 48));
		m.put(2, "melody", note(0, 96, 60));
		JsonArray tracks = m.ok("score_stats", null).getAsJsonArray("tracks");
		assertEquals("song_included", tracks.get(0).getAsJsonObject().getAsJsonObject("rank_limits").get("song_rule").getAsString());
		assertEquals("song_included", tracks.get(0).getAsJsonObject().get("default_rule").getAsString());
		assertEquals("song_excluded", tracks.get(1).getAsJsonObject().getAsJsonObject("rank_limits").get("song_rule").getAsString());
		assertEquals("song_included", tracks.get(2).getAsJsonObject().get("default_rule").getAsString());
		assertEquals("song_included", tracks.get(3).getAsJsonObject().get("default_rule").getAsString());
		assertEquals("song_included", m.ok("track_list", null).getAsJsonArray("tracks").get(0).getAsJsonObject()
				.get("song_rule").getAsString());
		// explicit argument wins
		JsonObject ex = m.ok("score_stats", obj("track", 1, "song_rule", "song_excluded"));
		assertEquals("song_excluded", ex.getAsJsonObject("rank_limits").get("song_rule").getAsString());
		// track_set song_rule wins over the voice default
		m.ok("track_set", obj("track", 1, "song_rule", "song_excluded"));
		assertEquals("song_excluded", m.ok("score_stats", obj("track", 1)).getAsJsonObject("rank_limits").get("song_rule")
				.getAsString());
	}

	/** A microphone song slot over the song limit: fits=false and two sheets by default (one sheet only when excluded). */
	@Test
	public void microphoneSongSlotIsJudgedAgainstTheSongLimit() {
		m.ok("project_new", obj("title", "mic"));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.fill(1, "song", 0, 230, MIC_PITCHES); // Practice: song limit 200 (song_included)
		JsonObject st = m.ok("score_stats", obj("track", 1, "rank", "Practice"));
		assertFalse(st.getAsJsonObject("rank_limits").get("fits").getAsBoolean());
		assertEquals(30, st.getAsJsonObject("rank_limits").getAsJsonObject("parts").getAsJsonObject("song").get("over_by")
				.getAsInt());
		JsonObject sp = m.ok("score_split_sheets", obj("track", 1, "rank", "Practice"));
		assertEquals("song_included", sp.get("song_rule").getAsString());
		assertEquals(2, sp.get("sheet_count").getAsInt());
		JsonObject fit = m.ok("score_fit_suggest", obj("track", 1, "rank", "Practice"));
		assertEquals("song_included", fit.get("song_rule").getAsString());
		// the old behaviour on request
		JsonObject old = m.ok("score_split_sheets", obj("track", 1, "rank", "Practice", "song_rule", "song_excluded"));
		assertEquals(1, old.get("sheet_count").getAsInt());
	}

	/** MabiIcco offers only chorus (C) instruments as another track's song instrument. */
	@Test
	public void songProgramMustBeAChorus() {
		m.ok("project_new", obj("title", "sp"));
		for (int bad : new int[] { 120, 121, 90, 80 }) {
			JsonObject e = m.err("track_add", obj("name", "x", "program", 0, "song_program", bad), "BAD_ARGUMENT");
			assertTrue(e.get("message").getAsString(), e.get("message").getAsString().contains("코러스(C)"));
			assertTrue(e.get("message").getAsString(), e.get("message").getAsString().contains("program"));
			JsonArray allowed = e.getAsJsonObject("details").getAsJsonArray("allowed_song_programs");
			assertTrue(allowed.toString(), allowed.contains(new com.google.gson.JsonPrimitive(100)));
			assertTrue(allowed.toString(), allowed.contains(new com.google.gson.JsonPrimitive(110)));
		}
		m.ok("track_add", obj("name", "c", "program", 0, "song_program", 110));
		m.ok("track_add", obj("name", "d", "program", 21, "song_program", 100));
		m.err("track_set", obj("track", 1, "song_program", 121), "BAD_ARGUMENT");
		m.ok("track_set", obj("track", 1, "song_program", -2));
		m.err("track_import_mml", obj("mml", "MML@c,,,d;", "program", 0, "song_program", 120), "BAD_ARGUMENT");
		assertEquals(2, m.ok("track_list", null).getAsJsonArray("tracks").size());
	}

	/** Microphone notes written outside the sounding window are silent (score_notes, score_stats, instruments). */
	@Test
	public void microphoneNotesOutsideTheWindowAreSilent() {
		m.ok("project_new", obj("title", "silent"));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.ok("track_add", obj("name", "bass", "program", 29));
		// 121 sounds for written 24..57; 23 and 58 are silent
		m.put(1, "song", note(0, 96, 23), note(96, 96, 24), note(192, 96, 57), note(288, 96, 58), note(384, 96, 70));
		// bass 29: 14..103 with 56..61 silent
		m.put(2, "melody", note(0, 96, 13), note(96, 96, 14), note(192, 96, 56), note(288, 96, 103), note(384, 96, 104));
		JsonArray mic = m.ok("score_notes", obj("track", 1, "part", "song", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		boolean[] want = { true, false, false, true, true };
		for (int i = 0; i < want.length; i++) {
			assertEquals("mic note " + i, want[i], mic.get(i).getAsJsonObject().get("silent").getAsBoolean());
		}
		JsonArray bass = m.ok("score_notes", obj("track", 2, "part", "melody", "from_measure", 1, "to_measure", 2))
				.getAsJsonArray("notes");
		boolean[] wantBass = { true, false, true, false, true };
		for (int i = 0; i < wantBass.length; i++) {
			assertEquals("bass note " + i, wantBass[i], bass.get(i).getAsJsonObject().get("silent").getAsBoolean());
		}
		assertEquals(3, m.ok("score_stats", obj("track", 1)).get("silent_notes").getAsInt());
		assertEquals(3, m.ok("score_stats", obj("track", 2)).get("silent_notes").getAsInt());
		// a silent write warns
		JsonObject w = m.put(1, "song", note(768, 96, 60));
		assertTrue(w.getAsJsonArray("warnings").toString(), w.getAsJsonArray("warnings").toString().contains("silent_note"));
		// sounding mode refuses a pitch that would land outside the window (no DLS: properties range is open)
		m.err("score_put_notes", obj("track", 1, "part", "song", "pitch_mode", "sounding", "notes",
				arr(note(960, 96, "A6"))), "PITCH_NOT_PRODUCIBLE");
		// the instrument table shows the window
		for (JsonElement e : m.ok("instruments", obj("dls", false, "type", "V")).getAsJsonArray("instruments")) {
			JsonObject i = e.getAsJsonObject();
			if (i.get("program").getAsInt() == 121) {
				JsonObject a = i.getAsJsonObject("sounding").getAsJsonObject("audible_written");
				assertEquals(24, a.get("from").getAsInt());
				assertEquals(57, a.get("to").getAsInt());
				assertEquals(2, i.getAsJsonObject("sounding").getAsJsonArray("silent").size());
			}
		}
	}

	/** Exactly at the limit is one sheet, one character over is two (melody song_excluded and microphone song slot). */
	@Test
	public void exactlyAtTheLimitIsOneSheet() {
		m.ok("project_new", obj("title", "limit"));
		m.ok("track_add", obj("name", "lute", "program", 0));
		// Practice song_excluded melody limit = 200 + 200/3 = 266
		m.fill(1, "melody", 0, 266, LUTE_PITCHES);
		JsonObject st = m.ok("score_stats", obj("track", 1, "rank", "Practice"));
		assertTrue(st.getAsJsonObject("rank_limits").get("fits").getAsBoolean());
		assertEquals(266, st.getAsJsonObject("rank_limits").getAsJsonObject("parts").getAsJsonObject("melody").get("limit")
				.getAsInt());
		JsonObject sp = m.ok("score_split_sheets", obj("track", 1, "rank", "Practice"));
		assertEquals(1, sp.get("sheet_count").getAsInt());
		assertEquals(266, sp.getAsJsonArray("sheets").get(0).getAsJsonObject().getAsJsonObject("chars").get("melody").getAsInt());

		m.ok("project_new", obj("title", "limit2"));
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.fill(1, "melody", 0, 267, LUTE_PITCHES);
		assertFalse(m.ok("score_stats", obj("track", 1, "rank", "Practice")).getAsJsonObject("rank_limits").get("fits")
				.getAsBoolean());
		assertEquals(2, m.ok("score_split_sheets", obj("track", 1, "rank", "Practice")).get("sheet_count").getAsInt());

		m.ok("project_new", obj("title", "limit3"));
		m.ok("track_add", obj("name", "mic", "program", 121));
		m.fill(1, "song", 0, 200, MIC_PITCHES); // Practice song limit 200
		assertTrue(m.ok("score_stats", obj("track", 1, "rank", "Practice")).getAsJsonObject("rank_limits").get("fits")
				.getAsBoolean());
		assertEquals(1, m.ok("score_split_sheets", obj("track", 1, "rank", "Practice")).get("sheet_count").getAsInt());
	}

	/**
	 * project_new: a non-default tempo is written (tempo=0T97, t97) and re-opens; 120 equals the default and is not
	 * written (no event, no t120), and project_info reports the effective tick-0 tempo either way.
	 */
	@Test
	public void projectNewTempoIsSaved() throws Exception {
		for (int bpm : new int[] { 97, 200 }) {
			m.ok("project_new", obj("title", "t" + bpm, "tempo", bpm));
			m.ok("track_add", obj("name", "p", "program", 0));
			m.put(1, "melody", note(0, 96, 60));
			Path f = m.tmp.resolve("tempo_" + bpm + ".mmi");
			m.ok("project_save_as", obj("path", f.toString()));
			String text = Files.readString(f, StandardCharsets.UTF_8);
			assertTrue(text, text.contains("tempo=0T" + bpm));
			assertTrue(text, text.contains("MML@t" + bpm));
			m.ok("project_open", obj("path", f.toString()));
			JsonObject info = m.ok("project_info", null);
			JsonArray tempos = info.getAsJsonArray("tempos");
			assertEquals(info.toString(), 1, tempos.size());
			assertEquals(bpm, tempos.get(0).getAsJsonObject().get("bpm").getAsInt());
			assertEquals(0, tempos.get(0).getAsJsonObject().get("tick").getAsInt());
			assertEquals(bpm, info.get("tempo_at_start").getAsInt());
			assertFalse(info.get("tempo_at_start_default").getAsBoolean());
		}
		for (Integer bpm : new Integer[] { 120, null }) {
			JsonObject a = obj("title", "default");
			if (bpm != null) {
				a.addProperty("tempo", bpm);
			}
			JsonObject created = m.ok("project_new", a);
			assertEquals(120, created.get("tempo_at_start").getAsInt());
			assertTrue(created.get("tempo_at_start_default").getAsBoolean());
			m.ok("track_add", obj("name", "p", "program", 0));
			m.put(1, "melody", note(0, 96, 60));
			Path f = m.tmp.resolve("tempo_default_" + bpm + ".mmi");
			m.ok("project_save_as", obj("path", f.toString()));
			String text = Files.readString(f, StandardCharsets.UTF_8);
			assertTrue(text, text.contains("tempo=\n") || text.contains("tempo=\r\n"));
			assertTrue(text, text.contains("mml-track=MML@c,,;"));
			m.ok("project_open", obj("path", f.toString()));
			JsonObject info = m.ok("project_info", null);
			assertEquals(0, info.getAsJsonArray("tempos").size());
			assertEquals(120, info.get("tempo_at_start").getAsInt());
			assertTrue(info.get("tempo_at_start_default").getAsBoolean());
			// score_finalize uses the effective tempo for its unit and says it is the default
			JsonObject s = m.ok("score_finalize", obj("dry_run", true)).getAsJsonObject("signature");
			assertEquals(120, s.get("bpm").getAsInt());
			assertTrue(s.get("bpm_default").getAsBoolean());
			assertEquals(24, s.get("unit_ticks").getAsInt());
		}
	}
}
