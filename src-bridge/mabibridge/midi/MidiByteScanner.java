package mabibridge.midi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 표준 MIDI 파일(SMF)을 바이트 단위로 훑어 구조 오류의 위치를 찾는 스캐너.
 *
 * <p>{@code javax.sound.midi}는 파일이 깨졌을 때 "어디서" 깨졌는지 알려 주지 않으므로,
 * {@code MThd}/{@code MTrk} 청크를 순회하며 가변 길이 수(VLQ)와 러닝 스테이터스 규칙대로
 * 이벤트를 따라가, 규칙에 어긋나는 첫 지점을 트랙 번호·파일 오프셋·직전 이벤트 틱으로 보고한다.
 * 이 클래스는 진단 전용이며 음표를 해석하지 않는다.</p>
 */
public final class MidiByteScanner {

	/**
	 * 발견한 구조 문제 하나.
	 *
	 * @param trackNumber MTrk 청크 순번(1부터). 헤더 문제면 0
	 * @param fileOffset 문제가 된 바이트의 파일 오프셋(0부터)
	 * @param tickBefore 문제 직전까지 누적된 이벤트 틱(원본 해상도). 모르면 -1
	 * @param description 사람이 읽는 설명
	 */
	public record Problem(int trackNumber, long fileOffset, long tickBefore, String description) {
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			if (trackNumber > 0) {
				sb.append("트랙 ").append(trackNumber);
			} else {
				sb.append("헤더");
			}
			sb.append(", 파일 오프셋 0x").append(Long.toHexString(fileOffset).toUpperCase())
				.append(" (").append(fileOffset).append(")");
			if (tickBefore >= 0) {
				sb.append(", 직전 이벤트 틱 ").append(tickBefore);
			}
			sb.append(": ").append(description);
			return sb.toString();
		}
	}

	/** 스캔 중 본 박자 메타 이벤트(실패 위치를 마디로 바꾸는 데 쓴다). */
	public record ScannedTimeSignature(int trackNumber, long tick, int numerator, int denominator) {}

	/**
	 * 스캔 결과.
	 *
	 * @param format 헤더의 포맷(읽지 못했으면 -1)
	 * @param declaredTracks 헤더에 적힌 트랙 수(읽지 못했으면 -1)
	 * @param division 헤더의 division 값(읽지 못했으면 -1)
	 * @param trackChunks 실제로 찾은 MTrk 청크 수
	 * @param problems 발견한 문제 목록(파일 순서)
	 * @param timeSignatures 문제가 나기 전까지 본 박자 이벤트
	 */
	public record Result(int format, int declaredTracks, int division, int trackChunks,
			List<Problem> problems, List<ScannedTimeSignature> timeSignatures) {
		public boolean ok() {
			return problems.isEmpty();
		}

		/** 첫 번째 문제(없으면 null). */
		public Problem firstProblem() {
			return problems.isEmpty() ? null : problems.get(0);
		}
	}

	private MidiByteScanner() {}

	/** 파일을 읽어 스캔한다. 읽기 자체가 실패하면 IOException. */
	public static Result scan(Path file) throws IOException {
		return scan(Files.readAllBytes(file));
	}

	/** 바이트 배열을 스캔한다. 예외를 던지지 않는다. */
	public static Result scan(byte[] data) {
		List<Problem> problems = new ArrayList<>();
		List<ScannedTimeSignature> sigs = new ArrayList<>();
		int format = -1;
		int declaredTracks = -1;
		int division = -1;
		int trackChunks = 0;

		int pos = 0;
		if (data.length < 14 || !chunkIdAt(data, 0, "MThd")) {
			problems.add(new Problem(0, 0, -1, "MThd 헤더가 없음"));
			return new Result(format, declaredTracks, division, 0, problems, sigs);
		}
		long headerLen = u32(data, 4);
		if (headerLen < 6 || 8 + headerLen > data.length) {
			problems.add(new Problem(0, 4, -1, "MThd 길이가 비정상: " + headerLen));
			return new Result(format, declaredTracks, division, 0, problems, sigs);
		}
		format = u16(data, 8);
		declaredTracks = u16(data, 10);
		division = u16(data, 12);
		if (format > 2) {
			problems.add(new Problem(0, 8, -1, "알 수 없는 포맷 " + format));
		}
		pos = (int) (8 + headerLen);

		while (pos < data.length) {
			if (pos + 8 > data.length) {
				problems.add(new Problem(trackChunks + 1, pos, -1,
						"청크 헤더가 잘림(남은 바이트 " + (data.length - pos) + ")"));
				break;
			}
			long len = u32(data, pos + 4);
			boolean isTrack = chunkIdAt(data, pos, "MTrk");
			if (!isTrack) {
				// 알 수 없는 청크는 SMF 규격상 건너뛰어도 된다. 다만 글자가 아니면 구조가 어긋난 것.
				if (!isPrintableId(data, pos)) {
					problems.add(new Problem(trackChunks + 1, pos, -1,
							"청크 ID가 비정상(바이트 " + hex(data, pos, 4) + ") — 앞 트랙 청크 길이가 틀렸을 수 있음"));
					break;
				}
				pos += 8 + (int) Math.min(len, data.length);
				continue;
			}
			trackChunks++;
			int start = pos + 8;
			long end = start + len;
			if (end > data.length) {
				problems.add(new Problem(trackChunks, pos + 4, -1,
						"MTrk 길이 " + len + "이(가) 파일 끝을 넘음(남은 바이트 " + (data.length - start) + ")"));
				end = data.length;
			}
			scanTrack(data, trackChunks, start, (int) end, problems, sigs);
			if (!problems.isEmpty()) {
				break; // 첫 문제만 신뢰할 수 있다. 그 뒤는 연쇄 오류일 가능성이 크다.
			}
			pos = (int) end;
		}
		if (problems.isEmpty() && declaredTracks >= 0 && trackChunks != declaredTracks) {
			problems.add(new Problem(0, 10, -1,
					"헤더의 트랙 수 " + declaredTracks + "와 실제 MTrk 청크 수 " + trackChunks + "가 다름"));
		}
		return new Result(format, declaredTracks, division, trackChunks,
				Collections.unmodifiableList(problems), Collections.unmodifiableList(sigs));
	}

	private static void scanTrack(byte[] d, int track, int start, int end,
			List<Problem> problems, List<ScannedTimeSignature> sigs) {
		int p = start;
		long tick = 0;
		int running = -1;
		boolean sawEnd = false;
		while (p < end) {
			if (sawEnd) {
				problems.add(new Problem(track, p, tick, "End of Track 뒤에 데이터가 더 있음"));
				return;
			}
			// delta time (VLQ, 최대 4바이트)
			long delta = 0;
			int n = 0;
			int vlqStart = p;
			while (true) {
				if (p >= end) {
					problems.add(new Problem(track, vlqStart, tick, "델타 타임 도중에 트랙이 끝남"));
					return;
				}
				int b = d[p++] & 0xFF;
				delta = (delta << 7) | (b & 0x7F);
				n++;
				if ((b & 0x80) == 0) {
					break;
				}
				if (n >= 4) {
					problems.add(new Problem(track, vlqStart, tick, "델타 타임이 4바이트를 넘음"));
					return;
				}
			}
			long tickBefore = tick;
			tick += delta;
			if (p >= end) {
				problems.add(new Problem(track, p, tickBefore, "이벤트 없이 트랙이 끝남"));
				return;
			}
			int statusPos = p;
			int b = d[p] & 0xFF;
			int status;
			if (b >= 0x80) {
				status = b;
				p++;
			} else {
				if (running < 0) {
					problems.add(new Problem(track, statusPos, tickBefore,
							"상태 바이트가 와야 할 자리에 데이터 바이트 0x" + hex2(b) + "가 있고 러닝 스테이터스도 없음"));
					return;
				}
				status = running;
			}

			if (status == 0xFF) {
				if (p >= end) {
					problems.add(new Problem(track, p, tickBefore, "메타 이벤트 종류 바이트가 잘림"));
					return;
				}
				int type = d[p++] & 0xFF;
				long[] vl = readVlq(d, p, end);
				if (vl == null) {
					problems.add(new Problem(track, p, tickBefore, "메타 이벤트 길이(VLQ)가 비정상"));
					return;
				}
				p = (int) vl[1];
				long mlen = vl[0];
				if (p + mlen > end) {
					problems.add(new Problem(track, statusPos, tickBefore,
							"메타 이벤트(0x" + hex2(type) + ") 길이 " + mlen + "가 트랙 끝을 넘음"));
					return;
				}
				if (type == 0x58 && mlen >= 2) {
					int num = d[p] & 0xFF;
					int pow = d[p + 1] & 0xFF;
					if (pow < 8) {
						sigs.add(new ScannedTimeSignature(track, tick, num, 1 << pow));
					}
				}
				if (type == 0x2F) {
					sawEnd = true;
				}
				p += (int) mlen;
			} else if (status == 0xF0 || status == 0xF7) {
				long[] vl = readVlq(d, p, end);
				if (vl == null) {
					problems.add(new Problem(track, p, tickBefore, "SysEx 길이(VLQ)가 비정상"));
					return;
				}
				p = (int) vl[1];
				if (p + vl[0] > end) {
					problems.add(new Problem(track, statusPos, tickBefore, "SysEx 길이 " + vl[0] + "가 트랙 끝을 넘음"));
					return;
				}
				p += (int) vl[0];
			} else if (status >= 0xF0) {
				problems.add(new Problem(track, statusPos, tickBefore,
						"SMF 안에서 쓸 수 없는 상태 바이트 0x" + hex2(status)));
				return;
			} else {
				running = status;
				int cmd = status & 0xF0;
				int dataBytes = (cmd == 0xC0 || cmd == 0xD0) ? 1 : 2;
				for (int i = 0; i < dataBytes; i++) {
					if (p >= end) {
						problems.add(new Problem(track, p, tickBefore,
								"채널 메시지(0x" + hex2(status) + ") 데이터 도중에 트랙이 끝남"));
						return;
					}
					int db = d[p] & 0xFF;
					if (db >= 0x80) {
						problems.add(new Problem(track, p, tickBefore,
								"채널 메시지(0x" + hex2(status) + ", 채널 " + ((status & 0x0F) + 1) + ")의 "
								+ (i + 1) + "번째 데이터 바이트가 0x" + hex2(db) + "(0x80 이상) — 데이터 바이트는 0x7F 이하여야 함"));
						return;
					}
					p++;
				}
			}
		}
		if (!sawEnd) {
			problems.add(new Problem(track, end, tick, "End of Track(FF 2F 00) 없이 트랙 청크가 끝남"));
		}
	}

	/** VLQ를 읽어 {값, 다음 위치}를 돌려준다. 4바이트를 넘거나 잘리면 null. */
	private static long[] readVlq(byte[] d, int p, int end) {
		long v = 0;
		for (int n = 0; n < 4; n++) {
			if (p >= end) {
				return null;
			}
			int b = d[p++] & 0xFF;
			v = (v << 7) | (b & 0x7F);
			if ((b & 0x80) == 0) {
				return new long[] { v, p };
			}
		}
		return null;
	}

	private static boolean chunkIdAt(byte[] d, int p, String id) {
		if (p + 4 > d.length) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			if (d[p + i] != (byte) id.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isPrintableId(byte[] d, int p) {
		for (int i = 0; i < 4; i++) {
			int c = d[p + i] & 0xFF;
			if (c < 0x20 || c > 0x7E) {
				return false;
			}
		}
		return true;
	}

	private static long u32(byte[] d, int p) {
		return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
	}

	private static int u16(byte[] d, int p) {
		return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
	}

	private static String hex2(int v) {
		return String.format("%02X", v);
	}

	private static String hex(byte[] d, int p, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n && p + i < d.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(hex2(d[p + i] & 0xFF));
		}
		return sb.toString();
	}
}
