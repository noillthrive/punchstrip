package mabibridge.midi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 참조 MIDI를 고정 템포 격자로 다시 놓기(reference_regrid).
 *
 * <p>음표마다 원본 템포 지도로 잰 벽시계 시각 {@code sec = secondsAt(srcTick) + offsetSec}(오디오 시각 = MIDI 시각 +
 * offsetSec)을 고정 격자 {@code tick = tick0 + (sec - firstDownbeatSec) / (60 / bpm) * 96}에 놓는다
 * ({@code tick0} = measureOffset 마디의 시작 틱). 음 끝도 같은 식(길이 = 끝 - 시작, 최소 1틱). 시작이 0틱보다
 * 6틱 넘게 앞이면 버리고(못갖춘마디 앞 음), 그 사이면 0틱에 붙인다. 음표 아닌 이벤트도 같은 식으로 옮기고(앞이면 0),
 * 템포·박자는 하나(bpm, time signature)로 바꾼다. 양자화는 하지 않는다(reference_cleanup).</p>
 *
 * <p>통계: 음 시작의 16분 격자 거리(원본 자기 격자 = srcTick*96/해상도 / 새 격자, 둘 다 6틱 반올림 전 값), 음마다 자기 격자 위치와 새
 * 위치의 차(일정한 차 = 중앙값을 뺀 흔들림), 원본 마디줄(마디 시작)을 새 격자에 놓았을 때 자기 위치와의 차의
 * 흔들림 — 다시 놓은 뒤의 마디줄은 고정 격자 그 자체라 흔들림 0.</p>
 */
public final class Regrid {

	public static final int SIXTEENTH = 24;
	static final double SNAP_BEFORE_START = 6.0;

	/** 다시 놓기 값. */
	public record Params(double bpm, double firstDownbeatSec, int numerator, int denominator, int measureOffset,
			double offsetSec) {
		public long ticksPerMeasure() {
			return (long) numerator * (4L * MeasureMap.TPQN96 / denominator);
		}

		public long tick0() {
			return (measureOffset - 1L) * ticksPerMeasure();
		}

		/** 원본 틱 → 새 틱(반올림 전). */
		public double map(ReferenceMidi raw, long srcTick) {
			double sec = raw.secondsAt(srcTick) + offsetSec;
			return tick0() + (sec - firstDownbeatSec) / (60.0 / bpm) * MeasureMap.TPQN96;
		}
	}

	/** 결과: 새 SMF 바이트, 통계, 개수. */
	public record Output(byte[] bytes, Map<String, Object> stats, int notesIn, int notesWritten,
			int droppedBeforeStart, int snappedToZero, int overlapsTrimmed) {}

	private Regrid() {}

	public static Output run(ReferenceMidi raw, byte[] rawBytes, Params p) throws ReferenceMidiException {
		Map<Integer, List<ReferenceRewrite.NoteOut>> by = new HashMap<>();
		int dropped = 0;
		int snapped = 0;
		for (Note n : raw.notesAll()) {
			double a = p.map(raw, n.srcTick());
			double b = p.map(raw, n.srcEnd());
			if (a < -SNAP_BEFORE_START) {
				dropped++;
				continue;
			}
			long t = Math.round(a);
			if (t < 0) {
				t = 0;
				snapped++;
			}
			long len = Math.max(1, Math.round(b) - t);
			by.computeIfAbsent(n.track(), k -> new ArrayList<>())
					.add(new ReferenceRewrite.NoteOut(n.channel(), n.pitch(), n.velocity(), t, len));
		}
		ReferenceRewrite.Result r = ReferenceRewrite.write(rawBytes, x -> p.map(raw, x),
				new ReferenceRewrite.FixedTiming(p.bpm(), p.numerator(), p.denominator()), by);
		return new Output(r.bytes(), stats(raw, p), raw.notesAll().size(), r.notesWritten(), dropped, snapped,
				r.overlapsTrimmed());
	}

	/** 16분 격자까지 거리(틱, 0~12). */
	static double d16(double t) {
		double m = t - SIXTEENTH * Math.floor(t / SIXTEENTH);
		return Math.min(m, SIXTEENTH - m);
	}

	static Map<String, Object> dist(double[] v) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("n", v.length);
		if (v.length == 0) {
			return o;
		}
		double[] s = v.clone();
		Arrays.sort(s);
		o.put("median", round1(pct(s, 50)));
		o.put("p90", round1(pct(s, 90)));
		o.put("max", round1(s[s.length - 1]));
		return o;
	}

	static double pct(double[] sorted, double q) {
		if (sorted.length == 1) {
			return sorted[0];
		}
		double pos = q / 100.0 * (sorted.length - 1);
		int i = (int) Math.floor(pos);
		int j = Math.min(sorted.length - 1, i + 1);
		return sorted[i] + (sorted[j] - sorted[i]) * (pos - i);
	}

	static double round1(double x) {
		return Math.round(x * 10.0) / 10.0;
	}

	static double median(double[] v) {
		double[] s = v.clone();
		Arrays.sort(s);
		return s.length == 0 ? 0 : pct(s, 50);
	}

	static Map<String, Object> onsetBlock(double[] before, double[] after) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("before_own_grid", dist(before));
		o.put("after_fixed_grid", dist(after));
		o.put("within_6_ticks_pct_before", pctWithin(before, 6));
		o.put("within_6_ticks_pct_after", pctWithin(after, 6));
		return o;
	}

	static double pctWithin(double[] v, double lim) {
		if (v.length == 0) {
			return 0;
		}
		int k = 0;
		for (double x : v) {
			if (x <= lim) {
				k++;
			}
		}
		return round1(100.0 * k / v.length);
	}

	/** 다시 놓기 전후 통계(음 시작 격자 거리, 음 위치 흔들림, 마디줄 흔들림; 전체와 트랙별). */
	public static Map<String, Object> stats(ReferenceMidi raw, Params p) {
		List<Note> all = raw.notesAll();
		Map<Integer, List<Note>> byTrack = new LinkedHashMap<>();
		for (Note n : all) {
			byTrack.computeIfAbsent(n.track(), k -> new ArrayList<>()).add(n);
		}
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("unit", "ticks (quarter = 96, 16th = 24)");
		double[] before = new double[all.size()];
		double[] after = new double[all.size()];
		double[] shift = new double[all.size()];
		for (int i = 0; i < all.size(); i++) {
			Note n = all.get(i);
			double m = p.map(raw, n.srcTick());
			before[i] = d16(n.srcTick() * (double) MeasureMap.TPQN96 / raw.resolution());
			after[i] = d16(m);
			shift[i] = m - n.tick96();
		}
		o.put("onset_offset_from_16th", onsetBlock(before, after));
		double c = median(shift);
		double[] wob = new double[shift.length];
		for (int i = 0; i < shift.length; i++) {
			wob[i] = Math.abs(shift[i] - c);
		}
		Map<String, Object> ps = new LinkedHashMap<>();
		ps.put("constant_shift_ticks", round1(c));
		ps.put("wander_before", dist(wob));
		ps.put("wander_after", "0 (the regridded reference's own grid is the fixed grid)");
		o.put("note_position_shift", ps);
		// barlines of the source tempo map on the fixed grid
		MeasureMap src = raw.measureMap();
		MeasureMap m96 = raw.measureMap96();
		int mc = raw.measureCount();
		double[] dev = new double[mc];
		for (int k = 1; k <= mc; k++) {
			dev[k - 1] = p.map(raw, src.measureStart(k)) - m96.measureStart(k);
		}
		double cb = median(dev);
		double[] bw = new double[mc];
		double maxRun = 0;
		for (int k = 0; k < mc; k++) {
			bw[k] = Math.abs(dev[k] - cb);
			maxRun = Math.max(maxRun, bw[k]);
		}
		Map<String, Object> bl = new LinkedHashMap<>();
		bl.put("measures", mc);
		bl.put("constant_shift_ticks", round1(cb));
		bl.put("wander_before", dist(bw));
		bl.put("wander_before_sixteenths_max", round1(maxRun / SIXTEENTH));
		bl.put("wander_after", 0);
		o.put("barline_wander", bl);
		List<Map<String, Object>> per = new ArrayList<>();
		for (Map.Entry<Integer, List<Note>> e : byTrack.entrySet()) {
			List<Note> l = e.getValue();
			double[] b = new double[l.size()];
			double[] a = new double[l.size()];
			for (int i = 0; i < l.size(); i++) {
				b[i] = d16(l.get(i).srcTick() * (double) MeasureMap.TPQN96 / raw.resolution());
				a[i] = d16(p.map(raw, l.get(i).srcTick()));
			}
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("track", e.getKey());
			t.put("name", raw.track(e.getKey()).name());
			t.put("notes", l.size());
			t.put("drum", l.get(0).isDrum());
			t.putAll(onsetBlock(b, a));
			per.add(t);
		}
		o.put("tracks", per);
		return o;
	}
}
