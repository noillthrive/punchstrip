package mabibridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * The bridge needs an installed MabiIcco: the app-folder check, the class-path check, and the bridge process itself
 * stopping with the "MabiIcco is required" message (exit 5) when MabiIcco's classes are missing.
 */
public class MabiIccoRequiredTest {

	private static Path tmp() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "required-");
	}

	private static void assertRequiredMessage(String m) {
		assertTrue(m, m.startsWith("MabiIcco is required: "));
		assertTrue(m, m.contains("contains no MabiIcco code"));
		assertTrue(m, m.contains(MabiIccoRequired.SOURCE_URL));
		assertTrue(m, m.contains("set \"MABIICCO_APP="));
	}

	@Test
	public void appFolderCheck() throws Exception {
		assertRequiredMessage(MabiIccoRequired.checkAppFolder(null));
		Path d = tmp();
		String empty = MabiIccoRequired.checkAppFolder(d);
		assertRequiredMessage(empty);
		assertTrue(empty, empty.contains("does not contain MabiIcco.jar, lib\\flatlaf\\, lib\\commons-compress\\"));
		assertTrue(MabiIccoRequired.checkAppFolder(d.resolve("none")).contains("is not a folder"));
		Files.writeString(d.resolve("MabiIcco.jar"), "x");
		Files.createDirectories(d.resolve("lib/flatlaf"));
		assertTrue(MabiIccoRequired.checkAppFolder(d).contains("does not contain lib\\commons-compress\\"));
		Files.createDirectories(d.resolve("lib/commons-compress"));
		assertNull(MabiIccoRequired.checkAppFolder(d));
	}

	@Test
	public void appFromEnv() {
		assertNull(MabiIccoRequired.appFromEnv(null));
		assertNull(MabiIccoRequired.appFromEnv(" \"\" "));
		Path p = Path.of("C:/Tools/MabiIcco-1.5.12/app").toAbsolutePath().normalize();
		assertEquals(p, MabiIccoRequired.appFromEnv("\"C:\\Tools\\MabiIcco-1.5.12\\app\\\""));
		assertEquals(p, MabiIccoRequired.appFromEnv("C:/Tools/MabiIcco-1.5.12/app/"));
	}

	@Test
	public void classpathCheck() {
		assertNull("the test class path has MabiIcco", MabiIccoRequired.checkClasspath(getClass().getClassLoader()));
		String m = MabiIccoRequired.checkClasspath(new URLClassLoader(new URL[0], null));
		assertRequiredMessage(m);
		assertTrue(m, m.contains("jp.fourthline.mmlTools.MMLScore"));
	}

	/** The bridge started without MabiIcco on the class path: exit 5 and the message on stderr; --version still works. */
	@Test
	public void bridgeStopsWithoutMabiIcco() throws Exception {
		String cp = codeSource(Main.class) + File.pathSeparator + codeSource(com.google.gson.Gson.class);
		Path d = tmp();
		for (String[] run : new String[][] { { "serve" }, { "--selfcheck", "--no-dls" }, { "--version" } }) {
			List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-Djava.awt.headless=true", "-Dmabiicco.test_mode", "-Duser.home=" + d.resolve("home"), "-cp", cp,
					"mabibridge.Main"));
			cmd.addAll(List.of(run));
			Path err = d.resolve(run[0].replace("-", "") + ".err");
			Path out = d.resolve(run[0].replace("-", "") + ".out");
			Process p = new ProcessBuilder(cmd).directory(d.toFile()).redirectError(err.toFile())
					.redirectOutput(out.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE).start();
			p.getOutputStream().close();
			assertTrue("bridge did not stop", p.waitFor(60, TimeUnit.SECONDS));
			String e = Files.readString(err, StandardCharsets.UTF_8);
			if (run[0].equals("--version")) {
				assertEquals(e, 0, p.exitValue());
				assertTrue(Files.readString(out, StandardCharsets.UTF_8).startsWith("MabiIccoBridge " + Main.VERSION));
				continue;
			}
			assertEquals(e, MabiIccoRequired.EXIT_CODE, p.exitValue());
			assertTrue(e, e.startsWith("[bridge] MabiIcco is required: MabiIcco's classes are not on the class path"));
			assertTrue(e, e.contains(MabiIccoRequired.SOURCE_URL));
			assertTrue(e, !e.contains("Exception") && !e.contains("NoClassDefFoundError"));
			assertEquals("nothing on the protocol channel", 0, Files.size(out));
		}
	}

	private static String codeSource(Class<?> c) throws Exception {
		return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}
}
