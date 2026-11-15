package mabibridge.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * 줄 단위 JSON-RPC 2.0. 한 줄에 메시지 하나, UTF-8, 줄 끝은 {@code "\n"} 하나.
 *
 * <p>읽기는 주어진 입력(보통 {@code System.in})을 UTF-8로, 쓰기는 주어진 출력(보통 {@code Main}이 떼어 둔
 * 진짜 stdout)에만 한다. {@code println}을 쓰지 않는다(Windows에서 CRLF가 붙는다).</p>
 */
public final class JsonRpc {

	public static final String VERSION = "2.0";

	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private final BufferedReader in;
	private final OutputStream out;

	public JsonRpc(InputStream in, OutputStream out) {
		this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		this.out = out;
	}

	/** 다음 줄(줄 끝 문자 제외, CRLF로 와도 CR은 떨어진다). 입력이 끝나면 null. */
	public String readLine() throws IOException {
		return in.readLine();
	}

	/** 메시지 하나를 한 줄로 쓰고 바로 내보낸다. */
	public synchronized void write(JsonElement message) throws IOException {
		byte[] b = (serialize(message) + "\n").getBytes(StandardCharsets.UTF_8);
		out.write(b);
		out.flush();
	}

	/** 한 줄 직렬화. 결과에 줄바꿈 문자가 들어가지 않음을 확인한다(문자열 안의 줄바꿈은 \n으로 이스케이프된다). */
	public static String serialize(JsonElement message) {
		String s = Json.COMPACT.toJson(message);
		if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
			throw new IllegalStateException("직렬화 결과에 줄바꿈이 있음");
		}
		return s;
	}

	/** 성공 응답. */
	public static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", VERSION);
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		o.add("result", result == null ? new JsonObject() : result);
		return o;
	}

	/** 오류 응답. data는 null이면 넣지 않는다. */
	public static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		if (data != null && !data.isJsonNull()) {
			err.add("data", data);
		}
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", VERSION);
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		o.add("error", err);
		return o;
	}
}
