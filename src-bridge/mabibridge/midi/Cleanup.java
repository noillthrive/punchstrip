package mabibridge.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 참조 트랙 하나의 음표 정리(reference_cleanup). 96틱 기준 음표 목록 → 정리된 목록 + 단계별 개수.
 *
 * <p>순서: (1) quantize 16|32: 시작·끝을 가장 가까운 칸(24|12틱)으로, 끝이 시작 이하이면 한 칸. (2) fold_to_range
 * [lo, hi]: 밖의 음을 옥타브씩 옮겨 안으로. (3) 같은 음높이: 시작 차가 dedupe_ticks 이하면 중복으로 보고 하나로
 * 합침(끝은 긴 쪽, 세기는 큰 쪽), 겹치면 앞 음 끝을 뒤 음 시작으로 자름. (4) top_line: 시작 묶음마다 가장 높은 음,
 * 더 높은 음이 울리는 동안 시작하는 낮은 음은 버림, 다음 줄 음이 오면 앞 음을 자름(단선율). 또는 max_polyphony N:
 * 시작 틱마다 울리는 음(이어지는 음 + 새 음)이 N개를 넘으면 높은 N개만 — 넘친 새 음은 버리고 이어지던 음은 그 틱에서
 * 자름. (5) min_length_ticks보다 짧은 음 버림.</p>
 */
public final class Cleanup {

	/** 정리 값. quantize·maxPolyphony·foldLo/Hi는 null이면 하지 않음. */
	public record Options(Integer quantize, int dedupeTicks, int minLengthTicks, Integer maxPolyphony, boolean topLine,
			Integer foldLo, Integer foldHi) {}

	/** 결과. */
	public record Outcome(List<ReferenceRewrite.NoteOut> notes, Map<String, Integer> counts) {}

	private static final class N {
		long t;
		long e;
		int p;
		int v;
		final int ch;

		N(long t, long e, int p, int v, int ch) {
			this.t = t;
			this.e = e;
			this.p = p;
			this.v = v;
			this.ch = ch;
		}
	}

	private Cleanup() {}

	public static Outcome run(List<Note> in, Options o) {
		Map<String, Integer> c = new LinkedHashMap<>();
		c.put("notes_before", in.size());
		List<N> ns = new ArrayList<>();
		for (Note n : in) {
			ns.add(new N(n.tick96(), n.tick96() + Math.max(1, n.len96()), n.pitch(), n.velocity(), n.channel()));
		}
		// (1) quantize
		int moved = 0;
		if (o.quantize() != null) {
			long g = o.quantize() == 32 ? 12 : 24;
			for (N n : ns) {
				long t = Math.round((double) n.t / g) * g;
				long e = Math.round((double) n.e / g) * g;
				if (e <= t) {
					e = t + g;
				}
				if (t != n.t || e != n.e) {
					moved++;
				}
				n.t = t;
				n.e = e;
			}
		}
		c.put("quantized_changed", moved);
		// (2) fold
		int folded = 0;
		if (o.foldLo() != null) {
			for (N n : ns) {
				int p = n.p;
				while (p < o.foldLo()) {
					p += 12;
				}
				while (p > o.foldHi()) {
					p -= 12;
				}
				if (p != n.p) {
					folded++;
					n.p = p;
				}
			}
		}
		c.put("folded", folded);
		// (3) duplicates / same-pitch overlaps
		ns.sort(Comparator.<N>comparingInt(n -> n.ch).thenComparingInt(n -> n.p).thenComparingLong(n -> n.t));
		List<N> out = new ArrayList<>();
		int dup = 0;
		int ovl = 0;
		for (N n : ns) {
			N prev = out.isEmpty() ? null : out.get(out.size() - 1);
			if (prev != null && prev.ch == n.ch && prev.p == n.p) {
				if (n.t - prev.t <= o.dedupeTicks()) {
					prev.e = Math.max(prev.e, n.e);
					prev.v = Math.max(prev.v, n.v);
					dup++;
					continue;
				}
				if (prev.e > n.t) {
					prev.e = n.t;
					ovl++;
				}
			}
			out.add(n);
		}
		c.put("duplicates_merged", dup);
		c.put("overlaps_trimmed", ovl);
		out.sort(Comparator.<N>comparingLong(n -> n.t).thenComparing(Comparator.<N>comparingInt(n -> n.p).reversed()));
		// (4) top line or max polyphony
		int dropped = 0;
		int cut = 0;
		if (o.topLine()) {
			List<N> line = new ArrayList<>();
			int i = 0;
			while (i < out.size()) {
				int j = i;
				while (j < out.size() && out.get(j).t == out.get(i).t) {
					j++;
				}
				N top = out.get(i); // highest of the onset group (sorted pitch desc)
				dropped += j - i - 1;
				N last = line.isEmpty() ? null : line.get(line.size() - 1);
				if (last != null && last.e > top.t && last.p > top.p) {
					dropped++;
				} else {
					if (last != null && last.e > top.t) {
						last.e = top.t;
						cut++;
					}
					line.add(top);
				}
				i = j;
			}
			out = line;
			c.put("top_line_dropped", dropped);
			c.put("top_line_truncated", cut);
		} else if (o.maxPolyphony() != null) {
			int max = o.maxPolyphony();
			List<N> kept = new ArrayList<>();
			int i = 0;
			while (i < out.size()) {
				int j = i;
				long t = out.get(i).t;
				while (j < out.size() && out.get(j).t == t) {
					j++;
				}
				List<N> sounding = new ArrayList<>();
				for (N k : kept) {
					if (k.e > t) {
						sounding.add(k);
					}
				}
				List<N> group = new ArrayList<>(out.subList(i, j));
				List<N> all = new ArrayList<>(sounding);
				all.addAll(group);
				all.sort(Comparator.<N>comparingInt(n -> n.p).reversed());
				List<N> keep = all.subList(0, Math.min(max, all.size()));
				for (N k : sounding) {
					if (!keep.contains(k)) {
						k.e = t;
						cut++;
					}
				}
				for (N g : group) {
					if (keep.contains(g)) {
						kept.add(g);
					} else {
						dropped++;
					}
				}
				i = j;
			}
			out = kept;
			c.put("polyphony_dropped", dropped);
			c.put("polyphony_truncated", cut);
		}
		// (5) too short
		int shortN = 0;
		List<ReferenceRewrite.NoteOut> res = new ArrayList<>();
		for (N n : out) {
			long len = n.e - n.t;
			if (len <= 0 || len < o.minLengthTicks()) {
				shortN++;
				continue;
			}
			res.add(new ReferenceRewrite.NoteOut(n.ch, n.p, n.v, n.t, len));
		}
		res.sort(Comparator.comparingLong(ReferenceRewrite.NoteOut::tick)
				.thenComparing(Comparator.comparingInt(ReferenceRewrite.NoteOut::pitch).reversed()));
		c.put("too_short_dropped", shortN);
		c.put("notes_after", res.size());
		return new Outcome(res, c);
	}

	/** 동시에 울리는 음의 최대 수(96틱 음표 목록). */
	public static int maxSimultaneous(List<ReferenceRewrite.NoteOut> notes) {
		List<long[]> ev = new ArrayList<>();
		for (ReferenceRewrite.NoteOut n : notes) {
			ev.add(new long[] { n.tick(), 1 });
			ev.add(new long[] { n.tick() + n.length(), -1 });
		}
		ev.sort(Comparator.<long[]>comparingLong(x -> x[0]).thenComparingLong(x -> x[1]));
		int cur = 0;
		int max = 0;
		for (long[] x : ev) {
			cur += (int) x[1];
			max = Math.max(max, cur);
		}
		return max;
	}
}
