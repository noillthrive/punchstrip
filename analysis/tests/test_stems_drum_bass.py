"""stem_drum_hits / stem_bass_notes (synthetic stems), drum-27 key table, balance lead margin."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

import pytest

import synth
from mabiear import server, stems
from mabiear.io import EarError


@pytest.fixture(scope="module")
def drum_wav(tmp_path_factory):
    d = tmp_path_factory.mktemp("drums")
    y, truth = synth.drum_stem()
    return synth.write_wav(d / "drums.wav", y, synth.DRUM_SR), truth


def _by(hits, cls):
    return {h["pos"] for h in hits if h["class"] == cls}


def _truth(truth, cls):
    return {f"{m}:{b}:{t}" for c, m, b, t in truth if c == cls}


# ---------------------------------------------------------------- drum table

def test_drum_table_shared_with_bridge():
    t = stems.drum_table()
    keys = [k["key"] for k in t["keys"]]
    assert keys == [41, 43, 45, 47, 48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65]
    ids = [k["id"] for k in t["keys"]]
    assert len(set(ids)) == len(ids)
    lv = {k["id"]: k["level_db_vs_kick"] for k in t["keys"]}
    assert lv["kick"] == 0.0 and lv["low_tom"] == 2.2 and lv["closed_hat"] == -14.0 and lv["ride"] == -16.8
    assert lv["crash_choke"] is None and lv["ride_cup"] is None
    assert stems.DRUM_TABLE_PATH == stems.DRUM_TABLE_PACKAGE  # package data (0.4.1)
    assert stems.DRUM_TABLE_PACKAGE.parts[-3:] == ("mabiear", "data", "drum27_keys.json")


def test_drum_table_package_copy_equals_bridge_copy():
    """mabiear/data/drum27_keys.json must stay byte-identical to the bridge's src-bridge copy."""
    if not stems.DRUM_TABLE_BRIDGE.is_file():
        pytest.skip("bridge source tree not present (release zip)")
    assert stems.DRUM_TABLE_PACKAGE.read_bytes() == stems.DRUM_TABLE_BRIDGE.read_bytes()


def test_velocity_for_key_levels():
    k = stems.key_by_id()
    assert stems.velocity_for(k["kick"], 0.0) == (15, False)
    assert stems.velocity_for(k["closed_hat"], -14.0) == (15, False)
    # hat 10 dB under its own v15 level: 15 * 10^(-10/40) = 8.4
    assert stems.velocity_for(k["closed_hat"], -24.0) == (8, False)
    assert stems.velocity_for(k["snare"], 5.0) == (15, True)  # louder than the key can be
    assert stems.velocity_for(k["ghost"], -60.0)[0] == 1
    assert stems.velocity_for(k["ride_cup"], -5.0) == (15, False)  # no level: table cap


# ---------------------------------------------------------------- drums

def test_drum_hits_classes_and_positions(drum_wav):
    wav, truth = drum_wav
    res = server.tool_stem_drum_hits(str(wav), 120, 0.5)
    R = res["result"]
    assert res["out_path"] is None
    hits = R["hits"]
    for cls in ("kick", "snare"):
        assert _by(hits, cls) == _truth(truth, cls), cls
    hats_t, hats_g = _truth(truth, "closed_hat"), _by(hits, "closed_hat")
    assert not (hats_g - hats_t), hats_g - hats_t  # no hat off the 8th grid
    assert len(hats_g & hats_t) >= 40  # hats under a ringing crash may be missed (documented)
    cr = {c["pos"]: c for c in R["crashes"]}
    assert set(cr) == {"1:1:0", "4:4:0", "7:3:0"}, cr
    assert cr["4:4:0"]["pickup"] is True and cr["1:1:0"]["pickup"] is False
    assert R["tempo_check"]["warning"] is None and abs(R["tempo_check"]["comb_off_pct"]) < 0.5
    k = [h for h in hits if h["class"] == "kick"]
    assert all(h["key"] == 41 and h["velocity"] >= 12 for h in k), [h["velocity"] for h in k]
    assert sorted(h["velocity"] for h in k)[len(k) // 2] == 15  # the kick reference is the p90 isolated kick
    assert all(h["key"] == 45 for h in hits if h["class"] == "snare")
    assert all(h["key"] == 59 and 1 <= h["velocity"] <= 15 for h in hits if h["class"] == "closed_hat")
    assert all(0 <= h["confidence"] <= 1 for h in hits)
    assert all(abs(h["grid_error_ticks"]) <= 12 for h in hits)
    assert R["notes"] and any("Ride vs open hat" in n for n in R["notes"])
    # placements are score_put_notes notes
    pl = R["placements"]
    assert pl and set(pl[0]) >= {"tick", "len", "pitch", "vel"}
    assert {(p["tick"], p["pitch"]) for p in pl if p["pitch"] == 41} == {(h["tick"], 41) for h in k}
    assert R["levels"]["kick_ref_source"] == "isolated_kicks_p90"


def test_drum_hits_offset_grid32_layer_sections(drum_wav):
    wav, _ = drum_wav
    secs = [{"name": "A", "from_measure": 5, "to_measure": 8}, {"name": "B", "from_measure": 9, "to_measure": 12}]
    R = server.tool_stem_drum_hits(str(wav), 120, 0.5, measure_offset=5, grid=32, sections=secs,
                                   low_tom_layer=True)["result"]
    k = [h["pos"] for h in sorted(R["hits"], key=lambda h: h["tick"]) if h["class"] == "kick"]
    assert k[0] == "5:1:0" and k[-1] == "12:3:0" and len(k) == 16
    assert all(h["tick"] % 12 == 0 for h in R["hits"])
    assert all(h["grid"] == "32nd" for h in R["hits"])
    assert R["low_tom_layer_added"] == 16
    lt = [p for p in R["placements"] if p["pitch"] == 43]
    assert len(lt) == 16 and all(p["vel"] == 9 and p["class"] == "low_tom_layer" for p in lt)
    assert [s["name"] for s in R["sections"]] == ["A", "B"]
    assert R["sections"][0]["counts"]["kick"] == 8 and R["sections"][1]["counts"]["snare"] == 8
    assert all(h["section"] in ("A", "B") for h in R["hits"])


def test_drum_hits_tempo_warning_and_min_confidence(drum_wav):
    wav, _ = drum_wav
    R = server.tool_stem_drum_hits(str(wav), 117, 0.5)["result"]
    tc = R["tempo_check"]
    assert tc["warning"] and abs(tc["comb_bpm"] - 120) < 0.5 and tc["comb_off_pct"] > 1.0, tc
    R2 = server.tool_stem_drum_hits(str(wav), 120, 0.5, min_confidence=0.9)["result"]
    assert all(h["confidence"] >= 0.9 for h in R2["hits"])
    assert R2["dropped"]["below_min_confidence"] > 0


def test_drum_hits_out_json(tmp_path, drum_wav):
    wav, _ = drum_wav
    out = tmp_path / "out" / "drums_hits.json"
    res = server.tool_stem_drum_hits(str(wav), 120, 0.5, out_json=str(out))
    assert res["out_path"] == str(out) and "result" not in res
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.stem_drums/1" and doc["tool"] == "stem_drum_hits"
    assert len(doc["hits"]) == res["summary"]["hits"] and doc["crashes"] == res["summary"]["crashes"]
    with pytest.raises(EarError) as e:
        server.tool_stem_drum_hits(str(wav), 120, 0.5, out_json=str(out))
    assert e.value.code == "EXISTS"
    with pytest.raises(EarError) as e:
        server.tool_stem_drum_hits(str(wav), 120, 0.5, out_json=str(wav.parent / "x.json"))
    assert e.value.code == "PROTECTED_PATH"  # next to the input stem


@pytest.mark.parametrize("kw", [{"grid": 24}, {"grid": 16.0}, {"time_signature": "4-4"}, {"time_signature": "4/3"},
                                {"bpm": "120"}, {"measure_offset": 0}, {"min_confidence": 1.5},
                                {"sections": [{"name": "A", "from_measure": 3, "to_measure": 2}]},
                                {"sections": [{"name": "A", "from_measure": 1}]},
                                {"sections": [{"name": "A", "from_measure": 1, "to_measure": 2, "x": 1}]},
                                {"low_tom_layer": "yes"}, {"out_json": "rel.json"}, {"first_downbeat_sec": 999.0}])
def test_drum_hits_invalid_args(drum_wav, kw):
    wav, _ = drum_wav
    a = {"bpm": 120, "first_downbeat_sec": 0.5, **kw}
    with pytest.raises(EarError) as e:
        server.tool_stem_drum_hits(str(wav), **a)
    assert e.value.code == "BAD_ARGUMENT", e.value.to_dict()


def test_stem_raw_arg_kinds():
    server.build_server()  # registers the argument kinds
    with pytest.raises(EarError):
        server.check_raw_args("stem_drum_hits", {"bpm": "78"})
    with pytest.raises(EarError):
        server.check_raw_args("stem_bass_notes", {"grid": 16.5})
    with pytest.raises(EarError):
        server.check_raw_args("stem_drum_hits", {"low_tom_layer": 1})
    server.check_raw_args("stem_drum_hits", {"bpm": 78, "first_downbeat_sec": 0.549, "grid": 16})


# ---------------------------------------------------------------- bass

BASS = [(0, 1.5, 28), (2, 1.5, 35), (4, 1.5, 36), (6, 0.75, 31), (7, 0.75, 30), (8, 3, 39)]


@pytest.fixture(scope="module")
def bass_wav(tmp_path_factory):
    d = tmp_path_factory.mktemp("bass")
    return synth.write_wav(d / "bass.wav", synth.bass_stem(BASS, gate=0.9))


def test_bass_notes_pitches_positions(bass_wav):
    res = server.tool_stem_bass_notes(str(bass_wav), 120, 0.5)
    R = res["result"]
    got = [(n["pos"], n["pitch"]) for n in R["notes"]]
    assert got == [("1:1:0", 28), ("1:3:0", 35), ("2:1:0", 36), ("2:3:0", 31), ("2:4:0", 30), ("3:1:0", 39)], got
    lens = [n["len"] for n in R["notes"]]
    assert abs(lens[0] - 144) <= 24 and abs(lens[-1] - 288) <= 36, lens
    assert all(not n["octave_ambiguous"] for n in R["notes"]), R["notes"]
    roots = {x["measure"]: x["root"] for x in R["measure_roots"]}
    assert roots == {1: "E", 2: "C", 3: "D#"}, R["measure_roots"]
    pl = R["placements"]
    assert [p["pitch"] for p in pl] == [28, 35, 36, 31, 30, 39] and all(6 <= p["vel"] <= 15 for p in pl)
    assert R["limits"]


def test_bass_notes_range_min_len_offset(bass_wav):
    R = server.tool_stem_bass_notes(str(bass_wav), 120, 0.5, range_low=33, range_high=71, measure_offset=3)["result"]
    assert [n["pitch"] for n in R["notes"]] == [35, 36, 39], R["notes"]
    assert R["notes"][0]["pos"] == "3:3:0"
    R = server.tool_stem_bass_notes(str(bass_wav), 120, 0.5, min_note_ticks=200)["result"]
    assert [n["pitch"] for n in R["notes"]] == [39], R["notes"]
    assert R["counts"]["dropped_short"] == 5


def test_bass_octave_ambiguous_weak_fundamental(tmp_path):
    y = synth.bass_stem([(0, 2, 33), (2, 2, 33)], gate=0.9, h=(0.08, 1.0, 0.5))
    wav = synth.write_wav(tmp_path / "weak.wav", y)
    R = server.tool_stem_bass_notes(str(wav), 120, 0.5)["result"]
    assert R["notes"] and any(n["octave_ambiguous"] for n in R["notes"]), R["notes"]


def test_bass_out_json_and_invalid(tmp_path, bass_wav):
    out = tmp_path / "o" / "bass.json"
    res = server.tool_stem_bass_notes(str(bass_wav), 120, 0.5, out_json=str(out))
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schema"] == "mabiear.stem_bass/1" and len(doc["notes"]) == res["summary"]["counts"]["notes"]
    for kw in ({"range_low": 60, "range_high": 40}, {"min_note_ticks": 3}, {"grid": 8}):
        with pytest.raises(EarError) as e:
            server.tool_stem_bass_notes(str(bass_wav), 120, 0.5, **kw)
        assert e.value.code == "BAD_ARGUMENT"


# ---------------------------------------------------------------- balance: pitched percussion, lead margin

def _split_meta(tmp_path, entries):
    """entries: [(track, name, parts, signal, info)] -> split meta (track or part files)."""
    d = tmp_path / "in"
    files, tracks, seen = [], [], set()
    for i, (tr, name, parts, y, info) in enumerate(entries):
        p = synth.write_wav(d / f"f{i}.wav", y)
        files.append({"path": str(p), "tracks": [tr], "parts": parts})
        if tr not in seen:
            seen.add(tr)
            tracks.append({"track": tr, "name": name, **info})
    meta = synth.meta_for(120, 2, files=files, split="part" if any(e[2] != "all" for e in entries) else "track")
    meta["tracks"] = tracks
    return synth.write_json(d / "split.json", meta)


def _two(g1_db, g2_db, notes=(["A4"], ["G4"])):
    import numpy as np

    a = synth.song([list(notes[0])], 120) * 10 ** (g1_db / 20)
    b = synth.song([list(notes[1])], 120) * 10 ** (g2_db / 20)
    return np.concatenate([a, b])


LEAD = {"program": 2, "instrument_type": "N"}
XYLO = {"program": 77, "instrument_type": "K"}
DRUM = {"program": 27, "instrument_type": "D"}


def test_balance_xylophone_and_handbell_pitched(tmp_path):
    from mabiear.balance import is_unpitched, pitched_kind

    assert is_unpitched([XYLO]) is False and pitched_kind([XYLO], "all")[0] is True
    assert is_unpitched([{"program": 70, "instrument_type": "P"}]) is False
    assert is_unpitched([{"program": 66, "instrument_type": "P"}]) is True
    assert is_unpitched([{"program": 1090, "instrument_type": "M"}]) is True  # mobile bass drum
    assert is_unpitched([DRUM]) is True and is_unpitched([{"program": 27}]) is True
    meta = _split_meta(tmp_path, [(1, "Chords", "all", synth.song([["C4", "E4", "G4"], ["A3", "C4", "E4"]], 120),
                                   {"program": 21, "instrument_type": "N"}),
                                  (2, "Xylo", "all", _two(-30, -30), XYLO)])
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"))
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    xy = [p for p in doc["parts"] if p["tracks"] == [2]][0]
    assert xy["pitched"] is True and xy["unpitched"] is False
    assert res["summary"]["counts"]["buried_by_part"] == {"T2 Xylo": 2}, res["summary"]  # skipped as unpitched
    assert doc["params"]["buried_skipped_parts"] == []


def test_balance_lead_margin_sections(tmp_path):
    meta = _split_meta(tmp_path, [(1, "Lead", "all", _two(0, 0), LEAD), (2, "Xylo", "all", _two(-1, -3), XYLO),
                                  (3, "Drum", "all", _two(6, 6), DRUM)])
    secs = [{"name": "A", "from_measure": 1, "to_measure": 1}, {"name": "B", "start_sec": 2.0, "end_sec": 4.0}]
    for lead in (1, "T1", "T1 Lead", "Lead"):
        res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), overwrite=True, lead_part=lead,
                                  sections=secs)
        lm = res["summary"]["lead_margin"]
        assert lm["lead_part"] == ["T1 Lead"] and lm["competitors"] == ["T2 Xylo"], lm  # drum excluded
        a, b = lm["sections"]
        assert abs(a["lead_margin_db"] - 1.0) < 0.3 and a["in_band"] is False and a["status"] == "low", a
        assert abs(b["lead_margin_db"] - 3.0) < 0.3 and b["in_band"] is True and b["status"] == "ok", b
        assert a["main_competitor"] == "T2 Xylo" and b["from_measure"] == 2 and b["to_measure"] == 2
    doc = json.loads(Path(res["out_path"]).read_text(encoding="utf-8"))
    ws = [w for w in doc["warnings"] if w["type"] == "lead_margin_low"]
    assert len(ws) == 1 and ws[0]["section"] == "A" and doc["counts"]["lead_margin_low"] == 1
    assert [m["measure"] for m in doc["lead_margin"]["measures"]] == [1, 2]
    assert "리드 여유" in res["summary_text"]
    # band moved: B (3.0) is now above the band, A (1.0) inside
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), overwrite=True, lead_part=1, sections=secs,
                              margin_band=[0.5, 2.0])
    a, b = res["summary"]["lead_margin"]["sections"]
    assert a["in_band"] is True and b["status"] == "high"
    # no sections: one section over the selected range
    res = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), overwrite=True, lead_part=1)
    (s,) = res["summary"]["lead_margin"]["sections"]
    assert s["name"] == "all" and abs(s["lead_margin_db"] - 2.0) < 0.3 and s["measures"] == 2


def test_balance_lead_margin_split_parts(tmp_path):
    y = _two(0, 0)
    meta = _split_meta(tmp_path, [(1, "Lead", ["melody"], y, LEAD), (1, "Lead", ["chord1"], y, LEAD),
                                  (2, "Xylo", ["melody"], y, XYLO)])
    lm = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), lead_part="T1")["summary"]["lead_margin"]
    assert lm["lead_part"] == ["T1 Lead/melody", "T1 Lead/chord1"]
    assert abs(lm["sections"][0]["lead_margin_db"] - 3.01) < 0.2, lm  # the lead's two parts are summed
    lm = server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), overwrite=True,
                             lead_part="T1/melody")["summary"]["lead_margin"]
    assert lm["lead_part"] == ["T1 Lead/melody"] and "T1 Lead/chord1" in lm["competitors"]
    assert abs(lm["sections"][0]["lead_margin_db"]) < 0.2


@pytest.mark.parametrize("kw", [{"lead_part": "T9"}, {"lead_part": 9}, {"lead_part": True}, {"lead_part": 1.5},
                                {"lead_part": 1, "margin_band": [3.0, 2.0]}, {"lead_part": 1, "margin_band": [1]},
                                {"sections": [{"name": "A", "from_measure": 1, "to_measure": 1}]},
                                {"margin_band": [1.0, 2.0]},
                                {"lead_part": 1, "sections": [{"name": "A", "from_measure": 1}]},
                                {"lead_part": 1, "sections": [{"name": "A", "start_sec": 3.0, "end_sec": 1.0}]},
                                {"lead_part": 1, "sections": []}])
def test_balance_lead_margin_invalid(tmp_path, kw):
    meta = _split_meta(tmp_path, [(1, "Lead", "all", _two(0, 0), LEAD), (2, "Xylo", "all", _two(-1, -3), XYLO)])
    with pytest.raises(EarError) as e:
        server.tool_balance(str(meta), out_dir=str(tmp_path / "out"), **kw)
    assert e.value.code == "BAD_ARGUMENT", e.value.to_dict()
