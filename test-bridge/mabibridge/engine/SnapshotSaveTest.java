package mabibridge.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

/** (g) snapshot/restore, (h) saveAs 안전. */
public class SnapshotSaveTest {

	private static EngineException expect(String code, Runnable r) {
		try {
			r.run();
		} catch (EngineException e) {
			assertEquals(e.toString(), code, e.code());
			return e;
		}
		fail("EngineException " + code + " 이 나야 함");
		return null;
	}

	@Test
	public void snapshotRestoreRoundTrip() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("snap");
		BridgeEngine e = EngineTestSupport.engine(tmp);
		Project p = e.newProject("snap", "", "4/4", 100);
		p.addTrack("a", 0, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4"), NoteSpec.at(96, 96, "E4")), "strict", false);
		byte[] s1State = p.state();
		String s1Mml = p.mml(1).get(0).mml();
		List<NoteView> s1Notes = p.notes(1, "melody", 1, 10);

		Snapshots.SnapshotInfo info = e.snapshot("before edit");
		assertEquals(1, info.id());
		assertEquals("before edit", info.label());
		assertNotNull(info.path());
		assertTrue(Files.isRegularFile(Path.of(info.path())));
		String fname = Path.of(info.path()).getFileName().toString();
		assertTrue(fname, fname.matches("\\d{8}-\\d{6}-\\d{3}_" + ProcessHandle.current().pid() + "_1\\.mmi"));
		assertArrayEquals(s1State, Files.readAllBytes(Path.of(info.path())));

		p.putNotes(1, "melody", List.of(NoteSpec.at(48, 96, "D4")), "merge", false);
		p.addTrack("b", 21, null, 30, 90);
		assertEquals(2, p.trackCount());
		assertFalse(s1Mml.equals(p.mml(1).get(0).mml()));

		ProjectInfo after = e.restore(1);
		assertEquals(1, after.tracks().size());
		assertEquals(s1Mml, p.mml(1).get(0).mml());
		assertEquals(s1Notes, p.notes(1, "melody", 1, 10));
		assertArrayEquals(s1State, p.state());
		assertEquals(1, e.snapshots().list().size());
		assertEquals(1, after.snapshots());
		expect(EngineException.NO_SNAPSHOT, () -> e.restore(99));
	}

	/**
	 * 같은 스냅샷 폴더를 쓰는 엔진 두 개(서버 두 개와 같은 상황, id는 둘 다 1부터)가 연달아 만들어도 파일이 겹치지 않는다.
	 * 같은 밀리초·같은 pid·같은 id면 _2, _3 접미사로 피한다.
	 */
	@Test
	public void snapshotFileNamesDoNotCollide() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("snap-collide");
		java.util.Set<String> paths = new java.util.HashSet<>();
		for (int k = 0; k < 3; k++) {
			BridgeEngine e = EngineTestSupport.engine(tmp);
			e.newProject("c", "", "4/4", 120);
			for (int i = 0; i < 3; i++) {
				Snapshots.SnapshotInfo info = e.snapshot("s" + i);
				assertNotNull("파일 쓰기 실패", info.path());
				assertTrue("경로 중복: " + info.path(), paths.add(info.path()));
				String name = Path.of(info.path()).getFileName().toString();
				assertTrue(name, name.matches("\\d{8}-\\d{6}-\\d{3}_\\d+_" + info.id() + "(_\\d+)?\\.mmi"));
			}
		}
		assertEquals(9, paths.size());
	}

	/** 같은 시각·같은 pid·같은 id면 _2, _3 접미사로 피한다(시각을 고정한 Snapshots 셋이 같은 폴더를 쓴다). */
	@Test
	public void snapshotSuffixWhenNameTaken() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("snap-suffix");
		BridgeEngine e = EngineTestSupport.engine(tmp);
		Project p = e.newProject("c", "", "4/4", 120);
		java.time.LocalDateTime fixed = java.time.LocalDateTime.of(2026, 11, 1, 9, 30, 15, 123_000_000);
		Path dir = tmp.resolve("fixed");
		String base = "20261101-093015-123_" + ProcessHandle.current().pid() + "_1";
		String[] expected = { base + ".mmi", base + "_2.mmi", base + "_3.mmi" };
		for (String name : expected) {
			Snapshots s = new Snapshots(dir, () -> fixed);
			Snapshots.SnapshotInfo info = s.snapshot(p.score(), "x");
			assertNotNull(info.path());
			assertEquals(name, Path.of(info.path()).getFileName().toString());
		}
	}

	@Test
	public void saveAsRefusesExistingFile() throws Exception {
		Path tmp = EngineTestSupport.tmpDir("save");
		BridgeEngine e = EngineTestSupport.engine(tmp);
		Project p = e.newProject("save", "", "3/4", 90);
		p.addTrack("a", 0, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.atPos("2:3:0", "4", "A4")), "strict", false);
		Path f = tmp.resolve("out.mmi");
		Path saved = p.saveAs(f);
		assertTrue(Files.isRegularFile(saved));
		byte[] first = Files.readAllBytes(saved);
		expect(EngineException.FILE_EXISTS, () -> p.saveAs(f));
		assertArrayEquals("기존 파일은 그대로", first, Files.readAllBytes(saved));
		expect(EngineException.BAD_PATH, () -> p.saveAs(tmp.resolve("out.txt")));

		// 다시 열기: 3/4 박자에서 2:3:0 = 틱 288+192 = 480
		Project q = e.open(saved);
		assertEquals("3/4", q.info().timeSignature());
		assertEquals(480, q.notes(1, "melody", 1, 5).get(0).tick());
		assertEquals("2:3:0", q.notes(1, "melody", 1, 5).get(0).pos());
	}

	/** 시험 몸체(검사 예외 허용). */
	private interface Body {
		void run(Path root) throws Exception;
	}

	/**
	 * 임시 프로젝트 폴더와 그 안의 프로젝트 파일(mabibridge-project.properties)을 만들고 시스템 속성
	 * mabibridge.project로 가리킨 채 body를 돌린다. protect: 한 줄 목록(;)과 protect.N 줄, 공백 든 이름, 절대 경로,
	 * 주석, BOM, 백슬래시.
	 */
	static void withTempProject(Body body) throws Exception {
		Path root = EngineTestSupport.tmpDir("project");
		Files.createDirectories(root.resolve("songs").resolve("prev"));
		Path abs = root.resolveSibling(root.getFileName() + "-abs");
		Files.writeString(root.resolve(ProjectConfig.FILE),
				"\uFEFF# 시험 프로젝트\nprotect = app ; songs\n\nprotect.2 =   refs\\a b  \n# protect.3 = x_commented\n"
						+ "protect.4 = " + abs + "\nrender.dir = out/render\nanalysis.dir = " + abs.resolve("ana") + "\n",
				StandardCharsets.UTF_8);
		String prev = System.getProperty(ProjectConfig.PROPERTY);
		System.setProperty(ProjectConfig.PROPERTY, root.resolve(ProjectConfig.FILE).toString());
		try {
			body.run(root);
		} finally {
			if (prev == null) {
				System.clearProperty(ProjectConfig.PROPERTY);
			} else {
				System.setProperty(ProjectConfig.PROPERTY, prev);
			}
		}
	}

	@Test
	public void saveAsRefusesProtectedFolders() throws Exception {
		withTempProject(root -> {
			Path tmp = EngineTestSupport.tmpDir("protect");
			BridgeEngine e = EngineTestSupport.engine(tmp);
			Project p = e.newProject("protect", "", "4/4", 120);
			p.addTrack("a", 0, null, null, null);
			Path lower = Path.of(root.toString().toLowerCase(Locale.ROOT));
			Path[] targets = {
					root.resolve("songs").resolve("bridge_should_not_write.mmi"),
					root.resolve("songs").resolve("prev").resolve("새폴더").resolve("x.mmi"),
					root.resolve("refs").resolve("a b").resolve("x.mmi"),
					root.resolve("app").resolve("bridge_should_not_write.mmi"),
					lower.resolve("app").resolve("MabiIcco-1.5.12").resolve("..").resolve("y.mmi"),
			};
			for (Path t : targets) {
				expect(EngineException.PROTECTED_PATH, () -> p.saveAs(t));
				assertFalse("보호 폴더에 파일이 생기면 안 됨: " + t, Files.exists(t));
			}
			assertFalse(Files.exists(root.resolve("songs").resolve("prev").resolve("새폴더")));
			// 보호 목록 밖은 저장된다.
			assertTrue(Files.exists(p.saveAs(root.resolve("free").resolve("ok.mmi"))));
		});
	}

	/** 기본 보호 폴더 = 프로젝트 파일의 protect 항목(상대·절대, 주석·빈 줄 무시, 폴더가 없어도). */
	@Test
	public void protectedDefaultsFollowProjectFile() throws Exception {
		withTempProject(root -> {
			List<Path> dirs = BridgeEngine.defaultProtectedDirs();
			Path abs = root.resolveSibling(root.getFileName() + "-abs");
			assertEquals(List.of(root.resolve("app"), root.resolve("songs"), root.resolve("refs").resolve("a b"), abs),
					dirs);
			ProjectConfig.Loaded l = ProjectConfig.find();
			assertEquals(root.resolve("out").resolve("render"), l.renderDir());
			assertEquals(abs.resolve("ana"), l.analysisDir());
			assertTrue(l.warnings().toString(), l.warnings().isEmpty());
		});
	}

	/** mabibridge.project=none: no project file, no default protection. */
	@Test
	public void projectLookupCanBeSwitchedOff() throws Exception {
		String prev = System.getProperty(ProjectConfig.PROPERTY);
		System.setProperty(ProjectConfig.PROPERTY, "none");
		try {
			assertEquals(null, ProjectConfig.find());
			assertTrue(BridgeEngine.defaultProtectedDirs().isEmpty());
		} finally {
			if (prev == null) {
				System.clearProperty(ProjectConfig.PROPERTY);
			} else {
				System.setProperty(ProjectConfig.PROPERTY, prev);
			}
		}
	}

	/** Lookup order: code folder ancestors first, then the working directory; unknown keys warn. */
	@Test
	public void projectLookupOrderAndWarnings() throws Exception {
		Path a = EngineTestSupport.tmpDir("look-a");
		Path b = EngineTestSupport.tmpDir("look-b");
		Path codeDir = a.resolve("x").resolve("y");
		Path cwd = b.resolve("z");
		Files.createDirectories(codeDir);
		Files.createDirectories(cwd);
		Path outer = ProjectConfig.locate(null, null, codeDir, cwd); // a project file further up (or none)
		assertTrue(String.valueOf(outer), outer == null || (!outer.startsWith(a) && !outer.startsWith(b)));
		// a file above the code folder (e.g. when the repository itself sits inside a project) always wins over cwd
		Path codeOuter = ProjectConfig.locate(null, null, codeDir, codeDir);
		Files.writeString(b.resolve(ProjectConfig.FILE), "colour = red\nprotect = q\n", StandardCharsets.UTF_8);
		assertEquals(codeOuter != null ? codeOuter : b.resolve(ProjectConfig.FILE),
				ProjectConfig.locate(null, null, codeDir, cwd));
		Files.writeString(a.resolve(ProjectConfig.FILE), "protect = p\n", StandardCharsets.UTF_8);
		assertEquals(a.resolve(ProjectConfig.FILE), ProjectConfig.locate(null, null, codeDir, cwd));
		assertEquals(b.resolve("f.properties"), ProjectConfig.locate(null, b.resolve("f.properties").toString(), codeDir, cwd));
		ProjectConfig.Loaded l = ProjectConfig.load(b.resolve(ProjectConfig.FILE));
		assertEquals(1, l.warnings().size());
		assertEquals(List.of(b.resolve("q")), l.protect());
		assertEquals(null, l.renderDir());
	}

	@Test
	public void noProject() {
		BridgeEngine e = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(null));
		expect(EngineException.NO_PROJECT, () -> e.project());
		expect(EngineException.NO_PROJECT, () -> e.snapshot("x"));
	}
}
