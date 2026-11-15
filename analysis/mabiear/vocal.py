"""Agreement between the score's lead and a note-level transcription of the original vocal (``vocal_agreement``).

The transcription is the output file of an external singing transcriber (openvpi GAME): CSV ``onset,offset,pitch``
with a header, or the same three columns tab-separated without a header (``.txt``); seconds from the start of the
vocal file, pitch = float MIDI (60 = C4, the singer's real octave). mabi-ear only reads that file; it does not run
the transcriber.

Rules (the trust rules of the transcriber's notes, measured on earlier songs):

- transcribed notes shorter than ``min_note_sec`` (0.12 s) are slides or scoops and are ignored;
- an out-of-key transcribed note that sits ``slide_cents`` (40) or more from the nearest semitone is a slide and is
  ignored (key: ``key`` "auto" = Krumhansl key of the score's lead notes weighted by length, a name such as
  "D major" / "F#m", or "none" to skip this rule);
- the transcriber is monophonic: harmony or doubled vocals come out as one line, so recording -> score misses inside
  a harmony passage may be the other voice (reported, not corrected).

Two directions, pitch class only (octave reported separately):

- **score -> recording**: for each lead note, the transcribed notes (after the rules) overlapping its first
  ``head_sec`` (1.0 s; the whole note when shorter). None = 'voice not heard' (not counted); agree when one of them has
  the note's pitch class.
- **recording -> score**: for each transcribed note (after the rules, outside ``exclude_measures``) the score covers it
  when a lead note of the same pitch class starts at most ``onset_tolerance_ticks`` (24, a 16th) before the sung onset
  and is still sounding at it.
"""
from __future__ import annotations

import csv
import logging
from pathlib import Path

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.vocal")

SCHEMA_VOCAL = "mabiear.vocal_agreement/1"

LIMITS = [
    "The transcription is monophonic: harmony and doubled vocals become one line, so disagreements inside a harmony "
    "passage can be the other voice; score notes where it hears no voice are not counted.",
    "Transcriber note ends are often cut early on held notes; agreement uses onsets and the head of each score note.",
    "Pitch class only; octave_relation reports how the score's octave relates to the sung one.",
    "The time map must be right (score tick -> recording seconds); a constant offset shows as onset_offset_ms.",
]


def load_transcription(path: Path) -> list[dict]:
    rows = []
    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as f:
            text = f.read()
    except (OSError, UnicodeDecodeError) as e:
        raise EarError("BAD_ARGUMENT", f"전사 파일을 읽지 못했습니다: {path}", {"reason": str(e)})
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if not lines:
        raise EarError("BAD_ARGUMENT", "전사 파일이 비어 있습니다.", {"path": str(path)})
    delim = "\t" if "\t" in lines[0] else ","
    reader = csv.reader(lines, delimiter=delim)
    for i, row in enumerate(reader):
        if len(row) < 3:
            continue
        try:
            on, off, p = float(row[0]), float(row[1]), float(row[2])
        except ValueError:
            if i == 0:
                continue  # header
            raise EarError("BAD_ARGUMENT", f"전사 파일 {i + 1}번째 줄을 읽을 수 없습니다(onset, offset, pitch).",
                           {"path": str(path), "line": i + 1})
        if off > on:
            rows.append({"on": on, "off": off, "pitch": p})
    if not rows:
        raise EarError("BAD_ARGUMENT", "전사 파일에 음이 없습니다(onset, offset, pitch 세 칸).", {"path": str(path)})
    rows.sort(key=lambda x: x["on"])
    return rows


def trusted_notes(trans: list[dict], key_pcs: set | None, min_note_sec: float = 0.12,
                  slide_cents: float = 40.0) -> tuple[list[dict], dict]:
    """The transcribed notes that survive the trust rules (short notes and out-of-key slides removed)."""
    kept, short, slide = [], 0, 0
    for g in trans:
        if g["off"] - g["on"] < min_note_sec:
            short += 1
            continue
        p = int(round(g["pitch"]))
        cents = 100 * (g["pitch"] - p)
        if key_pcs is not None and abs(cents) >= slide_cents and p % 12 not in key_pcs:
            slide += 1
            continue
        kept.append({**g, "p": p, "cents": cents})
    return kept, {"transcribed_notes": len(trans), "kept": len(kept), "ignored_short": short,
                  "ignored_out_of_key_slides": slide}


def _name(p: int) -> str:
    return io.PITCH_NAMES[p % 12] + str(p // 12 - 1)


def run_vocal_agreement(trans: list[dict], notes: list[dict], tmap, sections: list[dict], key_pcs: set | None,
                        key_name: str | None, min_note_sec: float = 0.12, slide_cents: float = 40.0,
                        head_sec: float = 1.0, tol_ticks: int = 24, exclude: list | None = None) -> dict:
    import bisect

    import numpy as np

    exclude = exclude or []

    def excluded(m):
        return any(a <= m <= b for a, b in exclude)

    kept, ign_short, ign_slide = [], 0, 0
    for g in trans:
        if g["off"] - g["on"] < min_note_sec:
            ign_short += 1
            continue
        pr_ = int(round(g["pitch"]))
        cents = 100 * (g["pitch"] - pr_)
        if key_pcs is not None and abs(cents) >= slide_cents and pr_ % 12 not in key_pcs:
            ign_slide += 1
            continue
        k = float(tmap.tick(g["on"]))
        kept.append({**g, "p": pr_, "cents": cents, "tick": k, "measure": tmap.pos(int(np.floor(k)))[1]})
    ons = [g["on"] for g in kept]

    # score -> recording
    s2r = []
    for n in notes:
        t0 = float(tmap.sec(n["tick"]))
        t1n = float(tmap.sec(n["tick"] + n["len"]))
        t1 = t0 + min(t1n - t0, head_sec)
        i = bisect.bisect_left(ons, t0 - 5.0)
        cands = []
        for g in kept[i:]:
            if g["on"] > t1:
                break
            ov = min(g["off"], t1) - max(g["on"], t0)
            if ov > 0:
                cands.append((ov, g))
        agree = any(g["p"] % 12 == n["pitch"] % 12 for _, g in cands)
        best = max(cands, key=lambda x: x[0])[1] if cands else None
        match = next((g for _, g in sorted(cands, key=lambda x: -x[0]) if g["p"] % 12 == n["pitch"] % 12), None)
        pos, meas, _ = tmap.pos(n["tick"])
        s2r.append({"pos": pos, "measure": meas, "tick": n["tick"], "name": n["name"], "pitch": n["pitch"],
                    "len": n["len"], "track": n.get("track"), "sec": r(t0, 3), "voice_heard": bool(cands),
                    "agree": bool(agree),
                    "sung": None if best is None else f"{_name(best['p'])} ({best['pitch']:.2f})",
                    "sung_on_sec": None if best is None else r(best["on"], 3),
                    "onset_offset_ms": None if match is None else r(1000 * (match["on"] - t0), 0),
                    "octave_relation": (None if match is None else int(round((n["pitch"] - match["p"]) / 12)))})
    # recording -> score
    starts = sorted((n["tick"], n["tick"] + n["len"], n["pitch"], n["name"]) for n in notes)
    st_ticks = [x[0] for x in starts]
    r2s = []
    for g in kept:
        if excluded(g["measure"]):
            continue
        k = g["tick"]
        j = bisect.bisect_right(st_ticks, k + tol_ticks)
        cover = False
        there = []
        for a, b, p, nm in starts[max(0, j - 64):j]:
            if a - tol_ticks <= k < b:
                there.append(nm)
                if p % 12 == g["p"] % 12:
                    cover = True
        pos, meas, _ = tmap.pos(int(round(k)))
        r2s.append({"pos": pos, "measure": meas, "sec": r(g["on"], 3), "dur_sec": r(g["off"] - g["on"], 3),
                    "sung": _name(g["p"]), "pitch": r(g["pitch"], 2), "cents": r(g["cents"], 0), "covered": cover,
                    "score_has": there})

    def sec_of(m):
        for s in sections:
            if s["from_measure"] <= m <= s["to_measure"]:
                return s["name"]
        return None

    per = []
    for s in sections:
        a = [x for x in s2r if s["from_measure"] <= x["measure"] <= s["to_measure"]]
        h = [x for x in a if x["voice_heard"]]
        b = [x for x in r2s if s["from_measure"] <= x["measure"] <= s["to_measure"]]
        per.append({"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"],
                    "score_notes": len(a), "voice_heard": len(h), "agree": sum(x["agree"] for x in h),
                    "score_to_recording_pct": r(100 * sum(x["agree"] for x in h) / len(h), 1) if h else None,
                    "sung_notes": len(b), "covered": sum(x["covered"] for x in b),
                    "recording_to_score_pct": r(100 * sum(x["covered"] for x in b) / len(b), 1) if b else None})
    heard = [x for x in s2r if x["voice_heard"]]
    offs = [x["onset_offset_ms"] for x in s2r if x["onset_offset_ms"] is not None]
    octs = {}
    for x in s2r:
        if x["octave_relation"] is not None:
            octs[str(x["octave_relation"])] = octs.get(str(x["octave_relation"]), 0) + 1
    total = {"score_notes": len(s2r), "voice_heard": len(heard), "agree": sum(x["agree"] for x in heard),
             "score_to_recording_pct": r(100 * sum(x["agree"] for x in heard) / len(heard), 1) if heard else None,
             "sung_notes": len(r2s), "covered": sum(x["covered"] for x in r2s),
             "recording_to_score_pct": r(100 * sum(x["covered"] for x in r2s) / len(r2s), 1) if r2s else None,
             "transcribed_notes": len(trans), "ignored_short": ign_short, "ignored_out_of_key_slides": ign_slide,
             "excluded_measures": exclude,
             "onset_offset_ms_median": r(float(np.median(offs)), 0) if offs else None,
             "octave_relation_score_minus_sung": octs}
    for x in s2r:
        x["section"] = sec_of(x["measure"])
    for x in r2s:
        x["section"] = sec_of(x["measure"])
    return {"total": total, "sections": per,
            "score_disagreements": [x for x in s2r if x["voice_heard"] and not x["agree"]],
            "recording_not_in_score": [x for x in r2s if not x["covered"]],
            "score_no_voice": [{k: x[k] for k in ("pos", "name", "sec")} for x in s2r if not x["voice_heard"]],
            "key": {"used": key_name, "scale_pcs": None if key_pcs is None else sorted(key_pcs)},
            "rules": {"min_note_sec": min_note_sec, "slide_cents": slide_cents, "head_sec": head_sec,
                      "onset_tolerance_ticks": tol_ticks},
            "limits": LIMITS}


def markdown(doc: dict) -> str:
    t = doc["total"]
    lines = ["# 노래 전사와 악보 리드 대조 (vocal_agreement)", "",
             f"악보→녹음 **{t['agree']}/{t['voice_heard']}** ({t['score_to_recording_pct']}%, 목소리가 들린 음 중 같은 음이름), "
             f"녹음→악보 **{t['covered']}/{t['sung_notes']}** ({t['recording_to_score_pct']}%). 짧은 음 {t['ignored_short']}개·"
             f"조 밖 슬라이드 {t['ignored_out_of_key_slides']}개는 뺐다(조: {doc['key']['used']}).", "",
             "## 구간별", "", "| 구간 | 마디 | 악보→녹음 | % | 녹음→악보 | % |", "|---|---|---:|---:|---:|---:|"]
    for s in doc["sections"]:
        lines.append(f"| {s['name']} | {s['from_measure']}–{s['to_measure']} | {s['agree']}/{s['voice_heard']} | "
                     f"{s['score_to_recording_pct'] if s['score_to_recording_pct'] is not None else '-'} | "
                     f"{s['covered']}/{s['sung_notes']} | "
                     f"{s['recording_to_score_pct'] if s['recording_to_score_pct'] is not None else '-'} |")
    lines += ["", "## 악보 음이 노래와 다른 곳", "", "| 위치 | 원곡 초 | 악보 | 노래 |", "|---|---:|---|---|"]
    for x in doc["score_disagreements"]:
        lines.append(f"| {x['pos']} | {x['sec']:.2f} | {x['name']} | {x['sung']} |")
    lines += ["", "## 노래에 있는데 악보 리드에 없는 음", "", "| 위치 | 원곡 초 | 노래 | 악보에 있는 음 |", "|---|---:|---|---|"]
    for x in doc["recording_not_in_score"]:
        lines.append(f"| {x['pos']} | {x['sec']:.2f} | {x['sung']} ({x['pitch']}, {x['dur_sec']}초) | "
                     f"{', '.join(x['score_has']) or '쉼'} |")
    lines += ["", "## 한계", ""] + [f"- {s}" for s in LIMITS]
    return "\n".join(lines) + "\n"
