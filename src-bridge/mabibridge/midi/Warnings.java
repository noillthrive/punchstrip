package mabibridge.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 참조 MIDI를 열면서 발견한 비정상과, 이 모듈이 해석하지 않고 버린 이벤트의 종류별 개수.
 */
public final class Warnings {

	/** 2시간. 곡 길이가 이 이상이면 비정상으로 본다. */
	public static final double ABNORMAL_LENGTH_SECONDS = 2 * 60 * 60;

	private final List<String> messages;
	private final Map<String, Integer> dropped;
	private final int unfinishedNotes;
	private final int retriggers;
	private final int orphanNoteOffs;
	private final int zeroLengthNotes;
	private final int invalidDataBytes;
	private final boolean abnormalLength;
	private final boolean byteScanProblem;

	private Warnings(Builder b, List<String> messages) {
		this.messages = Collections.unmodifiableList(messages);
		this.dropped = Collections.unmodifiableMap(new TreeMap<>(b.dropped));
		this.unfinishedNotes = b.unfinishedNotes;
		this.retriggers = b.retriggers;
		this.orphanNoteOffs = b.orphanNoteOffs;
		this.zeroLengthNotes = b.zeroLengthNotes;
		this.invalidDataBytes = b.invalidDataBytes;
		this.abnormalLength = b.abnormalLength;
		this.byteScanProblem = b.byteScanProblem;
	}

	/** 사람이 읽는 경고 문장 목록(버린 이벤트 요약 포함). */
	public List<String> messages() {
		return messages;
	}

	/** 버린 이벤트: 종류 → 개수(종류 이름순). */
	public Map<String, Integer> droppedEvents() {
		return dropped;
	}

	/** 끝나지 않아 트랙 끝까지로 처리한 음 수. */
	public int unfinishedNotes() {
		return unfinishedNotes;
	}

	/** 울리는 중에 같은 채널·같은 음이 다시 note-on 된 수. */
	public int retriggers() {
		return retriggers;
	}

	/** 짝이 되는 note-on이 없는 note-off 수. */
	public int orphanNoteOffs() {
		return orphanNoteOffs;
	}

	/** 원본 길이가 0틱인 음 수. */
	public int zeroLengthNotes() {
		return zeroLengthNotes;
	}

	/** 데이터 바이트가 0x80 이상이라 버린 채널 메시지 수(Java 리더가 걸러내지 못한 깨진 데이터). */
	public int invalidDataBytes() {
		return invalidDataBytes;
	}

	/** 곡 길이가 {@link #ABNORMAL_LENGTH_SECONDS} 이상인가. */
	public boolean abnormalLength() {
		return abnormalLength;
	}

	/** Java는 열었지만 바이트 스캔에서 구조 문제가 나왔는가. */
	public boolean byteScanProblem() {
		return byteScanProblem;
	}

	public boolean isEmpty() {
		return messages.isEmpty();
	}

	static final class Builder {
		private final List<String> messages = new ArrayList<>();
		private final Map<String, Integer> dropped = new TreeMap<>();
		int unfinishedNotes;
		int retriggers;
		int orphanNoteOffs;
		int zeroLengthNotes;
		int invalidDataBytes;
		boolean abnormalLength;
		boolean byteScanProblem;

		void add(String message) {
			messages.add(message);
		}

		void drop(String kind) {
			dropped.merge(kind, 1, Integer::sum);
		}

		Warnings build() {
			List<String> all = new ArrayList<>(messages);
			if (!dropped.isEmpty()) {
				StringBuilder sb = new StringBuilder("해석하지 않고 버린 이벤트: ");
				boolean first = true;
				for (Map.Entry<String, Integer> e : dropped.entrySet()) {
					if (!first) {
						sb.append(", ");
					}
					first = false;
					sb.append(e.getKey()).append(" ").append(e.getValue()).append("개");
				}
				all.add(sb.toString());
			}
			return new Warnings(this, all);
		}
	}
}
