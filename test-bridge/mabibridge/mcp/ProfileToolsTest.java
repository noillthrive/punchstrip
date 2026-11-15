package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;

/**
 * MCP 도구 score_profile: 현재 프로젝트·파일 경로(현재 프로젝트를 바꾸지 않음)·out_path(쓰기, FILE_EXISTS, overwrite,
 * PROTECTED_PATH)·오류 코드. 보호 폴더 시험은 시험용 임시 보호 폴더로 한다(진짜 보호 폴더에 흔적을 남기지 않게). DLS는 없는 파일로 줘서
 * 음역은 properties 기준.
 */
public class ProfileToolsTest {

	private ServerState state;
	private ToolRegistry tools;
	private Path tmp;
	private Path prot;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "profile-mcp-");
		prot = tmp.resolve("보호");
		List<Path> dirs = new ArrayList<>(BridgeEngine.defaultProtectedDirs());
		dirs.add(prot);
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(List.of(tmp.resolve("none.dls"))).withProtected(dirs));
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

	private JsonObject err(String tool, JsonObject args, String code) {
		Object[] b = call(tool, args);
		assertTrue(tool + " 오류여야 함: " + b[1], (Boolean) b[0]);
		assertEquals(String.valueOf(b[1]), code, ((JsonObject) b[1]).get("error").getAsString());
		return (JsonObject) b[1];
	}

	private static JsonObject obj(Object... kv) {
		return McpServerTest.obj(kv);
	}

	private void project() {
		ok("project_new", obj("title", "프로필"));
		ok("track_add", obj("name", "바이올린", "program", 22));
		JsonArray n = new JsonArray();
		n.add(obj("pos", "1:1:0", "len", "4", "pitch", "G3", "vel", 12));
		n.add(obj("pos", "1:2:0", "len", "4", "pitch", "A3", "vel", 10));
		JsonObject a = obj("track", 1, "part", "melody");
		a.add("notes", n);
		ok("score_put_notes", a);
	}

	@Test
	public void currentProject() {
		project();
		JsonObject r = ok("score_profile", null);
		assertTrue(r.get("ok").getAsBoolean());
		assertEquals("mabibridge.profile/1", r.get("schema").getAsString());
		assertFalse(r.has("out_path"));
		assertTrue(r.get("summary_text").getAsString().contains("「프로필」"));
		assertEquals(1, r.getAsJsonObject("summary").get("tracks").getAsInt());
		JsonObject t1 = r.getAsJsonObject("profile").getAsJsonArray("tracks").get(0).getAsJsonObject();
		// 바이올린 G3(55) → 들리는 G5(79)
		assertEquals(55, t1.getAsJsonObject("written_range").get("min").getAsInt());
		assertEquals(79, t1.getAsJsonObject("sounding_range").get("min").getAsInt());
		assertEquals(11.0, t1.getAsJsonObject("velocity").get("mean").getAsDouble(), 1e-9);
	}

	@Test
	public void outPathAndFilePath() throws IOException {
		project();
		Path mmi = tmp.resolve("저장.mmi");
		ok("project_save_as", obj("path", mmi.toString()));
		ok("track_add", obj("name", "플룻", "program", 5)); // 현재 프로젝트는 2트랙, 저장한 파일은 1트랙
		Path out = tmp.resolve("p").resolve("저장.profile.json");
		JsonObject r = ok("score_profile", obj("path", mmi.toString(), "out_path", out.toString()));
		assertEquals(out.toString(), r.get("out_path").getAsString());
		assertFalse(r.has("profile"));
		assertEquals(1, r.getAsJsonObject("summary").get("tracks").getAsInt());
		JsonObject file = Json.parseStrict(Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("mmi", file.getAsJsonObject("file").get("format").getAsString());
		// 현재 프로젝트는 그대로
		assertEquals(2, ok("project_info", null).getAsJsonArray("tracks").size());
		// 이미 있으면 FILE_EXISTS, overwrite면 덮어씀
		err("score_profile", obj("path", mmi.toString(), "out_path", out.toString()), "FILE_EXISTS");
		ok("score_profile", obj("path", mmi.toString(), "out_path", out.toString(), "overwrite", true));
		// 범위
		JsonObject rr = ok("score_profile", obj("from_measure", 1, "to_measure", 1));
		assertEquals(1, rr.getAsJsonObject("summary").getAsJsonObject("range").get("to_measure").getAsInt());
	}

	@Test
	public void errors() {
		err("score_profile", null, "NO_PROJECT");
		project();
		Path inProt = prot.resolve("x.profile.json");
		err("score_profile", obj("out_path", inProt.toString()), "PROTECTED_PATH");
		assertFalse(Files.exists(inProt));
		err("score_profile", obj("out_path", tmp.resolve("x.txt").toString()), "BAD_PATH");
		err("score_profile", obj("out_path", "rel.json"), "BAD_ARGUMENT");
		err("score_profile", obj("path", tmp.resolve("x.txt").toString()), "BAD_PATH");
		err("score_profile", obj("path", tmp.resolve("없음.mmi").toString()), "FILE_NOT_FOUND");
		err("score_profile", obj("from_measure", 3, "to_measure", 2), "BAD_ARGUMENT");
		err("score_profile", obj("bogus", 1), "BAD_ARGUMENT");
	}
}
