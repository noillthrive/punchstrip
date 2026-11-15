package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;

/** In-process MCP tool calls for tests (same pattern as the other *ToolsTest classes, shared by the 0.7.0 tests). */
final class McpTestCalls {

	final ServerState state;
	final ToolRegistry tools;
	final Path tmp;

	/** Server without the Mabinogi DLS (properties ranges) or, with {@code dls}, the default DLS. */
	McpTestCalls(String name, boolean dls) throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, name + "-");
		BridgeEngine.Config cfg = BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"));
		if (!dls) {
			cfg = cfg.withDls(java.util.List.of(tmp.resolve("none.dls")));
		}
		state = new ServerState(new BridgeEngine(cfg), null);
		tools = ToolRegistry.standard();
	}

	Object[] call(String tool, JsonObject args) {
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

	JsonObject ok(String tool, JsonObject args) {
		Object[] b = call(tool, args);
		assertFalse(tool + ": " + b[1], (Boolean) b[0]);
		return (JsonObject) b[1];
	}

	JsonObject err(String tool, JsonObject args, String code) {
		Object[] b = call(tool, args);
		assertTrue(tool + " should fail: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), code, ((JsonObject) b[1]).get("error").getAsString());
		return (JsonObject) b[1];
	}

	static JsonObject obj(Object... kv) {
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

	static JsonArray arr(Object... es) {
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

	static JsonObject note(long tick, int len, Object pitch) {
		return obj("tick", tick, "len", len, "pitch", pitch);
	}

	static JsonObject note(long tick, int len, Object pitch, int vel) {
		return obj("tick", tick, "len", len, "pitch", pitch, "vel", vel);
	}

	JsonObject put(int track, String part, JsonObject... notes) {
		return ok("score_put_notes", obj("track", track, "part", part, "notes", arr((Object[]) notes)));
	}

	/** Generated MML characters of one slot (score_stats chars). */
	int chars(int track, String part) {
		return ok("score_stats", obj("track", track)).getAsJsonObject("chars").get(part).getAsInt();
	}

	/**
	 * Fills one slot from tick {@code from} with 16th / 8th notes until its MML has exactly {@code target} characters
	 * (the last note is swapped for another pitch / length when the count jumps past the target). Returns the tick after
	 * the last note.
	 */
	long fill(int track, String part, long from, int target, int[] pitches) {
		long tick = from;
		int i = 0;
		while (true) {
			int len = (i % 7 == 3) ? 48 : 24;
			put(track, part, note(tick, len, pitches[i % pitches.length], 8 + (i % 3)));
			int c = chars(track, part);
			if (c == target) {
				return tick + len;
			}
			if (c > target) {
				ok("score_delete_notes", obj("track", track, "part", part, "ticks", arr(tick)));
				for (int p : pitches) {
					for (int l : new int[] { 24, 48, 12, 96 }) {
						for (int v = 8; v <= 10; v++) {
							put(track, part, note(tick, l, p, v));
							if (chars(track, part) == target) {
								return tick + l;
							}
							ok("score_delete_notes", obj("track", track, "part", part, "ticks", arr(tick)));
						}
					}
				}
				throw new AssertionError("cannot reach exactly " + target + " characters in track " + track + " " + part);
			}
			tick += len;
			i++;
		}
	}
}
