package mabibridge.engine;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import jp.fourthline.mmlTools.MMLScore;

/**
 * 스냅샷. {@code score.getObjectState()} 바이트(.mmi 텍스트와 같은 형식)를 메모리에 두고,
 * 같은 내용을 {@code <폴더>/<yyyyMMdd-HHmmss-SSS>_<pid>_<id>.mmi}에도 쓴다. 파일 쓰기가 실패해도 메모리 스냅샷은 남는다(path = null).
 */
public final class Snapshots {

	/** 스냅샷 정보. time은 ISO-8601 지역 시각, path는 파일 경로(쓰지 못했으면 null). */
	public record SnapshotInfo(int id, String label, String time, String path) {}

	private record Entry(SnapshotInfo info, byte[] bytes) {}

	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
	private static final long PID = ProcessHandle.current().pid();

	private final Path dir;
	private final List<Entry> entries = new ArrayList<>();
	private int nextId = 1;

	private final Supplier<LocalDateTime> clock;

	public Snapshots(Path dir) {
		this(dir, LocalDateTime::now);
	}

	/** 시험용: 시각을 고정해 파일 이름 충돌을 재현한다. */
	Snapshots(Path dir, Supplier<LocalDateTime> clock) {
		this.dir = dir;
		this.clock = clock;
	}

	public Path dir() {
		return dir;
	}

	/** 같은 이름이 있을 때 접미사 _2, _3 … 을 붙여 볼 최대 횟수. */
	static final int MAX_SUFFIX = 100;

	/**
	 * 지금 악보 상태를 저장한다. 파일 이름은 {@code <yyyyMMdd-HHmmss-SSS>_<pid>_<id>.mmi}(서버 여러 개가 같은 초에
	 * 만들어도 겹치지 않게), 그래도 있으면 {@code _2}, {@code _3} … 을 붙인다.
	 */
	public synchronized SnapshotInfo snapshot(MMLScore score, String label) {
		return snapshot(score.getObjectState(), label);
	}

	/** Same, from state bytes taken earlier ({@code MMLScore.getObjectState()}). */
	public synchronized SnapshotInfo snapshot(byte[] bytes, String label) {
		int id = nextId++;
		LocalDateTime full = clock.get();
		LocalDateTime now = full.withNano(0);
		String path = null;
		if (dir != null) {
			String base = full.format(FILE_TIME) + "_" + PID + "_" + id;
			Path file = dir.resolve(base + ".mmi");
			try {
				Files.createDirectories(dir);
				for (int n = 2;; n++) {
					try {
						Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
						path = file.toAbsolutePath().toString();
						break;
					} catch (FileAlreadyExistsException e) {
						if (n > MAX_SUFFIX) {
							throw e;
						}
						file = dir.resolve(base + "_" + n + ".mmi");
					}
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("[engine] 스냅샷 파일을 쓰지 못함(메모리에는 남김): " + file + " : " + e);
			}
		}
		SnapshotInfo info = new SnapshotInfo(id, label == null ? "" : label, now.toString(), path);
		entries.add(new Entry(info, bytes));
		return info;
	}

	/** 스냅샷 바이트. 없으면 NO_SNAPSHOT. */
	public synchronized byte[] bytes(int id) {
		for (Entry e : entries) {
			if (e.info().id() == id) {
				return e.bytes().clone();
			}
		}
		throw new EngineException(EngineException.NO_SNAPSHOT, "스냅샷 " + id + "번이 없음",
				EngineException.details("id", id));
	}

	public synchronized List<SnapshotInfo> list() {
		List<SnapshotInfo> l = new ArrayList<>();
		for (Entry e : entries) {
			l.add(e.info());
		}
		return Collections.unmodifiableList(l);
	}

	public synchronized int size() {
		return entries.size();
	}
}
