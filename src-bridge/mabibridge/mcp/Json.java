package mabibridge.mcp;

import java.io.IOException;
import java.io.StringReader;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * MCP 층의 JSON 도우미. 엔진 record의 camelCase 필드는 snake_case로 내보낸다.
 * null도 키를 남긴다({@code "compare_summary": null}처럼 응답 형식이 null을 요구하는 곳이 있다).
 */
public final class Json {

	private Json() {}

	/** 한 줄 직렬화(프로토콜용). */
	public static final Gson COMPACT = base().create();

	/** 2칸 들여쓰기(도구 결과 text용). */
	public static final Gson PRETTY = base().setPrettyPrinting().create();

	private static GsonBuilder base() {
		return new GsonBuilder()
				.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
				.serializeNulls()
				.disableHtmlEscaping();
	}

	/** 자바 객체 → JSON 트리(snake_case). null이면 JsonNull. */
	public static JsonElement tree(Object o) {
		if (o == null) {
			return JsonNull.INSTANCE;
		}
		if (o instanceof JsonElement e) {
			return e;
		}
		return COMPACT.toJsonTree(o);
	}

	/** 2칸 들여쓰기 문자열. */
	public static String pretty(Object o) {
		return PRETTY.toJson(tree(o));
	}

	/**
	 * 엄격한 JSON 파싱(RFC 8259). 따옴표 없는 키, 끝에 남은 글자 등은 거부한다.
	 *
	 * @throws JsonParseException 형식이 틀렸을 때
	 */
	public static JsonElement parseStrict(String text) {
		try {
			JsonReader r = new JsonReader(new StringReader(text));
			r.setStrictness(Strictness.STRICT);
			if (r.peek() == JsonToken.END_DOCUMENT) {
				throw new JsonSyntaxException("빈 입력");
			}
			JsonElement e = JsonParser.parseReader(r);
			if (r.peek() != JsonToken.END_DOCUMENT) {
				throw new JsonSyntaxException("JSON 값 뒤에 남은 글자가 있음");
			}
			return e;
		} catch (IOException | IllegalStateException | NumberFormatException e) {
			throw new JsonSyntaxException(e.getMessage(), e);
		}
	}
}
