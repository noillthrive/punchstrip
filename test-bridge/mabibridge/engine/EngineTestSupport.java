package mabibridge.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assume;

/** 엔진 테스트 공통 도우미. */
final class EngineTestSupport {

	private EngineTestSupport() {}

	/** java.io.tmpdir(빌드가 시험용 tmp 폴더로 돌린다) 아래 새 폴더. */
	static Path tmpDir(String name) throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "engine-" + name + "-");
	}

	static BridgeEngine engine(Path tmp) {
		return new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
	}

	/** 파일이 없으면 건너뛴다(건너뛴 사실을 stderr에 남긴다). */
	static void assumeFile(String test, Path p) {
		boolean ok = Files.isRegularFile(p);
		if (!ok) {
			System.err.println("[SKIP] " + test + ": 시험 파일 없음 " + p);
		}
		Assume.assumeTrue("시험 파일 없음: " + p, ok);
	}
}
