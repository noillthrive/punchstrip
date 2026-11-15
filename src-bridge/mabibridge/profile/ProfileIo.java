package mabibridge.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mabibridge.engine.EngineException;

/** 프로필 JSON 쓰기(UTF-8, 2칸 들여쓰기, null 키 유지). */
public final class ProfileIo {

	public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping()
			.create();

	private ProfileIo() {}

	/** 오류 details(키, 값, 키, 값 …). null 값도 남긴다. */
	public static java.util.Map<String, Object> details(Object... kv) {
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}

	public static String toJson(Object o) {
		return PRETTY.toJson(o);
	}

	/**
	 * 파일에 쓴다. 부모 폴더는 만든다.
	 *
	 * @throws EngineException FILE_EXISTS(overwrite false인데 있음), IO_ERROR
	 */
	public static void write(Path path, Object o, boolean overwrite) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			byte[] b = (toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
			if (overwrite) {
				Files.write(path, b);
			} else {
				Files.write(path, b, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			}
		} catch (FileAlreadyExistsException e) {
			throw new EngineException(EngineException.FILE_EXISTS, "이미 있는 파일(overwrite: true로 덮어쓸 수 있음): " + path,
					ProfileIo.details("path", path.toString()), e);
		} catch (IOException e) {
			throw new EngineException(EngineException.IO_ERROR, "쓰기 실패: " + e.getMessage(),
					ProfileIo.details("path", path.toString()), e);
		}
	}
}
