package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.MmlImport;
import mabibridge.engine.Project;
import mabibridge.engine.TrackMerge;

/** {@code score_merge_tracks} and {@code track_import_mml} (tracks are removed / inserted, so mapping is renumbered). */
final class TrackTools {

	private TrackTools() {}

	static void register(ToolRegistry r) {
		JsonObject pair = new JsonObject();
		pair.addProperty("type", "array");
		pair.addProperty("description", "[트랙 번호, 파트 \"melody\"|\"chord1\"|\"chord2\"]");
		pair.add("items", ToolRegistry.intOrString("트랙 번호 또는 파트 이름"));
		pair.addProperty("minItems", 2);
		pair.addProperty("maxItems", 2);

		r.add("score_merge_tracks",
				"같은 악기(program) 트랙 2개 이상을 한 트랙(멜로디·화음1·화음2)으로 합친다. 노래 파트는 옮기지 않음(대상 트랙 것은 그대로). "
						+ "strategy by_voice(기본): 모든 음을 모아(같은 시작·같은 음은 하나로) 시작 틱마다 높은 음부터 비어 있는 성부(앞 음이 끝난 "
						+ "성부, melody → chord1 → chord2 순)에 넣는다. 빈 성부보다 음이 많으면 바깥 음(맨 위, 맨 아래, 둘째 위…)부터 남기고 "
						+ "안쪽 음을 뺀다(dropped). keep_parts: 원본 순서대로 음이 있는 파트를 대상의 다음 빈 파트로(3개 넘으면 BAD_ARGUMENT). "
						+ "priority: order [[트랙, 파트], …] 첫째 → melody, 둘째 → chord1, 셋째 → chord2, 넷째부터는 그 음 전체 길이가 비어 있는 "
						+ "첫 성부에, 없으면 뺌; 앞 쌍에 이미 넣은 음과 시작·음높이가 같은 음은 하나로(duplicates_merged); order에 없는 파트의 음은 뺌(not_in_order). max_len_ticks: 합치기 전에 음 길이 상한(6의 배수). "
						+ "한 성부 안 1~5틱 쉼표는 앞 음을 늘려 메움(gaps_closed). 원본 트랙은 지운다(keep_sources: true면 그대로 두고 대상만 바꿈) — "
						+ "뒤 트랙 번호가 당겨지고 compare 매핑도 따라 바뀐다(renumbered). 바꾸기 전에 스냅샷을 자동으로 남김(snapshot_id, restore로 "
						+ "되돌림). dry_run: true면 바꿔 세어 보고 되돌림(스냅샷 없음). 응답: result_track, parts{melody·chord1·chord2·song: notes, "
						+ "chars, limit, over_by}, fits, over, rank_limits(score_stats와 같음), mapping[{from_track, from_part, to_part, notes}], "
						+ "dropped_count, dropped_by_source, dropped(처음 20개: tick, pos, pitch, name, from_track, from_part, reason), "
						+ "duplicates_merged, length_capped, gaps_closed, removed_tracks, renumbered, sources, warnings.",
				props()
						.add("tracks", intArray("합칠 트랙 번호(1부터) 2개 이상. 순서가 keep_parts의 파트 순서"))
						.add("target", integer("결과를 담을 트랙(tracks 중 하나, 기본 첫째). 이름·음량·좌우·노래 파트는 이 트랙 것", 1, null))
						.add("strategy", stringEnum("by_voice(기본), keep_parts, priority", TrackMerge.BY_VOICE, TrackMerge.KEEP_PARTS,
								TrackMerge.PRIORITY))
						.add("order", ToolRegistry.array("strategy priority의 우선순위 [[트랙, 파트], …], 예 [[8, \"melody\"], [6, \"melody\"], "
								+ "[6, \"chord1\"]] — 첫째 → melody, 둘째 → chord1, 셋째 → chord2", pair))
						.add("rank", FitTools.rankArg())
						.add("song_rule", FitTools.songRuleArg())
						.add("dry_run", bool("true면 바꾸지 않고 결과만 센다(기본 false)"))
						.add("max_len_ticks", integer("합치기 전 음 길이 상한(틱, 6의 배수, 4분음표 = 96). 생략하면 그대로", 6, null))
						.add("keep_sources", bool("true면 원본 트랙(대상 말고)을 지우지 않고 그대로 둔다(기본 false)"))
						.object("tracks"),
				TrackTools::merge);

		r.add("track_import_mml",
				"MML 글 \"MML@멜로디,화음1,화음2[,노래];\"로 트랙을 더한다(MabiIcco 자신의 MML 해석기 MMLTrack.setMML, "
						+ "MML 가져오기 대화상자와 같음). 자브키엘 악보집은 sheets에 장 순서대로 — 파트마다 이어 붙여(MabiIcco 악보집 가져오기 "
						+ "MMLText.join과 같음) 뒤 장이 앞 장의 o·l·v·t 상태를 이어받는다. mml만, sheets만, 둘 다(mml이 1장) 모두 된다. "
						+ "공백·줄바꿈은 지운다. MML@로 시작하지 않거나, MML에 없는 글자가 있거나, 파트가 4개를 넘거나, 음이 하나도 없으면 "
						+ "BAD_ARGUMENT. 4번째(노래) 파트에 음이 있는데 악기가 노래 악기(V/C)가 아니고 song_program도 없으면 BAD_ARGUMENT. "
						+ "글의 t 명령은 악보 전역 템포 목록에 들어간다(MabiIcco와 같음 — tempo_changed, warnings tempo_merged). 더하기 전에 "
						+ "스냅샷을 자동으로 남김. 응답: track(새 번호), parts{notes, input_chars, chars(생성된 MML 글자 수)}, notes, rank_limits, "
						+ "fits, tempos(글 안 템포), score_tempos_before/after, parse_warnings(해석기가 무시하거나 건너뛴 토큰: part, sheet, index, token, "
						+ "message, 처음 30개)·parse_warning_count, sheet_chars, renumbered(insert_at 뒤 트랙), warnings.",
				props()
						.add("mml", string("\"MML@멜로디,화음1,화음2[,노래];\"(파트 1~4개). sheets와 함께 주면 1장"))
						.add("sheets", ToolRegistry.array("자브키엘 악보집의 장들(MML@…; 문자열, 장 순서대로, 64장까지)",
								string("한 장의 MML@…;")))
						.add("program", integer("악기 번호(instruments의 program)", 0, null))
						.add("name", string("트랙 이름(32자까지, 기본 Track<n>)"))
						.add("song_program", integer("노래 악기 번호. −1 = 코러스 없음(기본), −2 = 노래 제외, 그 밖은 코러스(C) 악기 100·110(MabiIcco처럼 노래·마이크 V는 받지 않음)", -2, null))
						.add("panpot", integer("좌우 위치 0~127(기본 64)", 0, 127))
						.add("volume", integer("음량 0~127(기본 100)", 0, 127))
						.add("insert_at", integer("새 트랙의 위치(1부터, 기본 맨 뒤). 그 자리 뒤 트랙 번호는 하나씩 밀림", 1, null))
						.add("rank", FitTools.rankArg())
						.add("song_rule", FitTools.songRuleArg())
						.object("program"),
				TrackTools::importMml);
	}

	private static Object merge(ServerState s, Args a) {
		List<TrackMerge.OrderEntry> order = null;
		JsonElement oe = a.raw("order");
		if (oe != null) {
			if (!oe.isJsonArray()) {
				throw Args.bad("order는 [[트랙, 파트], …] 배열", "argument", "order");
			}
			order = new ArrayList<>();
			int i = 0;
			for (JsonElement e : oe.getAsJsonArray()) {
				if (!e.isJsonArray() || e.getAsJsonArray().size() != 2) {
					throw Args.bad("order[" + i + "]는 [트랙 번호, 파트 이름] 두 칸 배열: " + e, "index", i, "entry", e.toString());
				}
				JsonArray p = e.getAsJsonArray();
				int track = Args.toInt(p.get(0), "order[" + i + "][0]");
				JsonElement pe = p.get(1);
				if (!pe.isJsonPrimitive() || !pe.getAsJsonPrimitive().isString()) {
					throw Args.bad("order[" + i + "][1]은 파트 이름(melody, chord1, chord2): " + pe, "index", i, "entry",
							e.toString());
				}
				order.add(new TrackMerge.OrderEntry(track, pe.getAsString()));
				i++;
			}
		}
		Project p = s.project();
		TrackMerge.Result r = p.mergeTracks(a.optIntList("tracks"), a.optInt("target"), a.optString("strategy"), order,
				FitTools.rank(a), a.optString("song_rule"), a.optBool("dry_run", false), a.optInt("max_len_ticks"),
				a.optBool("keep_sources", false));
		JsonObject o = Json.tree(r).getAsJsonObject();
		if (!r.dryRun() && !r.removedTracks().isEmpty()) {
			o.add("mapping_after", remapMapping(s, n -> {
				int shift = 0;
				for (int rm : r.removedTracks()) {
					if (rm == n) {
						return 0;
					}
					if (rm < n) {
						shift++;
					}
				}
				return n - shift;
			}));
		}
		return o;
	}

	private static Object importMml(ServerState s, Args a) {
		List<String> sheets = new ArrayList<>();
		String mml = a.optString("mml");
		if (mml != null) {
			sheets.add(mml);
		}
		JsonElement se = a.raw("sheets");
		if (se != null) {
			if (!se.isJsonArray()) {
				throw Args.bad("sheets는 MML 문자열 배열", "argument", "sheets");
			}
			int i = 0;
			for (JsonElement e : se.getAsJsonArray()) {
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
					throw Args.bad("sheets[" + i + "]는 MML 문자열", "index", i);
				}
				sheets.add(e.getAsString());
				i++;
			}
			if (i == 0) {
				throw Args.bad("sheets가 비어 있음", "argument", "sheets");
			}
		}
		if (sheets.isEmpty()) {
			throw Args.bad("mml 또는 sheets가 필요함(\"MML@멜로디,화음1,화음2[,노래];\")", "argument", "mml");
		}
		Project p = s.project();
		MmlImport.Result r = p.importMml(sheets, a.reqInt("program"), a.optString("name"), a.optInt("song_program"),
				a.optInt("panpot"), a.optInt("volume"), a.optInt("insert_at"), FitTools.rank(a), a.optString("song_rule"));
		JsonObject o = Json.tree(r).getAsJsonObject();
		if (!r.renumbered().isEmpty()) {
			int at = r.track();
			o.add("mapping_after", remapMapping(s, n -> n >= at ? n + 1 : n));
		}
		return o;
	}

	/** Renumbers the compare mapping keys (f returns 0 = the track was removed) and returns the new mapping JSON. */
	private static JsonObject remapMapping(ServerState s, java.util.function.IntUnaryOperator f) {
		Map<Integer, MappingEntry> old = s.mapping();
		if (old.isEmpty()) {
			return s.mappingJson();
		}
		Map<Integer, MappingEntry> m = new TreeMap<>();
		for (Map.Entry<Integer, MappingEntry> e : old.entrySet()) {
			int n = f.applyAsInt(e.getKey());
			if (n > 0) {
				m.put(n, e.getValue());
			}
		}
		s.setMapping(m);
		return s.mappingJson();
	}
}
