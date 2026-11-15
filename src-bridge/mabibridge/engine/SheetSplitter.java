package mabibridge.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.fourthline.mmlTools.core.MMLException;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.TimeSignature;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_split_sheets}: 한 트랙을 글자 수 한도에 맞는 여러 장(악보)으로 나눈다. 열린 악보는 바꾸지 않는다.
 *
 * <p>장마다 그 마디 범위만 담은 트랙 하나짜리 사본을 0틱부터 다시 만들고(템포는 자른 자리에서 쓰던 템포를 0틱에 두고, 범위 안의
 * 템포 변경은 그만큼 당긴다. 박자도 같은 방법), MabiIcco 생성기로 MML을 만든다. 그래서 {@code t}·{@code v}·{@code o}·{@code l}은
 * 생성기가 장 처음에 다시 적는다.</p>
 *
 * <p>나누는 법(앞에서부터 욕심껏): 장의 첫 마디에서 마디를 하나씩 늘려 모든 파트가 한도 안인 가장 긴 범위를 찾는다. 그 끝에서 뒤로
 * 범위 길이의 절반까지 거슬러 가며 <b>어떤 파트의 음도 걸치지 않는</b> 마디 경계를 찾고, 있으면 거기서 자른다. 없으면 가장 긴 범위
 * 끝에서 자르고 걸친 음을 {@code cut_notes}로 알린다. 걸친 음은 앞 장에서 경계까지로 줄이고, {@code carry}면 남은 길이를 다음 장
 * 0틱에서 다시 친다.</p>
 */
final class SheetSplitter {

	/** 자른 자리에 걸친 음. keptLen = 앞 장에 남은 길이, carriedLen = 다음 장 처음에 다시 친 길이(carry가 아니면 0). */
	public record CutNote(String part, int tick, String pos, int pitch, String name, int len, int keptLen, int carriedLen) {}

	/**
	 * 장 하나. chars는 실제 MML 파트 글자 수, check는 랭크 판정, startBpm은 장 처음 템포, crossingCut은 끝 경계에 걸친 음이 있었는지.
	 */
	public record Sheet(int index, int fromMeasure, int toMeasure, long fromTick, long toTick, int startBpm, String mml,
			Map<String, Integer> chars, CharLimits.Evaluation check, boolean fits, boolean crossingCut,
			List<CutNote> cutNotes, String path) {

		Sheet withPath(String p) {
			return new Sheet(index, fromMeasure, toMeasure, fromTick, toTick, startBpm, mml, chars, check, fits, crossingCut,
					cutNotes, p);
		}
	}

	/** 응답. */
	public record SplitResult(int track, String name, String rank, String songRule, int lastMeasure, boolean carry,
			int sheetCount, boolean complete, Integer remainingFromMeasure, List<Sheet> sheets, Map<String, Integer> totals,
			List<String> warnings, String text) {

		SplitResult withSheets(List<Sheet> s) {
			return new SplitResult(track, name, rank, songRule, lastMeasure, carry, sheetCount, complete, remainingFromMeasure,
					List.copyOf(s), totals, warnings, text);
		}
	}

	private final MMLScore src;
	private final MMLTrack orig;
	private final int track;
	private final MeasureMap map;
	private final CharLimits.Rank rank;
	private final String rule;
	private final boolean carry;
	private final MMLScore work;
	private final MMLTrack wt;
	private final Map<Long, Built> cache = new HashMap<>();

	/** 만들어 본 범위. */
	record Built(int from, int to, CharLimits.Evaluation check, int[] chars, String mml, byte[] state, int startBpm) {}

	SheetSplitter(MMLScore src, int track, MeasureMap map, CharLimits.Rank rank, String rule, boolean carry) {
		this.src = src;
		this.orig = src.getTrack(track - 1);
		this.track = track;
		this.map = map;
		this.rank = rank;
		this.rule = rule;
		this.carry = carry;
		this.work = TrackCopy.singleTrack(src, track - 1);
		this.wt = work.getTrack(0);
	}

	/** 음이 있는 마지막 마디(모든 파트). 음이 없으면 0. */
	int lastMeasure() {
		long end = 0;
		for (Part p : Part.values()) {
			for (MMLNoteEvent e : orig.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
				end = Math.max(end, e.getEndTick());
			}
		}
		return end == 0 ? 0 : map.measureOf(end - 1);
	}

	/** 경계 틱 b에 걸친 음(시작 &lt; b &lt; 끝). */
	List<CutNote> crossing(long b) {
		List<CutNote> l = new ArrayList<>();
		for (Part p : Part.values()) {
			for (MMLNoteEvent e : orig.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
				if (e.getTickOffset() < b && e.getEndTick() > b) {
					int kept = (int) (b - e.getTickOffset());
					l.add(new CutNote(p.key(), e.getTickOffset(), map.position(e.getTickOffset()).toString(), e.getNote() + 12,
							NoteSpec.pitchName(e.getNote() + 12), e.getTick(), kept, carry ? e.getTick() - kept : 0));
				}
			}
		}
		return l;
	}

	/** [from 마디 시작, to 마디 끝)을 0틱부터 담은 사본을 만들고 생성·판정한다. */
	Built build(int from, int to) {
		long key = ((long) from << 32) | to;
		Built b = cache.get(key);
		if (b != null) {
			return b;
		}
		long a = map.measureStart(from);
		long z = map.measureEnd(to);
		// 템포: 자른 자리에서 쓰던 템포를 0틱에, 범위 안 변경은 당겨서
		List<MMLTempoEvent> srcTempo = src.getTempoEventList();
		int startBpm = MMLTempoEvent.searchOnTick(srcTempo, a);
		List<MMLTempoEvent> tl = work.getTempoEventList();
		tl.clear();
		if (startBpm != MMLTempoEvent.INITIAL_TEMPO) {
			// 게임의 장은 늘 t120으로 시작하므로 120이면 적지 않는다
			tl.add(new MMLTempoEvent(startBpm, 0));
		}
		for (MMLTempoEvent te : srcTempo) {
			if (te.getTickOffset() > a && te.getTickOffset() < z) {
				tl.add(new MMLTempoEvent(te.getTempo(), (int) (te.getTickOffset() - a)));
			}
		}
		// 박자: 자른 자리의 박자를 기본으로, 범위 안 변경은 당겨서
		setSignatures(a, z);
		// 음: 범위 안에서 울리는 음을 잘라 담는다(앞 장에서 걸쳐 온 음은 carry일 때만)
		for (Part p : Part.values()) {
			List<MMLNoteEvent> dst = wt.getMMLEventAtIndex(p.index()).getMMLNoteEventList();
			dst.clear();
			for (MMLNoteEvent e : orig.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
				if (e.getTickOffset() >= z) {
					break;
				}
				if (e.getEndTick() <= a || (e.getTickOffset() < a && !carry)) {
					continue;
				}
				long s = Math.max(e.getTickOffset(), a);
				long t = Math.min(e.getEndTick(), z);
				MMLNoteEvent c = e.clone();
				c.setTickOffset((int) (s - a));
				c.setTick((int) (t - s));
				dst.add(c);
			}
		}
		TrackCopy.generate(work, "score_split_sheets " + from + "~" + to + "마디");
		CharLimits.Evaluation ev = CharLimits.evaluate(wt, rank, rule);
		b = new Built(from, to, ev, TrackCopy.mmlChars(wt), wt.getMabiMML(), work.getObjectState(), startBpm);
		cache.put(key, b);
		return b;
	}

	private void setSignatures(long a, long z) {
		List<MeasureMap.Signature> sigs = map.signatures();
		MeasureMap.Signature at = sigs.get(0);
		for (MeasureMap.Signature s : sigs) {
			if (s.tick() <= a) {
				at = s;
			}
		}
		work.getTimeSignatureList().clear();
		work.setBaseTime(at.numerator() + "/" + at.denominator());
		for (MeasureMap.Signature s : sigs) {
			if (s.tick() > a && s.tick() < z) {
				try {
					work.addTimeSignature(new TimeSignature(work, (int) (s.tick() - a), s.numerator(), s.denominator()));
				} catch (MMLException e) {
					throw new EngineException(EngineException.MML_GENERATE_FAILED, "장 사본의 박자를 만들 수 없음: " + e, Map.of(), e);
				}
			}
		}
	}

	SplitResult split(int maxSheets) {
		int last = lastMeasure();
		if (last == 0) {
			throw new EngineException(EngineException.BAD_ARGUMENT, "트랙 " + track + "에 음표가 없어 나눌 것이 없음",
					EngineException.details("track", track));
		}
		List<Sheet> sheets = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int a = 1;
		Integer remaining = null;
		while (a <= last) {
			if (sheets.size() >= maxSheets) {
				remaining = a;
				break;
			}
			Built first = build(a, a);
			int best = a;
			if (first.check().fits()) {
				while (best + 1 <= last && build(a, best + 1).check().fits()) {
					best++;
				}
			} else {
				warnings.add("장 " + (sheets.size() + 1) + ": " + a + "마디 하나만으로도 한도를 넘음(" + first.check().over() + ")");
			}
			int cut = best;
			if (best < last) {
				int floor = Math.max(a, best - (best - a) / 2);
				for (int c = best; c >= floor; c--) {
					if (crossing(map.measureEnd(c)).isEmpty() && build(a, c).check().fits()) {
						cut = c;
						break;
					}
				}
			}
			List<CutNote> cutNotes = (cut < last) ? crossing(map.measureEnd(cut)) : List.of();
			Built b = build(a, cut);
			Map<String, Integer> chars = new LinkedHashMap<>();
			for (Part p : Part.values()) {
				chars.put(p.key(), b.chars()[p.index()]);
			}
			sheets.add(new Sheet(sheets.size() + 1, a, cut, map.measureStart(a), map.measureEnd(cut), b.startBpm(), b.mml(),
					chars, b.check(), b.check().fits(), !cutNotes.isEmpty(), List.copyOf(cutNotes), null));
			a = cut + 1;
		}
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (Part p : Part.values()) {
			int sum = 0;
			for (Sheet s : sheets) {
				sum += s.chars().get(p.key());
			}
			totals.put(p.key(), sum);
		}
		if (remaining != null) {
			warnings.add("max_sheets " + maxSheets + "장으로 끝까지 나누지 못함: " + remaining + "~" + last + "마디가 남음");
		}
		StringBuilder text = new StringBuilder();
		text.append("트랙 ").append(track).append(" 1~").append(last).append("마디를 랭크 ").append(rank.name()).append(' ')
				.append(rule).append(" 기준 ").append(sheets.size()).append("장으로: ");
		for (int i = 0; i < sheets.size(); i++) {
			Sheet s = sheets.get(i);
			if (i > 0) {
				text.append(", ");
			}
			text.append(s.index()).append("장 ").append(s.fromMeasure()).append('~').append(s.toMeasure()).append("마디");
			if (s.crossingCut()) {
				text.append("(걸친 음 ").append(s.cutNotes().size()).append("개)");
			}
			if (!s.fits()) {
				text.append("(한도 넘음)");
			}
		}
		text.append(remaining == null ? "." : ". 끝까지 나누지 못함(" + remaining + "마디부터 남음).");
		return new SplitResult(track, orig.getTrackName(), rank.name(), rule, last, carry, sheets.size(), remaining == null,
				remaining, sheets, totals, warnings, text.toString());
	}

	/** 장 i(1부터)의 사본 상태 바이트(.mmi 내용). */
	byte[] stateOf(Sheet s) {
		return build(s.fromMeasure(), s.toMeasure()).state();
	}
}
