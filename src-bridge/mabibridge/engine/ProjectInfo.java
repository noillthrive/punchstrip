package mabibridge.engine;

import java.util.List;

/**
 * 프로젝트 요약. 참조 MIDI·매핑 정보는 덧붙인다.
 *
 * @param timeSignature  틱 0에서 실제로 쓰는 박자("4/4"). 파일 머리말 time=을 [time-signature]의 틱 0 항목이 덮으면 그 값
 * @param timeSignatures 박자 변경 목록(기본 박자 포함, 틱 순서)
 * @param tempos         템포 목록. 비어 있으면 120 BPM(마비노기 기본)
 * @param totalMeasures  음표가 있는 마지막 마디(음표가 없으면 0)
 * @param totalTicks     음표 기준 곡 길이(틱)
 * @param totalSec       음표 기준 곡 길이(초)
 * @param source         연 파일 또는 마지막으로 저장한 파일(없으면 null)
 * @param dirty          마지막 저장(또는 열기) 뒤 바뀌었는지
 * @param snapshots      스냅샷 수
 * @param songRule       글자 수 기본 판정 기준
 * @param baseTime       파일 머리말의 기본 박자(time=, MabiIcco {@code MMLScore.getBaseTime})
 * @param timeSignatureOverridden baseTime을 틱 0의 박자 변경이 덮었는가(그러면 timeSignature ≠ baseTime)
 */
public record ProjectInfo(String title, String author, String timeSignature, List<TimeSig> timeSignatures,
		List<TempoView> tempos, int totalMeasures, int totalTicks, double totalSec, List<TrackSummary> tracks,
		String source, boolean dirty, int snapshots, String songRule, String baseTime,
		boolean timeSignatureOverridden, int tempoAtStart, boolean tempoAtStartDefault) {

	public record TimeSig(int tick, int measure, String signature) {}

	public record TempoView(int tick, String pos, int bpm) {}
}
