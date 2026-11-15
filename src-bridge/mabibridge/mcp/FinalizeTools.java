package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import mabibridge.engine.EngineException;
import mabibridge.engine.Finalize;

/** score_finalize: tuning signature in a lead-in and silent end padding for a finished score. */
final class FinalizeTools {

	private FinalizeTools() {}

	static final String DESCRIPTION = "다 만든 악보를 합주용으로 마무리한다(곡을 다 만든 뒤 한 번 부른다; 사용자가 시그니처나 끝 맞춤을 원하지 않으면 "
			+ "signature: false 또는 end_padding: false). 시그니처는 이 도구만 넣는다. 있는 음은 지우거나 바꾸지 않는다. "
			+ "(a) 시그니처: 곡 앞에 온마디 n개(틱 0 박자, n × 마디 ≥ 8u + 96틱인 가장 작은 n ≥ 1, 응답 measures_inserted — 1이 아닐 수 "
			+ "있음)를 넣고 모든 음·템포 변경·박자 변경·마커를 그만큼 뒤로 민다(틱 0 템포·박자는 머리 마디에도 그대로) — 그래서 끝난 뒤에는 마디 "
			+ "번호가 n만큼 커진다. 틱 0 템포가 적혀 있으면 같은 템포를 원래 첫 틱에도 둔다(MabiIcco에서 머리 마디를 지워도 템포가 남게, 트랙마다 "
			+ "3~7자, 글자 수 판정에 들어감; 그 복사만 자리가 없으면 빼고 머리 마디는 넣음 — signature.lead_in_tempo.status omitted와 경고 "
			+ "tempo_copy_omitted: 그때는 머리 마디를 지운 뒤 시작 템포를 다시 넣어야 함). 머리 마디에는 트랙마다 \"bridge\" 시그니처: 단위 u = 틱 0 템포(없으면 기본 120) 85 미만 "
			+ "32분(12틱)·170 미만 16분(24틱)·그 이상 8분(48틱), 들리는 음 B·D·G를 0·u·2u에 길이 u, E를 3u에 길이 5u(G 위 장6도, 못 내면 G 아래 "
			+ "단3도). 옥타브는 악기만으로 정한다(트랙의 음과 무관): 네 음을 모두 낼 수 있고(무음 음 제외) 평균이 악기의 시그니처 기준음"
			+ "(signature_home.properties)에 가장 가까운 것, 표에 없는 악기는 낼 수 있는 음역의 가운데. 세기는 velocity(기본 12)가 일반 악기에서 "
			+ "내는 MIDI 세기에 가장 가깝게 악기마다 바꾼다(실로폰 등 x11 악기 v12 → v9). 노래·마이크·코러스 트랙은 노래 칸, 드럼(27)은 스네어 ×3 + "
			+ "크래시(세기 15). 칸은 멜로디가 한도에 들면 멜로디, 아니면 다른 칸. 머리 마디의 쉼표만으로 한도를 넘는 칸이 생기면 시그니처를 하나도 넣지 "
			+ "않고 이유를 알린다. 자리가 없는 트랙은 건너뛰고 어느 칸이 몇 자 넘는지 알린다. 이미 이 도구의 시그니처가 있으면 다시 넣지 않고(시그니처가 없는 트랙을 이름으로 알림), "
			+ "틱 0에 MabiIcco 조율음이 있으면 on_existing_tuning(skip 기본 / prepend). signature.status skipped는 시그니처만 해당하고 끝 맞춤은 "
			+ "그래도 한다. (b) 끝 맞춤: 가장 늦은 음 끝 T_end보다 6틱 넘게 일찍 끝나는 트랙마다 세기 0 음 하나(길이 6틱, T_end에 끝남, 그 앞 "
			+ "쉼표가 1~5틱이라 MML로 쓸 수 없을 때만 마지막 음 바로 뒤부터 T_end까지; 그 트랙 마지막 음의 높이)를 한도에 들고 글자가 가장 적게 "
			+ "드는 칸에 넣는다(합주 중 먼저 끝나 빠지는 사람이 없게). 두 번 불러도 더 넣지 않는다(padding.status done = 이번에 넣음, "
			+ "already_present = 전에 넣은 것이 있음, skipped = 자리가 없음). 악보를 바꿀 때만 바꾸기 전 스냅샷을 자동으로 남긴다(restore로 되돌림, 바뀐 것이 없으면 스냅샷 없음). dry_run: "
			+ "true면 같은 보고만 하고 악보는 그대로(changed false, would_change = 실제로 부르면 바뀌는지). 응답: changed, would_change, signature{status, bpm, bpm_default, unit, measures_inserted, lead_in_tempo, "
			+ "tracks[{slot, sounding, written, velocities, chars_added, reason}]}, padding{status, end_tick, tracks[{status, slot, pos, len, "
			+ "chars_added, reason}]}, chars[트랙마다 before/after/added, fits], warnings, text.";

	static void register(ToolRegistry r) {
		r.add("score_finalize", DESCRIPTION,
				props()
						.add("signature", bool("머리 마디와 시그니처를 넣을지(기본 true)"))
						.add("end_padding", bool("끝을 세기 0 음으로 맞출지(기본 true)"))
						.add("velocity", integer("시그니처 음 세기(1~15, 기본 12, 일반 악기 기준 — x11 악기는 같은 MIDI 세기가 되게 바꿈). 드럼 크래시는 언제나 15", 1, 15))
						.add("on_existing_tuning", stringEnum("틱 0에 MabiIcco 조율음이 이미 있을 때: skip(기본, 시그니처 안 넣음) 또는 "
								+ "prepend(그 앞에 넣음)", Finalize.TUNING_SKIP, Finalize.TUNING_PREPEND))
						.add("dry_run", bool("true면 바꾸지 않고 결과만 보고(기본 false)"))
						.add("rank", FitTools.rankArg())
						.add("song_rule", FitTools.songRuleArg())
						.object(),
				FinalizeTools::run);
	}

	private static Object run(ServerState s, Args a) {
		try {
			s.engine().ensureDls();
		} catch (EngineException e) {
			System.err.println("[mcp] score_finalize: DLS를 읽지 못해 properties 음역으로 고름: " + e.getMessage());
		}
		Finalize.Options o = new Finalize.Options(a.optBool("signature", true), a.optBool("end_padding", true),
				a.optInt("velocity", Finalize.DEFAULT_VELOCITY), a.optString("on_existing_tuning"), a.optBool("dry_run", false),
				FitTools.rank(a), a.optString("song_rule"));
		return s.project().finalizeScore(o);
	}
}
