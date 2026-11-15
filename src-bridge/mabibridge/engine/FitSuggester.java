package mabibridge.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;
import mabibridge.midi.MeasureMap;

/**
 * {@code score_fit_suggest}: 글자 수 한도를 넘은 파트마다 {@link TransformOp} 후보 사다리를 <b>사본</b>에 하나씩 적용해
 * MML을 다시 만들어 보고(열린 악보는 바꾸지 않음), 사다리를 차례로 시험해 글자를 줄이는 단계만 누적한 계획을 만든다.
 * 수치는 {@code score_transform}과 같은 {@link TransformOp#apply}와 같은 생성기로 낸다.
 */
final class FitSuggester {

	/** 후보 사다리(가벼운 것부터). velocity_min_change의 delta 2 = ±1 세기 변화를 없앰(delta 1은 아무것도 바꾸지 않음). */
	static final List<TransformOp> LADDER = List.of(
			TransformOp.of(TransformOp.VELOCITY_MIN_CHANGE, Map.of("delta", 2)),
			TransformOp.of(TransformOp.VELOCITY_QUANTIZE, Map.of("step", 2)),
			TransformOp.of(TransformOp.LENGTH_GRID, Map.of("ticks", 12)),
			TransformOp.of(TransformOp.MIN_NOTE_LEN, Map.of("ticks", 12, "mode", "delete")),
			TransformOp.of(TransformOp.VELOCITY_QUANTIZE, Map.of("step", 3)),
			TransformOp.of(TransformOp.DROP_BELOW_VEL, Map.of("v", 2)),
			TransformOp.of(TransformOp.LENGTH_GRID, Map.of("ticks", 24)));

	/** 후보 하나(한 파트에만 적용). fitsAlone = 이 후보만으로 그 파트가 한도 안에 들어감. */
	public record Candidate(String op, Map<String, Object> params, int charsBefore, int charsAfter, int saved,
			int notesAffected, boolean fitsAlone, List<String> examples) {}

	/** 넘은 파트 하나. */
	public record PartFit(String part, int chars, int limit, int overBy, List<Candidate> candidates) {}

	/**
	 * 계획에 남긴 단계(누적). chars는 그 단계까지 적용한 뒤의 판정 글자 수, overPartsChars는 처음에 넘었던 파트들의
	 * 글자 수 합(단계를 남길지 정하는 값), overByParts는 그때 한도를 넘은 파트 → 넘은 글자 수, overTotal은 그 합.
	 */
	public record PlanStep(String op, Map<String, Object> params, List<String> parts, int notesAffected,
			Map<String, Integer> chars, int overPartsChars, boolean fits, Map<String, Integer> overByParts, int overTotal) {}

	/** 시험해 보고 버린 단계: 바뀐 음이 없거나 넘은 파트들의 글자 수 합이 줄지 않음(before → after). */
	public record Skipped(String op, Map<String, Object> params, int notesAffected, int overPartsCharsBefore,
			int overPartsCharsAfter, String reason) {}

	/**
	 * 계획. 사다리를 차례로 시험해 글자를 줄이는 단계만 누적한 것(steps)과 버린 단계(skipped). fits면 steps를 다 적용하면 한도 안,
	 * 아니면 남은 초과: overByParts(파트 → 넘은 글자 수), overTotal(합), remainingOverBy(= overTotal, 예전 이름).
	 * calls는 steps를 그대로 보낼 score_transform 인자들.
	 */
	public record Plan(boolean fits, List<PlanStep> steps, List<Skipped> skipped, Map<String, Integer> chars,
			Map<String, Integer> overByParts, int overTotal, int remainingOverBy, List<Map<String, Object>> calls,
			String note) {}

	/** 응답. */
	public record FitSuggestion(int track, String name, String rank, String songRule, CharLimits.Evaluation before,
			List<String> over, List<PartFit> parts, Plan plan, List<Map<String, Object>> ladder, String text) {}

	private FitSuggester() {}

	static FitSuggestion suggest(MMLScore score, int track, CharLimits.Rank rank, String rule, List<Part> only,
			MeasureMap map) {
		MMLTrack orig = score.getTrack(track - 1);
		CharLimits.Evaluation before = CharLimits.evaluate(orig, rank, rule);
		List<Part> over = new ArrayList<>();
		for (Part p : Part.values()) {
			if (before.over().contains(p.key()) && (only == null || only.contains(p))) {
				over.add(p);
			}
		}
		List<String> overKeys = keys(over);
		List<Map<String, Object>> ladder = new ArrayList<>();
		for (TransformOp op : LADDER) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("op", op.op());
			m.put("params", op.params());
			ladder.add(m);
		}
		if (over.isEmpty()) {
			String text = before.fits() ? "트랙 " + track + "은(는) 랭크 " + rank.name() + " " + rule + " 기준 한도 안이라 줄일 필요가 없음"
					: "고른 파트(" + keys(only) + ")는 한도 안. 넘은 파트: " + before.over();
			Plan plan = new Plan(before.fits(), List.of(), List.of(), judged(before), before.overByParts(), before.overTotal(),
					before.overTotal(), List.of(), text);
			return new FitSuggestion(track, orig.getTrackName(), rank.name(), before.songRule(), before, overKeys, List.of(),
					plan, ladder, text);
		}

		MMLScore work = TrackCopy.singleTrack(score, track - 1);
		MMLTrack wt = work.getTrack(0);
		List<List<MMLNoteEvent>> base = TrackCopy.copyNotes(orig);

		List<PartFit> parts = new ArrayList<>();
		for (Part p : over) {
			CharLimits.PartLimit pl = before.parts().get(p.key());
			List<Candidate> cands = new ArrayList<>();
			for (TransformOp op : LADDER) {
				TrackCopy.putNotes(wt, base);
				List<TransformOp.Change> ch = op.apply(p.key(), wt.getMMLEventAtIndex(p.index()).getMMLNoteEventList(), 0,
						Long.MAX_VALUE, map);
				TrackCopy.generate(work, "score_fit_suggest");
				CharLimits.Evaluation ev = CharLimits.evaluate(wt, rank, rule);
				CharLimits.PartLimit after = ev.parts().get(p.key());
				cands.add(new Candidate(op.op(), op.params(), pl.chars(), after.chars(), pl.chars() - after.chars(),
						ch.size(), after.overBy() == 0, examples(ch, 3)));
			}
			parts.add(new PartFit(p.key(), pl.chars(), pl.limit(), pl.overBy(), cands));
		}

		// 계획(욕심껏): 사다리 단계를 넘은 파트 모두에 시험 적용해, 넘은 파트들의 글자 수 합이 줄 때만 남기고
		// 아니면(그대로이거나 늘면) 그 단계를 버리고 다음 단계로. 트랙 전체가 한도 안에 들어가면 멈춘다.
		List<List<MMLNoteEvent>> cur = base;
		List<PlanStep> steps = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();
		CharLimits.Evaluation ev = before;
		int total = overTotal(before, over);
		for (TransformOp op : LADDER) {
			TrackCopy.putNotes(wt, cur);
			int n = 0;
			for (Part p : over) {
				n += op.apply(p.key(), wt.getMMLEventAtIndex(p.index()).getMMLNoteEventList(), 0, Long.MAX_VALUE, map).size();
			}
			TrackCopy.generate(work, "score_fit_suggest");
			CharLimits.Evaluation tryEv = CharLimits.evaluate(wt, rank, rule);
			int tryTotal = overTotal(tryEv, over);
			if (n == 0 || tryTotal >= total) {
				skipped.add(new Skipped(op.op(), op.params(), n, total, tryTotal,
						n == 0 ? "바뀐 음 없음" : (tryTotal == total ? "글자 수가 줄지 않음" : "글자 수가 늚")));
				continue;
			}
			cur = TrackCopy.copyNotes(wt);
			ev = tryEv;
			total = tryTotal;
			steps.add(new PlanStep(op.op(), op.params(), overKeys, n, judged(ev), total, ev.fits(), ev.overByParts(),
					ev.overTotal()));
			if (ev.fits()) {
				break;
			}
		}
		List<Map<String, Object>> calls = new ArrayList<>();
		for (PlanStep st : steps) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("track", track);
			c.put("parts", st.parts());
			c.put("op", st.op());
			c.put("params", st.params());
			calls.add(c);
		}
		String note;
		if (ev.fits()) {
			note = "사다리에서 글자를 줄이는 " + steps.size() + "단계를 " + overKeys + "에 차례로 적용하면 랭크 " + rank.name() + " "
					+ before.songRule() + " 한도 안(" + charsText(ev, over) + ")";
		} else {
			note = (steps.isEmpty() ? "사다리에서 글자를 줄이는 단계가 없어 그대로 "
					: "사다리에서 글자를 줄이는 " + steps.size() + "단계를 모두 적용해도 ") + ev.overText() + "(합 " + ev.overTotal() + "자, "
					+ charsText(ev, over) + "). score_split_sheets로 장을 나누거나 음을 줄일 것";
		}
		Plan plan = new Plan(ev.fits(), steps, skipped, judged(ev), ev.overByParts(), ev.overTotal(), ev.overTotal(), calls,
				note);
		StringBuilder text = new StringBuilder();
		text.append("트랙 ").append(track).append(" 랭크 ").append(rank.name()).append(' ').append(before.songRule()).append(": ")
				.append(before.overText()).append("(합 ").append(before.overTotal()).append("자; ");
		for (int i = 0; i < parts.size(); i++) {
			PartFit pf = parts.get(i);
			if (i > 0) {
				text.append(", ");
			}
			text.append(pf.part()).append(' ').append(pf.chars()).append('/').append(pf.limit());
		}
		text.append("). ").append(note);
		return new FitSuggestion(track, orig.getTrackName(), rank.name(), before.songRule(), before, overKeys, parts, plan,
				ladder, text.toString());
	}

	/** 넘은 파트들의 판정 글자 수 합. */
	private static int overTotal(CharLimits.Evaluation ev, List<Part> over) {
		int t = 0;
		for (Part p : over) {
			t += ev.parts().get(p.key()).chars();
		}
		return t;
	}

	private static Map<String, Integer> judged(CharLimits.Evaluation ev) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Map.Entry<String, CharLimits.PartLimit> e : ev.parts().entrySet()) {
			m.put(e.getKey(), e.getValue().chars());
		}
		return m;
	}

	private static String charsText(CharLimits.Evaluation ev, List<Part> parts) {
		StringBuilder sb = new StringBuilder();
		for (Part p : parts) {
			CharLimits.PartLimit pl = ev.parts().get(p.key());
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(p.key()).append(' ').append(pl.chars()).append('/').append(pl.limit());
		}
		return sb.toString();
	}

	static List<String> examples(List<TransformOp.Change> ch, int n) {
		List<String> l = new ArrayList<>();
		for (int i = 0; i < Math.min(n, ch.size()); i++) {
			TransformOp.Change c = ch.get(i);
			String what = switch (c.change()) {
			case "velocity" -> "v" + c.from() + "→v" + c.to();
			case "length" -> c.from() + "→" + c.to() + "틱";
			default -> "지움";
			};
			l.add(c.pos() + " " + c.name() + " " + what);
		}
		return l;
	}

	private static List<String> keys(List<Part> ps) {
		List<String> l = new ArrayList<>();
		if (ps != null) {
			for (Part p : ps) {
				l.add(p.key());
			}
		}
		return l;
	}
}
