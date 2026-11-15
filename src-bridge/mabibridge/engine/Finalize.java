package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jp.fourthline.mmlTools.MMLEventList;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_finalize}: a finished score gets (a) a lead-in with the bridge's tuning signature in every track and (b)
 * silent end padding so that every part ends at the same moment. Existing notes are never deleted or altered (the
 * lead-in is a pure shift of all time-anchored data).
 *
 * <p><b>Signature "bridge"</b>: unit u from the tempo at tick 0 (&lt; 85 → 12 ticks, &lt; 170 → 24, else 48). Sounding
 * pitch classes B, D, G at 0, u, 2u (length u), then E at 3u held for 5u (the passage ends at 8u). Pitched tracks: B D G
 * ascending within one octave, then the E a major sixth above the G if producible, otherwise the E a minor third below
 * it; the octave is the one whose four notes are all producible and whose mean lies closest to the instrument's
 * signature home pitch ({@link SignatureHome}, {@code signature_home.properties}; ties: higher; no entry: middle of the
 * producible range). It depends on the instrument only, so the passage sounds the same in every score. Voice / chorus
 * tracks (type V/C): the
 * same in the song slot. Drums (27): snare at 0, u, 2u (the velocity argument), crash at 3u (velocity 15).</p>
 *
 * <p><b>Lead-in</b>: n whole measures of the tick-0 time signature, n the smallest integer ≥ 1 with n × measure ≥ 8u +
 * 96. Inserted with MabiIcco's own measure insertion ({@code MMLScore.addTicks(0, true)}: notes, tempo changes, markers,
 * time signatures, bar-line types) and the tempo events of tick 0 kept at tick 0 so the tick-0 tempo also covers the
 * lead-in.</p>
 *
 * <p><b>End padding</b>: T_end = the latest note end. Each track with notes whose own last end is more than
 * {@value #PAD_LEN} ticks before T_end gets one velocity-0 note ending at T_end (length {@value #PAD_LEN}; longer only
 * when the rest before it would be shorter than the shortest MML length), at its last note's pitch, in the slot where
 * it fits and costs the fewest characters.</p>
 */
public final class Finalize {

	public static final String TUNING_SKIP = "skip";
	public static final String TUNING_PREPEND = "prepend";
	public static final int DEFAULT_VELOCITY = 12;
	public static final int CRASH_VELOCITY = 15;
	public static final int PAD_LEN = 6;
	/** Room after the passage inside the lead-in (one quarter). */
	public static final int LEAD_IN_GAP = 96;
	/** Sounding pitch classes of the passage: B, D, G, E. */
	static final int[] PITCH_CLASSES = { 11, 2, 7, 4 };
	static final String[] PITCH_LETTERS = { "B", "D", "G", "E" };

	/** Arguments. rank / songRule null = defaults (rank "1", the track's default rule). */
	public record Options(boolean signature, boolean endPadding, int velocity, String onExistingTuning, boolean dryRun,
			String rank, String songRule) {}

	/** One track's signature result. status: added | skipped. */
	public record SignatureTrack(int track, String name, int program, String instrument, String kind, String status,
			String slot, List<String> sounding, List<Integer> written, String timbre, Double targetPitch,
			String targetSource, Integer charsAdded, String reason, Map<String, Object> detail, List<Integer> velocities,
			String velocityRule, Integer velocityOffset) {}

	/**
	 * Signature section. status: added (every eligible track), partial (some tracks skipped), skipped (nothing added,
	 * see reason), already_present, existing_tuning, disabled. bpm is the tempo in effect at tick 0 (the unit follows it);
	 * bpmDefault true when the score has no tick-0 tempo event and plays at the default 120.
	 */
	public record Signature(String status, String reason, Integer bpm, Integer unitTicks, String unit,
			Integer measuresInserted, Integer shiftTicks, String measureSignature, Integer passageTicks,
			List<Map<String, Object>> leadInOver, List<Map<String, Object>> existing, List<SignatureTrack> tracks,
			Boolean bpmDefault, Map<String, Object> leadInTempo) {

		Signature(String status, String reason, Integer bpm, Integer unitTicks, String unit, Integer measuresInserted,
				Integer shiftTicks, String measureSignature, Integer passageTicks, List<Map<String, Object>> leadInOver,
				List<Map<String, Object>> existing, List<SignatureTrack> tracks) {
			this(status, reason, bpm, unitTicks, unit, measuresInserted, shiftTicks, measureSignature, passageTicks,
					leadInOver, existing, tracks, null, null);
		}
	}

	/** One track's end padding. status: added | skipped (no room) | already_present | not_needed | no_notes. */
	public record PaddingTrack(int track, String name, String status, Integer endTick, String slot, Integer tick,
			String pos, Integer len, Integer pitch, String pitchName, Integer charsAdded, String reason,
			Map<String, Integer> costBySlot) {}

	/**
	 * End padding section. status: done (notes added in this call) | skipped (none added, some tracks without room) |
	 * already_present (padding notes from an earlier call) | nothing_to_do | no_notes | disabled.
	 */
	public record Padding(String status, Integer endTick, String endPos, List<PaddingTrack> tracks) {}

	/** Characters per slot before / after, judged with rank / rule. */
	public record TrackChars(int track, String name, String songRule, Map<String, Integer> before,
			Map<String, Integer> after, Map<String, Integer> added, boolean fitsBefore, boolean fitsAfter,
			List<String> overAfter) {}

	/** Tool answer. */
	/**
	 * Tool answer. {@code changed}: the score was changed by this call (always false with dry_run);
	 * {@code wouldChange}: the call changes (or, with dry_run, would change) the score.
	 */
	public record Result(boolean ok, boolean dryRun, boolean changed, boolean wouldChange, String rank, Signature signature,
			Padding padding,
			List<TrackChars> chars, Integer snapshotId, Snapshots.SnapshotInfo snapshot, List<String> warnings,
			String text) {}

	private Finalize() {}

	// ======================================================================
	// unit / lead-in
	// ======================================================================

	/** Unit ticks from the tick-0 tempo: &lt; 85 → 12 (32nd), 85..169 → 24 (16th), ≥ 170 → 48 (8th). */
	public static int unitTicks(int bpm) {
		if (bpm < 85) {
			return 12;
		}
		return bpm < 170 ? 24 : 48;
	}

	static String unitName(int u) {
		return switch (u) {
		case 12 -> "32분음표";
		case 24 -> "16분음표";
		default -> "8분음표";
		};
	}

	/** Measures to insert: smallest n ≥ 1 with n × measureTicks ≥ 8u + 96. */
	public static int leadInMeasures(long measureTicks, int u) {
		long need = 8L * u + LEAD_IN_GAP;
		return (int) Math.max(1, (need + measureTicks - 1) / measureTicks);
	}

	// ======================================================================
	// drum keys
	// ======================================================================

	private static int[] drumKeys;

	/** {snare, crash} written keys of drum 27 from the bridge's key table (ids "snare" and "crash"). */
	static synchronized int[] drumKeys() {
		if (drumKeys == null) {
			try (InputStream in = Finalize.class.getResourceAsStream("/mabibridge/engine/drum27_keys.json")) {
				if (in == null) {
					throw new IllegalStateException("drum27_keys.json not on the class path");
				}
				JsonObject t = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				Integer snare = null;
				Integer crash = null;
				for (JsonElement e : t.getAsJsonArray("keys")) {
					JsonObject k = e.getAsJsonObject();
					String id = k.get("id").getAsString();
					if ("snare".equals(id)) {
						snare = k.get("key").getAsInt();
					} else if ("crash".equals(id)) {
						crash = k.get("key").getAsInt();
					}
				}
				if (snare == null || crash == null) {
					throw new IllegalStateException("drum27_keys.json has no snare / crash key");
				}
				drumKeys = new int[] { snare, crash };
			} catch (IOException e) {
				throw new IllegalStateException("drum27_keys.json unreadable", e);
			}
		}
		return drumKeys.clone();
	}

	// ======================================================================
	// run
	// ======================================================================

	static Result run(Project p, Options o) {
		if (o.velocity() < 1 || o.velocity() > 15) {
			throw new EngineException(BAD_ARGUMENT, "velocity는 1~15", details("velocity", o.velocity()));
		}
		String onTuning = o.onExistingTuning() == null || o.onExistingTuning().isBlank() ? TUNING_SKIP
				: o.onExistingTuning().trim().toLowerCase(Locale.ROOT);
		if (!TUNING_SKIP.equals(onTuning) && !TUNING_PREPEND.equals(onTuning)) {
			throw new EngineException(BAD_ARGUMENT, "on_existing_tuning은 skip 또는 prepend",
					details("on_existing_tuning", o.onExistingTuning()));
		}
		CharLimits.Rank rank = CharLimits.rank(o.rank());
		String ruleArg = CharLimits.checkRule(o.songRule());
		if (p.trackCount() == 0) {
			throw new EngineException(EngineException.NO_TRACK, "트랙이 없음(track_add 먼저)");
		}
		Ctx c = new Ctx(p, rank, ruleArg, o.velocity());
		byte[] state0 = p.state();
		boolean dirty0 = p.dirty();
		List<int[]> charsBefore = c.allChars();
		List<CharLimits.Evaluation> evalBefore = c.allEvals();
		List<String> warnings = new ArrayList<>();
		for (int i = 0; i < evalBefore.size(); i++) {
			String vw = p.voiceRuleWarning(i + 1, ruleArg);
			if (vw != null) {
				warnings.add(vw);
			}
			if (!evalBefore.get(i).fits()) {
				warnings.add("already_over: 트랙 " + (i + 1) + " " + evalBefore.get(i).overText() + " — finalize 전부터 한도를 넘음"
						+ "(머리 마디가 더하는 글자는 그 트랙의 다른 칸 판정에 쓰지 않음)");
			}
		}

		Signature sig = o.signature() ? signature(c, onTuning, evalBefore, warnings) : disabledSignature();
		Padding pad = o.endPadding() ? padding(c) : new Padding("disabled", null, null, List.of());

		List<int[]> charsAfter = c.allChars();
		List<CharLimits.Evaluation> evalAfter = c.allEvals();
		boolean changed = !java.util.Arrays.equals(state0, p.state());
		// the snapshot holds the state before this call; it is written only when the call really changes the score
		Snapshots.SnapshotInfo snap = o.dryRun() || !changed ? null
				: p.engine().snapshots().snapshot(state0, "auto: score_finalize");
		List<TrackChars> chars = new ArrayList<>();
		for (int n = 1; n <= p.trackCount(); n++) {
			int[] b = charsBefore.get(n - 1);
			int[] a = charsAfter.get(n - 1);
			int[] d = new int[4];
			for (int i = 0; i < 4; i++) {
				d[i] = a[i] - b[i];
			}
			CharLimits.Evaluation ea = evalAfter.get(n - 1);
			chars.add(new TrackChars(n, p.trackRef(n).getTrackName(), ea.songRule(), charMap(b), charMap(a), charMap(d),
					evalBefore.get(n - 1).fits(), ea.fits(), ea.over()));
		}
		if (o.dryRun()) {
			p.restoreState(state0);
			p.setDirty(dirty0);
		} else if (!changed) {
			p.setDirty(dirty0);
		}
		String text = text(o.dryRun(), changed, sig, pad);
		return new Result(true, o.dryRun(), changed && !o.dryRun(), changed, rank.name(), sig, pad, chars,
				snap == null ? null : snap.id(), snap,
				warnings, text);
	}

	private static Signature disabledSignature() {
		return new Signature("disabled", "signature: false", null, null, null, null, null, null, null, List.of(), List.of(),
				List.of());
	}

	private static String text(boolean dry, boolean changed, Signature s, Padding pd) {
		StringBuilder t = new StringBuilder(dry ? "(dry_run, 바꾸지 않음) " : "");
		switch (s.status()) {
		case "added", "partial" -> {
			long added = s.tracks().stream().filter(x -> "added".equals(x.status())).count();
			t.append("시그니처: ").append(s.measuresInserted()).append("마디를 앞에 넣고(마디 번호 +").append(s.measuresInserted())
					.append(") 트랙 ").append(added).append('/').append(s.tracks().size()).append("개에 ").append(s.unit())
					.append(" 단위로 넣음");
			if ("partial".equals(s.status())) {
				t.append(", 자리가 없어 빠진 트랙 있음");
			}
			t.append(". ");
		}
		case "disabled" -> t.append("시그니처: 넣지 않음(signature false). ");
		default -> t.append("시그니처: 넣지 않음(").append(s.reason()).append("). ");
		}
		switch (pd.status()) {
		case "done" -> {
			long added = pd.tracks().stream().filter(x -> "added".equals(x.status())).count();
			long skipped = pd.tracks().stream().filter(x -> "skipped".equals(x.status())).count();
			long present = pd.tracks().stream().filter(x -> "already_present".equals(x.status())).count();
			t.append("끝 맞춤: ").append(pd.endPos()).append("에서 끝나도록 무음 음표 ").append(added).append("개 새로 넣음")
					.append(present > 0 ? ", 이미 있던 것 " + present + "개" : "")
					.append(skipped > 0 ? ", 자리가 없어 " + skipped + "트랙 못 넣음" : "").append('.');
		}
		case "skipped" -> {
			long skipped = pd.tracks().stream().filter(x -> "skipped".equals(x.status())).count();
			t.append("끝 맞춤: 새로 넣은 음 없음 — 자리가 없어 ").append(skipped).append("트랙 못 넣음(이유는 tracks[].reason).");
		}
		case "already_present" -> {
			long present = pd.tracks().stream().filter(x -> "already_present".equals(x.status())).count();
			t.append("끝 맞춤: 이미 되어 있음(세기 0 음 ").append(present).append("개), 새로 넣은 음 없음.");
		}
		case "nothing_to_do" -> t.append("끝 맞춤: 모든 트랙이 이미 같이 끝남.");
		case "no_notes" -> t.append("끝 맞춤: 음표가 없음.");
		default -> t.append("끝 맞춤: 하지 않음(end_padding false).");
		}
		if (!changed && !dry) {
			t.append(" 악보는 그대로.");
		}
		return t.toString();
	}

	// ======================================================================
	// context
	// ======================================================================

	/** Shared state of one run. */
	private static final class Ctx {
		final Project p;
		final CharLimits.Rank rank;
		final String ruleArg;
		final int velocity;
		final InstrumentTable table;

		Ctx(Project p, CharLimits.Rank rank, String ruleArg, int velocity) {
			this.p = p;
			this.rank = rank;
			this.ruleArg = ruleArg;
			this.velocity = velocity;
			this.table = p.engine().instrumentTable();
		}

		MMLScore score() {
			return p.score();
		}

		MMLTrack track(int n) {
			return p.trackRef(n);
		}

		String rule(int n) {
			return ruleArg != null ? ruleArg : p.ruleOf(n);
		}

		CharLimits.Evaluation eval(int n) {
			return CharLimits.evaluate(track(n), rank, rule(n));
		}

		List<CharLimits.Evaluation> allEvals() {
			List<CharLimits.Evaluation> l = new ArrayList<>();
			for (int n = 1; n <= p.trackCount(); n++) {
				l.add(eval(n));
			}
			return l;
		}

		List<int[]> allChars() {
			List<int[]> l = new ArrayList<>();
			for (int n = 1; n <= p.trackCount(); n++) {
				l.add(TrackCopy.mmlChars(track(n)));
			}
			return l;
		}

		InstrumentTable.Instrument inst(int n) {
			return table.get(track(n).getProgram());
		}

		/** Slots the track's instrument plays (voice / chorus → song only). */
		List<Part> slots(int n) {
			InstrumentTable.Instrument i = inst(n);
			List<Part> l = new ArrayList<>();
			if (i == null) {
				l.add(Part.MELODY);
				l.add(Part.CHORD1);
				l.add(Part.CHORD2);
				return l;
			}
			for (Part pt : Part.values()) {
				if (i.allowsPart(pt)) {
					l.add(pt);
				}
			}
			return l;
		}

		boolean voice(int n) {
			InstrumentTable.Instrument i = inst(n);
			return i != null && Project.isVoiceType(i.type());
		}
	}

	private static Map<String, Integer> charMap(int[] c) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part pt : Part.values()) {
			m.put(pt.key(), c[pt.index()]);
		}
		return m;
	}

	/** Slots over the limit that were not over in {@code base} (slot → over_by). */
	private static Map<String, Integer> newlyOver(CharLimits.Evaluation now, CharLimits.Evaluation base) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Map.Entry<String, CharLimits.PartLimit> e : now.parts().entrySet()) {
			int ob = e.getValue().overBy();
			int was = base == null ? 0 : base.parts().get(e.getKey()).overBy();
			if (ob > 0 && was == 0) {
				m.put(e.getKey(), ob);
			}
		}
		return m;
	}

	// ======================================================================
	// (a) signature
	// ======================================================================

	private static Signature signature(Ctx c, String onTuning, List<CharLimits.Evaluation> evalBefore,
			List<String> warnings) {
		Project p = c.p;
		MMLScore score = c.score();
		// existing passage
		List<Map<String, Object>> own = ownSignatures(c);
		if (!own.isEmpty()) {
			java.util.Set<Integer> with = new java.util.TreeSet<>();
			for (Map<String, Object> m : own) {
				with.add((Integer) m.get("track"));
			}
			List<SignatureTrack> tracks = new ArrayList<>();
			List<String> missing = new ArrayList<>();
			for (int t = 1; t <= p.trackCount(); t++) {
				MMLTrack tr = c.track(t);
				InstrumentTable.Instrument inst = c.inst(t);
				String kind = tr.getProgram() == 27 ? "drums" : c.voice(t) ? "voice" : "pitched";
				String slot = null;
				for (Map<String, Object> m : own) {
					if (((Integer) m.get("track")) == t) {
						slot = (String) m.get("slot");
					}
				}
				if (with.contains(t)) {
					tracks.add(new SignatureTrack(t, tr.getTrackName(), tr.getProgram(), inst == null ? null : inst.name(), kind,
							"already_present", slot, null, null, null, null, null, null, null, null, null, null, null));
				} else {
					missing.add(t + "(" + tr.getTrackName() + ")");
					tracks.add(skip(t, tr.getTrackName(), tr.getProgram(), inst == null ? null : inst.name(), kind,
							"이 트랙에는 시그니처가 없음 — 앞선 호출에서 건너뛰었거나(자리 없음·낼 수 없는 악기) 그 뒤에 더한 트랙. "
									+ "다시 넣지 않음",
							null));
				}
			}
			String why = "이 도구의 시그니처가 이미 1마디에 있음 — 다시 넣지 않음"
					+ (missing.isEmpty() ? "" : ". 시그니처가 없는 트랙: " + String.join(", ", missing));
			return new Signature("already_present", why, null, null, null, null, null, null, null, List.of(), own, tracks);
		}
		List<Map<String, Object>> tuning = tuningNotes(c);
		if (!tuning.isEmpty() && TUNING_SKIP.equals(onTuning)) {
			return new Signature("existing_tuning",
					"틱 0에 MabiIcco 조율음(같은 음 붙임줄 4개 이상)이 있음 — 그 앞에 넣으려면 on_existing_tuning prepend", null, null, null,
					null, null, null, null, List.of(), tuning, List.of());
		}
		for (int n = 1; n <= p.trackCount(); n++) {
			if (c.track(n).getCommonStartOffset() != 0) {
				return new Signature("skipped", "트랙 " + n + "에 시작 위치(startOffset " + c.track(n).getCommonStartOffset()
						+ ")가 있어 머리 마디를 넣지 않음", null, null, null, null, null, null, null, List.of(), tuning, List.of());
			}
		}
		int bpm = MMLTempoEvent.searchOnTick(score.getTempoEventList(), 0);
		Boolean bpmDefault = !MMLTempoEvent.searchEqualsTick(score.getTempoEventList(), 0);
		int u = unitTicks(bpm);
		MeasureMap map = p.measureMap();
		MeasureMap.Signature s0 = map.signatures().get(0);
		long measure = map.measureEnd(1) - map.measureStart(1);
		int n = leadInMeasures(measure, u);
		int shift = (int) (n * measure);
		String sigText = s0.numerator() + "/" + s0.denominator();

		// shift
		byte[] pre = p.state();
		p.mutate(() -> insertLeadIn(score, n));
		// 1. the lead-in rest alone must fit (every slot that fitted before)
		List<CharLimits.Evaluation> evalRest = c.allEvals();
		List<Map<String, Object>> leadOver = overList(evalRest, evalBefore);
		if (!leadOver.isEmpty()) {
			p.restoreState(pre);
			StringBuilder why = new StringBuilder("머리 마디의 쉼표만으로(템포 복사 없이도) 한도를 넘는 칸이 있어 시그니처를 넣지 않음:");
			for (Map<String, Object> m : leadOver) {
				why.append(" 트랙 ").append(m.get("track")).append(' ').append(m.get("slot")).append(" +").append(m.get("over_by"))
						.append("자");
			}
			return new Signature("skipped", why.toString(), bpm, u, unitName(u), 0, 0, sigText, 8 * u, leadOver, tuning,
					List.of(), bpmDefault, null);
		}
		// 2. the tick-0 tempo is also placed at the first original tick, so that deleting the lead-in measures with
		// MabiIcco's delete-measure (which drops events inside the deleted range, the tick-0 tempo included) leaves the
		// original tempo at tick 0. It counts toward the budget; when it is what breaks the budget it is omitted.
		Map<String, Object> leadInTempo = null;
		if (!bpmDefault) {
			byte[] rest = p.state();
			List<int[]> withoutCopy = c.allChars();
			p.mutate(() -> addTempoCopy(score, shift, bpm));
			List<int[]> withCopy = c.allChars();
			List<Map<String, Object>> cost = new ArrayList<>();
			int total = 0;
			for (int t = 1; t <= p.trackCount(); t++) {
				Map<String, Integer> bySlot = new LinkedHashMap<>();
				int sum = 0;
				for (Part pt : Part.values()) {
					int d = withCopy.get(t - 1)[pt.index()] - withoutCopy.get(t - 1)[pt.index()];
					if (d != 0) {
						bySlot.put(pt.key(), d);
					}
					sum += d;
				}
				total += sum;
				cost.add(details("track", t, "chars", sum, "by_slot", bySlot));
			}
			List<Map<String, Object>> copyOver = overList(c.allEvals(), evalBefore);
			if (copyOver.isEmpty()) {
				leadInTempo = details("status", "kept", "tick", shift, "bpm", bpm, "chars_total", total, "tracks", cost,
						"no_room", List.of(), "why", "틱 0 템포를 원래 첫 틱에도 둠 — MabiIcco에서 머리 마디를 지워도 원래 템포가 남게");
			} else {
				p.restoreState(rest);
				StringBuilder where = new StringBuilder();
				for (Map<String, Object> m : copyOver) {
					where.append(where.length() == 0 ? "" : ", ").append("트랙 ").append(m.get("track")).append(' ')
							.append(m.get("slot")).append(" +").append(m.get("over_by")).append("자");
				}
				leadInTempo = details("status", "omitted", "tick", shift, "bpm", bpm, "chars_total", 0, "tracks", cost,
						"no_room", copyOver, "why", "템포 복사(t" + bpm + ")를 넣으면 한도를 넘는 칸이 있어 넣지 않음: " + where);
				warnings.add("tempo_copy_omitted: 원래 첫 틱의 템포 복사(t" + bpm + ")를 넣을 자리가 없음(" + where
						+ ") — 나중에 MabiIcco에서 머리 마디 " + n + "개를 지우면 시작 템포가 기본 120이 되므로 시작 템포를 t" + bpm
						+ "로 다시 넣을 것");
			}
		}
		List<CharLimits.Evaluation> evalLead = c.allEvals();

		List<SignatureTrack> tracks = new ArrayList<>();
		int added = 0;
		for (int t = 1; t <= p.trackCount(); t++) {
			SignatureTrack r = placeSignature(c, t, u, evalLead.get(t - 1));
			tracks.add(r);
			if ("added".equals(r.status())) {
				added++;
			}
		}
		if (added == 0) {
			p.restoreState(pre);
			return new Signature("skipped", "어느 트랙에도 넣을 자리가 없어 머리 마디도 넣지 않음", bpm, u, unitName(u), 0, 0, sigText,
					8 * u, List.of(), tuning, tracks, bpmDefault, leadInTempo);
		}
		if (!tuning.isEmpty()) {
			warnings.add("existing_tuning: 원래 있던 조율음 앞에 시그니처를 넣음(prepend)");
		}
		String status = added == tracks.size() ? "added" : "partial";
		return new Signature(status, null, bpm, u, unitName(u), n, shift, sigText, 8 * u, List.of(), tuning, tracks, bpmDefault, leadInTempo);
	}

	/** Slots over the limit in {@code now} that fitted in {@code before}: {track, slot, chars, limit, over_by}. */
	private static List<Map<String, Object>> overList(List<CharLimits.Evaluation> now, List<CharLimits.Evaluation> before) {
		List<Map<String, Object>> l = new ArrayList<>();
		for (int t = 1; t <= now.size(); t++) {
			for (Map.Entry<String, Integer> e : newlyOver(now.get(t - 1), before.get(t - 1)).entrySet()) {
				CharLimits.PartLimit pl = now.get(t - 1).parts().get(e.getKey());
				l.add(details("track", t, "slot", e.getKey(), "chars", pl.chars(), "limit", pl.limit(), "over_by", e.getValue()));
			}
		}
		return l;
	}

	/** Adds a tempo event of {@code bpm} at {@code tick} (the first original tick), keeping the list in tick order. */
	static void addTempoCopy(MMLScore score, int tick, int bpm) {
		List<MMLTempoEvent> l = score.getTempoEventList();
		int i = 0;
		while (i < l.size() && l.get(i).getTickOffset() < tick) {
			i++;
		}
		if (i < l.size() && l.get(i).getTickOffset() == tick) {
			return; // a tempo change is already there
		}
		l.add(i, new MMLTempoEvent(bpm, tick));
	}

	/** Inserts n measures at tick 0 (MabiIcco's own insertion) and keeps the tick-0 tempo events at tick 0. */
	static void insertLeadIn(MMLScore score, int n) {
		List<MMLTempoEvent> zero = new ArrayList<>();
		for (MMLTempoEvent te : score.getTempoEventList()) {
			if (te.getTickOffset() == 0) {
				zero.add(te);
			}
		}
		for (int i = 0; i < n; i++) {
			score.addTicks(0, true);
		}
		for (MMLTempoEvent te : zero) {
			te.setTickOffset(0);
		}
	}

	/** The tool's own passage at tick 0 (any track, any slot). */
	private static List<Map<String, Object>> ownSignatures(Ctx c) {
		List<Map<String, Object>> l = new ArrayList<>();
		int[] keys = drumKeys();
		for (int t = 1; t <= c.p.trackCount(); t++) {
			MMLTrack tr = c.track(t);
			for (Part pt : Part.values()) {
				List<MMLNoteEvent> ns = tr.getMMLEventAtIndex(pt.index()).getMMLNoteEventList();
				if (ns.size() < 4) {
					continue;
				}
				int u = ns.get(0).getTick();
				if (u != 12 && u != 24 && u != 48) {
					continue;
				}
				boolean shape = true;
				for (int i = 0; i < 4; i++) {
					MMLNoteEvent e = ns.get(i);
					if (e.getTickOffset() != i * u || e.getTick() != (i == 3 ? 5 * u : u)) {
						shape = false;
						break;
					}
				}
				if (!shape || (ns.size() > 4 && ns.get(4).getTickOffset() < 8 * u)) {
					continue;
				}
				int prog = c.p.soundingProgram(t, pt);
				boolean match;
				if (prog == 27) {
					match = ns.get(0).getNote() + 12 == keys[0] && ns.get(1).getNote() + 12 == keys[0]
							&& ns.get(2).getNote() + 12 == keys[0] && ns.get(3).getNote() + 12 == keys[1];
				} else {
					match = true;
					for (int i = 0; i < 4; i++) {
						int s = SoundingPitch.soundingOf(prog, ns.get(i).getNote() + 12);
						if (s < 0 || Math.floorMod(s, 12) != PITCH_CLASSES[i]) {
							match = false;
							break;
						}
					}
				}
				if (match) {
					l.add(details("track", t, "slot", pt.key(), "unit_ticks", u));
				}
			}
		}
		return l;
	}

	/** MabiIcco tuning notes (tie chains of 4 or more equal notes) starting at tick 0. */
	private static List<Map<String, Object>> tuningNotes(Ctx c) {
		List<Map<String, Object>> l = new ArrayList<>();
		for (int t = 1; t <= c.p.trackCount(); t++) {
			MMLTrack tr = c.track(t);
			for (Part pt : Part.values()) {
				for (MMLNoteEvent e : tr.getMMLEventAtIndex(pt.index()).getMMLNoteEventList()) {
					if (e.getTickOffset() == 0 && e.isTuningNote()) {
						l.add(details("track", t, "slot", pt.key(), "pitch", e.getNote() + 12, "len", e.getTick(), "base",
								e.getTuningBase().getBase()));
					}
					if (e.getTickOffset() > 0) {
						break;
					}
				}
			}
		}
		return l;
	}

	/** Written pitch for sounding s with one timbre, or null if not producible (range, silence, MIDI limits). */
	static Integer producible(InstrumentTable.Instrument inst, int prog, int s, String timbre) {
		Integer w = SoundingPitch.writtenFor(prog, s, timbre);
		if (w == null || w < NoteSpec.MIN_MIDI || w > NoteSpec.MAX_MIDI) {
			return null;
		}
		InstrumentTable.Range r = inst == null ? null : inst.range();
		if (r != null && ((r.low() != null && w < r.low()) || (r.high() != null && w > r.high()))) {
			return null;
		}
		return SoundingPitch.soundingOf(prog, w) == s ? w : null;
	}

	/** The passage chosen for one pitched program. */
	record Passage(int[] sounding, int[] written, String timbre, boolean upperE, double mean) {}

	/**
	 * Chooses the octave: all four producible with one timbre, E a major sixth above G if producible else a minor third
	 * below, mean closest to target (ties: higher). Null if no octave works.
	 */
	static Passage choosePassage(InstrumentTable.Instrument inst, int prog, double target) {
		Passage best = null;
		double bestD = Double.MAX_VALUE;
		for (int b = 11; b <= 127; b += 12) {
			Passage cand = null;
			for (String tb : SoundingPitch.timbres(prog)) {
				int[] up = { b, b + 3, b + 8, b + 17 };
				int[] low = { b, b + 3, b + 8, b + 5 };
				int[] w = writtenAll(inst, prog, up, tb);
				if (w != null) {
					cand = new Passage(up, w, tb, true, mean(up));
					break;
				}
				w = writtenAll(inst, prog, low, tb);
				if (w != null && cand == null) {
					cand = new Passage(low, w, tb, false, mean(low));
					// keep looking for a timbre that allows the upper E
				}
			}
			if (cand == null) {
				continue;
			}
			double d = Math.abs(cand.mean() - target);
			if (d < bestD || d == bestD) { // ascending octaves: equal distance → the higher one wins
				best = cand;
				bestD = d;
			}
		}
		return best;
	}

	private static int[] writtenAll(InstrumentTable.Instrument inst, int prog, int[] s, String tb) {
		int[] w = new int[s.length];
		for (int i = 0; i < s.length; i++) {
			Integer x = producible(inst, prog, s[i], tb);
			if (x == null) {
				return null;
			}
			w[i] = x;
		}
		return w;
	}

	private static double mean(int[] a) {
		double s = 0;
		for (int v : a) {
			s += v;
		}
		return s / a.length;
	}

	/** Middle of the producible sounding range of a program (null if nothing producible). */
	static Double producibleMiddle(InstrumentTable.Instrument inst, int prog) {
		Integer lo = null;
		Integer hi = null;
		for (int s = 0; s <= 127; s++) {
			for (String tb : SoundingPitch.timbres(prog)) {
				if (producible(inst, prog, s, tb) != null) {
					lo = lo == null ? s : lo;
					hi = s;
					break;
				}
			}
		}
		return lo == null ? null : (lo + hi) / 2.0;
	}

	private static SignatureTrack placeSignature(Ctx c, int t, int u, CharLimits.Evaluation base) {
		Project p = c.p;
		MMLTrack tr = c.track(t);
		int program = tr.getProgram();
		InstrumentTable.Instrument inst = c.inst(t);
		String name = tr.getTrackName();
		String instName = inst == null ? null : inst.name();
		boolean voice = c.voice(t);
		boolean drums = program == 27;
		String kind = drums ? "drums" : voice ? "voice" : "pitched";
		List<Part> slots = voice ? List.of(Part.SONG) : c.slots(t);
		if (slots.isEmpty()) {
			return skip(t, name, program, instName, kind, "이 악기는 쓸 수 있는 칸이 없음", null);
		}
		int[] written;
		List<String> soundingNames = new ArrayList<>();
		String timbre = null;
		Double target = null;
		String targetSource = null;
		int[] vels = { c.velocity, c.velocity, c.velocity, c.velocity };
		String velRule = "x8";
		// per-program offset from signature_home.properties (velocity_offset.<program>), after the scale conversion
		Integer velOffset = SignatureHome.velocityOffset(program);
		if (velOffset != null) {
			for (int i = 0; i < 3; i++) {
				vels[i] = Math.max(1, Math.min(15, vels[i] + velOffset));
			}
			if (program != 27) {
				vels[3] = vels[0];
			}
		}
		if (drums) {
			int[] k = drumKeys();
			written = new int[] { k[0], k[0], k[0], k[1] };
			vels[3] = CRASH_VELOCITY;
			soundingNames = List.of("snare", "snare", "snare", "crash");
		} else {
			int prog = p.soundingProgram(t, slots.get(0));
			InstrumentTable.Instrument pi = c.table.get(prog);
			// same MIDI velocity as `velocity` on an ordinary (x8) instrument: e.g. xylophone 77 (x11) v12 → v9
			velRule = pi == null ? null : InstrumentTable.velocityRule(pi.type());
			int v = scaledVelocity(velRule, c.velocity);
			if (velOffset != null) {
				v = Math.max(1, Math.min(15, v + velOffset));
			}
			vels = new int[] { v, v, v, v };
			if (!SoundingPitch.rule(prog).kind().pitched()) {
				return skip(t, name, program, instName, kind,
						"음높이를 고를 수 없는 악기(" + SoundingPitch.summary(prog) + ")라 시그니처를 넣지 않음", null);
			}
			Double home = SignatureHome.of(prog);
			if (home != null) {
				target = home;
				targetSource = "home_table";
			} else {
				target = producibleMiddle(pi, prog);
				targetSource = "range_middle";
			}
			Passage ps = target == null ? null : choosePassage(pi, prog, target);
			if (ps == null) {
				return skip(t, name, program, instName, kind, "B·D·G·E를 모두 낼 수 있는 옥타브가 없음", null);
			}
			written = ps.written();
			timbre = SoundingPitch.rule(prog).kind() == SoundingPitch.Kind.REGIONS ? ps.timbre() : null;
			for (int s : ps.sounding()) {
				soundingNames.add(NoteSpec.pitchName(s));
			}
		}
		int[] fw = written;
		int[] fv = vels;
		Map<String, Object> tried = new LinkedHashMap<>();
		for (Part slot : slots) {
			byte[] before = p.state();
			int[] cb = TrackCopy.mmlChars(tr);
			try {
				p.mutate(() -> {
					MMLEventList el = c.track(t).getMMLEventAtIndex(slot.index());
					for (int i = 0; i < 4; i++) {
						el.addMMLNoteEvent(new MMLNoteEvent(fw[i] - 12, i == 3 ? 5 * u : u, i * u, fv[i]));
					}
				});
			} catch (EngineException e) {
				tried.put(slot.key(), "MML 생성 실패: " + e.getMessage());
				continue;
			}
			CharLimits.Evaluation ev = c.eval(t);
			Map<String, Integer> over = newlyOver(ev, base);
			if (over.isEmpty()) {
				int[] ca = TrackCopy.mmlChars(c.track(t));
				List<Integer> wl = new ArrayList<>();
				for (int w : fw) {
					wl.add(w);
				}
				return new SignatureTrack(t, name, program, instName, kind, "added", slot.key(), List.copyOf(soundingNames),
						wl, timbre, target, targetSource, ca[slot.index()] - cb[slot.index()], null,
						tried.isEmpty() ? null : details("tried", tried), List.of(vels[0], vels[1], vels[2], vels[3]), velRule, velOffset);
			}
			tried.put(slot.key(), overReason(slot, over, ev));
			p.restoreState(before);
		}
		StringBuilder why = new StringBuilder("어느 칸에도 자리가 없음 — ");
		why.append(String.join("; ", tried.values().stream().map(String::valueOf).toList()));
		return skip(t, name, program, instName, kind, why.toString(), details("tried", tried, "sounding", soundingNames));
	}

	/**
	 * The MML velocity on an instrument with velocity rule {@code rule} ({@link InstrumentTable#velocityRule}) whose MIDI
	 * velocity is closest to {@code v} on an ordinary instrument (x8: v × 8). x11 (percussion P, xylophone K): v in
	 * 1..11 with v × 11 closest (ties: the lower). Other rules: v unchanged.
	 */
	public static int scaledVelocity(String rule, int v) {
		if (!"x11".equals(rule)) {
			return v;
		}
		int target = Math.min(v, 15) * 8;
		int best = 1;
		for (int k = 1; k <= 11; k++) {
			if (Math.abs(k * 11 - target) < Math.abs(best * 11 - target)) {
				best = k;
			}
		}
		return best;
	}

	private static SignatureTrack skip(int t, String name, int program, String inst, String kind, String reason,
			Map<String, Object> detail) {
		return new SignatureTrack(t, name, program, inst, kind, "skipped", null, null, null, null, null, null, null, reason,
				detail, null, null, null);
	}

	// ======================================================================
	// (b) end padding
	// ======================================================================

	private static Padding padding(Ctx c) {
		Project p = c.p;
		long tEnd = 0;
		for (int t = 1; t <= p.trackCount(); t++) {
			tEnd = Math.max(tEnd, trackEnd(c.track(t)));
		}
		MeasureMap map = p.measureMap();
		if (tEnd == 0) {
			return new Padding("no_notes", null, null, List.of());
		}
		long end = tEnd;
		String endPos = map.position(end).toString();
		List<PaddingTrack> l = new ArrayList<>();
		int added = 0;
		for (int t = 1; t <= p.trackCount(); t++) {
			int tn = t;
			MMLTrack tr = c.track(t);
			String name = tr.getTrackName();
			long own = trackEnd(tr);
			if (own == 0) {
				l.add(new PaddingTrack(t, name, "no_notes", null, null, null, null, null, null, null, null, null, null));
				continue;
			}
			if (end - own <= PAD_LEN) {
				MMLNoteEvent last = lastNote(tr);
				boolean pad = own == end && last.getVelocity() == 0 && trackEndAudible(tr) < end;
				l.add(new PaddingTrack(t, name, pad ? "already_present" : "not_needed", (int) own, null,
						pad ? last.getTickOffset() : null, pad ? map.position(last.getTickOffset()).toString() : null,
						pad ? last.getTick() : null, null, null, null,
						pad ? "끝 맞춤용 세기 0 음이 이미 있음" : null, null));
				continue;
			}
			MMLNoteEvent last = lastNote(tr);
			int pitch = last.getNote() + 12;
			long start = end - PAD_LEN;
			if (start - own < PAD_LEN) {
				start = own; // a rest shorter than 6 ticks cannot be written; the silent note starts right after the last one
			}
			int len = (int) (end - start);
			int st = (int) start;
			List<Part> slots = c.voice(t) ? List.of(Part.SONG) : c.slots(t);
			CharLimits.Evaluation base = c.eval(t);
			Map<String, Integer> cost = new LinkedHashMap<>();
			Part best = null;
			int bestCost = Integer.MAX_VALUE;
			Map<String, Object> why = new LinkedHashMap<>();
			for (Part slot : slots) {
				byte[] before = p.state();
				int[] cb = TrackCopy.mmlChars(tr);
				try {
					p.mutate(() -> c.track(tn).getMMLEventAtIndex(slot.index())
							.addMMLNoteEvent(new MMLNoteEvent(pitch - 12, len, st, 0)));
				} catch (EngineException e) {
					why.put(slot.key(), "MML 생성 실패: " + e.getMessage());
					continue;
				}
				int[] ca = TrackCopy.mmlChars(c.track(t));
				CharLimits.Evaluation ev = c.eval(t);
				Map<String, Integer> over = newlyOver(ev, base);
				int d = ca[slot.index()] - cb[slot.index()];
				p.restoreState(before);
				if (!over.isEmpty()) {
					why.put(slot.key(), overReason(slot, over, ev));
					continue;
				}
				cost.put(slot.key(), d);
				if (d < bestCost) {
					bestCost = d;
					best = slot;
				}
			}
			if (best == null) {
				l.add(new PaddingTrack(t, name, "skipped", (int) own, null, st, map.position(st).toString(), len, pitch,
						NoteSpec.pitchName(pitch), null, "어느 칸에도 자리가 없음 — " + String.join("; ", why.values().stream().map(String::valueOf).toList()), cost));
				continue;
			}
			Part fb = best;
			p.mutate(() -> c.track(tn).getMMLEventAtIndex(fb.index()).addMMLNoteEvent(new MMLNoteEvent(pitch - 12, len, st, 0)));
			added++;
			l.add(new PaddingTrack(t, name, "added", (int) own, best.key(), st, map.position(st).toString(), len, pitch,
					NoteSpec.pitchName(pitch), bestCost, null, cost));
		}
		long skipped = l.stream().filter(x -> "skipped".equals(x.status())).count();
		long present = l.stream().filter(x -> "already_present".equals(x.status())).count();
		String status = added > 0 ? "done" : skipped > 0 ? "skipped" : present > 0 ? "already_present" : "nothing_to_do";
		return new Padding(status, (int) end, endPos, l);
	}

	/** End of the latest note with a velocity above 0 (0 if none). */
	static long trackEndAudible(MMLTrack tr) {
		long e = 0;
		for (Part pt : Part.values()) {
			for (MMLNoteEvent n : tr.getMMLEventAtIndex(pt.index()).getMMLNoteEventList()) {
				if (n.getVelocity() > 0) {
					e = Math.max(e, n.getEndTick());
				}
			}
		}
		return e;
	}

	/** "chord1에 넣으면 chord1 1612/1600자(12자 초과)" for the slots that would go over. */
	private static String overReason(Part slot, Map<String, Integer> over, CharLimits.Evaluation ev) {
		List<String> l = new ArrayList<>();
		for (Map.Entry<String, Integer> e : over.entrySet()) {
			CharLimits.PartLimit pl = ev.parts().get(e.getKey());
			l.add(e.getKey() + " " + pl.chars() + "/" + pl.limit() + "자(" + e.getValue() + "자 초과)");
		}
		return slot.key() + "에 넣으면 " + String.join(", ", l);
	}

	static long trackEnd(MMLTrack tr) {
		long e = 0;
		for (Part pt : Part.values()) {
			for (MMLNoteEvent n : tr.getMMLEventAtIndex(pt.index()).getMMLNoteEventList()) {
				e = Math.max(e, n.getEndTick());
			}
		}
		return e;
	}

	/** The note that ends last (ties: the later start, then the lower slot). */
	private static MMLNoteEvent lastNote(MMLTrack tr) {
		MMLNoteEvent best = null;
		for (Part pt : Part.values()) {
			for (MMLNoteEvent n : tr.getMMLEventAtIndex(pt.index()).getMMLNoteEventList()) {
				if (best == null || n.getEndTick() > best.getEndTick()
						|| (n.getEndTick() == best.getEndTick() && n.getTickOffset() > best.getTickOffset())) {
					best = n;
				}
			}
		}
		return best;
	}
}
