package mabibridge.mcp;

import static mabibridge.mcp.McpTestCalls.arr;
import static mabibridge.mcp.McpTestCalls.note;
import static mabibridge.mcp.McpTestCalls.obj;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.google.gson.JsonObject;

/** score_put_notes dry_run: the resulting characters per slot without changing the project. */
public class PutNotesDryRunToolsTest {

	@Test
	public void dryRunReportsCharactersAndKeepsTheScore() throws Exception {
		McpTestCalls m = new McpTestCalls("putdry", false);
		m.ok("project_new", obj("title", "dry"));
		m.ok("track_add", obj("name", "lute", "program", 0));
		m.put(1, "melody", note(0, 96, 60));
		Path a = m.tmp.resolve("a.mmi");
		m.ok("project_save_as", obj("path", a.toString()));
		JsonObject d = m.ok("score_put_notes", obj("track", 1, "part", "melody", "dry_run", true, "rank", "Practice",
				"notes", arr(note(96, 96, 64), note(192, 96, 67))));
		assertTrue(d.get("dry_run").getAsBoolean());
		assertEquals(1, d.getAsJsonObject("chars_before").get("melody").getAsInt());
		int after = d.getAsJsonObject("stats").getAsJsonObject("chars").get("melody").getAsInt();
		assertEquals(3, after);
		assertEquals("RANK_t", d.getAsJsonObject("rank_limits").get("rank_key").getAsString());
		assertEquals(3, d.getAsJsonObject("rank_limits").getAsJsonObject("parts").getAsJsonObject("melody").get("chars")
				.getAsInt());
		// nothing changed: same notes, not dirty, same saved bytes
		assertEquals(1, m.ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1)).get("count")
				.getAsInt());
		assertFalse(m.ok("project_info", null).get("dirty").getAsBoolean());
		Path b = m.tmp.resolve("b.mmi");
		m.ok("project_save_as", obj("path", b.toString()));
		assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
		// the real call gives the same numbers
		JsonObject r = m.ok("score_put_notes", obj("track", 1, "part", "melody", "notes", arr(note(96, 96, 64), note(192, 96, 67))));
		assertEquals(after, r.getAsJsonObject("stats").getAsJsonObject("chars").get("melody").getAsInt());
		// an overlap in a dry run is still refused, and the score stays as it was
		m.err("score_put_notes", obj("track", 1, "part", "melody", "dry_run", true, "notes", arr(note(0, 96, 62))), "OVERLAP");
		assertEquals(3, m.ok("score_notes", obj("track", 1, "part", "melody", "from_measure", 1, "to_measure", 1)).get("count")
				.getAsInt());
	}
}
