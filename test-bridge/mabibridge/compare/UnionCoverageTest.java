package mabibridge.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;

import mabibridge.compare.CompareReport.MelodyCounts;
import mabibridge.compare.CompareReport.Union;
import mabibridge.compare.CompareReport.UnionTrack;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.mcp.Json;
import mabibridge.midi.ReferenceMidi;

/**
 * 합산(중복 제거) 일치. 참조 트랙 1(리드) 2마디: C4 D4 E4 F4 | G4 A4 B4 C5. 참조 트랙 2(반주) C3 온음표 2개.
 * 악보: 트랙 1은 1마디 리드, 트랙 2는 2마디 리드, 트랙 3은 1마디 리드를 한 옥타브 위로 겹침(류트, 적은 대로 울림).
 * 매핑 1·2·3 → 참조 1. 항목별 합은 참조 음 8개를 세 번 세어 24음 중 8음이지만 실제 덮음은 8/8이다.
 */
public class UnionCoverageTest {

	private static BridgeEngine engine;
	private static ReferenceMidi ref;

	@BeforeClass
	public static void setUpClass() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "union-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t1 = seq.createTrack();
		t1.add(new MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));
		t1.add(new MidiEvent(new MetaMessage(0x58, new byte[] { 4, 2, 24, 8 }, 4), 0));
		int[] lead = { 60, 62, 64, 65, 67, 69, 71, 72 };
		for (int i = 0; i < lead.length; i++) {
			note(t1, 0, lead[i], i * 480L, 480);
		}
		Track t2 = seq.createTrack();
		note(t2, 1, 48, 0, 1920);
		note(t2, 1, 48, 1920, 1920);
		Path mid = tmp.resolve("union.mid");
		MidiSystem.write(seq, 1, mid.toFile());
		ref = ReferenceMidi.load(mid);
	}

	private static void note(Track t, int ch, int pitch, long tick, long len) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, 100), tick));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick + len));
	}

	private static List<NoteSpec> specs(int fromTick, int... pitches) {
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < pitches.length; i++) {
			l.add(NoteSpec.at(fromTick + i * 96, 96, pitches[i]));
		}
		return l;
	}

	private static Project score() {
		Project p = engine.newProject("union", "", "4/4", 120);
		for (int i = 1; i <= 3; i++) {
			p.addTrack("t" + i, 0, null, null, null);
		}
		p.putNotes(1, "melody", specs(0, 60, 62, 64, 65), "strict", false);
		p.putNotes(2, "melody", specs(384, 67, 69, 71, 72), "strict", false);
		p.putNotes(3, "melody", specs(0, 72, 74, 76, 77), "strict", false);
		return p;
	}

	private static Mapping leadToAll() {
		Map<Integer, Mapping.Entry> m = new TreeMap<>();
		for (int i = 1; i <= 3; i++) {
			m.put(i, new Mapping.Entry(List.of(1), List.of(1, 2)));
		}
		return new Mapping(m);
	}

	@Test
	public void unionCountsEachReferenceNoteOnce() {
		Project p = score();
		CompareReport r = Comparer.compare(p, ref, leadToAll(), 1, 2, null, engine.instrumentTable(), false,
				new Comparer.Options("written", 0, false, false));
		// 항목별 합(예전 그대로): 8음 × 3항목 = 24음 중 8음
		MelodyCounts c = r.summary().melody();
		assertEquals(24, c.refTotal());
		assertEquals(8, c.matched());
		Union u = r.summary().union();
		assertNotNull(u);
		assertEquals(8, u.total());
		assertEquals(8, u.matchedAny());
		assertEquals(Double.valueOf(1.0), u.ratio());
		assertEquals(3, u.entries());
		assertEquals(1, u.tracks().size());
		UnionTrack t = u.tracks().get(0);
		assertEquals(1, t.refTrack());
		assertEquals(8, t.total());
		assertEquals(8, t.matchedAny());
		assertEquals(List.of(1, 2, 3), t.scoreTracks());
		assertTrue(r.text(), r.text().contains("합산(중복 제거) 일치: 원곡 8음 중 8음(100.0%)"));
		assertTrue(r.text(), r.text().contains("참조 1번 8/8(악보 트랙 1·2·3)"));
		// JSON: summary.union.tracks[0].ref_track, matched_any
		JsonObject js = Json.tree(r).getAsJsonObject().getAsJsonObject("summary").getAsJsonObject("union");
		assertEquals(8, js.get("matched_any").getAsInt());
		assertEquals(1, js.getAsJsonArray("tracks").get(0).getAsJsonObject().get("ref_track").getAsInt());
	}

	@Test
	public void unionPartialAndOctaveTolerant() {
		Project p = score();
		Map<Integer, Mapping.Entry> m = new TreeMap<>();
		m.put(1, new Mapping.Entry(List.of(1), List.of(1, 2)));
		m.put(3, new Mapping.Entry(List.of(1), List.of(1, 2)));
		Mapping map = new Mapping(m);
		// 엄격: 트랙 3은 옥타브 위라 맞는 것이 없다 → 2마디는 아무도 덮지 않아 4/8
		CompareReport strict = Comparer.compare(p, ref, map, 1, 2, null, engine.instrumentTable(), false,
				new Comparer.Options("written", 0, false, false));
		assertEquals(16, strict.summary().melody().refTotal());
		assertEquals(4, strict.summary().union().matchedAny());
		assertEquals(8, strict.summary().union().total());
		assertEquals(Double.valueOf(0.5), strict.summary().union().ratio());
		// 옥타브 허용: 트랙 3도 1마디를 맞히지만 같은 참조 음이라 합산은 그대로 4/8, 항목별 합은 8
		CompareReport tol = Comparer.compare(p, ref, map, 1, 2, null, engine.instrumentTable(), false,
				new Comparer.Options("written", 0, false, true));
		assertEquals(8, tol.summary().melody().matched());
		assertEquals(4, tol.summary().union().matchedAny());
		// 트랙 3만 매핑하면 옥타브 허용 짝도 합산에서 맞은 것으로 센다
		CompareReport only3 = Comparer.compare(p, ref, Mapping.of(3, List.of(1), List.of(1, 2)), 1, 2, null,
				engine.instrumentTable(), false, new Comparer.Options("written", 0, false, true));
		assertEquals(4, only3.summary().union().matchedAny());
		// 항목이 하나면 문장은 넣지 않는다(항목별 수와 같으므로)
		assertTrue(only3.text(), !only3.text().contains("합산(중복 제거)"));
	}

	@Test
	public void unionNullWithoutMelodyMapping() {
		Project p = score();
		CompareReport r = Comparer.compare(p, ref, Mapping.empty(), 1, 2, null, engine.instrumentTable(), false,
				Comparer.Options.DEFAULTS);
		assertNull(r.summary().union());
		CompareReport noRef = Comparer.compare(p, null, leadToAll(), 1, 2, null, engine.instrumentTable(), false,
				Comparer.Options.DEFAULTS);
		assertNull(noRef.summary().union());
	}

	@Test
	public void writeSummaryUnionOnChangedMeasures() {
		Project p = score();
		CompareSummary s = Comparer.summary(p, ref, leadToAll(), List.of(1), engine.instrumentTable(), false,
				new Comparer.Options("written", 0, false, false));
		assertEquals(12, s.summary().melody().refTotal());
		Union u = s.summary().union();
		assertNotNull(u);
		assertEquals(4, u.total());
		assertEquals(4, u.matchedAny());
		assertTrue(s.text(), s.text().contains("합산(중복 제거) 일치 4/4(100.0%)"));
		assertTrue(s.text(), s.text().split("\\. ").length <= 2);
		CompareSummary s2 = Comparer.summary(p, ref, leadToAll(), List.of(2), engine.instrumentTable(), false,
				new Comparer.Options("written", 0, false, false));
		assertEquals(4, s2.summary().union().total());
		assertEquals(4, s2.summary().union().matchedAny());
	}
}
