package mabibridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jp.fourthline.mmlTools.TimeSignature;
import mabibridge.engine.BridgeEngine;
import mabibridge.engine.EngineException;
import mabibridge.engine.NoteSpec;
import mabibridge.engine.Project;

/**
 * 렌더 시험 1(피아노 C4 온음표)·시험 2(채널 16 이상 유실 검출)와 메타 JSON 형식.
 * DLS가 없으면 [SKIP]. 출력은 java.io.tmpdir(빌드가 돌린 시험용 tmp 폴더) 아래.
 */
public class RenderTest {

	static final String ENGINE = "MabiIccoBridge test / mmlTools 1.5.12";
	static final double SEC_87_MEASURE = 4 * 60.0 / 87; // 2.7586

	static Path tmp;

	@BeforeClass
	public static void setUp() throws IOException {
		Path base = Path.of(System.getProperty("java.io.tmpdir"));
		Files.createDirectories(base);
		tmp = Files.createTempDirectory(base, "render-");
	}

	static BridgeEngine engine() {
		return new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots")));
	}

	static void assumeDls(String test) {
		boolean ok = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!ok) {
			System.err.println("[SKIP] RenderTest." + test + ": DLS 파일 없음 " + BridgeEngine.defaultDls());
		}
		Assume.assumeTrue("DLS 파일 없음", ok);
	}

	static WavRender.Request req(Integer from, Integer to, List<Integer> tracks, String split, String name) {
		return new WavRender.Request(from, to, tracks, null, split, null, name, tmp, false);
	}

	// ------------------------------------------------------------------ WAV 읽기

	/** WAV → 16bit 스테레오 샘플(인터리브). 형식도 확인한다. */
	static short[] readWav(Path p) throws Exception {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(p.toFile())) {
			AudioFormat f = in.getFormat();
			assertEquals(44100f, f.getSampleRate(), 0.01);
			assertEquals(16, f.getSampleSizeInBits());
			assertEquals(2, f.getChannels());
			assertFalse(f.isBigEndian());
			byte[] b = in.readAllBytes();
			short[] s = new short[b.length / 2];
			for (int i = 0; i < s.length; i++) {
				s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
			}
			return s;
		}
	}

	/** [fromFrame, toFrame) 구간의 peak dBFS(무음 = -999). ch = -1 두 채널, 0 왼쪽, 1 오른쪽. */
	static double peakDb(short[] s, long fromFrame, long toFrame, int ch) {
		int peak = 0;
		for (long f = Math.max(0, fromFrame); f < Math.min(toFrame, s.length / 2); f++) {
			for (int c = 0; c < 2; c++) {
				if (ch >= 0 && c != ch) {
					continue;
				}
				peak = Math.max(peak, Math.abs(s[(int) (2 * f + c)]));
			}
		}
		return peak == 0 ? -999 : 20 * Math.log10(peak / 32768.0);
	}

	static double rmsDb(short[] s, int ch) {
		double sum = 0;
		long n = 0;
		for (int i = 0; i < s.length; i++) {
			if (ch >= 0 && (i % 2) != ch) {
				continue;
			}
			sum += (double) s[i] * s[i];
			n++;
		}
		return sum <= 0 ? -999 : 10 * Math.log10(sum / n / (32768.0 * 32768.0));
	}

	/** 자기상관으로 구한 기본 주파수(Hz). 최댓값의 0.9배 이상인 가장 짧은 주기를 고른다(옥타브 아래 오인 방지). */
	static double f0(short[] s, double fromSec, double toSec) {
		int a = (int) (fromSec * 44100), b = (int) (toSec * 44100);
		int n = b - a;
		double[] x = new double[n];
		double mean = 0;
		for (int i = 0; i < n; i++) {
			x[i] = (s[2 * (a + i)] + s[2 * (a + i) + 1]) / 2.0;
			mean += x[i];
		}
		mean /= n;
		for (int i = 0; i < n; i++) {
			x[i] -= mean;
		}
		int minLag = 44100 / 2500, maxLag = 44100 / 60; // 60~2500Hz
		double[] r = new double[maxLag + 1];
		double best = 0;
		for (int lag = minLag; lag <= maxLag; lag++) {
			double acc = 0;
			for (int i = 0; i + lag < n; i++) {
				acc += x[i] * x[i + lag];
			}
			r[lag] = acc / (n - lag);
			best = Math.max(best, r[lag]);
		}
		for (int lag = minLag + 1; lag < maxLag; lag++) {
			if (r[lag] >= 0.9 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
				// 포물선 보간
				double y0 = r[lag - 1], y1 = r[lag], y2 = r[lag + 1];
				double d = (y0 - 2 * y1 + y2) == 0 ? 0 : 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2);
				return 44100.0 / (lag + d);
			}
		}
		return -1;
	}

	/** expected 대비 actual의 옥타브 차(log2). */
	static double octaves(double expected, double actual) {
		return actual > 0 ? Math.log(actual / expected) / Math.log(2) : Double.NaN;
	}

	/** actual이 expected의 2^k배(k 정수, 옥타브 차이 허용)인지(±0.05옥타브). */
	static void assertOctaveOf(String msg, double expected, double actual) {
		double oct = Math.log(actual / expected) / Math.log(2);
		assertTrue(msg + ": " + expected + " vs " + actual, actual > 0 && Math.abs(oct - Math.round(oct)) < 0.05);
	}

	static double midiHz(int midi) {
		return 440.0 * Math.pow(2, (midi - 69) / 12.0);
	}

	static JsonObject readMeta(Path wav) throws IOException {
		Path json = wav.resolveSibling(wav.getFileName().toString().replaceAll("\\.wav$", ".json"));
		return JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	static Path path(JsonObject resp, int i) {
		return Path.of(resp.getAsJsonArray("files").get(i).getAsJsonObject().get("path").getAsString());
	}

	// ------------------------------------------------------------------ 시험 1

	/** 시험 1: 피아노 C4 온음표(87BPM) → 2.76초 ≤ 길이 ≤ 2.76 + tail, peak > −40, 앞 0.1초 안에 시작, 끝 0.1초 거의 무음. */
	@Test
	public void test1PianoWholeNote() throws Exception {
		assumeDls("test1PianoWholeNote");
		BridgeEngine e = engine();
		Project p = e.newProject("시험1", null, "4/4", 87);
		p.addTrack("피아노", 21, null, null, null);
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 384, "C4")), "strict", false);

		JsonObject r = WavRender.renderToFiles(e, p, req(null, null, null, null, "t1"), null, ENGINE);
		assertEquals(1, r.getAsJsonArray("files").size());
		Path wav = path(r, 0);
		short[] s = readWav(wav);
		long frames = s.length / 2;
		double dur = frames / 44100.0;
		double peak = peakDb(s, 0, frames, -1);
		double head = peakDb(s, 0, 4410, -1);
		double last = peakDb(s, frames - 4410, frames, -1);
		JsonObject meta = readMeta(wav);
		double tailLimit = meta.getAsJsonObject("audio").get("tail_sec_limit").getAsDouble();
		double f = f0(s, 0.3, 1.0);
		System.err.println(String.format(Locale.ROOT,
				"[render-test] 시험1: 길이 %.4f초, peak %.2f dBFS, 앞 0.1초 peak %.2f, 끝 0.1초 peak %.2f, tail %.3f초, "
						+ "f0 %.1fHz(MIDI 60 기대 %.1fHz, 차 %+.2f옥타브)",
				dur, peak, head, last, meta.getAsJsonObject("audio").get("tail_sec_actual").getAsDouble(), f, midiHz(60),
				octaves(midiHz(60), f)));
		assertOctaveOf("시험1 f0 = C(옥타브 무관)", midiHz(60), f);
		assertTrue("길이 " + dur, dur >= SEC_87_MEASURE - 1e-3 && dur <= SEC_87_MEASURE + tailLimit + 0.01);
		assertTrue("peak " + peak, peak > -40);
		assertTrue("앞 0.1초 안에 소리 " + head, head > -60);
		assertTrue("끝 0.1초 거의 무음 " + last, last < -60);

		// 메타 형식
		assertEquals("mabibridge.render/1", meta.get("schema").getAsString());
		assertEquals(dur, meta.getAsJsonObject("audio").get("duration_sec").getAsDouble(), 1e-3);
		JsonArray ms = meta.getAsJsonArray("measures");
		assertEquals(1, ms.size());
		assertEquals(4, ms.get(0).getAsJsonObject().getAsJsonArray("beats").size());
		assertEquals(SEC_87_MEASURE, ms.get(0).getAsJsonObject().get("end_sec").getAsDouble(), 1e-4);
		MetaSchema.assertComplete(meta);
	}

	// ------------------------------------------------------------------ 시험 2

	static final int[] PITCH = { 60, 64, 67, 72, 57 }; // C4 E4 G4 C5 A3
	static final int[] PROGRAM = { 21, 22, 24, 5, 2 }; // 피아노, 바이올린, 하프, 플룻, 만돌린
	static final String[] PROGRAM_NAME = { "피아노", "바이올린", "하프", "플룻", "만돌린" };

	/**
	 * 시험 2: 트랙 5개, melody에 서로 다른 음 하나씩 → split track 5파일 모두 무음이 아님(트랙 5 = 채널 16~19).
	 * 트랙 5 단독 렌더와 RMS 차 &lt; 1dB. 트랙 5 음높이를 바꾸면 기본 주파수가 바뀜. 덧붙여 트랙 1은 왼쪽 끝, 트랙 5는 오른쪽 끝에
	 * 두어 채널이 0~15로 접히면(트랙 1 채널 설정으로 울리면) 소리가 왼쪽으로 가는 것을 잡는다.
	 */
	@Test
	public void test2ChannelsAbove16() throws Exception {
		assumeDls("test2ChannelsAbove16");
		BridgeEngine e = engine();
		Project p = e.newProject("시험2", null, "4/4", 87);
		for (int i = 0; i < 5; i++) {
			int pan = (i == 0) ? 0 : (i == 4 ? 127 : 64);
			p.addTrack("T" + (i + 1), PROGRAM[i], null, pan, null);
			p.putNotes(i + 1, "melody", List.of(NoteSpec.at(0, 384, PITCH[i])), "strict", false);
		}
		JsonObject split = WavRender.renderToFiles(e, p, req(null, null, null, "track", "t2"), null, ENGINE);
		assertEquals(5, split.getAsJsonArray("files").size());
		double[] rms = new double[5];
		double[] fs = new double[5];
		for (int i = 0; i < 5; i++) {
			Path wav = path(split, i);
			assertTrue(wav.getFileName().toString(), wav.getFileName().toString().equals(String.format("t2__t%02d.wav", i + 1)));
			short[] s = readWav(wav);
			rms[i] = rmsDb(s, -1);
			fs[i] = f0(s, 0.3, 1.0);
			System.err.println(String.format(Locale.ROOT,
					"[render-test] 시험2 f0: 트랙%d %s(%d) MIDI %d 기대 %.1fHz → 측정 %.1fHz(차 %+.2f옥타브)", i + 1,
					PROGRAM_NAME[i], PROGRAM[i], PITCH[i], midiHz(PITCH[i]), fs[i], octaves(midiHz(PITCH[i]), fs[i])));
			assertOctaveOf("트랙" + (i + 1) + " f0 음이름", midiHz(PITCH[i]), fs[i]);
			assertTrue("트랙 " + (i + 1) + " 무음", peakDb(s, 0, s.length / 2, -1) > -40);
			JsonObject meta = readMeta(wav);
			assertEquals("track", meta.get("split").getAsString());
			assertEquals(5, meta.getAsJsonArray("files").size());
			JsonObject tr = meta.getAsJsonArray("tracks").get(0).getAsJsonObject();
			assertEquals(i + 1, tr.get("track").getAsInt());
			assertEquals(i * 4, tr.getAsJsonObject("parts").getAsJsonObject("melody").get("channel").getAsInt());
			MetaSchema.assertComplete(meta);
		}
		short[] t5 = readWav(path(split, 4));
		double left5 = rmsDb(t5, 0), right5 = rmsDb(t5, 1);
		short[] t1 = readWav(path(split, 0));
		double left1 = rmsDb(t1, 0), right1 = rmsDb(t1, 1);
		double f5 = f0(t5, 0.3, 1.0);

		JsonObject solo = WavRender.renderToFiles(e, p, req(null, null, List.of(5), null, "t2solo"), null, ENGINE);
		short[] s5 = readWav(path(solo, 0));
		double soloRms = rmsDb(s5, -1);

		// 트랙 5의 음만 바꾼다(A3 → E4)
		p.deleteNotes(5, "melody", 1, 1);
		p.putNotes(5, "melody", List.of(NoteSpec.at(0, 384, 64)), "strict", false);
		JsonObject moved = WavRender.renderToFiles(e, p, req(null, null, null, "track", "t2moved"), null, ENGINE);
		short[] m5 = readWav(path(moved, 4));
		double fm = f0(m5, 0.3, 1.0);
		double f1 = f0(t1, 0.3, 1.0);

		System.err.println(String.format(Locale.ROOT,
				"[render-test] 시험2: RMS t1..t5 = %.2f %.2f %.2f %.2f %.2f dBFS; 트랙5 단독 %.2f(차 %.3f dB); "
						+ "트랙5 L/R %.1f/%.1f, 트랙1 L/R %.1f/%.1f; f0 트랙1 %.1fHz, 트랙5 %.1fHz → 바꾼 뒤 %.1fHz",
				rms[0], rms[1], rms[2], rms[3], rms[4], soloRms, Math.abs(soloRms - rms[4]), left5, right5, left1, right1,
				f1, f5, fm));
		assertTrue("트랙5 단독과 split RMS 차 < 1dB", Math.abs(soloRms - rms[4]) < 1.0);
		assertTrue("트랙5는 오른쪽(팬 127): L " + left5 + " R " + right5, right5 > left5 + 20);
		assertTrue("트랙1은 왼쪽(팬 0): L " + left1 + " R " + right1, left1 > right1 + 20);
		// 악기에 따라 적은 음보다 옥타브 위로 울리는 것이 있어(피아노 등) 음이름(옥타브 무시)과 음정 비만 본다.
		assertOctaveOf("트랙1 f0 = C", midiHz(60), f1);
		assertOctaveOf("트랙5 f0 = A", midiHz(57), f5);
		System.err.println(String.format(Locale.ROOT,
				"[render-test] 시험2 f0: 트랙5 만돌린(2) MIDI 64로 바꾼 뒤 기대 %.1fHz → 측정 %.1fHz(차 %+.2f옥타브)",
				midiHz(64), fm, octaves(midiHz(64), fm)));
		assertOctaveOf("바꾼 뒤 f0 = E", midiHz(64), fm);
		assertEquals("A3 → E4 음정 비(완전5도)", Math.pow(2, 7 / 12.0), fm / f5, 0.03);
	}

	// ------------------------------------------------------------------ 박자표·범위·오류

	/** measures[].beats는 박자표를 따른다(3/4 → 3박, 6/8 → 6박). 범위 앞에서 걸친 음은 넣지 않는다. */
	@Test
	public void measuresFollowTimeSignature() throws Exception {
		assumeDls("measuresFollowTimeSignature");
		BridgeEngine e = engine();
		Project p = e.newProject("박자", null, "3/4", 120);
		p.addTrack("피아노", 21, null, null, null);
		// 1~2마디(3/4, 288틱씩)에 음, 3마디부터 6/8(마디 288틱, 박 48틱)
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 288, "C4"), NoteSpec.at(288, 576, "E4"), NoteSpec.at(864, 96, "G4")),
				"strict", false);
		p.score().getTimeSignatureList().add(new TimeSignature(p.score(), 576, 6, 8));

		// 2~3마디: 2마디에서 시작해 3마디까지 걸친 E4는 들어가고, 1마디 C4는 빠진다.
		JsonObject r = WavRender.renderToFiles(e, p, req(2, 4, null, null, "ts"), null, ENGINE);
		JsonObject meta = readMeta(path(r, 0));
		JsonArray ms = meta.getAsJsonArray("measures");
		assertEquals(3, ms.size());
		JsonObject m2 = ms.get(0).getAsJsonObject(), m3 = ms.get(1).getAsJsonObject();
		assertEquals(2, m2.get("measure").getAsInt());
		assertEquals(288, m2.get("tick").getAsInt());
		assertEquals(0.0, m2.get("start_sec").getAsDouble(), 1e-9);
		assertEquals(3, m2.getAsJsonArray("beats").size());
		assertEquals(0.5, m2.getAsJsonArray("beats").get(1).getAsDouble(), 1e-6);
		assertEquals(6, m3.getAsJsonArray("beats").size());
		assertEquals("6/8", m3.get("sig").getAsString());
		assertEquals(1.5, m3.get("start_sec").getAsDouble(), 1e-6);
		assertEquals(1.75, m3.getAsJsonArray("beats").get(1).getAsDouble(), 1e-6);
		assertEquals(2, meta.getAsJsonArray("time_signatures").size());
		JsonObject parts = meta.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonObject("parts");
		assertEquals(2, parts.getAsJsonObject("melody").get("notes").getAsInt()); // E4, G4
		assertEquals(288, meta.getAsJsonObject("range").get("from_tick").getAsInt());
		assertEquals(1152, meta.getAsJsonObject("range").get("to_tick").getAsInt());
		MetaSchema.assertComplete(meta);
	}

	@Test
	public void errors() throws Exception {
		// 보호 폴더는 이 시험이 직접 정한다(설치 위치의 프로젝트 파일에 기대지 않음)
		Path guarded = tmp.resolve("guarded");
		BridgeEngine e = new BridgeEngine(BridgeEngine.Config.defaults().withSnapshots(tmp.resolve("snapshots"))
				.withProtected(java.util.List.of(guarded)));
		Project p = e.newProject("오류", null, "4/4", 120);
		p.addTrack("피아노", 21, null, null, null);
		// 보호 폴더(DLS 없이도 먼저 거절)
		Path prot = guarded.resolve("render");
		expect("PROTECTED_PATH", () -> WavRender.renderToFiles(e, p,
				new WavRender.Request(null, null, null, null, null, null, "x", prot, false), null, ENGINE));
		expect("BAD_ARGUMENT", () -> WavRender.renderToFiles(e, p,
				new WavRender.Request(null, null, null, null, null, null, "a/b", tmp, false), null, ENGINE));
		expect("BAD_ARGUMENT", () -> WavRender.renderToFiles(e, p,
				new WavRender.Request(null, null, null, null, null, null, "x", Path.of("relative"), false), null, ENGINE));
		boolean dls = BridgeEngine.defaultDls().stream().anyMatch(Files::isRegularFile);
		if (!dls) {
			System.err.println("[SKIP] RenderTest.errors(DLS 필요한 부분): DLS 파일 없음");
			return;
		}
		expect("NOTHING_TO_RENDER", () -> WavRender.renderToFiles(e, p, req(null, null, null, null, "empty"), null, ENGINE));
		expect("BAD_ARGUMENT", () -> WavRender.renderToFiles(e, p,
				new WavRender.Request(null, null, null, null, "bogus", null, "x", tmp, false), null, ENGINE));
		expect("NO_TRACK", () -> WavRender.renderToFiles(e, p, req(null, null, List.of(3), null, "x"), null, ENGINE));
		p.putNotes(1, "melody", List.of(NoteSpec.at(0, 96, "C4")), "strict", false);
		expect("NOTHING_TO_RENDER", () -> WavRender.renderToFiles(e, p, req(5, 6, null, null, "late"), null, ENGINE));
		WavRender.renderToFiles(e, p, req(null, null, null, null, "dup"), null, ENGINE);
		expect("FILE_EXISTS", () -> WavRender.renderToFiles(e, p, req(null, null, null, null, "dup"), null, ENGINE));
		JsonObject again = WavRender.renderToFiles(e, p,
				new WavRender.Request(null, null, null, null, null, 0.5, "dup", tmp, true), null, ENGINE);
		assertNotNull(again);
		assertTrue(readMeta(path(again, 0)).getAsJsonObject("audio").get("tail_sec_actual").getAsDouble() <= 0.5 + 1e-6);
	}

	interface Body {
		Object run() throws Exception;
	}

	static void expect(String code, Body b) throws Exception {
		try {
			b.run();
			fail(code + " 오류가 나야 함");
		} catch (EngineException ex) {
			assertEquals(ex.toString(), code, ex.code());
		}
	}
}
