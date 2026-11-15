package mabibridge.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;

/** 랭크별 한도, unison_clash·템포 검사, score_transform 연산, score_fit_suggest, score_split_sheets. */
public class FitTest {

	private static Project project(String name) throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir(name));
		Project p = e.newProject(name, "", "4/4", 120);
		p.addTrack("t", 0, null, null, null); // 류트(N): melody·chord1·chord2
		return p;
	}

	private static List<MMLNoteEvent> notes(Project p, int track, String part) {
		return p.score().getTrack(track - 1).getMMLEventAtIndex(Part.of(part).index()).getMMLNoteEventList();
	}

	private static int[] lens(List<MMLNoteEvent> l) {
		return l.stream().mapToInt(MMLNoteEvent::getTick).toArray();
	}

	private static int[] vels(List<MMLNoteEvent> l) {
		return l.stream().mapToInt(MMLNoteEvent::getVelocity).toArray();
	}

	private static TransformResult tr(Project p, String part, String op, Map<String, Object> params) {
		return p.transform(1, List.of(part), null, null, TransformOp.of(op, params));
	}

	private static void expectBad(Runnable r) {
		try {
			r.run();
			fail("BAD_ARGUMENT여야 함");
		} catch (EngineException e) {
			assertEquals(e.getMessage(), EngineException.BAD_ARGUMENT, e.code());
		}
	}

	// ------------------------------------------------------------------ D1 랭크

	@Test
	public void rankLimitsFromComposeRankTable() throws Exception {
		Project p = project("rank");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		CharLimits.Evaluation one = p.rankCheck(1, null, null);
		assertEquals("1", one.rank());
		assertEquals("song_excluded", one.songRule());
		assertEquals(Integer.valueOf(1600), one.parts().get("melody").limit());
		assertEquals(Integer.valueOf(1200), one.parts().get("chord1").limit());
		assertEquals(Integer.valueOf(900), one.parts().get("chord2").limit());
		assertNull(one.parts().get("song").limit());
		CharLimits.Evaluation f = p.rankCheck(1, "F", "song_included");
		assertEquals(Integer.valueOf(400), f.parts().get("melody").limit());
		assertEquals(Integer.valueOf(200), f.parts().get("chord1").limit());
		assertEquals(Integer.valueOf(100), f.parts().get("chord2").limit());
		assertEquals(Integer.valueOf(400), f.parts().get("song").limit());
		assertEquals("F", p.rankCheck(1, "RANK_F", null).rank());
		assertEquals(Integer.valueOf(400 + 133), p.rankCheck(1, "f", "song_excluded").parts().get("melody").limit());
		CharLimits.Evaluation prac = p.rankCheck(1, "Practice", null);
		assertEquals("RANK_t", prac.rankKey());
		assertEquals(Integer.valueOf(200 + 66), prac.parts().get("melody").limit());
		assertEquals(16, CharLimits.ranks().size());
		assertEquals("1", CharLimits.ranks().get(15).name());
		expectBad(() -> p.rankCheck(1, "Z", null));
		expectBad(() -> p.rankCheck(1, "1", "song_whatever"));
		// over_by: 연습 랭크 멜로디 한도보다 길게
		List<NoteSpec> busy = new ArrayList<>();
		String[] pitches = { "C4", "D#5", "F#3", "A5" };
		for (int i = 0; i < 80; i++) {
			busy.add(NoteSpec.at(1000 * 6 + i * 24L, 24, pitches[i % 4], 3 + (i % 11)));
		}
		p.putNotes(1, "melody", busy, "strict", false);
		CharLimits.Evaluation pr = p.rankCheck(1, "Practice", null);
		int chars = p.mml(1).get(0).chars().get("melody");
		assertEquals(chars, pr.parts().get("melody").chars());
		assertEquals(chars - 266, pr.parts().get("melody").overBy());
		assertEquals(List.of("melody"), pr.over());
		assertFalse(pr.fits());
		// 예전 두 기준은 그대로
		assertEquals(1600, p.statsOf(1).limits().songExcluded().melody());
	}

	// ------------------------------------------------------------------ D1 경고

	@Test
	public void unisonSameStartAndRetrigger() throws Exception {
		Project p = project("unison");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(384, 192, "E4"), NoteSpec.at(768, 96, "G4")),
				"strict", false);
		EditResult r = p.putNotes(1, "chord1",
				List.of(NoteSpec.at(0, 48, "C4"), NoteSpec.at(480, 96, "E4"), NoteSpec.at(864, 96, "G4"), NoteSpec.at(96, 96, "D4")),
				"strict", false);
		ScoreChecks.UnisonClash u = p.unisonClash(1);
		assertEquals("pitched", u.kind());
		assertEquals(2, u.count()); // 0틱 C4 같은 시작, 2:2:0 E4 다시 치기. 3마디 G4는 멜로디 끝(864)에서 시작 = 겹치지 않음
		assertEquals(1, u.sameStart());
		assertEquals(1, u.retrigger());
		ScoreChecks.Clash c0 = u.examples().get(0);
		assertEquals("same_start", c0.type());
		assertEquals("1:1:0", c0.pos());
		assertEquals(60, c0.pitch());
		assertEquals(List.of("melody", "chord1"), c0.parts());
		ScoreChecks.Clash c1 = u.examples().get(1);
		assertEquals("retrigger", c1.type());
		assertEquals("2:2:0", c1.pos());
		assertEquals("E4", c1.name());
		assertTrue(r.warnings().toString(), r.warnings().stream().anyMatch(w -> w.startsWith("unison_clash:")));
		List<Map<String, Object>> w = p.trackWarnings(1);
		assertEquals(1, w.size());
		assertEquals("unison_clash", w.get(0).get("code"));
		assertEquals(2, w.get(0).get("count"));

		// 앞 음이 뒤 파트에 있는 다시 치기: chord2 긴 음 안에서 멜로디가 같은 음을 시작
		p.putNotes(1, "chord2", List.of(NoteSpec.at(1536, 192, "A4")), "strict", false);
		p.putNotes(1, "melody", List.of(NoteSpec.at(1584, 48, "A4")), "strict", false);
		u = p.unisonClash(1);
		assertEquals(3, u.count());
		ScoreChecks.Clash c2 = u.examples().get(2);
		assertEquals(List.of("chord2", "melody"), c2.parts());
		assertEquals("5:1:48", c2.pos());
	}

	@Test
	public void unisonDrumsFlaggedAndSongWithOwnInstrumentSkipped() throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("unison-drum"));
		Project p = e.newProject("d", "", "4/4", 120);
		p.addTrack("drum", 27, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4")), "strict", false);
		p.putNotes(1, "chord1", List.of(NoteSpec.at(0, 48, "C4")), "strict", false);
		ScoreChecks.UnisonClash u = p.unisonClash(1);
		assertEquals("drum", u.kind());
		assertEquals(1, u.count());
		assertEquals("drum", p.trackWarnings(1).get(0).get("kind"));

		p.addTrack("lute+chorus", 0, 110, null, null); // 노래 악기(여성 코러스)가 따로 있음 — MabiIcco는 코러스만 받는다
		p.putNotes(2, "melody", List.of(NoteSpec.at(0, 96, "E4")), "strict", false);
		EditResult r = p.putNotes(2, "song", List.of(NoteSpec.at(0, 96, "E4")), "strict", false);
		assertEquals(0, p.unisonClash(2).count());
		assertFalse(r.warnings().toString(), r.warnings().stream().anyMatch(w -> w.startsWith("unison_clash")));
		assertTrue(p.trackWarnings(2).isEmpty());
	}

	@Test
	public void tempoMidNoteAndEndDiffers() throws Exception {
		Project p = project("tempo");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 192, "C4"), NoteSpec.at(192, 192, "D4")), "strict", false);
		assertTrue(p.trackWarnings(1).isEmpty());
		assertTrue(p.scoreWarnings().isEmpty());
		List<MMLTempoEvent> tl = p.score().getTempoEventList();
		new MMLTempoEvent(100, 0).appendToListElement(tl);
		new MMLTempoEvent(90, 96).appendToListElement(tl); // 0~192 음 한가운데
		new MMLTempoEvent(80, 192).appendToListElement(tl); // 음 경계 = 걸리지 않음
		p.generate();
		List<Map<String, Object>> w = p.trackWarnings(1);
		assertEquals(w.toString(), 1, w.size());
		assertEquals("tempo_mid_note", w.get(0).get("code"));
		assertEquals(1, w.get(0).get("count"));
		@SuppressWarnings("unchecked")
		List<ScoreChecks.TempoMidNote> items = (List<ScoreChecks.TempoMidNote>) w.get(0).get("items");
		assertEquals(96, items.get(0).tick());
		assertEquals("1:2:0", items.get(0).pos());
		assertEquals(90, items.get(0).bpm());
		assertEquals("melody", items.get(0).part());
		assertEquals("C4", items.get(0).note().name());
		assertEquals(Integer.valueOf(60), items.get(0).note().soundingPitch()); // 류트는 적은 대로
		assertEquals("C4", items.get(0).note().soundingName());
		assertEquals(Boolean.FALSE, items.get(0).note().silent());
		List<Map<String, Object>> sw = p.scoreWarnings();
		assertEquals(sw.toString(), 1, sw.size());
		assertEquals("tempo_end_differs", sw.get(0).get("code"));
		assertEquals(100, sw.get(0).get("first_bpm"));
		assertEquals(80, sw.get(0).get("last_bpm"));
		// 트랙이 둘이어도 tempo_end_differs는 악보 전체에 한 번(트랙 warnings에는 없음)
		p.addTrack("t2", 0, null, null, null);
		p.putNotes(2, "melody", List.of(NoteSpec.at(0, 48, "C4")), "strict", false);
		assertTrue(p.trackWarnings(2).isEmpty());
		assertEquals(1, p.scoreWarnings().size());
		// 마지막 템포를 처음과 같게 하면 tempo_end_differs는 사라진다
		new MMLTempoEvent(100, 192).appendToListElement(tl);
		p.generate();
		assertTrue(p.scoreWarnings().isEmpty());
		w = p.trackWarnings(1);
		assertEquals(w.toString(), 1, w.size());
		assertEquals("tempo_mid_note", w.get(0).get("code"));
	}

	// ------------------------------------------------------------------ D2 연산

	@Test
	public void velocityOps() throws Exception {
		Project p = project("vel");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4", 14), NoteSpec.at(48, 48, "D4", 2), NoteSpec.at(96, 48, "E4", 8)),
				"strict", false);
		TransformResult r = tr(p, "melody", "velocity_offset", Map.of("delta", 3));
		assertArrayEquals(new int[] { 15, 5, 11 }, vels(notes(p, 1, "melody")));
		assertEquals(3, r.notesAffected());
		tr(p, "melody", "velocity_offset", Map.of("delta", -6));
		assertArrayEquals(new int[] { 9, 0, 5 }, vels(notes(p, 1, "melody")));

		Project q = project("quant");
		q.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4", 8), NoteSpec.at(48, 48, "C4", 7), NoteSpec.at(96, 48, "C4", 15),
				NoteSpec.at(144, 48, "C4", 14), NoteSpec.at(192, 48, "C4", 0)), "strict", false);
		r = tr(q, "melody", "velocity_quantize", Map.of("step", 3));
		assertArrayEquals(new int[] { 9, 6, 15, 15, 0 }, vels(notes(q, 1, "melody")));
		assertEquals(3, r.notesAffected());
		tr(q, "melody", "velocity_quantize", Map.of("step", 4));
		// 9 → 8, 6 → 4·8 사이 같은 거리라 낮은 4, 15 → 16 → 15로 자름
		assertArrayEquals(new int[] { 8, 4, 15, 15, 0 }, vels(notes(q, 1, "melody")));
		assertEquals(8, TransformOp.quantize(8, 4));
		assertEquals(9, TransformOp.quantize(8, 3));
		assertEquals(12, TransformOp.quantize(14, 4));

		Project one = project("quant-one");
		one.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4", 8), NoteSpec.at(48, 48, "D4", 8)), "strict", false);
		r = tr(one, "melody", "velocity_quantize", Map.of("step", 3));
		assertEquals("세기가 하나뿐이면 그대로", 0, r.notesAffected());
		assertArrayEquals(new int[] { 8, 8 }, vels(notes(one, 1, "melody")));
		assertEquals(Boolean.TRUE, r.params().get("keep_if_single_level"));
		r = tr(one, "melody", "velocity_quantize", Map.of("step", 3, "keep_if_single_level", false));
		assertArrayEquals(new int[] { 9, 9 }, vels(notes(one, 1, "melody")));
		expectBad(() -> TransformOp.of("velocity_quantize", Map.of("step", 3, "keep_if_single_level", "maybe")));
		assertEquals(Integer.valueOf(1), r.fromMeasure()); // 기본 범위를 채워 돌려준다
		assertEquals(Integer.valueOf(1), r.toMeasure());

		Project m = project("minchange");
		m.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4", 8), NoteSpec.at(48, 48, "C4", 9), NoteSpec.at(96, 48, "C4", 8),
				NoteSpec.at(144, 48, "C4", 10), NoteSpec.at(192, 48, "C4", 11)), "strict", false);
		int before = m.mml(1).get(0).chars().get("melody");
		r = tr(m, "melody", "velocity_min_change", Map.of("delta", 2));
		assertArrayEquals(new int[] { 8, 8, 8, 10, 10 }, vels(notes(m, 1, "melody")));
		assertEquals(2, r.notesAffected());
		assertTrue(r.charsAfter().get("melody") < before);
		assertEquals(before, r.charsBefore().get("melody").intValue());
		assertEquals(m.mml(1).get(0).chars(), r.charsAfter());
		// delta 1은 아무것도 바꾸지 않는다
		assertEquals(0, tr(m, "melody", "velocity_min_change", Map.of("delta", 1)).notesAffected());

		Project d = project("drop");
		d.putNotes(1, "melody", List.of(NoteSpec.at(0, 48, "C4", 2), NoteSpec.at(48, 48, "D4", 3), NoteSpec.at(96, 48, "E4", 4)),
				"strict", false);
		r = tr(d, "melody", "drop_below_vel", Map.of("v", 3));
		assertEquals(2, r.notesAffected());
		assertEquals(1, notes(d, 1, "melody").size());
		assertEquals(4, notes(d, 1, "melody").get(0).getVelocity());
	}

	@Test
	public void lengthScale() throws Exception {
		Project p = project("scale");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(96, 6, "D4"), NoteSpec.at(192, 24, "E4"),
				NoteSpec.at(288, 12, "F4")), "strict", false);
		tr(p, "melody", "length_scale", Map.of("ratio", 0.75));
		assertArrayEquals(new int[] { 72, 6, 18, 12 }, lens(notes(p, 1, "melody"))); // 12×0.75 = 9 → 6틱 반올림 12

		Project q = project("scale-up");
		q.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(120, 48, "D4")), "strict", false);
		tr(q, "melody", "length_scale", Map.of("ratio", 1.5));
		assertArrayEquals(new int[] { 120, 72 }, lens(notes(q, 1, "melody"))); // 144 → 다음 음(120)까지

		Project m = project("scale-min");
		m.putNotes(1, "melody", List.of(NoteSpec.at(0, 24, "C4"), NoteSpec.at(96, 12, "D4"), NoteSpec.at(192, 96, "E4")),
				"strict", false);
		tr(m, "melody", "length_scale", Map.of("ratio", 0.5, "min_len_ticks", 24));
		assertArrayEquals(new int[] { 24, 12, 48 }, lens(notes(m, 1, "melody"))); // min_len 아래로 줄이지 않고, 더 짧던 음은 늘리지 않음
		expectBad(() -> TransformOp.of("length_scale", Map.of("ratio", 0.75, "min_len_ticks", 10)));
		expectBad(() -> TransformOp.of("length_scale", Map.of("ratio", 0.0)));
		expectBad(() -> TransformOp.of("length_scale", Map.of()));
	}

	@Test
	public void minNoteLen() throws Exception {
		Project p = project("minlen-del");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 6, "C4"), NoteSpec.at(96, 12, "D4"), NoteSpec.at(192, 24, "E4")),
				"strict", false);
		TransformResult r = tr(p, "melody", "min_note_len", Map.of("ticks", 12, "mode", "delete"));
		assertEquals(1, r.notesAffected());
		assertArrayEquals(new int[] { 12, 24 }, lens(notes(p, 1, "melody")));

		Project q = project("minlen-ext");
		q.putNotes(1, "melody", List.of(NoteSpec.at(0, 6, "C4"), NoteSpec.at(6, 6, "D4"), NoteSpec.at(96, 6, "E4"),
				NoteSpec.at(192, 48, "F4")), "strict", false);
		r = tr(q, "melody", "min_note_len", Map.of("ticks", 12, "mode", "extend"));
		// 0틱 음은 다음 음(6틱)이 막아 그대로, 6틱 음은 다음 음까지 90틱이라 12로, 96틱 음도 12로
		assertArrayEquals(new int[] { 6, 12, 12, 48 }, lens(notes(q, 1, "melody")));
		assertEquals(2, r.notesAffected());
		expectBad(() -> TransformOp.of("min_note_len", Map.of("ticks", 12, "mode", "shrink")));
		expectBad(() -> TransformOp.of("min_note_len", Map.of("ticks", 12)));
	}

	@Test
	public void lengthGrid() throws Exception {
		Project p = project("grid");
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 30, "C4"), NoteSpec.at(96, 42, "D4"), NoteSpec.at(144, 42, "E4"),
				NoteSpec.at(198, 6, "F4"), NoteSpec.at(204, 66, "G4"), NoteSpec.at(300, 6, "A4")), "strict", false);
		tr(p, "melody", "length_grid", Map.of("ticks", 24));
		// 0+30 → 24; 96+42=138 → 144(다음 144와 같음); 144+42=186 → 192; 198+6 → 216은 다음 204를 넘음, 격자 192 ≤ 시작 → 그대로;
		// 204+66=270 → 264; 300+6=306 → 격자 288 ≤ 시작이라 다음 격자 312(마지막 음)
		assertArrayEquals(new int[] { 24, 48, 48, 6, 60, 12 }, lens(notes(p, 1, "melody")));
		for (int i = 1; i < notes(p, 1, "melody").size(); i++) {
			assertTrue(notes(p, 1, "melody").get(i - 1).getEndTick() <= notes(p, 1, "melody").get(i).getTickOffset());
		}
		expectBad(() -> TransformOp.of("length_grid", Map.of("ticks", 10)));
		expectBad(() -> TransformOp.of("length_grid", Map.of("ticks", 12, "extra", 1)));
	}

	@Test
	public void transformRangePartsSnapshotRestoreAndErrors() throws Exception {
		Project p = project("range");
		BridgeEngine e = engineOf(p);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4", 8), NoteSpec.at(384, 96, "D4", 8), NoteSpec.at(768, 96, "E4", 8)),
				"strict", false);
		p.putNotes(1, "chord1", List.of(NoteSpec.at(384, 96, "G3", 8)), "strict", false);
		byte[] before = p.state();
		TransformResult r = p.transform(1, List.of("melody"), 2, 2, TransformOp.of("velocity_offset", Map.of("delta", 4)));
		assertArrayEquals(new int[] { 8, 12, 8 }, vels(notes(p, 1, "melody")));
		assertArrayEquals(new int[] { 8 }, vels(notes(p, 1, "chord1")));
		assertEquals(1, r.notesAffected());
		assertEquals(List.of("melody"), r.parts());
		assertEquals(Integer.valueOf(2), r.fromMeasure());
		assertEquals(Integer.valueOf(2), r.toMeasure());
		TransformResult all = p.transform(1, null, null, null, TransformOp.of("velocity_offset", Map.of("delta", 0)));
		assertEquals(Integer.valueOf(1), all.fromMeasure());
		assertEquals(Integer.valueOf(3), all.toMeasure()); // 음이 시작하는 마지막 마디
		e.restore(r.snapshotId());
		assertTrue(r.snapshotId() >= 1);
		assertTrue(p.dirty());
		e.restore(r.snapshotId());
		assertArrayEquals(before, p.state());
		expectBad(() -> TransformOp.of("nope", Map.of()));
		expectBad(() -> TransformOp.of("velocity_offset", Map.of("delta", 16)));
		expectBad(() -> TransformOp.of("velocity_offset", Map.of("delta", 1.5)));
		expectBad(() -> TransformOp.of("velocity_quantize", Map.of("step", 1)));
		expectBad(() -> TransformOp.of("drop_below_vel", Map.of("v", 15)));
		expectBad(() -> TransformOp.of("velocity_offset", Map.of("delta", "2")));
		expectBad(() -> TransformOp.of("length_scale", Map.of("ratio", "0.75")));
		expectBad(() -> TransformOp.of("velocity_quantize", Map.of("step", 3, "keep_if_single_level", "true")));
		expectBad(() -> TransformOp.of("min_note_len", Map.of("ticks", 12, "mode", 1)));
		expectBad(() -> TransformOp.of("length_grid", Map.of("ticks", true)));
		expectBad(() -> p.transform(1, List.of(), null, null, TransformOp.of("velocity_offset", Map.of("delta", 1))));
		expectBad(() -> p.transform(1, null, 3, 2, TransformOp.of("velocity_offset", Map.of("delta", 1))));
		int snaps = e.snapshots().size();
		expectBad(() -> p.transform(1, List.of("bass"), null, null, TransformOp.of("velocity_offset", Map.of("delta", 1))));
		assertEquals("검증 실패에서는 스냅샷을 남기지 않음", snaps, e.snapshots().size());
	}

	private static BridgeEngine engineOf(Project p) throws Exception {
		java.lang.reflect.Field f = Project.class.getDeclaredField("engine");
		f.setAccessible(true);
		return (BridgeEngine) f.get(p);
	}

	// ------------------------------------------------------------------ 사본의 글자 수 = 원래 글자 수

	// ------------------------------------------------------------------ D3

	/** 세기를 8·9로 번갈아 바꾼 8분음표: 음마다 v 명령이 붙어 글자 수가 커진다. */
	private static List<NoteSpec> alternatingVel(int count) {
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			l.add(NoteSpec.at(i * 48L, 48, "C4", 8 + (i % 2)));
		}
		return l;
	}

	@Test
	public void fitSuggestPlanFitsAndMatchesTransform() throws Exception {
		Project p = project("fit");
		p.putNotes(1, "melody", alternatingVel(200), "strict", false);
		CharLimits.Evaluation ev = p.rankCheck(1, "F", null);
		assertTrue("F 랭크 멜로디(533)를 넘어야 이 시험이 성립: " + ev.parts().get("melody").chars(), !ev.fits());
		byte[] state = p.state();
		FitSuggester.FitSuggestion s = p.fitSuggest(1, "F", null, null);
		assertArrayEquals("제안은 악보를 바꾸지 않음", state, p.state());
		assertEquals(List.of("melody"), s.over());
		FitSuggester.PartFit pf = s.parts().get(0);
		assertEquals(ev.parts().get("melody").chars(), pf.chars());
		assertEquals(FitSuggester.LADDER.size(), pf.candidates().size());
		FitSuggester.Candidate c0 = pf.candidates().get(0);
		assertEquals("velocity_min_change", c0.op());
		assertEquals(100, c0.notesAffected());
		assertTrue(c0.fitsAlone());
		assertEquals(3, c0.examples().size());
		assertEquals(c0.charsBefore() - c0.charsAfter(), c0.saved());
		assertTrue(s.plan().fits());
		assertEquals(1, s.plan().steps().size());
		assertEquals(0, s.plan().remainingOverBy());
		assertEquals(0, s.plan().overTotal());
		assertTrue(s.plan().overByParts().isEmpty());
		assertEquals(Map.of(), s.plan().steps().get(0).overByParts());
		assertEquals(1, s.plan().calls().size());
		// 계획대로 score_transform을 하면 글자 수가 그대로 맞는다
		TransformResult r = p.transform(1, List.of("melody"), null, null, TransformOp.of("velocity_min_change", Map.of("delta", 2)));
		assertEquals(c0.charsAfter(), r.charsAfter().get("melody").intValue());
		assertEquals(s.plan().chars().get("melody"), r.charsAfter().get("melody"));
		assertTrue(p.rankCheck(1, "F", null).fits());
	}

	@Test
	public void fitSuggestReportsRemainingWhenNothingFits() throws Exception {
		Project p = project("fit-no");
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			l.add(NoteSpec.at(i * 96L, 96, (i % 2 == 0) ? "C4" : "C5"));
		}
		p.putNotes(1, "melody", l, "strict", false);
		List<NoteSpec> c1 = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			c1.add(NoteSpec.at(i * 96L, 96, (i % 2 == 0) ? "C3" : "C4"));
		}
		p.putNotes(1, "chord1", c1, "strict", false);
		CharLimits.Evaluation ev = p.rankCheck(1, "Practice", null);
		FitSuggester.FitSuggestion s = p.fitSuggest(1, "Practice", null, null);
		assertEquals(List.of("melody", "chord1"), s.over());
		Map<String, Integer> obp = s.plan().overByParts();
		assertEquals(List.of("melody", "chord1"), new ArrayList<>(obp.keySet()));
		assertEquals(Integer.valueOf(ev.parts().get("melody").overBy()), obp.get("melody"));
		assertEquals(Integer.valueOf(ev.parts().get("chord1").overBy()), obp.get("chord1"));
		assertEquals(obp.get("melody") + obp.get("chord1"), s.plan().overTotal());
		assertEquals(s.plan().overTotal(), s.plan().remainingOverBy());
		String want = "한도 초과: melody " + obp.get("melody") + "자, chord1 " + obp.get("chord1") + "자";
		assertTrue(s.text(), s.text().contains(want));
		assertTrue(s.plan().note(), s.plan().note().contains(want));
		assertFalse(s.plan().fits());
		assertTrue(s.plan().steps().isEmpty());
		assertTrue(s.plan().calls().isEmpty());
		assertEquals(FitSuggester.LADDER.size(), s.plan().skipped().size());
		assertTrue(s.plan().remainingOverBy() > 0);
		assertTrue(s.text(), s.plan().note().startsWith("사다리에서 글자를 줄이는 단계가 없어"));
		// 한도 안이면 제안 없음
		FitSuggester.FitSuggestion ok = p.fitSuggest(1, "1", null, null);
		assertTrue(ok.plan().fits());
		assertTrue(ok.parts().isEmpty());
	}

	/** 모든 음이 v8인 파트: velocity_quantize는 계획에 들어가지 않고, 계획의 글자 수는 단계마다 줄기만 한다. */
	@Test
	public void fitPlanKeepsOnlyReducingSteps() throws Exception {
		Project p = project("fit-v8");
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < 160; i++) {
			// 옥타브를 오르내리고 길이에 붙임줄(36틱 = 16.8 → 점음표·붙임줄)이 생기게
			l.add(NoteSpec.at(i * 48L, (i % 3 == 0) ? 42 : 48, (i % 2 == 0) ? "C4" : "C5", 8));
		}
		p.putNotes(1, "melody", l, "strict", false);
		CharLimits.Evaluation ev = p.rankCheck(1, "Practice", null);
		assertFalse("연습 랭크를 넘어야 이 시험이 성립: " + ev.parts().get("melody").chars(), ev.fits());
		int before = ev.parts().get("melody").chars();
		FitSuggester.FitSuggestion s = p.fitSuggest(1, "Practice", null, null);
		int prev = before;
		for (FitSuggester.PlanStep st : s.plan().steps()) {
			assertFalse("velocity_quantize가 계획에 들어감: " + s.plan(), st.op().equals("velocity_quantize"));
			assertTrue(st.notesAffected() > 0);
			assertTrue("단계마다 글자 수가 줄어야 함: " + prev + " → " + st.overPartsChars(), st.overPartsChars() < prev);
			prev = st.overPartsChars();
			assertEquals(st.overByParts().values().stream().mapToInt(Integer::intValue).sum(), st.overTotal());
		}
		assertTrue(s.plan().chars().get("melody") <= before);
		assertEquals(s.plan().steps().size(), s.plan().calls().size());
		assertTrue(s.plan().skipped().stream().anyMatch(k -> k.op().equals("velocity_quantize")));
		for (FitSuggester.Skipped k : s.plan().skipped()) {
			assertTrue(k.toString(), k.notesAffected() == 0 || k.overPartsCharsAfter() >= k.overPartsCharsBefore());
		}
		// 계획을 그대로 보내면 같은 글자 수
		for (Map<String, Object> c : s.plan().calls()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> params = (Map<String, Object>) c.get("params");
			@SuppressWarnings("unchecked")
			List<String> parts = (List<String>) c.get("parts");
			p.transform(1, parts, null, null, TransformOp.of((String) c.get("op"), params));
		}
		assertEquals(s.plan().chars().get("melody").intValue(), p.rankCheck(1, "Practice", null).parts().get("melody").chars());
	}

	// ------------------------------------------------------------------ D4

	private static final Pattern TEMPO = Pattern.compile("t(\\d+)");

	private static Integer firstTempo(String mml) {
		Matcher m = TEMPO.matcher(mml);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	/** 마디마다 8분음표 8개(옥타브를 오르내려 글자 수가 많음). */
	private static List<NoteSpec> denseMelody(int measures) {
		List<NoteSpec> l = new ArrayList<>();
		for (int m = 0; m < measures; m++) {
			for (int i = 0; i < 8; i++) {
				l.add(NoteSpec.at(m * 384L + i * 48L, 48, (i % 2 == 0) ? "C4" : "C5", 8));
			}
		}
		return l;
	}

	@Test
	public void splitSheetsPrefersCleanCutsAndSetsTempo() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("split");
		BridgeEngine e = EngineTestSupport.engine(tmp);
		Project p = e.newProject("split", "", "4/4", 100);
		p.addTrack("t", 0, null, null, null);
		p.putNotes(1, "melody", denseMelody(60), "strict", false);
		// 화음2: 홀수 마디 처음에서 두 마디 길이 → 홀수 마디 끝 경계는 걸침, 짝수 마디 끝은 깨끗함
		List<NoteSpec> pads = new ArrayList<>();
		for (int m = 0; m < 60; m += 2) {
			pads.add(NoteSpec.at(m * 384L, 768, "G3", 8));
		}
		p.putNotes(1, "chord2", pads, "strict", false);
		new MMLTempoEvent(80, 24 * 384).appendToListElement(p.score().getTempoEventList()); // 25마디부터 80
		p.generate();
		byte[] state = p.state();
		SheetSplitter.SplitResult r = p.splitSheets(1, "Practice", null, 8, true, null, null, false);
		assertArrayEquals("장 나누기는 악보를 바꾸지 않음", state, p.state());
		assertTrue(r.text(), r.sheetCount() >= 3);
		assertTrue(r.complete());
		assertEquals(60, r.lastMeasure());
		int expectFrom = 1;
		for (SheetSplitter.Sheet s : r.sheets()) {
			assertTrue("장 " + s.index() + " 한도 안: " + s.check(), s.fits());
			assertEquals(expectFrom, s.fromMeasure());
			expectFrom = s.toMeasure() + 1;
			if (s.toMeasure() < 60) {
				assertFalse("깨끗한 경계가 있으면 걸치지 않게 자름: 장 " + s.index() + " ~" + s.toMeasure(), s.crossingCut());
				assertEquals(0, s.toMeasure() % 2);
			}
			int bpm = (s.fromMeasure() >= 25) ? 80 : 100;
			assertEquals(bpm, s.startBpm());
			String melody = Project.splitMml(s.mml())[0];
			assertEquals("장 " + s.index() + " MML 처음 템포: " + melody, Integer.valueOf(bpm), firstTempo(melody));
			assertTrue(melody, melody.startsWith("t" + bpm));
		}
		assertEquals(61, expectFrom);

		// 저장: 장마다 새 .mmi, 다시 열면 0틱에서 시작하고 템포가 맞음, 두 번째는 FILE_EXISTS
		Path out = tmp.resolve("sheets");
		SheetSplitter.SplitResult saved = p.splitSheets(1, "Practice", null, 8, true, out, "split", false);
		int total = 0;
		for (SheetSplitter.Sheet s : saved.sheets()) {
			assertNotNull(s.path());
			assertTrue(Files.isRegularFile(Path.of(s.path())));
			assertTrue(s.path().endsWith("split_장" + s.index() + ".mmi"));
			BridgeEngine e2 = EngineTestSupport.engine(EngineTestSupport.tmpDir("split-open"));
			Project q = e2.open(Path.of(s.path()));
			assertEquals(s.mml(), q.mml(1).get(0).mml());
			MMLTrack t = q.score().getTrack(0);
			assertEquals(0, t.getMMLEventAtIndex(0).getMMLNoteEventList().get(0).getTickOffset());
			total += t.getMMLEventAtIndex(0).getMMLNoteEventList().size();
		}
		assertEquals(480, total);
		try {
			p.splitSheets(1, "Practice", null, 8, true, out, "split", false);
			fail("FILE_EXISTS여야 함");
		} catch (EngineException ex) {
			assertEquals(EngineException.FILE_EXISTS, ex.code());
		}
		p.splitSheets(1, "Practice", null, 8, true, out, "split", true);

		// max_sheets가 모자라면 complete false
		SheetSplitter.SplitResult two = p.splitSheets(1, "Practice", null, 2, true, null, null, false);
		assertFalse(two.complete());
		assertEquals(2, two.sheetCount());
		assertNotNull(two.remainingFromMeasure());
	}

	@Test
	public void splitSheetsReportsCrossingCutAndCarries() throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("split-cross"));
		Project p = e.newProject("cross", "", "4/4", 120);
		p.addTrack("t", 0, null, null, null);
		p.putNotes(1, "melody", denseMelody(50), "strict", false);
		// 화음1: 모든 마디 경계에 걸치는 음(3박째에서 한 마디 길이)
		List<NoteSpec> tied = new ArrayList<>();
		for (int m = 0; m < 50; m++) {
			tied.add(NoteSpec.at(m * 384L + 192, 384, "E3", 8));
		}
		p.putNotes(1, "chord1", tied, "strict", false);
		SheetSplitter.SplitResult r = p.splitSheets(1, "Practice", null, 8, true, null, null, false);
		assertTrue(r.text(), r.sheetCount() >= 3);
		for (int i = 0; i < r.sheets().size(); i++) {
			SheetSplitter.Sheet s = r.sheets().get(i);
			assertTrue("장 " + s.index() + " 한도 안: " + s.check(), s.fits());
			assertNull(firstTempo(s.mml()));
			if (i < r.sheets().size() - 1) {
				assertTrue(s.crossingCut());
				assertEquals(1, s.cutNotes().size());
				SheetSplitter.CutNote c = s.cutNotes().get(0);
				assertEquals("chord1", c.part());
				assertEquals(192, c.keptLen());
				assertEquals(192, c.carriedLen());
				assertEquals(c.len(), c.keptLen() + c.carriedLen());
				assertEquals(s.toTick() - 192, c.tick());
			}
		}
		// 다음 장 화음1은 0틱에서 남은 길이(2분음표)로 다시 친다
		String chord1 = Project.splitMml(r.sheets().get(1).mml())[1];
		assertTrue("쉼표 없이 E3로 시작: " + chord1, chord1.replaceFirst("^[vlot<>0-9.]*", "").startsWith("e"));
		assertFalse(r.sheets().get(1).cutNotes().isEmpty());
		SheetSplitter.SplitResult noCarry = p.splitSheets(1, "Practice", null, 8, false, null, null, false);
		assertEquals(0, noCarry.sheets().get(0).cutNotes().get(0).carriedLen());
	}
}
