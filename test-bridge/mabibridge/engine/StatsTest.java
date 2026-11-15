package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import jp.fourthline.mmlTools.ComposeRank;

/** (f) 글자 수 두 기준. */
public class StatsTest {

	/** 6분 음표 패턴: 옥타브와 세기를 계속 바꿔 최적화로 줄지 않게 한다. 대략 음표 하나에 5~6자. */
	private static List<NoteSpec> busyMelody(int count) {
		String[] pitches = { "C4", "D#5", "F#3", "A5", "C#4", "G5" };
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			l.add(NoteSpec.at(i * 24L, 24, pitches[i % pitches.length], 3 + (i % 11)));
		}
		return l;
	}

	@Test
	public void longMelodyOverOnlyInSongIncluded() throws Exception {
		Project found = null;
		int melody = 0;
		for (int n = 200; n <= 600; n += 10) {
			BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("stats"));
			Project p = e.newProject("stats", "", "4/4", 120);
			p.addTrack("long", 0, null, null, null);
			p.putNotes(1, "melody", busyMelody(n), "strict", false);
			melody = p.statsOf(1).chars().get("melody");
			if (melody > 1300) {
				found = p;
				break;
			}
		}
		assertTrue("멜로디 1300자 넘는 악보를 만들지 못함(마지막 " + melody + "자)", found != null);
		assertTrue("노래 제외 한도(1600) 안이어야 이 시험이 성립: " + melody, melody <= 1600);
		TrackStats s = found.statsOf(1);
		System.err.println("[Stats] 멜로디 " + melody + "자, " + s.rankText() + ", " + s.playTimeSec() + "초");
		assertEquals(List.of("melody"), s.limits().songIncluded().over());
		assertEquals(List.of(), s.limits().songExcluded().over());
		assertEquals(1600, s.limits().songExcluded().melody());
		assertEquals(1200, s.limits().songExcluded().chord1());
		assertEquals(900, s.limits().songExcluded().chord2());
		assertEquals(1200, s.limits().songIncluded().melody());
		assertEquals(800, s.limits().songIncluded().chord1());
		assertEquals(500, s.limits().songIncluded().chord2());
		assertEquals(1200, s.limits().songIncluded().song());
		assertEquals("song_excluded", s.defaultRule());
		assertEquals(List.of(), s.overDefault());
		assertEquals(found.mml(1).get(0).parts().get("melody").length(), melody);
		assertTrue(s.rankText(), s.rankText().startsWith("Rank "));
		assertTrue(s.playTimeSec() > 0);

		found.setSongRule("song_included");
		assertEquals(List.of("melody"), found.statsOf(1).overDefault());
	}

	/** 노래 파트가 있으면 노래 제외 기준의 over가 아니라 songNotAllowed로 알린다(글자 수 초과가 아님). */
	@Test
	public void songPartIsNotAllowedNotOver() throws Exception {
		BridgeEngine e = EngineTestSupport.engine(EngineTestSupport.tmpDir("stats-song"));
		Project p = e.newProject("song", "", "4/4", 120);
		p.addTrack("plain", 0, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		TrackStats plain = p.statsOf(1);
		assertFalse(plain.limits().songExcluded().songNotAllowed());
		assertEquals(List.of(), plain.limits().songExcluded().over());

		p.putNotes(1, "song", List.of(NoteSpec.at(0, 96, "E4")), "strict", false);
		TrackStats s = p.statsOf(1);
		System.err.println("[Stats] 노래 파트 글자 수 " + s.chars().get("song") + ", 노래 제외 " + s.limits().songExcluded());
		assertTrue("노래 파트 글자 수가 0보다 커야 이 시험이 성립", s.chars().get("song") > 0);
		assertTrue(s.limits().songExcluded().songNotAllowed());
		assertFalse(s.limits().songExcluded().over().contains("song"));
		assertEquals(List.of(), s.limits().songExcluded().over());
	}

	@Test
	public void limitsMatchEngineTopRank() {
		ComposeRank top = ComposeRank.getTopRank();
		int[] t = Project.RankLimits.top();
		assertEquals(top.getMelody(), t[0]);
		assertEquals(top.getChord1(), t[1]);
		assertEquals(top.getChord2(), t[2]);
		ComposeRank ex = ComposeRank.getTopExcludeSongRank();
		assertEquals(1600, ex.getMelody());
		assertEquals(1200, ex.getChord1());
		assertEquals(900, ex.getChord2());
	}
}
