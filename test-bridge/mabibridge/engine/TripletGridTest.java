package mabibridge.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 셋잇단 격자. 4분 셋잇단(64틱)·8분 셋잇단(32)·16분 셋잇단(16)·2분 셋잇단(128)을 넣고 MML과 저장·다시 열기 왕복을 본다.
 * MabiIcco는 l6 = 64, l12 = 32, l24 = 16, l3 = 128틱을 그대로 쓰고, 생성할 때 자체 검증(만든 MML을 되읽어 비교)을 한다.
 */
public class TripletGridTest {

	private static List<String> ticks(List<NoteView> l) {
		List<String> s = new ArrayList<>();
		for (NoteView n : l) {
			s.add(n.tick() + "+" + n.len() + ":" + n.name());
		}
		return s;
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

	@Test
	public void quarterTripletRoundTrip() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("triplet");
		BridgeEngine eng = EngineTestSupport.engine(tmp);
		Project p = eng.newProject("triplet", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		// 1마디: 4분 셋잇단 C D E(틱 0·64·128, 길이 64) + F 2분음표. 2마디: pos와 MML 길이 "6"으로 같은 꼴
		EditResult r = p.putNotes(1, "melody", List.of(NoteSpec.at(0, 64, "C4"), NoteSpec.at(64, 64, "D4"),
				NoteSpec.at(128, 64, "E4"), NoteSpec.at(192, 192, "F4"), NoteSpec.atPos("2:1:0", "6", "C4"),
				NoteSpec.atPos("2:1:64", "6", "D4"), NoteSpec.atPos("2:2:32", "6", "E4"), NoteSpec.atPos("2:3:0", "2", "F4")),
				"strict", false);
		assertTrue(r.aligned().isEmpty());
		// 화음1: 8분 셋잇단(32) 한 박 + 16분 셋잇단(16) 한 8분 + 2분 셋잇단(128) 세 개는 3마디
		p.putNotes(1, "chord1", List.of(NoteSpec.at(0, 32, "C3"), NoteSpec.at(32, 32, "D3"), NoteSpec.at(64, 32, "E3"),
				NoteSpec.at(96, 16, "F3"), NoteSpec.at(112, 16, "G3"), NoteSpec.at(128, 16, "A3"),
				NoteSpec.at(768, 128, "C3"), NoteSpec.at(896, 128, "D3"), NoteSpec.at(1024, 128, "E3")), "strict", false);
		List<String> mel = ticks(p.notes(1, "melody", 1, 3));
		List<String> ch = ticks(p.notes(1, "chord1", 1, 3));
		assertEquals(List.of("0+64:C4", "64+64:D4", "128+64:E4", "192+192:F4", "384+64:C4", "448+64:D4", "512+64:E4",
				"576+192:F4"), mel);
		String mml = p.mml(1).get(0).mml();
		// 셋잇단 길이가 MML 길이 그대로 나온다: 4분 셋잇단 l6, 8분 l12, 16분 l24, 2분 l3
		assertEquals("MML@l6cdef2cdef2,l12<cdel24fgar1.r8l3cde,;", mml);
		// 저장 → 다시 열기 → 같은 틱
		Path f = tmp.resolve("triplet.mmi");
		p.saveAs(f);
		Project q = eng.open(f);
		assertEquals(mel, ticks(q.notes(1, "melody", 1, 3)));
		assertEquals(ch, ticks(q.notes(1, "chord1", 1, 3)));
		assertEquals(mml, q.mml(1).get(0).mml());
		System.err.println("[triplet] MML " + mml);
	}

	/** 못갖춘마디(1/16) 뒤 마디 시작은 24 + 384k라 16의 배수가 아니다. 셋잇단 격자는 마디 시작 기준이다. */
	@Test
	public void tripletGridIsMeasureRelative() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("triplet-pickup");
		BridgeEngine eng = EngineTestSupport.engine(tmp);
		Project p = eng.newProject("pickup", "", null,
				List.of(new Project.TimeSigSpec(1, "1/16"), new Project.TimeSigSpec(2, "4/4")), 172);
		p.addTrack("t1", 0, null, null, null);
		// 2마디 시작 = 틱 24. 셋잇단 88·152(마디 기준 64·128)는 받고, 절대 틱으로 16의 배수인 80(마디 기준 56)은 거부
		p.putNotes(1, "melody", List.of(NoteSpec.atPos("2:1:0", 64, "C5"), NoteSpec.at(88, 64, "D5"),
				NoteSpec.atPos("2:2:32", 64, "E5"), NoteSpec.atPos("2:3:0", 192, "F5")), "strict", false);
		assertEquals(List.of("24+64:C5", "88+64:D5", "152+64:E5", "216+192:F5"), ticks(p.notes(1, "melody", 1, 2)));
		byte[] before = p.state();
		EngineException ex = expect(EngineException.BAD_NOTE,
				() -> p.putNotes(1, "chord1", List.of(NoteSpec.at(80, 32, "C4")), "strict", false));
		assertEquals("tick", ex.details().get("field"));
		assertArrayEquals(before, p.state());
		// 템포도 같은 격자(셋잇단 위치 88)
		p.setTempos(List.of(new Project.TempoSpec(0L, null, 172), new Project.TempoSpec(88L, null, 180)));
		Path f = tmp.resolve("pickup.mmi");
		p.saveAs(f);
		Project q = eng.open(f);
		assertEquals(ticks(p.notes(1, "melody", 1, 2)), ticks(q.notes(1, "melody", 1, 2)));
		assertEquals(p.mml(1).get(0).mml(), q.mml(1).get(0).mml());
		assertTrue(q.mml(1).get(0).mml(), q.mml(1).get(0).mml().contains("t180"));
		expect(EngineException.BAD_ARGUMENT,
				() -> p.setTempos(List.of(new Project.TempoSpec(0L, null, 172), new Project.TempoSpec(80L, null, 180))));
	}

	/** 격자 밖(6의 배수도 16의 배수도 아님)은 예전처럼 BAD_NOTE, 6틱 미만 쉼표가 생기면 엔진이 거부하고 그대로 둔다. */
	@Test
	public void offGridAndShortGaps() throws Exception {
		BridgeEngine eng = EngineTestSupport.engine(EngineTestSupport.tmpDir("triplet-gap"));
		Project p = eng.newProject("gap", "", "4/4", 120);
		p.addTrack("t1", 0, null, null, null);
		for (long t : new long[] { 40, 50, 100 }) {
			EngineException ex = expect(EngineException.BAD_NOTE,
					() -> p.putNotes(1, "melody", List.of(NoteSpec.at(t, 96, "C4")), "strict", false));
			assertEquals("tick", ex.details().get("field"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("셋잇단 격자"));
		}
		expect(EngineException.BAD_NOTE, () -> p.putNotes(1, "melody", List.of(NoteSpec.at(0, 40, "C4")), "strict", false));
		// align은 예전처럼 6의 배수로
		EditResult a = p.putNotes(1, "melody", List.of(NoteSpec.at(40, 40, "C4")), "strict", true);
		assertEquals(2, a.aligned().size());
		assertEquals(42, a.aligned().get(0).to());
		p.deleteNotes(1, "melody", 1, 1);
		// 셋잇단 64 + 6의 배수 위치 132 → 쉼표 4틱: MML로 쓸 수 없어 엔진이 거부(MML_GENERATE_FAILED), 악보는 그대로
		p.putNotes(1, "melody", List.of(NoteSpec.at(64, 64, "C4")), "strict", false);
		byte[] before = p.state();
		String mmlBefore = p.mml(1).get(0).mml();
		EngineException g = expect(EngineException.MML_GENERATE_FAILED,
				() -> p.putNotes(1, "melody", List.of(NoteSpec.at(132, 60, "D4")), "strict", false));
		assertTrue(g.getMessage(), g.getMessage().contains("6틱보다 짧은"));
		assertArrayEquals(before, p.state());
		assertEquals(mmlBefore, p.mml(1).get(0).mml());
		// score_replace_range도 같은 격자
		Map<String, List<NoteSpec>> parts = new LinkedHashMap<>();
		parts.put("melody", List.of(NoteSpec.at(0, 64, "C4"), NoteSpec.at(64, 64, "D4"), NoteSpec.at(128, 64, "E4")));
		p.replaceRange(1, 1, 1, parts, false);
		assertEquals(List.of("0+64:C4", "64+64:D4", "128+64:E4"), ticks(p.notes(1, "melody", 1, 1)));
	}
}
