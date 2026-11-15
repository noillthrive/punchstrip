package mabibridge.engine;

import java.util.ArrayList;
import java.util.List;

import jp.fourthline.mmlTools.MMLExceptionList;
import jp.fourthline.mmlTools.MMLNoteEvent;
import jp.fourthline.mmlTools.MMLScore;
import jp.fourthline.mmlTools.MMLTrack;
import jp.fourthline.mmlTools.MMLVerifyException;

/**
 * 열린 악보를 건드리지 않고 MML을 다시 만들어 보기 위한 트랙 하나짜리 사본.
 * {@code MMLScore.clone()}(상태 바이트로 복제)에서 다른 트랙을 빼므로 트랙 설정(악기, 노래 악기, 시작 오프셋 등)과
 * 악보 전역 템포·박자가 그대로 남는다. 트랙의 MML은 그 트랙의 음과 템포만으로 만들어지므로 글자 수가 원래 악보와 같다
 * ({@code TransformTest}가 확인).
 */
final class TrackCopy {

	private TrackCopy() {}

	/** 트랙 index0(0부터)만 남긴 사본. 생성은 하지 않는다. */
	static MMLScore singleTrack(MMLScore src, int index0) {
		MMLScore c = src.clone();
		for (int i = c.getTrackCount() - 1; i >= 0; i--) {
			if (i != index0) {
				c.removeTrack(i);
			}
		}
		return c;
	}

	/** 파트별 음표의 깊은 사본(엔진 음표는 길이·세기를 바꿀 수 있으므로). */
	static List<List<MMLNoteEvent>> copyNotes(MMLTrack t) {
		List<List<MMLNoteEvent>> l = new ArrayList<>();
		for (Part p : Part.values()) {
			List<MMLNoteEvent> c = new ArrayList<>();
			for (MMLNoteEvent e : t.getMMLEventAtIndex(p.index()).getMMLNoteEventList()) {
				c.add(e.clone());
			}
			l.add(c);
		}
		return l;
	}

	/** 트랙의 음표를 notes의 깊은 사본으로 바꾼다. */
	static void putNotes(MMLTrack t, List<List<MMLNoteEvent>> notes) {
		for (Part p : Part.values()) {
			List<MMLNoteEvent> dst = t.getMMLEventAtIndex(p.index()).getMMLNoteEventList();
			dst.clear();
			for (MMLNoteEvent e : notes.get(p.index())) {
				dst.add(e.clone());
			}
		}
	}

	/** 사본 악보를 생성한다. 실패하면 MML_GENERATE_FAILED. */
	static void generate(MMLScore s, String where) {
		try {
			s.generateAll();
		} catch (MMLExceptionList | MMLVerifyException e) {
			throw new EngineException(EngineException.MML_GENERATE_FAILED, where + ": 사본의 MML 생성 실패: " + e,
					EngineException.details("where", where), e);
		}
	}

	/** 생성된 트랙의 실제 MML 파트 글자 수 {melody, chord1, chord2, song}(score_mml의 chars와 같음). */
	static int[] mmlChars(MMLTrack t) {
		t.mmlRank(); // 노래 제외 설정 반영(Project.mmlOf와 같음)
		String[] parts = Project.splitMml(t.getMabiMML());
		return new int[] { parts[0].length(), parts[1].length(), parts[2].length(), parts[3].length() };
	}
}
