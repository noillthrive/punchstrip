package mabibridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import mabibridge.engine.ProjectConfig;

/** Default render folder: MABIBRIDGE_OUT (absolute), project file render.dir, else &lt;jar folder&gt;/output/render. */
public class MainDefaultsTest {

	private static Path tmp(String name) throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "main-" + name + "-");
	}

	@Test
	public void standaloneInstallUsesOutputRender() throws Exception {
		Path install = tmp("install").resolve("mabi-mcp");
		Files.createDirectories(install);
		assertEquals(install.resolve("output").resolve("render"), Main.defaultRenderDir(null, install, null));
		assertEquals(install.resolve("output").resolve("render"), Main.defaultRenderDir("  ", install, null));
	}

	@Test
	public void envWinsOverProjectFile() throws Exception {
		Path install = tmp("env");
		Path out = install.resolve("my out");
		ProjectConfig.Loaded project = new ProjectConfig.Loaded(install.resolve("p"), install.resolve("pr"), null,
				List.of(), List.of());
		assertEquals(out.resolve("render"), Main.defaultRenderDir(out.toString(), install, project));
		assertEquals(install.resolve("pr"), Main.defaultRenderDir(null, install, project));
	}

	@Test
	public void relativeOutEnvIsRejected() throws Exception {
		Path install = tmp("rel");
		try {
			Main.defaultRenderDir("relative\\out", install, null);
			fail("relative MABIBRIDGE_OUT must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("MABIBRIDGE_OUT"));
		}
	}
}
