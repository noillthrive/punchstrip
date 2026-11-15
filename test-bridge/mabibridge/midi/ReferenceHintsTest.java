package mabibridge.midi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
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

import org.junit.Assume;
import org.junit.Test;

/** 참조 힌트: 멜로디 점수·역할, 페달 끝, 격자, 성부 초안. */
public class ReferenceHintsTest {

	static final int RES = 480;
	static final int BAR = RES * 4;

	static MetaMessage meta(int type, byte[] data) throws Exception {
		return new MetaMessage(type, data, data.length);
	}

	static void note(Track t, int ch, int pitch, int vel, long on, long off) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, vel), on));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), off));
	}

	static void cc(Track t, int ch, int cc, int value, long tick) throws Exception {
		t.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, cc, value), tick));
	}

	static Track named(Sequence seq, String name) throws Exception {
		Track t = seq.createTrack();
		t.add(new MidiEvent(meta(0x03, name.getBytes("UTF-8")), 0));
		return t;
	}

	static ReferenceMidi load(Sequence seq) throws Exception {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		MidiSystem.write(seq, 1, bout);
		return ReferenceMidi.load(Path.of("synthetic.mid"), bout.toByteArray());
	}

	/**
	 * 8마디 4/4 합성 곡: 트랙 1 템포·박자만, 2 "Vocal"(높은 단선율), 3 "Bass"(낮은 단선율), 4 "Keys"(가운데 3화음, 8분음표),
	 * 5 "Strings"(온음표 3화음), 6 드럼(채널 10). 시험(e)의 MCP 도구 시험도 이 파일을 쓴다.
	 */
	public static byte[] ensembleBytes() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, RES);
		Track cond = seq.createTrack();
		cond.add(new MidiEvent(meta(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }), 0));
		cond.add(new MidiEvent(meta(0x58, new byte[] { 4, 2, 24, 8 }), 0));
		Track vocal = named(seq, "Vocal");
		Track bass = named(seq, "Bass");
		Track keys = named(seq, "Keys");
		Track pad = named(seq, "Strings");
		Track drums = named(seq, "Drums");
		int[] mel = { 72, 74, 76, 77, 79, 77, 76, 74 };
		for (int m = 0; m < 8; m++) {
			long bs = (long) m * BAR;
			for (int b = 0; b < 4; b++) {
				long t = bs + (long) b * RES;
				note(vocal, 0, mel[(m + b) % mel.length], 100, t, t + RES - 20);
				note(keys, 2, 60, 80, t, t + RES / 2);
				note(keys, 2, 64, 80, t, t + RES / 2);
				note(keys, 2, 67, 80, t, t + RES / 2);
				note(drums, 9, 36, 100, t, t + 60);
			}
			note(bass, 1, 36, 90, bs, bs + 2 * RES);
			note(bass, 1, 43, 90, bs + 2 * RES, bs + 4 * RES);
			note(pad, 3, 55, 70, bs, bs + BAR);
			note(pad, 3, 59, 70, bs, bs + BAR);
			note(pad, 3, 62, 70, bs, bs + BAR);
		}
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		MidiSystem.write(seq, 1, bout);
		return bout.toByteArray();
	}

	// (a) 멜로디 점수 순위와 역할
	@Test
	public void melodyScoreAndRoles() throws Exception {
		ReferenceMidi m = ReferenceMidi.load(Path.of("ensemble.mid"), ensembleBytes());
		assertEquals(6, m.trackCount());
		List<ReferenceHints.TrackHint> h = m.trackHints();
		ReferenceHints.TrackHint cond = h.get(0);
		ReferenceHints.TrackHint vocal = h.get(1);
		ReferenceHints.TrackHint bass = h.get(2);
		ReferenceHints.TrackHint keys = h.get(3);
		ReferenceHints.TrackHint pad = h.get(4);
		ReferenceHints.TrackHint drums = h.get(5);

		assertEquals("unknown", cond.roleGuess());
		assertEquals(0.0, cond.melodyScore(), 0);
		assertNull(cond.melodyRank());

		assertEquals("melody", vocal.roleGuess());
		assertEquals(Integer.valueOf(1), vocal.melodyRank());
		assertEquals(1.0, vocal.components().nameKeyword(), 0);
		assertEquals(1.0, vocal.components().monophony(), 1e-9);
		assertEquals(1.0, vocal.components().coverage(), 1e-9);
		assertTrue(vocal.melodyScore() > keys.melodyScore());
		assertTrue(vocal.melodyScore() > bass.melodyScore());
		assertTrue(vocal.melodyScore() > pad.melodyScore());
		// 점수 = 가중 평균(세 자리 반올림)
		ReferenceHints.Components c = vocal.components();
		double expect = 0.35 * c.height() + 0.30 * c.monophony() + 0.20 * c.coverage() + 0.15 * c.nameKeyword();
		assertEquals(expect, vocal.melodyScore(), 0.002);

		assertEquals("bass", bass.roleGuess());
		assertEquals(0.0, bass.components().height(), 0);
		assertEquals("chords", keys.roleGuess());
		assertEquals(3.0, keys.evidence().meanGroupSize(), 1e-9);
		assertEquals("pad", pad.roleGuess());
		assertEquals("drums", drums.roleGuess());
		assertEquals(0.0, drums.melodyScore(), 0);
		assertNull(drums.melodyRank());

		// 열쇠말: 낱말 전체가 같아야 하는 짧은 것
		assertEquals("vo", ReferenceHints.keyword("Vo. 1"));
		assertEquals("female", ReferenceHints.keyword("Female Vo."));
		assertNull(ReferenceHints.keyword("Element Guitar"));
		assertEquals("보컬", ReferenceHints.keyword("메인 보컬"));
	}

	// (b) 서스테인 페달 끝
	@Test
	public void pedalEnd() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, RES);
		Track t = named(seq, "Piano");
		cc(t, 0, 64, 127, 0);
		note(t, 0, 60, 100, 0, 240); // 페달 중 → 다음 C4 시작(1200)까지
		note(t, 0, 64, 100, 480, 720); // 페달 중 → 해제(1440)까지
		note(t, 0, 60, 100, 1200, 1300); // 페달 중 → 해제(1440)까지
		cc(t, 0, 64, 0, 1440);
		note(t, 0, 67, 100, 1920, 2160); // 페달 없음
		cc(t, 0, 64, 127, 2400);
		note(t, 0, 69, 100, 2400, 2640); // note-off와 같은 틱에 해제 → 늘지 않음
		cc(t, 0, 64, 0, 2640);
		note(t, 1, 72, 100, 0, 240); // 다른 채널: 페달 없음
		ReferenceMidi m = load(seq);
		List<Note> n = new ArrayList<>(m.notes(1, 1, 10));
		n.sort(java.util.Comparator.comparingLong(Note::srcTick).thenComparingInt(Note::channel));
		Note c1 = n.get(0);
		assertEquals(60, c1.pitch());
		assertTrue(c1.pedal());
		assertEquals(1200, c1.srcPedalEnd());
		assertEquals(240, c1.pedalEnd96());
		Note other = n.get(1);
		assertEquals(72, other.pitch());
		assertFalse(other.pedal());
		assertEquals(other.end96(), other.pedalEnd96());
		Note e4 = n.get(2);
		assertEquals(64, e4.pitch());
		assertEquals(1440, e4.srcPedalEnd());
		assertEquals(288, e4.pedalEnd96());
		Note c2 = n.get(3);
		assertEquals(1200, c2.srcTick());
		assertEquals(1440, c2.srcPedalEnd());
		Note g4 = n.get(4);
		assertFalse(g4.pedal());
		assertEquals(2160, g4.srcPedalEnd());
		Note a4 = n.get(5);
		assertFalse(a4.pedal());
		assertEquals(a4.srcEnd(), a4.srcPedalEnd());
		for (Note x : n) {
			assertTrue(x.pedalEnd96() >= x.end96());
		}
		// 페달은 여전히 버린 이벤트로 센다(경고 수는 전과 같음)
		assertEquals(Integer.valueOf(4), m.warnings().droppedEvents().get("CC#64(서스테인)"));
	}

	// (c) 박마다 격자: 16분음표 박은 straight, 셋잇단 8분음표 박은 triplet, 빈 박은 none
	@Test
	public void gridHints() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, RES);
		Track t = named(seq, "Lead");
		for (int i = 0; i < 4; i++) {
			note(t, 0, 72, 100, i * 120L, i * 120L + 100);
		}
		for (int i = 0; i < 3; i++) {
			note(t, 0, 74, 100, 480 + i * 160L, 480 + i * 160L + 140);
		}
		note(t, 0, 76, 100, 1440, 1900);
		ReferenceMidi m = load(seq);
		List<ReferenceHints.GridHint> g = ReferenceHints.gridHints(m, m.notes(1, 1, 1), 1, 1,
				ReferenceHints.DEFAULT_TRIPLET_RATIO, ReferenceHints.DEFAULT_STRAIGHT_TOL);
		assertEquals(4, g.size());
		assertEquals("straight", g.get(0).grid());
		assertEquals(4, g.get(0).onsets());
		assertEquals(0.0, g.get(0).straightErr(), 1e-9);
		assertEquals(8.0, g.get(0).tripletErr(), 0.01); // 0, 24→8(32), 48→16, 72→8(64)
		assertEquals("triplet", g.get(1).grid());
		assertEquals(3, g.get(1).onsets());
		assertEquals(5.33, g.get(1).straightErr(), 0.01);
		assertEquals(0.0, g.get(1).tripletErr(), 1e-9);
		assertEquals("none", g.get(2).grid());
		assertEquals(0, g.get(2).onsets());
		assertNull(g.get(2).straightErr());
		assertEquals("straight", g.get(3).grid());
		// 기준을 느슨하게: straight_tol 6이면 셋잇단 박도 straight
		List<ReferenceHints.GridHint> loose = ReferenceHints.gridHints(m, m.notes(1, 1, 1), 1, 1, 0.6, 6.0);
		assertEquals("straight", loose.get(1).grid());
		ReferenceHints.GridSummary s = ReferenceHints.gridSummary(g);
		assertEquals(2, s.straight());
		assertEquals(1, s.triplet());
		assertEquals(1, s.none());
		assertEquals(List.of("1:2:0"), s.tripletBeats());
	}

	// (d) 성부 초안: 4음 화음 → 3성부(위·아래 유지, 하나 버림, 새 음이름 우선), 겹침 끊기, 세기 변환
	@Test
	public void voiceDraft() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, RES);
		Track a = named(seq, "Strings A");
		Track b = named(seq, "Strings B");
		// 묶음 1(0틱, 2박 길이): C5 G4 E4 C4 → 1성부 C5, 3성부 C4, 가운데는 2성부 기준음 60에 가까운 E4, G4는 버림
		for (int p : new int[] { 72, 67, 64 }) {
			note(a, 0, p, 100, 0, 960);
		}
		note(b, 1, 60, 127, 0, 960);
		note(b, 1, 72, 50, 0, 480); // 같은 시작·같은 음(C5) → duplicate
		// 묶음 2(480틱): G5 A#4 C4 C3 → 2성부 앞 음 64에서 C4(4)가 A#4(6)보다 가깝지만 C는 C3에 이미 있음 → A#4
		note(a, 0, 79, 1, 480, 960);
		note(a, 0, 70, 100, 480, 960);
		note(a, 0, 60, 100, 480, 960);
		note(b, 1, 48, 100, 480, 960);
		ReferenceMidi m = load(seq);
		List<Note> all = new ArrayList<>(m.notesAll(1, 1));
		VoiceDraft.Result r = VoiceDraft.draft(all, m.measureMap96(), 3, VoiceDraft.DEFAULT_HOMES_3, 2000, false);
		assertEquals(2, r.groups());
		assertEquals(3, r.voices().size());
		List<VoiceDraft.DraftNote> v1 = r.voices().get(0).notes();
		List<VoiceDraft.DraftNote> v2 = r.voices().get(1).notes();
		List<VoiceDraft.DraftNote> v3 = r.voices().get(2).notes();
		assertEquals("melody", r.voices().get(0).partSuggestion());
		assertEquals("chord1", r.voices().get(1).partSuggestion());
		assertEquals("chord2", r.voices().get(2).partSuggestion());
		assertEquals(List.of(72, 79), v1.stream().map(VoiceDraft.DraftNote::pitch).toList());
		assertEquals(List.of(64, 70), v2.stream().map(VoiceDraft.DraftNote::pitch).toList());
		assertEquals(List.of(60, 48), v3.stream().map(VoiceDraft.DraftNote::pitch).toList());
		// 겹침 끊기: 묶음 1의 세 음이 192틱에서 96틱으로
		assertEquals(3, r.trimmed());
		for (List<VoiceDraft.DraftNote> l : List.of(v1, v2, v3)) {
			assertEquals(96, l.get(0).len());
			assertEquals(96, l.get(1).len());
			for (int i = 0; i + 1 < l.size(); i++) {
				assertTrue(l.get(i).tick() + l.get(i).len() <= l.get(i + 1).tick());
			}
		}
		// 세기: round(v/127×15), 1~15. C5는 큰 세기(100)가 남는다
		assertEquals(12, v1.get(0).vel());
		assertEquals(1, v1.get(1).vel());
		assertEquals(15, v3.get(0).vel());
		assertEquals("1:1:0", v1.get(0).pos());
		assertEquals("1:2:0", v1.get(1).pos());
		// 버린 음: duplicate C5, G4(새 음이름이지만 빈 성부 없음), C4(같은 음이름)
		assertEquals(3, r.dropped().size());
		assertTrue(r.dropped().stream().anyMatch(d -> d.pitch() == 72 && d.reason().equals("duplicate")));
		assertTrue(r.dropped().stream().anyMatch(d -> d.pitch() == 67 && d.reason().equals("no_free_voice")));
		assertTrue(r.dropped().stream().anyMatch(d -> d.pitch() == 60 && d.tick() == 96
				&& d.reason().equals("same_pitch_class")));
		assertTrue(r.summaryText(), r.summaryText().startsWith("3성부 초안: 멜로디 2음, 화음1 2음, 화음2 2음, 버린 음 3"));
		assertFalse(r.truncated());

		// 성부 1개: 가장 높은 음만
		VoiceDraft.Result one = VoiceDraft.draft(all, m.measureMap96(), 1, VoiceDraft.DEFAULT_HOMES_1, 2000, false);
		assertEquals(List.of(72, 79), one.voices().get(0).notes().stream().map(VoiceDraft.DraftNote::pitch).toList());
		// 최대 음 수: 늦은 묶음부터 자른다
		VoiceDraft.Result cut = VoiceDraft.draft(all, m.measureMap96(), 3, VoiceDraft.DEFAULT_HOMES_3, 4, false);
		assertTrue(cut.truncated());
		assertEquals(Long.valueOf(96), cut.truncatedFromTick());
		int total = 0;
		for (VoiceDraft.Voice v : cut.voices()) {
			total += v.notes().size();
			assertEquals(192, v.notes().get(0).len()); // 잘린 묶음 때문에 끊지 않는다
		}
		assertEquals(3, total);
		// 집계는 남긴 범위(96틱 앞)만: 버린 음은 묶음 1의 duplicate C5·G4 둘, 끊은 음 0, 묶음 1개, 입력 5음
		assertEquals(2, cut.dropped().size());
		assertTrue(cut.dropped().stream().allMatch(d -> d.tick() < 96));
		assertEquals(0, cut.trimmed());
		assertEquals(1, cut.groups());
		assertEquals(5, cut.inputNotes());
		assertEquals("3성부 초안: 멜로디 1음, 화음1 1음, 화음2 1음, 버린 음 2, 최대 음 수 4를 넘어 틱 96부터 자름", cut.summaryText());
	}
}
