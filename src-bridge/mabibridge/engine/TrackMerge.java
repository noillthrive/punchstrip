package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_merge_tracks}: merge two or more tracks of the same program into one track (melody / chord1 /
 * chord2). Only the three instrument parts move; the target's song part stays as it is.
 *
 * <ul>
 * <li>{@code by_voice}: all notes of all sources are pooled (exact duplicates = same onset and pitch are merged, the
 * longer one kept), grouped by onset and dealt to three monophonic voices: at each onset the free voices (previous note
 * ended) take the group's notes from high to low in voice order melody, chord1, chord2. When a group has more notes
 * than free voices the outer notes are kept first (top, bottom, second top, ...) and the inner ones are dropped.</li>
 * <li>{@code keep_parts}: every non-empty part (sources in the given order, parts melody, chord1, chord2) goes to the
 * next free part of the target. More than three non-empty parts is an error.</li>
 * <li>{@code priority}: {@code order} = [track, part] pairs; pair 1 → melody, 2 → chord1, 3 → chord2; notes of pairs
 * 4+ fill gaps in the first voice that is free for the whole note, else they are dropped. A note with the same onset
 * and pitch as an already placed note of a higher-priority pair is merged into it ({@code duplicates_merged}). Non-empty parts not named in
 * {@code order} are dropped (reason {@code not_in_order}).</li>
 * </ul>
 *
 * <p>Within a voice a gap of 1-5 ticks between two notes cannot be written in MML (shortest length 6 ticks); such a gap
 * is closed by extending the earlier note ({@code gaps_closed}). A snapshot is taken before the change unless
 * {@code dry_run}; a dry run changes the score, measures it and restores the previous state.</p>
 */
public final class TrackMerge {

	public static final String BY_VOICE = "by_voice";
	public static final String KEEP_PARTS = "keep_parts";
	public static final String PRIORITY = "priority";
	static final int VOICES = 3;
	static final int DROPPED_LIST = 20;

	/** One [track, part] pair of the {@code priority} order. */
	public record OrderEntry(int track, String part) {}

	/** Notes moved from one source part to one target part. */
	public record MapEntry(int fromTrack, String fromPart, String toPart, int notes) {}

	/** One resulting part: note count, MML chars, limit and over-by under the chosen rank / rule. */
	public record PartOut(int notes, int chars, Integer limit, int overBy) {}

	/** A dropped note (positions in the score before removal of sources; ticks do not change). */
	public record Dropped(int tick, String pos, int len, int pitch, String name, int vel, int fromTrack, String fromPart,
			String reason) {}

	/** Source track before the merge. */
	public record SourceInfo(int track, String name, int volume, int panpot, Map<String, Integer> notes,
			Map<String, Integer> chars) {}

	/** Tool response. */
	public record Result(boolean ok, boolean dryRun, String strategy, List<Integer> tracks, int target, int resultTrack,
			String name, int program, String instrument, List<SourceInfo> sources, Map<String, PartOut> parts,
			boolean fits, List<String> over, CharLimits.Evaluation rankLimits, List<MapEntry> mapping, int notesIn,
			int notesOut, int droppedCount, Map<String, Integer> droppedBySource, List<Dropped> dropped,
			int duplicatesMerged, int lengthCapped, int gapsClosed, Integer maxLenTicks, boolean keepSources,
			List<Integer> removedTracks, Map<String, Integer> renumbered, Integer snapshotId,
			Snapshots.SnapshotInfo snapshot, List<String> warnings) {}

	/** A note taken from a source part (a clone; the source score is not touched while planning). */
	private record Src(MMLNoteEvent e, int track, Part part) {
		int tick() {
			return e.getTickOffset();
		}

		int end() {
			return e.getEndTick();
		}
	}

	private TrackMerge() {}

	static String strategy(String s) {
		String v = (s == null || s.isBlank()) ? BY_VOICE : s.trim().toLowerCase(Locale.ROOT);
		if (!BY_VOICE.equals(v) && !KEEP_PARTS.equals(v) && !PRIORITY.equals(v)) {
			throw new EngineException(BAD_ARGUMENT, "strategy는 by_voice, keep_parts, priority 중 하나: " + s,
					details("strategy", s));
		}
		return v;
	}

	static Result run(Project p, List<Integer> tracksArg, Integer targetArg, String strategyArg, List<OrderEntry> order,
			String rank, String songRule, boolean dryRun, Integer maxLen, boolean keepSources) {
		String strategy = strategy(strategyArg);
		if (tracksArg == null || tracksArg.size() < 2) {
			throw new EngineException(BAD_ARGUMENT, "tracks는 트랙 번호 2개 이상", details("tracks", tracksArg));
		}
		List<Integer> tracks = new ArrayList<>();
		for (Integer t : tracksArg) {
			p.trackRef(t == null ? 0 : t);
			if (tracks.contains(t)) {
				throw new EngineException(BAD_ARGUMENT, "tracks에 같은 트랙이 두 번: " + t, details("tracks", tracksArg));
			}
			tracks.add(t);
		}
		int target = targetArg == null ? tracks.get(0) : targetArg;
		if (!tracks.contains(target)) {
			throw new EngineException(BAD_ARGUMENT, "target은 tracks 중 하나여야 함: " + target,
					details("target", target, "tracks", tracks));
		}
		if (maxLen != null && (maxLen < 6 || maxLen % 6 != 0)) {
			throw new EngineException(BAD_ARGUMENT, "max_len_ticks는 6 이상, 6의 배수(4분음표 = 96)", details("max_len_ticks", maxLen));
		}
		if (PRIORITY.equals(strategy) != (order != null)) {
			throw new EngineException(BAD_ARGUMENT,
					PRIORITY.equals(strategy) ? "strategy priority에는 order([[트랙, 파트], …])가 필요함"
							: "order는 strategy priority에서만 쓴다",
					details("strategy", strategy));
		}
		CharLimits.rank(rank);
		if (songRule != null) {
			CharLimits.checkRule(songRule);
		}
		InstrumentTable table = p.engine().instrumentTable();
		MMLTrack tt = p.trackRef(target);
		int program = tt.getProgram();
		Map<Integer, Integer> programs = new LinkedHashMap<>();
		for (int t : tracks) {
			programs.put(t, p.trackRef(t).getProgram());
		}
		if (programs.values().stream().distinct().count() > 1) {
			throw new EngineException(BAD_ARGUMENT, "같은 악기(program) 트랙끼리만 합친다: " + programs,
					details("programs", programs));
		}
		InstrumentTable.Instrument inst = table.get(program);
		List<String> warnings = new ArrayList<>();
		// song parts: only the target's stays; another source's song notes would be lost with its track
		for (int t : tracks) {
			int songNotes = p.trackRef(t).getMMLEventAtIndex(Part.SONG.index()).getMMLNoteEventList().size();
			if (t != target && songNotes > 0) {
				if (!keepSources) {
					throw new EngineException(BAD_ARGUMENT, "트랙 " + t + "에 노래 파트 음 " + songNotes
							+ "개가 있음 — 합치기는 melody·chord1·chord2만 옮기고 원본 트랙을 지우므로 노래 파트가 사라진다. 먼저 옮기거나 지우거나 keep_sources: true",
							details("track", t, "song_notes", songNotes));
				}
				warnings.add("song_not_merged: 트랙 " + t + "의 노래 파트 " + songNotes + "음은 합치지 않음(원본 트랙에 남음)");
			}
		}
		MeasureMap map = p.measureMap();
		// sources (clones), length cap
		List<SourceInfo> sources = new ArrayList<>();
		Map<String, List<Src>> byPart = new LinkedHashMap<>();
		int capped = 0;
		int notesIn = 0;
		for (int t : tracks) {
			MMLTrack mt = p.trackRef(t);
			int[] chars = TrackCopy.mmlChars(mt);
			Map<String, Integer> nc = new LinkedHashMap<>();
			Map<String, Integer> cc = new LinkedHashMap<>();
			for (Part part : Part.values()) {
				nc.put(part.key(), mt.getMMLEventAtIndex(part.index()).getMMLNoteEventList().size());
				cc.put(part.key(), chars[part.index()]);
			}
			sources.add(new SourceInfo(t, mt.getTrackName(), mt.getVolume(), mt.getPanpot(), nc, cc));
			for (Part part : new Part[] { Part.MELODY, Part.CHORD1, Part.CHORD2 }) {
				List<Src> l = new ArrayList<>();
				for (MMLNoteEvent e : mt.getMMLEventAtIndex(part.index()).getMMLNoteEventList()) {
					MMLNoteEvent c = e.clone();
					if (maxLen != null && c.getTick() > maxLen) {
						c.setTick(maxLen);
						capped++;
					}
					l.add(new Src(c, t, part));
				}
				notesIn += l.size();
				byPart.put(key(t, part), l);
			}
		}
		if (sources.stream().map(SourceInfo::volume).distinct().count() > 1) {
			StringBuilder sb = new StringBuilder();
			for (SourceInfo s : sources) {
				sb.append(sb.length() == 0 ? "" : ", ").append("트랙 ").append(s.track()).append(' ').append(s.volume());
			}
			warnings.add("volume_differs: 트랙 음량이 다름(" + sb + ") — 결과는 대상 트랙 " + target
					+ "의 음량을 쓴다(음마다 세기 v는 그대로)");
		}

		// plan
		List<List<Src>> voices = new ArrayList<>();
		for (int i = 0; i < VOICES; i++) {
			voices.add(new ArrayList<>());
		}
		List<Src> dropped = new ArrayList<>();
		List<String> dropReasons = new ArrayList<>();
		int[] dup = new int[1];
		switch (strategy) {
		case KEEP_PARTS -> keepParts(byPart, voices);
		case PRIORITY -> priority(byPart, order, tracks, voices, dropped, dropReasons, dup);
		default -> byVoice(byPart, voices, dropped, dropReasons, dup);
		}
		int gaps = 0;
		for (List<Src> v : voices) {
			v.sort(Comparator.comparingInt(Src::tick));
			gaps += closeGaps(v);
		}
		// mapping counts
		Map<String, int[]> mapCount = new LinkedHashMap<>();
		for (int i = 0; i < VOICES; i++) {
			for (Src s : voices.get(i)) {
				mapCount.computeIfAbsent(s.track() + "|" + s.part().key() + "|" + Part.ofIndex(i).key(), k -> new int[1])[0]++;
			}
		}
		List<MapEntry> mapping = new ArrayList<>();
		for (Map.Entry<String, int[]> e : mapCount.entrySet()) {
			String[] k = e.getKey().split("\\|");
			mapping.add(new MapEntry(Integer.parseInt(k[0]), k[1], k[2], e.getValue()[0]));
		}
		mapping.sort(Comparator.comparingInt((MapEntry m) -> Part.of(m.toPart()).index())
				.thenComparingInt(m -> tracks.indexOf(m.fromTrack())).thenComparingInt(m -> Part.of(m.fromPart()).index()));
		Map<String, Integer> droppedBy = new LinkedHashMap<>();
		List<Dropped> droppedList = new ArrayList<>();
		List<Integer> dIdx = new ArrayList<>();
		for (int i = 0; i < dropped.size(); i++) {
			dIdx.add(i);
		}
		dIdx.sort(Comparator.comparingInt((Integer i) -> dropped.get(i).tick()).thenComparingInt(i -> -dropped.get(i).e().getNote()));
		for (int i : dIdx) {
			Src s = dropped.get(i);
			droppedBy.merge("T" + s.track() + " " + s.part().key(), 1, Integer::sum);
			if (droppedList.size() < DROPPED_LIST) {
				int midi = s.e().getNote() + 12;
				droppedList.add(new Dropped(s.tick(), map.position(s.tick()).toString(), s.e().getTick(), midi,
						NoteSpec.pitchName(midi), s.e().getVelocity(), s.track(), s.part().key(), dropReasons.get(i)));
			}
		}

		// apply
		List<Integer> removed = new ArrayList<>();
		if (!keepSources) {
			for (int t : tracks) {
				if (t != target) {
					removed.add(t);
				}
			}
		}
		removed.sort(null);
		Snapshots.SnapshotInfo snap = null;
		byte[] state = null;
		boolean dirtyBefore = p.dirty();
		Map<Integer, String> rulesBefore = p.trackRulesCopy();
		if (dryRun) {
			state = p.state();
		} else {
			snap = p.engine().snapshots().snapshot(p.score(), "auto: score_merge_tracks " + tracks + " → " + target + " "
					+ strategy);
		}
		p.mutate(() -> {
			MMLTrack t = p.score().getTrack(target - 1);
			for (int i = 0; i < VOICES; i++) {
				List<MMLNoteEvent> dst = t.getMMLEventAtIndex(i).getMMLNoteEventList();
				dst.clear();
				for (Src s : voices.get(i)) {
					dst.add(s.e());
				}
			}
			for (int k = removed.size() - 1; k >= 0; k--) {
				p.score().removeTrack(removed.get(k) - 1);
			}
		});
		Map<String, Integer> renumbered = new LinkedHashMap<>();
		int before = p.trackCount() + removed.size();
		for (int n = 1; n <= before; n++) {
			int nn = renumber(n, removed);
			if (nn != n) {
				renumbered.put(String.valueOf(n), nn == 0 ? null : nn);
			}
		}
		p.remapTrackRules(n -> renumber(n, removed));
		int resultTrack = renumber(target, removed);
		MMLTrack rt = p.trackRef(resultTrack);
		CharLimits.Evaluation ev = p.rankCheck(resultTrack, rank, songRule);
		Map<String, PartOut> parts = new LinkedHashMap<>();
		int notesOut = 0;
		for (Part part : Part.values()) {
			int n = rt.getMMLEventAtIndex(part.index()).getMMLNoteEventList().size();
			if (part != Part.SONG) {
				notesOut += n;
			}
			CharLimits.PartLimit pl = ev.parts().get(part.key());
			parts.put(part.key(), new PartOut(n, pl.chars(), pl.limit(), pl.overBy()));
		}
		if (!ev.fits()) {
			warnings.add("rank_over: 결과 트랙 " + resultTrack + " " + ev.overText() + " (랭크 " + ev.rank() + ", " + ev.songRule()
					+ ")");
		}
		String uw = ScoreChecks.unisonWarning(p.unisonClash(resultTrack));
		if (uw != null) {
			warnings.add("트랙 " + resultTrack + ": " + uw);
		}
		if (!dropped.isEmpty()) {
			warnings.add("dropped: " + dropped.size() + "음을 넣을 자리가 없어 뺌 " + droppedBy);
		}
		if (gaps > 0) {
			warnings.add("gaps_closed: 한 성부 안에서 6틱보다 짧은 쉼표 " + gaps + "곳을 앞 음을 늘려 메움");
		}
		if (inst != null && !inst.allowsPart(Part.CHORD1)) {
			warnings.add("악기 " + program + "(" + inst.name() + ", 타입 " + inst.type() + ")는 화음 파트를 쓰지 않음");
		}
		Result r = new Result(true, dryRun, strategy, tracks, target, resultTrack, rt.getTrackName(), program,
				inst == null ? null : inst.name(), sources, parts, ev.fits(), ev.over(), ev, mapping, notesIn, notesOut,
				dropped.size(), droppedBy, droppedList, dup[0], capped, gaps, maxLen, keepSources, removed, renumbered,
				snap == null ? null : snap.id(), snap, warnings);
		if (dryRun) {
			p.restoreState(state);
			p.setDirty(dirtyBefore);
			p.setTrackRules(rulesBefore);
		}
		return r;
	}

	/** New number of track n after removing {@code removed} (sorted); 0 if n itself was removed. */
	static int renumber(int n, List<Integer> removed) {
		int shift = 0;
		for (int r : removed) {
			if (r == n) {
				return 0;
			}
			if (r < n) {
				shift++;
			}
		}
		return n - shift;
	}

	private static String key(int track, Part part) {
		return track + "|" + part.key();
	}

	private static void keepParts(Map<String, List<Src>> byPart, List<List<Src>> voices) {
		List<String> nonEmpty = new ArrayList<>();
		for (Map.Entry<String, List<Src>> e : byPart.entrySet()) {
			if (!e.getValue().isEmpty()) {
				nonEmpty.add(e.getKey());
			}
		}
		if (nonEmpty.size() > VOICES) {
			List<Map<String, Object>> l = new ArrayList<>();
			for (String k : nonEmpty) {
				String[] s = k.split("\\|");
				l.add(details("track", Integer.parseInt(s[0]), "part", s[1], "notes", byPart.get(k).size()));
			}
			throw new EngineException(BAD_ARGUMENT, "keep_parts: 음이 있는 파트가 " + nonEmpty.size()
					+ "개라 한 트랙(멜로디·화음1·화음2 3개)에 담을 수 없음 — strategy by_voice나 priority를 쓴다",
					details("non_empty_parts", l));
		}
		for (int i = 0; i < nonEmpty.size(); i++) {
			voices.get(i).addAll(byPart.get(nonEmpty.get(i)));
		}
	}

	private static void priority(Map<String, List<Src>> byPart, List<OrderEntry> order, List<Integer> tracks,
			List<List<Src>> voices, List<Src> dropped, List<String> reasons, int[] dup) {
		if (order.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "order가 비어 있음([[트랙, 파트], …])");
		}
		List<String> seen = new ArrayList<>();
		for (int i = 0; i < order.size(); i++) {
			OrderEntry o = order.get(i);
			Part part = Part.of(o.part());
			if (part == Part.SONG) {
				throw new EngineException(BAD_ARGUMENT, "order[" + i + "]: 노래 파트는 합치지 않음(melody, chord1, chord2)",
						details("index", i, "entry", List.of(o.track(), o.part())));
			}
			if (!tracks.contains(o.track())) {
				throw new EngineException(BAD_ARGUMENT, "order[" + i + "]: 트랙 " + o.track() + "는 tracks에 없음",
						details("index", i, "entry", List.of(o.track(), o.part()), "tracks", tracks));
			}
			String k = key(o.track(), part);
			if (seen.contains(k)) {
				throw new EngineException(BAD_ARGUMENT, "order[" + i + "]: 같은 [트랙, 파트]가 두 번",
						details("index", i, "entry", List.of(o.track(), o.part())));
			}
			seen.add(k);
		}
		List<TreeMap<Integer, Src>> busy = new ArrayList<>();
		for (int i = 0; i < VOICES; i++) {
			busy.add(new TreeMap<>());
		}
		java.util.Set<Long> placed = new java.util.HashSet<>();
		for (int i = 0; i < seen.size(); i++) {
			List<Src> l = byPart.get(seen.get(i));
			for (Src s : l) {
				// same onset and pitch as a note of a higher-priority pair: one note (the game would cut one with the other)
				long key = ((long) s.tick() << 8) | (s.e().getNote() & 0xff);
				if (placed.contains(key)) {
					dup[0]++;
					continue;
				}
				if (i < VOICES) {
					placed.add(key);
					voices.get(i).add(s);
					busy.get(i).put(s.tick(), s);
					continue;
				}
				boolean done = false;
				for (int v = 0; v < VOICES && !done; v++) {
					if (free(busy.get(v), s)) {
						placed.add(key);
						voices.get(v).add(s);
						busy.get(v).put(s.tick(), s);
						done = true;
					}
				}
				if (!done) {
					dropped.add(s);
					reasons.add("no_free_voice");
				}
			}
		}
		for (Map.Entry<String, List<Src>> e : byPart.entrySet()) {
			if (!seen.contains(e.getKey())) {
				for (Src s : e.getValue()) {
					dropped.add(s);
					reasons.add("not_in_order");
				}
			}
		}
	}

	/** True when [s.tick, s.end) overlaps no note of the voice. */
	private static boolean free(TreeMap<Integer, Src> voice, Src s) {
		Map.Entry<Integer, Src> lower = voice.floorEntry(s.tick());
		if (lower != null && lower.getValue().end() > s.tick()) {
			return false;
		}
		Map.Entry<Integer, Src> higher = voice.ceilingEntry(s.tick());
		return higher == null || higher.getKey() >= s.end();
	}

	private static void byVoice(Map<String, List<Src>> byPart, List<List<Src>> voices, List<Src> dropped,
			List<String> reasons, int[] dup) {
		// pool + exact duplicates (same onset and pitch): keep the longer (then louder) note
		Map<Long, Src> pool = new HashMap<>();
		List<Src> order = new ArrayList<>();
		for (List<Src> l : byPart.values()) {
			for (Src s : l) {
				long k = ((long) s.tick() << 8) | (s.e().getNote() & 0xff);
				Src old = pool.get(k);
				if (old == null) {
					pool.put(k, s);
					order.add(s);
					continue;
				}
				dup[0]++;
				if (s.e().getTick() > old.e().getTick()
						|| (s.e().getTick() == old.e().getTick() && s.e().getVelocity() > old.e().getVelocity())) {
					pool.put(k, s);
					order.set(order.indexOf(old), s);
				}
			}
		}
		TreeMap<Integer, List<Src>> groups = new TreeMap<>();
		for (Src s : order) {
			groups.computeIfAbsent(s.tick(), k -> new ArrayList<>()).add(s);
		}
		int[] end = new int[VOICES];
		for (Map.Entry<Integer, List<Src>> g : groups.entrySet()) {
			int tick = g.getKey();
			List<Src> notes = g.getValue();
			notes.sort(Comparator.comparingInt((Src s) -> -s.e().getNote()));
			List<Integer> freeV = new ArrayList<>();
			for (int v = 0; v < VOICES; v++) {
				if (end[v] <= tick) {
					freeV.add(v);
				}
			}
			List<Src> kept;
			if (notes.size() <= freeV.size()) {
				kept = notes;
			} else {
				// keep the outer notes first: top, bottom, second top, second bottom, ...
				kept = new ArrayList<>();
				int lo = 0;
				int hi = notes.size() - 1;
				boolean top = true;
				while (kept.size() < freeV.size()) {
					kept.add(top ? notes.get(lo++) : notes.get(hi--));
					top = !top;
				}
				for (Src s : notes) {
					if (!kept.contains(s)) {
						dropped.add(s);
						reasons.add(freeV.isEmpty() ? "no_free_voice" : "more_than_free_voices");
					}
				}
				kept.sort(Comparator.comparingInt((Src s) -> -s.e().getNote()));
			}
			for (int i = 0; i < kept.size(); i++) {
				int v = freeV.get(i);
				voices.get(v).add(kept.get(i));
				end[v] = kept.get(i).end();
			}
		}
	}

	/** Closes gaps of 1-5 ticks inside one voice (sorted, non-overlapping) by extending the earlier note. */
	private static int closeGaps(List<Src> v) {
		int n = 0;
		for (int i = 0; i + 1 < v.size(); i++) {
			MMLNoteEvent a = v.get(i).e();
			int gap = v.get(i + 1).tick() - a.getEndTick();
			if (gap > 0 && gap < 6) {
				a.setTick(a.getTick() + gap);
				n++;
			}
		}
		return n;
	}
}
