package mabibridge.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * McpClient: the default start uses the installed MabiIcco (MABIICCO_APP), --jar swaps the bridge jar, --cp gives the
 * whole class path; building the commands, and spawning a child with the test JVM's class path given as --cp.
 */
public class McpClientTest {

	private static Path tmp() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "client-");
	}

	/** A fake MabiIcco "app" folder (MabiIcco.jar, lib\flatlaf, lib\commons-compress) and a fake install root. */
	private static Path[] fakeInstall() throws Exception {
		Path d = tmp();
		Path app = Files.createDirectories(d.resolve("MabiIcco-1.5.12").resolve("app"));
		Files.writeString(app.resolve("MabiIcco.jar"), "x");
		Files.createDirectories(app.resolve("lib").resolve("flatlaf"));
		Files.createDirectories(app.resolve("lib").resolve("commons-compress"));
		Path root = Files.createDirectories(d.resolve("punchstrip"));
		Files.writeString(root.resolve("MabiIccoBridge.jar"), "x");
		Files.createDirectories(root.resolve("lib").resolve("gson"));
		return new Path[] { app, root };
	}

	/** Default start: -cp with MabiIcco's jars from MABIICCO_APP, then the bridge jar and Gson of the install. */
	@Test
	public void defaultCommandUsesTheInstalledMabiIcco() throws Exception {
		Path[] f = fakeInstall();
		Path app = f[0];
		Path root = f[1];
		Path home = root.resolve("bridge/runtime/home");
		String w = File.separator + "*";
		String expected = String.join(File.pathSeparator, app.resolve("MabiIcco.jar").toString(),
				app.resolve("lib").resolve("flatlaf") + w, app.resolve("lib").resolve("commons-compress") + w,
				root.resolve("MabiIccoBridge.jar").toString(), root.resolve("lib").resolve("gson") + w);
		// quotes and a trailing backslash in the variable are accepted, as in run-bridge.cmd
		for (String env : new String[] { app.toString(), "\"" + app + "\"", app + File.separator }) {
			List<String> cmd = McpClient.childCommand(root, home, null, null, List.of("--x"), env);
			int i = cmd.indexOf("-cp");
			assertTrue(cmd.toString(), i > 0 && !cmd.contains("-jar"));
			assertEquals(env, expected, cmd.get(i + 1));
			assertEquals("mabibridge.Main", cmd.get(i + 2));
			assertEquals("--x", cmd.get(cmd.size() - 1));
			assertTrue(cmd.contains("-Dmabiicco.test_mode"));
			assertTrue(cmd.contains("-Dmabiicco.ticktable=tickTable.dat"));
			assertTrue(cmd.contains("-Duser.home=" + home));
		}
		// --jar swaps only the bridge jar; MabiIcco still comes from MABIICCO_APP
		Path frozen = Files.writeString(root.resolve("frozen.jar"), "x");
		List<String> cj = McpClient.childCommand(root, home, frozen, null, List.of(), app.toString());
		assertEquals(expected.replace(root.resolve("MabiIccoBridge.jar").toString(), frozen.toString()),
				cj.get(cj.indexOf("-cp") + 1));
	}

	/** No MABIICCO_APP, an empty folder, or an app folder without lib\: no child, the message says MabiIcco is required. */
	@Test
	public void missingMabiIccoIsRefusedWithTheRequiredMessage() throws Exception {
		Path[] f = fakeInstall();
		Path root = f[1];
		Path empty = Files.createDirectories(tmp().resolve("empty"));
		Path noLib = Files.createDirectories(tmp().resolve("app"));
		Files.writeString(noLib.resolve("MabiIcco.jar"), "x");
		for (String env : new String[] { null, "", "  ", empty.toString(), noLib.toString(),
				empty.resolve("none").toString() }) {
			try {
				McpClient.childCommand(root, root, null, null, List.of(), env);
				fail("must refuse MABIICCO_APP=" + env);
			} catch (IllegalArgumentException e) {
				String m = e.getMessage();
				assertTrue(m, m.startsWith("MabiIcco is required: "));
				assertTrue(m, m.contains("MABIICCO_APP"));
				assertTrue(m, m.contains("https://github.com/fourthline/mmlTools"));
				assertTrue(m, m.contains("works only with an installed MabiIcco"));
			}
		}
		try {
			McpClient.childCommand(root, root, null, null, List.of(), noLib.toString());
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("lib\\flatlaf\\, lib\\commons-compress\\"));
		}
		// a missing bridge jar is reported as such (not as missing MabiIcco)
		Path bare = tmp();
		try {
			McpClient.childCommand(bare, bare, null, null, List.of(), f[0].toString());
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("MabiIccoBridge.jar"));
		}
	}

	/** --cp ignores MABIICCO_APP (the whole class path is given), so it works without an install. */
	@Test
	public void cpDoesNotNeedMabiIccoApp() throws Exception {
		Path d = tmp();
		Path a = Files.writeString(d.resolve("a.jar"), "x");
		List<String> cc = McpClient.childCommand(d, d, null, a.toString(), List.of(), null);
		assertEquals(a.toAbsolutePath().toString(), cc.get(cc.indexOf("-cp") + 1));
	}

	@Test
	public void jarAndCpOptions() throws Exception {
		Path d = tmp();
		Path a = Files.writeString(d.resolve("a.jar"), "x");
		Path b = Files.writeString(d.resolve("b.jar"), "x");
		Path[] f = fakeInstall();
		Path root = f[1];
		List<String> cj = McpClient.childCommand(root, root, a, null, List.of(), f[0].toString());
		assertTrue(cj.toString(), cj.get(cj.indexOf("-cp") + 1).contains(a.toAbsolutePath().normalize().toString()));
		List<String> cc = McpClient.childCommand(root, root, null, a + File.pathSeparator + b, List.of("--log", "l"));
		int i = cc.indexOf("-cp");
		assertTrue(cc.toString(), i > 0 && !cc.contains("-jar"));
		assertEquals(a.toAbsolutePath() + File.pathSeparator + b.toAbsolutePath(), cc.get(i + 1));
		assertEquals("mabibridge.Main", cc.get(i + 2));
		assertEquals(List.of("--log", "l"), cc.subList(i + 3, cc.size()));
		for (Object[] bad : new Object[][] { { a, a.toString() }, { d.resolve("none.jar"), null },
				{ null, a + File.pathSeparator + d.resolve("none.jar") }, { null, File.pathSeparator } }) {
			try {
				McpClient.childCommand(root, root, (Path) bad[0], (String) bad[1], List.of(), f[0].toString());
				fail("거부해야 함: " + bad[0] + " " + bad[1]);
			} catch (IllegalArgumentException e) {
				// 기대
			}
		}
	}

	@Test
	public void cpFolderWildcard() throws Exception {
		Path d = tmp();
		Path a = Files.writeString(d.resolve("a.jar"), "x");
		Path lib = Files.createDirectories(d.resolve("lib").resolve("gson"));
		Path root = Path.of("F:/repo");
		String cp = a + File.pathSeparator + d.resolve("lib") + File.separator + "*" + File.pathSeparator + lib + "/*";
		List<String> cc = McpClient.childCommand(root, root, null, cp, List.of());
		String got = cc.get(cc.indexOf("-cp") + 1);
		assertEquals(a.toAbsolutePath() + File.pathSeparator + d.resolve("lib").toAbsolutePath() + File.separator + "*"
				+ File.pathSeparator + lib.toAbsolutePath() + File.separator + "*", got);
		try {
			McpClient.childCommand(root, root, null, a + File.pathSeparator + d.resolve("none") + File.separator + "*",
					List.of());
			fail("없는 폴더 와일드카드는 거부해야 함");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("none"));
		}
	}

	@Test
	public void clientRootWalksUp() throws Exception {
		Path d = tmp();
		Files.createDirectories(d.resolve("bridge"));
		Files.writeString(d.resolve("bridge/run-bridge.cmd"), "rem");
		Path sub = Files.createDirectories(d.resolve("bridge/runtime/frozen"));
		assertEquals(d, McpClient.clientRoot(sub));
		assertEquals(d, McpClient.clientRoot(d));
		// 끝까지 없으면 처음 폴더(드라이브 뿌리에는 bridge/run-bridge.cmd가 없다)
		Path top = d.getRoot();
		assertEquals(top, McpClient.clientRoot(top));
	}

	/** 시험 JVM의 클래스 경로(브리지 클래스 + MabiIcco.jar + lib)를 --cp로 주면 그 클래스로 자식이 뜬다. */
	@Test
	public void spawnsWithExplicitClasspath() throws Exception {
		Path d = tmp();
		Path req = Files.writeString(d.resolve("req.jsonl"),
				"{\"tool\": \"project_new\", \"args\": {\"title\": \"cp\"}}\n{\"tool\": \"project_info\", \"args\": {}}\n",
				StandardCharsets.UTF_8);
		Path out = d.resolve("out.txt");
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
		int code = new McpClient().run(new String[] { req.toString(), "--out", out.toString(), "--timeout", "120", "--cp",
				System.getProperty("java.class.path") }, ps);
		String text = Files.readString(out, StandardCharsets.UTF_8);
		assertEquals(text, 0, code);
		assertTrue(text, text.contains("# 자식: -cp "));
		assertTrue(text, text.contains("project_info — ok"));
		assertTrue(text, text.contains("요청 2개, 오류 0개"));
		// 없는 --jar는 자식을 띄우지 않고 거부
		try {
			new McpClient().run(new String[] { req.toString(), "--jar", d.resolve("none.jar").toString() }, ps);
			fail("없는 jar를 받음");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("--jar 파일이 없음"));
		}
	}
}
