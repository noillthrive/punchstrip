package mabibridge.mcp;

import static mabibridge.mcp.ToolRegistry.bool;
import static mabibridge.mcp.ToolRegistry.integer;
import static mabibridge.mcp.ToolRegistry.noArgs;
import static mabibridge.mcp.ToolRegistry.props;
import static mabibridge.mcp.ToolRegistry.string;
import static mabibridge.mcp.ToolRegistry.stringEnum;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.Project;
import mabibridge.engine.SoundingPitch;
import mabibridge.midi.ReferenceMidi;

/** 프로젝트·트랙·악기·스냅샷 도구. */
final class ProjectTools {

	private ProjectTools() {}

	static final String[] SONG_RULES = { Project.SONG_EXCLUDED, Project.SONG_INCLUDED };

	static void register(ToolRegistry r) {
		r.add("project_new",
				"새 악보를 만든다(지금 열린 악보는 닫힌다. 저장하지 않은 변경은 사라지니 필요하면 먼저 snapshot이나 project_save_as). "
						+ "참조 MIDI와 매핑은 그대로 남는다.",
				props()
						.add("title", string("곡 제목"))
						.add("author", string("작곡·편곡자"))
						.add("time_signature", string("박자, 예 \"4/4\"(기본), \"3/4\", \"6/8\". time_signatures와 함께 줄 수 없음"))
						.add("time_signatures", timeSignaturesSchema("박자 목록(project_set_time_signatures와 같은 형식·규칙). "
								+ "time_signature와 함께 줄 수 없음"))
						.add("tempo", integer("템포 BPM 32~255(기본 120 = 마비노기 기본)", Project.MIN_BPM, Project.MAX_BPM))
						.add("song_rule", stringEnum("글자 수 기본 판정 기준(기본 song_excluded)", SONG_RULES))
						.object(),
				ProjectTools::projectNew);

		r.add("project_open",
				".mmi 파일을 연다(읽기만, 원본 파일은 바꾸지 않는다). 지금 열린 악보는 닫힌다.",
				props().add("path", string(".mmi 파일의 절대 경로")).object("path"),
				(s, a) -> {
					s.engine().open(path(a.reqString("path")));
					return projectInfo(s);
				});

		r.add("project_save_as",
				"지금 악보를 새 .mmi 파일로 저장한다. 이미 있는 파일과 보호 폴더(--protect, 프로젝트 보호 목록) 안에는 저장하지 않는다.",
				props().add("path", string("저장할 새 파일의 절대 경로(.mmi로 끝나야 함)")).object("path"),
				(s, a) -> {
					Path saved = s.project().saveAs(path(a.reqString("path")));
					JsonObject o = new JsonObject();
					o.addProperty("ok", true);
					o.addProperty("path", saved.toString());
					return o;
				});

		r.add("project_info",
				"열린 악보의 요약(제목, 박자, 템포, 길이, 트랙 표)과 참조 MIDI 로드 여부, 매핑, 스냅샷 수를 본다.",
				noArgs(), (s, a) -> projectInfo(s));

		r.add("project_set",
				"열린 악보의 제목·작곡자를 바꾼다. 준 값만 바뀌고, 둘 중 하나는 있어야 한다. 빈 문자열은 지운다. "
						+ "파일은 저장하지 않으니 project_save_as로 저장한다. 응답은 project_info.",
				props()
						.add("title", string("새 곡 제목(줄바꿈 불가)"))
						.add("author", string("새 작곡·편곡자(줄바꿈 불가)"))
						.object(),
				(s, a) -> {
					String title = a.optString("title");
					String author = a.optString("author");
					if (title == null && author == null) {
						throw new EngineException(EngineException.BAD_ARGUMENT, "title, author 중 하나는 있어야 함");
					}
					s.project().setInfo(title, author);
					return projectInfo(s);
				});

		r.add("project_set_time_signatures",
				"박자 목록을 통째로 바꾼다. 항목 {measure, signature}는 마디 순서대로, 첫 항목은 1마디(틱 0), 한 마디에 하나, "
						+ "앞 항목과 같은 박자는 빼고 준다. 마디 번호는 앞 항목들의 박자로 센 이 악보의 마디(1부터, 참조 MIDI reference_info와 같은 셈). "
						+ "박자는 MabiIcco가 받는 것만(분자 1~32 또는 64, 분모 1·2·4·8·16·32·64). 음표·템포는 절대 틱 그대로 남고 "
						+ "pos(마디:박:틱)만 새 박자로 다시 읽힌다. 템포를 pos로 줄 거면 이것을 먼저 부른다. 저장은 project_save_as. 응답은 project_info.",
				props().add("time_signatures", timeSignaturesSchema("박자 목록, 예 [{\"measure\": 1, \"signature\": \"1/16\"}, "
						+ "{\"measure\": 2, \"signature\": \"4/4\"}, {\"measure\": 91, \"signature\": \"5/8\"}]"))
						.object("time_signatures"),
				(s, a) -> {
					s.project().setTimeSignatures(timeSignatures(a.raw("time_signatures")));
					return projectInfo(s);
				});

		r.add("project_set_tempos",
				"템포 목록을 통째로 바꾼다. 항목마다 tick(MML 틱, 6의 배수 또는 마디 시작 기준 16의 배수 = 셋잇단 격자) 또는 pos(\"마디:박:틱\", 지금 박자 목록으로 풂 — 박자를 먼저 "
						+ "project_set_time_signatures로 놓을 것) 중 하나와 정수 bpm(32~255, 마비노기가 t 명령을 따르는 범위). 틱 0 항목이 꼭 있어야 하고, 틱 순서대로, 한 틱에 하나. "
						+ "템포는 악보 전역이라 모든 트랙의 MML에 t 명령으로 들어간다. 저장은 project_save_as. 응답은 project_info.",
				props().add("tempos", ToolRegistry.array("템포 목록, 예 [{\"tick\": 0, \"bpm\": 150}, {\"pos\": \"10:1:0\", \"bpm\": 160}]",
						props()
								.add("tick", integer("템포가 바뀌는 틱(MML 틱, 4분음표 = 96, 6의 배수 또는 마디 시작 기준 16의 배수). pos와 둘 중 하나만", 0, null))
								.add("pos", string("템포가 바뀌는 위치 \"마디:박:틱\"(지금 박자 목록 기준). tick과 둘 중 하나만"))
								.add("bpm", integer("BPM 정수 32~255", Project.MIN_BPM, Project.MAX_BPM))
								.object("bpm")))
						.object("tempos"),
				(s, a) -> {
					s.project().setTempos(tempos(a.raw("tempos")));
					return projectInfo(s);
				});

		r.add("track_add",
				"트랙을 더한다(최대 24개). 악기 번호는 instruments 도구로 확인한다. 응답의 track이 새 트랙 번호(1부터)다.",
				props()
						.add("name", string("트랙 이름(32자까지)"))
						.add("program", integer("악기 번호(instruments의 program)", 0, null))
						.add("song_program", integer("노래 악기 번호. −1 = 코러스 없음(기본), −2 = 노래 제외, 그 밖은 코러스(C) 악기 100·110(MabiIcco처럼 노래·마이크 V는 받지 않음)", -2, null))
						.add("panpot", integer("좌우 위치 0~127(기본 64 = 가운데)", 0, 127))
						.add("volume", integer("음량 0~127(기본 100)", 0, 127))
						.object("name", "program"),
				(s, a) -> {
					Project p = s.project();
					int n = p.addTrack(a.reqString("name"), a.reqInt("program"), a.optInt("song_program"), a.optInt("panpot"),
							a.optInt("volume"));
					JsonObject o = new JsonObject();
					o.addProperty("ok", true);
					o.addProperty("track", n);
					o.add("summary", Json.tree(p.tracks().get(n - 1)));
					return o;
				});

		r.add("track_set",
				"트랙의 이름·악기·노래 악기·좌우·음량·글자 수 기본 판정을 바꾼다. 준 값만 바뀐다.",
				props()
						.add("track", integer("트랙 번호(1부터)", 1, null))
						.add("name", string("새 트랙 이름(32자까지)"))
						.add("program", integer("새 악기 번호", 0, null))
						.add("song_program", integer("노래 악기 번호(−1 없음, −2 노래 제외, 그 밖은 코러스(C) 악기 100·110(MabiIcco처럼 노래·마이크 V는 받지 않음))", -2, null))
						.add("panpot", integer("좌우 위치 0~127", 0, 127))
						.add("volume", integer("음량 0~127", 0, 127))
						.add("song_rule", stringEnum("이 트랙의 글자 수 기본 판정 기준", SONG_RULES))
						.object("track"),
				(s, a) -> s.project().setTrack(a.reqInt("track"), new Project.TrackChanges(a.optString("name"),
						a.optInt("program"), a.optInt("song_program"), a.optInt("panpot"), a.optInt("volume"),
						a.optString("song_rule"))));

		r.add("track_list", "트랙 요약 목록(번호, 이름, 악기, 파트별 음표 수, 허용 파트).", noArgs(), (s, a) -> {
			JsonObject o = new JsonObject();
			o.add("tracks", Json.tree(s.project().tracks()));
			return o;
		});

		r.add("instruments",
				"악기 목록(번호, 한국어·영어 이름, 타입, 허용 파트, 음역)과 악기마다 적은 음과 실제로 울리는 음의 차이(sounding: "
						+ "피아노 +1옥타브, 바이올린 +2옥타브 등)·들리는 음역(sounding_range). 처음 부르면 마비노기 음원(DLS, 약 370MB)을 읽어 몇 초 걸린다. "
						+ "dls: false면 음원을 읽지 않고 properties 기준 음역만 준다. velocity_rule(세기 → MIDI 벨로시티: \"x8\" 일반 "
						+ "v15 = 120, \"x11\" 타악기 P·실로폰 K는 v11에서 자르고 ×11 — 실로폰 v11 ≈ 일반 v15)·velocity_max, 드럼 27에는 "
						+ "drum_keys(키마다 key·id·class·name_en·name_ko·dls_name·mml·level_db_vs_kick(같은 세기에서 킥 대비 dB, 세기 v의 "
						+ "크기 ∝ 40·log10 v)·suggested_vel·in_game_note — 킥 41은 인게임에서 작아 로우 탐 43을 겹침).",
				props()
						.add("type", stringEnum("이 타입만: N 일반, P 타악기, K 키보드류, V 노래, C 코러스, D 드럼", "N", "P", "K", "V",
								"C", "D"))
						.add("dls", bool("음원(DLS)을 읽어 정확한 음역을 쓸지(기본 true). DLS를 읽지 못하면 DLS_UNAVAILABLE 오류"))
						.object(),
				ProjectTools::instruments);

		r.add("snapshot", "지금 악보 상태를 저장해 둔다(메모리 + 스냅샷 폴더의 .mmi). 큰 편집 전에 부른다.",
				props().add("label", string("알아보기 쉬운 이름")).object(),
				(s, a) -> s.engine().snapshot(a.optString("label")));

		r.add("restore", "스냅샷으로 악보를 되돌린다(그 뒤의 변경은 사라진다). 응답은 project_info.",
				props().add("id", integer("snapshot이 돌려준 id", 1, null)).object("id"),
				(s, a) -> {
					s.engine().restore(a.reqInt("id"));
					return projectInfo(s);
				});

		r.add("snapshot_list", "스냅샷 목록(id, label, time, path).", noArgs(), (s, a) -> {
			JsonObject o = new JsonObject();
			o.add("snapshots", Json.tree(s.engine().snapshots().list()));
			return o;
		});
	}

	private static Object projectNew(ServerState s, Args a) {
		String rule = a.optString("song_rule");
		List<Project.TimeSigSpec> sigs = a.has("time_signatures") ? timeSignatures(a.raw("time_signatures")) : null;
		Project p = s.engine().newProject(a.optString("title"), a.optString("author"), a.optString("time_signature"), sigs,
				a.optInt("tempo"));
		if (rule != null) {
			p.setSongRule(rule);
		}
		return projectInfo(s);
	}

	static JsonObject timeSignaturesSchema(String description) {
		return ToolRegistry.array(description, props()
				.add("measure", integer("이 박자가 시작하는 마디(1부터, 앞 항목들의 박자로 센 마디). 첫 항목은 1", 1,
						Project.MAX_SIGNATURE_MEASURE))
				.add("signature", string("박자 \"분자/분모\", 예 \"4/4\", \"5/8\", \"1/16\""))
				.object("measure", "signature"));
	}

	private static final Set<String> SIG_KEYS = Set.of("measure", "signature");
	private static final Set<String> TEMPO_KEYS = Set.of("tick", "pos", "bpm");

	/** time_signatures 배열 → 항목 목록. 형식이 틀리면 BAD_ARGUMENT(details에 index·entry). */
	static List<Project.TimeSigSpec> timeSignatures(JsonElement e) {
		JsonArray arr = array(e, "time_signatures");
		List<Project.TimeSigSpec> l = new ArrayList<>();
		for (int i = 0; i < arr.size(); i++) {
			JsonObject o = entry(arr.get(i), "time_signatures", i, SIG_KEYS);
			int measure = strictInt(o, "measure", "time_signatures", i);
			JsonElement sig = o.get("signature");
			if (sig == null || sig.isJsonNull() || !sig.isJsonPrimitive() || !sig.getAsJsonPrimitive().isString()) {
				throw entryBad("time_signatures", i, o, "signature는 \"분자/분모\" 문자열");
			}
			l.add(new Project.TimeSigSpec(measure, sig.getAsString()));
		}
		return l;
	}

	/** tempos 배열 → 항목 목록. 형식이 틀리면 BAD_ARGUMENT(details에 index·entry). */
	static List<Project.TempoSpec> tempos(JsonElement e) {
		JsonArray arr = array(e, "tempos");
		List<Project.TempoSpec> l = new ArrayList<>();
		for (int i = 0; i < arr.size(); i++) {
			JsonObject o = entry(arr.get(i), "tempos", i, TEMPO_KEYS);
			Long tick = null;
			if (o.has("tick") && !o.get("tick").isJsonNull()) {
				tick = (long) strictInt(o, "tick", "tempos", i);
			}
			String pos = null;
			if (o.has("pos") && !o.get("pos").isJsonNull()) {
				JsonElement p = o.get("pos");
				if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) {
					throw entryBad("tempos", i, o, "pos는 \"마디:박:틱\" 문자열");
				}
				pos = p.getAsString();
			}
			int bpm = strictInt(o, "bpm", "tempos", i);
			l.add(new Project.TempoSpec(tick, pos, bpm));
		}
		return l;
	}

	private static JsonArray array(JsonElement e, String name) {
		if (e == null || !e.isJsonArray()) {
			throw Args.bad(name + "는 배열이어야 함", "argument", name);
		}
		if (e.getAsJsonArray().isEmpty()) {
			throw Args.bad(name + "가 비어 있음(항목이 하나 이상 필요)", "argument", name);
		}
		return e.getAsJsonArray();
	}

	private static JsonObject entry(JsonElement e, String name, int i, Set<String> keys) {
		if (e == null || !e.isJsonObject()) {
			throw Args.bad(name + "[" + i + "]는 객체여야 함", "index", i, "entry", String.valueOf(e),
					"reason", "객체 {" + String.join(", ", new java.util.TreeSet<>(keys)) + "}");
		}
		JsonObject o = e.getAsJsonObject();
		for (String k : o.keySet()) {
			if (!keys.contains(k)) {
				throw entryBad(name, i, o, "모르는 키 " + k + " (쓸 수 있는 키: " + new java.util.TreeSet<>(keys) + ")");
			}
		}
		return o;
	}

	/** JSON 정수만(문자열 "3"·2.5 거부, 2.0은 받음). */
	private static int strictInt(JsonObject o, String k, String name, int i) {
		JsonElement v = o.get(k);
		if (v == null || v.isJsonNull()) {
			throw entryBad(name, i, o, k + "가 필요함(정수)");
		}
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
			try {
				return new BigDecimal(v.getAsString()).intValueExact();
			} catch (ArithmeticException | NumberFormatException ex) {
				// 아래에서 거부
			}
		}
		throw entryBad(name, i, o, k + "는 JSON 정수여야 함: " + v);
	}

	private static EngineException entryBad(String name, int i, JsonObject o, String reason) {
		return Args.bad(name + "[" + i + "]: " + reason, "index", i, "entry", Json.tree(o), "reason", reason);
	}

	private static Object instruments(ServerState s, Args a) {
		String type = a.optString("type");
		boolean useDls = a.optBool("dls", true);
		JsonObject o = new JsonObject();
		if (useDls) {
			BridgeEngine.DlsStatus st = s.engine().ensureDls();
			o.add("dls", Json.tree(st));
		} else {
			JsonObject d = new JsonObject();
			d.addProperty("loaded", BridgeEngine.dlsLoaded());
			d.addProperty("note", "dls: false — 음원을 읽지 않음");
			o.add("dls", d);
		}
		List<InstrumentTable.Instrument> list = s.engine().instruments(type);
		o.addProperty("count", list.size());
		// 악기마다 "적은 음 대 들리는 음"(sounding)과 연주 가능한 적은 음역을 들리는 음으로 옮긴 범위(sounding_range, 세트별)
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (InstrumentTable.Instrument i : list) {
			JsonObject io = Json.tree(i).getAsJsonObject();
			io.add("sounding", Json.tree(SoundingPitch.describe(i.program())));
			InstrumentTable.Range rg = i.range();
			io.add("sounding_range", Json.tree(SoundingPitch.soundingRange(i.program(), rg == null ? null : rg.low(),
					rg == null ? null : rg.high())));
			// 세기 규칙(x8 / x11)과 드럼 27의 키 표
			io.addProperty("velocity_rule", InstrumentTable.velocityRule(i.type()));
			io.addProperty("velocity_max", InstrumentTable.velocityMax(i.type()));
			if (i.program() == DrumKeyTable.DRUM_PROGRAM) {
				JsonObject t = DrumKeyTable.table();
				io.add("drum_keys", t.get("keys"));
				io.addProperty("drum_level_rule", t.get("level_rule").getAsString());
				io.addProperty("drum_level_reference", t.get("level_reference").getAsString());
				io.addProperty("drum_in_game", t.get("in_game").getAsString());
			}
			arr.add(io);
		}
		o.add("instruments", arr);
		o.addProperty("sounding_source", SoundingPitch.SOURCE);
		return o;
	}

	/** project_info: 엔진 요약 + 참조·매핑·compare 연결 상태. */
	static JsonObject projectInfo(ServerState s) {
		JsonObject o = Json.tree(s.project().info()).getAsJsonObject();
		ReferenceMidi ref = s.reference();
		JsonObject r = new JsonObject();
		r.addProperty("loaded", ref != null);
		if (ref != null) {
			r.addProperty("file", String.valueOf(ref.file()));
			r.addProperty("track_count", ref.trackCount());
			r.addProperty("measure_count", ref.measureCount());
		}
		o.add("reference", r);
		o.add("mapping", s.mappingJson());
		o.addProperty("compare_wired", s.compareHook() != null);
		o.addProperty("dls_loaded", BridgeEngine.dlsLoaded());
		return o;
	}

	static Path path(String p) {
		try {
			return Path.of(p);
		} catch (InvalidPathException e) {
			throw new EngineException(EngineException.BAD_PATH, "경로 형식이 틀림: " + e.getMessage(),
					java.util.Map.of("path", p));
		}
	}
}
