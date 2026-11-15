package mabibridge.compare;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.Project;
import mabibridge.mcp.CompareHook;
import mabibridge.mcp.CompareOptions;
import mabibridge.mcp.MappingEntry;
import mabibridge.midi.ReferenceMidi;

/**
 * MCP 층({@code mabibridge.mcp})에 {@link Comparer}를 꽂는 얇은 어댑터. {@code mabibridge.mcp.CompareHooks}가
 * 반사로 찾는다(생성자 {@code (BridgeEngine)} 우선, 없으면 인자 없음).
 *
 * <p>{@code compare}는 DLS 로딩 계기다: DLS를 아직 읽지 않았으면 이 객체에서 한 번만 {@code ensureDls()}를
 * 시도하고, 실패하면 악기표(properties) 음역으로 진행한다. {@code summary}(쓰기 도구마다 불림)는 DLS를 읽으려 하지 않고
 * 지금 상태를 그대로 쓴다.</p>
 */
public final class CompareHookImpl implements CompareHook {

	private final BridgeEngine engine;
	private InstrumentTable ownTable;
	private boolean dlsTried;

	/** MCP 층이 쓰는 생성자. 엔진의 악기표와 DLS 상태를 쓴다. */
	public CompareHookImpl(BridgeEngine engine) {
		this.engine = engine;
	}

	/** 엔진 없이. 악기표를 직접 읽고, DLS가 이미 읽혀 있으면 그 음역을 쓴다(DLS를 직접 읽지는 않는다). */
	public CompareHookImpl() {
		this(null);
	}

	/** 선택 인자 없이(도구 기본: 항목 값 또는 sounding·마디 차 0). */
	@Override
	public Object compare(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, int from, int to,
			Set<Integer> scoreTracks) {
		return compare(p, ref, mapping, from, to, scoreTracks, CompareOptions.NONE);
	}

	@Override
	public Object summary(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping,
			List<Integer> changedMeasures) {
		return summary(p, ref, mapping, changedMeasures, CompareOptions.NONE);
	}

	@Override
	public Object suggestMapping(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks) {
		return suggestMapping(p, ref, from, to, scoreTracks, CompareOptions.NONE);
	}

	@Override
	public Object compare(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, int from, int to,
			Set<Integer> scoreTracks, CompareOptions options) {
		tryDls();
		return Comparer.compare(p, ref, toMapping(mapping), from, to, scoreTracks, table(), BridgeEngine.dlsLoaded(),
				toOptions(options));
	}

	@Override
	public Object summary(Project p, ReferenceMidi ref, Map<Integer, MappingEntry> mapping, List<Integer> changedMeasures,
			CompareOptions options) {
		return Comparer.summary(p, ref, toMapping(mapping), changedMeasures, table(), BridgeEngine.dlsLoaded(),
				toOptions(options));
	}

	@Override
	public Object suggestMapping(Project p, ReferenceMidi ref, int from, int to, Set<Integer> scoreTracks,
			CompareOptions options) {
		return MappingSuggester.suggest(p, ref, from, to, scoreTracks, table(), toOptions(options));
	}

	/** {@code mcp.CompareOptions} → {@code Comparer.Options}. null이면 도구 기본. */
	static Comparer.Options toOptions(CompareOptions o) {
		if (o == null) {
			return Comparer.Options.DEFAULTS;
		}
		return new Comparer.Options(o.pitchMode(), o.measureOffset(), o.measureOffsetAuto(), o.tolerant(),
				o.autoOffsetMax() == null ? Comparer.AUTO_MAX : o.autoOffsetMax());
	}

	/** DLS를 아직 읽지 않았으면 한 번만 시도한다. */
	private void tryDls() {
		if (engine == null || BridgeEngine.dlsLoaded() || dlsTried) {
			return;
		}
		dlsTried = true;
		try {
			engine.ensureDls();
		} catch (EngineException e) {
			System.err.println("[compare] DLS를 읽지 못해 악기표(properties) 음역으로 검사: " + e.getMessage());
		}
	}

	private InstrumentTable table() {
		if (engine != null) {
			return engine.instrumentTable();
		}
		if (ownTable == null) {
			ownTable = InstrumentTable.load();
		}
		if (BridgeEngine.dlsLoaded()) {
			ownTable.refineWithDls();
		}
		return ownTable;
	}

	/** {@code mcp.MappingEntry} → {@code compare.Mapping}. null이면 빈 대응표. */
	static Mapping toMapping(Map<Integer, MappingEntry> m) {
		if (m == null || m.isEmpty()) {
			return Mapping.empty();
		}
		Map<Integer, Mapping.Entry> out = new TreeMap<>();
		for (Map.Entry<Integer, MappingEntry> e : m.entrySet()) {
			MappingEntry me = e.getValue();
			if (e.getKey() == null || me == null) {
				continue;
			}
			out.put(e.getKey(), new Mapping.Entry(me.melodyFrom(), me.chordsFrom(), me.matchParts(), me.pitchMode(),
					me.measureOffset()));
		}
		return new Mapping(out);
	}
}
