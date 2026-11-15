package mabibridge.midi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToDoubleFunction;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * 참조 MIDI를 4분음표 = 96틱 SMF(형식 1)로 다시 쓴다(reference_regrid·reference_cleanup).
 *
 * <p>원본 바이트의 트랙 구조와 음표 아닌 이벤트(트랙 이름, 프로그램 변경, 컨트롤 변경, 포트, 조표, 마커 …)는
 * 그대로 두고 틱만 {@code tickMap}(원본 틱 → 새 96틱, 반올림 전 실수)으로 옮긴다(음수는 0). 음표는 원본 대신
 * {@code notesByTrack}(트랙 번호 1부터 → 새 틱의 음표)으로 쓴다. {@code fixedTempo}가 있으면 원본 템포(meta 0x51)와
 * 박자(meta 0x58)를 모두 버리고 첫 트랙 0틱에 그 템포·박자 하나만 쓴다(템포 지도 버리기).</p>
 *
 * <p>같은 틱에서는 note-off, 다른 이벤트, note-on 순서. 같은 트랙·채널·음높이의 음이 겹치면 앞 음의 끝을 뒤 음의
 * 시작으로 자른다({@link Result#overlapsTrimmed()}) — 다시 읽을 때 재타격으로 길이가 바뀌지 않게.</p>
 */
public final class ReferenceRewrite {

	/** 새 틱(96 기준)의 음표 하나. channel은 1~16. */
	public record NoteOut(int channel, int pitch, int velocity, long tick, long length) {}

	/** 고정 템포·박자. */
	public record FixedTiming(double bpm, int numerator, int denominator) {}

	/** 쓴 결과. */
	public record Result(byte[] bytes, int notesWritten, int overlapsTrimmed, int eventsCopied) {}

	private ReferenceRewrite() {}

	/** 원본 바이트를 다시 읽는다(실패는 ReferenceMidiException). */
	public static Sequence parse(byte[] bytes) throws ReferenceMidiException {
		try {
			return MidiSystem.getSequence(new ByteArrayInputStream(bytes));
		} catch (InvalidMidiDataException | IOException e) {
			throw new ReferenceMidiException("참조 MIDI 바이트를 다시 읽을 수 없음: " + e, null, null, e);
		}
	}

	public static Result write(byte[] srcBytes, LongToDoubleFunction tickMap, FixedTiming fixedTiming,
			Map<Integer, List<NoteOut>> notesByTrack) throws ReferenceMidiException {
		Sequence in = parse(srcBytes);
		Sequence out;
		try {
			out = new Sequence(Sequence.PPQ, MeasureMap.TPQN96);
		} catch (InvalidMidiDataException e) {
			throw new IllegalStateException(e);
		}
		record Ev(long tick, int order, int seq, MidiMessage msg) {}
		int copied = 0;
		int written = 0;
		int trimmed = 0;
		Track[] trs = in.getTracks();
		for (int ti = 0; ti < trs.length; ti++) {
			Track src = trs[ti];
			Track dst = out.createTrack();
			List<Ev> evs = new ArrayList<>();
			int seq = 0;
			if (ti == 0 && fixedTiming != null) {
				evs.add(new Ev(0, 1, seq++, tempoMeta(fixedTiming.bpm())));
				evs.add(new Ev(0, 1, seq++, timeSigMeta(fixedTiming.numerator(), fixedTiming.denominator())));
			}
			for (int i = 0; i < src.size(); i++) {
				MidiEvent e = src.get(i);
				MidiMessage m = e.getMessage();
				if (m instanceof MetaMessage mm) {
					int t = mm.getType();
					if (t == 0x2F) {
						continue; // end of track: Track keeps its own
					}
					if (fixedTiming != null && (t == 0x51 || t == 0x58)) {
						continue;
					}
				} else if (m instanceof ShortMessage sm) {
					int cmd = sm.getCommand();
					if (cmd == ShortMessage.NOTE_ON || cmd == ShortMessage.NOTE_OFF) {
						continue;
					}
				}
				long nt = Math.max(0, Math.round(tickMap.applyAsDouble(e.getTick())));
				evs.add(new Ev(nt, 1, seq++, m));
				copied++;
			}
			List<NoteOut> notes = new ArrayList<>(notesByTrack.getOrDefault(ti + 1, List.of()));
			notes.sort(Comparator.comparingLong(NoteOut::tick).thenComparingInt(NoteOut::pitch));
			List<long[]> ends = new ArrayList<>(); // parallel: [end] for each note in order
			List<NoteOut> kept = new ArrayList<>();
			Map<Long, Integer> idxByKey = new HashMap<>();
			for (NoteOut n : notes) {
				if (n.length() <= 0 || n.tick() < 0) {
					continue;
				}
				long key = (long) n.channel() * 128 + n.pitch();
				Integer pi = idxByKey.get(key);
				if (pi != null) {
					long[] pe = ends.get(pi);
					NoteOut prev = kept.get(pi);
					if (pe[0] > n.tick()) {
						if (n.tick() <= prev.tick()) {
							// same start: keep the longer one already stored, skip this duplicate
							pe[0] = Math.max(pe[0], n.tick() + n.length());
							trimmed++;
							continue;
						}
						pe[0] = n.tick();
						trimmed++;
					}
				}
				idxByKey.put(key, kept.size());
				kept.add(n);
				ends.add(new long[] { n.tick() + n.length() });
			}
			for (int k = 0; k < kept.size(); k++) {
				NoteOut n = kept.get(k);
				long end = ends.get(k)[0];
				if (end <= n.tick()) {
					continue;
				}
				evs.add(new Ev(n.tick(), 2, seq++, shortMsg(ShortMessage.NOTE_ON, n.channel() - 1, n.pitch(),
						Math.max(1, Math.min(127, n.velocity())))));
				evs.add(new Ev(end, 0, seq++, shortMsg(ShortMessage.NOTE_OFF, n.channel() - 1, n.pitch(), 0)));
				written++;
			}
			evs.sort(Comparator.comparingLong(Ev::tick).thenComparingInt(Ev::order).thenComparingInt(Ev::seq));
			for (Ev e : evs) {
				dst.add(new MidiEvent(e.msg(), e.tick()));
			}
		}
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try {
			MidiSystem.write(out, 1, bo);
		} catch (IOException e) {
			throw new ReferenceMidiException("다시 쓴 참조 MIDI를 만들 수 없음: " + e, null, null, e);
		}
		return new Result(bo.toByteArray(), written, trimmed, copied);
	}

	private static ShortMessage shortMsg(int cmd, int ch, int d1, int d2) {
		try {
			return new ShortMessage(cmd, ch, d1, d2);
		} catch (InvalidMidiDataException e) {
			throw new IllegalArgumentException(e);
		}
	}

	static MetaMessage tempoMeta(double bpm) {
		int us = (int) Math.round(60_000_000.0 / bpm);
		byte[] d = { (byte) (us >> 16), (byte) (us >> 8), (byte) us };
		try {
			return new MetaMessage(0x51, d, 3);
		} catch (InvalidMidiDataException e) {
			throw new IllegalArgumentException(e);
		}
	}

	static MetaMessage timeSigMeta(int num, int den) {
		int pow = Integer.numberOfTrailingZeros(den);
		byte[] d = { (byte) num, (byte) pow, 24, 8 };
		try {
			return new MetaMessage(0x58, d, 4);
		} catch (InvalidMidiDataException e) {
			throw new IllegalArgumentException(e);
		}
	}
}
