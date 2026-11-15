package mabibridge.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 틱 ↔ (마디, 박, 박 안의 틱) 양방향 변환. 박자 변경을 반영한다.
 *
 * <p>한 인스턴스는 한 가지 틱 단위(원본 해상도, 또는 4분음표 = 96틱)로 동작한다.
 * 원본 해상도 지도에서 {@link #to96(int)}로 96틱 지도를 얻는다.</p>
 *
 * <p>박자 변경이 마디 중간에 오면, 그 직전 마디는 그 지점에서 잘린 "짧은 마디"로 보고
 * 변경 지점부터 새 마디를 센다(SMF는 박자 메타가 놓인 틱부터 새 박자가 적용된다).
 * 이런 지점은 {@link #irregularChanges()}로 알려 준다.</p>
 */
public final class MeasureMap {

	/** MabiIcco 내부 해상도(4분음표당 틱). */
	public static final int TPQN96 = 96;

	/** 박자 변경 하나(이 지도의 틱 단위). */
	public record Signature(long tick, int numerator, int denominator) {
		public Signature {
			if (tick < 0) {
				throw new IllegalArgumentException("tick < 0: " + tick);
			}
			if (numerator <= 0) {
				throw new IllegalArgumentException("numerator <= 0: " + numerator);
			}
			if (denominator <= 0 || Integer.bitCount(denominator) != 1) {
				throw new IllegalArgumentException("denominator must be a power of two: " + denominator);
			}
		}
	}

	/** 마디·박 위치. 마디·박은 1부터, tickInBeat는 0부터(이 지도의 틱 단위). */
	public record Position(int measure, int beat, long tickInBeat) {
		/** "마디:박:틱" 형식. */
		@Override
		public String toString() {
			return measure + ":" + beat + ":" + tickInBeat;
		}
	}

	private record Segment(long startTick, int startMeasure, int numerator, int denominator,
			long beatLength, long measureLength) {}

	private final int ticksPerQuarter;
	private final List<Segment> segments;
	private final List<Signature> signatures;
	private final List<Long> irregular;

	private MeasureMap(int ticksPerQuarter, List<Segment> segments, List<Signature> signatures, List<Long> irregular) {
		this.ticksPerQuarter = ticksPerQuarter;
		this.segments = segments;
		this.signatures = signatures;
		this.irregular = irregular;
	}

	/**
	 * 지도를 만든다.
	 *
	 * @param ticksPerQuarter 4분음표당 틱
	 * @param sigs 박자 변경 목록(순서 무관). 틱 0에 박자가 없으면 4/4로 시작한다.
	 *             같은 틱에 여러 개가 있으면 목록에서 뒤에 있는 것이 이긴다.
	 *             바로 앞 박자와 같은 박자는 무시한다(마디를 끊지 않는다).
	 */
	public static MeasureMap of(int ticksPerQuarter, List<Signature> sigs) {
		if (ticksPerQuarter <= 0) {
			throw new IllegalArgumentException("ticksPerQuarter <= 0: " + ticksPerQuarter);
		}
		List<Signature> sorted = new ArrayList<>(sigs);
		// 안정 정렬: 같은 틱이면 원래 순서 유지 → 뒤의 것이 이긴다.
		sorted.sort(Comparator.comparingLong(Signature::tick));
		List<Signature> effective = new ArrayList<>();
		for (Signature s : sorted) {
			if (!effective.isEmpty() && effective.get(effective.size() - 1).tick() == s.tick()) {
				effective.set(effective.size() - 1, s);
			} else {
				effective.add(s);
			}
		}
		if (effective.isEmpty() || effective.get(0).tick() != 0) {
			effective.add(0, new Signature(0, 4, 4));
		}

		List<Segment> segs = new ArrayList<>();
		List<Signature> kept = new ArrayList<>();
		List<Long> irregular = new ArrayList<>();
		for (Signature s : effective) {
			long beatLen = (long) ticksPerQuarter * 4 / s.denominator();
			if (beatLen <= 0) {
				throw new IllegalArgumentException("beat length is zero for denominator " + s.denominator()
						+ " at " + ticksPerQuarter + " ticks per quarter");
			}
			long measureLen = beatLen * s.numerator();
			if (segs.isEmpty()) {
				segs.add(new Segment(0, 1, s.numerator(), s.denominator(), beatLen, measureLen));
				kept.add(s);
				continue;
			}
			Segment prev = segs.get(segs.size() - 1);
			if (prev.numerator() == s.numerator() && prev.denominator() == s.denominator()) {
				continue; // 같은 박자 반복은 무시
			}
			long offset = s.tick() - prev.startTick();
			long whole = offset / prev.measureLength();
			long rem = offset % prev.measureLength();
			int startMeasure = (int) (prev.startMeasure() + whole + (rem != 0 ? 1 : 0));
			if (rem != 0) {
				irregular.add(s.tick());
			}
			segs.add(new Segment(s.tick(), startMeasure, s.numerator(), s.denominator(), beatLen, measureLen));
			kept.add(s);
		}
		return new MeasureMap(ticksPerQuarter, Collections.unmodifiableList(segs),
				Collections.unmodifiableList(kept), Collections.unmodifiableList(irregular));
	}

	/**
	 * 원본 해상도 틱을 4분음표 = 96틱으로 바꾼다. MabiIcco MIDI 가져오기와 같은 규칙:
	 * {@code srcTick*96/resolution}을 정수 내림한 뒤 6틱 단위로 반올림({@code v += 3; v -= v % 6}).
	 */
	public static long convTick96(long srcTick, int resolution) {
		long value = srcTick * TPQN96 / resolution;
		value += 3;
		value -= value % 6;
		return value;
	}

	/** 같은 박자 구조를 4분음표 = 96틱 단위로 옮긴 지도. 박자 변경 틱은 {@link #convTick96}로 바꾼다. */
	public MeasureMap to96(int srcResolution) {
		if (ticksPerQuarter != srcResolution) {
			throw new IllegalArgumentException("this map is " + ticksPerQuarter + " ticks per quarter, not " + srcResolution);
		}
		List<Signature> sigs = new ArrayList<>();
		for (Signature s : signatures) {
			sigs.add(new Signature(convTick96(s.tick(), srcResolution), s.numerator(), s.denominator()));
		}
		return of(TPQN96, sigs);
	}

	public int ticksPerQuarter() {
		return ticksPerQuarter;
	}

	/** 실제로 적용되는 박자 변경 목록(중복·반복 제거 후). */
	public List<Signature> signatures() {
		return signatures;
	}

	/** 마디 중간에 놓인 박자 변경의 틱 목록. */
	public List<Long> irregularChanges() {
		return irregular;
	}

	private Segment segmentForTick(long tick) {
		Segment found = segments.get(0);
		for (Segment s : segments) {
			if (s.startTick() <= tick) {
				found = s;
			} else {
				break;
			}
		}
		return found;
	}

	private int segmentIndexForMeasure(int measure) {
		int idx = 0;
		for (int i = 0; i < segments.size(); i++) {
			if (segments.get(i).startMeasure() <= measure) {
				idx = i;
			} else {
				break;
			}
		}
		return idx;
	}

	/** 틱의 위치. 음수 틱은 허용하지 않는다. */
	public Position position(long tick) {
		if (tick < 0) {
			throw new IllegalArgumentException("tick < 0: " + tick);
		}
		Segment s = segmentForTick(tick);
		long off = tick - s.startTick();
		long m = off / s.measureLength();
		long inMeasure = off % s.measureLength();
		long beat = inMeasure / s.beatLength();
		long inBeat = inMeasure % s.beatLength();
		return new Position(Math.toIntExact(s.startMeasure() + m), (int) beat + 1, inBeat);
	}

	/** 틱이 속한 마디(1부터). */
	public int measureOf(long tick) {
		return position(tick).measure();
	}

	/** 마디 시작 틱. */
	public long measureStart(int measure) {
		if (measure < 1) {
			throw new IllegalArgumentException("measure < 1: " + measure);
		}
		Segment s = segments.get(segmentIndexForMeasure(measure));
		return s.startTick() + (long) (measure - s.startMeasure()) * s.measureLength();
	}

	/** 마디 끝 틱(다음 마디 시작, 배타). 박자 변경으로 잘린 마디는 변경 지점까지. */
	public long measureEnd(int measure) {
		int idx = segmentIndexForMeasure(measure);
		long end = measureStart(measure) + segments.get(idx).measureLength();
		if (idx + 1 < segments.size()) {
			end = Math.min(end, segments.get(idx + 1).startTick());
		}
		return end;
	}

	/** 마디의 박자 분자. */
	public int numeratorAt(int measure) {
		return segments.get(segmentIndexForMeasure(measure)).numerator();
	}

	/** 마디의 박자 분모. */
	public int denominatorAt(int measure) {
		return segments.get(segmentIndexForMeasure(measure)).denominator();
	}

	/** 마디의 한 박 길이(이 지도의 틱 단위). */
	public long beatLength(int measure) {
		return segments.get(segmentIndexForMeasure(measure)).beatLength();
	}

	/** 마디 안의 박 수. 잘린 마디는 실제로 들어 있는 박 수(마지막 박이 짧을 수 있음). */
	public int beatsInMeasure(int measure) {
		long len = measureEnd(measure) - measureStart(measure);
		long bl = beatLength(measure);
		return (int) ((len + bl - 1) / bl);
	}

	/**
	 * (마디, 박, 박 안의 틱) → 틱.
	 *
	 * @throws IllegalArgumentException 마디 &lt; 1, 박이 마디 박 수 범위 밖, 박 안의 틱이 0~박 길이-1 밖일 때
	 */
	public long tickOf(int measure, int beat, long tickInBeat) {
		if (measure < 1) {
			throw new IllegalArgumentException("measure < 1: " + measure);
		}
		int beats = beatsInMeasure(measure);
		if (beat < 1 || beat > beats) {
			throw new IllegalArgumentException("beat " + beat + " out of 1.." + beats + " in measure " + measure);
		}
		long bl = beatLength(measure);
		if (tickInBeat < 0 || tickInBeat >= bl) {
			throw new IllegalArgumentException("tickInBeat " + tickInBeat + " out of 0.." + (bl - 1));
		}
		long tick = measureStart(measure) + (long) (beat - 1) * bl + tickInBeat;
		if (tick >= measureEnd(measure)) {
			throw new IllegalArgumentException("position " + measure + ":" + beat + ":" + tickInBeat
					+ " is past the end of a shortened measure");
		}
		return tick;
	}

	/** {@link #tickOf(int, int, long)}의 Position판. */
	public long tickOf(Position p) {
		return tickOf(p.measure(), p.beat(), p.tickInBeat());
	}
}
