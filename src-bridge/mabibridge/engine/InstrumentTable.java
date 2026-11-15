package mabibridge.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jp.fourthline.mabiicco.midi.InstClass;
import jp.fourthline.mabiicco.midi.MabiDLS;
import jp.fourthline.mmlTools.parser.MMLEventParser;

/**
 * 악기표. {@code resources/instrument.properties}(영어)와 {@code instrument_ko.properties}(한국어)를
 * 클래스패스에서 직접 읽어, DLS 없이도 악기 이름·타입·허용 파트·properties 음역을 알 수 있게 한다.
 *
 * <p>MabiIcco는 {@code ResourceBundle}로 악기표를 읽으므로 한국어 로케일에서는 같은 번호에 대해
 * 한국어 파일의 줄 전체(이름·타입·음역)가 이긴다. 이 표도 같은 규칙을 따른다(한국어 줄 우선,
 * 영어 이름은 따로 보관).</p>
 *
 * <p>음역은 모두 <b>MIDI 번호</b>(60 = C4)다. 엔진 내부 음 번호는 MIDI − 12.</p>
 */
public final class InstrumentTable {

	/** MIDI 번호 음역. 한쪽이 지정되지 않았으면 null. forced는 properties의 {@code !} 표기(DLS 범위를 무시하고 그대로 씀). */
	public record Range(Integer low, Integer high, String lowName, String highName, boolean lowForced, boolean highForced) {}

	/**
	 * 악기 하나.
	 *
	 * @param program      엔진의 논리 악기 번호(뱅크 0이면 그냥 번호, {@code 0.2}처럼 뱅크가 있으면 {@code (bank << 9) + program})
	 * @param programText  properties 키 그대로("29", "0.2")
	 * @param type         타입 문자(N/P/K/V/C/D/M/0). properties에 타입이 없으면 N(엔진과 같은 규칙)
	 * @param typeExplicit properties에 타입이 적혀 있었는지
	 * @param allowedParts 이 타입이 쓰는 파트(melody/chord1/chord2/song)
	 * @param propRange    properties에 적힌 음역(없으면 null)
	 * @param range        현재 쓰는 음역(DLS를 읽기 전에는 propRange, 읽은 뒤에는 DLS 기준)
	 * @param rangeSource  "properties" 또는 "dls"
	 * @param inDls        DLS에 이 악기가 있는지(DLS를 읽기 전에는 null)
	 */
	public record Instrument(int program, String programText, int bank, String nameKo, String nameEn,
			String type, boolean typeExplicit, List<String> allowedParts, Range propRange, Range range,
			String rangeSource, Boolean inDls) {

		/** 한국어 이름이 있으면 그것, 없으면 영어 이름. */
		public String name() {
			return nameKo != null ? nameKo : nameEn;
		}

		/** 엔진 {@code InstType.allowTempoChordPart()}와 같은 판정(N, D만 true). */
		public boolean allowTempoChordPart() {
			return "N".equals(type) || "D".equals(type);
		}

		public boolean allowsPart(Part p) {
			return allowedParts.contains(p.key());
		}
	}

	private final Map<Integer, Instrument> byProgram;
	private final List<String> warnings;

	private InstrumentTable(Map<Integer, Instrument> byProgram, List<String> warnings) {
		this.byProgram = byProgram;
		this.warnings = warnings;
	}

	/** 클래스패스의 {@code /resources/instrument.properties}와 {@code instrument_ko.properties}를 읽는다. */
	public static InstrumentTable load() {
		Properties en = readProps("/resources/instrument.properties", true);
		Properties ko = readProps("/resources/instrument_ko.properties", false);
		List<String> warnings = new ArrayList<>();
		TreeMap<Integer, Instrument> map = new TreeMap<>();
		List<String> keys = new ArrayList<>();
		for (String k : en.stringPropertyNames()) {
			keys.add(k);
		}
		if (ko != null) {
			for (String k : ko.stringPropertyNames()) {
				if (!keys.contains(k)) {
					keys.add(k);
				}
			}
		}
		for (String key : keys) {
			int[] pb = parseKey(key);
			if (pb == null) {
				warnings.add("악기표 키를 해석할 수 없어 건너뜀: " + key);
				continue;
			}
			String enLine = en.getProperty(key);
			String koLine = (ko != null) ? ko.getProperty(key) : null;
			String line = (koLine != null) ? koLine : enLine;
			try {
				Instrument inst = parseLine(pb[0], pb[1], key, line, enLine, koLine);
				map.put(inst.program(), inst);
			} catch (RuntimeException e) {
				warnings.add("악기표 줄을 해석할 수 없어 건너뜀: " + key + "=" + line + " (" + e + ")");
			}
		}
		return new InstrumentTable(map, Collections.unmodifiableList(warnings));
	}

	private static Properties readProps(String resource, boolean required) {
		try (InputStream in = InstrumentTable.class.getResourceAsStream(resource)) {
			if (in == null) {
				if (required) {
					throw new IllegalStateException("리소스 없음: " + resource);
				}
				return null;
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) {
				text = text.substring(1);
			}
			Properties p = new Properties();
			p.load(new StringReader(text));
			return p;
		} catch (IOException e) {
			throw new IllegalStateException("리소스를 읽을 수 없음: " + resource, e);
		}
	}

	/** "29" → {29, 0}, "0.2" → {0, 2}. 형식이 틀리면 null. */
	private static int[] parseKey(String key) {
		if (!key.matches("\\d+(\\.\\d+)?")) {
			return null;
		}
		String[] s = key.split("\\.");
		int program = Integer.parseInt(s[0]);
		int bank = (s.length > 1) ? Integer.parseInt(s[1]) : 0;
		return new int[] { program, bank };
	}

	private static Instrument parseLine(int program, int bank, String key, String line, String enLine, String koLine) {
		String[] str = line.split(",");
		String type = "N";
		boolean typeExplicit = false;
		if (str.length > 1) {
			type = str[1].trim();
			typeExplicit = true;
			if (!type.matches("[0NPVCKDM]")) {
				throw new IllegalArgumentException("모르는 악기 타입: " + type);
			}
		}
		Range propRange = null;
		if (str.length > 2) {
			String lowS = str[2].trim();
			String highS = (str.length > 3) ? str[3].trim() : null;
			Integer low = parseRangeNote(lowS);
			Integer high = (highS != null) ? parseRangeNote(highS) : null;
			propRange = new Range(low, high, low == null ? null : NoteSpec.pitchName(low),
					high == null ? null : NoteSpec.pitchName(high), lowS.startsWith("!"),
					highS != null && highS.startsWith("!"));
		}
		String nameEn = (enLine != null) ? enLine.split(",")[0].trim() : null;
		String nameKo = (koLine != null) ? koLine.split(",")[0].trim() : null;
		// InstClass.logicalProgramNum(bank, program)과 같은 계산. InstClass를 부르면 그 정적 초기화가
		// MabiIccoProperties(설정 파일)를 건드리므로 DLS를 읽기 전에는 InstClass를 쓰지 않는다.
		int logical = ((bank & 0x7f) << 9) + program;
		return new Instrument(logical, key, bank, nameKo, nameEn, type, typeExplicit, partsOf(type), propRange,
				propRange, "properties", null);
	}

	/** properties 음역 표기("O5c", "!o0c") → MIDI 번호. 엔진의 {@code MMLEventParser.firstNoteNumber}로 해석한다. */
	private static Integer parseRangeNote(String s) {
		String body = s.startsWith("!") ? s.substring(1) : s;
		int note = MMLEventParser.firstNoteNumber(body);
		return note + 12;
	}

	/**
	 * 세기(v) → MIDI 벨로시티 규칙(MabiIcco {@code InstType.convertVelocityMML2Midi}). 일반·드럼·노래·코러스·모바일 타악기
	 * (N/D/V/C/M)는 v를 15에서 자르고 ×8("x8", v15 = 120), 타악기 P(스네어드럼·큰북·작은북·심벌즈·핸드벨)와 K(실로폰)는
	 * 11에서 자르고 ×11("x11", v11 = 121), 0은 소리 없음("none").
	 */
	public static String velocityRule(String type) {
		if (type == null) {
			return null;
		}
		return switch (type) {
		case "P", "K" -> "x11";
		case "0" -> "none";
		default -> "x8";
		};
	}

	/**
	 * 옮길(transpose) 수 있는 악기 타입인가(MabiIcco {@code InstType.allowTranspose}): N·K(실로폰)·V·C는 true, D(드럼)·
	 * P(타악기)·M(모바일 타악기)·0은 false. null(악기표에 없음)은 true.
	 */
	public static boolean allowsTranspose(String type) {
		return type == null || switch (type) {
		case "D", "P", "M", "0" -> false;
		default -> true;
		};
	}

	/** {@link #velocityRule}의 가장 큰 유효 세기(15, 11, 0). */
	public static Integer velocityMax(String type) {
		String r = velocityRule(type);
		return r == null ? null : switch (r) {
		case "x11" -> 11;
		case "none" -> 0;
		default -> 15;
		};
	}

	static List<String> partsOf(String type) {
		return switch (type) {
		case "N", "D" -> List.of("melody", "chord1", "chord2");
		case "P", "K", "M" -> List.of("melody");
		case "V", "C" -> List.of("song");
		default -> List.of();
		};
	}

	/** 악기 번호로 찾는다. 없으면 null. */
	public Instrument get(int program) {
		return byProgram.get(program);
	}

	public boolean contains(int program) {
		return byProgram.containsKey(program);
	}

	/** 번호 순서 목록. type이 null이 아니면 그 타입만. */
	public List<Instrument> list(String type) {
		List<Instrument> l = new ArrayList<>();
		for (Instrument i : byProgram.values()) {
			if (type == null || type.equalsIgnoreCase(i.type())) {
				l.add(i);
			}
		}
		return l;
	}

	/** 표를 읽을 때 생긴 경고. */
	public List<String> warnings() {
		return warnings;
	}

	/**
	 * DLS를 읽은 뒤 음역을 DLS 기준으로 바꾼다. {@code MabiDLS.getInstance().getAllInst()}에서 논리 번호가
	 * 정확히 같은 악기를 찾는다({@code getInstByProgram}은 뱅크를 떼고 다시 찾기 때문에 모바일 악기가
	 * 일반 악기로 잘못 맞춰질 수 있어 쓰지 않는다). 음역은 {@code InstClass.getLowerNote()/getUpperNote()}(엔진 번호)
	 * + 12. 이 값은 엔진이 이미 DLS 범위와 properties 범위의 교집합으로 계산한 것이다.
	 *
	 * @return DLS에서 찾은 악기 수
	 */
	public synchronized int refineWithDls() {
		List<InstClass> all = MabiDLS.getInstance().getAllInst();
		Map<Integer, InstClass> dls = new LinkedHashMap<>();
		for (InstClass c : all) {
			dls.putIfAbsent(c.getProgram(), c);
		}
		int found = 0;
		for (Map.Entry<Integer, Instrument> e : byProgram.entrySet()) {
			Instrument i = e.getValue();
			InstClass c = dls.get(i.program());
			if (c != null) {
				found++;
				int low = c.getLowerNote() + 12;
				int high = c.getUpperNote() + 12;
				Range r = new Range(low, high, NoteSpec.pitchName(low), NoteSpec.pitchName(high), false, false);
				e.setValue(new Instrument(i.program(), i.programText(), i.bank(), i.nameKo(), i.nameEn(), i.type(),
						i.typeExplicit(), i.allowedParts(), i.propRange(), r, "dls", true));
			} else {
				e.setValue(new Instrument(i.program(), i.programText(), i.bank(), i.nameKo(), i.nameEn(), i.type(),
						i.typeExplicit(), i.allowedParts(), i.propRange(), i.range(), i.rangeSource(), false));
			}
		}
		return found;
	}
}
