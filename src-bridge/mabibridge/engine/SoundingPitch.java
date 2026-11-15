package mabibridge.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * "적은 음 대 들리는 음" 표. 마비노기 악기는 악보에 적은 음과 실제로 울리는 음이 악기마다 다르다
 * (피아노는 +1옥타브, 바이올린은 +2옥타브, 전자 기타·가야금·베이스는 구간마다 다름 등).
 *
 * <p>자료는 jar 안의 {@code mabibridge/engine/sounding_pitch.properties}(출처와 갱신 방법은 그 파일 머리말).
 * 모든 음높이는 MIDI 번호(60 = C4)다. 노래 파트의 음은 트랙의 {@code songProgram}으로 찾는다
 * ({@link #programFor}).</p>
 *
 * <p>상태가 없는 정적 표다. 처음 쓸 때 한 번 읽는다.</p>
 */
public final class SoundingPitch {

	/** {@link #soundingOf}: 그 적은 음은 소리가 나지 않는다(베이스 56~61). */
	public static final int SILENT = -1;
	/** {@link #soundingOf}: 음높이가 없는 악기(타악기·효과음). */
	public static final int UNPITCHED = -2;

	public static final String TIMBRE_DEFAULT = "default";
	public static final String TIMBRE_ALT = "alt";

	public static final String RESOURCE = "/mabibridge/engine/sounding_pitch.properties";
	public static final String SOURCE = "render_wav 렌더의 기본 주파수 측정과 MSXspirit01~05.dls 샘플 분석 (2026-11-01 측정)";

	/** 표의 종류. */
	public enum Kind {
		OFFSET("offset"), REGIONS("regions"), FIXED("fixed"), UNPITCHED("unpitched"), SFX("sfx"), UNKNOWN("unknown");

		private final String key;

		Kind(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}

		/** 들리는 음높이를 원곡과 비교할 수 있는 종류인가(offset·regions·unknown). */
		public boolean pitched() {
			return this == OFFSET || this == REGIONS || this == UNKNOWN;
		}
	}

	/**
	 * 구간 하나. from·to는 적은 음(포함, null이면 열린 끝). offset이 null이면 무음 구간.
	 */
	public record Region(Integer from, Integer to, Integer offset, String timbre) {
		public boolean contains(int written) {
			return (from == null || written >= from) && (to == null || written <= to);
		}

		public boolean silent() {
			return offset == null;
		}
	}

	/**
	 * 악기 하나의 규칙.
	 *
	 * @param offset     OFFSET·UNKNOWN의 반음(그 밖은 0)
	 * @param regions    REGIONS의 구간들(적은 음 순서). 그 밖은 빈 목록
	 * @param fixedPitch FIXED의 들리는 음(그 밖은 null)
	 * @param verified   측정으로 확인했는가(표에 없는 번호는 false)
	 */
	public record Rule(int program, Kind kind, int offset, List<Region> regions, Integer fixedPitch, boolean verified) {}

	private static Map<Integer, Rule> rules;
	/** 소리 나는 적은 음 창(program → {lo, hi}, 포함). 창 밖의 적은 음은 무음. 표의 {@code audible.<번호>=lo..hi} 줄. */
	private static Map<Integer, int[]> audible;

	/** 소리 나는 적은 음 창 줄의 키 앞머리. */
	static final String AUDIBLE_PREFIX = "audible.";

	private SoundingPitch() {}

	// ======================================================================
	// 표 읽기
	// ======================================================================

	private static synchronized Map<Integer, Rule> rules() {
		if (rules == null) {
			Map<Integer, int[]> au = new TreeMap<>();
			rules = load(au);
			audible = Collections.unmodifiableMap(au);
		}
		return rules;
	}

	private static Map<Integer, int[]> audible() {
		rules();
		return audible;
	}

	/**
	 * 렌더로 잰 "소리 나는 적은 음" 창 {lo, hi}(MIDI, 포함). 표에 없으면 null(창 제한 없음: 무음은 구간 규칙만). 창 밖의 적은 음은
	 * {@link #soundingOf}가 {@link #SILENT}로 돌려준다.
	 */
	public static int[] audibleWindow(int program) {
		int[] w = audible().get(program);
		return w == null ? null : w.clone();
	}

	private static boolean outsideWindow(int program, int written) {
		int[] w = audible().get(program);
		return w != null && (written < w[0] || written > w[1]);
	}

	private static Map<Integer, Rule> load(Map<Integer, int[]> audibleOut) {
		String text;
		try (InputStream in = SoundingPitch.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("자료 파일이 클래스패스에 없음: " + RESOURCE + " (빌드가 src-bridge의 자료 파일을 jar에 복사한다)");
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("자료 파일을 읽을 수 없음: " + RESOURCE, e);
		}
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Map<Integer, Rule> m = new TreeMap<>();
		for (String key : p.stringPropertyNames()) {
			if (key.trim().startsWith(AUDIBLE_PREFIX)) {
				String num = key.trim().substring(AUDIBLE_PREFIX.length());
				String[] lh = p.getProperty(key).trim().split("\\.\\.", -1);
				try {
					audibleOut.put(Integer.parseInt(num), new int[] { Integer.parseInt(lh[0].trim()), Integer.parseInt(lh[1].trim()) });
				} catch (RuntimeException e) {
					throw new IllegalStateException(RESOURCE + ": 소리 나는 음 창 형식이 틀림(" + key + "=lo..hi): " + p.getProperty(key));
				}
				continue;
			}
			int program;
			try {
				program = Integer.parseInt(key.trim());
			} catch (NumberFormatException e) {
				throw new IllegalStateException(RESOURCE + ": 악기 번호가 아닌 키 " + key);
			}
			m.put(program, parse(program, p.getProperty(key).trim()));
		}
		return Collections.unmodifiableMap(m);
	}

	static Rule parse(int program, String v) {
		String[] kv = v.split(":", 2);
		String kind = kv[0].trim();
		String body = kv.length > 1 ? kv[1].trim() : "";
		switch (kind) {
		case "offset":
			return new Rule(program, Kind.OFFSET, Integer.parseInt(body.replace("+", "")), List.of(), null, true);
		case "fixed":
			return new Rule(program, Kind.FIXED, 0, List.of(), Integer.parseInt(body), true);
		case "unpitched":
			return new Rule(program, Kind.UNPITCHED, 0, List.of(), null, true);
		case "sfx":
			return new Rule(program, Kind.SFX, 0, List.of(), null, true);
		case "regions": {
			List<Region> rs = new ArrayList<>();
			for (String r : body.split(";")) {
				String[] f = r.trim().split(":");
				String[] ft = f[0].split("\\.\\.", -1);
				if (ft.length != 2 || f.length < 2) {
					throw new IllegalStateException(RESOURCE + ": 구간 형식이 틀림(" + program + "): " + r);
				}
				Integer from = ft[0].isBlank() ? null : Integer.valueOf(ft[0].trim());
				Integer to = ft[1].isBlank() ? null : Integer.valueOf(ft[1].trim());
				if ("silent".equals(f[1].trim())) {
					rs.add(new Region(from, to, null, null));
				} else {
					String timbre = f.length > 2 ? f[2].trim() : TIMBRE_DEFAULT;
					rs.add(new Region(from, to, Integer.parseInt(f[1].trim().replace("+", "")), timbre));
				}
			}
			return new Rule(program, Kind.REGIONS, 0, List.copyOf(rs), null, true);
		}
		default:
			throw new IllegalStateException(RESOURCE + ": 모르는 종류(" + program + "): " + v);
		}
	}

	/** 악기 규칙. 표에 없으면 UNKNOWN(오프셋 0, verified false). */
	public static Rule rule(int program) {
		Rule r = rules().get(program);
		return r != null ? r : new Rule(program, Kind.UNKNOWN, 0, List.of(), null, false);
	}

	/** 표에 있는 악기 번호들. */
	public static List<Integer> programs() {
		return List.copyOf(rules().keySet());
	}

	/**
	 * 한 파트의 음을 내는 악기 번호. 노래 파트는 트랙의 노래 악기(songProgram ≥ 0)를 쓴다. 트랙 악기가 노래·코러스
	 * 타입이거나 노래 악기가 없으면 트랙 악기.
	 */
	public static int programFor(int program, int songProgram, Part part, String instrumentType) {
		boolean mainIsSong = "V".equals(instrumentType) || "C".equals(instrumentType);
		if (part == Part.SONG && !mainIsSong && songProgram >= 0) {
			return songProgram;
		}
		return program;
	}

	// ======================================================================
	// 변환
	// ======================================================================

	/**
	 * 적은 음 → 들리는 음.
	 *
	 * @return 들리는 MIDI 번호, 또는 {@link #SILENT}(무음 구간), {@link #UNPITCHED}(타악기·효과음)
	 */
	public static int soundingOf(int program, int writtenMidi) {
		Rule r = rule(program);
		if (r.kind().pitched() && outsideWindow(program, writtenMidi)) {
			return SILENT;
		}
		switch (r.kind()) {
		case OFFSET:
		case UNKNOWN:
			return writtenMidi + r.offset();
		case FIXED:
			return r.fixedPitch();
		case UNPITCHED:
		case SFX:
			return UNPITCHED;
		case REGIONS:
		default:
			for (Region g : r.regions()) {
				if (g.contains(writtenMidi)) {
					return g.silent() ? SILENT : writtenMidi + g.offset();
				}
			}
			return SILENT;
		}
	}

	/**
	 * 들리는 음 → 적을 음. 구간 악기는 timbre로 세트를 고른다(null이면 default). 악기 음역은 보지 않는다(부르는 쪽이 본다).
	 *
	 * @return 적을 MIDI 번호. 그 세트로 낼 수 없거나(구간 밖) 고정·무음높이 악기면 null
	 */
	public static Integer writtenFor(int program, int soundingMidi, String timbre) {
		Rule r = rule(program);
		String t = (timbre == null || timbre.isBlank()) ? TIMBRE_DEFAULT : timbre;
		switch (r.kind()) {
		case OFFSET:
		case UNKNOWN: {
			int w = soundingMidi - r.offset();
			return outsideWindow(program, w) ? null : w;
		}
		case REGIONS:
			for (Region g : r.regions()) {
				if (!g.silent() && t.equals(g.timbre())) {
					int w = soundingMidi - g.offset();
					return g.contains(w) && !outsideWindow(program, w) ? w : null;
				}
			}
			return null;
		default:
			return null;
		}
	}

	/** 적은 음이 속한 세트 이름(default/alt), 무음 구간이면 "silent". 구간 악기가 아니면 default. */
	public static String timbreOf(int program, int writtenMidi) {
		Rule r = rule(program);
		if (r.kind().pitched() && outsideWindow(program, writtenMidi)) {
			return "silent";
		}
		if (r.kind() != Kind.REGIONS) {
			return TIMBRE_DEFAULT;
		}
		for (Region g : r.regions()) {
			if (g.contains(writtenMidi)) {
				return g.silent() ? "silent" : g.timbre();
			}
		}
		return "silent";
	}

	/** 구간 악기에서 고를 수 있는 세트 이름(구간 순서). 그 밖은 [default]. */
	public static List<String> timbres(int program) {
		Rule r = rule(program);
		List<String> l = new ArrayList<>();
		if (r.kind() == Kind.REGIONS) {
			for (Region g : r.regions()) {
				if (!g.silent() && !l.contains(g.timbre())) {
					l.add(g.timbre());
				}
			}
		} else {
			l.add(TIMBRE_DEFAULT);
		}
		return l;
	}

	// ======================================================================
	// 설명
	// ======================================================================

	/** 짧은 한국어 요약: "+1옥타브", "+2옥타브", "적은 대로", "구간별", "고정음 C4", "음높이 없음", "효과음", "미확인(적은 대로 가정)". */
	public static String summary(int program) {
		Rule r = rule(program);
		return switch (r.kind()) {
		case OFFSET -> offsetText(r.offset());
		case REGIONS -> "구간별";
		case FIXED -> "고정음 " + NoteSpec.pitchName(r.fixedPitch());
		case UNPITCHED -> "음높이 없음";
		case SFX -> "효과음";
		case UNKNOWN -> "미확인(적은 대로 가정)";
		};
	}

	/** 반음 수 → "+1옥타브", "−2옥타브", "적은 대로", "+7반음". */
	public static String offsetText(int semis) {
		if (semis == 0) {
			return "적은 대로";
		}
		if (semis % 12 == 0) {
			return (semis > 0 ? "+" : "−") + Math.abs(semis / 12) + "옥타브";
		}
		return (semis > 0 ? "+" : "−") + Math.abs(semis) + "반음";
	}

	/**
	 * 악기 하나의 설명(instruments 응답의 {@code sounding}). 키: program, kind, offset_semitones, summary, regions[{from,
	 * to, offset, timbre}], silent[{from, to}], fixed_pitch, fixed_name, verified, detail, source.
	 */
	public static Map<String, Object> describe(int program) {
		Rule r = rule(program);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", r.kind().key());
		m.put("offset_semitones", (r.kind() == Kind.OFFSET || r.kind() == Kind.UNKNOWN) ? r.offset() : null);
		m.put("summary", summary(program));
		List<Map<String, Object>> regions = new ArrayList<>();
		List<Map<String, Object>> silent = new ArrayList<>();
		for (Region g : r.regions()) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("from", g.from());
			x.put("to", g.to());
			if (g.silent()) {
				silent.add(x);
			} else {
				x.put("offset", g.offset());
				x.put("timbre", g.timbre());
				regions.add(x);
			}
		}
		int[] aw = audible().get(program);
		if (aw != null && r.kind().pitched()) {
			// 창 밖도 무음 구간으로 알린다(아래쪽 끝 ~ lo−1, hi+1 ~ 위쪽 끝)
			Map<String, Object> below = new LinkedHashMap<>();
			below.put("from", null);
			below.put("to", aw[0] - 1);
			silent.add(0, below);
			Map<String, Object> above = new LinkedHashMap<>();
			above.put("from", aw[1] + 1);
			above.put("to", null);
			silent.add(above);
		}
		m.put("regions", regions);
		m.put("silent", silent);
		if (aw != null) {
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("from", aw[0]);
			a.put("to", aw[1]);
			a.put("from_name", NoteSpec.pitchName(aw[0]));
			a.put("to_name", NoteSpec.pitchName(aw[1]));
			m.put("audible_written", a);
		} else {
			m.put("audible_written", null);
		}
		m.put("fixed_pitch", r.fixedPitch());
		m.put("fixed_name", r.fixedPitch() == null ? null : NoteSpec.pitchName(r.fixedPitch()));
		m.put("verified", r.verified());
		m.put("detail", detail(r));
		return m;
	}

	/** 사람이 읽는 한 줄 설명(소리 나는 적은 음 창이 있으면 그 범위를 덧붙인다). */
	public static String detail(Rule r) {
		String base = baseDetail(r);
		int[] aw = audible().get(r.program());
		if (aw != null && r.kind().pitched()) {
			return base + ". 적은 음 " + NoteSpec.pitchName(aw[0]) + "~" + NoteSpec.pitchName(aw[1]) + " 밖은 무음(렌더로 확인)";
		}
		return base;
	}

	private static String baseDetail(Rule r) {
		switch (r.kind()) {
		case OFFSET:
			return r.offset() == 0 ? "적은 음 그대로 울림" : "적은 음보다 " + offsetText(r.offset()) + " 울림";
		case REGIONS: {
			List<String> l = new ArrayList<>();
			for (Region g : r.regions()) {
				String span = (g.from() == null ? "" : NoteSpec.pitchName(g.from())) + "~"
						+ (g.to() == null ? "" : NoteSpec.pitchName(g.to()));
				l.add(span + (g.silent() ? " 무음" : " " + offsetText(g.offset()) + "(" + g.timbre() + ")"));
			}
			return "적은 음의 구간마다 다름: " + String.join(", ", l);
		}
		case FIXED:
			return "적은 음과 무관하게 " + NoteSpec.pitchName(r.fixedPitch()) + " 한 음";
		case UNPITCHED:
			return "음높이 없음(음 번호가 소리 종류를 고름)";
		case SFX:
			return "효과음(새소리). 음높이 비교는 의미 없음";
		case UNKNOWN:
		default:
			return "측정하지 않은 악기. 적은 대로 울린다고 가정함";
		}
	}

	/**
	 * 연주 가능한 적은 음역 [writtenLow, writtenHigh]를 들리는 음으로 옮긴 범위(세트마다 하나). 키: timbre, written_low,
	 * written_high, low, high, low_name, high_name. 음높이 없는 악기는 빈 목록. 음역 끝을 모르면(null) 그 끝은 null.
	 */
	public static List<Map<String, Object>> soundingRange(int program, Integer writtenLow, Integer writtenHigh) {
		Rule r = rule(program);
		int[] aw = audible().get(program);
		if (aw != null && r.kind().pitched()) {
			writtenLow = maxOf(writtenLow, aw[0]);
			writtenHigh = minOf(writtenHigh, aw[1]);
		}
		List<Map<String, Object>> out = new ArrayList<>();
		switch (r.kind()) {
		case OFFSET:
		case UNKNOWN:
			out.add(rangeEntry(TIMBRE_DEFAULT, writtenLow, writtenHigh, r.offset()));
			break;
		case FIXED: {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("timbre", TIMBRE_DEFAULT);
			x.put("written_low", writtenLow);
			x.put("written_high", writtenHigh);
			x.put("low", r.fixedPitch());
			x.put("high", r.fixedPitch());
			x.put("low_name", NoteSpec.pitchName(r.fixedPitch()));
			x.put("high_name", NoteSpec.pitchName(r.fixedPitch()));
			out.add(x);
			break;
		}
		case REGIONS:
			for (Region g : r.regions()) {
				if (g.silent()) {
					continue;
				}
				Integer lo = maxOf(g.from(), writtenLow);
				Integer hi = minOf(g.to(), writtenHigh);
				if (lo != null && hi != null && lo > hi) {
					continue;
				}
				out.add(rangeEntry(g.timbre(), lo, hi, g.offset()));
			}
			break;
		default:
			break;
		}
		return out;
	}

	private static Map<String, Object> rangeEntry(String timbre, Integer wl, Integer wh, int off) {
		Map<String, Object> x = new LinkedHashMap<>();
		x.put("timbre", timbre);
		x.put("written_low", wl);
		x.put("written_high", wh);
		Integer lo = wl == null ? null : wl + off;
		Integer hi = wh == null ? null : wh + off;
		x.put("low", lo);
		x.put("high", hi);
		x.put("low_name", lo == null ? null : NoteSpec.pitchName(lo));
		x.put("high_name", hi == null ? null : NoteSpec.pitchName(hi));
		return x;
	}

	private static Integer maxOf(Integer a, Integer b) {
		if (a == null) {
			return b;
		}
		return b == null ? a : Math.max(a, b);
	}

	private static Integer minOf(Integer a, Integer b) {
		if (a == null) {
			return b;
		}
		return b == null ? a : Math.min(a, b);
	}

	/** 들리는 음 → 응답용 3필드(pitch, name, silent). 음높이 없는 악기는 pitch·name null, silent false. */
	public record Sound(Integer pitch, String name, boolean silent) {
		public static Sound of(int program, int writtenMidi) {
			int s = soundingOf(program, writtenMidi);
			if (s == SILENT) {
				return new Sound(null, null, true);
			}
			if (s == UNPITCHED) {
				return new Sound(null, null, false);
			}
			return new Sound(s, NoteSpec.pitchName(s), false);
		}
	}
}
