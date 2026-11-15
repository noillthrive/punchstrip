package mabibridge.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.midi.MeasureMap;

/**
 * 렌더로는 들리지 않는 악보 정적 검사.
 *
 * <ul>
 * <li>{@code unison_clash}: 한 트랙 안에서 서로 다른 두 파트가 같은 적은 음을 같은 틱에 시작하거나, 한쪽이 울리는 동안 다른 쪽이
 * 같은 음을 시작한다(다시 치기). 마비노기는 악기 하나가 그 트랙의 파트를 모두 연주해서 뒤 음이 앞 음을 끊는다. 브리지 렌더는 파트를
 * 다른 채널로 울려서 이 끊김을 재현하지 못한다. 노래 파트는 노래 악기가 따로 있으면(울리는 악기가 다르면) 빼고, 노래 제외 트랙이면 뺀다.</li>
 * <li>{@code tempo_mid_note}: 템포 변경 틱이 어떤 파트 음의 울림 안(시작 &lt; 틱 &lt; 끝)에 떨어진다.</li>
 * <li>{@code tempo_end_differs}: 마지막 템포 변경의 BPM이 처음 템포(0틱에서의 템포)와 다르다(장을 이어 연주할 때 문제).</li>
 * </ul>
 */
public final class ScoreChecks {

	public static final String UNISON_CLASH = "unison_clash";
	public static final String TEMPO_MID_NOTE = "tempo_mid_note";
	public static final String TEMPO_END_DIFFERS = "tempo_end_differs";

	/** 예시 최대 수. */
	public static final int MAX_EXAMPLES = 10;
	/** tempo_mid_note 항목 최대 수. */
	public static final int MAX_TEMPO_ITEMS = 20;

	/** 타악기 타입(compare와 같음). */
	static final Set<String> DRUM_TYPES = Set.of("D", "P", "M");

	private ScoreChecks() {}

	/**
	 * 같은 음 겹침 예시. type은 "same_start"(같은 틱 시작) 또는 "retrigger"(앞 음이 울리는 동안 다시 침).
	 * pos·tick은 뒤에 시작한 음(같은 틱이면 그 틱), parts는 [앞 음 파트, 뒤 음 파트](같은 틱이면 파트 순서).
	 */
	public record Clash(int tick, String pos, int pitch, String name, List<String> parts, String type) {}

	/** 트랙 하나의 unison_clash 결과. kind는 "drum"(타입 D·P·M) 또는 "pitched". */
	public record UnisonClash(int track, String kind, int count, int sameStart, int retrigger, List<Clash> examples) {}

	/** 비교할 파트(같은 악기가 울리는 파트들). */
	static List<Part> clashParts(MMLTrack t, String instrumentType) {
		List<Part> l = new ArrayList<>(List.of(Part.MELODY, Part.CHORD1, Part.CHORD2));
		int main = SoundingPitch.programFor(t.getProgram(), t.getSongProgram(), Part.MELODY, instrumentType);
		int song = SoundingPitch.programFor(t.getProgram(), t.getSongProgram(), Part.SONG, instrumentType);
		if (t.getSongProgram() != MMLTrack.EXCLUDE_SONG && song == main) {
			l.add(Part.SONG);
		}
		return l;
	}

	/** 트랙 하나를 검사한다. */
	public static UnisonClash unisonClash(MMLTrack t, int trackNo, String instrumentType, MeasureMap map) {
		List<Part> parts = clashParts(t, instrumentType);
		int count = 0;
		int same = 0;
		int re = 0;
		List<Clash> ex = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			List<MMLNoteEvent> a = t.getMMLEventAtIndex(parts.get(i).index()).getMMLNoteEventList();
			for (int j = i + 1; j < parts.size(); j++) {
				List<MMLNoteEvent> b = t.getMMLEventAtIndex(parts.get(j).index()).getMMLNoteEventList();
				int lo = 0;
				for (MMLNoteEvent x : a) {
					// b는 시작 순. x 시작 전에 끝난 b 음은 다음 x에서도 볼 필요가 없다(x 시작도 오름차순, 파트 안 음은 겹치지 않음).
					while (lo < b.size() && b.get(lo).getEndTick() <= x.getTickOffset()) {
						lo++;
					}
					for (int k = lo; k < b.size(); k++) {
						MMLNoteEvent y = b.get(k);
						if (y.getTickOffset() >= x.getEndTick()) {
							break;
						}
						if (y.getNote() != x.getNote() || y.getEndTick() <= x.getTickOffset()) {
							continue;
						}
						// 두 음의 구간이 겹친다. 같은 시작이거나, 한쪽이 다른 쪽 울림 안에서 시작한 것.
						count++;
						String type;
						MMLNoteEvent later;
						List<String> ps;
						if (y.getTickOffset() == x.getTickOffset()) {
							same++;
							type = "same_start";
							later = y;
							ps = List.of(parts.get(i).key(), parts.get(j).key());
						} else if (y.getTickOffset() > x.getTickOffset()) {
							re++;
							type = "retrigger";
							later = y;
							ps = List.of(parts.get(i).key(), parts.get(j).key());
						} else {
							re++;
							type = "retrigger";
							later = x;
							ps = List.of(parts.get(j).key(), parts.get(i).key());
						}
						ex.add(new Clash(later.getTickOffset(), map.position(later.getTickOffset()).toString(),
								later.getNote() + 12, NoteSpec.pitchName(later.getNote() + 12), ps, type));
					}
				}
			}
		}
		ex.sort((p, q) -> p.tick() != q.tick() ? Integer.compare(p.tick(), q.tick())
				: p.parts().toString().compareTo(q.parts().toString()));
		List<Clash> top = List.copyOf(ex.subList(0, Math.min(MAX_EXAMPLES, ex.size())));
		String kind = (instrumentType != null && DRUM_TYPES.contains(instrumentType)) ? "drum" : "pitched";
		return new UnisonClash(trackNo, kind, count, same, re, top);
	}

	/** 쓰기 응답 warnings에 넣을 한 줄(겹침이 없으면 null). */
	public static String unisonWarning(UnisonClash u) {
		if (u.count() == 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("unison_clash: 트랙 ").append(u.track()).append("에서 다른 파트끼리 같은 음이 겹침 ").append(u.count())
				.append("곳(같은 틱 ").append(u.sameStart()).append(", 다시 치기 ").append(u.retrigger()).append(")");
		if ("drum".equals(u.kind())) {
			sb.append(", 타악기 트랙");
		}
		sb.append(" — 처음 ");
		for (int i = 0; i < Math.min(3, u.examples().size()); i++) {
			Clash c = u.examples().get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(c.pos()).append(' ').append(c.name()).append(' ').append(String.join("·", c.parts()));
		}
		sb.append(". 게임에서는 뒤 음이 앞 음을 끊는다(렌더로는 들리지 않음)");
		return sb.toString();
	}

	/** 템포 변경이 걸린 음. */
	public record TempoMidNote(int tick, String pos, int bpm, int track, String part, NoteView note) {}

	/**
	 * 템포 변경 틱이 트랙 trackNo 음의 울림 안(시작 &lt; 틱 &lt; 끝)에 떨어진 곳. 같은 템포 변경이 여러 음에 걸리면 음마다 한 항목.
	 * 음표에는 다른 음표 응답처럼 들리는 음(sounding_pitch·sounding_name·silent, 노래 파트는 노래 악기 기준)을 채운다.
	 */
	public static List<TempoMidNote> tempoMidNotes(MMLScore score, MeasureMap map, int trackNo, String instrumentType) {
		Integer onlyTrack = trackNo;
		List<TempoMidNote> l = new ArrayList<>();
		for (MMLTempoEvent te : score.getTempoEventList()) {
			int tick = te.getTickOffset();
			for (int ti = 0; ti < score.getTrackCount(); ti++) {
				if (onlyTrack != null && onlyTrack != ti + 1) {
					continue;
				}
				MMLTrack t = score.getTrack(ti);
				for (Part p : Part.values()) {
					for (MMLNoteEvent e : t.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
						if (e.getTickOffset() >= tick) {
							break;
						}
						if (e.getEndTick() > tick) {
							int prog = SoundingPitch.programFor(t.getProgram(), t.getSongProgram(), p, instrumentType);
							l.add(new TempoMidNote(tick, map.position(tick).toString(), te.getTempo(), ti + 1, p.key(),
									NoteSpec.view(e, map).withSounding(prog)));
						}
					}
				}
			}
		}
		return l;
	}

	/** 처음 템포(0틱)와 마지막 템포 변경. differs가 false면 경고 아님. 템포 변경이 없으면 null. */
	public record TempoEnd(int firstBpm, int lastBpm, int lastTick, String lastPos, boolean differs) {}

	public static TempoEnd tempoEnd(MMLScore score, MeasureMap map) {
		List<MMLTempoEvent> l = score.getTempoEventList();
		if (l.isEmpty()) {
			return null;
		}
		int first = MMLTempoEvent.searchOnTick(l, 0);
		MMLTempoEvent last = l.get(l.size() - 1);
		return new TempoEnd(first, last.getTempo(), last.getTickOffset(), map.position(last.getTickOffset()).toString(),
				first != last.getTempo());
	}

	/**
	 * score_stats의 트랙별 warnings 목록. 항목은 {@code code}로 구분하는 객체이고, 없으면 빈 목록.
	 * unison_clash(이 트랙), tempo_mid_note(이 트랙의 음). 악보 전체 검사(tempo_end_differs)는 {@link #scoreWarnings}.
	 */
	public static List<Map<String, Object>> trackWarnings(MMLScore score, int trackNo, String instrumentType,
			MeasureMap map) {
		List<Map<String, Object>> w = new ArrayList<>();
		UnisonClash u = unisonClash(score.getTrack(trackNo - 1), trackNo, instrumentType, map);
		if (u.count() > 0) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", UNISON_CLASH);
			m.put("track", trackNo);
			m.put("kind", u.kind());
			m.put("count", u.count());
			m.put("same_start", u.sameStart());
			m.put("retrigger", u.retrigger());
			m.put("examples", u.examples());
			m.put("message", unisonWarning(u));
			w.add(m);
		}
		List<TempoMidNote> tm = tempoMidNotes(score, map, trackNo, instrumentType);
		if (!tm.isEmpty()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", TEMPO_MID_NOTE);
			m.put("track", trackNo);
			m.put("count", tm.size());
			m.put("items", List.copyOf(tm.subList(0, Math.min(MAX_TEMPO_ITEMS, tm.size()))));
			TempoMidNote f = tm.get(0);
			m.put("message", "tempo_mid_note: 템포 변경이 트랙 " + trackNo + " 음의 울림 안에 " + tm.size() + "곳(처음 " + f.pos()
					+ " t" + f.bpm() + ", " + f.part() + " " + f.note().pos() + " " + f.note().name()
					+ "). 템포 위치를 음 시작으로 옮기거나 음을 나눌 것");
			w.add(m);
		}
		return w;
	}

	/** score_stats의 악보 전체 warnings(tempo_end_differs). 없으면 빈 목록. */
	public static List<Map<String, Object>> scoreWarnings(MMLScore score, MeasureMap map) {
		List<Map<String, Object>> w = new ArrayList<>();
		TempoEnd te = tempoEnd(score, map);
		if (te != null && te.differs()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", TEMPO_END_DIFFERS);
			m.put("first_bpm", te.firstBpm());
			m.put("last_bpm", te.lastBpm());
			m.put("last_tick", te.lastTick());
			m.put("last_pos", te.lastPos());
			m.put("message", "tempo_end_differs: 마지막 템포 t" + te.lastBpm() + "(" + te.lastPos() + ")가 처음 템포 t"
					+ te.firstBpm() + "와 다름. 장을 이어 연주하면 다음 장 시작 템포를 확인할 것");
			w.add(m);
		}
		return w;
	}
}
