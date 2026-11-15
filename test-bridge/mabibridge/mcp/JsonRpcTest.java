package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

/** (a) JsonRpc 파싱·직렬화. */
public class JsonRpcTest {

	@Test
	public void writeIsOneLineWithSingleLf() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JsonRpc rpc = new JsonRpc(new ByteArrayInputStream(new byte[0]), out);
		JsonObject r = new JsonObject();
		r.addProperty("text", "여러\n줄\r\n문자열 <> & \"따옴표\"");
		rpc.write(JsonRpc.result(new JsonPrimitive(1), r));
		rpc.write(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "x", null));
		byte[] b = out.toByteArray();
		String s = new String(b, StandardCharsets.UTF_8);
		assertFalse("CR이 있으면 안 됨", s.indexOf('\r') >= 0);
		String[] lines = s.split("\n", -1);
		assertEquals("두 메시지 + 마지막 빈 조각", 3, lines.length);
		assertEquals("", lines[2]);
		assertTrue(lines[0].startsWith("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"));
		assertTrue("HTML 이스케이프 안 함", lines[0].contains("<> &"));
		assertTrue(lines[0].contains("\\n") && lines[0].contains("\\r\\n"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"x\"}}", lines[1]);
		assertEquals("여러\n줄\r\n문자열 <> & \"따옴표\"", Json.parseStrict(lines[0]).getAsJsonObject()
				.getAsJsonObject("result").get("text").getAsString());
	}

	@Test
	public void readLineUtf8AndCrlfTolerant() throws Exception {
		byte[] in = "{\"a\":\"日本語 한글\"}\r\n{\"b\":1}\n".getBytes(StandardCharsets.UTF_8);
		JsonRpc rpc = new JsonRpc(new ByteArrayInputStream(in), new ByteArrayOutputStream());
		assertEquals("{\"a\":\"日本語 한글\"}", rpc.readLine());
		assertEquals("{\"b\":1}", rpc.readLine());
		assertEquals(null, rpc.readLine());
	}

	@Test
	public void strictParsing() {
		assertTrue(Json.parseStrict("{\"jsonrpc\":\"2.0\",\"id\":1}").isJsonObject());
		assertTrue(Json.parseStrict("[1,2]").isJsonArray());
		for (String bad : new String[] { "{a:1}", "{\"a\":1} x", "{\"a\":1}{\"b\":2}", "{\"a\":", "'x'", "", "   ",
				"{\"a\":01}", "{\"a\":NaN}" }) {
			try {
				Json.parseStrict(bad);
				fail("거부해야 함: " + bad);
			} catch (JsonParseException e) {
				// 기대
			}
		}
	}

	/** Gson은 지역 클래스를 직렬화하지 않으므로 중첩 record로 둔다. */
	record R(int changedMeasures, Object compareSummary, String velocity15) {}

	@Test
	public void snakeCaseAndNulls() {
		String s = Json.COMPACT.toJson(Json.tree(new R(3, null, "x")));
		assertEquals("{\"changed_measures\":3,\"compare_summary\":null,\"velocity15\":\"x\"}", s);
	}
}
