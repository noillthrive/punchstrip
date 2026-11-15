package mabibridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 메타 JSON이 메타 JSON 형식의 필드를 모두 갖는지 검사한다(시험 도우미). */
final class MetaSchema {

	private MetaSchema() {}

	static final List<String> TOP = List.of("schema", "rendered_at", "engine", "project", "dls_files", "audio", "synth",
			"range", "tempo_map", "time_signatures", "measures", "tracks", "split", "files");
	static final List<String> AUDIO = List.of("sample_rate", "bits", "channels", "duration_sec", "peak_dbfs",
			"tail_sec_actual");
	static final List<String> SYNTH = List.of("class", "midi_channels", "large_mode", "max_polyphony",
			"load_default_soundbank", "interpolation", "control_rate", "reverb", "chorus", "auto_gain_control",
			"light_reverb", "jitter_correction", "latency");
	static final List<String> RANGE = List.of("from_measure", "to_measure", "from_tick", "to_tick", "note_rule",
			"note_start_offset_tick", "track_mute_ignored");
	static final List<String> MEASURE = List.of("measure", "tick", "start_sec", "end_sec", "beats");
	static final List<String> TRACK = List.of("track", "name", "program", "program_name", "panpot", "volume", "parts");
	static final List<String> FILE = List.of("path", "tracks", "parts");

	static void assertComplete(JsonObject m) {
		has(m, TOP, "");
		assertEquals("mabibridge.render/1", m.get("schema").getAsString());
		has(m.getAsJsonObject("project"), List.of("title", "source_path"), "project.");
		has(m.getAsJsonObject("audio"), AUDIO, "audio.");
		assertEquals(44100, m.getAsJsonObject("audio").get("sample_rate").getAsInt());
		assertEquals(16, m.getAsJsonObject("audio").get("bits").getAsInt());
		assertEquals(2, m.getAsJsonObject("audio").get("channels").getAsInt());
		has(m.getAsJsonObject("synth"), SYNTH, "synth.");
		assertEquals(false, m.getAsJsonObject("synth").get("load_default_soundbank").getAsBoolean());
		has(m.getAsJsonObject("range"), RANGE, "range.");
		assertEquals("start_in_range", m.getAsJsonObject("range").get("note_rule").getAsString());
		for (JsonElement e : m.getAsJsonArray("tempo_map")) {
			has(e.getAsJsonObject(), List.of("tick", "bpm"), "tempo_map[].");
		}
		for (JsonElement e : m.getAsJsonArray("time_signatures")) {
			has(e.getAsJsonObject(), List.of("tick", "sig"), "time_signatures[].");
		}
		JsonArray ms = m.getAsJsonArray("measures");
		assertTrue("measures 비어 있음", ms.size() > 0);
		int from = m.getAsJsonObject("range").get("from_measure").getAsInt();
		int to = m.getAsJsonObject("range").get("to_measure").getAsInt();
		assertEquals(to - from + 1, ms.size());
		for (JsonElement e : ms) {
			has(e.getAsJsonObject(), MEASURE, "measures[].");
		}
		assertTrue(m.getAsJsonArray("tracks").size() > 0);
		for (JsonElement e : m.getAsJsonArray("tracks")) {
			has(e.getAsJsonObject(), TRACK, "tracks[].");
			for (var pe : e.getAsJsonObject().getAsJsonObject("parts").entrySet()) {
				has(pe.getValue().getAsJsonObject(), List.of("notes", "channel"), "tracks[].parts." + pe.getKey() + ".");
			}
		}
		assertTrue(m.getAsJsonArray("files").size() > 0);
		for (JsonElement e : m.getAsJsonArray("files")) {
			has(e.getAsJsonObject(), FILE, "files[].");
		}
	}

	private static void has(JsonObject o, List<String> keys, String where) {
		for (String k : keys) {
			assertTrue("메타에 " + where + k + " 없음", o.has(k));
		}
	}
}
