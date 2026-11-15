package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;
import static mabibridge.mcp.ToolRegistry.stringEnum;
import static mabibridge.mcp.ToolRegistry.typed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.EngineException;
import mabibridge.midi.Cleanup;
import mabibridge.midi.MeasureMap;
import mabibridge.midi.Note;
import mabibridge.midi.ReferenceMidi;
import mabibridge.midi.ReferenceMidiException;
import mabibridge.midi.ReferenceRewrite;
import mabibridge.midi.Regrid;

/**
 * 참조 MIDI 다시 놓기·정리: reference_regrid, reference_cleanup.
 *
 * <p>원본(reference_load가 읽은 바이트와 그 해석)은 {@link ServerState.ReferenceOrigin}에 그대로 남는다.
 * 다시 놓기는 언제나 원본에서 새로 만들고(앞서 한 정리는 버림), 정리는 지금 참조(다시 놓은 것이든 원본이든)의 한
 * 트랙을 바꾼 새 참조를 만든다. {@code reference_regrid {"reset": true}}는 원본으로 되돌린다.</p>
 */
final class ReferenceRegridTools {

	static final String REFERENCE_REWRITE_FAILED = "REFERENCE_REWRITE_FAILED";
	private static final Pattern SIG = Pattern.compile("^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*$");

	private ReferenceRegridTools() {}

	static void register(ToolRegistry r) {
		r.add("reference_regrid",
				"참조 MIDI의 음을 MIDI 자신의 템포 지도로 잰 벽시계 시각(초)에서 고정 격자(bpm, first_downbeat_sec = "
						+ "measure_offset 마디 시작, 4분음표 = 96)로 다시 놓고 템포 지도를 버린다(AI 전사 MIDI처럼 템포 이벤트가 "
						+ "오디오를 따르지 않을 때). offset_sec: 오디오 시각 = MIDI 시각 + offset_sec(예: 렌더-원곡 정렬값). "
						+ "음은 양자화하지 않는다(reference_cleanup). 프로그램·CC·트랙 이름 등도 같은 식으로 옮긴다. 원본은 남고 "
						+ "reference_info의 derived에 regridded: true와 값이 보인다. 언제나 원본에서 다시 만든다(앞선 "
						+ "reference_cleanup은 버림). reset: true면 원본으로 되돌린다. 응답 stats: 음 시작의 16분 격자 거리"
						+ "(전: 자기 격자, 후: 새 격자; 중앙값·p90·±6틱 안 %, 트랙별), 음 위치 흔들림·마디줄 흔들림(원본 마디줄을 "
						+ "새 격자에 놓았을 때 자기 자리와의 차에서 일정한 차를 뺀 것; 다시 놓은 뒤는 0).",
				props()
						.add("bpm", number("고정 템포(4분음표/분, 20~400). reset이 아니면 필수"))
						.add("first_downbeat_sec", number("measure_offset 마디가 시작하는 오디오 시각(초, 0 이상). reset이 아니면 필수"))
						.add("time_signature", string("박자 \"4/4\"(기본)"))
						.add("measure_offset", integer("first_downbeat_sec에 오는 마디 번호(기본 1)", 1, 10000))
						.add("offset_sec", number("오디오 시각 = MIDI 시각 + offset_sec(기본 0, -60~60)"))
						.add("source", stringEnum("시각의 출처(지금은 wallclock만: MIDI 템포 지도로 잰 초)", "wallclock"))
						.add("reset", bool("true면 원본 참조로 되돌린다(다른 인자는 주지 않음)"))
						.object(),
				ReferenceRegridTools::regrid);

		r.add("reference_cleanup",
				"참조 MIDI 한 트랙의 음표 정리(다른 트랙·이벤트는 그대로, 원본은 남음). 순서: quantize 16|32(시작·끝을 "
						+ "칸에) → fold_to_range [lo, hi](옥타브 접기) → 같은 음높이 중복(시작 차 ≤ dedupe_ticks, 기본 0)은 하나로, "
						+ "겹침은 앞 음을 자름 → top_line(단선율 윗줄) 또는 max_polyphony N(시작마다 높은 N개) → min_length_ticks보다 "
						+ "짧은 음 버림. 단계별 개수, 전후 음 수·최대 동시 음 수. dry_run: true면 개수만 보고 바꾸지 않는다. "
						+ "reference_info의 derived.cleanups에 기록.",
				props()
						.add("track", integer("정리할 참조 트랙 번호(1부터)", 1, null))
						.add("quantize", integer("16 또는 32(칸 24|12틱). 생략하면 양자화 안 함", 16, 32))
						.add("dedupe_ticks", integer("같은 음높이 시작 차가 이 틱 이하면 중복(기본 0 = 같은 시작만)", 0, 96))
						.add("min_length_ticks", integer("이보다 짧은 음 버림(기본 0)", 0, 384))
						.add("max_polyphony", integer("시작 틱마다 울리는 음을 높은 N개로(1~16). top_line과 함께 못 씀", 1, 16))
						.add("top_line", bool("true면 윗줄 하나만 남김(기본 false)"))
						.add("fold_to_range", intArray("[낮은 MIDI, 높은 MIDI](11 이상 차이): 밖의 음을 옥타브씩 옮김"))
						.add("dry_run", bool("true면 개수만(기본 false)"))
						.object("track"),
				ReferenceRegridTools::cleanup);
	}

	static JsonObject number(String description) {
		return typed("number", description);
	}

	// ------------------------------------------------------------------ 인자

	static Double optNum(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return new BigDecimal(e.getAsString()).doubleValue();
		}
		throw Args.bad(k + "는 숫자(JSON 수, 문자열 불가)여야 함: " + e, "argument", k, "value", e.toString());
	}

	static double reqNum(Args a, String k, double lo, double hi) {
		Double v = optNum(a, k);
		if (v == null) {
			throw Args.bad("인자 " + k + "가 필요함(숫자)", "argument", k);
		}
		return range(k, v, lo, hi);
	}

	static double range(String k, double v, double lo, double hi) {
		if (!(v >= lo && v <= hi)) {
			throw Args.bad(k + "는 " + lo + "~" + hi + ": " + v, "argument", k, "value", v);
		}
		return v;
	}

	static int[] timeSignature(Args a) {
		String s = a.optString("time_signature");
		if (s == null) {
			return new int[] { 4, 4 };
		}
		Matcher m = SIG.matcher(s);
		if (m.matches()) {
			int n = Integer.parseInt(m.group(1));
			int d = Integer.parseInt(m.group(2));
			if (n >= 1 && n <= 32 && (d == 1 || d == 2 || d == 4 || d == 8 || d == 16 || d == 32)) {
				return new int[] { n, d };
			}
		}
		throw Args.bad("time_signature는 \"분자/분모\"(분자 1~32, 분모 1·2·4·8·16·32): " + s, "argument", "time_signature",
				"value", s);
	}

	// ------------------------------------------------------------------ reference_regrid

	private static Object regrid(ServerState s, Args a) {
		s.requireReference();
		ServerState.ReferenceOrigin origin = s.referenceOrigin();
		if (origin == null) {
			throw new EngineException(EngineException.NO_REFERENCE, "원본 참조 바이트가 없음. reference_load로 다시 열 것");
		}
		boolean reset = a.optBool("reset", false);
		if (reset) {
			for (String k : List.of("bpm", "first_downbeat_sec", "time_signature", "measure_offset", "offset_sec",
					"source")) {
				if (a.has(k)) {
					throw Args.bad("reset: true에는 다른 인자를 주지 않는다: " + k, "argument", k);
				}
			}
			int dropped = s.referenceCleanups().size();
			boolean was = s.referenceRegrid() != null;
			s.restoreReferenceOrigin();
			JsonObject o = new JsonObject();
			o.addProperty("reset", true);
			o.addProperty("was_regridded", was);
			o.addProperty("discarded_cleanups", dropped);
			o.add("derived", derived(s));
			o.addProperty("measure_count", s.requireReference().measureCount());
			return o;
		}
		double bpm = reqNum(a, "bpm", 20, 400);
		double first = reqNum(a, "first_downbeat_sec", 0, 36000);
		int[] ts = timeSignature(a);
		int mo = ReferenceTools.strictInt(a, "measure_offset", 1);
		if (mo < 1 || mo > 10000) {
			throw Args.bad("measure_offset는 1~10000", "measure_offset", mo);
		}
		Double off = optNum(a, "offset_sec");
		double offset = off == null ? 0.0 : range("offset_sec", off, -60, 60);
		String src = a.optString("source");
		if (src != null && !src.equals("wallclock")) {
			throw Args.bad("source는 \"wallclock\"만", "argument", "source", "value", src);
		}
		Regrid.Params p = new Regrid.Params(bpm, first, ts[0], ts[1], mo, offset);
		ReferenceMidi raw = origin.reference();
		Regrid.Output out;
		ReferenceMidi ref;
		try {
			out = Regrid.run(raw, origin.bytes(), p);
			ref = ReferenceMidi.load(raw.file(), out.bytes());
		} catch (ReferenceMidiException e) {
			throw new EngineException(REFERENCE_REWRITE_FAILED, e.getMessage(), Map.<String, Object>of("path", String.valueOf(raw.file())),
					e);
		}
		int discarded = s.referenceCleanups().size();
		JsonObject params = new JsonObject();
		params.addProperty("bpm", bpm);
		params.addProperty("first_downbeat_sec", first);
		params.addProperty("time_signature", ts[0] + "/" + ts[1]);
		params.addProperty("measure_offset", mo);
		params.addProperty("offset_sec", offset);
		params.addProperty("source", "wallclock");
		JsonObject info = new JsonObject();
		info.add("params", params);
		info.addProperty("notes_in", out.notesIn());
		info.addProperty("notes_written", out.notesWritten());
		info.addProperty("dropped_before_start", out.droppedBeforeStart());
		info.addProperty("snapped_to_zero", out.snappedToZero());
		info.addProperty("overlaps_trimmed", out.overlapsTrimmed());
		info.addProperty("source_tempo_events", raw.tempos().size());
		info.addProperty("source_measure_count", raw.measureCount());
		info.addProperty("measure_count", ref.measureCount());
		s.setDerivedReference(ref, out.bytes(), info);
		JsonObject o = info.deepCopy();
		o.addProperty("regridded", true);
		o.addProperty("discarded_cleanups", discarded);
		o.add("stats", Json.tree(out.stats()));
		o.add("tracks", trackCounts(ref));
		o.addProperty("text", text(info, out.stats()));
		return o;
	}

	@SuppressWarnings("unchecked")
	private static String text(JsonObject info, Map<String, Object> st) {
		Map<String, Object> on = (Map<String, Object>) st.get("onset_offset_from_16th");
		Map<String, Object> b = (Map<String, Object>) ((Map<String, Object>) st.get("barline_wander")).get("wander_before");
		Map<String, Object> bb = (Map<String, Object>) on.get("before_own_grid");
		Map<String, Object> aa = (Map<String, Object>) on.get("after_fixed_grid");
		return "음 " + info.get("notes_written") + "개를 " + info.getAsJsonObject("params").get("bpm") + " BPM 고정 격자로("
				+ info.get("source_tempo_events") + "개 템포 이벤트 버림, 앞에서 버린 음 " + info.get("dropped_before_start")
				+ "개). 원본 마디줄 흔들림 중앙값 " + b.get("median") + "틱 / p90 " + b.get("p90") + "틱 / 최대 " + b.get("max")
				+ "틱 → 0. 음 시작의 16분 격자 거리 중앙값 " + bb.get("median") + " → " + aa.get("median") + "틱, p90 "
				+ bb.get("p90") + " → " + aa.get("p90") + "틱, ±6틱 안 " + on.get("within_6_ticks_pct_before") + "% → "
				+ on.get("within_6_ticks_pct_after") + "%";
	}

	static JsonArray trackCounts(ReferenceMidi m) {
		JsonArray a = new JsonArray();
		for (var t : m.tracks()) {
			JsonObject o = new JsonObject();
			o.addProperty("number", t.number());
			o.addProperty("name", t.name());
			o.addProperty("note_count", t.noteCount());
			o.addProperty("drum", t.drum());
			a.add(o);
		}
		return a;
	}

	// ------------------------------------------------------------------ reference_cleanup

	private static Object cleanup(ServerState s, Args a) {
		ReferenceMidi cur = s.requireReference();
		if (s.referenceOrigin() == null) {
			throw new EngineException(EngineException.NO_REFERENCE, "원본 참조 바이트가 없음. reference_load로 다시 열 것");
		}
		int track = ReferenceTools.strictReqInt(a, "track");
		if (track < 1 || track > cur.trackCount()) {
			throw Args.bad("참조 트랙 " + track + "번이 없음(1~" + cur.trackCount() + ")", "track", track, "track_count",
					cur.trackCount());
		}
		Integer q = a.has("quantize") ? ReferenceTools.strictInt(a, "quantize", 16) : null;
		if (q != null && q != 16 && q != 32) {
			throw Args.bad("quantize는 16 또는 32", "quantize", q);
		}
		int dedupe = ReferenceTools.strictInt(a, "dedupe_ticks", 0);
		if (dedupe < 0 || dedupe > 96) {
			throw Args.bad("dedupe_ticks는 0~96", "dedupe_ticks", dedupe);
		}
		int minLen = ReferenceTools.strictInt(a, "min_length_ticks", 0);
		if (minLen < 0 || minLen > 384) {
			throw Args.bad("min_length_ticks는 0~384", "min_length_ticks", minLen);
		}
		Integer poly = a.has("max_polyphony") ? ReferenceTools.strictInt(a, "max_polyphony", 1) : null;
		if (poly != null && (poly < 1 || poly > 16)) {
			throw Args.bad("max_polyphony는 1~16", "max_polyphony", poly);
		}
		boolean top = a.optBool("top_line", false);
		if (top && poly != null) {
			throw Args.bad("top_line과 max_polyphony는 함께 쓸 수 없음", "argument", "max_polyphony");
		}
		List<Integer> fold = ReferenceTools.strictIntList(a, "fold_to_range");
		Integer lo = null;
		Integer hi = null;
		if (fold != null) {
			if (fold.size() != 2 || fold.get(0) < 0 || fold.get(1) > 127 || fold.get(1) - fold.get(0) < 11) {
				throw Args.bad("fold_to_range는 [낮은 MIDI, 높은 MIDI](0~127, 11 이상 차이)", "fold_to_range", fold);
			}
			lo = fold.get(0);
			hi = fold.get(1);
		}
		boolean dry = a.optBool("dry_run", false);
		List<Note> in = new ArrayList<>();
		for (Note n : cur.notesAll()) {
			if (n.track() == track) {
				in.add(n);
			}
		}
		Cleanup.Options opt = new Cleanup.Options(q, dedupe, minLen, poly, top, lo, hi);
		Cleanup.Outcome res = Cleanup.run(in, opt);
		List<ReferenceRewrite.NoteOut> before = new ArrayList<>();
		for (Note n : in) {
			before.add(new ReferenceRewrite.NoteOut(n.channel(), n.pitch(), n.velocity(), n.tick96(), Math.max(1, n.len96())));
		}
		JsonObject options = new JsonObject();
		options.addProperty("quantize", q);
		options.addProperty("dedupe_ticks", dedupe);
		options.addProperty("min_length_ticks", minLen);
		options.addProperty("max_polyphony", poly);
		options.addProperty("top_line", top);
		options.add("fold_to_range", fold == null ? null : Json.tree(fold));
		JsonObject o = new JsonObject();
		o.addProperty("track", track);
		o.addProperty("name", cur.track(track).name());
		o.addProperty("dry_run", dry);
		o.add("options", options);
		o.add("counts", Json.tree(res.counts()));
		o.addProperty("max_simultaneous_before", Cleanup.maxSimultaneous(before));
		o.addProperty("max_simultaneous_after", Cleanup.maxSimultaneous(res.notes()));
		if (dry) {
			o.addProperty("applied", false);
			o.add("derived", derived(s));
			return o;
		}
		int res96 = cur.resolution();
		Map<Integer, List<ReferenceRewrite.NoteOut>> by = new LinkedHashMap<>();
		for (Note n : cur.notesAll()) {
			if (n.track() != track) {
				by.computeIfAbsent(n.track(), k -> new ArrayList<>()).add(new ReferenceRewrite.NoteOut(n.channel(),
						n.pitch(), n.velocity(), n.tick96(), Math.max(1, n.len96())));
			}
		}
		by.put(track, res.notes());
		ReferenceMidi ref;
		byte[] bytes;
		try {
			ReferenceRewrite.Result rw = ReferenceRewrite.write(s.referenceBytes(),
					x -> MeasureMap.convTick96(x, res96), null, by);
			bytes = rw.bytes();
			ref = ReferenceMidi.load(cur.file(), bytes);
		} catch (ReferenceMidiException e) {
			throw new EngineException(REFERENCE_REWRITE_FAILED, e.getMessage(), Map.<String, Object>of("path", String.valueOf(cur.file())),
					e);
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("track", track);
		entry.add("options", options);
		entry.add("counts", Json.tree(res.counts()));
		s.addReferenceCleanup(ref, bytes, entry);
		o.addProperty("applied", true);
		o.add("derived", derived(s));
		return o;
	}

	// ------------------------------------------------------------------ reference_info

	/** reference_info·reference_load에 붙는 derived 블록. */
	static JsonObject derived(ServerState s) {
		JsonObject d = new JsonObject();
		ServerState.ReferenceOrigin origin = s.referenceOrigin();
		d.addProperty("regridded", s.referenceRegrid() != null);
		d.add("regrid", s.referenceRegrid() == null ? null : s.referenceRegrid().deepCopy());
		JsonArray c = new JsonArray();
		for (JsonObject x : s.referenceCleanups()) {
			c.add(x.deepCopy());
		}
		d.add("cleanups", c);
		d.addProperty("raw_kept", origin != null);
		if (origin != null) {
			ReferenceMidi raw = origin.reference();
			JsonObject r = new JsonObject();
			r.addProperty("file", String.valueOf(raw.file()));
			r.addProperty("resolution", raw.resolution());
			r.addProperty("tempo_events", raw.tempos().size());
			r.addProperty("measure_count", raw.measureCount());
			r.addProperty("seconds", raw.seconds());
			r.addProperty("restore", "reference_regrid {\"reset\": true}");
			d.add("raw", r);
		}
		return d;
	}
}
