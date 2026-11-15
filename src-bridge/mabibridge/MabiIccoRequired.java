package mabibridge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bridge contains none of MabiIcco's code: it runs only on top of an installed MabiIcco (its {@code app} folder,
 * given by the environment variable {@code MABIICCO_APP}). This class finds that folder, checks that it holds the
 * expected files, checks that MabiIcco's classes are on the class path, and words the message the user sees when
 * something is missing. It uses no MabiIcco class itself, so it works before MabiIcco is found.
 */
public final class MabiIccoRequired {

	/** Where MabiIcco is published by its author. */
	public static final String SOURCE_URL = "https://github.com/fourthline/mmlTools";
	/** The environment variable that names MabiIcco's {@code app} folder. */
	public static final String ENV = "MABIICCO_APP";
	/** Exit code when MabiIcco is missing (the same as {@code bridge\run-bridge.cmd}). */
	public static final int EXIT_CODE = 5;

	/** Files the bridge uses from the {@code app} folder (relative paths; a trailing "/" means a folder). */
	static final String[] APP_FILES = { "MabiIcco.jar", "lib/flatlaf/", "lib/commons-compress/" };
	/** Classes that must come from MabiIcco.jar. */
	static final String[] CLASSES = { "jp.fourthline.mmlTools.MMLScore", "jp.fourthline.mabiicco.midi.MabiDLS" };

	private MabiIccoRequired() {}

	/** The user-facing message: what is wrong ({@code detail}), that MabiIcco is required, and where to get it. */
	public static String message(String detail) {
		return "MabiIcco is required: " + detail + "\n"
				+ "  This tool contains no MabiIcco code and works only with an installed MabiIcco (1.5.12 is known to work).\n"
				+ "  Get MabiIcco from its author: " + SOURCE_URL + " (Releases), install it, then set " + ENV + "\n"
				+ "  to its \"app\" folder, the folder that contains MabiIcco.jar and lib\\, for example:\n"
				+ "    set \"" + ENV + "=C:\\Tools\\MabiIcco-1.5.12\\app\"";
	}

	/** {@code MABIICCO_APP} as a folder (surrounding quotes and a trailing separator removed), or null when unset. */
	public static Path appFromEnv(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.strip().replace("\"", "");
		while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))) {
			s = s.substring(0, s.length() - 1);
		}
		return s.isEmpty() ? null : Path.of(s).toAbsolutePath().normalize();
	}

	/**
	 * Checks an {@code app} folder. Returns null when it has every file the bridge uses, else the full message.
	 *
	 * @param app the folder, or null when {@code MABIICCO_APP} is not set
	 */
	public static String checkAppFolder(Path app) {
		if (app == null) {
			return message(ENV + " is not set");
		}
		if (!Files.isDirectory(app)) {
			return message(ENV + " is not a folder: " + app);
		}
		StringBuilder missing = new StringBuilder();
		for (String f : APP_FILES) {
			boolean dir = f.endsWith("/");
			Path p = app.resolve(dir ? f.substring(0, f.length() - 1) : f);
			if (dir ? !Files.isDirectory(p) : !Files.isRegularFile(p)) {
				missing.append(missing.length() == 0 ? "" : ", ").append(f.replace('/', '\\'));
			}
		}
		return missing.length() == 0 ? null : message(ENV + " (" + app + ") does not contain " + missing);
	}

	/** Returns null when MabiIcco's classes can be loaded by {@code loader}, else the full message. */
	public static String checkClasspath(ClassLoader loader) {
		for (String c : CLASSES) {
			try {
				Class.forName(c, false, loader);
			} catch (ClassNotFoundException | LinkageError e) {
				return message("MabiIcco's classes are not on the class path (" + c + " not found)");
			}
		}
		return null;
	}
}
