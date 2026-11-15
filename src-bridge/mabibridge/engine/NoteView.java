package mabibridge.engine;

/**
 * 응답용 음표. tick·len은 MML 틱(4분음표 = 96), pos는 "마디:박:틱",
 * pitch는 MIDI 번호(60 = C4), name은 음 이름("C4", "F#4"), vel은 0~15.
 *
 * <p>{@code soundingPitch}·{@code soundingName}은 그 트랙 악기(노래 파트는 노래 악기)로 실제 울리는 음
 * ({@link SoundingPitch}). 무음 구간에 적힌 음이면 둘 다 null이고 {@code silent} true, 음높이가 없는 악기(타악기·효과음)는
 * 둘 다 null이고 {@code silent} false. 악기를 모르는 자리에서 만든 음표는 세 필드가 모두 null이다.</p>
 */
public record NoteView(int tick, String pos, int len, int pitch, String name, int vel, Integer soundingPitch,
		String soundingName, Boolean silent) {

	/** 들리는 음 없이(형태). */
	public NoteView(int tick, String pos, int len, int pitch, String name, int vel) {
		this(tick, pos, len, pitch, name, vel, null, null, null);
	}

	/** 악기 번호로 들리는 음을 채운 사본. */
	public NoteView withSounding(int program) {
		SoundingPitch.Sound s = SoundingPitch.Sound.of(program, pitch);
		return new NoteView(tick, pos, len, pitch, name, vel, s.pitch(), s.name(), s.silent());
	}
}
