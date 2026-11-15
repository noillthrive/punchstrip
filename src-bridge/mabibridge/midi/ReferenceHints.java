package mabibridge.midi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 참조 MIDI를 편곡할 때 쓰는 읽기 전용 힌트: 트랙마다 멜로디 점수·역할 추정, 박마다 격자(셋잇단) 추정.
 *
 * <p>모두 단순 규칙이다. 판단의 근거 수치를 함께 돌려주므로 에이전트가 확인하고 고쳐 쓸 수 있다.
 * 길이 0 음(원본 길이 0틱, 실제로 울리지 않음)은 멜로디 점수·역할 계산에서 뺀다.</p>
 */
public final class ReferenceHints {

	private ReferenceHints() {}

	// ================================================================== 멜로디 점수·역할

	/** 멜로디 점수 가중치: 높이 0.35, 단선율 0.30, 곡 덮음 0.20, 이름 0.15. */
	public static final double W_HEIGHT = 0.35;
	public static final double W_MONOPHONY = 0.30;
	public static final double W_COVERAGE = 0.20;
	public static final double W_NAME = 0.15;
	/** 높이 정규화: 맨 윗선 평균 음 48(C3) → 0, 84(C6) → 1. */
	public static final int HEIGHT_LOW = 48;
	public static final int HEIGHT_HIGH = 84;
	/** 베이스: 평균 음 < 48, 단선율 > 0.8. */
	public static final double BASS_MEAN_PITCH = 48;
	public static final double BASS_MONOPHONY = 0.8;
	/** 패드: 평균 길이 ≥ 1박(4분음표). */
	public static final double PAD_MEAN_BEATS = 1.0;
	/** 화음·패드: 시작점의 절반 넘게(> 0.5) 동시에 2음 이상. */
	public static final double POLY_MOST = 0.5;
	/** 멜로디: 점수 0.5 이상. */
	public static final double MELODY_MIN = 0.5;
	/** 멜로디 후보: 같은 시각에 시작하는 음 묶음의 평균 크기 < 2.5(멜로디 + 화성 한 줄까지, 3음 이상 블록 화음은 아님). */
	public static final double MELODY_MAX_GROUP = 2.5;
	/** 역할을 매기려면 음이 시작하는 마디가 곡의 5% 이상(그리고 2마디 이상)이어야 한다. */
	public static final double SPARSE_COVERAGE = 0.05;
	/** 이름 열쇠말(소문자, 이름 어디에 있어도). */
	public static final List<String> NAME_KEYWORDS = List.of("vocal", "voice", "melody", "lead", "main", "female", "women",
			"woman", "singer", "soprano", "보컬", "멜로디", "노래", "ボーカル", "メロディ", "歌");
	/** 이름 열쇠말(소문자, 낱말 전체가 같을 때만 — 짧아서 다른 낱말 안에 들어가기 쉬운 것). */
	public static final List<String> NAME_TOKENS = List.of("vo", "vox", "men", "man", "male", "sing");

	/**
	 * 멜로디 점수의 성분(모두 0~1). "맨 윗선"은 같은 원본 틱에 시작한 음 묶음마다 가장 높은 음 하나를 고른 줄이다
	 * (3도 화음을 붙여 적은 보컬처럼 한 트랙에 멜로디 + 화성이 같이 있어도 멜로디 줄로 본다).
	 *
	 * @param height 맨 윗선 평균 음높이 (평균 − 48) / 36, 0~1로 자름
	 * @param monophony 맨 윗선 음 중 맨 윗선의 다른 음과 겹치지 않는 음의 비율(겹침 1/32음표 이하는 겹침으로 안 봄)
	 * @param coverage 곡 전체 마디 중 이 트랙 음이 하나라도 시작하는 마디의 비율
	 * @param nameKeyword 트랙 이름이나 악기 이름에 열쇠말이 있으면 1
	 */
	public record Components(double height, double monophony, double coverage, double nameKeyword) {}

	/**
	 * 역할 판단 근거.
	 *
	 * @param notesUsed 계산에 쓴 음 수(길이 0 음 제외)
	 * @param topLineNotes 맨 윗선 음 수(= 서로 다른 시작 틱 수)
	 * @param meanPitch 모든 음의 평균 음높이(MIDI, 참조 MIDI 음 = 들리는 음)
	 * @param topLineMeanPitch 맨 윗선 평균 음높이
	 * @param meanLenBeats 모든 음의 평균 길이(박 = 4분음표)
	 * @param polyphonyRatio 서로 다른 시작 틱 중 그 순간 이 트랙 음이 2개 이상 울리는 비율
	 * @param meanGroupSize 같은 시각에 시작하는 음 묶음의 평균 크기(음 수 / 시작 틱 수)
	 * @param noteMonophony 모든 음 기준 단선율(다른 음과 겹치지 않는 음의 비율, 참고용)
	 * @param measuresWithNotes 음이 시작하는 마디 수
	 * @param instrument 이름 열쇠말 검사에 쓴 악기 이름(GM, 없으면 null)
	 * @param matchedKeyword 걸린 열쇠말(없으면 null)
	 */
	public record Evidence(int notesUsed, int topLineNotes, Double meanPitch, Double topLineMeanPitch,
			Double meanLenBeats, Double polyphonyRatio, Double meanGroupSize, Double noteMonophony, int measuresWithNotes,
			String instrument, String matchedKeyword) {}

	/**
	 * 트랙 하나의 힌트.
	 *
	 * @param track 트랙 번호(1부터)
	 * @param melodyScore 0~1(드럼·빈 트랙은 0)
	 * @param components 점수 성분
	 * @param melodyRank 드럼이 아니고 음이 있는 트랙 중 점수 순위(1 = 가장 높음, 그 밖은 null)
	 * @param roleGuess melody·bass·chords·pad·drums·unknown
	 * @param roleReason 역할 판단 이유(한국어)
	 * @param evidence 근거 수치
	 */
	public record TrackHint(int track, double melodyScore, Components components, Integer melodyRank, String roleGuess,
			String roleReason, Evidence evidence) {}

	/**
	 * 트랙마다 멜로디 점수와 역할 추정(번호 순).
	 *
	 * <p>역할 순서: ① 드럼 채널 → drums ② 울리는 음이 없거나 음이 있는 마디가 너무 적음 → unknown
	 * ③ 평균 음 &lt; 48·단선율 &gt; 0.8 → bass ④ 남은 트랙 중 묶음 평균 &lt; 2.5이고 멜로디 점수 1위·0.5 이상 → melody
	 * ⑤ 시작점의 절반 넘게 2음 이상·평균 길이 ≥ 1박 → pad ⑥ 시작점의 절반 넘게 2음 이상 → chords ⑦ 그 밖 unknown.</p>
	 */
	public static List<TrackHint> trackHints(ReferenceMidi m) {
		int count = m.trackCount();
		int measures = Math.max(1, m.measureCount());
		long tol = Math.max(1, m.resolution() / 8);
		int sparseMeasures = Math.max(2, (int) Math.ceil(SPARSE_COVERAGE * measures));
		List<Stat> stats = new ArrayList<>();
		for (int t = 1; t <= count; t++) {
			TrackInfo info = m.track(t);
			List<Note> ns = new ArrayList<>();
			for (Note n : m.notes(t, 1, Integer.MAX_VALUE)) {
				if (n.srcLength() > 0) {
					ns.add(n);
				}
			}
			stats.add(new Stat(info, ns, measures, tol, m.resolution()));
		}
		String[] role = new String[count];
		String[] reason = new String[count];
		for (int i = 0; i < count; i++) {
			Stat s = stats.get(i);
			if (s.info.drum()) {
				role[i] = "drums";
				reason[i] = "드럼 채널(10)에서 소리를 냄";
			} else if (s.notes.isEmpty()) {
				role[i] = "unknown";
				reason[i] = "울리는 음이 없음";
			} else if (s.measuresWithNotes < sparseMeasures) {
				role[i] = "unknown";
				reason[i] = "음이 있는 마디가 " + s.measuresWithNotes + "개뿐(역할을 매기려면 " + sparseMeasures + "마디 이상)";
			} else if (s.meanPitch < BASS_MEAN_PITCH && s.monophony > BASS_MONOPHONY) {
				role[i] = "bass";
				reason[i] = String.format(Locale.ROOT, "평균 음 %.1f < %d, 단선율 %.2f > %.1f", s.meanPitch,
						(int) BASS_MEAN_PITCH, s.monophony, BASS_MONOPHONY);
			}
		}
		// 순위(드럼 아님, 음 있음)
		List<Integer> ranked = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			if (!stats.get(i).info.drum() && !stats.get(i).notes.isEmpty()) {
				ranked.add(i);
			}
		}
		ranked.sort(Comparator.comparingDouble((Integer i) -> -stats.get(i).score).thenComparingInt(i -> i));
		Integer[] rank = new Integer[count];
		for (int r = 0; r < ranked.size(); r++) {
			rank[ranked.get(r)] = r + 1;
		}
		// 멜로디: 남은 트랙 중 블록 화음이 아닌 것의 1위
		int best = -1;
		for (int i : ranked) {
			if (role[i] == null && stats.get(i).meanGroupSize < MELODY_MAX_GROUP) {
				best = i;
				break;
			}
		}
		if (best >= 0 && stats.get(best).score >= MELODY_MIN) {
			Stat s = stats.get(best);
			role[best] = "melody";
			reason[best] = String.format(Locale.ROOT,
					"드럼·베이스가 아니고 묶음 평균 %.2f < %.1f인 트랙 중 멜로디 점수 1위(%.3f ≥ %.1f)", s.meanGroupSize,
					MELODY_MAX_GROUP, s.score, MELODY_MIN);
		}
		for (int i = 0; i < count; i++) {
			if (role[i] != null) {
				continue;
			}
			Stat s = stats.get(i);
			if (s.polyRatio > POLY_MOST && s.meanLenBeats >= PAD_MEAN_BEATS) {
				role[i] = "pad";
				reason[i] = String.format(Locale.ROOT, "시작점의 %.0f%%에서 2음 이상, 평균 길이 %.2f박 ≥ 1박", s.polyRatio * 100,
						s.meanLenBeats);
			} else if (s.polyRatio > POLY_MOST) {
				role[i] = "chords";
				reason[i] = String.format(Locale.ROOT, "시작점의 %.0f%%에서 2음 이상, 평균 길이 %.2f박 < 1박", s.polyRatio * 100,
						s.meanLenBeats);
			} else {
				role[i] = "unknown";
				reason[i] = i == best
						? String.format(Locale.ROOT, "멜로디 후보 1위지만 멜로디 점수 %.3f < %.1f", s.score, MELODY_MIN)
						: String.format(Locale.ROOT, "규칙에 맞는 역할 없음(멜로디 점수 %.3f, 순위 %d)", s.score, rank[i]);
			}
		}
		List<TrackHint> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Stat s = stats.get(i);
			boolean zero = s.info.drum() || s.notes.isEmpty();
			boolean has = !s.notes.isEmpty();
			Components c = zero ? new Components(0, 0, 0, s.nameKeyword) : new Components(r3(s.height), r3(s.monophony),
					r3(s.coverage), s.nameKeyword);
			Evidence e = new Evidence(s.notes.size(), s.topLine, has ? r2(s.meanPitch) : null,
					has ? r2(s.topMeanPitch) : null, has ? r3(s.meanLenBeats) : null, has ? r3(s.polyRatio) : null,
					has ? r2(s.meanGroupSize) : null, has ? r3(s.noteMonophony) : null, s.measuresWithNotes, s.instrument,
					s.keyword);
			out.add(new TrackHint(i + 1, zero ? 0.0 : r3(s.score), c, rank[i], role[i], reason[i], e));
		}
		return List.copyOf(out);
	}

	/** 한 트랙의 계산값. */
	private static final class Stat {
		final TrackInfo info;
		final List<Note> notes;
		double meanPitch;
		double topMeanPitch;
		double meanLenBeats;
		double monophony;
		double noteMonophony;
		double polyRatio;
		double meanGroupSize;
		double coverage;
		double height;
		int topLine;
		int measuresWithNotes;
		double nameKeyword;
		String keyword;
		String instrument;
		double score;

		Stat(TrackInfo info, List<Note> notes, int measures, long tol, int resolution) {
			this.info = info;
			this.notes = notes;
			instrument = instrumentName(info);
			keyword = keyword(info.name());
			if (keyword == null && instrument != null) {
				keyword = keyword(instrument);
			}
			nameKeyword = keyword != null ? 1 : 0;
			if (notes.isEmpty()) {
				return;
			}
			List<Note> ns = new ArrayList<>(notes);
			ns.sort(Comparator.comparingLong(Note::srcTick).thenComparing(Comparator.comparingInt(Note::pitch).reversed()));
			double sumP = 0;
			double sumL = 0;
			Set<Integer> ms = new HashSet<>();
			List<Note> top = new ArrayList<>();
			long lastTick = Long.MIN_VALUE;
			for (Note n : ns) {
				sumP += n.pitch();
				sumL += (double) n.srcLength() / resolution;
				ms.add(n.measure());
				if (n.srcTick() != lastTick) {
					top.add(n); // 그 시각에 시작한 음 중 가장 높은 음
					lastTick = n.srcTick();
				}
			}
			meanPitch = sumP / ns.size();
			meanLenBeats = sumL / ns.size();
			measuresWithNotes = ms.size();
			topLine = top.size();
			meanGroupSize = (double) ns.size() / top.size();
			double sumTop = 0;
			for (Note n : top) {
				sumTop += n.pitch();
			}
			topMeanPitch = sumTop / top.size();
			coverage = Math.min(1.0, (double) ms.size() / measures);
			height = clamp01((topMeanPitch - HEIGHT_LOW) / (HEIGHT_HIGH - HEIGHT_LOW));
			monophony = monophony(top, tol);
			noteMonophony = monophony(ns, tol);
			// 동시 발음: 서로 다른 시작 틱마다 그 순간 울리는 음 수(그 틱에 시작한 음 + 앞에서 시작해 tol 넘게 이어지는 음)
			int poly = 0;
			for (Note t0 : top) {
				long t = t0.srcTick();
				int c = 0;
				for (Note n : ns) {
					if (n.srcTick() > t) {
						break;
					}
					if (n.srcTick() == t || n.srcEnd() > t + tol) {
						c++;
					}
				}
				if (c >= 2) {
					poly++;
				}
			}
			polyRatio = (double) poly / top.size();
			score = W_HEIGHT * height + W_MONOPHONY * monophony + W_COVERAGE * coverage + W_NAME * nameKeyword;
		}

		/** 다른 음과 tol보다 길게 겹치지 않는 음의 비율(ns는 시작 순). */
		static double monophony(List<Note> ns, long tol) {
			boolean[] overlaps = new boolean[ns.size()];
			for (int i = 0; i < ns.size(); i++) {
				Note a = ns.get(i);
				for (int j = i + 1; j < ns.size(); j++) {
					Note b = ns.get(j);
					if (b.srcTick() >= a.srcEnd() - tol) {
						break;
					}
					long ov = Math.min(a.srcEnd(), b.srcEnd()) - Math.max(a.srcTick(), b.srcTick());
					if (ov > tol) {
						overlaps[i] = true;
						overlaps[j] = true;
					}
				}
			}
			int mono = 0;
			for (boolean o : overlaps) {
				if (!o) {
					mono++;
				}
			}
			return (double) mono / ns.size();
		}
	}

	/** 트랙 이름·악기 이름 검사에 쓸 GM 악기 이름(첫 음 시점 프로그램, 없으면 마지막 프로그램). */
	static String instrumentName(TrackInfo info) {
		if (info.drum()) {
			return null;
		}
		for (TrackInfo.ChannelProgram cp : info.channelPrograms()) {
			Integer p = cp.programAtFirstNote() != null ? cp.programAtFirstNote() : cp.finalProgram();
			if (p != null) {
				return MidiNames.gmName(p);
			}
		}
		return info.mabiImportProgram() == null ? null : MidiNames.gmName(info.mabiImportProgram());
	}

	/** 이름에 든 첫 열쇠말(대소문자 무시). 없으면 null. */
	static String keyword(String name) {
		if (name == null) {
			return null;
		}
		String low = name.toLowerCase(Locale.ROOT);
		for (String k : NAME_KEYWORDS) {
			if (low.contains(k)) {
				return k;
			}
		}
		for (String tok : low.split("[^\\p{L}\\p{N}]+")) {
			if (NAME_TOKENS.contains(tok)) {
				return tok;
			}
		}
		return null;
	}

	// ================================================================== 격자(셋잇단) 힌트

	/** 셋잇단 판정 기본값: triplet_err < 0.6 × straight_err. */
	public static final double DEFAULT_TRIPLET_RATIO = 0.6;
	/** 셋잇단 판정 기본값: straight_err > 3.6틱(24틱의 15%). */
	public static final double DEFAULT_STRAIGHT_TOL = 3.6;

	/**
	 * 박 하나의 격자 추정.
	 *
	 * @param measure 마디
	 * @param beat 박(1부터)
	 * @param pos "마디:박:0"
	 * @param onsets 이 박 안의 서로 다른 시작 시각 수(같은 시각의 화음은 하나로)
	 * @param grid straight(1/4박 격자) · triplet(1/3박 격자) · none(시작이 없음)
	 * @param straightErr 시작들이 1/4박 격자(4분음표 박이면 24틱) 가장 가까운 점까지의 평균 거리(96틱 기준, 없으면 null)
	 * @param tripletErr 1/3박 격자(32틱)까지의 평균 거리
	 */
	public record GridHint(int measure, int beat, String pos, int onsets, String grid, Double straightErr,
			Double tripletErr) {}

	/**
	 * 박마다 시작들이 1/4박 격자와 1/3박 격자 중 어디에 더 가까운가.
	 *
	 * <p>시작 시각은 6틱 반올림 전의 정확한 96틱 환산 값(원본 틱 × 96 / 해상도)을 쓴다. 6틱 반올림 뒤에는
	 * 셋잇단 32·64틱이 30·66틱이 되어 판정이 흐려지기 때문이다. 박 끝(다음 박 시작)도 격자 점으로 본다.</p>
	 *
	 * @param notes 볼 음들(보통 reference_notes가 고른 음). 범위 밖 시작은 무시
	 */
	public static List<GridHint> gridHints(ReferenceMidi m, Collection<Note> notes, int fromMeasure, int toMeasure,
			double tripletRatio, double straightTol) {
		MeasureMap map = m.measureMap96();
		double scale = (double) MeasureMap.TPQN96 / m.resolution();
		TreeSet<Long> srcOnsets = new TreeSet<>();
		for (Note n : notes) {
			srcOnsets.add(n.srcTick());
		}
		List<GridHint> out = new ArrayList<>();
		for (int meas = fromMeasure; meas <= toMeasure; meas++) {
			long mStart = map.measureStart(meas);
			long mEnd = map.measureEnd(meas);
			long bl = map.beatLength(meas);
			int beats = map.beatsInMeasure(meas);
			for (int b = 1; b <= beats; b++) {
				long bs = mStart + (long) (b - 1) * bl;
				long be = Math.min(bs + bl, mEnd);
				// 원본 틱 범위로 바꿔 시작들을 고른다
				long srcFrom = (long) Math.ceil(bs / scale - 1e-9);
				int k = 0;
				double se = 0;
				double te = 0;
				for (long st : srcOnsets.tailSet(srcFrom, true)) {
					double x = st * scale;
					if (x >= be) {
						break;
					}
					if (x < bs) {
						continue;
					}
					double rel = x - bs;
					se += gridDistance(rel, bl / 4.0);
					te += gridDistance(rel, bl / 3.0);
					k++;
				}
				String pos = meas + ":" + b + ":0";
				if (k == 0) {
					out.add(new GridHint(meas, b, pos, 0, "none", null, null));
					continue;
				}
				double sErr = se / k;
				double tErr = te / k;
				String g = (tErr < tripletRatio * sErr && sErr > straightTol) ? "triplet" : "straight";
				out.add(new GridHint(meas, b, pos, k, g, r2(sErr), r2(tErr)));
			}
		}
		return List.copyOf(out);
	}

	/** rel에서 간격 g 격자의 가장 가까운 점까지 거리. */
	static double gridDistance(double rel, double g) {
		double q = Math.round(rel / g) * g;
		return Math.abs(rel - q);
	}

	/** 격자 판정 개수 {straight, triplet, none}. */
	public record GridSummary(int straight, int triplet, int none, List<String> tripletBeats) {}

	public static GridSummary gridSummary(List<GridHint> hints) {
		int s = 0;
		int t = 0;
		int n = 0;
		List<String> tb = new ArrayList<>();
		for (GridHint h : hints) {
			switch (h.grid()) {
			case "straight" -> s++;
			case "triplet" -> {
				t++;
				tb.add(h.pos());
			}
			default -> n++;
			}
		}
		return new GridSummary(s, t, n, List.copyOf(tb));
	}

	// ================================================================== 도우미

	static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	static double r2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	static double r3(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}
}
