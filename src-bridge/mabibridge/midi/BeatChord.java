package mabibridge.midi;

import java.util.List;
import java.util.Set;

/**
 * 한 박 안에서 울리는(그 박에서 시작했거나 앞에서 이어지는) 음들.
 *
 * @param measure 마디(1부터)
 * @param beat 박(1부터)
 * @param pos "마디:박:0"
 * @param tick96Start 박 시작(96틱 단위)
 * @param tick96End 박 끝(96틱 단위, 배타)
 * @param pitchClasses 음이름 집합(옥타브 무시, C→B 순서). 예: [C, E, G]
 * @param bass 가장 낮은 음의 음이름(음이 없으면 null)
 * @param notes 실제 음 목록(음 낮은 순, 같으면 트랙 순)
 */
public record BeatChord(int measure, int beat, String pos, long tick96Start, long tick96End,
		Set<String> pitchClasses, String bass, List<Note> notes) {
}
