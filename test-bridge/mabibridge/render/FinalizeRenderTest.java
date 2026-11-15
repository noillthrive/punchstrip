package mabibridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.Finalize;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;

/**
 * After score_finalize the audio from the first original measure onward is the original score's audio: the lead-in is
 * a pure shift and the end padding is silent (velocity 0). Rendered with and without the lead-in measures, sample for
 * sample. Skipped without the Mabinogi DLS.
 */
public class FinalizeRenderTest {

	static Path tmp;

	@BeforeClass
	public static void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "finalize-render-");
	}

	private static Project score(BridgeEngine engine) {
		Project p = engine.newProject("finalize render", null, "4/4", 120);
		p.addTrack("lute", 0, null, null, null);
		p.addTrack("bass", 29, null, null, null);
		p.addTrack("drums", 27, null, null, null);
		p.addTrack("mic", 121, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, 60, 10), NoteSpec.at(96, 96, 64, 10), NoteSpec.at(192, 192, 67, 10),
				NoteSpec.at(384, 384, 72, 10), NoteSpec.at(768, 288, 71, 10)), "strict", false);
		p.putNotes(1, "chord1", List.of(NoteSpec.at(0, 384, 55, 8), NoteSpec.at(384, 384, 57, 8)), "strict", false);
		p.putNotes(2, "melody", List.of(NoteSpec.at(0, 192, 24, 12), NoteSpec.at(192, 192, 31, 12), NoteSpec.at(384, 384, 29, 12)),
				"strict", false);
		p.putNotes(3, "melody", List.of(NoteSpec.at(0, 48, 41, 12), NoteSpec.at(96, 48, 45, 12), NoteSpec.at(192, 48, 41, 12),
				NoteSpec.at(288, 48, 45, 12), NoteSpec.at(384, 96, 60, 15)), "strict", false);
		p.putNotes(4, "song", List.of(NoteSpec.at(0, 192, 45, 12), NoteSpec.at(192, 192, 48, 12), NoteSpec.at(384, 480, 47, 12)),
				"strict", false);
		return p;
	}

	@Test
	public void audioAfterTheLeadInEqualsTheOriginal() {
		boolean dls = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!dls) {
			System.err.println("[SKIP] FinalizeRenderTest: DLS 파일 없음 " + BridgeEngine.defaultDls());
		}
		Assume.assumeTrue("DLS 파일 없음", dls);
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		engine.ensureDls();
		Project p = score(engine);
		OfflineRenderer.Audio orig = WavRender.renderInMemory(engine, p,
				new WavRender.Request(null, null, null, null, null, 1.0, null, null, false)).get(0);
		Finalize.Result r = p.finalizeScore(new Finalize.Options(true, true, 12, null, false, null, null));
		assertEquals("added", r.signature().status());
		int n = r.signature().measuresInserted();
		assertEquals(1, n);
		long padded = r.padding().tracks().stream().filter(t -> "added".equals(t.status())).count();
		assertTrue("some track was padded", padded >= 2);
		OfflineRenderer.Audio fin = WavRender.renderInMemory(engine, p,
				new WavRender.Request(n + 1, null, null, null, null, 1.0, null, null, false)).get(0);
		byte[] a = orig.pcm();
		byte[] b = fin.pcm();
		assertEquals("same rendered length", orig.length(), fin.length());
		int diff = 0;
		int first = -1;
		for (int i = 0; i < orig.length(); i++) {
			if (a[i] != b[i]) {
				diff++;
				if (first < 0) {
					first = i;
				}
			}
		}
		assertEquals("differing bytes (first at " + first + ")", 0, diff);
		// and the lead-in itself sounds (the signature is audible)
		OfflineRenderer.Audio lead = WavRender.renderInMemory(engine, p,
				new WavRender.Request(1, 1, null, null, null, 1.0, null, null, false)).get(0);
		assertTrue("lead-in audible", lead.peakDbfs() != null && lead.peakDbfs() > -40);
	}
}
