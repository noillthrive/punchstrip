package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_NOTE;
import static mabibridge.engine.EngineException.details;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.fourthline.mmlTools.MMLEvent;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.core.MMLException;
import jp.fourthline.mmlTools.core.MMLTicks;
import mabibridge.midi.MeasureMap;

/**
 * 입력 음표. {@code tick}(정수) 또는 {@code pos}("마디:박:틱") 중 하나, {@code len}(정수 틱 또는
 * MML 길이 문자열 "4", "8.", "4&16"), {@code pitch}(정수 MIDI 번호 또는 "C4"/"F#4"/"Bb3"/"c+4"/"E-3"),
 * {@code vel}(0~15, 기본 8).
 *
 * <p>len과 pitch는 {@link Number} 또는 {@link String}을 받는다(JSON에서 온 Double도 정수면 받는다).</p>
 */
public final class NoteSpec {

	/** 엔진 음 번호 범위 −1~107 = MIDI 11~119. */
	public static final int MIN_MIDI = 11;
	public static final int MAX_MIDI = 119;
	public static final int DEFAULT_VEL = 8;
	public static final int TICK_UNIT = 6;
	/**
	 * 셋잇단 격자. 16분 셋잇단 16, 8분 셋잇단 32, 4분 셋잇단 64, 2분 셋잇단 128틱(MML l24·l12·l6·l3)이 모두 이 배수다.
	 * 틱은 그 마디 시작 기준으로 본다(못갖춘마디 뒤 마디 시작은 16의 배수가 아닐 수 있다).
	 */
	public static final int TRIPLET_UNIT = 16;

	/**
	 * 틱이 격자 위인가. 그 마디 시작 기준으로 6의 배수(64분음표 격자) 또는 16의 배수(셋잇단 격자). 마디 시작은 언제나
	 * 6의 배수라(MabiIcco 박자 분모 ≤ 64) 6의 배수 쪽은 절대 틱으로 봐도 같다.
	 */
	public static boolean onGrid(MeasureMap map, long tick) {
		if (tick % TICK_UNIT == 0) {
			return true;
		}
		long start = (map == null) ? 0 : map.measureStart(map.measureOf(tick));
		return (tick - start) % TRIPLET_UNIT == 0;
	}

	/** 길이가 격자 위인가(6의 배수 또는 16의 배수). */
	public static boolean lenOnGrid(long len) {
		return len % TICK_UNIT == 0 || len % TRIPLET_UNIT == 0;
	}

	/** 격자 밖 틱·길이의 거부 이유 끝말(쓰는 곳마다 같은 글). */
	public static final String GRID_TEXT = "6의 배수(64분음표 격자) 또는 마디 시작 기준 16의 배수(셋잇단 격자: 16분 16·8분 32·4분 64·2분 128)";

	private final Long tick;
	private final String pos;
	private final Object len;
	private final Object pitch;
	private final Integer vel;

	public NoteSpec(Long tick, String pos, Object len, Object pitch, Integer vel) {
		this.tick = tick;
		this.pos = pos;
		this.len = len;
		this.pitch = pitch;
		this.vel = vel;
	}

	/** tick 기준 음표(vel 기본 8). len·pitch는 정수 또는 문자열. */
	public static NoteSpec at(long tick, Object len, Object pitch) {
		return new NoteSpec(tick, null, len, pitch, null);
	}

	public static NoteSpec at(long tick, Object len, Object pitch, int vel) {
		return new NoteSpec(tick, null, len, pitch, vel);
	}

	/** pos("마디:박:틱") 기준 음표. */
	public static NoteSpec atPos(String pos, Object len, Object pitch) {
		return new NoteSpec(null, pos, len, pitch, null);
	}

	public static NoteSpec atPos(String pos, Object len, Object pitch, int vel) {
		return new NoteSpec(null, pos, len, pitch, vel);
	}

	public Long tick() {
		return tick;
	}

	public String pos() {
		return pos;
	}

	public Object len() {
		return len;
	}

	public Object pitch() {
		return pitch;
	}

	public Integer vel() {
		return vel;
	}

	@Override
	public String toString() {
		return "{tick=" + tick + ", pos=" + pos + ", len=" + len + ", pitch=" + pitch + ", vel=" + vel + "}";
	}

	/** 해석된 음표. tick·len은 격자 위({@link #onGrid}·{@link #lenOnGrid}), pitch는 MIDI 번호. */
	public record Resolved(int index, int tick, int len, int pitch, int vel) {
		public int end() {
			return tick + len;
		}

		/** 엔진 음표로 바꾼다(음 번호 = MIDI − 12). */
		public MMLNoteEvent toEvent() {
			return new MMLNoteEvent(pitch - 12, len, tick, vel);
		}
	}

	/** align=true로 맞춘 기록. field는 "tick" 또는 "len". */
	public record Aligned(int index, String field, long from, long to) {}

	/**
	 * 검증하고 해석한다.
	 *
	 * @param map        틱 ↔ 마디 지도(pos를 쓸 때)
	 * @param align      격자 밖이면 가장 가까운 6의 배수로 맞출지
	 * @param index      입력 목록 안의 위치(오류·정렬 기록용, 0부터)
	 * @param alignedOut 맞춘 기록을 더할 목록(null 가능)
	 * @throws EngineException BAD_NOTE
	 */
	public Resolved resolve(MeasureMap map, boolean align, int index, List<Aligned> alignedOut) {
		long t;
		if (tick != null && pos != null) {
			throw bad(index, "tick", tick, "tick과 pos 중 하나만 줘야 함");
		} else if (tick != null) {
			t = tick;
		} else if (pos != null) {
			t = parsePos(map, pos, index);
		} else {
			throw bad(index, "tick", null, "tick 또는 pos가 필요함");
		}
		if (t < 0) {
			throw bad(index, "tick", t, "음수 tick");
		}
		if (!onGrid(map, t)) {
			if (!align) {
				throw bad(index, "tick", t, "tick이 격자 밖(" + GRID_TEXT + "이 아님). align=true면 가장 가까운 6의 배수로 맞춤");
			}
			long a = roundTo6(t);
			if (alignedOut != null) {
				alignedOut.add(new Aligned(index, "tick", t, a));
			}
			t = a;
		}

		long l = parseLen(len, index);
		if (l <= 0) {
			throw bad(index, "len", len, "길이는 0보다 커야 함");
		}
		if (!lenOnGrid(l)) {
			if (!align) {
				throw bad(index, "len", l, "len이 격자 밖(6의 배수 또는 16의 배수가 아님). align=true면 가장 가까운 6의 배수로 맞춤");
			}
			long a = Math.max(TICK_UNIT, roundTo6(l));
			if (alignedOut != null) {
				alignedOut.add(new Aligned(index, "len", l, a));
			}
			l = a;
		}
		if (t + l >= MMLEvent.MAX_TICK) {
			throw bad(index, "tick", t, "곡 길이 한도(" + MMLEvent.MAX_TICK + "틱)를 넘음");
		}

		int p = parsePitch(pitch, index);
		if (p < MIN_MIDI || p > MAX_MIDI) {
			throw bad(index, "pitch", pitch, "음높이가 MIDI " + MIN_MIDI + "~" + MAX_MIDI + "(B-1~B8) 밖");
		}

		int v = (vel == null) ? DEFAULT_VEL : vel;
		if (v < 0 || v > 15) {
			throw bad(index, "vel", vel, "세기는 0~15");
		}
		return new Resolved(index, (int) t, (int) l, p, v);
	}

	/** 가장 가까운 6의 배수(반은 올림). */
	static long roundTo6(long v) {
		return Math.floorDiv(v + 3, TICK_UNIT) * TICK_UNIT;
	}

	private static EngineException bad(int index, String field, Object value, String reason) {
		return new EngineException(BAD_NOTE, "음표 " + index + "번(0부터): " + reason,
				details("index", index, "field", field, "value", value == null ? null : String.valueOf(value), "reason", reason));
	}

	private static final Pattern POS = Pattern.compile("\\s*(\\d+)\\s*:\\s*(\\d+)\\s*:\\s*(\\d+)\\s*");

	private static long parsePos(MeasureMap map, String pos, int index) {
		Matcher m = POS.matcher(pos);
		if (!m.matches()) {
			throw bad(index, "pos", pos, "pos 형식은 \"마디:박:틱\"(예 \"28:3:48\")");
		}
		try {
			return map.tickOf(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)));
		} catch (IllegalArgumentException e) {
			throw bad(index, "pos", pos, "마디 안에 없는 위치: " + e.getMessage());
		}
	}

	private static final Pattern LEN = Pattern.compile("\\d+\\.*(&\\d+\\.*)*");

	/** 정수(틱) 또는 MML 길이 문자열("4", "8.", "4&16") → 틱. */
	public static long parseLen(Object len, int index) {
		if (len == null) {
			throw bad(index, "len", null, "len이 필요함");
		}
		if (len instanceof Number n) {
			return exactLong(n, index, "len");
		}
		String s = len.toString().trim();
		if (!LEN.matcher(s).matches()) {
			throw bad(index, "len", s, "len은 정수 틱 또는 MML 길이(\"4\", \"8.\", \"4&16\")");
		}
		long total = 0;
		for (String part : s.split("&")) {
			try {
				total += MMLTicks.getTick(part);
			} catch (MMLException | RuntimeException e) {
				throw bad(index, "len", s, "MML 길이를 틱으로 바꿀 수 없음: " + part);
			}
		}
		return total;
	}

	private static long exactLong(Number n, int index, String field) {
		double d = n.doubleValue();
		if (d != Math.rint(d) || Double.isInfinite(d)) {
			throw bad(index, field, n, field + "는 정수여야 함");
		}
		return (long) d;
	}

	private static final Pattern NAME = Pattern.compile("([A-Ga-g])([#+b\\-]*)(\\d+)");
	private static final int[] NATURAL = { 9, 11, 0, 2, 4, 5, 7 }; // A B C D E F G

	/** 정수(MIDI) 또는 음 이름("C4", "F#4", "Bb3", "c+4", "E-3") → MIDI 번호. 이름의 옥타브는 0 이상(C4 = 60). */
	public static int parsePitch(Object pitch, int index) {
		if (pitch == null) {
			throw bad(index, "pitch", null, "pitch가 필요함");
		}
		if (pitch instanceof Number n) {
			return (int) exactLong(n, index, "pitch");
		}
		String s = pitch.toString().trim();
		if (s.matches("-?\\d+")) {
			return Integer.parseInt(s);
		}
		Matcher m = NAME.matcher(s);
		if (!m.matches()) {
			throw bad(index, "pitch", s, "음 이름 형식은 \"C4\", \"F#4\", \"Bb3\"(# + 는 올림, b - 는 내림)");
		}
		int base = NATURAL[Character.toUpperCase(m.group(1).charAt(0)) - 'A'];
		int acc = 0;
		for (char c : m.group(2).toCharArray()) {
			acc += (c == '#' || c == '+') ? 1 : -1;
		}
		int octave = Integer.parseInt(m.group(3));
		return (octave + 1) * 12 + base + acc;
	}

	private static final String[] NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	/** MIDI 번호 → 음 이름("C4", "F#4"). 올림표로 쓴다. */
	public static String pitchName(int midi) {
		int oct = Math.floorDiv(midi, 12) - 1;
		return NAMES[Math.floorMod(midi, 12)] + oct;
	}

	/** 엔진 음표 → 응답용 음표. */
	public static NoteView view(MMLNoteEvent e, MeasureMap map) {
		int midi = e.getNote() + 12;
		return new NoteView(e.getTickOffset(), map.position(e.getTickOffset()).toString(), e.getTick(), midi,
				pitchName(midi), e.getVelocity());
	}
}
