package mabibridge.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.Marker;
import jp.fourthline.mmlTools.TimeSignature;

/**
 * The documented removal of the signature: finalize, save, re-open, then MabiIcco's own delete-measure
 * ({@code MMLScore.removeTicks(0, true)}, what the GUI does) for the inserted measures gives back the original score —
 * notes, tempo map, meter, markers and the saved bytes.
 */
public class FinalizeRemovalTest {

	/** Comparable summary of a score: notes per track/part, tempos, base time, time signatures, markers. */
	static List<String> summary(MMLScore s) {
		List<String> l = new ArrayList<>();
		for (int t = 0; t < s.getTrackCount(); t++) {
			for (int p = 0; p < 4; p++) {
				for (MMLNoteEvent e : s.getTrack(t).getMMLEventAtIndex(p).getMMLNoteEventList()) {
					l.add("note " + t + "/" + p + " " + e.getTickOffset() + "+" + e.getTick() + " n" + e.getNote() + " v"
							+ e.getVelocity() + (e.isTuningNote() ? " tuning" : ""));
				}
			}
		}
		for (MMLTempoEvent e : s.getTempoEventList()) {
			l.add("tempo " + e.getTickOffset() + " " + e.getTempo());
		}
		l.add("base " + s.getBaseTime());
		for (TimeSignature ts : s.getTimeSignatureList()) {
			l.add("ts " + ts.getMeasureOffset() + " " + ts.getTickOffset() + " " + ts.getNumTime() + "/" + ts.getBaseTick());
		}
		for (Marker m : s.getMarkerList()) {
			l.add("marker " + m);
		}
		return l;
	}

	private static void check(String name, String mmi, int expectMeasures) throws Exception {
		Path dir = EngineTestSupport.tmpDir("removal-" + name.replaceAll("[^A-Za-z0-9]", "_"));
		BridgeEngine engine = EngineTestSupport.engine(dir);
		Path in = dir.resolve("in.mmi");
		Files.writeString(in, mmi, StandardCharsets.UTF_8);
		Project orig = engine.open(in);
		List<String> want = summary(orig.score());
		byte[] wantBytes = orig.state();

		Finalize.Result r = orig.finalizeScore(new Finalize.Options(true, false, 12, null, false, null, null));
		assertEquals(name + " " + r.signature(), "added", r.signature().status());
		int n = r.signature().measuresInserted();
		assertEquals(name, expectMeasures, n);
		Path saved = dir.resolve("finalized.mmi");
		orig.saveAs(saved);

		Project q = engine.open(saved);
		MMLScore s = q.score();
		for (int i = 0; i < n; i++) {
			s.removeTicks(0, true); // MabiIcco: delete the first measure
		}
		s.generateAll();
		assertEquals(name, want, summary(s));
		assertEquals(name + " saved bytes", new String(wantBytes, StandardCharsets.UTF_8),
				new String(s.getObjectState(), StandardCharsets.UTF_8));
	}

	private static String score(String time, String tempo, String extra, String... tracks) {
		StringBuilder b = new StringBuilder("[mml-score]\nversion=1\ntitle=removal\nauthor=\ntime=" + time + "\ntempo=" + tempo + "\n");
		for (String t : tracks) {
			b.append(t);
		}
		return b.append(extra).toString();
	}

	private static String track(String mml, int program) {
		return "mml-track=" + mml + "\nname=t" + program + "\nprogram=" + program + "\nsongProgram=-1\npanpot=64\nvisible=true\n";
	}

	@Test
	public void nonDefaultTempo() throws Exception {
		check("t97", score("4/4", "0T97,768T110", "[marker]\n0=intro\n768=verse\n",
				track("MML@t97l8cdefgab>c<b1t110c1,l2ceg1,;", 0), track("MML@t97o2c1c1t110c1,,;", 29),
				track("MML@,,,t97l4cdefg1t110g1;", 121)), 1);
	}

	@Test
	public void defaultTempo() throws Exception {
		check("t120", score("4/4", "", "", track("MML@l8cdefgab>c<b1,l2ceg1,;", 0), track("MML@o2c1c1,,;", 29)), 1);
	}

	@Test
	public void threeFourAtTickZero() throws Exception {
		// base time 3/4
		check("3/4", score("3/4", "0T100", "", track("MML@t100l4cdefgab>c2.,,;", 0)), 1);
		// file header 4/4 overridden by a time-signature event at measure 0 (tick 0) = 3/4, and a 2/4 bar later
		check("3/4 event", score("4/4", "0T100", "[time-signature]\n0=3/4\n576=2/4\n",
				track("MML@t100l4cdefgab>cd,,;", 0)), 1);
	}

	@Test
	public void twoLeadInMeasures() throws Exception {
		// tempo 180 → unit 48 → 8u + 96 = 480 > 384: two measures
		check("n2", score("4/4", "0T180,768T150", "", track("MML@t180l8cdefgab>c<b1t150c1,,;", 0),
				track("MML@t180<<a8r8a8r8a8r8a8r8>>c1t150c1,,;", 27)), 2);
		// 2/4 at tempo 120 → two measures
		check("n2 2/4", score("2/4", "0T120", "", track("MML@t120l8cdefgab>c,,;", 0)), 2);
	}

	@Test
	public void tempoCopySurvivesSaveAndOpen() throws Exception {
		Path dir = EngineTestSupport.tmpDir("removal-copy");
		BridgeEngine engine = EngineTestSupport.engine(dir);
		Path in = dir.resolve("in.mmi");
		Files.writeString(in, score("4/4", "0T97", "", track("MML@t97cdef,,;", 0)), StandardCharsets.UTF_8);
		Project p = engine.open(in);
		Finalize.Result r = p.finalizeScore(new Finalize.Options(true, false, 12, null, false, null, null));
		assertEquals(384, ((Number) r.signature().leadInTempo().get("tick")).intValue());
		Path saved = dir.resolve("f.mmi");
		p.saveAs(saved);
		String text = Files.readString(saved, StandardCharsets.UTF_8);
		assertTrue(text, text.contains("tempo=0T97,384T97"));
		Project q = engine.open(saved);
		assertEquals(2, q.score().getTempoEventList().size());
		assertEquals(384, q.score().getTempoEventList().get(1).getTickOffset());
	}
}
