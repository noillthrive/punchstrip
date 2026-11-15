package mabibridge.engine;

import java.util.List;
import java.util.Map;

/**
 * {@code score_transform} 응답.
 *
 * @param notesAffected 바뀌거나 지워진 음 수(모든 파트 합)
 * @param byPart        파트별 바뀐 음 수
 * @param charsBefore   적용 전 실제 MML 파트 글자 수(score_mml의 chars)
 * @param charsAfter    적용 후(MabiIcco 생성기로 다시 만든 MML)
 * @param snapshot      적용 전에 자동으로 남긴 스냅샷(restore의 id)
 * @param check         적용 후 기본 랭크·기준 판정
 * @param examples      바뀐 음 예시(최대 10개)
 * @param warnings      적용 후 경고(unison_clash 등)
 */
public record TransformResult(boolean ok, int track, String op, Map<String, Object> params, List<String> parts,
		Integer fromMeasure, Integer toMeasure, int notesAffected, Map<String, Integer> byPart,
		Map<String, Integer> charsBefore, Map<String, Integer> charsAfter, Map<String, Integer> saved, int snapshotId,
		Snapshots.SnapshotInfo snapshot, CharLimits.Evaluation check, List<TransformOp.Change> examples,
		List<String> warnings) {}
