package mabibridge.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.TimeSignature;
import jp.fourthline.mmlTools.core.ResourceLoader;
import mabibridge.engine.EngineException;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Part;
import mabibridge.engine.SoundingPitch;
import mabibridge.midi.MeasureMap;

/**
 * 편곡 프로필({@code score_profile}). 원곡 MIDI 없이 악보만으로 "이 편곡은 어떤 모양인가"를 수치로 뽑는다:
 * 악기 조합과 역할 추정, 트랙·파트별 음 수·음역(적은 음과 들리는 음)·세기·음 길이·밀도·쉬는 마디, 동시 발음 수, 화음 보이싱,
 * 저음역 겹침, 글자 수 사용률, 템포·박자, 구간 악기의 세트 사용, 무음 구간 음, 옥타브 보정(적은 음 대 들리는 음) 원재료.
 *
 * <p>악보는 읽기만 한다. 범위 규칙은 {@code start_in_range}(시작 틱이 [from 마디 시작, to 마디 끝) 안인 음만 센다).
 * 쉬는 마디·동시 발음·저음 겹침·보이싱처럼 "그 순간 울리는 음"을 보는 수치는 범위 앞에서 시작해 걸친 음도 울리는 것으로 본다.
 * 글자 수는 곡 전체 기준이다(범위와 무관).</p>
 *
 * <p>음높이는 모두 MIDI 번호(60 = C4, MML {@code o4c}). 들리는 음은 {@link SoundingPitch} 표로 바꾼다. 노래 파트는
 * 트랙의 노래 악기 기준.</p>
 */
public final class ScoreProfile {

	public static final String SCHEMA = "mabibridge.profile/1";
	/** 글자 수 판정 기준 이름(score_stats의 song_rule과 같음). */
	static final String RULE_SONG_INCLUDED = "song_included";
	static final String RULE_SONG_EXCLUDED = "song_excluded";
	/** 저음역 기준: 들리는 음이 이 값보다 낮으면 저음(48 = C3). */
	public static final int LOW_THRESHOLD = 48;
	/** 짧은 음 기준(틱, 12 = 32분음표). */
	public static final int SHORT_NOTE_TICKS = 12;
	/** 화음 역할 기준: 소리 나는 박의 동시 발음 수 평균이 이 값 이상. */
	public static final double CHORD_POLYPHONY = 2.0;
	/** 베이스 역할 기준: 들리는 음 중앙값이 이 값 이하인 트랙 중 가장 낮은 것. */
	public static final int BASS_MAX_MEDIAN = 48;

	private static final Part[] MAIN = { Part.MELODY, Part.CHORD1, Part.CHORD2 };

	/** 결과: 전체 JSON(Map), 짧은 요약(Map), 한국어 요약문. */
	public record Result(Map<String, Object> profile, Map<String, Object> summary, String summaryText) {}

	// ======================================================================
	// 내부 자료
	// ======================================================================

	/** 음 하나. sound: 들리는 MIDI 번호, 또는 SILENT/UNPITCHED. */
	private record Note(int start, int end, int written, int sound, int vel, String timbre) {
		boolean pitched() {
			return sound >= 0;
		}
	}

	private static final class PartData {
		Part part;
		int program;
		SoundingPitch.Kind kind;
		List<Note> all = new ArrayList<>();
		List<Note> in = new ArrayList<>();
		int wholeCount;

		/** t에 울리는 음(없으면 null). 한 파트는 한 번에 한 음. */
		Note at(long t) {
			int lo = 0;
			int hi = all.size() - 1;
			int idx = -1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (all.get(mid).start() <= t) {
					idx = mid;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			for (int i = idx; i >= 0 && i >= idx - 2; i--) {
				Note n = all.get(i);
				if (n.start() <= t && n.end() > t) {
					return n;
				}
			}
			return null;
		}

		/** 시작 틱이 a보다 큰 첫 음의 번호(없으면 크기). */
		int firstStartAfter(long a) {
			int lo = 0;
			int hi = all.size();
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (all.get(mid).start() <= a) {
					lo = mid + 1;
				} else {
					hi = mid;
				}
			}
			return lo;
		}

		/** [a, b)와 겹치는 음이 있는가. pred가 null이 아니면 그 조건을 만족하는 음만. */
		boolean overlaps(long a, long b, java.util.function.Predicate<Note> pred) {
			int lo = 0;
			int hi = all.size() - 1;
			int idx = 0;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (all.get(mid).start() < a) {
					idx = mid;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			for (int i = Math.max(0, idx - 2); i < all.size(); i++) {
				Note n = all.get(i);
				if (n.start() >= b) {
					break;
				}
				if (n.end() > a && (pred == null || pred.test(n))) {
					return true;
				}
			}
			return false;
		}
	}

	private static final class TrackData {
		int number;
		MMLTrack t;
		InstrumentTable.Instrument inst;
		String type;
		SoundingPitch.Kind kind;
		PartData[] parts = new PartData[4];
		int notesIn;
		Integer soundMedian;
		Integer writtenMedian;
		double polyMeanActive;
		int polyMax;
		int[] beatPoly;
		double densityMean;
		String role;
		List<String> tags = new ArrayList<>();
		String roleReason;
		Map<String, Object> json;
	}

	private ScoreProfile() {}

	// ======================================================================
	// 진입점
	// ======================================================================

	/**
	 * 프로필을 만든다.
	 *
	 * @param src   읽은 악보
	 * @param table 악기표(이름·타입)
	 * @param from  시작 마디(null = 1)
	 * @param to    끝 마디(포함, null = 음표가 있는 마지막 마디)
	 * @throws EngineException BAD_ARGUMENT(범위)
	 */
	public static Result build(ScoreLoader.Loaded src, InstrumentTable table, Integer from, Integer to) {
		MMLScore score = src.score();
		MeasureMap map = measureMap(score);
		int totalTicks = score.getTotalTickLength();
		int lastMeasure = totalTicks > 0 ? map.measureOf(totalTicks - 1L) : 0;
		List<String> warnings = new ArrayList<>(src.warnings());

		int f = from == null ? 1 : from;
		int l = to == null ? Math.max(1, lastMeasure) : to;
		if (f < 1 || l < 1) {
			throw new EngineException(EngineException.BAD_ARGUMENT, "마디 번호는 1부터",
					ProfileIo.details("from_measure", from, "to_measure", to));
		}
		if (l < f) {
			throw new EngineException(EngineException.BAD_ARGUMENT, "to_measure가 from_measure보다 앞",
					ProfileIo.details("from_measure", from, "to_measure", to));
		}
		if (lastMeasure > 0 && f > lastMeasure) {
			throw new EngineException(EngineException.BAD_ARGUMENT,
					"from_measure가 악보의 마지막 마디(" + lastMeasure + ")보다 뒤",
					ProfileIo.details("from_measure", from, "last_measure", lastMeasure));
		}
		if (lastMeasure > 0 && l > lastMeasure) {
			warnings.add("to_measure " + l + "가 음표가 있는 마지막 마디(" + lastMeasure + ")보다 뒤");
		}
		long rs = map.measureStart(f);
		long re = map.measureEnd(l);
		boolean whole = (from == null || from == 1) && (to == null || to >= lastMeasure);
		int nMeasures = l - f + 1;

		// 박 목록
		List<long[]> beats = new ArrayList<>();
		for (int m = f; m <= l; m++) {
			long ms = map.measureStart(m);
			long me = map.measureEnd(m);
			long bl = map.beatLength(m);
			int bi = 0;
			for (long b = ms; b < me; b += bl, bi++) {
				beats.add(new long[] { b, Math.min(me, b + bl), m, bi + 1 });
			}
		}

		int[] top = rankTop();
		int[] limits = { top[0], top[1], top[2], top[0] };
		int d = top[0] / 3;
		int[] limitsEx = { top[0] + d, top[1] + d, top[2] + d, top[0] };

		// 트랙 자료
		List<TrackData> tds = new ArrayList<>();
		for (int i = 0; i < score.getTrackCount(); i++) {
			tds.add(trackData(i + 1, score.getTrack(i), table, rs, re));
		}

		// 트랙별 수치
		for (TrackData td : tds) {
			int[] srcChars = (src.sourceChars() != null && td.number - 1 < src.sourceChars().size())
					? src.sourceChars().get(td.number - 1)
					: null;
			td.json = trackJson(td, map, f, l, beats, nMeasures, limits, limitsEx, srcChars, table);
		}

		assignRoles(tds);
		for (TrackData td : tds) {
			Map<String, Object> rj = new LinkedHashMap<>();
			rj.put("role", td.role);
			rj.put("tags", td.tags);
			rj.put("reason", td.roleReason);
			rj.put("evidence", evidence(td));
			td.json.put("role", rj);
		}

		Map<String, Object> ensemble = ensemble(tds, beats, map, table);

		Map<String, Object> fileJson = fileJson(src, score, map, lastMeasure, totalTicks);
		Map<String, Object> range = new LinkedHashMap<>();
		range.put("from_measure", f);
		range.put("to_measure", l);
		range.put("from_tick", rs);
		range.put("to_tick", re);
		range.put("measures", nMeasures);
		range.put("beats", beats.size());
		range.put("whole_song", whole);
		range.put("note_rule", "start_in_range");

		Map<String, Object> lim = new LinkedHashMap<>();
		lim.put("rank", "RANK_1");
		lim.put("melody", limits[0]);
		lim.put("chord1", limits[1]);
		lim.put("chord2", limits[2]);
		lim.put("song", limits[3]);
		Map<String, Object> limEx = new LinkedHashMap<>();
		limEx.put("melody", limitsEx[0]);
		limEx.put("chord1", limitsEx[1]);
		limEx.put("chord2", limitsEx[2]);
		lim.put("song_excluded", limEx);
		lim.put("scope", "whole_song");

		Map<String, Object> th = new LinkedHashMap<>();
		th.put("low_register_below", LOW_THRESHOLD);
		th.put("low_register_name", NoteSpec.pitchName(LOW_THRESHOLD));
		th.put("short_note_ticks", SHORT_NOTE_TICKS);
		th.put("chord_polyphony", CHORD_POLYPHONY);
		th.put("bass_max_median", BASS_MAX_MEDIAN);

		Map<String, Object> p = new LinkedHashMap<>();
		p.put("schema", SCHEMA);
		p.put("file", fileJson);
		p.put("range", range);
		p.put("char_limits", lim);
		p.put("thresholds", th);
		List<Map<String, Object>> tl = new ArrayList<>();
		for (TrackData td : tds) {
			tl.add(td.json);
		}
		p.put("tracks", tl);
		p.put("ensemble", ensemble);
		p.put("sounding_source", SoundingPitch.SOURCE);
		p.put("warnings", warnings);

		Map<String, Object> summary = summary(fileJson, range, tds, ensemble, warnings);
		String text = summaryText(fileJson, range, tds, ensemble);
		return new Result(p, summary, text);
	}

	// ======================================================================
	// 악보 → 자료
	// ======================================================================

	static MeasureMap measureMap(MMLScore score) {
		List<MeasureMap.Signature> sigs = new ArrayList<>();
		String[] b = score.getBaseTime().split("/");
		sigs.add(new MeasureMap.Signature(0, Integer.parseInt(b[0].trim()), Integer.parseInt(b[1].trim())));
		for (TimeSignature ts : score.getTimeSignatureList()) {
			int den = (4 * MeasureMap.TPQN96) / ts.getBaseTick();
			sigs.add(new MeasureMap.Signature(ts.getTickOffset(), ts.getNumTime(), den));
		}
		return MeasureMap.of(MeasureMap.TPQN96, sigs);
	}

	private static TrackData trackData(int number, MMLTrack t, InstrumentTable table, long rs, long re) {
		TrackData td = new TrackData();
		td.number = number;
		td.t = t;
		td.inst = table.get(t.getProgram());
		td.type = td.inst == null ? null : td.inst.type();
		td.kind = SoundingPitch.rule(t.getProgram()).kind();
		for (Part part : Part.values()) {
			PartData pd = new PartData();
			pd.part = part;
			pd.program = SoundingPitch.programFor(t.getProgram(), t.getSongProgram(), part, td.type);
			pd.kind = SoundingPitch.rule(pd.program).kind();
			List<MMLNoteEvent> evs = t.getMMLEventAtIndex(part.index()).getMMLNoteEventList();
			pd.wholeCount = evs.size();
			for (MMLNoteEvent e : evs) {
				int w = e.getNote() + 12;
				int s = SoundingPitch.soundingOf(pd.program, w);
				Note n = new Note(e.getTickOffset(), e.getTickOffset() + e.getTick(), w, s, e.getVelocity(),
						SoundingPitch.timbreOf(pd.program, w));
				pd.all.add(n);
				if (n.start() >= rs && n.start() < re) {
					pd.in.add(n);
				}
			}
			pd.all.sort((x, y) -> Integer.compare(x.start(), y.start()));
			td.parts[part.index()] = pd;
			td.notesIn += pd.in.size();
		}
		return td;
	}

	// ======================================================================
	// 트랙
	// ======================================================================

	private static Map<String, Object> trackJson(TrackData td, MeasureMap map, int f, int l, List<long[]> beats,
			int nMeasures, int[] limits, int[] limitsEx, int[] srcChars, InstrumentTable table) {
		MMLTrack t = td.t;
		Map<String, Object> j = new LinkedHashMap<>();
		j.put("track", td.number);
		j.put("name", t.getTrackName());
		j.put("program", t.getProgram());
		j.put("instrument", td.inst == null ? null : td.inst.name());
		j.put("instrument_type", td.type);
		j.put("sounding", SoundingPitch.summary(t.getProgram()));
		j.put("sounding_kind", td.kind.key());
		int sp = t.getSongProgram();
		j.put("song_program", sp);
		InstrumentTable.Instrument si = sp >= 0 ? table.get(sp) : null;
		j.put("song_program_name", sp == MMLTrack.NO_CHORUS ? "없음"
				: sp == MMLTrack.EXCLUDE_SONG ? "노래 제외" : (si == null ? null : si.name()));
		boolean mainIsSong = "V".equals(td.type) || "C".equals(td.type);
		j.put("song_sounding", (!mainIsSong && sp >= 0) ? SoundingPitch.summary(sp) : null);
		j.put("panpot", t.getPanpot());
		j.put("volume", t.getVolume());
		j.put("notes", td.notesIn);

		// 글자 수(곡 전체)
		t.mmlRank();
		String[] act = splitMml(t.getMabiMML());
		String[] raw = t.getMabiMMLArray();
		int[] chars = { act[0].length(), act[1].length(), act[2].length(), raw[3] == null ? 0 : raw[3].length() };

		Map<String, Object> parts = new LinkedHashMap<>();
		List<Integer> allW = new ArrayList<>();
		List<Integer> allS = new ArrayList<>();
		int silent = 0;
		for (Part part : Part.values()) {
			PartData pd = td.parts[part.index()];
			int i = part.index();
			parts.put(part.key(), partJson(pd, map, f, l, nMeasures, chars[i], limits[i], limitsEx[i],
					srcChars == null ? null : srcChars[i]));
			for (Note n : pd.in) {
				allW.add(n.written());
				if (n.pitched()) {
					allS.add(n.sound());
				} else if (n.sound() == SoundingPitch.SILENT) {
					silent++;
				}
			}
		}
		j.put("parts", parts);

		Map<String, Object> w = pitchStats(allW);
		Map<String, Object> s = pitchStats(allS);
		s.put("silent", silent);
		j.put("written_range", w);
		j.put("sounding_range", s);
		List<Note> allIn = new ArrayList<>();
		for (PartData pd : td.parts) {
			allIn.addAll(pd.in);
		}
		j.put("velocity", velocity(allIn));
		td.writtenMedian = (Integer) w.get("median");
		td.soundMedian = (Integer) s.get("median");

		// 마디 밀도(모든 파트 합)
		int[] perMeasure = new int[nMeasures];
		for (PartData pd : td.parts) {
			for (Note n : pd.in) {
				int m = map.measureOf(n.start()) - f;
				if (m >= 0 && m < nMeasures) {
					perMeasure[m]++;
				}
			}
		}
		Map<String, Object> dens = density(perMeasure, f);
		j.put("density", dens);
		td.densityMean = (Double) dens.get("mean");
		int rest = 0;
		for (int m = f; m <= l; m++) {
			long a = map.measureStart(m);
			long b = map.measureEnd(m);
			boolean any = false;
			for (PartData pd : td.parts) {
				if (pd.overlaps(a, b, null)) {
					any = true;
					break;
				}
			}
			if (!any) {
				rest++;
			}
		}
		j.put("rest_measure_ratio", r3((double) rest / nMeasures));

		// 동시 발음 수(박 단위)
		int activeBeats = 0;
		long sumActive = 0;
		int max = 0;
		String maxAt = null;
		td.beatPoly = new int[beats.size()];
		for (int bi = 0; bi < beats.size(); bi++) {
			long[] bt = beats.get(bi);
			int c = maxSimultaneous(td.parts, bt[0], bt[1]);
			td.beatPoly[bi] = c;
			if (c > 0) {
				activeBeats++;
				sumActive += c;
			}
			if (c > max) {
				max = c;
				maxAt = bt[2] + ":" + bt[3];
			}
		}
		Map<String, Object> poly = new LinkedHashMap<>();
		td.polyMeanActive = activeBeats == 0 ? 0 : (double) sumActive / activeBeats;
		td.polyMax = max;
		poly.put("mean_active", r3(td.polyMeanActive));
		poly.put("mean", r3(beats.isEmpty() ? 0 : (double) sumActive / beats.size()));
		poly.put("max", max);
		poly.put("max_at", maxAt);
		poly.put("beats_active", activeBeats);
		poly.put("beats", beats.size());
		j.put("polyphony", poly);

		// 보이싱(들리는 음 간격)
		Map<String, Object> voicing = new LinkedHashMap<>();
		voicing.put("melody_chord1", voicing(td.parts[0], td.parts[1]));
		voicing.put("chord1_chord2", voicing(td.parts[1], td.parts[2]));
		voicing.put("melody_chord2", voicing(td.parts[0], td.parts[2]));
		j.put("voicing", voicing);

		Map<String, Object> mw = new LinkedHashMap<>();
		Map<String, Object> mpw = pitchStats(writtenOf(td.parts[0].in));
		Map<String, Object> mps = pitchStats(soundOf(td.parts[0].in));
		mw.put("written", width(mpw));
		mw.put("sounding", width(mps));
		j.put("melody_range_width", mw);

		// 저음 사용 박
		int lowBeats = 0;
		for (long[] bt : beats) {
			for (PartData pd : td.parts) {
				if (pd.overlaps(bt[0], bt[1], n -> n.pitched() && n.sound() < LOW_THRESHOLD)) {
					lowBeats++;
					break;
				}
			}
		}
		Map<String, Object> low = new LinkedHashMap<>();
		low.put("beats", lowBeats);
		low.put("ratio", r3(beats.isEmpty() ? 0 : (double) lowBeats / beats.size()));
		j.put("low_register", low);

		// 글자 수 판정 기준. score_stats 기본과 같게, 노래 파트에 음이 있으면 노래 포함, 아니면 노래 제외
		// (노래 제외 한도 = 각 파트 + 멜로디/3). chars_ratio(노래 포함 한도 대비)는 그대로 두고 chars_ratio_default를 더한다.
		String rule = td.parts[Part.SONG.index()].wholeCount > 0 ? RULE_SONG_INCLUDED : RULE_SONG_EXCLUDED;
		j.put("chars_rule", rule);
		@SuppressWarnings("unchecked")
		Map<String, Object> jp = (Map<String, Object>) j.get("parts");
		for (Part part : Part.values()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> pj = (Map<String, Object>) jp.get(part.key());
			Object ex = pj.get("chars_ratio_song_excluded");
			pj.put("chars_ratio_default", RULE_SONG_EXCLUDED.equals(rule) && ex != null ? ex : pj.get("chars_ratio"));
		}

		// 구간 악기 세트(주 악기 파트)
		if (td.kind == SoundingPitch.Kind.REGIONS) {
			j.put("timbre_sets", timbreSets(td.parts, true));
		} else {
			j.put("timbre_sets", null);
		}
		return j;
	}

	private static Map<String, Object> partJson(PartData pd, MeasureMap map, int f, int l, int nMeasures, int chars,
			int limit, int limitEx, Integer srcChars) {
		Map<String, Object> j = new LinkedHashMap<>();
		j.put("notes", pd.in.size());
		j.put("notes_whole_song", pd.wholeCount);
		j.put("program", pd.program);
		j.put("chars", chars);
		j.put("chars_limit", limit);
		j.put("chars_ratio", r3((double) chars / limit));
		if (pd.part != Part.SONG) {
			j.put("chars_limit_song_excluded", limitEx);
			j.put("chars_ratio_song_excluded", r3((double) chars / limitEx));
		}
		j.put("chars_source", srcChars);
		if (pd.in.isEmpty()) {
			j.put("written", null);
			j.put("sounding", null);
			j.put("velocity", null);
			j.put("length", null);
			j.put("density", null);
			j.put("rest_measure_ratio", restRatio(pd, map, f, l, nMeasures));
			return j;
		}
		j.put("written", pitchStats(writtenOf(pd.in)));
		Map<String, Object> s = pitchStats(soundOf(pd.in));
		int silent = 0;
		int unp = 0;
		for (Note n : pd.in) {
			if (n.sound() == SoundingPitch.SILENT) {
				silent++;
			} else if (n.sound() == SoundingPitch.UNPITCHED) {
				unp++;
			}
		}
		s.put("silent", silent);
		s.put("unpitched", unp);
		s.put("timbre_sets", pd.kind == SoundingPitch.Kind.REGIONS ? timbreSets(new PartData[] { pd }, false) : null);
		j.put("sounding", s);

		j.put("velocity", velocity(pd.in));

		// 길이
		double lsum = 0;
		int shortN = 0;
		for (Note n : pd.in) {
			int len = n.end() - n.start();
			lsum += len;
			if (len <= SHORT_NOTE_TICKS) {
				shortN++;
			}
		}
		Map<String, Object> len = new LinkedHashMap<>();
		len.put("mean_ticks", r3(lsum / pd.in.size()));
		len.put("short_ratio", r3((double) shortN / pd.in.size()));
		j.put("length", len);

		int[] perMeasure = new int[nMeasures];
		for (Note n : pd.in) {
			int m = map.measureOf(n.start()) - f;
			if (m >= 0 && m < nMeasures) {
				perMeasure[m]++;
			}
		}
		j.put("density", density(perMeasure, f));
		j.put("rest_measure_ratio", restRatio(pd, map, f, l, nMeasures));
		return j;
	}

	/** 세기 v(0~15)의 평균·표준편차(모집단)·히스토그램. 음이 없으면 null. */
	private static Map<String, Object> velocity(List<Note> notes) {
		if (notes.isEmpty()) {
			return null;
		}
		double sum = 0;
		int[] hist = new int[16];
		for (Note n : notes) {
			sum += n.vel();
			hist[Math.max(0, Math.min(15, n.vel()))]++;
		}
		double mean = sum / notes.size();
		double var = 0;
		for (Note n : notes) {
			var += (n.vel() - mean) * (n.vel() - mean);
		}
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("mean", r3(mean));
		v.put("sd", r3(Math.sqrt(var / notes.size())));
		List<Integer> h = new ArrayList<>();
		for (int x : hist) {
			h.add(x);
		}
		v.put("histogram", h);
		return v;
	}

	private static double restRatio(PartData pd, MeasureMap map, int f, int l, int nMeasures) {
		int rest = 0;
		for (int m = f; m <= l; m++) {
			if (!pd.overlaps(map.measureStart(m), map.measureEnd(m), null)) {
				rest++;
			}
		}
		return r3((double) rest / nMeasures);
	}

	private static Map<String, Object> density(int[] perMeasure, int f) {
		int sum = 0;
		int active = 0;
		int max = 0;
		int maxM = 0;
		for (int i = 0; i < perMeasure.length; i++) {
			sum += perMeasure[i];
			if (perMeasure[i] > 0) {
				active++;
			}
			if (perMeasure[i] > max) {
				max = perMeasure[i];
				maxM = f + i;
			}
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("mean", r3(perMeasure.length == 0 ? 0 : (double) sum / perMeasure.length));
		d.put("mean_active", r3(active == 0 ? 0 : (double) sum / active));
		d.put("max", max);
		d.put("max_measure", max == 0 ? null : maxM);
		return d;
	}

	/** [a, b) 안의 동시 발음 수 최대(박 시작과 박 안의 음 시작 시점마다 센다). */
	private static int maxSimultaneous(PartData[] parts, long a, long b) {
		List<Long> pts = new ArrayList<>();
		pts.add(a);
		for (PartData pd : parts) {
			for (int i = pd.firstStartAfter(a); i < pd.all.size(); i++) {
				Note n = pd.all.get(i);
				if (n.start() >= b) {
					break;
				}
				pts.add((long) n.start());
			}
		}
		int max = 0;
		for (long t : pts) {
			int c = 0;
			for (PartData pd : parts) {
				if (pd.at(t) != null) {
					c++;
				}
			}
			max = Math.max(max, c);
		}
		return max;
	}

	/**
	 * 두 파트의 들리는 음 간격(upper − lower, 반음). 범위 안에서 시작하는 두 파트의 음 시작 시점마다, 두 파트 모두 음높이 있는 음이
	 * 울리면 한 번 센다. 음수는 성부 교차(아래 파트가 위로 올라감).
	 */
	private static Map<String, Object> voicing(PartData upper, PartData lower) {
		java.util.TreeSet<Integer> pts = new java.util.TreeSet<>();
		for (Note n : upper.in) {
			pts.add(n.start());
		}
		for (Note n : lower.in) {
			pts.add(n.start());
		}
		List<Integer> iv = new ArrayList<>();
		for (int t : pts) {
			Note a = upper.at(t);
			Note b = lower.at(t);
			if (a != null && b != null && a.pitched() && b.pitched()) {
				iv.add(a.sound() - b.sound());
			}
		}
		Map<String, Object> j = new LinkedHashMap<>();
		j.put("samples", iv.size());
		if (iv.isEmpty()) {
			j.put("median", null);
			j.put("mean_abs", null);
			j.put("crossings", 0);
			j.put("unisons", 0);
			j.put("distribution", Map.of());
			return j;
		}
		List<Integer> sorted = new ArrayList<>(iv);
		Collections.sort(sorted);
		int cross = 0;
		int uni = 0;
		double abs = 0;
		TreeMap<Integer, Integer> dist = new TreeMap<>();
		for (int x : iv) {
			if (x < 0) {
				cross++;
			} else if (x == 0) {
				uni++;
			}
			abs += Math.abs(x);
			dist.merge(x, 1, Integer::sum);
		}
		j.put("median", sorted.get((sorted.size() - 1) / 2));
		j.put("mean_abs", r3(abs / iv.size()));
		j.put("crossings", cross);
		j.put("unisons", uni);
		Map<String, Integer> ds = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : dist.entrySet()) {
			ds.put(String.valueOf(e.getKey()), e.getValue());
		}
		j.put("distribution", ds);
		return j;
	}

	private static Map<String, Integer> timbreSets(PartData[] parts, boolean mainOnly) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (PartData pd : parts) {
			if (mainOnly && pd.part == Part.SONG) {
				continue;
			}
			if (pd.kind != SoundingPitch.Kind.REGIONS) {
				continue;
			}
			for (Note n : pd.in) {
				m.merge(n.timbre(), 1, Integer::sum);
			}
		}
		return m;
	}

	// ======================================================================
	// 역할
	// ======================================================================

	private static void assignRoles(List<TrackData> tds) {
		List<TrackData> pitched = new ArrayList<>();
		for (TrackData td : tds) {
			if (td.notesIn == 0) {
				td.role = "empty";
				td.roleReason = "범위 안에 음이 없음";
			} else if (td.kind == SoundingPitch.Kind.UNPITCHED || "D".equals(td.type) || "P".equals(td.type)) {
				td.role = "drums";
				td.roleReason = "타입 " + td.type + "(" + (td.kind == SoundingPitch.Kind.FIXED ? "고정음 타악기" : "타악기") + ")";
			} else if (td.kind == SoundingPitch.Kind.SFX) {
				td.role = "sfx";
				td.roleReason = "효과음 악기(새소리)";
			} else if ("V".equals(td.type) || "C".equals(td.type)) {
				td.role = "song";
				td.roleReason = "노래 악기(타입 " + td.type + ")";
			} else if (td.soundMedian == null) {
				td.role = "melody";
				td.roleReason = "들리는 음높이를 알 수 없음(" + td.kind.key() + ")";
			} else {
				pitched.add(td);
			}
			if (td.notesIn > 0 && td.parts[Part.SONG.index()].in.size() > 0 && !"song".equals(td.role)) {
				td.tags.add("song_part");
			}
		}
		TrackData bass = null;
		for (TrackData td : pitched) {
			if (bass == null || td.soundMedian < bass.soundMedian) {
				bass = td;
			}
		}
		if (bass != null && bass.soundMedian > BASS_MAX_MEDIAN) {
			bass = null;
		}
		List<TrackData> melodies = new ArrayList<>();
		for (TrackData td : pitched) {
			if (td == bass) {
				td.role = "bass";
				td.roleReason = "들리는 음 중앙값 " + name(td.soundMedian) + "(" + td.soundMedian + ")이 가장 낮고 "
						+ name(BASS_MAX_MEDIAN) + "(" + BASS_MAX_MEDIAN + ") 이하";
			} else if (td.polyMeanActive >= CHORD_POLYPHONY) {
				td.role = "chord";
				td.roleReason = "소리 나는 박의 동시 발음 평균 " + fmt(td.polyMeanActive) + " ≥ " + fmt(CHORD_POLYPHONY);
			} else {
				td.role = "melody";
				td.roleReason = "소리 나는 박의 동시 발음 평균 " + fmt(td.polyMeanActive) + " < " + fmt(CHORD_POLYPHONY);
				melodies.add(td);
			}
			if (td != bass && td.soundMedian <= BASS_MAX_MEDIAN) {
				td.tags.add("low");
			}
		}
		// 주선율(lead): melody 역할 중 음 밀도가 최대의 절반 이상인 트랙에서 들리는 음 중앙값이 가장 높은 것
		double maxD = 0;
		for (TrackData td : melodies) {
			maxD = Math.max(maxD, td.densityMean);
		}
		TrackData lead = null;
		for (TrackData td : melodies) {
			if (td.densityMean >= maxD * 0.5 && (lead == null || td.soundMedian > lead.soundMedian)) {
				lead = td;
			}
		}
		if (lead != null) {
			lead.tags.add("lead");
			lead.roleReason += "; 주선율 추정: 밀도 " + fmt(lead.densityMean) + "(최대 " + fmt(maxD) + "의 절반 이상) 중 들리는 음 중앙값 "
					+ name(lead.soundMedian) + "이 가장 높음";
		}
	}

	private static Map<String, Object> evidence(TrackData td) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("instrument_type", td.type);
		e.put("sounding_kind", td.kind.key());
		e.put("sounding_median", td.soundMedian);
		e.put("sounding_median_name", td.soundMedian == null ? null : name(td.soundMedian));
		e.put("polyphony_mean_active", r3(td.polyMeanActive));
		e.put("density_mean", r3(td.densityMean));
		e.put("notes", td.notesIn);
		return e;
	}

	// ======================================================================
	// 합주
	// ======================================================================

	private static Map<String, Object> ensemble(List<TrackData> tds, List<long[]> beats, MeasureMap map,
			InstrumentTable table) {
		Map<String, Object> j = new LinkedHashMap<>();
		j.put("track_count", tds.size());
		int withNotes = 0;
		List<Integer> programs = new ArrayList<>();
		List<String> names = new ArrayList<>();
		Map<String, Integer> pc = new TreeMap<>((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
		for (TrackData td : tds) {
			if (td.notesIn > 0) {
				withNotes++;
			}
			programs.add(td.t.getProgram());
			names.add(td.inst == null ? "?" + td.t.getProgram() : td.inst.name());
			pc.merge(String.valueOf(td.t.getProgram()), 1, Integer::sum);
		}
		j.put("tracks_with_notes", withNotes);
		j.put("programs", programs);
		j.put("instruments", names);
		j.put("program_counts", pc);

		Map<String, List<Integer>> roles = new LinkedHashMap<>();
		for (String r : new String[] { "melody", "chord", "bass", "drums", "song", "sfx", "empty" }) {
			roles.put(r, new ArrayList<>());
		}
		List<Integer> lead = new ArrayList<>();
		for (TrackData td : tds) {
			roles.computeIfAbsent(td.role, k -> new ArrayList<>()).add(td.number);
			if (td.tags.contains("lead")) {
				lead.add(td.number);
			}
		}
		j.put("roles", roles);
		j.put("lead", lead);
		j.put("role_text", roleText(roles, lead));

		// 저음역 겹침: 박마다 들리는 음 < 48인 음이 울리는 (트랙, 파트) 수
		TreeMap<Integer, Integer> hist = new TreeMap<>();
		int max = 0;
		String maxAt = null;
		int active = 0;
		long sum = 0;
		int ge2 = 0;
		int ge3 = 0;
		for (long[] bt : beats) {
			int c = 0;
			for (TrackData td : tds) {
				for (PartData pd : td.parts) {
					if (pd.overlaps(bt[0], bt[1], n -> n.pitched() && n.sound() < LOW_THRESHOLD)) {
						c++;
					}
				}
			}
			hist.merge(c, 1, Integer::sum);
			if (c > 0) {
				active++;
				sum += c;
			}
			if (c >= 2) {
				ge2++;
			}
			if (c >= 3) {
				ge3++;
			}
			if (c > max) {
				max = c;
				maxAt = bt[2] + ":" + bt[3];
			}
		}
		Map<String, Object> low = new LinkedHashMap<>();
		low.put("below", LOW_THRESHOLD);
		low.put("below_name", name(LOW_THRESHOLD));
		low.put("max_parts", max);
		low.put("max_at", maxAt);
		low.put("mean_active", r3(active == 0 ? 0 : (double) sum / active));
		low.put("beats_with_low", active);
		low.put("beats_ge2", ge2);
		low.put("beats_ge3", ge3);
		Map<String, Integer> hs = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
			hs.put(String.valueOf(e.getKey()), e.getValue());
		}
		low.put("histogram", hs);
		j.put("low_register", low);

		// 전체 동시 발음 수(모든 트랙·파트)
		int tmax = 0;
		long tsum = 0;
		int tact = 0;
		for (int bi = 0; bi < beats.size(); bi++) {
			int c = 0;
			for (TrackData td : tds) {
				c += td.beatPoly[bi];
			}
			if (c > 0) {
				tact++;
				tsum += c;
			}
			tmax = Math.max(tmax, c);
		}
		Map<String, Object> tp = new LinkedHashMap<>();
		tp.put("mean_active", r3(tact == 0 ? 0 : (double) tsum / tact));
		tp.put("max", tmax);
		tp.put("note", "박마다 트랙별 최대 동시 발음 수를 더한 값(같은 순간이 아닐 수 있어 상한에 가까움)");
		j.put("polyphony_total", tp);

		// 옥타브 보정 원재료
		List<Map<String, Object>> oc = new ArrayList<>();
		for (TrackData td : tds) {
			Map<String, Object> m = octave(td, false, table);
			if (m != null) {
				oc.add(m);
			}
			Map<String, Object> s = octave(td, true, table);
			if (s != null) {
				oc.add(s);
			}
		}
		j.put("octave_correction", oc);

		// 글자 수 사용률
		List<Double> ratios = new ArrayList<>();
		List<String> over = new ArrayList<>();
		double maxR = 0;
		String maxRAt = null;
		for (TrackData td : tds) {
			@SuppressWarnings("unchecked")
			Map<String, Object> parts = (Map<String, Object>) td.json.get("parts");
			for (Part part : Part.values()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> pj = (Map<String, Object>) parts.get(part.key());
				if (td.parts[part.index()].wholeCount == 0) {
					continue;
				}
				double r = (Double) pj.get("chars_ratio_default");
				ratios.add(r);
				if (r > maxR) {
					maxR = r;
					maxRAt = td.number + ":" + part.key();
				}
				if (r > 1.0) {
					over.add(td.number + ":" + part.key());
				}
			}
		}
		Map<String, Object> cu = new LinkedHashMap<>();
		double mean = 0;
		for (double r : ratios) {
			mean += r;
		}
		cu.put("parts", ratios.size());
		cu.put("mean_ratio", r3(ratios.isEmpty() ? 0 : mean / ratios.size()));
		cu.put("max_ratio", r3(maxR));
		cu.put("max_at", maxRAt);
		cu.put("over_rank1", over);
		cu.put("rule", "track_default");
		cu.put("note", "음이 있는 파트만, 곡 전체 글자 수, RANK_1 한도 대비. 트랙마다 score_stats 기본과 같은 기준(tracks[].chars_rule: "
				+ "노래 파트에 음이 있으면 song_included = 멜로디·노래 1200, 화음1 800, 화음2 500, 아니면 song_excluded = 1600/1200/900)");
		j.put("chars_usage", cu);

		int silent = 0;
		List<Integer> mix = new ArrayList<>();
		for (TrackData td : tds) {
			for (PartData pd : td.parts) {
				for (Note n : pd.in) {
					if (n.sound() == SoundingPitch.SILENT) {
						silent++;
					}
				}
			}
			if (td.kind == SoundingPitch.Kind.REGIONS) {
				Map<String, Integer> ts = timbreSets(td.parts, true);
				if (ts.getOrDefault("default", 0) > 0 && ts.getOrDefault("alt", 0) > 0) {
					mix.add(td.number);
				}
			}
		}
		j.put("silent_notes", silent);
		j.put("timbre_mix_tracks", mix);
		return j;
	}

	/**
	 * 옥타브 보정 원재료 한 줄. song이 false면 주 악기 파트(멜로디·화음1·화음2), true면 노래 파트(노래 악기가 주 악기와 다르고 음이 있을 때만).
	 */
	private static Map<String, Object> octave(TrackData td, boolean song, InstrumentTable table) {
		List<Note> notes = new ArrayList<>();
		int prog;
		if (song) {
			PartData pd = td.parts[Part.SONG.index()];
			if (pd.in.isEmpty() || pd.program == td.t.getProgram()) {
				return null;
			}
			prog = pd.program;
			notes.addAll(pd.in);
		} else {
			prog = td.t.getProgram();
			for (Part p : MAIN) {
				notes.addAll(td.parts[p.index()].in);
			}
			// 노래 악기가 주 악기와 같으면(노래 타입 트랙) 노래 파트도 같은 악기
			PartData sp = td.parts[Part.SONG.index()];
			if (sp.program == prog) {
				notes.addAll(sp.in);
			}
			if (notes.isEmpty()) {
				return null;
			}
		}
		SoundingPitch.Rule rule = SoundingPitch.rule(prog);
		List<Integer> w = new ArrayList<>();
		List<Integer> s = new ArrayList<>();
		Map<String, Integer> sets = new LinkedHashMap<>();
		for (Note n : notes) {
			w.add(n.written());
			if (n.pitched()) {
				s.add(n.sound());
			}
			if (rule.kind() == SoundingPitch.Kind.REGIONS) {
				sets.merge(n.timbre(), 1, Integer::sum);
			}
		}
		Integer wm = median(w);
		Integer sm = median(s);
		Integer tableOffset = null;
		String dominant = null;
		if (rule.kind() == SoundingPitch.Kind.OFFSET || rule.kind() == SoundingPitch.Kind.UNKNOWN) {
			tableOffset = rule.offset();
		} else if (rule.kind() == SoundingPitch.Kind.REGIONS) {
			int a = sets.getOrDefault("default", 0);
			int b = sets.getOrDefault("alt", 0);
			dominant = a >= b ? "default" : "alt";
			for (SoundingPitch.Region g : rule.regions()) {
				if (!g.silent() && dominant.equals(g.timbre())) {
					tableOffset = g.offset();
					break;
				}
			}
		}
		Integer diff = (wm != null && sm != null) ? sm - wm : null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("track", td.number);
		m.put("parts", song ? "song" : "main");
		m.put("program", prog);
		InstrumentTable.Instrument inst = table.get(prog);
		m.put("instrument", inst == null ? null : inst.name());
		m.put("kind", rule.kind().key());
		m.put("sounding", SoundingPitch.summary(prog));
		m.put("notes", notes.size());
		m.put("written_median", wm);
		m.put("written_median_name", wm == null ? null : name(wm));
		m.put("sounding_median", sm);
		m.put("sounding_median_name", sm == null ? null : name(sm));
		m.put("diff", diff);
		m.put("table_offset", tableOffset);
		m.put("matches_table", diff != null && tableOffset != null ? diff.equals(tableOffset) : null);
		m.put("timbre_sets", rule.kind() == SoundingPitch.Kind.REGIONS ? sets : null);
		m.put("dominant_set", dominant);
		String[] est = estimate(rule, wm, sm, sets);
		m.put("estimate", est[0]);
		m.put("estimate_text", est[1]);
		return m;
	}

	/**
	 * 편곡자가 들리는 음을 맞추려고 옮겨 적었는지 대강 추정한다(근거가 약하므로 참고만). 반환 {코드, 설명}.
	 * <ul>
	 * <li>적은 대로 울리는 악기: not_needed</li>
	 * <li>위로 울리는 악기(+12, +24): 들리는 음 중앙값 ≥ 86(D6)이면 not_shifted_likely, 적은 음 중앙값 ≤ 59(B3)이고 들리는 음 중앙값
	 * ≤ 84(C6)이면 shifted_likely, 그 밖은 unclear</li>
	 * <li>구간 악기: 쓴 세트(set_default/set_alt/set_mix)</li>
	 * <li>고정음·음높이 없음·효과음: n/a, 표에 없는 악기: unknown</li>
	 * </ul>
	 */
	static String[] estimate(SoundingPitch.Rule rule, Integer wm, Integer sm, Map<String, Integer> sets) {
		switch (rule.kind()) {
		case OFFSET: {
			int off = rule.offset();
			if (off == 0) {
				return new String[] { "not_needed", "적은 대로 울리는 악기" };
			}
			if (sm == null || wm == null) {
				return new String[] { "unclear", "음높이 있는 음이 없음" };
			}
			if (off > 0 && sm >= 86) {
				return new String[] { "not_shifted_likely",
						"들리는 음 중앙값 " + name(sm) + "이 D6 이상으로 높음 — 적은 음을 옮기지 않은 듯" };
			}
			if (off > 0 && wm <= 59 && sm <= 84) {
				return new String[] { "shifted_likely", "적은 음 중앙값 " + name(wm) + "(B3 이하)이 낮고 들리는 음 " + name(sm)
						+ "은 보통 음역 — 들리는 음을 맞추려 낮게 적은 듯" };
			}
			return new String[] { "unclear", "적은 음 " + name(wm) + " → 들리는 음 " + name(sm) + "(" + SoundingPitch.offsetText(off)
					+ "), 판단 보류" };
		}
		case REGIONS: {
			int a = sets.getOrDefault("default", 0);
			int b = sets.getOrDefault("alt", 0);
			int s = sets.getOrDefault("silent", 0);
			String tail = s > 0 ? ", 무음 구간 음 " + s + "개" : "";
			if (a > 0 && b > 0) {
				return new String[] { "set_mix", "두 세트를 섞어 씀(default " + a + ", alt " + b + tail + ")" };
			}
			if (b > 0) {
				return new String[] { "set_alt", "alt 세트(튜닝음)만 씀(" + b + "개" + tail + ")" };
			}
			if (a > 0) {
				return new String[] { "set_default", "default 세트만 씀(" + a + "개" + tail + ")" };
			}
			return new String[] { "unclear", "음높이 있는 음이 없음" + tail };
		}
		case UNKNOWN:
			return new String[] { "unknown", "표에 없는 악기(적은 대로 가정)" };
		default:
			return new String[] { "n/a", SoundingPitch.summary(rule.program()) + " 악기" };
		}
	}

	private static String roleText(Map<String, List<Integer>> roles, List<Integer> lead) {
		String[][] ko = { { "melody", "멜로디" }, { "chord", "화음" }, { "bass", "베이스" }, { "drums", "드럼" },
				{ "song", "노래" }, { "sfx", "효과음" }, { "empty", "빈 트랙" } };
		List<String> l = new ArrayList<>();
		for (String[] k : ko) {
			List<Integer> t = roles.get(k[0]);
			if (t != null && !t.isEmpty()) {
				l.add(k[1] + " " + joinInts(t));
			}
		}
		String s = String.join(" · ", l);
		if (!lead.isEmpty()) {
			s += " (주선율 추정 " + joinInts(lead) + ")";
		}
		return s;
	}

	// ======================================================================
	// 파일·요약
	// ======================================================================

	private static Map<String, Object> fileJson(ScoreLoader.Loaded src, MMLScore score, MeasureMap map, int lastMeasure,
			int totalTicks) {
		Map<String, Object> j = new LinkedHashMap<>();
		Path p = src.path();
		j.put("path", p == null ? null : p.toString());
		j.put("name", p == null ? null : p.getFileName().toString());
		j.put("format", src.format());
		j.put("title", src.title());
		j.put("author", src.author());
		j.put("encoding", src.encoding());
		// 처음 박자: 0틱의 박자 변경([time-signature] 0=...)이 기본 박자(time=)보다 앞선다(마디 지도와 같은 규칙)
		MeasureMap.Signature first = map.signatures().isEmpty() ? null : map.signatures().get(0);
		j.put("time_signature", first == null ? score.getBaseTime() : first.numerator() + "/" + first.denominator());
		j.put("base_time", score.getBaseTime());
		List<Map<String, Object>> sigs = new ArrayList<>();
		for (MeasureMap.Signature s : map.signatures()) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("tick", s.tick());
			x.put("measure", map.measureOf(s.tick()));
			x.put("sig", s.numerator() + "/" + s.denominator());
			sigs.add(x);
		}
		j.put("time_signatures", sigs);
		List<Map<String, Object>> tempos = new ArrayList<>();
		for (MMLTempoEvent t : score.getTempoEventList()) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("tick", t.getTickOffset());
			x.put("pos", map.position(t.getTickOffset()).toString());
			x.put("bpm", t.getTempo());
			tempos.add(x);
		}
		j.put("tempos", tempos);
		j.put("initial_tempo", score.getTempoEventList().isEmpty() || score.getTempoEventList().get(0).getTickOffset() > 0
				? MMLTempoEvent.INITIAL_TEMPO
				: score.getTempoEventList().get(0).getTempo());
		j.put("measures", lastMeasure);
		j.put("total_ticks", totalTicks);
		j.put("duration_sec", r3(score.getTotalTime() / 1000.0));
		j.put("generated", src.generated());
		return j;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> summary(Map<String, Object> file, Map<String, Object> range, List<TrackData> tds,
			Map<String, Object> ens, List<String> warnings) {
		Map<String, Object> s = new LinkedHashMap<>();
		Map<String, Object> f = new LinkedHashMap<>();
		for (String k : new String[] { "path", "format", "title", "author", "time_signature", "initial_tempo", "measures",
				"duration_sec" }) {
			f.put(k, file.get(k));
		}
		f.put("tempo_changes", ((List<?>) file.get("tempos")).size());
		s.put("file", f);
		s.put("range", range);
		s.put("tracks", tds.size());
		s.put("tracks_with_notes", ens.get("tracks_with_notes"));
		List<Map<String, Object>> inst = new ArrayList<>();
		for (TrackData td : tds) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("track", td.number);
			x.put("name", td.t.getTrackName());
			x.put("program", td.t.getProgram());
			x.put("instrument", td.inst == null ? null : td.inst.name());
			x.put("role", td.role);
			x.put("tags", td.tags);
			x.put("notes", td.notesIn);
			Map<String, Object> w = (Map<String, Object>) td.json.get("written_range");
			Map<String, Object> sr = (Map<String, Object>) td.json.get("sounding_range");
			x.put("written", rangeText(w));
			x.put("sounding", rangeText(sr));
			x.put("sounding_offset", SoundingPitch.summary(td.t.getProgram()));
			inst.add(x);
		}
		s.put("instruments", inst);
		s.put("roles", ens.get("roles"));
		s.put("role_text", ens.get("role_text"));
		Map<String, Object> cu = (Map<String, Object>) ens.get("chars_usage");
		s.put("chars_ratio_mean", cu.get("mean_ratio"));
		s.put("chars_ratio_max", cu.get("max_ratio"));
		s.put("chars_over_rank1", cu.get("over_rank1"));
		s.put("chars_rule", "track_default"); // 트랙마다 노래 파트에 음이 있으면 song_included, 아니면 song_excluded
		Map<String, Object> low = (Map<String, Object>) ens.get("low_register");
		s.put("low_parts_max", low.get("max_parts"));
		s.put("low_beats_ge3", low.get("beats_ge3"));
		int pmax = 0;
		for (TrackData td : tds) {
			pmax = Math.max(pmax, td.polyMax);
		}
		s.put("track_polyphony_max", pmax);
		List<Map<String, Object>> oc = new ArrayList<>();
		for (Map<String, Object> o : (List<Map<String, Object>>) ens.get("octave_correction")) {
			Map<String, Object> x = new LinkedHashMap<>();
			for (String k : new String[] { "track", "parts", "program", "written_median_name", "sounding_median_name", "diff",
					"table_offset", "matches_table", "estimate" }) {
				x.put(k, o.get(k));
			}
			oc.add(x);
		}
		s.put("octave_correction", oc);
		s.put("silent_notes", ens.get("silent_notes"));
		s.put("timbre_mix_tracks", ens.get("timbre_mix_tracks"));
		s.put("warnings", warnings);
		return s;
	}

	@SuppressWarnings("unchecked")
	private static String summaryText(Map<String, Object> file, Map<String, Object> range, List<TrackData> tds,
			Map<String, Object> ens) {
		StringBuilder b = new StringBuilder();
		String title = (String) file.get("title");
		String fname = (String) file.get("name");
		b.append(title != null && !title.isBlank() ? "「" + title + "」"
				: fname != null ? "「" + fname + "」(제목 없음)" : "(제목 없음)").append(' ');
		b.append(tds.size()).append("트랙(음 있는 ").append(ens.get("tracks_with_notes")).append("), ");
		b.append(file.get("measures")).append("마디·").append(Math.round((Double) file.get("duration_sec"))).append("초, ");
		b.append("템포 ").append(file.get("initial_tempo"));
		int tc = ((List<?>) file.get("tempos")).size();
		if (tc > 1) {
			b.append("(변경 ").append(tc).append("회)");
		}
		b.append(", 박자 ").append(file.get("time_signature")).append('.');
		if (!Boolean.TRUE.equals(range.get("whole_song"))) {
			b.append(" 범위 ").append(range.get("from_measure")).append("~").append(range.get("to_measure")).append("마디.");
		}
		b.append(" 역할: ").append(ens.get("role_text")).append('.');
		Map<String, Object> cu = (Map<String, Object>) ens.get("chars_usage");
		b.append(" 글자 수 사용률(1랭크 대비, 트랙 기본 기준 — 노래 파트가 있으면 노래 포함, 없으면 노래 제외) 평균 ").append(pct((Double) cu.get("mean_ratio"))).append(", 최대 ")
				.append(pct((Double) cu.get("max_ratio")));
		if (cu.get("max_at") != null) {
			b.append("(").append(cu.get("max_at")).append(")");
		}
		b.append('.');
		Map<String, Object> low = (Map<String, Object>) ens.get("low_register");
		b.append(" 저음(").append(name(LOW_THRESHOLD)).append(" 미만) 동시 파트 최대 ").append(low.get("max_parts"))
				.append(", 3파트 이상 박 ").append(low.get("beats_ge3")).append('.');
		List<String> oc = new ArrayList<>();
		for (Map<String, Object> o : (List<Map<String, Object>>) ens.get("octave_correction")) {
			Object diff = o.get("diff");
			if (diff == null || ((Integer) diff) == 0) {
				continue;
			}
			oc.add(o.get("track") + "번 " + o.get("instrument") + " " + o.get("written_median_name") + "→"
					+ o.get("sounding_median_name") + "(" + signed((Integer) diff) + ")");
		}
		if (!oc.isEmpty()) {
			b.append(" 들리는 음(중앙값): ").append(String.join(", ", oc)).append('.');
		}
		int silent = (Integer) ens.get("silent_notes");
		if (silent > 0) {
			b.append(" 무음 구간 음 ").append(silent).append("개.");
		}
		List<Integer> mix = (List<Integer>) ens.get("timbre_mix_tracks");
		if (!mix.isEmpty()) {
			b.append(" 세트 섞음: 트랙 ").append(joinInts(mix)).append('.');
		}
		return b.toString();
	}

	// ======================================================================
	// 도우미
	// ======================================================================

	private static List<Integer> writtenOf(List<Note> l) {
		List<Integer> r = new ArrayList<>();
		for (Note n : l) {
			r.add(n.written());
		}
		return r;
	}

	private static List<Integer> soundOf(List<Note> l) {
		List<Integer> r = new ArrayList<>();
		for (Note n : l) {
			if (n.pitched()) {
				r.add(n.sound());
			}
		}
		return r;
	}

	/** 아래쪽 중앙값(정수). 빈 목록이면 null. */
	static Integer median(List<Integer> v) {
		if (v.isEmpty()) {
			return null;
		}
		List<Integer> s = new ArrayList<>(v);
		Collections.sort(s);
		return s.get((s.size() - 1) / 2);
	}

	private static Map<String, Object> pitchStats(List<Integer> v) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("count", v.size());
		if (v.isEmpty()) {
			m.put("min", null);
			m.put("max", null);
			m.put("median", null);
			m.put("min_name", null);
			m.put("max_name", null);
			m.put("median_name", null);
			return m;
		}
		int mn = Collections.min(v);
		int mx = Collections.max(v);
		int md = median(v);
		m.put("min", mn);
		m.put("max", mx);
		m.put("median", md);
		m.put("min_name", name(mn));
		m.put("max_name", name(mx));
		m.put("median_name", name(md));
		return m;
	}

	private static Integer width(Map<String, Object> st) {
		Object a = st.get("min");
		Object b = st.get("max");
		return (a == null || b == null) ? null : (Integer) b - (Integer) a;
	}

	private static String rangeText(Map<String, Object> st) {
		if (st == null || st.get("min") == null) {
			return null;
		}
		return st.get("min_name") + "~" + st.get("max_name") + " (중앙 " + st.get("median_name") + ")";
	}

	static String name(int midi) {
		return NoteSpec.pitchName(midi);
	}

	static double r3(double x) {
		return Math.round(x * 1000.0) / 1000.0;
	}

	private static String fmt(double x) {
		return String.format(Locale.ROOT, "%.2f", x);
	}

	private static String pct(Double x) {
		return x == null ? "-" : Math.round(x * 100) + "%";
	}

	private static String signed(int x) {
		return (x > 0 ? "+" : "") + x;
	}

	static String joinInts(List<Integer> l) {
		List<String> s = new ArrayList<>();
		for (int x : l) {
			s.add(String.valueOf(x));
		}
		return String.join("·", s);
	}

	/** "MML@a,b,c[,d];" → {a, b, c, d}(없으면 ""). Project.splitMml과 같은 규칙. */
	static String[] splitMml(String mml) {
		String body = mml.startsWith("MML@") ? mml.substring(4) : mml;
		if (body.endsWith(";")) {
			body = body.substring(0, body.length() - 1);
		}
		String[] s = body.split(",", -1);
		String[] out = { "", "", "", "" };
		for (int i = 0; i < s.length && i < 4; i++) {
			out[i] = s[i];
		}
		return out;
	}

	private static int[] rankTopCache;

	/** rank.properties의 RANK_1(멜로디, 화음1, 화음2). */
	static synchronized int[] rankTop() {
		if (rankTopCache == null) {
			try (InputStream in = ResourceLoader.getResourceFile("rank.properties")) {
				Properties p = new Properties();
				p.load(in);
				String v = p.getProperty("RANK_1");
				if (v == null) {
					throw new IllegalStateException("rank.properties에 RANK_1이 없음");
				}
				String[] s = v.split(",");
				rankTopCache = new int[] { Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim()),
						Integer.parseInt(s[2].trim()) };
			} catch (IOException e) {
				throw new IllegalStateException("rank.properties를 읽을 수 없음", e);
			}
		}
		return rankTopCache.clone();
	}
}
