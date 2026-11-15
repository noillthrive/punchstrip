package mabibridge.engine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the Mabinogi DLS sound banks ({@code MSXspirit0*.dls}) on this PC.
 *
 * <p>Order (first that applies wins):</p>
 * <ol>
 * <li>an explicit list ({@code --dls a.dls,b.dls}); entries may also be folders,</li>
 * <li>environment variable {@code MABI_DLS}: a comma/semicolon list of files, or a folder holding
 * {@code MSXspirit0*.dls},</li>
 * <li>probing the usual install folders ({@link #PROBE_DIRS}),</li>
 * <li>otherwise the historical default {@code C:\Nexon\Mabinogi\mp3\MSXspirit01..05.dls} (which then reports as missing).</li>
 * </ol>
 *
 * <p>The game assets are never shipped with the bridge; the user points it at their own Mabinogi install.</p>
 */
public final class DlsLocator {

	/** Environment variable with a DLS list or folder. */
	public static final String ENV = "MABI_DLS";

	/** File name pattern of the Mabinogi instrument banks. */
	public static final String GLOB = "MSXspirit0*.dls";

	/** Usual install folders (checked in this order). Unverified guesses except the first. */
	public static final List<String> PROBE_DIRS = List.of(
			"C:\\Nexon\\Mabinogi\\mp3",
			"C:\\Program Files\\Nexon\\Mabinogi\\mp3",
			"C:\\Program Files (x86)\\Nexon\\Mabinogi\\mp3",
			"C:\\Nexon\\Library\\mabinogi\\appdata\\mp3",
			"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Mabinogi\\mp3",
			"C:\\Program Files\\Steam\\steamapps\\common\\Mabinogi\\mp3",
			"D:\\Nexon\\Mabinogi\\mp3",
			"E:\\Nexon\\Mabinogi\\mp3");

	/** Where the paths came from, for logs and {@code --selfcheck}. */
	public enum Source {
		ARGUMENT("--dls"), ENV_VAR("MABI_DLS"), PROBE("install folder probe"), FALLBACK("default (not found)");

		private final String label;

		Source(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** Result: the paths to try and their origin. Paths may not exist (FALLBACK, or a wrong --dls). */
	public record Result(List<Path> paths, Source source, String detail) {
		public Result {
			paths = List.copyOf(paths);
		}

		public List<Path> existing() {
			List<Path> l = new ArrayList<>();
			for (Path p : paths) {
				if (Files.isRegularFile(p)) {
					l.add(p);
				}
			}
			return l;
		}

		/**
		 * The usual five banks {@code MSXspirit01..05.dls} that will NOT be loaded: absent from the existing files of this
		 * list (a partial install, or a deliberate --dls / MABI_DLS list with fewer files). Instruments stored in those
		 * banks cannot be rendered or listed. Paths point to where the bank would be (next to the first given file).
		 */
		public List<Path> missingSiblings() {
			return missingBanks(paths);
		}

		public List<Path> missing() {
			List<Path> l = new ArrayList<>();
			for (Path p : paths) {
				if (!Files.isRegularFile(p)) {
					l.add(p);
				}
			}
			return l;
		}
	}

	private DlsLocator() {}

	/** Locate with the real environment. {@code explicit} = {@code --dls} entries or null. */
	public static Result locate(List<Path> explicit) {
		return locate(explicit, System.getenv(ENV), defaultProbeDirs(), BridgeEngine.DEFAULT_DLS);
	}

	/** Testable core. */
	static Result locate(List<Path> explicit, String env, List<Path> probeDirs, List<Path> fallback) {
		if (explicit != null) {
			return new Result(expand(explicit), Source.ARGUMENT, "");
		}
		if (env != null && !env.isBlank()) {
			List<Path> l = new ArrayList<>();
			for (String s : env.split("[,;]")) {
				String t = s.trim();
				if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
					t = t.substring(1, t.length() - 1);
				}
				if (!t.isEmpty()) {
					l.add(Path.of(t));
				}
			}
			return new Result(expand(l), Source.ENV_VAR, env.trim());
		}
		// prefer a folder with all five banks; otherwise the folder with the most banks (first one on a tie)
		List<Path> best = null;
		Path bestDir = null;
		for (Path dir : probeDirs) {
			List<Path> found = inFolder(dir);
			if (found.isEmpty()) {
				continue;
			}
			if (hasAllBanks(dir)) {
				return new Result(found, Source.PROBE, dir.toString());
			}
			if (best == null || countBanks(dir) > countBanks(bestDir)) {
				best = found;
				bestDir = dir;
			}
		}
		if (best != null) {
			return new Result(best, Source.PROBE, bestDir + " (incomplete: not all of MSXspirit01..05.dls)");
		}
		return new Result(fallback, Source.FALLBACK, "");
	}

	/** Banks of {@link #BANKS} not among the existing files of {@code paths} (by file name, case-insensitive). */
	public static List<Path> missingBanks(List<Path> paths) {
		List<String> have = new ArrayList<>();
		Path dir = null;
		for (Path p : paths) {
			if (dir == null && p.toAbsolutePath().getParent() != null) {
				dir = p.toAbsolutePath().getParent();
			}
			if (Files.isRegularFile(p)) {
				have.add(p.getFileName().toString().toLowerCase(Locale.ROOT));
			}
		}
		List<Path> out = new ArrayList<>();
		for (String b : BANKS) {
			if (!have.contains(b.toLowerCase(Locale.ROOT))) {
				out.add(dir == null ? Path.of(b) : dir.resolve(b));
			}
		}
		return out;
	}

	/** The five banks of a normal Mabinogi install. */
	public static final List<String> BANKS = List.of("MSXspirit01.dls", "MSXspirit02.dls", "MSXspirit03.dls",
			"MSXspirit04.dls", "MSXspirit05.dls");

	static boolean hasAllBanks(Path dir) {
		return countBanks(dir) == BANKS.size();
	}

	static int countBanks(Path dir) {
		int n = 0;
		for (String b : BANKS) {
			if (Files.isRegularFile(dir.resolve(b))) {
				n++;
			}
		}
		return n;
	}

	/** Folders become their sorted {@code MSXspirit0*.dls}; files stay as they are. */
	static List<Path> expand(List<Path> entries) {
		List<Path> out = new ArrayList<>();
		for (Path p : entries) {
			if (Files.isDirectory(p)) {
				List<Path> f = inFolder(p);
				if (f.isEmpty()) {
					out.add(p.resolve("MSXspirit01.dls")); // reported as missing, keeps the folder visible
				} else {
					out.addAll(f);
				}
			} else {
				out.add(p);
			}
		}
		return out;
	}

	/** Sorted {@code MSXspirit0*.dls} files in a folder (empty if none or not a folder). */
	static List<Path> inFolder(Path dir) {
		List<Path> l = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return l;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, GLOB)) {
			for (Path p : ds) {
				if (Files.isRegularFile(p)) {
					l.add(p);
				}
			}
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
		l.sort((a, b) -> a.getFileName().toString().toLowerCase(Locale.ROOT)
				.compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT)));
		return l;
	}

	/**
	 * {@link #PROBE_DIRS}. Network and removable drives are deliberately not scanned (a disconnected drive can stall
	 * start-up); a Mabinogi install elsewhere is given with {@code MABI_DLS} or {@code --dls}.
	 */
	static List<Path> defaultProbeDirs() {
		List<Path> l = new ArrayList<>();
		for (String s : PROBE_DIRS) {
			l.add(Path.of(s));
		}
		return Collections.unmodifiableList(l);
	}
}
