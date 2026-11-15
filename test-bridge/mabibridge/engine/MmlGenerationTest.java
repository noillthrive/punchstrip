package mabibridge.engine;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/**
 * (b) MML 생성 손 검증. 4/4, 템포 120(템포 이벤트 없음 = 마비노기 기본), 트랙 1 악기 0(류트).
 *
 * <p>기대값은 엔진 규칙을 읽고 미리 적었다. 출발 상태는 o4·l4·v8(MMLBuilder의 초기 음 = o4, 세기 8),
 * 4분음표(96틱)는 길이 생략, 세기가 바뀌면 v, 옥타브가 바뀌면 &lt; &gt;(MMLNoteEvent.changeOctaveinMMLString),
 * 마지막에 GEN2 최적화(MMLStringOptimizer.optimizeGen2 = OxLxFixedOptimizer + NxBpCmOptimizer).
 * {@code MMLText.getMML()}은 "MML@" + 멜로디 + "," + 화음1 + "," + 화음2 (+ "," + 노래) + ";".</p>
 */
public class MmlGenerationTest {

	private static Project project(Integer tempo) throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("mmlgen"));
		Project p = e.newProject("t", "", "4/4", tempo);
		assertEquals(1, p.addTrack("lute", 0, null, null, null));
		return p;
	}

	private static String mml(Project p) {
		return p.mml(1).get(0).mml();
	}

	@Test
	public void cdefQuarterNotes() throws Exception {
		Project p = project(120);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4", 8), NoteSpec.at(96, 96, "D4", 8),
				NoteSpec.at(192, 96, "E4", 8), NoteSpec.at(288, 96, "F4", 8)), "strict", false);
		// 기대(미리 적음): MML@cdef,,;
		assertEquals("MML@cdef,,;", mml(p));
		assertEquals(4, p.mml(1).get(0).chars().get("melody").intValue());
	}

	/**
	 * B4 → C5. 처음 기대값은 "MML@b>c,,;"(옥타브 변화 &gt;)였다. 실제 출력은 "MML@bb+,,;":
	 * GEN2 최적화의 NxBpCmOptimizer가 "&gt;c"를 같은 옥타브의 "b+"(= C5)로 바꿔 옥타브 기호를 없앤다
	 * (글자 수는 같고 뒤따르는 옥타브 상태를 바꾸지 않는다). 그래서 이 경우는 &gt;가 들어가지 않는 것이 엔진 규칙이다.
	 * &gt;가 들어가는 예는 {@link #octaveUpB4toD5()}.
	 */
	@Test
	public void octaveB4toC5() throws Exception {
		Project p = project(null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "B4"), NoteSpec.at(96, 96, "C5")), "strict", false);
		assertEquals("MML@bb+,,;", mml(p));
	}

	@Test
	public void octaveUpB4toD5() throws Exception {
		Project p = project(null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "B4"), NoteSpec.at(96, 96, "D5")), "strict", false);
		// 기대(미리 적음): MML@b>d,,;
		assertEquals("MML@b>d,,;", mml(p));
	}

	/**
	 * D5 → B4. 처음 기대값은 "MML@&gt;d&lt;b,,;"였다. 실제 출력은 "MML@n62b,,;": NxBpCmOptimizer가
	 * 옥타브를 올렸다 내리는 "&gt;d&lt;"(3자)를 음 번호 명령 "n62"(3자, 엔진 음 번호 62 = o5d = MIDI 74)로 바꿨다.
	 * 글자 수가 같을 때도 옥타브 상태를 바꾸지 않는 쪽을 고르는 것이 엔진 규칙이다.
	 */
	@Test
	public void octaveDownD5toB4() throws Exception {
		Project p = project(null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "D5"), NoteSpec.at(96, 96, "B4")), "strict", false);
		assertEquals("MML@n62b,,;", mml(p));
	}

	/** 옥타브가 두 번 이상 내려가 있으면 &lt;가 남는다: C4 C3 C3 → 기대(미리 적음) "MML@c&lt;cc,,;". */
	@Test
	public void octaveDownStays() throws Exception {
		Project p = project(null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(96, 96, "C3"), NoteSpec.at(192, 96, "C3")),
				"strict", false);
		assertEquals("MML@c<cc,,;", mml(p));
	}

	@Test
	public void velocityChange() throws Exception {
		Project p = project(null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4", 8), NoteSpec.at(96, 96, "D4", 12),
				NoteSpec.at(192, 96, "E4", 12), NoteSpec.at(288, 96, "F4", 8)), "strict", false);
		// 기대(미리 적음): MML@cv12dev8f,,;
		assertEquals("MML@cv12dev8f,,;", mml(p));
	}

	@Test
	public void tempoNot120() throws Exception {
		Project p = project(87);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		// 기대(미리 적음): MML@t87c,,;
		assertEquals("MML@t87c,,;", mml(p));
	}

	@Test
	public void restAndLength() throws Exception {
		Project p = project(null);
		// 8분음표 C4를 2박(96)에, 2분음표 E4를 3박(192)에
		p.putNotes(1, "melody", List.of(NoteSpec.atPos("1:2:0", "8", "C4"), NoteSpec.atPos("1:3:0", "2", "E4")),
				"strict", false);
		// 기대(미리 적음): MML@rc8r8e2,,;
		assertEquals("MML@rc8r8e2,,;", mml(p));
	}

	@Test
	public void emptyMelodyGetsPlaceholder() throws Exception {
		Project p = project(null);
		p.putNotes(1, "chord1", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		// 기대(미리 적음): 멜로디가 비고 화음이 있으면 앱 기본 "<>"가 멜로디 자리에 들어감 → MML@<>,c,;
		assertEquals("MML@<>,c,;", mml(p));
		assertEquals(2, p.statsOf(1).chars().get("melody").intValue());
	}
}
