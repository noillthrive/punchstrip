package mabibridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import mabibridge.engine.EngineException;

/** Render temp files are unique and created exclusively (no DLS needed). */
public class WavTempTest {

	private static Path tmpDir() throws Exception {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "wavtemp-");
	}

	@Test
	public void tempFilesAreUniqueAndNextToTarget() throws Exception {
		Path dir = tmpDir();
		Path target = dir.resolve("song.wav");
		Path a = WavRender.newTemp(target);
		Path b = WavRender.newTemp(target);
		assertNotEquals(a, b);
		assertTrue(Files.isRegularFile(a) && Files.isRegularFile(b));
		assertEquals(dir, a.getParent());
		assertTrue(a.getFileName().toString().startsWith(".song.wav."));
	}

	/** The old fixed temp name (.name.tmp) may exist, even as a folder: it is neither used nor touched. */
	@Test
	public void oldPredictableTempNameIsIgnored() throws Exception {
		Path dir = tmpDir();
		Path target = dir.resolve("m.json");
		Path old = dir.resolve(".m.json.tmp");
		Files.createDirectories(old);
		WavRender.writeText(target, "{}\n", false);
		assertEquals("{}\n", Files.readString(target, StandardCharsets.UTF_8));
		assertTrue(Files.isDirectory(old));
	}

	/** Two writers of the same target at once (as two bridge processes would): no corrupt mix, no leftover temp. */
	@Test
	public void concurrentWritersDoNotCollide() throws Exception {
		Path dir = tmpDir();
		String big1 = "a".repeat(2_000_000);
		String big2 = "b".repeat(2_000_000);
		for (int round = 0; round < 10; round++) {
			Path target = dir.resolve("r" + round + ".json");
			boolean overwrite = round % 2 == 0;
			CountDownLatch go = new CountDownLatch(1);
			AtomicInteger exists = new AtomicInteger();
			List<Throwable> errors = new ArrayList<>();
			List<Thread> ts = new ArrayList<>();
			for (String body : new String[] { big1, big2 }) {
				Thread t = new Thread(() -> {
					try {
						go.await();
						WavRender.writeText(target, body, overwrite);
					} catch (EngineException e) {
						if (EngineException.FILE_EXISTS.equals(e.code())) {
							exists.incrementAndGet();
						} else {
							synchronized (errors) {
								errors.add(e);
							}
						}
					} catch (Throwable e) {
						synchronized (errors) {
							errors.add(e);
						}
					}
				});
				t.start();
				ts.add(t);
			}
			go.countDown();
			for (Thread t : ts) {
				t.join();
			}
			assertTrue(errors.toString(), errors.isEmpty());
			String got = Files.readString(target, StandardCharsets.UTF_8);
			assertTrue("round " + round, got.equals(big1) || got.equals(big2));
			if (!overwrite) {
				assertEquals("round " + round + ": exactly one writer must see FILE_EXISTS", 1, exists.get());
			}
			try (var l = Files.list(dir)) {
				assertEquals("no temp files left", 0, l.filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
			}
		}
	}
}
