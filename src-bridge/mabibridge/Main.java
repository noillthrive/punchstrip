package mabibridge;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.DlsLocator;
import mabibridge.engine.ProjectConfig;
import mabibridge.mcp.CompareHook;
import mabibridge.mcp.CompareHooks;
import mabibridge.mcp.McpServer;
import mabibridge.mcp.ServerState;
import mabibridge.mcp.ToolRegistry;

/**
 * MabiIcco MCP bridge entry point.
 *
 * <p>stdout is reserved for the MCP protocol channel. Right after startup the real stdout is captured into a
 * dedicated stream and System.out is redirected to System.err (UTF-8), so log output from the MabiIcco engine
 * never pollutes it.</p>
 *
 * <pre>
 * MabiIccoBridge --version
 * MabiIccoBridge --selfcheck [--dls a.dls,b.dls | --dls folder] [--no-dls]
 * MabiIccoBridge [serve] [--dls a.dls,b.dls] [--protect dir]... [--snapshots dir] [--log file] [--render-dir dir]
 * </pre>
 */
public final class Main {

	public static final String VERSION = "0.7.0";
	/** The MabiIcco version this bridge was built and tested against (not the detected one, see {@link #mabiIccoVersion()}). */
	public static final String MMLTOOLS_VERSION = "1.5.12";

	private static PrintStream protocolOut;

	private Main() {}

	/**
	 * Version of the MabiIcco found on the class path: the {@code MabiIcco-Version} attribute of the manifest of the jar
	 * that holds MabiIcco's classes, or null when MabiIcco is missing or its jar has no such attribute.
	 */
	public static String mabiIccoVersion() {
		try {
			Class<?> k = Class.forName("jp.fourthline.mmlTools.MMLScore", false, Main.class.getClassLoader());
			java.security.CodeSource cs = k.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null) {
				return null;
			}
			Path p = Path.of(cs.getLocation().toURI());
			if (!Files.isRegularFile(p)) {
				return null;
			}
			try (java.util.jar.JarFile j = new java.util.jar.JarFile(p.toFile())) {
				java.util.jar.Manifest m = j.getManifest();
				String v = m == null ? null : m.getMainAttributes().getValue("MabiIcco-Version");
				return v == null || v.isBlank() ? null : v.strip();
			}
		} catch (ClassNotFoundException | LinkageError | URISyntaxException | IOException | RuntimeException e) {
			return null;
		}
	}

	/** "MabiIcco 1.5.12 found; built and tested against MabiIcco 1.5.12" (or "MabiIcco version unknown; …"). */
	static String versionNote() {
		String v = mabiIccoVersion();
		return (v == null ? "MabiIcco version unknown" : "MabiIcco " + v + " found") + "; built and tested against MabiIcco "
				+ MMLTOOLS_VERSION;
	}

	/** The dedicated stream bound to the process's real stdout. */
	public static PrintStream protocolOut() {
		return protocolOut;
	}

	public static void main(String[] args) {
		protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
		System.setErr(err);
		System.setOut(err);

		int code = run(args);
		protocolOut.flush();
		System.err.flush();
		System.exit(code);
	}

	private static int run(String[] args) {
		if (args.length == 1 && "--version".equals(args[0])) {
			protocolOut.println("MabiIccoBridge " + VERSION + " (" + versionNote() + ")");
			return 0;
		}
		// the bridge runs on top of an installed MabiIcco: stop with a clear message when its classes are missing
		String noMabiIcco = MabiIccoRequired.checkClasspath(Main.class.getClassLoader());
		if (noMabiIcco != null) {
			System.err.println("[bridge] " + noMabiIcco.replace("\n", "\n[bridge] "));
			return MabiIccoRequired.EXIT_CODE;
		}
		if (args.length >= 1 && "--selfcheck".equals(args[0])) {
			List<Path> dls = null;
			boolean noDls = false;
			for (int i = 1; i < args.length; i++) {
				if ("--no-dls".equals(args[i])) {
					noDls = true;
				} else if ("--dls".equals(args[i]) && i + 1 < args.length) {
					dls = splitPaths(args[++i]);
				} else {
					System.err.println("[bridge] unknown --selfcheck argument: " + args[i]);
					usage();
					return 2;
				}
			}
			return selfcheck(dls, noDls);
		}
		Options o;
		try {
			o = Options.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println("[bridge] " + e.getMessage());
			usage();
			return 2;
		}
		return serve(o);
	}

	// ---------------------------------------------------------------- options

	/** Start-up options. Paths here are expected to be ASCII (the Windows launcher may mangle others). */
	static final class Options {
		List<Path> dls;
		final List<Path> protect = new ArrayList<>();
		Path snapshots;
		Path log;
		Path renderDir;

		static Options parse(String[] args) {
			Options o = new Options();
			int i = 0;
			if (args.length > 0 && "serve".equals(args[0])) {
				i = 1;
			}
			for (; i < args.length; i++) {
				String a = args[i];
				switch (a) {
				case "--dls":
					o.dls = splitPaths(value(args, ++i, a));
					break;
				case "--protect":
					o.protect.add(Path.of(value(args, ++i, a)));
					break;
				case "--snapshots":
					o.snapshots = Path.of(value(args, ++i, a));
					break;
				case "--log":
					o.log = Path.of(value(args, ++i, a));
					break;
				case "--render-dir":
					o.renderDir = Path.of(value(args, ++i, a));
					break;
				default:
					throw new IllegalArgumentException("unknown argument: " + a);
				}
			}
			return o;
		}

		private static String value(String[] args, int i, String name) {
			if (i >= args.length) {
				throw new IllegalArgumentException(name + " needs a value");
			}
			return args[i];
		}
	}

	/**
	 * Base folder for runtime defaults: the folder that holds MabiIccoBridge.jar (or, when run from
	 * build/classes, its parent). Defaults never depend on the current working directory.
	 */
	static Path baseDir() {
		try {
			Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path parent = code.toAbsolutePath().getParent();
			if (parent != null) {
				return parent;
			}
		} catch (URISyntaxException | RuntimeException e) {
			// fall through
		}
		return Path.of("").toAbsolutePath();
	}

	static Path defaultSnapshots() {
		return baseDir().resolve("bridge").resolve("runtime").resolve("snapshots");
	}

	static Path defaultLog() {
		return baseDir().resolve("bridge").resolve("runtime").resolve("log").resolve("bridge.log");
	}

	/** Comma/semicolon separated paths (blank entries dropped). */
	static List<Path> splitPaths(String v) {
		List<Path> l = new ArrayList<>();
		for (String p : v.split("[,;]")) {
			if (!p.isBlank()) {
				l.add(Path.of(p.trim()));
			}
		}
		return l;
	}

	/** Environment variable for the output base folder: renders go to {@code <MABIBRIDGE_OUT>\render}. */
	public static final String OUT_ENV = "MABIBRIDGE_OUT";

	/**
	 * render_wav 기본 출력 폴더. 순서: 환경 변수 {@value #OUT_ENV}(절대 경로여야 함)가 있으면 {@code <그 폴더>/render};
	 * 프로젝트 파일({@link ProjectConfig})의 {@code render.dir}; 아니면 {@code <jar 폴더>/output/render}. 폴더는 처음 렌더할 때 만든다.
	 *
	 * @throws IllegalArgumentException {@value #OUT_ENV}가 상대 경로일 때
	 */
	static Path defaultRenderDir() {
		return defaultRenderDir(System.getenv(OUT_ENV), baseDir(), ProjectConfig.find());
	}

	static Path defaultRenderDir(String outEnv, Path base, ProjectConfig.Loaded project) {
		if (outEnv != null && !outEnv.isBlank()) {
			return Path.of(checkOutEnv(outEnv)).resolve("render").toAbsolutePath().normalize();
		}
		if (project != null && project.renderDir() != null) {
			return project.renderDir();
		}
		return base.resolve("output").resolve("render").toAbsolutePath().normalize();
	}

	/** {@value #OUT_ENV} must be absolute (a relative value would depend on the client's working directory). */
	static String checkOutEnv(String outEnv) {
		String v = outEnv.trim();
		if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
			v = v.substring(1, v.length() - 1);
		}
		if (!Path.of(v).isAbsolute()) {
			throw new IllegalArgumentException(OUT_ENV + " must be an absolute path (for example C:\\mabi-out), got: " + outEnv);
		}
		return v;
	}

	/** Non-ASCII characters in the install path can be mangled by the Windows java launcher (ANSI code page). */
	static void warnNonAscii(Path base) {
		String s = base.toString();
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 0x7e) {
				System.err.println("[bridge] 주의/warning: install path has non-ASCII characters (" + s + "). If start-up"
						+ " fails on another PC, unpack to an ASCII path such as C:\\mabi-mcp");
				return;
			}
		}
	}

	// ---------------------------------------------------------------- serve

	private static int serve(Options o) {
		Path logFile = (o.log != null ? o.log : defaultLog()).toAbsolutePath().normalize();
		Path snapshots = (o.snapshots != null ? o.snapshots : defaultSnapshots()).toAbsolutePath().normalize();
		Path renderDir;
		try {
			renderDir = (o.renderDir != null ? o.renderDir : defaultRenderDir()).toAbsolutePath().normalize();
		} catch (IllegalArgumentException e) {
			System.err.println("[bridge] ERROR: " + e.getMessage());
			return 2;
		}
		BridgeEngine.Config cfg = BridgeEngine.Config.defaults().withSnapshots(snapshots);
		if (!o.protect.isEmpty()) {
			List<Path> dirs = new ArrayList<>(cfg.protectedDirs());
			for (Path p : o.protect) {
				Path a = p.toAbsolutePath().normalize();
				if (!dirs.contains(a)) {
					dirs.add(a);
				}
			}
			cfg = cfg.withProtected(dirs);
		}
		// 스냅샷·로그는 보호 폴더 안에 쓰지 않는다(시작 거부)
		for (Path[] w : new Path[][] { { logFile, Path.of("--log") }, { snapshots, Path.of("--snapshots") } }) {
			Path d = BridgeEngine.protectedBy(w[0], cfg.protectedDirs());
			if (d != null) {
				System.err.println("[bridge] ERROR: " + w[1] + " " + w[0] + " is inside the protected folder " + d);
				return 2;
			}
		}
		openLog(logFile);
		ProjectConfig.Loaded project = ProjectConfig.find();
		System.err.println("[bridge] project file: " + (project == null ? "none"
				: project.file() + " (found " + ProjectConfig.foundVia(project) + ")"));
		if (project != null) {
			for (String w : project.warnings()) {
				System.err.println("[bridge] warning: " + w);
			}
		}

		DlsLocator.Result dls = DlsLocator.locate(o.dls);
		cfg = cfg.withDls(dls.paths());
		warnNonAscii(baseDir());
		System.err.println("[bridge] DLS(" + dls.source().label() + "): 있음 " + dls.existing().size() + "개"
				+ (dls.missing().isEmpty() ? "" : ", 없음 " + dls.missing()) + " " + dls.existing()
				+ (dls.missingSiblings().isEmpty() ? "" : ", 읽지 않는 음원(그 안의 악기는 렌더 불가) " + dls.missingSiblings()));

		System.err.println("[bridge] MabiIccoBridge " + VERSION + " (" + versionNote() + ") 시작"
				+ ": headless=" + System.getProperty("java.awt.headless")
				+ ", test_mode=" + (System.getProperty("mabiicco.test_mode") != null)
				+ ", DLS 경로 " + cfg.dlsPaths().size() + "개"
				+ ", 보호 폴더 " + cfg.protectedDirs()
				+ ", 스냅샷 " + snapshots
				+ ", 렌더 " + renderDir
				+ ", 로그 " + logFile);

		BridgeEngine engine;
		try {
			engine = new BridgeEngine(cfg);
		} catch (RuntimeException e) {
			System.err.println("[bridge] 엔진 시작 실패: " + e.getMessage());
			return 1;
		}
		CompareHook hook = CompareHooks.discover(engine);
		System.err.println("[bridge] compare 연결: " + (hook == null ? "없음(NOT_WIRED)" : hook.getClass().getName()));
		ServerState state = new ServerState(engine, hook);
		try {
			engine.checkNotProtected(renderDir);
		} catch (RuntimeException e) {
			System.err.println("[bridge] 경고: --render-dir가 보호 폴더 안이라 out_dir 없는 render_wav는 PROTECTED_PATH로 거절됨: "
					+ renderDir);
		}
		state.setRenderDir(renderDir);
		ToolRegistry tools = ToolRegistry.standard();
		new McpServer(state, tools, System.in, protocolOut, VERSION).run();
		return 0;
	}

	/** stderr를 로그 파일(UTF-8, 이어쓰기)에도 남긴다. 열지 못하면 stderr만 쓴다. */
	private static void openLog(Path file) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			OutputStream fileOut = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
					StandardOpenOption.WRITE);
			OutputStream errOut = new FileOutputStream(FileDescriptor.err);
			PrintStream tee = new PrintStream(new Tee(errOut, fileOut), true, StandardCharsets.UTF_8);
			System.setErr(tee);
			System.setOut(tee);
			System.err.println("[bridge] ---- " + LocalDateTime.now().withNano(0) + " pid " + ProcessHandle.current().pid()
					+ " ----");
		} catch (IOException | RuntimeException e) {
			System.err.println("[bridge] 로그 파일을 열지 못함(stderr만 씀): " + file + " : " + e);
		}
	}

	/** 두 곳에 쓰는 스트림. 로그 파일 쓰기가 실패해도 stderr는 계속 쓴다. */
	private static final class Tee extends OutputStream {
		private final OutputStream a;
		private OutputStream b;

		Tee(OutputStream a, OutputStream b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public synchronized void write(int c) throws IOException {
			a.write(c);
			if (b != null) {
				try {
					b.write(c);
				} catch (IOException e) {
					b = null;
				}
			}
		}

		@Override
		public synchronized void write(byte[] buf, int off, int len) throws IOException {
			a.write(buf, off, len);
			if (b != null) {
				try {
					b.write(buf, off, len);
				} catch (IOException e) {
					b = null;
				}
			}
		}

		@Override
		public synchronized void flush() throws IOException {
			a.flush();
			if (b != null) {
				try {
					b.flush();
				} catch (IOException e) {
					b = null;
				}
			}
		}
	}

	// ---------------------------------------------------------------- selfcheck

	/** Where a class was loaded from (jar path), or a short reason. */
	private static String origin(Class<?> c) {
		try {
			java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
			return cs == null || cs.getLocation() == null ? "(bootstrap)" : Path.of(cs.getLocation().toURI()).toString();
		} catch (URISyntaxException | RuntimeException e) {
			return "(" + e + ")";
		}
	}

	/**
	 * Self check (all output on stderr, exit 0 = PASS): classes and resources on the class path, where they come from,
	 * default paths, the DLS files found ({@code --dls} or MABI_DLS or the install probe), and a 1-second in-memory render.
	 * A missing DLS is a FAIL with a hint, unless {@code --no-dls} is given (then the render line is SKIP).
	 */
	private static int selfcheck(List<Path> dlsArg, boolean noDls) {
		System.err.println("[selfcheck] MabiIccoBridge " + VERSION + " (" + versionNote() + "), Java "
				+ System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") "
				+ System.getProperty("java.home"));
		warnNonAscii(baseDir());
		String[][] classes = {
				{ "jp.fourthline.mmlTools.MMLScore", "MabiIcco.jar" },
				{ "jp.fourthline.mabiicco.midi.MabiDLS", "MabiIcco.jar" },
				{ "com.google.gson.Gson", "lib\\gson" },
				{ "com.formdev.flatlaf.FlatLightLaf", "lib\\flatlaf (MabiIcco app)" },
				{ "org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream",
						"lib\\commons-compress (MabiIcco app)" },
				{ "mabibridge.mcp.McpServer", "MabiIccoBridge.jar" },
		};
		boolean ok = true;
		for (String[] c : classes) {
			try {
				Class<?> k = Class.forName(c[0], false, Main.class.getClassLoader());
				System.err.println("[selfcheck] OK   " + c[0] + " <- " + origin(k));
			} catch (Throwable e) {
				ok = false;
				System.err.println("[selfcheck] FAIL " + c[0] + " : " + e + "  (expected in " + c[1]
						+ "; set MABIICCO_APP to your MabiIcco 1.5.12 app folder)");
			}
		}
		for (String r : new String[] { "/resources/rank.properties", "/resources/instrument.properties",
				"/jp/fourthline/mmlTools/core/tickTable.dat", "/mabibridge/engine/sounding_pitch.properties",
				"/mabibridge/engine/drum27_keys.json", "/mabibridge/engine/signature_home.properties" }) {
			boolean found = Main.class.getResource(r) != null;
			ok &= found;
			System.err.println("[selfcheck] " + (found ? "OK  " : "FAIL") + " resource " + r);
		}
		ProjectConfig.Loaded project = ProjectConfig.find();
		System.err.println("[selfcheck] info project file " + (project == null
				? "none (nothing is protected except --protect)"
				: project.file() + " (found " + ProjectConfig.foundVia(project) + ")"));
		if (project != null) {
			for (String w : project.warnings()) {
				System.err.println("[selfcheck] WARN " + w);
			}
		}
		try {
			System.err.println("[selfcheck] info render dir " + defaultRenderDir());
		} catch (IllegalArgumentException e) {
			ok = false;
			System.err.println("[selfcheck] FAIL render dir: " + e.getMessage());
		}
		System.err.println("[selfcheck] info snapshots " + defaultSnapshots() + ", log " + defaultLog());
		System.err.println("[selfcheck] info protected " + BridgeEngine.defaultProtectedDirs());
		DlsLocator.Result dls = DlsLocator.locate(dlsArg);
		System.err.println("[selfcheck] info DLS source: " + dls.source().label()
				+ (dls.detail().isEmpty() ? "" : " (" + dls.detail() + ")"));
		for (Path p : dls.existing()) {
			System.err.println("[selfcheck] info DLS found   " + p);
		}
		for (Path p : dls.missing()) {
			System.err.println("[selfcheck] info DLS missing " + p);
		}
		for (Path p : dls.missingSiblings()) {
			System.err.println("[selfcheck] WARN DLS bank not loaded (instruments in it cannot be rendered): " + p);
		}
		ok &= selfcheckRender(dls, noDls);
		System.err.println("[selfcheck] " + (ok ? "PASS" : "FAIL"));
		return ok ? 0 : 1;
	}

	/**
	 * 렌더 항목: 신시사이저를 열어 피아노 한 음을 약 1초 렌더하고 닫는다(파일은 쓰지 않는다). test_mode가 없으면 SKIP.
	 * DLS가 없으면 FAIL(설정 안내를 찍음), {@code --no-dls}면 SKIP.
	 */
	private static boolean selfcheckRender(DlsLocator.Result dls, boolean noDls) {
		if (System.getProperty("mabiicco.test_mode") == null) {
			System.err.println("[selfcheck] SKIP render: -Dmabiicco.test_mode 없음");
			return true;
		}
		if (noDls) {
			System.err.println("[selfcheck] SKIP render: --no-dls");
			return true;
		}
		if (dls.existing().isEmpty()) {
			System.err.println("[selfcheck] FAIL render: no Mabinogi DLS file found (" + dls.source().label() + ": "
					+ dls.paths() + ").");
			System.err.println("[selfcheck]      hint: set MABI_DLS to your Mabinogi mp3 folder (the one with"
					+ " MSXspirit01.dls ... MSXspirit05.dls), e.g. set MABI_DLS=D:\\Games\\Mabinogi\\mp3, or pass"
					+ " --dls <folder or files>. Use --selfcheck --no-dls to check everything except sound.");
			return false;
		}
		BridgeEngine.Config cfg = BridgeEngine.Config.defaults().withSnapshots(defaultSnapshots()).withDls(dls.paths());
		try {
			long t0 = System.nanoTime();
			BridgeEngine engine = new BridgeEngine(cfg);
			mabibridge.engine.Project p = engine.newProject("selfcheck", null, "4/4", 240);
			p.addTrack("piano", 0, null, null, null);
			p.putNotes(1, "melody", List.of(mabibridge.engine.NoteSpec.at(0, 96 * 2, "C4")), "strict", false);
			List<mabibridge.render.OfflineRenderer.Audio> l = mabibridge.render.WavRender.renderInMemory(engine, p,
					new mabibridge.render.WavRender.Request(null, null, null, null, null, 1.0, null, null, false));
			mabibridge.render.OfflineRenderer.Audio a = l.get(0);
			long ms = (System.nanoTime() - t0) / 1_000_000;
			boolean ok = a.peakDbfs() != null && a.peakDbfs() > -40;
			System.err.println("[selfcheck] " + (ok ? "OK  " : "FAIL") + " render: " + String.format(java.util.Locale.ROOT,
					"%.3f초, peak %s dBFS, %dms(DLS 읽기 포함)", a.durationSec(),
					a.peakDbfs() == null ? "무음" : String.format(java.util.Locale.ROOT, "%.1f", a.peakDbfs()), ms));
			return ok;
		} catch (Throwable e) {
			System.err.println("[selfcheck] FAIL render: " + e);
			if (!(e instanceof mabibridge.engine.EngineException)) {
				e.printStackTrace(System.err);
			}
			return false;
		}
	}

	private static void usage() {
		System.err.println("Usage: MabiIccoBridge --version");
		System.err.println("       MabiIccoBridge --selfcheck [--dls a.dls,b.dls | --dls folder] [--no-dls]");
		System.err.println("       MabiIccoBridge [serve] [--dls a.dls,b.dls] [--protect dir]... [--snapshots dir] [--log file] [--render-dir dir]");
	}
}
