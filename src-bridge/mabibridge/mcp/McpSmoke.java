package mabibridge.mcp;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * 개발용 스모크 시험. 브리지를 자식 프로세스로 띄워 stdio로 실제 MCP 대화를 하고 단계별 통과/실패 표를 찍는다.
 *
 * <pre>
 * java -Dmabiicco.test_mode -Duser.home=&lt;저장소 안 폴더&gt; -cp MabiIccoBridge.jar mabibridge.mcp.McpSmoke [--direct]
 * </pre>
 *
 * <p>기본은 {@code cmd /c bridge\run-bridge.cmd}(인자 없음)로 띄운다. {@code --direct}면 같은 JVM 플래그와 클래스 경로
 * (설치된 MabiIcco {@code MABIICCO_APP} + {@code MabiIccoBridge.jar} + Gson, McpClient와 같음)로 직접 띄운다. 자식 stdout의 모든 줄은 JSON-RPC 2.0 객체여야 하고
 * 줄 끝은 LF 하나여야 한다(아니면 실패). 자식 stderr는 {@code bridge/runtime/smoke/<시각>/bridge-stderr.log}.
 * render_wav 단계(끄려면 {@code -Dmabibridge.smoke.render=false})는 DLS가 있어야 통과한다. 작업 폴더는
 * {@code -Dmabibridge.smoke.out=<폴더>}(아래에 시각 폴더)로 바꿀 수 있다(smoke-test.cmd가 output\smoke를 준다).
 * 참조 MIDI 단계는 시스템 속성 {@code mabibridge.smoke.midi}(또는 환경 변수 {@code MABIBRIDGE_SMOKE_MIDI})로 MIDI 파일을
 * 주었을 때만 돈다(없으면 SKIP). 종료 코드 0 = 모두 통과.</p>
 */
public final class McpSmoke {

	private static final long TIMEOUT_SEC = 120;

	private record Line(String text, boolean crlf) {}

	private record Step(String name, boolean pass, String note) {}

	private final List<Step> steps = new ArrayList<>();
	private final List<Line> allLines = new ArrayList<>();
	private final BlockingQueue<Line> queue = new LinkedBlockingQueue<>();
	private OutputStream childIn;
	private int nextId = 1;
	private PrintStream out;

	public static void main(String[] args) throws Exception {
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		boolean direct = args.length > 0 && "--direct".equals(args[0]);
		int code = new McpSmoke().run(direct, out);
		out.flush();
		System.exit(code);
	}

	static Path repoRoot() throws URISyntaxException {
		Path code = Path.of(McpSmoke.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		return code.toAbsolutePath().getParent();
	}

	int run(boolean direct, PrintStream out) throws Exception {
		this.out = out;
		Path root = repoRoot();
		String outProp = System.getProperty("mabibridge.smoke.out");
		Path work = (outProp != null && !outProp.isBlank() ? Path.of(outProp) : root.resolve("bridge/runtime/smoke"))
				.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		Files.createDirectories(work);
		Path stderrFile = work.resolve("bridge-stderr.log");
		String midi = System.getProperty("mabibridge.smoke.midi", System.getenv("MABIBRIDGE_SMOKE_MIDI"));
		Path refMidi = midi == null || midi.isBlank() ? null : Path.of(midi);

		List<String> cmd = new ArrayList<>();
		if (direct) {
			Path home = root.resolve("bridge/runtime/home");
			Files.createDirectories(home);
			// same command as McpClient's default: installed MabiIcco from MABIICCO_APP + this bridge jar + Gson
			cmd.addAll(McpClient.childCommand(root, home, null, null, List.of()));
		} else {
			// cmd /d /s /c ""<path>"": /s strips only the outer quotes, so a path with & ( ) ! or spaces survives
			cmd.add("cmd");
			cmd.add("/d");
			cmd.add("/s");
			cmd.add("/c");
			cmd.add("\"\"" + root.resolve("bridge").resolve("run-bridge.cmd") + "\"\"");
		}
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectError(stderrFile.toFile());
		if (System.getenv("JAVA_HOME") == null) {
			pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
		}
		out.println("# McpSmoke: " + String.join(" ", cmd));
		out.println("# stderr: " + stderrFile);
		Process proc = pb.start();
		childIn = proc.getOutputStream();
		Thread reader = new Thread(() -> readAll(proc.getInputStream()), "smoke-reader");
		reader.setDaemon(true);
		reader.start();

		try {
			dialog(work, refMidi);
		} catch (Exception e) {
			steps.add(new Step("(중단)", false, e.toString()));
		}

		// stdin을 닫으면 서버가 끝나야 한다.
		childIn.close();
		boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
		if (!exited) {
			proc.descendants().forEach(ProcessHandle::destroyForcibly);
			proc.destroyForcibly();
		}
		reader.join(5000);
		steps.add(new Step("stdin 닫으면 종료(코드 0)", exited && proc.exitValue() == 0,
				exited ? "exit " + proc.exitValue() : "30초 안에 끝나지 않아 강제 종료"));

		// 남은 응답(예상 밖 줄)과 stdout 전체 검사
		int nonJson = 0;
		int crlf = 0;
		List<String> bad = new ArrayList<>();
		synchronized (allLines) {
			for (Line l : allLines) {
				if (l.crlf()) {
					crlf++;
				}
				if (!isRpc(l.text())) {
					nonJson++;
					if (bad.size() < 3) {
						bad.add(l.text().length() > 120 ? l.text().substring(0, 120) + "…" : l.text());
					}
				}
			}
		}
		int extra = queue.size();
		steps.add(new Step("자식 stdout: JSON-RPC 아닌 줄 0개", nonJson == 0,
				"전체 " + allLines.size() + "줄, JSON-RPC 아닌 줄 " + nonJson + "개" + (bad.isEmpty() ? "" : " " + bad)));
		steps.add(new Step("자식 stdout: CRLF 줄 0개", crlf == 0, "CRLF " + crlf + "줄"));
		steps.add(new Step("남은(예상 밖) 응답 0개", extra == 0, extra + "개"));

		int fail = 0;
		out.println();
		out.println("| # | 단계 | 결과 | 비고 |");
		out.println("|---|---|---|---|");
		for (int i = 0; i < steps.size(); i++) {
			Step s = steps.get(i);
			if (!s.pass()) {
				fail++;
			}
			out.println("| " + (i + 1) + " | " + s.name() + " | " + (s.pass() ? "통과" : "실패") + " | "
					+ s.note().replace("|", "/") + " |");
		}
		out.println();
		out.println("# 결과: " + (steps.size() - fail) + "/" + steps.size() + " 통과" + (fail == 0 ? " — 모두 통과" : ""));
		out.println("# 자식 stdout: 전체 " + allLines.size() + "줄, JSON-RPC 아닌 줄 " + nonJson + "개, CRLF " + crlf + "줄");
		return fail == 0 ? 0 : 1;
	}

	private void dialog(Path work, Path refMidi) throws Exception {
		JsonObject p = new JsonObject();
		p.addProperty("protocolVersion", "2025-06-18");
		p.add("capabilities", new JsonObject());
		JsonObject ci = new JsonObject();
		ci.addProperty("name", "McpSmoke");
		ci.addProperty("version", "1");
		p.add("clientInfo", ci);
		request("initialize", "initialize", p, r -> {
			JsonObject res = r.getAsJsonObject("result");
			return res != null && "2025-06-18".equals(res.get("protocolVersion").getAsString())
					&& "mabiicco-bridge".equals(res.getAsJsonObject("serverInfo").get("name").getAsString())
					&& res.getAsJsonObject("capabilities").has("tools");
		});

		notifyOnly("notifications/initialized");

		request("tools/list", "tools/list (39개, compare·render_wav·score_finalize 포함)", new JsonObject(), r -> {
			JsonArray tools = r.getAsJsonObject("result").getAsJsonArray("tools");
			boolean hasCompare = false;
			for (JsonElement t : tools) {
				JsonObject o = t.getAsJsonObject();
				if ("compare".equals(o.get("name").getAsString())) {
					hasCompare = true;
				}
				if (!"object".equals(o.getAsJsonObject("inputSchema").get("type").getAsString())) {
					return false;
				}
			}
			lastNote = "도구 " + tools.size() + "개";
			return tools.size() == 39 && hasCompare;
		});

		call("project_new", obj("title", "smoke"), false, null);
		call("track_add", obj("name", "smoke", "program", 0), false, null);

		JsonArray notes = new JsonArray();
		String[] names = { "C4", "D4", "E4", "F4" };
		for (int i = 0; i < 4; i++) {
			notes.add(obj("tick", i * 96, "len", "4", "pitch", names[i]));
		}
		JsonObject put = obj("track", 1, "part", "melody");
		put.add("notes", notes);
		call("score_put_notes", put, false, b -> b.has("changed_measures") && b.has("stats") && b.has("compare_summary"));
		call("score_mml", obj("track", 1), false, b -> {
			lastNote = b.get("mml").getAsString();
			return "MML@cdef,,;".equals(b.get("mml").getAsString());
		});
		call("score_stats", obj("track", 1), false, b -> b.getAsJsonObject("limits").has("song_excluded"));
		if (Boolean.parseBoolean(System.getProperty("mabibridge.smoke.render", "true"))) {
			call("render_wav", obj("name", "smoke", "out_dir", work.resolve("render").toString()), false, b -> {
				if (!b.has("files") || b.getAsJsonArray("files").size() == 0) {
					lastNote = String.valueOf(b.get("error"));
					return false;
				}
				JsonObject f = b.getAsJsonArray("files").get(0).getAsJsonObject();
				Path wav = Path.of(f.get("path").getAsString());
				lastNote = wav + " " + (Files.isRegularFile(wav) ? wavSize(wav) : "없음");
				return Files.isRegularFile(wav) && wavSize(wav) > 44;
			});
		} else {
			steps.add(new Step("render_wav", true, "SKIP: -Dmabibridge.smoke.render=false"));
		}

		if (refMidi == null) {
			steps.add(new Step("reference_load", true, "SKIP: 참조 MIDI 없음(-Dmabibridge.smoke.midi=<file.mid>로 지정)"));
		} else if (Files.isRegularFile(refMidi)) {
			call("reference_load", obj("path", refMidi.toString()), false, b -> {
				lastNote = "트랙 " + b.get("track_count") + ", 마디 " + b.get("measure_count") + ", " + b.get("seconds") + "초";
				return b.get("track_count").getAsInt() > 0;
			});
			JsonObject rn = obj("from_measure", 1, "to_measure", 8, "max", 20);
			call("reference_notes", rn, false, b -> {
				lastNote = "total " + b.get("total") + ", returned " + b.get("returned") + ", truncated " + b.get("truncated");
				return b.has("notes");
			});
		} else {
			steps.add(new Step("reference_load", false, "시험 파일 없음: " + refMidi));
		}

		Path save = work.resolve("smoke.mmi");
		call("project_save_as", obj("path", save.toString()), false, b -> b.get("ok").getAsBoolean());
		int[] snapId = new int[1];
		call("snapshot", obj("label", "smoke"), false, b -> {
			snapId[0] = b.get("id").getAsInt();
			return snapId[0] > 0;
		});
		call("restore", obj("id", snapId[0]), false, b -> b.has("tracks"));

		call("compare", obj("from_measure", 1, "to_measure", 2), null, b -> {
			if (b.has("error")) {
				lastNote = b.get("error").getAsString();
				// NOT_WIRED = compare 모듈 없음; NO_REFERENCE = 참조 MIDI 단계를 건너뛰었을 때(정상)
				return "NOT_WIRED".equals(b.get("error").getAsString())
						|| (refMidi == null && "NO_REFERENCE".equals(b.get("error").getAsString()));
			}
			lastNote = "compare 연결됨(isError false)";
			return true;
		});
		request("ping", "ping (compare 뒤)", new JsonObject(), r -> r.has("result"));

		request("no/such/method", "모르는 메서드 → -32601", new JsonObject(),
				r -> r.has("error") && r.getAsJsonObject("error").get("code").getAsInt() == -32601);

		// 깨진 JSON 한 줄 → -32700, id null
		sendRaw("{\"jsonrpc\":\"2.0\",\"id\":999,\"method\":");
		Line l = queue.poll(TIMEOUT_SEC, TimeUnit.SECONDS);
		boolean ok = false;
		String note = "응답 없음";
		if (l != null) {
			note = l.text();
			try {
				JsonObject r = Json.parseStrict(l.text()).getAsJsonObject();
				ok = r.get("id").isJsonNull() && r.getAsJsonObject("error").get("code").getAsInt() == -32700;
				note = "code " + r.getAsJsonObject("error").get("code") + ", id " + r.get("id");
			} catch (RuntimeException e) {
				note = "해석 실패: " + e;
			}
		}
		steps.add(new Step("깨진 JSON → -32700 (id null)", ok, note));

		request("ping", "ping (마지막)", new JsonObject(), r -> r.has("result"));
	}

	private String lastNote = "";

	private static long wavSize(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return -1;
		}
	}

	private static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			o.add((String) kv[i], Json.tree(kv[i + 1]));
		}
		return o;
	}

	/** 요청을 보내고 응답을 받아 id·jsonrpc를 확인한 뒤 check로 판정한다. */
	private JsonObject request(String method, String label, JsonObject params, Predicate<JsonObject> check)
			throws Exception {
		int id = nextId++;
		JsonObject m = new JsonObject();
		m.addProperty("jsonrpc", "2.0");
		m.addProperty("id", id);
		m.addProperty("method", method);
		m.add("params", params);
		sendRaw(JsonRpc.serialize(m));
		Line l = queue.poll(TIMEOUT_SEC, TimeUnit.SECONDS);
		if (l == null) {
			steps.add(new Step(label, false, TIMEOUT_SEC + "초 안에 응답 없음"));
			throw new IOException("응답 없음: " + method);
		}
		JsonObject r;
		try {
			r = Json.parseStrict(l.text()).getAsJsonObject();
		} catch (RuntimeException e) {
			steps.add(new Step(label, false, "응답이 JSON 객체가 아님: " + l.text()));
			return null;
		}
		boolean idOk = r.has("id") && r.get("id").isJsonPrimitive() && r.get("id").getAsInt() == id;
		boolean verOk = r.has("jsonrpc") && "2.0".equals(r.get("jsonrpc").getAsString());
		lastNote = "";
		boolean ok;
		try {
			ok = idOk && verOk && check.test(r);
		} catch (RuntimeException e) {
			ok = false;
			lastNote = "판정 중 예외: " + e;
		}
		String note = "id " + (idOk ? "일치" : "불일치(" + r.get("id") + ")") + (verOk ? "" : ", jsonrpc 틀림")
				+ (lastNote.isEmpty() ? "" : ", " + lastNote);
		if (!ok && r.has("error")) {
			note += ", error " + r.get("error");
		}
		steps.add(new Step(label, ok, note));
		return r;
	}

	/**
	 * tools/call. expectError가 null이면 isError 어느 쪽이든 check로 판정. check는 결과 text의 JSON 본문을 받는다.
	 */
	private void call(String tool, JsonObject args, Boolean expectError, Predicate<JsonObject> check) throws Exception {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", args);
		String[] isErr = new String[1];
		request("tools/call", "tools/call " + tool, params, r -> {
			JsonObject res = r.getAsJsonObject("result");
			if (res == null) {
				return false;
			}
			boolean e = res.get("isError").getAsBoolean();
			isErr[0] = "isError " + e;
			String text = res.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			JsonObject body = Json.parseStrict(text).getAsJsonObject();
			if (expectError != null && e != expectError) {
				lastNote = isErr[0] + ", " + (body.has("message") ? body.get("message").getAsString() : "");
				return false;
			}
			boolean ok = check == null || check.test(body);
			lastNote = isErr[0] + (lastNote.isEmpty() ? "" : ", " + lastNote);
			return ok;
		});
	}

	private void notifyOnly(String method) throws IOException {
		JsonObject m = new JsonObject();
		m.addProperty("jsonrpc", "2.0");
		m.addProperty("method", method);
		sendRaw(JsonRpc.serialize(m));
		steps.add(new Step(method + " (알림, 응답 없어야 함)", true, "다음 요청의 id 일치로 확인"));
	}

	private void sendRaw(String line) throws IOException {
		childIn.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		childIn.flush();
	}

	/** 자식 stdout을 바이트로 읽어 LF로 줄을 나눈다(CR이 앞에 있으면 표시). */
	private void readAll(InputStream in) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try {
			int c;
			while ((c = in.read()) >= 0) {
				if (c == '\n') {
					byte[] b = buf.toByteArray();
					boolean cr = b.length > 0 && b[b.length - 1] == '\r';
					String s = new String(b, 0, cr ? b.length - 1 : b.length, StandardCharsets.UTF_8);
					Line l = new Line(s, cr);
					synchronized (allLines) {
						allLines.add(l);
					}
					queue.add(l);
					buf.reset();
				} else {
					buf.write(c);
				}
			}
		} catch (IOException e) {
			// 끝
		}
		if (buf.size() > 0) {
			Line l = new Line(buf.toString(StandardCharsets.UTF_8) + " (줄 끝 없음)", false);
			synchronized (allLines) {
				allLines.add(l);
			}
		}
	}

	private static boolean isRpc(String s) {
		try {
			JsonElement e = Json.parseStrict(s);
			return e.isJsonObject() && "2.0".equals(e.getAsJsonObject().get("jsonrpc").getAsString());
		} catch (JsonParseException | NullPointerException | IllegalStateException | UnsupportedOperationException e) {
			return false;
		}
	}
}
