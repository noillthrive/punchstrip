package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.core.MMLException;
import jp.fourthline.mmlTools.core.MMLText;
import jp.fourthline.mmlTools.core.MMLTokenizer;
import jp.fourthline.mmlTools.core.MelodyParser;
import jp.fourthline.mmlTools.core.ParserWarn3ML;
import mabibridge.midi.MeasureMap;

/**
 * {@code track_import_mml}: add a track from {@code MML@melody,chord1,chord2[,song];} text.
 *
 * <p>Sheets of a Zabkiel book are joined part by part in order with MabiIcco's own {@link MMLText#join} (the same as
 * {@code ui.mml.MMLXImportPanel.textJoin()}), so a later sheet continues the o/l/v/t state left by the previous one.
 * The joined parts are parsed by MabiIcco's {@link MMLTrack#setMML(String, String, String, String)} (the four-string
 * form, so an empty melody does not get the "melody empty" filler) and the track is added with {@link MMLScore#addTrack}
 * as {@code ui.mml.MMLInputPanel.applyMMLTrack()} does: tempo commands in the text are merged into the score's global
 * tempo list (MabiIcco behaviour; reported in {@code tempo_changes}).</p>
 *
 * <p>Parse warnings come from a scan with MabiIcco's {@link MMLTokenizer} and {@link MelodyParser} that follows the
 * token loop of {@code MMLEventParser} (which only prints these problems to stderr): a token the parser rejects is
 * skipped and does not advance time.</p>
 */
public final class MmlImport {

	static final int MAX_SHEETS = 64;
	static final int WARN_LIST = 30;
	private static final String ALLOWED = "abcdefgABCDEFGnNrRtToOlLvV<>&0123456789.+#-";
	private static final Pattern NOTE = Pattern.compile("[a-gA-GrR][+#-]?\\d*\\.*");
	private static final Pattern N_CMD = Pattern.compile("[nN]\\d+");
	private static final Pattern L_CMD = Pattern.compile("[lL]\\d+\\.*");
	private static final Pattern NUM_CMD = Pattern.compile("[oOtTvV]\\d+");

	/** One part of the new track. */
	public record PartInfo(int notes, int inputChars, int chars) {}

	/** A tempo command found in the text. */
	public record TempoInfo(int tick, String pos, int bpm) {}

	/** A parse warning: part, sheet (1-based), character index inside that sheet's part, token. */
	public record ParseWarning(String part, int sheet, int index, String token, String message) {}

	/** Tool response. */
	public record Result(boolean ok, int track, String name, int program, String instrument, String instrumentType,
			int songProgram, int sheets, List<Map<String, Integer>> sheetChars, Map<String, PartInfo> parts, int notes,
			CharLimits.Evaluation rankLimits, boolean fits, List<TempoInfo> tempos, List<Map<String, Object>> scoreTemposBefore,
			List<Map<String, Object>> scoreTemposAfter, boolean tempoChanged, int parseWarningCount,
			List<ParseWarning> parseWarnings, int whitespaceRemoved, Map<String, Integer> renumbered, Integer snapshotId,
			Snapshots.SnapshotInfo snapshot, List<String> warnings) {}

	private MmlImport() {}

	/** "MML@a,b,c[,d];" (whitespace removed) → 1-4 parts. BAD_ARGUMENT when it is not MML. */
	static String[] parseSheet(String raw, int sheet, int[] ws) {
		if (raw == null) {
			throw new EngineException(BAD_ARGUMENT, "MML 문자열이 없음(sheet " + sheet + ")", details("sheet", sheet));
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (Character.isWhitespace(c)) {
				ws[0]++;
			} else {
				sb.append(c);
			}
		}
		String s = sb.toString();
		if (!s.startsWith("MML@")) {
			throw new EngineException(BAD_ARGUMENT, "MML 글이 아님: \"MML@\"로 시작해야 함(sheet " + sheet + ")",
					details("sheet", sheet, "start", s.substring(0, Math.min(20, s.length()))));
		}
		String body = s.substring(4);
		int semi = body.indexOf(';');
		if (semi >= 0) {
			if (semi != body.length() - 1) {
				throw new EngineException(BAD_ARGUMENT, "';' 뒤에 글자가 있음(sheet " + sheet + ") — 트랙 하나의 MML@…; 하나만 준다",
						details("sheet", sheet, "after", body.substring(semi + 1, Math.min(body.length(), semi + 21))));
			}
			body = body.substring(0, semi);
		}
		String[] parts = body.split(",", -1);
		if (parts.length > 4) {
			throw new EngineException(BAD_ARGUMENT, "파트가 " + parts.length + "개(sheet " + sheet
					+ ") — melody, chord1, chord2, song 4개까지", details("sheet", sheet, "parts", parts.length));
		}
		List<Map<String, Object>> bad = new ArrayList<>();
		for (int p = 0; p < parts.length; p++) {
			String t = parts[p];
			for (int i = 0; i < t.length() && bad.size() < 10; i++) {
				if (ALLOWED.indexOf(t.charAt(i)) < 0) {
					bad.add(details("sheet", sheet, "part", Part.ofIndex(p).key(), "index", i, "char", String.valueOf(t.charAt(i)),
							"context", t.substring(Math.max(0, i - 8), Math.min(t.length(), i + 8))));
				}
			}
		}
		if (!bad.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "MML에 쓸 수 없는 글자가 있음: " + bad.get(0).get("char") + " (sheet " + sheet + " "
					+ bad.get(0).get("part") + " " + bad.get(0).get("index") + "번째)", details("bad_chars", bad));
		}
		String[] out = { "", "", "", "" };
		System.arraycopy(parts, 0, out, 0, parts.length);
		return out;
	}

	static Result run(Project p, List<String> sheetsArg, int program, String nameArg, Integer songProgramArg,
			Integer panpot, Integer volume, Integer insertAt, String rank, String rule) {
		if (sheetsArg == null || sheetsArg.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "mml 또는 sheets가 필요함(\"MML@멜로디,화음1,화음2[,노래];\")");
		}
		if (sheetsArg.size() > MAX_SHEETS) {
			throw new EngineException(BAD_ARGUMENT, "sheets는 " + MAX_SHEETS + "장까지", details("sheets", sheetsArg.size()));
		}
		MMLScore score = p.score();
		if (score.getTrackCount() >= MMLScore.MAX_TRACK) {
			throw new EngineException(EngineException.TOO_MANY_TRACKS, "트랙은 최대 " + MMLScore.MAX_TRACK + "개",
					details("track_count", score.getTrackCount()));
		}
		int count = score.getTrackCount();
		int pos = insertAt == null ? count + 1 : insertAt;
		if (pos < 1 || pos > count + 1) {
			throw new EngineException(BAD_ARGUMENT, "insert_at은 1~" + (count + 1) + "(트랙 수 + 1 = 맨 뒤)",
					details("insert_at", insertAt, "track_count", count));
		}
		String name = (nameArg == null || nameArg.isEmpty()) ? "Track" + (count + 1) : nameArg;
		p.checkTrackName(name);
		p.checkTrackProgram(program);
		int sp = songProgramArg == null ? MMLTrack.NO_CHORUS : songProgramArg;
		p.checkTrackSongProgram(sp);
		int pan = panpot == null ? 64 : panpot;
		int vol = volume == null ? MMLTrack.INITIAL_VOLUME : volume;
		if (pan < 0 || pan > 127) {
			throw new EngineException(BAD_ARGUMENT, "panpot는 0~127", details("panpot", pan));
		}
		if (vol < 0 || vol > 127) {
			throw new EngineException(BAD_ARGUMENT, "volume는 0~127", details("volume", vol));
		}
		CharLimits.rank(rank);
		if (rule != null) {
			CharLimits.checkRule(rule);
		}

		// sheets → joined parts (MMLText.join, as MabiIcco's book import)
		int[] ws = new int[1];
		MMLText joined = new MMLText();
		List<Map<String, Integer>> sheetChars = new ArrayList<>();
		List<int[]> sheetStart = new ArrayList<>(); // start index of each sheet inside the joined part, per part
		int[] run = new int[4];
		for (int i = 0; i < sheetsArg.size(); i++) {
			String[] parts = parseSheet(sheetsArg.get(i), i + 1, ws);
			sheetStart.add(run.clone());
			Map<String, Integer> c = new LinkedHashMap<>();
			for (Part part : Part.values()) {
				c.put(part.key(), parts[part.index()].length());
				run[part.index()] += parts[part.index()].length();
			}
			sheetChars.add(c);
			joined.join(new MMLText().setMMLText(parts));
		}
		String[] text = new String[4];
		for (int i = 0; i < 4; i++) {
			text[i] = joined.getText(i);
		}

		// parse warnings (MabiIcco tokenizer + MelodyParser, MMLEventParser's loop)
		List<ParseWarning> pw = new ArrayList<>();
		int[] pwCount = new int[1];
		for (Part part : Part.values()) {
			scan(text[part.index()], part, sheetStart, pw, pwCount);
		}

		// parse with MabiIcco
		MMLTrack t = new MMLTrack().setMML(text[0], text[1], text[2], text[3]);
		int[] noteCounts = new int[4];
		int total = 0;
		for (Part part : Part.values()) {
			noteCounts[part.index()] = t.getMMLEventAtIndex(part.index()).getMMLNoteEventList().size();
			total += noteCounts[part.index()];
		}
		if (total == 0) {
			throw new EngineException(BAD_ARGUMENT, "MML에 음이 하나도 없음(쉼표·명령만) — 가져올 것이 없다",
					details("chars", Map.of("melody", text[0].length(), "chord1", text[1].length(), "chord2", text[2].length(),
							"song", text[3].length())));
		}
		InstrumentTable.Instrument inst = p.engine().instrumentTable().get(program);
		String type = inst == null ? null : inst.type();
		boolean songInst = "V".equals(type) || "C".equals(type);
		List<String> warnings = new ArrayList<>();
		if (noteCounts[Part.SONG.index()] > 0 && !songInst && sp < 0) {
			throw new EngineException(BAD_ARGUMENT, "4번째 파트(노래)에 음이 " + noteCounts[Part.SONG.index()] + "개 있는데 악기 " + program
					+ (inst == null ? "" : "(" + inst.name() + ", 타입 " + type + ")")
					+ "는 노래 악기가 아님 — 노래 파트를 빼거나 song_program(코러스 C 악기 번호)을 준다",
					details("program", program, "type", type, "song_notes", noteCounts[Part.SONG.index()], "song_program", sp));
		}
		for (Part part : new Part[] { Part.MELODY, Part.CHORD1, Part.CHORD2 }) {
			if (noteCounts[part.index()] > 0 && inst != null && !inst.allowsPart(part)) {
				warnings.add("part_not_played: 악기 " + program + "(" + inst.name() + ", 타입 " + type + ")는 " + part.key()
						+ " 파트를 쓰지 않음(음 " + noteCounts[part.index()] + "개)");
			}
		}
		List<TempoInfo> tempos = new ArrayList<>();
		MeasureMap mapBefore = p.measureMap();
		for (MMLTempoEvent e : t.getGlobalTempoList()) {
			tempos.add(new TempoInfo(e.getTickOffset(), mapBefore.position(e.getTickOffset()).toString(), e.getTempo()));
		}
		List<Map<String, Object>> tBefore = tempoList(score.getTempoEventList(), mapBefore);

		Snapshots.SnapshotInfo snap = p.engine().snapshots().snapshot(score, "auto: track_import_mml " + name);
		String fname = name;
		int fsp = sp;
		p.mutate(() -> {
			t.setTrackName(fname);
			t.setProgram(program);
			t.setSongProgram(fsp);
			t.setPanpot(pan);
			t.setVolume(vol);
			int i = score.addTrack(t);
			if (i < 0) {
				throw new EngineException(EngineException.TOO_MANY_TRACKS, "트랙은 최대 " + MMLScore.MAX_TRACK + "개");
			}
			if (pos - 1 != i) {
				score.moveTrack(i, pos - 1);
			}
		});
		Map<String, Integer> renumbered = new LinkedHashMap<>();
		for (int n = pos; n <= count; n++) {
			renumbered.put(String.valueOf(n), n + 1);
		}
		p.remapTrackRules(n -> n >= pos ? n + 1 : n);
		List<Map<String, Object>> tAfter = tempoList(score.getTempoEventList(), p.measureMap());
		boolean tempoChanged = !tBefore.equals(tAfter);
		if (tempoChanged) {
			warnings.add("tempo_merged: 글의 t 명령 " + tempos.size() + "개가 악보 전역 템포 목록에 들어감(MabiIcco 가져오기와 같음, 모든 트랙에 적용) — "
					+ "필요하면 project_set_tempos로 고친다");
		}
		if (ws[0] > 0) {
			warnings.add("whitespace_removed: 공백·줄바꿈 " + ws[0] + "글자를 지움");
		}
		if (pwCount[0] > 0) {
			warnings.add("parse_warnings: MabiIcco 해석기가 무시하거나 건너뛴 토큰 " + pwCount[0] + "개(parse_warnings)");
		}
		int[] chars = TrackCopy.mmlChars(t);
		Map<String, PartInfo> parts = new LinkedHashMap<>();
		for (Part part : Part.values()) {
			parts.put(part.key(), new PartInfo(noteCounts[part.index()], text[part.index()].length(), chars[part.index()]));
		}
		CharLimits.Evaluation ev = p.rankCheck(pos, rank, rule);
		if (!ev.fits()) {
			warnings.add("rank_over: 트랙 " + pos + " " + ev.overText() + " (랭크 " + ev.rank() + ", " + ev.songRule() + ")");
		}
		String uw = ScoreChecks.unisonWarning(p.unisonClash(pos));
		if (uw != null) {
			warnings.add("트랙 " + pos + ": " + uw);
		}
		return new Result(true, pos, name, program, inst == null ? null : inst.name(), type, sp, sheetsArg.size(), sheetChars,
				parts, total, ev, ev.fits(), tempos, tBefore, tAfter, tempoChanged, pwCount[0], pw, ws[0], renumbered,
				snap.id(), snap, warnings);
	}

	private static List<Map<String, Object>> tempoList(List<MMLTempoEvent> l, MeasureMap map) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (MMLTempoEvent e : l) {
			out.add(details("tick", e.getTickOffset(), "pos", map.position(e.getTickOffset()).toString(), "bpm", e.getTempo()));
		}
		return out;
	}

	/** Token scan following {@code MMLEventParser.parseNextEvent}; collects what it would skip or ignore. */
	static void scan(String mml, Part part, List<int[]> sheetStart, List<ParseWarning> out, int[] count) {
		if (mml.isEmpty()) {
			return;
		}
		MMLTokenizer tok = new MMLTokenizer(mml);
		MelodyParser parser = new MelodyParser(mml);
		while (tok.hasNext()) {
			String token = tok.next();
			int index = tok.getStart();
			char c = token.charAt(0);
			String msg = null;
			if (c == '&' || c == '<' || c == '>') {
				if (token.length() > 1) {
					msg = "명령 뒤에 알 수 없는 글자";
				}
			} else if (c == 'v' || c == 'V') {
				if (!NUM_CMD.matcher(token).matches()) {
					msg = "세기 v 뒤에 숫자가 없음(무시됨)";
				} else {
					int v = Integer.parseInt(token.substring(1));
					if (v > 15) {
						msg = "세기 " + v + "는 0~15 밖(무시됨)";
					}
				}
			} else if (c == 't' || c == 'T') {
				if (!NUM_CMD.matcher(token).matches()) {
					msg = "템포 t 뒤에 숫자가 없음(무시됨)";
				} else {
					int v = Integer.parseInt(token.substring(1));
					if (v <= 0 || v > 1000) {
						msg = "템포 " + v + "는 MabiIcco가 받지 않음(무시됨)";
					} else if (v < 32 || v > 255) {
						msg = "템포 " + v + "는 마비노기가 따르는 32~255 밖";
					}
				}
			} else {
				boolean shapeOk = switch (c) {
				case 'n', 'N' -> N_CMD.matcher(token).matches();
				case 'l', 'L' -> L_CMD.matcher(token).matches();
				case 'o', 'O' -> NUM_CMD.matcher(token).matches();
				default -> NOTE.matcher(token).matches();
				};
				try {
					parser.noteGT(token);
					if (!shapeOk) {
						msg = "알 수 없는 토큰 모양(MabiIcco는 읽지만 뜻이 다를 수 있음)";
					}
				} catch (MMLException | ParserWarn3ML | RuntimeException e) {
					msg = "MabiIcco 해석기가 건너뜀: " + e.getMessage();
				}
			}
			if (msg != null) {
				count[0]++;
				if (out.size() < WARN_LIST) {
					int sheet = 1;
					for (int s = 0; s < sheetStart.size(); s++) {
						int start = sheetStart.get(s)[part.index()];
						int next = s + 1 < sheetStart.size() ? sheetStart.get(s + 1)[part.index()] : Integer.MAX_VALUE;
						if (start <= index && index < next) {
							sheet = s + 1;
							break;
						}
					}
					out.add(new ParseWarning(part.key(), sheet, index - sheetStart.get(sheet - 1)[part.index()], token, msg));
				}
			}
		}
	}
}
