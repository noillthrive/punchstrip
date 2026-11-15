package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/** "적은 음 대 들리는 음" 표(SoundingPitch). DLS 없이 돈다. */
public class SoundingPitchTest {

	private static int s(int program, int written) {
		return SoundingPitch.soundingOf(program, written);
	}

	@Test
	public void offsets() {
		assertEquals(72, s(21, 60)); // 피아노 +1옥타브
		assertEquals(74, s(22, 50)); // 바이올린 +2옥타브
		assertEquals(74, s(25, 50)); // 튜닝 바이올린 +2
		assertEquals(59, s(23, 35)); // 첼로 +2
		assertEquals(81, s(77, 57)); // 실로폰 +2
		assertEquals(60, s(0, 60)); // 류트 그대로
		assertEquals(57, s(2, 57)); // 만돌린 그대로
		assertEquals(60, s(5, 60)); // 플룻 그대로
		assertEquals(86, s(3, 86)); // 휘슬 그대로
		assertEquals(72, s(55, 60)); // 튜닝 플루트 +1
		assertEquals(57, s(80, 45)); // 남성 음성 +1
		assertEquals(64, s(90, 40)); // 여성 음성 +2
		assertEquals(64, s(121, 40)); // 밀키웨이(여성) +2
	}

	@Test
	public void regions() {
		// 가야금·전자 기타: 63 이하 +2옥타브, 64 이상 −2옥타브
		assertEquals(74, s(28, 50));
		assertEquals(46, s(28, 70));
		assertEquals(87, s(28, 63));
		assertEquals(40, s(28, 64));
		assertEquals(40, s(20, 64));
		assertEquals(40, s(20, 16));
		// 베이스: 55 이하 +1, 56~61 무음, 62 이상 −3
		assertEquals(52, s(29, 40));
		assertEquals(67, s(29, 55));
		for (int w = 56; w <= 61; w++) {
			assertEquals("베이스 " + w, SoundingPitch.SILENT, s(29, w));
		}
		assertEquals(26, s(29, 62));
		assertEquals(34, s(29, 70));
		assertEquals("default", SoundingPitch.timbreOf(29, 40));
		assertEquals("silent", SoundingPitch.timbreOf(29, 59));
		assertEquals("alt", SoundingPitch.timbreOf(29, 70));
		assertEquals("alt", SoundingPitch.timbreOf(28, 64));
		assertEquals(List.of("default", "alt"), SoundingPitch.timbres(29));
		assertEquals(List.of("default"), SoundingPitch.timbres(21));
	}

	@Test
	public void fixedUnpitchedSfx() {
		// 빈 병: 적은 음과 무관하게 한 음. 속성 파일 순서대로 12 = 시(B3), 13 = 라(A3)
		assertEquals(60, s(7, 48));
		assertEquals(60, s(7, 72));
		assertEquals(62, s(8, 60));
		assertEquals(67, s(11, 30));
		assertEquals(59, s(12, 60));
		assertEquals(57, s(13, 60));
		// 핸드벨 C5~C6
		assertEquals(72, s(69, 40));
		assertEquals(84, s(76, 60));
		// 드럼·북·심벌즈, 새소리
		assertEquals(SoundingPitch.UNPITCHED, s(27, 53));
		assertEquals(SoundingPitch.UNPITCHED, s(65, 60));
		assertEquals(SoundingPitch.UNPITCHED, s(68, 60));
		assertEquals(SoundingPitch.UNPITCHED, s(14, 59));
		assertEquals(SoundingPitch.Kind.FIXED, SoundingPitch.rule(7).kind());
		assertEquals(SoundingPitch.Kind.UNPITCHED, SoundingPitch.rule(27).kind());
		assertEquals(SoundingPitch.Kind.SFX, SoundingPitch.rule(17).kind());
		assertFalse(SoundingPitch.rule(7).kind().pitched());
	}

	@Test
	public void unknownProgram() {
		// 모바일 류트(0.2) = 논리 번호 (2 << 9) + 0
		int mobile = (2 << 9);
		SoundingPitch.Rule r = SoundingPitch.rule(mobile);
		assertEquals(SoundingPitch.Kind.UNKNOWN, r.kind());
		assertFalse(r.verified());
		assertEquals(60, s(mobile, 60));
		assertEquals(Integer.valueOf(60), SoundingPitch.writtenFor(mobile, 60, null));
		assertTrue(SoundingPitch.rule(21).verified());
	}

	@Test
	public void reverse() {
		assertEquals(Integer.valueOf(60), SoundingPitch.writtenFor(21, 72, null));
		assertEquals(Integer.valueOf(50), SoundingPitch.writtenFor(22, 74, null));
		assertEquals(Integer.valueOf(64), SoundingPitch.writtenFor(5, 64, "default"));
		// 가야금: 두 세트가 같은 들리는 음을 다른 적은 음으로 낸다
		assertEquals(Integer.valueOf(50), SoundingPitch.writtenFor(28, 74, "default"));
		assertEquals(Integer.valueOf(98), SoundingPitch.writtenFor(28, 74, "alt"));
		assertEquals(Integer.valueOf(70), SoundingPitch.writtenFor(28, 46, "alt"));
		assertEquals(Integer.valueOf(22), SoundingPitch.writtenFor(28, 46, "default"));
		// 베이스: 들리는 E2(40) → default E1(28) / alt E5(76)
		assertEquals(Integer.valueOf(28), SoundingPitch.writtenFor(29, 40, null));
		assertEquals(Integer.valueOf(28), SoundingPitch.writtenFor(29, 40, "default"));
		assertEquals(Integer.valueOf(76), SoundingPitch.writtenFor(29, 40, "alt"));
		// 세트의 구간 밖: 베이스 default로 들리는 70 → 적을 음 58은 무음 구간
		assertNull(SoundingPitch.writtenFor(29, 70, "default"));
		assertNull(SoundingPitch.writtenFor(29, 20, "alt")); // 56 < 62
		assertNull(SoundingPitch.writtenFor(29, 40, "nope"));
		// 고정·무음높이는 적을 음이 없다
		assertNull(SoundingPitch.writtenFor(7, 60, null));
		assertNull(SoundingPitch.writtenFor(27, 53, null));
		assertNull(SoundingPitch.writtenFor(14, 60, null));
		// 왕복
		for (int p : new int[] { 0, 2, 5, 19, 21, 22, 23, 24, 80, 90 }) {
			int[] win = SoundingPitch.audibleWindow(p); // voices 80 / 90: written pitches outside the window are silent
			for (int w = 40; w <= 80; w++) {
				if (win != null && (w < win[0] || w > win[1])) {
					assertNull(p + "/" + w, SoundingPitch.writtenFor(p, w + SoundingPitch.rule(p).offset(), null));
					assertEquals(p + "/" + w, SoundingPitch.SILENT, SoundingPitch.soundingOf(p, w));
					continue;
				}
				assertEquals(p + "/" + w, Integer.valueOf(w), SoundingPitch.writtenFor(p, s(p, w), null));
			}
		}
		for (int w = 14; w <= 103; w++) {
			int snd = s(29, w);
			if (snd >= 0) {
				assertEquals("베이스 " + w, Integer.valueOf(w), SoundingPitch.writtenFor(29, snd, SoundingPitch.timbreOf(29, w)));
			}
		}
	}

	@Test
	public void describeAndRange() {
		Map<String, Object> piano = SoundingPitch.describe(21);
		assertEquals("offset", piano.get("kind"));
		assertEquals(12, piano.get("offset_semitones"));
		assertEquals("+1옥타브", piano.get("summary"));
		assertEquals(Boolean.TRUE, piano.get("verified"));
		Map<String, Object> bass = SoundingPitch.describe(29);
		assertEquals("regions", bass.get("kind"));
		assertNull(bass.get("offset_semitones"));
		assertEquals(2, ((List<?>) bass.get("regions")).size());
		// 56~61 and, from the render, everything outside written 14~103
		assertEquals("[{from=null, to=13}, {from=56, to=61}, {from=104, to=null}]", String.valueOf(bass.get("silent")));
		assertEquals(Map.of("from", 14, "to", 103, "from_name", "D0", "to_name", "G7"), bass.get("audible_written"));
		assertEquals("구간별", bass.get("summary"));
		Map<String, Object> bottle = SoundingPitch.describe(12);
		assertEquals("fixed", bottle.get("kind"));
		assertEquals(59, bottle.get("fixed_pitch"));
		assertEquals("B3", bottle.get("fixed_name"));
		assertEquals("음높이 없음", SoundingPitch.summary(27));
		assertEquals("적은 대로", SoundingPitch.summary(0));
		assertEquals("+2옥타브", SoundingPitch.summary(22));

		// 베이스 DLS 음역 14~103: 두 세트 모두 들리는 D1(26)~G4(67)
		List<Map<String, Object>> br = SoundingPitch.soundingRange(29, 14, 103);
		assertEquals(2, br.size());
		for (Map<String, Object> x : br) {
			assertEquals(26, x.get("low"));
			assertEquals(67, x.get("high"));
		}
		assertEquals(55, br.get(0).get("written_high"));
		assertEquals(62, br.get(1).get("written_low"));
		List<Map<String, Object>> pr = SoundingPitch.soundingRange(21, 9, 96);
		assertEquals(1, pr.size());
		assertEquals(21, pr.get(0).get("low"));
		assertEquals(108, pr.get(0).get("high"));
		assertTrue(SoundingPitch.soundingRange(27, 41, 65).isEmpty());
		assertEquals(60, SoundingPitch.soundingRange(7, 24, 96).get(0).get("high"));
		// 음역을 모르면 끝이 null
		assertNull(SoundingPitch.soundingRange(21, null, null).get(0).get("low"));
	}

	@Test
	public void songPartUsesSongProgram() {
		assertEquals(90, SoundingPitch.programFor(0, 90, Part.SONG, "N"));
		assertEquals(0, SoundingPitch.programFor(0, 90, Part.MELODY, "N"));
		assertEquals(0, SoundingPitch.programFor(0, -1, Part.SONG, "N"));
		assertEquals(80, SoundingPitch.programFor(80, 90, Part.SONG, "V")); // 노래 악기 트랙은 자기 악기
	}

	/**
	 * 악기표의 모바일 아닌 악기는 모두 표에 있다(빠지면 unknown이 된다). 30~34(영어 표에만 있는 번호, DLS에 없어 측정 못 함)는
	 * 일부러 unknown으로 둔다.
	 */
	@Test
	public void tableCoversInstrumentTable() {
		InstrumentTable t = InstrumentTable.load();
		for (InstrumentTable.Instrument i : t.list(null)) {
			if (i.bank() != 0 || (i.program() >= 30 && i.program() <= 34)) {
				assertEquals(SoundingPitch.Kind.UNKNOWN, SoundingPitch.rule(i.program()).kind());
				continue;
			}
			assertTrue("표에 없음: " + i.program() + " " + i.name(), SoundingPitch.programs().contains(i.program()));
		}
	}
}
