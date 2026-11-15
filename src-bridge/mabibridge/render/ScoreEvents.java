package mabibridge.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import jp.fourthline.mabiicco.midi.MabiDLS;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;

/**
 * 엔진이 만든 재생용 시퀀스에서 음표(노트온·노트오프 짝)를 꺼낸다.
 *
 * <p>{@code MabiDLS.createSequenceOnSepChannel(score)}를 그대로 부른다. 채널 = 트랙×4 + 파트, 세기 변환, 음높이 +12,
 * 음 시작 지연 보정, 음표 단위 뮤트는 모두 엔진 규칙 그대로다. 트랙 0(템포 메타)과 PROGRAM_CHANGE는 버린다
 * (채널 프로그램은 {@link OfflineRenderer}가 앱의 changeProgram 규칙으로 정한다).</p>
 */
public final class ScoreEvents {

	/** 엔진은 모든 음을 1틱 늦게 시작시키고 1틱 짧게 끝낸다({@code convertMidiPart(..., startOffset=1, ...)}). */
	public static final int NOTE_START_OFFSET_TICK = 1;

	/**
	 * 음표 하나(MIDI 기준).
	 *
	 * @param channel   0~95(트랙 인덱스×4 + 파트)
	 * @param track     1부터
	 * @param part      0 멜로디, 1·2 화음, 3 노래
	 * @param note      MIDI 음 번호(엔진 변환 뒤)
	 * @param velocity  MIDI 세기(엔진 변환 뒤, 0~127)
	 * @param onTick    노트온 틱(악보 절대 틱, +1 지연과 시작 지연 보정 반영)
	 * @param offTick   노트오프 틱
	 * @param startTick 악보상 음 시작 틱(= onTick − 1 − 시작 지연 보정). 범위 판정에 쓴다
	 */
	public record Note(int channel, int track, int part, int note, int velocity, long onTick, long offTick,
			long startTick) {}

	private ScoreEvents() {}

	/** 악보의 모든 음표. DLS가 읽혀 있어야 한다(엔진이 악기 타입으로 쓸 파트·세기를 정한다). */
	public static List<Note> extract(MMLScore score) throws InvalidMidiDataException {
		Sequence seq = MabiDLS.getInstance().createSequenceOnSepChannel(score);
		Track[] tracks = seq.getTracks();
		List<Note> out = new ArrayList<>();
		for (int ti = 1; ti < tracks.length; ti++) { // 트랙 0 = 템포 메타
			Track t = tracks[ti];
			Map<Integer, Deque<long[]>> pending = new HashMap<>();
			for (int i = 0; i < t.size(); i++) {
				MidiEvent ev = t.get(i);
				MidiMessage m = ev.getMessage();
				if (!(m instanceof ShortMessage sm)) {
					continue; // 끝 메타 등
				}
				int cmd = sm.getCommand();
				int ch = sm.getChannel(); // ExtendMessage는 16 이상 채널을 그대로 돌려준다
				if (cmd == ShortMessage.NOTE_ON) {
					int key = (ch << 8) | sm.getData1();
					pending.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new long[] { ev.getTick(), sm.getData2() });
				} else if (cmd == ShortMessage.NOTE_OFF) {
					int key = (ch << 8) | sm.getData1();
					Deque<long[]> q = pending.get(key);
					if (q == null || q.isEmpty()) {
						continue;
					}
					long[] on = q.removeFirst();
					int trackIndex = ch / 4;
					int part = ch % 4;
					int correct = 0;
					if (trackIndex < score.getTrackCount()) {
						MMLTrack mt = score.getTrack(trackIndex);
						correct = mt.getAttackDelayCorrect(part);
					}
					long start = on[0] - NOTE_START_OFFSET_TICK - correct;
					out.add(new Note(ch, trackIndex + 1, part, sm.getData1(), (int) on[1], on[0], ev.getTick(), start));
				}
				// PROGRAM_CHANGE와 그 밖은 버린다
			}
		}
		out.sort((a, b) -> {
			int c = Long.compare(a.onTick(), b.onTick());
			return c != 0 ? c : Integer.compare(a.channel(), b.channel());
		});
		return Collections.unmodifiableList(out);
	}
}
