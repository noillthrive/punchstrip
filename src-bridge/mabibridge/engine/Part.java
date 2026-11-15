package mabibridge.engine;

/** 트랙 안의 파트 4개. 엔진의 파트 번호(0~3)와 브리지의 이름을 잇는다. */
public enum Part {
	MELODY("melody"),
	CHORD1("chord1"),
	CHORD2("chord2"),
	SONG("song");

	private final String key;

	Part(String key) {
		this.key = key;
	}

	/** 브리지에서 쓰는 이름("melody" 등). */
	public String key() {
		return key;
	}

	/** 엔진 파트 번호(0 멜로디, 1 화음1, 2 화음2, 3 노래). */
	public int index() {
		return ordinal();
	}

	public static Part of(String key) {
		if (key != null) {
			for (Part p : values()) {
				if (p.key.equalsIgnoreCase(key.trim())) {
					return p;
				}
			}
		}
		throw new EngineException(EngineException.BAD_ARGUMENT,
				"파트 이름이 틀림: " + key + " (melody, chord1, chord2, song 중 하나)",
				EngineException.details("part", key));
	}

	public static Part ofIndex(int index) {
		return values()[index];
	}
}
