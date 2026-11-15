package mabibridge.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * compare 모듈({@code mabibridge.compare})이 MCP 층에 꽂히는 자리.
 *
 * <p>구현은 {@code mabibridge.compare.CompareHookImpl}(public 클래스)로 두거나
 * {@code META-INF/services/mabibridge.mcp.CompareHook}로 등록한다. 생성자는
 * {@code (mabibridge.engine.BridgeEngine)} 하나를 받는 것이 있으면 그것을, 없으면 인자 없는 것을 쓴다
 * ({@link CompareHooks#discover}). 반환값은 Gson으로 직렬화할 수 있어야 한다(Map, List, record, 기본형).
 * record 필드 이름은 snake_case로 바뀌어 나간다. {@code java.nio.file.Path}처럼 JDK 내부 클래스는 넣지 말 것.</p>
 *
 * <p>호출은 언제나 MCP 서버의 한 스레드에서 순서대로 온다. 예외를 던지면: {@code compare}는 그 도구의 오류 응답
 * ({@code EngineException}이면 그 코드, 그 밖은 {@code INTERNAL_ERROR})이 되고, {@code summary}는 쓰기 도구 응답의
 * {@code compare_summary}가 null이 되며 {@code compare_summary_note}에 이유가 적힌다(쓰기 자체는 이미 반영된 뒤다).</p>
 */
public interface CompareHook {

	/**
	 * {@code compare} 도구 본체. 부르기 전에 MCP 층이 DLS 로딩을 시도한다(실패해도 부른다.
	 * {@code BridgeEngine.dlsLoaded()}로 확인할 것).
	 *
	 * @param p           열린 프로젝트(null 아님)
	 * @param ref         참조 MIDI(null 아님)
	 * @param mapping     대응표(악보 트랙 번호 → 항목). 비어 있을 수 있다(그러면 화음 검사만). 바꿀 수 없는 Map
	 * @param from        시작 마디(1 이상)
	 * @param to          끝 마디(from 이상, 양끝 포함)
	 * @param scoreTracks 검사할 악보 트랙 번호들(1부터, null 아님). 도구 인자 tracks를 생략하면 모든 트랙
	 * @return 응답 객체
	 */
	Object compare(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, int from, int to,
			Set<Integer> scoreTracks);

	/**
	 * 쓰기 도구 응답의 {@code compare_summary}. 참조가 로드돼 있을 때만 불린다(매핑은 비어 있을 수 있다).
	 *
	 * @param changedMeasures 이번 쓰기로 바뀐 마디(오름차순, 비어 있을 수 있음)
	 * @return 짧은 요약 객체(null이면 응답에도 null)
	 */
	Object summary(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, List<Integer> changedMeasures);

	/**
	 * {@code compare_suggest_mapping} 본체. 저장하지 않는다. 구현하지 않은 연결 지점은 {@code NOT_WIRED}.
	 *
	 * @param p           열린 프로젝트(null 아님)
	 * @param ref         참조 MIDI(null 아님)
	 * @param from        시작 마디(1 이상)
	 * @param to          끝 마디(from 이상, 양끝 포함)
	 * @param scoreTracks 제안할 악보 트랙 번호들(1부터, null 아님)
	 * @return 악보 트랙마다 상위 3개 참조 트랙 후보
	 */
	default Object suggestMapping(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks) {
		throw new mabibridge.engine.EngineException(ServerState.NOT_WIRED,
				"이 compare 모듈은 매핑 제안(compare_suggest_mapping)을 지원하지 않습니다");
	}

	/**
	 * 선택 인자(pitch_mode, measure_offset, octave_tolerant)를 받는 {@code compare}. MCP 층은 언제나 이것을 부른다.
	 * 기본 구현은 선택 인자를 무시하고 옛 메서드를 부른다(이전 연결 지점과의 호환).
	 */
	default Object compare(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, int from, int to,
			Set<Integer> scoreTracks, CompareOptions options) {
		return compare(p, ref, mapping, from, to, scoreTracks);
	}

	/** 선택 인자를 받는 {@code summary}(쓰기 도구는 {@link CompareOptions#NONE}). 기본 구현은 옛 메서드. */
	default Object summary(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, List<Integer> changedMeasures,
			CompareOptions options) {
		return summary(p, ref, mapping, changedMeasures);
	}

	/** 선택 인자를 받는 {@code compare_suggest_mapping}. 기본 구현은 옛 메서드. */
	default Object suggestMapping(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks,
			CompareOptions options) {
		return suggestMapping(p, ref, from, to, scoreTracks);
	}
}
