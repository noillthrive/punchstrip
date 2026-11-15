package mabibridge.compare;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;
import mabibridge.midi.ReferenceMidiException;

/**
 * 개발·검증용 CLI. compare 결과의 {@code text}와 {@code summary}를 표준출력에 찍는다(엔진 로그는 표준오류).
 *
 * <pre>
 * CompareCli &lt;악보.mmi | -&gt; &lt;참조.mid | - | none&gt; &lt;from-to&gt; [매핑 JSON | 매핑 JSON 파일 | -] [--dls] [--json]
 * </pre>
 *
 * <p>경로 자리에 {@code -}를 주면 표준입력에서 UTF-8로 한 줄씩(인자 순서대로) 읽는다(한글·한자 경로용).
 * 매핑 자리의 {@code -}는 매핑 JSON 파일 경로를 한 줄 읽는다. 매핑 인자가 {@code {}로 시작하면 JSON 문자열로 본다.
 * 참조 자리에 {@code none}이면 참조 없이 비교한다. {@code --dls}는 DLS를 읽어 음역을 DLS 기준으로,
 * {@code --json}은 보고 전체를 JSON으로 찍는다.</p>
 *
 * <p>종료 코드: 0 성공, 1 오류, 2 사용법 오류.</p>
 */
public final class CompareCli {

	private CompareCli() {}

	private static BufferedReader stdin;

	public static void main(String[] args) {
		String enc = System.getProperty("mabibridge.cli.encoding", "UTF-8");
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, Charset.forName(enc));
		System.setOut(System.err);
		int code;
		try {
			code = run(args, out);
		} catch (EngineException e) {
			out.println("오류 " + e.code() + ": " + e.getMessage());
			code = 1;
		} catch (IllegalStateException | IllegalArgumentException e) {
			out.println("오류: " + e.getMessage());
			code = 1;
		}
		out.flush();
		System.exit(code);
	}

	/** main 본체(테스트용). */
	public static int run(String[] args, PrintStream out) {
		List<String> pos = new ArrayList<>();
		boolean dls = false;
		boolean json = false;
		for (String a : args) {
			if ("--dls".equals(a)) {
				dls = true;
			} else if ("--json".equals(a)) {
				json = true;
			} else {
				pos.add(a);
			}
		}
		if (pos.size() < 3 || pos.size() > 4) {
			usage(out);
			return 2;
		}
		int from;
		int to;
		try {
			String[] r = pos.get(2).split("-", 2);
			from = Integer.parseInt(r[0].trim());
			to = (r.length > 1) ? Integer.parseInt(r[1].trim()) : from;
		} catch (NumberFormatException e) {
			out.println("마디 범위는 27-34 형식: " + pos.get(2));
			return 2;
		}
		try {
			Path mmi = pathArg(pos.get(0));
			ReferenceMidi ref = null;
			if (!"none".equalsIgnoreCase(pos.get(1))) {
				try {
					ref = ReferenceMidi.load(pathArg(pos.get(1)));
				} catch (ReferenceMidiException e) {
					out.println("참조 MIDI 오류: " + e.getMessage());
					return 1;
				}
			}
			Mapping mapping = Mapping.empty();
			if (pos.size() == 4) {
				String m = pos.get(3).strip();
				if (m.startsWith("{")) {
					mapping = Mapping.fromJson(m);
				} else {
					mapping = Mapping.fromJson(Files.readString(pathArg(m), StandardCharsets.UTF_8));
				}
			}
			Path home = Path.of(System.getProperty("java.io.tmpdir"), "mabibridge-compare-cli");
			BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(home.resolve("snapshots")));
			boolean dlsLoaded = false;
			if (dls) {
				try {
					dlsLoaded = engine.ensureDls().loaded();
				} catch (EngineException e) {
					System.err.println("[compare] DLS를 읽지 못해 악기표 음역으로 검사: " + e.getMessage());
				}
			}
			Project p = engine.open(mmi);
			CompareReport r = Comparer.compare(p, ref, mapping, from, to, null, engine.instrumentTable(), dlsLoaded);
			Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
					.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
			if (json) {
				out.println(gson.toJson(r));
			} else {
				out.println(r.text());
				out.println();
				out.println(gson.toJson(r.summary()));
				if (!r.notesSkipped().isEmpty()) {
					out.println();
					out.println("건너뛴 것·참고:");
					for (String s : r.notesSkipped()) {
						out.println("  - " + s);
					}
				}
			}
			return 0;
		} catch (IOException | InvalidPathException e) {
			out.println("경로를 해석할 수 없음: " + e.getMessage()
					+ " (한글·한자 경로는 경로 자리에 - 를 주고 표준입력으로 UTF-8 경로를 보낼 것)");
			return 2;
		}
	}

	private static Path pathArg(String a) throws IOException {
		String s = a;
		if ("-".equals(a)) {
			if (stdin == null) {
				stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			}
			s = stdin.readLine();
			if (s == null || s.isBlank()) {
				throw new IOException("표준입력에서 경로를 읽지 못함");
			}
			s = s.strip();
			if (s.startsWith("﻿")) {
				s = s.substring(1);
			}
		}
		return Path.of(s);
	}

	private static void usage(PrintStream out) {
		out.println("사용법: CompareCli <악보.mmi | -> <참조.mid | - | none> <from-to> [매핑 JSON | 파일 | -] [--dls] [--json]");
	}
}
