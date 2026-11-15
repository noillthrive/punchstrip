package mabibridge.engine;

import java.util.List;
import java.util.Map;

/**
 * 트랙 요약.
 *
 * @param track           1부터
 * @param instrumentName  악기표의 이름(한국어 우선). 표에 없으면 null
 * @param instrumentType  타입 문자(N/P/K/V/C/D/M/0). 표에 없으면 null
 * @param songProgram     노래 악기 번호(−1 = 코러스 없음, −2 = 노래 제외)
 * @param noteCounts      파트별 음표 수(melody, chord1, chord2, song)
 * @param allowedParts    악기 타입이 쓰는 파트
 * @param songRule        이 트랙의 글자 수 기본 판정 기준
 * @param sounding        트랙 악기의 "적은 음 대 들리는 음" 요약({@link SoundingPitch#summary}: "+1옥타브", "구간별" 등)
 * @param songSounding    노래 파트를 내는 노래 악기의 요약(노래 악기가 없거나 트랙 악기가 노래 타입이면 null)
 * @param velocityRule    세기 → MIDI 벨로시티 규칙 "x8"(일반, v15 = 120) 또는 "x11"(타악기 P·실로폰 K, v11에서 자름)
 * @param velocityMax     유효한 가장 큰 세기(15 또는 11)
 */
public record TrackSummary(int track, String name, int program, String instrumentName, String instrumentType,
		int songProgram, String songProgramName, int panpot, int volume, Map<String, Integer> noteCounts,
		List<String> allowedParts, String songRule, String sounding, String songSounding, String velocityRule,
		Integer velocityMax) {}
