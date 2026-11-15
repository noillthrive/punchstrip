"""합성 신호와 손으로 쓴 메타 JSON 만들기(테스트 전용)."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 22050
NOTE = {"C4": 261.63, "E4": 329.63, "G4": 392.00, "A3": 220.00, "F3": 174.61, "A4": 440.0,
        "C3": 130.81, "D4": 293.66, "B3": 246.94, "E2": 82.41, "A1": 55.0, "G2": 98.0}


def chord_beats(notes: list[str], beats: int, beat_sec: float, sr: int = SR, amp: float = 0.25) -> np.ndarray:
    """화음을 박마다 다시 치는 사인파(박마다 감쇠)."""
    n = int(round(beats * beat_sec * sr))
    t = np.arange(n) / sr
    tb = np.mod(t, beat_sec)
    env = 0.25 + 0.75 * np.exp(-tb * 4.0)
    y = np.zeros(n)
    for name in notes:
        y += np.sin(2 * np.pi * NOTE[name] * t)
    return (amp * env * y).astype(np.float32)


def silence(sec: float, sr: int = SR) -> np.ndarray:
    return np.zeros(int(round(sec * sr)), dtype=np.float32)


def song(chords: list[list[str]], bpm: float, lead: float = 0.0, tail: float = 0.0, sr: int = SR) -> np.ndarray:
    beat = 60.0 / bpm
    parts = [silence(lead, sr)] + [chord_beats(c, 4, beat, sr) for c in chords] + [silence(tail, sr)]
    return np.concatenate(parts)


def meta_for(bpm: float, n_measures: int, first: int = 1, start: float = 0.0, files=None, split="none",
             wav: str = "render.wav", schema: str = "mabibridge.render/1") -> dict:
    beat = 60.0 / bpm
    ms = []
    for i in range(n_measures):
        s = start + i * 4 * beat
        ms.append({"measure": first + i, "tick": 384 * (first - 1 + i), "start_sec": round(s, 6),
                   "end_sec": round(s + 4 * beat, 6), "beats": [round(s + j * beat, 6) for j in range(4)]})
    return {"schema": schema, "rendered_at": "2026-11-01T00:00:00+09:00", "engine": "synthetic test",
            "project": {"title": "synthetic", "source_path": None},
            "audio": {"sample_rate": SR, "bits": 16, "channels": 1},
            "tempo_map": [{"tick": 0, "bpm": bpm}], "time_signatures": [{"tick": 0, "sig": "4/4"}],
            "measures": ms, "tracks": [], "split": split,
            "files": files if files is not None else [{"path": wav, "tracks": [1], "parts": "all"}]}


def write_wav(path: Path, y: np.ndarray, sr: int = SR) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), y, sr, subtype="PCM_16")
    return path


def write_json(path: Path, obj: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    return path


# ---------------------------------------------------------------- 드럼·베이스 스템(합성)

DRUM_SR = 44100


def _bp_noise(n, lo, hi, rng, sr=DRUM_SR):
    from scipy.signal import butter, sosfilt
    x = rng.standard_normal(n)
    sos = butter(4, [lo, min(hi, sr / 2 - 100)], btype="bandpass", fs=sr, output="sos")
    return sosfilt(sos, x)


def kick(rng, sr=DRUM_SR):
    n = int(0.35 * sr)
    t = np.arange(n) / sr
    f = 50 + 60 * np.exp(-t * 40)
    ph = 2 * np.pi * np.cumsum(f) / sr
    return 0.9 * np.sin(ph) * np.exp(-t * 18)


def snare(rng, sr=DRUM_SR, amp=0.5):
    n = int(0.25 * sr)
    t = np.arange(n) / sr
    body = np.sin(2 * np.pi * 200 * t) * np.exp(-t * 30)
    nz = _bp_noise(n, 1500, 6000, rng, sr) * np.exp(-t * 14)
    nz /= np.max(np.abs(nz)) + 1e-9
    return amp * (0.6 * body + 0.8 * nz)


def hat(rng, sr=DRUM_SR, amp=0.25, decay=0.03):
    n = int(0.15 * sr)
    t = np.arange(n) / sr
    nz = _bp_noise(n, 7000, 16000, rng, sr)
    nz /= np.max(np.abs(nz)) + 1e-9
    return amp * nz * np.exp(-t / decay)


def crash(rng, sr=DRUM_SR, amp=0.5):
    n = int(2.0 * sr)
    t = np.arange(n) / sr
    nz = _bp_noise(n, 3000, 16000, rng, sr)
    nz /= np.max(np.abs(nz)) + 1e-9
    return amp * nz * np.exp(-t * 1.8)


def drum_stem(bpm=120.0, first=0.5, measures=8, crashes=((1, 1), (4, 4), (7, 3)), sr=DRUM_SR, seed=1, tail=2.0):  # noqa: E501
    """4/4: kick beats 1, 3; snare 2, 4; closed hat every 8th; crashes at (measure, beat)."""
    rng = np.random.default_rng(seed)
    q = 60.0 / bpm
    n = int((first + measures * 4 * q + tail) * sr)
    y = np.zeros(n)

    def put(sig, sec):
        a = int(round(sec * sr))
        b = min(n, a + sig.size)
        y[a:b] += sig[: b - a]

    truth = []
    for m in range(measures):
        for bt in range(4):
            s = first + (m * 4 + bt) * q
            if bt in (0, 2):
                put(kick(rng, sr), s)
                truth.append(("kick", m + 1, bt + 1, 0))
            else:
                put(snare(rng, sr), s)
                truth.append(("snare", m + 1, bt + 1, 0))
            for e in (0, 0.5):
                put(hat(rng, sr), s + e * q)
                truth.append(("closed_hat", m + 1, bt + 1, int(e * 96)))
    for (m, bt) in crashes:
        put(crash(rng, sr), first + ((m - 1) * 4 + bt - 1) * q)
        truth.append(("crash", m, bt, 0))
    return (0.5 * y / max(1e-9, np.max(np.abs(y)))).astype(np.float32), truth


def bass_tone(midi, dur, sr=22050, amp=0.4, h=(1.0, 0.5, 0.25)):
    n = int(dur * sr)
    t = np.arange(n) / sr
    f = 440.0 * 2 ** ((midi - 69) / 12)
    env = np.minimum(1, t / 0.01) * np.exp(-t * 1.5)
    rel = np.minimum(1, (dur - t) / 0.03)
    y = sum(a * np.sin(2 * np.pi * f * (k + 1) * t) for k, a in enumerate(h))
    return amp * y * env * np.clip(rel, 0, 1)


def bass_stem(notes, bpm=120.0, first=0.5, sr=22050, tail=1.0, gate=0.95, h=(1.0, 0.5, 0.25)):
    """notes: [(start_quarter, len_quarter, midi)] from the first downbeat; gate = sounding fraction of each note."""
    q = 60.0 / bpm
    end = max(s + l for s, l, _ in notes)
    n = int((first + end * q + tail) * sr)
    y = np.zeros(n)
    for s, l, p in notes:
        sig = bass_tone(p, l * q * gate, sr, h=h)
        a = int(round((first + s * q) * sr))
        y[a:a + sig.size] += sig[: n - a]
    return y.astype(np.float32)


# ---------------------------------------------------------------- 노래 스템(합성)

def voice_tone(midi, dur, sr=22050, amp=0.3, vibrato=0.35, partials=8, seed=0):
    """Sung-like tone: harmonic partials (1/k roll-off), 5.5 Hz vibrato of +-vibrato semitones, soft attack."""
    n = int(dur * sr)
    t = np.arange(n) / sr
    f = 440.0 * 2 ** ((midi - 69 + vibrato * np.sin(2 * np.pi * 5.5 * t) * np.minimum(1, t / 0.15)) / 12)
    ph = 2 * np.pi * np.cumsum(f) / sr
    y = sum((1.0 / k) * np.sin(k * ph) for k in range(1, partials + 1) if k * f.max() < sr / 2)
    env = np.minimum(1, t / 0.03) * np.minimum(1, np.maximum(0, (dur - t) / 0.04))
    return amp * y * env


def vocal_stem(notes, bpm=120.0, first=0.5, sr=22050, tail=1.0, gate=0.9, double=None, noise_db=-45.0, seed=3):
    """notes: [(start_quarter, len_quarter, midi)]; double = (semitones, gain) adds a quieter doubled voice."""
    rng = np.random.default_rng(seed)
    q = 60.0 / bpm
    end = max(s + l for s, l, _ in notes)
    n = int((first + end * q + tail) * sr)
    y = np.zeros(n)
    for s, l, p in notes:
        sig = voice_tone(p, l * q * gate, sr)
        if double is not None:
            sig = sig + double[1] * voice_tone(p + double[0], l * q * gate, sr, vibrato=0.2)
        a = int(round((first + s * q) * sr))
        y[a:a + sig.size] += sig[: n - a]
    y += 10 ** (noise_db / 20) * rng.standard_normal(n)
    return y.astype(np.float32)
