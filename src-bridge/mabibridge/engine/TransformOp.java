package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jp.fourthline.mmlTools.MMLNoteEvent;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_transform}의 연산 하나(검증된 인자). {@link #apply}가 한 파트의 음표 목록을 그 자리에서 바꾼다.
 * {@code score_fit_suggest}의 시험 적용도 같은 {@link #apply}를 쓰므로 수치가 같다.
 *
 * <p>공통 규칙: 범위 [fromTick, toTick) 안에서 <b>시작하는</b> 음만 바꾼다. "다음 음"은 같은 파트에서 바로 뒤에 시작하는 음
 * (범위 밖이어도)이고, 길이 연산은 음 끝이 다음 음 시작을 넘지 않게 한다. 틱은 6의 배수(64분음표)를 지킨다.
 * 미세 조정 음(tuning note, 엔진의 64분 분할 표시)은 길이 연산에서 건드리지 않는다.</p>
 */
public final class TransformOp {

	public static final String VELOCITY_OFFSET = "velocity_offset";
	public static final String VELOCITY_QUANTIZE = "velocity_quantize";
	public static final String VELOCITY_MIN_CHANGE = "velocity_min_change";
	public static final String LENGTH_SCALE = "length_scale";
	public static final String MIN_NOTE_LEN = "min_note_len";
	public static final String LENGTH_GRID = "length_grid";
	public static final String DROP_BELOW_VEL = "drop_below_vel";

	public static final List<String> OPS = List.of(VELOCITY_OFFSET, VELOCITY_QUANTIZE, VELOCITY_MIN_CHANGE, LENGTH_SCALE,
			MIN_NOTE_LEN, LENGTH_GRID, DROP_BELOW_VEL);

	private static final Map<String, Set<String>> KEYS = Map.of(
			VELOCITY_OFFSET, Set.of("delta"),
			VELOCITY_QUANTIZE, Set.of("step", "keep_if_single_level"),
			VELOCITY_MIN_CHANGE, Set.of("delta"),
			LENGTH_SCALE, Set.of("ratio", "min_len_ticks"),
			MIN_NOTE_LEN, Set.of("ticks", "mode"),
			LENGTH_GRID, Set.of("ticks"),
			DROP_BELOW_VEL, Set.of("v"));

	/** 바뀐 음 하나(응답 예시용). change는 "velocity", "length", "deleted". */
	public record Change(String part, int tick, String pos, String name, String change, int from, int to) {}

	private final String op;
	private final Map<String, Object> params;
	private final int i1; // delta·step·ticks·v
	private final int i2; // min_len_ticks
	private final double ratio;
	private final boolean flag; // min_note_len: extend, velocity_quantize: keep_if_single_level

	private TransformOp(String op, Map<String, Object> params, int i1, int i2, double ratio, boolean flag) {
		this.op = op;
		this.params = params;
		this.i1 = i1;
		this.i2 = i2;
		this.ratio = ratio;
		this.flag = flag;
	}

	public String op() {
		return op;
	}

	/** 정규화한 인자(응답·계획에 그대로 쓴다). */
	public Map<String, Object> params() {
		return params;
	}

	@Override
	public String toString() {
		return op + params;
	}

	// ======================================================================
	// 만들기·검증
	// ======================================================================

	/**
	 * 이름과 인자(값은 Number 또는 String)로 만든다. 모르는 연산·모르는 인자·빠진 인자·범위 밖 값은 BAD_ARGUMENT.
	 */
	public static TransformOp of(String op, Map<String, Object> params) {
		String o = op == null ? "" : op.trim().toLowerCase(Locale.ROOT);
		if (!KEYS.containsKey(o)) {
			throw new EngineException(BAD_ARGUMENT, "모르는 op: " + op + " (쓸 수 있는 op: " + OPS + ")",
					details("op", op, "ops", OPS));
		}
		Map<String, Object> p = params == null ? Map.of() : params;
		for (String k : p.keySet()) {
			if (!KEYS.get(o).contains(k)) {
				throw new EngineException(BAD_ARGUMENT, o + "의 모르는 인자: " + k + " (쓸 수 있는 인자: " + KEYS.get(o) + ")",
						details("op", o, "param", k, "allowed", KEYS.get(o)));
			}
		}
		Map<String, Object> norm = new LinkedHashMap<>();
		switch (o) {
		case VELOCITY_OFFSET -> {
			int d = intParam(o, p, "delta", -15, 15, null);
			norm.put("delta", d);
			return new TransformOp(o, norm, d, 0, 0, false);
		}
		case VELOCITY_QUANTIZE -> {
			int s = intParam(o, p, "step", 2, 15, null);
			boolean keep = boolParam(o, p, "keep_if_single_level", true);
			norm.put("step", s);
			norm.put("keep_if_single_level", keep);
			return new TransformOp(o, norm, s, 0, 0, keep);
		}
		case VELOCITY_MIN_CHANGE -> {
			int d = intParam(o, p, "delta", 1, 15, null);
			norm.put("delta", d);
			return new TransformOp(o, norm, d, 0, 0, false);
		}
		case LENGTH_SCALE -> {
			double r = doubleParam(o, p, "ratio", 0.1, 4.0);
			int m = intParam(o, p, "min_len_ticks", 6, 384, 6);
			check6(o, "min_len_ticks", m);
			norm.put("ratio", r);
			norm.put("min_len_ticks", m);
			return new TransformOp(o, norm, 0, m, r, false);
		}
		case MIN_NOTE_LEN -> {
			int t = intParam(o, p, "ticks", 6, 384, null);
			check6(o, "ticks", t);
			Object mode = p.get("mode");
			String md = (mode instanceof String ms) ? ms.trim().toLowerCase(Locale.ROOT) : null;
			if (!"delete".equals(md) && !"extend".equals(md)) {
				throw new EngineException(BAD_ARGUMENT, o + "의 mode는 \"delete\" 또는 \"extend\"", details("op", o, "mode", mode));
			}
			norm.put("ticks", t);
			norm.put("mode", md);
			return new TransformOp(o, norm, t, 0, 0, "extend".equals(md));
		}
		case LENGTH_GRID -> {
			int t = intParam(o, p, "ticks", 6, 384, null);
			check6(o, "ticks", t);
			norm.put("ticks", t);
			return new TransformOp(o, norm, t, 0, 0, false);
		}
		case DROP_BELOW_VEL -> {
			int v = intParam(o, p, "v", 0, 14, null);
			norm.put("v", v);
			return new TransformOp(o, norm, v, 0, 0, false);
		}
		default -> throw new IllegalStateException(o);
		}
	}

	private static void check6(String op, String k, int v) {
		if (v % NoteSpec.TICK_UNIT != 0) {
			throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 6의 배수(64분음표 단위)", details("op", op, k, v));
		}
	}

	private static int intParam(String op, Map<String, Object> p, String k, int min, int max, Integer def) {
		Object v = p.get(k);
		if (v == null) {
			if (def != null) {
				return def;
			}
			throw new EngineException(BAD_ARGUMENT, op + "에는 params." + k + "가 필요함(정수 " + min + "~" + max + ")",
					details("op", op, "param", k));
		}
		long n;
		// 엄격: JSON 수만 받는다(문자열 "2"는 거부, 다른 도구 인자와 같음)
		if (v instanceof Number num && !Double.isInfinite(num.doubleValue()) && num.doubleValue() == Math.rint(num.doubleValue())
				&& Math.abs(num.doubleValue()) < 1e9) {
			n = (long) num.doubleValue();
		} else {
			throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 정수(JSON 수, 문자열 불가): " + v,
					details("op", op, "param", k, "value", v));
		}
		if (n < min || n > max) {
			throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 " + min + "~" + max + ": " + n,
					details("op", op, "param", k, "value", n, "min", min, "max", max));
		}
		return (int) n;
	}

	private static boolean boolParam(String op, Map<String, Object> p, String k, boolean def) {
		Object v = p.get(k);
		if (v == null) {
			return def;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 true 또는 false(JSON 참거짓, 문자열 불가): " + v,
				details("op", op, "param", k, "value", v));
	}

	private static double doubleParam(String op, Map<String, Object> p, String k, double min, double max) {
		Object v = p.get(k);
		double d;
		if (v instanceof Number num) {
			d = num.doubleValue();
		} else if (v == null) {
			throw new EngineException(BAD_ARGUMENT, op + "에는 params." + k + "가 필요함(수 " + min + "~" + max + ")",
					details("op", op, "param", k));
		} else {
			throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 수(JSON 수, 문자열 불가): " + v,
					details("op", op, "param", k, "value", v));
		}
		if (!(d >= min && d <= max)) {
			throw new EngineException(BAD_ARGUMENT, op + "의 " + k + "는 " + min + "~" + max + ": " + d,
					details("op", op, "param", k, "value", d, "min", min, "max", max));
		}
		return d;
	}

	// ======================================================================
	// 적용
	// ======================================================================

	/**
	 * 한 파트 음표 목록(시작 순, 겹침 없음)을 그 자리에서 바꾼다.
	 *
	 * @return 바뀐 음(시작 순)
	 */
	public List<Change> apply(String part, List<MMLNoteEvent> notes, long fromTick, long toTick, MeasureMap map) {
		List<Change> ch = new ArrayList<>();
		switch (op) {
		case VELOCITY_OFFSET -> {
			for (MMLNoteEvent e : notes) {
				if (in(e, fromTick, toTick)) {
					setVel(part, e, clampVel(e.getVelocity() + i1), map, ch);
				}
			}
		}
		case VELOCITY_QUANTIZE -> {
			// keep_if_single_level: 범위 안 음이 세기 하나만 쓰면 v 명령은 이미 하나뿐이라 바꾸지 않는다(v8 → v9처럼 늘기만 함)
			if (flag && notes.stream().filter(e -> in(e, fromTick, toTick)).map(MMLNoteEvent::getVelocity).distinct()
					.count() <= 1) {
				break;
			}
			for (MMLNoteEvent e : notes) {
				if (in(e, fromTick, toTick)) {
					setVel(part, e, clampVel(quantize(e.getVelocity(), i1)), map, ch);
				}
			}
		}
		case VELOCITY_MIN_CHANGE -> {
			// 앞 음의 세기(바뀐 뒤 값)를 들고 가며, 차이가 delta보다 작은 변화는 앞 세기로 바꾼다 → MML의 v 명령이 줄어든다.
			Integer cur = null;
			for (MMLNoteEvent e : notes) {
				if (e.getTickOffset() >= toTick) {
					break;
				}
				if (!in(e, fromTick, toTick)) {
					cur = e.getVelocity();
					continue;
				}
				int v = e.getVelocity();
				if (cur != null && v != cur && Math.abs(v - cur) < i1) {
					setVel(part, e, cur, map, ch);
				} else {
					cur = v;
				}
			}
		}
		case LENGTH_SCALE -> {
			for (int i = 0; i < notes.size(); i++) {
				MMLNoteEvent e = notes.get(i);
				if (!in(e, fromTick, toTick) || e.isTuningNote()) {
					continue;
				}
				int len = e.getTick();
				int target = (int) (Math.floor(len * ratio / NoteSpec.TICK_UNIT + 0.5) * NoteSpec.TICK_UNIT);
				target = Math.max(target, Math.min(i2, len)); // min_len 아래로 줄이지 않음(원래 더 짧던 음은 늘리지 않음)
				target = Math.max(target, NoteSpec.TICK_UNIT);
				target = Math.min(target, room(notes, i));
				setLen(part, e, target, map, ch);
			}
		}
		case MIN_NOTE_LEN -> {
			for (int i = 0; i < notes.size(); i++) {
				MMLNoteEvent e = notes.get(i);
				if (!in(e, fromTick, toTick) || e.isTuningNote() || e.getTick() >= i1) {
					continue;
				}
				if (flag) {
					if (room(notes, i) >= i1) {
						setLen(part, e, i1, map, ch);
					}
				} else {
					ch.add(new Change(part, e.getTickOffset(), map.position(e.getTickOffset()).toString(), name(e), "deleted",
							e.getTick(), 0));
					notes.remove(i);
					i--;
				}
			}
		}
		case LENGTH_GRID -> {
			for (int i = 0; i < notes.size(); i++) {
				MMLNoteEvent e = notes.get(i);
				if (!in(e, fromTick, toTick) || e.isTuningNote()) {
					continue;
				}
				long base = map.measureStart(map.measureOf(e.getTickOffset()));
				long start = e.getTickOffset();
				long end = e.getEndTick();
				long limit = start + room(notes, i);
				long g = i1;
				long near = base + Math.floorDiv(end - base + g / 2, g) * g;
				if (near <= start) {
					near = base + (Math.floorDiv(start - base, g) + 1) * g;
				}
				if (near > limit) {
					near = base + Math.floorDiv(limit - base, g) * g;
				}
				if (near <= start) {
					continue; // 다음 음까지 격자 점이 없음 → 그대로
				}
				setLen(part, e, (int) (near - start), map, ch);
			}
		}
		case DROP_BELOW_VEL -> {
			for (int i = 0; i < notes.size(); i++) {
				MMLNoteEvent e = notes.get(i);
				if (in(e, fromTick, toTick) && e.getVelocity() <= i1) {
					ch.add(new Change(part, e.getTickOffset(), map.position(e.getTickOffset()).toString(), name(e), "deleted",
							e.getVelocity(), 0));
					notes.remove(i);
					i--;
				}
			}
		}
		default -> throw new IllegalStateException(op);
		}
		return ch;
	}

	/** step의 배수 중 v에 가장 가까운 값(같으면 낮은 쪽). 0~15로 자르는 것은 부르는 쪽. */
	static int quantize(int v, int step) {
		int lo = Math.floorDiv(v, step) * step;
		int hi = lo + step;
		return (v - lo <= hi - v) ? lo : hi;
	}

	private static boolean in(MMLNoteEvent e, long from, long to) {
		return e.getTickOffset() >= from && e.getTickOffset() < to;
	}

	/** 음 i가 가질 수 있는 최대 길이(다음 음 시작까지, 마지막 음이면 엔진 한도까지). */
	private static int room(List<MMLNoteEvent> notes, int i) {
		MMLNoteEvent e = notes.get(i);
		if (i + 1 < notes.size()) {
			return notes.get(i + 1).getTickOffset() - e.getTickOffset();
		}
		return Math.max(e.getTick(), jp.fourthline.mmlTools.MMLEvent.MAX_TICK - 1 - e.getTickOffset());
	}

	private static int clampVel(int v) {
		return Math.max(0, Math.min(MMLNoteEvent.MAX_VOL, v));
	}

	private static String name(MMLNoteEvent e) {
		return NoteSpec.pitchName(e.getNote() + 12);
	}

	private static void setVel(String part, MMLNoteEvent e, int v, MeasureMap map, List<Change> ch) {
		if (v != e.getVelocity()) {
			ch.add(new Change(part, e.getTickOffset(), map.position(e.getTickOffset()).toString(), name(e), "velocity",
					e.getVelocity(), v));
			e.setVelocity(v);
		}
	}

	private static void setLen(String part, MMLNoteEvent e, int len, MeasureMap map, List<Change> ch) {
		if (len != e.getTick() && len > 0) {
			ch.add(new Change(part, e.getTickOffset(), map.position(e.getTickOffset()).toString(), name(e), "length",
					e.getTick(), len));
			e.setTick(len);
		}
	}
}
