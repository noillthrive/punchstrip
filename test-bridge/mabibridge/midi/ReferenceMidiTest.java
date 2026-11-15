package mabibridge.midi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.Assume;
import org.junit.Test;

public class ReferenceMidiTest {

	// (d) MeasureMap 왕복과 96틱 환산
	@Test
	public void measureMapRoundTrip() {
		MeasureMap src = MeasureMap.of(480, List.of(new MeasureMap.Signature(0, 4, 4)));
		assertEquals(27L * 1920, src.tickOf(28, 1, 0));
		assertEquals(new MeasureMap.Position(28, 1, 0), src.position(51840));
		assertEquals(new MeasureMap.Position(28, 2, 5), src.position(51840 + 480 + 5));
		assertEquals(new MeasureMap.Position(27, 4, 479), src.position(51839));

		MeasureMap m96 = src.to96(480);
		assertEquals(27L * 384, m96.tickOf(28, 1, 0));
		assertEquals("28:3:48", m96.position(27L * 384 + 2 * 96 + 48).toString());

		assertEquals(96, MeasureMap.convTick96(480, 480));
		assertEquals(48, MeasureMap.convTick96(240, 480));
		// 5틱 → 5*96/480 = 1 → (1+3) - (4 % 6) = 0
		assertEquals(0, MeasureMap.convTick96(5, 480));
		// 15틱 → 3 → (3+3) - 0 = 6
		assertEquals(6, MeasureMap.convTick96(15, 480));
		assertEquals(10368, MeasureMap.convTick96(51840, 480));
	}

	@Test
	public void measureMapTimeSignatureChange() {
		// 4/4 2마디 뒤 3/4, 그다음 마디 중간(3/4 1박 뒤)에 6/8
		MeasureMap m = MeasureMap.of(96, List.of(new MeasureMap.Signature(0, 4, 4),
				new MeasureMap.Signature(768, 3, 4), new MeasureMap.Signature(768 + 288 + 96, 6, 8)));
		assertEquals(3, m.measureOf(768));
		assertEquals(3, m.numeratorAt(3));
		assertEquals(768 + 288, m.measureStart(4));
		// 4마디는 6/8 변경 때문에 1박(96틱)짜리 짧은 마디
		assertEquals(768 + 288 + 96, m.measureEnd(4));
		assertEquals(1, m.beatsInMeasure(4));
		assertEquals(5, m.measureOf(768 + 288 + 96));
		assertEquals(48, m.beatLength(5));
		assertEquals(6, m.beatsInMeasure(5));
		assertEquals(List.of(768L + 288 + 96), m.irregularChanges());
		long t = m.tickOf(5, 4, 10);
		assertEquals(new MeasureMap.Position(5, 4, 10), m.position(t));
	}

	// 합성 MIDI로 음 짝짓기 규칙 확인
	@Test
	public void noteMatchingRules() throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track t = seq.createTrack();
		t.add(new MidiEvent(meta(0x03, "Lead".getBytes("UTF-8")), 0));
		t.add(new MidiEvent(meta(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }), 0)); // 500000µs = 120 BPM
		t.add(new MidiEvent(meta(0x58, new byte[] { 3, 2, 24, 8 }), 0)); // 3/4
		t.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 40, 0), 0));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 0), 480)); // vel 0 = off
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 64, 127), 480));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 64, 90), 720)); // 재타격
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 64, 0), 960));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 67, 8), 1440)); // 끝나지 않음
		t.add(new MidiEvent(new ShortMessage(ShortMessage.PITCH_BEND, 0, 0, 64), 1440));
		t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 72, 0), 1500)); // 짝 없음
		Track drums = seq.createTrack();
		drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
		drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 60));
		t.add(new MidiEvent(meta(0x2F, new byte[0]), 1920));

		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		MidiSystem.write(seq, 1, bout);
		ReferenceMidi m = ReferenceMidi.load(Path.of("synthetic.mid"), bout.toByteArray());

		assertEquals(2, m.trackCount());
		assertEquals(120.0, m.initialBpm(), 0.001);
		List<Note> n = m.notes(1, 1, 100);
		assertEquals(4, n.size());
		assertEquals("C4", n.get(0).pitchName());
		assertEquals(1, n.get(0).channel());
		assertEquals(480, n.get(0).srcLength());
		assertEquals(96, n.get(0).len96());
		assertEquals("1:1:0", n.get(0).pos());
		assertEquals(12, n.get(0).velocity15());
		// 재타격: 앞 음은 720에서 끝, 새 음은 720~960
		assertEquals("E4", n.get(1).pitchName());
		assertEquals(240, n.get(1).srcLength());
		assertEquals(15, n.get(1).velocity15());
		assertEquals(720, n.get(2).srcTick());
		assertEquals(240, n.get(2).srcLength());
		// 3/4: 1440틱 = 2마디 1박
		assertEquals(2, n.get(3).measure());
		assertEquals("2:1:0", n.get(3).pos());
		assertEquals(1, n.get(3).velocity15());
		assertEquals(1920 - 1440, n.get(3).srcLength());

		Warnings w = m.warnings();
		assertEquals(1, w.retriggers());
		assertEquals(1, w.unfinishedNotes());
		assertEquals(1, w.orphanNoteOffs());
		assertEquals(Integer.valueOf(1), w.droppedEvents().get("피치 벤드"));
		assertEquals(Integer.valueOf(40), m.track(1).channelPrograms().get(0).programAtFirstNote());
		assertTrue(m.track(2).drum());

		List<BeatChord> c = m.chordsByBeat(1, 1, Set.of(1, 2));
		assertEquals(3, c.size());
		assertEquals(Set.of("C"), c.get(0).pitchClasses()); // 드럼 제외
		assertEquals(Set.of("E"), c.get(1).pitchClasses());
		List<BeatChord> withDrums = m.chordsByBeat(1, 1, null, true);
		assertTrue(withDrums.get(0).notes().stream().anyMatch(Note::isDrum));
		assertNotNull(c.get(0).bass());
	}

	@Test
	public void byteScannerFindsBadDataByte() throws Exception {
		// MThd + MTrk: delta 0, note-on 90 3C 80(데이터 바이트 0x80 이상)
		byte[] data = {
			'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0x01, (byte) 0xE0,
			'M', 'T', 'r', 'k', 0, 0, 0, 8, 0x00, (byte) 0x90, 0x3C, (byte) 0x80, 0x00, (byte) 0xFF, 0x2F, 0x00
		};
		MidiByteScanner.Result r = MidiByteScanner.scan(data);
		assertFalse(r.ok());
		assertEquals(1, r.firstProblem().trackNumber());
		assertEquals(25, r.firstProblem().fileOffset());
		assertEquals(0, r.firstProblem().tickBefore());
	}

	@Test
	public void pitchNames() {
		assertEquals("C4", MidiNames.pitchName(60));
		assertEquals("C-1", MidiNames.pitchName(0));
		assertEquals("A#3", MidiNames.pitchName(58));
		assertEquals("G9", MidiNames.pitchName(127));
	}

	private static MetaMessage meta(int type, byte[] data) throws Exception {
		return new MetaMessage(type, data, data.length);
	}
}
