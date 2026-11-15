package mabibridge.engine;

import java.util.List;
import java.util.Map;

/**
 * 트랙 하나의 글자 수 통계. 두 기준을 항상 함께 담는다.
 *
 * @param chars       실제 마비노기용 MML(MML@…;)에 들어가는 파트별 글자 수. song은 생성된 노래 파트 글자 수
 *                    (트랙이 노래 제외 설정이면 MML에는 들어가지 않는다)
 * @param limits      두 기준의 한도와 초과 파트
 * @param defaultRule 기본 판정 기준("song_excluded" 또는 "song_included")
 * @param rankText    {@code MMLTrack.mmlRankFormat()}
 * @param playTimeSec {@code MMLTrack.getPlayTime()}
 * @param sounding    트랙 악기의 "적은 음 대 들리는 음" 요약({@link SoundingPitch#summary})
 * @param soundingPitchMin 모든 파트 음의 들리는 음 중 가장 낮은 것(MIDI, 노래 파트는 노래 악기 기준). 음높이 있는 음이 없으면 null
 * @param soundingNameMin  그 음 이름
 * @param soundingPitchMax 가장 높은 들리는 음
 * @param soundingNameMax  그 음 이름
 * @param silentNotes      무음 구간(베이스 56~61 등)에 적혀 소리가 나지 않는 음 수
 */
public record TrackStats(int track, String name, int program, Map<String, Integer> chars, Limits limits,
		String defaultRule, String rankText, double playTimeSec, String sounding, Integer soundingPitchMin,
		String soundingNameMin, Integer soundingPitchMax, String soundingNameMax, int silentNotes) {

	public record Limits(SongExcluded songExcluded, SongIncluded songIncluded) {}

	/**
	 * 노래 제외 기준: RANK_1 + 멜로디/3(1600/1200/900). over에는 글자 수 초과 파트만 넣는다.
	 * songNotAllowed는 노래 파트가 비어 있지 않은지(이 기준에서는 노래 파트를 실을 수 없음). 글자 수 초과와는 다른 판정이다.
	 */
	public record SongExcluded(int melody, int chord1, int chord2, List<String> over, boolean songNotAllowed) {}

	/** 노래 포함 기준: RANK_1 그대로(1200/800/500, 노래 1200). */
	public record SongIncluded(int melody, int chord1, int chord2, int song, List<String> over) {}

	/** 기본 기준에서 초과한 파트. */
	public List<String> overDefault() {
		return "song_included".equals(defaultRule) ? limits.songIncluded().over() : limits.songExcluded().over();
	}
}
