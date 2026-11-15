"""0.5.0: align fixed mapping, lead_check, stem_melody_notes method ridge, vocal_agreement (synthetic audio only)."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

import synth
from mabiear import server
from mabiear.io import EarError

# ---------------------------------------------------------------- align with fixed parts

CH8 = [["C4", "E4", "G4"], ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["G4", "B3", "D4"],
       ["E4", "G4", "B3"], ["D4", "F3", "A3"], ["C4", "E4", "A4"], ["G2", "B3", "D4"]]
TRUE_RATIO = 120 / 126
TRUE_OFFSET = 1.5


@pytest.fixture(scope="module")
def align_files(tmp_path_factory):
    d = tmp_path_factory.mktemp("al")
    rend = synth.write_wav(d / "r8.wav", synth.song(CH8, 120, tail=1.0))
    meta = synth.write_json(d / "r8.json", synth.meta_for(120, 8, wav=str(rend)))
    y = np.concatenate([synth.silence(TRUE_OFFSET)] + [synth.chord_beats(c, 4, 60 / 126) for c in CH8 + CH8]
                       + [synth.silence(1.0)])
    orig = synth.write_wav(d / "o16.wav", y)
    return d, rend, meta, orig


def test_align_fixed_offset_and_ratio_skips_search(tmp_path, align_files):
    d, rend, meta, orig = align_files
    res = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="fx",
                            offset_sec=1.234, tempo_ratio=0.95)
    s = res["summary"]
    assert s["offset_sec"] == pytest.approx(1.234, abs=1e-4) and s["tempo_ratio"] == pytest.approx(0.95, abs=1e-5)
    assert s["fit_method"] == "fixed" and s["mapping_used"] == "linear"
    assert s["fixed"]["searched"] == []
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    m3 = [m for m in doc["measures"] if m["measure"] == 3][0]
    assert m3["orig_start_sec"] == pytest.approx(1.234 + 0.95 * 4.0, abs=2e-3)


def test_align_fixed_ratio_searches_offset(tmp_path, align_files):
    d, rend, meta, orig = align_files
    s = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="fr", search_sec=[0.0, 18.0],
                          orig_bpm=126.0)["summary"]
    assert s["tempo_ratio"] == pytest.approx(TRUE_RATIO, abs=1e-6)
    assert s["offset_sec"] == pytest.approx(TRUE_OFFSET, abs=0.05)
    assert s["fixed"]["searched"] == ["offset"] and s["fit_method"] == "fixed_ratio+offset_search"


def test_align_first_downbeat_with_measure_offset_searches_ratio(tmp_path, align_files):
    d, rend, meta, orig = align_files
    # render measure 3 starts at 4.0 s; in the original it is at 1.5 + 4.0 * 120/126
    first = TRUE_OFFSET + 4.0 * TRUE_RATIO
    s = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="fd", search_sec=[0.0, 18.0],
                          first_downbeat_sec=first, measure_offset=3)["summary"]
    assert s["tempo_ratio"] == pytest.approx(TRUE_RATIO, abs=0.004)
    m3 = [m for m in s["measures"] if m["measure"] == 3][0]
    assert m3["orig_start_sec"] == pytest.approx(first, abs=0.02)
    assert s["fixed"]["searched"] == ["tempo_ratio"]


def test_align_fixed_arguments_conflict(tmp_path, align_files):
    d, rend, meta, orig = align_files
    with pytest.raises(EarError) as e:
        server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="x", offset_sec=1.0,
                          first_downbeat_sec=1.0)
    assert e.value.code == "BAD_ARGUMENT"
    with pytest.raises(EarError):
        server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="y", measure_offset=2)
    with pytest.raises(EarError):
        server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="z", first_downbeat_sec=1.0,
                          measure_offset=99)


# ---------------------------------------------------------------- lead_check

PHRASE = [(0, 1, 67), (1, 1, 69), (2, 1, 71), (3, 1, 72), (4, 2, 74), (6, 0.5, 72), (6.5, 0.5, 71), (7, 1, 69),
          (8, 1, 67), (9, 1, 67), (10, 2, 64), (12, 1, 62), (13, 1, 64), (14, 2, 67)]
PLANT = {4: 76, 10: 62}       # index -> wrong pitch written in the score


def notes_json(path: Path, phrase, plant=None, shift=12, vel=12):
    """A score_notes-like response (score an octave above the voice, as a flute lead would be)."""
    ns = []
    for i, (s, ln, p) in enumerate(phrase):
        q = (plant or {}).get(i, p)
        ns.append({"tick": int(round(s * 96)), "pos": None, "len": int(round(ln * 96)), "pitch": q + shift,
                   "vel": vel, "sounding_pitch": q + shift, "silent": False})
    return synth.write_json(path, {"track": 1, "part": "melody", "notes": ns})


@pytest.fixture(scope="module")
def lead_files(tmp_path_factory):
    d = tmp_path_factory.mktemp("lead")
    stem = synth.write_wav(d / "voice.wav", synth.vocal_stem(PHRASE))
    # weak passage: the voice is missing for notes 8-9 (only the noise floor)
    weak = [x for i, x in enumerate(PHRASE) if i not in (8, 9)]
    weak_stem = synth.write_wav(d / "voice_weak.wav", synth.vocal_stem(weak))
    # stereo mix: centred voice + accompaniment chords panned hard left / right (different chords)
    v = synth.vocal_stem(PHRASE)
    n = v.size
    # harmonic-rich held tones inside the voice's octave window (C4 left, F4 right), as loud as the voice
    left = synth.voice_tone(60, n / synth.SR, vibrato=0.0)[:n]
    right = synth.voice_tone(65, n / synth.SR, vibrato=0.0)[:n]
    mix = np.stack([v + left, v + right], axis=1).astype(np.float32)
    mix_wav = synth.write_wav(d / "mix.wav", mix)
    return {"dir": d, "stem": stem, "weak": weak_stem, "mix": mix_wav,
            "correct": notes_json(d / "correct.json", PHRASE),
            "planted": notes_json(d / "planted.json", PHRASE, PLANT)}


def _lead(files, audio, notes, tmp_path, name, **kw):
    kw.setdefault("source", "stem")
    kw.setdefault("pyin", False)
    return server.tool_lead_check(str(audio), str(notes), bpm=120.0, first_downbeat_sec=0.5,
                                  out_dir=str(tmp_path), name=name, **kw)


def _rows(res):
    return json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))["notes"]


def test_lead_check_correct_line_not_flagged(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["stem"], lead_files["correct"], tmp_path, "ok")
    c = res["summary"]["counts"]
    assert c["wrong"] == 0 and c["agree"] >= 13, res["summary_text"]
    assert res["summary"]["analysis"]["octave_shift"] == -12
    assert Path(res["summary"]["md_path"]).exists()


def test_lead_check_planted_notes_are_wrong_with_the_sung_pitch(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["stem"], lead_files["planted"], tmp_path, "pl")
    rows = _rows(res)
    for i, true in ((4, 74), (10, 64)):
        assert rows[i]["verdict"] == "wrong", rows[i]
        assert rows[i]["heard_pc"] == true % 12 and rows[i]["heard_midi"] == true
    assert sum(1 for i, x in enumerate(rows) if x["verdict"] == "wrong" and i not in PLANT) == 0


def test_lead_check_weak_passage_is_uncertain(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["weak"], lead_files["correct"], tmp_path, "wk")
    rows = _rows(res)
    assert rows[8]["verdict"] == "uncertain" and rows[9]["verdict"] == "uncertain", (rows[8], rows[9])
    assert "weak" in rows[8]["reasons"]
    assert res["summary"]["counts"]["wrong"] == 0


def test_lead_check_full_mix_mid_side(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["mix"], lead_files["planted"], tmp_path, "mx", source="mix")
    rows = _rows(res)
    assert rows[4]["verdict"] == "wrong" and rows[4]["heard_pc"] == 74 % 12
    assert rows[10]["verdict"] == "wrong" and rows[10]["heard_pc"] == 64 % 12
    assert sum(1 for i, x in enumerate(rows) if x["verdict"] == "wrong" and i not in PLANT) == 0
    assert res["summary"]["analysis"]["mode_used"] == "mix" and res["summary"]["analysis"]["side_k"] is not None


def test_lead_check_filters_and_map_errors(tmp_path, lead_files):
    with pytest.raises(EarError) as e:
        _lead(lead_files, lead_files["stem"], lead_files["correct"], tmp_path, "e1", velocities=[3])
    assert e.value.code == "BAD_ARGUMENT"
    with pytest.raises(EarError):
        server.tool_lead_check(str(lead_files["stem"]), str(lead_files["correct"]), out_dir=str(tmp_path), name="e2")
    with pytest.raises(EarError):
        server.tool_lead_check(str(lead_files["stem"]), str(lead_files["correct"]), bpm=120.0, first_downbeat_sec=0.5,
                               source="vocal", out_dir=str(tmp_path), name="e3")
    res = _lead(lead_files, lead_files["stem"], lead_files["correct"], tmp_path, "rng", from_measure=1, to_measure=2)
    assert res["summary"]["counts"]["notes"] == 8


def test_lead_check_with_render_meta_and_align(tmp_path, lead_files):
    """The same notes through render_meta + align_json: a render whose measure 1 is at 0 s, aligned by a fixed line."""
    d = lead_files["dir"]
    meta = synth.write_json(d / "r.json", synth.meta_for(120, 8, wav=str(d / "r.wav")))
    al = {"schema": "mabiear.align/1", "mapping_used": "linear", "offset_sec": 0.5, "tempo_ratio": 1.0,
          "measures": [{"measure": m["measure"], "orig_start_sec": m["start_sec"] + 0.5, "orig_end_sec": m["end_sec"] + 0.5,
                        "beats_orig": [b + 0.5 for b in m["beats"]]} for m in json.loads(meta.read_text())["measures"]]}
    aj = synth.write_json(d / "r_align.json", al)
    res = server.tool_lead_check(str(lead_files["stem"]), str(lead_files["planted"]), source="stem", pyin=False,
                                 render_meta=str(meta), align_json=str(aj), out_dir=str(tmp_path), name="ma")
    rows = _rows(res)
    assert rows[4]["verdict"] == "wrong" and rows[10]["verdict"] == "wrong"
    assert res["summary"]["counts"]["wrong"] == 2


# ---------------------------------------------------------------- stem_melody_notes method ridge

SOLO = [(0, 1, 79), (1, 0.5, 81), (1.5, 0.5, 83), (2, 2, 84), (4, 1, 81), (5, 1, 79), (6, 2, 76),
        (10, 1, 77), (11, 1, 79), (12, 2, 81)]


def lead_tone(midi, dur, sr=22050, amp=0.25):
    """Synth lead with a weak fundamental and strong 2nd partial (the classic octave trap), 12 partials."""
    n = int(dur * sr)
    t = np.arange(n) / sr
    f = 440.0 * 2 ** ((midi - 69) / 12)
    gains = [0.35, 1.0, 0.7, 0.55, 0.45, 0.35, 0.3, 0.25, 0.2, 0.15, 0.12, 0.1]
    y = sum(g * np.sin(2 * np.pi * (k + 1) * f * t) for k, g in enumerate(gains) if (k + 1) * f < sr / 2)
    env = np.minimum(1, t / 0.01) * np.minimum(1, np.maximum(0, (dur - t) / 0.03))
    return amp * y * env


@pytest.fixture(scope="module")
def solo_wav(tmp_path_factory):
    d = tmp_path_factory.mktemp("solo")
    q = 0.5
    first = 0.5
    total = int((first + 15 * q) * 22050)
    y = np.zeros(total)
    for s, ln, p in SOLO:
        sig = lead_tone(p, ln * q * 0.95)
        a = int((first + s * q) * 22050)
        y[a:a + sig.size] += sig
    # accompaniment bleed in the rest (quarters 8-10): one tone far below the solo's register
    bl = 0.6 * synth.voice_tone(60, 2 * q * 0.95, vibrato=0.0)
    a = int((first + 8 * q) * 22050)
    y[a:a + bl.size] += bl
    y += 10 ** (-50 / 20) * np.random.default_rng(5).standard_normal(total)
    return synth.write_wav(d / "solo.wav", y.astype(np.float32))


def test_ridge_octave_from_harmonic_evidence(tmp_path, solo_wav):
    res = server.tool_stem_melody_notes(str(solo_wav), 120.0, 0.5, method="ridge", range_low=48, range_high=96)
    notes = res["result"]["notes"]
    truth = [(int(s * 96), p) for s, _, p in SOLO]
    hit = 0
    for tk, p in truth:
        c = [n for n in notes if abs(n["tick"] - tk) <= 24]
        assert c, (tk, p, [(n["tick"], n["pitch"]) for n in notes])
        assert c[0]["pitch"] == p, (tk, p, c[0])
        hit += 1
    assert hit == len(SOLO)
    # the low bleed tone in the rest is not part of the line
    assert not any(n["pitch"] < 70 for n in notes), [(n["pos"], n["name"]) for n in notes]
    assert res["result"]["counts"]["dropped_bleed"] >= 1
    assert res["result"]["track"]["method"] == "ridge"


def test_ridge_options_only_with_ridge(solo_wav):
    with pytest.raises(EarError):
        server.tool_stem_melody_notes(str(solo_wav), 120.0, 0.5, method="salience", triplets=True)
    res = server.tool_stem_melody_notes(str(solo_wav), 120.0, 0.5, method="ridge", triplets=True, range_low=48,
                                        range_high=96, from_sec=0.4, to_sec=3.0)
    assert res["result"]["grid"]["triplets"] is True
    assert all(n["sec"] < 3.0 for n in res["result"]["notes"])


# ---------------------------------------------------------------- vocal_agreement

def _game_rows():
    """Sung notes for PHRASE at 120 BPM, measure 1 at 0.5 s (voice = score - 12), plus traps."""
    rows = []
    for s, ln, p in PHRASE:
        on = 0.5 + s * 0.5 + 0.01
        rows.append((on, on + ln * 0.5 * 0.9, p + 0.03))
    rows[2] = (rows[2][0], rows[2][1], 70.02)            # sung A#4 where the score has B: score disagreement
    rows.append((0.5 + 15.0 * 0.5, 0.5 + 15.0 * 0.5 + 0.08, 60.0))   # 80 ms scoop: ignored
    rows.append((0.5 + 16.0 * 0.5, 0.5 + 16.5 * 0.5, 61.45))          # C#+45c out of key (C major): slide, ignored
    rows.append((0.5 + 17.0 * 0.5, 0.5 + 17.8 * 0.5, 65.0))           # F4 where the score rests: not covered
    return rows


@pytest.fixture(scope="module")
def game_files(tmp_path_factory):
    d = tmp_path_factory.mktemp("game")
    rows = _game_rows()
    csvp = d / "vocals.csv"
    csvp.write_text("onset,offset,pitch\n" + "".join(f"{a:.3f},{b:.3f},{p:.3f}\n" for a, b, p in rows), encoding="utf-8")
    txtp = d / "vocals.txt"
    txtp.write_text("".join(f"{a:.3f}\t{b:.3f}\t{p:.3f}\n" for a, b, p in rows), encoding="utf-8")
    return {"csv": csvp, "txt": txtp, "notes": notes_json(d / "notes.json", PHRASE)}


def test_vocal_agreement_both_directions(tmp_path, game_files):
    res = server.tool_vocal_agreement(str(game_files["csv"]), str(game_files["notes"]), bpm=120.0,
                                      first_downbeat_sec=0.5, key="C major", out_dir=str(tmp_path), name="va")
    t = res["summary"]["total"]
    assert t["score_notes"] == 14 and t["voice_heard"] == 14 and t["agree"] == 13
    assert t["ignored_short"] == 1 and t["ignored_out_of_key_slides"] == 1
    assert t["sung_notes"] == 15 and t["covered"] == 13          # the A#4 and the F4 in the rest
    assert t["octave_relation_score_minus_sung"] == {"1": 13}
    dis = res["summary"]["score_disagreements"]
    assert len(dis) == 1 and dis[0]["name"] == "B5" and dis[0]["sung"].startswith("A#4")
    miss = {x["sung"] for x in res["summary"]["recording_not_in_score"]}
    assert miss == {"A#4", "F4"}
    assert Path(res["summary"]["md_path"]).exists()


def test_vocal_agreement_txt_key_none_and_exclusion(tmp_path, game_files):
    res = server.tool_vocal_agreement(str(game_files["txt"]), str(game_files["notes"]), bpm=120.0,
                                      first_downbeat_sec=0.5, key="none", exclude_measures=[[5, 5]],
                                      out_dir=str(tmp_path), name="vb")
    t = res["summary"]["total"]
    assert t["ignored_out_of_key_slides"] == 0
    # measure 5 (quarters 16-19) holds the slide (now kept) and the F4: both excluded from recording -> score
    assert t["sung_notes"] == 14 and t["covered"] == 13
    res2 = server.tool_vocal_agreement(str(game_files["txt"]), str(game_files["notes"]), bpm=120.0,
                                       first_downbeat_sec=0.5, key="auto", out_dir=str(tmp_path), name="vc")
    # the phrase has no F or F#: C major and G major both fit; the estimate must contain every phrase class
    assert res2["summary"]["key"]["used"].split(" (")[0] in ("C major", "G major")
    assert {p % 12 for _, _, p in PHRASE} <= set(res2["summary"]["key"]["scale_pcs"])


def test_vocal_agreement_bad_arguments(tmp_path, game_files):
    with pytest.raises(EarError):
        server.tool_vocal_agreement(str(game_files["csv"]), str(game_files["notes"]), bpm=120.0,
                                    first_downbeat_sec=0.5, exclude_measures=[[3]], out_dir=str(tmp_path), name="x")
    bad = tmp_path / "bad.csv"
    bad.write_text("onset,offset,pitch\nx,y,z\n", encoding="utf-8")
    with pytest.raises(EarError):
        server.tool_vocal_agreement(str(bad), str(game_files["notes"]), bpm=120.0, first_downbeat_sec=0.5,
                                    out_dir=str(tmp_path), name="y")


# ---------------------------------------------------------------- 0.5.0 review fixes

@pytest.mark.parametrize("err_ms", [-60, 60])
def test_lead_check_tolerates_time_map_error(tmp_path, lead_files, err_ms):
    """A time map 60 ms early or late: the correct line stays unflagged, planted notes are still found, and the
    estimated correction is reported."""
    first = 0.5 + err_ms / 1000
    ok = server.tool_lead_check(str(lead_files["stem"]), str(lead_files["correct"]), source="stem", pyin=False,
                                bpm=120.0, first_downbeat_sec=first, out_dir=str(tmp_path), name=f"ok{err_ms}")
    assert ok["summary"]["counts"]["wrong"] == 0, ok["summary_text"]
    assert ok["summary"]["analysis"]["time_offset"]["global_offset_ms"] is not None
    pl = server.tool_lead_check(str(lead_files["stem"]), str(lead_files["planted"]), source="stem", pyin=False,
                                bpm=120.0, first_downbeat_sec=first, out_dir=str(tmp_path), name=f"pl{err_ms}")
    rows = _rows(pl)
    assert rows[4]["verdict"] == "wrong" and rows[10]["verdict"] == "wrong"
    assert sum(1 for i, x in enumerate(rows) if x["verdict"] == "wrong" and i not in PLANT) == 0
    assert all("time_offset_ms" in x for x in rows)


def test_lead_check_mix_wrong_confidence_is_capped(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["mix"], lead_files["planted"], tmp_path, "cap", source="mix")
    for w in res["summary"]["wrong"]:
        assert w["confidence"] <= 0.6 and w["heard_confidence"] <= 0.6


def test_align_reports_repeat_alternatives(tmp_path, align_files):
    """The original holds the 8 measures twice: with only the tempo fixed and no search_sec both placements score the
    same -> alternatives, a warning that names search_sec, and a lowered confidence."""
    d, rend, meta, orig = align_files
    s = server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="rep", orig_bpm=126.0)["summary"]
    assert s["repeat_ambiguous"] is True and s["alternatives"]
    assert any("search_sec" in w for w in s["warnings"])
    assert s["confidence"] != "높음"


def test_nan_and_early_method_checks(tmp_path, align_files, solo_wav):
    d, rend, meta, orig = align_files
    for kw in ({"offset_sec": float("nan")}, {"tempo_ratio": float("inf")}, {"search_sec": [float("nan"), 10.0]}):
        with pytest.raises(EarError) as e:
            server.tool_align(str(rend), str(meta), str(orig), out_dir=str(tmp_path), name="nan", **kw)
        assert e.value.code == "BAD_ARGUMENT", kw
    with pytest.raises(EarError) as e:
        server.tool_stem_melody_notes(str(tmp_path / "missing.wav"), 120.0, 0.5, method="ridgee")
    assert e.value.code == "BAD_ARGUMENT"


EIGHTHS = [(0.5 * i, 0.5, p) for i, p in enumerate([67, 71, 69, 72, 71, 74, 72, 76, 74, 71, 72, 69, 71, 67, 69, 64])]


@pytest.mark.parametrize("err_ms", [-60, 60])
def test_lead_check_estimates_time_offset_on_fast_notes(tmp_path, err_ms):
    """Legato eighth notes: the reported correction points the right way (a late map is undone within 30 ms; an early
    map is under-corrected because the 186 ms analysis frames blur the note starts, but never moved the wrong way),
    and nothing is flagged."""
    d = tmp_path / "e"
    stem = synth.write_wav(d / "eighths.wav", synth.vocal_stem(EIGHTHS, gate=0.98))
    nj = notes_json(d / "eighths.json", EIGHTHS)
    res = server.tool_lead_check(str(stem), str(nj), source="stem", pyin=False, bpm=120.0,
                                 first_downbeat_sec=0.5 + err_ms / 1000, out_dir=str(tmp_path), name="e8")
    off = res["summary"]["analysis"]["time_offset"]["global_offset_ms"]
    if err_ms > 0:
        assert abs(off + err_ms) <= 30, off
    else:
        assert 0 <= off <= 90, off
    assert res["summary"]["counts"]["wrong"] == 0, res["summary_text"]


# ---------------------------------------------------------------- lead_check with a vocal transcription

def _write_game(path: Path, rows):
    path.write_text("onset,offset,pitch\n" + "".join(f"{a:.3f},{b:.3f},{p:.3f}\n" for a, b, p in rows),
                    encoding="utf-8")
    return path


def test_lead_check_transcription_turns_contradicted_wrong_into_uncertain(tmp_path, lead_files):
    # the transcription "hears" the score's (planted) pitch at index 4 and the sung pitch everywhere else
    rows = []
    for i, (s, ln, p) in enumerate(PHRASE):
        sung = PLANT.get(4) if i == 4 else p
        rows.append((0.5 + s * 0.5 + 0.01, 0.5 + (s + ln) * 0.5 - 0.02, sung + 0.02))
    game = _write_game(tmp_path / "t.csv", rows)
    base = _lead(lead_files, lead_files["stem"], lead_files["planted"], tmp_path, "nt")
    res = _lead(lead_files, lead_files["stem"], lead_files["planted"], tmp_path, "wt", transcription=str(game),
                transcription_key="none")
    a, b = _rows(base), _rows(res)
    assert a[4]["verdict"] == "wrong" and b[4]["verdict"] == "uncertain"
    assert "transcription_disagrees" in b[4]["reasons"]
    assert b[10]["verdict"] == "wrong" and b[10]["transcription_pitch"]["name"] == "E4"
    t = res["summary"]["transcription"]
    assert t["wrong_to_uncertain"] == 1 and t["uncertain_to_wrong"] == 0 and t["notes_with_transcription"] == 14
    # without the argument nothing changes and no transcription fields are written
    assert base["summary"]["transcription"] is None and "transcription_pitch" not in a[0]


def test_apply_transcription_confirms_uncertain():
    from mabiear.leadcheck import apply_transcription

    rows = [{"pos": "1:1:0", "sec": 1.0, "sec_end": 1.5, "pitch": 74, "verdict": "uncertain", "heard_pc": 4,
             "heard_confidence": 0.6, "confidence": 0.3, "reasons": ["small_margin"]},
            {"pos": "1:2:0", "sec": 1.5, "sec_end": 2.0, "pitch": 74, "verdict": "uncertain", "heard_pc": 4,
             "heard_confidence": 0.6, "confidence": 0.3, "reasons": ["small_margin"]},
            {"pos": "1:3:0", "sec": 2.0, "sec_end": 2.5, "pitch": 74, "verdict": "agree", "heard_pc": 2,
             "confidence": 0.9, "reasons": []}]
    trans = [{"on": 1.02, "off": 1.45, "pitch": 64.0, "p": 64},      # E: agrees with the heard class -> wrong
             {"on": 1.52, "off": 1.95, "pitch": 66.0, "p": 66},      # F#: a third reading -> stays uncertain
             {"on": 2.02, "off": 2.45, "pitch": 62.0, "p": 62}]
    info = apply_transcription(rows, trans)
    assert rows[0]["verdict"] == "wrong" and "transcription_confirms" in rows[0]["reasons"]
    assert rows[1]["verdict"] == "uncertain" and rows[2]["verdict"] == "agree"
    assert info == {"notes_with_transcription": 3, "wrong_to_uncertain": 0, "uncertain_to_wrong": 1}


def test_lead_check_warns_at_time_search_edge_and_high_side_k(tmp_path, lead_files):
    res = _lead(lead_files, lead_files["mix"], lead_files["correct"], tmp_path, "hk", source="mix", side_k=0.8)
    assert any("side_k 0.8" in w for w in res["summary"]["warnings"])
    d = tmp_path / "edge"
    stem = synth.write_wav(d / "eighths.wav", synth.vocal_stem(EIGHTHS, gate=0.98))
    nj = notes_json(d / "eighths.json", EIGHTHS)
    for err in (0.2, -0.2):   # 200 ms off: beyond the +-120 ms the tool can correct
        far = server.tool_lead_check(str(stem), str(nj), source="stem", pyin=False, bpm=120.0,
                                     first_downbeat_sec=0.5 + err, out_dir=str(tmp_path), name="edge", overwrite=True)
        to = far["summary"]["analysis"]["time_offset"]
        assert to["at_search_edge"] is True and abs(to["global_offset_ms"]) == 120
        assert any("align" in w and "120ms" in w for w in far["summary"]["warnings"])
    ok = server.tool_lead_check(str(stem), str(nj), source="stem", pyin=False, bpm=120.0, first_downbeat_sec=0.5,
                                out_dir=str(tmp_path), name="noedge")
    assert ok["summary"]["analysis"]["time_offset"]["at_search_edge"] is False


def test_apply_transcription_does_not_upgrade_weak_or_unsure_notes():
    from mabiear.leadcheck import apply_transcription

    base = {"sec": 1.0, "sec_end": 1.5, "pitch": 74, "verdict": "uncertain", "heard_pc": 4, "confidence": 0.2}
    rows = [dict(base, pos="a", heard_confidence=0.6, reasons=["weak"]),          # weak signal only
            dict(base, pos="b", heard_confidence=0.35, reasons=["small_margin"])]  # tool unsure of its pitch
    trans = [{"on": 1.0, "off": 1.5, "pitch": 64.0, "p": 64}]
    info = apply_transcription(rows, trans)
    assert [x["verdict"] for x in rows] == ["uncertain", "uncertain"]
    assert rows[0]["transcription_pitch"]["name"] == "E4" and rows[0]["transcription_agrees"] is False
    assert info["uncertain_to_wrong"] == 0


def test_apply_transcription_ignores_a_neighbour_that_only_touches_the_window():
    from mabiear.leadcheck import apply_transcription

    rows = [{"pos": "x", "sec": 1.0, "sec_end": 1.5, "pitch": 69, "verdict": "wrong", "heard_pc": 11,
             "heard_confidence": 0.9, "confidence": 0.9, "reasons": []}]
    # the neighbour (sung A, the score's wrong pitch) runs 40 ms into the note; the note itself is sung B
    trans = [{"on": 0.5, "off": 1.04, "pitch": 69.0, "p": 69}, {"on": 1.04, "off": 1.5, "pitch": 71.0, "p": 71}]
    info = apply_transcription(rows, trans)
    assert rows[0]["verdict"] == "wrong" and rows[0]["transcription_pitch"]["name"] == "B4"
    assert info["wrong_to_uncertain"] == 0


def test_lead_check_planted_neighbour_pitch_stays_wrong_with_a_correct_transcription(tmp_path, lead_files):
    """Planted wrong pitch = the previous sung pitch (index 2 written as 69, sung 71 after a sung 69); a correct
    transcription whose previous note runs 60 ms late into this one must not hide the error."""
    d = lead_files["dir"]
    nj = notes_json(d / "planted_neighbour.json", PHRASE, {2: 69})
    rows = []
    for i, (st, ln, p) in enumerate(PHRASE):
        on, off = 0.5 + st * 0.5, 0.5 + (st + ln) * 0.5
        if i == 1:
            off += 0.06
        if i == 2:
            on += 0.06
        rows.append((on, off, p + 0.02))
    game = _write_game(tmp_path / "neighbour.csv", rows)
    base = _rows(_lead(lead_files, lead_files["stem"], nj, tmp_path, "nb0"))
    assert base[2]["verdict"] == "wrong"
    res = _lead(lead_files, lead_files["stem"], nj, tmp_path, "nb1", transcription=str(game), transcription_key="none")
    x = _rows(res)[2]
    assert x["verdict"] == "wrong", x
    assert x["transcription_pitch"]["name"] == "B4" and x["transcription_agrees"] is False
    assert res["summary"]["transcription"]["wrong_to_uncertain"] == 0
