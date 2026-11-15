package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/** (i) DLS, (j) test_mode 없으면 시작 거부, 악기표·NoteSpec 단위 시험. */
public class BootstrapTest {

	@Test
	public void refusesWithoutTestMode() {
		String saved = System.getProperty("mabiicco.test_mode");
		assertNotNull("이 시험은 -Dmabiicco.test_mode 로 돌려야 함", saved);
		System.clearProperty("mabiicco.test_mode");
		try {
			new BridgeEngine();
			fail("test_mode 없이 시작되면 안 됨");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("test_mode"));
		} finally {
			System.setProperty("mabiicco.test_mode", saved);
		}
		assertNotNull(new BridgeEngine());
	}

	@Test
	public void instrumentTableWithoutDls() {
		InstrumentTable t = InstrumentTable.load();
		InstrumentTable.Instrument bass = t.get(29);
		assertEquals("Bass", bass.nameEn());
		assertEquals("베이스", bass.nameKo());
		assertEquals("N", bass.type());
		assertEquals(List.of("melody", "chord1", "chord2"), bass.allowedParts());
		assertNull(bass.propRange());
		assertEquals("properties", bass.rangeSource());
		InstrumentTable.Instrument whistle = t.get(3);
		assertEquals(Integer.valueOf(72), whistle.propRange().low()); // O5c = 엔진 60 = MIDI 72
		assertEquals(Integer.valueOf(100), whistle.propRange().high()); // O7e = 엔진 88 = MIDI 100
		assertEquals("D", t.get(27).type());
		assertTrue(t.get(27).allowTempoChordPart());
		assertEquals("P", t.get(66).type());
		assertFalse(t.get(66).allowTempoChordPart());
		assertEquals(List.of("melody"), t.get(66).allowedParts());
		assertEquals(List.of("song"), t.get(90).allowedParts());
		assertEquals("스네어드럼", t.get(65).nameKo()); // 한국어 파일에만 있는 번호
		assertNull(t.get(65).nameEn());
		InstrumentTable.Instrument mobileLute = t.get(2 << 9);
		assertEquals("0.2", mobileLute.programText());
		InstrumentTable.Instrument mobileDrum = t.get((2 << 9) + 66);
		assertTrue(mobileDrum.propRange().lowForced());
		assertEquals(Integer.valueOf(12), mobileDrum.propRange().low()); // !o0c
		assertTrue(t.warnings().toString(), t.warnings().isEmpty());
	}

	@Test
	public void noteSpecParsing() {
		assertEquals(60, NoteSpec.parsePitch("C4", 0));
		assertEquals(66, NoteSpec.parsePitch("F#4", 0));
		assertEquals(58, NoteSpec.parsePitch("Bb3", 0));
		assertEquals(73, NoteSpec.parsePitch("Db5", 0));
		assertEquals(61, NoteSpec.parsePitch("c+4", 0));
		assertEquals(51, NoteSpec.parsePitch("E-3", 0));
		assertEquals(64, NoteSpec.parsePitch(64.0, 0));
		assertEquals("C4", NoteSpec.pitchName(60));
		assertEquals("F#4", NoteSpec.pitchName(66));
		assertEquals("B-1", NoteSpec.pitchName(11));
		assertEquals(96, NoteSpec.parseLen("4", 0));
		assertEquals(72, NoteSpec.parseLen("8.", 0));
		assertEquals(120, NoteSpec.parseLen("4&16", 0));
		assertEquals(384, NoteSpec.parseLen("1", 0));
		assertEquals(6, NoteSpec.parseLen("64", 0));
		assertEquals(90, NoteSpec.parseLen(90, 0));
	}

	/** 타악기(P, 멜로디 파트만) 트랙의 화음 파트에 넣으면 막지는 않고 경고한다. */
	@Test
	public void percussionChordPartWarns() throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("boot"));
		Project p = e.newProject("t", "", "4/4", 120);
		p.addTrack("perc", 66, null, null, null);
		// 화음1에 음표를 넣으면 타악기 경고
		EditResult r = p.putNotes(1, "chord1", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		assertEquals(1, r.warnings().size());
	}

	@Test
	public void dlsLoadingWhenAvailable() {
		boolean present = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!present) {
			System.err.println("[SKIP] BootstrapTest.dlsLoadingWhenAvailable: DLS 파일 없음 " + BridgeEngine.defaultDls());
		}
		Assume.assumeTrue("DLS 파일 없음", present);
		BridgeEngine e = new BridgeEngine();
		BridgeEngine.DlsStatus s = e.ensureDls();
		System.err.println("[DLS] 파일 " + s.files().size() + "개, 악기 " + s.instruments() + "개, 악기표 일치 "
				+ s.matchedInTable() + "개, " + s.millis() + " ms");
		assertTrue(s.loaded());
		assertTrue(BridgeEngine.dlsLoaded());
		InstrumentTable.Instrument bass = null;
		for (InstrumentTable.Instrument i : e.instruments(null)) {
			if (i.program() == 29) {
				bass = i;
			}
		}
		assertNotNull("29번 Bass가 목록에 있어야 함", bass);
		assertEquals("dls", bass.rangeSource());
		assertTrue(bass.inDls());
		int lo = bass.range().low();
		int hi = bass.range().high();
		System.err.println("[DLS] 29 Bass 음역 " + bass.range().lowName() + "(" + lo + ") ~ " + bass.range().highName() + "(" + hi + ")");
		assertTrue("최저 " + lo, lo >= 0 && lo <= 127);
		assertTrue("최고 " + hi, hi >= 0 && hi <= 127);
		assertTrue(lo <= hi);
		// 두 번째 호출은 다시 읽지 않는다.
		assertEquals(0, e.ensureDls().millis());
	}
}
