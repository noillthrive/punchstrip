package mabibridge.render;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sound.sampled.AudioFormat;

import com.google.gson.JsonObject;

/**
 * 오프라인 신시사이저 설정. {@code SoftSynthesizer.openStream}의 info 키 14개를 모두 명시한다.
 * 빠진 키는 Java Preferences(Windows는 HKCU 레지스트리)에 저장된 값이 덮어쓰므로 하나도 빼지 않는다.
 *
 * <p>앱({@code MabiDLS.initializeMIDI})과 같은 값: midi channels 96, large mode true, max polyphony 256.
 * JDK 기본값 그대로: interpolation linear, control rate 147, reverb·chorus·auto gain control·light reverb·
 * jitter correction true, latency 120000, device id 0, format 44.1kHz/16bit/2ch. 다른 값 하나:
 * load default soundbank false(true면 기본 뱅크를 찾다가 {@code user.home/.gervill}에 파일을 쓰는 경로가 있다).</p>
 */
public final class SynthSettings {

	public static final String SYNTH_CLASS = "com.sun.media.sound.SoftSynthesizer";

	public static final float SAMPLE_RATE = 44100f;
	public static final int BITS = 16;
	public static final int CHANNELS = 2;
	public static final int FRAME_BYTES = 4;
	/** 44100 Hz · 16bit · 스테레오 · signed · little endian(GUI WAV 내보내기와 같음). */
	public static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);

	public static final int MIDI_CHANNELS = 96;
	public static final boolean LARGE_MODE = true;
	public static final int MAX_POLYPHONY = 256;
	public static final boolean LOAD_DEFAULT_SOUNDBANK = false;
	public static final String INTERPOLATION = "linear";
	public static final float CONTROL_RATE = 147f;
	public static final boolean REVERB = true;
	public static final boolean CHORUS = true;
	public static final boolean AUTO_GAIN_CONTROL = true;
	public static final boolean LIGHT_REVERB = true;
	public static final boolean JITTER_CORRECTION = true;
	public static final long LATENCY = 120000L;
	public static final int DEVICE_ID = 0;

	/** 제어 버퍼 = (int)(44100/147) = 300프레임 = 1200바이트(약 6.8ms). 스트림을 이 단위로 읽는다. */
	public static final int BLOCK_FRAMES = (int) (SAMPLE_RATE / CONTROL_RATE);
	public static final int BLOCK_BYTES = BLOCK_FRAMES * FRAME_BYTES;

	private SynthSettings() {}

	/** openStream에 넘길 info(키 14개). */
	public static Map<String, Object> info() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("midi channels", MIDI_CHANNELS);
		m.put("large mode", LARGE_MODE);
		m.put("max polyphony", MAX_POLYPHONY);
		m.put("load default soundbank", LOAD_DEFAULT_SOUNDBANK);
		m.put("interpolation", INTERPOLATION);
		m.put("control rate", CONTROL_RATE);
		m.put("reverb", REVERB);
		m.put("chorus", CHORUS);
		m.put("auto gain control", AUTO_GAIN_CONTROL);
		m.put("light reverb", LIGHT_REVERB);
		m.put("jitter correction", JITTER_CORRECTION);
		m.put("latency", LATENCY);
		m.put("device id", DEVICE_ID);
		m.put("format", FORMAT);
		return m;
	}

	/** 메타 JSON의 synth 객체. */
	public static JsonObject metaJson() {
		JsonObject o = new JsonObject();
		o.addProperty("class", SYNTH_CLASS);
		o.addProperty("midi_channels", MIDI_CHANNELS);
		o.addProperty("large_mode", LARGE_MODE);
		o.addProperty("max_polyphony", MAX_POLYPHONY);
		o.addProperty("load_default_soundbank", LOAD_DEFAULT_SOUNDBANK);
		o.addProperty("interpolation", INTERPOLATION);
		o.addProperty("control_rate", (int) CONTROL_RATE);
		o.addProperty("reverb", REVERB);
		o.addProperty("chorus", CHORUS);
		o.addProperty("auto_gain_control", AUTO_GAIN_CONTROL);
		o.addProperty("light_reverb", LIGHT_REVERB);
		o.addProperty("jitter_correction", JITTER_CORRECTION);
		o.addProperty("latency", LATENCY);
		o.addProperty("device_id", DEVICE_ID);
		return o;
	}
}
