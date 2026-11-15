package mabibridge.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mabibridge.compare.CompareReport.ChordBeat;
import mabibridge.compare.CompareReport.Crossing;
import mabibridge.compare.CompareReport.MeasureDetail;
import mabibridge.compare.CompareReport.MelodyCounts;
import mabibridge.compare.CompareReport.MelodyItem;
import mabibridge.compare.CompareReport.MelodyMeasure;
import mabibridge.compare.CompareReport.RangeViolation;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * compare 시험. 합성 참조 MIDI(PPQ 480, 4/4, 120 BPM)를 메모리에서 만들어 임시 파일로 쓴 뒤 읽는다.
 * 참조 트랙 1: C4 D4 E4 F4 4분음표(1마디). 참조 트랙 2: C3(2분음표) G3(2분음표).
 */
public class CompareTest {

	private static Path tmp;
	private static BridgeEngine engine;
	private static ReferenceMidi ref;

	private static final Mapping MAP = Mapping.of(1, List.of(1), List.of(1, 2));

	@BeforeClass
	public static void setUpClass() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "compare-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		ref = ReferenceMidi.load(writeSyntheticMidi(tmp.resolve("synthetic.mid")));
	}

	private static Path writeSyntheticMidi(Path out) throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(meta(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }), 0)); // 120 BPM
		t1.add(new MidiEvent(meta(0x58, new byte[] { 4, 2, 24, 8 }), 0)); // 4/4
		int[] mel = { 60, 62, 64, 65 };
		for (int i = 0; i < mel.length; i++) {
			note(t1, 0, mel[i], i * 480, 480);
		}
		Track t2 = seq.createTrack();
		note(t2, 1, 48, 0, 960);
		note(t2, 1, 55, 960, 960);
		File f = out.toFile();
		MidiSystem.write(seq, 1, f);
		return out;
	}

	private static void note(Track t, int ch, int pitch, long tick, long len) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, 100), tick));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick + len));
	}

	private static MetaMessage meta(int type, byte[] data) throws Exception {
		return new MetaMessage(type, data, data.length);
	}

	/** 새 악보, 트랙 1(류트), 멜로디·화음1을 넣는다. 음표는 {틱, 길이, MIDI}. */
	private static Project score(int program, int[][] melody, int[][] chord1) {
		Project p = engine.newProject("cmp", "", "4/4", 120);
		p.addTrack("t1", program, null, null, null);
		if (melody.length > 0) {
			p.putNotes(1, "melody", specs(melody), "strict", false);
		}
		if (chord1.length > 0) {
			p.putNotes(1, "chord1", specs(chord1), "strict", false);
		}
		return p;
	}

	private static List<NoteSpec> specs(int[][] notes) {
		List<NoteSpec> l = new ArrayList<>();
		for (int[] n : notes) {
			l.add(NoteSpec.at(n[0], n[1], n[2]));
		}
		return l;
	}

	private static final int[][] MELODY_OK = { { 0, 96, 60 }, { 96, 96, 62 }, { 192, 96, 64 }, { 288, 96, 65 } };
	private static final int[][] CHORD_OK = { { 0, 192, 48 }, { 192, 192, 55 } };

	private static CompareReport cmp(Project p, ReferenceMidi r, Mapping m, int from, int to) {
		return Comparer.compare(p, r, m, from, to, null, engine.instrumentTable(), false);
	}

	private static List<MelodyItem> items(CompareReport r, String status) {
		List<MelodyItem> l = new ArrayList<>();
		for (MeasureDetail d : r.measures()) {
			for (MelodyMeasure mm : d.melody()) {
				for (MelodyItem i : mm.items()) {
					if (status.equals(i.status())) {
						l.add(i);
					}
				}
			}
		}
		return l;
	}

	// (a) 멜로디 한 음(E4 → E♭4)만 다름
	@Test
	public void wrongNote() {
		Project p = score(0, new int[][] { { 0, 96, 60 }, { 96, 96, 62 }, { 192, 96, 63 }, { 288, 96, 65 } }, CHORD_OK);
		CompareReport r = cmp(p, ref, MAP, 1, 1);
		MelodyCounts c = r.summary().melody();
		assertEquals(new MelodyCounts(3, 1, 0, 0, 0), c);
		MelodyItem w = items(r, "wrong").get(0);
		assertEquals("1:3:0", w.pos());
		assertEquals("E4", w.ref());
		assertEquals("D#4", w.score());
		assertEquals(Long.valueOf(0), w.tickDiff());
		assertEquals(Long.valueOf(0), w.lenDiff());
		// 화음 기준이 [1, 2](멜로디 트랙 포함)이므로 3박에서 원곡 E가 빈다(악보는 D#).
		assertEquals(4, r.summary().chordBeats());
		assertEquals(1, r.summary().chordMissingBeats());
		ChordBeat b3 = r.measures().get(0).chords().get(2);
		assertEquals("1:3:0", b3.pos());
		assertEquals(List.of("E"), b3.missing());
		assertEquals(List.of("D#"), b3.extra());
		assertEquals(List.of(1, 2), r.chordsFrom());
		// 화음 기준을 반주 트랙(2)만으로 하면 빈 박 0
		CompareReport r2 = cmp(p, ref, Mapping.of(1, List.of(1), List.of(2)), 1, 1);
		assertEquals(new MelodyCounts(3, 1, 0, 0, 0), r2.summary().melody());
		assertEquals(0, r2.summary().chordMissingBeats());
		assertEquals(0, r2.summary().overlaps());
	}

	@Test
	public void allMatch() {
		CompareReport r = cmp(score(0, MELODY_OK, CHORD_OK), ref, MAP, 1, 1);
		assertEquals(new MelodyCounts(4, 0, 0, 0, 0), r.summary().melody());
		assertEquals(0, r.summary().chordMissingBeats());
		assertEquals(1, r.melodyTracks().size());
		assertEquals(4, r.melodyTracks().get(0).refNotes());
		assertTrue(r.text(), r.text().contains("4음 중 4음 일치"));
	}

	/** 옥타브만 다른 음은 wrong이되 octaveOnly로 표시하고 문장에 알린다. */
	@Test
	public void octaveOnlyWrong() {
		int[][] mel = { { 0, 96, 72 }, { 96, 96, 74 }, { 192, 96, 64 }, { 288, 96, 66 } };
		CompareReport r = cmp(score(0, mel, CHORD_OK), ref, MAP, 1, 1);
		assertEquals(new MelodyCounts(1, 3, 0, 0, 0), r.summary().melody());
		assertEquals(2, r.melodyTracks().get(0).wrongOctave());
		List<MelodyItem> w = items(r, "wrong");
		assertEquals(Boolean.TRUE, w.get(0).octaveOnly());
		assertEquals(Boolean.TRUE, w.get(1).octaveOnly());
		assertEquals(Boolean.FALSE, w.get(2).octaveOnly()); // F4 → F#4
		assertTrue(r.text(), r.text().contains("그중 2음은 옥타브만 다름"));
	}

	// (b) 빠짐, 어긋남, 원곡에 없는 음
	@Test
	public void missingNote() {
		CompareReport r = cmp(score(0, new int[][] { { 0, 96, 60 }, { 192, 96, 64 }, { 288, 96, 65 } }, CHORD_OK), ref,
				MAP, 1, 1);
		assertEquals(new MelodyCounts(3, 0, 0, 1, 0), r.summary().melody());
		MelodyItem m = items(r, "missing").get(0);
		assertEquals("D4", m.ref());
		assertEquals("1:2:0", m.pos());
		assertNull(m.score());
	}

	@Test
	public void shiftedNote() {
		CompareReport r = cmp(score(0, new int[][] { { 0, 96, 60 }, { 108, 84, 62 }, { 192, 96, 64 }, { 288, 96, 65 } },
				CHORD_OK), ref, MAP, 1, 1);
		assertEquals(new MelodyCounts(3, 0, 1, 0, 0), r.summary().melody());
		MelodyItem s = items(r, "shifted").get(0);
		assertEquals(Long.valueOf(12), s.tickDiff());
		assertEquals(Long.valueOf(-12), s.lenDiff());
		assertEquals("1:2:12", s.scorePos());
		assertTrue(r.text(), r.text().contains("12틱 늦게"));
	}

	@Test
	public void extraNote() {
		int[][] mel = { { 0, 96, 60 }, { 96, 96, 62 }, { 192, 96, 64 }, { 288, 96, 65 }, { 384, 96, 67 } };
		CompareReport r = cmp(score(0, mel, CHORD_OK), ref, MAP, 1, 2);
		assertEquals(new MelodyCounts(4, 0, 0, 0, 1), r.summary().melody());
		MelodyItem e = items(r, "extra").get(0);
		assertEquals("G4", e.score());
		assertEquals("2:1:0", e.pos());
		assertEquals(2, r.measures().get(1).measure());
		assertEquals(1, r.measures().get(1).melody().get(0).counts().extra());
	}

	/** 짝은 1:1: 악보 한 음이 두 참조 음에 쓰이지 않는다. */
	@Test
	public void oneToOnePairing() {
		// 참조 C4(0), D4(96). 악보 D4(6) 하나 → C4와 wrong(차 6)이 먼저 잡히고, D4는 짝이 없어 missing.
		CompareReport r = cmp(score(0, new int[][] { { 6, 84, 62 } }, new int[0][]), ref, MAP, 1, 1);
		MelodyCounts c = r.summary().melody();
		assertEquals(0, c.matched());
		assertEquals(1, c.wrong());
		assertEquals(3, c.missing()); // D4, E4, F4
		assertEquals(0, c.extra());
		assertEquals(4, c.refTotal());
	}

	// (c) 화음1에서 G3를 빼면 3·4박에 G가 빈다
	@Test
	public void chordMissing() {
		CompareReport r = cmp(score(0, MELODY_OK, new int[][] { { 0, 192, 48 } }), ref, MAP, 1, 1);
		List<ChordBeat> beats = r.measures().get(0).chords();
		assertEquals(4, beats.size());
		assertEquals(List.of(), beats.get(0).missing());
		assertEquals(List.of(), beats.get(1).missing());
		assertEquals(List.of("G"), beats.get(2).missing());
		assertEquals(List.of("G"), beats.get(3).missing());
		assertEquals(List.of("E", "G"), beats.get(2).ref());
		assertEquals(List.of("E"), beats.get(2).score());
		assertEquals(2, r.summary().chordMissingBeats());
		assertTrue(r.text(), r.text().contains("1마디(4박) 중 2박"));
		assertTrue(r.text(), r.text().contains("1:3 G"));
	}

	// (d) 성부 교차
	@Test
	public void crossing() {
		Project p = score(0, new int[][] { { 0, 96, 64 } }, new int[][] { { 0, 96, 67 } });
		CompareReport r = cmp(p, null, null, 1, 1);
		assertEquals(1, r.summary().crossings());
		Crossing c = r.measures().get(0).crossings().get(0);
		assertEquals("1:1:0", c.pos());
		assertEquals("melody<chord1", c.type());
		assertEquals("E4", c.melody());
		assertEquals("G4", c.chord1());
		assertEquals(1, c.track());
		assertTrue(r.text(), r.text().contains("성부 교차"));
		assertTrue(r.text(), r.text().contains("1:1:0 멜로디 E4가 화음1 G4보다 낮음"));
	}

	/** 성부 교차는 정보 등급. 요약문은 "참고"로 따로 쓰고, 문제가 많은 마디 순위와 쓰기 요약의 문제 목록에서 뺀다. */
	@Test
	public void crossingIsInformational() {
		// 1마디: 멜로디 E4 < 화음1 G4 교차만 있음. 2마디는 빈 마디.
		Project p = score(0, new int[][] { { 0, 96, 64 } }, new int[][] { { 0, 96, 67 } });
		CompareReport r = cmp(p, null, null, 1, 2);
		assertEquals(1, r.summary().crossings());
		assertTrue(r.text(), r.text().contains("참고: 성부 교차 1곳(위 성부 음이 아래 성부보다 낮아지는 곳, 전문 편곡에서도 흔함)"));
		assertFalse(r.text(), r.text().contains("문제가 많은 마디"));

		CompareReport none = cmp(score(0, MELODY_OK, CHORD_OK), null, null, 1, 1);
		assertTrue(none.text(), none.text().contains("참고: 성부 교차 없음"));

		Mapping m = Mapping.of(1, List.of(1), List.of(1, 2));
		Project q = score(0, MELODY_OK, new int[][] { { 0, 96, 67 } });
		CompareSummary s = Comparer.summary(q, ref, m, List.of(1), engine.instrumentTable(), false);
		assertEquals(1, s.summary().crossings());
		assertTrue(s.text(), s.text().contains("— 참고: 성부 교차 1곳(전문 편곡에서도 흔함)."));
		assertFalse(s.text(), s.text().contains("교차 1곳,"));
		assertTrue(s.text(), s.text().split("\\. ").length <= 2);
	}

	/** 성부 교차 상세는 언제나 "참고: 성부 교차 N곳(설명): 트랙 T K곳(처음 …)" 꼴(트랙이 하나든 여럿이든). */
	@Test
	public void crossingDetailFormatIsUniform() {
		String head = "참고: 성부 교차 2곳(위 성부 음이 아래 성부보다 낮아지는 곳, 전문 편곡에서도 흔함): ";
		// 트랙 하나, 교차 2곳: 예시를 시작 순으로(3개까지)
		Project one = score(0, new int[][] { { 0, 96, 64 }, { 96, 96, 64 } }, new int[][] { { 0, 96, 67 }, { 96, 96, 67 } });
		CompareReport r1 = cmp(one, null, null, 1, 1);
		assertEquals(2, r1.summary().crossings());
		assertTrue(r1.text(), r1.text().contains(head
				+ "트랙 1 2곳(처음 1:1:0 멜로디 E4가 화음1 G4보다 낮음, 1:2:0 멜로디 E4가 화음1 G4보다 낮음)"));
		assertFalse(r1.text(), r1.text().contains(")("));

		// 트랙 둘, 각 1곳: 트랙마다 첫 예시 하나(많은 순, 같으면 번호 순)
		Project two = score(0, new int[][] { { 0, 96, 64 } }, new int[][] { { 0, 96, 67 } });
		two.addTrack("t2", 0, null, null, null);
		two.putNotes(2, "chord1", specs(new int[][] { { 96, 96, 60 } }), "strict", false);
		two.putNotes(2, "chord2", specs(new int[][] { { 96, 96, 62 } }), "strict", false);
		CompareReport r2 = cmp(two, null, null, 1, 1);
		assertEquals(2, r2.summary().crossings());
		assertTrue(r2.text(), r2.text().contains(head + "트랙 1 1곳(처음 1:1:0 멜로디 E4가 화음1 G4보다 낮음), "
				+ "트랙 2 1곳(처음 1:2:0 화음1 C4가 화음2 D4보다 낮음)"));
		assertFalse(r2.text(), r2.text().contains(")("));
	}

	/** 타악기 트랙(드럼 세트 27, 타입 D)은 비교 방식과 무관하게 pitch_compare_skipped에 kind "drum"으로 한 번 들어간다. */
	@Test
	public void drumTrackListedInPitchCompareSkipped() {
		Project p = score(0, MELODY_OK, CHORD_OK);
		p.addTrack("drum", 27, null, null, null);
		p.putNotes(2, "melody", specs(new int[][] { { 0, 96, 60 }, { 96, 96, 62 } }), "strict", false);
		for (Comparer.Options o : new Comparer.Options[] { Comparer.Options.LEGACY,
				new Comparer.Options(Comparer.SOUNDING, 0, false, false) }) {
			CompareReport r = Comparer.compare(p, ref, MAP, 1, 1, null, engine.instrumentTable(), false, o);
			assertEquals(o.toString(), 1, r.pitchCompareSkipped().size());
			CompareReport.PitchSkip ps = r.pitchCompareSkipped().get(0);
			assertEquals(2, ps.track());
			assertEquals(27, ps.program());
			assertEquals(Comparer.KIND_DRUM, ps.kind());
			assertEquals(Comparer.REASON_DRUM, ps.reason());
			assertTrue(r.text(), r.text().contains("음높이 비교를 하지 않은 트랙: 트랙 2"));
			assertTrue(r.text(), r.text().contains(" — 타악기"));
		}
		// 타악기 트랙이 없으면 비어 있음(written)
		assertTrue(cmp(score(0, MELODY_OK, CHORD_OK), ref, MAP, 1, 1).pitchCompareSkipped().isEmpty());
	}

	@Test
	public void crossingChord1Chord2AndNoCrossWhenOnePartSilent() {
		Project p = engine.newProject("cmp", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		// 화음1 C4(0~96), 화음2 E4(48~144): 48~96에서 교차. 멜로디는 비어 있음.
		p.putNotes(1, "chord1", specs(new int[][] { { 0, 96, 60 }, { 192, 96, 72 } }), "strict", false);
		p.putNotes(1, "chord2", specs(new int[][] { { 48, 96, 64 } }), "strict", false);
		CompareReport r = cmp(p, null, null, 1, 1);
		assertEquals(1, r.summary().crossings());
		Crossing c = r.measures().get(0).crossings().get(0);
		assertEquals("chord1<chord2", c.type());
		assertEquals("1:1:48", c.pos());
		assertEquals("C4", c.chord1());
		assertEquals("E4", c.chord2());
		assertNull(c.melody());
		// 화음1 C6(192~)에는 함께 울리는 화음2가 없으니 교차 아님
	}

	// (e) 음역: 휘슬(3번, properties O5c~O7e)에 C4
	@Test
	public void rangeViolationProperties() {
		Project p = score(3, new int[][] { { 0, 96, 60 }, { 96, 96, 72 } }, new int[0][]);
		CompareReport r = cmp(p, null, null, 1, 1);
		assertEquals(1, r.summary().rangeViolations());
		RangeViolation v = r.measures().get(0).range().get(0);
		assertEquals("C4", v.name());
		assertEquals(60, v.pitch());
		assertEquals("melody", v.part());
		assertEquals("properties", v.rangeSource());
		assertEquals("C5", v.low());
		assertEquals("E7", v.high());
		assertEquals(3, v.program());
		assertFalse(r.dlsLoaded());
		assertTrue(r.rangeChecks().get(0).checked());
		assertEquals("properties", r.rangeChecks().get(0).rangeSource());
		assertTrue(r.text(), r.text().contains("음역 이탈(악기가 낼 수 없는 높이) 1음"));
		assertTrue(String.valueOf(r.notesSkipped()), r.notesSkipped().stream().anyMatch(s -> s.contains("properties")));
	}

	/** 류트(0번)는 properties에 음역이 없다 → 검사 건너뜀 표시. */
	@Test
	public void rangeUnknownIsReported() {
		CompareReport r = cmp(score(0, MELODY_OK, new int[0][]), null, null, 1, 1);
		assertEquals(0, r.summary().rangeViolations());
		assertFalse(r.rangeChecks().get(0).checked());
		assertTrue(r.text(), r.text().contains("음역 정보가 없어 확인 못 한 트랙: 1"));
	}

	// (f) 참조 없음
	@Test
	public void noReference() {
		Project p = score(3, new int[][] { { 0, 96, 64 }, { 96, 96, 60 } }, new int[][] { { 0, 96, 67 } });
		CompareReport r = cmp(p, null, MAP, 1, 1);
		assertEquals(MelodyCounts.ZERO, r.summary().melody());
		assertEquals(0, r.summary().chordBeats());
		assertTrue(r.melodyTracks().isEmpty());
		assertTrue(r.measures().get(0).chords().isEmpty());
		assertNull(r.chordsFrom());
		assertTrue(r.notesSkipped().stream().anyMatch(s -> s.startsWith("멜로디 일치") && s.contains("참조")));
		assertTrue(r.notesSkipped().stream().anyMatch(s -> s.startsWith("화음 구성음") && s.contains("참조")));
		// 3~6항목은 있다
		assertEquals(1, r.summary().crossings());
		assertEquals(3, r.summary().rangeViolations()); // E4, C4, G4 모두 휘슬 음역(C5~E7) 아래
		assertEquals(0, r.summary().overlaps());
		assertNotNull(r.summary().charsOver());
		assertEquals(1, r.chars().size());
		assertTrue(r.text(), r.text().contains("참조 MIDI가 없어"));
	}

	@Test
	public void noMappingStillComparesChordsAgainstAll() {
		CompareReport r = cmp(score(0, MELODY_OK, CHORD_OK), ref, Mapping.empty(), 1, 1);
		assertTrue(r.melodyTracks().isEmpty());
		assertEquals(4, r.summary().chordBeats());
		assertEquals(0, r.summary().chordMissingBeats());
		assertNull(r.chordsFrom());
		assertTrue(r.text(), r.text().contains("멜로디 일치 검사는 건너뛰었습니다"));
	}

	// (g) text
	@Test
	public void textHasAllTopics() {
		Project p = score(0, new int[][] { { 0, 96, 60 }, { 192, 96, 63 }, { 288, 96, 65 } }, CHORD_OK);
		CompareReport r = cmp(p, ref, MAP, 1, 1);
		String t = r.text();
		assertNotNull(t);
		assertFalse(t.isBlank());
		for (String w : new String[] { "일치", "다름", "빠짐", "교차", "음역", "글자 수" }) {
			assertTrue("'" + w + "' 없음: " + t, t.contains(w));
		}
		assertTrue(t, t.startsWith("1마디: "));
		assertTrue(t, t.contains("참조 E4 / 악보 D#4"));
	}

	@Test
	public void summaryForWriteTools() {
		Project p = score(0, new int[][] { { 0, 96, 60 }, { 192, 96, 63 }, { 288, 96, 65 } }, CHORD_OK);
		CompareSummary s = Comparer.summary(p, ref, MAP, List.of(1), engine.instrumentTable(), false);
		assertFalse(s.skipped());
		assertNull(s.reason());
		assertEquals(new MelodyCounts(2, 1, 0, 1, 0), s.summary().melody());
		assertTrue(s.text(), s.text().split("\\. ").length <= 2);

		CompareSummary noRef = Comparer.summary(p, null, MAP, List.of(1), engine.instrumentTable(), false);
		assertTrue(noRef.skipped());
		assertEquals("참조 미로드", noRef.reason());
		assertNotNull(noRef.summary());
		assertNotNull(noRef.summary().charsOver());

		CompareSummary noMap = Comparer.summary(p, ref, null, List.of(1), engine.instrumentTable(), false);
		assertTrue(noMap.skipped());
		assertEquals("매핑 없음", noMap.reason());
		assertEquals(4, noMap.summary().chordBeats());

		// 바뀐 마디만 더한다(1·3마디 → 2마디는 빠짐)
		CompareSummary two = Comparer.summary(p, ref, MAP, List.of(3, 1), engine.instrumentTable(), false);
		assertEquals(List.of(1, 3), two.measures());
		assertEquals(8, two.summary().chordBeats());

		CompareSummary none = Comparer.summary(p, ref, MAP, List.of(), engine.instrumentTable(), false);
		assertTrue(none.skipped());
		assertNull(none.summary());
	}

	@Test
	public void scoreTracksFilterAndOverlapCount() {
		Project p = score(0, MELODY_OK, CHORD_OK);
		p.addTrack("t2", 0, null, null, null);
		p.putNotes(2, "melody", specs(new int[][] { { 0, 96, 50 } }), "strict", false);
		p.putNotes(2, "chord1", specs(new int[][] { { 0, 96, 70 } }), "strict", false);
		CompareReport all = Comparer.compare(p, ref, MAP, 1, 1, null, engine.instrumentTable(), false);
		assertEquals(List.of(1, 2), all.scoreTracks());
		assertEquals(1, all.summary().crossings()); // 트랙 2의 D3 < A#4
		assertTrue(all.text(), all.text().contains("트랙 2 1곳(처음 1:1:0 ")); // 형식
		CompareReport only1 = Comparer.compare(p, ref, MAP, 1, 1, Set.of(1), engine.instrumentTable(), false);
		assertEquals(List.of(1), only1.scoreTracks());
		assertEquals(0, only1.summary().crossings());
		assertEquals(0, only1.summary().chordMissingBeats());
	}

	@Test
	public void mappingJson() {
		Mapping m = Mapping.fromJson("{\"1\": {\"melody_from\": [3], \"chords_from\": \"all\"}, \"2\": {\"chords_from\": [4, 5]}}");
		assertEquals(List.of(3), m.tracks().get(1).melodyFrom());
		assertNull(m.tracks().get(1).chordsFrom());
		assertNull(m.tracks().get(2).melodyFrom());
		assertEquals(List.of(4, 5), m.tracks().get(2).chordsFrom());
		assertTrue(m.hasMelody());
		assertTrue(Mapping.fromJson("{}").isEmpty());
		try {
			Mapping.fromJson("{\"x\": {}}");
			throw new AssertionError("잘못된 키가 통과됨");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("트랙 번호"));
		}
	}

	/** compare 모듈이 쓰는 Gson 설정(LOWER_CASE_WITH_UNDERSCORES)으로 응답 필드 이름이 나오는지. */
	@Test
	public void gsonFieldNames() {
		CompareReport r = cmp(score(3, MELODY_OK, CHORD_OK), ref, MAP, 1, 1);
		Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
				.disableHtmlEscaping().create();
		String json = gson.toJson(r);
		for (String k : new String[] { "\"range\":[1,1]", "\"mapping_used\":{\"1\":{\"melody_from\":[1],\"chords_from\":[1,2],\"match_parts\":\"melody\"}}",
				"\"dls_loaded\":false", "\"summary\":", "\"melody\":{\"matched\":4", "\"chord_missing_beats\":0",
				"\"overlaps\":0", "\"crossings\":", "\"range_violations\":", "\"chars_over\":{\"song_excluded\":[],\"song_included\":[]}",
				"\"measures\":[{\"measure\":1", "\"chords\":[{\"pos\":\"1:1:0\"", "\"notes_skipped\":", "\"text\":" }) {
			assertTrue(k + " 없음: " + json, json.contains(k));
		}
		Map<?, ?> back = gson.fromJson(json, Map.class);
		assertTrue(back.containsKey("range_checks"));
	}

	// ======================================================================
	//·a2·a3·a4
	// ======================================================================

	/** 합성 참조 + 트랙 3(채널 3): 길이 0 음 A4(2박), 트랙 1 C4와 같은 시작·같은 음의 길이 0 C4(1박). */
	private static ReferenceMidi zeroLengthRef() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(meta(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }), 0));
		t1.add(new MidiEvent(meta(0x58, new byte[] { 4, 2, 24, 8 }), 0));
		int[] mel = { 60, 62, 64, 65 };
		for (int i = 0; i < mel.length; i++) {
			note(t1, 0, mel[i], i * 480, 480);
		}
		Track t2 = seq.createTrack();
		note(t2, 1, 48, 0, 960);
		note(t2, 1, 55, 960, 960);
		Track t3 = seq.createTrack();
		note(t3, 2, 69, 480, 0);
		note(t3, 2, 60, 0, 0);
		Path out = tmp.resolve("zero-length.mid");
		MidiSystem.write(seq, 1, out.toFile());
		return ReferenceMidi.load(out);
	}

	/** a1: 원본 길이 0 음은 멜로디 짝짓기와 화음 비교에서 빠지고 ignored_zero_length로 센다. */
	@Test
	public void zeroLengthNotesAreIgnored() throws Exception {
		ReferenceMidi z = zeroLengthRef();
		assertEquals(0, z.notes(3, 1, 1).get(0).srcLength());
		Mapping m = Mapping.of(1, List.of(1, 3), List.of(1, 2, 3));
		CompareReport r = cmp(score(0, MELODY_OK, CHORD_OK), z, m, 1, 1);
		// A4는 빠짐이 아니라 제외. 길이 0 C4는 트랙 1의 C4와 같은 음이라 하나로 합쳐져 제외 수에 넣지 않는다.
		assertEquals(new MelodyCounts(4, 0, 0, 0, 0), r.summary().melody());
		assertEquals(1, r.summary().ignoredZeroLength());
		assertEquals(1, r.melodyTracks().get(0).ignoredZeroLength());
		assertEquals(4, r.melodyTracks().get(0).refNotes());
		assertEquals(1, r.measures().get(0).melody().get(0).ignoredZeroLength());
		// 화음: 2박의 A가 원곡 음에 들어가지 않으므로 빈 박 0. 1박(C4)·2박(A4)에서 하나씩 뺐다.
		assertEquals(0, r.summary().chordMissingBeats());
		assertEquals(List.of(), r.measures().get(0).chords().get(1).missing());
		assertEquals(1, r.measures().get(0).chords().get(0).ignoredZeroLength());
		assertEquals(1, r.measures().get(0).chords().get(1).ignoredZeroLength());
		assertEquals(2, r.summary().chordIgnoredZeroLength());
		assertTrue(r.text(), r.text().contains("길이 0이라 울리지 않는 음 1개는 세지 않음"));
		// 쓰기 도구 요약에도 같은 값
		CompareSummary s = Comparer.summary(score(0, MELODY_OK, CHORD_OK), z, m, List.of(1), engine.instrumentTable(),
				false);
		assertEquals(1, s.summary().ignoredZeroLength());
		assertEquals(0, s.summary().melody().missing());
	}

	/** a2: match_parts "any"면 멜로디가 아닌 파트의 같은 음·같은 시작도 matched. 다른 파트는 wrong·extra로 세지 않는다. */
	@Test
	public void matchPartsAny() {
		Project p = engine.newProject("cmp", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		p.putNotes(1, "melody", specs(new int[][] { { 0, 96, 60 } }), "strict", false);
		p.putNotes(1, "chord1", specs(new int[][] { { 192, 96, 64 }, { 288, 96, 65 } }), "strict", false);
		// 참조 D4(96)와 같은 시작이지만 다른 음: any여도 wrong이 되지 않고 D4는 빠짐으로 남는다.
		p.putNotes(1, "chord2", specs(new int[][] { { 96, 96, 67 } }), "strict", false);

		CompareReport mel = cmp(p, ref, Mapping.of(1, List.of(1), List.of(1, 2)), 1, 1);
		assertEquals(new MelodyCounts(1, 0, 0, 3, 0), mel.summary().melody());
		assertEquals("melody", mel.melodyTracks().get(0).matchParts());

		Mapping any = new Mapping(Map.of(1, new Mapping.Entry(List.of(1), List.of(1, 2), "any")));
		CompareReport r = cmp(p, ref, any, 1, 1);
		assertEquals(new MelodyCounts(3, 0, 0, 1, 0), r.summary().melody());
		assertEquals("any", r.melodyTracks().get(0).matchParts());
		assertEquals(Map.of("melody", 1, "chord1", 2), r.melodyTracks().get(0).matchedByPart());
		assertEquals(Map.of("melody", 1, "chord1", 2), r.measures().get(0).melody().get(0).matchedByPart());
		MelodyItem miss = items(r, "missing").get(0);
		assertEquals("D4", miss.ref());
		assertNull(miss.part());
		assertEquals("any", r.mappingUsed().get("1").matchParts());
		assertTrue(r.text(), r.text().contains("트랙의 모든 파트와 비교"));
		assertTrue(r.text(), r.text().contains("화음1 2음은 멜로디 아닌 파트에서"));

		// 1:1 유지: 같은 음이 멜로디와 화음1에 다 있으면 멜로디가 먼저, 화음1 음은 남아도 extra가 아니다.
		Project q = score(0, MELODY_OK, new int[][] { { 0, 96, 60 } });
		CompareReport rq = cmp(q, ref, any, 1, 1);
		assertEquals(new MelodyCounts(4, 0, 0, 0, 0), rq.summary().melody());
		assertEquals(Map.of("melody", 4), rq.melodyTracks().get(0).matchedByPart());

		// JSON 매핑에서도 읽는다. 잘못된 값은 거부.
		assertEquals("any", Mapping.fromJson("{\"1\": {\"melody_from\": [1], \"match_parts\": \"any\"}}").tracks().get(1)
				.matchParts());
		assertEquals("melody", Mapping.fromJson("{\"1\": {\"melody_from\": [1]}}").tracks().get(1).matchParts());
		try {
			Mapping.fromJson("{\"1\": {\"melody_from\": [1], \"match_parts\": \"chord1\"}}");
			throw new AssertionError("잘못된 match_parts가 통과됨");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("match_parts"));
		}
	}

	/** a3: 마디 수와 박 수를 함께 쓸 때 "27~34마디(32박)" 형식. */
	@Test
	public void chordSentenceWording() {
		CompareReport r = cmp(score(0, MELODY_OK, CHORD_OK), ref, MAP, 1, 2);
		assertTrue(r.text(), r.text().contains("1~2마디(8박) 모두"));
		assertFalse(r.text(), r.text().contains("2마디 8박"));
	}

	/** a4: 악보 트랙마다 일치율 상위 참조 트랙을 제안한다. */
	@Test
	public void suggestMapping() {
		Project p = engine.newProject("cmp", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		p.addTrack("t2", 0, null, null, null);
		p.addTrack("t3", 0, null, null, null);
		// 트랙 1: 참조 1의 4음 중 3음을 멜로디에, 1음(F4)을 화음1에
		p.putNotes(1, "melody", specs(new int[][] { { 0, 96, 60 }, { 96, 96, 62 }, { 192, 96, 64 } }), "strict", false);
		p.putNotes(1, "chord1", specs(new int[][] { { 288, 96, 65 } }), "strict", false);
		// 트랙 2: 참조 2의 C3·G3를 화음2에, 참조 1의 C4 하나를 멜로디에
		p.putNotes(2, "chord2", specs(new int[][] { { 0, 192, 48 }, { 192, 192, 55 } }), "strict", false);
		p.putNotes(2, "melody", specs(new int[][] { { 0, 96, 60 } }), "strict", false);
		// 트랙 3: 비어 있음
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 1, null, engine.instrumentTable());
		assertEquals(List.of(1, 2), s.refTracks());
		assertEquals(3, s.tracks().size());

		MappingSuggestion.TrackSuggestion t1 = s.tracks().get(0);
		assertEquals(4, t1.scoreNotes());
		assertEquals(1, t1.suggestions().size()); // 참조 2와는 맞는 음이 없다
		MappingSuggestion.Candidate c1 = t1.suggestions().get(0);
		assertEquals(1, c1.refTrack());
		assertEquals(4, c1.matched());
		assertEquals(4, c1.total());
		assertEquals(1.0, c1.ratio(), 1e-9);
		assertEquals(Map.of("melody", 3, "chord1", 1), c1.byPart());

		MappingSuggestion.TrackSuggestion t2 = s.tracks().get(1);
		assertEquals(2, t2.suggestions().size());
		assertEquals(2, t2.suggestions().get(0).refTrack()); // 2/2 = 1.0
		assertEquals(1.0, t2.suggestions().get(0).ratio(), 1e-9);
		assertEquals(1, t2.suggestions().get(1).refTrack()); // 1/4 = 0.25
		assertEquals(0.25, t2.suggestions().get(1).ratio(), 1e-9);

		assertTrue(s.tracks().get(2).suggestions().isEmpty());
		assertNotNull(s.tracks().get(2).note());

		assertEquals(List.of(1), s.suggestedMapping().get("1").get("melody_from"));
		assertEquals("any", s.suggestedMapping().get("1").get("match_parts"));
		assertEquals(List.of(2), s.suggestedMapping().get("2").get("melody_from"));
		assertFalse(s.suggestedMapping().containsKey("3"));
		assertTrue(s.text(), s.text().contains("트랙 1(t1) → 참조 1번"));

		// tracks 제한
		MappingSuggestion only2 = MappingSuggester.suggest(p, ref, 1, 1, Set.of(2), engine.instrumentTable());
		assertEquals(1, only2.tracks().size());
		assertEquals(2, only2.tracks().get(0).track());
	}

	/** a4: 길이 0 음은 total에서 빠진다(제안 쪽도 a1 규칙). */
	@Test
	public void suggestMappingIgnoresZeroLength() throws Exception {
		ReferenceMidi z = zeroLengthRef();
		Project p = engine.newProject("cmp", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		p.putNotes(1, "melody", specs(new int[][] { { 0, 96, 60 } }), "strict", false);
		MappingSuggestion s = MappingSuggester.suggest(p, z, 1, 1, null, engine.instrumentTable());
		// 참조 3은 길이 0 음뿐이라 후보가 아니다.
		assertEquals(List.of(1, 2), s.refTracks());
		assertEquals(1, s.tracks().get(0).suggestions().get(0).refTrack());
		assertEquals(4, s.tracks().get(0).suggestions().get(0).total());
	}

}
