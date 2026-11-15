package mabibridge.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/** (c) strict 겹침 거부·merge 보고, (d) 6의 배수 검증·align, (e) replaceRange. */
public class EditTest {

	private Project p;

	@Before
	public void setUp() throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("edit"));
		p = e.newProject("edit", "", "4/4", 87);
		p.addTrack("t1", 0, null, null, null);
	}

	private static EngineException expect(String code, Runnable r) {
		try {
			r.run();
		} catch (EngineException e) {
			assertEquals(e.toString(), code, e.code());
			return e;
		}
		fail("EngineException " + code + " 이 나야 함");
		return null;
	}

	// ---- (c) ----

	@Test
	public void strictOverlapRejectsAndLeavesScoreUnchanged() {
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(192, 96, "E4")), "strict", false);
		byte[] before = p.state();
		String mmlBefore = p.mml(1).get(0).mml();
		EngineException ex = expect(EngineException.OVERLAP, () -> p.putNotes(1, "melody",
				List.of(NoteSpec.at(384, 96, "G4"), NoteSpec.at(48, 96, "D4")), "strict", false));
		List<?> pairs = (List<?>) ex.details().get("pairs");
		assertEquals(1, pairs.size());
		assertArrayEquals("악보가 바뀌면 안 됨", before, p.state());
		assertEquals(mmlBefore, p.mml(1).get(0).mml());
		assertEquals(2, p.notes(1, "melody", 1, 10).size());
	}

	/** 새 음이 기존 음을 통째로 덮는 경우(MMLEventList.isOverlapNote가 놓치는 경우)도 거부한다. */
	@Test
	public void strictOverlapContainment() {
		p.putNotes(1, "melody", List.of(NoteSpec.at(96, 48, "C4")), "strict", false);
		byte[] before = p.state();
		expect(EngineException.OVERLAP, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, 288, "D4")), "strict", false));
		assertArrayEquals(before, p.state());
	}

	@Test
	public void strictAdjacentIsNotOverlap() {
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		EditResult r = p.putNotes(1, "melody", List.of(NoteSpec.at(96, 96, "D4")), "strict", false);
		assertEquals(1, r.added());
		assertTrue(r.affected().isEmpty());
		assertEquals(2, p.notes(1, "melody", 1, 1).size());
	}

	@Test
	public void inputNotesOverlappingEachOtherRejected() {
		byte[] before = p.state();
		EngineException ex = expect(EngineException.OVERLAP, () -> p.putNotes(1, "chord1",
				List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(0, 96, "E4")), "merge", false));
		assertEquals("input", ex.details().get("between"));
		assertArrayEquals(before, p.state());
	}

	@Test
	public void mergeTruncatesPreviousAndRemovesCovered() {
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(192, 96, "E4")), "strict", false);
		// D4 [48, 240): C4 [0,96)는 48로 잘리고, E4 [192,288)는 시작이 덮여 엔진이 지운다.
		EditResult r = p.putNotes(1, "melody", List.of(NoteSpec.at(48, 192, "D4")), "merge", false);
		assertEquals(2, r.affected().size());
		EditResult.AffectedNote a0 = r.affected().get(0);
		assertEquals("truncated", a0.change());
		assertEquals(96, a0.before().len());
		assertEquals(48, a0.after().len());
		assertEquals("C4", a0.before().name());
		EditResult.AffectedNote a1 = r.affected().get(1);
		assertEquals("removed", a1.change());
		assertEquals("E4", a1.before().name());
		assertNull(a1.after());
		assertEquals(1, r.deleted());
		List<NoteView> notes = p.notes(1, "melody", 1, 1);
		assertEquals(2, notes.size());
		assertEquals(48, notes.get(0).len());
		assertEquals("D4", notes.get(1).name());
		assertEquals(List.of(1), r.changedMeasures());
		assertEquals("MML@t87c8d2,,;", p.mml(1).get(0).mml());
	}

	// ---- (d) ----

	@Test
	public void tickNotMultipleOf6IsBadNote() {
		byte[] before = p.state();
		EngineException ex = expect(EngineException.BAD_NOTE,
				() -> p.putNotes(1, "melody", List.of(NoteSpec.at(50, 96, "C4")), "strict", false));
		assertEquals("tick", ex.details().get("field"));
		assertArrayEquals(before, p.state());
	}

	@Test
	public void alignRoundsTo6AndReports() {
		EditResult r = p.putNotes(1, "melody", List.of(NoteSpec.at(50, 94, "C4"), NoteSpec.at(200, 96, 62)), "strict", true);
		assertEquals(3, r.aligned().size());
		assertEquals("tick", r.aligned().get(0).field());
		assertEquals(50, r.aligned().get(0).from());
		assertEquals(48, r.aligned().get(0).to());
		assertEquals("len", r.aligned().get(1).field());
		assertEquals(96, r.aligned().get(1).to());
		assertEquals(1, r.aligned().get(2).index());
		assertEquals(198, r.aligned().get(2).to());
		List<NoteView> n = p.notes(1, "melody", 1, 1);
		assertEquals(48, n.get(0).tick());
		assertEquals(96, n.get(0).len());
		assertEquals(198, n.get(1).tick());
	}

	@Test
	public void otherBadNotes() {
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 10)), "strict", false));
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 120)), "strict", false));
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4", 16)), "strict", false));
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, "4x", "C4")), "strict", false));
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.atPos("1:5:0", 96, "C4")), "strict", false));
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody",
				List.of(new NoteSpec(0L, "1:1:0", 96, "C4", null)), "strict", false));
		// 경계값은 통과: MIDI 11(엔진 −1), 119(엔진 107)
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 11), NoteSpec.at(96, 96, 119)), "strict", false);
		List<NoteView> n = p.notes(1, "melody", 1, 1);
		assertEquals(11, n.get(0).pitch());
		assertEquals(119, n.get(1).pitch());
	}

	// ---- (e) ----

	private static List<NoteSpec> measureNotes(int measure, String... pitches) {
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < pitches.length; i++) {
			l.add(NoteSpec.atPos(measure + ":" + (i + 1) + ":0", "4", pitches[i]));
		}
		return l;
	}

	@Test
	public void replaceRangeKeepsOtherMeasures() {
		List<NoteSpec> fill = new ArrayList<>(measureNotes(27, "C4", "D4", "E4", "F4"));
		fill.addAll(measureNotes(28, "G4", "A4", "B4", "C5"));
		Map<String, List<NoteSpec>> parts = new LinkedHashMap<>();
		parts.put("melody", fill);
		parts.put("chord1", measureNotes(27, "C3", "C3", "C3", "C3"));
		EditResult r0 = p.replaceRange(1, 27, 28, parts, false);
		assertEquals(List.of(27, 28), r0.changedMeasures());
		List<NoteView> m27 = p.notes(1, "melody", 27, 27);
		List<NoteView> c27 = p.notes(1, "chord1", 27, 27);
		assertEquals(4, m27.size());
		assertEquals("27:1:0", m27.get(0).pos());

		Map<String, List<NoteSpec>> repl = new LinkedHashMap<>();
		repl.put("melody", List.of(NoteSpec.atPos("28:1:0", "2", "E5"), NoteSpec.atPos("28:3:0", "2", "D5")));
		EditResult r = p.replaceRange(1, 28, 28, repl, false);
		assertEquals(List.of(28), r.changedMeasures());
		assertEquals(4, r.deleted());
		assertEquals(2, r.added());
		assertEquals(List.of("melody"), r.parts());
		assertEquals("27마디 멜로디는 그대로", m27, p.notes(1, "melody", 27, 27));
		assertEquals("chord1은 손대지 않음", c27, p.notes(1, "chord1", 27, 27));
		List<NoteView> m28 = p.notes(1, "melody", 28, 28);
		assertEquals(2, m28.size());
		assertEquals("E5", m28.get(0).name());
		assertEquals(192, m28.get(0).len());
	}

	@Test
	public void replaceRangeRejectsNotesOutsideRange() {
		byte[] before = p.state();
		Map<String, List<NoteSpec>> repl = new LinkedHashMap<>();
		repl.put("melody", List.of(NoteSpec.atPos("28:4:0", "2", "E5"))); // 29마디로 넘어감
		expect(EngineException.OUT_OF_RANGE, () -> p.replaceRange(1, 28, 28, repl, false));
		Map<String, List<NoteSpec>> repl2 = new LinkedHashMap<>();
		repl2.put("melody", List.of(NoteSpec.atPos("27:1:0", "4", "E5")));
		expect(EngineException.OUT_OF_RANGE, () -> p.replaceRange(1, 28, 28, repl2, false));
		assertArrayEquals(before, p.state());
	}

	@Test
	public void replaceRangeTruncatesNoteComingIn() {
		p.putNotes(1, "melody", List.of(NoteSpec.atPos("27:3:0", "1", "C4")), "strict", false); // 28마디 2박까지
		Map<String, List<NoteSpec>> repl = new LinkedHashMap<>();
		repl.put("melody", List.of(NoteSpec.atPos("28:3:0", "4", "G4")));
		EditResult r = p.replaceRange(1, 28, 28, repl, false);
		assertEquals(1, r.affected().size());
		assertEquals("truncated", r.affected().get(0).change());
		assertEquals(192, r.affected().get(0).after().len());
		assertEquals(List.of(28), r.changedMeasures());
	}

	@Test
	public void deleteByRangeAndTicks() {
		p.replaceRange(1, 1, 2, Map.of("melody", measureNotes(1, "C4", "D4", "E4", "F4")), false);
		p.putNotes(1, "melody", measureNotes(2, "G4", "A4"), "strict", false);
		EditResult r = p.deleteNotes(1, "melody", 2, 2);
		assertEquals(2, r.deleted());
		assertEquals(List.of(2), r.changedMeasures());
		EditResult r2 = p.deleteNotes(1, "melody", List.of(96, 100));
		assertEquals(1, r2.deleted());
		assertEquals(1, r2.warnings().size());
		assertEquals(3, p.notes(1, "melody", 1, 5).size());
	}

	@Test
	public void writeResultHasStats() {
		EditResult r = p.putNotes(1, "melody", measureNotes(1, "C4"), "strict", false);
		assertTrue(r.ok());
		assertEquals(1, r.stats().track());
		assertEquals(4, r.stats().chars().get("melody").intValue()); // t87c
		assertNull(r.compareSummary());
	}

	@Test
	public void noTrackAndBadPart() {
		expect(EngineException.NO_TRACK, () -> p.notes(2, "melody", 1, 1));
		expect(EngineException.BAD_ARGUMENT, () -> p.notes(1, "drums", 1, 1));
		expect(EngineException.BAD_ARGUMENT, () -> p.addTrack("x", 9999, null, null, null));
	}
}
