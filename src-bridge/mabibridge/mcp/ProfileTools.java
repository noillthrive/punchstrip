package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import mabibridge.engine.EngineException;
import mabibridge.profile.ProfileIo;
import mabibridge.profile.ScoreLoader;
import mabibridge.profile.ScoreProfile;

/** 편곡 프로필 도구 score_profile. */
final class ProfileTools {

	private ProfileTools() {}

	static void register(ToolRegistry r) {
		r.add("score_profile",
				"악보 하나의 '편곡 모양'을 수치로 뽑는다(원곡 MIDI 불필요): 악기 조합·역할 추정(멜로디/화음/베이스/드럼/노래), "
						+ "트랙·파트별 음 수·적은 음역과 들리는 음역·세기 분포·음 길이·마디당 밀도·쉬는 마디, 동시 발음 수, 화음 보이싱(들리는 음 간격), "
						+ "저음역 겹침, 글자 수 사용률, 템포·박자, 구간 악기 세트 사용, 무음 구간 음, 옥타브 보정 원재료. path를 주면 그 .mmi 또는 "
						+ "3MLE .mml을 읽기만 하고(현재 프로젝트는 그대로), 없으면 현재 프로젝트. out_path를 주면 전체 JSON(mabibridge.profile/1)을 "
						+ "파일로 쓰고 응답에는 요약만, 없으면 응답의 profile에 전체를 넣는다.",
				props()
						.add("path", string(".mmi 또는 3MLE .mml 절대 경로(읽기만, 보호 폴더도 됨). 생략 = 현재 프로젝트"))
						.add("from_measure", integer("시작 마디(1부터). 생략 = 1", 1, null))
						.add("to_measure", integer("끝 마디(포함). 생략 = 음표가 있는 마지막 마디", 1, null))
						.add("out_path", string("전체 JSON을 쓸 .json 절대 경로(보호 폴더 안이면 PROTECTED_PATH)"))
						.add("overwrite", bool("out_path에 파일이 있으면 덮어쓸지(기본 false = FILE_EXISTS)"))
						.object(),
				ProfileTools::profile);
	}

	private static Object profile(ServerState s, Args a) {
		Integer from = a.optInt("from_measure");
		Integer to = a.optInt("to_measure");
		boolean overwrite = a.optBool("overwrite", false);
		Path out = null;
		String op = a.optString("out_path");
		if (op != null && !op.isBlank()) {
			out = ProjectTools.path(op.trim());
			if (!out.isAbsolute()) {
				throw Args.bad("out_path는 절대 경로여야 함: " + op, "out_path", op);
			}
			if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
				throw new EngineException(EngineException.BAD_PATH, "out_path는 .json으로 끝나야 함",
						ProfileIo.details("path", op));
			}
			out = out.normalize();
			s.engine().checkNotProtected(out);
			if (!overwrite && Files.exists(out)) {
				throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일(overwrite: true로 덮어쓸 수 있음): " + out,
						ProfileIo.details("path", out.toString()));
			}
		}
		ScoreLoader.Loaded src;
		String p = a.optString("path");
		if (p != null && !p.isBlank()) {
			src = ScoreLoader.load(s.engine(), ProjectTools.path(p.trim()));
		} else {
			src = ScoreLoader.ofProject(s.project());
		}
		ScoreProfile.Result r = ScoreProfile.build(src, s.engine().instrumentTable(), from, to);
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("ok", true);
		if (out != null) {
			ProfileIo.write(out, r.profile(), overwrite);
			resp.put("out_path", out.toString());
		}
		resp.put("schema", ScoreProfile.SCHEMA);
		resp.put("summary_text", r.summaryText());
		resp.put("summary", r.summary());
		if (out == null) {
			resp.put("profile", r.profile());
		}
		return resp;
	}
}
