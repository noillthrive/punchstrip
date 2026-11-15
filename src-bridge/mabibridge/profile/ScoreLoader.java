package mabibridge.profile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jp.fourthline.mmlTools.MMLExceptionList;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLScoreSerializer;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.MMLVerifyException;
import jp.fourthline.mmlTools.parser.MMLFile;
import jp.fourthline.mmlTools.parser.MMLParseException;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.Project;

/**
 * 편곡 프로필용 악보 읽기. {@code .mmi}(MabiIcco)와 3MLE 형식 {@code .mml}을 <b>읽기만</b> 한다. 현재 프로젝트는
 * 건드리지 않는다(새 {@link MMLScore}를 만든다). 보호 폴더 안 파일도 읽을 수 있다(쓰지 않으므로).
 *
 * <p>3MLE {@code .mml}은 원본 파서 {@link MMLFile}로 읽는다. 그 파서는 트랙을 만들 때 음원(DLS)의 악기표를 찾으므로
 * {@link BridgeEngine#ensureDls()}를 먼저 부른다(DLS가 없으면 {@code DLS_UNAVAILABLE}). 원본 파서는
 * {@code [Settings]}를 Shift_JIS로 읽어 한국어 제목이 깨지므로, 제목·작성자는 파일의 {@code Encoding=} 값으로 다시
 * 읽는다.</p>
 */
public final class ScoreLoader {

	public static final String FORMAT_MMI = "mmi";
	public static final String FORMAT_MML = "mml";
	public static final String FORMAT_PROJECT = "project";

	/**
	 * 읽은 악보.
	 *
	 * @param score       엔진 악보(읽기 전용으로 쓸 것)
	 * @param path        파일 절대 경로(현재 프로젝트를 새로 만들었으면 null)
	 * @param format      "mmi", "mml", "project"
	 * @param title       제목(3MLE는 파일 인코딩으로 다시 읽은 값)
	 * @param author      작성자(3MLE의 Source=)
	 * @param encoding    3MLE의 Encoding= 값(그 밖은 null)
	 * @param sourceChars 3MLE 원문의 트랙·파트별 글자 수(트랙마다 {멜로디, 화음1, 화음2, 노래}). 원문을 알 수 없으면 null
	 * @param generated   MabiIcco 규칙으로 MML을 다시 만들었는지(실패하면 false, 글자 수는 파일에 적힌 MML 기준)
	 * @param warnings    읽으며 생긴 경고
	 */
	public record Loaded(MMLScore score, Path path, String format, String title, String author, String encoding,
			List<int[]> sourceChars, boolean generated, List<String> warnings) {}

	private ScoreLoader() {}

	/** 현재 프로젝트(이미 생성된 상태)를 그대로 쓴다. */
	public static Loaded ofProject(Project p) {
		MMLScore s = p.score();
		return new Loaded(s, p.source(), FORMAT_PROJECT, s.getTitle(), s.getAuthor(), null, null, true, List.of());
	}

	/** 이미 있는 악보(시험용). generateAll은 부르지 않는다. */
	public static Loaded ofScore(MMLScore s, String format) {
		return new Loaded(s, null, format, s.getTitle(), s.getAuthor(), null, null, true, List.of());
	}

	/** 확장자가 .mmi 또는 .mml인지(대소문자 무시). */
	public static boolean isSupported(Path p) {
		return formatOf(p) != null;
	}

	static String formatOf(Path p) {
		Path n = p.getFileName();
		if (n == null) {
			return null;
		}
		String s = n.toString().toLowerCase(Locale.ROOT);
		if (s.endsWith(".mmi")) {
			return FORMAT_MMI;
		}
		if (s.endsWith(".mml")) {
			return FORMAT_MML;
		}
		return null;
	}

	/**
	 * 파일을 읽는다.
	 *
	 * @throws EngineException BAD_PATH(확장자), FILE_NOT_FOUND, IO_ERROR, PARSE_FAILED, DLS_UNAVAILABLE(.mml)
	 */
	public static Loaded load(BridgeEngine engine, Path path) {
		Path abs = path.toAbsolutePath().normalize();
		String format = formatOf(abs);
		if (format == null) {
			throw new EngineException(EngineException.BAD_PATH, ".mmi 또는 .mml(3MLE) 파일만 읽을 수 있음",
					ProfileIo.details("path", abs.toString()));
		}
		if (!Files.isRegularFile(abs)) {
			throw new EngineException(EngineException.FILE_NOT_FOUND, "파일이 없음: " + abs,
					ProfileIo.details("path", abs.toString()));
		}
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(abs);
		} catch (IOException e) {
			throw new EngineException(EngineException.IO_ERROR, "파일을 읽을 수 없음: " + e.getMessage(),
					ProfileIo.details("path", abs.toString()), e);
		}
		return FORMAT_MMI.equals(format) ? loadMmi(abs, bytes) : loadMml(engine, abs, bytes);
	}

	private static Loaded loadMmi(Path abs, byte[] bytes) {
		MMLScore s = new MMLScore();
		try (InputStream in = new ByteArrayInputStream(bytes)) {
			new MMLScoreSerializer(s).parse(in);
		} catch (MMLParseException | RuntimeException | IOException e) {
			throw new EngineException(EngineException.PARSE_FAILED, ".mmi를 해석할 수 없음: " + e.getMessage(),
					ProfileIo.details("path", abs.toString()), e);
		}
		List<String> w = new ArrayList<>();
		boolean gen = generate(s, w);
		return new Loaded(s, abs, FORMAT_MMI, s.getTitle(), s.getAuthor(), null, null, gen, List.copyOf(w));
	}

	private static Loaded loadMml(BridgeEngine engine, Path abs, byte[] bytes) {
		engine.ensureDls();
		MMLScore s;
		try (InputStream in = new ByteArrayInputStream(bytes)) {
			s = new MMLFile().parse(in);
		} catch (MMLParseException | RuntimeException | IOException e) {
			throw new EngineException(EngineException.PARSE_FAILED, "3MLE .mml을 해석할 수 없음: " + e.getMessage(),
					ProfileIo.details("path", abs.toString()), e);
		}
		List<String> w = new ArrayList<>();
		// 원문 글자 수: 시작 마커가 없으면 파서가 MML을 다시 만들지 않으므로 지금 값이 원문이다.
		List<int[]> src = null;
		if (s.getMarkerList().isEmpty()) {
			src = new ArrayList<>();
			for (MMLTrack t : s.getTrackList()) {
				String[] a = t.getMabiMMLArray();
				src.add(new int[] { len(a, 0), len(a, 1), len(a, 2), len(a, 3) });
			}
		} else {
			w.add("시작 마커가 있어 원본 파서가 MML을 다시 만들었으므로 3MLE 원문 글자 수(chars_source)는 비움");
		}
		boolean gen = generate(s, w);
		String[] meta = settings(bytes);
		if (meta[2] == null) {
			w.add("[Settings]에 Encoding=이 없어 제목을 CP949로 읽음");
		}
		return new Loaded(s, abs, FORMAT_MML, meta[0] != null ? meta[0] : s.getTitle(),
				meta[1] != null ? meta[1] : s.getAuthor(), meta[2], src, gen, List.copyOf(w));
	}

	private static int len(String[] a, int i) {
		return (a != null && i < a.length && a[i] != null) ? a[i].length() : 0;
	}

	/** MabiIcco 규칙으로 MML을 다시 만든다(GUI의 열기와 같은 생성). 실패하면 경고를 남기고 false. */
	private static boolean generate(MMLScore s, List<String> warnings) {
		try {
			s.generateAll();
			return true;
		} catch (MMLExceptionList | MMLVerifyException | RuntimeException e) {
			warnings.add("MabiIcco 규칙의 MML 다시 만들기 실패(글자 수는 파일에 적힌 MML 기준): " + e);
			return false;
		}
	}

	/**
	 * 3MLE {@code [Settings]}의 Title·Source·Encoding을 파일 인코딩으로 읽는다. 반환 {title, source, encoding}(없으면 null).
	 */
	static String[] settings(byte[] bytes) {
		String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
		String encName = null;
		for (String line : ascii.split("\r?\n|\r")) {
			if (line.startsWith("Encoding=")) {
				encName = line.substring("Encoding=".length()).trim();
				break;
			}
		}
		Charset cs = charset(encName);
		String text = new String(bytes, cs);
		String title = null;
		String source = null;
		boolean inSettings = false;
		for (String line : text.split("\r?\n|\r")) {
			if (line.startsWith("[")) {
				inSettings = line.trim().equals("[Settings]");
				continue;
			}
			if (!inSettings) {
				continue;
			}
			if (line.startsWith("Title=") && title == null) {
				title = line.substring(6).trim();
			} else if (line.startsWith("Source=") && source == null) {
				source = line.substring(7).trim();
			}
		}
		return new String[] { title, source, encName };
	}

	/** Encoding= 값 → Charset. ks_c_5601-1987·euc-kr 계열은 MS949(CP949), 모르면 MS949. */
	static Charset charset(String name) {
		if (name == null || name.isBlank()) {
			return Charset.forName("MS949");
		}
		String n = name.trim().toLowerCase(Locale.ROOT);
		if (n.contains("5601") || n.contains("949") || n.contains("euc-kr") || n.contains("euc_kr") || n.equals("korean")) {
			return Charset.forName("MS949");
		}
		try {
			return Charset.forName(name.trim());
		} catch (RuntimeException e) {
			return Charset.forName("MS949");
		}
	}
}
