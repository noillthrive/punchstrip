package mabibridge.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.Project;
import mabibridge.midi.ReferenceMidi;

/**
 * MCP 서버 상태. 엔진(프로젝트·스냅샷), 참조 MIDI, compare 대응표, compare 연결 지점.
 * 한 스레드에서만 쓴다.
 */
public final class ServerState {

	/** compare 모듈이 없을 때의 오류 코드. */
	public static final String NOT_WIRED = "NOT_WIRED";
	public static final String NOT_WIRED_MESSAGE = "compare 모듈이 아직 연결되지 않았습니다";

	public static final String NOTE_NOT_WIRED = "compare 모듈 미연결";
	public static final String NOTE_NO_REFERENCE = "참조 미로드";
	public static final String NOTE_NO_MAPPING = "매핑 없음(화음 검사만)";

	private final BridgeEngine engine;
	private CompareHook compareHook;
	private ReferenceMidi reference;
	private Map<Integer, MappingEntry> mapping = Collections.emptyMap();
	/** render_wav의 기본 출력 폴더(--render-dir). null이면 out_dir가 필수. */
	private java.nio.file.Path renderDir;

	public ServerState(BridgeEngine engine, CompareHook compareHook) {
		this.engine = engine;
		this.compareHook = compareHook;
	}

	/** render_wav 기본 출력 폴더(없으면 null). */
	public java.nio.file.Path renderDir() {
		return renderDir;
	}

	public void setRenderDir(java.nio.file.Path dir) {
		this.renderDir = dir;
	}

	public BridgeEngine engine() {
		return engine;
	}

	/** 열린 프로젝트. 없으면 NO_PROJECT. */
	public Project project() {
		return engine.project();
	}

	public CompareHook compareHook() {
		return compareHook;
	}

	public void setCompareHook(CompareHook hook) {
		this.compareHook = hook;
	}

	/** compare 모듈이 없으면 NOT_WIRED. */
	public CompareHook requireCompareHook() {
		if (compareHook == null) {
			throw new EngineException(NOT_WIRED, NOT_WIRED_MESSAGE);
		}
		return compareHook;
	}

	/** 참조 MIDI(없으면 null). */
	public ReferenceMidi reference() {
		return reference;
	}

	/** 참조 MIDI. 없으면 NO_REFERENCE. */
	public ReferenceMidi requireReference() {
		if (reference == null) {
			throw new EngineException(EngineException.NO_REFERENCE, "참조 MIDI가 없음. 먼저 reference_load를 부를 것");
		}
		return reference;
	}

	public void setReference(ReferenceMidi ref) {
		this.reference = ref;
	}

	// ---- 다시 놓은·정리한 참조(원본은 남긴다) ----

	/** reference_load가 읽은 원본 바이트와 그 해석. */
	public record ReferenceOrigin(byte[] bytes, ReferenceMidi reference) {}

	private ReferenceOrigin referenceOrigin;
	private byte[] referenceBytes;
	private JsonObject referenceRegrid;
	private final List<JsonObject> referenceCleanups = new java.util.ArrayList<>();

	/** 새 참조를 연다(원본으로 기록, 다시 놓기·정리 기록은 지움). */
	public void setReferenceOrigin(ReferenceMidi ref, byte[] bytes) {
		this.reference = ref;
		this.referenceOrigin = new ReferenceOrigin(bytes, ref);
		this.referenceBytes = bytes;
		this.referenceRegrid = null;
		this.referenceCleanups.clear();
	}

	/** 원본(없으면 null). */
	public ReferenceOrigin referenceOrigin() {
		return referenceOrigin;
	}

	/** 지금 참조의 SMF 바이트(원본 또는 다시 쓴 것). */
	public byte[] referenceBytes() {
		return referenceBytes;
	}

	/** reference_regrid 기록(다시 놓지 않았으면 null). */
	public JsonObject referenceRegrid() {
		return referenceRegrid;
	}

	/** reference_cleanup 기록(차례대로). */
	public List<JsonObject> referenceCleanups() {
		return Collections.unmodifiableList(referenceCleanups);
	}

	/** 다시 놓은 참조로 바꾼다(정리 기록은 지움). */
	public void setDerivedReference(ReferenceMidi ref, byte[] bytes, JsonObject regridInfo) {
		this.reference = ref;
		this.referenceBytes = bytes;
		this.referenceRegrid = regridInfo;
		this.referenceCleanups.clear();
	}

	/** 정리한 참조로 바꾸고 기록을 더한다. */
	public void addReferenceCleanup(ReferenceMidi ref, byte[] bytes, JsonObject entry) {
		this.reference = ref;
		this.referenceBytes = bytes;
		this.referenceCleanups.add(entry);
	}

	/** 원본으로 되돌린다. */
	public void restoreReferenceOrigin() {
		if (referenceOrigin != null) {
			this.reference = referenceOrigin.reference();
			this.referenceBytes = referenceOrigin.bytes();
		}
		this.referenceRegrid = null;
		this.referenceCleanups.clear();
	}

	/** 대응표(바꿀 수 없음, 비어 있을 수 있음). */
	public Map<Integer, MappingEntry> mapping() {
		return mapping;
	}

	public void setMapping(Map<Integer, MappingEntry> m) {
		this.mapping = Collections.unmodifiableMap(new TreeMap<>(m));
	}

	/** 대응표 JSON. */
	public JsonObject mappingJson() {
		JsonObject o = new JsonObject();
		for (Map.Entry<Integer, MappingEntry> e : mapping.entrySet()) {
			JsonObject v = new JsonObject();
			v.add("melody_from", ints(e.getValue().melodyFrom()));
			if (e.getValue().chordsFromAll()) {
				v.addProperty("chords_from", "all");
			} else {
				v.add("chords_from", ints(e.getValue().chordsFrom()));
			}
			v.addProperty("match_parts", e.getValue().matchParts());
			if (e.getValue().pitchMode() != null) {
				v.addProperty("pitch_mode", e.getValue().pitchMode());
			}
			if (e.getValue().measureOffset() != null) {
				v.addProperty("measure_offset", e.getValue().measureOffset());
			}
			o.add(String.valueOf(e.getKey()), v);
		}
		return o;
	}

	private static JsonArray ints(List<Integer> l) {
		JsonArray a = new JsonArray();
		for (Integer i : l) {
			a.add(i);
		}
		return a;
	}

	/** 쓰기 도구 응답의 compare_summary와 그 설명. */
	public record CompareSummary(JsonElement summary, String note) {}

	/**
	 * 쓰기 뒤 compare 요약. 연결 지점이 없거나 참조가 없으면 null과 이유. 요약이 실패해도 예외를 던지지 않는다
	 * (쓰기는 이미 반영됐다).
	 */
	public CompareSummary compareSummary(List<Integer> changedMeasures) {
		if (compareHook == null) {
			return new CompareSummary(JsonNull.INSTANCE, NOTE_NOT_WIRED);
		}
		if (reference == null) {
			return new CompareSummary(JsonNull.INSTANCE, NOTE_NO_REFERENCE);
		}
		try {
			Object s = compareHook.summary(engine.project(), reference, mapping, List.copyOf(changedMeasures),
					CompareOptions.NONE);
			return new CompareSummary(Json.tree(s), mapping.isEmpty() ? NOTE_NO_MAPPING : null);
		} catch (Throwable e) {
			if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) {
				throw (VirtualMachineError) e;
			}
			System.err.println("[mcp] compare 요약 실패: " + e);
			e.printStackTrace(System.err);
			return new CompareSummary(JsonNull.INSTANCE, "compare 요약 실패: " + e);
		}
	}
}
