package mabibridge.midi;

import java.util.List;
import java.util.Set;

/**
 * 참조 MIDI 트랙 하나의 요약.
 *
 * <p>프로그램(악기)은 (포트, 채널) 단위로 적용되므로, 이 트랙이 음을 내는 채널({@link #noteChannels})과
 * 그 채널에 실제로 프로그램 변경을 보낸 트랙({@link ChannelProgram#sourceTracks})을 따로 보여 준다.
 * 이 트랙이 보낸 프로그램 변경은 {@link #sentProgramChanges}에 있으며, 그 대상 채널이
 * 이 트랙의 음 채널이 아닐 수도 있다(다른 트랙의 악기를 덮어쓰는 구조, {@link #overridesTracks}).</p>
 *
 * @param number 트랙 번호(1부터)
 * @param name 트랙 이름(meta 3, 없으면 "Track N")
 * @param nameFromMeta 이름이 meta 3에서 왔는가
 * @param ports 이 트랙의 채널 메시지가 나간 MIDI 포트(meta 0x21, 없으면 0)
 * @param channels 이 트랙이 채널 메시지를 보낸 채널 전부(1~16)
 * @param noteChannels 이 트랙의 note-on 채널(1~16)
 * @param sentProgramChanges 이 트랙이 보낸 프로그램 변경(대상 포트·채널 포함)
 * @param channelPrograms 이 트랙의 음 (포트, 채널)별 프로그램 정보(모든 트랙이 보낸 변경 기준)
 * @param overridesTracks 이 트랙이 보낸 프로그램 변경이 악기를 바꾸는 다른 트랙들(같은 포트·채널에서 음을 내는 트랙)
 * @param channelProgramsIgnoringPorts {@code channelPrograms}를 포트를 무시하고(모든 포트를 하나로 보고) 다시 계산한 것.
 *        포트를 모르는 재생기(MabiIcco 재생, Java 기본 신스 등)에서 들리는 악기. 이때 port는 -1
 * @param overridesTracksIgnoringPorts {@code overridesTracks}를 포트를 무시하고 계산한 것
 * @param mabiImportProgram MabiIcco MIDI 가져오기 규칙으로 이 트랙에 붙을 프로그램
 *        (이 트랙 안의 마지막 프로그램 변경 값, 채널은 무시. 없으면 null)
 * @param mabiImportDrum MabiIcco 가져오기 규칙으로 드럼 트랙이 되는가(이 트랙의 마지막 프로그램 변경이 채널 10)
 * @param drum 채널 10에서 음을 내는가
 * @param noteCount 음표 수
 * @param lowest 최저음 MIDI 번호(음이 없으면 null)
 * @param lowestName 최저음 이름(음이 없으면 null)
 * @param highest 최고음 MIDI 번호(음이 없으면 null)
 * @param highestName 최고음 이름(음이 없으면 null)
 * @param firstMeasure 첫 음표의 마디(음이 없으면 null)
 * @param lastMeasure 마지막(시작이 가장 늦은) 음표의 마디(음이 없으면 null)
 * @param soundingRatio 한 음이라도 울리는 시간 / 곡 길이(0~1, 틱 기준)
 * @param endTick 트랙 끝 틱(End of Track, 원본 해상도)
 */
public record TrackInfo(int number, String name, boolean nameFromMeta, Set<Integer> ports, Set<Integer> channels,
		Set<Integer> noteChannels, List<ProgramChange> sentProgramChanges, List<ChannelProgram> channelPrograms,
		Set<Integer> overridesTracks, List<ChannelProgram> channelProgramsIgnoringPorts,
		Set<Integer> overridesTracksIgnoringPorts, Integer mabiImportProgram, boolean mabiImportDrum, boolean drum, int noteCount,
		Integer lowest, String lowestName, Integer highest, String highestName, Integer firstMeasure,
		Integer lastMeasure, double soundingRatio, long endTick) {

	/**
	 * 프로그램 변경 하나.
	 *
	 * @param srcTick 원본 해상도 틱
	 * @param pos "마디:박:틱"(96틱 기준)
	 * @param sourceTrack 이 변경을 보낸 트랙(1부터)
	 * @param port 대상 MIDI 포트(meta 0x21, 없으면 0)
	 * @param channel 대상 채널(1~16)
	 * @param program 프로그램 번호(0~127)
	 * @param gmName GM 악기 이름(채널 10이면 드럼 킷 번호)
	 */
	public record ProgramChange(long srcTick, String pos, int sourceTrack, int port, int channel, int program,
			String gmName) {}

	/**
	 * 한 (포트, 채널)의 프로그램 정보. 프로그램 변경은 같은 포트·같은 채널에만 적용된다.
	 *
	 * @param port MIDI 포트(-1 = 포트를 무시하고 계산한 값)
	 * @param channel 채널(1~16)
	 * @param programAtFirstNote 이 트랙이 이 채널에서 첫 음을 낼 때 적용 중인 프로그램(변경이 없었으면 null = GM 기본 0)
	 * @param finalProgram 이 채널의 마지막 프로그램 변경 값(곡 전체, 어느 트랙이 보냈든. 없으면 null)
	 * @param sourceTracks 이 채널에 프로그램 변경을 보낸 트랙들
	 * @param overriddenByOtherTrack 이 트랙이 아닌 트랙이 이 채널의 프로그램을 바꿨는가
	 * @param ambiguous 같은 틱에 여러 트랙이 서로 다른 프로그램을 보내, 실제 적용 순서가 재생기에 따라 달라질 수 있는가
	 *        (이때 programAtFirstNote·finalProgram은 트랙 번호가 큰 쪽이 나중에 온다고 가정한 값)
	 * @param changes 이 채널에 대한 모든 프로그램 변경(틱 순, 같은 틱이면 트랙 순)
	 */
	public record ChannelProgram(int port, int channel, Integer programAtFirstNote, Integer finalProgram,
			Set<Integer> sourceTracks, boolean overriddenByOtherTrack, boolean ambiguous, List<ProgramChange> changes) {}
}
