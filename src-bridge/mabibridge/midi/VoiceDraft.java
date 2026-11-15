package mabibridge.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 참조 음들을 1~3성부로 나눈 초안(읽기만). 악보·참조를 바꾸지 않는다.
 *
 * <p>규칙(같은 시작 틱 = 한 묶음):</p>
 * <ol>
 * <li>묶음의 가장 높은 음 → 1성부, 2음 이상이고 성부가 2개 이상이면 가장 낮은 음 → 마지막 성부.</li>
 * <li>나머지 음 → 비어 있는 성부 중 그 성부의 앞 음(없으면 기준음 home)과 가장 가까운 성부.
 *     음이 성부보다 많으면 묶음에 새 음이름(옥타브 무시)을 더하는 음을 먼저 고르고, 나머지는 버리며 이유를 남긴다.</li>
 * <li>한 성부 안에서 음이 다음 시작을 넘으면 그 시작에서 끊는다(겹침 없음).</li>
 * <li>세기: round(velocity / 127 × 15), 1~15로 자름.</li>
 * </ol>
 * <p>같은 시작·같은 음이 여러 트랙에 있으면 하나만 남긴다(길이는 긴 쪽, 세기는 큰 쪽). 드럼 채널 음과
 * 길이 0 음(원본 길이 0틱)은 넣지 않는다.</p>
 */
public final class VoiceDraft {

	public static final int MAX_VOICES = 3;
	/** 기본 기준음: 1성부 C5(72), 2성부 C4(60), 3성부 C3(48). 2성부 초안은 [72, 48], 1성부는 [72]. */
	public static final List<Integer> DEFAULT_HOMES_3 = List.of(72, 60, 48);
	public static final List<Integer> DEFAULT_HOMES_2 = List.of(72, 48);
	public static final List<Integer> DEFAULT_HOMES_1 = List.of(72);

	private static final String[] PARTS = { "melody", "chord1", "chord2" };
	private static final String[] PART_KO = { "멜로디", "화음1", "화음2" };

	/** 성부에 넣은 음. tick·len은 96틱(4분음표 = 96), pitch는 들리는 MIDI 번호, vel은 0~15 세기. */
	public record DraftNote(long tick, long len, int pitch, int vel, String pos, int track, int srcVelocity) {}

	/** 버린 음과 이유 코드(duplicate · no_free_voice · same_pitch_class · voices_1). */
	public record Dropped(long tick, String pos, int pitch, String name, int track, String reason, String reasonText) {}

	/** 성부 하나. */
	public record Voice(int index, String partSuggestion, List<DraftNote> notes) {}

	/**
	 * 결과.
	 *
	 * @param groups 시작 틱 묶음 수(잘랐으면 남긴 범위 안만)
	 * @param inputNotes 쓴 참조 음 수(드럼·길이 0 제외 전, 잘랐으면 남긴 범위 안만)
	 * @param skippedDrum 뺀 드럼 채널 음 수
	 * @param skippedZeroLength 뺀 길이 0 음 수
	 * @param trimmed 다음 시작에서 끊은 음 수(남긴 음끼리)
	 * @param truncated maxNotes를 넘어 뒤쪽을 잘랐는가
	 * @param truncatedFromTick 잘랐다면 남기지 않은 첫 음의 틱(아니면 null)
	 * @param lengthSource "note_off" 또는 "pedal_end"
	 */
	public record Result(List<Voice> voices, List<Dropped> dropped, int groups, int inputNotes, int skippedDrum,
			int skippedZeroLength, int trimmed, boolean truncated, Long truncatedFromTick, List<Integer> homePitches,
			String lengthSource, String summaryText) {}

	private VoiceDraft() {}

	public static List<Integer> defaultHomes(int voices) {
		return switch (voices) {
		case 1 -> DEFAULT_HOMES_1;
		case 2 -> DEFAULT_HOMES_2;
		default -> DEFAULT_HOMES_3;
		};
	}

	/** 96틱 세기 변환: round(v / 127 × 15), 1~15. */
	public static int vel15(int velocity) {
		int v = (int) Math.round(velocity / 127.0 * 15.0);
		return Math.max(1, Math.min(15, v));
	}

	/**
	 * 초안을 만든다.
	 *
	 * @param notes 참조 음(아무 순서)
	 * @param map96 96틱 마디 지도(pos 문자열용)
	 * @param voices 성부 수 1~3
	 * @param homes 성부마다 기준음(길이 = voices)
	 * @param maxNotes 내보낼 최대 음 수(넘으면 늦은 음부터 자름)
	 * @param usePedal true면 길이를 페달 끝(pedal_end)까지로 본다
	 */
	public static Result draft(List<Note> notes, MeasureMap map96, int voices, List<Integer> homes, int maxNotes,
			boolean usePedal) {
		if (voices < 1 || voices > MAX_VOICES) {
			throw new IllegalArgumentException("voices 1~3: " + voices);
		}
		if (homes.size() != voices) {
			throw new IllegalArgumentException("homes 길이 != voices");
		}
		int skippedDrum = 0;
		int skippedZero = 0;
		List<Dropped> dropped = new ArrayList<>();
		// 시작 틱 → (음 → 대표 음표)
		TreeMap<Long, Map<Integer, Cand>> groups = new TreeMap<>();
		for (Note n : notes) {
			if (n.isDrum()) {
				skippedDrum++;
				continue;
			}
			if (n.srcLength() <= 0) {
				skippedZero++;
				continue;
			}
			long len = usePedal ? n.pedalEnd96() - n.tick96() : n.len96();
			Map<Integer, Cand> g = groups.computeIfAbsent(n.tick96(), k -> new LinkedHashMap<>());
			Cand c = g.get(n.pitch());
			if (c == null) {
				g.put(n.pitch(), new Cand(n, len));
			} else {
				// 같은 시작·같은 음: 하나만(긴 길이, 큰 세기)
				Note keep = n.velocity() > c.note.velocity() ? n : c.note;
				Note lose = keep == n ? c.note : n;
				g.put(n.pitch(), new Cand(keep, Math.max(len, c.len)));
				dropped.add(drop(lose, map96, "duplicate", "같은 시작·같은 음이 다른 트랙에도 있어 하나만 남김"));
			}
		}

		List<List<DraftNote>> vs = new ArrayList<>();
		int[] prev = new int[voices];
		for (int i = 0; i < voices; i++) {
			vs.add(new ArrayList<>());
			prev[i] = homes.get(i);
		}
		for (Map.Entry<Long, Map<Integer, Cand>> e : groups.entrySet()) {
			List<Cand> g = new ArrayList<>(e.getValue().values());
			g.sort(Comparator.comparingInt((Cand c) -> c.note.pitch()).reversed());
			Cand[] assign = new Cand[voices];
			assign[0] = g.get(0);
			List<Cand> rest = new ArrayList<>(g.subList(1, g.size()));
			if (voices == 1) {
				for (Cand c : rest) {
					dropped.add(drop(c.note, map96, "voices_1", "성부가 1개라 묶음의 가장 높은 음만 남김"));
				}
				rest.clear();
			} else if (!rest.isEmpty()) {
				assign[voices - 1] = rest.remove(rest.size() - 1);
			}
			Set<Integer> pcs = new HashSet<>();
			for (Cand c : assign) {
				if (c != null) {
					pcs.add(c.note.pitch() % 12);
				}
			}
			// 가운데 성부 채우기
			while (!rest.isEmpty()) {
				Cand bestC = null;
				int bestV = -1;
				int bestNew = 2;
				int bestD = Integer.MAX_VALUE;
				for (Cand c : rest) {
					int isNew = pcs.contains(c.note.pitch() % 12) ? 1 : 0;
					for (int v = 1; v < voices - 1; v++) {
						if (assign[v] != null) {
							continue;
						}
						int d = Math.abs(c.note.pitch() - prev[v]);
						if (isNew < bestNew || (isNew == bestNew && d < bestD)) {
							bestNew = isNew;
							bestD = d;
							bestC = c;
							bestV = v;
						}
					}
				}
				if (bestC == null) {
					break; // 빈 성부가 없음
				}
				assign[bestV] = bestC;
				pcs.add(bestC.note.pitch() % 12);
				rest.remove(bestC);
			}
			for (Cand c : rest) {
				boolean samePc = pcs.contains(c.note.pitch() % 12);
				dropped.add(drop(c.note, map96, samePc ? "same_pitch_class" : "no_free_voice",
						samePc ? "성부가 모자라 버림(같은 음이름이 이미 다른 성부에 있음)" : "성부가 모자라 버림(새 음이름이지만 빈 성부 없음)"));
			}
			for (int v = 0; v < voices; v++) {
				Cand c = assign[v];
				if (c == null) {
					continue;
				}
				Note n = c.note;
				vs.get(v).add(new DraftNote(n.tick96(), c.len, n.pitch(), vel15(n.velocity()),
						map96.position(n.tick96()).toString(), n.track(), n.velocity()));
				prev[v] = n.pitch();
			}
		}

		// 최대 음 수: 늦은 묶음부터 통째로 자른다. 집계(버린 음·끊은 음·묶음 수·입력 수)는 남긴 범위(자른 틱 앞)만 센다
		int total = 0;
		for (List<DraftNote> l : vs) {
			total += l.size();
		}
		boolean truncated = false;
		Long cutTick = null;
		int groupCount = groups.size();
		int inputCount = notes.size();
		if (total > maxNotes) {
			List<Long> ticks = new ArrayList<>();
			for (List<DraftNote> l : vs) {
				for (DraftNote d : l) {
					ticks.add(d.tick());
				}
			}
			ticks.sort(null);
			long limitTick = ticks.get(maxNotes); // 이 틱부터는 남기지 않는다(같은 틱 묶음은 통째로)
			truncated = true;
			cutTick = limitTick;
			for (List<DraftNote> l : vs) {
				l.removeIf(d -> d.tick() >= limitTick);
			}
			dropped.removeIf(d -> d.tick() >= limitTick);
			groupCount = groups.headMap(limitTick).size();
			inputCount = 0;
			skippedDrum = 0;
			skippedZero = 0;
			for (Note n : notes) {
				if (n.tick96() >= limitTick) {
					continue;
				}
				inputCount++;
				if (n.isDrum()) {
					skippedDrum++;
				} else if (n.srcLength() <= 0) {
					skippedZero++;
				}
			}
		}

		// 성부 안 겹침 끊기(남긴 음끼리만)
		int trimmed = 0;
		for (List<DraftNote> l : vs) {
			for (int i = 0; i + 1 < l.size(); i++) {
				DraftNote x = l.get(i);
				long next = l.get(i + 1).tick();
				if (x.tick() + x.len() > next) {
					l.set(i, new DraftNote(x.tick(), next - x.tick(), x.pitch(), x.vel(), x.pos(), x.track(), x.srcVelocity()));
					trimmed++;
				}
			}
		}

		List<Voice> outV = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		sb.append(voices).append("성부 초안: ");
		for (int v = 0; v < voices; v++) {
			String part = partFor(v, voices);
			outV.add(new Voice(v + 1, part, List.copyOf(vs.get(v))));
			if (v > 0) {
				sb.append(", ");
			}
			sb.append(partKo(part)).append(' ').append(vs.get(v).size()).append("음");
		}
		sb.append(", 버린 음 ").append(dropped.size());
		if (trimmed > 0) {
			sb.append(", 끊은 음 ").append(trimmed);
		}
		if (truncated) {
			sb.append(", 최대 음 수 ").append(maxNotes).append("를 넘어 틱 ").append(cutTick).append("부터 자름");
		}
		return new Result(List.copyOf(outV), List.copyOf(dropped), groupCount, inputCount, skippedDrum, skippedZero,
				trimmed, truncated, cutTick, List.copyOf(homes), usePedal ? "pedal_end" : "note_off", sb.toString());
	}

	/** 성부 번호(0부터) → 파트 제안. 마지막 성부는 성부 수에 따라 chord1 또는 chord2. */
	static String partFor(int v, int voices) {
		return PARTS[Math.min(v, PARTS.length - 1)];
	}

	private static String partKo(String part) {
		for (int i = 0; i < PARTS.length; i++) {
			if (PARTS[i].equals(part)) {
				return PART_KO[i];
			}
		}
		return part;
	}

	private record Cand(Note note, long len) {}

	private static Dropped drop(Note n, MeasureMap map96, String reason, String text) {
		return new Dropped(n.tick96(), map96.position(n.tick96()).toString(), n.pitch(), n.pitchName(), n.track(), reason,
				text);
	}
}
