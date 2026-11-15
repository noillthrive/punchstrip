package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.noArgs;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.EngineException;
import mabibridge.midi.BeatChord;
import mabibridge.midi.Note;
import mabibridge.midi.ReferenceMidi;
import mabibridge.midi.ReferenceHints;
import mabibridge.midi.ReferenceMidiException;
import mabibridge.midi.VoiceDraft;
import mabibridge.midi.Warnings;

/** 참조 MIDI 도구. */
final class ReferenceTools {

	public static final String REFERENCE_LOAD_FAILED = "REFERENCE_LOAD_FAILED";
	static final int DEFAULT_MAX = 400;
	static final int MAX_MAX = 20000;

	private ReferenceTools() {}

	static void register(ToolRegistry r) {
		r.add("reference_load",
				"비교·참고용 MIDI 파일을 연다(읽기만). 성공하면 요약(트랙 표, 템포, 박자, 마디 수, 경고)을 준다. "
						+ "깨진 파일이면 어느 트랙·오프셋이 깨졌는지 알려 주고 이전 참조는 그대로 둔다.",
				props().add("path", string(".mid 파일의 절대 경로")).object("path"),
				ReferenceTools::load);

		r.add("reference_info", "이미 연 참조 MIDI의 요약(reference_load와 같은 내용, 다시 읽지 않음). "
				+ "트랙마다 melody_score(0~1, 멜로디일 가능성)·components·role_guess(melody·bass·chords·pad·drums·unknown) 힌트가 붙는다. "
				+ "derived: regridded(reference_regrid 했는가)·regrid(값과 개수)·cleanups(reference_cleanup 기록)·raw(원본 요약).",
				noArgs(),
				(s, a) -> {
					JsonObject o = summary(s.requireReference());
					o.add("derived", ReferenceRegridTools.derived(s));
					return o;
				});

		r.add("reference_notes",
				"참조 MIDI의 음표를 마디 범위로 본다(tick·len·pos는 4분음표 = 96 기준으로 환산, src_*는 원본 해상도). "
						+ "max개를 넘으면 잘라내고 truncated: true. 넓은 범위는 트랙을 지정해 나눠 볼 것. "
						+ "음마다 pedal_end(서스테인 페달까지 본 울림 끝 틱)·pedal, 응답에 박마다 격자 힌트 grid_hints(straight·triplet·none).",
				props()
						.add("tracks", intArray("볼 참조 트랙 번호들(1부터). 생략하면 전체(드럼 포함, drum 표시)"))
						.add("from_measure", integer("시작 마디(1부터)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.add("max", integer("최대 음표 수(기본 400)", 1, MAX_MAX))
						.add("triplet_ratio", tripletRatioSchema())
						.add("straight_tol", straightTolSchema())
						.object("from_measure", "to_measure"),
				ReferenceTools::notes);

		r.add("reference_chords",
				"참조 MIDI에서 박마다 울리는 음이름 집합(옥타브 무시)과 그 음들을 본다. 화음 진행을 파악할 때 쓴다. 드럼 채널은 뺀다. "
						+ "응답에 박마다 격자 힌트 grid_hints(reference_notes와 같은 규칙).",
				props()
						.add("from_measure", integer("시작 마디(1부터)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.add("tracks", intArray("볼 참조 트랙 번호들. 생략하면 드럼 제외 전체"))
						.add("triplet_ratio", tripletRatioSchema())
						.add("straight_tol", straightTolSchema())
						.object("from_measure", "to_measure"),
				ReferenceTools::chords);

		r.add("reference_voice_draft",
				"참조 트랙들의 음을 1~3성부(멜로디·화음1·화음2)로 나눈 초안을 만든다(읽기만, 악보·참조는 그대로). "
						+ "같은 시작 틱 묶음에서 가장 높은 음 → 1성부, 가장 낮은 음 → 마지막 성부, 나머지는 앞 음과 가까운 성부. "
						+ "성부가 모자라면 새 음이름을 더하는 음을 남기고 나머지는 dropped. 성부 안 겹침은 끊는다(trimmed). "
						+ "voices[].notes는 score_put_notes에 pitch_mode: \"sounding\"으로 그대로 넣을 수 있다(pos는 positions에 따로).",
				props()
						.add("tracks", intArray("나눌 참조 트랙 번호들(1부터, 1개 이상). 드럼 채널 음은 뺀다"))
						.add("from_measure", integer("시작 마디(1부터)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.add("voices", integer("성부 수 1~3(기본 3)", 1, VoiceDraft.MAX_VOICES))
						.add("home_pitches", intArray("성부마다 처음 기준음(MIDI, 길이 = voices). 기본 [72, 60, 48](2성부 [72, 48], 1성부 [72])"))
						.add("max_notes", integer("내보낼 최대 음 수(기본 2000). 넘으면 늦은 음부터 자르고 truncated: true", 1, MAX_MAX))
						.add("use_pedal", bool("true면 음 길이를 pedal_end(서스테인 페달까지)로 본다(기본 false = note-off)"))
						.object("tracks", "from_measure", "to_measure"),
				ReferenceTools::voiceDraft);
	}

	private static Object load(ServerState s, Args a) {
		String p = a.reqString("path");
		Path path = ProjectTools.path(p);
		ReferenceMidi ref;
		byte[] bytes = null;
		try {
			try {
				bytes = java.nio.file.Files.readAllBytes(path);
			} catch (java.io.IOException io) {
				bytes = null; // ReferenceMidi.load(path) below reports the read failure in its own words
			}
			ref = bytes == null ? ReferenceMidi.load(path) : ReferenceMidi.load(path, bytes);
		} catch (ReferenceMidiException e) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("path", p);
			if (e.trackNumber().isPresent()) {
				d.put("track", e.trackNumber().getAsInt());
			}
			e.location().ifPresent(l -> {
				d.put("file_offset", l.fileOffset());
				d.put("tick_before", l.tickBefore());
				d.put("location", l.toString());
			});
			d.put("previous_reference_kept", s.reference() != null);
			throw new EngineException(REFERENCE_LOAD_FAILED, e.getMessage(), d, e);
		}
		s.setReferenceOrigin(ref, bytes);
		JsonObject o = summary(ref);
		o.add("derived", ReferenceRegridTools.derived(s));
		return o;
	}

	/** reference_load·reference_info 응답. */
	static JsonObject summary(ReferenceMidi m) {
		JsonObject o = new JsonObject();
		o.addProperty("file", String.valueOf(m.file()));
		o.addProperty("format", m.format());
		o.addProperty("track_count", m.trackCount());
		o.addProperty("resolution", m.resolution());
		o.addProperty("tick_length", m.tickLength());
		o.addProperty("seconds", m.seconds());
		o.addProperty("initial_bpm", m.initialBpm());
		o.addProperty("measure_count", m.measureCount());
		o.add("tempos", Json.tree(m.tempos()));
		o.add("time_signatures", Json.tree(m.timeSignatures()));
		o.add("key_signatures", Json.tree(m.keySignatures()));
		o.add("markers", Json.tree(m.markers()));
		JsonArray tracks = Json.tree(m.tracks()).getAsJsonArray();
		addTrackHints(m, tracks, o);
		o.add("tracks", tracks);
		Warnings w = m.warnings();
		JsonObject wj = new JsonObject();
		wj.add("messages", Json.tree(w.messages()));
		wj.add("dropped_events", Json.tree(w.droppedEvents()));
		wj.addProperty("unfinished_notes", w.unfinishedNotes());
		wj.addProperty("retriggers", w.retriggers());
		wj.addProperty("orphan_note_offs", w.orphanNoteOffs());
		wj.addProperty("zero_length_notes", w.zeroLengthNotes());
		wj.addProperty("invalid_data_bytes", w.invalidDataBytes());
		wj.addProperty("abnormal_length", w.abnormalLength());
		wj.addProperty("byte_scan_problem", w.byteScanProblem());
		o.add("warnings", wj);
		return o;
	}

	private static Set<Integer> trackSet(ReferenceMidi m, List<Integer> tracks) {
		if (tracks == null || tracks.isEmpty()) {
			return null;
		}
		Set<Integer> set = new LinkedHashSet<>();
		for (int t : tracks) {
			if (t < 1 || t > m.trackCount()) {
				throw Args.bad("참조 트랙 " + t + "번이 없음(1~" + m.trackCount() + ")", "track", t, "track_count",
						m.trackCount());
			}
			set.add(t);
		}
		return set;
	}

	static void checkRange(int from, int to) {
		if (from < 1 || to < from) {
			throw Args.bad("마디 범위가 틀림: " + from + "~" + to + " (1 이상, from ≤ to)", "from_measure", from, "to_measure", to);
		}
	}

	private static Object notes(ServerState s, Args a) {
		ReferenceMidi m = s.requireReference();
		Range rg = range(m, a);
		int from = rg.from();
		int to = rg.to();
		int max = strictInt(a, "max", DEFAULT_MAX);
		if (max < 1 || max > MAX_MAX) {
			throw Args.bad("max는 1~" + MAX_MAX, "max", max);
		}
		double ratio = tripletRatio(a);
		double tol = straightTol(a);
		Set<Integer> tracks = trackSet(m, strictIntList(a, "tracks"));
		JsonArray out = new JsonArray();
		List<Note> selected = new ArrayList<>();
		int total = 0;
		int pedalCount = 0;
		for (Note n : m.notesAll(from, to)) {
			if (tracks != null && !tracks.contains(n.track())) {
				continue;
			}
			total++;
			selected.add(n);
			if (n.pedal()) {
				pedalCount++;
			}
			if (out.size() < max) {
				out.add(noteJson(n));
			}
		}
		JsonObject o = new JsonObject();
		o.add("range", rangeJson(from, to));
		o.add("note", Json.tree(rg.note()));
		o.add("tracks", tracks == null ? Json.tree("all") : Json.tree(tracks));
		o.addProperty("total", total);
		o.addProperty("returned", out.size());
		o.addProperty("truncated", total > out.size());
		o.addProperty("pedal_notes", pedalCount);
		o.add("notes", out);
		addGrid(o, m, selected, from, to, ratio, tol);
		return o;
	}

	static JsonArray rangeJson(int from, int to) {
		JsonArray r = new JsonArray();
		r.add(from);
		r.add(to);
		return r;
	}

	/** 참조 음표. */
	static JsonObject noteJson(Note n) {
		JsonObject o = new JsonObject();
		o.addProperty("track", n.track());
		o.addProperty("channel", n.channel());
		o.addProperty("pitch", n.pitch());
		o.addProperty("name", n.pitchName());
		o.addProperty("velocity", n.velocity());
		o.addProperty("velocity15", n.velocity15());
		o.addProperty("tick", n.tick96());
		o.addProperty("len", n.len96());
		o.addProperty("pos", n.pos());
		o.addProperty("measure", n.measure());
		o.addProperty("src_tick", n.srcTick());
		o.addProperty("src_len", n.srcLength());
		o.addProperty("drum", n.isDrum());
		o.addProperty("pedal_end", n.pedalEnd96());
		o.addProperty("pedal", n.pedal());
		return o;
	}

	private static Object chords(ServerState s, Args a) {
		ReferenceMidi m = s.requireReference();
		Range rg = range(m, a);
		int from = rg.from();
		int to = rg.to();
		double ratio = tripletRatio(a);
		double tol = straightTol(a);
		Set<Integer> tracks = trackSet(m, strictIntList(a, "tracks"));
		List<BeatChord> beats = m.chordsByBeat(from, to, tracks == null ? null : new HashSet<>(tracks));
		JsonArray out = new JsonArray();
		for (BeatChord b : beats) {
			JsonObject o = new JsonObject();
			o.addProperty("pos", b.pos());
			o.addProperty("measure", b.measure());
			o.addProperty("beat", b.beat());
			o.addProperty("tick", b.tick96Start());
			o.addProperty("tick_end", b.tick96End());
			o.add("pitch_classes", Json.tree(b.pitchClasses()));
			o.addProperty("bass", b.bass());
			JsonArray ns = new JsonArray();
			for (Note n : b.notes()) {
				JsonObject x = new JsonObject();
				x.addProperty("track", n.track());
				x.addProperty("pitch", n.pitch());
				x.addProperty("name", n.pitchName());
				x.addProperty("tick", n.tick96());
				x.addProperty("len", n.len96());
				x.addProperty("pos", n.pos());
				ns.add(x);
			}
			o.add("notes", ns);
			out.add(o);
		}
		JsonObject o = new JsonObject();
		o.add("range", rangeJson(from, to));
		o.add("note", Json.tree(rg.note()));
		o.add("tracks", tracks == null ? Json.tree("all_non_drum") : Json.tree(tracks));
		o.addProperty("beat_count", out.size());
		o.add("beats", out);
		List<Note> starts = new ArrayList<>();
		for (Note n : m.notesAll(from, to)) {
			if (!n.isDrum() && (tracks == null || tracks.contains(n.track()))) {
				starts.add(n);
			}
		}
		addGrid(o, m, starts, from, to, ratio, tol);
		return o;
	}


	// ------------------------------------------------------------------ 인자(엄격한 정수, 마디 범위)

	/**
	 * 고른 마디 범위. to_measure가 곡 끝을 넘으면 마지막 마디로 줄이고 note에 적는다(아니면 note는 null).
	 */
	record Range(int from, int to, String note) {}

	/** from_measure·to_measure(필수, JSON 정수). from이 곡 끝 뒤면 BAD_ARGUMENT, to가 곡 끝 뒤면 마지막 마디로 줄인다. */
	static Range range(ReferenceMidi m, Args a) {
		int from = strictReqInt(a, "from_measure");
		int to = strictReqInt(a, "to_measure");
		checkRange(from, to);
		int last = m.measureCount();
		if (from > last) {
			throw Args.bad("시작 마디 " + from + "가 곡 끝(" + last + "마디) 뒤", "from_measure", from, "measure_count", last);
		}
		if (to > last) {
			return new Range(from, last, "to_measure를 " + last + "로 줄임(곡 끝, 준 값 " + to + ")");
		}
		return new Range(from, to, null);
	}

	/** JSON 수 중 정수만(2.0 같은 정수 모양 소수는 받음, 문자열 "3"·2.5는 BAD_ARGUMENT). */
	static int strictIntValue(JsonElement e, String k) {
		if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			try {
				long v = new BigDecimal(e.getAsString()).longValueExact();
				if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
					return (int) v;
				}
			} catch (ArithmeticException | NumberFormatException ex) {
				// 아래에서 거부
			}
		}
		throw Args.bad(k + "는 정수(JSON 수, 문자열 불가)여야 함: " + e, "argument", k, "value", String.valueOf(e));
	}

	static int strictReqInt(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null) {
			throw Args.bad("인자 " + k + "가 필요함(정수)", "argument", k);
		}
		return strictIntValue(e, k);
	}

	static int strictInt(Args a, String k, int def) {
		JsonElement e = a.raw(k);
		return e == null ? def : strictIntValue(e, k);
	}

	/** JSON 정수 배열(없으면 null). */
	static List<Integer> strictIntList(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null) {
			return null;
		}
		if (!e.isJsonArray()) {
			throw Args.bad(k + "는 정수 배열이어야 함", "argument", k, "value", e.toString());
		}
		List<Integer> l = new ArrayList<>();
		int i = 0;
		for (JsonElement x : e.getAsJsonArray()) {
			l.add(strictIntValue(x, k + "[" + i++ + "]"));
		}
		return l;
	}

	// ------------------------------------------------------------------ 힌트

	static final double TRIPLET_RATIO_MIN = 0.05;
	static final double TRIPLET_RATIO_MAX = 1.0;
	static final double STRAIGHT_TOL_MAX = 24.0;
	static final int DEFAULT_MAX_NOTES = 2000;

	private static JsonObject tripletRatioSchema() {
		JsonObject o = ToolRegistry.typed("number", "셋잇단 판정: triplet_err < 이 값 × straight_err(기본 "
				+ ReferenceHints.DEFAULT_TRIPLET_RATIO + ", " + TRIPLET_RATIO_MIN + "~" + TRIPLET_RATIO_MAX + ")");
		o.addProperty("minimum", TRIPLET_RATIO_MIN);
		o.addProperty("maximum", TRIPLET_RATIO_MAX);
		return o;
	}

	private static JsonObject straightTolSchema() {
		JsonObject o = ToolRegistry.typed("number", "셋잇단 판정: straight_err가 이 틱보다 커야 함(기본 "
				+ ReferenceHints.DEFAULT_STRAIGHT_TOL + " = 24틱의 15%, 0~" + STRAIGHT_TOL_MAX + ")");
		o.addProperty("minimum", 0);
		o.addProperty("maximum", STRAIGHT_TOL_MAX);
		return o;
	}

	private static Double optNumber(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return new BigDecimal(e.getAsString()).doubleValue();
		}
		throw Args.bad(k + "는 숫자여야 함: " + e, "argument", k, "value", e.toString());
	}

	static double tripletRatio(Args a) {
		Double v = optNumber(a, "triplet_ratio");
		if (v == null) {
			return ReferenceHints.DEFAULT_TRIPLET_RATIO;
		}
		if (!(v >= TRIPLET_RATIO_MIN && v <= TRIPLET_RATIO_MAX)) {
			throw Args.bad("triplet_ratio는 " + TRIPLET_RATIO_MIN + "~" + TRIPLET_RATIO_MAX, "triplet_ratio", v);
		}
		return v;
	}

	static double straightTol(Args a) {
		Double v = optNumber(a, "straight_tol");
		if (v == null) {
			return ReferenceHints.DEFAULT_STRAIGHT_TOL;
		}
		if (!(v >= 0 && v <= STRAIGHT_TOL_MAX)) {
			throw Args.bad("straight_tol은 0~" + STRAIGHT_TOL_MAX + "틱", "straight_tol", v);
		}
		return v;
	}

	private static void addGrid(JsonObject o, ReferenceMidi m, List<Note> notes, int from, int to, double ratio,
			double tol) {
		List<ReferenceHints.GridHint> g = ReferenceHints.gridHints(m, notes, from, to, ratio, tol);
		JsonObject rule = new JsonObject();
		rule.addProperty("triplet_ratio", ratio);
		rule.addProperty("straight_tol", tol);
		rule.addProperty("straight_grid", "1/4박(4분음표 박이면 24틱)");
		rule.addProperty("triplet_grid", "1/3박(32틱)");
		rule.addProperty("text", "박 안 시작들(같은 시각은 하나로, 6틱 반올림 전 값)의 격자까지 평균 거리. "
				+ "triplet_err < triplet_ratio × straight_err 이고 straight_err > straight_tol이면 triplet");
		o.add("grid_rule", rule);
		o.add("grid_summary", Json.tree(ReferenceHints.gridSummary(g)));
		o.add("grid_hints", Json.tree(g));
	}

	/** reference_load·reference_info의 트랙마다 힌트를 더한다. */
	private static void addTrackHints(ReferenceMidi m, JsonArray tracks, JsonObject o) {
		List<ReferenceHints.TrackHint> hints = m.trackHints();
		Map<String, List<Integer>> roles = new LinkedHashMap<>();
		for (String r : new String[] { "melody", "bass", "chords", "pad", "drums", "unknown" }) {
			roles.put(r, new ArrayList<>());
		}
		List<ReferenceHints.TrackHint> ranked = new ArrayList<>();
		for (int i = 0; i < hints.size() && i < tracks.size(); i++) {
			ReferenceHints.TrackHint h = hints.get(i);
			JsonObject t = tracks.get(i).getAsJsonObject();
			t.addProperty("melody_score", h.melodyScore());
			t.add("components", Json.tree(h.components()));
			t.add("melody_rank", Json.tree(h.melodyRank()));
			t.addProperty("role_guess", h.roleGuess());
			t.addProperty("role_reason", h.roleReason());
			t.add("role_evidence", Json.tree(h.evidence()));
			roles.get(h.roleGuess()).add(h.track());
			if (h.melodyRank() != null) {
				ranked.add(h);
			}
		}
		ranked.sort((x, y) -> Integer.compare(x.melodyRank(), y.melodyRank()));
		JsonArray rank = new JsonArray();
		for (ReferenceHints.TrackHint h : ranked) {
			rank.add(h.track());
		}
		JsonObject rule = new JsonObject();
		JsonObject w = new JsonObject();
		w.addProperty("height", ReferenceHints.W_HEIGHT);
		w.addProperty("monophony", ReferenceHints.W_MONOPHONY);
		w.addProperty("coverage", ReferenceHints.W_COVERAGE);
		w.addProperty("name_keyword", ReferenceHints.W_NAME);
		rule.add("weights", w);
		rule.add("name_keywords", Json.tree(ReferenceHints.NAME_KEYWORDS));
		rule.add("name_tokens", Json.tree(ReferenceHints.NAME_TOKENS));
		rule.addProperty("text", "맨 윗선 = 같은 시각에 시작한 음 묶음마다 가장 높은 음. melody_score = 0.35×높이(맨 윗선 평균 음 48→0, 84→1) "
				+ "+ 0.30×단선율(맨 윗선 음이 서로 겹치지 않는 비율) + 0.20×곡 덮음(음이 있는 마디 비율) + 0.15×이름 열쇠말. "
				+ "드럼·빈 트랙은 0. 역할 순서: 드럼 채널 → drums / 음이 없거나 음이 있는 마디가 곡의 5%(최소 2마디) 미만 → unknown / "
				+ "평균 음 < 48·단선율 > 0.8 → bass / 남은 트랙 중 묶음 평균 < 2.5이고 점수 1위·0.5 이상 → melody / "
				+ "시작점의 절반 넘게 2음 이상·평균 길이 ≥ 1박 → pad / 시작점의 절반 넘게 2음 이상 → chords / 그 밖 unknown");
		JsonObject hint = new JsonObject();
		hint.add("melody_ranking", rank);
		hint.add("roles", Json.tree(roles));
		hint.add("rule", rule);
		o.add("track_hints", hint);
	}

	private static Object voiceDraft(ServerState s, Args a) {
		ReferenceMidi m = s.requireReference();
		List<Integer> tl = strictIntList(a, "tracks");
		if (tl == null || tl.isEmpty()) {
			throw Args.bad("tracks가 필요함(참조 트랙 번호 1개 이상)", "argument", "tracks");
		}
		Set<Integer> tracks = trackSet(m, tl);
		Range rg = range(m, a);
		int from = rg.from();
		int to = rg.to();
		int voices = strictInt(a, "voices", VoiceDraft.MAX_VOICES);
		if (voices < 1 || voices > VoiceDraft.MAX_VOICES) {
			throw Args.bad("voices는 1~" + VoiceDraft.MAX_VOICES, "voices", voices);
		}
		List<Integer> homes = strictIntList(a, "home_pitches");
		if (homes == null) {
			homes = VoiceDraft.defaultHomes(voices);
		} else {
			if (homes.size() != voices) {
				throw Args.bad("home_pitches 길이(" + homes.size() + ")가 voices(" + voices + ")와 다름", "home_pitches", homes,
						"voices", voices);
			}
			for (int h : homes) {
				if (h < 0 || h > 127) {
					throw Args.bad("home_pitches는 MIDI 0~127", "home_pitches", homes);
				}
			}
		}
		int maxNotes = strictInt(a, "max_notes", DEFAULT_MAX_NOTES);
		if (maxNotes < 1 || maxNotes > MAX_MAX) {
			throw Args.bad("max_notes는 1~" + MAX_MAX, "max_notes", maxNotes);
		}
		boolean usePedal = a.optBool("use_pedal", false);
		List<Note> sel = new ArrayList<>();
		for (Note n : m.notesAll(from, to)) {
			if (tracks.contains(n.track())) {
				sel.add(n);
			}
		}
		VoiceDraft.Result r = VoiceDraft.draft(sel, m.measureMap96(), voices, homes, maxNotes, usePedal);
		JsonObject o = new JsonObject();
		o.add("range", rangeJson(from, to));
		o.add("note", Json.tree(rg.note()));
		o.add("tracks", Json.tree(tracks));
		o.addProperty("voices_requested", voices);
		o.add("home_pitches", Json.tree(r.homePitches()));
		o.addProperty("pitch_mode_hint", "sounding");
		o.addProperty("length_source", r.lengthSource());
		o.addProperty("summary_text", r.summaryText());
		JsonArray vs = new JsonArray();
		int total = 0;
		for (VoiceDraft.Voice v : r.voices()) {
			JsonObject vj = new JsonObject();
			vj.addProperty("index", v.index());
			vj.addProperty("part_suggestion", v.partSuggestion());
			vj.addProperty("count", v.notes().size());
			JsonArray ns = new JsonArray();
			JsonArray pos = new JsonArray();
			JsonArray src = new JsonArray();
			Integer lo = null;
			Integer hi = null;
			for (VoiceDraft.DraftNote d : v.notes()) {
				JsonObject n = new JsonObject();
				n.addProperty("tick", d.tick());
				n.addProperty("len", d.len());
				n.addProperty("pitch", d.pitch());
				n.addProperty("vel", d.vel());
				ns.add(n);
				pos.add(d.pos());
				src.add(d.track());
				lo = lo == null ? d.pitch() : Math.min(lo, d.pitch());
				hi = hi == null ? d.pitch() : Math.max(hi, d.pitch());
			}
			vj.add("lowest", Json.tree(lo));
			vj.add("highest", Json.tree(hi));
			vj.add("notes", ns);
			vj.add("positions", pos);
			vj.add("source_tracks", src);
			total += v.notes().size();
			vs.add(vj);
		}
		o.addProperty("total", total);
		o.add("voices", vs);
		o.addProperty("dropped_count", r.dropped().size());
		o.add("dropped", Json.tree(r.dropped()));
		o.addProperty("trimmed", r.trimmed());
		o.addProperty("truncated", r.truncated());
		o.add("truncated_from_tick", Json.tree(r.truncatedFromTick()));
		o.addProperty("groups", r.groups());
		o.addProperty("input_notes", r.inputNotes());
		o.addProperty("skipped_drum", r.skippedDrum());
		o.addProperty("skipped_zero_length", r.skippedZeroLength());
		o.addProperty("note_format", "voices[].notes = {tick, len, pitch, vel}: 4분음표 = 96틱, pitch는 들리는 MIDI 번호, "
				+ "vel = round(velocity/127×15)(1~15). score_put_notes에 pitch_mode: \"sounding\"으로 넣는다. "
				+ "positions[i]는 notes[i]의 \"마디:박:틱\"(참조 기준), source_tracks[i]는 원래 참조 트랙");
		return o;
	}
}
