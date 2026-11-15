package mabibridge.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.ProfileCli;
import mabibridge.engine.Project;

/**
 * 편곡 프로필: 작은 악보로 음역·세기·밀도·보이싱·역할 추정·들리는 음 적용·옥타브 보정 원재료·범위, 3MLE 읽기, 일괄 CLI.
 *
 * <p>악보(4/4, 120, 96틱 = 4분음표):</p>
 * <ul>
 * <li>트랙 1 플룻(5, 적은 대로): 멜로디 1마디 C5(v10, 4분)·E5(v12, 4분)·G5(v8, 2분), 3마디 C6(v14, 온음표)</li>
 * <li>트랙 2 바이올린(22, +2옥타브): 1마디 온음표 멜로디 G3, 화음1 C3, 화음2 E3</li>
 * <li>트랙 3 베이스(29, ≤55 +1옥타브, 56~61 무음): 1~3마디 첫 박 E1(28) 4분, 2마디 둘째 박 A#3(58, 무음)</li>
 * <li>트랙 4 드럼(27): 1마디 4분 넷</li>
 * </ul>
 */
public class ScoreProfileTest {

	private Path tmp;
	private BridgeEngine engine;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "profile-");
		engine = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
	}

	private Project fixture() {
		Project p = engine.newProject("프로필 시험", "클로드", "4/4", 120);
		p.addTrack("플룻", 5, null, null, null);
		p.addTrack("바이올린", 22, null, null, null);
		p.addTrack("베이스", 29, null, null, null);
		p.addTrack("드럼", 27, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C5", 10), NoteSpec.at(96, 96, "E5", 12),
				NoteSpec.at(192, 192, "G5", 8), NoteSpec.at(768, 384, "C6", 14)), "strict", false);
		p.putNotes(2, "melody", List.of(NoteSpec.at(0, 384, "G3", 8)), "strict", false);
		p.putNotes(2, "chord1", List.of(NoteSpec.at(0, 384, "C3", 8)), "strict", false);
		p.putNotes(2, "chord2", List.of(NoteSpec.at(0, 384, "E3", 8)), "strict", false);
		p.putNotes(3, "melody", List.of(NoteSpec.at(0, 96, 28, 8), NoteSpec.at(384, 96, 28, 8),
				NoteSpec.at(480, 96, 58, 8), NoteSpec.at(768, 96, 28, 8)), "strict", false);
		p.putNotes(4, "melody", List.of(NoteSpec.at(0, 96, 60, 8), NoteSpec.at(96, 96, 60, 8), NoteSpec.at(192, 96, 60, 8),
				NoteSpec.at(288, 96, 60, 8)), "strict", false);
		return p;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o, String... keys) {
		Object x = o;
		for (String k : keys) {
			x = ((Map<String, Object>) x).get(k);
		}
		return (Map<String, Object>) x;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> track(Map<String, Object> profile, int n) {
		return ((List<Map<String, Object>>) profile.get("tracks")).get(n - 1);
	}

	private static double d(Object o) {
		return ((Number) o).doubleValue();
	}

	private ScoreProfile.Result build(Project p, Integer from, Integer to) {
		return ScoreProfile.build(ScoreLoader.ofProject(p), engine.instrumentTable(), from, to);
	}

	@Test
	public void rangeVelocityDensity() {
		Map<String, Object> pr = build(fixture(), null, null).profile();
		assertEquals(ScoreProfile.SCHEMA, pr.get("schema"));
		assertEquals(3, m(pr, "file").get("measures"));
		Map<String, Object> mel = m(track(pr, 1), "parts", "melody");
		assertEquals(4, mel.get("notes"));
		Map<String, Object> w = m(mel, "written");
		assertEquals(72, w.get("min"));
		assertEquals(84, w.get("max"));
		assertEquals(76, w.get("median")); // 72,76,79,84의 아래쪽 중앙값
		assertEquals("E5", w.get("median_name"));
		Map<String, Object> v = m(mel, "velocity");
		assertEquals(11.0, d(v.get("mean")), 1e-9);
		assertEquals(Math.sqrt(5.0), d(v.get("sd")), 1e-3); // (1+1+9+9)/4 = 5
		@SuppressWarnings("unchecked")
		List<Integer> h = (List<Integer>) v.get("histogram");
		assertEquals(16, h.size());
		assertEquals(1, (int) h.get(10));
		assertEquals(1, (int) h.get(14));
		Map<String, Object> dens = m(mel, "density");
		assertEquals(4.0 / 3, d(dens.get("mean")), 1e-3);
		assertEquals(3, dens.get("max"));
		assertEquals(1, dens.get("max_measure"));
		assertEquals(1.0 / 3, d(mel.get("rest_measure_ratio")), 1e-3); // 2마디는 쉼
		Map<String, Object> len = m(mel, "length");
		assertEquals((96 + 96 + 192 + 384) / 4.0, d(len.get("mean_ticks")), 1e-9);
		assertEquals(0.0, d(len.get("short_ratio")), 1e-9);
		assertTrue((Integer) mel.get("chars") > 0);
		assertEquals(1200, mel.get("chars_limit"));
	}

	@Test
	public void soundingPitchApplied() {
		Map<String, Object> pr = build(fixture(), null, null).profile();
		// 바이올린 +24
		Map<String, Object> vw = m(track(pr, 2), "written_range");
		Map<String, Object> vs = m(track(pr, 2), "sounding_range");
		assertEquals((Integer) vw.get("min") + 24, (int) (Integer) vs.get("min"));
		assertEquals((Integer) vw.get("max") + 24, (int) (Integer) vs.get("max"));
		// 플룻은 적은 대로
		assertEquals(m(track(pr, 1), "written_range").get("max"), m(track(pr, 1), "sounding_range").get("max"));
		// 베이스: E1(28) → E2(40), A#3(58) 무음, 세트 default 3 + silent 1
		Map<String, Object> bs = m(track(pr, 3), "parts", "melody", "sounding");
		assertEquals(40, bs.get("min"));
		assertEquals(40, bs.get("max"));
		assertEquals(1, bs.get("silent"));
		assertEquals(Map.of("default", 3, "silent", 1), m(bs, "timbre_sets"));
		assertEquals(1, m(pr, "ensemble").get("silent_notes"));
		// 드럼은 음높이 없음
		Map<String, Object> ds = m(track(pr, 4), "parts", "melody", "sounding");
		assertNull(ds.get("min"));
		assertEquals(4, ds.get("unpitched"));
	}

	@Test
	public void voicingAndPolyphony() {
		Map<String, Object> t2 = track(build(fixture(), null, null).profile(), 2);
		// 들리는 음: 멜로디 G5(79), 화음1 C5(72), 화음2 E5(76)
		Map<String, Object> mc1 = m(t2, "voicing", "melody_chord1");
		assertEquals(1, mc1.get("samples"));
		assertEquals(7, mc1.get("median"));
		assertEquals(0, mc1.get("crossings"));
		Map<String, Object> c1c2 = m(t2, "voicing", "chord1_chord2");
		assertEquals(-4, c1c2.get("median"));
		assertEquals(1, c1c2.get("crossings"));
		Map<String, Object> poly = m(t2, "polyphony");
		assertEquals(3, poly.get("max"));
		assertEquals(3.0, d(poly.get("mean_active")), 1e-9);
		assertEquals(4, poly.get("beats_active"));
		assertEquals(12, poly.get("beats"));
	}

	@Test
	public void rolesAndEnsemble() {
		ScoreProfile.Result r = build(fixture(), null, null);
		Map<String, Object> pr = r.profile();
		assertEquals("melody", m(track(pr, 1), "role").get("role"));
		assertEquals(List.of("lead"), m(track(pr, 1), "role").get("tags"));
		assertEquals("chord", m(track(pr, 2), "role").get("role"));
		assertEquals("bass", m(track(pr, 3), "role").get("role"));
		assertEquals("drums", m(track(pr, 4), "role").get("role"));
		assertNotNull(m(track(pr, 3), "role").get("reason"));
		Map<String, Object> ens = m(pr, "ensemble");
		assertEquals(4, ens.get("track_count"));
		assertEquals(List.of(5, 22, 29, 27), ens.get("programs"));
		Map<String, Object> low = m(ens, "low_register");
		assertEquals(1, low.get("max_parts")); // 베이스만 C3 아래
		// 옥타브 보정 원재료: 바이올린 적은 음 중앙값 E3(52) → E5(76), 표 +24와 같음, 옮겨 적은 듯
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> oc = (List<Map<String, Object>>) ens.get("octave_correction");
		Map<String, Object> vio = oc.stream().filter(o -> o.get("track").equals(2)).findFirst().orElseThrow();
		assertEquals(52, vio.get("written_median"));
		assertEquals(76, vio.get("sounding_median"));
		assertEquals(24, vio.get("diff"));
		assertEquals(24, vio.get("table_offset"));
		assertEquals(true, vio.get("matches_table"));
		assertEquals("shifted_likely", vio.get("estimate"));
		Map<String, Object> fl = oc.stream().filter(o -> o.get("track").equals(1)).findFirst().orElseThrow();
		assertEquals("not_needed", fl.get("estimate"));
		Map<String, Object> ba = oc.stream().filter(o -> o.get("track").equals(3)).findFirst().orElseThrow();
		assertEquals("set_default", ba.get("estimate"));
		assertEquals(12, ba.get("table_offset"));
		assertTrue(r.summaryText(), r.summaryText().contains("베이스 3"));
		assertEquals(4, r.summary().get("tracks"));
	}

	@Test
	public void measureRange() {
		Project p = fixture();
		Map<String, Object> pr = build(p, 3, 3).profile();
		assertEquals(3, m(pr, "range").get("from_measure"));
		assertEquals(false, m(pr, "range").get("whole_song"));
		assertEquals(1, m(track(pr, 1), "parts", "melody").get("notes"));
		assertEquals("empty", m(track(pr, 2), "role").get("role"));
		// 범위 오류
		try {
			build(p, 3, 2);
			assertTrue("BAD_ARGUMENT여야 함", false);
		} catch (EngineException e) {
			assertEquals(EngineException.BAD_ARGUMENT, e.code());
		}
		try {
			build(p, 9, null);
			assertTrue("BAD_ARGUMENT여야 함", false);
		} catch (EngineException e) {
			assertEquals(EngineException.BAD_ARGUMENT, e.code());
		}
	}

	/** 3MLE .mml 읽기(원본 파서 MMLFile, DLS 필요). 시험 파일은 test-bridge 안에 만든 짧은 파일. */
	@Test
	public void read3mle() {
		boolean dls = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!dls) {
			System.err.println("[SKIP] read3mle: DLS 없음 " + BridgeEngine.defaultDls());
		}
		Assume.assumeTrue(dls);
		Path f = Path.of("test-bridge/mabibridge/profile/fixture_3mle.mml").toAbsolutePath();
		ScoreLoader.Loaded src = ScoreLoader.load(engine, f);
		assertEquals("mml", src.format());
		assertEquals("시험곡 3MLE", src.title()); // CP949(ks_c_5601-1987)로 다시 읽은 제목
		assertEquals("클로드", src.author());
		assertEquals("ks_c_5601-1987", src.encoding());
		Map<String, Object> pr = ScoreProfile.build(src, engine.instrumentTable(), null, null).profile();
		assertEquals(2, m(pr, "ensemble").get("track_count"));
		Map<String, Object> t1 = track(pr, 1);
		assertEquals("피아노", t1.get("name"));
		assertEquals(21, t1.get("program"));
		Map<String, Object> mel = m(t1, "parts", "melody");
		assertEquals(8, mel.get("notes"));
		assertEquals(72, m(mel, "written").get("min"));
		assertEquals(84, m(mel, "written").get("max"));
		assertEquals(84, m(mel, "sounding").get("min")); // 피아노 +12
		assertEquals(20, mel.get("chars_source")); // "t100v10l8o5cdefgab>c"
		assertEquals(4, m(t1, "parts", "chord1").get("notes"));
		Map<String, Object> t2 = track(pr, 2);
		assertEquals(0, t2.get("program"));
		assertEquals("류트", t2.get("name"));
		assertEquals(4, m(t2, "parts", "melody").get("notes"));
		assertEquals(100, m(pr, "file").get("initial_tempo"));
		// 0틱: 멜로디 o5c(들리는 C6=84) − 화음1 o4c(들리는 C5=72) = 12
		Map<String, Object> mc1 = m(t1, "voicing", "melody_chord1");
		assertEquals(1, m(mc1, "distribution").get("12"));
		assertEquals(0, mc1.get("crossings"));
	}

	@Test
	public void loaderErrors() throws IOException {
		try {
			ScoreLoader.load(engine, tmp.resolve("a.txt"));
			assertTrue(false);
		} catch (EngineException e) {
			assertEquals(EngineException.BAD_PATH, e.code());
		}
		try {
			ScoreLoader.load(engine, tmp.resolve("없음.mmi"));
			assertTrue(false);
		} catch (EngineException e) {
			assertEquals(EngineException.FILE_NOT_FOUND, e.code());
		}
	}

	@Test
	public void cliWritesJsonAndTsv() throws IOException {
		Project p = fixture();
		Path mmi = tmp.resolve("in").resolve("프로필 시험.mmi");
		p.saveAs(mmi);
		Path out = tmp.resolve("out");
		ByteArrayOutputStream ob = new ByteArrayOutputStream();
		ByteArrayOutputStream eb = new ByteArrayOutputStream();
		int code = ProfileCli.run(new String[] { "--out", out.toString(), tmp.resolve("in").toString() },
				new PrintStream(ob, true, StandardCharsets.UTF_8), new PrintStream(eb, true, StandardCharsets.UTF_8));
		assertEquals(eb.toString(StandardCharsets.UTF_8), 0, code);
		assertTrue(Files.isRegularFile(out.resolve("프로필 시험.profile.json")));
		List<String> pl = Files.readAllLines(out.resolve("profiles.tsv"), StandardCharsets.UTF_8);
		assertEquals(2, pl.size());
		assertTrue(pl.get(0).startsWith("file\tformat\ttitle\ttracks"));
		assertTrue(pl.get(1), pl.get(1).startsWith("프로필 시험.mmi\tmmi\t프로필 시험\t4\t"));
		List<String> tl = Files.readAllLines(out.resolve("tracks.tsv"), StandardCharsets.UTF_8);
		assertEquals(5, tl.size());
		assertTrue(tl.get(3), tl.get(3).contains("\tbass\t"));

		// 없는 파일이 섞이면 건너뛰고 종료 코드 1
		int code2 = ProfileCli.run(new String[] { "--out", out.toString(), mmi.toString(), tmp.resolve("없음.mmi").toString() },
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
				new PrintStream(eb, true, StandardCharsets.UTF_8));
		assertEquals(1, code2);
		assertTrue(eb.toString(StandardCharsets.UTF_8).contains("FILE_NOT_FOUND"));
		assertEquals(2, Files.readAllLines(out.resolve("profiles.tsv"), StandardCharsets.UTF_8).size());
		assertFalse(Files.exists(out.resolve("없음.profile.json")));
	}
}
