package mabibridge.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import mabibridge.engine.EngineException;
import mabibridge.engine.NoteSpec;

/**
 * 도구 인자(JSON object) 읽기. 형식이 틀리면 {@code BAD_ARGUMENT}(음표는 {@code BAD_NOTE}).
 * null 값은 없는 것으로 본다.
 */
public final class Args {

	private final JsonObject o;

	public Args(JsonObject o) {
		this.o = (o == null) ? new JsonObject() : o;
	}

	public JsonObject json() {
		return o;
	}

	public static EngineException bad(String message, Object... kv) {
		Map<String, Object> d = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			d.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return new EngineException(EngineException.BAD_ARGUMENT, message, d);
	}

	/** 스키마에 없는 인자가 있으면 거부한다(오타로 인자가 조용히 무시되는 것을 막는다). */
	public void checkKnown(Set<String> allowed) {
		List<String> unknown = new ArrayList<>();
		for (String k : o.keySet()) {
			if (!allowed.contains(k)) {
				unknown.add(k);
			}
		}
		if (!unknown.isEmpty()) {
			throw bad("모르는 인자: " + unknown + " (쓸 수 있는 인자: " + new TreeSet<>(allowed) + ")", "unknown", unknown,
					"allowed", new TreeSet<>(allowed));
		}
	}

	public boolean has(String k) {
		return o.has(k) && !o.get(k).isJsonNull();
	}

	public JsonElement raw(String k) {
		return has(k) ? o.get(k) : null;
	}

	// ---- 정수 ----

	public int reqInt(String k) {
		if (!has(k)) {
			throw bad("인자 " + k + "가 필요함(정수)", "argument", k);
		}
		return toInt(o.get(k), k);
	}

	public Integer optInt(String k) {
		return has(k) ? toInt(o.get(k), k) : null;
	}

	public int optInt(String k, int def) {
		return has(k) ? toInt(o.get(k), k) : def;
	}

	/** 정수(또는 정수 모양 문자열 "3"). */
	static int toInt(JsonElement e, String k) {
		long v = toLong(e, k);
		if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
			throw bad(k + "가 정수 범위를 넘음", "argument", k, "value", e.toString());
		}
		return (int) v;
	}

	static long toLong(JsonElement e, String k) {
		if (e != null && e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			try {
				if (p.isNumber()) {
					return new BigDecimal(p.getAsString()).longValueExact();
				}
				if (p.isString() && p.getAsString().trim().matches("-?\\d{1,18}")) {
					return Long.parseLong(p.getAsString().trim());
				}
			} catch (ArithmeticException | NumberFormatException ex) {
				// 아래에서 거부
			}
		}
		throw bad(k + "는 정수여야 함: " + e, "argument", k, "value", String.valueOf(e));
	}

	public List<Integer> optIntList(String k) {
		if (!has(k)) {
			return null;
		}
		JsonElement e = o.get(k);
		if (!e.isJsonArray()) {
			throw bad(k + "는 정수 배열이어야 함", "argument", k);
		}
		List<Integer> l = new ArrayList<>();
		int i = 0;
		for (JsonElement x : e.getAsJsonArray()) {
			l.add(toInt(x, k + "[" + i++ + "]"));
		}
		return l;
	}

	// ---- 문자열·참거짓 ----

	public String reqString(String k) {
		String s = optString(k);
		if (s == null || s.isEmpty()) {
			throw bad("인자 " + k + "가 필요함(문자열)", "argument", k);
		}
		return s;
	}

	public String optString(String k) {
		if (!has(k)) {
			return null;
		}
		JsonElement e = o.get(k);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw bad(k + "는 문자열이어야 함", "argument", k, "value", e.toString());
		}
		return e.getAsString();
	}

	public boolean optBool(String k, boolean def) {
		if (!has(k)) {
			return def;
		}
		JsonElement e = o.get(k);
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		throw bad(k + "는 true 또는 false", "argument", k, "value", e.toString());
	}

	// ---- 배열·객체 ----

	public JsonArray reqArray(String k) {
		if (!has(k) || !o.get(k).isJsonArray()) {
			throw bad("인자 " + k + "가 필요함(배열)", "argument", k);
		}
		return o.getAsJsonArray(k);
	}

	public JsonObject reqObject(String k) {
		if (!has(k) || !o.get(k).isJsonObject()) {
			throw bad("인자 " + k + "가 필요함(객체)", "argument", k);
		}
		return o.getAsJsonObject(k);
	}

	// ---- 음표 ----

	private static final Set<String> NOTE_KEYS = Set.of("tick", "pos", "len", "pitch", "vel");

	/** 음표 배열 → NoteSpec 목록. */
	public static List<NoteSpec> notes(JsonArray a, String where) {
		List<NoteSpec> l = new ArrayList<>();
		for (int i = 0; i < a.size(); i++) {
			l.add(note(a.get(i), i, where));
		}
		return l;
	}

	static NoteSpec note(JsonElement e, int index, String where) {
		if (e == null || !e.isJsonObject()) {
			throw badNote(index, where, "음표는 객체 {tick 또는 pos, len, pitch, vel?}여야 함");
		}
		JsonObject n = e.getAsJsonObject();
		for (String k : n.keySet()) {
			if (!NOTE_KEYS.contains(k)) {
				throw badNote(index, where, "음표에 모르는 키 " + k + " (쓸 수 있는 키: tick, pos, len, pitch, vel)");
			}
		}
		Long tick = null;
		if (n.has("tick") && !n.get("tick").isJsonNull()) {
			try {
				tick = toLong(n.get("tick"), "tick");
			} catch (EngineException ex) {
				throw badNote(index, where, "tick은 정수(MML 틱, 4분음표 = 96)");
			}
		}
		String pos = null;
		if (n.has("pos") && !n.get("pos").isJsonNull()) {
			JsonElement p = n.get("pos");
			if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) {
				throw badNote(index, where, "pos는 \"마디:박:틱\" 문자열");
			}
			pos = p.getAsString();
		}
		Object len = numberOrString(n.get("len"), index, where, "len");
		Object pitch = numberOrString(n.get("pitch"), index, where, "pitch");
		Integer vel = null;
		if (n.has("vel") && !n.get("vel").isJsonNull()) {
			try {
				vel = toInt(n.get("vel"), "vel");
			} catch (EngineException ex) {
				throw badNote(index, where, "vel은 0~15 정수");
			}
		}
		return new NoteSpec(tick, pos, len, pitch, vel);
	}

	private static Object numberOrString(JsonElement e, int index, String where, String field) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isNumber()) {
				BigDecimal d = new BigDecimal(p.getAsString());
				try {
					return d.longValueExact();
				} catch (ArithmeticException ex) {
					return d.doubleValue();
				}
			}
			if (p.isString()) {
				return p.getAsString();
			}
		}
		throw badNote(index, where, field + "는 정수 또는 문자열");
	}

	private static EngineException badNote(int index, String where, String reason) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("index", index);
		d.put("part", where);
		d.put("reason", reason);
		return new EngineException(EngineException.BAD_NOTE, where + " 음표 " + index + "번(0부터): " + reason, d);
	}
}
