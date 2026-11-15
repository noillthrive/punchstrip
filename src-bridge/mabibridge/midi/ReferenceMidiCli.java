package mabibridge.midi;

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

/**
 * 개발·검증용 CLI. 참조 MIDI의 요약과 트랙 표를 표준출력에 찍는다.
 *
 * <pre>
 * java -cp classes mabibridge.midi.ReferenceMidiCli &lt;file.mid&gt; [마디범위 예: 27-34 또는 27]
 * echo C:\경로\file.mid | java -cp classes mabibridge.midi.ReferenceMidiCli - [마디범위]
 * </pre>
 *
 * <p>경로 자리에 {@code -}를 주면 표준입력 첫 줄(UTF-8)을 경로로 쓴다. Windows의 Java 런처는
 * 명령줄 인자를 시스템 코드 페이지로 바꾸므로, 그 코드 페이지에 없는 글자가 든 경로는 이렇게 넘긴다.</p>
 *
 * <p>종료 코드: 0 성공, 1 MIDI를 열 수 없음, 2 사용법 오류.
 * 출력 문자셋은 UTF-8이며, 시스템 속성 {@code mabibridge.cli.encoding}으로 바꿀 수 있다.</p>
 */
public final class ReferenceMidiCli {

	private ReferenceMidiCli() {}

	public static void main(String[] args) {
		String enc = System.getProperty("mabibridge.cli.encoding", "UTF-8");
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, Charset.forName(enc));
		int code = run(args, out);
		out.flush();
		if (code != 0) {
			System.exit(code);
		}
	}

	/** main 본체(테스트용). 종료 코드를 돌려준다. */
	public static int run(String[] args, PrintStream out) {
		if (args.length < 1 || args.length > 2) {
			out.println("사용법: ReferenceMidiCli <file.mid | -> [마디범위 예: 27-34]  (- 는 표준입력 첫 줄의 UTF-8 경로)");
			return 2;
		}
		int from = -1;
		int to = -1;
		if (args.length == 2) {
			try {
				String[] p = args[1].split("-", 2);
				from = Integer.parseInt(p[0].trim());
				to = p.length > 1 ? Integer.parseInt(p[1].trim()) : from;
				if (from < 1 || to < from) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				out.println("마디 범위 형식이 틀림: " + args[1] + " (예: 27-34)");
				return 2;
			}
		}
		Path file;
		try {
			file = Path.of(args[0].equals("-") ? readPathFromStdin() : args[0]);
		} catch (InvalidPathException | IOException e) {
			out.println("파일 경로를 해석할 수 없음: " + e.getMessage());
			out.println("Windows에서는 명령줄 인자가 시스템 코드 페이지로 바뀌어, 그 코드 페이지에 없는 글자(예: 音)가 깨진다."
					+ " 그 폴더로 이동해 상대 경로로 주거나, 경로 자리에 - 를 주고 표준입력으로 UTF-8 경로 한 줄을 보낸다.");
			return 2;
		}
		ReferenceMidi midi;
		try {
			midi = ReferenceMidi.load(file);
		} catch (ReferenceMidiException e) {
			out.println("오류: " + e.getMessage());
			return 1;
		}
		printSummary(midi, out);
		if (from > 0) {
			printNotes(midi, from, to, out);
		}
		return 0;
	}

	static void printSummary(ReferenceMidi m, PrintStream out) {
		out.println("파일: " + m.file());
		out.printf(Locale.ROOT, "포맷 %d | 트랙 %d | 해상도 %d 틱/4분음표 | 길이 %d 틱, %.3f초 (%s) | 마디 %d%n",
				m.format(), m.trackCount(), m.resolution(), m.tickLength(), m.seconds(), clock(m.seconds()),
				m.measureCount());
		out.println();
		out.println("[템포]");
		if (m.tempos().isEmpty()) {
			out.println("  (없음 — 120 BPM으로 간주)");
		}
		for (ReferenceMidi.Tempo t : m.tempos()) {
			out.printf(Locale.ROOT, "  틱 %-8d %-10s %7.2f BPM  (%d µs/4분음표, 트랙 %d)%n", t.srcTick(), t.pos(), t.bpm(),
					t.microsPerQuarter(), t.sourceTrack());
		}
		out.println("[박자]");
		for (ReferenceMidi.TimeSignature s : m.timeSignatures()) {
			out.printf(Locale.ROOT, "  틱 %-8d 마디 %-4d %d/%d%s%n", s.srcTick(), s.measure(), s.numerator(),
					s.denominator(), s.sourceTrack() > 0 ? "  (트랙 " + s.sourceTrack() + ")" : "  (기본값)");
		}
		if (!m.keySignatures().isEmpty()) {
			out.println("[조표]");
			for (ReferenceMidi.KeySignature k : m.keySignatures()) {
				out.printf(Locale.ROOT, "  틱 %-8d %-10s %s (sf=%d, 트랙 %d)%n", k.srcTick(), k.pos(), k.name(),
						k.sharpsFlats(), k.sourceTrack());
			}
		}
		if (!m.markers().isEmpty()) {
			out.println("[마커]");
			for (ReferenceMidi.Marker k : m.markers()) {
				out.printf(Locale.ROOT, "  틱 %-8d %-10s %s (트랙 %d)%n", k.srcTick(), k.pos(), k.text(), k.sourceTrack());
			}
		}
		out.println();
		out.println("[트랙]");
		out.printf(Locale.ROOT, "  %3s | %-24s | %-4s | %-8s | %5s | %-9s | %-9s | %5s | %-12s | %s%n", "#", "이름", "포트",
				"음 채널", "음표", "음역", "마디", "울림%", "Mabi가져오기", "프로그램(포트·채널: 첫 음 시점 → 최종 [보낸 트랙])");
		for (TrackInfo t : m.tracks()) {
			String range = t.lowest() == null ? "-" : t.lowestName() + "-" + t.highestName();
			String meas = t.firstMeasure() == null ? "-" : t.firstMeasure() + "-" + t.lastMeasure();
			List<String> progs = new ArrayList<>();
			for (TrackInfo.ChannelProgram cp : t.channelPrograms()) {
				StringBuilder sb = new StringBuilder();
				sb.append("p").append(cp.port()).append("ch").append(cp.channel()).append(": ");
				sb.append(cp.programAtFirstNote() == null ? "기본(0)" : String.valueOf(cp.programAtFirstNote()));
				sb.append(" → ").append(cp.finalProgram() == null ? "기본(0)" : String.valueOf(cp.finalProgram()));
				if (cp.finalProgram() != null) {
					sb.append(" ").append(cp.channel() == 10 ? "Drum kit" : MidiNames.gmName(cp.finalProgram()));
				}
				if (!cp.sourceTracks().isEmpty()) {
					sb.append(" [트랙 ").append(joinInts(cp.sourceTracks())).append("]");
				}
				if (cp.overriddenByOtherTrack()) {
					sb.append(" *다른 트랙도 보냄");
				}
				if (cp.ambiguous()) {
					sb.append(" !동시 충돌");
				}
				for (TrackInfo.ChannelProgram mp : t.channelProgramsIgnoringPorts()) {
					if (mp.channel() == cp.channel() && (!java.util.Objects.equals(mp.programAtFirstNote(), cp.programAtFirstNote())
							|| !java.util.Objects.equals(mp.finalProgram(), cp.finalProgram()))) {
						sb.append(" {포트 무시: ").append(mp.programAtFirstNote() == null ? "기본(0)" : mp.programAtFirstNote())
								.append(" → ").append(mp.finalProgram() == null ? "기본(0)" : mp.finalProgram())
								.append(" [트랙 ").append(joinInts(mp.sourceTracks())).append("]")
								.append(mp.ambiguous() ? " !동시 충돌" : "").append("}");
					}
				}
				progs.add(sb.toString());
			}
			String imp = t.mabiImportProgram() == null ? "-" : (t.mabiImportDrum() ? "드럼" : String.valueOf(t.mabiImportProgram()));
			out.printf(Locale.ROOT, "  %3d | %-24s | %-4s | %-8s | %5d | %-9s | %-9s | %5.1f | %-12s | %s%s%n", t.number(),
					cut(t.name(), 24), t.ports().isEmpty() ? "-" : joinInts(t.ports()),
					t.noteChannels().isEmpty() ? "-" : joinInts(t.noteChannels()), t.noteCount(), range, meas,
					t.soundingRatio() * 100, imp, progs.isEmpty() ? "-" : String.join("; ", progs),
					t.drum() ? "  (드럼)" : "");
		}
		boolean anySent = false;
		for (TrackInfo t : m.tracks()) {
			if (!t.sentProgramChanges().isEmpty()) {
				if (!anySent) {
					out.println();
					out.println("[트랙별로 보낸 프로그램 변경]");
					anySent = true;
				}
				List<String> l = new ArrayList<>();
				for (TrackInfo.ProgramChange pc : t.sentProgramChanges()) {
					l.add("p" + pc.port() + "ch" + pc.channel() + "=" + pc.program() + "(" + pc.gmName() + ")@" + pc.pos());
				}
				out.printf(Locale.ROOT, "  트랙 %d: %s%s%n", t.number(), String.join(", ", l),
						t.overridesTracks().isEmpty() ? "" : "  → 트랙 " + joinInts(t.overridesTracks()) + "의 악기에 영향");
			}
		}
		out.println();
		out.println("[경고]");
		if (m.warnings().isEmpty()) {
			out.println("  (없음)");
		}
		for (String s : m.warnings().messages()) {
			out.println("  - " + s);
		}
	}

	static void printNotes(ReferenceMidi m, int from, int to, PrintStream out) {
		out.println();
		out.printf(Locale.ROOT, "[음표: 마디 %d-%d]%n", from, to);
		int total = 0;
		for (TrackInfo t : m.tracks()) {
			List<Note> notes = m.notes(t.number(), from, to);
			if (notes.isEmpty()) {
				continue;
			}
			total += notes.size();
			out.printf(Locale.ROOT, "== 트랙 %d (%s) — %d음%n", t.number(), t.name(), notes.size());
			out.printf(Locale.ROOT, "   %-10s %-5s %4s %3s %4s %7s %5s %9s %6s%n", "위치", "음", "번호", "ch", "vel",
					"tick96", "len96", "srcTick", "srcLen");
			for (Note n : notes) {
				out.printf(Locale.ROOT, "   %-10s %-5s %4d %3d %3d/%-2d %7d %5d %9d %6d%n", n.pos(), n.pitchName(), n.pitch(),
						n.channel(), n.velocity(), n.velocity15(), n.tick96(), n.len96(), n.srcTick(), n.srcLength());
			}
		}
		out.printf(Locale.ROOT, "합계 %d음%n", total);
	}

	private static String readPathFromStdin() throws IOException {
		BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line = r.readLine();
		if (line == null || line.isBlank()) {
			throw new IOException("표준입력에서 경로를 읽지 못함");
		}
		line = line.strip();
		if (!line.isEmpty() && line.charAt(0) == '﻿') {
			line = line.substring(1);
		}
		return line;
	}

	private static String clock(double secs) {
		int m = (int) (secs / 60);
		double s = secs - m * 60;
		return String.format(Locale.ROOT, "%d:%04.1f", m, s);
	}

	private static String joinInts(Iterable<Integer> it) {
		StringBuilder sb = new StringBuilder();
		for (int i : it) {
			if (sb.length() > 0) {
				sb.append(",");
			}
			sb.append(i);
		}
		return sb.toString();
	}

	private static String cut(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
