package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.array;
import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.intArray;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.Main;
import mabibridge.render.WavRender;

/** 오프라인 WAV 렌더 도구 render_wav. */
final class RenderTools {

	private RenderTools() {}

	static void register(ToolRegistry r) {
		JsonObject tail = ToolRegistry.typed("number",
				"마지막 음(또는 범위 끝) 뒤 꼬리(여운)의 상한, 초. 소리가 다 사라지면 더 일찍 끝낸다. 0~60, 기본 5");
		tail.addProperty("minimum", 0);
		tail.addProperty("maximum", WavRender.MAX_TAIL_SEC);
		r.add("render_wav",
				"지금 악보(또는 마디·트랙·파트 일부)를 오디오 장치 없이 WAV(44.1kHz/16bit/스테레오)로 렌더하고, 옆에 같은 이름의 "
						+ "메타 JSON(mabibridge.render/1: 마디·박 시각표, 트랙 설정, 신시사이저 설정)을 쓴다. 소리 분석 서버에 넘길 파일을 만들 때 쓴다. "
						+ "범위 앞에서 시작해 걸친 음은 넣지 않는다(start_in_range).",
				props()
						.add("from_measure", integer("시작 마디(1부터). 생략 = 1", 1, null))
						.add("to_measure", integer("끝 마디(포함). 생략 = 음표가 있는 마지막 마디", 1, null))
						.add("tracks", intArray("렌더할 트랙 번호(1부터). 생략 = 전체"))
						.add("parts", array("렌더할 파트. 생략 = 전체",
								stringEnum("파트", "melody", "chord1", "chord2", "song")))
						.add("split", stringEnum("none(기본, 파일 하나) | track(트랙마다 파일) | part(트랙×파트마다 파일)",
								WavRender.SPLIT_NONE, WavRender.SPLIT_TRACK, WavRender.SPLIT_PART))
						.add("tail_sec", tail)
						.add("name", string("파일 이름 앞머리(경로·확장자 없이). 생략 = 제목_범위_시각"))
						.add("out_dir", string("출력 폴더 절대 경로. 생략 = 서버의 --render-dir(기본 <MABIBRIDGE_OUT>/render 또는 <설치>/output/render)"))
						.add("overwrite", bool("같은 이름 파일이 있으면 덮어쓸지(기본 false = FILE_EXISTS)"))
						.object(),
				RenderTools::render);
	}

	private static Object render(ServerState s, Args a) {
		Path outDir = null;
		String od = a.optString("out_dir");
		if (od != null && !od.isBlank()) {
			try {
				outDir = Path.of(od.trim());
			} catch (InvalidPathException e) {
				throw Args.bad("out_dir 경로 형식이 틀림: " + od, "out_dir", od);
			}
		}
		WavRender.Request req = new WavRender.Request(a.optInt("from_measure"), a.optInt("to_measure"),
				a.optIntList("tracks"), stringList(a, "parts"), a.optString("split"), optDouble(a, "tail_sec"),
				a.optString("name"), outDir, a.optBool("overwrite", false));
		return WavRender.renderToFiles(s.engine(), s.project(), req, s.renderDir(), engineLabel());
	}

	static String engineLabel() {
		String found = Main.mabiIccoVersion();
		return "MabiIccoBridge " + Main.VERSION + " / MabiIcco " + (found == null ? "unknown" : found)
				+ " (built and tested against " + Main.MMLTOOLS_VERSION + ")";
	}

	private static Double optDouble(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null) {
			return null;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return new BigDecimal(e.getAsString()).doubleValue();
		}
		throw Args.bad(k + "는 숫자여야 함: " + e, "argument", k, "value", e.toString());
	}

	static List<String> stringList(Args a, String k) {
		JsonElement e = a.raw(k);
		if (e == null) {
			return null;
		}
		if (!e.isJsonArray()) {
			throw Args.bad(k + "는 문자열 배열이어야 함", "argument", k);
		}
		List<String> l = new ArrayList<>();
		for (JsonElement x : e.getAsJsonArray()) {
			if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString()) {
				throw Args.bad(k + "의 원소는 문자열(melody, chord1, chord2, song)", "argument", k);
			}
			l.add(x.getAsString());
		}
		return l;
	}
}
