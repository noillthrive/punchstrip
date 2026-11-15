package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.notesArray;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.EditResult;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.NoteView;
import mabibridge.engine.Project;

/** 악보 편집·MML·글자 수 도구. */
final class ScoreTools {

	static final String[] PARTS = { "melody", "chord1", "chord2", "song" };
	/** score_notes의 "all"(있는 파트 모두). */
	static final String PART_ALL = "all";
	static final String[] PARTS_ALL = { "melody", "chord1", "chord2", "song", PART_ALL };

	private ScoreTools() {}

	private static JsonObject track() {
		return integer("트랙 번호(1부터)", 1, null);
	}

	private static JsonObject part() {
		return stringEnum("파트: melody(멜로디), chord1(화음1), chord2(화음2), song(노래)", PARTS);
	}

	static void register(ToolRegistry r) {
		r.add("score_notes",
				"악보 한 트랙·한 파트의 음표를 마디 범위로 본다(시작 틱이 범위 안인 음표). part \"all\"이면 그 트랙에 있는 파트 모두를 "
						+ "{\"parts\": {\"melody\": [...], \"chord1\": [...], …}}로(노래 파트는 노래 악기가 있을 때만).",
				props()
						.add("track", track())
						.add("part", stringEnum("파트: melody(멜로디), chord1(화음1), chord2(화음2), song(노래), all(있는 파트 모두)",
								PARTS_ALL))
						.add("from_measure", integer("시작 마디(1부터)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.object("track", "part", "from_measure", "to_measure"),
				ScoreTools::notes);

		r.add("score_put_notes",
				"한 파트에 음표를 넣는다. strict(기본)는 기존 음표와 하나라도 겹치면 아무것도 넣지 않고 OVERLAP을 돌려준다. "
						+ "merge는 엔진 규칙대로 겹친 기존 음표를 자르거나 지우고 그 목록을 affected로 알려 준다. 한 파트는 한 번에 한 음만 낸다.",
				props()
						.add("track", track())
						.add("part", part())
						.add("notes", notesArray("넣을 음표들"))
						.add("mode", stringEnum("겹침 처리: strict(기본) 또는 merge", "strict", "merge"))
						.add("align", bool("tick·len이 격자(6의 배수, 또는 셋잇단 격자 16의 배수 — tick은 마디 시작 기준) 밖이면 가장 가까운 6의 배수로 맞출지(기본 false = 거부)"))
						.add("pitch_mode", pitchMode())
						.add("timbre", timbre())
						.add("dry_run", bool("true면 넣어 본 결과(stats의 칸별 글자 수, chars_before, rank_limits, 경고)만 보고 악보는 그대로 둔다"
								+ "(기본 false, 스냅샷 없음)"))
						.add("rank", FitTools.rankArg())
						.add("song_rule", FitTools.songRuleArg())
						.object("track", "part", "notes"),
				ScoreTools::put);

		r.add("score_delete_notes",
				"한 파트의 음표를 지운다. 마디 범위(from_measure~to_measure, 시작 틱 기준) 또는 시작 틱 목록(ticks) 중 하나로 고른다.",
				props()
						.add("track", track())
						.add("part", part())
						.add("from_measure", integer("시작 마디(ticks와 함께 쓰지 않음)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.add("ticks", intArray("지울 음표의 시작 틱들(MML 틱)"))
						.object("track", "part"),
				ScoreTools::delete);

		r.add("score_replace_range",
				"한 트랙의 마디 범위에서, parts에 준 파트만 비우고 새 음표로 채운다(한 번에 생성). 범위 밖에 걸치는 음표가 있으면 "
						+ "아무것도 바꾸지 않고 OUT_OF_RANGE. 편곡을 고칠 때 가장 많이 쓰는 도구.",
				props()
						.add("track", track())
						.add("from_measure", integer("시작 마디(1부터)", 1, null))
						.add("to_measure", integer("끝 마디(포함)", 1, null))
						.add("parts", props()
								.add("melody", notesArray("멜로디 파트 새 음표(빈 배열이면 비우기만)"))
								.add("chord1", notesArray("화음1 파트 새 음표"))
								.add("chord2", notesArray("화음2 파트 새 음표"))
								.add("song", notesArray("노래 파트 새 음표"))
								.object())
						.add("align", bool("격자(6의 배수 또는 셋잇단 16의 배수) 밖의 tick·len을 6의 배수로 맞출지(기본 false = 거부)"))
						.add("pitch_mode", pitchMode())
						.add("timbre", timbre())
						.object("track", "from_measure", "to_measure", "parts"),
				ScoreTools::replace);
		describe(r, "score_replace_range", "parts", "바꿀 파트 → 음표 배열. 적지 않은 파트는 그대로 둔다");

		r.add("score_copy_range",
				"마디 범위(from_measure~to_measure, 포함)를 dest_measure로 복사한다(트랙 모두 또는 tracks, 파트 모두). transpose 반음"
						+ "(드럼·타악기 D/P/M은 옮기지 않음; 옮긴 음이 악기 음역 밖이면 out_of_range \"drop\"(기본)은 빼고 \"fold\"는 옥타브로 "
						+ "접음 — 트랙마다 dropped·folded), velocity_delta(0~15로 자름). mode replace(기본: 대상 범위를 먼저 비움, "
						+ "score_replace_range와 같은 규칙)·merge(비우지 않고 엔진 규칙으로 넣음, merge_conflicts). 원본과 대상 마디 길이(박자)가 "
						+ "같아야 한다. 템포는 복사하지 않음. 바꾸기 전에 스냅샷을 자동으로 남기고 snapshot_id를 준다(restore로 되돌림). "
						+ "dry_run: true면 바꾼 결과를 세기만 하고 되돌림(스냅샷 없음). 응답: 트랙마다 copied·copied_by_part·dropped·folded·"
						+ "velocity_clamped·truncated_at_end·cleared·truncated_before·merge_conflicts·chars_before/after·rank_limits"
						+ "(rank·song_rule, score_stats와 같음), warnings(rank_over·unison_clash·dropped_out_of_range·tempo_not_copied).",
				props()
						.add("from_measure", integer("원본 시작 마디(1부터)", 1, null))
						.add("to_measure", integer("원본 끝 마디(포함)", 1, null))
						.add("dest_measure", integer("대상 시작 마디(1부터)", 1, null))
						.add("tracks", tracksArg())
						.add("transpose", integer("옮길 반음(−48~48, 기본 0). 드럼·타악기는 옮기지 않음", -48, 48))
						.add("velocity_delta", integer("세기에 더할 값(−15~15, 기본 0, 결과는 0~15로 자름)", -15, 15))
						.add("mode", stringEnum("replace(기본: 대상 범위를 비우고 복사) 또는 merge(비우지 않음)", "replace", "merge"))
						.add("out_of_range", stringEnum("옮긴 음이 음역 밖일 때: drop(기본, 뺌) 또는 fold(옥타브로 접음)", "drop", "fold"))
						.add("dry_run", bool("true면 바꾸지 않고 결과만 센다(기본 false)"))
						.add("rank", FitTools.rankArg())
						.add("song_rule", FitTools.songRuleArg())
						.object("from_measure", "to_measure", "dest_measure"),
				ScoreTools::copyRange);

		r.add("score_mml",
				"마비노기용 MML(\"MML@멜로디,화음1,화음2[,노래];\")과 파트별 문자열·글자 수를 본다. track을 생략하면 모든 트랙.",
				props().add("track", track()).object(),
				(s, a) -> {
					Integer t = a.optInt("track");
					if (t != null) {
						return s.project().mml(t).get(0);
					}
					JsonObject o = new JsonObject();
					o.add("tracks", Json.tree(s.project().mml(null)));
					return o;
				});

		r.add("score_stats",
				"트랙의 글자 수와 두 기준(노래 제외 / 노래 포함)의 한도·초과 파트, 랭크, 연주 시간을 본다. track을 생략하면 모든 트랙. "
						+ "rank_limits = 고른 랭크(rank, 기본 \"1\")·기준(song_rule, 기본 트랙 기준)에서 파트마다 chars·limit·over_by. "
						+ "warnings = unison_clash(한 트랙의 다른 파트끼리 같은 음이 겹침 — 게임에서는 뒤 음이 앞 음을 끊음), tempo_mid_note"
						+ "(템포 변경이 음 울림 중간). 응답 맨 위 score_warnings = 악보 전체 검사 tempo_end_differs(마지막 템포 ≠ 처음 템포).",
				props().add("track", track()).add("rank", FitTools.rankArg()).add("song_rule", FitTools.songRuleArg()).object(),
				(s, a) -> {
					Integer t = a.optInt("track");
					String rank = FitTools.rank(a);
					String rule = a.optString("song_rule");
					if (t != null) {
						JsonObject one = FitTools.statsJson(s.project(), t, rank, rule);
						one.add("score_warnings", Json.tree(s.project().scoreWarnings()));
						return one;
					}
					com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
					for (int i = 1; i <= s.project().trackCount(); i++) {
						arr.add(FitTools.statsJson(s.project(), i, rank, rule));
					}
					JsonObject o = new JsonObject();
					o.add("tracks", arr);
					o.add("score_warnings", Json.tree(s.project().scoreWarnings()));
					return o;
				});
	}

	private static JsonObject pitchMode() {
		return stringEnum("음표 pitch의 뜻. \"written\"(기본): 악보에 적을 음 그대로. \"sounding\": 실제로 들릴 음 — 서버가 트랙 악기"
				+ "(노래 파트는 노래 악기)의 표로 적을 음으로 바꿔 넣는다(피아노는 1옥타브, 바이올린은 2옥타브 낮춰 적음 등). "
				+ "원곡 음높이를 옮겨 적을 때는 sounding을 쓴다. 낼 수 없는 음이면 PITCH_NOT_PRODUCIBLE", "written", "sounding");
	}

	private static JsonObject timbre() {
		return stringEnum("pitch_mode sounding에서 구간 악기(전자 기타 20·가야금 28·베이스 29)의 음색 세트. \"default\"(기본, "
				+ "기타·가야금은 맑은 음 = 적은 음 D#4 이하, 베이스는 G3 이하) 또는 \"alt\"(기타·가야금 튜닝음 = E4 이상, 베이스 D4 이상). "
				+ "두 세트는 같은 들리는 음역을 덮는다", "default", "alt");
	}

	/** pitch_mode·timbre 인자. 잘못 주면 BAD_ARGUMENT. */
	private static String[] pitchArgs(Args a) {
		String mode = a.optString("pitch_mode");
		String timbre = a.optString("timbre");
		if (mode == null) {
			mode = "written";
		}
		if (!"written".equals(mode) && !"sounding".equals(mode)) {
			throw Args.bad("pitch_mode는 \"written\" 또는 \"sounding\": " + mode, "pitch_mode", mode);
		}
		if (timbre != null && !"sounding".equals(mode)) {
			throw Args.bad("timbre는 pitch_mode \"sounding\"일 때만 쓴다", "timbre", timbre);
		}
		return new String[] { mode, timbre };
	}

	/** sounding이면 들리는 음 → 적을 음. 음역을 DLS 기준으로 보려고 DLS를 한 번 읽어 본다(실패하면 properties 음역). */
	private static List<NoteSpec> convert(ServerState s, Project p, int track, String part, List<NoteSpec> notes,
			String[] pa) {
		if (!"sounding".equals(pa[0])) {
			return notes;
		}
		try {
			s.engine().ensureDls();
		} catch (mabibridge.engine.EngineException e) {
			System.err.println("[mcp] pitch_mode sounding: DLS를 읽지 못해 properties 음역으로 검사: " + e.getMessage());
		}
		return p.toWritten(track, part, notes, pa[1]);
	}

	private static void addPitchInfo(JsonObject o, Project p, int track, String part, String[] pa, int converted) {
		o.addProperty("pitch_mode", pa[0]);
		o.addProperty("pitch_converted", "sounding".equals(pa[0]) ? converted : 0);
		if ("sounding".equals(pa[0])) {
			o.addProperty("timbre", pa[1] == null ? "default" : pa[1]);
			o.addProperty("range_source", p.rangeSourceOf(track, part));
		}
	}

	/** 중첩 객체 스키마에 description을 붙인다. */
	private static void describe(ToolRegistry r, String tool, String prop, String description) {
		r.get(tool).inputSchema().getAsJsonObject("properties").getAsJsonObject(prop).addProperty("description",
				description);
	}

	private static Object notes(ServerState s, Args a) {
		int track = a.reqInt("track");
		String part = a.reqString("part");
		int from = a.reqInt("from_measure");
		int to = a.reqInt("to_measure");
		if (PART_ALL.equals(part)) {
			// 있는 파트 모두. 노래 파트는 노래 악기가 있을 때만(멜로디·화음은 언제나)
			List<String> keys = s.project().partsOf(track);
			JsonObject parts = new JsonObject();
			int count = 0;
			for (String k : keys) {
				List<NoteView> l = s.project().notes(track, k, from, to);
				count += l.size();
				parts.add(k, Json.tree(l));
			}
			JsonObject o = new JsonObject();
			o.addProperty("track", track);
			o.addProperty("part", PART_ALL);
			o.add("range", ReferenceTools.rangeJson(from, to));
			o.addProperty("count", count);
			o.add("parts", parts);
			return o;
		}
		List<NoteView> l = s.project().notes(track, part, from, to);
		JsonObject o = new JsonObject();
		o.addProperty("track", track);
		o.addProperty("part", part);
		o.add("range", ReferenceTools.rangeJson(from, to));
		o.addProperty("count", l.size());
		o.add("notes", Json.tree(l));
		return o;
	}

	private static Object put(ServerState s, Args a) {
		Project p = s.project();
		String part = a.reqString("part");
		int track = a.reqInt("track");
		String[] pa = pitchArgs(a);
		List<NoteSpec> notes = convert(s, p, track, part, Args.notes(a.reqArray("notes"), part), pa);
		String mode = a.optString("mode");
		boolean align = a.optBool("align", false);
		String rank = FitTools.rank(a);
		String rule = a.optString("song_rule");
		if (a.optBool("dry_run", false)) {
			// put, measure, put the score back (state bytes and dirty flag)
			Map<String, Integer> before = p.statsOf(track).chars();
			JsonObject o = p.dryRun(() -> {
				EditResult r = p.putNotes(track, part, notes, mode, align);
				JsonObject j = Json.tree(r).getAsJsonObject();
				j.add("rank_limits", Json.tree(p.rankCheck(track, rank, rule)));
				return j;
			});
			o.addProperty("dry_run", true);
			o.add("chars_before", Json.tree(before));
			addPitchInfo(o, p, track, part, pa, notes.size());
			return o;
		}
		EditResult r = p.putNotes(track, part, notes, mode, align);
		JsonObject o = editResponse(s, r);
		if (rank != null || rule != null) {
			o.add("rank_limits", Json.tree(p.rankCheck(track, rank, rule)));
		}
		addPitchInfo(o, p, track, part, pa, notes.size());
		return o;
	}

	private static Object delete(ServerState s, Args a) {
		Project p = s.project();
		int track = a.reqInt("track");
		String part = a.reqString("part");
		boolean byRange = a.has("from_measure") || a.has("to_measure");
		boolean byTicks = a.has("ticks");
		if (byRange == byTicks) {
			throw Args.bad("from_measure·to_measure 또는 ticks 중 하나만 줄 것");
		}
		EditResult r;
		if (byRange) {
			r = p.deleteNotes(track, part, a.reqInt("from_measure"), a.reqInt("to_measure"));
		} else {
			r = p.deleteNotes(track, part, a.optIntList("ticks"));
		}
		return editResponse(s, r);
	}

	private static Object replace(ServerState s, Args a) {
		Project p = s.project();
		JsonObject parts = a.reqObject("parts");
		Map<String, List<NoteSpec>> m = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : parts.entrySet()) {
			if (e.getValue() == null || e.getValue().isJsonNull()) {
				continue;
			}
			if (!e.getValue().isJsonArray()) {
				throw Args.bad("parts." + e.getKey() + "는 음표 배열이어야 함", "part", e.getKey());
			}
			m.put(e.getKey(), Args.notes(e.getValue().getAsJsonArray(), e.getKey()));
		}
		int track = a.reqInt("track");
		String[] pa = pitchArgs(a);
		int converted = 0;
		String firstPart = null;
		for (Map.Entry<String, List<NoteSpec>> e : m.entrySet()) {
			List<NoteSpec> c = convert(s, p, track, e.getKey(), e.getValue(), pa);
			converted += c.size();
			e.setValue(c);
			if (firstPart == null) {
				firstPart = e.getKey();
			}
		}
		EditResult r = p.replaceRange(track, a.reqInt("from_measure"), a.reqInt("to_measure"), m, a.optBool("align", false));
		JsonObject o = editResponse(s, r);
		addPitchInfo(o, p, track, firstPart == null ? "melody" : firstPart, pa, converted);
		return o;
	}

	private static JsonObject tracksArg() {
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		JsonObject o = new JsonObject();
		com.google.gson.JsonArray t = new com.google.gson.JsonArray();
		t.add("array");
		t.add("string");
		o.add("type", t);
		o.add("items", items);
		o.addProperty("description", "복사할 트랙 번호 목록(1부터) 또는 \"all\"(기본, 모든 트랙)");
		return o;
	}

	private static Object copyRange(ServerState s, Args a) {
		List<Integer> tracks = null;
		JsonElement te = a.raw("tracks");
		if (te != null && !te.isJsonNull()) {
			if (te.isJsonPrimitive() && te.getAsJsonPrimitive().isString()) {
				if (!"all".equals(te.getAsString())) {
					throw Args.bad("tracks는 정수 배열 또는 \"all\": " + te, "argument", "tracks", "value", te.toString());
				}
			} else {
				tracks = a.optIntList("tracks");
			}
		}
		Project p = s.project();
		mabibridge.engine.RangeCopy.Result r = p.copyRange(a.reqInt("from_measure"), a.reqInt("to_measure"),
				a.reqInt("dest_measure"), tracks, a.optInt("transpose", 0), a.optInt("velocity_delta", 0),
				a.optString("mode"), a.optString("out_of_range"), a.optBool("dry_run", false), FitTools.rank(a),
				a.optString("song_rule"));
		JsonObject o = Json.tree(r).getAsJsonObject();
		if (!r.dryRun()) {
			ServerState.CompareSummary c = s.compareSummary(r.changedMeasures());
			o.add("compare_summary", c.summary());
			if (c.note() == null) {
				o.add("compare_summary_note", com.google.gson.JsonNull.INSTANCE);
			} else {
				o.addProperty("compare_summary_note", c.note());
			}
		}
		return o;
	}

	/** 쓰기 도구 공통 응답: EditResult + compare_summary(연결 지점이 채운다). */
	static JsonObject editResponse(ServerState s, EditResult r) {
		JsonObject o = Json.tree(r).getAsJsonObject();
		ServerState.CompareSummary c = s.compareSummary(r.changedMeasures());
		o.add("compare_summary", c.summary());
		if (c.note() == null) {
			o.add("compare_summary_note", com.google.gson.JsonNull.INSTANCE);
		} else {
			o.addProperty("compare_summary_note", c.note());
		}
		return o;
	}
}
