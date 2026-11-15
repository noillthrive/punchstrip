package mabibridge.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import mabibridge.MabiIccoRequired;

/**
 * 요청 파일 드라이버. 브리지를 자식 프로세스로 띄우고 요청 파일의 줄을 순서대로(동기) 보내 응답을 사람이 읽는 형식으로 찍는다.
 *
 * <pre>
 * java -Dmabiicco.test_mode -Duser.home=bridge\runtime\home -cp MabiIccoBridge.jar mabibridge.mcp.McpClient
 *      &lt;요청.jsonl | -&gt; [--out 응답.txt] [--err 자식stderr.txt] [--timeout 초] [--jar 브리지.jar | --cp a;b]
 *      [--server-args 인자...]
 * </pre>
 *
 * <p>요청 파일: UTF-8, 한 줄에 JSON 하나. 빈 줄과 {@code #}·{@code //}로 시작하는 줄은 건너뛴다.</p>
 * <ul>
 * <li>단축형 {@code {"tool": "score_put_notes", "args": {...}}} → {@code tools/call}로 바꾸고 id는 자동.</li>
 * <li>완전한 JSON-RPC 요청 {@code {"jsonrpc":"2.0","id":..,"method":..,"params":..}}은 그대로 보낸다. id가 없으면 알림으로
 * 보내고 응답을 기다리지 않는다.</li>
 * </ul>
 *
 * <p>{@code initialize}·{@code notifications/initialized}는 드라이버가 먼저 보낸다. 출력은 요청마다
 * {@code === [번호] 도구 — ok|isError} 머리줄과 결과 {@code content[0].text}(JSON-RPC 껍데기를 벗긴 본문).
 * 마지막 줄은 "요청 n개, 오류 m개". 종료 코드: 0 모두 성공, 1 오류 있음, 2 실행 실패(서버가 죽음 등).</p>
 *
 * <p>자식은 저장소 루트(이 jar가 있는 폴더)를 작업 폴더로, run-bridge.cmd와 같은 JVM 플래그와 클래스 경로로 띄운다:
 * {@code java -cp "<MABIICCO_APP>\MabiIcco.jar;<MABIICCO_APP>\lib\flatlaf\*;<MABIICCO_APP>\lib\commons-compress\*;
 * <루트>\MabiIccoBridge.jar;<루트>\lib\gson\*" mabibridge.Main}. MabiIcco는 설치된 것(환경 변수 {@code MABIICCO_APP} =
 * 그 {@code app} 폴더)만 쓴다. 없거나 파일이 모자라면 MabiIcco가 필요하다는 안내와 함께 시작하지 않는다(종료 코드 2).
 * 자식 stderr는 {@code --err} 파일(기본: {@code --out} 파일 이름 + {@code .err}, {@code --out}이 없으면
 * {@code bridge/runtime/client/<시각>.err}).</p>
 *
 * <p>{@code --jar <경로>}면 저장소의 {@code MabiIccoBridge.jar} 대신 그 브리지 jar(얼린 사본 등)를 같은 클래스 경로에 넣는다
 * (MabiIcco는 여전히 {@code MABIICCO_APP}에서). {@code --cp <a;b;…>}면 {@code java -cp <a;b;…> mabibridge.Main}으로
 * 띄운다(클래스 경로를 통째로 직접 줄 때, {@code MABIICCO_APP}을 보지 않음). 경로는 지금 폴더 기준으로
 * 절대 경로로 바꾸고, 없는 파일이면 시작하지 않는다(종료 코드 2). 저장소 루트는 이 클래스를
 * 읽어 온 곳의 폴더이고, 그 폴더에 {@code bridge/run-bridge.cmd}가 없으면(저장소 안 하위 폴더의 사본에서 읽었으면)
 * 위로 올라가며 그런 폴더를 찾는다.</p>
 */
public final class McpClient {

	private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
	private final List<String> nonJson = new ArrayList<>();
	private OutputStream childIn;
	private Process proc;
	private PrintStream console;
	private Writer outFile;
	private long timeoutSec = 600;
	private int autoId = 1;

	public static void main(String[] args) throws Exception {
		PrintStream console = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		int code;
		try {
			code = new McpClient().run(args, console);
		} catch (IllegalArgumentException e) {
			console.print("오류: " + e.getMessage() + "\n");
			console.print("사용법: McpClient <요청.jsonl | -> [--out 응답.txt] [--err 파일] [--timeout 초]"
					+ " [--jar 브리지.jar | --cp a;b] [--server-args 인자...]\n");
			code = 2;
		}
		console.flush();
		System.exit(code);
	}

	int run(String[] args, PrintStream console) throws Exception {
		this.console = console;
		if (args.length < 1) {
			throw new IllegalArgumentException("요청 파일이 필요함");
		}
		String input = args[0];
		Path out = null;
		Path err = null;
		Path jar = null;
		String cp = null;
		List<String> serverArgs = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			switch (args[i]) {
			case "--out" -> out = Path.of(need(args, ++i, "--out"));
			case "--err" -> err = Path.of(need(args, ++i, "--err"));
			case "--timeout" -> timeoutSec = Long.parseLong(need(args, ++i, "--timeout"));
			case "--jar" -> jar = Path.of(need(args, ++i, "--jar"));
			case "--cp" -> cp = need(args, ++i, "--cp");
			case "--server-args" -> {
				for (i++; i < args.length; i++) {
					serverArgs.add(args[i]);
				}
			}
			default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
			}
		}

		List<String> lines = readRequests(input);
		Path root = clientRoot(McpSmoke.repoRoot());
		Path home = root.resolve("bridge/runtime/home");
		List<String> cmd = childCommand(root, home, jar, cp, serverArgs);
		if (err == null) {
			if (out != null) {
				err = Path.of(out.toString() + ".err");
			} else {
				err = root.resolve("bridge/runtime/client")
						.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".err");
			}
		}
		Path errAbs = err.toAbsolutePath();
		if (errAbs.getParent() != null) {
			Files.createDirectories(errAbs.getParent());
		}
		if (out != null) {
			Path o = out.toAbsolutePath();
			if (o.getParent() != null) {
				Files.createDirectories(o.getParent());
			}
			outFile = Files.newBufferedWriter(o, StandardCharsets.UTF_8);
		}

		Files.createDirectories(home);
		if (jar != null || cp != null) {
			emit("# 자식: -cp " + cmd.get(cmd.indexOf("-cp") + 1) + " mabibridge.Main\n");
		}
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(root.toFile());
		pb.redirectError(errAbs.toFile());
		proc = pb.start();
		childIn = proc.getOutputStream();
		Thread reader = new Thread(this::readChild, "client-reader");
		reader.setDaemon(true);
		reader.start();

		int requests = 0;
		int errors = 0;
		boolean fatal = false;
		try {
			// 초기화
			JsonObject init = new JsonObject();
			init.addProperty("jsonrpc", "2.0");
			init.addProperty("id", "init");
			init.addProperty("method", "initialize");
			JsonObject ip = new JsonObject();
			ip.addProperty("protocolVersion", McpServer.DEFAULT_PROTOCOL);
			ip.add("capabilities", new JsonObject());
			JsonObject ci = new JsonObject();
			ci.addProperty("name", "McpClient");
			ci.addProperty("version", "1");
			ip.add("clientInfo", ci);
			init.add("params", ip);
			JsonObject ir = exchange(init);
			if (ir == null || !ir.has("result")) {
				emit("초기화 실패: " + ir + "\n");
				fatal = true;
			} else {
				JsonObject si = ir.getAsJsonObject("result").getAsJsonObject("serverInfo");
				emit("# 서버 " + si.get("name").getAsString() + " " + si.get("version").getAsString() + ", 프로토콜 "
						+ ir.getAsJsonObject("result").get("protocolVersion").getAsString() + "\n");
				JsonObject n = new JsonObject();
				n.addProperty("jsonrpc", "2.0");
				n.addProperty("method", "notifications/initialized");
				send(n);
			}

			for (int i = 0; i < lines.size() && !fatal; i++) {
				String line = lines.get(i);
				requests++;
				int no = requests;
				JsonObject msg;
				String label;
				try {
					JsonElement e = Json.parseStrict(line);
					if (!e.isJsonObject()) {
						throw new JsonParseException("JSON 객체가 아님");
					}
					JsonObject o = e.getAsJsonObject();
					if (o.has("tool")) {
						for (String k : o.keySet()) {
							if (!k.equals("tool") && !k.equals("args")) {
								throw new JsonParseException("단축형에 모르는 키 " + k + " (tool, args만)");
							}
						}
						JsonElement a = o.get("args");
						if (a != null && !a.isJsonNull() && !a.isJsonObject()) {
							throw new JsonParseException("args는 객체여야 함");
						}
						msg = new JsonObject();
						msg.addProperty("jsonrpc", "2.0");
						msg.addProperty("id", "r" + autoId++);
						msg.addProperty("method", "tools/call");
						JsonObject p = new JsonObject();
						p.addProperty("name", o.get("tool").getAsString());
						p.add("arguments", a == null || a.isJsonNull() ? new JsonObject() : a);
						msg.add("params", p);
						label = o.get("tool").getAsString();
					} else if (o.has("method")) {
						msg = o;
						String method = o.get("method").getAsString();
						label = method;
						if ("tools/call".equals(method) && o.has("params") && o.getAsJsonObject("params").has("name")) {
							label = o.getAsJsonObject("params").get("name").getAsString() + " (tools/call)";
						}
					} else {
						throw new JsonParseException("{\"tool\":…, \"args\":…} 또는 JSON-RPC 요청이어야 함");
					}
				} catch (RuntimeException e) {
					errors++;
					emit("=== [" + no + "] 요청 줄 해석 실패 — isError\n" + e.getMessage() + "\n줄: " + abbreviate(line) + "\n\n");
					continue;
				}

				if (!msg.has("id")) {
					send(msg);
					emit("=== [" + no + "] " + label + " — 알림(응답 없음)\n\n");
					continue;
				}
				JsonObject resp = exchange(msg);
				if (resp == null) {
					errors++;
					fatal = true;
					emit("=== [" + no + "] " + label + " — 응답 없음(서버가 끝났거나 " + timeoutSec + "초 초과). 중단\n\n");
					break;
				}
				if (resp.has("error")) {
					errors++;
					JsonObject er = resp.getAsJsonObject("error");
					emit("=== [" + no + "] " + label + " — JSON-RPC 오류 " + er.get("code") + "\n"
							+ er.get("message").getAsString() + "\n\n");
					continue;
				}
				JsonElement result = resp.get("result");
				if (result != null && result.isJsonObject() && result.getAsJsonObject().has("content")) {
					JsonObject r = result.getAsJsonObject();
					boolean isError = r.has("isError") && r.get("isError").getAsBoolean();
					if (isError) {
						errors++;
					}
					StringBuilder sb = new StringBuilder();
					sb.append("=== [").append(no).append("] ").append(label).append(" — ").append(isError ? "isError" : "ok")
							.append('\n');
					JsonArray content = r.getAsJsonArray("content");
					for (JsonElement c : content) {
						JsonObject co = c.getAsJsonObject();
						if (co.has("text")) {
							sb.append(co.get("text").getAsString()).append('\n');
						} else {
							sb.append(Json.pretty(co)).append('\n');
						}
					}
					emit(sb.append('\n').toString());
				} else {
					emit("=== [" + no + "] " + label + " — ok\n" + Json.pretty(result) + "\n\n");
				}
			}
		} finally {
			try {
				childIn.close();
			} catch (IOException e) {
				// 무시
			}
			if (!proc.waitFor(30, TimeUnit.SECONDS)) {
				proc.descendants().forEach(ProcessHandle::destroyForcibly);
				proc.destroyForcibly();
				emit("# 서버가 30초 안에 끝나지 않아 강제 종료함\n");
			}
			reader.join(5000);
		}
		if (!nonJson.isEmpty()) {
			emit("# 경고: 서버 stdout에 JSON 아닌 줄 " + nonJson.size() + "개: " + abbreviate(nonJson.get(0)) + "\n");
		}
		emit("요청 " + requests + "개, 오류 " + errors + "개" + (fatal ? " (중단됨)" : "") + "\n");
		emit("# 서버 종료 코드 " + proc.exitValue() + ", 서버 stderr: " + errAbs + "\n");
		if (outFile != null) {
			outFile.close();
		}
		return fatal ? 2 : (errors > 0 ? 1 : 0);
	}

	/** 자식 명령, MabiIcco는 환경 변수 {@code MABIICCO_APP}에서. */
	static List<String> childCommand(Path root, Path home, Path jar, String cp, List<String> serverArgs) {
		return childCommand(root, home, jar, cp, serverArgs, System.getenv(MabiIccoRequired.ENV));
	}

	/**
	 * 자식 명령. cp가 null이면 {@link #installClasspath}(설치된 MabiIcco {@code mabiiccoApp} + 브리지 jar — jar가 null이면
	 * {@code <root>/MabiIccoBridge.jar} — + {@code <root>/lib/gson/*}), cp면 {@code -cp <cp> mabibridge.Main}. 경로는 지금
	 * 폴더 기준 절대 경로로 바꾼다. {@code 폴더\*}(폴더 와일드카드)는 폴더가 있는지만 보고 그대로 넘긴다(java가 그 폴더 바로
	 * 아래 jar로 펼친다. 하위 폴더 lib\gson 등은 따로 적어야 한다).
	 *
	 * @param mabiiccoApp {@code MABIICCO_APP}의 값(따옴표·끝 역슬래시 허용), 없으면 null
	 * @throws IllegalArgumentException jar와 cp를 함께 줬거나, 파일이 없거나, cp가 비었거나, MabiIcco가 없을 때
	 */
	static List<String> childCommand(Path root, Path home, Path jar, String cp, List<String> serverArgs,
			String mabiiccoApp) {
		if (jar != null && cp != null) {
			throw new IllegalArgumentException("--jar와 --cp는 함께 쓸 수 없음");
		}
		List<String> cmd = new ArrayList<>();
		cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		cmd.add("--add-exports");
		cmd.add("java.desktop/com.sun.media.sound=ALL-UNNAMED");
		cmd.add("-Djava.awt.headless=true");
		cmd.add("-Dmabiicco.test_mode");
		cmd.add("-Dmabiicco.ticktable=tickTable.dat");
		cmd.add("-Duser.home=" + home);
		cmd.add("-Xmx1024m");
		if (cp != null) {
			List<String> parts = new ArrayList<>();
			for (String s : cp.split(File.pathSeparator)) {
				if (s.isBlank()) {
					continue;
				}
				// 폴더 와일드카드 "lib\\*"·"lib/*"(그 폴더 바로 아래 jar 모두, 하위 폴더는 아님 — java가 펼친다)
				String e = s.strip();
				boolean wild = e.equals("*") || e.endsWith("/*") || e.endsWith("\\*");
				String base = wild ? e.substring(0, e.length() - 1) : e;
				Path p = Path.of(base.isEmpty() ? "." : base).toAbsolutePath().normalize();
				if (wild ? !Files.isDirectory(p) : !Files.exists(p)) {
					throw new IllegalArgumentException("--cp의 항목이 없음: " + p + (wild ? File.separator + "*" : ""));
				}
				parts.add(wild ? p + File.separator + "*" : p.toString());
			}
			if (parts.isEmpty()) {
				throw new IllegalArgumentException("--cp가 비었음");
			}
			cmd.add("-cp");
			cmd.add(String.join(File.pathSeparator, parts));
			cmd.add("mabibridge.Main");
		} else {
			Path j = (jar == null) ? root.resolve("MabiIccoBridge.jar") : jar.toAbsolutePath().normalize();
			if (!Files.isRegularFile(j)) {
				throw new IllegalArgumentException(jar != null ? "--jar 파일이 없음: " + j
						: "브리지 jar가 없음: " + j + " (릴리스 zip을 풀거나 ant jar로 빌드)");
			}
			cmd.add("-cp");
			cmd.add(installClasspath(MabiIccoRequired.appFromEnv(mabiiccoApp), j, root.resolve("lib").resolve("gson")));
			cmd.add("mabibridge.Main");
		}
		cmd.addAll(serverArgs);
		return cmd;
	}

	/**
	 * run-bridge.cmd와 같은 클래스 경로: {@code <app>\MabiIcco.jar;<app>\lib\flatlaf\*;<app>\lib\commons-compress\*;
	 * <bridgeJar>;<gsonDir>\*}.
	 *
	 * @throws IllegalArgumentException app이 null이거나 필요한 파일이 없을 때(메시지 = 사용자 안내), gson 폴더가 없을 때
	 */
	static String installClasspath(Path app, Path bridgeJar, Path gsonDir) {
		String problem = MabiIccoRequired.checkAppFolder(app);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		if (!Files.isDirectory(gsonDir)) {
			throw new IllegalArgumentException("Gson 폴더가 없음: " + gsonDir);
		}
		String w = File.separator + "*";
		return String.join(File.pathSeparator, app.resolve("MabiIcco.jar").toString(),
				app.resolve("lib").resolve("flatlaf") + w, app.resolve("lib").resolve("commons-compress") + w,
				bridgeJar.toString(), gsonDir.toAbsolutePath().normalize() + w);
	}

	/**
	 * 저장소 루트. 이 클래스를 읽어 온 폴더에 {@code bridge/run-bridge.cmd}가 있으면 그 폴더(예전 그대로), 없으면
	 * 위로 올라가며 그런 폴더를 찾는다(저장소 안 얼린 사본에서 읽었을 때). 끝까지 없으면 처음 폴더.
	 */
	static Path clientRoot(Path codeDir) {
		for (Path d = codeDir; d != null; d = d.getParent()) {
			if (Files.isRegularFile(d.resolve("bridge/run-bridge.cmd"))) {
				return d;
			}
		}
		return codeDir;
	}

	private static String need(String[] a, int i, String name) {
		if (i >= a.length) {
			throw new IllegalArgumentException(name + " 뒤에 값이 필요함");
		}
		return a[i];
	}

	private static List<String> readRequests(String input) throws IOException {
		List<String> raw;
		if ("-".equals(input)) {
			raw = new ArrayList<>();
			BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			String l;
			while ((l = r.readLine()) != null) {
				raw.add(l);
			}
		} else {
			raw = Files.readAllLines(Path.of(input), StandardCharsets.UTF_8);
		}
		List<String> out = new ArrayList<>();
		for (String l : raw) {
			String t = l.strip();
			if (t.startsWith("﻿")) {
				t = t.substring(1).strip();
			}
			if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) {
				continue;
			}
			out.add(t);
		}
		return out;
	}

	private void emit(String s) throws IOException {
		console.print(s);
		console.flush();
		if (outFile != null) {
			outFile.write(s);
			outFile.flush();
		}
	}

	private void send(JsonObject msg) throws IOException {
		childIn.write((JsonRpc.serialize(msg) + "\n").getBytes(StandardCharsets.UTF_8));
		childIn.flush();
	}

	/** 요청을 보내고 같은 id의 응답을 기다린다. 서버가 끝났거나 시간이 지나면 null. */
	private JsonObject exchange(JsonObject msg) throws Exception {
		send(msg);
		JsonElement id = msg.get("id");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
		while (true) {
			long left = deadline - System.nanoTime();
			if (left <= 0) {
				return null;
			}
			String line = queue.poll(Math.min(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
			if (line == null) {
				if (!proc.isAlive() && queue.isEmpty()) {
					return null;
				}
				continue;
			}
			JsonObject r;
			try {
				r = Json.parseStrict(line).getAsJsonObject();
			} catch (RuntimeException e) {
				continue; // readChild가 이미 nonJson에 적었다
			}
			if (id.equals(r.get("id"))) {
				return r;
			}
			emit("# 다른 id의 응답(무시): " + abbreviate(line) + "\n");
		}
	}

	private void readChild() {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String l;
			while ((l = r.readLine()) != null) {
				try {
					JsonElement e = Json.parseStrict(l);
					if (!e.isJsonObject()) {
						throw new JsonParseException("객체 아님");
					}
				} catch (RuntimeException e) {
					synchronized (nonJson) {
						nonJson.add(l);
					}
				}
				queue.add(l);
			}
		} catch (IOException e) {
			// 끝
		}
	}

	private static String abbreviate(String s) {
		return s.length() > 160 ? s.substring(0, 160) + "…" : s;
	}
}
