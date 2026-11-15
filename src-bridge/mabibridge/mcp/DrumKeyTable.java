package mabibridge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 드럼(27) 키 표. jar 안 리소스 {@code /mabibridge/engine/drum27_keys.json}(키·이름·종류·킥 대비 소리 크기·보정 세기·
 * 인게임 메모) 하나만 읽는다. 같은 파일을 mabi-ear(stem_drum_hits)도 저장소 경로로 읽는다.
 */
public final class DrumKeyTable {

	public static final String RESOURCE = "/mabibridge/engine/drum27_keys.json";
	public static final int DRUM_PROGRAM = 27;

	private static JsonObject table;

	private DrumKeyTable() {}

	/** 표 전체(사본). 리소스가 없거나 깨졌으면 IllegalStateException. */
	public static synchronized JsonObject table() {
		if (table == null) {
			try (InputStream in = DrumKeyTable.class.getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("리소스 없음: " + RESOURCE);
				}
				table = Json.parseStrict(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (IOException e) {
				throw new IllegalStateException("리소스를 읽을 수 없음: " + RESOURCE, e);
			}
		}
		return table.deepCopy();
	}

	/** {@code instruments}의 드럼 항목에 붙일 키 목록(키 순서). */
	public static JsonArray keys() {
		return table().getAsJsonArray("keys");
	}
}
