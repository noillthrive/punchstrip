package mabibridge.engine;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 개발·검증용 CLI. 표준출력에 사람이 읽는 형식으로 찍는다(엔진 로그는 표준오류).
 *
 * <pre>
 * EngineCli info &lt;file.mmi | -&gt;
 * EngineCli roundtrip &lt;in.mmi | -&gt; &lt;out.mmi | -&gt;
 * EngineCli demo &lt;out.mmi | -&gt;
 * EngineCli instruments [--dls] [타입]
 * </pre>
 *
 * <p>경로 자리에 {@code -}를 주면 표준입력에서 UTF-8로 한 줄씩(인자 순서대로) 경로를 읽는다.
 * Windows의 Java 런처는 명령줄 인자를 시스템 코드 페이지로 바꾸므로 그 코드 페이지에 없는 글자(예: 音)가 든 경로는 이렇게 넘긴다.</p>
 *
 * <p>JVM 플래그: {@code -Dmabiicco.test_mode -Duser.home=<저장소 안 폴더> -Djava.awt.headless=true
 * --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED}. 종료 코드: 0 성공, 1 엔진 오류, 2 사용법 오류.</p>
 */
public final class EngineCli {

	private EngineCli() {}

	public static void main(String[] args) {
		String enc = System.getProperty("mabibridge.cli.encoding", "UTF-8");
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, Charset.forName(enc));
		// 엔진이 System.out에 찍는 로그(DLS 파일 이름 등)는 표준오류로 보낸다.
		System.setOut(System.err);
		int code;
		try {
			code = run(args, out);
		} catch (EngineException e) {
			out.println("오류 " + e.code() + ": " + e.getMessage());
			if (!e.details().isEmpty()) {
				out.println("  details: " + e.details());
			}
			code = 1;
		} catch (IllegalStateException e) {
			out.println("시작 거부: " + e.getMessage());
			code = 1;
		}
		out.flush();
		System.exit(code);
	}

	private static BufferedReader stdin;

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

	/** main 본체(테스트용). */
	public static int run(String[] args, PrintStream out) {
		if (args.length < 1) {
			usage(out);
			return 2;
		}
		try {
			switch (args[0]) {
			case "info":
				if (args.length != 2) {
					break;
				}
				return info(pathArg(args[1]), out);
			case "roundtrip":
				if (args.length != 3) {
					break;
				}
				return roundtrip(pathArg(args[1]), pathArg(args[2]), out);
			case "demo":
				if (args.length != 2) {
					break;
				}
				return demo(pathArg(args[1]), out);
			case "instruments": {
				boolean dls = false;
				String type = null;
				for (int i = 1; i < args.length; i++) {
					if ("--dls".equals(args[i])) {
						dls = true;
					} else {
						type = args[i];
					}
				}
				return instruments(dls, type, out);
			}
			default:
				break;
			}
		} catch (IOException | InvalidPathException e) {
			out.println("경로를 해석할 수 없음: " + e.getMessage()
					+ " (한글·한자 경로는 경로 자리에 - 를 주고 표준입력으로 UTF-8 경로를 보낼 것)");
			return 2;
		}
		usage(out);
		return 2;
	}

	private static void usage(PrintStream out) {
		out.println("사용법: EngineCli info <mmi|->");
		out.println("        EngineCli roundtrip <in.mmi|-> <out.mmi|->");
		out.println("        EngineCli demo <out.mmi|->");
		out.println("        EngineCli instruments [--dls] [타입 N/P/K/V/C/D/M]");
		out.println("  - 는 표준입력의 UTF-8 경로 한 줄(인자 순서대로)");
	}

	private static int info(Path file, PrintStream out) {
		BridgeEngine engine = new BridgeEngine();
		Project p = engine.open(file);
		printInfo(p, out);
		printMml(p, out, 160);
		printStats(p, out);
		return 0;
	}

	private static int roundtrip(Path in, Path outFile, PrintStream out) {
		BridgeEngine engine = new BridgeEngine();
		Project p = engine.open(in);
		printInfo(p, out);
		Path saved = p.saveAs(outFile);
		out.println("저장: " + saved);
		return 0;
	}

	private static int demo(Path outFile, PrintStream out) {
		BridgeEngine engine = new BridgeEngine();
		Project p = engine.newProject("demo", "mabibridge", "4/4", 120);
		int t = p.addTrack("데모 류트", 0, null, null, null);
		EditResult r = p.putNotes(t, "melody", List.of(
				NoteSpec.at(0, 96, "C4"),
				NoteSpec.at(96, "4", "D4"),
				NoteSpec.at(192, 96, 64),
				NoteSpec.atPos("1:4:0", "4", "F4"),
				NoteSpec.atPos("2:1:0", "4", "B4"),
				NoteSpec.atPos("2:2:0", "4", "C5", 10),
				NoteSpec.atPos("2:3:0", "2", "Bb4", 10)), "strict", false);
		out.println("[멜로디 넣기] changed_measures=" + r.changedMeasures() + " added=" + r.added());
		r = p.putNotes(t, "chord1", List.of(
				NoteSpec.at(0, "1", "E3"),
				NoteSpec.atPos("2:1:0", "2.", "G3"),
				NoteSpec.atPos("2:4:0", "4", "F#3")), "strict", false);
		out.println("[화음1 넣기] changed_measures=" + r.changedMeasures() + " added=" + r.added());
		printInfo(p, out);
		printMml(p, out, Integer.MAX_VALUE);
		printStats(p, out);
		Path saved = p.saveAs(outFile);
		out.println("저장: " + saved);
		return 0;
	}

	private static int instruments(boolean dls, String type, PrintStream out) {
		BridgeEngine engine = new BridgeEngine();
		if (dls) {
			BridgeEngine.DlsStatus s = engine.ensureDls();
			out.printf(Locale.ROOT, "DLS: 파일 %d개, 악기 %d개, 악기표 일치 %d개, %d ms%n", s.files().size(), s.instruments(),
					s.matchedInTable(), s.millis());
		}
		out.printf(Locale.ROOT, "%-6s | %-4s | %-24s | %-22s | %-24s | %-16s | %s%n", "번호", "타입", "이름", "영어 이름",
				"허용 파트", "음역(MIDI)", "출처");
		for (InstrumentTable.Instrument i : engine.instruments(type)) {
			String range = (i.range() == null) ? "-"
					: (i.range().low() == null ? "?" : i.range().lowName() + "(" + i.range().low() + ")") + "~"
							+ (i.range().high() == null ? "?" : i.range().highName() + "(" + i.range().high() + ")");
			out.printf(Locale.ROOT, "%-6s | %-4s | %-24s | %-22s | %-24s | %-16s | %s%s%n", i.programText(), i.type(),
					i.name(), i.nameEn() == null ? "-" : i.nameEn(), String.join(",", i.allowedParts()), range,
					i.rangeSource(), i.inDls() == null ? "" : (i.inDls() ? " (DLS 있음)" : " (DLS 없음)"));
		}
		return 0;
	}

	static void printInfo(Project p, PrintStream out) {
		ProjectInfo i = p.info();
		out.println("== 프로젝트 ==");
		out.println("파일: " + (i.source() == null ? "(새 악보)" : i.source()));
		out.println("제목: " + i.title() + " | 작곡: " + i.author() + " | 박자: " + i.timeSignature());
		List<String> sigs = new ArrayList<>();
		for (ProjectInfo.TimeSig s : i.timeSignatures()) {
			sigs.add(s.measure() + "마디 " + s.signature());
		}
		out.println("박자 목록: " + sigs);
		List<String> tempos = new ArrayList<>();
		for (ProjectInfo.TempoView t : i.tempos()) {
			tempos.add(t.pos() + " " + t.bpm());
		}
		out.println("템포: " + (tempos.isEmpty() ? "(없음 = 120)" : tempos));
		out.printf(Locale.ROOT, "길이: %d마디, %d틱, %.3f초 | 트랙 %d개%n", i.totalMeasures(), i.totalTicks(), i.totalSec(),
				i.tracks().size());
		for (TrackSummary t : i.tracks()) {
			out.printf(Locale.ROOT, "  트랙 %2d %-16s 악기 %3d %s(%s) 노래 %d(%s) pan %d vol %d 음표 %s%n", t.track(), t.name(),
					t.program(), t.instrumentName(), t.instrumentType(), t.songProgram(), t.songProgramName(), t.panpot(),
					t.volume(), t.noteCounts());
		}
	}

	static void printMml(Project p, PrintStream out, int max) {
		out.println("== MML ==");
		for (TrackMml m : p.mml(null)) {
			String s = m.mml();
			if (s.length() > max) {
				s = s.substring(0, max) + "…(" + s.length() + "자)";
			}
			out.println("트랙 " + m.track() + " " + m.name() + ": " + s);
			out.println("  글자 수 " + m.chars());
		}
	}

	static void printStats(Project p, PrintStream out) {
		out.println("== 글자 수 (두 기준) ==");
		for (TrackStats s : p.stats(null)) {
			Map<String, Integer> c = s.chars();
			TrackStats.SongExcluded ex = s.limits().songExcluded();
			TrackStats.SongIncluded in = s.limits().songIncluded();
			out.printf(Locale.ROOT, "트랙 %d %s: 멜로디 %d, 화음1 %d, 화음2 %d, 노래 %d | %s | %.3f초%n", s.track(), s.name(),
					c.get("melody"), c.get("chord1"), c.get("chord2"), c.get("song"), s.rankText(), s.playTimeSec());
			out.printf(Locale.ROOT, "  노래 제외 기준 한도 %d/%d/%d 초과 %s 노래 불가 %s%n", ex.melody(), ex.chord1(), ex.chord2(),
					ex.over(), ex.songNotAllowed());
			out.printf(Locale.ROOT, "  노래 포함 기준 한도 %d/%d/%d/%d 초과 %s%n", in.melody(), in.chord1(), in.chord2(), in.song(),
					in.over());
			out.println("  기본 판정: " + s.defaultRule());
		}
	}
}
