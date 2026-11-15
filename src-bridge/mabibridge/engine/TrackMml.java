package mabibridge.engine;

import java.util.Map;

/**
 * 트랙 하나의 마비노기용 MML.
 *
 * @param mml   {@code MMLTrack.getMabiMML()} 그대로("MML@…;")
 * @param parts mml을 쉼표로 나눈 파트 문자열(melody, chord1, chord2, song). 노래 파트가 mml에 없으면 song은 ""
 * @param chars parts 각각의 글자 수
 */
public record TrackMml(int track, String name, String mml, Map<String, String> parts, Map<String, Integer> chars) {}
