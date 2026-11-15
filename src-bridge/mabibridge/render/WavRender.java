package mabibridge.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import jp.fourthline.mabiicco.midi.InstClass;
import jp.fourthline.mabiicco.midi.InstType;
import jp.fourthline.mabiicco.midi.MabiDLS;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoConverter;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.Part;
import mabibridge.engine.Project;
import mabibridge.midi.MeasureMap;

/**
 * {@code render_wav} 도구의 본체: 현재 악보(또는 마디·트랙·파트 일부)를 WAV(44.1kHz/16bit/스테레오)로
 * 오프라인 렌더하고, 옆에 같은 이름의 메타 JSON({@value #SCHEMA})을 쓴다.
 *
 * <p>범위 규칙: {@code from_measure} 시작 틱 ≤ 악보상 음 시작 틱 &lt; {@code to_measure} 끝 틱인 음표만(start_in_range),
 * 그 짝 노트오프는 범위 밖이어도 넣는다. 렌더 시각 0 = {@code from_measure} 시작. 길이는 적어도 범위 끝(또는 마지막
 * 노트오프 중 늦은 쪽, 이를 기준 끝이라 부른다)까지이고, 그 뒤 소리가 다 사라지면 멈추며 {@code tail_sec}가 상한이다.</p>
 */
public final class WavRender {

	public static final String SCHEMA = "mabibridge.render/1";
	public static final String RENDER_FAILED = "RENDER_FAILED";
	public static final String SYNTH_UNAVAILABLE = "SYNTH_UNAVAILABLE";
	public static final String NOTHING_TO_RENDER = "NOTHING_TO_RENDER";
	public static final String NOTE_RULE = "start_in_range";
	public static final double DEFAULT_TAIL_SEC = 5.0;
	public static final double MAX_TAIL_SEC = 60.0;
	public static final String SPLIT_NONE = "none";
	public static final String SPLIT_TRACK = "track";
	public static final String SPLIT_PART = "part";

	/** 메타·README에 적는 알려진 한계. */
	public static final List<String> LIMITATIONS = List.of(
			"auto gain control(자동 게인)이 켜져 있어 트랙·파트 파일을 더한 것은 전체 파일과 같지 않을 수 있다. 밸런스는 전체 파일 기준으로 볼 것",
			"노트오프는 제어 버퍼(300프레임, 약 6.8ms) 단위로 처리되어 6.8ms보다 짧은 음은 소리가 나지 않을 수 있다",
			"GUI의 WAV 내보내기와 샘플 단위로 같지는 않다(앞 무음 길이, 이벤트 시각 양자화 차이)",
			"모든 음은 엔진 규칙대로 1틱 늦게 시작하고 1틱 짧게 끝난다(note_start_offset_tick)");
	public static final String LIMIT_MOBILE = "모바일 타악기(타입 M) 트랙이 있다: 엔진이 음을 무작위로 골라 렌더할 때마다 소리가 다를 수 있다";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls()
			.create();

	/**
	 * 요청. null = 생략.
	 *
	 * @param outDir 출력 폴더(절대 경로). null이면 서버의 --render-dir
	 */
	public record Request(Integer fromMeasure, Integer toMeasure, List<Integer> tracks, List<String> parts, String split,
			Double tailSec, String name, Path outDir, boolean overwrite) {}

	/** 파일 하나로 렌더할 묶음. tracks는 1부터, parts는 0~3. */
	record Group(String suffix, List<Integer> tracks, Set<Integer> parts, List<ScoreEvents.Note> notes) {}

	/** 렌더 계획(검증과 음표 고르기를 마친 상태). */
	record Plan(int fromMeasure, int toMeasure, long fromTick, long toTick, double fromMs, double rangeEndSec,
			List<Integer> tracks, Set<Integer> parts, String split, double tailSec, List<Group> groups,
			List<JsonObject> skipped, MeasureMap map, boolean wholeSong) {}

	/** 렌더한 파일 하나. */
	record Rendered(Group group, OfflineRenderer.Audio audio, long millis) {}

	private WavRender() {}

	// ======================================================================
	// 계획
	// ======================================================================

	static Plan plan(BridgeEngine engine, Project project, Request req) {
		MMLScore score = project.score();
		String split = req.split() == null ? SPLIT_NONE : req.split();
		if (!List.of(SPLIT_NONE, SPLIT_TRACK, SPLIT_PART).contains(split)) {
			throw bad("split은 none, track, part 중 하나: " + split, "split", split);
		}
		double tail = req.tailSec() == null ? DEFAULT_TAIL_SEC : req.tailSec();
		if (!(tail >= 0 && tail <= MAX_TAIL_SEC)) {
			throw bad("tail_sec는 0~" + MAX_TAIL_SEC + "초: " + tail, "tail_sec", tail);
		}
		int trackCount = score.getTrackCount();
		if (trackCount == 0) {
			throw new EngineException(NOTHING_TO_RENDER, "악보에 트랙이 없음", Map.of());
		}
		List<Integer> tracks = new ArrayList<>();
		if (req.tracks() == null) {
			for (int i = 1; i <= trackCount; i++) {
				tracks.add(i);
			}
		} else {
			if (req.tracks().isEmpty()) {
				throw bad("tracks가 비어 있음(생략하면 전체)", "tracks", req.tracks());
			}
			for (int t : new TreeSet<>(req.tracks())) {
				if (t < 1 || t > trackCount) {
					Map<String, Object> d = new LinkedHashMap<>();
					d.put("track", t);
					d.put("track_count", trackCount);
					throw new EngineException(EngineException.NO_TRACK, "트랙 " + t + "번이 없음(1~" + trackCount + ")", d);
				}
				tracks.add(t);
			}
		}
		Set<Integer> parts = new TreeSet<>();
		if (req.parts() == null) {
			for (Part p : Part.values()) {
				parts.add(p.index());
			}
		} else {
			if (req.parts().isEmpty()) {
				throw bad("parts가 비어 있음(생략하면 전체)", "parts", req.parts());
			}
			for (String s : req.parts()) {
				parts.add(Part.of(s).index());
			}
		}

		// 음표(엔진 시퀀스에서)
		List<ScoreEvents.Note> all;
		try {
			all = ScoreEvents.extract(score);
		} catch (InvalidMidiDataException | RuntimeException e) {
			throw new EngineException(RENDER_FAILED, "재생용 시퀀스를 만들지 못함: " + e, Map.of(), e);
		}

		MeasureMap map = project.measureMap();
		long lastStart = -1;
		for (ScoreEvents.Note n : all) {
			lastStart = Math.max(lastStart, Math.max(0, n.startTick()));
		}
		int totalTicks = score.getTotalTickLength();
		int lastMeasure = Math.max(lastStart >= 0 ? map.measureOf(lastStart) : 0,
				totalTicks > 0 ? map.measureOf(totalTicks - 1L) : 0);
		boolean whole = req.fromMeasure() == null && req.toMeasure() == null;
		int from = req.fromMeasure() == null ? 1 : req.fromMeasure();
		int to;
		if (req.toMeasure() != null) {
			to = req.toMeasure();
		} else {
			to = Math.max(lastMeasure, from);
		}
		if (from < 1 || to < from) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("from_measure", from);
			d.put("to_measure", to);
			throw bad("마디 범위가 틀림: " + from + "~" + to + " (1 이상, from ≤ to)", d);
		}
		long fromTick = map.measureStart(from);
		long toTick = map.measureEnd(to);
		double fromMs = ms(score, fromTick);
		double rangeEndSec = (ms(score, toTick) - fromMs) / 1000.0;

		// 범위·트랙·파트 고르기
		Map<Integer, List<ScoreEvents.Note>> byChannel = new LinkedHashMap<>();
		Set<Integer> trackSet = new LinkedHashSet<>(tracks);
		for (ScoreEvents.Note n : all) {
			if (!trackSet.contains(n.track()) || !parts.contains(n.part())) {
				continue;
			}
			if (n.startTick() < fromTick || n.startTick() >= toTick) {
				continue;
			}
			byChannel.computeIfAbsent(n.channel(), k -> new ArrayList<>()).add(n);
		}

		List<Group> groups = new ArrayList<>();
		List<JsonObject> skipped = new ArrayList<>();
		if (SPLIT_NONE.equals(split)) {
			List<ScoreEvents.Note> notes = new ArrayList<>();
			Set<Integer> used = new TreeSet<>();
			for (List<ScoreEvents.Note> l : byChannel.values()) {
				notes.addAll(l);
				for (ScoreEvents.Note n : l) {
					used.add(n.track());
				}
			}
			for (int t : tracks) {
				if (!used.contains(t)) {
					skipped.add(skip(t, null, "범위·파트 안에 음표 없음"));
				}
			}
			if (!notes.isEmpty()) {
				groups.add(new Group("", List.copyOf(used), parts, notes));
			}
		} else {
			for (int t : tracks) {
				if (SPLIT_TRACK.equals(split)) {
					List<ScoreEvents.Note> notes = new ArrayList<>();
					for (int p : parts) {
						notes.addAll(byChannel.getOrDefault((t - 1) * 4 + p, List.of()));
					}
					if (notes.isEmpty()) {
						skipped.add(skip(t, null, "범위·파트 안에 음표 없음"));
					} else {
						groups.add(new Group(String.format(Locale.ROOT, "__t%02d", t), List.of(t), parts, notes));
					}
				} else {
					for (int p : parts) {
						List<ScoreEvents.Note> notes = byChannel.getOrDefault((t - 1) * 4 + p, List.of());
						if (notes.isEmpty()) {
							continue; // 파트 단위로는 빈 파트가 흔하므로 skipped에 다 적지 않는다
						}
						groups.add(new Group(String.format(Locale.ROOT, "__t%02d_%s", t, Part.ofIndex(p).key()), List.of(t),
								Set.of(p), notes));
					}
				}
			}
		}
		if (groups.isEmpty()) {
			checkInstrumentsLoaded(engine, score, tracks);
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("from_measure", from);
			d.put("to_measure", to);
			d.put("tracks", tracks);
			d.put("parts", partNames(parts));
			throw new EngineException(NOTHING_TO_RENDER, "고른 범위·트랙·파트에 음표가 없음(" + from + "~" + to + "마디)", d);
		}
		return new Plan(from, to, fromTick, toTick, fromMs, rangeEndSec, tracks, parts, split, tail, groups, skipped,
				map, whole);
	}

	private static JsonObject skip(int track, String part, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("track", track);
		if (part != null) {
			o.addProperty("part", part);
		}
		o.addProperty("reason", reason);
		return o;
	}

	static double ms(MMLScore score, long tick) {
		return MMLTempoConverter.getTimeOnTickOffset(score.getTempoEventList(), (int) tick);
	}

	// ======================================================================
	// 렌더
	// ======================================================================

	/** 묶음 하나를 렌더한다(신시사이저를 새로 열고 닫는다). */
	static Rendered renderGroup(Project project, Plan plan, Group g, String label) {
		MMLScore score = project.score();
		List<OfflineRenderer.Timed> events = new ArrayList<>();
		long lastUs = 0;
		for (ScoreEvents.Note n : g.notes()) {
			long on = us(score, plan, n.onTick());
			long off = Math.max(on, us(score, plan, n.offTick()));
			events.add(new OfflineRenderer.Timed(on, ShortMessage.NOTE_ON, n.channel(), n.note(), n.velocity()));
			events.add(new OfflineRenderer.Timed(off, ShortMessage.NOTE_OFF, n.channel(), n.note(), 0));
			lastUs = Math.max(lastUs, off);
		}
		// 시각 순, 같은 시각은 노트오프 먼저
		events.sort(Comparator.comparingLong(OfflineRenderer.Timed::us)
				.thenComparingInt(t -> t.command() == ShortMessage.NOTE_OFF ? 0 : 1));
		double endBase = Math.max(plan.rangeEndSec(), lastUs / 1e6);
		List<Integer> load = new ArrayList<>();
		for (int t : g.tracks()) {
			load.add(t - 1);
		}
		long t0 = System.nanoTime();
		try {
			OfflineRenderer.Audio a = OfflineRenderer.render(score, load, events, endBase, plan.tailSec(), label);
			long msTaken = (System.nanoTime() - t0) / 1_000_000;
			System.err.println("[render] " + label + ": " + String.format(Locale.ROOT, "%.2f", a.durationSec()) + "초, "
					+ msTaken + "ms, 음표 " + g.notes().size() + "개");
			return new Rendered(g, a, msTaken);
		} catch (OfflineRenderer.SynthUnavailable e) {
			System.err.println("[render] " + label + " 신시사이저 열기 실패: " + e);
			e.printStackTrace(System.err);
			throw new EngineException(SYNTH_UNAVAILABLE, e.getMessage(), Map.of(), e);
		} catch (OutOfMemoryError e) {
			throw new EngineException(RENDER_FAILED, "메모리 부족으로 렌더 실패(범위를 줄일 것): " + e, Map.of());
		} catch (IOException | InvalidMidiDataException | RuntimeException e) {
			System.err.println("[render] " + label + " 렌더 실패: " + e);
			e.printStackTrace(System.err);
			throw new EngineException(RENDER_FAILED, "렌더 실패: " + e, Map.of(), e);
		}
	}

	private static long us(MMLScore score, Plan plan, long tick) {
		double msv = ms(score, tick) - plan.fromMs();
		return Math.max(0L, Math.round(msv * 1000.0));
	}

	/**
	 * 고른 트랙의 악기가 읽은 DLS에 없으면(다섯 음원 중 일부만 준 경우) 무엇이 빠졌는지 알리는 DLS_INCOMPLETE.
	 * 일부만 준 DLS 자체는 거부하지 않는다(그 음원의 악기만 쓰는 악보는 그대로 렌더된다).
	 */
	static void checkInstrumentsLoaded(BridgeEngine engine, MMLScore score, List<Integer> tracks) {
		if (!BridgeEngine.dlsLoaded()) {
			return;
		}
		jp.fourthline.mabiicco.midi.MabiDLS dls = jp.fourthline.mabiicco.midi.MabiDLS.getInstance();
		List<String> missing = new ArrayList<>();
		for (int t : tracks) {
			int p = score.getTrack(t - 1).getProgram();
			jp.fourthline.mabiicco.midi.InstClass inst = dls.getInstByProgram(p);
			if (inst == null || inst.getInstrument() == null) {
				missing.add("트랙 " + t + " 악기 " + p);
			}
		}
		if (missing.isEmpty()) {
			return;
		}
		List<String> banks = new ArrayList<>();
		for (java.nio.file.Path b : mabibridge.engine.DlsLocator.missingBanks(engine.config().dlsPaths())) {
			banks.add(b.getFileName().toString());
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("instruments_not_loaded", missing);
		d.put("banks_not_loaded", banks);
		throw new EngineException(EngineException.DLS_INCOMPLETE, "읽은 DLS에 악기가 없어 렌더할 음이 없음: " + missing
				+ (banks.isEmpty() ? "" : ". 읽지 않은 음원: " + banks + " — MABI_DLS나 --dls에 마비노기 mp3 폴더(다섯 파일)를 준다"), d);
	}

	/** 파일을 쓰지 않고 메모리에만 렌더한다(--selfcheck와 시험용). */
	public static List<OfflineRenderer.Audio> renderInMemory(BridgeEngine engine, Project project, Request req) {
		engine.ensureDls();
		Plan plan = plan(engine, project, req);
		List<OfflineRenderer.Audio> l = new ArrayList<>();
		for (Group g : plan.groups()) {
			l.add(renderGroup(project, plan, g, "memory" + g.suffix()).audio());
		}
		return l;
	}

	/**
	 * 렌더하고 WAV + 메타 JSON을 쓴다. 응답 JSON(도구 결과)을 돌려준다.
	 *
	 * @param defaultDir  out_dir가 없을 때 쓸 폴더(서버의 --render-dir)
	 * @param engineLabel 메타 engine 값(예 "MabiIccoBridge 0.2.0 / mmlTools 1.5.12")
	 */
	public static JsonObject renderToFiles(BridgeEngine engine, Project project, Request req, Path defaultDir,
			String engineLabel) {
		long t0 = System.nanoTime();
		// 출력 폴더와 이름부터 검사한다(DLS를 읽기 전에 빨리 거절).
		Path dir = req.outDir() != null ? req.outDir() : defaultDir;
		if (dir == null) {
			throw bad("출력 폴더가 정해지지 않음(out_dir를 줄 것)");
		}
		if (!dir.isAbsolute()) {
			throw bad("out_dir는 절대 경로여야 함: " + dir, "out_dir", dir.toString());
		}
		dir = dir.toAbsolutePath().normalize();
		engine.checkNotProtected(dir);
		String base = req.name() == null ? null : checkName(req.name());

		BridgeEngine.DlsStatus dls = engine.ensureDls();
		Plan plan = plan(engine, project, req);
		if (base == null) {
			base = defaultName(project.score(), plan);
		}

		// 파일 이름을 모두 정하고 미리 검사한다(렌더 뒤에 FILE_EXISTS가 나지 않게).
		List<Path[]> paths = new ArrayList<>();
		for (Group g : plan.groups()) {
			Path wav = dir.resolve(base + g.suffix() + ".wav");
			Path json = dir.resolve(base + g.suffix() + ".json");
			for (Path p : new Path[] { wav, json }) {
				engine.checkNotProtected(p);
				if (!req.overwrite() && Files.exists(p)) {
					Map<String, Object> d = new LinkedHashMap<>();
					d.put("path", p.toString());
					throw new EngineException(EngineException.FILE_EXISTS,
							"이미 있는 파일(새 name을 쓰거나 overwrite: true): " + p, d);
				}
			}
			paths.add(new Path[] { wav, json });
		}
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("path", dir.toString());
			throw new EngineException(EngineException.IO_ERROR, "출력 폴더를 만들지 못함: " + e, d, e);
		}

		System.err.println("[render] 시작: " + plan.fromMeasure() + "~" + plan.toMeasure() + "마디, 트랙 " + plan.tracks()
				+ ", 파트 " + partNames(plan.parts()) + ", split " + plan.split() + ", 파일 " + plan.groups().size() + "개 → "
				+ dir);
		List<Rendered> done = new ArrayList<>();
		for (int i = 0; i < plan.groups().size(); i++) {
			Group g = plan.groups().get(i);
			done.add(renderGroup(project, plan, g, base + g.suffix() + " (" + (i + 1) + "/" + plan.groups().size() + ")"));
		}

		// files 목록(모든 메타에 같은 목록)
		JsonArray files = new JsonArray();
		for (int i = 0; i < done.size(); i++) {
			files.add(fileEntry(paths.get(i)[0], done.get(i), plan));
		}
		String renderedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
		JsonArray outFiles = new JsonArray();
		for (int i = 0; i < done.size(); i++) {
			Rendered r = done.get(i);
			Path wav = paths.get(i)[0];
			Path json = paths.get(i)[1];
			JsonObject meta = meta(engine, project, plan, r, files, dls, renderedAt, engineLabel);
			writeWav(wav, r.audio(), req.overwrite());
			writeText(json, GSON.toJson(meta) + "\n", req.overwrite());
			JsonObject f = fileEntry(wav, r, plan);
			f.addProperty("meta_path", slash(json));
			f.add("duration_sec", meta.getAsJsonObject("audio").get("duration_sec"));
			f.add("peak_dbfs", meta.getAsJsonObject("audio").get("peak_dbfs"));
			f.add("rms_dbfs", meta.getAsJsonObject("audio").get("rms_dbfs"));
			f.add("tail_sec_actual", meta.getAsJsonObject("audio").get("tail_sec_actual"));
			f.addProperty("notes", r.group().notes().size());
			f.addProperty("render_ms", r.millis());
			outFiles.add(f);
		}

		long totalMs = (System.nanoTime() - t0) / 1_000_000;
		double audioSec = 0;
		List<String> warnings = new ArrayList<>();
		for (Rendered r : done) {
			audioSec += r.audio().durationSec();
			for (String w : r.audio().warnings()) {
				String s = r.group().suffix().isEmpty() ? w : r.group().suffix().substring(2) + ": " + w;
				if (!warnings.contains(s)) {
					warnings.add(s);
				}
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("split", plan.split());
		o.addProperty("out_dir", slash(dir));
		o.add("range", rangeJson(plan));
		o.addProperty("measures", plan.toMeasure() - plan.fromMeasure() + 1);
		o.add("files", outFiles);
		JsonArray sk = new JsonArray();
		plan.skipped().forEach(sk::add);
		o.add("skipped", sk);
		JsonArray w = new JsonArray();
		warnings.forEach(w::add);
		o.add("warnings", w);
		o.addProperty("render_ms", totalMs);
		o.addProperty("audio_sec", round(audioSec, 3));
		o.addProperty("speed_x_realtime", totalMs > 0 ? round(audioSec / (totalMs / 1000.0), 1) : null);
		System.err.println("[render] 끝: 파일 " + done.size() + "개, 소리 " + String.format(Locale.ROOT, "%.1f", audioSec)
				+ "초, " + totalMs + "ms");
		return o;
	}

	// ======================================================================
	// 메타 JSON
	// ======================================================================

	static JsonObject meta(BridgeEngine engine, Project project, Plan plan, Rendered r, JsonArray files,
			BridgeEngine.DlsStatus dls, String renderedAt, String engineLabel) {
		MMLScore score = project.score();
		JsonObject m = new JsonObject();
		m.addProperty("schema", SCHEMA);
		m.addProperty("rendered_at", renderedAt);
		m.addProperty("engine", engineLabel);

		JsonObject proj = new JsonObject();
		proj.addProperty("title", score.getTitle());
		Path src = project.source();
		if (src == null) {
			proj.add("source_path", JsonNull.INSTANCE);
		} else {
			proj.addProperty("source_path", src.toString().replace('\\', '/'));
		}
		m.add("project", proj);

		JsonArray d = new JsonArray();
		for (String f : dls.files()) {
			d.add(f.replace('\\', '/'));
		}
		m.add("dls_files", d);

		OfflineRenderer.Audio a = r.audio();
		JsonObject audio = new JsonObject();
		audio.addProperty("sample_rate", (int) SynthSettings.SAMPLE_RATE);
		audio.addProperty("bits", SynthSettings.BITS);
		audio.addProperty("channels", SynthSettings.CHANNELS);
		audio.addProperty("duration_sec", round(a.durationSec(), 4));
		audio.addProperty("peak_dbfs", a.peakDbfs() == null ? null : round(a.peakDbfs(), 2));
		audio.addProperty("tail_sec_actual", round(a.tailSecActual(), 4));
		audio.addProperty("rms_dbfs", a.rmsDbfs() == null ? null : round(a.rmsDbfs(), 2));
		audio.addProperty("frames", a.frames());
		audio.addProperty("tail_sec_limit", plan.tailSec());
		audio.addProperty("end_base_sec", round(a.endBaseSec(), 4));
		audio.addProperty("hit_tail_limit", a.hitTailLimit());
		m.add("audio", audio);

		m.add("synth", SynthSettings.metaJson());
		m.add("range", rangeJson(plan));
		m.add("tempo_map", tempoMap(score));
		m.add("time_signatures", timeSignatures(plan.map()));
		m.add("measures", measures(score, plan));
		m.add("tracks", tracks(engine, score, r.group()));
		m.addProperty("split", plan.split());
		m.add("files", files.deepCopy());

		JsonArray lim = new JsonArray();
		LIMITATIONS.forEach(lim::add);
		if (hasMobilePercussion(score, r.group())) {
			lim.add(LIMIT_MOBILE);
		}
		m.add("limitations", lim);
		JsonArray w = new JsonArray();
		a.warnings().forEach(w::add);
		m.add("warnings", w);
		return m;
	}

	private static JsonObject rangeJson(Plan plan) {
		JsonObject o = new JsonObject();
		o.addProperty("from_measure", plan.fromMeasure());
		o.addProperty("to_measure", plan.toMeasure());
		o.addProperty("from_tick", plan.fromTick());
		o.addProperty("to_tick", plan.toTick());
		o.addProperty("note_rule", NOTE_RULE);
		o.addProperty("note_start_offset_tick", ScoreEvents.NOTE_START_OFFSET_TICK);
		o.addProperty("track_mute_ignored", true);
		o.addProperty("whole_song", plan.wholeSong());
		o.addProperty("range_sec", round(plan.rangeEndSec(), 6));
		return o;
	}

	private static JsonArray tempoMap(MMLScore score) {
		JsonArray a = new JsonArray();
		List<MMLTempoEvent> l = score.getTempoEventList();
		if (l.isEmpty() || l.get(0).getTickOffset() > 0) {
			JsonObject o = new JsonObject();
			o.addProperty("tick", 0);
			o.addProperty("bpm", MMLTempoEvent.INITIAL_TEMPO);
			a.add(o);
		}
		for (MMLTempoEvent t : l) {
			JsonObject o = new JsonObject();
			o.addProperty("tick", t.getTickOffset());
			o.addProperty("bpm", t.getTempo());
			a.add(o);
		}
		return a;
	}

	private static JsonArray timeSignatures(MeasureMap map) {
		JsonArray a = new JsonArray();
		for (MeasureMap.Signature s : map.signatures()) {
			JsonObject o = new JsonObject();
			o.addProperty("tick", s.tick());
			o.addProperty("sig", s.numerator() + "/" + s.denominator());
			a.add(o);
		}
		return a;
	}

	/** 마디마다 시작·끝·박 시각(렌더 시각, 0 = 범위 시작). 박 수는 그 마디의 박자표를 따른다. */
	static JsonArray measures(MMLScore score, Plan plan) {
		MeasureMap map = plan.map();
		JsonArray a = new JsonArray();
		for (int m = plan.fromMeasure(); m <= plan.toMeasure(); m++) {
			long start = map.measureStart(m);
			long end = map.measureEnd(m);
			long bl = map.beatLength(m);
			JsonObject o = new JsonObject();
			o.addProperty("measure", m);
			o.addProperty("tick", start);
			o.addProperty("start_sec", round(sec(score, plan, start), 6));
			o.addProperty("end_sec", round(sec(score, plan, end), 6));
			o.addProperty("sig", map.numeratorAt(m) + "/" + map.denominatorAt(m));
			JsonArray beats = new JsonArray();
			for (long t = start; t < end; t += bl) {
				beats.add(round(sec(score, plan, t), 6));
			}
			o.add("beats", beats);
			a.add(o);
		}
		return a;
	}

	private static double sec(MMLScore score, Plan plan, long tick) {
		return (ms(score, tick) - plan.fromMs()) / 1000.0;
	}

	static JsonArray tracks(BridgeEngine engine, MMLScore score, Group g) {
		Map<Integer, Map<Integer, Integer>> counts = new LinkedHashMap<>();
		for (ScoreEvents.Note n : g.notes()) {
			counts.computeIfAbsent(n.track(), k -> new LinkedHashMap<>()).merge(n.part(), 1, Integer::sum);
		}
		JsonArray a = new JsonArray();
		for (int t : g.tracks()) {
			MMLTrack mt = score.getTrack(t - 1);
			JsonObject o = new JsonObject();
			o.addProperty("track", t);
			o.addProperty("name", mt.getTrackName());
			o.addProperty("program", mt.getProgram());
			InstrumentTable.Instrument inst = engine.instrumentTable().get(mt.getProgram());
			o.addProperty("program_name", inst == null ? null : inst.name());
			o.addProperty("instrument_type", inst == null ? null : inst.type());
			o.addProperty("song_program", mt.getSongProgram());
			o.addProperty("panpot", mt.getPanpot());
			o.addProperty("volume", mt.getVolume());
			JsonObject parts = new JsonObject();
			Map<Integer, Integer> c = counts.getOrDefault(t, Map.of());
			for (int p = 0; p < 4; p++) {
				Integer n = c.get(p);
				if (n == null || n == 0) {
					continue;
				}
				JsonObject po = new JsonObject();
				po.addProperty("notes", n);
				po.addProperty("channel", (t - 1) * 4 + p);
				parts.add(Part.ofIndex(p).key(), po);
			}
			o.add("parts", parts);
			a.add(o);
		}
		return a;
	}

	private static boolean hasMobilePercussion(MMLScore score, Group g) {
		for (int t : g.tracks()) {
			InstClass inst = MabiDLS.getInstance().getInstByProgram(score.getTrack(t - 1).getProgram());
			if (inst != null && inst.getType() == InstType.PERCUSSION_MOBILE) {
				return true;
			}
		}
		return false;
	}

	private static JsonObject fileEntry(Path wav, Rendered r, Plan plan) {
		JsonObject o = new JsonObject();
		o.addProperty("path", wav.toString().replace('\\', '/'));
		JsonArray t = new JsonArray();
		r.group().tracks().forEach(t::add);
		o.add("tracks", t);
		if (r.group().parts().size() == 4) {
			o.addProperty("parts", "all");
		} else {
			JsonArray p = new JsonArray();
			partNames(r.group().parts()).forEach(p::add);
			o.add("parts", p);
		}
		return o;
	}

	static List<String> partNames(Set<Integer> parts) {
		List<String> l = new ArrayList<>();
		for (int p : parts) {
			l.add(Part.ofIndex(p).key());
		}
		return l;
	}

	// ======================================================================
	// 파일 쓰기
	// ======================================================================

	/**
	 * 0.6.2: a new, exclusively created temp file next to target ({@code .<name>.<random>.tmp}). Two bridge processes
	 * rendering the same name at once each get their own temp file; a pre-existing file or link at a guessable name is
	 * never opened.
	 */
	static Path newTemp(Path target) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		return Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
	}

	private static void writeWav(Path target, OfflineRenderer.Audio a, boolean overwrite) {
		Path tmp = null;
		try {
			tmp = newTemp(target);
			try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(a.pcm(), 0, a.length()),
					SynthSettings.FORMAT, a.frames())) {
				AudioSystem.write(in, AudioFileFormat.Type.WAVE, tmp.toFile());
			}
			move(tmp, target, overwrite);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(tmp);
			throw ioError("WAV를 쓰지 못함: " + e, target, e);
		}
	}

	static void writeText(Path target, String text, boolean overwrite) {
		Path tmp = null;
		try {
			tmp = newTemp(target);
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			move(tmp, target, overwrite);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(tmp);
			throw ioError("메타 JSON을 쓰지 못함: " + e, target, e);
		}
	}

	private static void move(Path tmp, Path target, boolean overwrite) throws IOException {
		try {
			if (overwrite) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(tmp, target);
			}
		} catch (FileAlreadyExistsException e) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("path", target.toString());
			deleteQuietly(tmp);
			throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일: " + target, d, e);
		}
	}

	private static void deleteQuietly(Path p) {
		if (p == null) {
			return;
		}
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignore) {
			// 무시
		}
	}

	private static EngineException ioError(String msg, Path p, Throwable e) {
		if (e instanceof EngineException ee) {
			return ee;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("path", p.toString());
		return new EngineException(EngineException.IO_ERROR, msg, d, e);
	}

	// ======================================================================
	// 이름
	// ======================================================================

	/** 사용자가 준 이름: 경로 구분자·윈도 금지 글자 없이, 120자 이하. */
	static String checkName(String name) {
		String n = name.trim();
		if (n.isEmpty() || n.length() > 120 || n.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || n.startsWith(".")
				|| n.endsWith(".") || n.toLowerCase(Locale.ROOT).endsWith(".wav")) {
			throw bad("name은 파일 이름 앞머리(경로·확장자 없이, 120자 이하, \\ / : * ? \" < > | 금지): " + name, "name", name);
		}
		return n;
	}

	/** 기본 이름: 제목 + 범위 + 시각. */
	static String defaultName(MMLScore score, Plan plan) {
		String t = score.getTitle() == null ? "" : score.getTitle().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if (t.length() > 60) {
			t = t.substring(0, 60);
		}
		while (t.startsWith(".")) {
			t = t.substring(1);
		}
		if (t.isEmpty()) {
			t = "render";
		}
		String range = plan.wholeSong() ? "all" : ("m" + plan.fromMeasure() + "-" + plan.toMeasure());
		return t + "_" + range + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
	}

	// ======================================================================

	/** 메타·응답의 경로는 슬래시로 쓴다(Python에서 그대로 쓰기 쉽게). */
	static String slash(Path p) {
		return p.toString().replace('\\', '/');
	}

	static double round(double v, int digits) {
		double f = Math.pow(10, digits);
		return Math.round(v * f) / f;
	}

	private static EngineException bad(String message, Object... kv) {
		Map<String, Object> d = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			d.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return new EngineException(EngineException.BAD_ARGUMENT, message, d);
	}

	private static EngineException bad(String message, Map<String, Object> d) {
		return new EngineException(EngineException.BAD_ARGUMENT, message, d);
	}
}
