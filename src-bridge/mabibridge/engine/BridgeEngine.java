package mabibridge.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jp.fourthline.mabiicco.midi.MabiDLS;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.MMLBuilder;
import jp.fourthline.mmlTools.core.MMLText;
import jp.fourthline.mmlTools.core.MMLTickTable;
import jp.fourthline.mmlTools.optimizer.MMLStringOptimizer;

/**
 * MabiIcco 엔진을 GUI 없이 감싸는 진입점.
 *
 * <p>엔진은 스레드 안전하지 않다. 이 객체의 메서드는 한 스레드에서 순서대로 부른다.
 * 정적 설정은 JVM 전역이므로 인스턴스를 여러 개 만들어도 같은 값이 적용된다.</p>
 */
public final class BridgeEngine {

	/**
	 * 예전 고정 DLS 경로 C:\Nexon\Mabinogi\mp3\MSXspirit01~05.dls. 실제 기본값은 {@link #defaultDls()}
	 * ({@link DlsLocator}: 환경 변수 MABI_DLS, 흔한 설치 폴더 순, 못 찾으면 이 목록).
	 */
	public static final List<Path> DEFAULT_DLS;
	static {
		List<Path> l = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			l.add(Path.of("C:\\Nexon\\Mabinogi\\mp3\\MSXspirit0" + i + ".dls"));
		}
		DEFAULT_DLS = Collections.unmodifiableList(l);
	}

	public static final String DEFAULT_SNAPSHOTS = "bridge/runtime/snapshots";

	/** 엔진 설정. */
	public record Config(List<Path> dlsPaths, List<Path> protectedDirs, Path snapshotsDir) {
		public Config {
			dlsPaths = List.copyOf(dlsPaths);
			protectedDirs = List.copyOf(protectedDirs);
		}

		/** DLS 기본 경로({@link #defaultDls()}), 기본 보호 폴더({@link #defaultProtectedDirs()}), 스냅샷 bridge/runtime/snapshots. */
		public static Config defaults() {
			return new Config(defaultDls(), defaultProtectedDirs(), Path.of(DEFAULT_SNAPSHOTS));
		}

		public Config withDls(List<Path> dls) {
			return new Config(dls, protectedDirs, snapshotsDir);
		}

		public Config withProtected(List<Path> dirs) {
			return new Config(dlsPaths, dirs, snapshotsDir);
		}

		public Config withSnapshots(Path dir) {
			return new Config(dlsPaths, protectedDirs, dir);
		}
	}

	/** DLS 로딩 결과. */
	public record DlsStatus(boolean loaded, List<String> files, long millis, int instruments, int matchedInTable) {}

	private static final String[] REQUIRED_RESOURCES = {
			"/resources/rank.properties",
			"/resources/instrument.properties",
			"/resources/appResource.properties",
	};

	private static InstrumentTable staticTable;
	private static DlsStatus dlsStatus;

	private final Config config;
	private final InstrumentTable instruments;
	private final Snapshots snapshots;
	private Project project;

	public BridgeEngine() {
		this(Config.defaults());
	}

	/**
	 * 시작 순서: 안전 점검 → 리소스 점검 → 악기표 → 정적 설정 6개 → 틱 표 검증. DLS는 읽지 않는다.
	 *
	 * @throws IllegalStateException {@code mabiicco.test_mode}가 없거나 리소스가 없을 때
	 */
	public BridgeEngine(Config config) {
		// 1. 안전 점검
		if (System.getProperty("mabiicco.test_mode") == null) {
			throw new IllegalStateException("-Dmabiicco.test_mode 가 없어 시작을 거부함"
					+ " (MabiIcco가 사용자 홈의 .mabiicco.config를 읽고 쓰지 않게 하려면 반드시 필요)");
		}
		if (!"true".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
			warn("java.awt.headless=true 가 아님. GUI 없이 돌리려면 -Djava.awt.headless=true 를 붙일 것");
		}
		// 2. 리소스 점검
		for (String r : REQUIRED_RESOURCES) {
			if (BridgeEngine.class.getResource(r) == null) {
				throw new IllegalStateException("클래스패스에 리소스가 없어 시작을 거부함: " + r);
			}
		}
		// 미리 만든 틱 표(jar 안 tickTable.dat)를 쓴다. 공식 실행 설정과 같음. 이미 지정했으면 그대로.
		if (System.getProperty("mabiicco.ticktable") == null
				&& MMLTickTable.class.getResource("tickTable.dat") != null) {
			System.setProperty("mabiicco.ticktable", "tickTable.dat");
		}
		this.config = config;
		// 3·4. 악기표 → 정적 설정
		this.instruments = InstrumentTable.load();
		for (String w : instruments.warnings()) {
			warn(w);
		}
		applyStaticSettings(instruments);
		// 5. 틱 표 검증(실패해도 경고만)
		try {
			if (!MMLTickTable.getInstance().verify()) {
				warn("MMLTickTable.verify() 실패: 틱 역변환 표가 앱과 다를 수 있음");
			}
		} catch (Throwable e) {
			warn("MMLTickTable.verify() 중 예외: " + e);
		}
		this.snapshots = new Snapshots(config.snapshotsDir());
	}

	/** 정적 설정 6개를 MabiIcco 앱의 기본값으로. */
	static synchronized void applyStaticSettings(InstrumentTable table) {
		staticTable = table;
		MMLStringOptimizer.setOptimizeLevel(MMLStringOptimizer.GEN2);
		MMLBuilder.setMMLVZeroTempo(false);
		MMLText.setMelodyEmptyStr("<>");
		MMLScore.setMMLFix64(false);
		// 앱(MabiIcco.tempoAllowChordPart): DLS 악기의 타입이 허용하면 true, 악기를 못 찾으면 true.
		MMLTrack.setTempoAllowChordPartFunction(p -> {
			InstrumentTable.Instrument i = staticTable.get(p);
			return (i == null) || i.allowTempoChordPart();
		});
		MMLTrack.setPercussionMotionFixFunction(p -> false);
	}

	static void warn(String msg) {
		System.err.println("[engine] 경고: " + msg);
	}

	static void log(String msg) {
		System.err.println("[engine] " + msg);
	}

	public Config config() {
		return config;
	}

	public InstrumentTable instrumentTable() {
		return instruments;
	}

	/** 악기 목록(DLS를 읽지 않는다). type이 null이 아니면 그 타입만. */
	public List<InstrumentTable.Instrument> instruments(String type) {
		return instruments.list(type);
	}

	/** DLS를 읽었는지. */
	public static synchronized boolean dlsLoaded() {
		return dlsStatus != null && dlsStatus.loaded();
	}

	/**
	 * DLS를 처음 부를 때만 읽는다({@code MabiDLS.loadingDLSFiles(paths, null)}, 신시사이저 초기화 없음).
	 * 읽은 뒤 악기표의 음역을 DLS 기준으로 바꾼다.
	 *
	 * @throws EngineException DLS_UNAVAILABLE
	 */
	public DlsStatus ensureDls() {
		synchronized (BridgeEngine.class) {
			if (dlsStatus != null && dlsStatus.loaded()) {
				int matched = instruments.refineWithDls();
				return new DlsStatus(true, dlsStatus.files(), 0, dlsStatus.instruments(), matched);
			}
			List<File> files = new ArrayList<>();
			List<String> missing = new ArrayList<>();
			for (Path p : config.dlsPaths()) {
				if (Files.isRegularFile(p)) {
					files.add(p.toFile());
				} else {
					missing.add(p.toString());
				}
			}
			if (files.isEmpty()) {
				throw new EngineException(EngineException.DLS_UNAVAILABLE, "DLS 파일을 하나도 찾지 못함",
						EngineException.details("paths", pathStrings(config.dlsPaths())));
			}
			if (!missing.isEmpty()) {
				warn("없는 DLS 파일은 건너뜀: " + missing);
			}
			log("DLS 읽기 시작: " + files.size() + "개");
			long t0 = System.nanoTime();
			List<File> loaded;
			try {
				loaded = MabiDLS.getInstance().loadingDLSFiles(files, null);
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				long ms = (System.nanoTime() - t0) / 1_000_000;
				log("DLS 읽기 실패(" + ms + "ms): " + e);
				throw new EngineException(EngineException.DLS_UNAVAILABLE, "DLS를 읽지 못함: " + e,
						EngineException.details("paths", pathStrings(config.dlsPaths()), "millis", ms), e);
			}
			long ms = (System.nanoTime() - t0) / 1_000_000;
			int count = MabiDLS.getInstance().getAllInst().size();
			// MabiIccoProperties가 이 시점에 초기화되면 그 속성 콜백이 정적 설정을 다시 건드릴 수 있으므로 다시 적용한다.
			applyStaticSettings(instruments);
			int matched = instruments.refineWithDls();
			List<String> names = new ArrayList<>();
			for (File f : loaded) {
				names.add(f.getPath());
			}
			dlsStatus = new DlsStatus(true, Collections.unmodifiableList(names), ms, count, matched);
			log("DLS 읽기 성공: 파일 " + loaded.size() + "개, 악기 " + count + "개, 악기표 일치 " + matched + "개, "
					+ ms + "ms");
			return dlsStatus;
		}
	}

	private static List<String> pathStrings(List<Path> l) {
		List<String> s = new ArrayList<>();
		for (Path p : l) {
			s.add(p.toString());
		}
		return s;
	}

	// ---- 프로젝트 ----

	/** 새 악보. 템포 120이면 템포 이벤트를 넣지 않는다(GUI의 새 악보와 같음, 마비노기 기본 120). */
	public Project newProject(String title, String author, String timeSignature, Integer tempo) {
		project = Project.create(this, title, author, timeSignature, tempo);
		return project;
	}

	/** 박자 목록({@link Project.TimeSigSpec})으로 새 악보. timeSignature와 함께 주면 BAD_ARGUMENT. */
	public Project newProject(String title, String author, String timeSignature, List<Project.TimeSigSpec> timeSignatures,
			Integer tempo) {
		project = Project.create(this, title, author, timeSignature, timeSignatures, tempo);
		return project;
	}

	/** .mmi를 연다(읽기만). */
	public Project open(Path path) {
		project = Project.open(this, path);
		return project;
	}

	/** 열린 프로젝트. 없으면 NO_PROJECT. */
	public Project project() {
		if (project == null) {
			throw new EngineException(EngineException.NO_PROJECT, "열린 프로젝트가 없음. 먼저 새로 만들거나 열 것");
		}
		return project;
	}

	public boolean hasProject() {
		return project != null;
	}

	// ---- 스냅샷 ----

	public Snapshots snapshots() {
		return snapshots;
	}

	public Snapshots.SnapshotInfo snapshot(String label) {
		return snapshots.snapshot(project().score(), label);
	}

	/** 스냅샷으로 되돌린다({@code putObjectState} 뒤 {@code generateAll()}). */
	public ProjectInfo restore(int id) {
		Project p = project();
		p.restoreState(snapshots.bytes(id));
		return p.info();
	}

	// ---- 보호 폴더 ----

	public List<Path> protectedDirs() {
		return config.protectedDirs();
	}

	/** 저장 경로가 보호 폴더 안이면 PROTECTED_PATH. 경로는 실제 경로(있는 부분까지)로 바꿔 대소문자 무시로 비교한다. */
	public void checkNotProtected(Path target) {
		Path d = protectedBy(target, config.protectedDirs());
		if (d != null) {
			throw new EngineException(EngineException.PROTECTED_PATH, "보호 폴더 안에는 저장하지 않음: " + d,
					EngineException.details("path", target.toString(), "protected", d.toString()));
		}
	}

	/** target이 dirs 중 하나(또는 그 안)이면 그 폴더, 아니면 null. 실제 경로(정션·링크 풀기)로 대소문자 무시 비교. */
	public static Path protectedBy(Path target, List<Path> dirs) {
		String t = canonical(target);
		for (Path d : dirs) {
			String dd = canonical(d);
			if (t.equals(dd) || t.startsWith(dd.endsWith(File.separator) ? dd : dd + File.separator)) {
				return d;
			}
		}
		return null;
	}

	/** 있는 상위 폴더까지 toRealPath로 풀고 나머지를 붙인 소문자 경로. */
	static String canonical(Path p) {
		Path abs = p.toAbsolutePath().normalize();
		Path existing = abs;
		Path rest = null;
		while (existing != null && !Files.exists(existing)) {
			Path name = existing.getFileName();
			rest = (rest == null) ? name : name.resolve(rest);
			existing = existing.getParent();
		}
		Path base = abs;
		if (existing != null) {
			try {
				base = existing.toRealPath();
				if (rest != null) {
					base = base.resolve(rest);
				}
			} catch (IOException e) {
				base = abs;
			}
		}
		return base.normalize().toString().toLowerCase(Locale.ROOT);
	}

	/** --dls 없이 쓸 DLS 경로: {@link DlsLocator#locate(List)} (MABI_DLS, 흔한 설치 폴더, 못 찾으면 {@link #DEFAULT_DLS}). */
	public static List<Path> defaultDls() {
		return DlsLocator.locate(null).paths();
	}

	/**
	 * 기본 보호 폴더 = 프로젝트 파일({@link ProjectConfig}, {@value ProjectConfig#FILE})의 {@code protect} 항목.
	 * 파일이 없으면 빈 목록(보호 폴더는 {@code --protect}로 더한다). 폴더가 없어도 목록에는 넣는다.
	 */
	public static List<Path> defaultProtectedDirs() {
		ProjectConfig.Loaded p = ProjectConfig.find();
		return p == null ? new ArrayList<>() : new ArrayList<>(p.protect());
	}
}
