package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import jp.fourthline.mmlTools.MMLEventList;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_copy_range}: 마디 범위를 다른 위치로 복사한다(트랙 여러 개, 파트 모두). 옮김(transpose)은 옮길 수 있는
 * 악기(타입 N·K·V·C, MabiIcco {@code InstType.allowTranspose})에만, 드럼 D·타악기 P·모바일 M은 그대로. 옮긴 음이 악기 음역
 * (DLS를 읽었으면 DLS 기준) 밖이면 버리거나(drop) 옥타브로 접는다(fold). 세기 더하기는 0~15로 자른다.
 *
 * <p>replace(기본)는 대상 범위의 고른 트랙을 먼저 비운다(대상 범위에서 시작하는 음은 지우고, 앞에서 들어오는 음은 대상 시작에서
 * 자름 — score_replace_range와 같음). merge는 비우지 않고 엔진 규칙(겹친 앞 음은 잘리고 뒤 음은 지워짐)으로 넣고 그 수를
 * merge_conflicts로 알린다. 원본은 바꾸기 전에 값으로 읽어 두므로 원본·대상 범위가 겹쳐도 된다. 복사한 음이 대상 범위 끝을
 * 넘으면 끝에서 자른다. 템포·박자는 복사하지 않는다.</p>
 */
public final class RangeCopy {

	static final String MODE_REPLACE = "replace";
	static final String MODE_MERGE = "merge";
	static final String OOR_DROP = "drop";
	static final String OOR_FOLD = "fold";
	static final int MAX_TRANSPOSE = 48;

	/** 트랙 하나의 결과. */
	public record TrackResult(int track, String name, int program, String instrument, String instrumentType,
			boolean transposed, String transposeNote, int copied, Map<String, Integer> copiedByPart, int dropped,
			int folded, int velocityClamped, int truncatedAtEnd, int silent, int cleared, int truncatedBefore,
			int mergeConflicts, Map<String, Integer> charsBefore, Map<String, Integer> charsAfter,
			CharLimits.Evaluation rankLimits, List<Map<String, Object>> droppedNotes) {}

	/** 도구 응답. */
	public record Result(boolean ok, boolean dryRun, int fromMeasure, int toMeasure, int destMeasure, int destToMeasure,
			long shiftTicks, String mode, int transpose, int velocityDelta, String outOfRange, List<TrackResult> tracks,
			Map<String, Integer> totals, Integer snapshotId, Snapshots.SnapshotInfo snapshot, List<Integer> changedMeasures,
			List<String> warnings) {}

	/** 복사할 음 하나(값으로). */
	private record Plan(Part part, int tick, int len, int midi, int vel) {}

	private RangeCopy() {}

	static Result run(Project p, int from, int to, int dest, List<Integer> tracksArg, int transpose, int velocityDelta,
			String modeArg, String oorArg, boolean dryRun, String rank, String songRule) {
		MMLTrackAccess acc = new MMLTrackAccess(p);
		if (from < 1 || to < from || dest < 1) {
			throw new EngineException(BAD_ARGUMENT, "마디 범위가 틀림: " + from + "~" + to + " → " + dest + " (1 이상, from ≤ to)",
					details("from_measure", from, "to_measure", to, "dest_measure", dest));
		}
		if (Math.abs(transpose) > MAX_TRANSPOSE) {
			throw new EngineException(BAD_ARGUMENT, "transpose는 −" + MAX_TRANSPOSE + "~" + MAX_TRANSPOSE + " 반음",
					details("transpose", transpose));
		}
		if (velocityDelta < -15 || velocityDelta > 15) {
			throw new EngineException(BAD_ARGUMENT, "velocity_delta는 −15~15", details("velocity_delta", velocityDelta));
		}
		String mode = modeArg == null || modeArg.isBlank() ? MODE_REPLACE : modeArg.trim().toLowerCase(Locale.ROOT);
		if (!MODE_REPLACE.equals(mode) && !MODE_MERGE.equals(mode)) {
			throw new EngineException(BAD_ARGUMENT, "mode는 replace 또는 merge", details("mode", modeArg));
		}
		String oor = oorArg == null || oorArg.isBlank() ? OOR_DROP : oorArg.trim().toLowerCase(Locale.ROOT);
		if (!OOR_DROP.equals(oor) && !OOR_FOLD.equals(oor)) {
			throw new EngineException(BAD_ARGUMENT, "out_of_range는 drop 또는 fold", details("out_of_range", oorArg));
		}
		List<Integer> tracks = new ArrayList<>();
		if (tracksArg == null) {
			for (int i = 1; i <= p.trackCount(); i++) {
				tracks.add(i);
			}
		} else {
			if (tracksArg.isEmpty()) {
				throw new EngineException(BAD_ARGUMENT, "tracks가 비어 있음(생략하거나 \"all\"이면 모든 트랙)");
			}
			for (Integer t : tracksArg) {
				acc.track(t);
				if (!tracks.contains(t)) {
					tracks.add(t);
				}
			}
		}
		if (tracks.isEmpty()) {
			throw new EngineException(EngineException.NO_TRACK, "트랙이 없음(track_add 먼저)");
		}
		CharLimits.rank(rank); // 랭크 이름 검사(틀리면 BAD_ARGUMENT)
		MeasureMap map = p.measureMap();
		int n = to - from + 1;
		long s0 = map.measureStart(from);
		long s1 = map.measureEnd(to);
		long d0 = map.measureStart(dest);
		long d1 = map.measureEnd(dest + n - 1);
		List<Map<String, Object>> mism = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			long a = map.measureEnd(from + i) - map.measureStart(from + i);
			long b = map.measureEnd(dest + i) - map.measureStart(dest + i);
			if (a != b) {
				mism.add(details("source_measure", from + i, "source_ticks", a, "dest_measure", dest + i, "dest_ticks", b));
			}
		}
		if (!mism.isEmpty()) {
			throw new EngineException(BAD_ARGUMENT, "원본과 대상 마디의 박자(길이)가 다름 — 같은 길이의 마디끼리만 복사한다",
					details("mismatch", mism.subList(0, Math.min(8, mism.size()))));
		}
		long shift = d0 - s0;
		InstrumentTable table = acc.engine().instrumentTable();
		List<String> warnings = new ArrayList<>();
		// 원본 읽기(값으로) + 계획
		Map<Integer, List<Plan>> plans = new LinkedHashMap<>();
		Map<Integer, int[]> stats = new LinkedHashMap<>(); // dropped, folded, velClamped, truncEnd, silent
		Map<Integer, List<Map<String, Object>>> droppedNotes = new LinkedHashMap<>();
		Map<Integer, Boolean> transposed = new LinkedHashMap<>();
		Map<Integer, String> transposeNote = new LinkedHashMap<>();
		for (int tn : tracks) {
			MMLTrack t = acc.track(tn);
			InstrumentTable.Instrument inst = table.get(t.getProgram());
			List<Plan> pl = new ArrayList<>();
			int[] st = new int[5];
			List<Map<String, Object>> dn = new ArrayList<>();
			boolean anyT = false;
			String note = null;
			for (Part part : Part.values()) {
				int prog = p.soundingProgram(tn, part);
				InstrumentTable.Instrument pi = table.get(prog);
				boolean canT = InstrumentTable.allowsTranspose(pi == null ? null : pi.type());
				int tr = canT ? transpose : 0;
				if (!canT && transpose != 0) {
					note = "악기 " + prog + (pi == null ? "" : "(" + pi.name() + ", 타입 " + pi.type() + ")")
							+ "는 옮기지 않음(드럼·타악기)";
				}
				InstrumentTable.Range rg = pi == null ? null : pi.range();
				int lo = Math.max(NoteSpec.MIN_MIDI, rg == null || rg.low() == null ? NoteSpec.MIN_MIDI : rg.low());
				int hi = Math.min(NoteSpec.MAX_MIDI, rg == null || rg.high() == null ? NoteSpec.MAX_MIDI : rg.high());
				for (MMLNoteEvent e : t.getMMLEventAtIndex(part.index()).getMMLNoteEventList()) {
					if (e.getTickOffset() < s0 || e.getTickOffset() >= s1) {
						continue;
					}
					long nt = e.getTickOffset() + shift;
					int len = e.getTick();
					if (nt + len > d1) {
						len = (int) (d1 - nt);
						st[3]++;
					}
					int midi = e.getNote() + 12 + tr;
					boolean wasOut = midi < lo || midi > hi;
					if (wasOut && OOR_FOLD.equals(oor) && hi - lo >= 11) {
						while (midi > hi) {
							midi -= 12;
						}
						while (midi < lo) {
							midi += 12;
						}
					}
					if (midi < lo || midi > hi) {
						st[0]++;
						if (dn.size() < 10) {
							dn.add(details("part", part.key(), "pos", map.position(e.getTickOffset()).toString(), "pitch",
									e.getNote() + 12, "transposed_pitch", e.getNote() + 12 + tr, "range", List.of(lo, hi)));
						}
						continue;
					}
					if (wasOut) {
						st[1]++;
					}
					int vel = e.getVelocity() + velocityDelta;
					if (vel < 0 || vel > 15) {
						st[2]++;
						vel = Math.max(0, Math.min(15, vel));
					}
					if (SoundingPitch.soundingOf(prog, midi) == SoundingPitch.SILENT) {
						st[4]++;
					}
					if (tr != 0) {
						anyT = true;
					}
					pl.add(new Plan(part, (int) nt, len, midi, vel));
				}
			}
			plans.put(tn, pl);
			stats.put(tn, st);
			droppedNotes.put(tn, dn);
			transposed.put(tn, anyT || (transpose != 0 && note == null));
			transposeNote.put(tn, note);
		}
		// 템포는 복사하지 않음
		List<String> tempos = new ArrayList<>();
		for (MMLTempoEvent te : p.score().getTempoEventList()) {
			if (te.getTickOffset() > s0 && te.getTickOffset() < s1) {
				tempos.add(map.position(te.getTickOffset()) + " t" + te.getTempo());
			}
		}
		if (!tempos.isEmpty()) {
			warnings.add("tempo_not_copied: 원본 범위 안 템포 변경 " + tempos.size() + "개(" + String.join(", ", tempos.subList(0,
					Math.min(5, tempos.size()))) + ")는 복사하지 않음 — 필요하면 project_set_tempos");
		}
		Map<Integer, int[]> before = new LinkedHashMap<>();
		for (int tn : tracks) {
			before.put(tn, TrackCopy.mmlChars(acc.track(tn)));
		}
		Snapshots.SnapshotInfo snap = null;
		byte[] state = null;
		boolean dirtyBefore = p.dirty();
		if (dryRun) {
			state = p.state();
		} else {
			snap = acc.engine().snapshots().snapshot(p.score(), "auto: score_copy_range " + from + "~" + to + " → " + dest
					+ (transpose != 0 ? " transpose " + transpose : "") + (velocityDelta != 0 ? " vel " + velocityDelta : "")
					+ " " + mode);
		}
		Map<Integer, int[]> edits = new LinkedHashMap<>(); // cleared, truncatedBefore, mergeConflicts, copied
		Map<Integer, Map<String, Integer>> byPart = new LinkedHashMap<>();
		String fMode = mode;
		p.mutate(() -> {
			for (int tn : tracks) {
				MMLTrack t = p.score().getTrack(tn - 1);
				int[] ed = new int[4];
				Map<String, Integer> bp = new LinkedHashMap<>();
				for (Part part : Part.values()) {
					MMLEventList el = t.getMMLEventAtIndex(part.index());
					List<MMLNoteEvent> l = el.getMMLNoteEventList();
					if (MODE_REPLACE.equals(fMode)) {
						for (MMLNoteEvent e : new ArrayList<>(l)) {
							if (e.getTickOffset() >= d0 && e.getTickOffset() < d1) {
								l.remove(e);
								ed[0]++;
							} else if (e.getTickOffset() < d0 && e.getEndTick() > d0) {
								e.setTick((int) (d0 - e.getTickOffset()));
								ed[1]++;
							}
						}
					}
					Map<MMLNoteEvent, int[]> was = new IdentityHashMap<>();
					for (MMLNoteEvent e : l) {
						was.put(e, new int[] { e.getTick() });
					}
					int c = 0;
					for (Plan pl : plans.get(tn)) {
						if (pl.part() != part) {
							continue;
						}
						el.addMMLNoteEvent(new MMLNoteEvent(pl.midi() - 12, pl.len(), pl.tick(), pl.vel()));
						c++;
					}
					// merge: 엔진이 자르거나 지운 기존 음
					Map<MMLNoteEvent, Boolean> now = new IdentityHashMap<>();
					for (MMLNoteEvent e : el.getMMLNoteEventList()) {
						now.put(e, Boolean.TRUE);
					}
					for (Map.Entry<MMLNoteEvent, int[]> w : was.entrySet()) {
						if (!now.containsKey(w.getKey()) || w.getKey().getTick() != w.getValue()[0]) {
							ed[2]++;
						}
					}
					bp.put(part.key(), c);
					ed[3] += c;
				}
				edits.put(tn, ed);
				byPart.put(tn, bp);
			}
		});
		List<TrackResult> out = new ArrayList<>();
		Map<String, Integer> totals = new LinkedHashMap<>();
		TreeSet<Integer> changed = new TreeSet<>();
		for (int tn : tracks) {
			MMLTrack t = p.score().getTrack(tn - 1);
			InstrumentTable.Instrument inst = table.get(t.getProgram());
			int[] st = stats.get(tn);
			int[] ed = edits.get(tn);
			int[] after = TrackCopy.mmlChars(t);
			CharLimits.Evaluation ev = p.rankCheck(tn, rank, songRule);
			out.add(new TrackResult(tn, t.getTrackName(), t.getProgram(), inst == null ? null : inst.name(),
					inst == null ? null : inst.type(), transposed.get(tn), transposeNote.get(tn), ed[3], byPart.get(tn), st[0],
					st[1], st[2], st[3], st[4], ed[0], ed[1], ed[2], charMap(before.get(tn)), charMap(after), ev,
					droppedNotes.get(tn)));
			if (!ev.fits()) {
				warnings.add("rank_over: 트랙 " + tn + " " + ev.overText() + " (랭크 " + ev.rank() + ", " + ev.songRule() + ")");
			}
			String vw = p.voiceRuleWarning(tn, songRule);
			if (vw != null) {
				warnings.add(vw);
			}
			String uw = ScoreChecks.unisonWarning(p.unisonClash(tn));
			if (uw != null) {
				warnings.add("트랙 " + tn + ": " + uw);
			}
			if (st[0] > 0) {
				warnings.add("dropped_out_of_range: 트랙 " + tn + " " + st[0] + "음이 옮긴 뒤 음역 밖이라 빠짐"
						+ (OOR_DROP.equals(oor) ? "(out_of_range \"fold\"면 옥타브로 접음)" : ""));
			}
			if (st[4] > 0) {
				warnings.add("silent_note: 트랙 " + tn + " " + st[4] + "음이 악기의 무음 구간에 적힘(소리 나지 않음)");
			}
			if (ed[2] > 0) {
				warnings.add("merge_conflicts: 트랙 " + tn + " 기존 음 " + ed[2] + "개가 겹쳐 잘리거나 지워짐(엔진 규칙)");
			}
			if (ed[0] + ed[1] + ed[2] + ed[3] > 0) {
				for (int m = dest; m <= dest + n - 1; m++) {
					changed.add(m);
				}
				if (ed[1] > 0) {
					changed.add(map.measureOf(d0 - 1));
				}
			}
			for (String k : new String[] { "copied", "dropped", "folded", "velocity_clamped", "truncated_at_end", "silent",
					"cleared", "truncated_before", "merge_conflicts" }) {
				totals.merge(k, switch (k) {
				case "copied" -> ed[3];
				case "dropped" -> st[0];
				case "folded" -> st[1];
				case "velocity_clamped" -> st[2];
				case "truncated_at_end" -> st[3];
				case "silent" -> st[4];
				case "cleared" -> ed[0];
				case "truncated_before" -> ed[1];
				default -> ed[2];
				}, Integer::sum);
			}
		}
		if (dryRun) {
			p.restoreState(state);
			p.setDirty(dirtyBefore);
		}
		if (totals.getOrDefault("copied", 0) == 0) {
			warnings.add("원본 범위 " + from + "~" + to + "마디에 복사할 음이 없음");
		}
		return new Result(true, dryRun, from, to, dest, dest + n - 1, shift, mode, transpose, velocityDelta, oor, out, totals,
				snap == null ? null : snap.id(), snap, new ArrayList<>(changed), warnings);
	}

	private static Map<String, Integer> charMap(int[] c) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			m.put(p.key(), c[p.index()]);
		}
		return m;
	}

	/** Project의 트랙·엔진 접근(같은 패키지). */
	private record MMLTrackAccess(Project p) {
		MMLTrack track(Integer n) {
			if (n == null || n < 1 || n > p.trackCount()) {
				throw new EngineException(EngineException.NO_TRACK, "트랙 " + n + "번이 없음(1~" + p.trackCount() + ")",
						details("track", n, "track_count", p.trackCount()));
			}
			return p.score().getTrack(n - 1);
		}

		BridgeEngine engine() {
			return p.engine();
		}
	}
}
