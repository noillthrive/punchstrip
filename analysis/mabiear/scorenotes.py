"""Score notes (from a note list JSON) and the map from score ticks to seconds of the original recording.

Used by ``lead_check`` and ``vocal_agreement``. Notes come from a file the caller writes, usually the bridge's
``score_notes`` response saved as JSON. Accepted shapes (all ticks absolute, quarter = 96):

- a list of notes ``[{"tick", "len", "pitch", ...}, ...]``;
- one ``score_notes`` response ``{"notes": [...]}`` or ``{"parts": {"melody": [...], "song": [...]}}`` (all parts);
- a list of such responses (several tracks), or ``{"tracks": [<response>, ...]}``.

A note needs ``tick`` and ``len`` (ticks) and ``pitch``; ``sounding_pitch`` is used when present and
``pitch_mode`` is "sounding" (default; notes marked ``silent: true`` are skipped). ``vel`` is optional (needed only
for the ``velocities`` filter). Overlapping notes are allowed (the lead may be split over two tracks).

Time map (one of):

- grid: ``bpm`` + ``first_downbeat_sec`` (+ ``time_signature``, ``measure_offset``): the stem-tool convention,
  tool measure ``measure_offset`` starts at ``first_downbeat_sec`` of the original, one constant tempo;
- ``render_meta`` + ``align_json``: the score tick of each render measure (meta ``measures[].tick``) and its
  original-time beats from the align result (``measures[].beats_orig``, ``orig_end_sec``), piecewise linear between
  beats (so a path or segment mapping of ``align`` is followed), linear extrapolation outside.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from . import io
from .io import EarError, r

TPQ = 96


def _note_rows(obj: Any) -> list[dict]:
    if isinstance(obj, list):
        if all(isinstance(x, dict) and "tick" in x for x in obj):
            return list(obj)
        out = []
        for x in obj:
            out += _note_rows(x)
        return out
    if isinstance(obj, dict):
        if isinstance(obj.get("tracks"), list) and "notes" not in obj and "parts" not in obj:
            return _note_rows(obj["tracks"])
        rows = []
        if isinstance(obj.get("notes"), list):
            rows += [dict(n, track=n.get("track", obj.get("track")), part=n.get("part", obj.get("part")))
                     for n in obj["notes"] if isinstance(n, dict)]
        if isinstance(obj.get("parts"), dict):
            for part, ns in obj["parts"].items():
                if isinstance(ns, list):
                    rows += [dict(n, track=n.get("track", obj.get("track")), part=n.get("part", part))
                             for n in ns if isinstance(n, dict)]
        return rows
    return []


def load_notes(path: Path, arg: str = "notes_json", pitch_mode: str = "sounding", velocities=None,
               tick_shift: int = 0, from_tick: int | None = None, to_tick: int | None = None) -> tuple[list[dict], dict]:
    """Notes sorted by tick: {tick, len, pitch, vel, track, part, name}. Returns (notes, info)."""
    import json

    try:
        with open(path, "r", encoding="utf-8-sig") as f:
            data = json.load(f)
    except (OSError, UnicodeDecodeError, ValueError) as e:
        raise EarError("BAD_ARGUMENT", f"음 목록 JSON을 읽지 못했습니다: {path}", {"arg": arg, "reason": str(e)})
    rows = _note_rows(data)
    if not rows:
        raise EarError("BAD_ARGUMENT", "음 목록 JSON에 음이 없습니다(notes 목록, score_notes 응답, 또는 그 목록).",
                       {"arg": arg, "path": str(path)})
    vset = None if velocities is None else set(velocities)
    out, skipped = [], {"silent": 0, "velocity": 0, "range": 0, "no_pitch": 0}
    for i, n in enumerate(rows):
        try:
            tick = int(n["tick"]) + int(tick_shift)
            ln = int(n["len"])
        except (KeyError, TypeError, ValueError):
            raise EarError("BAD_ARGUMENT", f"음 {i}에 tick/len이 없습니다.", {"arg": arg, "index": i})
        if n.get("silent") is True:
            skipped["silent"] += 1
            continue
        p = n.get("sounding_pitch") if pitch_mode == "sounding" and n.get("sounding_pitch") is not None else n.get("pitch")
        if isinstance(p, bool) or not isinstance(p, (int, float)):
            skipped["no_pitch"] += 1
            continue
        if vset is not None and n.get("vel") not in vset:
            skipped["velocity"] += 1
            continue
        if (from_tick is not None and tick < from_tick) or (to_tick is not None and tick >= to_tick):
            skipped["range"] += 1
            continue
        p = int(round(p))
        out.append({"tick": tick, "len": max(1, ln), "pitch": p, "vel": n.get("vel"), "track": n.get("track"),
                    "part": n.get("part"), "name": io.PITCH_NAMES[p % 12] + str(p // 12 - 1),
                    "written_pos": n.get("pos")})
    out.sort(key=lambda x: (x["tick"], x["pitch"]))
    if not out:
        raise EarError("BAD_ARGUMENT", "고른 조건에 맞는 음이 없습니다(velocities·범위·pitch_mode를 확인).",
                       {"arg": arg, "skipped": skipped})
    return out, {"notes_in_file": len(rows), "notes_used": len(out), "skipped": skipped, "pitch_mode": pitch_mode,
                 "velocities": None if vset is None else sorted(vset), "tick_shift": int(tick_shift)}


class TickMap:
    """Score tick -> original seconds, and tick -> (pos, measure)."""

    def __init__(self, knots_tick, knots_sec, measures: list[tuple[int, int, int, int]], kind: str, info: dict):
        import numpy as np

        self.kt = np.asarray(knots_tick, dtype=float)
        self.ks = np.asarray(knots_sec, dtype=float)
        self.measures = measures  # (measure, tick0, tpm, beat_ticks) sorted
        self.kind = kind
        self.info = info

    def sec(self, tick):
        import numpy as np

        t = np.asarray(tick, dtype=float)
        v = np.interp(t, self.kt, self.ks)
        if self.kt.size >= 2:
            s0 = (self.ks[1] - self.ks[0]) / (self.kt[1] - self.kt[0])
            s1 = (self.ks[-1] - self.ks[-2]) / (self.kt[-1] - self.kt[-2])
            v = np.where(t < self.kt[0], self.ks[0] + (t - self.kt[0]) * s0, v)
            v = np.where(t > self.kt[-1], self.ks[-1] + (t - self.kt[-1]) * s1, v)
        return v

    def tick(self, sec):
        """Inverse map (seconds -> tick), monotone knots assumed."""
        import numpy as np

        s = np.asarray(sec, dtype=float)
        v = np.interp(s, self.ks, self.kt)
        if self.kt.size >= 2:
            s0 = (self.kt[1] - self.kt[0]) / (self.ks[1] - self.ks[0])
            s1 = (self.kt[-1] - self.kt[-2]) / (self.ks[-1] - self.ks[-2])
            v = np.where(s < self.ks[0], self.kt[0] + (s - self.ks[0]) * s0, v)
            v = np.where(s > self.ks[-1], self.kt[-1] + (s - self.ks[-1]) * s1, v)
        return v

    def _measure_row(self, tick: int):
        rows = self.measures
        best = rows[0]
        for row in rows:
            if row[1] <= tick:
                best = row
            else:
                break
        m, t0, tpm, bt = best
        if tick < t0:  # before the table: count back with the first measure's length
            k = (t0 - tick + tpm - 1) // tpm
            return m - k, t0 - k * tpm, tpm, bt
        if tick >= t0 + tpm and best is rows[-1]:
            k = (tick - t0) // tpm
            return m + k, t0 + k * tpm, tpm, bt
        return best

    def pos(self, tick: int) -> tuple[str, int, int]:
        """("m:b:t", measure, beat)."""
        m, t0, tpm, bt = self._measure_row(int(tick))
        rr = int(tick) - t0
        return f"{m}:{rr // bt + 1}:{rr % bt}", m, rr // bt + 1

    def ticks_per_beat(self, tick: int) -> int:
        return self._measure_row(int(tick))[3]


def grid_map(bpm: float, first: float, num: int, den: int, measure_offset: int) -> TickMap:
    beat_ticks = 4 * TPQ // den
    tpm = num * beat_ticks
    tick0 = (measure_offset - 1) * tpm
    q = 60.0 / bpm
    kt = [tick0, tick0 + 1000 * tpm]
    ks = [first, first + 1000 * tpm / TPQ * q]
    return TickMap(kt, ks, [(measure_offset, tick0, tpm, beat_ticks)], "grid",
                   {"bpm": bpm, "first_downbeat_sec": first, "time_signature": f"{num}/{den}",
                    "measure_offset": measure_offset})


def _sig_ticks(sig: str | None) -> tuple[int, int]:
    try:
        a, b = str(sig or "4/4").split("/")
        num, den = int(a), int(b)
        bt = 4 * TPQ // den
        return num * bt, bt
    except (ValueError, ZeroDivisionError):
        return 4 * TPQ, TPQ


def meta_align_map(meta_path: Path, align_path: Path) -> TickMap:
    raw = io.read_json(meta_path, "render_meta")
    if raw.get("schema") != io.META_SCHEMA:
        raise EarError("META_INVALID", f"메타 스키마가 '{io.META_SCHEMA}'가 아닙니다.", {"path": str(meta_path)})
    al = io.read_json(align_path, "align_json")
    if not isinstance(al.get("measures"), list) or not al["measures"]:
        raise EarError("BAD_ARGUMENT", "align_json에 measures가 없습니다.", {"path": str(align_path)})
    am = {m["measure"]: m for m in al["measures"] if isinstance(m, dict) and "measure" in m}
    kt, ks, rows = [], [], []
    for m in raw.get("measures") or []:
        if not isinstance(m, dict) or "tick" not in m or m.get("measure") not in am:
            continue
        tpm, bt = _sig_ticks(m.get("sig"))
        a = am[m["measure"]]
        bo = [float(x) for x in (a.get("beats_orig") or [a["orig_start_sec"]])]
        t0 = int(m["tick"])
        for k, s in enumerate(bo):
            kt.append(t0 + k * bt)
            ks.append(s)
        kt.append(t0 + tpm)
        ks.append(float(a["orig_end_sec"]))
        rows.append((int(m["measure"]), t0, tpm, bt))
    if len(kt) < 2:
        raise EarError("BAD_ARGUMENT", "render_meta와 align_json의 마디가 맞지 않습니다(같은 렌더의 결과인지 확인).",
                       {"render_meta": str(meta_path), "align_json": str(align_path)})
    pairs = sorted(set(zip(kt, ks)))
    # keep one second per tick (measure end = next start), monotone
    import numpy as np

    t_u, s_u = [], []
    for t, s in pairs:
        if t_u and t == t_u[-1]:
            s_u[-1] = 0.5 * (s_u[-1] + s)
            continue
        t_u.append(t)
        s_u.append(s)
    s_u = list(np.maximum.accumulate(np.asarray(s_u)))
    rows.sort(key=lambda x: x[1])
    return TickMap(t_u, s_u, rows, "render_meta+align_json",
                   {"render_meta": str(meta_path), "align_json": str(align_path),
                    "mapping_used": al.get("mapping_used"), "offset_sec": al.get("offset_sec"),
                    "tempo_ratio": al.get("tempo_ratio")})


def build_map(bpm, first_downbeat_sec, time_signature, measure_offset, render_meta, align_json) -> TickMap:
    """Server-side argument check for the two time-map forms (exactly one)."""
    from .stems import parse_time_signature

    grid_given = bpm is not None or first_downbeat_sec is not None
    meta_given = render_meta is not None or align_json is not None
    if grid_given == meta_given:
        raise EarError("BAD_ARGUMENT", "시간 지도는 bpm + first_downbeat_sec(격자) 또는 render_meta + align_json 중 "
                       "하나만 줍니다.", {"bpm": bpm, "first_downbeat_sec": first_downbeat_sec,
                                          "render_meta": render_meta, "align_json": align_json})
    if grid_given:
        if bpm is None or first_downbeat_sec is None:
            raise EarError("BAD_ARGUMENT", "격자 시간 지도는 bpm과 first_downbeat_sec가 모두 필요합니다.", {})
        for k, v in (("bpm", bpm), ("first_downbeat_sec", first_downbeat_sec)):
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v != v or abs(v) == float("inf"):
                raise EarError("BAD_ARGUMENT", f"'{k}'는 유한한 숫자여야 합니다.", {k: str(v)})
        if not 20 <= bpm <= 400:
            raise EarError("BAD_ARGUMENT", "'bpm'이 범위(20~400) 밖입니다.", {"bpm": bpm})
        mo = 1 if measure_offset is None else measure_offset
        if isinstance(mo, bool) or not isinstance(mo, int) or not 1 <= mo <= 100000:
            raise EarError("BAD_ARGUMENT", "'measure_offset'은 1 이상 정수입니다.", {"measure_offset": measure_offset})
        num, den = parse_time_signature(time_signature or "4/4")
        return grid_map(float(bpm), float(first_downbeat_sec), num, den, mo)
    if render_meta is None or align_json is None:
        raise EarError("BAD_ARGUMENT", "render_meta와 align_json을 함께 줍니다.", {})
    return meta_align_map(io.input_file(render_meta, "render_meta"), io.input_file(align_json, "align_json"))


def check_velocities(v) -> list[int] | None:
    if v is None:
        return None
    if not isinstance(v, list) or not v or not all(isinstance(x, int) and not isinstance(x, bool) for x in v):
        raise EarError("BAD_ARGUMENT", "'velocities'는 정수 목록입니다(예: [12]).", {"velocities": v})
    return v


def key_profile_pcs(key: str | None) -> set[int] | None:
    """Scale pitch classes of "D major" / "F#m" / "C" (None for None/"none")."""
    if key in (None, "none"):
        return None
    from .chords import parse_key

    root, mode = parse_key(key)
    steps = (0, 2, 3, 5, 7, 8, 10) if mode == "minor" else (0, 2, 4, 5, 7, 9, 11)
    return {(root + s) % 12 for s in steps}


def estimate_key(notes: list[dict]) -> tuple[str, set[int], float]:
    """Krumhansl key of the score notes weighted by length. Returns (name, scale pcs, r)."""
    import numpy as np

    major = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
    minor = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
    h = np.zeros(12)
    for n in notes:
        h[n["pitch"] % 12] += n["len"]
    best = (-2.0, 0, False)
    for root in range(12):
        for prof, mn in ((major, False), (minor, True)):
            c = float(np.corrcoef(h, np.roll(prof, root))[0, 1]) if h.std() > 0 else 0.0
            if c > best[0]:
                best = (c, root, mn)
    c, root, mn = best
    steps = (0, 2, 3, 5, 7, 8, 10) if mn else (0, 2, 4, 5, 7, 9, 11)
    return f"{io.PITCH_NAMES[root]} {'minor' if mn else 'major'}", {(root + s) % 12 for s in steps}, r(c, 3)
