"""stem_melody_notes (synthetic vocal stems)."""
from __future__ import annotations

import json

import pytest

import synth
from mabiear import server
from mabiear.io import EarError

# C major phrase, quarter positions from the first downbeat (120 BPM: quarter = 0.5 s)
PHRASE = [(0, 1, 67), (1, 1, 69), (2, 1, 71), (3, 1, 72), (4, 2, 74), (6, 0.5, 72), (6.5, 0.5, 71), (7, 1, 69),
          (8, 1, 67), (9, 1, 67), (10, 2, 64), (12, 1, 62), (13, 1, 64), (14, 2, 67)]


def _truth_ticks():
    return [(int(s * 96), int(l * 96), p) for s, l, p in PHRASE]


@pytest.fixture(scope="module")
def vocal_wav(tmp_path_factory):
    d = tmp_path_factory.mktemp("vocal")
    return synth.write_wav(d / "vocal.wav", synth.vocal_stem(PHRASE))


@pytest.fixture(scope="module")
def doubled_wav(tmp_path_factory):
    d = tmp_path_factory.mktemp("vocal2")
    # a quieter voice an octave below (gain 0.3) must not pull the line down
    return synth.write_wav(d / "doubled.wav", synth.vocal_stem(PHRASE, double=(-12, 0.3)))


def _match(notes, tol=24):
    out = []
    for t, ln, p in _truth_ticks():
        c = [n for n in notes if abs(n["tick"] - t) <= tol]
        out.append((p, min(c, key=lambda n: abs(n["tick"] - t)) if c else None))
    return out


def test_melody_salience_exact(vocal_wav):
    res = server.tool_stem_melody_notes(str(vocal_wav), 120, 0.5, method="salience")
    R = res["result"]
    notes = R["notes"]
    m = _match(notes)
    found = [(p, n) for p, n in m if n is not None]
    assert len(found) == 14  # the repeated G (quarters 8 and 9, 50 ms gap) stays two notes (level dip)
    exact = sum(1 for p, n in found if n["pitch"] == p)
    assert exact / len(found) >= 0.9
    # monophonic, on the 16th grid, lengths at least one step
    ticks = [n["tick"] for n in notes]
    assert ticks == sorted(ticks) and len(set(ticks)) == len(ticks)
    for a, b in zip(notes, notes[1:]):
        assert a["tick"] + a["length_ticks"] <= b["tick"]
    assert all(n["tick"] % 24 == 0 and n["length_ticks"] >= 24 for n in notes)
    assert {"pitch", "pos", "length_ticks", "confidence", "source"} <= set(notes[0])
    assert notes[0]["pos"] == "1:1:0"
    assert R["coverage"]["voiced_pct"] > 50 and R["coverage"]["note_time_pct"] > 50
    assert R["counts"]["notes"] == len(notes) and len(R["placements"]) == len(notes)
    assert R["sections_auto"] and R["sections"][0]["notes"] == sum(1 for n in notes if n["measure"] <= 8)
    assert sum(x["notes"] for x in R["per_measure"]) == len(notes)
    assert abs(R["track"]["tuning_cents"]) < 20


def test_melody_long_note_and_vibrato(vocal_wav):
    notes = server.tool_stem_melody_notes(str(vocal_wav), 120, 0.5, method="salience")["result"]["notes"]
    d = [n for n in notes if n["tick"] == 384]  # D5 half note with vibrato -> one note, not split
    assert d and d[0]["pitch"] == 74 and d[0]["length_ticks"] >= 144


def test_melody_doubled_octave_keeps_dominant(doubled_wav):
    notes = server.tool_stem_melody_notes(str(doubled_wav), 120, 0.5, method="salience")["result"]["notes"]
    found = [(p, n) for p, n in _match(notes) if n is not None]
    assert len(found) >= 13
    assert sum(1 for p, n in found if n["pitch"] == p) / len(found) >= 0.9
    # the doubled octave is reported
    assert sum(1 for p, n in found if n["octave_ambiguous"]) >= 10


def test_melody_measure_offset_and_grid32(vocal_wav):
    R = server.tool_stem_melody_notes(str(vocal_wav), 120, 0.5, grid=32, measure_offset=3, method="salience",
                                      sections=[{"name": "A", "from_measure": 3, "to_measure": 4}])["result"]
    assert R["notes"][0]["pos"] == "3:1:0" and R["grid"]["grid_ticks"] == 12
    assert all(n["tick"] % 12 == 0 for n in R["notes"])
    assert R["sections"][0]["name"] == "A" and not R["sections_auto"]


def test_melody_auto_and_out_json(vocal_wav, tmp_path):
    out = tmp_path / "mel.json"
    res = server.tool_stem_melody_notes(str(vocal_wav), 120, 0.5, method="auto", out_json=str(out))
    assert res["out_path"] == str(out)
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.stem_melody/1" and doc["tool"] == "stem_melody_notes"
    assert "pyin_voiced_pct" in doc["track"]
    assert any(n["pyin_agree"] is not None for n in doc["notes"])
    assert res["summary"]["counts"]["notes"] == len(doc["notes"])
    with pytest.raises(EarError) as e:
        server.tool_stem_melody_notes(str(vocal_wav), 120, 0.5, out_json=str(out))
    assert e.value.code == "EXISTS"


@pytest.mark.parametrize("kw", [{"method": "crepe"}, {"range_low": 70, "range_high": 75}, {"grid": 8},
                                {"min_note_ticks": 3}, {"merge_gap_ticks": -1}, {"bpm": "120"}])
def test_melody_bad_args(vocal_wav, kw):
    args = {"bpm": 120, **kw}
    bpm = args.pop("bpm")
    with pytest.raises(EarError) as e:
        server.tool_stem_melody_notes(str(vocal_wav), bpm, 0.5, **args)
    assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- stem_chords

PROG = [["C4", "E4", "G4"], ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["B3", "D4", "G4"]] * 2
ROOTS = [36, 33, 29, 31] * 2  # C2 A1 F1 G1


@pytest.fixture(scope="module")
def chord_wavs(tmp_path_factory):
    d = tmp_path_factory.mktemp("chords")
    harm = synth.song(PROG, 120, lead=0.5, tail=1.0)
    bass = synth.bass_stem([(4 * i, 4, p) for i, p in enumerate(ROOTS)], bpm=120, first=0.5, tail=1.0)
    n = min(harm.size, bass.size)
    voc = synth.vocal_stem([(0, 16, 76), (16, 16, 79)], bpm=120, first=0.5, tail=1.0)
    voc = voc[:n] if voc.size >= n else synth.np.pad(voc, (0, n - voc.size))
    mix = harm[:n] + voc
    return (synth.write_wav(d / "harm.wav", harm[:n]), synth.write_wav(d / "bass.wav", bass[:n]),
            synth.write_wav(d / "vocal.wav", voc), synth.write_wav(d / "mix.wav", mix))


def test_chords_progression_and_bass(chord_wavs):
    harm, bass, _, _ = chord_wavs
    R = server.tool_stem_chords([str(harm)], 120, 0.5, bass_stem_path=str(bass))["result"]
    ms = R["measures"]
    assert [m["chord"] for m in ms[:8]] == ["C", "Am", "F", "G"] * 2
    assert all(m["bass_agree"] for m in ms[:8])
    assert R["key"]["estimated"] in ("C major", "A minor")
    assert R["counts"]["bass_agree_pct"] == 100.0
    assert len(R["beats"]) == 4 * R["grid"]["measures"] and R["beats"][0]["pos"] == "1:1:0"
    for m in ms[:8]:
        v = m["voicing"]
        assert len(v) == 3 and 48 <= min(v) and max(v) <= 72
        third = 3 if m["quality"] == "min" else 4
        assert {p % 12 for p in v} == {(m["root_pc"] + x) % 12 for x in (0, third, 7)}
    # least movement between consecutive voicings (C -> Am shares two notes)
    a, b = ms[0]["voicing"], ms[1]["voicing"]
    assert sum(abs(x - y) for x, y in zip(a, b)) <= 4
    assert len(R["placements"]) == 3 * sum(1 for m in ms if m["voicing"])


def test_chords_given_key_four_voices_no_beats(chord_wavs):
    harm, bass, _, _ = chord_wavs
    R = server.tool_stem_chords([str(harm)], 120, 0.5, key="C major", voicing_size=4, voicing_range=[55, 79],
                                per_beat=False, qualities=["maj", "min"])["result"]
    assert R["key"]["used"] == "C major" and R["key"]["given"]
    assert R["beats"] is None
    assert [m["chord"] for m in R["measures"][:4]] == ["C", "Am", "F", "G"]
    assert all(len(m["voicing"]) == 4 and 55 <= min(m["voicing"]) and max(m["voicing"]) <= 79
               for m in R["measures"][:4])
    assert all(m["bass_agree"] is None for m in R["measures"])


def test_chords_mix_minus_vocal(chord_wavs, tmp_path):
    _, bass, voc, mix = chord_wavs
    out = tmp_path / "ch.json"
    res = server.tool_stem_chords([str(mix)], 120, 0.5, subtract_paths=[str(voc)], bass_stem_path=str(bass),
                                  out_json=str(out))
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.stem_chords/1"
    assert [m["chord"] for m in doc["measures"][:4]] == ["C", "Am", "F", "G"]
    assert res["summary"]["measures"][0]["chord"] == "C"


def test_parse_key():
    from mabiear.chords import parse_key

    assert parse_key("auto") is None
    assert parse_key("F minor") == (5, "minor") and parse_key("F#m") == (6, "minor")
    assert parse_key("Bb major") == (10, "major") and parse_key("C") == (0, "major")
    with pytest.raises(EarError):
        parse_key("H dur")


@pytest.mark.parametrize("kw", [{"qualities": ["maj", "add9"]}, {"voicing_range": [60, 65]}, {"voicing_size": 5},
                                {"key": "Xb"}, {"paths": []}, {"per_beat": "yes"}])
def test_chords_bad_args(chord_wavs, kw):
    harm = str(chord_wavs[0])
    args = {"paths": [harm], **kw}
    paths = args.pop("paths")
    with pytest.raises(EarError) as e:
        server.tool_stem_chords(paths, 120, 0.5, **args)
    assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- structure_repeats

def _chord_measure(midis, bpm=120.0, bright=False, sr=22050, seed=0):
    np = synth.np
    beat = 60.0 / bpm
    n = int(round(4 * beat * sr))
    t = np.arange(n) / sr
    env = 0.25 + 0.75 * np.exp(-np.mod(t, beat) * 4.0)
    y = np.zeros(n)
    for p in midis:
        f = 440.0 * 2 ** ((p - 69) / 12)
        y += np.sin(2 * np.pi * f * t)
        if bright:
            for k in (2, 3, 4, 5):
                y += 0.5 / k * np.sin(2 * np.pi * f * k * t)
    if bright:
        y += 0.3 * np.random.default_rng(seed).standard_normal(n) * np.exp(-np.mod(t, beat / 2) * 30)
    return (0.2 * env * y).astype(np.float32)


A_SEC = [(60, 64, 67), (57, 60, 64), (53, 57, 60), (55, 59, 62)]
B_SEC = [(62, 65, 69), (64, 67, 71), (65, 69, 72), (67, 71, 74)]


@pytest.fixture(scope="module")
def form_wav(tmp_path_factory):
    np = synth.np
    d = tmp_path_factory.mktemp("form")
    ms = ([_chord_measure(c) for c in A_SEC] + [_chord_measure(c) for c in B_SEC] + [_chord_measure(c) for c in A_SEC]
          + [_chord_measure(tuple(p + 2 for p in c)) for c in B_SEC]
          + [_chord_measure(c, bright=True, seed=i) for i, c in enumerate(A_SEC)])
    y = np.concatenate([synth.silence(0.5)] + ms + [synth.silence(1.0)])
    return synth.write_wav(d / "form.wav", y)


def _entry(cmap, t0):
    return next((e for e in cmap if e["target"][0] <= t0 <= e["target"][1]), None)


def test_structure_exact_transposed_harmony(form_wav):
    R = server.tool_structure_repeats([str(form_wav)], 120, 0.5, thresholds={"chroma": 0.9, "mfcc": 0.6})["result"]
    assert R["grid"]["measures"] == 20
    cmap = R["copy_map"]
    e = _entry(cmap, 9)
    assert e["kind"] == "exact" and e["target"] == [9, 12] and e["source"] == [1, 4] and e["transpose"] == 0
    e = _entry(cmap, 13)
    assert e["kind"] == "transposed" and e["transpose"] == 2 and e["source"][0] == 5 and e["target"] == [13, 16]
    e = _entry(cmap, 17)
    assert e["kind"] == "harmony" and e["target"] == [17, 20] and e["source"][0] in (1, 9)
    assert R["thresholds"]["mode"] == "given" and R["thresholds"]["chroma"] == 0.9
    assert R["coverage"]["by_kind"]["exact"] == 4 and R["coverage"]["by_kind"]["transposed"] == 4
    assert all(x["measures"] >= 4 for x in R["runs"])
    assert len(R["per_measure"]) == 20 and R["per_measure"][8]["source"] == 1


def test_structure_auto_and_source_limit(form_wav, tmp_path):
    out = tmp_path / "st.json"
    res = server.tool_structure_repeats([str(form_wav)], 120, 0.5, source_measures=[1, 8], out_json=str(out))
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.structure/1" and doc["thresholds"]["mode"] == "auto"
    assert 0.8 <= doc["thresholds"]["chroma"] <= 0.97
    for e in doc["copy_map"]:
        assert 1 <= e["source"][0] and e["source"][1] <= 8 and e["target"][0] > 8
    assert _entry(doc["copy_map"], 9)["source"] == [1, 4]
    assert res["summary"]["coverage"]["copy_target_measures"] >= 8


def test_structure_measure_offset_and_n_measures(form_wav):
    R = server.tool_structure_repeats([str(form_wav)], 120, 0.5, measure_offset=5, n_measures=12,
                                      thresholds={"chroma": 0.9, "mfcc": 0.6})["result"]
    assert R["grid"]["measures"] == 12 and R["per_measure"][0]["measure"] == 5
    assert _entry(R["copy_map"], 13)["source"] == [5, 8]


@pytest.mark.parametrize("kw", [{"thresholds": {"chroma": 2}}, {"thresholds": "fixed"}, {"min_run_measures": 1},
                                {"source_measures": [5, 2]}, {"n_measures": 1}, {"paths": []}])
def test_structure_bad_args(form_wav, kw):
    args = {"paths": [str(form_wav)], **kw}
    paths = args.pop("paths")
    with pytest.raises(EarError) as e:
        server.tool_structure_repeats(paths, 120, 0.5, **args)
    assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- argument hygiene

def test_unknown_argument_names_rejected():
    server.build_server()  # registers ARG_NAMES
    assert "stem_chords" in server.ARG_NAMES and "paths" in server.ARG_NAMES["stem_chords"]
    with pytest.raises(EarError) as e:
        server.check_raw_args("stem_chords", {"paths": ["x"], "bpm": 100, "first_downbeat_sec": 0.5, "tempo": 3,
                                              "bass": "y"})
    assert e.value.code == "BAD_ARGUMENT" and e.value.details["unknown"] == ["bass", "tempo"]
    server.check_raw_args("stem_chords", {"paths": ["x"], "bpm": 100, "first_downbeat_sec": 0.5})
    server.check_raw_args("no_such_tool", {"anything": 1})  # unknown tools are left to the SDK


def test_bad_argument_code_and_alias():
    d = EarError("BAD_ARGUMENT", "m", {"a": 1}).to_dict()
    assert d["error"] == "BAD_ARGUMENT" and d["error_alias"] == "INVALID_ARGS" and d["ok"] is False
    assert "error_alias" not in EarError("FILE_NOT_FOUND", "m").to_dict()
