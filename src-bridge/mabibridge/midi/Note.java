package mabibridge.midi;

/**
 * 참조 MIDI의 음표 하나.
 *
 * <p>위치({@code pos}, {@code measure})는 4분음표 = 96틱으로 환산한 시작 틱({@code tick96}) 기준이다.
 * MabiIcco가 같은 MIDI를 가져왔을 때 보이는 위치와 맞추기 위해서다.</p>
 *
 * @param track 트랙 번호(1부터)
 * @param channel MIDI 채널(1~16)
 * @param pitch MIDI 음 번호(0~127)
 * @param pitchName "C4" 형식(MIDI 60 = C4, 올림표는 #)
 * @param velocity 원본 벨로시티(1~127)
 * @param velocity15 velocity/8 을 0~15로 자른 값(MabiIcco MIDI 가져오기의 음량 규칙)
 * @param srcTick 원본 해상도 시작 틱
 * @param srcLength 원본 해상도 길이(틱)
 * @param tick96 96틱 환산 시작({@link MeasureMap#convTick96})
 * @param len96 96틱 환산 길이 = convTick96(끝) - convTick96(시작), 6보다 작으면 6
 * @param pos "마디:박:틱"(마디·박 1부터, 틱은 96 기준 박 안의 위치)
 * @param measure 마디(1부터)
 * @param srcPedalEnd 서스테인 페달(CC64)까지 본 울림 끝(원본 해상도, 배타). 페달이 없으면 {@link #srcEnd()}와 같다
 * @param pedalEnd96 {@code srcPedalEnd}의 96틱 환산. 항상 {@link #end96()} 이상
 */
public record Note(int track, int channel, int pitch, String pitchName, int velocity, int velocity15,
		long srcTick, long srcLength, long tick96, long len96, String pos, int measure, long srcPedalEnd,
		long pedalEnd96) {

	/** 페달 없이 이 음만 있는 생성자(시험·합성용). 울림 끝 = note-off. */
	public Note(int track, int channel, int pitch, String pitchName, int velocity, int velocity15, long srcTick,
			long srcLength, long tick96, long len96, String pos, int measure) {
		this(track, channel, pitch, pitchName, velocity, velocity15, srcTick, srcLength, tick96, len96, pos, measure,
				srcTick + srcLength, tick96 + len96);
	}

	/** 페달 때문에 note-off보다 길게 울리는가. */
	public boolean pedal() {
		return srcPedalEnd > srcEnd();
	}

	/** 원본 해상도 끝 틱(배타). */
	public long srcEnd() {
		return srcTick + srcLength;
	}

	/** 96틱 환산 끝 틱(배타). */
	public long end96() {
		return tick96 + len96;
	}

	/** 옥타브를 뺀 음이름(C, C#, …). */
	public String pitchClass() {
		return MidiNames.pitchClassName(pitch);
	}

	/** 드럼 채널(10) 음표인가. */
	public boolean isDrum() {
		return channel == 10;
	}
}
