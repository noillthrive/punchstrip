package mabibridge.midi;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 참조 MIDI를 열 수 없을 때. 메시지에 파일명과 원인, 가능하면 깨진 위치(트랙·오프셋·틱)가 들어 있다.
 */
public final class ReferenceMidiException extends Exception {
	private static final long serialVersionUID = 1L;

	private final transient Path file;
	private final transient MidiByteScanner.Problem location;

	public ReferenceMidiException(String message, Path file, MidiByteScanner.Problem location, Throwable cause) {
		super(message, cause);
		this.file = file;
		this.location = location;
	}

	/** 열려던 파일. */
	public Path file() {
		return file;
	}

	/** 바이트 스캔으로 찾은 깨진 위치(못 찾았으면 비어 있음). */
	public Optional<MidiByteScanner.Problem> location() {
		return Optional.ofNullable(location);
	}

	/** 깨진 트랙 번호(1부터, 못 찾았거나 헤더 문제면 비어 있음). */
	public OptionalInt trackNumber() {
		return (location != null && location.trackNumber() > 0) ? OptionalInt.of(location.trackNumber()) : OptionalInt.empty();
	}
}
