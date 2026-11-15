package mabibridge.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.mcp.CompareHook;
import mabibridge.mcp.CompareHooks;
import mabibridge.mcp.MappingEntry;

/** MCP 연결 어댑터. DLS는 읽지 않는 설정(없는 경로)으로 돌린다. */
public class CompareHookImplTest {

	private static BridgeEngine engine() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		Path tmp = Files.createTempDirectory(base, "compare-hook-");
		return new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withDls(List.of(tmp.resolve("none.dls"))));
	}

	@Test
	public void discoveredByReflection() throws Exception {
		CompareHook h = CompareHooks.discover(engine());
		assertTrue(String.valueOf(h), h instanceof CompareHookImpl);
	}

	@Test
	public void mappingConversion() {
		Mapping m = CompareHookImpl.toMapping(Map.of(1, new MappingEntry(List.of(3), null), 2,
				new MappingEntry(null, List.of(4, 5))));
		assertEquals(List.of(3), m.tracks().get(1).melodyFrom());
		assertNull(m.tracks().get(1).chordsFrom());
		assertEquals(List.of(), m.tracks().get(2).melodyFrom());
		assertEquals(List.of(4, 5), m.tracks().get(2).chordsFrom());
		assertTrue(m.hasMelody());
		assertTrue(CompareHookImpl.toMapping(null).isEmpty());
	}

	@Test
	public void compareAndSummaryWithoutDls() throws Exception {
		BridgeEngine e = engine();
		Project p = e.newProject("hook", "", "4/4", 120);
		p.addTrack("t1", 3, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 60)), "strict", false);
		CompareHook h = new CompareHookImpl(e);
		// 참조 없이도(3~6항목) 동작해야 한다. DLS 경로가 없으므로 ensureDls 실패 → properties 음역.
		Object r = h.compare(p, null, Map.of(), 1, 1, Set.of(1));
		assertTrue(r instanceof CompareReport);
		CompareReport cr = (CompareReport) r;
		assertEquals(BridgeEngine.dlsLoaded(), cr.dlsLoaded());
		assertEquals(1, cr.summary().rangeViolations());
		Object s = h.summary(p, null, Map.of(), List.of(1));
		assertTrue(s instanceof CompareSummary);
		assertEquals("참조 미로드", ((CompareSummary) s).reason());
		// 인자 없는 생성자
		Object r2 = new CompareHookImpl().compare(p, null, null, 1, 1, Set.of());
		assertEquals(1, ((CompareReport) r2).summary().rangeViolations());
	}
}
