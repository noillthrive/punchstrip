package mabibridge.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import mabibridge.compare.CompareReport.ChordBeat;
import mabibridge.compare.CompareReport.Crossing;
import mabibridge.compare.CompareReport.MeasureDetail;
import mabibridge.compare.CompareReport.MelodyCounts;
import mabibridge.compare.CompareReport.MelodyItem;
import mabibridge.compare.CompareReport.MelodyMeasure;
import mabibridge.compare.CompareReport.MelodyTrack;
import mabibridge.compare.CompareReport.Overlap;
import mabibridge.compare.CompareReport.RangeCheck;
import mabibridge.compare.CompareReport.RangeViolation;
import mabibridge.compare.CompareReport.Summary;

/**
 * compare 결과를 비전문가가 읽는 한국어 문단으로 바꾼다. 0인 항목은 "없음"으로 짧게,
 * 예시는 항목마다 3개까지, 마디별 상세는 문제가 가장 많은 마디 3개까지만 쓴다.
 *
 * <p>성부 교차는 전문 편곡에서도 흔해 정보 등급이다. "문제가 많은 마디" 순위와 쓰기 요약의 문제 목록에서 빼고,
 * "참고: 성부 교차 N곳(전문 편곡에서도 흔함)"으로 따로 한 문장을 쓴다({@code summary.crossings}는 그대로 센다).</p>
 */
final class CompareText {

	static final int EXAMPLES = 3;
	static final int WORST = 3;

	private CompareText() {}

	// ======================================================================
	// compare 전체 문단
	// ======================================================================

	static String full(CompareReport r, boolean refLoaded) {
		int from = r.range().get(0);
		int to = r.range().get(1);
		int measureCount = to - from + 1;
		Summary s = r.summary();
		boolean multiTrack = r.scoreTracks().size() > 1;
		int off = r.measureOffsetUsed();
		List<String> sent = new ArrayList<>();

		// 비교 기준(들리는 음, 마디 차)
		if (Comparer.SOUNDING.equals(r.pitchMode())) {
			sent.add("비교 기준: 들리는 음(악기마다 적은 음과 실제로 울리는 음의 차이를 표로 바꿔 원곡과 비교)"
					+ (r.octaveTolerant() ? ", 옥타브만 다른 음도 짝으로 인정" : ""));
		} else if (r.octaveTolerant()) {
			sent.add("비교 기준: 적은 음, 옥타브만 다른 음도 짝으로 인정");
		}
		if (off != 0 || r.measureOffsetAuto()) {
			sent.add("마디 차 " + signed(off) + (r.measureOffsetAuto() ? "(auto로 찾음)" : "")
					+ ": 악보 마디 = 참조 마디 " + signed(off) + ", 마디 번호는 참조 기준이고 괄호 안이 악보 마디");
		}
		if (!r.pitchCompareSkipped().isEmpty()) {
			List<String> l = new ArrayList<>();
			boolean drum = false;
			boolean other = false;
			for (CompareReport.PitchSkip ps : r.pitchCompareSkipped()) {
				l.add("트랙 " + ps.track() + (ps.instrument() == null ? "" : "(" + ps.instrument() + ")"));
				if (Comparer.KIND_DRUM.equals(ps.kind())) {
					drum = true;
				} else {
					other = true;
				}
			}
			// 타악기 트랙도 들어간다
			String kinds = drum ? (other ? "타악기·고정음·음높이 없음·효과음 악기" : "타악기") : "고정음·음높이 없음·효과음 악기";
			sent.add("음높이 비교를 하지 않은 트랙: " + String.join(", ", l) + " — " + kinds);
		}

		// (1) 멜로디
		if (!refLoaded) {
			sent.add("참조 MIDI가 없어 멜로디 일치·화음 비교는 건너뛰었습니다");
		} else if (r.melodyTracks().isEmpty()) {
			sent.add("매핑(어느 악보 트랙이 원곡 어느 트랙의 멜로디를 따르는지)이 없어 멜로디 일치 검사는 건너뛰었습니다");
		} else {
			for (MelodyTrack mt : r.melodyTracks()) {
				sent.add(melodySentence(r, mt));
			}
			// 항목이 둘 이상이면 참조 음을 한 번씩만 센 합산 일치를 함께 쓴다
			if (s.union() != null && r.melodyTracks().size() > 1) {
				sent.add(unionSentence(s.union()));
			}
		}

		// (2) 화음
		if (refLoaded) {
			String src = (r.chordsFrom() == null) ? "" : "참조 " + joinInts(r.chordsFrom()) + "번 기준으로 ";
			//: "1마디 4박 중"(1번 마디로 읽힘) 대신 "27~34마디(32박)"처럼 범위와 박 수를 함께 쓴다.
			String span = rangeLabel(from, to, off) + "(" + s.chordBeats() + "박)";
			if (s.chordBeats() == 0) {
				sent.add("화음: 비교할 박이 없습니다");
			} else if (s.chordMissingBeats() == 0) {
				sent.add("화음 구성음(원곡에서 함께 울리는 음)은 " + src + span + " 모두 악보에 들어 있습니다");
			} else {
				List<String> ex = new ArrayList<>();
				for (MeasureDetail d : r.measures()) {
					for (ChordBeat c : d.chords()) {
						if (!c.missing().isEmpty()) {
							ex.add(beatLabel(c.pos()) + " " + String.join("·", c.missing()));
						}
					}
				}
				sent.add("화음 구성음(원곡에서 함께 울리는 음)은 " + src + span + " 중 " + s.chordMissingBeats()
						+ "박에서 원곡 음이 비어 있습니다(" + examples(ex) + ")");
			}
		}

		// (3) 겹침
		List<String> ov = new ArrayList<>();
		for (MeasureDetail d : r.measures()) {
			for (Overlap o : d.overlaps()) {
				ov.add("트랙 " + o.track() + " " + partName(o.part()) + " " + o.pos());
			}
		}
		sent.add(ov.isEmpty() ? "파트 안 겹침 없음"
				: "파트 안 겹침 " + ov.size() + "곳(" + examples(ov) + ", 엔진 정리 오류일 수 있음)");

		// (5) 음역
		sent.add(rangeSentence(r, multiTrack));

		// 들리는 음 경고
		if (!r.soundingWarnings().isEmpty()) {
			List<String> l = new ArrayList<>();
			for (CompareReport.SoundingWarning w : r.soundingWarnings()) {
				if ("silent_note".equals(w.code())) {
					l.add("트랙 " + w.track() + " 무음 구간(소리 안 남)에 적힌 음 " + w.count() + "개(" + examples(w.positions()) + ")");
				} else {
					l.add("트랙 " + w.track() + " 두 음색 세트 섞어 씀(" + w.timbres() + ", 정보)");
				}
			}
			sent.add("들리는 음 경고: " + String.join(", ", l));
		}

		// (6) 글자 수
		sent.add("글자 수: 노래 제외 기준 " + overText(s.charsOver().songExcluded()) + songNotAllowedText(r)
				+ ", 노래 포함 기준 " + overText(s.charsOver().songIncluded()));

		// 문제가 많은 마디
		if (measureCount > 1) {
			String worst = worstMeasures(r.measures(), off);
			if (worst != null) {
				sent.add("문제가 많은 마디: " + worst);
			}
		}
		// (4) 교차: 정보(문제로 세지 않음)
		sent.add(crossingSentence(r));
		for (String n : r.notesSkipped()) {
			if (n.contains("박자 구성")) {
				sent.add("주의: 악보와 참조의 박자 구성이 달라 마디 위치가 어긋날 수 있습니다");
			} else if (n.startsWith("measure_offset auto: ")) {
				// auto가 아무것도 못 찾았거나 범위 끝·악보 끝에 걸림
				sent.add("주의: " + n);
			}
		}

		StringBuilder sb = new StringBuilder(rangeLabel(from, to, off)).append(": ");
		for (int i = 0; i < sent.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(sent.get(i)).append('.');
		}
		return sb.toString();
	}

	private static String melodySentence(CompareReport r, MelodyTrack mt) {
		boolean any = Mapping.MATCH_ANY.equals(mt.matchParts());
		String label = "멜로디(트랙 " + mt.track() + " ← 참조 " + joinInts(mt.melodyFrom()) + "번"
				+ (any ? ", 트랙의 모든 파트와 비교" : "") + ")";
		MelodyCounts c = mt.counts();
		List<MelodyItem> items = new ArrayList<>();
		for (MeasureDetail d : r.measures()) {
			for (MelodyMeasure mm : d.melody()) {
				if (mm.track() == mt.track()) {
					items.addAll(mm.items());
				}
			}
		}
		StringBuilder sb = new StringBuilder(label);
		if (mt.refNotes() == 0) {
			sb.append(": 이 범위에 참조 음이 없습니다");
		} else {
			sb.append(' ').append(c.refTotal()).append("음 중 ").append(c.matched()).append("음 일치");
			if (c.octaveShifted() > 0) {
				sb.append("(그중 ").append(c.octaveShifted()).append("음은 옥타브만 달라 일치로 셈)");
			}
			List<String> other = new ArrayList<>();
			for (Map.Entry<String, Integer> e : mt.matchedByPart().entrySet()) {
				if (!"melody".equals(e.getKey())) {
					other.add(partName(e.getKey()) + " " + e.getValue() + "음");
				}
			}
			if (!other.isEmpty()) {
				sb.append("(그중 ").append(String.join("·", other)).append("은 멜로디 아닌 파트에서)");
			}
			if (c.wrong() > 0) {
				String oct = "";
				if (mt.wrongOctave() == c.wrong()) {
					oct = "모두 옥타브만 다름; ";
				} else if (mt.wrongOctave() > 0) {
					oct = "그중 " + mt.wrongOctave() + "음은 옥타브만 다름; ";
				}
				sb.append(", ").append(c.wrong()).append("음 다름(").append(oct).append(examples(pick(items,
						Comparer.WRONG, i -> i.pos() + " 참조 " + i.ref() + " / 악보 " + i.score()))).append(')');
			}
			if (c.shifted() > 0) {
				sb.append(", ").append(c.shifted()).append("음 박자 어긋남(").append(examples(pick(items, Comparer.SHIFTED,
						i -> i.pos() + " " + i.ref() + " " + shiftText(i.tickDiff())))).append(')');
			}
			if (c.missing() > 0) {
				sb.append(", ").append(c.missing()).append("음 빠짐(").append(examples(pick(items, Comparer.MISSING,
						i -> i.pos() + " " + i.ref()))).append(')');
			}
		}
		if (c.extra() > 0) {
			sb.append(", 원곡에 없는 음 ").append(c.extra()).append("개(").append(examples(pick(items, Comparer.EXTRA,
					i -> i.scorePos() + " " + i.score()))).append(')');
		}
		if (mt.refNotes() > 0 && c.issues() == 0) {
			sb.append(" — 모두 일치");
		}
		if (mt.ignoredZeroLength() > 0) {
			sb.append(" (원곡에서 길이 0이라 울리지 않는 음 ").append(mt.ignoredZeroLength()).append("개는 세지 않음)");
		}
		if (mt.silentNotes() > 0) {
			sb.append(" (무음 구간에 적혀 소리가 나지 않는 악보 음 ").append(mt.silentNotes()).append("개는 뺌)");
		}
		if (mt.octaveShiftMain() != null && mt.octaveShiftMain() != 0) {
			sb.append(" (음이름이 맞는 짝은 주로 원곡보다 ").append(semisText(mt.octaveShiftMain()))
					.append(Comparer.SOUNDING.equals(mt.pitchMode()) ? " 울림)" : " 적힘)");
		}
		return sb.toString();
	}

	/** "합산(중복 제거) 일치: 원곡 120음 중 116음(96.7%) — 참조 5번 116/120(악보 트랙 1·2·3)". */
	static String unionSentence(CompareReport.Union u) {
		StringBuilder sb = new StringBuilder("합산(중복 제거) 일치: 원곡 ").append(u.total()).append("음 중 ")
				.append(u.matchedAny()).append("음");
		if (u.ratio() != null) {
			sb.append('(').append(percent(u.ratio())).append(')');
		}
		sb.append("이 매핑한 악보 트랙 중 어디서든 일치");
		int sum = 0;
		for (CompareReport.UnionTrack t : u.tracks()) {
			sum += t.total();
		}
		if (sum > u.total()) {
			sb.append("(참조 트랙끼리 같은 시작·같은 음인 ").append(sum - u.total()).append("음은 한 번만 셈)");
		}
		List<String> l = new ArrayList<>();
		for (CompareReport.UnionTrack t : u.tracks()) {
			List<String> st = new ArrayList<>();
			for (int n : t.scoreTracks()) {
				st.add(String.valueOf(n));
			}
			l.add("참조 " + t.refTrack() + "번 " + t.matchedAny() + "/" + t.total() + "(악보 트랙 " + String.join("·", st) + ")");
		}
		if (!l.isEmpty()) {
			sb.append(" — ").append(String.join(", ", l));
		}
		return sb.toString();
	}

	private static String percent(double r) {
		return String.format(java.util.Locale.ROOT, "%.1f%%", r * 100);
	}

	private static String shiftText(Long diff) {
		if (diff == null) {
			return "";
		}
		return diff > 0 ? diff + "틱 늦게" : (-diff) + "틱 일찍";
	}

	private static String crossingLabel(Crossing c) {
		if (c.melody() != null) {
			return c.pos() + " 멜로디 " + c.melody() + subject(c.melody()) + " 화음1 " + c.chord1() + "보다 낮음";
		}
		return c.pos() + " 화음1 " + c.chord1() + subject(c.chord1()) + " 화음2 " + c.chord2() + "보다 낮음";
	}

	/**
	 * 음 이름 뒤 주격 조사 이/가. 이름은 옥타브 숫자로 끝나므로 그 숫자를 읽는 소리의 받침으로 고른다
	 * (0 영, 1 일, 3 삼, 6 육, 7 칠, 8 팔 → 이 / 2 이, 4 사, 5 오, 9 구 → 가).
	 */
	static String subject(String name) {
		if (name == null || name.isEmpty()) {
			return "이";
		}
		return "2459".indexOf(name.charAt(name.length() - 1)) >= 0 ? "가" : "이";
	}

	/** 성부 교차 상세의 머리(개수와 설명). 뒤에 ": " + 트랙별 항목이 붙는다. */
	static final String CROSSING_HEAD_NOTE = "(위 성부 음이 아래 성부보다 낮아지는 곳, 전문 편곡에서도 흔함)";

	/**
	 * 성부 교차 트랙 항목 하나. 언제나 {@code 트랙 T K곳(처음 위치 설명[, 위치 설명…])} 꼴이고, 예시는 시작 순으로 shown개까지.
	 */
	static String crossingTrackItem(int track, List<Crossing> l, int shown) {
		List<String> ex = new ArrayList<>();
		for (int i = 0; i < Math.min(shown, l.size()); i++) {
			ex.add(crossingLabel(l.get(i)));
		}
		return "트랙 " + track + " " + l.size() + "곳(처음 " + String.join(", ", ex) + ")";
	}

	/**
	 * 형식을 하나로 맞췄다. {@code 참고: 성부 교차 N곳(설명): 트랙 T K곳(처음 …)[, 트랙 …][ 외 M개 트랙]}.
	 * 교차가 있는 트랙이 하나면 그 트랙 예시를 3개까지, 여럿이면 트랙마다 첫 예시 하나(많은 순 3트랙까지).
	 */
	private static String crossingSentence(CompareReport r) {
		Map<Integer, List<Crossing>> byTrack = new TreeMap<>();
		int total = 0;
		for (MeasureDetail d : r.measures()) {
			for (Crossing c : d.crossings()) {
				byTrack.computeIfAbsent(c.track(), k -> new ArrayList<>()).add(c);
				total++;
			}
		}
		if (total == 0) {
			return "참고: 성부 교차 없음";
		}
		String head = "참고: 성부 교차 " + total + "곳" + CROSSING_HEAD_NOTE;
		if (byTrack.size() == 1) {
			Map.Entry<Integer, List<Crossing>> e = byTrack.entrySet().iterator().next();
			return head + ": " + crossingTrackItem(e.getKey(), e.getValue(), EXAMPLES);
		}
		List<Map.Entry<Integer, List<Crossing>>> l = new ArrayList<>(byTrack.entrySet());
		l.sort((a, b) -> a.getValue().size() != b.getValue().size()
				? Integer.compare(b.getValue().size(), a.getValue().size())
				: Integer.compare(a.getKey(), b.getKey()));
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(EXAMPLES, l.size()); i++) {
			Map.Entry<Integer, List<Crossing>> e = l.get(i);
			parts.add(crossingTrackItem(e.getKey(), e.getValue(), 1));
		}
		return head + ": " + String.join(", ", parts) + (l.size() > EXAMPLES ? " 외 " + (l.size() - EXAMPLES) + "개 트랙" : "");
	}

	private static String rangeSentence(CompareReport r, boolean multiTrack) {
		List<String> ex = new ArrayList<>();
		for (MeasureDetail d : r.measures()) {
			for (RangeViolation v : d.range()) {
				ex.add((multiTrack ? "트랙 " + v.track() + " " : "") + v.pos() + " " + v.name() + "(음역 "
						+ nz(v.low()) + "~" + nz(v.high()) + ")");
			}
		}
		boolean props = false;
		List<String> unchecked = new ArrayList<>();
		for (RangeCheck rc : r.rangeChecks()) {
			if (rc.checked() && !"dls".equals(rc.rangeSource())) {
				props = true;
			}
			if (!rc.checked() && !Comparer.NOTE_DRUM.equals(rc.note())) {
				unchecked.add(String.valueOf(rc.track()));
			}
		}
		StringBuilder sb = new StringBuilder();
		if (ex.isEmpty()) {
			sb.append("음역 이탈 없음");
		} else {
			sb.append("음역 이탈(악기가 낼 수 없는 높이) ").append(ex.size()).append("음(").append(examples(ex)).append(')');
		}
		if (props) {
			sb.append(" — 음원(DLS) 대신 악기표 음역 기준");
		}
		if (!unchecked.isEmpty()) {
			sb.append(", 음역 정보가 없어 확인 못 한 트랙: ").append(String.join("·", dedupe(unchecked)));
		}
		return sb.toString();
	}

	private static String overText(List<String> over) {
		if (over.isEmpty()) {
			return "여유";
		}
		List<String> l = new ArrayList<>();
		for (String o : over) {
			String[] tp = o.split(":", 2);
			l.add("트랙 " + tp[0] + " " + partName(tp.length > 1 ? tp[1] : ""));
		}
		return String.join(", ", l) + " 초과";
	}

	/** 노래 파트가 있어 노래 제외 기준으로는 실을 수 없는 트랙. 없으면 빈 문자열. */
	private static String songNotAllowedText(CompareReport r) {
		List<String> l = new ArrayList<>();
		for (CompareReport.CharsTrack c : r.chars()) {
			if (c.songNotAllowed()) {
				l.add(String.valueOf(c.track()));
			}
		}
		return l.isEmpty() ? "" : "(트랙 " + String.join("·", l) + "에 노래 파트가 있어 이 기준으로는 실을 수 없음)";
	}

	private static String worstMeasures(List<MeasureDetail> ms, int off) {
		record Score(int measure, int total, String text) {}
		List<Score> l = new ArrayList<>();
		for (MeasureDetail d : ms) {
			int wrong = 0, shifted = 0, missing = 0, extra = 0;
			for (MelodyMeasure mm : d.melody()) {
				wrong += mm.counts().wrong();
				shifted += mm.counts().shifted();
				missing += mm.counts().missing();
				extra += mm.counts().extra();
			}
			int chordMiss = 0;
			for (ChordBeat c : d.chords()) {
				if (!c.missing().isEmpty()) {
					chordMiss++;
				}
			}
			// 성부 교차는 정보 등급이라 세지 않는다.
			int range = d.range().size();
			int over = d.overlaps().size();
			int total = wrong + shifted + missing + extra + chordMiss + range + over;
			if (total == 0) {
				continue;
			}
			List<String> parts = new ArrayList<>();
			add(parts, "다름", wrong);
			add(parts, "어긋남", shifted);
			add(parts, "빠짐", missing);
			add(parts, "원곡에 없는 음", extra);
			add(parts, "화음 빈 박", chordMiss);
			add(parts, "음역 이탈", range);
			add(parts, "겹침", over);
			l.add(new Score(d.measure(), total, measureLabel(d.measure(), off) + "(" + String.join("·", parts) + ")"));
		}
		if (l.isEmpty()) {
			return null;
		}
		l.sort((a, b) -> a.total() != b.total() ? Integer.compare(b.total(), a.total())
				: Integer.compare(a.measure(), b.measure()));
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(WORST, l.size()); i++) {
			out.add(l.get(i).text());
		}
		return String.join(", ", out);
	}

	private static void add(List<String> parts, String name, int n) {
		if (n > 0) {
			parts.add(name + " " + n);
		}
	}

	// ======================================================================
	// 쓰기 도구용 두 문장
	// ======================================================================

	static String brief(List<Integer> measures, Summary s, String reason) {
		return brief(measures, s, reason, Comparer.WRITTEN, 0);
	}

	/** 바뀐 마디는 악보 마디. sounding이면 "(들리는 음 기준)", 마디 차가 있으면 "(참조 = 악보 − n)"을 덧붙인다. */
	static String brief(List<Integer> measures, Summary s, String reason, String pitchMode, int off) {
		String label = measuresLabel(measures);
		StringBuilder sb = new StringBuilder(label);
		List<String> basis = new ArrayList<>();
		if (Comparer.SOUNDING.equals(pitchMode)) {
			basis.add("들리는 음 기준");
		}
		if (off != 0) {
			basis.add("악보 마디, 참조 = 악보 " + signed(-off));
		}
		if (!basis.isEmpty()) {
			sb.append('(').append(String.join(", ", basis)).append(')');
		}
		sb.append(": ");
		String chord = (s.chordBeats() == 0) ? "화음 비교 박 없음"
				: (s.chordMissingBeats() == 0 ? "화음 빈 박 없음(" + s.chordBeats() + "박)"
						: "화음 빈 박 " + s.chordMissingBeats() + "/" + s.chordBeats());
		if (CompareSummary.REASON_NO_REFERENCE.equals(reason)) {
			sb.append("참조가 없어 멜로디·화음 비교는 건너뜀");
		} else if (CompareSummary.REASON_NO_MAPPING.equals(reason)) {
			sb.append("매핑이 없어 멜로디 비교는 건너뜀, ").append(chord);
		} else {
			MelodyCounts c = s.melody();
			sb.append("멜로디 ").append(c.refTotal()).append("음 중 ").append(c.matched()).append("음 일치");
			List<String> parts = new ArrayList<>();
			add(parts, "다름", c.wrong());
			add(parts, "어긋남", c.shifted());
			add(parts, "빠짐", c.missing());
			add(parts, "원곡에 없는 음", c.extra());
			add(parts, "옥타브만 달라 일치로 셈", c.octaveShifted());
			if (!parts.isEmpty()) {
				sb.append('(').append(String.join("·", parts)).append(')');
			}
			// 같은 참조 음을 여러 항목이 세었으면 합산(중복 제거) 수를 덧붙인다
			CompareReport.Union u = s.union();
			if (u != null && u.total() != c.refTotal()) {
				sb.append(", 합산(중복 제거) 일치 ").append(u.matchedAny()).append('/').append(u.total());
				if (u.ratio() != null) {
					sb.append('(').append(percent(u.ratio())).append(')');
				}
			}
			sb.append(", ").append(chord);
		}
		sb.append(". ");
		sb.append(s.rangeViolations() == 0 ? "음역 이탈 없음" : "음역 이탈 " + s.rangeViolations() + "음");
		sb.append(", ").append(s.overlaps() == 0 ? "겹침 없음" : "겹침 " + s.overlaps() + "곳");
		if (s.silentNotes() > 0) {
			sb.append(", 무음 구간 음 ").append(s.silentNotes()).append("개(소리 안 남)");
		}
		List<String> ex = s.charsOver().songExcluded();
		List<String> in = s.charsOver().songIncluded();
		if (ex.isEmpty() && in.isEmpty()) {
			sb.append(", 글자 수 여유");
		} else {
			sb.append(", 글자 수 초과(노래 제외 기준 ").append(ex.isEmpty() ? "없음" : String.join(" ", ex))
					.append(" / 노래 포함 기준 ").append(in.isEmpty() ? "없음" : String.join(" ", in)).append(')');
		}
		// 성부 교차는 정보 등급이라 문제 목록 뒤에 "참고"로 따로 쓴다(없으면 쓰지 않음). 두 문장 이내를 지키려고 같은 문장 끝에 붙인다.
		if (s.crossings() > 0) {
			sb.append(" — 참고: 성부 교차 ").append(s.crossings()).append("곳(전문 편곡에서도 흔함)");
		}
		sb.append('.');
		return sb.toString();
	}

	// ======================================================================
	// 도우미
	// ======================================================================

	static String rangeLabel(int from, int to) {
		return from == to ? from + "마디" : from + "~" + to + "마디";
	}

	/** 마디 차가 있으면 "참조 25~32마디(악보 27~34마디)". 없으면 {@link #rangeLabel(int, int)}. */
	static String rangeLabel(int from, int to, int off) {
		if (off == 0) {
			return rangeLabel(from, to);
		}
		return "참조 " + rangeLabel(from, to) + "(악보 " + rangeLabel(from + off, to + off) + ")";
	}

	/** "참조 27마디(악보 29마디)". 마디 차가 0이면 "27마디". */
	static String measureLabel(int m, int off) {
		return off == 0 ? m + "마디" : "참조 " + m + "마디(악보 " + (m + off) + "마디)";
	}

	static String signed(int v) {
		return v > 0 ? "+" + v : String.valueOf(v);
	}

	/** 반음 → "1옥타브 높게"·"2옥타브 낮게"·"7반음 높게". */
	static String semisText(int d) {
		String dir = d > 0 ? " 높게" : " 낮게";
		int a = Math.abs(d);
		return (a % 12 == 0 ? (a / 12) + "옥타브" : a + "반음") + dir;
	}

	private static String measuresLabel(List<Integer> ms) {
		if (ms.isEmpty()) {
			return "바뀐 마디 없음";
		}
		int first = ms.get(0);
		int last = ms.get(ms.size() - 1);
		if (last - first + 1 == ms.size()) {
			return rangeLabel(first, last);
		}
		if (ms.size() <= 4) {
			List<String> l = new ArrayList<>();
			for (int m : ms) {
				l.add(String.valueOf(m));
			}
			return String.join("·", l) + "마디";
		}
		return first + "~" + last + "마디 중 마디 " + ms.size() + "개";
	}

	/** "28:3:0" → "28:3". */
	private static String beatLabel(String pos) {
		return pos.endsWith(":0") ? pos.substring(0, pos.length() - 2) : pos;
	}

	static String partName(String part) {
		return switch (part) {
		case "melody" -> "멜로디";
		case "chord1" -> "화음1";
		case "chord2" -> "화음2";
		case "song" -> "노래";
		default -> part;
		};
	}

	private static List<String> pick(List<MelodyItem> items, String status, Function<MelodyItem, String> f) {
		List<String> l = new ArrayList<>();
		for (MelodyItem i : items) {
			if (status.equals(i.status())) {
				l.add(f.apply(i));
			}
		}
		return l;
	}

	private static String examples(List<String> l) {
		List<String> head = l.subList(0, Math.min(EXAMPLES, l.size()));
		String s = String.join(", ", head);
		return l.size() > EXAMPLES ? s + " …" : s;
	}

	private static String joinInts(List<Integer> l) {
		List<String> s = new ArrayList<>();
		for (int i : l) {
			s.add(String.valueOf(i));
		}
		return String.join("·", s);
	}

	private static List<String> dedupe(List<String> l) {
		List<String> out = new ArrayList<>();
		for (String s : l) {
			if (!out.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	private static String nz(String s) {
		return s == null ? "?" : s;
	}
}
