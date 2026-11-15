package mabibridge.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import mabibridge.compare.Comparer.Options;
import mabibridge.compare.Comparer.Pairing;
import mabibridge.compare.Comparer.SNote;
import mabibridge.compare.Comparer.TrackData;
import mabibridge.compare.MappingSuggestion.Candidate;
import mabibridge.compare.MappingSuggestion.TrackSuggestion;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.Part;
import mabibridge.engine.Project;
import mabibridge.engine.SoundingPitch;
import mabibridge.midi.MeasureMap;
import mabibridge.midi.ReferenceMidi;

/**
 * 매핑 제안. 악보 트랙마다 참조 트랙 전부(드럼 제외)와 {@code match_parts: "any"} 기준으로
 * 짝을 맞춰 일치율(맞은 참조 음 ÷ 참조 음 수, 길이 0 음 제외)과 순위 점수를 매겨 상위 3개를 돌려준다. 상태를 갖지 않고, 악보·참조는 읽기만 한다.
 *
 * <p>짝짓기 규칙은 {@code compare}의 멜로디 일치와 같다(1:1, 같은 음·시작 차 ≤ 6틱이 matched). 참조 트랙 하나씩 따로 맞추므로
 * 같은 악보 음이 서로 다른 참조 트랙의 후보에서 각각 쓰일 수 있다(트랙끼리 경쟁시키지 않는다).</p>
 *
 * <p>{@link Options}의 pitch_mode(sounding이면 들리는 음으로 비교, 고정음·음높이 없음·효과음 악기 트랙은 제안하지 않음),
 * measure_offset(정수 또는 auto), octave_tolerant를 받는다. 후보마다 들리는(또는 적은) 음을 −2~+2옥타브 옮겨 가장 잘 맞는
 * {@code octaveShift}와 그때의 일치율을 함께 보고한다(이 탐색은 언제나 엄격한 같은 음 기준).</p>
 *
 * <p>순위는 일치율이 아니라 맞은 음 수가 들어가는 점수 {@code score = matched − 0.5 × (total − matched)}로 매긴다.
 * 참조 음이 {@code max(8, 악보 트랙 음 수의 10%)}보다 적은 후보는 {@code small_sample}로 표시하고 뒤로 보낸다(참조 음 1개짜리
 * 트랙이 1/1 = 100%로 1위가 되던 문제). 정렬: small_sample 아님 먼저 → score 높은 순 → 일치율 → 맞은 수 → 참조 번호.</p>
 *
 * <p>small_sample 기준을 {@code min(max(8, ceil(악보 트랙 음 수 × 10%)), 악보 트랙 음 수)}로 바꿨다(악보 트랙 음이 8개보다
 * 적으면 그 수가 상한. 5음짜리 트랙이 5/5로 다 맞은 짝을 small_sample로 밀어내던 문제). 정렬은 네 묶음:
 * ① small_sample 아니고 score &gt; 0 → ② small_sample이고 score &gt; 0 → ③ small_sample 아니고 score ≤ 0
 * → ④ small_sample이고 score ≤ 0.
 * 묶음 안은 score → 일치율 → 맞은 수 → 참조 번호. 쓴 기준 수는 {@code TrackSuggestion.smallSampleThreshold}에 적는다.</p>
 */
public final class MappingSuggester {

	/** 악보 트랙마다 돌려주는 후보 수. */
	public static final int TOP = 3;
	/** octave_shift 탐색 범위(옥타브). */
	public static final int OCTAVE_RANGE = 2;
	/** small_sample 기준의 최소 참조 음 수. */
	public static final int SMALL_SAMPLE_MIN = 8;
	/** small_sample 기준의 악보 트랙 음 수 비율. */
	public static final double SMALL_SAMPLE_RATIO = 0.10;
	/** score에서 안 맞은 참조 음 하나가 빼는 값. */
	public static final double MISS_WEIGHT = 0.5;

	private MappingSuggester() {}

	/** 동작({@link Options#LEGACY}: written, 마디 차 0, 엄격). */
	public static MappingSuggestion suggest(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks,
			InstrumentTable table) {
		return suggest(p, ref, from, to, scoreTracks, table, Options.LEGACY);
	}

	/**
	 * @param p           악보(읽기만)
	 * @param ref         참조(null 아님)
	 * @param from        시작 마디(1부터, 참조 마디)
	 * @param to          끝 마디(포함, 참조 마디)
	 * @param scoreTracks 제안할 악보 트랙(null 또는 비어 있으면 전체)
	 * @param table       악기표(타악기 판정·악기 이름). null이면 타악기 판정 없음
	 * @param opt         선택(null이면 {@link Options#DEFAULTS}: sounding, 마디 차 0, 엄격)
	 * @throws IllegalArgumentException 마디 범위가 틀렸거나 악보 쪽 시작 마디가 1보다 작을 때
	 */
	public static MappingSuggestion suggest(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks,
			InstrumentTable table, Options opt) {
		if (p == null || ref == null) {
			throw new IllegalArgumentException("악보와 참조가 모두 필요함");
		}
		if (from < 1 || to < from) {
			throw new IllegalArgumentException("마디 범위가 틀림: " + from + "~" + to + " (1 이상, from ≤ to)");
		}
		Options o = (opt == null) ? Options.DEFAULTS : opt;
		String mode = (o.pitchMode() == null) ? Comparer.SOUNDING : o.pitchMode();
		List<String> skipped = new ArrayList<>();
		MeasureMap smap = p.measureMap();
		MeasureMap rmap = ref.measureMap96();
		if (!rmap.signatures().equals(smap.signatures())) {
			skipped.add("악보와 참조의 박자 구성이 달라 마디 위치가 어긋날 수 있음(짝짓기는 절대 틱으로 함)");
		}

		Map<String, Integer> offScores = null;
		int off;
		if (o.autoOffset()) {
			offScores = Comparer.autoOffsetScores(p, ref, Mapping.empty(), from, to, scoreTracks, table, o, mode);
			off = Comparer.bestOffset(offScores);
			skipped.addAll(Comparer.autoOffsetNotes(p, offScores, from, to, off, o.autoOffsetMax()));
		} else {
			off = (o.measureOffset() == null) ? 0 : o.measureOffset();
		}
		if (from + off < 1) {
			throw new IllegalArgumentException("악보 쪽 시작 마디가 1보다 작음: 참조 " + from + "마디 + measure_offset " + off);
		}
		long rs = smap.measureStart(from + off);
		long re = smap.measureEnd(to + off);
		long shift = Comparer.shiftOf(smap, rmap, from, off);

		// 후보 참조 트랙: 범위 안에 길이 있는 드럼 아닌 음이 있는 트랙
		List<String> drumOnly = new ArrayList<>();
		List<Integer> refTracks = Comparer.candidateRefTracks(ref, from, to, drumOnly);
		if (!drumOnly.isEmpty()) {
			skipped.add("드럼 채널(10) 음만 있는 참조 트랙 " + String.join(", ", drumOnly) + "번은 후보에서 뺌");
		}

		List<TrackData> all = Comparer.readTracks(p, table);
		List<TrackData> sel = new ArrayList<>();
		if (scoreTracks == null || scoreTracks.isEmpty()) {
			sel.addAll(all);
		} else {
			for (int n : new TreeSet<>(scoreTracks)) {
				if (n >= 1 && n <= all.size()) {
					sel.add(all.get(n - 1));
				} else {
					skipped.add("악보 트랙 " + n + "번이 없어 뺌");
				}
			}
		}

		List<TrackSuggestion> out = new ArrayList<>();
		Map<String, Map<String, Object>> mapping = new LinkedHashMap<>();
		for (TrackData tdw : sel) {
			int scoreNotes = 0;
			for (Part part : Part.values()) {
				for (SNote s : tdw.part(part)) {
					if (s.tick() >= rs && s.tick() < re) {
						scoreNotes++;
					}
				}
			}
			String inst = tdw.inst() == null ? null : tdw.inst().name();
			String sounding = SoundingPitch.summary(tdw.program());
			if (tdw.drum()) {
				out.add(new TrackSuggestion(tdw.number(), tdw.name(), tdw.program(), inst, true, scoreNotes, List.of(),
						"타악기 트랙이라 제안하지 않음(음 번호가 음높이가 아니라 북·심벌 종류)", sounding, null));
				continue;
			}
			if (!tdw.pitchComparable(mode)) {
				out.add(new TrackSuggestion(tdw.number(), tdw.name(), tdw.program(), inst, false, scoreNotes, List.of(),
						"고정음·음높이 없음·효과음 악기라 들리는 음으로 비교하지 않음(" + sounding + ")", sounding, null));
				continue;
			}
			if (scoreNotes == 0) {
				out.add(new TrackSuggestion(tdw.number(), tdw.name(), tdw.program(), inst, false, 0, List.of(),
						"이 범위에 악보 음이 없음", sounding, null));
				continue;
			}
			int threshold = smallSampleThreshold(scoreNotes);
			TrackData td = Comparer.SOUNDING.equals(mode) ? tdw.sounding() : tdw;
			List<Candidate> cands = new ArrayList<>();
			List<Candidate> octaveOnly = new ArrayList<>();
			for (int rt : refTracks) {
				Candidate c = score(td, rt, ref, from, to, rs, re, shift, o.octaveTolerant(), scoreNotes);
				if (c == null) {
					continue;
				}
				if (c.matched() > 0) {
					cands.add(c);
				} else if (c.octaveShiftMatched() > 0) {
					octaveOnly.add(c);
				}
			}
			// 순서: 묶음(① small 아님·score > 0 → ② small·score > 0 → ③ small 아님·score ≤ 0 → ④ small·score ≤ 0),
			// 그 안에서 score(맞은 수 − 0.5 × 안 맞은 수), 일치율, 맞은 수, 참조 번호.
			// octave_tolerant면 옥타브만 다른 짝도 matched에 들어가므로 옥타브를 옮겨 적은 트랙도 제대로 찾는다.
			cands.sort(Comparator.comparingInt((Candidate c) -> rankGroup(c.smallSample(), c.score()))
					.thenComparingDouble((Candidate c) -> -c.score())
					.thenComparingDouble(c -> -exactRatio(c)).thenComparingInt(c -> -c.matched())
					.thenComparingInt(Candidate::refTrack));
			String note = null;
			if (cands.isEmpty() && !octaveOnly.isEmpty()) {
				// 그대로는 하나도 안 맞고 옥타브를 옮겨야만 맞을 때, 옮겨서 맞는 순으로 보여 준다.
				// 같은 점수식을 옮겨서 맞은 수(octave_shift_matched)로 계산한다(small_sample은 뒤로).
				// 묶음도 같은 점수로 나눈다.
				octaveOnly.sort(Comparator
						.comparingInt((Candidate c) -> rankGroup(c.smallSample(), scoreOf(c.octaveShiftMatched(), c.total())))
						.thenComparingDouble((Candidate c) -> -scoreOf(c.octaveShiftMatched(), c.total()))
						.thenComparingDouble(c -> -c.octaveShiftRatio()).thenComparingInt(c -> -c.octaveShiftMatched())
						.thenComparingInt(Candidate::refTrack));
				cands = octaveOnly;
				note = "그대로는 맞는 음이 없고 옥타브를 옮겨야만 맞음(octave_shift·octave_shift_ratio 참고, "
						+ "octave_tolerant: true면 일치로 셈)";
			}
			List<Candidate> top = List.copyOf(cands.subList(0, Math.min(TOP, cands.size())));
			out.add(new TrackSuggestion(tdw.number(), tdw.name(), tdw.program(), inst, false, scoreNotes, top,
					top.isEmpty() ? "같은 음·같은 시작으로 맞는 참조 트랙이 없음" : note, sounding, threshold));
			if (!top.isEmpty()) {
				Map<String, Object> e = new LinkedHashMap<>();
				e.put("melody_from", List.of(top.get(0).refTrack()));
				e.put("chords_from", "all");
				e.put("match_parts", Mapping.MATCH_ANY);
				e.put("pitch_mode", mode);
				e.put("measure_offset", off);
				mapping.put(String.valueOf(tdw.number()), e);
			}
		}
		return new MappingSuggestion(List.of(from, to), Mapping.MATCH_ANY, List.copyOf(refTracks), List.copyOf(out),
				mapping, List.copyOf(skipped), mode, off, o.autoOffset(), offScores, List.of(from + off, to + off),
				o.octaveTolerant(), text(from, to, off, o.autoOffset(), mode, out));
	}

	/** 악보 트랙 하나 × 참조 트랙 하나. 범위 안 참조 음이 없으면 null. */
	private static Candidate score(TrackData td, int refTrack, ReferenceMidi ref, int from, int to, long rs, long re,
			long shift, boolean tolerant, int scoreNotes) {
		Pairing pg = Comparer.pair(td, List.of(refTrack), ref, from, to, rs, re, true, shift, tolerant);
		int total = 0;
		int matched = 0;
		int octave = 0;
		int[] byPart = new int[Part.values().length];
		for (int i = 0; i < pg.refs().size(); i++) {
			Comparer.RefN r = pg.refs().get(i);
			if (!r.inRange()) {
				continue;
			}
			total++;
			int j = pg.pair()[i];
			if (j < 0) {
				continue;
			}
			SNote s = pg.sc().get(j);
			int d = s.cmp() - r.n().pitch();
			if (Math.abs(s.tick() - r.tick()) <= Comparer.SAME_START
					&& (d == 0 || (tolerant && Math.floorMod(d, 12) == 0))) {
				matched++;
				if (d != 0) {
					octave++;
				}
				byPart[s.part().index()]++;
			}
		}
		if (total == 0) {
			return null;
		}
		// 옥타브 이동 탐색: 비교 음을 k옥타브 옮겨 엄격하게 맞춘다. 같으면 |k|가 작은 쪽(0 우선).
		int bestK = 0;
		int bestM = -1;
		for (int a = 0; a <= OCTAVE_RANGE; a++) {
			for (int k : (a == 0 ? new int[] { 0 } : new int[] { -a, a })) {
				Pairing q = Comparer.pair(td.shifted(12 * k), List.of(refTrack), ref, from, to, rs, re, true, shift, false);
				int m = Comparer.countMatched(q, false);
				if (m > bestM) {
					bestM = m;
					bestK = k;
				}
			}
		}
		double ratio = round3((double) matched / total);
		return new Candidate(refTrack, ref.track(refTrack).name(), ratio, matched, total, Comparer.partMap(byPart),
				pg.ignored().size(), -bestK, round3((double) bestM / total), bestM, octave, scoreOf(matched, total),
				smallSample(total, scoreNotes));
	}

	/** 순위 점수 = matched − 0.5 × (total − matched). */
	static double scoreOf(int matched, int total) {
		return matched - MISS_WEIGHT * (total - matched);
	}

	/**
	 * small_sample 기준 수 = min(max(8, ceil(악보 트랙 음 수 × 10%)), 악보 트랙 음 수). 악보 트랙 음이 8개보다 적으면 그 수가
	 * 상한이라, 악보 음을 모두 덮는 참조 트랙은 small_sample이 되지 않는다. ceil 앞의 1e-9는 부동소수 오차(110 × 0.1 = 11.000000000000002) 막이.
	 */
	static int smallSampleThreshold(int scoreNotes) {
		int n = Math.max(0, scoreNotes);
		int tenth = (int) Math.ceil(n * SMALL_SAMPLE_RATIO - 1e-9);
		return Math.min(Math.max(SMALL_SAMPLE_MIN, tenth), n);
	}

	/** 참조 음 수가 {@link #smallSampleThreshold(int)}보다 작은가. */
	static boolean smallSample(int total, int scoreNotes) {
		return total < smallSampleThreshold(scoreNotes);
	}

	/**
	 * 정렬 묶음. 0 = small_sample 아니고 score &gt; 0, 1 = small_sample이고 score &gt; 0,
	 * 2 = small_sample 아니고 score ≤ 0, 3 = small_sample이고 score ≤ 0.
	 */
	static int rankGroup(boolean smallSample, double score) {
		int base = (score > 0) ? 0 : 2;
		return smallSample ? base + 1 : base;
	}

	private static double round3(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	private static double exactRatio(Candidate c) {
		return c.total() == 0 ? 0 : (double) c.matched() / c.total();
	}


	private static String text(int from, int to, int off, boolean auto, String mode, List<TrackSuggestion> l) {
		StringBuilder sb = new StringBuilder(CompareText.rangeLabel(from, to, off))
				.append(" 매핑 제안(악보 트랙의 어느 파트든 같은 음·같은 시작이면 일치로 셈, 저장하지 않음");
		if (Comparer.SOUNDING.equals(mode)) {
			sb.append(", 들리는 음 기준");
		}
		if (off != 0 || auto) {
			sb.append(", 마디 차 ").append(CompareText.signed(off)).append(auto ? "(auto)" : "");
		}
		sb.append("): ");
		List<String> parts = new ArrayList<>();
		for (TrackSuggestion t : l) {
			String head = "트랙 " + t.track() + (t.name() == null || t.name().isBlank() ? "" : "(" + t.name() + ")");
			if (t.suggestions().isEmpty()) {
				parts.add(head + " 제안 없음 — " + t.note());
				continue;
			}
			List<String> c = new ArrayList<>();
			for (Candidate x : t.suggestions()) {
				String oct = (x.octaveShift() != 0 && x.octaveShiftMatched() > x.matched())
						? ", 악보가 원곡보다 " + CompareText.signed(x.octaveShift()) + "옥타브로 보면 " + pct(x.octaveShiftRatio())
						: "";
				c.add("참조 " + x.refTrack() + "번 " + x.name() + " " + pct(x.ratio()) + "(" + x.matched() + "/" + x.total()
						+ oct + (x.smallSample() ? ", 참조 음이 적어 순위 뒤로" : "") + ")");
			}
			parts.add(head + " → " + String.join(", ", c));
		}
		sb.append(String.join("; ", parts)).append('.');
		sb.append(" 1위로 만든 대응표가 suggested_mapping에 있습니다. 쓰려면 compare_set_mapping에 넣으세요.");
		return sb.toString();
	}

	private static String pct(double ratio) {
		return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
	}
}
