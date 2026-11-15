package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.fourthline.mmlTools.MMLEventList;
import jp.fourthline.mmlTools.MMLExceptionList;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLScoreSerializer;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.MMLVerifyException;
import jp.fourthline.mmlTools.TimeSignature;
import jp.fourthline.mmlTools.core.MMLText;
import jp.fourthline.mmlTools.core.ResourceLoader;
import jp.fourthline.mmlTools.parser.MMLParseException;
import mabibridge.midi.MeasureMap;

/**
 * 열린 악보 하나. 프로젝트·트랙·악보 편집·글자 수 도구의 Java판.
 *
 * <p>모든 쓰기는 먼저 {@code getObjectState()}로 상태를 받아 두고, 바꾼 뒤 {@code generateAll()}을 부른다.
 * 생성이 실패하면 {@code putObjectState}로 되돌리고 {@code MML_GENERATE_FAILED}를 던진다.
 * 검증 오류(BAD_NOTE, OVERLAP, OUT_OF_RANGE 등)는 악보를 바꾸기 전에 던진다.</p>
 *
 * <p>트랙 번호는 1부터, 마디 번호는 1부터, 틱은 MML 틱(4분음표 = 96), 음높이는 MIDI 번호다.</p>
 */
public final class Project {

	public static final String SONG_EXCLUDED = "song_excluded";
	public static final String SONG_INCLUDED = "song_included";
	public static final String MODE_STRICT = "strict";
	public static final String MODE_MERGE = "merge";
	public static final String COMPARE_NOTE = "compare는 구현 예정";

	private final BridgeEngine engine;
	private final MMLScore score;
	private Path source;
	private boolean dirty;
	private String songRule = SONG_EXCLUDED;
	private final Map<Integer, String> trackSongRule = new HashMap<>();

	private Project(BridgeEngine engine, MMLScore score, Path source) {
		this.engine = engine;
		this.score = score;
		this.source = source;
	}

	// ======================================================================
	// 만들기·열기·저장
	// ======================================================================

	static Project create(BridgeEngine engine, String title, String author, String timeSignature, Integer tempo) {
		return create(engine, title, author, timeSignature, null, tempo);
	}

	/**
	 * 새 악보. {@code timeSignatures}(박자 목록, {@link #setTimeSignatures}와 같은 규칙)를 주면 그것으로 박자를 놓는다.
	 * {@code timeSignature}와 함께 주면 BAD_ARGUMENT.
	 */
	static Project create(BridgeEngine engine, String title, String author, String timeSignature,
			List<TimeSigSpec> timeSignatures, Integer tempo) {
		if (timeSignature != null && timeSignatures != null) {
			throw new EngineException(BAD_ARGUMENT, "time_signature와 time_signatures 중 하나만 줘야 함",
					details("time_signature", timeSignature));
		}
		List<ResolvedSig> sigList = (timeSignatures == null) ? null : resolveTimeSignatures(timeSignatures);
		String ts = (timeSignature == null || timeSignature.isBlank()) ? "4/4" : timeSignature.trim();
		validateTimeSignature(ts);
		int bpm = (tempo == null) ? MMLTempoEvent.INITIAL_TEMPO : tempo;
		if (bpm < MIN_BPM || bpm > MAX_BPM) {
			throw new EngineException(BAD_ARGUMENT, "템포는 " + MIN_BPM + "~" + MAX_BPM + "(마비노기가 t 명령을 따르는 범위)",
					details("tempo", tempo, "allowed_bpm", List.of(MIN_BPM, MAX_BPM)));
		}
		MMLScore s = new MMLScore();
		s.setTitle(title == null ? "" : title);
		s.setAuthor(author == null ? "" : author);
		s.setBaseTime(ts);
		if (sigList != null) {
			applyTimeSignatures(s, sigList);
		}
		// 기본 120과 다른 템포만 틱 0 템포로 적는다(.mmi의 tempo= 줄과 트랙 MML의 t). 120은 적지 않는다: 게임·엔진 기본이라
		// 글자가 들지 않고 같게 연주된다(트랙마다 t120 4글자를 아낌). project_info의 tempo_at_start(_default)가 실제 템포를 알린다.
		if (bpm != MMLTempoEvent.INITIAL_TEMPO) {
			s.getTempoEventList().add(new MMLTempoEvent(bpm, 0));
		}
		Project p = new Project(engine, s, null);
		p.generate();
		return p;
	}

	private static void validateTimeSignature(String ts) {
		if (!ts.matches("\\d+/\\d+")) {
			throw new EngineException(BAD_ARGUMENT, "박자 형식은 \"4/4\"", details("time_signature", ts));
		}
		String[] s = ts.split("/");
		if (!Arrays.asList(TimeSignature.TIME_COUNT_LIST).contains(String.valueOf(Integer.parseInt(s[0])))
				|| !Arrays.asList(TimeSignature.TIME_BASE_LIST).contains(String.valueOf(Integer.parseInt(s[1])))) {
			throw new EngineException(BAD_ARGUMENT, "엔진이 쓰지 않는 박자: " + ts + " (분자 1~32 또는 64, 분모 1·2·4·8·16·32·64)",
					details("time_signature", ts));
		}
	}

	static Project open(BridgeEngine engine, Path path) {
		Path abs = path.toAbsolutePath().normalize();
		if (!isMmi(abs)) {
			throw new EngineException(EngineException.BAD_PATH, ".mmi 파일만 열 수 있음", details("path", abs.toString()));
		}
		if (!Files.isRegularFile(abs)) {
			throw new EngineException(EngineException.FILE_NOT_FOUND, "파일이 없음: " + abs, details("path", abs.toString()));
		}
		MMLScore s = new MMLScore();
		try (InputStream in = Files.newInputStream(abs)) {
			new MMLScoreSerializer(s).parse(in);
		} catch (MMLParseException | RuntimeException e) {
			throw new EngineException(EngineException.PARSE_FAILED, ".mmi를 해석할 수 없음: " + e.getMessage(),
					details("path", abs.toString()), e);
		} catch (IOException e) {
			throw new EngineException(EngineException.IO_ERROR, "파일을 읽을 수 없음: " + e.getMessage(),
					details("path", abs.toString()), e);
		}
		Project p = new Project(engine, s, abs);
		p.generate();
		return p;
	}

	private static boolean isMmi(Path p) {
		Path name = p.getFileName();
		return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".mmi");
	}

	/**
	 * 새 이름으로 저장한다. {@code .mmi}만, 기존 파일이 있으면 FILE_EXISTS, 보호 폴더 안이면 PROTECTED_PATH.
	 * 쓴 뒤 다시 읽어 {@code getObjectState()}와 바이트가 같은지 확인한다.
	 *
	 * @return 저장한 절대 경로
	 */
	public Path saveAs(Path path) {
		Path abs = path.toAbsolutePath().normalize();
		if (!isMmi(abs)) {
			throw new EngineException(EngineException.BAD_PATH, "저장 파일 이름은 .mmi로 끝나야 함", details("path", abs.toString()));
		}
		engine.checkNotProtected(abs);
		if (Files.exists(abs)) {
			throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일에는 저장하지 않음(새 이름을 쓸 것): " + abs,
					details("path", abs.toString()));
		}
		generate();
		byte[] expected = score.getObjectState();
		try {
			Path parent = abs.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			// writeToOutputStream은 스트림을 닫는다. try-with-resources의 두 번째 close는 무해하다.
			try (OutputStream out = Files.newOutputStream(abs, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				new MMLScoreSerializer(score).writeToOutputStream(out);
			}
			byte[] written = Files.readAllBytes(abs);
			if (!Arrays.equals(expected, written)) {
				throw new EngineException(EngineException.IO_ERROR, "저장한 파일을 다시 읽은 내용이 다름: " + abs,
						details("path", abs.toString(), "expected_bytes", expected.length, "written_bytes", written.length));
			}
		} catch (FileAlreadyExistsException e) {
			throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일에는 저장하지 않음: " + abs,
					details("path", abs.toString()), e);
		} catch (IOException e) {
			throw new EngineException(EngineException.IO_ERROR, "저장 실패: " + e.getMessage(), details("path", abs.toString()), e);
		}
		source = abs;
		dirty = false;
		return abs;
	}

	// ======================================================================
	// 조회
	// ======================================================================

	/** 엔진 악보(compare 등 내부용). 직접 바꾸면 생성 상태가 어긋나므로 읽기만 할 것. */
	public MMLScore score() {
		return score;
	}

	public Path source() {
		return source;
	}

	public boolean dirty() {
		return dirty;
	}

	public String songRule() {
		return songRule;
	}

	/** 프로젝트 기본 글자 수 판정 기준("song_excluded" 또는 "song_included"). */
	public void setSongRule(String rule) {
		songRule = checkRule(rule);
	}

	private static String checkRule(String rule) {
		if (SONG_EXCLUDED.equals(rule) || SONG_INCLUDED.equals(rule)) {
			return rule;
		}
		throw new EngineException(BAD_ARGUMENT, "song_rule은 song_excluded 또는 song_included", details("song_rule", rule));
	}

	/**
	 * {@code project_set}: 제목·작곡자를 바꾼다. null인 값은 그대로 둔다(둘 다 null이면 BAD_ARGUMENT).
	 * 빈 문자열은 지운다. .mmi는 줄 단위({@code title=}, {@code author=})라 줄바꿈 등 제어 문자는 거부한다.
	 * 바꾸면 dirty가 된다(저장은 {@code project_save_as}).
	 */
	public void setInfo(String title, String author) {
		if (title == null && author == null) {
			throw new EngineException(BAD_ARGUMENT, "title, author 중 하나는 있어야 함");
		}
		checkInfoText("title", title);
		checkInfoText("author", author);
		mutate(() -> {
			if (title != null) {
				score.setTitle(title);
			}
			if (author != null) {
				score.setAuthor(author);
			}
		});
	}

	private static void checkInfoText(String key, String v) {
		if (v == null) {
			return;
		}
		for (int i = 0; i < v.length(); i++) {
			if (Character.isISOControl(v.charAt(i))) {
				throw new EngineException(BAD_ARGUMENT, key + "에 줄바꿈·제어 문자는 쓸 수 없음(.mmi는 한 줄에 하나)", details(key, v));
			}
		}
	}

	// ======================================================================
	// 박자·템포 목록
	// ======================================================================

	/** 박자 목록 항목 하나: 마디(1부터, 이 목록의 앞 항목들로 센 악보 마디)와 박자 "분자/분모". */
	public record TimeSigSpec(int measure, String signature) {}

	/** 템포 목록 항목 하나: {@code tick} 또는 {@code pos} 중 하나와 BPM. */
	public record TempoSpec(Long tick, String pos, int bpm) {}

	/**
	 * 템포 BPM 범위 32~255. MabiIcco 템포 대화상자({@code ui/editor/MMLTempoEditor} TempoInputDialog의 스피너 32~255)와
	 * 마비노기 쪽 해석기({@code core/MelodyParser}: 32~255 밖의 {@code t}는 무시)가 같은 범위다.
	 * {@link MMLTempoEvent}의 자료 한계(1~1000)보다 좁다. 밖의 값은 MML에 적혀도 게임에서 무시된다.
	 */
	public static final int MIN_BPM = 32;
	public static final int MAX_BPM = 255;

	/** 박자 마디 번호의 상한(틱이 int를 넘지 않게). */
	public static final int MAX_SIGNATURE_MEASURE = 10000;

	/** 검사한 박자 항목: 마디(1부터), 시작 틱, 분자·분모. */
	private record ResolvedSig(int measure, long tick, int numerator, int denominator) {}

	/**
	 * {@code project_set_time_signatures}: 박자 목록을 통째로 바꾼다. 첫 항목(1마디)은 MabiIcco의 기본 박자
	 * ({@code time=})가 되고, 나머지는 {@code [time-signature]} 항목(MabiIcco 마디 = 이 마디 − 1)이 된다.
	 * 음표·템포·마커는 절대 틱 그대로 남는다(음표의 pos는 새 박자로 다시 읽힌다).
	 */
	public void setTimeSignatures(List<TimeSigSpec> list) {
		List<ResolvedSig> sigs = resolveTimeSignatures(list);
		mutate(() -> applyTimeSignatures(score, sigs));
	}

	private static EngineException badSig(int index, TimeSigSpec e, String reason) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("measure", e == null ? null : e.measure());
		entry.put("signature", e == null ? null : e.signature());
		return new EngineException(BAD_ARGUMENT, "time_signatures[" + index + "]: " + reason,
				details("index", index, "entry", entry, "reason", reason));
	}

	private static List<ResolvedSig> resolveTimeSignatures(List<TimeSigSpec> list) {
		if (list == null || list.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "time_signatures는 항목이 하나 이상인 배열(첫 항목은 1마디)",
					details("argument", "time_signatures"));
		}
		List<ResolvedSig> out = new ArrayList<>();
		long tick = 0;
		for (int i = 0; i < list.size(); i++) {
			TimeSigSpec e = list.get(i);
			if (e == null || e.signature() == null) {
				throw badSig(i, e, "measure와 signature가 필요함");
			}
			int[] nd = parseSignature(e.signature());
			if (nd == null) {
				throw badSig(i, e, "엔진이 쓰지 않는 박자 \"" + e.signature()
						+ "\" (형식 \"분자/분모\", 분자 1~32 또는 64, 분모 1·2·4·8·16·32·64)");
			}
			if (e.measure() < 1 || e.measure() > MAX_SIGNATURE_MEASURE) {
				throw badSig(i, e, "measure는 1~" + MAX_SIGNATURE_MEASURE);
			}
			if (i == 0) {
				if (e.measure() != 1) {
					throw badSig(i, e, "첫 항목은 1마디(틱 0)여야 함");
				}
			} else {
				ResolvedSig prev = out.get(out.size() - 1);
				if (e.measure() == prev.measure()) {
					throw badSig(i, e, "같은 마디(" + e.measure() + ")가 두 번 있음");
				}
				if (e.measure() < prev.measure()) {
					throw badSig(i, e, "마디 순서대로 줘야 함(앞 항목은 " + prev.measure() + "마디)");
				}
				if (nd[0] == prev.numerator() && nd[1] == prev.denominator()) {
					throw badSig(i, e, "앞 항목(" + prev.measure() + "마디)과 같은 박자 " + nd[0] + "/" + nd[1] + " — 빼고 줄 것");
				}
				tick = prev.tick() + (long) (e.measure() - prev.measure()) * measureTicks(prev.numerator(), prev.denominator());
			}
			out.add(new ResolvedSig(e.measure(), tick, nd[0], nd[1]));
		}
		return out;
	}

	private static long measureTicks(int num, int den) {
		return (long) num * (4L * MeasureMap.TPQN96 / den);
	}

	/** "n/d" → {n, d}. MabiIcco 박자 목록({@link TimeSignature#TIME_COUNT_LIST}·{@code TIME_BASE_LIST}) 밖이면 null. */
	private static int[] parseSignature(String sig) {
		String s = sig.trim();
		if (!s.matches("\\d{1,3}/\\d{1,3}")) {
			return null;
		}
		String[] p = s.split("/");
		int n = Integer.parseInt(p[0]);
		int d = Integer.parseInt(p[1]);
		if (!Arrays.asList(TimeSignature.TIME_COUNT_LIST).contains(String.valueOf(n))
				|| !Arrays.asList(TimeSignature.TIME_BASE_LIST).contains(String.valueOf(d))) {
			return null;
		}
		return new int[] { n, d };
	}

	/** 검사한 박자 목록을 악보에 넣는다. MabiIcco가 센 마디·틱이 검사 때 계산과 다르면 IllegalStateException. */
	private static void applyTimeSignatures(MMLScore s, List<ResolvedSig> sigs) {
		s.getTimeSignatureList().clear();
		ResolvedSig first = sigs.get(0);
		s.setBaseTime(first.numerator() + "/" + first.denominator());
		for (int i = 1; i < sigs.size(); i++) {
			ResolvedSig r = sigs.get(i);
			TimeSignature ts;
			try {
				// 생성자가 지금까지 넣은 박자로 틱 → MabiIcco 마디(0부터)를 센다.
				ts = new TimeSignature(s, Math.toIntExact(r.tick()), r.numerator(), r.denominator());
			} catch (jp.fourthline.mmlTools.core.MMLException e) {
				throw new IllegalStateException("TimeSignature 생성 실패: " + r, e);
			}
			if (ts.getMeasureOffset() != r.measure() - 1) {
				throw new IllegalStateException("MabiIcco 마디 계산이 다름: " + r + " → measureOffset " + ts.getMeasureOffset());
			}
			s.addTimeSignature(ts);
		}
		List<TimeSignature> l = s.getTimeSignatureList();
		if (l.size() != sigs.size() - 1) {
			throw new IllegalStateException("박자 목록 수가 다름: " + l.size() + " ≠ " + (sigs.size() - 1));
		}
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i).getTickOffset() != sigs.get(i + 1).tick()) {
				throw new IllegalStateException("박자 틱이 다름: " + l.get(i) + " ≠ " + sigs.get(i + 1));
			}
		}
	}

	/**
	 * {@code project_set_tempos}: 템포 목록을 통째로 바꾼다. {@code pos}는 지금 박자 목록으로 푼다(박자를 먼저 바꿀 것).
	 * 틱 0 항목이 있어야 하고, 틱 순서대로, 한 틱에 하나, 틱은 격자 위({@link NoteSpec#onGrid}, 셋잇단 격자 포함),
	 * BPM은 {@link #MIN_BPM}~{@link #MAX_BPM}.
	 * 템포 목록은 악보 전역 하나({@code getTempoEventList()})이고 모든 트랙이 같은 목록 객체를 본다.
	 */
	public void setTempos(List<TempoSpec> list) {
		if (list == null || list.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "tempos는 항목이 하나 이상인 배열(틱 0 항목 필요)", details("argument", "tempos"));
		}
		MeasureMap map = measureMap();
		List<long[]> resolved = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			TempoSpec e = list.get(i);
			long tick;
			if (e.tick() != null && e.pos() != null) {
				throw badTempo(i, e, "tick과 pos 중 하나만 줘야 함");
			} else if (e.tick() != null) {
				tick = e.tick();
				if (tick < 0 || tick > Integer.MAX_VALUE) {
					throw badTempo(i, e, "tick은 0 이상 정수");
				}
			} else if (e.pos() != null) {
				Matcher m = TEMPO_POS.matcher(e.pos());
				if (!m.matches()) {
					throw badTempo(i, e, "pos 형식은 \"마디:박:틱\"(예 \"10:1:0\")");
				}
				try {
					tick = map.tickOf(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)));
				} catch (IllegalArgumentException ex) {
					throw badTempo(i, e, "지금 박자 목록에는 없는 위치: " + ex.getMessage());
				}
			} else {
				throw badTempo(i, e, "tick 또는 pos가 필요함");
			}
			if (!NoteSpec.onGrid(map, tick)) {
				throw badTempo(i, e, "템포 위치는 " + NoteSpec.GRID_TEXT + "여야 함: 틱 " + tick);
			}
			if (e.bpm() < MIN_BPM || e.bpm() > MAX_BPM) {
				throw badTempo(i, e, "bpm은 " + MIN_BPM + "~" + MAX_BPM + " 정수(MabiIcco 템포 대화상자·마비노기가 t 명령을 따르는 범위): "
						+ e.bpm(), "allowed_bpm", List.of(MIN_BPM, MAX_BPM));
			}
			if (i == 0 && tick != 0) {
				throw badTempo(i, e, "첫 항목은 틱 0이어야 함(곡 시작 템포)");
			}
			if (i > 0) {
				long prev = resolved.get(i - 1)[0];
				if (tick == prev) {
					throw badTempo(i, e, "한 틱(" + tick + ")에 템포가 두 개");
				}
				if (tick < prev) {
					throw badTempo(i, e, "틱 순서대로 줘야 함(앞 항목은 틱 " + prev + ")");
				}
			}
			resolved.add(new long[] { tick, e.bpm() });
		}
		mutate(() -> {
			List<MMLTempoEvent> global = score.getTempoEventList();
			global.clear();
			for (long[] r : resolved) {
				global.add(new MMLTempoEvent((int) r[1], (int) r[0]));
			}
			// 트랙들은 악보 전역 목록 객체를 그대로 본다(MMLScore.addTrack). 혹시 다르면 맞춘다.
			for (MMLTrack t : score.getTrackList()) {
				if (t.getGlobalTempoList() != global) {
					t.setGlobalTempoList(global);
				}
			}
		});
	}

	private static final Pattern TEMPO_POS = Pattern.compile("\\s*(\\d{1,6})\\s*:\\s*(\\d{1,6})\\s*:\\s*(\\d{1,9})\\s*");

	private static EngineException badTempo(int index, TempoSpec e, String reason, Object... extra) {
		Map<String, Object> entry = new LinkedHashMap<>();
		if (e.tick() != null) {
			entry.put("tick", e.tick());
		}
		if (e.pos() != null) {
			entry.put("pos", e.pos());
		}
		entry.put("bpm", e.bpm());
		Map<String, Object> d = details("index", index, "entry", entry, "reason", reason);
		d.putAll(details(extra));
		return new EngineException(BAD_ARGUMENT, "tempos[" + index + "]: " + reason, d);
	}

	/** 악보 박자 목록(기본 박자 + {@code getTimeSignatureList()})으로 만든 96틱 마디 지도. */
	public MeasureMap measureMap() {
		List<MeasureMap.Signature> sigs = new ArrayList<>();
		String[] b = score.getBaseTime().split("/");
		sigs.add(new MeasureMap.Signature(0, Integer.parseInt(b[0].trim()), Integer.parseInt(b[1].trim())));
		for (TimeSignature ts : score.getTimeSignatureList()) {
			int den = (4 * MeasureMap.TPQN96) / ts.getBaseTick();
			sigs.add(new MeasureMap.Signature(ts.getTickOffset(), ts.getNumTime(), den));
		}
		return MeasureMap.of(MeasureMap.TPQN96, sigs);
	}

	/** {@code project_info}. */
	public ProjectInfo info() {
		MeasureMap map = measureMap();
		List<ProjectInfo.TimeSig> sigs = new ArrayList<>();
		for (MeasureMap.Signature s : map.signatures()) {
			sigs.add(new ProjectInfo.TimeSig((int) s.tick(), map.measureOf(s.tick()), s.numerator() + "/" + s.denominator()));
		}
		List<ProjectInfo.TempoView> tempos = new ArrayList<>();
		for (MMLTempoEvent t : score.getTempoEventList()) {
			tempos.add(new ProjectInfo.TempoView(t.getTickOffset(), map.position(t.getTickOffset()).toString(), t.getTempo()));
		}
		int totalTicks = score.getTotalTickLength();
		int measures = (totalTicks > 0) ? map.measureOf(totalTicks - 1L) : 0;
		// time_signature는 틱 0에서 실제로 쓰는 박자. 파일 머리말 time=과 [time-signature] 0=…이 다르면 뒤쪽이 이긴다
		String base = score.getBaseTime();
		String effective = (!sigs.isEmpty() && sigs.get(0).tick() == 0) ? sigs.get(0).signature() : base;
		boolean overridden = !normalizeSig(base).equals(effective);
		return new ProjectInfo(score.getTitle(), score.getAuthor(), effective, sigs, tempos, measures, totalTicks,
				score.getTotalTime() / 1000.0, tracks(), source == null ? null : source.toString(), dirty,
				engine.snapshots().size(), songRule, base, overridden, MMLTempoEvent.searchOnTick(score.getTempoEventList(), 0),
				!MMLTempoEvent.searchEqualsTick(score.getTempoEventList(), 0));
	}

	/** "4 / 4" → "4/4"(숫자만). 읽을 수 없으면 그대로. */
	private static String normalizeSig(String s) {
		try {
			String[] p = s.split("/");
			return Integer.parseInt(p[0].trim()) + "/" + Integer.parseInt(p[1].trim());
		} catch (RuntimeException e) {
			return s;
		}
	}

	/** 트랙 요약 목록. */
	public List<TrackSummary> tracks() {
		List<TrackSummary> l = new ArrayList<>();
		for (int i = 1; i <= score.getTrackCount(); i++) {
			l.add(summary(i));
		}
		return l;
	}

	public int trackCount() {
		return score.getTrackCount();
	}

	private MMLTrack track(int n) {
		if (n < 1 || n > score.getTrackCount()) {
			throw new EngineException(EngineException.NO_TRACK, "트랙 " + n + "번이 없음(1~" + score.getTrackCount() + ")",
					details("track", n, "track_count", score.getTrackCount()));
		}
		return score.getTrack(n - 1);
	}

	private TrackSummary summary(int n) {
		MMLTrack t = track(n);
		InstrumentTable.Instrument inst = engine.instrumentTable().get(t.getProgram());
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			counts.put(p.key(), t.getMMLEventAtIndex(p.index()).getMMLNoteEventList().size());
		}
		int sp = t.getSongProgram();
		String spName;
		if (sp == MMLTrack.NO_CHORUS) {
			spName = "없음";
		} else if (sp == MMLTrack.EXCLUDE_SONG) {
			spName = "노래 제외";
		} else {
			InstrumentTable.Instrument si = engine.instrumentTable().get(sp);
			spName = (si == null) ? null : si.name();
		}
		boolean mainIsSong = inst != null && ("V".equals(inst.type()) || "C".equals(inst.type()));
		String songSounding = (!mainIsSong && sp >= 0) ? SoundingPitch.summary(sp) : null;
		return new TrackSummary(n, t.getTrackName(), t.getProgram(), inst == null ? null : inst.name(),
				inst == null ? null : inst.type(), sp, spName, t.getPanpot(), t.getVolume(), counts,
				inst == null ? List.of() : inst.allowedParts(), ruleOf(n),
				SoundingPitch.summary(t.getProgram()), songSounding,
				inst == null ? null : InstrumentTable.velocityRule(inst.type()),
				inst == null ? null : InstrumentTable.velocityMax(inst.type()));
	}

	/** 트랙 n(1부터)의 파트 p를 실제로 울리는 악기 번호(노래 파트는 노래 악기, {@link SoundingPitch#programFor}). */
	public int soundingProgram(int n, Part p) {
		return soundingProgram(track(n), p);
	}

	private int soundingProgram(MMLTrack t, Part p) {
		InstrumentTable.Instrument inst = engine.instrumentTable().get(t.getProgram());
		return SoundingPitch.programFor(t.getProgram(), t.getSongProgram(), p, inst == null ? null : inst.type());
	}

	/**
	 * {@code score_notes} part "all"이 보여 줄 파트(Part 순서): 트랙 악기가 쓰는 파트, 노래 악기(song_program ≥ 0)가 있으면
	 * song, 그리고 (악기가 쓰지 않아도) 음표가 들어 있는 파트.
	 */
	public List<String> partsOf(int track) {
		MMLTrack t = track(track);
		InstrumentTable.Instrument inst = engine.instrumentTable().get(t.getProgram());
		List<String> l = new ArrayList<>();
		for (Part p : Part.values()) {
			boolean allowed = inst == null ? p != Part.SONG : inst.allowsPart(p);
			boolean song = p == Part.SONG && t.getSongProgram() >= 0;
			boolean has = !t.getMMLEventAtIndex(p.index()).getMMLNoteEventList().isEmpty();
			if (allowed || song || has) {
				l.add(p.key());
			}
		}
		return l;
	}

	/** 파트의 음표 중 시작 틱이 [from 마디 시작, to 마디 끝) 안인 것. 음표마다 들리는 음(sounding_pitch)을 채운다. */
	public List<NoteView> notes(int track, String part, int fromMeasure, int toMeasure) {
		MMLTrack t = track(track);
		Part p = Part.of(part);
		MeasureMap map = measureMap();
		long[] r = range(map, fromMeasure, toMeasure);
		int prog = soundingProgram(t, p);
		List<NoteView> l = new ArrayList<>();
		for (MMLNoteEvent e : t.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
			if (e.getTickOffset() >= r[0] && e.getTickOffset() < r[1]) {
				l.add(NoteSpec.view(e, map).withSounding(prog));
			}
		}
		return l;
	}

	private static long[] range(MeasureMap map, int from, int to) {
		if (from < 1 || to < from) {
			throw new EngineException(BAD_ARGUMENT, "마디 범위가 틀림: " + from + "~" + to + " (1 이상, from ≤ to)",
					details("from_measure", from, "to_measure", to));
		}
		return new long[] { map.measureStart(from), map.measureEnd(to) };
	}

	// ======================================================================
	// 트랙
	// ======================================================================

	/** 트랙 하나에서 바꿀 값. null인 필드는 그대로 둔다. */
	public record TrackChanges(String name, Integer program, Integer songProgram, Integer panpot, Integer volume,
			String songRule) {}

	/**
	 * 트랙을 더한다.
	 *
	 * @param songProgram null이면 −1(코러스 없음). −2는 노래 제외. 그 밖에는 악기표의 코러스(C) 타입 악기 번호
	 * @param panpot      null이면 64(0~127)
	 * @param volume      null이면 100(0~127)
	 * @return 새 트랙 번호(1부터)
	 */
	public int addTrack(String name, int program, Integer songProgram, Integer panpot, Integer volume) {
		if (score.getTrackCount() >= MMLScore.MAX_TRACK) {
			throw new EngineException(EngineException.TOO_MANY_TRACKS, "트랙은 최대 " + MMLScore.MAX_TRACK + "개",
					details("track_count", score.getTrackCount()));
		}
		String nm = (name == null || name.isEmpty()) ? "Track" + (score.getTrackCount() + 1) : name;
		checkName(nm);
		checkProgram(program);
		int sp = (songProgram == null) ? MMLTrack.NO_CHORUS : songProgram;
		checkSongProgram(sp);
		int pan = (panpot == null) ? 64 : panpot;
		int vol = (volume == null) ? MMLTrack.INITIAL_VOLUME : volume;
		check0to127("panpot", pan);
		check0to127("volume", vol);
		int[] idx = new int[1];
		mutate(() -> {
			MMLTrack t = new MMLTrack();
			t.setTrackName(nm);
			t.setProgram(program);
			t.setSongProgram(sp);
			t.setPanpot(pan);
			t.setVolume(vol);
			// addTrack이 트랙의 템포 리스트를 악보 전역 리스트로 바꾼다(템포는 악보 전역).
			int i = score.addTrack(t);
			if (i < 0) {
				throw new EngineException(EngineException.TOO_MANY_TRACKS, "트랙은 최대 " + MMLScore.MAX_TRACK + "개");
			}
			idx[0] = i + 1;
		});
		return idx[0];
	}

	/** 트랙 값을 바꾼다. 악기가 바뀌면 MML(템포 배치)이 바뀔 수 있어 다시 생성한다. */
	public TrackSummary setTrack(int track, TrackChanges c) {
		track(track);
		if (c == null) {
			return summary(track);
		}
		if (c.name() != null) {
			checkName(c.name());
		}
		if (c.program() != null) {
			checkProgram(c.program());
		}
		if (c.songProgram() != null) {
			checkSongProgram(c.songProgram());
		}
		if (c.panpot() != null) {
			check0to127("panpot", c.panpot());
		}
		if (c.volume() != null) {
			check0to127("volume", c.volume());
		}
		if (c.songRule() != null) {
			checkRule(c.songRule());
		}
		boolean scoreChange = c.name() != null || c.program() != null || c.songProgram() != null || c.panpot() != null
				|| c.volume() != null;
		if (scoreChange) {
			mutate(() -> {
				MMLTrack t = score.getTrack(track - 1);
				if (c.name() != null) {
					t.setTrackName(c.name());
				}
				if (c.program() != null) {
					t.setProgram(c.program());
				}
				if (c.songProgram() != null) {
					t.setSongProgram(c.songProgram());
				}
				if (c.panpot() != null) {
					t.setPanpot(c.panpot());
				}
				if (c.volume() != null) {
					t.setVolume(c.volume());
				}
			});
		}
		if (c.songRule() != null) {
			trackSongRule.put(track, c.songRule());
		}
		return summary(track);
	}

	private static void checkName(String name) {
		if (name.length() > 32) {
			throw new EngineException(BAD_ARGUMENT, "트랙 이름은 32자까지(엔진 한도)", details("name", name));
		}
	}

	private void checkProgram(int program) {
		if (!engine.instrumentTable().contains(program)) {
			throw new EngineException(BAD_ARGUMENT, "악기표에 없는 악기 번호: " + program, details("program", program));
		}
	}

	/**
	 * 노래 악기(song_program) 검사. MabiIcco는 트랙의 노래 악기 칸에 코러스(타입 C) 악기만 보여 준다
	 * ({@code MMLTrackView}의 songComboBox = {@code InstType.SUB_INST_LIST} = CHORUS, + 없음·노래 제외). 노래·마이크(타입 V,
	 * 80~94·120·121)는 트랙 악기(program)로만 고를 수 있으므로 여기서는 거부한다.
	 */
	private void checkSongProgram(int sp) {
		if (sp == MMLTrack.NO_CHORUS || sp == MMLTrack.EXCLUDE_SONG) {
			return;
		}
		InstrumentTable.Instrument i = engine.instrumentTable().get(sp);
		if (i == null || !"C".equals(i.type())) {
			List<Integer> chorus = new ArrayList<>();
			for (InstrumentTable.Instrument c : engine.instrumentTable().list("C")) {
				chorus.add(c.program());
			}
			String why = (i != null && "V".equals(i.type()))
					? " — " + sp + "(" + i.name() + ")은 노래·마이크(V) 악기라 MabiIcco에서 다른 트랙의 노래 악기로 고를 수 없음. "
							+ "노래·마이크는 track_add·track_set의 program으로 둘 것(그 트랙은 노래 파트만 씀)"
					: "";
			throw new EngineException(BAD_ARGUMENT,
					"song_program은 −1(없음), −2(노래 제외) 또는 코러스(C) 악기 번호 " + chorus + why,
					details("song_program", sp, "allowed_song_programs", chorus,
							"type", i == null ? null : i.type()));
		}
	}

	private static void check0to127(String field, int v) {
		if (v < 0 || v > 127) {
			throw new EngineException(BAD_ARGUMENT, field + "는 0~127", details(field, v));
		}
	}

	// ======================================================================
	// 악보 편집
	// ======================================================================

	/**
	 * 음표를 넣는다({@code score_put_notes}).
	 *
	 * @param mode strict(기본): 기존 음표와 하나라도 겹치면 아무것도 바꾸지 않고 OVERLAP.
	 *             merge: 엔진의 자동 정리(앞 음은 잘리고 뒤에 겹친 음은 지워짐)를 허용하고 그 결과를 affected로 보고.
	 *             입력 음표끼리 겹치면 두 모드 모두 OVERLAP.
	 */
	public EditResult putNotes(int track, String part, List<NoteSpec> notes, String mode, boolean align) {
		MMLTrack t = track(track);
		Part p = Part.of(part);
		String m = (mode == null || mode.isBlank()) ? MODE_STRICT : mode.trim().toLowerCase(Locale.ROOT);
		if (!MODE_STRICT.equals(m) && !MODE_MERGE.equals(m)) {
			throw new EngineException(BAD_ARGUMENT, "mode는 strict 또는 merge", details("mode", mode));
		}
		if (notes == null) {
			notes = List.of();
		}
		MeasureMap map = measureMap();
		int prog = soundingProgram(t, p);
		List<EditResult.AlignedNote> aligned = new ArrayList<>();
		List<NoteSpec.Resolved> rs = resolveAll(p, notes, map, align, aligned);
		checkInputOverlaps(p, rs, map, prog);
		MMLEventList list = t.getMMLEventAtIndex(p.index());
		if (MODE_STRICT.equals(m)) {
			List<Map<String, Object>> pairs = new ArrayList<>();
			for (NoteSpec.Resolved r : rs) {
				for (MMLNoteEvent e : list.getMMLNoteEventList()) {
					// 구간이 겹치는지 직접 본다. MMLEventList.isOverlapNote는 새 음이 기존 음을 통째로 덮는 경우
					// (새 음 시작 < 기존 시작, 새 음 끝 > 기존 끝)를 놓치므로 쓰지 않는다.
					if (r.tick() < e.getEndTick() && e.getTickOffset() < r.end()) {
						Map<String, Object> pair = new LinkedHashMap<>();
						pair.put("index", r.index());
						pair.put("new", view(r, map, prog));
						pair.put("existing", NoteSpec.view(e, map).withSounding(prog));
						pairs.add(pair);
					}
				}
			}
			if (!pairs.isEmpty()) {
				throw new EngineException(EngineException.OVERLAP,
						p.key() + " 파트의 기존 음표와 겹치는 음표가 " + pairs.size() + "쌍 있어 아무것도 넣지 않음(merge면 엔진 규칙으로 정리)",
						details("track", track, "part", p.key(), "pairs", pairs));
			}
		}
		List<String> warnings = partWarnings(t, p);
		warnings.addAll(silentWarnings(p, prog, rs, map));
		List<EditResult.AffectedNote> affected = new ArrayList<>();
		TreeSet<Integer> changed = new TreeSet<>();
		mutate(() -> {
			MMLEventList l = score.getTrack(track - 1).getMMLEventAtIndex(p.index());
			List<Before> before = before(l);
			for (NoteSpec.Resolved r : rs) {
				l.addMMLNoteEvent(r.toEvent());
				addSpan(changed, map, r.tick(), r.end());
			}
			diff(p, before, l, map, affected, changed, prog);
		});
		int removed = (int) affected.stream().filter(a -> "removed".equals(a.change())).count();
		addUnisonWarning(warnings, track);
		return result(track, List.of(p.key()), changed, rs.size(), removed, aligned, affected, warnings);
	}

	/** 시작 틱이 [from 마디 시작, to 마디 끝) 안인 음표를 지운다. */
	public EditResult deleteNotes(int track, String part, int fromMeasure, int toMeasure) {
		int prog = soundingProgram(track(track), Part.of(part));
		Part p = Part.of(part);
		MeasureMap map = measureMap();
		long[] r = range(map, fromMeasure, toMeasure);
		List<EditResult.AffectedNote> affected = new ArrayList<>();
		TreeSet<Integer> changed = new TreeSet<>();
		mutate(() -> {
			List<MMLNoteEvent> l = score.getTrack(track - 1).getMMLEventAtIndex(p.index()).getMMLNoteEventList();
			for (MMLNoteEvent e : new ArrayList<>(l)) {
				if (e.getTickOffset() >= r[0] && e.getTickOffset() < r[1]) {
					affected.add(new EditResult.AffectedNote(p.key(), "deleted", NoteSpec.view(e, map).withSounding(prog), null));
					addSpan(changed, map, e.getTickOffset(), e.getEndTick());
					l.remove(e);
				}
			}
		});
		return result(track, List.of(p.key()), changed, 0, affected.size(), List.of(), affected, new ArrayList<>());
	}

	/** 시작 틱이 ticks 중 하나와 같은 음표를 지운다. 없는 틱은 warnings에 적는다. */
	public EditResult deleteNotes(int track, String part, List<Integer> ticks) {
		int prog = soundingProgram(track(track), Part.of(part));
		Part p = Part.of(part);
		MeasureMap map = measureMap();
		List<EditResult.AffectedNote> affected = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		TreeSet<Integer> changed = new TreeSet<>();
		mutate(() -> {
			List<MMLNoteEvent> l = score.getTrack(track - 1).getMMLEventAtIndex(p.index()).getMMLNoteEventList();
			for (Integer tick : (ticks == null ? List.<Integer>of() : ticks)) {
				MMLNoteEvent found = null;
				for (MMLNoteEvent e : l) {
					if (tick != null && e.getTickOffset() == tick) {
						found = e;
						break;
					}
				}
				if (found == null) {
					warnings.add("틱 " + tick + "에서 시작하는 음표가 없음");
					continue;
				}
				affected.add(new EditResult.AffectedNote(p.key(), "deleted", NoteSpec.view(found, map).withSounding(prog), null));
				addSpan(changed, map, found.getTickOffset(), found.getEndTick());
				l.remove(found);
			}
		});
		return result(track, List.of(p.key()), changed, 0, affected.size(), List.of(), affected, warnings);
	}

	/**
	 * 명시된 파트만 그 마디 범위를 비우고 새로 채운다({@code score_replace_range}). 한 번의 생성.
	 * 비우기 = 시작 틱이 범위 안인 음표를 지우고, 범위 앞에서 시작해 범위 안으로 들어오는 음표는 범위 시작에서 자른다.
	 * 새 음표가 범위 밖에 걸치면(시작 &lt; 범위 시작 또는 끝 &gt; 범위 끝) 아무것도 바꾸지 않고 OUT_OF_RANGE.
	 */
	public EditResult replaceRange(int track, int fromMeasure, int toMeasure, Map<String, List<NoteSpec>> parts,
			boolean align) {
		MMLTrack t = track(track);
		MeasureMap map = measureMap();
		long[] r = range(map, fromMeasure, toMeasure);
		if (parts == null || parts.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "parts가 비어 있음(바꿀 파트를 하나 이상 줄 것)");
		}
		Map<Part, List<NoteSpec.Resolved>> resolved = new EnumMap<>(Part.class);
		List<EditResult.AlignedNote> aligned = new ArrayList<>();
		for (Map.Entry<String, List<NoteSpec>> en : parts.entrySet()) {
			Part p = Part.of(en.getKey());
			int prog = soundingProgram(t, p);
			if (resolved.containsKey(p)) {
				throw new EngineException(BAD_ARGUMENT, "같은 파트가 두 번 들어 있음: " + p.key());
			}
			List<NoteSpec.Resolved> rs = resolveAll(p, en.getValue() == null ? List.of() : en.getValue(), map, align, aligned);
			for (NoteSpec.Resolved n : rs) {
				if (n.tick() < r[0] || n.end() > r[1]) {
					throw new EngineException(EngineException.OUT_OF_RANGE,
							p.key() + " 음표 " + n.index() + "번이 범위 " + fromMeasure + "~" + toMeasure + "마디(틱 " + r[0] + "~" + r[1]
									+ ") 밖에 걸침",
							details("part", p.key(), "index", n.index(), "note", view(n, map, prog), "range_ticks", List.of(r[0], r[1])));
				}
			}
			checkInputOverlaps(p, rs, map, prog);
			resolved.put(p, rs);
		}
		List<String> warnings = new ArrayList<>();
		for (Part p : resolved.keySet()) {
			if (!resolved.get(p).isEmpty()) {
				warnings.addAll(partWarnings(t, p));
				warnings.addAll(silentWarnings(p, soundingProgram(t, p), resolved.get(p), map));
			}
		}
		List<EditResult.AffectedNote> affected = new ArrayList<>();
		TreeSet<Integer> changed = new TreeSet<>();
		int[] counts = new int[2];
		mutate(() -> {
			MMLTrack tt = score.getTrack(track - 1);
			for (Map.Entry<Part, List<NoteSpec.Resolved>> en : resolved.entrySet()) {
				Part p = en.getKey();
				int prog = soundingProgram(tt, p);
				MMLEventList el = tt.getMMLEventAtIndex(p.index());
				List<MMLNoteEvent> l = el.getMMLNoteEventList();
				for (MMLNoteEvent e : new ArrayList<>(l)) {
					if (e.getTickOffset() >= r[0] && e.getTickOffset() < r[1]) {
						affected.add(new EditResult.AffectedNote(p.key(), "cleared", NoteSpec.view(e, map).withSounding(prog), null));
						addSpan(changed, map, e.getTickOffset(), e.getEndTick());
						l.remove(e);
						counts[1]++;
					} else if (e.getTickOffset() < r[0] && e.getEndTick() > r[0]) {
						NoteView b = NoteSpec.view(e, map).withSounding(prog);
						addSpan(changed, map, r[0], e.getEndTick());
						e.setTick((int) (r[0] - e.getTickOffset()));
						affected.add(new EditResult.AffectedNote(p.key(), "truncated", b, NoteSpec.view(e, map).withSounding(prog)));
					}
				}
				List<Before> before = before(el);
				for (NoteSpec.Resolved n : en.getValue()) {
					el.addMMLNoteEvent(n.toEvent());
					addSpan(changed, map, n.tick(), n.end());
					counts[0]++;
				}
				// 비운 뒤라 엔진 자동 정리는 일어나지 않아야 한다. 일어나면 그대로 보고한다.
				diff(p, before, el, map, affected, changed, prog);
			}
		});
		List<String> keys = new ArrayList<>();
		for (Part p : resolved.keySet()) {
			keys.add(p.key());
		}
		addUnisonWarning(warnings, track);
		return result(track, keys, changed, counts[0], counts[1], aligned, affected, warnings);
	}

	private List<NoteSpec.Resolved> resolveAll(Part p, List<NoteSpec> notes, MeasureMap map, boolean align,
			List<EditResult.AlignedNote> alignedOut) {
		List<NoteSpec.Resolved> rs = new ArrayList<>();
		List<NoteSpec.Aligned> al = new ArrayList<>();
		for (int i = 0; i < notes.size(); i++) {
			NoteSpec s = notes.get(i);
			if (s == null) {
				throw new EngineException(EngineException.BAD_NOTE, "음표 " + i + "번이 비어 있음", details("index", i, "part", p.key()));
			}
			try {
				rs.add(s.resolve(map, align, i, al));
			} catch (EngineException e) {
				Map<String, Object> d = new LinkedHashMap<>(e.details());
				d.put("part", p.key());
				throw new EngineException(e.code(), p.key() + " " + e.getMessage(), d, e);
			}
		}
		for (NoteSpec.Aligned a : al) {
			alignedOut.add(new EditResult.AlignedNote(p.key(), a.index(), a.field(), a.from(), a.to()));
		}
		rs.sort(Comparator.comparingInt(NoteSpec.Resolved::tick));
		return rs;
	}

	/** 입력 음표끼리 겹치면 OVERLAP(한 파트는 한 번에 한 음만 낸다). */
	private static void checkInputOverlaps(Part p, List<NoteSpec.Resolved> sorted, MeasureMap map, int prog) {
		List<Map<String, Object>> pairs = new ArrayList<>();
		for (int i = 1; i < sorted.size(); i++) {
			NoteSpec.Resolved a = sorted.get(i - 1);
			NoteSpec.Resolved b = sorted.get(i);
			if (b.tick() < a.end()) {
				Map<String, Object> pair = new LinkedHashMap<>();
				pair.put("a_index", a.index());
				pair.put("a", view(a, map, prog));
				pair.put("b_index", b.index());
				pair.put("b", view(b, map, prog));
				pairs.add(pair);
			}
		}
		if (!pairs.isEmpty()) {
			throw new EngineException(EngineException.OVERLAP,
					p.key() + " 파트 입력 음표끼리 겹침(한 파트는 한 번에 한 음). 화음은 chord1/chord2 파트나 다른 트랙에 나눌 것",
					details("part", p.key(), "between", "input", "pairs", pairs));
		}
	}

	private static NoteView view(NoteSpec.Resolved r, MeasureMap map, int prog) {
		return new NoteView(r.tick(), map.position(r.tick()).toString(), r.len(), r.pitch(), NoteSpec.pitchName(r.pitch()), r.vel())
				.withSounding(prog);
	}

	/** 넣기 전 음표(엔진은 기존 음표 객체의 길이를 바꾸거나 목록에서 빼므로 원래 값을 따로 둔다). */
	private record Before(MMLNoteEvent ref, int tickOffset, int tick, int note, int vel) {}

	private static List<Before> before(MMLEventList l) {
		List<Before> b = new ArrayList<>();
		for (MMLNoteEvent e : l.getMMLNoteEventList()) {
			b.add(new Before(e, e.getTickOffset(), e.getTick(), e.getNote(), e.getVelocity()));
		}
		return b;
	}

	/** 넣기 전 음표 목록과 지금 목록을 비교해 잘리거나 지워진 기존 음표를 찾는다. */
	private static void diff(Part p, List<Before> before, MMLEventList after, MeasureMap map,
			List<EditResult.AffectedNote> out, Set<Integer> changed, int prog) {
		Map<MMLNoteEvent, Boolean> now = new IdentityHashMap<>();
		for (MMLNoteEvent e : after.getMMLNoteEventList()) {
			now.put(e, Boolean.TRUE);
		}
		for (Before b : before) {
			MMLNoteEvent e = b.ref();
			NoteView was = new NoteView(b.tickOffset(), map.position(b.tickOffset()).toString(), b.tick(), b.note() + 12,
					NoteSpec.pitchName(b.note() + 12), b.vel()).withSounding(prog);
			if (!now.containsKey(e)) {
				out.add(new EditResult.AffectedNote(p.key(), "removed", was, null));
				addSpan(changed, map, b.tickOffset(), b.tickOffset() + b.tick());
			} else if (e.getTick() != b.tick()) {
				out.add(new EditResult.AffectedNote(p.key(), "truncated", was, NoteSpec.view(e, map).withSounding(prog)));
				addSpan(changed, map, e.getEndTick(), b.tickOffset() + b.tick());
			}
		}
	}

	private static void addSpan(Set<Integer> set, MeasureMap map, long start, long end) {
		if (end <= start) {
			end = start + 1;
		}
		int a = map.measureOf(start);
		int b = map.measureOf(end - 1);
		for (int m = a; m <= b; m++) {
			set.add(m);
		}
	}

	private List<String> partWarnings(MMLTrack t, Part p) {
		List<String> w = new ArrayList<>();
		InstrumentTable.Instrument inst = engine.instrumentTable().get(t.getProgram());
		if (inst != null && !inst.allowsPart(p)) {
			w.add("악기 " + t.getProgram() + "(" + inst.name() + ", 타입 " + inst.type() + ")는 " + p.key()
					+ " 파트를 쓰지 않음(허용: " + inst.allowedParts() + ")");
		}
		if (p == Part.SONG) {
			if (t.getSongProgram() == MMLTrack.EXCLUDE_SONG) {
				w.add("트랙이 노래 제외(song_program −2)라 노래 파트는 마비노기용 MML에 실리지 않음");
			} else if (t.getSongProgram() == MMLTrack.NO_CHORUS && (inst == null || !inst.allowsPart(Part.SONG))) {
				w.add("노래 파트에 넣었지만 노래 악기(song_program)가 없음");
			}
		}
		return w;
	}

	/**
	 * 무음 구간(베이스 56~61 등)에 적은 음이 있으면 경고 한 줄("silent_note: …"). 소리가 나지 않으므로 막지는 않고 알린다.
	 */
	static List<String> silentWarnings(Part p, int prog, List<NoteSpec.Resolved> rs, MeasureMap map) {
		List<String> pos = new ArrayList<>();
		for (NoteSpec.Resolved n : rs) {
			if (SoundingPitch.soundingOf(prog, n.pitch()) == SoundingPitch.SILENT) {
				pos.add(map.position(n.tick()) + " " + NoteSpec.pitchName(n.pitch()));
			}
		}
		if (pos.isEmpty()) {
			return List.of();
		}
		String ex = String.join(", ", pos.subList(0, Math.min(5, pos.size()))) + (pos.size() > 5 ? " …" : "");
		return List.of("silent_note: " + p.key() + " 파트의 " + pos.size() + "음이 악기 " + prog + "의 무음 구간에 적혀 소리가 나지 않음("
				+ ex + "). " + SoundingPitch.detail(SoundingPitch.rule(prog)));
	}

	/**
	 * {@code pitch_mode: "sounding"}: 들리는 음으로 준 음표를 트랙 악기(노래 파트는 노래 악기)의 표로 적을 음으로 바꾼다.
	 * 구간 악기(20·28·29)는 timbre("default"·"alt", null이면 default)로 세트를 고른다. 바꾼 음이 악기 음역(DLS를 읽었으면
	 * DLS 기준, 아니면 properties 기준) 밖이거나, 무음 구간이거나, 고정·무음높이 악기면 아무것도 바꾸지 않고
	 * PITCH_NOT_PRODUCIBLE. 음표의 다른 값(tick·pos·len·vel)은 그대로 둔다.
	 *
	 * @param partKey 오류 보고용 파트 이름
	 * @return 바꾼 음표 목록(같은 순서)
	 */
	public List<NoteSpec> toWritten(int track, String partKey, List<NoteSpec> notes, String timbre) {
		MMLTrack t = track(track);
		Part p = Part.of(partKey);
		int prog = soundingProgram(t, p);
		String tb = (timbre == null || timbre.isBlank()) ? SoundingPitch.TIMBRE_DEFAULT : timbre.trim();
		if (!SoundingPitch.timbres(prog).contains(tb)) {
			throw new EngineException(BAD_ARGUMENT, "timbre는 " + SoundingPitch.timbres(prog) + " 중 하나(악기 " + prog + ")",
					details("timbre", timbre, "program", prog, "timbres", SoundingPitch.timbres(prog)));
		}
		InstrumentTable.Instrument inst = engine.instrumentTable().get(prog);
		InstrumentTable.Range range = inst == null ? null : inst.range();
		Integer lo = range == null ? null : range.low();
		Integer hi = range == null ? null : range.high();
		List<NoteSpec> out = new ArrayList<>();
		for (int i = 0; i < (notes == null ? 0 : notes.size()); i++) {
			NoteSpec s = notes.get(i);
			if (s == null) {
				out.add(null);
				continue;
			}
			int sounding;
			try {
				sounding = NoteSpec.parsePitch(s.pitch(), i);
			} catch (EngineException e) {
				Map<String, Object> d = new LinkedHashMap<>(e.details());
				d.put("part", p.key());
				throw new EngineException(e.code(), p.key() + " " + e.getMessage(), d, e);
			}
			Integer w = SoundingPitch.writtenFor(prog, sounding, tb);
			boolean inRange = w != null && (lo == null || w >= lo) && (hi == null || w <= hi)
					&& w >= NoteSpec.MIN_MIDI && w <= NoteSpec.MAX_MIDI;
			if (!inRange) {
				SoundingPitch.Rule rule = SoundingPitch.rule(prog);
				String why = !rule.kind().pitched() ? "이 악기는 " + SoundingPitch.detail(rule)
						: (w == null ? "세트 " + tb + "로는 그 음을 낼 수 없음" : "적을 음 " + NoteSpec.pitchName(w) + "(MIDI " + w
								+ ")이 악기 음역 밖");
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("track", track);
				d.put("part", p.key());
				d.put("index", i);
				d.put("tick", s.tick());
				d.put("pos", s.pos());
				d.put("sounding_pitch", sounding);
				d.put("sounding_name", NoteSpec.pitchName(sounding));
				d.put("written_pitch", w);
				d.put("program", prog);
				d.put("instrument", inst == null ? null : inst.name());
				d.put("timbre", tb);
				d.put("sounding", SoundingPitch.describe(prog));
				d.put("producible", SoundingPitch.soundingRange(prog, lo, hi));
				d.put("range_source", inst == null ? null : inst.rangeSource());
				throw new EngineException(EngineException.PITCH_NOT_PRODUCIBLE, p.key() + " 음표 " + i + "번(0부터): 들리는 음 "
						+ NoteSpec.pitchName(sounding) + "을(를) 악기 " + prog + (inst == null ? "" : "(" + inst.name() + ")")
						+ "로 낼 수 없음 — " + why, d);
			}
			out.add(new NoteSpec(s.tick(), s.pos(), s.len(), w, s.vel()));
		}
		return out;
	}

	/** {@link #toWritten}가 쓰는 음역 출처(DLS를 읽었으면 "dls"). 악기가 표에 없으면 null. */
	public String rangeSourceOf(int track, String partKey) {
		InstrumentTable.Instrument inst = engine.instrumentTable().get(soundingProgram(track(track), Part.of(partKey)));
		return inst == null ? null : inst.rangeSource();
	}

	private EditResult result(int track, List<String> parts, TreeSet<Integer> changed, int added, int deleted,
			List<EditResult.AlignedNote> aligned, List<EditResult.AffectedNote> affected, List<String> warnings) {
		return new EditResult(true, track, List.copyOf(parts), List.copyOf(changed), statsOf(track), null, COMPARE_NOTE,
				added, deleted, List.copyOf(aligned), List.copyOf(affected), List.copyOf(warnings));
	}

	// ======================================================================
	// MML·글자 수
	// ======================================================================

	/** 마비노기용 MML. track이 null이면 모든 트랙. */
	public List<TrackMml> mml(Integer track) {
		List<TrackMml> l = new ArrayList<>();
		if (track != null) {
			l.add(mmlOf(track));
		} else {
			for (int i = 1; i <= score.getTrackCount(); i++) {
				l.add(mmlOf(i));
			}
		}
		return l;
	}

	private TrackMml mmlOf(int n) {
		MMLTrack t = track(n);
		// 노래 제외 설정은 mmlRank()가 불릴 때 MMLText에 반영되므로 먼저 부른다.
		t.mmlRank();
		String mml = t.getMabiMML();
		String[] parts = splitMml(mml);
		Map<String, String> pm = new LinkedHashMap<>();
		Map<String, Integer> cm = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			pm.put(p.key(), parts[p.index()]);
			cm.put(p.key(), parts[p.index()].length());
		}
		return new TrackMml(n, t.getTrackName(), mml, pm, cm);
	}

	/** "MML@a,b,c[,d];" → {a, b, c, d}(없으면 ""). */
	static String[] splitMml(String mml) {
		String body = mml.startsWith("MML@") ? mml.substring(4) : mml;
		if (body.endsWith(";")) {
			body = body.substring(0, body.length() - 1);
		}
		String[] s = body.split(",", -1);
		String[] out = { "", "", "", "" };
		for (int i = 0; i < s.length && i < 4; i++) {
			out[i] = s[i];
		}
		return out;
	}

	/** 글자 수 통계. track이 null이면 모든 트랙. */
	public List<TrackStats> stats(Integer track) {
		List<TrackStats> l = new ArrayList<>();
		if (track != null) {
			l.add(statsOf(track));
		} else {
			for (int i = 1; i <= score.getTrackCount(); i++) {
				l.add(statsOf(i));
			}
		}
		return l;
	}

	/** 트랙 하나의 글자 수 통계. */
	public TrackStats statsOf(int n) {
		MMLTrack t = track(n);
		String rankText = t.mmlRankFormat(); // 노래 제외 설정 반영 + 순위 문자열
		String[] act = splitMml(t.getMabiMML());
		String[] raw = t.getMabiMMLArray();
		Map<String, Integer> chars = new LinkedHashMap<>();
		chars.put("melody", act[0].length());
		chars.put("chord1", act[1].length());
		chars.put("chord2", act[2].length());
		chars.put("song", raw[3].length());

		// 멜로디가 비고 화음이 있으면 엔진은 멜로디 자리에 "<>"(앱 기본)를 넣는다(노래 파트가 실리면 넣지 않음).
		String empty = MMLText.getMelodyEmptyStr();
		boolean chords = !raw[1].isEmpty() || !raw[2].isEmpty();
		int melIncl = (raw[0].isEmpty() && chords && raw[3].isEmpty()) ? empty.length() : raw[0].length();
		int melExcl = (raw[0].isEmpty() && chords) ? empty.length() : raw[0].length();

		int[] top = RankLimits.top();
		int d = top[0] / 3;
		int exM = top[0] + d;
		int exC1 = top[1] + d;
		int exC2 = top[2] + d;
		List<String> overEx = new ArrayList<>();
		if (melExcl > exM) {
			overEx.add("melody");
		}
		if (raw[1].length() > exC1) {
			overEx.add("chord1");
		}
		if (raw[2].length() > exC2) {
			overEx.add("chord2");
		}
		// 노래 파트가 있으면 노래 제외 기준에서는 실을 수 없다. 글자 수 초과가 아니므로 over가 아니라 songNotAllowed로 알린다.
		boolean songNotAllowed = !raw[3].isEmpty();
		List<String> overIn = new ArrayList<>();
		if (melIncl > top[0]) {
			overIn.add("melody");
		}
		if (raw[1].length() > top[1]) {
			overIn.add("chord1");
		}
		if (raw[2].length() > top[2]) {
			overIn.add("chord2");
		}
		if (raw[3].length() > top[0]) {
			overIn.add("song");
		}
		TrackStats.Limits limits = new TrackStats.Limits(
				new TrackStats.SongExcluded(exM, exC1, exC2, List.copyOf(overEx), songNotAllowed),
				new TrackStats.SongIncluded(top[0], top[1], top[2], top[0], List.copyOf(overIn)));
		// 들리는 음역(모든 파트, 노래 파트는 노래 악기 기준)과 무음 구간에 적힌 음 수
		Integer sMin = null;
		Integer sMax = null;
		int silent = 0;
		for (Part p : Part.values()) {
			int prog = soundingProgram(t, p);
			for (MMLNoteEvent e : t.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
				int s = SoundingPitch.soundingOf(prog, e.getNote() + 12);
				if (s == SoundingPitch.SILENT) {
					silent++;
				} else if (s >= 0) {
					sMin = (sMin == null) ? s : Math.min(sMin, s);
					sMax = (sMax == null) ? s : Math.max(sMax, s);
				}
			}
		}
		return new TrackStats(n, t.getTrackName(), t.getProgram(), chars, limits, ruleOf(n),
				rankText, t.getPlayTime(), SoundingPitch.summary(t.getProgram()), sMin,
				sMin == null ? null : NoteSpec.pitchName(sMin), sMax, sMax == null ? null : NoteSpec.pitchName(sMax), silent);
	}

	/** {@code rank.properties}의 RANK_1(멜로디, 화음1, 화음2). */
	static final class RankLimits {
		private static int[] top;

		static synchronized int[] top() {
			if (top == null) {
				try (InputStream in = ResourceLoader.getResourceFile("rank.properties")) {
					Properties p = new Properties();
					p.load(in);
					String v = p.getProperty("RANK_1");
					if (v == null) {
						throw new IllegalStateException("rank.properties에 RANK_1이 없음");
					}
					String[] s = v.split(",");
					top = new int[] { Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim()), Integer.parseInt(s[2].trim()) };
				} catch (IOException e) {
					throw new IllegalStateException("rank.properties를 읽을 수 없음", e);
				}
			}
			return top.clone();
		}
	}

	// ======================================================================
	// 랭크별 한도, 정적 검사, 일괄 변환, 한도 맞추기 제안, 장 나누기
	// ======================================================================

	/**
	 * 트랙의 글자 수 기본 판정 기준: 트랙 설정(track_set의 song_rule)이 있으면 그것, 없으면 트랙 악기가 노래·마이크·코러스(타입 V/C,
	 * 노래 파트만 쓰는 악기)일 때 song_included(노래 파트 한도가 적용되는 유일한 기준), 그 밖에는 프로젝트 기준(기본 song_excluded).
	 * 도구에 song_rule 인자를 주면 그것이 이긴다(부르는 쪽).
	 */
	public String ruleOf(int n) {
		MMLTrack t = track(n);
		String r = trackSongRule.get(n);
		if (r != null) {
			return r;
		}
		return isVoiceType(instrumentType(t)) ? SONG_INCLUDED : songRule;
	}

	/**
	 * 부르는 쪽이 노래·마이크·코러스 트랙에 song_excluded를 직접 주었을 때의 경고(아니면 null). 그 악기는 노래 칸만 연주하므로
	 * 마비노기에서 노래 칸을 끌 수 없다.
	 */
	public String voiceRuleWarning(int n, String explicitRule) {
		MMLTrack t = track(n);
		if (explicitRule == null || !SONG_EXCLUDED.equals(explicitRule.trim().toLowerCase(Locale.ROOT))
				|| !isVoiceType(instrumentType(t))) {
			return null;
		}
		InstrumentTable.Instrument i = engine.instrumentTable().get(t.getProgram());
		return "song_rule_voice: 트랙 " + n + "은 노래·마이크·코러스 악기(" + t.getProgram() + (i == null ? "" : " " + i.name())
				+ ")라 노래 칸만 연주하고 노래 칸을 끌 수 없음 — song_excluded 판정은 이 트랙에 맞지 않음(생략하면 song_included)";
	}

	/** 노래 파트만 쓰는 악기 타입(V 노래·마이크, C 코러스). */
	static boolean isVoiceType(String type) {
		return "V".equals(type) || "C".equals(type);
	}

	private String instrumentType(MMLTrack t) {
		InstrumentTable.Instrument inst = engine.instrumentTable().get(t.getProgram());
		return inst == null ? null : inst.type();
	}

	/** 랭크(null이면 1랭크)·기준(null이면 트랙 기본 기준)으로 판정한다. */
	public CharLimits.Evaluation rankCheck(int n, String rank, String rule) {
		MMLTrack t = track(n);
		CharLimits.Rank r = CharLimits.rank(rank);
		String ru = (rule == null) ? ruleOf(n) : CharLimits.checkRule(rule);
		return CharLimits.evaluate(t, r, ru);
	}

	/** score_stats의 악보 전체 warnings(tempo_end_differs). */
	public List<Map<String, Object>> scoreWarnings() {
		return ScoreChecks.scoreWarnings(score, measureMap());
	}

	/** score_stats의 트랙별 warnings(unison_clash, tempo_mid_note). */
	public List<Map<String, Object>> trackWarnings(int n) {
		MMLTrack t = track(n);
		return ScoreChecks.trackWarnings(score, n, instrumentType(t), measureMap());
	}

	/** 트랙 하나의 unison_clash. */
	public ScoreChecks.UnisonClash unisonClash(int n) {
		MMLTrack t = track(n);
		return ScoreChecks.unisonClash(t, n, instrumentType(t), measureMap());
	}

	private void addUnisonWarning(List<String> warnings, int track) {
		String w = ScoreChecks.unisonWarning(unisonClash(track));
		if (w != null) {
			warnings.add(w);
		}
	}

	private static List<Part> parts(List<String> keys) {
		if (keys == null) {
			return null;
		}
		if (keys.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "parts가 비어 있음(생략하면 모든 파트)");
		}
		List<Part> l = new ArrayList<>();
		for (String k : keys) {
			Part p = Part.of(k);
			if (!l.contains(p)) {
				l.add(p);
			}
		}
		l.sort(Comparator.comparingInt(Part::index));
		return l;
	}

	private static Map<String, Integer> charMap(int[] c) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			m.put(p.key(), c[p.index()]);
		}
		return m;
	}

	/**
	 * {@code score_transform}: 트랙의 파트들(null이면 모두)에서 마디 범위(null이면 처음·끝까지) 안에서 시작하는 음에
	 * 연산 하나를 적용한다. 바꾸기 전에 스냅샷을 자동으로 남긴다(검증이 끝난 뒤). 생성이 실패하면 되돌린다.
	 */
	public TransformResult transform(int track, List<String> partKeys, Integer fromMeasure, Integer toMeasure,
			TransformOp op) {
		MMLTrack t = track(track);
		List<Part> ps = parts(partKeys);
		if (ps == null) {
			ps = List.of(Part.values());
		}
		MeasureMap map = measureMap();
		if ((fromMeasure != null && fromMeasure < 1) || (toMeasure != null && toMeasure < 1)
				|| (fromMeasure != null && toMeasure != null && toMeasure < fromMeasure)) {
			throw new EngineException(BAD_ARGUMENT, "마디 범위가 틀림: " + fromMeasure + "~" + toMeasure + " (1 이상, from ≤ to)",
					details("from_measure", fromMeasure, "to_measure", toMeasure));
		}
		long from = (fromMeasure == null) ? 0 : map.measureStart(fromMeasure);
		long to = (toMeasure == null) ? Long.MAX_VALUE : map.measureEnd(toMeasure);
		// 응답에 실제로 쓴 범위를 적는다: 시작 기본 1, 끝 기본 = 이 트랙에서 음이 시작하는 마지막 마디(음이 없으면 시작 마디)
		int effFrom = (fromMeasure == null) ? 1 : fromMeasure;
		int effTo;
		if (toMeasure != null) {
			effTo = toMeasure;
		} else {
			long lastStart = -1;
			for (Part p : Part.values()) {
				for (MMLNoteEvent e : t.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
					lastStart = Math.max(lastStart, e.getTickOffset());
				}
			}
			effTo = Math.max(effFrom, lastStart < 0 ? effFrom : map.measureOf(lastStart));
		}
		int[] before = TrackCopy.mmlChars(t);
		Snapshots.SnapshotInfo snap = engine.snapshots().snapshot(score,
				"auto: score_transform 트랙 " + track + " " + op.op() + " " + op.params());
		List<Part> fps = ps;
		List<TransformOp.Change> changes = new ArrayList<>();
		Map<String, Integer> byPart = new LinkedHashMap<>();
		mutate(() -> {
			MMLTrack tt = score.getTrack(track - 1);
			for (Part p : fps) {
				List<TransformOp.Change> c = op.apply(p.key(), tt.getMMLEventAtIndex(p.index()).getMMLNoteEventList(), from, to,
						map);
				byPart.put(p.key(), c.size());
				changes.addAll(c);
			}
		});
		int[] after = TrackCopy.mmlChars(score.getTrack(track - 1));
		Map<String, Integer> saved = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			saved.put(p.key(), before[p.index()] - after[p.index()]);
		}
		List<String> warnings = new ArrayList<>();
		addUnisonWarning(warnings, track);
		if (changes.isEmpty()) {
			warnings.add("바뀐 음이 없음(악보는 그대로, 스냅샷 " + snap.id() + "번은 남김)");
		}
		List<String> keys = new ArrayList<>();
		for (Part p : ps) {
			keys.add(p.key());
		}
		return new TransformResult(true, track, op.op(), op.params(), keys, effFrom, effTo, changes.size(), byPart,
				charMap(before), charMap(after), saved, snap.id(), snap, rankCheck(track, null, null),
				List.copyOf(changes.subList(0, Math.min(10, changes.size()))), warnings);
	}

	/** {@code score_fit_suggest}(읽기만). */
	public FitSuggester.FitSuggestion fitSuggest(int track, String rank, String rule, List<String> partKeys) {
		track(track);
		CharLimits.Rank r = CharLimits.rank(rank);
		String ru = (rule == null) ? ruleOf(track) : CharLimits.checkRule(rule);
		return FitSuggester.suggest(score, track, r, ru, parts(partKeys), measureMap());
	}

	/** 파일 이름에 쓸 수 없는 글자. */
	private static final java.util.regex.Pattern BAD_NAME = java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

	/**
	 * {@code score_split_sheets}(열린 악보는 읽기만). outDir가 있으면 장마다 {@code <name>_장N.mmi}를 새로 쓴다
	 * (overwrite가 아니면 하나라도 있을 때 아무것도 쓰지 않고 FILE_EXISTS, 보호 폴더면 PROTECTED_PATH).
	 * 저장한 장은 {@code Sheet.path}에 경로가 들어간다.
	 */
	public SheetSplitter.SplitResult splitSheets(int track, String rank, String rule, int maxSheets, boolean carry,
			Path outDir, String name, boolean overwrite) {
		MMLTrack t = track(track);
		CharLimits.Rank r = CharLimits.rank(rank);
		String ru = (rule == null) ? ruleOf(track) : CharLimits.checkRule(rule);
		if (maxSheets < 1 || maxSheets > 64) {
			throw new EngineException(BAD_ARGUMENT, "max_sheets는 1~64", details("max_sheets", maxSheets));
		}
		if (outDir != null && !outDir.isAbsolute()) {
			throw new EngineException(BAD_ARGUMENT, "out_dir는 절대 경로", details("out_dir", outDir.toString()));
		}
		String nm;
		if (name == null || name.isBlank()) {
			String base = (score.getTitle() == null || score.getTitle().isBlank()) ? "sheet" : score.getTitle();
			nm = BAD_NAME.matcher(base + "_t" + track).replaceAll("_");
			if (nm.length() > 60) {
				nm = nm.substring(0, 60);
			}
		} else {
			nm = name.trim();
			if (BAD_NAME.matcher(nm).find() || nm.length() > 80) {
				throw new EngineException(BAD_ARGUMENT, "name에 경로 글자(\\ / : * ? \" < > |)나 제어 문자는 쓸 수 없음(80자까지)",
						details("name", name));
			}
		}
		SheetSplitter sp = new SheetSplitter(score, track, measureMap(), r, ru, carry);
		SheetSplitter.SplitResult res = sp.split(maxSheets);
		if (outDir == null) {
			return res;
		}
		Path dir = outDir.toAbsolutePath().normalize();
		engine.checkNotProtected(dir);
		List<Path> targets = new ArrayList<>();
		for (SheetSplitter.Sheet s : res.sheets()) {
			Path f = dir.resolve(nm + "_장" + s.index() + ".mmi");
			engine.checkNotProtected(f);
			if (Files.exists(f) && !overwrite) {
				throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일(overwrite: true면 덮어씀): " + f,
						details("path", f.toString()));
			}
			targets.add(f);
		}
		List<SheetSplitter.Sheet> withPath = new ArrayList<>();
		try {
			Files.createDirectories(dir);
			for (int i = 0; i < res.sheets().size(); i++) {
				SheetSplitter.Sheet s = res.sheets().get(i);
				MMLScore c = new MMLScore();
				c.putObjectState(sp.stateOf(s));
				String title = score.getTitle();
				c.setTitle((title == null || title.isEmpty() ? nm : title) + " 장" + s.index());
				Path f = targets.get(i);
				// writeToOutputStream은 스트림을 닫는다. try-with-resources의 두 번째 close는 무해하다.
				try (OutputStream out = Files.newOutputStream(f)) {
					new MMLScoreSerializer(c).writeToOutputStream(out);
				}
				withPath.add(s.withPath(f.toString()));
			}
		} catch (IOException e) {
			throw new EngineException(EngineException.IO_ERROR, "장 저장 실패: " + e.getMessage(),
					details("out_dir", dir.toString()), e);
		}
		return res.withSheets(withPath);
	}

	// ======================================================================
	// 생성·되돌리기
	// ======================================================================

	/** 지금 상태 바이트({@code getObjectState()}). */
	public byte[] state() {
		return score.getObjectState();
	}

	/** 상태 바이트로 되돌린다({@code putObjectState} 뒤 {@code generateAll()}). 생성이 실패하면 원래 상태로 돌아간다. */
	public void restoreState(byte[] bytes) {
		mutate(() -> score.putObjectState(bytes));
	}

	/**
	 * {@code score_copy_range}: from~to 마디(1부터, 포함)를 dest 마디로 복사한다({@link RangeCopy}). tracks가 null이면 모든
	 * 트랙. dry_run이면 바꾼 뒤 수를 세고 원래 상태로 되돌린다(스냅샷 없음). 아니면 바꾸기 전에 스냅샷을 자동으로 남긴다.
	 */
	public RangeCopy.Result copyRange(int from, int to, int dest, List<Integer> tracks, int transpose, int velocityDelta,
			String mode, String outOfRange, boolean dryRun, String rank, String rule) {
		return RangeCopy.run(this, from, to, dest, tracks, transpose, velocityDelta, mode, outOfRange, dryRun, rank, rule);
	}

	/**
	 * {@code score_merge_tracks}: merge tracks of the same program into one ({@link TrackMerge}). Snapshot before the
	 * change unless dry_run (then the score is restored after measuring).
	 */
	public TrackMerge.Result mergeTracks(List<Integer> tracks, Integer target, String strategy,
			List<TrackMerge.OrderEntry> order, String rank, String rule, boolean dryRun, Integer maxLenTicks,
			boolean keepSources) {
		return TrackMerge.run(this, tracks, target, strategy, order, rank, rule, dryRun, maxLenTicks, keepSources);
	}

	/**
	 * Runs {@code work} and then puts the score back exactly as it was (state bytes and dirty flag), also when it
	 * throws. For dry runs of the editing tools ({@code score_put_notes dry_run}).
	 */
	public <T> T dryRun(java.util.function.Supplier<T> work) {
		byte[] before = state();
		boolean d = dirty;
		try {
			return work.get();
		} finally {
			if (!Arrays.equals(before, state())) {
				restoreState(before);
			}
			dirty = d;
		}
	}

	/** {@code score_finalize}: lead-in signature and silent end padding ({@link Finalize}). */
	public Finalize.Result finalizeScore(Finalize.Options options) {
		return Finalize.run(this, options);
	}

	/** {@code track_import_mml}: add a track from MML text ({@link MmlImport}). */
	public MmlImport.Result importMml(List<String> sheets, int program, String name, Integer songProgram, Integer panpot,
			Integer volume, Integer insertAt, String rank, String rule) {
		return MmlImport.run(this, sheets, program, name, songProgram, panpot, volume, insertAt, rank, rule);
	}

	/** Track n (1-based) or NO_TRACK. */
	MMLTrack trackRef(int n) {
		return track(n);
	}

	/** Copy of the per-track song_rule settings (keyed by track number). */
	Map<Integer, String> trackRulesCopy() {
		return new HashMap<>(trackSongRule);
	}

	void setTrackRules(Map<Integer, String> m) {
		trackSongRule.clear();
		trackSongRule.putAll(m);
	}

	/** Renumbers the per-track song_rule settings after tracks were removed or inserted (f returns 0 = removed). */
	void remapTrackRules(java.util.function.IntUnaryOperator f) {
		Map<Integer, String> old = new HashMap<>(trackSongRule);
		trackSongRule.clear();
		for (Map.Entry<Integer, String> e : old.entrySet()) {
			int n = f.applyAsInt(e.getKey());
			if (n > 0) {
				trackSongRule.put(n, e.getValue());
			}
		}
	}

	void checkTrackName(String name) {
		checkName(name);
	}

	void checkTrackProgram(int program) {
		checkProgram(program);
	}

	void checkTrackSongProgram(int sp) {
		checkSongProgram(sp);
	}

	BridgeEngine engine() {
		return engine;
	}

	void setDirty(boolean d) {
		dirty = d;
	}

	/** 바꾸고 생성한다. 실패하면 바꾸기 전 상태로 되돌리고 예외를 다시 던진다. */
	void mutate(Runnable change) {
		byte[] before = score.getObjectState();
		try {
			change.run();
			generate();
			dirty = true;
		} catch (RuntimeException e) {
			rollback(before);
			throw e;
		}
	}

	private void rollback(byte[] before) {
		score.putObjectState(before);
		try {
			score.generateAll();
		} catch (MMLExceptionList | MMLVerifyException e) {
			BridgeEngine.warn("되돌린 상태의 MML 생성도 실패: " + e);
		}
	}

	/** {@code score.generateAll()}. 실패하면 MML_GENERATE_FAILED(어느 트랙·파트·음표인지 포함). */
	void generate() {
		try {
			score.generateAll();
		} catch (MMLExceptionList e) {
			MeasureMap map = measureMap();
			List<Map<String, Object>> errs = new ArrayList<>();
			StringBuilder sb = new StringBuilder();
			for (MMLExceptionList.Entry en : e.getErr()) {
				Map<String, Object> m = new LinkedHashMap<>();
				int[] tp = locate(en.getRelationPart());
				m.put("track", tp[0]);
				m.put("part", tp[1] >= 0 ? Part.ofIndex(tp[1]).key() : null);
				MMLNoteEvent note = en.getNote();
				if (note != null) {
					m.put("tick", note.getTickOffset());
					m.put("pos", map.position(Math.max(0, note.getTickOffset())).toString());
					m.put("note", NoteSpec.view(note, map));
				}
				String msg = (en.getException() != null) ? en.getException().getMessage() : String.valueOf(en);
				if (msg != null && msg.startsWith("Undefined tick table")) {
					// 6틱보다 짧은 길이(음·쉼표)는 MML 길이 표에 없다(가장 짧은 길이 l64 = 6틱)
					msg += " — " + SHORT_TICK_HINT;
				}
				m.put("message", msg);
				errs.add(m);
				if (sb.length() < 300) {
					sb.append(" [트랙 ").append(tp[0]).append(' ').append(m.get("part")).append(' ')
							.append(m.getOrDefault("pos", "?")).append(": ").append(msg).append(']');
				}
			}
			throw new EngineException(EngineException.MML_GENERATE_FAILED, "MML 생성 실패" + sb, details("errors", errs), e);
		} catch (MMLVerifyException e) {
			int n = score.getTrackList().indexOf(e.getTrack()) + 1;
			throw new EngineException(EngineException.MML_GENERATE_FAILED,
					"MML 생성 뒤 자체 검증 실패(트랙 " + n + "): 만든 MML을 되읽은 음표가 원래와 다름",
					details("errors", List.of(details("track", n, "message", "MMLVerifyException"))), e);
		}
	}

	/** MML 길이 표에 없는 길이(MMLTicks "Undefined tick table: n")의 설명. */
	public static final String SHORT_TICK_HINT = "6틱보다 짧은 음·쉼표는 MML로 쓸 수 없음(가장 짧은 길이 l64 = 6틱). "
			+ "셋잇단 격자(16의 배수)와 64분음표 격자(6의 배수)를 섞으면 음 사이에 2·4틱 쉼표가 생길 수 있음";

	/** 엔진 파트 리스트 → {트랙(1부터), 파트 번호}. 못 찾으면 {0, −1}. */
	private int[] locate(MMLEventList part) {
		for (int i = 0; i < score.getTrackCount(); i++) {
			MMLTrack t = score.getTrack(i);
			for (int p = 0; p < 4; p++) {
				if (t.getMMLEventAtIndex(p) == part) {
					return new int[] { i + 1, p };
				}
			}
		}
		return new int[] { 0, -1 };
	}
}
