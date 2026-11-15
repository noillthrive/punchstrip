package mabibridge.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.BeforeClass;
import org.junit.Test;

import mabibridge.compare.Comparer.Options;
import mabibridge.compare.CompareReport.MelodyCounts;
import mabibridge.compare.CompareReport.MelodyTrack;
import mabibridge.compare.CompareReport.SoundingWarning;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * 비교: pitch_mode(sounding/written), measure_offset(정수·auto), octave_tolerant, 무음·세트 섞임 경고,
 * 고정음 악기 건너뛰기, 쓰기 요약의 마디 차, 매핑 제안의 octave_shift.
 *
 * <p>합성 참조(PPQ 480, 4/4, 120 BPM). 트랙 1: 1마디 C4 D4 E4 F4, 2마디 G4 A4 B4 C5(4분음표). 트랙 2: 1마디 C3(온음표).</p>
 */
public class SoundingCompareTest {

	private static BridgeEngine engine;
	private static ReferenceMidi ref;

	private static final int[] MEL = { 60, 62, 64, 65, 67, 69, 71, 72 };

	@BeforeClass
	public static void setUpClass() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "sounding-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));
		t1.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		for (int i = 0; i < MEL.length; i++) {
			note(t1, 0, MEL[i], i * 480L, 480);
		}
		Track t2 = seq.createTrack();
		note(t2, 1, 48, 0, 1920);
		Path f = tmp.resolve("sounding.mid");
		MidiSystem.write(seq, 1, f.toFile());
		ref = ReferenceMidi.load(f);
	}

	private static void note(Track t, int ch, int pitch, long tick, long len) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, 100), tick));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick + len));
	}

	/** 트랙 1에 참조 트랙 1의 멜로디를 (적은 음 = 참조 음 + transpose)로, startMeasure 마디부터 넣는다. */
	private static Project score(int program, int transpose, int startMeasure) {
		Project p = engine.newProject("s", "", "4/4", 120);
		p.addTrack("t1", program, null, null, null);
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < MEL.length; i++) {
			l.add(NoteSpec.at((startMeasure - 1) * 384L + i * 96L, 96, MEL[i] + transpose));
		}
		p.putNotes(1, "melody", l, "strict", false);
		return p;
	}

	private static final Mapping MAP = Mapping.of(1, List.of(1), List.of(1));

	private static CompareReport cmp(Project p, Mapping m, int from, int to, Options o) {
		return Comparer.compare(p, ref, m, from, to, null, engine.instrumentTable(), false, o);
	}

	private static Options opt(String mode, Integer off, boolean auto, boolean tol) {
		return new Options(mode, off, auto, tol);
	}

	/** 피아노에 원곡 음높이를 그대로 적으면: written은 전부 일치, sounding은 전부 1옥타브 위(wrong, 옥타브만 다름). */
	@Test
	public void writtenVersusSounding() {
		Project p = score(21, 0, 1);
		CompareReport w = cmp(p, MAP, 1, 2, opt("written", null, false, false));
		assertEquals("written", w.pitchMode());
		assertEquals(new MelodyCounts(8, 0, 0, 0, 0), w.summary().melody());

		// 기본(Options.DEFAULTS) = sounding
		CompareReport s = cmp(p, MAP, 1, 2, Options.DEFAULTS);
		assertEquals("sounding", s.pitchMode());
		assertEquals(new MelodyCounts(0, 8, 0, 0, 0), s.summary().melody());
		MelodyTrack mt = s.melodyTracks().get(0);
		assertEquals(8, mt.wrongOctave());
		assertEquals(Integer.valueOf(12), mt.octaveShiftMain());
		assertEquals(Map.of("+12", 8), mt.octaveShiftCounts());
		CompareReport.MelodyItem it = s.measures().get(0).melody().get(0).items().get(0);
		assertEquals("C5", it.score()); // 비교에 쓴 음 = 들리는 음
		assertEquals("C4", it.scoreWritten()); // 적은 음
		assertTrue(s.text(), s.text().contains("들리는 음"));
		assertTrue(s.text(), s.text().contains("1옥타브 높게 울림"));
		// 화음(음이름)은 옥타브와 무관해 같다
		assertEquals(w.summary().chordMissingBeats(), s.summary().chordMissingBeats());

		// 1옥타브 낮춰 적으면 들리는 음이 원곡과 같다
		Project q = score(21, -12, 1);
		assertEquals(new MelodyCounts(8, 0, 0, 0, 0), cmp(q, MAP, 1, 2, Options.DEFAULTS).summary().melody());
		assertEquals(0, cmp(q, MAP, 1, 2, opt("written", null, false, false)).summary().melody().matched());
		// 옛 시그니처는 동작(written)
		assertEquals(8, Comparer.compare(p, ref, MAP, 1, 2, null, engine.instrumentTable(), false).summary().melody()
				.matched());
	}

	/** octave_tolerant: 옥타브만 다른 음을 짝으로 인정하고 octave_shifted로 따로 센다. 틀린 음은 그대로 wrong. */
	@Test
	public void octaveTolerant() {
		Project p = score(22, 0, 1); // 바이올린 +2옥타브
		CompareReport r = cmp(p, MAP, 1, 2, opt(null, null, false, true));
		assertTrue(r.octaveTolerant());
		assertEquals(new MelodyCounts(8, 0, 0, 0, 0, 8), r.summary().melody());
		assertEquals(Integer.valueOf(24), r.melodyTracks().get(0).octaveShiftMain());
		assertTrue(r.text(), r.text().contains("옥타브만 달라 일치로 셈"));
		// 반음 틀린 음은 tolerant여도 wrong
		Project q = engine.newProject("s", "", "4/4", 120);
		q.addTrack("t1", 0, null, null, null);
		q.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 72), NoteSpec.at(96, 96, 63)), "strict", false);
		CompareReport rq = cmp(q, MAP, 1, 1, opt(null, null, false, true));
		assertEquals(1, rq.summary().melody().matched());
		assertEquals(1, rq.summary().melody().octaveShifted());
		assertEquals(1, rq.summary().melody().wrong());
		// tolerant가 아니면 둘 다 wrong
		assertEquals(2, cmp(q, MAP, 1, 1, Options.DEFAULTS).summary().melody().wrong());
	}

	/** measure_offset 정수: 악보 마디 = 참조 마디 + 2. from·to는 참조 마디. */
	@Test
	public void measureOffsetInteger() {
		Project p = score(0, 0, 3); // 원곡 1~2마디를 악보 3~4마디에
		CompareReport z = cmp(p, MAP, 1, 2, opt(null, 0, false, false));
		assertEquals(0, z.summary().melody().matched());
		assertEquals(8, z.summary().melody().missing());

		CompareReport r = cmp(p, MAP, 1, 2, opt(null, 2, false, false));
		assertEquals(2, r.measureOffsetUsed());
		assertFalse(r.measureOffsetAuto());
		assertEquals(List.of(1, 2), r.range());
		assertEquals(List.of(3, 4), r.scoreRange());
		assertEquals(new MelodyCounts(8, 0, 0, 0, 0), r.summary().melody());
		assertEquals(1, r.measures().get(0).measure());
		assertEquals(3, r.measures().get(0).scoreMeasure());
		assertEquals(0, r.summary().chordMissingBeats());
		assertTrue(r.text(), r.text().contains("참조 1~2마디(악보 3~4마디)"));
		assertEquals(2, r.melodyTracks().get(0).measureOffset());

		// 악보 쪽 시작 마디가 1보다 작으면 거부
		try {
			cmp(p, MAP, 1, 2, opt(null, -1, false, false));
			throw new AssertionError("예외가 나야 함");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("1보다 작음"));
		}
	}

	/** measure_offset auto: −4~+4 중 맞은 음이 가장 많은 차. 매핑이 없어도 찾는다. */
	@Test
	public void measureOffsetAuto() {
		Project p = score(21, -12, 3); // 피아노, 들리는 음 = 원곡, 2마디 늦게
		CompareReport r = cmp(p, MAP, 1, 2, opt(null, null, true, false));
		assertTrue(r.measureOffsetAuto());
		assertEquals(2, r.measureOffsetUsed());
		assertNotNull(r.measureOffsetScores());
		int best = r.measureOffsetScores().get("2");
		for (Map.Entry<String, Integer> e : r.measureOffsetScores().entrySet()) {
			assertTrue(e.toString(), e.getValue() <= best);
		}
		assertFalse(r.measureOffsetScores().containsKey("-1")); // 참조 1마디 − 1 < 1
		assertEquals(8, r.summary().melody().matched());
		assertTrue(r.text(), r.text().contains("auto로 찾음"));
		// 매핑 없이(제안 방식으로 점수)
		CompareReport n = cmp(p, Mapping.empty(), 1, 2, opt(null, null, true, false));
		assertEquals(2, n.measureOffsetUsed());
	}

	/** 베이스 무음 구간(56~61)에 적힌 음: 경고 silent_note, sounding 비교에서 빼고 silent_notes로 센다. 두 세트 섞임: timbre_mix. */
	@Test
	public void silentNoteAndTimbreMix() {
		Project p = engine.newProject("s", "", "4/4", 120);
		p.addTrack("bass", 29, null, null, null);
		// 원곡 C4 D4 E4 F4(들리는 음). default 세트로 C4 = 적은 C3(48), D4 = 적은 50. E4는 무음 구간(적은 59 = B3)으로 잘못 적음.
		// F4는 alt 세트로 적은 101(F7, −3옥타브 → F4)
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 48), NoteSpec.at(96, 96, 50), NoteSpec.at(192, 96, 59),
				NoteSpec.at(288, 96, 101)), "strict", false);
		CompareReport r = cmp(p, MAP, 1, 1, Options.DEFAULTS);
		assertEquals(1, r.summary().silentNotes());
		assertEquals(1, r.melodyTracks().get(0).silentNotes());
		MelodyCounts c = r.summary().melody();
		assertEquals(3, c.matched());
		assertEquals(1, c.missing()); // 무음 음은 짝이 되지 않는다
		SoundingWarning silent = null;
		SoundingWarning mix = null;
		for (SoundingWarning w : r.soundingWarnings()) {
			if ("silent_note".equals(w.code())) {
				silent = w;
			} else if ("timbre_mix".equals(w.code())) {
				mix = w;
			}
		}
		assertNotNull(silent);
		assertEquals(1, silent.count());
		assertEquals(List.of("1:3:0 B3"), silent.positions());
		assertNotNull(mix);
		assertEquals(Map.of("default", 2, "alt", 1), mix.timbres());
		assertEquals(1, mix.count());
		assertTrue(r.text(), r.text().contains("무음 구간"));
		// written이어도 경고는 나온다(악보 사실). 무음 음을 빼지는 않는다.
		CompareReport w = cmp(p, MAP, 1, 1, opt("written", null, false, false));
		assertEquals(1, w.summary().silentNotes());
		assertEquals(2, w.soundingWarnings().size());
		assertEquals(0, w.melodyTracks().get(0).silentNotes());
	}

	/** 고정음·음높이 없음 악기 트랙은 sounding에서 음높이 비교(멜로디·화음)를 하지 않는다. */
	@Test
	public void fixedInstrumentSkipped() {
		Project p = engine.newProject("s", "", "4/4", 120);
		p.addTrack("bottle", 7, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 60), NoteSpec.at(96, 96, 62)), "strict", false);
		CompareReport r = cmp(p, MAP, 1, 1, Options.DEFAULTS);
		assertTrue(r.melodyTracks().isEmpty());
		assertEquals(1, r.pitchCompareSkipped().size());
		assertEquals("fixed", r.pitchCompareSkipped().get(0).kind());
		assertTrue(String.valueOf(r.notesSkipped()), r.notesSkipped().stream().anyMatch(s -> s.contains("고정음")));
		// 화음 쪽에서도 빠져 악보 음이름이 비어 있다
		assertTrue(r.measures().get(0).chords().get(0).score().isEmpty());
		// written이면 처럼 비교한다
		CompareReport w = cmp(p, MAP, 1, 1, opt("written", null, false, false));
		assertEquals(1, w.melodyTracks().size());
		assertTrue(w.pitchCompareSkipped().isEmpty());
	}

	/** 매핑 항목의 pitch_mode·measure_offset: 호출에서 주지 않으면 항목 값, 주면 호출 값이 이긴다. 쓰기 요약도 항목 값. */
	@Test
	public void entryOptionsAndSummary() {
		Project p = score(0, 0, 3);
		Mapping m = new Mapping(Map.of(1, new Mapping.Entry(List.of(1), List.of(1), "melody", "written", 2)));
		CompareReport r = cmp(p, m, 1, 2, Options.DEFAULTS);
		assertEquals("written", r.pitchMode());
		assertEquals(2, r.measureOffsetUsed());
		assertEquals(8, r.summary().melody().matched());
		CompareReport o = cmp(p, m, 1, 2, opt(null, 0, false, false));
		assertEquals(0, o.measureOffsetUsed());
		assertEquals(0, o.summary().melody().matched());

		CompareSummary s = Comparer.summary(p, ref, m, List.of(3), engine.instrumentTable(), false, Options.DEFAULTS);
		assertFalse(s.skipped());
		assertEquals(List.of(3), s.measures());
		assertEquals(2, s.measureOffsetUsed());
		assertEquals(new MelodyCounts(4, 0, 0, 0, 0), s.summary().melody());
		assertTrue(s.text(), s.text().contains("참조 = 악보 -2"));
		// 바뀐 마디가 참조 1마디보다 앞(악보 2마디)이면 비교할 마디가 없다
		assertTrue(Comparer.summary(p, ref, m, List.of(2), engine.instrumentTable(), false, Options.DEFAULTS).skipped());

		Mapping parsed = Mapping.fromJson("{\"1\": {\"melody_from\": [1], \"pitch_mode\": \"written\", \"measure_offset\": 2}}");
		assertEquals("written", parsed.tracks().get(1).pitchMode());
		assertEquals(Integer.valueOf(2), parsed.tracks().get(1).measureOffset());
		assertNull(Mapping.fromJson("{\"1\": {\"melody_from\": [1]}}").tracks().get(1).pitchMode());
	}

	/** 매핑 제안: octave_shift(악보가 원곡보다 몇 옥타브 높게 울리는지)와 그때의 일치율, measure_offset auto. */
	@Test
	public void suggestOctaveShiftAndOffset() {
		Project p = score(21, 0, 1); // 피아노에 원곡 음 그대로 → 1옥타브 높게 울림
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 2, null, engine.instrumentTable(), Options.DEFAULTS);
		assertEquals("sounding", s.pitchMode());
		MappingSuggestion.Candidate c = s.tracks().get(0).suggestions().get(0);
		assertEquals(1, c.refTrack());
		assertEquals(0, c.matched());
		assertEquals(1, c.octaveShift());
		assertEquals(1.0, c.octaveShiftRatio(), 1e-9);
		assertNotNull(s.tracks().get(0).note()); // 옥타브를 옮겨야만 맞음
		assertEquals("+1옥타브", s.tracks().get(0).sounding());

		MappingSuggestion t = MappingSuggester.suggest(p, ref, 1, 2, null, engine.instrumentTable(),
				opt(null, null, false, true));
		assertEquals(1.0, t.tracks().get(0).suggestions().get(0).ratio(), 1e-9);
		assertEquals(8, t.tracks().get(0).suggestions().get(0).octaveShifted());

		MappingSuggestion w = MappingSuggester.suggest(p, ref, 1, 2, null, engine.instrumentTable(),
				opt("written", null, false, false));
		assertEquals(1.0, w.tracks().get(0).suggestions().get(0).ratio(), 1e-9);
		assertEquals(0, w.tracks().get(0).suggestions().get(0).octaveShift());
		assertEquals("written", w.suggestedMapping().get("1").get("pitch_mode"));

		Project late = score(21, -12, 3);
		MappingSuggestion a = MappingSuggester.suggest(late, ref, 1, 2, null, engine.instrumentTable(),
				opt(null, null, true, false));
		assertEquals(2, a.measureOffsetUsed());
		assertEquals(List.of(3, 4), a.scoreRange());
		MappingSuggestion.Candidate ca = a.tracks().get(0).suggestions().get(0);
		assertEquals(1, ca.refTrack());
		assertEquals(1.0, ca.ratio(), 1e-9);
		assertEquals(0, ca.octaveShift());
		assertEquals(2, a.suggestedMapping().get("1").get("measure_offset"));
	}
}
