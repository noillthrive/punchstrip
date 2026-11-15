package mabibridge.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Signature table of {@code score_finalize}, resource {@code /mabibridge/engine/signature_home.properties} (format and
 * source in its header):
 * <ul>
 * <li>{@code <program>=<pitch>}: home pitch (sounding MIDI, may be fractional) that the mean of the four passage notes
 * should lie closest to. Programs without an entry use the middle of their producible range.</li>
 * <li>{@code velocity_offset.<program>=<n>}: added to the signature velocity of that program after the velocity-scale
 * conversion, the result clamped to 1..15 (drums: the snares; the crash stays 15). No entry = no offset.</li>
 * </ul>
 */
public final class SignatureHome {

	public static final String RESOURCE = "/mabibridge/engine/signature_home.properties";
	static final String OFFSET_PREFIX = "velocity_offset.";

	private record Table(Map<Integer, Double> home, Map<Integer, Integer> offsets) {}

	private static Table table;

	private SignatureHome() {}

	private static synchronized Table table() {
		if (table == null) {
			String text;
			try (InputStream in = SignatureHome.class.getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("not on the class path: " + RESOURCE + " (ant bridge-compile copies it)");
				}
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException("unreadable: " + RESOURCE, e);
			}
			table = parse(text);
		}
		return table;
	}

	/** Parses the table text (properties). Bad lines → IllegalStateException. */
	static Table parse(String text) {
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Map<Integer, Double> home = new TreeMap<>();
		Map<Integer, Integer> offsets = new TreeMap<>();
		for (String k : p.stringPropertyNames()) {
			String key = k.trim();
			String v = p.getProperty(k).trim();
			try {
				if (key.startsWith(OFFSET_PREFIX)) {
					offsets.put(Integer.parseInt(key.substring(OFFSET_PREFIX.length())), Integer.parseInt(v.replace("+", "")));
				} else {
					home.put(Integer.parseInt(key), Double.parseDouble(v));
				}
			} catch (NumberFormatException e) {
				throw new IllegalStateException(RESOURCE + ": bad line " + k + "=" + v);
			}
		}
		return new Table(Collections.unmodifiableMap(home), Collections.unmodifiableMap(offsets));
	}

	/** Home pitch of a program, or null (then the middle of the producible range is used). */
	public static Double of(int program) {
		return table().home().get(program);
	}

	/** Signature velocity offset of a program, or null (no entry). */
	public static Integer velocityOffset(int program) {
		return table().offsets().get(program);
	}

	/** Programs with a home pitch. */
	public static java.util.Set<Integer> programs() {
		return table().home().keySet();
	}

	/** Tests: replace the table with the given properties text; {@code null} goes back to the resource. */
	static synchronized void useForTest(String text) {
		table = text == null ? null : parse(text);
	}
}
