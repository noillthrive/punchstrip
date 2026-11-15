"""파트(트랙) 사이 밸런스(balance).

split 이 track/part 인 메타의 files[] 마다 마디별 RMS(dBFS, 모노)·K-가중 소리 크기(k_db)·스펙트럼 중심을 구한다.
기준 믹스: mix_wav 가 있으면 그 파일, 없으면 파트 신호의 합.
K-가중(ITU-R BS.1770): 고역 쉘프(약 1.7kHz 위 +4dB) + RLB 고역통과(약 38Hz) 2단 biquad. 사람 귀가 저음을 덜 크게
듣는 것을 반영해, RMS 로는 크게 나오는 킥·저음 파트를 작게 잰다. k_db = -0.691 + 10*log10(K-가중 신호 평균 제곱)
(모노, 게이팅 없음; 1kHz 사인파에서 rms_dbfs 와 거의 같다). 묻힘 판정에 쓰는 값은 weighting("k" 기본 / "rms").
쉬는 파트 판정(silence_db)과 저음 뭉침은 그대로 RMS 로 잰다.
경고
- buried(묻힘): 그 마디에서 쉬지 않는 파트(마디 RMS 가 silence_db(기본 -60dBFS) 이상) 중 믹스보다 기준 이상
  작은 파트. 기준은 buried_mode 로 고른다.
  - "relative"(기본): 쉬지 않는 파트 수 N 에 따라 -(10*log10(N) + buried_margin_db(기본 12)) dB
    (4파트 -18, 8파트 -21, 13파트 -23). 비슷한 크기의 파트 N개는 각각 믹스보다 약 10*log10(N) dB 작기 때문.
  - "fixed": 예전 방식, -buried_db(기본 12) dB 고정.
  무음높이 파트(드럼 27·북·심벌즈 65~68·type D/M)는 buried_include_unpitched=False(기본)면 경고하지 않는다
  (RMS·rel_mix_db 는 그대로 적고 unpitched: true 로 표시). N 에는 센다(믹스에 들어 있으므로). 실로폰 77(type K)과
  핸드벨 69~76(type P, 고정음)은 음높이 있는 파트다(예전에는 타악기로 빼서 리드를 덮는 실로폰을 놓쳤다).
- low_crowding(저음 뭉침): 음높이 있는 파트(드럼·타악기·노래 제외)만 세어, 같은 박에서 low_hz(기본 200Hz) 아래
  에너지가 low_min_db(기본 -40dBFS) 이상이고 그중 저음이 가장 센 파트와 low_rel_db(기본 6dB) 안에 드는 파트가
  low_min_parts(기본 3)개 이상
- lead_margin(리드 여유, lead_part 를 줄 때만): 마디마다 리드 K-가중 소리 크기 − 같은 마디에서 가장 센 다른
  음높이 파트(드럼·북·심벌즈 제외, 노래·실로폰·핸드벨 포함)의 K-가중 소리 크기. 구간(sections, 없으면 고른 범위 전체)마다
  평균·중앙값·최소와 margin_band(기본 [2.0, 3.5] dB) 안인지(in_band). 아래면 "lead_margin_low" 경고(인게임에서 리드가
  묻힐 위험 — 인게임 볼륨은 거리로 정해지고 팬은 없으므로 모노 합으로 잰다).

보정 근거: 사람이 만든 편곡 6개의 렌더 통계.
"""
from __future__ import annotations

import json
import logging
import math
from collections import Counter
from pathlib import Path

import numpy as np

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.balance")

BURIED_MODES = ("relative", "fixed")
WEIGHTINGS = ("k", "rms")
# ITU-R BS.1770 K-가중 필터의 아날로그 원형 값(libebur128 과 같은 값). 48kHz 에서 BS.1770 표의 계수와 같다.
K_SHELF_HZ = 1681.974450955533
K_SHELF_GAIN_DB = 3.999843853973347
K_SHELF_Q = 0.7071752369554196
K_SHELF_VB_EXP = 0.4996667741545416
K_HP_HZ = 38.13547087602444
K_HP_Q = 0.5003270373238773
K_OFFSET_DB = -0.691  # BS.1770 의 상수(1kHz 에서 K-가중 이득을 상쇄)


def k_weighting_sos(fs: float):
    """K-가중 2단 biquad(sos, 2x6). 아날로그 원형을 미리 휜 주파수로 scipy.signal.bilinear 로 바꾼다.

    1단 고역 쉘프 H(s) = (Vh s^2 + Vb s/Q + 1) / (s^2 + s/Q + 1), 2단 고역통과 s^2 / (s^2 + s/Q + 1)
    (s 는 W = 2 fs tan(pi f0 / fs) 로 정규화). 2단 분자는 BS.1770 처럼 [1, -2, 1].
    """
    import numpy as np
    from scipy.signal import bilinear

    vh = 10 ** (K_SHELF_GAIN_DB / 20.0)
    vb = vh ** K_SHELF_VB_EXP
    w = 2 * fs * np.tan(np.pi * K_SHELF_HZ / fs)
    b1, a1 = bilinear([vh / w ** 2, vb / (K_SHELF_Q * w), 1.0], [1 / w ** 2, 1 / (K_SHELF_Q * w), 1.0], fs)
    w2 = 2 * fs * np.tan(np.pi * K_HP_HZ / fs)
    _, a2 = bilinear([1 / w2 ** 2, 0.0, 0.0], [1 / w2 ** 2, 1 / (K_HP_Q * w2), 1.0], fs)
    return np.vstack([np.r_[b1 / a1[0], a1 / a1[0]], np.r_[[1.0, -2.0, 1.0], a2 / a2[0]]])


def k_weight(y, fs: float):
    """K-가중 필터를 건 신호(앞으로만, BS.1770 처럼)."""
    import numpy as np
    from scipy.signal import sosfilt

    y = np.asarray(y, dtype=np.float64)
    return sosfilt(k_weighting_sos(fs), y) if y.size else y


def k_db(yk, sr: int, t0: float, t1: float) -> float:
    """K-가중 신호 구간의 소리 크기 -0.691 + 10*log10(평균 제곱), 바닥 -120."""
    import numpy as np

    a, b = int(t0 * sr), int(t1 * sr)
    seg = yk[a:max(a + 1, b)]
    if seg.size == 0:
        return -120.0
    ms = float(np.mean(seg ** 2))
    if ms <= 0:
        return -120.0
    return max(-120.0, K_OFFSET_DB + 10.0 * math.log10(ms))
# 음높이 없는 악기(MabiIcco InstType과 브리지 sounding_pitch.properties의 unpitched): D 드럼, M 타악기(무작위),
# 드럼 27, 스네어드럼·큰북·작은북·심벌즈 65~68. P 타입이라도 핸드벨 69~76은 고정음(음높이 있음), K 실로폰 77은 +2옥타브.
UNPITCHED_TYPES = {"D", "M"}
VOCAL_TYPES = {"V", "C"}
DRUM_PROGRAM = 27
UNPITCHED_PROGRAMS = {27, 65, 66, 67, 68}
PERCUSSION_PROGRAMS = range(65, 69)  # 65~68: 음높이 없는 타악기(예전에는 65~76, 핸드벨까지 뺐다)
PITCHED_PERCUSSION_PROGRAMS = range(69, 78)  # 핸드벨 69~76(고정음), 실로폰 77(type K)
MARGIN_BAND = (2.0, 3.5)


def _unpitched_one(ty, prog) -> bool:
    """트랙 하나가 음높이 없는 악기인가: program 27·65~68, type D/M, 또는 핸드벨이 아닌 type P."""
    if isinstance(prog, int) and prog in UNPITCHED_PROGRAMS:
        return True
    if ty in UNPITCHED_TYPES:
        return True
    return ty == "P" and not (isinstance(prog, int) and prog in PITCHED_PERCUSSION_PROGRAMS)


def buried_threshold(mode: str, n_active: int, buried_db: float, margin_db: float) -> float:
    """묻힘 기준(dB, 음수). rel_mix_db 가 이 값 이하이면 묻힘."""
    if mode == "fixed":
        return -buried_db
    return -(10.0 * math.log10(max(1, n_active)) + margin_db)


def _track_info(meta: dict, meta_path: Path, f: dict, wav: Path) -> list[dict]:
    """파일에 든 트랙의 메타(tracks[] 항목). 렌더 메타에 없으면 WAV 옆 같은 이름 .json 메타에서 찾는다."""
    want = f.get("tracks")
    if not isinstance(want, list):
        return []
    found = {t.get("track"): t for t in meta.get("tracks") or [] if isinstance(t, dict)}
    if any(t not in found for t in want):
        side = wav.with_suffix(".json")
        if side.is_file() and side.resolve() != meta_path.resolve():
            try:
                sm = json.loads(side.read_text(encoding="utf-8"))
                for t in (sm.get("tracks") or [] if isinstance(sm, dict) else []):
                    if isinstance(t, dict) and t.get("track") in want:
                        found.setdefault(t.get("track"), t)
            except (OSError, ValueError) as e:
                log.info("옆 메타를 읽지 못함 %s: %s", side, e)
    return [found[t] for t in want if t in found]


def is_unpitched(infos: list[dict]) -> bool:
    """드럼(27)·타악기(65~68)·type D/M 트랙이 들어 있으면 True. 노래(V/C)·실로폰(77, K)·핸드벨(69~76)은 False."""
    return any(_unpitched_one(t.get("instrument_type"), t.get("program")) for t in infos)


def pitched_kind(infos: list[dict], parts) -> tuple[bool | None, str]:
    """(음높이 있는 파트인지, 이유). 트랙 정보가 없으면 (None, ...) — 저음 뭉침에서는 센다."""
    if isinstance(parts, list) and parts and all(p == "song" for p in parts):
        return False, "노래 파트"
    if not infos:
        return None, "트랙 정보 없음(음높이 있는 것으로 셈)"
    for t in infos:
        ty, prog = t.get("instrument_type"), t.get("program")
        if _unpitched_one(ty, prog):
            return False, f"드럼·타악기(program {prog}, type {ty})"
        if ty in VOCAL_TYPES:
            return False, f"노래(program {prog}, type {ty})"
    return True, "음높이 있음"


def _rms_db(y: np.ndarray, sr: int, t0: float, t1: float) -> float:
    a, b = int(t0 * sr), int(t1 * sr)
    s = y[a:max(a + 1, b)]
    if s.size == 0:
        return -120.0
    return io.db(float(np.sqrt(np.mean(s.astype(np.float64) ** 2))))


def _label(meta: dict, f: dict, idx: int) -> str:
    names = {t.get("track"): t.get("name") for t in meta.get("tracks") or [] if isinstance(t, dict)}
    tr = f.get("tracks")
    parts = f.get("parts")
    if isinstance(tr, list) and len(tr) == 1:
        nm = names.get(tr[0])
        base = f"T{tr[0]}" + (f" {nm}" if nm else "")
    elif isinstance(tr, list) and tr:
        base = "T" + "+".join(str(t) for t in tr)
    else:
        base = f"파일{idx + 1}"
    if parts and parts != "all":
        base += "/" + (",".join(parts) if isinstance(parts, list) else str(parts))
    return base


def resolve_files(meta: dict, meta_path: Path) -> list[dict]:
    split = meta.get("split")
    if split not in ("track", "part"):
        raise EarError("BAD_ARGUMENT", "balance에는 split이 'track' 또는 'part'인 메타가 필요합니다.",
                       {"split": split})
    files = meta.get("files")
    if not isinstance(files, list) or not files:
        raise EarError("META_INVALID", "메타에 'files' 목록이 없습니다.", {"path": str(meta_path)})
    out = []
    for i, f in enumerate(files):
        if not isinstance(f, dict) or not isinstance(f.get("path"), str):
            raise EarError("META_INVALID", f"files[{i}]에 path가 없습니다.", {"index": i})
        p = Path(f["path"])
        if not p.is_absolute():
            p = meta_path.parent / p
        if not p.is_file():
            raise EarError("FILE_NOT_FOUND", f"파트 파일이 없습니다: {p}", {"index": i, "path": str(p)})
        infos = _track_info(meta, meta_path, f, p)
        pitched, why = pitched_kind(infos, f.get("parts"))
        out.append({"label": _label(meta, f, i), "path": p, "tracks": f.get("tracks"), "parts": f.get("parts"),
                    "programs": [t.get("program") for t in infos],
                    "instrument_types": [t.get("instrument_type") for t in infos],
                    "pitched": pitched, "pitched_reason": why, "unpitched": is_unpitched(infos)})
    labels = [o["label"] for o in out]
    for o in out:  # 같은 이름이 있으면 번호를 붙인다
        if labels.count(o["label"]) > 1:
            o["label"] += f"#{out.index(o) + 1}"
    return out


def resolve_lead(files: list[dict], lead_part) -> list[int]:
    """lead_part -> files 번호 목록. 정수 = 트랙 번호, 문자열 = 라벨 그대로("T1 만돌린", "T1/melody") 또는 "T1"·"1"
    (그 트랙의 파일 모두, split part면 파트 합)·"T1/melody"(그 트랙의 그 파트들). 못 찾으면 BAD_ARGUMENT."""
    import re

    labels = [f["label"] for f in files]
    if isinstance(lead_part, bool) or not isinstance(lead_part, (int, str)):
        raise EarError("BAD_ARGUMENT", "'lead_part'는 트랙 번호(정수) 또는 파트 라벨(문자열)입니다.",
                       {"lead_part": lead_part, "labels": labels})
    if isinstance(lead_part, str):
        lp = lead_part.strip()
        if lp in labels:
            return [labels.index(lp)]
        m = re.match(r"^[Tt]?(\d+)(?:/([a-z0-9,]+))?$", lp)
        if not m:
            hit = [i for i, f in enumerate(files) if f["label"].split("/")[0].endswith(" " + lp)]
            if hit:
                return hit
            raise EarError("BAD_ARGUMENT", f"lead_part '{lead_part}'에 맞는 파트가 없습니다.",
                           {"lead_part": lead_part, "labels": labels})
        track, parts = int(m.group(1)), (m.group(2).split(",") if m.group(2) else None)
    else:
        track, parts = lead_part, None
    out = []
    for i, f in enumerate(files):
        if f["tracks"] != [track]:
            continue
        if parts is not None:  # "T1/melody": only per-part files (split part) whose parts are all in the request
            fp = f["parts"]
            if not isinstance(fp, list) or not set(fp) <= set(parts):
                continue
        out.append(i)
    if not out:
        raise EarError("BAD_ARGUMENT", f"lead_part {lead_part!r}에 맞는 파트가 없습니다(split 파일의 트랙·파트로 찾음).",
                       {"lead_part": lead_part, "labels": labels})
    return out


def check_margin_band(band) -> tuple[float, float]:
    if band is None:
        return MARGIN_BAND
    if (not isinstance(band, (list, tuple)) or len(band) != 2
            or any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in band)
            or not -60 <= band[0] < band[1] <= 60):
        raise EarError("BAD_ARGUMENT", "'margin_band'는 [최소, 최대] dB(-60~60, 최소 < 최대)입니다.", {"margin_band": band})
    return float(band[0]), float(band[1])


def check_lead_sections(sections, measures: list[dict]) -> list[dict]:
    """[{name, from_measure, to_measure}] 또는 [{name, start_sec, end_sec}] -> [{name, measures:[번호]}]."""
    if sections is None:
        return [{"name": "all", "measures": [m["measure"] for m in measures], "from_measure": measures[0]["measure"],
                 "to_measure": measures[-1]["measure"]}]
    if not isinstance(sections, list) or not sections:
        raise EarError("BAD_ARGUMENT", "'sections'는 [{name, from_measure, to_measure} 또는 {name, start_sec, end_sec}] "
                                       "목록입니다.", {})
    out = []
    for i, s in enumerate(sections):
        if not isinstance(s, dict) or not isinstance(s.get("name"), str) or not s["name"].strip():
            raise EarError("BAD_ARGUMENT", f"sections[{i}]에 name(문자열)이 필요합니다.", {"index": i, "entry": s})
        keys = set(s) - {"name"}
        if keys == {"from_measure", "to_measure"}:
            a, b = s["from_measure"], s["to_measure"]
            if any(isinstance(v, bool) or not isinstance(v, int) for v in (a, b)) or a > b:
                raise EarError("BAD_ARGUMENT", f"sections[{i}]: from_measure·to_measure는 정수, from ≤ to.",
                               {"index": i, "entry": s})
            nums = [m["measure"] for m in measures if a <= m["measure"] <= b]
        elif keys == {"start_sec", "end_sec"}:
            a, b = s["start_sec"], s["end_sec"]
            if any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in (a, b)) or a >= b:
                raise EarError("BAD_ARGUMENT", f"sections[{i}]: start_sec·end_sec는 숫자, start < end.",
                               {"index": i, "entry": s})
            nums = [m["measure"] for m in measures if a <= (m["start_sec"] + m["end_sec"]) / 2 < b]
        else:
            raise EarError("BAD_ARGUMENT", f"sections[{i}]는 {{name, from_measure, to_measure}} 또는 "
                                           "{name, start_sec, end_sec}입니다.", {"index": i, "entry": s})
        out.append({"name": s["name"].strip(), "measures": nums,
                    "from_measure": nums[0] if nums else None, "to_measure": nums[-1] if nums else None})
    return out


def lead_margin(files: list[dict], rows: list[dict], lead_idx: list[int], band: tuple[float, float],
                sections: list[dict], silence_db: float) -> dict:
    """마디별·구간별 리드 여유(리드 K − 가장 센 다른 음높이 파트 K)."""
    import statistics

    lead_labels = [files[i]["label"] for i in lead_idx]
    competitors = [i for i, f in enumerate(files) if i not in lead_idx and not f["unpitched"]]
    per = []
    for row in rows:
        ps = row["parts"]
        lead_p = [10 ** ((ps[i]["k_db"] - K_OFFSET_DB) / 10) for i in lead_idx
                  if ps[i]["k_db"] is not None and ps[i]["rms_dbfs"] is not None and ps[i]["rms_dbfs"] >= silence_db]
        others = [(ps[i]["k_db"], ps[i]["label"]) for i in competitors
                  if ps[i]["k_db"] is not None and ps[i]["rms_dbfs"] is not None and ps[i]["rms_dbfs"] >= silence_db]
        if not lead_p or not others:
            per.append({"measure": row["measure"], "lead_k_db": None, "top_other": None, "top_other_k_db": None,
                        "margin_db": None, "in_band": None,
                        "reason": "리드가 쉼" if not lead_p else "다른 음높이 파트가 없음"})
            continue
        lk = K_OFFSET_DB + 10 * math.log10(sum(lead_p))
        top_k, top_l = max(others)
        mg = lk - top_k
        per.append({"measure": row["measure"], "lead_k_db": r(lk, 2), "top_other": top_l, "top_other_k_db": r(top_k, 2),
                    "margin_db": r(mg, 2), "in_band": band[0] <= mg <= band[1]})
    by_m = {x["measure"]: x for x in per}
    secs, warns = [], []
    for s in sections:
        vals = [by_m[m] for m in s["measures"] if m in by_m and by_m[m]["margin_db"] is not None]
        if not vals:
            secs.append({"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"],
                         "measures": 0, "lead_margin_db": None, "in_band": None, "status": "no_data"})
            continue
        mv = [v["margin_db"] for v in vals]
        mean = statistics.fmean(mv)
        status = "low" if mean < band[0] else ("high" if mean > band[1] else "ok")
        comp = Counter(v["top_other"] for v in vals).most_common(1)[0][0]
        rec = {"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"],
               "measures": len(vals), "lead_margin_db": r(mean, 2), "median_db": r(statistics.median(mv), 2),
               "min_db": r(min(mv), 2), "min_measure": min(vals, key=lambda v: v["margin_db"])["measure"],
               "measures_below_band": sum(1 for v in mv if v < band[0]), "in_band": band[0] <= mean <= band[1],
               "status": status, "main_competitor": comp}
        secs.append(rec)
        if status == "low":
            warns.append({"type": "lead_margin_low", "section": s["name"], "from_measure": s["from_measure"],
                          "to_measure": s["to_measure"], "lead_margin_db": rec["lead_margin_db"],
                          "main_competitor": comp, "band": list(band),
                          "text": f"{s['name']}({s['from_measure']}~{s['to_measure']}마디): 리드 여유 {mean:+.1f}dB "
                                  f"< {band[0]:g}dB — 리드가 묻힐 위험(가장 센 경쟁 {comp})"})
    return {"lead_part": lead_labels, "margin_band": list(band), "competitors": [files[i]["label"] for i in competitors],
            "rule": "margin = 리드 K-가중(파일 여럿이면 합) − 같은 마디 가장 센 다른 음높이 파트 K-가중(드럼·북·심벌즈 제외; "
                    "노래·실로폰·핸드벨 포함), 쉬는 파트(rms < silence_db) 제외. 구간 값은 마디 값의 평균",
            "sections": secs, "measures": per, "warnings": warns}


def run_balance(meta: dict, meta_path: Path, mix_wav: Path | None, measures: list[dict], buried_db: float,
                silence_db: float, low_hz: float, low_min_db: float, low_rel_db: float,
                buried_mode: str = "relative", buried_margin_db: float = 12.0, low_min_parts: int = 3,
                buried_include_unpitched: bool = False, weighting: str = "k", lead_part=None,
                margin_band=None, sections=None) -> dict:
    import librosa
    from scipy.signal import butter, sosfiltfilt

    files = resolve_files(meta, meta_path)
    band = check_margin_band(margin_band)
    lead_idx = resolve_lead(files, lead_part) if lead_part is not None else None
    lead_secs = check_lead_sections(sections, measures) if lead_part is not None else None
    if lead_part is None and (sections is not None or margin_band is not None):
        raise EarError("BAD_ARGUMENT", "'sections'·'margin_band'는 lead_part와 함께 씁니다.", {})
    sigs = []
    sr = io.ANALYSIS_SR
    for f in files:
        y, sr = io.load_mono(f["path"])
        sigs.append(y)
    n = max(y.size for y in sigs)
    if mix_wav is not None:
        mix, _ = io.load_mono(mix_wav)
        mix_kind = "mix_wav"
    else:
        mix = np.zeros(n, dtype=np.float64)
        for y in sigs:
            mix[: y.size] += y
        mix_kind = "sum_of_parts"
    if weighting not in WEIGHTINGS:
        raise EarError("BAD_ARGUMENT", "'weighting'은 'k' 또는 'rms' 여야 합니다.", {"weighting": weighting})
    ksig = [k_weight(y, sr) for y in sigs]
    mix_k = k_weight(mix, sr)
    sos = butter(4, low_hz, btype="lowpass", fs=sr, output="sos")
    lows = [sosfiltfilt(sos, y.astype(np.float64)) if y.size > 30 else y.astype(np.float64) for y in sigs]
    cents, cent_t = [], None
    for y in sigs:
        c = librosa.feature.spectral_centroid(y=y, sr=sr, hop_length=512)[0]
        e = librosa.feature.rms(y=y, hop_length=512)[0]
        k = min(c.size, e.size)
        cents.append((c[:k], e[:k]))
    low_idx = [i for i, f in enumerate(files) if f["pitched"] is not False]  # 저음 뭉침에서 세는 파트
    rows, warnings = [], []
    active_total = 0
    for m in measures:
        s, e = m["start_sec"], m["end_sec"]
        mix_db = _rms_db(mix, sr, s, e)
        mix_kdb = k_db(mix_k, sr, s, e)
        prow, levels, klevels = [], [], []
        for f, y, yk, (c, en) in zip(files, sigs, ksig, cents):
            d = _rms_db(y, sr, s, e)
            kd = k_db(yk, sr, s, e)
            fr = np.arange(c.size) * 512 / sr
            sel = (fr >= s) & (fr < e)
            w = en[sel]
            cent = float(np.sum(c[sel] * w) / np.sum(w)) if sel.any() and np.sum(w) > 1e-9 else None
            levels.append(d)
            klevels.append(kd)
            prow.append({"label": f["label"], "rms_dbfs": r(d, 2), "rel_mix_db": r(d - mix_db, 2),
                         "k_db": r(kd, 2), "rel_mix_k_db": r(kd - mix_kdb, 2),
                         "centroid_hz": r(cent, 1), "silent": d < silence_db, "unpitched": f["unpitched"]})
        n_active = sum(1 for d in levels if d >= silence_db)
        active_total += n_active
        thr = buried_threshold(buried_mode, n_active, buried_db, buried_margin_db)
        wname = "K-가중" if weighting == "k" else "RMS"
        for f, d, kd in zip(files, levels, klevels):
            rel = (kd - mix_kdb) if weighting == "k" else (d - mix_db)
            if f["unpitched"] and not buried_include_unpitched:
                continue  # 드럼·타악기는 정보로만 남긴다
            if d >= silence_db and rel <= thr:
                warnings.append({"type": "buried", "measure": m["measure"], "part": f["label"],
                                 "rel_mix_db_used": r(rel, 2), "weighting": weighting,
                                 "rel_mix_db": r(d - mix_db, 2), "rel_mix_k_db": r(kd - mix_kdb, 2),
                                 "rms_dbfs": r(d, 2), "k_db": r(kd, 2), "threshold_db": r(thr, 2),
                                 "active_parts": n_active,
                                 "text": f"{m['measure']}마디 {f['label']}: 믹스보다 {abs(rel):.1f}dB({wname}) 작아 묻힘"
                                         f"(기준 {thr:.1f}dB, 소리 내는 파트 {n_active}개)"})
        crowd = []
        for bi, (b0, b1) in enumerate(io.beat_segments(m), start=1):
            lv = {i: _rms_db(lows[i], sr, b0, b1) for i in low_idx}
            top = max(lv.values()) if lv else -120.0
            strong = [files[i]["label"] for i, v in lv.items() if v >= low_min_db and v >= top - low_rel_db]
            if len(strong) >= low_min_parts:
                crowd.append(bi)
                warnings.append({"type": "low_crowding", "measure": m["measure"], "beat": bi, "parts": strong,
                                 "low_dbfs": {files[i]["label"]: r(v, 1) for i, v in lv.items()},
                                 "text": f"{m['measure']}마디 {bi}박: {low_hz:.0f}Hz 아래에 {', '.join(strong)} 겹침"})
        rows.append({"measure": m["measure"], "mix_rms_dbfs": r(mix_db, 2), "mix_k_db": r(mix_kdb, 2),
                     "active_parts": n_active,
                     "threshold_db": r(thr, 2), "parts": prow, "low_crowding_beats": crowd})
    buried = [w for w in warnings if w["type"] == "buried"]
    crowding = [w for w in warnings if w["type"] == "low_crowding"]
    by_part: dict[str, int] = {}
    for w in buried:
        by_part[w["part"]] = by_part.get(w["part"], 0) + 1
    keys = ("label", "tracks", "parts", "programs", "instrument_types", "pitched", "pitched_reason", "unpitched")
    lm = None
    if lead_idx is not None:
        lm = lead_margin(files, rows, lead_idx, band, lead_secs, silence_db)
        warnings.extend(lm["warnings"])
    return {"parts": [{**{k: f[k] for k in keys}, "path": str(f["path"])} for f in files],
            "mix": mix_kind, "weighting_used": weighting, "measures": rows, "warnings": warnings,
            "lead_margin": lm,
            "counts": {"buried": len(buried), "low_crowding": len(crowding), "buried_by_part": by_part,
                       "lead_margin_low": len(lm["warnings"]) if lm else 0,
                       "active_part_measures": active_total,
                       "buried_ratio": r(len(buried) / active_total, 3) if active_total else None,
                       "low_crowding_parts_counted": [files[i]["label"] for i in low_idx]},
            "params": {"weighting": weighting,
                       "buried_value": ("rel_mix_k_db(K-가중)" if weighting == "k" else "rel_mix_db(RMS)"),
                       "k_weighting": ("ITU-R BS.1770 K-가중: 고역 쉘프(f0 1681.97Hz, +4.0dB, Q 0.7072) + "
                                       "RLB 고역통과(f0 38.14Hz, Q 0.5003), scipy.signal.bilinear(미리 휜 주파수)로 "
                                       "분석 샘플레이트에서 계수 계산, sosfilt(앞으로만). k_db = -0.691 + "
                                       "10*log10(평균 제곱), 모노, 게이팅 없음"),
                       "silence_value": "rms_dbfs",
                       "buried_mode": buried_mode, "buried_margin_db": buried_margin_db, "buried_db": buried_db,
                       "buried_include_unpitched": buried_include_unpitched,
                       "buried_skipped_parts": [f["label"] for f in files
                                                if f["unpitched"] and not buried_include_unpitched],
                       "buried_rule": ("-(10*log10(N) + buried_margin_db), N = 그 마디에서 쉬지 않는 파트 수"
                                       if buried_mode == "relative" else "-buried_db 고정"),
                       "silence_db": silence_db, "low_hz": low_hz, "low_min_db": low_min_db,
                       "low_rel_db": low_rel_db, "low_min_parts": low_min_parts,
                       "low_excludes": "드럼(27)·타악기(65~68)·instrument_type D/M(핸드벨이 아닌 P)·노래(V/C, song 파트)",
                       "unpitched_rule": "program 27·65~68, type D/M, 핸드벨(69~76)이 아닌 type P. 실로폰 77(K)·핸드벨은 "
                                         "음높이 있음",
                       "sr": sr, "mono": True,
                       "rms": "모노(채널 평균) RMS, dBFS(사인파 진폭 1 = -3dBFS)"}}
