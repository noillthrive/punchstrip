package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import mabibridge.compare.CompareHookImpl;
import mabibridge.engine.BridgeEngine;

/**
 * 편의: score_stats의 정수 rank, score_notes의 part "all", project_info의 실제 박자(time= 덮어쓰기),
 * score_profile의 글자 수 기본 기준(트랙마다 score_stats 기본과 같게).
 */
public class QolToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;

	@Before
	public void setUp() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "qol-");
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
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

	private void bad(String tool, JsonObject args) {
		Object[] b = call(tool, args);
		assertTrue(tool + " 오류여야 함: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), "BAD_ARGUMENT", ((JsonObject) b[1]).get("error").getAsString());
	}

	private static JsonObject obj(Object... kv) {
		return McpServerTest.obj(kv);
	}

	private void put(int track, String part, int tick, int pitch) {
		JsonArray n = new JsonArray();
		n.add(obj("tick", tick, "len", 96, "pitch", pitch));
		JsonObject a = obj("track", track, "part", part);
		a.add("notes", n);
		ok("score_put_notes", a);
	}

	@Test
	public void statsRankNumberOrString() {
		ok("project_new", obj("title", "qol"));
		ok("track_add", obj("name", "lute", "program", 0));
		put(1, "melody", 0, 60);
		JsonObject s = ok("score_stats", obj("track", 1, "rank", "1"));
		JsonObject n = ok("score_stats", obj("track", 1, "rank", 1));
		assertEquals(s.get("rank_limits"), n.get("rank_limits"));
		assertEquals("1", n.getAsJsonObject("rank_limits").get("rank").getAsString());
		assertEquals(s.getAsJsonObject("rank_limits"), ok("score_stats", obj("track", 1, "rank", 1.0)).getAsJsonObject("rank_limits"));
		// 트랙을 주면 평평한 객체(rank_limits가 맨 위), 생략하면 tracks[]
		assertTrue(n.has("rank_limits") && !n.has("tracks"));
		JsonObject all = ok("score_stats", obj("rank", 9));
		assertTrue(all.has("tracks") && !all.has("rank_limits"));
		assertEquals("9", all.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonObject("rank_limits").get("rank")
				.getAsString());
		bad("score_stats", obj("track", 1, "rank", 1.5));
		bad("score_stats", obj("track", 1, "rank", true));
		Object[] t = call("score_stats", obj("track", 1, "rank", true));
		String msg = ((JsonObject) t[1]).get("message").getAsString();
		assertTrue(msg, msg.contains("정수(1~9) 또는 문자열"));
		ok("score_fit_suggest", obj("track", 1, "rank", 1));
	}

	@Test
	public void notesAllParts() {
		ok("project_new", obj("title", "qol"));
		ok("track_add", obj("name", "lute", "program", 0));
		put(1, "melody", 0, 60);
		put(1, "chord1", 0, 55);
		put(1, "chord1", 96, 57);
		JsonObject a = ok("score_notes", obj("track", 1, "part", "all", "from_measure", 1, "to_measure", 1));
		JsonObject parts = a.getAsJsonObject("parts");
		List<String> keys = new ArrayList<>(parts.keySet());
		assertEquals(List.of("melody", "chord1", "chord2"), keys); // 류트(N)는 노래 파트 없음
		assertEquals(1, parts.getAsJsonArray("melody").size());
		assertEquals(2, parts.getAsJsonArray("chord1").size());
		assertEquals(0, parts.getAsJsonArray("chord2").size());
		assertEquals(3, a.get("count").getAsInt());
		assertEquals("all", a.get("part").getAsString());
		assertEquals(parts.getAsJsonArray("chord1"),
				ok("score_notes", obj("track", 1, "part", "chord1", "from_measure", 1, "to_measure", 1)).getAsJsonArray("notes"));
		// 노래 악기를 주면 song도
		ok("track_set", obj("track", 1, "song_program", 110));
		assertTrue(ok("score_notes", obj("track", 1, "part", "all", "from_measure", 1, "to_measure", 1))
				.getAsJsonObject("parts").has("song"));
		Object[] b = call("score_notes", obj("track", 1, "part", "bass", "from_measure", 1, "to_measure", 1));
		assertTrue(String.valueOf(b[1]), (Boolean) b[0]);
	}

	/** time=1/4 머리말을 [time-signature] 0=4/4가 덮는 파일. */
	@Test
	public void infoShowsEffectiveTimeSignature() throws Exception {
		Path f = tmp.resolve("override.mmi");
		Files.writeString(f, "[mml-score]\nversion=1\ntitle=override\nauthor=\ntime=1/4\ntempo=0T120\n"
				+ "mml-track=MML@c4d4e4f4,,;\nname=t1\nprogram=0\nsongProgram=-1\npanpot=64\nvisible=true\n"
				+ "[time-signature]\n0=4/4\n", StandardCharsets.UTF_8);
		JsonObject o = ok("project_open", obj("path", f.toString()));
		assertEquals("4/4", o.get("time_signature").getAsString());
		assertEquals("1/4", o.get("base_time").getAsString());
		assertTrue(o.get("time_signature_overridden").getAsBoolean());
		JsonObject plain = ok("project_new", obj("title", "p", "time_signature", "3/4"));
		assertEquals("3/4", plain.get("time_signature").getAsString());
		assertFalse(plain.get("time_signature_overridden").getAsBoolean());
	}

	/** score_profile 글자 수 사용률: 노래 파트가 없는 트랙은 노래 제외 한도(멜로디 1600)로 본다. */
	@Test
	public void profileCharsRuleMatchesStats() {
		ok("project_new", obj("title", "prof"));
		ok("track_add", obj("name", "lute", "program", 0));
		// 세기·옥타브를 번갈아 바꿔 글자를 늘린다. 1200자를 넘을 때까지 20음씩
		int chars = 0;
		for (int b = 0; chars <= 1200 && b < 100; b++) {
			JsonArray n = new JsonArray();
			for (int k = 0; k < 20; k++) {
				int i = b * 20 + k;
				n.add(obj("tick", i * 54, "len", 42, "pitch", (i % 2 == 0 ? 48 : 74) + (i % 5), "vel", 3 + (i % 3) * 5));
			}
			JsonObject a = obj("track", 1, "part", "melody");
			a.add("notes", n);
			ok("score_put_notes", a);
			chars = ok("score_stats", obj("track", 1)).getAsJsonObject("chars").get("melody").getAsInt();
		}
		assertTrue("멜로디가 1200자보다 길어야 시험이 됨: " + chars, chars > 1200 && chars < 1600);
		JsonObject p = ok("score_profile", new JsonObject());
		JsonObject s = p.getAsJsonObject("summary");
		assertEquals("track_default", s.get("chars_rule").getAsString());
		assertTrue(s.toString(), s.get("chars_ratio_max").getAsDouble() < 1.0);
		assertEquals(0, s.getAsJsonArray("chars_over_rank1").size());
		JsonObject tr = p.getAsJsonObject("profile").getAsJsonArray("tracks").get(0).getAsJsonObject();
		assertEquals("song_excluded", tr.get("chars_rule").getAsString());
		JsonObject mel = tr.getAsJsonObject("parts").getAsJsonObject("melody");
		assertTrue(mel.get("chars_ratio").getAsDouble() > 1.0); // 노래 포함 한도 대비(예전 값 그대로)
		assertEquals(mel.get("chars_ratio_song_excluded"), mel.get("chars_ratio_default"));
	}
}
