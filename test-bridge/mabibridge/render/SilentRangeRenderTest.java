package mabibridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.InstrumentTable;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;
import mabibridge.engine.SoundingPitch;

/**
 * Silent written pitches derived from the render, not from a hand-written list: every written pitch 11..119 of the
 * bass (29), the voice / microphone instruments (80..84, 90..94, 120, 121) and the choruses (100, 110) is rendered on
 * its own (v12, 48 ticks, 3 s apart) and the per-note level decides "silent". The sounding-pitch table
 * ({@link SoundingPitch#soundingOf}) must call exactly those pitches SILENT. Skipped without the Mabinogi DLS.
 */
public class SilentRangeRenderTest {

	static final int[] PROGRAMS = { 29, 80, 81, 82, 83, 84, 90, 91, 92, 93, 94, 100, 110, 120, 121 };
	/** A rendered note counts as silent below this RMS (audible notes measure above -45 dBFS, silent ones are 0). */
	static final double SILENT_DB = -70;

	static Path tmp;

	@BeforeClass
	public static void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "silent-");
	}

	/** Written pitches 11..119 that render silent for one program. */
	static List<Integer> renderedSilent(BridgeEngine engine, int program) {
		Project p = engine.newProject("silent " + program, null, "4/4", 120);
		p.addTrack("t", program, null, null, null);
		InstrumentTable.Instrument inst = engine.instrumentTable().get(program);
		String part = ("V".equals(inst.type()) || "C".equals(inst.type())) ? "song" : "melody";
		List<NoteSpec> notes = new ArrayList<>();
		for (int m = NoteSpec.MIN_MIDI; m <= NoteSpec.MAX_MIDI; m++) {
			notes.add(NoteSpec.at((long) (m - NoteSpec.MIN_MIDI) * 576, 48, m, 12)); // 576 ticks = 3 s at 120
		}
		p.putNotes(1, part, notes, "strict", false);
		OfflineRenderer.Audio au = WavRender.renderInMemory(engine, p,
				new WavRender.Request(null, null, null, null, null, 1.0, null, null, false)).get(0);
		byte[] pcm = au.pcm();
		List<Integer> silent = new ArrayList<>();
		for (int m = NoteSpec.MIN_MIDI; m <= NoteSpec.MAX_MIDI; m++) {
			int f0 = (int) (((m - NoteSpec.MIN_MIDI) * 3.0 + 0.02) * 44100);
			int f1 = f0 + (int) (0.2 * 44100);
			double sum = 0;
			int n = 0;
			for (int f = f0; f < f1 && f * 4 + 3 < au.length(); f++) {
				for (int c = 0; c < 2; c++) {
					int i = f * 4 + c * 2;
					double v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)) / 32768.0;
					sum += v * v;
					n++;
				}
			}
			double db = (n == 0 || sum == 0) ? -999 : 10 * Math.log10(sum / n);
			if (db < SILENT_DB) {
				silent.add(m);
			}
		}
		return silent;
	}

	@Test
	public void tableSilenceMatchesRender() {
		boolean dls = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!dls) {
			System.err.println("[SKIP] SilentRangeRenderTest: DLS 파일 없음 " + BridgeEngine.defaultDls());
		}
		Assume.assumeTrue("DLS 파일 없음", dls);
		BridgeEngine engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
		engine.ensureDls();
		for (int prog : PROGRAMS) {
			List<Integer> rendered = renderedSilent(engine, prog);
			List<Integer> table = new ArrayList<>();
			for (int m = NoteSpec.MIN_MIDI; m <= NoteSpec.MAX_MIDI; m++) {
				if (SoundingPitch.soundingOf(prog, m) == SoundingPitch.SILENT) {
					table.add(m);
				}
			}
			System.err.println("[silent] program " + prog + " rendered silent " + rendered);
			assertEquals("program " + prog + ": table silent pitches = rendered silent pitches", rendered, table);
			assertTrue("program " + prog + " has audible pitches", rendered.size() < NoteSpec.MAX_MIDI - NoteSpec.MIN_MIDI + 1);
		}
	}
}
