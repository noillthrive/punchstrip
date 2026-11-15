package mabibridge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import mabibridge.engine.EngineException;

/**
 * MCP 서버. stdio 줄 단위 JSON-RPC 2.0, 단일 스레드, 입력이 끝나면 종료한다.
 *
 * <p>지원 메서드: {@code initialize}, {@code notifications/initialized}(무응답), {@code ping}, {@code tools/list},
 * {@code tools/call}, {@code resources/list}·{@code prompts/list}(빈 목록). 그 밖은 −32601.
 * 알림(id 없는 메시지)에는 어떤 경우에도 응답하지 않는다. 배치(배열)는 각각 처리해 배열로 응답한다.</p>
 *
 * <p>도구 처리 중 예외는 {@code isError: true} 결과로 바꾼다(본문 {@code {"error","message","details"}}).
 * 서버는 {@link VirtualMachineError}(StackOverflowError 제외) 말고는 죽지 않는다.</p>
 */
public final class McpServer {

	public static final String SERVER_NAME = "mabiicco-bridge";
	public static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
	public static final String DEFAULT_PROTOCOL = "2025-06-18";
	public static final String INTERNAL = "INTERNAL_ERROR";

	static final String INSTRUCTIONS = "MabiIcco(마비노기 MML 편집기) 악보를 GUI 없이 다루는 서버. "
			+ "순서: project_new 또는 project_open → track_add → score_put_notes / score_replace_range → score_mml·score_stats로 확인 → "
			+ "project_save_as(새 파일명). 틱은 4분음표 = 96, 위치 pos는 \"마디:박:틱\", 음높이는 MIDI 번호(60 = C4) 또는 \"C4\". "
			+ "참고 MIDI는 reference_load 뒤 reference_notes·reference_chords로 본다. 큰 편집 전에는 snapshot을 남긴다. "
			+ "곡을 다 만들면 score_finalize를 한 번 부른다(앞에 머리 마디와 합주 조율용 시그니처, 끝에 세기 0 음으로 모든 파트 끝 맞춤 — "
			+ "사용자가 시그니처나 끝 맞춤을 원하지 않으면 signature: false 또는 end_padding: false). 그 뒤에는 마디 번호가 넣은 머리 마디 수만큼 커진다.";

	private final ServerState state;
	private final ToolRegistry tools;
	private final JsonRpc rpc;
	private final String version;

	private int requests;
	private int notifications;
	private int toolCalls;
	private int toolErrors;
	private int protocolErrors;
	private final Map<String, Integer> perTool = new LinkedHashMap<>();

	public McpServer(ServerState state, ToolRegistry tools, InputStream in, OutputStream out, String version) {
		this.state = state;
		this.tools = tools;
		this.rpc = new JsonRpc(in, out);
		this.version = version;
	}

	/** 입력이 끝날 때까지 돈다. */
	public void run() {
		long t0 = System.nanoTime();
		log("서버 루프 시작(도구 " + tools.size() + "개)");
		String reason = "stdin EOF";
		try {
			String line;
			while ((line = rpc.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonElement resp;
				try {
					resp = handleLine(line);
				} catch (Throwable e) {
					rethrowFatal(e);
					log("메시지 처리 중 예상 못 한 예외: " + e);
					e.printStackTrace(System.err);
					resp = JsonRpc.error(null, JsonRpc.INTERNAL_ERROR, "Internal error: " + e, null);
				}
				if (resp != null) {
					rpc.write(resp);
				}
			}
		} catch (IOException e) {
			reason = "입출력 오류: " + e;
		}
		long sec = (System.nanoTime() - t0) / 1_000_000_000L;
		log("종료(" + reason + "): " + sec + "초, 요청 " + requests + ", 알림 " + notifications + ", 도구 호출 " + toolCalls
				+ "(오류 " + toolErrors + "), 프로토콜 오류 " + protocolErrors + ", 도구별 " + perTool);
	}

	/** 한 줄 처리. 응답할 것이 없으면 null(알림, 응답 메시지, 알림만 든 배치). */
	public JsonElement handleLine(String line) {
		JsonElement msg;
		try {
			msg = Json.parseStrict(line);
		} catch (JsonParseException e) {
			protocolErrors++;
			log("JSON 파싱 실패: " + e.getMessage());
			return JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage(), null);
		}
		if (msg.isJsonArray()) {
			JsonArray batch = msg.getAsJsonArray();
			if (batch.isEmpty()) {
				protocolErrors++;
				return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request: 빈 배치", null);
			}
			JsonArray out = new JsonArray();
			for (JsonElement e : batch) {
				JsonElement r = handleElement(e);
				if (r != null) {
					out.add(r);
				}
			}
			return out.isEmpty() ? null : out;
		}
		return handleElement(msg);
	}

	private JsonElement handleElement(JsonElement e) {
		if (!e.isJsonObject()) {
			protocolErrors++;
			return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request: 객체가 아님", null);
		}
		return handleMessage(e.getAsJsonObject());
	}

	/** 메시지 객체 하나. 알림이면 null. */
	JsonObject handleMessage(JsonObject m) {
		boolean isRequest = m.has("id");
		JsonElement id = isRequest ? m.get("id") : null;
		if (isRequest && !validId(id)) {
			protocolErrors++;
			return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request: id는 문자열 또는 정수", null);
		}
		JsonElement methodEl = m.get("method");
		if (methodEl == null || !methodEl.isJsonPrimitive() || !methodEl.getAsJsonPrimitive().isString()) {
			if (m.has("result") || m.has("error")) {
				return null; // 클라이언트의 응답 메시지(우리는 요청을 보내지 않으므로 무시)
			}
			protocolErrors++;
			return isRequest ? JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Invalid Request: method가 없음", null) : null;
		}
		String method = methodEl.getAsString();
		if (!JsonRpc.VERSION.equals(str(m.get("jsonrpc")))) {
			protocolErrors++;
			return isRequest ? JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Invalid Request: jsonrpc는 \"2.0\"", null) : null;
		}
		JsonElement paramsEl = m.get("params");
		JsonObject params = (paramsEl != null && paramsEl.isJsonObject()) ? paramsEl.getAsJsonObject() : new JsonObject();
		if (!isRequest) {
			notifications++;
			// notifications/initialized, notifications/cancelled 등. 응답하지 않는다.
			return null;
		}
		requests++;
		try {
			switch (method) {
			case "initialize":
				return JsonRpc.result(id, initialize(params));
			case "ping":
				return JsonRpc.result(id, new JsonObject());
			case "tools/list": {
				JsonObject r = new JsonObject();
				r.add("tools", tools.listJson());
				return JsonRpc.result(id, r);
			}
			case "tools/call":
				return toolsCall(id, params);
			case "resources/list": {
				JsonObject r = new JsonObject();
				r.add("resources", new JsonArray());
				return JsonRpc.result(id, r);
			}
			case "prompts/list": {
				JsonObject r = new JsonObject();
				r.add("prompts", new JsonArray());
				return JsonRpc.result(id, r);
			}
			default:
				protocolErrors++;
				return JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method, null);
			}
		} catch (Throwable e) {
			rethrowFatal(e);
			protocolErrors++;
			log(method + " 처리 중 예외: " + e);
			e.printStackTrace(System.err);
			return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e, null);
		}
	}

	private static boolean validId(JsonElement id) {
		if (id == null || id.isJsonNull()) {
			return true; // JSON-RPC 2.0은 null id를 허용한다(권장하지 않음). 그대로 돌려준다.
		}
		if (!id.isJsonPrimitive()) {
			return false;
		}
		JsonPrimitive p = id.getAsJsonPrimitive();
		return p.isString() || p.isNumber();
	}

	private static String str(JsonElement e) {
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : null;
	}

	private JsonObject initialize(JsonObject params) {
		String requested = str(params.get("protocolVersion"));
		String v = (requested != null && PROTOCOL_VERSIONS.contains(requested)) ? requested : DEFAULT_PROTOCOL;
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", v);
		JsonObject caps = new JsonObject();
		caps.add("tools", new JsonObject());
		r.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", SERVER_NAME);
		info.addProperty("version", version);
		r.add("serverInfo", info);
		r.addProperty("instructions", INSTRUCTIONS);
		JsonObject client = params.getAsJsonObject("clientInfo");
		log("initialize: 요청 버전 " + requested + " → " + v + (client != null ? ", 클라이언트 " + client : ""));
		return r;
	}

	private JsonObject toolsCall(JsonElement id, JsonObject params) {
		String name = str(params.get("name"));
		if (name == null) {
			protocolErrors++;
			return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: name이 없음", null);
		}
		ToolRegistry.Tool tool = tools.get(name);
		if (tool == null) {
			protocolErrors++;
			return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool: " + name, null);
		}
		JsonElement argsEl = params.get("arguments");
		if (argsEl != null && !argsEl.isJsonNull() && !argsEl.isJsonObject()) {
			protocolErrors++;
			return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: arguments는 객체", null);
		}
		toolCalls++;
		perTool.merge(name, 1, Integer::sum);
		long t0 = System.nanoTime();
		JsonObject result = callTool(tool, argsEl == null || argsEl.isJsonNull() ? new JsonObject() : argsEl.getAsJsonObject());
		long ms = (System.nanoTime() - t0) / 1_000_000;
		boolean err = result.get("isError").getAsBoolean();
		if (err) {
			toolErrors++;
		}
		log("tools/call " + name + " " + ms + "ms " + (err ? "isError" : "ok"));
		return JsonRpc.result(id, result);
	}

	/** 도구를 부르고 MCP CallToolResult를 만든다. 예외는 isError 결과로 바꾼다. */
	JsonObject callTool(ToolRegistry.Tool tool, JsonObject arguments) {
		try {
			Args a = new Args(arguments);
			a.checkKnown(tool.propertyNames());
			Object r = tool.handler().call(state, a);
			return toolResult(Json.pretty(r), false);
		} catch (EngineException e) {
			log("  " + tool.name() + " 오류 " + e.code() + ": " + e.getMessage());
			return toolResult(Json.pretty(errorBody(e.code(), e.getMessage(), Json.tree(e.details()))), true);
		} catch (IllegalArgumentException e) {
			log("  " + tool.name() + " 인자 오류: " + e);
			return toolResult(Json.pretty(errorBody(EngineException.BAD_ARGUMENT, String.valueOf(e.getMessage()),
					new JsonObject())), true);
		} catch (Throwable e) {
			rethrowFatal(e);
			log("  " + tool.name() + " 예상 못 한 예외: " + e);
			e.printStackTrace(System.err);
			JsonObject d = new JsonObject();
			d.addProperty("exception", e.getClass().getName());
			return toolResult(Json.pretty(errorBody(INTERNAL, "도구 처리 중 예상 못 한 오류: " + e, d)), true);
		}
	}

	static JsonObject errorBody(String code, String message, JsonElement details) {
		JsonObject o = new JsonObject();
		o.addProperty("error", code);
		o.addProperty("message", message);
		o.add("details", details == null || details.isJsonNull() ? new JsonObject() : details);
		return o;
	}

	static JsonObject toolResult(String text, boolean isError) {
		JsonObject c = new JsonObject();
		c.addProperty("type", "text");
		c.addProperty("text", text);
		JsonArray content = new JsonArray();
		content.add(c);
		JsonObject r = new JsonObject();
		r.add("content", content);
		r.addProperty("isError", isError);
		return r;
	}

	private static void rethrowFatal(Throwable e) {
		if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) {
			throw (VirtualMachineError) e;
		}
	}

	private static void log(String msg) {
		System.err.println("[mcp] " + msg);
	}
}
