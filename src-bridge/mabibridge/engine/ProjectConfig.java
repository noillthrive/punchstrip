package mabibridge.engine;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional project file {@value #FILE} (0.6.2). The same rules are implemented in mabi-ear ({@code mabiear/io.py}).
 *
 * <p>Lookup (first match wins): system property {@value #PROPERTY} or environment variable {@value #ENV} (a file path,
 * or {@code none} to switch the lookup off); otherwise the nearest {@value #FILE} in the folder of the code
 * (the jar's folder, or the classes folder) or one of its parents; otherwise the same search from the working
 * directory. Without a file: no project protection and the install-local default output folders.</p>
 *
 * <p>Format: UTF-8 text, {@code key = value} per line, {@code #} or {@code !} comments. No escapes: backslashes are
 * literal, so Windows paths can be written as they are. Paths are absolute or relative to the file's folder.</p>
 * <ul>
 * <li>{@code render.dir} — default {@code render_wav} folder of the bridge</li>
 * <li>{@code analysis.dir} — default output folder of mabi-ear</li>
 * <li>{@code protect} (several paths separated by {@code ;}) and {@code protect.<anything>} (one path each) —
 * folders both servers never write into</li>
 * </ul>
 */
public final class ProjectConfig {

	public static final String FILE = "mabibridge-project.properties";
	public static final String PROPERTY = "mabibridge.project";
	public static final String ENV = "MABIBRIDGE_PROJECT";

	/** A loaded project file. Paths are absolute and normalized; null when the key is absent. */
	public record Loaded(Path file, Path renderDir, Path analysisDir, List<Path> protect, List<String> warnings) {
		public Loaded {
			protect = List.copyOf(protect);
			warnings = List.copyOf(warnings);
		}
	}

	private ProjectConfig() {}

	/** The project file for this process, or null. Not cached: tests change the property. */
	public static Loaded find() {
		Path f = locate(System.getProperty(PROPERTY), System.getenv(ENV), codeDir(), Path.of("").toAbsolutePath());
		return f == null ? null : load(f);
	}

	/** Testable lookup. Returns the file or null. An explicit path that does not exist is returned anyway (load warns). */
	static Path locate(String prop, String env, Path codeDir, Path cwd) {
		String explicit = prop != null && !prop.isBlank() ? prop : env;
		if (explicit != null && !explicit.isBlank()) {
			String t = unquote(explicit.trim());
			if ("none".equalsIgnoreCase(t)) {
				return null;
			}
			return Path.of(t).toAbsolutePath().normalize();
		}
		for (Path start : new Path[] { codeDir, cwd }) {
			for (Path p = start; p != null; p = p.getParent()) {
				Path f = p.resolve(FILE);
				if (Files.isRegularFile(f)) {
					return f.toAbsolutePath().normalize();
				}
			}
		}
		return null;
	}

	/** How a loaded file was found: the explicit setting, above the install (code) folder, or above the working directory. */
	public static String foundVia(Loaded l) {
		if (l == null) {
			return "none";
		}
		String prop = System.getProperty(PROPERTY);
		String env = System.getenv(ENV);
		if ((prop != null && !prop.isBlank()) || (env != null && !env.isBlank())) {
			return PROPERTY + " / " + ENV;
		}
		Path code = codeDir();
		Path dir = l.file().getParent();
		if (code != null && dir != null && code.toAbsolutePath().normalize().startsWith(dir)) {
			return "above the install folder";
		}
		return "above the working directory";
	}

	/** Folder of the bridge classes (the jar's folder, or the classes folder itself). */
	static Path codeDir() {
		try {
			Path code = Path.of(ProjectConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI())
					.toAbsolutePath();
			return Files.isDirectory(code) ? code : code.getParent();
		} catch (URISyntaxException | RuntimeException e) {
			return null;
		}
	}

	/** Parse a project file. A missing or unreadable file gives an empty configuration with a warning. */
	public static Loaded load(Path file) {
		List<String> warn = new ArrayList<>();
		List<Path> protect = new ArrayList<>();
		Path render = null;
		Path analysis = null;
		Path base = file.toAbsolutePath().normalize().getParent();
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			warn.add("project file not readable: " + file + " : " + e);
			return new Loaded(file, null, null, protect, warn);
		}
		int n = 0;
		for (String raw : lines) {
			n++;
			String line = raw.startsWith("﻿") ? raw.substring(1) : raw;
			String t = line.strip();
			if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) {
				continue;
			}
			int eq = t.indexOf('=');
			if (eq <= 0) {
				warn.add(FILE + ":" + n + ": no key = value, ignored");
				continue;
			}
			String key = t.substring(0, eq).strip().toLowerCase(Locale.ROOT);
			String value = t.substring(eq + 1).strip();
			try {
				if (key.equals("render.dir")) {
					render = resolve(base, value);
				} else if (key.equals("analysis.dir")) {
					analysis = resolve(base, value);
				} else if (key.equals("protect")) {
					for (String part : value.split(";")) {
						if (!part.isBlank()) {
							add(protect, resolve(base, part));
						}
					}
				} else if (key.startsWith("protect.")) {
					if (!value.isBlank()) {
						add(protect, resolve(base, value));
					}
				} else {
					warn.add(FILE + ":" + n + ": unknown key " + key + ", ignored");
				}
			} catch (InvalidPathException e) {
				warn.add(FILE + ":" + n + ": bad path, ignored: " + value);
			}
		}
		return new Loaded(file, render, analysis, protect, warn);
	}

	private static void add(List<Path> l, Path p) {
		if (p != null && !l.contains(p)) {
			l.add(p);
		}
	}

	private static Path resolve(Path base, String value) {
		String v = unquote(value.strip());
		if (v.isEmpty()) {
			return null;
		}
		Path p = Path.of(v);
		return (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
	}

	private static String unquote(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}
}
