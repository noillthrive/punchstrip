package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.array;
import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import mabibridge.engine.CharLimits;
import mabibridge.engine.Project;
import mabibridge.engine.TransformOp;

/**
 * 글자 수 한도 맞추기 도구: score_transform(일괄 변환), score_fit_suggest(줄이는 방법 제안, 읽기만),
 * score_split_sheets(장 나누기, 열린 악보는 읽기만). score_stats의 rank 인자·warnings는 {@link ScoreTools}.
 */
final class FitTools {

	private FitTools() {}

	static JsonObject rankArg() {
		return ToolRegistry.intOrString("작곡 랭크(mmlTools ComposeRank의 이름): \"1\"(기본, 가장 높은 랭크)~\"9\", \"A\"~\"F\", 연습 랭크는 "
				+ "\"Practice\"(또는 \"연습\", 키 \"RANK_t\"). 키 \"RANK_1\" 꼴도 받는다. 숫자 랭크는 JSON 정수(1~9)로 줘도 된다");
	}

	/**
	 * rank 인자. 문자열은 그대로, JSON 정수(1~9 등)는 문자열로 바꾼다("rank": 1 → "1"). 그 밖(소수·불리언·배열)은
	 * BAD_ARGUMENT. 랭크 이름이 맞는지는 엔진이 본다.
	 */
	static String rank(Args a) {
		JsonElement e = a.raw("rank");
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return String.valueOf(Args.toInt(e, "rank"));
		}
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw Args.bad("rank는 정수(1~9) 또는 문자열(\"1\"~\"9\", \"A\"~\"F\", \"Practice\")이어야 함: " + e, "argument", "rank",
					"value", e.toString());
		}
		return e.getAsString();
	}

	static JsonObject songRuleArg() {
		return stringEnum("글자 수 판정 기준. 생략 = 트랙의 기본 기준(track_set의 song_rule, 없으면 노래·마이크·코러스 악기(타입 V/C, 노래 "
				+ "파트만 씀) 트랙은 song_included, 그 밖은 프로젝트 기준 — 기본 song_excluded). "
				+ "song_excluded: 각 파트 한도 + 멜로디/3, 노래 파트는 실을 수 없음. song_included: 랭크 표 그대로, 노래 한도 = 멜로디 한도",
				ProjectTools.SONG_RULES);
	}

	private static JsonObject partsArg(String what) {
		return array(what, stringEnum("파트", ScoreTools.PARTS));
	}

	static void register(ToolRegistry r) {
		r.add("score_transform",
				"한 트랙의 파트들(기본 모두)·마디 범위(기본 곡 전체)에 일괄 변환 하나를 적용한다(범위 안에서 시작하는 음만). 적용 전에 스냅샷을 "
						+ "자동으로 남기고 그 id를 돌려준다(restore로 되돌림). op: velocity_offset {delta −15~15}(세기 더하기, 0~15로 자름), "
						+ "velocity_quantize {step 2~15, keep_if_single_level 기본 true}(세기를 가장 가까운 step의 배수로, 같으면 낮은 쪽. 범위 안 세기가 하나뿐이면 그대로), velocity_min_change {delta 1~15}(앞 음과 세기 차이가 "
						+ "delta보다 작으면 앞 세기로 = v 명령 줄이기, delta 2면 ±1 변화가 없어짐), length_scale {ratio 0.1~4, min_len_ticks 6~384 "
						+ "(기본 6)}(음 길이에 ratio를 곱해 6틱 단위로 반올림, min_len 아래로 줄이지 않고 다음 음을 넘지 않음), min_note_len "
						+ "{ticks, mode delete|extend}(ticks보다 짧은 음을 지우거나, 다음 음까지 자리가 있으면 ticks로 늘림), length_grid {ticks}"
						+ "(음 끝을 마디 시작 기준 ticks 격자에 맞춤 = 붙임줄 줄이기, 다음 음을 넘지 않음), drop_below_vel {v 0~14}(세기 ≤ v인 음 "
						+ "지움). 틱 인자는 6의 배수. 응답: 바뀐 음 수, 파트별 글자 수 전/후(MabiIcco 생성기로 다시 만듦), snapshot_id.",
				props()
						.add("track", integer("트랙 번호(1부터)", 1, null))
						.add("parts", partsArg("바꿀 파트들(기본 모두)"))
						.add("from_measure", integer("시작 마디(기본 1)", 1, null))
						.add("to_measure", integer("끝 마디(포함, 기본 끝까지)", 1, null))
						.add("op", stringEnum("변환 이름", TransformOp.OPS.toArray(String[]::new)))
						.add("params", props().object())
						.object("track", "op"),
				FitTools::transform);
		describeOpen(r, "score_transform", "params",
				"op의 인자 객체. 예 {\"delta\": -2}, {\"step\": 2}, {\"ratio\": 0.75, \"min_len_ticks\": 12}, {\"ticks\": 12, \"mode\": \"delete\"}");

		r.add("score_fit_suggest",
				"글자 수 한도를 넘은 파트를 줄이는 방법을 제안한다(읽기만, 열린 악보는 바꾸지 않음). 넘은 파트마다 후보 사다리(velocity_min_change 2 → "
						+ "velocity_quantize 2 → length_grid 12 → min_note_len 12 delete → velocity_quantize 3 → drop_below_vel 2 → "
						+ "length_grid 24)를 사본에 하나씩 적용해 MML을 다시 만들어 보고 정확한 글자 수 전/후, 줄어든 글자, 바뀐 음 수, 그것만으로 한도에 "
						+ "드는지(fits_alone), 예시 위치 3개를 준다. plan은 사다리를 앞에서부터 누적 적용해 트랙 전체가 한도에 드는 가장 짧은 단계와 그대로 "
						+ "보낼 score_transform 인자(calls). 안 되면 remaining_over_by. 수치는 score_transform과 같은 구현이라 그대로 맞는다.",
				props()
						.add("track", integer("트랙 번호(1부터)", 1, null))
						.add("rank", rankArg())
						.add("song_rule", songRuleArg())
						.add("parts", partsArg("볼 파트들(기본 넘은 파트 모두)"))
						.object("track"),
				FitTools::fitSuggest);

		r.add("score_split_sheets",
				"한도를 넘는 트랙 하나를 여러 장(악보)으로 나눈다(열린 악보는 읽기만). 처음부터 욕심껏: 모든 파트가 한도 안인 가장 긴 온마디 범위를 찾고, "
						+ "그 끝에서 범위 길이의 절반까지 거슬러 가며 어떤 파트 음도 걸치지 않는 마디 경계를 고른다(없으면 걸친 음을 cut_notes로 알림). 장마다 "
						+ "0틱부터 다시 만든 사본에서 MML을 생성한다(자른 자리의 템포를 장 처음에 둠, t·v·o·l은 생성기가 다시 적음). out_dir를 주면 장마다 "
						+ "<name>_장N.mmi를 새로 쓴다(overwrite 기본 false).",
				props()
						.add("track", integer("트랙 번호(1부터)", 1, null))
						.add("rank", rankArg())
						.add("song_rule", songRuleArg())
						.add("max_sheets", integer("최대 장 수(기본 8). 모자라면 complete false와 remaining_from_measure", 1, 64))
						.add("carry", bool("걸친 음의 남은 길이를 다음 장 처음에 다시 칠지(기본 true). false면 앞 장에서 잘린 채 끝남"))
						.add("out_dir", string("장 .mmi를 쓸 폴더. 절대 경로 또는 서버 작업 폴더(설치 폴더) 기준 상대 경로(예 \"output/sheets\"). "
								+ "보호 폴더 안이면 PROTECTED_PATH. 생략 = 파일을 쓰지 않음"))
						.add("name", string("파일 이름 앞머리(경로 글자 불가). 기본 <제목>_t<트랙>"))
						.add("overwrite", bool("같은 이름 파일을 덮어쓸지(기본 false = FILE_EXISTS)"))
						.object("track"),
				FitTools::split);
	}

	/** 열린 객체(params): 어떤 키든 받는다(검증은 TransformOp). */
	private static void describeOpen(ToolRegistry r, String tool, String prop, String description) {
		JsonObject o = r.get(tool).inputSchema().getAsJsonObject("properties").getAsJsonObject(prop);
		o.addProperty("description", description);
		o.addProperty("additionalProperties", true);
	}

	private static Object transform(ServerState s, Args a) {
		Project p = s.project();
		Map<String, Object> params = new LinkedHashMap<>();
		JsonElement pe = a.raw("params");
		if (pe != null) {
			if (!pe.isJsonObject()) {
				throw Args.bad("params는 객체여야 함", "params", pe.toString());
			}
			for (Map.Entry<String, JsonElement> e : pe.getAsJsonObject().entrySet()) {
				params.put(e.getKey(), scalar(e.getKey(), e.getValue()));
			}
		}
		TransformOp op = TransformOp.of(a.reqString("op"), params);
		return p.transform(a.reqInt("track"), RenderTools.stringList(a, "parts"), a.optInt("from_measure"),
				a.optInt("to_measure"), op);
	}

	private static Object scalar(String k, JsonElement v) {
		if (v == null || v.isJsonNull()) {
			return null;
		}
		if (v.isJsonPrimitive()) {
			JsonPrimitive p = v.getAsJsonPrimitive();
			if (p.isNumber()) {
				return new BigDecimal(p.getAsString()).doubleValue();
			}
			// 문자열·참거짓은 그대로 넘기고 TransformOp가 인자마다 형식을 검사한다("2"처럼 수를 문자열로 주면 BAD_ARGUMENT)
			if (p.isString()) {
				return p.getAsString();
			}
			if (p.isBoolean()) {
				return p.getAsBoolean();
			}
		}
		throw Args.bad("params." + k + "는 수·문자열·true/false", "param", k, "value", v.toString());
	}

	private static Object fitSuggest(ServerState s, Args a) {
		int track = a.reqInt("track");
		JsonObject o = Json.tree(s.project().fitSuggest(track, rank(a), a.optString("song_rule"),
				RenderTools.stringList(a, "parts"))).getAsJsonObject();
		String w = s.project().voiceRuleWarning(track, a.optString("song_rule"));
		if (w != null) {
			com.google.gson.JsonArray ws = new com.google.gson.JsonArray();
			ws.add(w);
			o.add("warnings", ws);
		}
		return o;
	}

	private static Object split(ServerState s, Args a) {
		Path out = null;
		String od = a.optString("out_dir");
		if (od != null && !od.isBlank()) {
			out = ProjectTools.path(od.trim());
			if (!out.isAbsolute()) {
				// 상대 경로는 서버의 작업 폴더 기준(McpClient·run-bridge.cmd는 저장소 루트에서 띄운다)
				out = Path.of("").toAbsolutePath().resolve(out).normalize();
			}
		}
		int track = a.reqInt("track");
		JsonObject o = Json.tree(s.project().splitSheets(track, rank(a), a.optString("song_rule"),
				a.optInt("max_sheets", 8), a.optBool("carry", true), out, a.optString("name"), a.optBool("overwrite", false)))
				.getAsJsonObject();
		String w = s.project().voiceRuleWarning(track, a.optString("song_rule"));
		if (w != null) {
			o.getAsJsonArray("warnings").add(w);
		}
		return o;
	}

	/** score_stats 응답에 붙이는 rank_limits·warnings(트랙 하나). */
	static JsonObject statsJson(Project p, int track, String rank, String rule) {
		JsonObject o = Json.tree(p.statsOf(track)).getAsJsonObject();
		CharLimits.Evaluation ev = p.rankCheck(track, rank, rule);
		o.add("rank_limits", Json.tree(ev));
		com.google.gson.JsonArray ws = Json.tree(p.trackWarnings(track)).getAsJsonArray();
		String w = p.voiceRuleWarning(track, rule);
		if (w != null) {
			JsonObject x = new JsonObject();
			x.addProperty("code", "song_rule_voice");
			x.addProperty("track", track);
			x.addProperty("message", w);
			ws.add(x);
		}
		o.add("warnings", ws);
		return o;
	}

	static List<String> rankNames() {
		return CharLimits.rankNames();
	}
}
