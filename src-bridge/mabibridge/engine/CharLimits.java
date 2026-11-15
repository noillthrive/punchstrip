package mabibridge.engine;

import static mabibridge.engine.EngineException.BAD_ARGUMENT;
import static mabibridge.engine.EngineException.details;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.core.MMLText;
import jp.fourthline.mmlTools.core.ResourceLoader;

/**
 * 작곡 랭크별 글자 수 한도. 랭크 표는 mmlTools {@code ComposeRank}와 같은 자료({@code rank} 리소스 묶음,
 * {@code ResourceLoader})를 같은 방법으로 읽는다({@code ComposeRank}의 표는 밖에서 볼 수 없으므로).
 * 이름({@link Rank#name()})은 {@code ComposeRank.getRank()}가 돌려주는 글자 그대로("1"…"9", "A"…"F", 연습 랭크는
 * 지역 설정에 따라 "Practice"/"연습")이고, 키({@link Rank#key()})는 리소스 키("RANK_1" 등)다.
 *
 * <p>판정 글자 수는 {@link Project#statsOf}의 두 기준과 같다: 멜로디가 비고 화음이 있으면 엔진이 넣는 빈 멜로디 문자열 길이,
 * 화음·노래는 생성된 파트 글자 그대로. 노래 제외 기준의 한도는 {@code ComposeRank}처럼 각 파트 + 멜로디/3이고 노래 파트는 실을 수 없다.
 * 노래 포함 기준은 랭크 표 그대로, 노래 파트 한도는 멜로디 한도와 같다({@code ComposeRank.compare}).</p>
 */
public final class CharLimits {

	/** 기본 랭크(지금까지의 score_stats와 같은 RANK_1). */
	public static final String DEFAULT_RANK = "1";

	/** 랭크 하나. melody·chord1·chord2는 노래 포함 기준(표 그대로)의 한도. */
	public record Rank(String key, String name, int melody, int chord1, int chord2) {}

	/** 파트 하나의 판정. limit이 null이면 이 기준에서 세지 않는 파트(노래 제외 기준의 노래). */
	public record PartLimit(int chars, Integer limit, int overBy) {}

	/**
	 * 트랙 하나의 판정.
	 *
	 * @param rank           랭크 이름
	 * @param rankKey        리소스 키
	 * @param songRule       "song_excluded" 또는 "song_included"
	 * @param parts          파트별 판정(melody, chord1, chord2, song)
	 * @param over           한도를 넘은 파트
	 * @param fits           over가 비었는지
	 * @param songNotAllowed 노래 제외 기준인데 노래 파트가 비어 있지 않음(글자 수 초과와 다른 판정)
	 */
	public record Evaluation(String rank, String rankKey, String songRule, Map<String, PartLimit> parts,
			List<String> over, boolean fits, boolean songNotAllowed) {

		/** 넘은 파트 → 넘은 글자 수(넘은 파트만, 파트 순서). */
		public Map<String, Integer> overByParts() {
			Map<String, Integer> m = new LinkedHashMap<>();
			for (Map.Entry<String, PartLimit> e : parts.entrySet()) {
				if (e.getValue().overBy() > 0) {
					m.put(e.getKey(), e.getValue().overBy());
				}
			}
			return m;
		}

		/** 넘은 글자 수의 합(넘지 않으면 0). */
		public int overTotal() {
			int t = 0;
			for (PartLimit p : parts.values()) {
				t += p.overBy();
			}
			return t;
		}

		/** "한도 초과: melody 579자, chord1 336자"(넘지 않으면 "한도 안"). */
		public String overText() {
			if (fits) {
				return "한도 안";
			}
			StringBuilder sb = new StringBuilder("한도 초과: ");
			int i = 0;
			for (Map.Entry<String, Integer> e : overByParts().entrySet()) {
				if (i++ > 0) {
					sb.append(", ");
				}
				sb.append(e.getKey()).append(' ').append(e.getValue()).append('자');
			}
			return sb.toString();
		}

		/** 가장 많이 넘은 글자 수(넘지 않으면 0). */
		public int maxOverBy() {
			int m = 0;
			for (PartLimit p : parts.values()) {
				m = Math.max(m, p.overBy());
			}
			return m;
		}
	}

	private static List<Rank> ranks;

	private CharLimits() {}

	/** 랭크 목록(ComposeRank처럼 세 한도의 합이 작은 순 = 낮은 랭크부터). */
	public static synchronized List<Rank> ranks() {
		if (ranks == null) {
			ResourceBundle b = ResourceBundle.getBundle("rank", new ResourceLoader());
			List<Rank> l = new ArrayList<>();
			for (String key : Collections.list(b.getKeys())) {
				String[] s = b.getString(key).split(",");
				if (s.length != 4) {
					continue;
				}
				l.add(new Rank(key, s[3].trim(), Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim()),
						Integer.parseInt(s[2].trim())));
			}
			l.sort(Comparator.comparingInt((Rank r) -> r.melody() + r.chord1() + r.chord2()));
			ranks = List.copyOf(l);
		}
		return ranks;
	}

	/** 랭크 이름들(낮은 랭크부터). */
	public static List<String> rankNames() {
		List<String> l = new ArrayList<>();
		for (Rank r : ranks()) {
			l.add(r.name());
		}
		return l;
	}

	/**
	 * 이름(ComposeRank의 글자, 예 "1", "A", "Practice"), 리소스 키("RANK_1") 또는 키 뒤쪽("t")으로 찾는다. 대소문자 무시.
	 * null이나 빈 문자열이면 기본 랭크. 없으면 BAD_ARGUMENT.
	 */
	public static Rank rank(String nameOrKey) {
		String q = (nameOrKey == null || nameOrKey.isBlank()) ? DEFAULT_RANK : nameOrKey.trim();
		for (Rank r : ranks()) {
			String k = r.key();
			String suffix = k.startsWith("RANK_") ? k.substring(5) : k;
			if (r.name().equalsIgnoreCase(q) || k.equalsIgnoreCase(q) || suffix.equalsIgnoreCase(q)
					|| ("t".equals(suffix) && "practice".equalsIgnoreCase(q))) {
				return r;
			}
		}
		throw new EngineException(BAD_ARGUMENT, "모르는 랭크: " + nameOrKey + " (쓸 수 있는 랭크: " + rankNames() + ")",
				details("rank", nameOrKey, "ranks", rankNames()));
	}

	/** 판정 글자 수 {멜로디(노래 제외 기준), 멜로디(노래 포함 기준), 화음1, 화음2, 노래}. 트랙은 생성된 상태여야 한다. */
	static int[] judgedChars(MMLTrack t) {
		t.mmlRank(); // 노래 제외 설정을 MML 텍스트에 반영(Project.statsOf와 같음)
		String[] raw = t.getMabiMMLArray();
		String empty = MMLText.getMelodyEmptyStr();
		boolean chords = !raw[1].isEmpty() || !raw[2].isEmpty();
		int melIncl = (raw[0].isEmpty() && chords && raw[3].isEmpty()) ? empty.length() : raw[0].length();
		int melExcl = (raw[0].isEmpty() && chords) ? empty.length() : raw[0].length();
		return new int[] { melExcl, melIncl, raw[1].length(), raw[2].length(), raw[3].length() };
	}

	/** 생성된 트랙을 랭크·기준으로 판정한다. */
	public static Evaluation evaluate(MMLTrack t, Rank r, String songRule) {
		return evaluate(judgedChars(t), r, songRule);
	}

	static Evaluation evaluate(int[] c, Rank r, String songRule) {
		boolean included = Project.SONG_INCLUDED.equals(songRule);
		Map<String, PartLimit> parts = new LinkedHashMap<>();
		List<String> over = new ArrayList<>();
		int d = r.melody() / 3;
		int mel = included ? c[1] : c[0];
		int[] chars = { mel, c[2], c[3], c[4] };
		Integer[] lim = included ? new Integer[] { r.melody(), r.chord1(), r.chord2(), r.melody() }
				: new Integer[] { r.melody() + d, r.chord1() + d, r.chord2() + d, null };
		for (Part p : Part.values()) {
			int i = p.index();
			int ob = (lim[i] == null) ? 0 : Math.max(0, chars[i] - lim[i]);
			parts.put(p.key(), new PartLimit(chars[i], lim[i], ob));
			if (ob > 0) {
				over.add(p.key());
			}
		}
		boolean songNotAllowed = !included && c[4] > 0;
		return new Evaluation(r.name(), r.key(), included ? Project.SONG_INCLUDED : Project.SONG_EXCLUDED,
				Collections.unmodifiableMap(parts), List.copyOf(over), over.isEmpty(), songNotAllowed);
	}

	/** song_rule 확인(null이면 null). */
	static String checkRule(String rule) {
		if (rule == null) {
			return null;
		}
		String r = rule.trim().toLowerCase(Locale.ROOT);
		if (Project.SONG_EXCLUDED.equals(r) || Project.SONG_INCLUDED.equals(r)) {
			return r;
		}
		throw new EngineException(BAD_ARGUMENT, "song_rule은 song_excluded 또는 song_included", details("song_rule", rule));
	}
}
