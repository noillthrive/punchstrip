package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import jp.fourthline.mmlTools.MMLScore;

/**
 * score_finalize near a limit: the tempo copy is omitted (with a warning) when it alone breaks the budget, kept when
 * there is room; removal after "omitted" restores everything except the tick-0 tempo. Second call names the tracks
 * without a passage. The per-program velocity offset (injected table).
 */
public class FinalizeBudgetTest {

	private static final int[] PITCHES = { 60, 64, 67, 72, 62, 65, 69, 71, 59, 55 };

	/** Appends 16th / 8th notes to the melody from {@code tick} until it has exactly {@code target} characters. */
	private static long fill(Project p, long tick, int target) {
		int i = 0;
		while (true) {
			int len = (i % 7 == 3) ? 48 : 24;
			p.putNotes(1, "melody", List.of(NoteSpec.at(tick, len, PITCHES[i % PITCHES.length], 8 + i % 3)), "strict", false);
			int c = p.statsOf(1).chars().get("melody");
			if (c == target) {
				return tick + len;
			}
			if (c > target) {
				p.deleteNotes(1, "melody", List.of((int) tick));
				for (int pt : PITCHES) {
					for (int l : new int[] { 24, 48, 12, 96 }) {
						for (int v = 8; v <= 10; v++) {
							p.putNotes(1, "melody", List.of(NoteSpec.at(tick, l, pt, v)), "strict", false);
							if (p.statsOf(1).chars().get("melody") == target) {
								return tick + l;
							}
							p.deleteNotes(1, "melody", List.of((int) tick));
						}
					}
				}
				throw new AssertionError("cannot reach " + target);
			}
			tick += len;
			i++;
		}
	}

	/** {rest cost, copy cost} of the lead-in on the melody of track 1 (a copy of the score is measured). */
	private static int[] costs(Project p) throws Exception {
		MMLScore c = p.score().clone();
		c.generateAll();
		int a = TrackCopy.mmlChars(c.getTrack(0))[0];
		Finalize.insertLeadIn(c, 1);
		c.generateAll();
		int b = TrackCopy.mmlChars(c.getTrack(0))[0];
		Finalize.addTempoCopy(c, 384, 97);
		c.generateAll();
		int d = TrackCopy.mmlChars(c.getTrack(0))[0];
		return new int[] { b - a, d - b };
	}

	private static final Finalize.Options PRACTICE = new Finalize.Options(true, false, 12, null, false, "Practice",
			"song_included");

	/** Practice, song_included: melody limit 200. Filled to 200 − rest cost: the lead-in fits, the copy does not. */
	@Test
	public void tempoCopyOmittedNearTheLimitAndRemovalLosesOnlyTheStartTempo() throws Exception {
		Path dir = EngineTestSupport.tmpDir("budget");
		BridgeEngine engine = EngineTestSupport.engine(dir);
		Project p = engine.newProject("near", null, "4/4", 97);
		p.addTrack("lute", 0, null, null, null);
		long next = fill(p, 0, 180);
		int[] cost = costs(p);
		assertTrue("rest " + cost[0], cost[0] > 0);
		assertTrue("copy " + cost[1], cost[1] > 0);
		fill(p, next, 200 - cost[0]);
		assertEquals(cost[0] + cost[1], costs(p)[0] + costs(p)[1]);
		Path in = dir.resolve("near.mmi");
		p.saveAs(in);
		List<String> want = FinalizeRemovalTest.summary(p.score());

		Finalize.Result r = p.finalizeScore(PRACTICE);
		Finalize.Signature s = r.signature();
		assertEquals(s.toString(), "added", s.status());
		assertEquals("omitted", s.leadInTempo().get("status"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> noRoom = (List<Map<String, Object>>) s.leadInTempo().get("no_room");
		assertEquals(1, noRoom.get(0).get("track"));
		assertEquals("melody", noRoom.get(0).get("slot"));
		assertEquals(cost[1], noRoom.get(0).get("over_by"));
		assertTrue(r.warnings().toString(), r.warnings().stream().anyMatch(w -> w.startsWith("tempo_copy_omitted") && w.contains("97")));
		assertEquals("chord1", s.tracks().get(0).slot()); // melody is full after the lead-in, the passage goes to chord1
		assertEquals(1, p.score().getTempoEventList().size()); // only the tick-0 tempo
		assertEquals(200, p.rankCheck(1, "Practice", "song_included").parts().get("melody").chars());

		Path out = dir.resolve("near_final.mmi");
		p.saveAs(out);
		Project q = engine.open(out);
		q.score().removeTicks(0, true);
		q.score().generateAll();
		List<String> expect = new ArrayList<>(want);
		assertTrue(expect.remove("tempo 0 97"));
		assertEquals(expect, FinalizeRemovalTest.summary(q.score()));
		assertTrue(q.score().getTempoEventList().isEmpty()); // the start tempo must be entered again (default 120 now)
	}

	@Test
	public void tempoCopyKeptWithRoom() throws Exception {
		Path dir = EngineTestSupport.tmpDir("budget-room");
		Project p = EngineTestSupport.engine(dir).newProject("room", null, "4/4", 97);
		p.addTrack("lute", 0, null, null, null);
		fill(p, 0, 150);
		Finalize.Result r = p.finalizeScore(PRACTICE);
		assertEquals("added", r.signature().status());
		assertEquals("kept", r.signature().leadInTempo().get("status"));
		assertEquals(2, p.score().getTempoEventList().size());
		assertTrue(r.warnings().stream().noneMatch(w -> w.startsWith("tempo_copy_omitted")));
	}

	/** The lead-in rest itself does not fit: the reason names the rest, nothing is inserted. */
	@Test
	public void leadInRestThatDoesNotFit() throws Exception {
		Path dir = EngineTestSupport.tmpDir("budget-rest");
		Project p = EngineTestSupport.engine(dir).newProject("rest", null, "4/4", 97);
		p.addTrack("lute", 0, null, null, null);
		fill(p, 0, 200);
		byte[] before = p.state();
		Finalize.Result r = p.finalizeScore(PRACTICE);
		assertEquals("skipped", r.signature().status());
		assertTrue(r.signature().reason(), r.signature().reason().contains("쉼표만으로"));
		assertNull(r.signature().leadInTempo());
		assertEquals(new String(before), new String(p.state()));
	}

	/** Second call: the tracks without a passage are named. */
	@Test
	public void secondCallNamesTracksWithoutThePassage() throws Exception {
		Path dir = EngineTestSupport.tmpDir("budget-second");
		Project p = EngineTestSupport.engine(dir).newProject("second", null, "4/4", null);
		p.addTrack("bottle", 7, null, null, null);
		p.addTrack("lute", 0, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 60)), "strict", false);
		p.putNotes(2, "melody", List.of(NoteSpec.at(0, 96, 60)), "strict", false);
		Finalize.Options o = new Finalize.Options(true, false, 12, null, false, null, null);
		assertEquals("partial", p.finalizeScore(o).signature().status());
		Finalize.Signature s = p.finalizeScore(o).signature();
		assertEquals("already_present", s.status());
		assertTrue(s.reason(), s.reason().contains("시그니처가 없는 트랙: 1(bottle)"));
		assertEquals("skipped", s.tracks().get(0).status());
		assertEquals("already_present", s.tracks().get(1).status());
		assertEquals("melody", s.tracks().get(1).slot());
	}

	/** velocity_offset.<program> from the table, after the scale conversion, clamped to 1..15 (injected table). */
	@Test
	public void velocityOffsetFromTheTable() throws Exception {
		// shipped: both microphones three steps lower, the drum snares three steps higher (v15 like the crash)
		assertEquals(Integer.valueOf(-3), SignatureHome.velocityOffset(121));
		assertEquals(Integer.valueOf(-3), SignatureHome.velocityOffset(120));
		assertEquals(Integer.valueOf(3), SignatureHome.velocityOffset(27));
		assertNull(SignatureHome.velocityOffset(0));
		try {
			SignatureHome.useForTest("0=66\n77=90\nvelocity_offset.0=-3\nvelocity_offset.77=+1\nvelocity_offset.27=-20\n");
			Path dir = EngineTestSupport.tmpDir("budget-vel");
			Project p = EngineTestSupport.engine(dir).newProject("vel", null, "4/4", null);
			p.addTrack("lute", 0, null, null, null);
			p.addTrack("xylo", 77, null, null, null);
			p.addTrack("drums", 27, null, null, null);
			p.addTrack("flute", 5, null, null, null);
			Finalize.Result r = p.finalizeScore(new Finalize.Options(true, false, 12, null, false, null, null));
			List<Finalize.SignatureTrack> t = r.signature().tracks();
			assertEquals(List.of(9, 9, 9, 9), t.get(0).velocities());
			assertEquals(Integer.valueOf(-3), t.get(0).velocityOffset());
			assertEquals(List.of(10, 10, 10, 10), t.get(1).velocities()); // x11: v12 → v9, +1
			assertEquals(List.of(1, 1, 1, 15), t.get(2).velocities()); // snares clamped to 1, crash stays 15
			assertEquals(List.of(12, 12, 12, 12), t.get(3).velocities());
			assertNull(t.get(3).velocityOffset());
		} finally {
			SignatureHome.useForTest(null);
		}
	}
}
