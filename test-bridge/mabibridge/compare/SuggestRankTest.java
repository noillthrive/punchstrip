package mabibridge.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.BeforeClass;
import org.junit.Test;

import mabibridge.compare.Comparer.Options;
import mabibridge.compare.MappingSuggestion.Candidate;
import mabibridge.compare.MappingSuggestion.TrackSuggestion;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * 매핑 제안 순위(score·small_sample)와 실로폰(타입 K) 음높이 비교.
 *
 * <p>합성 참조(PPQ 480, 4/4, 120 BPM, 4마디). 트랙 1 "piano": 4분음표 16개(C5부터 온음계로 오르내림, 실제 4인 악보의 피아노 트랙처럼 긴 짝).
 * 트랙 2 "bass": 1마디 첫 박에 트랙 1과 같은 음 하나(베이스 1/1처럼 짧은 트랙). 트랙 3 "guitar": 2마디의 첫 세 박에 트랙 1과 같은 음
 * 셋(3/3).</p>
 */
public class SuggestRankTest {

	private static BridgeEngine engine;
	private static ReferenceMidi ref;

	/** 참조 트랙 1의 음(MIDI). */
	private static final int[] PIANO = { 72, 74, 76, 77, 79, 81, 83, 84, 83, 81, 79, 77, 76, 74, 72, 71 };

	@BeforeClass
	public static void setUpClass() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "suggest-rank-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(new MetaMessage(0x03, "piano".getBytes(), 5), 0));
		t1.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));
		t1.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		for (int i = 0; i < PIANO.length; i++) {
			note(t1, 0, PIANO[i], i * 480L, 480);
		}
		Track t2 = seq.createTrack();
		t2.add(new MidiEvent(new MetaMessage(0x03, "bass".getBytes(), 4), 0));
		note(t2, 1, PIANO[0], 0, 480);
		Track t3 = seq.createTrack();
		t3.add(new MidiEvent(new MetaMessage(0x03, "guitar".getBytes(), 6), 0));
		for (int i = 4; i < 7; i++) {
			note(t3, 2, PIANO[i], i * 480L, 480);
		}
		Path f = tmp.resolve("rank.mid");
		MidiSystem.write(seq, 1, f.toFile());
		ref = ReferenceMidi.load(f);
	}

	private static void note(Track t, int ch, int pitch, long tick, long len) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, 100), tick));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick + len));
	}

	/** 트랙 1에 참조 트랙 1의 음을 (참조 음 + transpose)로 넣되, skip번째 음은 뺀다(−1이면 다 넣음). */
	private static Project score(int program, int transpose, int skip) {
		Project p = engine.newProject("rank", "", "4/4", 120);
		p.addTrack("t1", program, null, null, null);
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < PIANO.length; i++) {
			if (i != skip) {
				l.add(NoteSpec.at(i * 96L, 96, PIANO[i] + transpose));
			}
		}
		p.putNotes(1, "melody", l, "strict", false);
		return p;
	}

	/**
	 * 실제 4인 악보의 16마디 구간 사례를 흉내 낸다: 악보 트랙이 긴 참조 트랙의 16음 중 15음을 따르고, 1음짜리·3음짜리 참조 트랙과도 모두 맞는다.
	 * 일치율로는 1/1·3/3(100%)이 15/16(94%)보다 앞서지만, 순위에서는 긴 짝이 1위다.
	 */
	@Test
	public void longPartnerBeatsTinyPerfectTrack() {
		Project p = score(0, 0, 15); // 류트, 적은 음 그대로(written으로 비교)
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(),
				new Options(Comparer.WRITTEN, 0, false, false));
		TrackSuggestion t = s.tracks().get(0);
		assertEquals(15, t.scoreNotes());
		List<Candidate> c = t.suggestions();
		assertEquals(3, c.size());

		Candidate first = c.get(0);
		assertEquals(1, first.refTrack());
		assertEquals(15, first.matched());
		assertEquals(16, first.total());
		assertEquals(0.938, first.ratio(), 1e-9);
		assertEquals(15 - 0.5 * 1, first.score(), 1e-9);
		assertFalse(first.smallSample());

		// 나머지는 small_sample(참조 음 < 8)이라 뒤로, 그 안에서 score 순(3/3 = 3 > 1/1 = 1)
		assertEquals(3, c.get(1).refTrack());
		assertEquals(1.0, c.get(1).ratio(), 1e-9);
		assertEquals(3.0, c.get(1).score(), 1e-9);
		assertTrue(c.get(1).smallSample());
		assertEquals(2, c.get(2).refTrack());
		assertEquals(1.0, c.get(2).ratio(), 1e-9);
		assertEquals(1.0, c.get(2).score(), 1e-9);
		assertTrue(c.get(2).smallSample());

		assertEquals(List.of(1), s.suggestedMapping().get("1").get("melody_from"));
		assertTrue(s.text(), s.text().contains("참조 음이 적어 순위 뒤로"));
	}

	/** small_sample 기준: total < max(8, 악보 트랙 음 수의 10%). score = matched − 0.5 × (total − matched). */
	@Test
	public void smallSampleThresholdAndScore() {
		assertTrue(MappingSuggester.smallSample(7, 10));
		assertFalse(MappingSuggester.smallSample(8, 10));
		assertTrue(MappingSuggester.smallSample(9, 100));
		assertFalse(MappingSuggester.smallSample(10, 100));
		assertTrue(MappingSuggester.smallSample(29, 300));
		assertEquals(86.5, MappingSuggester.scoreOf(87, 88), 1e-9);
		assertEquals(1.0, MappingSuggester.scoreOf(1, 1), 1e-9);
		assertEquals(-1.5, MappingSuggester.scoreOf(0, 3), 1e-9);
	}

	/** small_sample 기준 수 = min(max(8, ceil(악보 음 수 × 10%)), 악보 음 수). */
	@Test
	public void smallSampleThresholdCappedByScoreNotes() {
		assertEquals(0, MappingSuggester.smallSampleThreshold(0));
		assertEquals(1, MappingSuggester.smallSampleThreshold(1));
		assertEquals(5, MappingSuggester.smallSampleThreshold(5));
		assertEquals(7, MappingSuggester.smallSampleThreshold(7));
		assertEquals(8, MappingSuggester.smallSampleThreshold(8));
		assertEquals(8, MappingSuggester.smallSampleThreshold(80));
		assertEquals(9, MappingSuggester.smallSampleThreshold(81));
		assertEquals(11, MappingSuggester.smallSampleThreshold(110)); // 110 × 0.1 = 11.000000000000002 → 11
		assertEquals(30, MappingSuggester.smallSampleThreshold(300));
		assertEquals(31, MappingSuggester.smallSampleThreshold(301));
		// 악보 5음: 5/5 다 맞은 참조 트랙(참조 음 5)은 small_sample이 아님, 4음짜리는 small_sample
		assertFalse(MappingSuggester.smallSample(5, 5));
		assertTrue(MappingSuggester.smallSample(4, 5));
		assertEquals(0, MappingSuggester.rankGroup(false, 5));
		assertEquals(1, MappingSuggester.rankGroup(true, 3));
		assertEquals(2, MappingSuggester.rankGroup(false, 0));
		assertEquals(2, MappingSuggester.rankGroup(false, -6.5));
		assertEquals(3, MappingSuggester.rankGroup(true, 0));
		assertEquals(3, MappingSuggester.rankGroup(true, -1));
	}

	/**
	 * 결함 재현(v2 트랙 4 플루트 5음 ↔ 참조 17 5/5): 악보 트랙이 3음뿐이고 그 3음이 참조 3(guitar, 3음)과 모두 맞는다.
	 * 기준(max(8, …))으로는 3/3이 small_sample이 되어 3/16(score −3.5)인 piano가 1위였다. 는 기준이 3이라 guitar가 1위.
	 */
	@Test
	public void tinyScoreTrackKeepsItsFullMatch() {
		Project p = engine.newProject("rank", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 4; i < 7; i++) {
			l.add(NoteSpec.at(i * 96L, 96, PIANO[i]));
		}
		p.putNotes(1, "melody", l, "strict", false);
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(),
				new Options(Comparer.WRITTEN, 0, false, false));
		TrackSuggestion t = s.tracks().get(0);
		assertEquals(3, t.scoreNotes());
		assertEquals(Integer.valueOf(3), t.smallSampleThreshold());
		List<Candidate> c = t.suggestions();
		assertEquals(2, c.size());
		assertEquals(3, c.get(0).refTrack());
		assertEquals(3, c.get(0).matched());
		assertEquals(3, c.get(0).total());
		assertFalse(c.get(0).smallSample());
		assertEquals(1, c.get(1).refTrack());
		assertEquals(3, c.get(1).matched());
		assertEquals(16, c.get(1).total());
		assertEquals(-3.5, c.get(1).score(), 1e-9);
		assertEquals(List.of(3), s.suggestedMapping().get("1").get("melody_from"));
	}

	/**
	 * 묶음 ②가 ③보다 앞: small_sample이어도 score &gt; 0이면 small_sample 아닌 score ≤ 0 후보보다 앞선다.
	 * 악보 15음(기준 8) 중 3음만 참조 3(guitar)·참조 1과 맞고, 12음은 반음 위(안 맞음).
	 */
	@Test
	public void positiveSmallSampleBeatsNonPositiveLargeTrack() {
		Project p = engine.newProject("rank", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			l.add(NoteSpec.at(i * 96L, 96, (i >= 4 && i < 7) ? PIANO[i] : PIANO[i] + 1));
		}
		p.putNotes(1, "melody", l, "strict", false);
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(),
				new Options(Comparer.WRITTEN, 0, false, false));
		TrackSuggestion t = s.tracks().get(0);
		assertEquals(15, t.scoreNotes());
		assertEquals(Integer.valueOf(8), t.smallSampleThreshold());
		List<Candidate> c = t.suggestions();
		assertEquals(3, c.get(0).refTrack());
		assertTrue(c.get(0).smallSample());
		assertEquals(3.0, c.get(0).score(), 1e-9);
		assertEquals(1, c.get(1).refTrack());
		assertFalse(c.get(1).smallSample());
		assertTrue(c.get(1).score() <= 0);
		assertEquals(List.of(3), s.suggestedMapping().get("1").get("melody_from"));
	}

	/**
	 * 묶음 ③이 ④보다 앞: score ≤ 0끼리는 small_sample 아닌 후보가 score가 더 낮아도 앞선다.
	 * 악보 15음(기준 8) 중 5번째 음 하나만 참조 1(piano, 1/16, score −6.5)·참조 3(guitar, 1/3, score 0, small)과 맞는다.
	 */
	@Test
	public void nonPositiveLargeTrackBeatsNonPositiveSmallSample() {
		Project p = engine.newProject("rank", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			l.add(NoteSpec.at(i * 96L, 96, i == 4 ? PIANO[i] : PIANO[i] + 1));
		}
		p.putNotes(1, "melody", l, "strict", false);
		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(),
				new Options(Comparer.WRITTEN, 0, false, false));
		List<Candidate> c = s.tracks().get(0).suggestions();
		assertEquals(2, c.size());
		assertEquals(1, c.get(0).refTrack());
		assertFalse(c.get(0).smallSample());
		assertEquals(-6.5, c.get(0).score(), 1e-9);
		assertEquals(3, c.get(1).refTrack());
		assertTrue(c.get(1).smallSample());
		assertEquals(0.0, c.get(1).score(), 1e-9);
		assertEquals(List.of(1), s.suggestedMapping().get("1").get("melody_from"));
	}

	/**
	 * 실로폰(77, 타입 K, 들리는 음 +2옥타브): 원곡보다 2옥타브 낮게 적으면 들리는 음이 원곡과 같다. 타악기로 빼지 않고
	 * 매핑 제안·멜로디 비교·화음 커버리지에 넣는다.
	 */
	@Test
	public void xylophoneIsPitchCompared() {
		Project p = score(77, -24, -1);
		Options sounding = new Options(Comparer.SOUNDING, 0, false, false);

		MappingSuggestion s = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(), sounding);
		TrackSuggestion t = s.tracks().get(0);
		assertFalse(t.drum());
		assertEquals("+2옥타브", t.sounding());
		assertFalse(t.suggestions().isEmpty());
		Candidate c = t.suggestions().get(0);
		assertEquals(1, c.refTrack());
		assertEquals(16, c.matched());
		assertEquals(0, c.octaveShift());

		CompareReport r = Comparer.compare(p, ref, Mapping.of(1, List.of(1), List.of(1)), 1, 4, null,
				engine.instrumentTable(), false, sounding);
		assertEquals(16, r.summary().melody().matched());
		assertEquals(0, r.summary().melody().wrong());
		assertTrue(r.pitchCompareSkipped().isEmpty());
		// 화음 커버리지: 참조 1만 기준으로 16박, 실로폰이 모두 채운다(타악기로 빼면 16박 모두 빔)
		assertEquals(16, r.summary().chordBeats());
		assertEquals(0, r.summary().chordMissingBeats());
		for (String n : r.notesSkipped()) {
			assertFalse(n, n.contains("타악기"));
		}

		// auto 마디 차에도 들어간다(매핑 없이 악보 트랙마다 가장 잘 맞는 참조 트랙의 합)
		MappingSuggestion a = MappingSuggester.suggest(p, ref, 1, 4, null, engine.instrumentTable(),
				new Options(Comparer.SOUNDING, null, true, false));
		assertEquals(0, a.measureOffsetUsed());
		assertEquals(Integer.valueOf(16), a.measureOffsetScores().get("0"));
	}
}
