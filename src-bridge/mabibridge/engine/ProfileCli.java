package mabibridge.engine;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import mabibridge.profile.ProfileIo;
import mabibridge.profile.ProfileTsv;
import mabibridge.profile.ScoreLoader;
import mabibridge.profile.ScoreProfile;

/**
 * 편곡 프로필 일괄 CLI. 파일마다 {@code <이름>.profile.json}(mabibridge.profile/1)을 쓰고, 끝에 {@code profiles.tsv}(파일
 * 1행)와 {@code tracks.tsv}(트랙 1행)를 쓴다. 입력 파일은 읽기만 한다.
 *
 * <pre>
 * ProfileCli --out &lt;폴더 | -&gt; [--from N] [--to M] [--dls a.dls,b.dls] &lt;파일 또는 폴더 | -&gt;...
 * </pre>
 *
 * <p>폴더는 아래의 {@code .mmi}·{@code .mml}을 재귀로 찾는다(이름순). 경로 자리의 {@code -}는 표준입력에서 UTF-8로 한 줄에 하나씩
 * 끝까지 읽는다(한글·한자 경로용, 빈 줄과 {@code #} 줄은 건너뜀). {@code --out -}면 표준입력의 첫 줄이 출력 폴더다. 같은 이름이 겹치면
 * {@code <이름>__2.profile.json}처럼 번호를 붙인다. 출력 폴더의 같은 이름 파일은 덮어쓴다. 출력 폴더가 보호 폴더 안이면 거부한다.</p>
 *
 * <p>JVM 플래그: {@code -Dmabiicco.test_mode -Djava.awt.headless=true -Duser.home=<저장소 안 폴더>
 * --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED}. 종료 코드: 0 전부 성공, 1 일부(또는 전부) 실패, 2 사용법 오류.
 * 표준출력에는 파일마다 한 줄(OK/FAIL), 실패 이유와 엔진 로그는 표준오류.</p>
 */
public final class ProfileCli {

	private ProfileCli() {}

	public static void main(String[] args) {
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		// 원본 3MLE 파서가 System.out에 찍는 진단(16진 덤프 등)은 표준오류로 보낸다.
		System.setOut(System.err);
		int code;
		try {
			code = run(args, out, System.err);
		} catch (IllegalStateException e) {
			System.err.println("시작 거부: " + e.getMessage());
			code = 2;
		}
		out.flush();
		System.exit(code);
	}

	private static BufferedReader stdin;

	private static BufferedReader stdin() {
		if (stdin == null) {
			stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		}
		return stdin;
	}

	private static String clean(String s) {
		String x = s.strip();
		if (x.startsWith("﻿")) {
			x = x.substring(1).strip();
		}
		return x;
	}

	/** main 본체(테스트용). */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		String outArg = null;
		Integer from = null;
		Integer to = null;
		List<String> dls = null;
		List<String> inputs = new ArrayList<>();
		try {
			for (int i = 0; i < args.length; i++) {
				String a = args[i];
				switch (a) {
				case "--out":
					outArg = args[++i];
					break;
				case "--from":
					from = Integer.parseInt(args[++i]);
					break;
				case "--to":
					to = Integer.parseInt(args[++i]);
					break;
				case "--dls":
					dls = List.of(args[++i].split(","));
					break;
				default:
					if (a.startsWith("--")) {
						err.println("모르는 인자: " + a);
						usage(err);
						return 2;
					}
					inputs.add(a);
				}
			}
		} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
			err.println("인자 오류: " + e.getMessage());
			usage(err);
			return 2;
		}
		if (outArg == null || inputs.isEmpty()) {
			usage(err);
			return 2;
		}
		Path outDir;
		List<Path> roots = new ArrayList<>();
		try {
			if ("-".equals(outArg)) {
				String line = stdin().readLine();
				if (line == null || clean(line).isEmpty()) {
					err.println("표준입력에서 출력 폴더를 읽지 못함");
					return 2;
				}
				outDir = Path.of(clean(line));
			} else {
				outDir = Path.of(outArg);
			}
			for (String in : inputs) {
				if ("-".equals(in)) {
					String line;
					while ((line = stdin().readLine()) != null) {
						String s = clean(line);
						if (!s.isEmpty() && !s.startsWith("#")) {
							roots.add(Path.of(s));
						}
					}
				} else {
					roots.add(Path.of(in));
				}
			}
		} catch (IOException | InvalidPathException e) {
			err.println("경로를 해석할 수 없음: " + e.getMessage()
					+ " (한글·한자 경로는 경로 자리에 - 를 주고 표준입력으로 UTF-8 경로를 보낼 것)");
			return 2;
		}
		outDir = outDir.toAbsolutePath().normalize();

		BridgeEngine.Config cfg = BridgeEngine.Config.defaults().withSnapshots(outDir.resolve(".snapshots-unused"));
		if (dls != null) {
			List<Path> l = new ArrayList<>();
			for (String d : dls) {
				l.add(Path.of(d.trim()));
			}
			cfg = cfg.withDls(l);
		}
		BridgeEngine engine = new BridgeEngine(cfg);
		try {
			engine.checkNotProtected(outDir);
		} catch (EngineException e) {
			err.println("오류 " + e.code() + ": " + e.getMessage());
			return 2;
		}

		// 입력 모으기
		List<Path> files = new ArrayList<>();
		int failed = 0;
		for (Path r : roots) {
			Path a = r.toAbsolutePath().normalize();
			if (Files.isDirectory(a)) {
				try (Stream<Path> s = Files.walk(a)) {
					s.filter(Files::isRegularFile).filter(ScoreLoader::isSupported).sorted().forEach(files::add);
				} catch (IOException e) {
					err.println("FAIL " + a + " : 폴더를 읽지 못함 " + e.getMessage());
					failed++;
				}
			} else {
				files.add(a);
			}
		}
		if (files.isEmpty()) {
			err.println("처리할 .mmi·.mml 파일이 없음");
			return failed > 0 ? 1 : 2;
		}
		try {
			Files.createDirectories(outDir);
		} catch (IOException e) {
			err.println("출력 폴더를 만들지 못함: " + outDir + " " + e.getMessage());
			return 2;
		}

		ProfileTsv tsv = new ProfileTsv();
		Set<String> used = new HashSet<>();
		int ok = 0;
		for (Path f : files) {
			String label = f.getFileName().toString();
			try {
				ScoreLoader.Loaded src = ScoreLoader.load(engine, f);
				ScoreProfile.Result r = ScoreProfile.build(src, engine.instrumentTable(), from, to);
				String base = label.replaceFirst("(?i)\\.(mmi|mml)$", "");
				String name = base + ".profile.json";
				for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
					name = base + "__" + n + ".profile.json";
				}
				Path o = outDir.resolve(name);
				ProfileIo.write(o, r.profile(), true);
				tsv.add(label, r.profile());
				out.println("OK   " + label + " -> " + name + " | " + r.summaryText());
				ok++;
			} catch (EngineException e) {
				failed++;
				out.println("FAIL " + label);
				err.println("FAIL " + f + " : " + e.code() + " " + e.getMessage());
			} catch (RuntimeException e) {
				failed++;
				out.println("FAIL " + label);
				err.println("FAIL " + f + " : " + e);
				e.printStackTrace(err);
			}
		}
		try {
			Files.writeString(outDir.resolve("profiles.tsv"), tsv.profilesTsv(), StandardCharsets.UTF_8);
			Files.writeString(outDir.resolve("tracks.tsv"), tsv.tracksTsv(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			err.println("TSV를 쓰지 못함: " + e.getMessage());
			return 1;
		}
		out.println("파일 " + files.size() + "개: 성공 " + ok + ", 실패 " + failed + " -> " + outDir);
		return failed == 0 ? 0 : 1;
	}

	private static void usage(PrintStream err) {
		err.println("Usage: ProfileCli --out <폴더 | -> [--from N] [--to M] [--dls a.dls,b.dls] <파일 또는 폴더 | ->...");
		err.println("  폴더는 아래 .mmi·.mml을 재귀로 찾는다. - 는 표준입력에서 UTF-8 경로를 한 줄씩 끝까지 읽는다.");
	}
}
