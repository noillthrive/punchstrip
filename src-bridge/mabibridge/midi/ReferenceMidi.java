package mabibridge.midi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;

/**
 * "정답지"로 쓰는 참조 MIDI 파일을 엄격하게 읽은 결과.
 *
 * <p>{@code javax.sound.midi}만 쓴다. 파일이 깨졌으면 추측해서 고치지 않고
 * {@link ReferenceMidiException}으로 실패를 알린다(가능하면 깨진 트랙·오프셋 포함).
 * 열리기는 하지만 이상한 점(끝나지 않은 음, 재타격, 비정상적으로 긴 길이 등)은
 * {@link #warnings()}에 모은다.</p>
 *
 * <p>틱 단위: {@code srcTick}류는 원본 해상도, {@code tick96}류는 4분음표 = 96틱.
 * 마디·박 위치 문자열은 96틱 기준이다.</p>
 */
public final class ReferenceMidi {

	/** 템포 변경. bpm은 60,000,000/µs를 소수점 둘째 자리까지 반올림. */
	public record Tempo(long srcTick, String pos, int sourceTrack, int microsPerQuarter, double bpm) {}

	/** 박자 변경. measure는 이 변경이 시작되는 마디. */
	public record TimeSignature(long srcTick, String pos, int sourceTrack, int numerator, int denominator, int measure) {}

	/** 조표. sharpsFlats: 음수 = 내림표 개수, 양수 = 올림표 개수. */
	public record KeySignature(long srcTick, String pos, int sourceTrack, int sharpsFlats, boolean minor, String name) {}

	/** 마커(meta 6). */
	public record Marker(long srcTick, String pos, int sourceTrack, String text) {}

	private final Path file;
	private final int format;
	private final int resolution;
	private final long tickLength;
	private final long microsecondLength;
	private final List<Tempo> tempos;
	private final List<TimeSignature> timeSignatures;
	private final List<KeySignature> keySignatures;
	private final List<Marker> markers;
	private final MeasureMap measureMapSrc;
	private final MeasureMap measureMap96;
	private final int measureCount;
	private final List<TrackInfo> tracks;
	private final List<List<Note>> notesByTrack;
	private final List<Note> allNotes;
	private final Warnings warnings;

	private static final Comparator<Note> NOTE_ORDER = Comparator.comparingLong(Note::srcTick)
			.thenComparing(Comparator.comparingInt(Note::pitch).reversed())
			.thenComparingInt(Note::track)
			.thenComparingInt(Note::channel);

	private ReferenceMidi(Builder b) {
		this.file = b.file;
		this.format = b.format;
		this.resolution = b.resolution;
		this.tickLength = b.tickLength;
		this.microsecondLength = b.microsecondLength;
		this.tempos = List.copyOf(b.tempos);
		this.timeSignatures = List.copyOf(b.timeSignatures);
		this.keySignatures = List.copyOf(b.keySignatures);
		this.markers = List.copyOf(b.markers);
		this.measureMapSrc = b.measureMapSrc;
		this.measureMap96 = b.measureMap96;
		this.measureCount = b.measureCount;
		this.tracks = List.copyOf(b.tracks);
		List<List<Note>> byTrack = new ArrayList<>();
		for (List<Note> l : b.notesByTrack) {
			byTrack.add(List.copyOf(l));
		}
		this.notesByTrack = Collections.unmodifiableList(byTrack);
		this.allNotes = List.copyOf(b.allNotes);
		this.warnings = b.warnings;
	}

	// ------------------------------------------------------------------ 열기

	/**
	 * 참조 MIDI를 연다.
	 *
	 * @throws ReferenceMidiException 파일을 읽을 수 없거나 MIDI로 해석할 수 없을 때,
	 *         또는 SMPTE 해상도처럼 마디를 계산할 수 없는 형식일 때
	 */
	public static ReferenceMidi load(Path file) throws ReferenceMidiException {
		String fname = String.valueOf(file.getFileName());
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		} catch (IOException e) {
			throw new ReferenceMidiException("참조 MIDI를 읽을 수 없음: " + fname + " — 원인: " + describe(e),
					file, null, e);
		}
		return load(file, bytes);
	}

	/**
	 * 이미 읽은 바이트로 연다. {@code file}은 이름 표시와 {@link #file()}에만 쓴다.
	 * {@link MidiSystem#getSequence(java.io.InputStream)}는 {@code getSequence(File)}과 같은 리더를 쓴다.
	 */
	public static ReferenceMidi load(Path file, byte[] bytes) throws ReferenceMidiException {
		String fname = String.valueOf(file.getFileName());
		Sequence seq;
		MidiFileFormat fileFormat;
		try {
			fileFormat = MidiSystem.getMidiFileFormat(new ByteArrayInputStream(bytes));
			seq = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
		} catch (InvalidMidiDataException | IOException e) {
			MidiByteScanner.Result scan = MidiByteScanner.scan(bytes);
			MidiByteScanner.Problem p = scan.firstProblem();
			StringBuilder msg = new StringBuilder();
			msg.append("참조 MIDI를 열 수 없음: ").append(fname).append(" — 원인: ").append(describe(e)).append(". ");
			if (p != null) {
				msg.append("깨진 위치: ").append(p);
				String m = scanMeasure(scan, p);
				if (m != null) {
					msg.append(" [").append(m).append("]");
				}
			} else {
				msg.append("깨진 위치: 위치 특정 실패(바이트 스캔에서 구조 오류를 찾지 못함)");
			}
			throw new ReferenceMidiException(msg.toString(), file, p, e);
		}

		if (seq.getDivisionType() != Sequence.PPQ) {
			throw new ReferenceMidiException("참조 MIDI를 열 수 없음: " + fname
					+ " — 원인: SMPTE 타임코드 해상도(division type " + seq.getDivisionType()
					+ ")는 마디를 계산할 수 없어 지원하지 않음", file, null, null);
		}
		return new Builder(file, fileFormat.getType(), seq, MidiByteScanner.scan(bytes)).build();
	}

	private static String describe(Throwable e) {
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
	}

	/** 스캔 실패 위치의 틱을, 스캔 중 본 박자로 대략 마디로 바꾼다. */
	private static String scanMeasure(MidiByteScanner.Result scan, MidiByteScanner.Problem p) {
		if (p.tickBefore() < 0 || scan.division() <= 0 || (scan.division() & 0x8000) != 0) {
			return null;
		}
		try {
			List<MeasureMap.Signature> sigs = new ArrayList<>();
			for (MidiByteScanner.ScannedTimeSignature s : scan.timeSignatures()) {
				sigs.add(new MeasureMap.Signature(s.tick(), s.numerator(), s.denominator()));
			}
			MeasureMap map = MeasureMap.of(scan.division(), sigs);
			MeasureMap.Position pos = map.position(p.tickBefore());
			return "해상도 " + scan.division() + " 기준 마디 " + pos.measure() + " " + pos.beat() + "박 부근";
		} catch (RuntimeException ex) {
			return null;
		}
	}

	// ------------------------------------------------------------------ 요약

	/** 원본 파일 경로. */
	public Path file() {
		return file;
	}

	/** SMF 포맷(0/1/2). */
	public int format() {
		return format;
	}

	/** 트랙 수. */
	public int trackCount() {
		return tracks.size();
	}

	/** 해상도(틱/4분음표). */
	public int resolution() {
		return resolution;
	}

	/** 곡 길이(원본 틱, 가장 늦은 이벤트까지). */
	public long tickLength() {
		return tickLength;
	}

	/** 곡 길이(마이크로초, 템포 반영). */
	public long microsecondLength() {
		return microsecondLength;
	}

	/** 곡 길이(초). */
	public double seconds() {
		return microsecondLength / 1_000_000.0;
	}

	/** 템포 목록(틱 순). 파일에 템포가 없으면 비어 있다(재생기는 120 BPM으로 본다). */
	public List<Tempo> tempos() {
		return tempos;
	}

	/** 첫 템포(BPM). 없으면 120. */
	public double initialBpm() {
		return (!tempos.isEmpty() && tempos.get(0).srcTick() == 0) ? tempos.get(0).bpm() : 120.0;
	}

	/** 박자 목록(실제로 적용되는 것, 틱 순). 파일에 없으면 4/4 하나. */
	public List<TimeSignature> timeSignatures() {
		return timeSignatures;
	}

	/** 조표 목록(틱 순). */
	public List<KeySignature> keySignatures() {
		return keySignatures;
	}

	/** 마커 목록(틱 순). */
	public List<Marker> markers() {
		return markers;
	}

	/** 마디 수(곡 길이의 마지막 틱이 들어 있는 마디). */
	public int measureCount() {
		return measureCount;
	}

	/** 원본 해상도 마디 지도. */
	public MeasureMap measureMap() {
		return measureMapSrc;
	}

	/** 96틱 마디 지도. */
	public MeasureMap measureMap96() {
		return measureMap96;
	}

	/** 트랙 요약(번호 순). */
	public List<TrackInfo> tracks() {
		return tracks;
	}

	/** 트랙 요약(1부터). */
	public TrackInfo track(int number) {
		checkTrack(number);
		return tracks.get(number - 1);
	}

	/** 열 때 발견한 비정상. */
	public Warnings warnings() {
		return warnings;
	}

	private volatile List<ReferenceHints.TrackHint> trackHints;

	/** 트랙마다 멜로디 점수·역할 추정(처음 부를 때 계산해 둔다). */
	public List<ReferenceHints.TrackHint> trackHints() {
		List<ReferenceHints.TrackHint> h = trackHints;
		if (h == null) {
			h = ReferenceHints.trackHints(this);
			trackHints = h;
		}
		return h;
	}

	/** 원본 틱 → 초(템포 반영). */
	public double secondsAt(long srcTick) {
		return microsAt(tempos, srcTick, resolution) / 1_000_000.0;
	}

	private static double microsAt(List<Tempo> tempos, long tick, int resolution) {
		double us = 0;
		long lastTick = 0;
		double usPerTick = 500_000.0 / resolution;
		for (Tempo t : tempos) {
			if (t.srcTick() >= tick) {
				break;
			}
			us += (t.srcTick() - lastTick) * usPerTick;
			lastTick = t.srcTick();
			usPerTick = (double) t.microsPerQuarter() / resolution;
		}
		us += (tick - lastTick) * usPerTick;
		return us;
	}

	// ------------------------------------------------------------------ 음표

	/** 전체 음표(시작 틱 순, 같은 틱이면 음 높은 순, 그다음 트랙 순). */
	public List<Note> notesAll() {
		return allNotes;
	}

	/**
	 * 한 트랙의 마디 범위 음표(양끝 포함, 마디는 1부터). 시작 틱 순, 같은 틱이면 음 높은 순.
	 * 음표의 마디는 {@link Note#measure()}(96틱 환산 시작 기준)로 판단한다.
	 */
	public List<Note> notes(int track, int fromMeasure, int toMeasure) {
		checkTrack(track);
		checkRange(fromMeasure, toMeasure);
		return filter(notesByTrack.get(track - 1), fromMeasure, toMeasure);
	}

	/** 모든 트랙의 마디 범위 음표(양끝 포함). 정렬은 {@link #notesAll()}과 같다. */
	public List<Note> notesAll(int fromMeasure, int toMeasure) {
		checkRange(fromMeasure, toMeasure);
		return filter(allNotes, fromMeasure, toMeasure);
	}

	private static List<Note> filter(List<Note> src, int from, int to) {
		List<Note> out = new ArrayList<>();
		for (Note n : src) {
			if (n.measure() >= from && n.measure() <= to) {
				out.add(n);
			}
		}
		return Collections.unmodifiableList(out);
	}

	private void checkTrack(int track) {
		if (track < 1 || track > tracks.size()) {
			throw new IllegalArgumentException("track " + track + " out of 1.." + tracks.size());
		}
	}

	private static void checkRange(int from, int to) {
		if (from < 1) {
			throw new IllegalArgumentException("fromMeasure < 1: " + from);
		}
		if (to < from) {
			throw new IllegalArgumentException("toMeasure < fromMeasure: " + from + "-" + to);
		}
	}

	// ------------------------------------------------------------------ 박별 화음

	/**
	 * 박마다 울리는 음이름 집합. 드럼 채널(10)은 제외한다.
	 *
	 * @param tracks 볼 트랙 번호들(null 또는 비어 있으면 전 트랙)
	 */
	public List<BeatChord> chordsByBeat(int fromMeasure, int toMeasure, Set<Integer> tracks) {
		return chordsByBeat(fromMeasure, toMeasure, tracks, false);
	}

	/**
	 * 박마다 그 박 안에서 울리는(시작했거나 지속 중인) 음. 96틱 기준으로
	 * {@code tick96 < 박끝 && end96 > 박시작}인 음을 모은다.
	 *
	 * @param tracks 볼 트랙 번호들(null 또는 비어 있으면 전 트랙)
	 * @param includeDrums 채널 10 음도 넣을지
	 */
	public List<BeatChord> chordsByBeat(int fromMeasure, int toMeasure, Set<Integer> tracks, boolean includeDrums) {
		checkRange(fromMeasure, toMeasure);
		if (tracks != null) {
			for (int t : tracks) {
				checkTrack(t);
			}
		}
		boolean allTracks = tracks == null || tracks.isEmpty();
		long rangeStart = measureMap96.measureStart(fromMeasure);
		long rangeEnd = measureMap96.measureEnd(toMeasure);
		List<Note> candidates = new ArrayList<>();
		for (Note n : allNotes) {
			if (n.tick96() >= rangeEnd) {
				continue;
			}
			if (n.end96() <= rangeStart) {
				continue;
			}
			if (!allTracks && !tracks.contains(n.track())) {
				continue;
			}
			if (!includeDrums && n.isDrum()) {
				continue;
			}
			candidates.add(n);
		}
		Comparator<Note> lowFirst = Comparator.comparingInt(Note::pitch).thenComparingInt(Note::track)
				.thenComparingLong(Note::tick96);

		List<BeatChord> out = new ArrayList<>();
		for (int m = fromMeasure; m <= toMeasure; m++) {
			long mEnd = measureMap96.measureEnd(m);
			long bl = measureMap96.beatLength(m);
			int beats = measureMap96.beatsInMeasure(m);
			for (int b = 1; b <= beats; b++) {
				long bs = measureMap96.measureStart(m) + (long) (b - 1) * bl;
				long be = Math.min(bs + bl, mEnd);
				List<Note> sounding = new ArrayList<>();
				for (Note n : candidates) {
					if (n.tick96() < be && n.end96() > bs) {
						sounding.add(n);
					}
				}
				sounding.sort(lowFirst);
				TreeSet<Integer> pcs = new TreeSet<>();
				for (Note n : sounding) {
					pcs.add(n.pitch() % 12);
				}
				Set<String> names = new LinkedHashSet<>();
				for (int pc : pcs) {
					names.add(MidiNames.pitchClassName(pc));
				}
				String bass = sounding.isEmpty() ? null : sounding.get(0).pitchClass();
				out.add(new BeatChord(m, b, m + ":" + b + ":0", bs, be, Collections.unmodifiableSet(names), bass,
						Collections.unmodifiableList(sounding)));
			}
		}
		return Collections.unmodifiableList(out);
	}

	// ------------------------------------------------------------------ 내부: 해석

	private static final class Builder {
		final Path file;
		final int format;
		final int resolution;
		final long tickLength;
		final long microsecondLength;
		final List<Tempo> tempos = new ArrayList<>();
		final List<TimeSignature> timeSignatures = new ArrayList<>();
		final List<KeySignature> keySignatures = new ArrayList<>();
		final List<Marker> markers = new ArrayList<>();
		MeasureMap measureMapSrc;
		MeasureMap measureMap96;
		int measureCount;
		final List<TrackInfo> tracks = new ArrayList<>();
		final List<List<Note>> notesByTrack = new ArrayList<>();
		final List<Note> allNotes = new ArrayList<>();
		Warnings warnings;

		private final Sequence seq;
		private final MidiByteScanner.Result scan;
		private final Warnings.Builder w = new Warnings.Builder();

		/** 1차 해석에서 모은 음(위치 변환 전). */
		private record RawNote(int track, int port, int channel, int pitch, int velocity, long start, long end) {}

		/** 서스테인 페달(CC64) 이벤트. 수집 순서(트랙 순, 트랙 안 이벤트 순)를 seq로 남긴다. */
		private record RawPedal(long tick, int track, int port, int channel, int value, int seq) {}

		private record RawTimeSig(long tick, int track, int num, int den) {}

		private record RawTempo(long tick, int track, int us) {}

		private record RawKey(long tick, int track, int sf, boolean minor) {}

		private record RawMarker(long tick, int track, String text) {}

		private record RawProgram(long tick, int track, int port, int channel, int program) {}

		/** 포트와 채널(1~16)을 한 정수 키로. */
		private static int pc(int port, int channel) {
			return port * 16 + (channel - 1);
		}

		private boolean anyPortMeta;

		/**
		 * 프로그램 변경이 어느 트랙의 악기에 적용되는지 분석한다.
		 * mergePorts가 true면 포트를 무시한다(포트를 모르는 재생기 기준). 그때 ChannelProgram.port는 -1.
		 */
		private static final class ProgramAnalysis {
			final boolean mergePorts;
			final Map<Integer, List<TrackInfo.ProgramChange>> pcByKey = new java.util.TreeMap<>();
			final Map<Integer, Set<Integer>> playersByKey = new HashMap<>();
			final List<List<Integer>> sentKeysByTrack = new ArrayList<>();
			final List<Set<Integer>> noteKeysByTrack = new ArrayList<>();
			final Set<Integer> ambiguousKeys = new TreeSet<>();
			final List<String> ambiguityMessages = new ArrayList<>();

			ProgramAnalysis(boolean mergePorts, List<TrackInfo.ProgramChange> allPcs, List<Set<Integer>> notePortChans) {
				this.mergePorts = mergePorts;
				for (int ti = 0; ti < notePortChans.size(); ti++) {
					sentKeysByTrack.add(new ArrayList<>());
					Set<Integer> keys = new TreeSet<>();
					for (int k : notePortChans.get(ti)) {
						int key = mergePorts ? pc(0, k % 16 + 1) : k;
						keys.add(key);
						playersByKey.computeIfAbsent(key, x -> new TreeSet<>()).add(ti + 1);
					}
					noteKeysByTrack.add(keys);
				}
				for (TrackInfo.ProgramChange p : allPcs) {
					int key = key(p.port(), p.channel());
					pcByKey.computeIfAbsent(key, x -> new ArrayList<>()).add(p);
					sentKeysByTrack.get(p.sourceTrack() - 1).add(key);
				}
				// 같은 틱에 서로 다른 트랙이 같은 채널에 다른 프로그램을 보낸 곳(누군가 음을 내는 채널만)
				for (Map.Entry<Integer, List<TrackInfo.ProgramChange>> e : pcByKey.entrySet()) {
					if (!playersByKey.containsKey(e.getKey())) {
						continue;
					}
					List<TrackInfo.ProgramChange> l = e.getValue();
					for (int i = 0; i < l.size(); ) {
						int j = i;
						while (j < l.size() && l.get(j).srcTick() == l.get(i).srcTick()) {
							j++;
						}
						Set<Integer> progs = new TreeSet<>();
						Set<Integer> srcs = new TreeSet<>();
						List<String> desc = new ArrayList<>();
						for (int k = i; k < j; k++) {
							progs.add(l.get(k).program());
							srcs.add(l.get(k).sourceTrack());
							desc.add("트랙 " + l.get(k).sourceTrack() + "=" + l.get(k).program() + "(" + l.get(k).gmName() + ")");
						}
						if (progs.size() > 1 && srcs.size() > 1) {
							ambiguousKeys.add(e.getKey());
							TrackInfo.ProgramChange first = l.get(i);
							ambiguityMessages.add((mergePorts ? "" : "포트 " + first.port() + " ") + "채널 " + first.channel()
									+ ": 틱 " + first.srcTick() + "(" + first.pos() + ")에 여러 트랙이 동시에 다른 프로그램을 보냄 ["
									+ String.join(", ", desc) + "] — 어느 것이 남을지는 재생기의 트랙 병합 순서에 달림"
									+ "(이 모듈은 트랙 번호가 큰 쪽을 나중으로 봄)");
						}
						i = j;
					}
				}
			}

			int key(int port, int channel) {
				return mergePorts ? pc(0, channel) : pc(port, channel);
			}

			List<TrackInfo.ChannelProgram> channelPrograms(int trackNo, List<Note> trackNotes) {
				List<TrackInfo.ChannelProgram> cps = new ArrayList<>();
				for (int key : noteKeysByTrack.get(trackNo - 1)) {
					int c = key % 16 + 1;
					// 이 트랙이 이 채널에서 낸 첫 음(트랙 안에서 포트가 바뀌는 경우는 드물어 채널로만 찾는다)
					long firstOnChannel = Long.MAX_VALUE;
					for (Note n : trackNotes) {
						if (n.channel() == c) {
							firstOnChannel = n.srcTick();
							break;
						}
					}
					List<TrackInfo.ProgramChange> changes = pcByKey.getOrDefault(key, List.of());
					Integer atFirst = null;
					Integer last = null;
					Set<Integer> sources = new TreeSet<>();
					for (TrackInfo.ProgramChange p : changes) {
						if (p.srcTick() <= firstOnChannel) {
							atFirst = p.program();
						}
						last = p.program();
						sources.add(p.sourceTrack());
					}
					boolean overridden = false;
					for (int s : sources) {
						if (s != trackNo) {
							overridden = true;
						}
					}
					cps.add(new TrackInfo.ChannelProgram(mergePorts ? -1 : key / 16, c, atFirst, last,
							Collections.unmodifiableSet(sources), overridden, ambiguousKeys.contains(key), List.copyOf(changes)));
				}
				return List.copyOf(cps);
			}

			/** 이 트랙이 보낸 프로그램 변경이 악기를 바꾸는 다른 트랙들. */
			Set<Integer> overrides(int trackNo) {
				Set<Integer> out = new TreeSet<>();
				for (int key : sentKeysByTrack.get(trackNo - 1)) {
					for (int u : playersByKey.getOrDefault(key, Set.of())) {
						if (u != trackNo) {
							out.add(u);
						}
					}
				}
				return Collections.unmodifiableSet(out);
			}
		}

		/**
		 * 서스테인 페달(CC64)로 늘어나는 울림 끝. 페달은 같은 (포트, 채널)의 음에만 걸린다(어느 트랙이 보냈든).
		 *
		 * <p>규칙: note-off 바로 전(같은 틱의 페달 이벤트는 note-off 뒤로 본다) 페달 값이 64 이상이면,
		 * 울림은 note-off 뒤 첫 페달 해제(값 &lt; 64)와 같은 (포트, 채널)·같은 음의 다음 시작 중 이른 쪽에서 끝난다.
		 * 해제가 없으면 곡 끝. 페달이 내려가 있지 않으면 note-off 그대로.</p>
		 */
		private static final class PedalIndex {
			private final Map<Integer, List<RawPedal>> byKey = new HashMap<>();
			private final Map<Long, long[]> startsByPitch = new HashMap<>();
			private final long songEnd;

			PedalIndex(List<RawNote> notes, List<RawPedal> pedals, long songEnd) {
				this.songEnd = songEnd;
				for (RawPedal p : pedals) {
					byKey.computeIfAbsent(pc(p.port(), p.channel()), k -> new ArrayList<>()).add(p);
				}
				for (List<RawPedal> l : byKey.values()) {
					l.sort(Comparator.comparingLong(RawPedal::tick).thenComparingInt(RawPedal::seq));
				}
				if (pedals.isEmpty()) {
					return;
				}
				Map<Long, List<Long>> tmp = new HashMap<>();
				for (RawNote n : notes) {
					tmp.computeIfAbsent(pitchKey(n), k -> new ArrayList<>()).add(n.start());
				}
				for (Map.Entry<Long, List<Long>> e : tmp.entrySet()) {
					long[] a = e.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
					startsByPitch.put(e.getKey(), a);
				}
			}

			private static long pitchKey(RawNote n) {
				return ((long) pc(n.port(), n.channel()) << 7) | n.pitch();
			}

			long pedalEnd(RawNote n) {
				long off = n.end();
				List<RawPedal> l = byKey.get(pc(n.port(), n.channel()));
				if (l == null) {
					return off;
				}
				int last = -1;
				for (int i = 0; i < l.size() && l.get(i).tick() < off; i++) {
					last = i;
				}
				if (last < 0 || l.get(last).value() < 64) {
					return off;
				}
				long release = Math.max(songEnd, off);
				for (int i = last + 1; i < l.size(); i++) {
					if (l.get(i).value() < 64) {
						release = l.get(i).tick();
						break;
					}
				}
				long next = Long.MAX_VALUE;
				long[] starts = startsByPitch.get(pitchKey(n));
				if (starts != null) {
					int idx = java.util.Arrays.binarySearch(starts, off);
					if (idx < 0) {
						idx = -idx - 1;
					} else {
						while (idx > 0 && starts[idx - 1] == off) {
							idx--;
						}
					}
					while (idx < starts.length && starts[idx] <= n.start()) {
						idx++;
					}
					if (idx < starts.length) {
						next = starts[idx];
					}
				}
				return Math.max(off, Math.min(release, next));
			}
		}

		Builder(Path file, int format, Sequence seq, MidiByteScanner.Result scan) {
			this.file = file;
			this.format = format;
			this.seq = seq;
			this.scan = scan;
			this.resolution = seq.getResolution();
			this.tickLength = seq.getTickLength();
			this.microsecondLength = seq.getMicrosecondLength();
		}

		ReferenceMidi build() throws ReferenceMidiException {
			if (resolution <= 0) {
				throw new ReferenceMidiException("참조 MIDI를 열 수 없음: " + file.getFileName()
						+ " — 원인: 해상도가 0 이하(" + resolution + ")", file, null, null);
			}
			Track[] trs = seq.getTracks();
			if (format == 2) {
				w.add("format 2 파일: 트랙마다 독립된 패턴이라 템포·박자·마디 계산이 전 트랙에 맞지 않을 수 있음");
			}
			if (format == 0 && trs.length != 1) {
				w.add("format 0인데 트랙이 " + trs.length + "개");
			}
			if (!scan.ok()) {
				w.byteScanProblem = true;
				w.add("바이트 스캔에서 구조 문제 발견(Java는 파일을 열었지만 이 지점 이후의 해석은 원본 의도와 다를 수 있음): "
						+ scan.firstProblem());
			}

			// ---- 1차: 이벤트 수집
			List<RawNote> rawNotes = new ArrayList<>();
			List<RawPedal> rawPedals = new ArrayList<>();
			List<RawTimeSig> rawSigs = new ArrayList<>();
			List<RawTempo> rawTempos = new ArrayList<>();
			List<RawKey> rawKeys = new ArrayList<>();
			List<RawMarker> rawMarkers = new ArrayList<>();
			List<RawProgram> rawPrograms = new ArrayList<>();
			String[] names = new String[trs.length];
			long[] endTicks = new long[trs.length];
			List<Set<Integer>> chans = new ArrayList<>();
			List<Set<Integer>> noteChans = new ArrayList<>();
			List<Set<Integer>> notePortChans = new ArrayList<>();
			List<Set<Integer>> portsByTrack = new ArrayList<>();
			Integer[] importProgram = new Integer[trs.length];
			boolean[] importDrum = new boolean[trs.length];
			List<String> perTrackNoteIssues = new ArrayList<>();

			for (int ti = 0; ti < trs.length; ti++) {
				int trackNo = ti + 1;
				Track tr = trs[ti];
				endTicks[ti] = tr.ticks();
				Set<Integer> ch = new TreeSet<>();
				Set<Integer> nch = new TreeSet<>();
				Set<Integer> npc = new TreeSet<>();
				Set<Integer> ports = new TreeSet<>();
				chans.add(ch);
				noteChans.add(nch);
				notePortChans.add(npc);
				portsByTrack.add(ports);
				// MIDI 포트(meta 0x21)는 이후의 채널 메시지에 적용된다. 첫 포트 메타보다 앞에 있는
				// 채널 메시지는 그 첫 포트 메타의 값을 쓴다(이 파일처럼 설정 블록 뒤에 포트 메타를 두는 도구가 있다).
				int port = firstPort(tr);
				if (port >= 0) {
					anyPortMeta = true;
				} else {
					port = 0;
				}
				// (channel<<7 | pitch) → 울리는 중인 음의 {시작 틱, 벨로시티, 포트}
				Map<Integer, long[]> active = new HashMap<>();
				int unfinishedHere = 0;
				int retrigHere = 0;
				Set<Integer> orphanChannels = new TreeSet<>();
				int orphanHere = 0;
				int invalidDataHere = 0;
				for (int i = 0; i < tr.size(); i++) {
					MidiEvent ev = tr.get(i);
					long tick = ev.getTick();
					MidiMessage msg = ev.getMessage();
					if (msg instanceof MetaMessage mm) {
						if (mm.getType() == 0x21 && mm.getData().length >= 1) {
							port = mm.getData()[0] & 0xFF; // MIDI 포트: 이후 이 트랙의 채널 메시지가 나가는 포트
							continue;
						}
						handleMeta(mm, tick, trackNo, ti, names, rawSigs, rawTempos, rawKeys, rawMarkers);
					} else if (msg instanceof ShortMessage sm) {
						int status = sm.getStatus();
						if (status >= 0xF0) {
							w.drop(String.format("시스템 메시지 0x%02X", status));
							continue;
						}
						int cmd = sm.getCommand();
						int c = sm.getChannel() + 1;
						int d1 = sm.getData1();
						int d2 = sm.getData2();
						if (d1 > 127 || d2 > 127) {
							// Java 파일 리더는 0x80 이상 데이터 바이트를 그대로 넘기기도 한다. 해석하지 않는다.
							invalidDataHere++;
							w.invalidDataBytes++;
							continue;
						}
						ch.add(c);
						ports.add(port);
						if (cmd == ShortMessage.NOTE_ON && d2 > 0) {
							nch.add(c);
							npc.add(pc(port, c));
							int key = (c << 7) | d1;
							long[] prev = active.get(key);
							if (prev != null) {
								w.retriggers++;
								retrigHere++;
								rawNotes.add(new RawNote(trackNo, (int) prev[2], c, d1, (int) prev[1], prev[0], tick));
							}
							active.put(key, new long[] { tick, d2, port });
						} else if (cmd == ShortMessage.NOTE_OFF || cmd == ShortMessage.NOTE_ON) {
							int key = (c << 7) | d1;
							long[] prev = active.remove(key);
							if (prev == null) {
								w.orphanNoteOffs++;
								orphanHere++;
								orphanChannels.add(c);
							} else {
								rawNotes.add(new RawNote(trackNo, (int) prev[2], c, d1, (int) prev[1], prev[0], tick));
							}
						} else if (cmd == ShortMessage.PROGRAM_CHANGE) {
							rawPrograms.add(new RawProgram(tick, trackNo, port, c, d1));
							// MabiIcco 가져오기: 트랙 안 마지막 프로그램 변경이 이긴다. 채널 10이면 드럼.
							importDrum[ti] = (c == 10);
							importProgram[ti] = d1;
						} else if (cmd == ShortMessage.CONTROL_CHANGE) {
							if (d1 == 64) {
								// 서스테인 페달: 악보로는 가져오지 않지만(버린 이벤트로 셈) 음의 실제 울림 길이(pedal_end) 계산에 쓴다
								rawPedals.add(new RawPedal(tick, trackNo, port, c, d2, rawPedals.size()));
							}
							w.drop(String.format("CC#%d(%s)", d1, ccName(d1)));
						} else if (cmd == ShortMessage.PITCH_BEND) {
							w.drop("피치 벤드");
						} else if (cmd == ShortMessage.POLY_PRESSURE) {
							w.drop("폴리 애프터터치");
						} else if (cmd == ShortMessage.CHANNEL_PRESSURE) {
							w.drop("채널 애프터터치");
						} else {
							w.drop(String.format("채널 메시지 0x%02X", cmd));
						}
					} else if (msg instanceof SysexMessage) {
						w.drop("SysEx");
					} else {
						w.drop("알 수 없는 메시지(" + msg.getClass().getSimpleName() + ")");
					}
				}
				// 끝나지 않은 음: 트랙 끝까지
				List<Map.Entry<Integer, long[]>> rest = new ArrayList<>(active.entrySet());
				rest.sort(Comparator.comparingLong(e -> e.getValue()[0]));
				for (Map.Entry<Integer, long[]> e : rest) {
					int c = e.getKey() >> 7;
					int p = e.getKey() & 0x7F;
					rawNotes.add(new RawNote(trackNo, (int) e.getValue()[2], c, p, (int) e.getValue()[1], e.getValue()[0], tr.ticks()));
					unfinishedHere++;
				}
				if (unfinishedHere > 0) {
					w.unfinishedNotes += unfinishedHere;
					w.add("트랙 " + trackNo + ": 끝나지 않은 음 " + unfinishedHere + "개를 트랙 끝(틱 " + tr.ticks()
							+ ")까지로 처리함");
				}
				if (invalidDataHere > 0) {
					w.add("트랙 " + trackNo + ": 데이터 바이트가 0x80 이상인 채널 메시지 " + invalidDataHere
							+ "개 — 파일이 깨진 흔적, 해석하지 않고 버림");
				}
				if (retrigHere > 0) {
					perTrackNoteIssues.add("트랙 " + trackNo + " 재타격 " + retrigHere + "개");
				}
				if (orphanHere > 0) {
					perTrackNoteIssues.add("트랙 " + trackNo + " 짝 없는 note-off " + orphanHere + "개(채널 "
							+ orphanChannels + ")");
				}
			}

			// ---- 템포
			rawTempos.sort(Comparator.comparingLong(RawTempo::tick));
			List<Tempo> tempoTmp = new ArrayList<>();
			for (RawTempo t : rawTempos) {
				double bpm = Math.round(60_000_000.0 / t.us() * 100.0) / 100.0;
				if (!tempoTmp.isEmpty()) {
					Tempo prev = tempoTmp.get(tempoTmp.size() - 1);
					if (prev.srcTick() == t.tick()) {
						if (prev.microsPerQuarter() == t.us()) {
							continue; // 다른 트랙에 같은 템포가 중복으로 있음 — 하나만 둔다
						}
						w.add("틱 " + t.tick() + "에 서로 다른 템포가 둘 이상 있음(트랙 " + prev.sourceTrack() + ": " + prev.bpm()
								+ ", 트랙 " + t.track() + ": " + bpm + ") — 둘 다 목록에 둠, 재생 시에는 뒤의 것이 적용될 가능성이 큼");
					}
				}
				tempoTmp.add(new Tempo(t.tick(), null, t.track(), t.us(), bpm));
			}

			// ---- 박자 → 마디 지도
			rawSigs.sort(Comparator.comparingLong(RawTimeSig::tick));
			List<MeasureMap.Signature> sigs = new ArrayList<>();
			for (int i = 0; i < rawSigs.size(); i++) {
				RawTimeSig s = rawSigs.get(i);
				if (i > 0) {
					RawTimeSig p = rawSigs.get(i - 1);
					if (p.tick() == s.tick() && (p.num() != s.num() || p.den() != s.den())) {
						w.add("틱 " + s.tick() + "에 서로 다른 박자가 둘 이상 있음(트랙 " + p.track() + ": " + p.num() + "/"
								+ p.den() + ", 트랙 " + s.track() + ": " + s.num() + "/" + s.den() + ") — 뒤의 것을 씀");
					}
				}
				sigs.add(new MeasureMap.Signature(s.tick(), s.num(), s.den()));
			}
			measureMapSrc = MeasureMap.of(resolution, sigs);
			measureMap96 = measureMapSrc.to96(resolution);
			for (long t : measureMapSrc.irregularChanges()) {
				w.add("틱 " + t + "의 박자 변경이 마디 중간에 있음 — 그 앞 마디를 짧은 마디로 셈");
			}
			for (MeasureMap.Signature s : measureMapSrc.signatures()) {
				int src = 0;
				for (RawTimeSig r : rawSigs) {
					if (r.tick() == s.tick() && r.num() == s.numerator() && r.den() == s.denominator()) {
						src = r.track();
						break;
					}
				}
				timeSignatures.add(new TimeSignature(s.tick(), pos96(s.tick()), src, s.numerator(), s.denominator(),
						measureMapSrc.measureOf(s.tick())));
			}
			if (rawSigs.isEmpty()) {
				w.add("박자 메타가 없음 — 4/4로 가정");
			}
			measureCount = measureMapSrc.measureOf(Math.max(0, tickLength - 1));

			for (Tempo t : tempoTmp) {
				tempos.add(new Tempo(t.srcTick(), pos96(t.srcTick()), t.sourceTrack(), t.microsPerQuarter(), t.bpm()));
			}
			if (tempos.isEmpty()) {
				w.add("템포 메타가 없음 — 재생기는 120 BPM으로 봄");
			}
			rawKeys.sort(Comparator.comparingLong(RawKey::tick));
			Set<String> seenKeys = new java.util.HashSet<>();
			for (RawKey k : rawKeys) {
				if (!seenKeys.add(k.tick() + ":" + k.sf() + ":" + k.minor())) {
					continue; // 같은 틱·같은 조표가 여러 트랙에 중복 — 처음 것만 둔다
				}
				keySignatures.add(new KeySignature(k.tick(), pos96(k.tick()), k.track(), k.sf(), k.minor(),
						MidiNames.keyName(k.sf(), k.minor())));
			}
			rawMarkers.sort(Comparator.comparingLong(RawMarker::tick));
			for (RawMarker m : rawMarkers) {
				markers.add(new Marker(m.tick(), pos96(m.tick()), m.track(), m.text()));
			}

			// ---- 음표 변환
			for (int ti = 0; ti < trs.length; ti++) {
				notesByTrack.add(new ArrayList<>());
			}
			int[] zeroByTrack = new int[trs.length];
			PedalIndex pedals = new PedalIndex(rawNotes, rawPedals, tickLength);
			for (RawNote r : rawNotes) {
				if (r.end() == r.start()) {
					w.zeroLengthNotes++;
					zeroByTrack[r.track() - 1]++;
				}
				long t96 = MeasureMap.convTick96(r.start(), resolution);
				long l96 = MeasureMap.convTick96(r.end(), resolution) - t96;
				if (l96 < 6) {
					l96 = 6;
				}
				MeasureMap.Position p = measureMap96.position(t96);
				int v15 = Math.max(0, Math.min(15, r.velocity() / 8));
				long srcPedalEnd = pedals.pedalEnd(r);
				long pedalEnd96 = Math.max(MeasureMap.convTick96(srcPedalEnd, resolution), t96 + l96);
				Note n = new Note(r.track(), r.channel(), r.pitch(), MidiNames.pitchName(r.pitch()), r.velocity(), v15,
						r.start(), r.end() - r.start(), t96, l96, p.toString(), p.measure(), srcPedalEnd, pedalEnd96);
				notesByTrack.get(r.track() - 1).add(n);
				allNotes.add(n);
			}
			for (List<Note> l : notesByTrack) {
				l.sort(NOTE_ORDER);
			}
			allNotes.sort(NOTE_ORDER);
			if (w.retriggers > 0) {
				w.add("울리는 중인 같은 음의 재타격 " + w.retriggers + "개 — 앞 음을 재타격 시점에 끝냄");
			}
			if (w.orphanNoteOffs > 0) {
				w.add("짝 없는 note-off " + w.orphanNoteOffs + "개 — 무시함");
			}
			if (w.zeroLengthNotes > 0) {
				List<String> l = new ArrayList<>();
				for (int ti = 0; ti < zeroByTrack.length; ti++) {
					if (zeroByTrack[ti] > 0) {
						l.add("트랙 " + (ti + 1) + " " + zeroByTrack[ti] + "개");
					}
				}
				w.add("길이 0틱인 음 " + w.zeroLengthNotes + "개(" + String.join(", ", l) + ") — 96틱 환산 길이는 6으로 둠");
			}
			if (!perTrackNoteIssues.isEmpty()) {
				w.add("음 짝짓기 상세: " + String.join(", ", perTrackNoteIssues));
			}

			// ---- 프로그램 변경((포트, 채널) 기준, 그리고 포트를 무시한 기준)
			rawPrograms.sort(Comparator.comparingLong(RawProgram::tick).thenComparingInt(RawProgram::track));
			List<TrackInfo.ProgramChange> allPcs = new ArrayList<>();
			List<List<TrackInfo.ProgramChange>> pcByTrack = new ArrayList<>();
			for (int ti = 0; ti < trs.length; ti++) {
				pcByTrack.add(new ArrayList<>());
			}
			for (RawProgram r : rawPrograms) {
				TrackInfo.ProgramChange pc = new TrackInfo.ProgramChange(r.tick(), pos96(r.tick()), r.track(), r.port(),
						r.channel(), r.program(), r.channel() == 10 ? "Drum kit " + r.program() : MidiNames.gmName(r.program()));
				allPcs.add(pc);
				pcByTrack.get(r.track() - 1).add(pc);
			}
			ProgramAnalysis byPort = new ProgramAnalysis(false, allPcs, notePortChans);
			ProgramAnalysis merged = new ProgramAnalysis(true, allPcs, notePortChans);
			byPort.ambiguityMessages.forEach(w::add);

			// ---- 트랙 요약
			long maxNoteEnd = 0;
			int latestTrack = 0;
			for (int ti = 0; ti < trs.length; ti++) {
				int trackNo = ti + 1;
				List<Note> tn = notesByTrack.get(ti);
				Integer lo = null;
				Integer hi = null;
				for (Note n : tn) {
					lo = (lo == null) ? n.pitch() : Math.min(lo, n.pitch());
					hi = (hi == null) ? n.pitch() : Math.max(hi, n.pitch());
					if (n.srcEnd() > maxNoteEnd) {
						maxNoteEnd = n.srcEnd();
						latestTrack = trackNo;
					}
				}
				double ratio = soundingRatio(tn, tickLength);
				boolean nameMeta = names[ti] != null;
				String name = nameMeta ? names[ti] : "Track " + trackNo;
				tracks.add(new TrackInfo(trackNo, name, nameMeta, Collections.unmodifiableSet(portsByTrack.get(ti)),
						Collections.unmodifiableSet(chans.get(ti)), Collections.unmodifiableSet(noteChans.get(ti)),
						List.copyOf(pcByTrack.get(ti)), byPort.channelPrograms(trackNo, tn), byPort.overrides(trackNo),
						merged.channelPrograms(trackNo, tn), merged.overrides(trackNo),
						importProgram[ti], importDrum[ti], noteChans.get(ti).contains(10), tn.size(), lo,
						lo == null ? null : MidiNames.pitchName(lo), hi, hi == null ? null : MidiNames.pitchName(hi),
						tn.isEmpty() ? null : tn.get(0).measure(), tn.isEmpty() ? null : tn.get(tn.size() - 1).measure(),
						ratio, endTicks[ti]));
			}

			// ---- 다른 트랙의 악기를 덮어쓰는 구조
			for (TrackInfo t : tracks) {
				if (!t.overridesTracks().isEmpty()) {
					w.add("트랙 " + t.number() + "(" + t.name() + ")의 프로그램 변경이 다른 트랙 " + t.overridesTracks()
							+ "와 같은 (포트, 채널)에 적용됨 — 악기 덮어쓰기 구조");
				}
			}
			if (anyPortMeta) {
				List<String> extra = new ArrayList<>();
				for (TrackInfo t : tracks) {
					Set<Integer> more = new TreeSet<>(t.overridesTracksIgnoringPorts());
					more.removeAll(t.overridesTracks());
					if (!more.isEmpty()) {
						extra.add("트랙 " + t.number() + "→" + more);
					}
				}
				Set<Integer> ports = new TreeSet<>();
				for (Set<Integer> p : portsByTrack) {
					ports.addAll(p);
				}
				w.add("MIDI 포트 메타(0x21)를 쓰는 파일(포트 " + ports + "). 위 분석은 포트를 구분한 기준이다. "
						+ "포트를 무시하는 재생기(MabiIcco 재생, Java 기본 신스 등)에서는 채널이 겹쳐 "
						+ (extra.isEmpty() ? "추가 덮어쓰기는 없음" : "추가로 덮어씀: " + String.join(", ", extra))
						+ " (TrackInfo.channelProgramsIgnoringPorts 참고)");
			}
			for (TrackInfo t : tracks) {
				if (!t.noteChannels().isEmpty() && t.mabiImportProgram() != null && !t.mabiImportDrum()
						&& !t.channelPrograms().isEmpty()) {
					TrackInfo.ChannelProgram cp = t.channelPrograms().get(0);
					Integer actual = cp.programAtFirstNote();
					if (actual != null && !actual.equals(t.mabiImportProgram())) {
						w.add("트랙 " + t.number() + "(" + t.name() + "): MabiIcco 가져오기 규칙(트랙 안 마지막 프로그램 변경)으로는 "
								+ t.mabiImportProgram() + "(" + MidiNames.gmName(t.mabiImportProgram())
								+ ")이 되지만, 실제 음 채널(포트 " + cp.port() + " 채널 " + cp.channel() + ")의 첫 음 시점 프로그램은 "
								+ actual + "(" + MidiNames.gmName(actual) + ")");
					}
				}
			}

			// ---- 비정상 길이
			double secs = microsecondLength / 1_000_000.0;
			if (secs >= Warnings.ABNORMAL_LENGTH_SECONDS) {
				w.abnormalLength = true;
				StringBuilder sb = new StringBuilder();
				sb.append(String.format("곡 길이가 비정상적으로 김: %.1f초(%d마디)", secs, measureCount));
				long latestEndTick = 0;
				int latestEndTrack = 0;
				for (int ti = 0; ti < endTicks.length; ti++) {
					if (endTicks[ti] > latestEndTick) {
						latestEndTick = endTicks[ti];
						latestEndTrack = ti + 1;
					}
				}
				sb.append(String.format(" — 가장 늦게 끝나는 트랙 %d(끝 틱 %d, 마디 %d)", latestEndTrack, latestEndTick,
						measureMapSrc.measureOf(Math.max(0, latestEndTick - 1))));
				if (latestTrack > 0) {
					sb.append(String.format(", 가장 늦은 음은 트랙 %d(끝 마디 %d)", latestTrack,
							measureMapSrc.measureOf(Math.max(0, maxNoteEnd - 1))));
				}
				List<String> farTracks = new ArrayList<>();
				for (int ti = 0; ti < endTicks.length; ti++) {
					double s = microsAt(tempos, endTicks[ti], resolution) / 1_000_000.0;
					if (s >= Warnings.ABNORMAL_LENGTH_SECONDS) {
						farTracks.add(String.valueOf(ti + 1));
					}
				}
				if (!farTracks.isEmpty()) {
					sb.append(" / 2시간 이후까지 이어지는 트랙: ").append(String.join(", ", farTracks));
				}
				w.add(sb.toString());
			}

			warnings = w.build();
			return new ReferenceMidi(this);
		}

		/** 트랙의 첫 포트 메타(0x21) 값. 없으면 -1. */
		private static int firstPort(Track tr) {
			for (int i = 0; i < tr.size(); i++) {
				if (tr.get(i).getMessage() instanceof MetaMessage mm && mm.getType() == 0x21 && mm.getData().length >= 1) {
					return mm.getData()[0] & 0xFF;
				}
			}
			return -1;
		}

		private String pos96(long srcTick) {
			return measureMap96.position(MeasureMap.convTick96(srcTick, resolution)).toString();
		}

		private void handleMeta(MetaMessage mm, long tick, int trackNo, int ti, String[] names, List<RawTimeSig> rawSigs,
				List<RawTempo> rawTempos, List<RawKey> rawKeys, List<RawMarker> rawMarkers) {
			int type = mm.getType();
			byte[] d = mm.getData();
			switch (type) {
			case 0x51 -> {
				if (d.length == 3) {
					int us = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
					if (us > 0) {
						rawTempos.add(new RawTempo(tick, trackNo, us));
					} else {
						w.add("트랙 " + trackNo + " 틱 " + tick + ": 템포 0µs — 무시");
					}
				} else {
					w.add("트랙 " + trackNo + " 틱 " + tick + ": 템포 메타 길이가 " + d.length + "바이트 — 무시");
				}
			}
			case 0x58 -> {
				if (d.length >= 2 && (d[0] & 0xFF) > 0 && (d[1] & 0xFF) < 8) {
					rawSigs.add(new RawTimeSig(tick, trackNo, d[0] & 0xFF, 1 << (d[1] & 0xFF)));
				} else {
					w.add("트랙 " + trackNo + " 틱 " + tick + ": 해석할 수 없는 박자 메타 — 무시");
				}
			}
			case 0x59 -> {
				if (d.length >= 2) {
					rawKeys.add(new RawKey(tick, trackNo, d[0], d[1] != 0));
				}
			}
			case 0x06 -> rawMarkers.add(new RawMarker(tick, trackNo, decodeText(d)));
			case 0x03 -> {
				if (names[ti] == null) {
					String s = decodeText(d).strip();
					if (!s.isEmpty()) {
						names[ti] = s;
					}
				}
			}
			case 0x2F -> {
				// End of Track
			}
			default -> w.drop(String.format("메타 0x%02X(%s)", type, metaName(type)));
			}
		}

		private static double soundingRatio(List<Note> notes, long total) {
			if (total <= 0 || notes.isEmpty()) {
				return 0;
			}
			List<long[]> iv = new ArrayList<>();
			for (Note n : notes) {
				iv.add(new long[] { n.srcTick(), n.srcEnd() });
			}
			iv.sort(Comparator.comparingLong(a -> a[0]));
			long sum = 0;
			long cs = iv.get(0)[0];
			long ce = iv.get(0)[1];
			for (int i = 1; i < iv.size(); i++) {
				long[] a = iv.get(i);
				if (a[0] <= ce) {
					ce = Math.max(ce, a[1]);
				} else {
					sum += ce - cs;
					cs = a[0];
					ce = a[1];
				}
			}
			sum += ce - cs;
			return Math.min(1.0, (double) sum / total);
		}
	}

	/** 메타 텍스트 해독: UTF-8 → Shift_JIS(MS932) → CP949 → ISO-8859-1 순으로 엄격하게 시도. */
	static String decodeText(byte[] data) {
		int len = data.length;
		while (len > 0 && data[len - 1] == 0) {
			len--;
		}
		ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
		for (String cs : new String[] { "UTF-8", "MS932", "x-windows-949" }) {
			try {
				if (!Charset.isSupported(cs)) {
					continue;
				}
				CharBuffer cb = Charset.forName(cs).newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(buf.duplicate());
				return cb.toString();
			} catch (CharacterCodingException e) {
				// 다음 문자셋
			}
		}
		return new String(data, 0, len, StandardCharsets.ISO_8859_1);
	}

	private static String metaName(int type) {
		return switch (type) {
		case 0x00 -> "시퀀스 번호";
		case 0x01 -> "텍스트";
		case 0x02 -> "저작권";
		case 0x04 -> "악기 이름";
		case 0x05 -> "가사";
		case 0x07 -> "큐 포인트";
		case 0x08 -> "프로그램 이름";
		case 0x09 -> "장치 이름";
		case 0x20 -> "채널 접두";
		case 0x21 -> "포트";
		case 0x54 -> "SMPTE 오프셋";
		case 0x7F -> "시퀀서 전용";
		default -> "기타";
		};
	}

	private static String ccName(int cc) {
		return switch (cc) {
		case 0 -> "뱅크 MSB";
		case 1 -> "모듈레이션";
		case 6 -> "데이터 엔트리";
		case 7 -> "볼륨";
		case 10 -> "팬";
		case 11 -> "익스프레션";
		case 32 -> "뱅크 LSB";
		case 64 -> "서스테인";
		case 91 -> "리버브";
		case 93 -> "코러스";
		case 98, 99 -> "NRPN";
		case 100, 101 -> "RPN";
		case 120 -> "올 사운드 오프";
		case 121 -> "리셋 올 컨트롤러";
		case 123 -> "올 노트 오프";
		default -> "기타";
		};
	}
}
