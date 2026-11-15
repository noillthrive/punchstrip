package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

import mabibridge.compare.CompareHookImpl;
import mabibridge.engine.BridgeEngine;

/**
 * compare·compare_suggest_mapping의 auto_offset_max와 auto 알림. 참조 12마디, 마디 m은 음이름 m(C, C#, …)의 4분음표
 * 4개라 마디끼리 음이름이 겹치지 않는다. 악보는 참조 1~6마디를 7~12마디에 둔다(악보 = 참조 + 6).
 */
public class AutoOffsetToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "autooff-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(List.of(tmp.resolve("none.dls"))));
		state = new ServerState(engine, new CompareHookImpl(engine));
		tools = ToolRegistry.standard();
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t = seq.createTrack();
		t.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));
		t.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		for (int m = 0; m < 12; m++) {
			for (int b = 0; b < 4; b++) {
				long tick = (m * 4L + b) * 480;
				t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + m, 100), tick));
				t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60 + m, 0), tick + 480));
			}
		}
		Path mid = tmp.resolve("ref12.mid");
		MidiSystem.write(seq, 1, mid.toFile());
		ok("project_new", McpServerTest.obj("title", "autooff", "time_signature", "4/4", "tempo", 120));
		ok("track_add", McpServerTest.obj("name", "lute", "program", 0));
		JsonArray notes = new JsonArray();
		for (int m = 0; m < 6; m++) {
			for (int b = 0; b < 4; b++) {
				notes.add(McpServerTest.obj("tick", (m + 6) * 384 + b * 96, "len", 96, "pitch", 60 + m));
			}
		}
		JsonObject put = McpServerTest.obj("track", 1, "part", "melody");
		put.add("notes", notes);
		ok("score_put_notes", put);
		ok("reference_load", McpServerTest.obj("path", mid.toString()));
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

	private static String notes(JsonObject r) {
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : r.getAsJsonArray("notes_skipped")) {
			sb.append(e.getAsString()).append('\n');
		}
		return sb.toString();
	}

	@Test
	public void defaultRangeFindsNothingAndSaysSo() {
		JsonObject r = ok("compare", McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto"));
		assertEquals(0, r.get("measure_offset_used").getAsInt());
		assertEquals(5, r.getAsJsonObject("measure_offset_scores").size()); // 0~4(악보 쪽 시작이 1보다 작은 차는 뺌)
		assertTrue(notes(r), notes(r).contains("모든 후보에서 맞는 음이 0개라 0으로 비교함"));
		assertTrue(r.get("text").getAsString(), r.get("text").getAsString().contains("주의: measure_offset auto"));
	}

	@Test
	public void autoOffsetMaxWidensSearch() {
		JsonObject r = ok("compare", McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto",
				"auto_offset_max", 8));
		assertEquals(6, r.get("measure_offset_used").getAsInt());
		assertEquals(9, r.getAsJsonObject("measure_offset_scores").size()); // 0~8
		assertEquals(16, r.getAsJsonObject("measure_offset_scores").get("6").getAsInt());
		assertFalse(notes(r), notes(r).contains("measure_offset auto"));
		// 범위 끝(±6)에 걸리면 넓혀 보라는 알림
		JsonObject edge = ok("compare", McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto",
				"auto_offset_max", 6));
		assertEquals(6, edge.get("measure_offset_used").getAsInt());
		assertTrue(notes(edge), notes(edge).contains("찾는 범위(±6)의 끝"));
		// 악보 쪽 범위가 마지막 음 마디(12)를 넘음
		JsonObject past = ok("compare", McpServerTest.obj("from_measure", 3, "to_measure", 8, "measure_offset", "auto",
				"auto_offset_max", 8));
		assertEquals(6, past.get("measure_offset_used").getAsInt());
		assertTrue(notes(past), notes(past).contains("마지막 음 마디(12)를 넘음"));
		// compare_suggest_mapping도 같은 인자
		JsonObject sg = ok("compare_suggest_mapping", McpServerTest.obj("from_measure", 1, "to_measure", 4,
				"measure_offset", "auto", "auto_offset_max", 8));
		assertEquals(6, sg.get("measure_offset_used").getAsInt());
	}

	@Test
	public void badAutoOffsetMax() {
		for (JsonObject a : new JsonObject[] {
				McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto", "auto_offset_max", 0),
				McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto", "auto_offset_max", 65),
				McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", "auto", "auto_offset_max", "8"),
				McpServerTest.obj("from_measure", 1, "to_measure", 4, "measure_offset", 2, "auto_offset_max", 8),
				McpServerTest.obj("from_measure", 1, "to_measure", 4, "auto_offset_max", 8) }) {
			for (String tool : new String[] { "compare", "compare_suggest_mapping" }) {
				Object[] b = call(tool, a);
				assertTrue(tool + " " + a + ": " + b[1], (Boolean) b[0]);
				assertEquals(String.valueOf(b[1]), "BAD_ARGUMENT", ((JsonObject) b[1]).get("error").getAsString());
			}
		}
	}
}
