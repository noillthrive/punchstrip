package mabibridge.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sound.midi.Instrument;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.VoiceStatus;
import javax.sound.sampled.AudioInputStream;

import com.sun.media.sound.SoftShortMessage;
import com.sun.media.sound.SoftSynthesizer;

import jp.fourthline.mabiicco.midi.InstClass;
import jp.fourthline.mabiicco.midi.InstType;
import jp.fourthline.mabiicco.midi.MabiDLS;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;

/**
 * 오디오 장치 없이 JDK 내장 신시사이저(Gervill)의 오프라인 스트림으로 PCM을 만든다.
 *
 * <p>순서: {@code new SoftSynthesizer()} → {@code openStream(fmt, info)} → 악기 적재 → 채널 설정(앱 로직을 옮김) →
 * 모든 이벤트를 {@link SoftShortMessage} + 시각(µs)으로 먼저 보냄 → 스트림을 1200바이트씩 읽음 → 닫기.
 * 스트림을 읽을 때만 시간이 흐르므로 실시간보다 빠르다.</p>
 *
 * <p>{@code SoftShortMessage}를 쓰는 이유: 시각을 붙여 보낸 메시지 중 채널 16 이상은 수신기가 {@code clone()}해 두는데,
 * {@code ShortMessage}(엔진의 {@code ExtendMessage} 포함)의 clone은 채널을 하위 4비트로 잃는다. SoftShortMessage의 clone은
 * 채널을 보존한다.</p>
 *
 * <p>부르지 않는 것: {@code MidiSystem.getSynthesizer()}, {@code MabiDLS.initializeMIDI}·{@code updateMidiControl}·
 * {@code loadRequiredInstruments} 등 장치·GUI 의존 경로, {@code ExtendReceiver}, {@code SourceDataLine}.</p>
 */
public final class OfflineRenderer {

	/** 보낼 메시지 하나. us = 렌더 시작부터의 µs. */
	public record Timed(long us, int command, int channel, int data1, int data2) {}

	/** 렌더 결과(PCM 16bit LE 스테레오). pcm은 앞의 length 바이트만 유효하다. */
	public record Audio(byte[] pcm, int length, long frames, int peak, double sumSquares, double tailSecActual,
			double endBaseSec, boolean hitTailLimit, List<String> warnings) {

		public double durationSec() {
			return frames / (double) SynthSettings.SAMPLE_RATE;
		}

		/** 최대 절댓값 기준 dBFS(무음이면 null). */
		public Double peakDbfs() {
			return peak <= 0 ? null : 20.0 * Math.log10(peak / 32768.0);
		}

		/** 두 채널 전체 RMS dBFS(무음이면 null). */
		public Double rmsDbfs() {
			long n = frames * SynthSettings.CHANNELS;
			if (n == 0 || sumSquares <= 0) {
				return null;
			}
			return 10.0 * Math.log10(sumSquares / n / (32768.0 * 32768.0));
		}
	}

	/** 신시사이저를 열지 못함(SYNTH_UNAVAILABLE). */
	public static final class SynthUnavailable extends Exception {
		private static final long serialVersionUID = 1L;

		SynthUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private OfflineRenderer() {}

	/**
	 * 렌더한다. 신시사이저는 이 호출 안에서 열고, 실패해도 닫는다.
	 *
	 * @param score         채널 설정(팬·볼륨·악기)을 읽을 악보
	 * @param loadTracks    악기를 적재할 트랙 인덱스(0부터)
	 * @param events        보낼 메시지(시각 순 정렬, 같은 시각은 노트오프 먼저)
	 * @param endBaseSec    최소 길이(초). 이 시각까지는 무조건 읽는다(범위 끝과 마지막 이벤트 중 늦은 쪽)
	 * @param tailSec       endBaseSec 뒤 꼬리의 상한(초)
	 * @param progressLabel 진행 로그(stderr)에 붙일 이름
	 */
	public static Audio render(MMLScore score, Collection<Integer> loadTracks, List<Timed> events, double endBaseSec,
			double tailSec, String progressLabel) throws SynthUnavailable, InvalidMidiDataException, IOException {
		SoftSynthesizer synth = new SoftSynthesizer();
		AudioInputStream ais;
		try {
			ais = synth.openStream(SynthSettings.FORMAT, SynthSettings.info());
		} catch (MidiUnavailableException | RuntimeException e) {
			try {
				synth.close();
			} catch (RuntimeException ignore) {
				// 무시
			}
			throw new SynthUnavailable("신시사이저를 열지 못함: " + e, e);
		}
		List<String> warnings = new ArrayList<>();
		try {
			loadInstruments(synth, score, loadTracks, warnings);
			setupChannels(synth, score);
			Receiver rcv;
			try {
				rcv = synth.getReceiver();
			} catch (MidiUnavailableException e) {
				throw new SynthUnavailable("신시사이저 수신기를 얻지 못함: " + e, e);
			}
			for (Timed t : events) {
				SoftShortMessage m = new SoftShortMessage();
				m.setMessage(t.command(), t.channel(), t.data1(), t.data2());
				rcv.send(m, t.us());
			}
			return read(synth, ais, endBaseSec, tailSec, warnings, progressLabel);
		} finally {
			try {
				ais.close();
			} catch (IOException | RuntimeException ignore) {
				// 무시
			}
			synth.close();
		}
	}

	/** 트랙의 program·songProgram 악기를 연 뒤에 적재한다(앱 loadRequiredInstruments와 같은 대상, System.exit 경로 없음). */
	static void loadInstruments(SoftSynthesizer synth, MMLScore score, Collection<Integer> trackIndices,
			List<String> warnings) {
		MabiDLS dls = MabiDLS.getInstance();
		Set<Instrument> loaded = new LinkedHashSet<>();
		for (int ti : trackIndices) {
			MMLTrack t = score.getTrack(ti);
			int[] programs = { t.getProgram(), t.getSongProgram() };
			for (int k = 0; k < programs.length; k++) {
				int p = programs[k];
				if (k == 1 && p < 0) {
					continue;
				}
				InstClass inst = dls.getInstByProgram(p);
				if (inst == null || inst.getInstrument() == null) {
					warnings.add("트랙 " + (ti + 1) + ": 악기 " + p + "를 DLS에서 찾지 못함(그 파트는 소리가 나지 않을 수 있음)");
					continue;
				}
				if (loaded.add(inst.getInstrument()) && !synth.loadInstrument(inst.getInstrument())) {
					warnings.add("트랙 " + (ti + 1) + ": 악기 " + p + " 적재 실패");
				}
			}
		}
	}

	/**
	 * 채널 설정. 앱의 {@code initializeSynthesizer}(모든 채널 programChange(DLS_BANK, 0), CC91=0, CC64=0)와
	 * {@code updateMidiControl}(트랙마다 CC10 팬, CC7 볼륨, changeProgram) 로직을 옮겼다.
	 */
	static void setupChannels(SoftSynthesizer synth, MMLScore score) {
		MidiChannel[] ch = synth.getChannels();
		for (MidiChannel c : ch) {
			c.programChange(MabiDLS.DLS_BANK, 0);
			c.controlChange(91, 0); // 리버브 보내기 끔
			c.controlChange(64, 0); // 서스테인 끔
		}
		int n = Math.min(score.getTrackCount(), ch.length / 4);
		for (int ti = 0; ti < n; ti++) {
			MMLTrack t = score.getTrack(ti);
			int base = ti * 4;
			for (int i = base; i < base + 4; i++) {
				ch[i].controlChange(10, t.getPanpot());
				ch[i].controlChange(7, t.getVolume());
			}
			changeProgram(ch, base, t.getProgram(), t.getSongProgram());
		}
	}

	/** {@code MabiDLS.changeProgram}과 같은 규칙. */
	static void changeProgram(MidiChannel[] ch, int base, int program, int songProgram) {
		int midiProgram = InstClass.toMidiProram(program);
		int bank = InstClass.toMidiBank(program) + MabiDLS.DLS_BANK;
		if ((program & InstClass.DRUM) != 0) {
			bank = MabiDLS.DRUM_BANK;
			program -= InstClass.DRUM;
		}
		for (int i = base; i < base + 3; i++) {
			ch[i].programChange(bank, midiProgram);
		}
		ch[base + 3].programChange(MabiDLS.DLS_BANK, selectSongProgram(program, songProgram));
	}

	/** {@code MabiDLS.selectSongProgram}과 같은 규칙(악기를 못 찾으면 트랙 program). */
	static int selectSongProgram(int program, int songProgram) {
		InstClass inst = MabiDLS.getInstance().getInstByProgram(program);
		if (inst != null && inst.getType() != InstType.VOICE && songProgram >= 0) {
			return songProgram;
		}
		return program;
	}

	/** 스트림을 블록 단위로 읽는다. endBase 뒤로는 활성 보이스가 없고 블록이 전부 0이면 멈춘다. */
	private static Audio read(SoftSynthesizer synth, AudioInputStream ais, double endBaseSec, double tailSec,
			List<String> warnings, String label) throws IOException {
		final double sr = SynthSettings.SAMPLE_RATE;
		long endBaseFrames = (long) Math.ceil(endBaseSec * sr);
		long capFrames = endBaseFrames + (long) Math.floor(tailSec * sr);
		long initial = Math.min(Integer.MAX_VALUE - 16L,
				(endBaseFrames + (long) sr + SynthSettings.BLOCK_FRAMES) * SynthSettings.FRAME_BYTES);
		Buffer out = new Buffer((int) initial);
		byte[] blk = new byte[SynthSettings.BLOCK_BYTES];
		long frames = 0;
		int peak = 0;
		double sum = 0;
		boolean hitLimit = false;
		final long progressStep = (long) (sr * 30);
		long nextProgress = progressStep;
		while (true) {
			int got = readFully(ais, blk);
			if (got <= 0) {
				warnings.add("신시사이저 스트림이 일찍 끝남");
				break;
			}
			int useBytes = got - (got % SynthSettings.FRAME_BYTES);
			long blockFrames = useBytes / SynthSettings.FRAME_BYTES;
			boolean capped = false;
			if (frames + blockFrames >= capFrames) {
				blockFrames = Math.max(0, capFrames - frames);
				useBytes = (int) (blockFrames * SynthSettings.FRAME_BYTES);
				capped = true;
			}
			boolean zero = true;
			for (int i = 0; i + 1 < useBytes; i += 2) {
				int s = (short) ((blk[i] & 0xff) | (blk[i + 1] << 8));
				if (s != 0) {
					zero = false;
					int a = Math.abs(s);
					if (a > peak) {
						peak = a;
					}
					sum += (double) s * s;
				}
			}
			out.write(blk, 0, useBytes);
			frames += blockFrames;
			if (frames >= nextProgress) {
				System.err.println("[render] " + label + ": " + Math.round(frames / sr) + "초까지 렌더함");
				nextProgress += progressStep;
			}
			if (frames >= endBaseFrames && zero && !anyActiveVoice(synth)) {
				break;
			}
			if (capped) {
				hitLimit = true;
				break;
			}
		}
		double tailActual = Math.max(0, (frames - endBaseFrames) / sr);
		if (hitLimit && tailSec > 0) {
			warnings.add("꼬리 상한 tail_sec=" + tailSec + "초에서 잘림(소리가 아직 남아 있었음)");
		}
		return new Audio(out.buf(), out.size(), frames, peak, sum, tailActual, endBaseSec, hitLimit,
				List.copyOf(warnings));
	}

	private static int readFully(AudioInputStream ais, byte[] b) throws IOException {
		int off = 0;
		while (off < b.length) {
			int n = ais.read(b, off, b.length - off);
			if (n < 0) {
				break;
			}
			off += n;
		}
		return off;
	}

	/** 활성 보이스(active && volume > 0)가 하나라도 있는지(앱 PolyphonyMonitor와 같은 판정). */
	static boolean anyActiveVoice(SoftSynthesizer synth) {
		for (VoiceStatus v : synth.getVoiceStatus()) {
			if (v.active && v.volume > 0) {
				return true;
			}
		}
		return false;
	}

	/** 내부 배열을 복사하지 않고 넘기는 버퍼. */
	private static final class Buffer extends ByteArrayOutputStream {
		Buffer(int size) {
			super(Math.max(size, 1024));
		}

		byte[] buf() {
			return buf;
		}
	}
}
