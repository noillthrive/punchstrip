package mabibridge.midi;

/**
 * 음 이름, 음이름(옥타브 없는 이름), GM 악기 이름, 조표 이름 변환.
 */
public final class MidiNames {

	private static final String[] PITCH_CLASSES = {
		"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
	};

	private static final String[] GM = {
		// 피아노
		"Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
		"Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
		// 크로매틱 퍼커션
		"Celesta", "Glockenspiel", "Music Box", "Vibraphone",
		"Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
		// 오르간
		"Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
		"Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
		// 기타
		"Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
		"Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
		// 베이스
		"Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
		"Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
		// 현악
		"Violin", "Viola", "Cello", "Contrabass",
		"Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
		// 앙상블
		"String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
		"Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
		// 금관
		"Trumpet", "Trombone", "Tuba", "Muted Trumpet",
		"French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
		// 리드
		"Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
		"Oboe", "English Horn", "Bassoon", "Clarinet",
		// 파이프
		"Piccolo", "Flute", "Recorder", "Pan Flute",
		"Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
		// 신스 리드
		"Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
		"Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
		// 신스 패드
		"Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
		"Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
		// 신스 효과
		"FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
		"FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
		// 민속 악기
		"Sitar", "Banjo", "Shamisen", "Koto",
		"Kalimba", "Bagpipe", "Fiddle", "Shanai",
		// 타악
		"Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
		"Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
		// 효과음
		"Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
		"Telephone Ring", "Helicopter", "Applause", "Gunshot"
	};

	private static final String[] MAJOR_KEYS = {
		"Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#"
	};
	private static final String[] MINOR_KEYS = {
		"Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#"
	};

	private MidiNames() {}

	/** MIDI 음 번호를 "C4" 형식으로(MIDI 60 = C4, 올림표는 #). */
	public static String pitchName(int pitch) {
		if (pitch < 0 || pitch > 127) {
			throw new IllegalArgumentException("pitch out of range: " + pitch);
		}
		return PITCH_CLASSES[pitch % 12] + (pitch / 12 - 1);
	}

	/** 옥타브를 뺀 음이름(0=C … 11=B). */
	public static String pitchClassName(int pitchClass) {
		return PITCH_CLASSES[Math.floorMod(pitchClass, 12)];
	}

	/** 음이름 문자열의 순번(C=0 … B=11). 모르면 -1. */
	public static int pitchClassIndex(String name) {
		for (int i = 0; i < PITCH_CLASSES.length; i++) {
			if (PITCH_CLASSES[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** GM 악기 이름(프로그램 0~127). 범위 밖이면 "?". */
	public static String gmName(int program) {
		return (program >= 0 && program < GM.length) ? GM[program] : "?";
	}

	/** 조표 메타(sf: -7~7, minor: 0 장조 / 1 단조)를 "Eb major" 형식으로. */
	public static String keyName(int sharpsFlats, boolean minor) {
		if (sharpsFlats < -7 || sharpsFlats > 7) {
			return "unknown(sf=" + sharpsFlats + ")";
		}
		return (minor ? MINOR_KEYS : MAJOR_KEYS)[sharpsFlats + 7] + (minor ? " minor" : " major");
	}
}
