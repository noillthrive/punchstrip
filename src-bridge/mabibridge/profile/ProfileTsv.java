package mabibridge.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 여러 프로필을 한 줄씩 모은 표(TSV). {@code profiles.tsv}는 파일 1행, {@code tracks.tsv}는 트랙 1행. 값 안의 탭·줄바꿈은 공백으로
 * 바꾼다. 음 이름은 MIDI 기준(60 = C4).
 */
public final class ProfileTsv {

	public static final String[] PROFILE_COLUMNS = { "file", "format", "title", "tracks", "tracks_with_notes", "measures",
			"duration_sec", "initial_tempo", "tempo_changes", "time_signature", "programs", "instruments", "roles", "lead",
			"chars_ratio_mean", "chars_ratio_max", "chars_over_rank1", "low_parts_max", "low_beats_ge3",
			"track_polyphony_max", "octave_diff_vs_table", "octave_estimate", "silent_notes", "timbre_mix_tracks",
			"warnings", "path" };

	public static final String[] TRACK_COLUMNS = { "file", "track", "name", "program", "instrument", "type", "sounding",
			"role", "tags", "notes", "written_min", "written_median", "written_max", "sounding_min", "sounding_median",
			"sounding_max", "silent", "timbre_sets", "vel_mean", "vel_sd", "len_mean_melody", "short_ratio_melody",
			"density_mean", "density_max", "rest_ratio", "poly_mean_active", "poly_max", "voicing_mel_c1_median",
			"voicing_c1_c2_median", "crossings", "melody_width_sounding", "low_beat_ratio", "chars_melody", "chars_chord1",
			"chars_chord2", "chars_song", "chars_ratio_max" };

	private final StringBuilder profiles = new StringBuilder();
	private final StringBuilder tracks = new StringBuilder();

	public ProfileTsv() {
		profiles.append(String.join("\t", PROFILE_COLUMNS)).append('\n');
		tracks.append(String.join("\t", TRACK_COLUMNS)).append('\n');
	}

	public String profilesTsv() {
		return profiles.toString();
	}

	public String tracksTsv() {
		return tracks.toString();
	}

	/** 프로필 하나를 두 표에 더한다. label은 file 열(보통 파일 이름). */
	@SuppressWarnings("unchecked")
	public void add(String label, Map<String, Object> profile) {
		Map<String, Object> file = (Map<String, Object>) profile.get("file");
		Map<String, Object> ens = (Map<String, Object>) profile.get("ensemble");
		List<Map<String, Object>> tl = (List<Map<String, Object>>) profile.get("tracks");
		Map<String, Object> cu = (Map<String, Object>) ens.get("chars_usage");
		Map<String, Object> low = (Map<String, Object>) ens.get("low_register");
		int pmax = 0;
		for (Map<String, Object> t : tl) {
			pmax = Math.max(pmax, num(get(t, "polyphony", "max")).intValue());
		}
		List<String> oct = new ArrayList<>();
		List<String> est = new ArrayList<>();
		for (Map<String, Object> o : (List<Map<String, Object>>) ens.get("octave_correction")) {
			String key = o.get("track") + "/" + o.get("program") + ("song".equals(o.get("parts")) ? "s" : "");
			Object diff = o.get("diff");
			Object tab = o.get("table_offset");
			Object m = o.get("matches_table");
			oct.add(key + ":" + signed(diff) + "/" + signed(tab) + (Boolean.TRUE.equals(m) ? "=" : m == null ? "" : "≠"));
			est.add(key + ":" + o.get("estimate"));
		}
		List<String> prog = new ArrayList<>();
		for (Object p : (List<Object>) ens.get("programs")) {
			prog.add(String.valueOf(p));
		}
		row(profiles, label, file.get("format"), file.get("title"), ens.get("track_count"), ens.get("tracks_with_notes"),
				file.get("measures"), file.get("duration_sec"), file.get("initial_tempo"),
				((List<?>) file.get("tempos")).size(), file.get("time_signature"), String.join(",", prog),
				String.join(",", (List<String>) ens.get("instruments")), ens.get("role_text"), joinObj(ens.get("lead")),
				cu.get("mean_ratio"), cu.get("max_ratio"), joinObj(cu.get("over_rank1")), low.get("max_parts"),
				low.get("beats_ge3"), pmax, String.join(" ", oct), String.join(" ", est), ens.get("silent_notes"),
				joinObj(ens.get("timbre_mix_tracks")), ((List<?>) profile.get("warnings")).size(), file.get("path"));

		for (Map<String, Object> t : tl) {
			Map<String, Object> role = (Map<String, Object>) t.get("role");
			Map<String, Object> w = (Map<String, Object>) t.get("written_range");
			Map<String, Object> s = (Map<String, Object>) t.get("sounding_range");
			Map<String, Object> v = (Map<String, Object>) t.get("velocity");
			Map<String, Object> parts = (Map<String, Object>) t.get("parts");
			Map<String, Object> mel = (Map<String, Object>) parts.get("melody");
			Map<String, Object> melLen = (Map<String, Object>) mel.get("length");
			double cmax = 0;
			Object[] chars = new Object[4];
			int i = 0;
			for (String pk : new String[] { "melody", "chord1", "chord2", "song" }) {
				Map<String, Object> pj = (Map<String, Object>) parts.get(pk);
				chars[i++] = pj.get("chars");
				if (num(pj.get("notes_whole_song")).intValue() > 0) {
					cmax = Math.max(cmax, num(pj.get("chars_ratio_default")).doubleValue());
				}
			}
			Map<String, Object> voi = (Map<String, Object>) t.get("voicing");
			Map<String, Object> mc1 = (Map<String, Object>) voi.get("melody_chord1");
			Map<String, Object> c1c2 = (Map<String, Object>) voi.get("chord1_chord2");
			int cross = num(mc1.get("crossings")).intValue() + num(c1c2.get("crossings")).intValue();
			row(tracks, label, t.get("track"), t.get("name"), t.get("program"), t.get("instrument"), t.get("instrument_type"),
					t.get("sounding"), role.get("role"), joinObj(role.get("tags")), t.get("notes"), w.get("min_name"),
					w.get("median_name"), w.get("max_name"), s.get("min_name"), s.get("median_name"), s.get("max_name"),
					s.get("silent"), mapText(t.get("timbre_sets")), v == null ? null : v.get("mean"),
					v == null ? null : v.get("sd"), melLen == null ? null : melLen.get("mean_ticks"),
					melLen == null ? null : melLen.get("short_ratio"), get(t, "density", "mean"), get(t, "density", "max"),
					t.get("rest_measure_ratio"), get(t, "polyphony", "mean_active"), get(t, "polyphony", "max"),
					mc1.get("median"), c1c2.get("median"), cross, get(t, "melody_range_width", "sounding"),
					get(t, "low_register", "ratio"), chars[0], chars[1], chars[2], chars[3], Math.round(cmax * 1000) / 1000.0);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object get(Map<String, Object> m, String a, String b) {
		Object x = m.get(a);
		return x instanceof Map ? ((Map<String, Object>) x).get(b) : null;
	}

	private static Number num(Object o) {
		return o instanceof Number n ? n : 0;
	}

	private static String signed(Object o) {
		if (!(o instanceof Number n)) {
			return "-";
		}
		int x = n.intValue();
		return (x > 0 ? "+" : "") + x;
	}

	private static String joinObj(Object o) {
		if (!(o instanceof List<?> l)) {
			return o == null ? "" : String.valueOf(o);
		}
		List<String> s = new ArrayList<>();
		for (Object x : l) {
			s.add(String.valueOf(x));
		}
		return String.join(",", s);
	}

	@SuppressWarnings("unchecked")
	private static String mapText(Object o) {
		if (!(o instanceof Map)) {
			return "";
		}
		List<String> s = new ArrayList<>();
		for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
			s.add(e.getKey() + "=" + e.getValue());
		}
		return String.join(",", s);
	}

	private static void row(StringBuilder b, Object... cells) {
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				b.append('\t');
			}
			Object c = cells[i];
			b.append(c == null ? "" : String.valueOf(c).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
		}
		b.append('\n');
	}
}
