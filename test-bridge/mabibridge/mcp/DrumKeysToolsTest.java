package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.InstrumentTable;

/** instruments·track_list의 velocity_rule(x8 / x11)과 드럼 27의 drum_keys(리소스 drum27_keys.json). */
public class DrumKeysToolsTest {

	private ServerState state;
	private ToolRegistry tools;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "drumkeys-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(java.util.List.of(tmp.resolve("none.dls"))));
		state = new ServerState(engine, null);
		tools = ToolRegistry.standard();
	}

	private JsonObject ok(String tool, JsonObject args) {
		JsonObject p = new JsonObject();
		p.addProperty("name", tool);
		p.add("arguments", args);
		JsonObject m = new JsonObject();
		m.addProperty("jsonrpc", "2.0");
		m.addProperty("id", 1);
		m.addProperty("method", "tools/call");
		m.add("params", p);
		McpServer s = new McpServer(state, tools, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "t");
		JsonObject r = s.handleMessage(m).getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(tool + ": " + text, r.has("isError") && r.get("isError").getAsBoolean());
		return Json.parseStrict(text).getAsJsonObject();
	}

	private static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v instanceof Number n) {
				o.addProperty((String) kv[i], n);
			} else if (v instanceof Boolean b) {
				o.addProperty((String) kv[i], b);
			} else {
				o.addProperty((String) kv[i], (String) v);
			}
		}
		return o;
	}

	private Map<Integer, JsonObject> instruments() {
		Map<Integer, JsonObject> m = new HashMap<>();
		for (JsonElement e : ok("instruments", obj("dls", false)).getAsJsonArray("instruments")) {
			m.put(e.getAsJsonObject().get("program").getAsInt(), e.getAsJsonObject());
		}
		return m;
	}

	@Test
	public void velocityRuleByType() {
		assertEquals("x8", InstrumentTable.velocityRule("N"));
		assertEquals("x8", InstrumentTable.velocityRule("D"));
		assertEquals("x8", InstrumentTable.velocityRule("V"));
		assertEquals("x8", InstrumentTable.velocityRule("M"));
		assertEquals("x11", InstrumentTable.velocityRule("P"));
		assertEquals("x11", InstrumentTable.velocityRule("K"));
		assertEquals("none", InstrumentTable.velocityRule("0"));
		assertNull(InstrumentTable.velocityRule(null));
		assertEquals(Integer.valueOf(11), InstrumentTable.velocityMax("K"));
		assertEquals(Integer.valueOf(15), InstrumentTable.velocityMax("N"));

		Map<Integer, JsonObject> ins = instruments();
		for (int p : new int[] { 0, 2, 21, 27, 29, 80, 110 }) {
			assertEquals("program " + p, "x8", ins.get(p).get("velocity_rule").getAsString());
			assertEquals(15, ins.get(p).get("velocity_max").getAsInt());
		}
		for (int p : new int[] { 65, 66, 68, 69, 76, 77 }) {
			assertEquals("program " + p, "x11", ins.get(p).get("velocity_rule").getAsString());
			assertEquals(11, ins.get(p).get("velocity_max").getAsInt());
		}
		// 드럼 키 표는 27에만
		assertFalse(ins.get(77).has("drum_keys"));
		assertFalse(ins.get(21).has("drum_keys"));
	}

	@Test
	public void drumKeysOnDrum27() {
		JsonObject r = ok("instruments", obj("dls", false, "type", "D"));
		JsonObject drum = null;
		for (JsonElement e : r.getAsJsonArray("instruments")) {
			if (e.getAsJsonObject().get("program").getAsInt() == 27) {
				drum = e.getAsJsonObject();
			}
		}
		assertTrue(r.toString(), drum != null);
		JsonArray keys = drum.getAsJsonArray("drum_keys");
		assertEquals(15, keys.size());
		Map<Integer, JsonObject> byKey = new HashMap<>();
		Set<String> ids = new HashSet<>();
		int last = 0;
		for (JsonElement e : keys) {
			JsonObject k = e.getAsJsonObject();
			int key = k.get("key").getAsInt();
			assertTrue("키 순서", key > last);
			last = key;
			byKey.put(key, k);
			assertTrue("id 중복", ids.add(k.get("id").getAsString()));
			for (String f : new String[] { "class", "name_en", "name_ko", "dls_name", "mml" }) {
				assertTrue(key + " " + f, k.has(f) && !k.get(f).isJsonNull());
			}
			assertTrue(Set.of("kick", "tom", "snare", "hat", "crash", "ride").contains(k.get("class").getAsString()));
		}
		assertEquals(Set.of(41, 43, 45, 47, 48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65), byKey.keySet());
		assertEquals(0.0, byKey.get(41).get("level_db_vs_kick").getAsDouble(), 1e-9);
		assertEquals(2.2, byKey.get(43).get("level_db_vs_kick").getAsDouble(), 1e-9);
		assertEquals(-4.6, byKey.get(60).get("level_db_vs_kick").getAsDouble(), 1e-9);
		assertEquals(-14.0, byKey.get(59).get("level_db_vs_kick").getAsDouble(), 1e-9);
		assertEquals(-16.8, byKey.get(64).get("level_db_vs_kick").getAsDouble(), 1e-9);
		assertTrue(byKey.get(62).get("level_db_vs_kick").isJsonNull());
		assertEquals("kick", byKey.get(41).get("class").getAsString());
		assertEquals("closed_hat", byKey.get(59).get("id").getAsString());
		assertEquals("pedal_hat", byKey.get(57).get("id").getAsString());
		assertTrue(byKey.get(41).get("in_game_note").getAsString().contains("43"));
		assertEquals(15, byKey.get(41).get("suggested_vel").getAsInt());
		assertTrue(drum.get("drum_level_rule").getAsString().contains("40*log10"));
		assertTrue(drum.get("drum_in_game").getAsString().contains("41"));
		// 표는 한 곳(리소스)에서만: 도구 응답과 DrumKeyTable이 같다
		assertEquals(DrumKeyTable.keys(), keys);
	}

	@Test
	public void trackSummaryVelocityRule() {
		ok("project_new", obj("title", "vr"));
		ok("track_add", obj("name", "xylo", "program", 77));
		ok("track_add", obj("name", "drum", "program", 27));
		ok("track_add", obj("name", "bell", "program", 70));
		JsonArray t = ok("track_list", new JsonObject()).getAsJsonArray("tracks");
		assertEquals("x11", t.get(0).getAsJsonObject().get("velocity_rule").getAsString());
		assertEquals(11, t.get(0).getAsJsonObject().get("velocity_max").getAsInt());
		assertEquals("x8", t.get(1).getAsJsonObject().get("velocity_rule").getAsString());
		assertEquals(15, t.get(1).getAsJsonObject().get("velocity_max").getAsInt());
		assertEquals("x11", t.get(2).getAsJsonObject().get("velocity_rule").getAsString());
		JsonArray pi = ok("project_info", new JsonObject()).getAsJsonArray("tracks");
		assertEquals("x11", pi.get(0).getAsJsonObject().get("velocity_rule").getAsString());
	}
}
