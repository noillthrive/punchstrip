package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/** DLS location order --dls, MABI_DLS (files or folder), install probe, fallback. No real DLS needed. */
public class DlsLocatorTest {

	private static Path fakeInstall(String name, int n) throws Exception {
		Path dir = EngineTestSupport.tmpDir(name).resolve("mp3");
		Files.createDirectories(dir);
		for (int i = n; i >= 1; i--) { // created out of order: the result must be sorted
			Files.write(dir.resolve("MSXspirit0" + i + ".dls"), new byte[] { 1 });
		}
		Files.write(dir.resolve("other.dls"), new byte[] { 1 });
		return dir;
	}

	@Test
	public void explicitWinsAndFoldersExpand() throws Exception {
		Path dir = fakeInstall("explicit", 3);
		Path one = dir.resolve("MSXspirit02.dls");
		DlsLocator.Result r = DlsLocator.locate(List.of(one), "ignored", List.of(dir), BridgeEngine.DEFAULT_DLS);
		assertEquals(DlsLocator.Source.ARGUMENT, r.source());
		assertEquals(List.of(one), r.paths());
		r = DlsLocator.locate(List.of(dir), null, List.of(), BridgeEngine.DEFAULT_DLS);
		assertEquals(List.of(dir.resolve("MSXspirit01.dls"), dir.resolve("MSXspirit02.dls"), dir.resolve("MSXspirit03.dls")),
				r.paths());
		assertEquals(3, r.existing().size());
	}

	@Test
	public void envFolderOrList() throws Exception {
		Path dir = fakeInstall("env", 5);
		DlsLocator.Result r = DlsLocator.locate(null, " " + dir + " ", List.of(), BridgeEngine.DEFAULT_DLS);
		assertEquals(DlsLocator.Source.ENV_VAR, r.source());
		assertEquals(5, r.paths().size());
		assertEquals(dir.resolve("MSXspirit05.dls"), r.paths().get(4));
		r = DlsLocator.locate(null, "\"" + dir.resolve("MSXspirit01.dls") + "\";" + dir.resolve("MSXspirit09.dls"),
				List.of(), BridgeEngine.DEFAULT_DLS);
		assertEquals(2, r.paths().size());
		assertEquals(1, r.existing().size());
		assertEquals(List.of(dir.resolve("MSXspirit09.dls")), r.missing());
	}

	@Test
	public void probeThenFallback() throws Exception {
		Path empty = EngineTestSupport.tmpDir("probe-empty");
		Path dir = fakeInstall("probe", 2);
		DlsLocator.Result r = DlsLocator.locate(null, "", List.of(empty, empty.resolve("nope"), dir),
				BridgeEngine.DEFAULT_DLS);
		assertEquals(DlsLocator.Source.PROBE, r.source());
		assertEquals(2, r.paths().size());
		r = DlsLocator.locate(null, null, List.of(empty), BridgeEngine.DEFAULT_DLS);
		assertEquals(DlsLocator.Source.FALLBACK, r.source());
		assertEquals(BridgeEngine.DEFAULT_DLS, r.paths());
	}

	/** 0.6.2: a later folder with all five banks wins over an earlier partial one; missing banks are reported. */
	@Test
	public void probePrefersCompleteFolder() throws Exception {
		Path partial = fakeInstall("partial", 2);
		Path full = fakeInstall("full", 5);
		DlsLocator.Result r = DlsLocator.locate(null, null, List.of(partial, full), BridgeEngine.DEFAULT_DLS);
		assertEquals(5, r.paths().size());
		assertTrue(r.paths().get(0).startsWith(full));
		assertTrue(r.missingSiblings().isEmpty());
		r = DlsLocator.locate(null, null, List.of(partial), BridgeEngine.DEFAULT_DLS);
		assertEquals(2, r.paths().size());
		assertTrue(r.detail(), r.detail().contains("incomplete"));
		assertEquals(List.of(partial.resolve("MSXspirit03.dls"), partial.resolve("MSXspirit04.dls"),
				partial.resolve("MSXspirit05.dls")), r.missingSiblings());
		// explicit files from a partial folder: the absent banks are reported too
		r = DlsLocator.locate(List.of(partial.resolve("MSXspirit01.dls")), null, List.of(), BridgeEngine.DEFAULT_DLS);
		assertEquals(4, r.missingSiblings().size()); // 02..05: only 01 is in the list
		assertEquals(partial.resolve("MSXspirit02.dls"), r.missingSiblings().get(0));
	}

	@Test
	public void emptyFolderStaysVisible() throws Exception {
		Path empty = EngineTestSupport.tmpDir("env-empty");
		DlsLocator.Result r = DlsLocator.locate(null, empty.toString(), List.of(), BridgeEngine.DEFAULT_DLS);
		assertEquals(1, r.paths().size());
		assertTrue(r.existing().isEmpty());
		assertTrue(r.paths().get(0).startsWith(empty));
	}
}
