package mabibridge.compare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.compare.CompareReport.CharsOver;
import mabibridge.compare.CompareReport.CharsTrack;
import mabibridge.compare.CompareReport.ChordBeat;
import mabibridge.compare.CompareReport.Crossing;
import mabibridge.compare.CompareReport.MappingView;
import mabibridge.compare.CompareReport.MeasureDetail;
import mabibridge.compare.CompareReport.MelodyCounts;
import mabibridge.compare.CompareReport.MelodyItem;
import mabibridge.compare.CompareReport.MelodyMeasure;
import mabibridge.compare.CompareReport.MelodyTrack;
import mabibridge.compare.CompareReport.Overlap;
import mabibridge.compare.CompareReport.PitchSkip;
import mabibridge.compare.CompareReport.RangeCheck;
import mabibridge.compare.CompareReport.RangeViolation;
import mabibridge.compare.CompareReport.SoundingWarning;
import mabibridge.compare.CompareReport.Summary;
import mabibridge.compare.CompareReport.Union;
import mabibridge.compare.CompareReport.UnionTrack;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.NoteView;
import mabibridge.engine.Part;
import mabibridge.engine.Project;
import mabibridge.engine.SoundingPitch;
import mabibridge.engine.TrackStats;
import mabibridge.midi.BeatChord;
import mabibridge.midi.MeasureMap;
import mabibridge.midi.MidiNames;
import mabibridge.midi.Note;
import mabibridge.midi.ReferenceMidi;

/**
 * 원곡(참조 MIDI) 대비 비교. 상태를 갖지 않는 순수 함수 모음이다. 악보는 읽기만 한다.
 *
 * <p>6항목: (1) 멜로디 일치 (2) 화음 구성음 누락 (3) 파트 안 겹침 (4) 성부 교차 (5) 음역 이탈 (6) 글자 수 초과.
 * 참조가 없으면 (1)(2)는 건너뛰고 그 사실을 {@code notesSkipped}에 적는다.</p>
 *
 * <p>멜로디 짝짓기(1:1, 그리디): 참조 음과 악보 멜로디 음의 후보 쌍을 세 단계로 본다.
 * ① 같은 음 + 시작 차 ≤ 6틱 → matched, ② 시작 차 ≤ 6틱 + 다른 음 → wrong, ③ 같은 음 + 7 ≤ 차 ≤ 96 → shifted.
 * 각 단계에서 시작 차가 작은 쌍부터(같으면 참조 순, 악보 순) 아직 짝이 없는 두 음을 잇는다. 앞 단계가 먼저 음을 가져간다.
 * 남은 참조 음은 missing, 범위 안의 남은 악보 음은 extra. 범위 경계 밖 96틱 안의 음도 짝짓기에는 참여시켜
 * 경계에서 생기는 가짜 missing/extra를 줄인다(그 음들 자체는 세지 않는다).
 * 여러 참조 트랙을 합칠 때 같은 시작·같은 음은 하나로 본다.</p>
 *
 * <p>{@link Options}로 (a) {@code pitch_mode}("sounding"이면 악보 음을 {@link SoundingPitch} 표로 들리는 음으로 바꿔
 * 비교, 무음 구간 음은 빼고, 고정음·음높이 없음·효과음 악기 트랙은 음높이 비교를 하지 않음), (b) {@code measure_offset}
 * (악보 마디 = 참조 마디 + n. 마디 범위 from·to는 참조 마디), (c) {@code octave_tolerant}(① 다음에 "음이름 같고 옥타브만
 * 다름 + 시작 차 ≤ 6틱" 단계를 넣어 matched로 세고 octaveShifted로 따로 셈)를 고른다. 옛 시그니처는
 * {@link Options#LEGACY}(written, 0, 엄격) 그대로 와 같은 결과를 낸다.</p>
 */
public final class Comparer {

	/** 같은 시작으로 보는 최대 틱 차. */
	public static final int SAME_START = 6;
	/** shifted로 보는 최대 틱 차(4분음표). */
	public static final int SHIFT_MAX = 96;
	/**
	 * 악보 쪽 음높이 비교(멜로디 제안·화음·auto 마디 차·교차·음역)에서 빼는 악기 타입(타악기류: 드럼 D, 타악기 P, 모바일 타악기 M).
	 * 실로폰(K)은 음높이가 있어(들리는 음 표 +24) 여기서 뺐다. K는 멜로디 파트 하나만 쓰는 일반 악기처럼 비교한다.
	 */
	public static final Set<String> DRUM_TYPES = Set.of("D", "P", "M");
	/** measure_offset "auto"가 찾는 기본 범위({@link Options#autoOffsetMax}로 바꿀 수 있다). */
	public static final int AUTO_MIN = -4;
	public static final int AUTO_MAX = 4;

	public static final String SOUNDING = Mapping.PITCH_SOUNDING;
	public static final String WRITTEN = Mapping.PITCH_WRITTEN;

	static final String WRONG = "wrong";
	static final String SHIFTED = "shifted";
	static final String MISSING = "missing";
	static final String EXTRA = "extra";
	static final String MATCHED = "matched";

	/** RangeCheck.note: 음역 정보 없음. */
	public static final String NOTE_NO_RANGE = "음역 정보가 없어 검사하지 않음";
	/** RangeCheck.note: 타악기(음역 정보 없음). */
	public static final String NOTE_DRUM = "타악기라 음역 검사 안 함";

	private Comparer() {}

	/**
	 * 비교 선택. 값이 null이면 "호출에서 주지 않음"이다: 그러면 매핑 항목의 값(있으면), 그것도 없으면 기본값
	 * (pitch_mode "sounding", measure_offset 0)을 쓴다. 호출에서 준 값은 항목 값보다 앞선다.
	 *
	 * @param pitchMode      "sounding" | "written" | null
	 * @param measureOffset  악보 마디 = 참조 마디 + 이 값 | null
	 * @param autoOffset     true면 measureOffset을 무시하고 −autoOffsetMax~+autoOffsetMax 중 맞은 음이 가장 많은 값을 찾는다
	 * @param octaveTolerant 음이름이 같고 옥타브만 다른 음도 짝으로 인정
	 * @param autoOffsetMax  auto가 찾는 범위(1~64, 기본 {@link #AUTO_MAX})
	 */
	public record Options(String pitchMode, Integer measureOffset, boolean autoOffset, boolean octaveTolerant,
			int autoOffsetMax) {
		public Options {
			pitchMode = (pitchMode == null) ? null : Mapping.normalizePitchMode(pitchMode);
			if (autoOffsetMax < 1 || autoOffsetMax > AUTO_OFFSET_MAX_LIMIT) {
				throw new IllegalArgumentException("auto_offset_max는 1~" + AUTO_OFFSET_MAX_LIMIT + ": " + autoOffsetMax);
			}
		}

		/** 이전 형태(auto 범위 ±{@link #AUTO_MAX}). */
		public Options(String pitchMode, Integer measureOffset, boolean autoOffset, boolean octaveTolerant) {
			this(pitchMode, measureOffset, autoOffset, octaveTolerant, AUTO_MAX);
		}

		/** 예전 동작: 적은 음, 마디 차 0, 엄격. */
		public static final Options LEGACY = new Options(WRITTEN, 0, false, false);
		/** 도구 기본: 항목 값 또는 sounding·0·엄격. */
		public static final Options DEFAULTS = new Options(null, null, false, false);

		Options withOffset(int off) {
			return new Options(pitchMode, off, false, octaveTolerant, autoOffsetMax);
		}
	}

	/** auto_offset_max 상한. */
	public static final int AUTO_OFFSET_MAX_LIMIT = 64;

	// ======================================================================
	// 악보 음표 읽기
	// ======================================================================

	/**
	 * 악보 음표. pitch는 적은 음(MIDI), cmp는 비교에 쓰는 음(written이면 pitch, sounding이면 들리는 음; 음높이 없는 악기는
	 * {@link SoundingPitch#UNPITCHED}), silent는 sounding에서 무음 구간 음.
	 */
	record SNote(int track, Part part, int tick, int len, int pitch, int vel, int cmp, boolean silent) {
		int end() {
			return tick + len;
		}

		String name() {
			return NoteSpec.pitchName(pitch);
		}

		String cmpName() {
			return cmp >= 0 ? NoteSpec.pitchName(cmp) : null;
		}

		SNote withCmp(int c, boolean s) {
			return new SNote(track, part, tick, len, pitch, vel, c, s);
		}
	}

	/** 악보 트랙 하나. parts는 Part 순서, 각 목록은 시작 틱 순. */
	record TrackData(int number, String name, int program, int songProgram, InstrumentTable.Instrument inst,
			List<List<SNote>> parts) {
		boolean drum() {
			return inst != null && DRUM_TYPES.contains(inst.type());
		}

		List<SNote> part(Part p) {
			return parts.get(p.index());
		}

		/** 파트를 내는 악기 번호(노래 파트는 노래 악기). */
		int programFor(Part p) {
			return SoundingPitch.programFor(program, songProgram, p, inst == null ? null : inst.type());
		}

		/** 트랙 악기의 들리는 음 종류. */
		SoundingPitch.Kind kind() {
			return SoundingPitch.rule(program).kind();
		}

		/** 비교 음을 들리는 음으로 바꾼 사본. */
		TrackData sounding() {
			List<List<SNote>> ps = new ArrayList<>();
			for (Part p : Part.values()) {
				int prog = programFor(p);
				List<SNote> l = new ArrayList<>();
				for (SNote s : part(p)) {
					int c = SoundingPitch.soundingOf(prog, s.pitch());
					l.add(s.withCmp(c == SoundingPitch.SILENT ? -1 : c, c == SoundingPitch.SILENT));
				}
				ps.add(Collections.unmodifiableList(l));
			}
			return new TrackData(number, name, program, songProgram, inst, Collections.unmodifiableList(ps));
		}

		/** 비교 음을 semis만큼 옮긴 사본(무음·음높이 없는 음은 그대로). */
		TrackData shifted(int semis) {
			if (semis == 0) {
				return this;
			}
			List<List<SNote>> ps = new ArrayList<>();
			for (List<SNote> part : parts) {
				List<SNote> l = new ArrayList<>();
				for (SNote s : part) {
					l.add(s.cmp() >= 0 ? s.withCmp(s.cmp() + semis, s.silent()) : s);
				}
				ps.add(Collections.unmodifiableList(l));
			}
			return new TrackData(number, name, program, songProgram, inst, Collections.unmodifiableList(ps));
		}

		/** 이 비교 방식에서 음높이를 비교할 수 있는가(sounding이면 고정음·음높이 없음·효과음 악기는 아님). */
		boolean pitchComparable(String mode) {
			return !SOUNDING.equals(mode) || kind().pitched();
		}
	}

	static List<TrackData> readTracks(Project p, InstrumentTable table) {
		MMLScore score = p.score();
		List<TrackData> l = new ArrayList<>();
		for (int i = 0; i < score.getTrackCount(); i++) {
			MMLTrack t = score.getTrack(i);
			List<List<SNote>> parts = new ArrayList<>();
			for (Part part : Part.values()) {
				List<SNote> notes = new ArrayList<>();
				for (MMLNoteEvent e : t.getMMLEventAtIndex(part.index()).getMMLNoteEventList()) {
					int midi = e.getNote() + 12;
					notes.add(new SNote(i + 1, part, e.getTickOffset(), e.getTick(), midi, e.getVelocity(), midi, false));
				}
				notes.sort(Comparator.comparingInt(SNote::tick).thenComparingInt(SNote::pitch));
				parts.add(Collections.unmodifiableList(notes));
			}
			l.add(new TrackData(i + 1, t.getTrackName(), t.getProgram(), t.getSongProgram(),
					table == null ? null : table.get(t.getProgram()), Collections.unmodifiableList(parts)));
		}
		return l;
	}

	/** 비교 방식대로 읽는다(sounding이면 들리는 음). */
	static List<TrackData> readTracks(Project p, InstrumentTable table, String mode) {
		List<TrackData> w = readTracks(p, table);
		if (!SOUNDING.equals(mode)) {
			return w;
		}
		List<TrackData> s = new ArrayList<>();
		for (TrackData t : w) {
			s.add(t.sounding());
		}
		return s;
	}

	// ======================================================================
	// 옵션 풀기
	// ======================================================================

	/** 전체(화음 등)에 쓰는 비교 방식: 호출 값 → 항목들이 모두 같은 값을 적었으면 그 값 → sounding. */
	static String globalMode(Options opt, Mapping map) {
		if (opt.pitchMode() != null) {
			return opt.pitchMode();
		}
		String common = null;
		for (Mapping.Entry e : map.tracks().values()) {
			if (e.pitchMode() == null) {
				continue;
			}
			if (common == null) {
				common = e.pitchMode();
			} else if (!common.equals(e.pitchMode())) {
				return SOUNDING;
			}
		}
		return common == null ? SOUNDING : common;
	}

	/** auto가 아닐 때 전체 마디 차: 호출 값 → 항목들이 모두 같은 값을 적었으면 그 값 → 0. */
	static int globalOffset(Options opt, Mapping map) {
		if (opt.measureOffset() != null) {
			return opt.measureOffset();
		}
		Integer common = null;
		for (Mapping.Entry e : map.tracks().values()) {
			if (e.measureOffset() == null) {
				continue;
			}
			if (common == null) {
				common = e.measureOffset();
			} else if (!common.equals(e.measureOffset())) {
				return 0;
			}
		}
		return common == null ? 0 : common;
	}

	private static String trackMode(Options opt, Mapping.Entry me, String gMode) {
		if (opt.pitchMode() != null) {
			return opt.pitchMode();
		}
		return me.pitchMode() != null ? me.pitchMode() : gMode;
	}

	private static int trackOffset(Options opt, Mapping.Entry me, int gOff) {
		if (opt.autoOffset() || opt.measureOffset() != null) {
			return gOff;
		}
		return me.measureOffset() != null ? me.measureOffset() : gOff;
	}

	/** 악보 틱 − 참조 틱(96 기준): 참조 from 마디 시작이 악보 from+off 마디 시작에 오도록. */
	static long shiftOf(MeasureMap smap, MeasureMap rmap, int from, int off) {
		return smap.measureStart(from + off) - rmap.measureStart(from);
	}

	// ======================================================================
	// compare
	// ======================================================================

	/** 동작({@link Options#LEGACY}: written, 마디 차 0, 엄격). */
	public static CompareReport compare(Project p, ReferenceMidi ref, Mapping mapping, int from, int to,
			Set<Integer> scoreTracks, InstrumentTable table, boolean dlsLoaded) {
		return compare(p, ref, mapping, from, to, scoreTracks, table, dlsLoaded, Options.LEGACY);
	}

	/**
	 * 마디 범위를 비교한다.
	 *
	 * @param p           악보(읽기만)
	 * @param ref         참조(null이면 멜로디·화음 비교를 건너뜀)
	 * @param mapping     대응표(null 또는 비어 있으면 멜로디 비교를 건너뛰고 화음은 참조 전체와 비교)
	 * @param from        시작 마디(1부터, 참조 마디)
	 * @param to          끝 마디(포함, 참조 마디)
	 * @param scoreTracks 검사할 악보 트랙(null 또는 비어 있으면 전체)
	 * @param table       악기표(음역·타입). null이면 음역 검사와 타악기 판정을 건너뜀
	 * @param dlsLoaded   DLS를 읽은 상태인가(보고에 그대로 적는다)
	 * @param opt         선택(null이면 {@link Options#DEFAULTS})
	 * @throws IllegalArgumentException 마디 범위가 틀렸거나 악보 쪽 시작 마디(from + offset)가 1보다 작을 때
	 */
	public static CompareReport compare(Project p, ReferenceMidi ref, Mapping mapping, int from, int to,
			Set<Integer> scoreTracks, InstrumentTable table, boolean dlsLoaded, Options opt) {
		return compare(p, ref, mapping, from, to, scoreTracks, table, dlsLoaded, opt, new UnionAcc());
	}

	/** compare 본체. acc에 멜로디 짝짓기의 참조 음별 결과를 모은다(합산 일치). */
	private static CompareReport compare(Project p, ReferenceMidi ref, Mapping mapping, int from, int to,
			Set<Integer> scoreTracks, InstrumentTable table, boolean dlsLoaded, Options opt, UnionAcc acc) {
		if (p == null) {
			throw new IllegalArgumentException("악보가 없음");
		}
		if (from < 1 || to < from) {
			throw new IllegalArgumentException("마디 범위가 틀림: " + from + "~" + to + " (1 이상, from ≤ to)");
		}
		Options o = (opt == null) ? Options.DEFAULTS : opt;
		Mapping map = (mapping == null) ? Mapping.empty() : mapping;
		List<String> skipped = new ArrayList<>();
		MeasureMap smap = p.measureMap();
		String gMode = globalMode(o, map);

		Map<String, Integer> offScores = null;
		int gOff;
		if (o.autoOffset() && ref != null) {
			offScores = autoOffsetScores(p, ref, map, from, to, scoreTracks, table, o, gMode);
			gOff = bestOffset(offScores);
			skipped.addAll(autoOffsetNotes(p, offScores, from, to, gOff, o.autoOffsetMax()));
		} else {
			gOff = globalOffset(o, map);
		}
		if (o.autoOffset() && ref == null) {
			skipped.add("measure_offset auto: 참조 MIDI가 없어 찾지 않음(0으로 비교)");
		}
		if (from + gOff < 1) {
			throw new IllegalArgumentException("악보 쪽 시작 마디가 1보다 작음: 참조 " + from + "마디 + measure_offset " + gOff);
		}
		long rs = smap.measureStart(from + gOff);
		long re = smap.measureEnd(to + gOff);
		MeasureMap rmap = (ref == null) ? null : ref.measureMap96();
		long gShift = (ref == null) ? 0 : shiftOf(smap, rmap, from, gOff);

		List<TrackData> written = readTracks(p, table);
		List<TrackData> cmpAll = readTracks(p, table, gMode);
		List<TrackData> sel = selectTracks(written, scoreTracks, skipped);
		List<TrackData> selCmp = new ArrayList<>();
		Set<Integer> selNums = new TreeSet<>();
		for (TrackData t : sel) {
			selNums.add(t.number());
			selCmp.add(cmpAll.get(t.number() - 1));
		}

		TreeMap<Integer, Builder> measures = new TreeMap<>();
		for (int m = from; m <= to; m++) {
			measures.put(m, new Builder(m, m + gOff));
		}

		if (ref != null && !rmap.signatures().equals(smap.signatures())) {
			skipped.add("악보와 참조의 박자 구성이 달라 마디 위치가 어긋날 수 있음(멜로디는 절대 틱으로, 화음 박 경계는 참조 기준으로 비교)");
		}

		// 음높이 비교를 하지 않는 트랙: 타악기(D·P·M, 비교 방식과 무관)와 sounding일 때 고정음·음높이 없음·효과음 악기
		List<PitchSkip> pitchSkips = new ArrayList<>();
		for (TrackData td : sel) {
			if (td.drum()) {
				pitchSkips.add(drumSkip(td));
			} else if (SOUNDING.equals(gMode) && !td.kind().pitched()) {
				pitchSkips.add(pitchSkip(td));
			}
		}

		// (1) 멜로디
		List<MelodyTrack> melodyTracks = new ArrayList<>();
		if (ref == null) {
			skipped.add("멜로디 일치: 참조 MIDI가 없어 건너뜀");
		} else if (!map.hasMelody()) {
			skipped.add("멜로디 일치: 매핑(melody_from)이 없어 건너뜀");
		} else {
			for (Map.Entry<Integer, Mapping.Entry> e : map.tracks().entrySet()) {
				int st = e.getKey();
				Mapping.Entry me = e.getValue();
				if (!me.hasMelody()) {
					continue;
				}
				TrackData tdw = find(written, st);
				if (tdw == null) {
					skipped.add("멜로디 일치: 매핑의 악보 트랙 " + st + "번이 없어 건너뜀");
					continue;
				}
				if (!selNums.contains(st)) {
					continue;
				}
				String tMode = trackMode(o, me, gMode);
				int tOff = trackOffset(o, me, gOff);
				if (from + tOff < 1) {
					skipped.add("멜로디 일치(악보 트랙 " + st + "): 악보 쪽 시작 마디가 1보다 작아 건너뜀(measure_offset " + tOff + ")");
					continue;
				}
				if (!tdw.pitchComparable(tMode)) {
					skipped.add("멜로디 일치(악보 트랙 " + st + "): " + kindText(tdw) + " 악기라 음높이 비교를 하지 않음");
					if (!SOUNDING.equals(gMode) && !tdw.drum()) {
						pitchSkips.add(pitchSkip(tdw));
					}
					continue;
				}
				List<Integer> srcTracks = validRefTracks(ref, me.melodyFrom(), "멜로디 일치(악보 트랙 " + st + ")", skipped);
				if (srcTracks.isEmpty()) {
					continue;
				}
				TrackData td = SOUNDING.equals(tMode) ? tdw.sounding() : tdw;
				long tShift = shiftOf(smap, rmap, from, tOff);
				long trs = smap.measureStart(from + tOff);
				long tre = smap.measureEnd(to + tOff);
				melodyTracks.add(melody(td, srcTracks, me.matchAny(), ref, smap, from, to, trs, tre, tShift, tOff, tMode,
						o.octaveTolerant(), measures, acc));
			}
		}

		// (2) 화음
		List<Integer> chordsFromUsed = null;
		if (ref == null) {
			skipped.add("화음 구성음: 참조 MIDI가 없어 건너뜀");
		} else {
			chordsFromUsed = chordSourceTracks(map, selNums, ref, skipped);
			chords(selCmp, ref, chordsFromUsed, from, to, measures, skipped, gShift, gMode);
		}

		// (3) 겹침, (4) 교차(타악기 트랙은 음 번호가 악기 종류라 교차를 보지 않는다). 적은 음 기준.
		List<String> drumCross = new ArrayList<>();
		for (TrackData td : sel) {
			overlaps(td, smap, rs, re, gOff, measures);
			if (td.drum()) {
				drumCross.add(String.valueOf(td.number()));
			} else {
				crossings(td, smap, rs, re, gOff, measures);
			}
		}
		if (!drumCross.isEmpty()) {
			skipped.add("성부 교차: 타악기 트랙(" + String.join(", ", drumCross) + "번)은 검사하지 않음");
		}

		// (5) 음역(적은 음 기준)
		List<RangeCheck> rangeChecks = new ArrayList<>();
		if (table == null) {
			skipped.add("음역 이탈: 악기표가 없어 건너뜀");
		} else {
			for (TrackData td : sel) {
				ranges(td, table, smap, rs, re, gOff, measures, rangeChecks);
			}
			boolean props = false;
			List<String> noRange = new ArrayList<>();
			List<String> drums = new ArrayList<>();
			for (RangeCheck rc : rangeChecks) {
				props |= rc.checked() && !"dls".equals(rc.rangeSource());
				if (rc.checked()) {
					continue;
				}
				String label = rc.track() + (rc.instrument() == null ? "" : "(" + rc.instrument() + ")");
				if (NOTE_DRUM.equals(rc.note())) {
					drums.add(label);
				} else if (NOTE_NO_RANGE.equals(rc.note())) {
					noRange.add(label);
				} else {
					skipped.add("음역 이탈: 트랙 " + rc.track() + " " + rc.note());
				}
			}
			if (props) {
				skipped.add("음역 이탈: DLS 음역을 쓸 수 없어 악기표(properties) 음역으로 대신 검사함");
			}
			if (!noRange.isEmpty()) {
				skipped.add("음역 이탈: 음역 정보가 없어 검사하지 않은 트랙 " + String.join(", ", noRange)
						+ (dlsLoaded ? "" : " — DLS를 읽으면 검사할 수 있음"));
			}
			if (!drums.isEmpty()) {
				skipped.add("음역 이탈: 타악기 트랙 " + String.join(", ", drums) + "은 음역 검사 안 함");
			}
		}

		// 들리는 음 경고(무음 구간 음, 세트 섞임). 비교 방식과 무관하게 악보 사실로 본다.
		List<SoundingWarning> warnings = new ArrayList<>();
		for (TrackData td : sel) {
			soundingWarnings(td, smap, rs, re, gOff, measures, warnings);
		}

		// (6) 글자 수
		List<CharsTrack> chars = new ArrayList<>();
		List<String> overEx = new ArrayList<>();
		List<String> overIn = new ArrayList<>();
		for (TrackData td : sel) {
			TrackStats s = p.statsOf(td.number());
			chars.add(new CharsTrack(td.number(), s.chars(), s.limits().songExcluded().over(),
					s.limits().songIncluded().over(), s.limits().songExcluded().songNotAllowed(), s.defaultRule()));
			for (String part : s.limits().songExcluded().over()) {
				overEx.add(td.number() + ":" + part);
			}
			for (String part : s.limits().songIncluded().over()) {
				overIn.add(td.number() + ":" + part);
			}
		}
		CharsOver charsOver = new CharsOver(List.copyOf(overEx), List.copyOf(overIn));

		List<MeasureDetail> details = new ArrayList<>();
		for (Builder b : measures.values()) {
			details.add(b.build());
		}
		Summary summary = summarize(details, charsOver);
		if (!melodyTracks.isEmpty()) {
			summary = summary.withUnion(acc.build(null));
		}

		CompareReport noText = new CompareReport(List.of(from, to), mappingView(map), chordsFromUsed,
				List.copyOf(selNums), dlsLoaded, summary, List.copyOf(melodyTracks), List.copyOf(details),
				List.copyOf(rangeChecks), List.copyOf(chars), List.copyOf(skipped), gMode, gOff, o.autoOffset() && ref != null,
				offScores, List.of(from + gOff, to + gOff), o.octaveTolerant(), List.copyOf(pitchSkips),
				List.copyOf(warnings), null);
		return withText(noText, CompareText.full(noText, ref != null));
	}

	static CompareReport withText(CompareReport r, String text) {
		return new CompareReport(r.range(), r.mappingUsed(), r.chordsFrom(), r.scoreTracks(), r.dlsLoaded(),
				r.summary(), r.melodyTracks(), r.measures(), r.rangeChecks(), r.chars(), r.notesSkipped(), r.pitchMode(),
				r.measureOffsetUsed(), r.measureOffsetAuto(), r.measureOffsetScores(), r.scoreRange(), r.octaveTolerant(),
				r.pitchCompareSkipped(), r.soundingWarnings(), text);
	}

	private static PitchSkip pitchSkip(TrackData td) {
		return new PitchSkip(td.number(), td.program(), td.inst() == null ? null : td.inst().name(), td.kind().key(),
				kindText(td) + " 악기라 음높이 비교(멜로디 일치·화음)를 하지 않음");
	}

	/** {@link PitchSkip#kind()}의 타악기 값. */
	public static final String KIND_DRUM = "drum";
	/** 타악기 트랙의 {@link PitchSkip#reason()}. */
	public static final String REASON_DRUM = "타악기라 음높이 비교(화음·성부 교차·음역)를 하지 않음(음 번호가 음높이가 아니라 북·심벌 종류)";

	/** 타악기 트랙(D·P·M)은 비교 방식과 무관하게 음높이 비교에서 빠진다. */
	private static PitchSkip drumSkip(TrackData td) {
		return new PitchSkip(td.number(), td.program(), td.inst() == null ? null : td.inst().name(), KIND_DRUM, REASON_DRUM);
	}

	private static String kindText(TrackData td) {
		return switch (td.kind()) {
		case FIXED -> "고정음";
		case UNPITCHED -> "음높이 없는";
		case SFX -> "효과음";
		default -> td.kind().key();
		};
	}

	// ======================================================================
	// measure_offset auto
	// ======================================================================

	/**
	 * auto 결과의 알림. 모든 후보 점수가 0이면(맞는 음이 하나도 없음) 0을 고른 사실을, 고른 값이 찾는 범위의 끝이면
	 * 범위를 넓혀 보라고, 고른 마디 차의 악보 쪽 범위가 악보의 마지막 음 마디를 넘으면 그 사실을 적는다.
	 */
	static List<String> autoOffsetNotes(Project p, Map<String, Integer> scores, int from, int to, int off, int max) {
		List<String> l = new ArrayList<>();
		boolean allZero = true;
		for (int v : scores.values()) {
			if (v > 0) {
				allZero = false;
				break;
			}
		}
		if (allZero) {
			l.add("measure_offset auto: −" + max + "~+" + max + "의 모든 후보에서 맞는 음이 0개라 0으로 비교함(악보 쪽 범위가 "
					+ "악보 끝을 넘었거나 음이 맞는 곳이 없음 — 마디 범위나 auto_offset_max를 확인)");
		} else if (Math.abs(off) == max) {
			l.add("measure_offset auto: 고른 값 " + CompareText.signed(off) + "이 찾는 범위(±" + max
					+ ")의 끝이라 더 먼 마디 차가 맞을 수 있음(auto_offset_max를 늘려 확인)");
		}
		int last = lastNoteMeasure(p);
		if (last > 0 && to + off > last) {
			l.add("measure_offset auto: 악보 쪽 범위 " + (from + off) + "~" + (to + off) + "마디가 악보의 마지막 음 마디(" + last
					+ ")를 넘음");
		}
		return l;
	}

	/** 악보에서 음이 시작하는 마지막 마디(음이 없으면 0). */
	static int lastNoteMeasure(Project p) {
		long last = -1;
		for (TrackData t : readTracks(p, null)) {
			for (List<SNote> part : t.parts()) {
				for (SNote s : part) {
					last = Math.max(last, s.tick());
				}
			}
		}
		return last < 0 ? 0 : p.measureMap().measureOf(last);
	}

	/**
	 * −max~+max(기본 ±4, auto_offset_max)의 마디 차마다 맞은 음 수(옥타브 무시: 음이름이 같고 시작 차 ≤ 6틱인 1:1 짝).
	 * 매핑에 멜로디 항목이 있으면 그 트랙들의 합, 없으면 악보 트랙마다 가장 잘 맞는 참조 트랙의 합. 악보 쪽 시작 마디가 1보다
	 * 작아지는 차는 뺀다. 마디 차는 범위 전체에 하나다(구간마다 다른 차는 찾지 않음). 키는 "-4" … "4".
	 */
	static Map<String, Integer> autoOffsetScores(Project p, ReferenceMidi ref, Mapping map, int from, int to,
			Set<Integer> scoreTracks, InstrumentTable table, Options o, String gMode) {
		MeasureMap smap = p.measureMap();
		MeasureMap rmap = ref.measureMap96();
		List<TrackData> written = readTracks(p, table);
		Map<String, Integer> scores = new LinkedHashMap<>();
		List<Integer> refTracks = null;
		for (int off = -o.autoOffsetMax(); off <= o.autoOffsetMax(); off++) {
			if (from + off < 1) {
				continue;
			}
			long shift = shiftOf(smap, rmap, from, off);
			long rs = smap.measureStart(from + off);
			long re = smap.measureEnd(to + off);
			int total = 0;
			if (map.hasMelody()) {
				for (Map.Entry<Integer, Mapping.Entry> e : map.tracks().entrySet()) {
					Mapping.Entry me = e.getValue();
					TrackData tdw = find(written, e.getKey());
					if (!me.hasMelody() || tdw == null
							|| (scoreTracks != null && !scoreTracks.isEmpty() && !scoreTracks.contains(e.getKey()))) {
						continue;
					}
					String tMode = trackMode(o, me, gMode);
					if (!tdw.pitchComparable(tMode)) {
						continue;
					}
					TrackData td = SOUNDING.equals(tMode) ? tdw.sounding() : tdw;
					List<Integer> src = new ArrayList<>();
					for (int t : me.melodyFrom()) {
						if (t >= 1 && t <= ref.trackCount()) {
							src.add(t);
						}
					}
					if (src.isEmpty()) {
						continue;
					}
					total += countMatched(pair(td, src, ref, from, to, rs, re, me.matchAny(), shift, true), true);
				}
			} else {
				if (refTracks == null) {
					refTracks = candidateRefTracks(ref, from, to, null);
				}
				for (TrackData tdw : written) {
					if (scoreTracks != null && !scoreTracks.isEmpty() && !scoreTracks.contains(tdw.number())) {
						continue;
					}
					if (tdw.drum() || !tdw.pitchComparable(gMode)) {
						continue;
					}
					TrackData td = SOUNDING.equals(gMode) ? tdw.sounding() : tdw;
					int best = 0;
					for (int rt : refTracks) {
						best = Math.max(best, countMatched(pair(td, List.of(rt), ref, from, to, rs, re, true, shift, true), true));
					}
					total += best;
				}
			}
			scores.put(String.valueOf(off), total);
		}
		return scores;
	}

	/** 점수가 가장 높은 차. 같으면 절댓값이 작은 쪽, 그다음 작은 값. 점수가 없으면 0. */
	static int bestOffset(Map<String, Integer> scores) {
		Integer best = null;
		int bestScore = -1;
		for (Map.Entry<String, Integer> e : scores.entrySet()) {
			int off = Integer.parseInt(e.getKey());
			int sc = e.getValue();
			if (best == null || sc > bestScore || (sc == bestScore
					&& (Math.abs(off) < Math.abs(best) || (Math.abs(off) == Math.abs(best) && off < best)))) {
				best = off;
				bestScore = sc;
			}
		}
		return best == null ? 0 : best;
	}

	/** 범위 안에 길이 있는 드럼 아닌 음이 있는 참조 트랙. drumOnly가 null이 아니면 드럼 음만 있는 트랙을 더한다. */
	static List<Integer> candidateRefTracks(ReferenceMidi ref, int from, int to, List<String> drumOnly) {
		List<Integer> refTracks = new ArrayList<>();
		for (int t = 1; t <= ref.trackCount(); t++) {
			boolean any = false;
			boolean drum = false;
			for (Note n : ref.notes(t, from, to)) {
				if (n.isDrum()) {
					drum = true;
				} else if (n.srcLength() > 0) {
					any = true;
					break;
				}
			}
			if (any) {
				refTracks.add(t);
			} else if (drum && drumOnly != null) {
				drumOnly.add(String.valueOf(t));
			}
		}
		return refTracks;
	}

	/**
	 * 짝 중 "맞은" 것의 수. 같은 비교 음 + 시작 차 ≤ 6틱. octaveOk면 음이름만 같아도(12의 배수 차) 센다.
	 */
	static int countMatched(Pairing pg, boolean octaveOk) {
		int n = 0;
		for (int i = 0; i < pg.refs().size(); i++) {
			RefN r = pg.refs().get(i);
			int j = pg.pair()[i];
			if (!r.inRange() || j < 0) {
				continue;
			}
			SNote s = pg.sc().get(j);
			int d = s.cmp() - r.n().pitch();
			if (Math.abs(s.tick() - r.tick()) <= SAME_START && (d == 0 || (octaveOk && Math.floorMod(d, 12) == 0))) {
				n++;
			}
		}
		return n;
	}

	// ======================================================================
	// summary (쓰기 도구용)
	// ======================================================================

	/** 동작({@link Options#LEGACY}). */
	public static CompareSummary summary(Project p, ReferenceMidi ref, Mapping mapping, List<Integer> changedMeasures,
			InstrumentTable table, boolean dlsLoaded) {
		return summary(p, ref, mapping, changedMeasures, table, dlsLoaded, Options.LEGACY);
	}

	/**
	 * 바뀐 마디들만 짧게 요약한다. 바뀐 마디는 악보 마디이고, 마디 차(호출 값 → 항목 공통 값 → 0)를 빼서 참조 마디로 바꾼 뒤
	 * min~max 범위로 compare를 돌려 바뀐 마디의 결과만 더한다. auto는 쓰지 않는다(쓰기마다 탐색하지 않음).
	 * 참조가 없거나 매핑(melody_from)이 없으면 skipped = true와 이유를 적고, 나머지 항목(화음은 참조가 있을 때,
	 * 겹침·교차·음역·글자 수는 항상)은 그대로 넣는다.
	 */
	public static CompareSummary summary(Project p, ReferenceMidi ref, Mapping mapping, List<Integer> changedMeasures,
			InstrumentTable table, boolean dlsLoaded, Options opt) {
		Options o = (opt == null) ? Options.DEFAULTS : opt;
		Mapping map = (mapping == null) ? Mapping.empty() : mapping;
		int gOff = globalOffset(o, map);
		String gMode = globalMode(o, map);
		TreeSet<Integer> ms = new TreeSet<>();
		if (changedMeasures != null) {
			for (Integer m : changedMeasures) {
				if (m != null && m >= 1 && m - gOff >= 1) {
					ms.add(m);
				}
			}
		}
		if (ms.isEmpty()) {
			return new CompareSummary(true, CompareSummary.REASON_NO_CHANGE, List.of(), null,
					"바뀐 마디가 없어 비교하지 않았습니다.", gMode, gOff);
		}
		UnionAcc acc = new UnionAcc();
		CompareReport r = compare(p, ref, mapping, ms.first() - gOff, ms.last() - gOff, null, table, dlsLoaded,
				new Options(o.pitchMode(), o.measureOffset(), false, o.octaveTolerant()), acc);
		List<MeasureDetail> picked = new ArrayList<>();
		Set<Integer> pickedRef = new HashSet<>();
		for (MeasureDetail d : r.measures()) {
			if (ms.contains(d.scoreMeasure())) {
				picked.add(d);
				pickedRef.add(d.measure());
			}
		}
		Summary s = summarize(picked, r.summary().charsOver());
		if (!r.melodyTracks().isEmpty()) {
			s = s.withUnion(acc.build(pickedRef));
		}
		String reason = null;
		if (ref == null) {
			reason = CompareSummary.REASON_NO_REFERENCE;
		} else if (mapping == null || !mapping.hasMelody()) {
			reason = CompareSummary.REASON_NO_MAPPING;
		}
		List<Integer> list = List.copyOf(ms);
		return new CompareSummary(reason != null, reason, list, s, CompareText.brief(list, s, reason, r.pitchMode(), gOff),
				r.pitchMode(), gOff);
	}

	// ======================================================================
	// 합계
	// ======================================================================

	static Summary summarize(List<MeasureDetail> details, CharsOver charsOver) {
		MelodyCounts mc = MelodyCounts.ZERO;
		int beats = 0;
		int missBeats = 0;
		int extraBeats = 0;
		int overlaps = 0;
		int crossings = 0;
		int range = 0;
		int ignored = 0;
		int chordIgnored = 0;
		int silent = 0;
		for (MeasureDetail d : details) {
			for (MelodyMeasure mm : d.melody()) {
				mc = mc.plus(mm.counts());
				ignored += mm.ignoredZeroLength();
			}
			for (ChordBeat c : d.chords()) {
				beats++;
				chordIgnored += c.ignoredZeroLength();
				if (!c.missing().isEmpty()) {
					missBeats++;
				}
				if (!c.extra().isEmpty()) {
					extraBeats++;
				}
			}
			overlaps += d.overlaps().size();
			crossings += d.crossings().size();
			range += d.range().size();
			silent += d.silentNotes();
		}
		return new Summary(mc, beats, missBeats, extraBeats, overlaps, crossings, range, charsOver, ignored, chordIgnored,
				silent);
	}

	// ======================================================================
	// 공통 도우미
	// ======================================================================

	private static List<TrackData> selectTracks(List<TrackData> all, Set<Integer> scoreTracks, List<String> skipped) {
		if (scoreTracks == null || scoreTracks.isEmpty()) {
			return all;
		}
		List<TrackData> l = new ArrayList<>();
		for (int n : new TreeSet<>(scoreTracks)) {
			TrackData t = find(all, n);
			if (t == null) {
				skipped.add("악보 트랙 " + n + "번이 없어 뺌");
			} else {
				l.add(t);
			}
		}
		return l;
	}

	private static TrackData find(List<TrackData> all, int n) {
		return (n >= 1 && n <= all.size()) ? all.get(n - 1) : null;
	}

	private static List<Integer> validRefTracks(ReferenceMidi ref, Collection<Integer> want, String what,
			List<String> skipped) {
		List<Integer> ok = new ArrayList<>();
		TreeSet<Integer> sorted = new TreeSet<>();
		for (Integer t : want) {
			if (t == null) {
				skipped.add(what + ": 참조 트랙 번호가 비어 있어 뺌");
			} else {
				sorted.add(t);
			}
		}
		for (int t : sorted) {
			if (t < 1 || t > ref.trackCount()) {
				skipped.add(what + ": 참조 트랙 " + t + "번이 없어 뺌(1~" + ref.trackCount() + ")");
			} else {
				ok.add(t);
			}
		}
		return ok;
	}

	private static Map<String, MappingView> mappingView(Mapping m) {
		Map<String, MappingView> v = new LinkedHashMap<>();
		for (Map.Entry<Integer, Mapping.Entry> e : m.tracks().entrySet()) {
			Mapping.Entry me = e.getValue();
			v.put(String.valueOf(e.getKey()), new MappingView(me.melodyFrom() == null ? List.of() : me.melodyFrom(),
					me.chordsFrom() == null ? "all" : me.chordsFrom(), me.matchParts(), me.pitchMode(), me.measureOffset()));
		}
		return v;
	}

	private static String pos(MeasureMap smap, long tick) {
		return smap.position(Math.max(0, tick)).toString();
	}

	private static NoteView view(TrackData td, SNote n, MeasureMap smap) {
		return new NoteView(n.tick(), pos(smap, n.tick()), n.len(), n.pitch(), n.name(), n.vel())
				.withSounding(td.programFor(n.part()));
	}

	/** 마디별 결과를 모으는 가변 도우미. measure는 참조 마디, scoreMeasure는 악보 마디. */
	static final class Builder {
		final int measure;
		final int scoreMeasure;
		final Map<Integer, int[]> melodyCounts = new TreeMap<>(); // track → {matched, wrong, shifted, missing, extra, octave}
		final Map<Integer, List<MelodyItem>> melodyItems = new TreeMap<>();
		final Map<Integer, int[]> melodyIgnored = new TreeMap<>(); // track → {길이 0 음 수}
		final Map<Integer, int[]> melodyByPart = new TreeMap<>(); // track → Part 순서별 matched 수
		final List<ChordBeat> chords = new ArrayList<>();
		final List<Crossing> crossings = new ArrayList<>();
		final List<RangeViolation> range = new ArrayList<>();
		final List<Overlap> overlaps = new ArrayList<>();
		int silent;

		Builder(int measure, int scoreMeasure) {
			this.measure = measure;
			this.scoreMeasure = scoreMeasure;
		}

		void melodyTrack(int track) {
			melodyCounts.computeIfAbsent(track, k -> new int[6]);
			melodyItems.computeIfAbsent(track, k -> new ArrayList<>());
			melodyIgnored.computeIfAbsent(track, k -> new int[1]);
			melodyByPart.computeIfAbsent(track, k -> new int[Part.values().length]);
		}

		MeasureDetail build() {
			List<MelodyMeasure> mel = new ArrayList<>();
			for (Map.Entry<Integer, int[]> e : melodyCounts.entrySet()) {
				int[] c = e.getValue();
				List<MelodyItem> items = new ArrayList<>(melodyItems.get(e.getKey()));
				items.sort(Comparator.comparingLong(Builder::itemTick));
				mel.add(new MelodyMeasure(e.getKey(), new MelodyCounts(c[0], c[1], c[2], c[3], c[4], c[5]),
						List.copyOf(items), melodyIgnored.get(e.getKey())[0], partMap(melodyByPart.get(e.getKey()))));
			}
			List<Crossing> cr = new ArrayList<>(crossings);
			cr.sort(Comparator.comparingLong(Crossing::tick).thenComparingInt(Crossing::track));
			List<RangeViolation> rv = new ArrayList<>(range);
			rv.sort(Comparator.comparingLong(RangeViolation::tick).thenComparingInt(RangeViolation::track));
			return new MeasureDetail(measure, List.copyOf(mel), List.copyOf(chords), List.copyOf(cr), List.copyOf(rv),
					List.copyOf(overlaps), scoreMeasure, silent);
		}

		private static long itemTick(MelodyItem i) {
			return i.refTick() != null ? i.refTick() : i.scoreTick();
		}
	}

	/** Part 순서 배열 → {파트 키: 수}(0은 뺌, Part 순서). */
	static Map<String, Integer> partMap(int[] byPart) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			if (byPart[p.index()] > 0) {
				m.put(p.key(), byPart[p.index()]);
			}
		}
		return Collections.unmodifiableMap(m);
	}

	/** 마디 번호의 Builder. 범위 밖(박자 지도 차이 등)이면 가장 가까운 끝 마디. */
	private static Builder builderFor(TreeMap<Integer, Builder> measures, int m) {
		Builder b = measures.get(m);
		if (b != null) {
			return b;
		}
		return measures.get(Math.max(measures.firstKey(), Math.min(measures.lastKey(), m)));
	}

	/** 악보 틱의 Builder(악보 마디 − 마디 차 = 참조 마디). */
	private static Builder builderForScore(TreeMap<Integer, Builder> measures, MeasureMap smap, long tick, int off) {
		return builderFor(measures, smap.measureOf(Math.max(0, tick)) - off);
	}

	// ======================================================================
	// (1) 멜로디
	// ======================================================================

	/** 참조 음(짝짓기용). inRange = 세는 대상인가. tick = 악보 좌표로 옮긴 시작(tick96 + shift). */
	record RefN(Note n, boolean inRange, long tick) {
		RefN(Note n, boolean inRange) {
			this(n, inRange, n.tick96());
		}
	}

	/**
	 * 짝짓기 결과.
	 *
	 * @param refs     짝짓기에 쓴 참조 음(범위 안 + 경계 문맥, 길이 0 음은 뺌). 시작 순, 같으면 높은 음 먼저
	 * @param sc       짝짓기에 쓴 악보 음(멜로디 파트 먼저, any면 그 뒤에 chord1·chord2·song). 무음 음은 뺌
	 * @param isMelody sc[j]가 멜로디 파트 음인가
	 * @param pair     refs[i] → sc 인덱스(없으면 −1)
	 * @param ignored  범위 안에서 뺀 길이 0 참조 음(같은 시작·같은 음이 길이 있는 음으로도 있으면 넣지 않음)
	 * @param silent   범위 안에서 뺀 무음 악보 음(sc 후보 파트만)
	 */
	record Pairing(List<RefN> refs, List<SNote> sc, boolean[] isMelody, int[] pair, List<Note> ignored,
			List<SNote> silent) {}

	/** 형태(마디 차 0, 엄격). */
	static Pairing pair(TrackData td, List<Integer> srcTracks, ReferenceMidi ref, int from, int to, long rs, long re,
			boolean any) {
		return pair(td, srcTracks, ref, from, to, rs, re, any, 0, false);
	}

	/**
	 * 한 악보 트랙과 참조 트랙들의 1:1 짝짓기.
	 * 원본 길이(src_len)가 0인 참조 음은 울리지 않으므로 빼고 {@link Pairing#ignored}에 둔다.
	 * {@code any}면 악보 트랙의 모든 파트 음이 후보가 되지만, 멜로디가 아닌 파트는 단계 ①(같은 음·시작 차 ≤ 6)과
	 * (tolerant면) 옥타브 단계에만 참여한다(다른 성부의 음을 wrong·shifted로 잘못 잇지 않게).
	 *
	 * @param from·to 참조 마디
	 * @param rs·re   악보 쪽 틱 범위(악보 마디 from+off ~ to+off)
	 * @param shift   악보 틱 − 참조 틱({@link #shiftOf})
	 */
	static Pairing pair(TrackData td, List<Integer> srcTracks, ReferenceMidi ref, int from, int to, long rs, long re,
			boolean any, long shift, boolean tolerant) {
		MeasureMap rmap = ref.measureMap96();
		long refStart = rmap.measureStart(from) + shift;
		long refEnd = rmap.measureEnd(to) + shift;
		// 참조 음: 범위 + 앞뒤 한 마디(짝짓기 문맥용, 경계에서 96틱 안만)
		int ctxFrom = Math.max(1, from - 1);
		int ctxTo = to + 1;
		Map<String, Note> dedup = new LinkedHashMap<>();
		Map<String, Note> zero = new LinkedHashMap<>();
		for (int t : srcTracks) {
			for (Note n : ref.notes(t, ctxFrom, ctxTo)) {
				if (n.isDrum()) {
					continue;
				}
				String key = n.tick96() + "/" + n.pitch();
				if (n.srcLength() == 0) {
					zero.putIfAbsent(key, n);
				} else {
					dedup.putIfAbsent(key, n);
				}
			}
		}
		List<Note> ignored = new ArrayList<>();
		for (Map.Entry<String, Note> e : zero.entrySet()) {
			Note n = e.getValue();
			if (!dedup.containsKey(e.getKey()) && n.measure() >= from && n.measure() <= to) {
				ignored.add(n);
			}
		}
		List<RefN> refs = new ArrayList<>();
		for (Note n : dedup.values()) {
			boolean in = n.measure() >= from && n.measure() <= to;
			long t = n.tick96() + shift;
			if (in || (t >= refStart - SHIFT_MAX && t < refEnd + SHIFT_MAX)) {
				refs.add(new RefN(n, in, t));
			}
		}
		refs.sort(Comparator.comparingLong(RefN::tick).thenComparingInt(r -> -r.n().pitch()));

		long lo = Math.min(rs, refStart) - SHIFT_MAX;
		long hi = Math.max(re, refEnd) + SHIFT_MAX;
		List<SNote> sc = new ArrayList<>();
		List<Boolean> mel = new ArrayList<>();
		List<SNote> silent = new ArrayList<>();
		List<Part> parts = any ? List.of(Part.values()) : List.of(Part.MELODY);
		for (Part part : parts) {
			for (SNote s : td.part(part)) {
				if (s.tick() >= lo && s.tick() < hi) {
					if (s.silent()) {
						if (s.tick() >= rs && s.tick() < re) {
							silent.add(s);
						}
						continue;
					}
					sc.add(s);
					mel.add(part == Part.MELODY);
				}
			}
		}
		boolean[] isMelody = new boolean[sc.size()];
		for (int j = 0; j < isMelody.length; j++) {
			isMelody[j] = mel.get(j);
		}
		int[] pair = matchMelody(refs, sc, isMelody, tolerant);
		return new Pairing(refs, sc, isMelody, pair, List.copyOf(ignored), List.copyOf(silent));
	}

	private static MelodyTrack melody(TrackData td, List<Integer> srcTracks, boolean any, ReferenceMidi ref,
			MeasureMap smap, int from, int to, long rs, long re, long shift, int off, String mode, boolean tolerant,
			TreeMap<Integer, Builder> measures, UnionAcc acc) {
		acc.entry(td.number(), srcTracks);
		Pairing pg = pair(td, srcTracks, ref, from, to, rs, re, any, shift, tolerant);
		List<RefN> refs = pg.refs();
		List<SNote> sc = pg.sc();
		int[] pair = pg.pair();
		boolean[] scUsed = new boolean[sc.size()];
		for (int j : pair) {
			if (j >= 0) {
				scUsed[j] = true;
			}
		}

		for (Builder b : measures.values()) {
			b.melodyTrack(td.number());
		}
		for (Note z : pg.ignored()) {
			builderFor(measures, z.measure()).melodyIgnored.get(td.number())[0]++;
		}
		int[] total = new int[6];
		int[] byPart = new int[Part.values().length];
		int refCount = 0;
		int wrongOctave = 0;
		Map<Integer, Integer> shiftHist = new TreeMap<>();
		for (int i = 0; i < refs.size(); i++) {
			RefN r = refs.get(i);
			if (!r.inRange()) {
				continue;
			}
			refCount++;
			Note n = r.n();
			SNote s = (pair[i] >= 0) ? sc.get(pair[i]) : null;
			String status;
			int k;
			boolean octaveMatch = false;
			long ad = (s == null) ? Long.MAX_VALUE : Math.abs(s.tick() - r.tick());
			int diff = (s == null) ? 0 : s.cmp() - n.pitch();
			if (s == null) {
				status = MISSING;
				k = 3;
			} else if (diff != 0) {
				if (tolerant && ad <= SAME_START && Math.floorMod(diff, 12) == 0) {
					status = MATCHED;
					k = 0;
					octaveMatch = true;
				} else {
					status = WRONG;
					k = 1;
				}
			} else if (ad <= SAME_START) {
				status = MATCHED;
				k = 0;
			} else {
				status = SHIFTED;
				k = 2;
			}
			if (s != null && ad <= SAME_START && Math.floorMod(diff, 12) == 0) {
				shiftHist.merge(diff, 1, Integer::sum);
			}
			acc.note(n, td.number(), k == 0);
			Builder b = builderFor(measures, n.measure());
			b.melodyCounts.get(td.number())[k]++;
			total[k]++;
			if (k == 0) {
				b.melodyByPart.get(td.number())[s.part().index()]++;
				byPart[s.part().index()]++;
				if (octaveMatch) {
					b.melodyCounts.get(td.number())[5]++;
					total[5]++;
				}
				continue;
			}
			Boolean octave = null;
			if (k == 1) {
				octave = Math.floorMod(diff, 12) == 0;
				if (octave) {
					wrongOctave++;
				}
			}
			b.melodyItems.get(td.number()).add(new MelodyItem(status, n.pos(), n.track(), NoteSpec.pitchName(n.pitch()),
					n.pitch(), n.tick96(), n.len96(), s == null ? null : s.cmpName(), s == null ? null : s.cmp(),
					s == null ? null : (long) s.tick(), s == null ? null : (long) s.len(),
					s == null ? null : pos(smap, s.tick()), s == null ? null : s.tick() - r.tick(),
					s == null ? null : s.len() - n.len96(), octave, s == null ? null : s.part().key(),
					s == null ? null : s.name(), s == null ? null : s.pitch()));
		}
		// extra·scoreNotes는 멜로디 파트만 센다(any여도 다른 파트의 짝 없는 음은 다른 원곡 트랙을 담았을 수 있다).
		int scoreCount = 0;
		for (int j = 0; j < sc.size(); j++) {
			SNote s = sc.get(j);
			if (!pg.isMelody()[j] || s.tick() < rs || s.tick() >= re) {
				continue;
			}
			scoreCount++;
			if (scUsed[j]) {
				continue;
			}
			Builder b = builderForScore(measures, smap, s.tick(), off);
			b.melodyCounts.get(td.number())[4]++;
			total[4]++;
			b.melodyItems.get(td.number()).add(new MelodyItem(EXTRA, pos(smap, s.tick()), null, null, null, null, null,
					s.cmpName(), s.cmp(), (long) s.tick(), (long) s.len(), pos(smap, s.tick()), null, null, null,
					s.part().key(), s.name(), s.pitch()));
		}
		Integer main = null;
		int mainCount = 0;
		Map<String, Integer> hist = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : shiftHist.entrySet()) {
			hist.put(shiftKey(e.getKey()), e.getValue());
			if (e.getValue() > mainCount || (e.getValue() == mainCount && main != null && Math.abs(e.getKey()) < Math.abs(main))) {
				main = e.getKey();
				mainCount = e.getValue();
			}
		}
		return new MelodyTrack(td.number(), srcTracks, refCount, scoreCount,
				new MelodyCounts(total[0], total[1], total[2], total[3], total[4], total[5]), wrongOctave,
				any ? Mapping.MATCH_ANY : Mapping.MATCH_MELODY, pg.ignored().size(), partMap(byPart), mode, off,
				pg.silent().size(), main, Collections.unmodifiableMap(hist));
	}

	/**
	 * 합산(중복 제거) 일치를 모은다. 참조 음은 (참조 트랙, 시작, 음)으로 하나, 전체 합은 (시작, 음)으로 하나다.
	 * 매핑 항목(악보 트랙)마다 {@link #melody}가 범위 안 참조 음의 판정을 넣고, 하나라도 matched면 맞은 것으로 본다.
	 */
	static final class UnionAcc {
		/** "트랙/틱/음" → {참조 트랙, 마디, 틱, 음, matched(0|1)}. */
		private final Map<String, long[]> notes = new LinkedHashMap<>();
		/** 참조 트랙 → 그 트랙을 melody_from에 둔 악보 트랙들. */
		private final Map<Integer, Set<Integer>> scoreTracks = new TreeMap<>();
		private final Set<Integer> entries = new TreeSet<>();

		void entry(int scoreTrack, List<Integer> srcTracks) {
			entries.add(scoreTrack);
			for (int t : srcTracks) {
				scoreTracks.computeIfAbsent(t, k -> new TreeSet<>()).add(scoreTrack);
			}
		}

		void note(Note n, int scoreTrack, boolean matched) {
			long[] v = notes.computeIfAbsent(n.track() + "/" + n.tick96() + "/" + n.pitch(),
					k -> new long[] { n.track(), n.measure(), n.tick96(), n.pitch(), 0 });
			if (matched) {
				v[4] = 1;
			}
		}

		/** refMeasures가 null이면 전부, 아니면 그 참조 마디의 음만 센다. */
		Union build(Set<Integer> refMeasures) {
			Map<Integer, int[]> perTrack = new TreeMap<>();
			for (int t : scoreTracks.keySet()) {
				perTrack.put(t, new int[2]);
			}
			Map<String, Boolean> overall = new LinkedHashMap<>();
			for (long[] v : notes.values()) {
				if (refMeasures != null && !refMeasures.contains((int) v[1])) {
					continue;
				}
				int[] c = perTrack.computeIfAbsent((int) v[0], k -> new int[2]);
				c[0]++;
				c[1] += (int) v[4];
				overall.merge(v[2] + "/" + v[3], v[4] == 1, Boolean::logicalOr);
			}
			List<UnionTrack> tracks = new ArrayList<>();
			for (Map.Entry<Integer, int[]> e : perTrack.entrySet()) {
				int[] c = e.getValue();
				Set<Integer> st = scoreTracks.getOrDefault(e.getKey(), Set.of());
				tracks.add(new UnionTrack(e.getKey(), c[0], c[1], ratio(c[1], c[0]), List.copyOf(st)));
			}
			int total = overall.size();
			int matched = 0;
			for (boolean b : overall.values()) {
				if (b) {
					matched++;
				}
			}
			return new Union(List.copyOf(tracks), total, matched, ratio(matched, total), entries.size());
		}

		private static Double ratio(int m, int t) {
			return t == 0 ? null : Math.round(1000.0 * m / t) / 1000.0;
		}
	}

	/** 반음 차 → "0", "+12", "-24". */
	static String shiftKey(int d) {
		return d > 0 ? "+" + d : String.valueOf(d);
	}

	/** 형태(엄격). */
	static int[] matchMelody(List<RefN> refs, List<SNote> sc, boolean[] isMelody) {
		return matchMelody(refs, sc, isMelody, false);
	}

	/**
	 * 1:1 그리디 짝짓기. 반환: 참조 i → 악보 j(없으면 −1). 비교 음은 {@code SNote.cmp}, 참조 시작은 {@code RefN.tick}.
	 * 단계 ① 같은 음·차 ≤ 6, (tolerant면) ①' 음이름 같고 옥타브만 다름·차 ≤ 6, ② 다른 음·차 ≤ 6, ③ 같은 음·7 ≤ 차 ≤ 96.
	 * 단계마다 차가 작은 쌍부터(같으면 참조 순, 악보 순). {@code isMelody[j]}가 false인 악보 음은 ①·①'에만 참여한다.
	 * 후보는 시작 틱으로 정렬한 색인에서 ±96틱 창만 본다(결과는 전부 훑는 것과 같다).
	 */
	static int[] matchMelody(List<RefN> refs, List<SNote> sc, boolean[] isMelody, boolean tolerant) {
		int nr = refs.size();
		int ns = sc.size();
		int[] pair = new int[nr];
		Arrays.fill(pair, -1);
		boolean[] used = new boolean[ns];
		Integer[] order = new Integer[ns];
		for (int j = 0; j < ns; j++) {
			order[j] = j;
		}
		Arrays.sort(order, Comparator.comparingInt((Integer j) -> sc.get(j).tick()));
		int[] ticks = new int[ns];
		for (int k = 0; k < ns; k++) {
			ticks[k] = sc.get(order[k]).tick();
		}
		int[] phases = tolerant ? new int[] { 0, 3, 1, 2 } : new int[] { 0, 1, 2 };
		for (int phase : phases) {
			List<long[]> cands = new ArrayList<>();
			for (int i = 0; i < nr; i++) {
				if (pair[i] >= 0) {
					continue;
				}
				Note n = refs.get(i).n();
				long t0 = refs.get(i).tick();
				for (int k = lowerBound(ticks, t0 - SHIFT_MAX); k < ns && ticks[k] <= t0 + SHIFT_MAX; k++) {
					int j = order[k];
					if (used[j] || ((phase == 1 || phase == 2) && !isMelody[j])) {
						continue;
					}
					SNote s = sc.get(j);
					long ad = Math.abs(s.tick() - t0);
					int d = s.cmp() - n.pitch();
					boolean same = d == 0;
					boolean ok = switch (phase) {
					case 0 -> same && ad <= SAME_START;
					case 3 -> !same && Math.floorMod(d, 12) == 0 && ad <= SAME_START;
					case 1 -> !same && ad <= SAME_START;
					default -> same && ad > SAME_START;
					};
					if (ok) {
						cands.add(new long[] { ad, i, j });
					}
				}
			}
			cands.sort((a, b) -> {
				for (int k = 0; k < 3; k++) {
					int c = Long.compare(a[k], b[k]);
					if (c != 0) {
						return c;
					}
				}
				return 0;
			});
			for (long[] c : cands) {
				int i = (int) c[1];
				int j = (int) c[2];
				if (pair[i] < 0 && !used[j]) {
					pair[i] = j;
					used[j] = true;
				}
			}
		}
		return pair;
	}

	/** a[k] ≥ v인 가장 작은 k(없으면 a.length). a는 오름차순. */
	private static int lowerBound(int[] a, long v) {
		int lo = 0;
		int hi = a.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (a[mid] < v) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	// ======================================================================
	// (2) 화음
	// ======================================================================

	/** 화음 비교에 쓸 참조 트랙. null = 드럼 제외 전체. */
	private static List<Integer> chordSourceTracks(Mapping map, Set<Integer> selNums, ReferenceMidi ref,
			List<String> skipped) {
		if (map.isEmpty()) {
			return null;
		}
		TreeSet<Integer> union = new TreeSet<>();
		boolean anyEntry = false;
		for (Map.Entry<Integer, Mapping.Entry> e : map.tracks().entrySet()) {
			if (!selNums.contains(e.getKey())) {
				continue;
			}
			anyEntry = true;
			if (e.getValue().chordsFrom() == null) {
				return null; // 하나라도 "all"이면 전체
			}
			union.addAll(e.getValue().chordsFrom());
		}
		if (!anyEntry) {
			return null;
		}
		List<Integer> ok = validRefTracks(ref, union, "화음 구성음", skipped);
		if (ok.isEmpty()) {
			skipped.add("화음 구성음: 매핑의 chords_from이 비어 있어 참조 전체(드럼 제외)와 비교함");
			return null;
		}
		return ok;
	}

	/**
	 * 박마다 음이름 비교. sel은 비교 음(cmp)을 가진 트랙들. shift = 악보 틱 − 참조 틱. sounding이면 무음 음과
	 * 고정음·음높이 없음·효과음 악기 트랙을 뺀다.
	 */
	private static void chords(List<TrackData> sel, ReferenceMidi ref, List<Integer> srcTracks, int from, int to,
			TreeMap<Integer, Builder> measures, List<String> skipped, long shift, String mode) {
		List<BeatChord> beats = ref.chordsByBeat(from, to, srcTracks == null ? null : new HashSet<>(srcTracks));
		if (beats.isEmpty()) {
			return;
		}
		long lo = beats.get(0).tick96Start() + shift;
		long hi = beats.get(beats.size() - 1).tick96End() + shift;
		List<SNote> cand = new ArrayList<>();
		List<String> drumTracks = new ArrayList<>();
		List<String> fixedTracks = new ArrayList<>();
		for (TrackData td : sel) {
			if (td.drum()) {
				drumTracks.add(String.valueOf(td.number()));
				continue;
			}
			if (!td.pitchComparable(mode)) {
				fixedTracks.add(String.valueOf(td.number()));
				continue;
			}
			for (List<SNote> part : td.parts()) {
				for (SNote s : part) {
					if (!s.silent() && s.cmp() >= 0 && s.tick() < hi && s.end() > lo) {
						cand.add(s);
					}
				}
			}
		}
		if (!drumTracks.isEmpty()) {
			skipped.add("화음 구성음: 타악기 트랙(" + String.join(", ", drumTracks) + "번)은 악보 쪽에서 뺌");
		}
		if (!fixedTracks.isEmpty()) {
			skipped.add("화음 구성음: 고정음·음높이 없음·효과음 악기 트랙(" + String.join(", ", fixedTracks) + "번)은 악보 쪽에서 뺌");
		}
		for (BeatChord bc : beats) {
			long bs = bc.tick96Start() + shift;
			long be = bc.tick96End() + shift;
			TreeSet<Integer> scorePc = new TreeSet<>();
			for (SNote s : cand) {
				if (s.tick() < be && s.end() > bs) {
					scorePc.add(Math.floorMod(s.cmp(), 12));
				}
			}
			// 원본 길이 0인 음은 울리지 않으므로 뺀다.
			TreeSet<Integer> refPc = new TreeSet<>();
			int ignored = 0;
			for (Note n : bc.notes()) {
				if (n.srcLength() == 0) {
					ignored++;
					continue;
				}
				refPc.add(Math.floorMod(n.pitch(), 12));
			}
			List<String> missing = new ArrayList<>();
			List<String> extra = new ArrayList<>();
			for (int pc : refPc) {
				if (!scorePc.contains(pc)) {
					missing.add(MidiNames.pitchClassName(pc));
				}
			}
			for (int pc : scorePc) {
				if (!refPc.contains(pc)) {
					extra.add(MidiNames.pitchClassName(pc));
				}
			}
			builderFor(measures, bc.measure()).chords.add(new ChordBeat(bc.pos(), names(refPc), names(scorePc),
					List.copyOf(missing), List.copyOf(extra), ignored));
		}
	}

	private static List<String> names(Set<Integer> pcs) {
		List<String> l = new ArrayList<>();
		for (int pc : pcs) {
			l.add(MidiNames.pitchClassName(pc));
		}
		return List.copyOf(l);
	}

	// ======================================================================
	// (3) 파트 안 겹침
	// ======================================================================

	private static void overlaps(TrackData td, MeasureMap smap, long rs, long re, int off,
			TreeMap<Integer, Builder> measures) {
		for (Part part : Part.values()) {
			List<SNote> l = td.part(part);
			for (int i = 0; i + 1 < l.size(); i++) {
				SNote a = l.get(i);
				SNote b = l.get(i + 1);
				if (a.end() <= b.tick()) {
					continue;
				}
				boolean aIn = a.tick() >= rs && a.tick() < re;
				boolean bIn = b.tick() >= rs && b.tick() < re;
				if (!aIn && !bIn) {
					continue;
				}
				long at = aIn ? a.tick() : b.tick();
				builderForScore(measures, smap, at, off).overlaps.add(new Overlap(td.number(), part.key(),
						pos(smap, at), view(td, a, smap), view(td, b, smap)));
			}
		}
	}

	// ======================================================================
	// (4) 성부 교차(적은 음 기준)
	// ======================================================================

	private static void crossings(TrackData td, MeasureMap smap, long rs, long re, int off,
			TreeMap<Integer, Builder> measures) {
		crossPair(td, Part.MELODY, Part.CHORD1, smap, rs, re, off, measures);
		crossPair(td, Part.CHORD1, Part.CHORD2, smap, rs, re, off, measures);
	}

	/** upper 파트 음이 lower 파트 음보다 낮게(MIDI 번호 작게) 함께 울리는 음 쌍마다 하나. 위치는 함께 울리기 시작하는 곳. */
	private static void crossPair(TrackData td, Part upper, Part lower, MeasureMap smap, long rs, long re, int off,
			TreeMap<Integer, Builder> measures) {
		List<SNote> ul = clip(td.part(upper), rs, re);
		List<SNote> ll = clip(td.part(lower), rs, re);
		if (ul.isEmpty() || ll.isEmpty()) {
			return;
		}
		String type = upper.key() + "<" + lower.key();
		for (SNote a : ul) {
			for (SNote b : ll) {
				if (b.tick() >= a.end()) {
					break;
				}
				if (b.end() <= a.tick() || a.pitch() >= b.pitch()) {
					continue;
				}
				long s = Math.max(Math.max(a.tick(), b.tick()), rs);
				long e = Math.min(Math.min(a.end(), b.end()), re);
				if (s >= e) {
					continue;
				}
				String mel = (upper == Part.MELODY) ? a.name() : null;
				String c1 = (upper == Part.MELODY) ? b.name() : a.name();
				String c2 = (lower == Part.CHORD2) ? b.name() : null;
				builderForScore(measures, smap, s, off).crossings.add(new Crossing(td.number(), type, pos(smap, s), s,
						mel, c1, c2));
			}
		}
	}

	/** [rs, re)와 겹치는 음만. */
	private static List<SNote> clip(List<SNote> l, long rs, long re) {
		List<SNote> out = new ArrayList<>();
		for (SNote s : l) {
			if (s.tick() < re && s.end() > rs) {
				out.add(s);
			}
		}
		return out;
	}

	// ======================================================================
	// (5) 음역(적은 음 기준)
	// ======================================================================

	private static void ranges(TrackData td, InstrumentTable table, MeasureMap smap, long rs, long re, int off,
			TreeMap<Integer, Builder> measures, List<RangeCheck> checks) {
		InstrumentTable.Instrument main = td.inst();
		boolean mainIsSong = main != null && ("V".equals(main.type()) || "C".equals(main.type()));
		List<Part> mainParts = new ArrayList<>(List.of(Part.MELODY, Part.CHORD1, Part.CHORD2));
		if (mainIsSong) {
			mainParts.add(Part.SONG);
		}
		rangeFor(td, td.program(), main, mainParts, smap, rs, re, off, measures, checks,
				"악기표에 악기 " + td.program() + "번이 없음");

		if (!mainIsSong) {
			List<SNote> song = inRange(td.part(Part.SONG), rs, re);
			if (!song.isEmpty()) {
				if (td.songProgram() >= 0) {
					rangeFor(td, td.songProgram(), table.get(td.songProgram()), List.of(Part.SONG), smap, rs, re, off,
							measures, checks, "악기표에 노래 악기 " + td.songProgram() + "번이 없음");
				} else {
					checks.add(new RangeCheck(td.number(), List.of(Part.SONG.key()), td.songProgram(), null, null, null,
							null, false, 0, "노래 악기가 없어(코러스 없음/노래 제외) 노래 파트 음역은 검사하지 않음"));
				}
			}
		}
	}

	private static void rangeFor(TrackData td, int program, InstrumentTable.Instrument inst, List<Part> parts,
			MeasureMap smap, long rs, long re, int off, TreeMap<Integer, Builder> measures, List<RangeCheck> checks,
			String missingNote) {
		List<SNote> notes = new ArrayList<>();
		List<String> partKeys = new ArrayList<>();
		for (Part p : parts) {
			List<SNote> l = inRange(td.part(p), rs, re);
			if (!l.isEmpty()) {
				partKeys.add(p.key());
				notes.addAll(l);
			}
		}
		if (notes.isEmpty()) {
			return;
		}
		if (inst == null) {
			checks.add(new RangeCheck(td.number(), List.copyOf(partKeys), program, null, null, null, null, false, 0,
					missingNote + "(음역 검사 안 함)"));
			return;
		}
		InstrumentTable.Range r = inst.range();
		if (r == null || (r.low() == null && r.high() == null)) {
			checks.add(new RangeCheck(td.number(), List.copyOf(partKeys), program, inst.name(), null, null,
					inst.rangeSource(), false, 0, DRUM_TYPES.contains(inst.type()) ? NOTE_DRUM : NOTE_NO_RANGE));
			return;
		}
		int count = 0;
		for (SNote s : notes) {
			boolean low = r.low() != null && s.pitch() < r.low();
			boolean high = r.high() != null && s.pitch() > r.high();
			if (low || high) {
				count++;
				builderForScore(measures, smap, s.tick(), off).range.add(new RangeViolation(td.number(), s.part().key(),
						pos(smap, s.tick()), s.tick(), s.pitch(), s.name(), program, r.lowName(), r.highName(),
						inst.rangeSource()));
			}
		}
		checks.add(new RangeCheck(td.number(), List.copyOf(partKeys), program, inst.name(), r.lowName(), r.highName(),
				inst.rangeSource(), true, count, null));
	}

	/** 시작 틱이 [rs, re) 안인 음. */
	private static List<SNote> inRange(List<SNote> l, long rs, long re) {
		List<SNote> out = new ArrayList<>();
		for (SNote s : l) {
			if (s.tick() >= rs && s.tick() < re) {
				out.add(s);
			}
		}
		return out;
	}

	// ======================================================================
	// 들리는 음 경고
	// ======================================================================

	/** 무음 구간에 적힌 음(silent_note)과 구간 악기의 두 세트 섞임(timbre_mix). 마디별 무음 음 수도 센다. */
	private static void soundingWarnings(TrackData td, MeasureMap smap, long rs, long re, int off,
			TreeMap<Integer, Builder> measures, List<SoundingWarning> out) {
		List<String> silentPos = new ArrayList<>();
		int silent = 0;
		Map<String, Integer> timbres = new LinkedHashMap<>();
		List<String> altPos = new ArrayList<>();
		List<String> defPos = new ArrayList<>();
		String inst = td.inst() == null ? null : td.inst().name();
		for (Part part : Part.values()) {
			int prog = td.programFor(part);
			boolean regions = SoundingPitch.rule(prog).kind() == SoundingPitch.Kind.REGIONS;
			for (SNote s : inRange(td.part(part), rs, re)) {
				String label = pos(smap, s.tick()) + " " + s.name();
				if (SoundingPitch.soundingOf(prog, s.pitch()) == SoundingPitch.SILENT) {
					silent++;
					builderForScore(measures, smap, s.tick(), off).silent++;
					if (silentPos.size() < 10) {
						silentPos.add(label);
					}
					continue;
				}
				if (regions && prog == td.program()) {
					String t = SoundingPitch.timbreOf(prog, s.pitch());
					timbres.merge(t, 1, Integer::sum);
					List<String> l = SoundingPitch.TIMBRE_ALT.equals(t) ? altPos : defPos;
					if (l.size() < 10) {
						l.add(label);
					}
				}
			}
		}
		if (silent > 0) {
			out.add(new SoundingWarning("silent_note", td.number(), td.program(), inst, silent, List.copyOf(silentPos), null,
					"트랙 " + td.number() + "의 " + silent + "음이 무음 구간에 적혀 소리가 나지 않음. "
							+ SoundingPitch.detail(SoundingPitch.rule(td.program()))));
		}
		if (timbres.size() > 1) {
			int def = timbres.getOrDefault(SoundingPitch.TIMBRE_DEFAULT, 0);
			int alt = timbres.getOrDefault(SoundingPitch.TIMBRE_ALT, 0);
			List<String> few = (alt <= def) ? altPos : defPos;
			out.add(new SoundingWarning("timbre_mix", td.number(), td.program(), inst, Math.min(def, alt), List.copyOf(few),
					Collections.unmodifiableMap(timbres), "트랙 " + td.number() + "에서 두 음색 세트를 함께 씀(default " + def
							+ "음, alt " + alt + "음). 의도한 것이 아니면 한 세트로 맞출 것(정보). "
							+ SoundingPitch.detail(SoundingPitch.rule(td.program()))));
		}
	}
}
