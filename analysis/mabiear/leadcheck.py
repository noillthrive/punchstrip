"""Note-level check of the score's lead line against the original recording (``lead_check``).

For every lead note of the score: which pitch the original has while that note sounds (pitch class, octave-insensitive
comparison), a confidence and a verdict **agree / wrong (with the pitch heard) / uncertain**. No transcription of
our render is needed: the notes come from the score (``scorenotes``), so only the original is analysed.

Method (rules only, numpy/scipy/librosa):

1. Original at 22.05 kHz. Full mix (``source: "mix"``, stereo): mid/side enhancement
   ``A = max(|STFT(mid)| - k |STFT(side)|, 0)``; ``side_k`` "auto" (default) tries k = 0, 0.3, 0.5, 0.8, 1.0 on the
   densest 40 s of notes and keeps the k under which the most score notes lead their octave window (q >= 0.85, see 5;
   then the smallest k within 0.05 of the best share: deterministic, and a larger k must clearly pay). On the
   evaluation mix 0.8 was much worse than 0.3-0.5 (the voice has a wide stereo part).
   A stem (``source: "stem"``) or a mono file uses ``|STFT(mono)|``. STFT 4096 / hop 128 (5.8 ms), Hann.
2. Whitening: ``W = log1p(A / (median of A over 31 frequency bins + eps))`` (removes the spectral envelope, so the
   harmonic peaks of the voice stand out of a dense mix), then a 3-bin maximum (small detuning / vibrato).
3. Harmonic-sum salience on a 0.2-semitone pitch grid: ``S1(p) = sum_{h=1..8} w_h W(h f_p)`` (w = 1 for h <= 5, 0.7
   above) and a second pass without the fundamental ``S2(p) = sum_{h=2..10} w_h W(h f_p)``: a strong accompaniment
   line can win S1 through its fundamental alone, the voice's upper partials still carry S2.
4. Register and tuning: the octave relation of the original's lead to the score (-36..+24 semitones) and the tuning
   offset (-0.5..0.5 semitone) are the pair under which the score's notes are most salient (S1 at the expected pitch
   relative to the window median, averaged over notes; most notes are assumed right). Every note is then judged in
   the octave window [pitch + shift - 6, pitch + shift + 5]: one candidate per pitch class, in the register where the
   lead actually is (bass and high accompaniment octaves do not compete).
   Ridge: the best line through S1 inside [lowest + shift - 7, highest + shift + 7] with one unvoiced state (log-score
   dynamic programming: about 0.6 semitone per frame free, leaps cost 5, entering / leaving the unvoiced state 2);
   voicing = logistic of the band level (re the 95th percentile, midpoint -30 dB) x logistic of the salience
   peakiness (midpoint 1.8).
   Time correction (``fit_offsets``): the given map may be off by tens of ms; the correction is fitted from the audio
   (-120..+120 ms globally, +-60 ms more per phrase) and every note is judged with its phrase's correction.
5. Per score note (the stable middle of the note: 15 % / 30 ms after its start to 20 % / 30 ms before its end):
   q[c] = mean of e1[c] / max(e1) and e2[c] / max(e2), where e1 / e2 = mean over frames of the best S1 / S2 within
   +-0.4 semitone of the candidate of class c; ridge share = voiced ridge frames on c. Judged on the whole span and on
   the steady part (after the first 35 %, at most 120 ms); the steady part decides (a scoop into the right pitch is
   not an error; an attack on our pitch followed by another steady pitch is marked ``slide``).
   - **agree**: q[expected] >= 0.85, or the ridge holds the expected class on >= 50 % of its frames and q >= 0.78.
   - **wrong**: q[expected] < 0.78 and the class heard is >= 0.1 higher in q. The class heard = the ridge's class when
     it holds it on >= 30 % and q >= 0.85 there, else the best q. Octave of the heard note from the ridge.
   - **uncertain**: weak evidence (fewer than max(6, 25 %) voiced ridge frames, or 30 dB below the span), q between
     the thresholds (``small_margin``), or the ridge alone on another class (``ridge_other``: on a dense mix it follows
     chord tones).
6. Optional pYIN on a stem (``pyin``): per note the share of confident pYIN frames on each class; it confirms or
   breaks a tie (uncertain -> agree/wrong only when >= 70 % of >= 6 confident frames agree) and downgrades a verdict
   it contradicts to uncertain (``pyin_disagrees``).
7. Optional REPET-like background removal (``repet``, off): the median of the 30 most similar frames >= 1 s away is
   removed with a soft mask. It removed too much of the voice on the evaluation mix, so it stays off by default.
"""
from __future__ import annotations

import logging
import math
import time
from pathlib import Path

from . import io
from .io import EarError, r

log = logging.getLogger("mabiear.leadcheck")

SCHEMA_LEAD = "mabiear.lead_check/1"
SR = 22050
NFFT = 4096
HOP = 128
BPS = 5                    # pitch grid: bins per semitone (0.2 semitone)
WHITEN_BINS = 31
CHUNK_SEC = 30.0
CHUNK_PAD = 1.0
H1 = tuple(range(1, 9))
H2 = tuple(range(2, 11))
SOURCES = ("mix", "stem")
SIDE_K_CANDIDATES = (0.0, 0.3, 0.5, 0.8, 1.0)
SIDE_K_MARGIN = 0.05        # auto: a larger k must beat the smaller ones by this share of notes
VERDICTS = ("agree", "wrong", "uncertain")

LIMITS = [
    "Pitch class only: the octave of the original is reported (heard_midi) but never counted as an error.",
    "One line: when two voices sing (harmony, doubled lead) the stronger one decides; a harmony note under the melody "
    "can make a correct note 'wrong' or 'uncertain'.",
    "Rhythm, chords and balance are not checked; a note is judged only while it sounds (attack and release margins "
    "removed), so an early or late note is not an error unless its pitch disagrees.",
    "A full mix needs a centre-panned lead (mid/side); a lead panned to one side or doubled by a loud centred "
    "instrument is weak evidence. A stem is better when available.",
    "The time map must be right to within about one sixteenth; check align / first_downbeat_sec first.",
]


def _w(h: int) -> float:
    return 1.0 if h <= 5 else 0.7


def midi_hz(m):
    return 440.0 * 2.0 ** ((m - 69.0) / 12.0)


def load_stereo(path: Path, t0: float, t1: float, sr: int = SR):
    """Channels (n, ch) float32 resampled to sr between t0 and t1 seconds (clipped to the file)."""
    import numpy as np

    sf = io._sf()
    info = io.probe(path)
    native = info["sample_rate"]
    a = max(0, int(math.floor(t0 * native)))
    b = min(info["frames"], int(math.ceil(t1 * native)))
    if b <= a:
        raise EarError("BAD_ARGUMENT", "음들의 시각이 오디오 밖입니다(시간 지도를 확인).",
                       {"from_sec": r(t0), "to_sec": r(t1), "duration_sec": r(info["duration_sec"])})
    try:
        data, nsr = sf.read(str(path), dtype="float32", always_2d=True, start=a, stop=b)
    except Exception as e:
        raise EarError("AUDIO_READ_FAILED", f"오디오를 읽지 못했습니다: {path}", {"path": str(path), "reason": str(e)})
    if nsr != sr:
        import librosa

        data = np.stack([librosa.resample(data[:, c], orig_sr=nsr, target_sr=sr, res_type="soxr_hq")
                         for c in range(data.shape[1])], axis=1).astype(np.float32)
    return data, a / native


def _harm_index(pitches, harmonics, df, nbins):
    import numpy as np

    out = []
    for h in harmonics:
        pos = h * midi_hz(pitches) / df
        ok = pos < nbins - 2
        lo = np.minimum(np.floor(pos).astype(int), nbins - 2)
        out.append((h, lo, pos - lo, ok))
    return out


REPET_K = 30
REPET_EXCLUDE_SEC = 1.0
REPET_SUB = 4
REPET_CONTEXT_SEC = 8.0     # audio before / after each block used only to estimate the repeating background


def repet_residual(A, hop: int):
    """Soft-masked residual after removing the accompaniment that repeats inside the block (REPET-like).

    Frames are compared on a 4x coarser grid (cosine of magnitude spectra); the background of a frame is the median
    of its 30 most similar frames at least 1 s away, clipped to the frame; the residual ``max(A - bg, 0)`` gets the
    soft mask ``res^2 / (res^2 + (2 bg)^2)``. A sung line rarely repeats exactly inside one block, the accompaniment
    loop does."""
    import numpy as np

    nb, nf = A.shape
    idx = np.arange(0, nf, REPET_SUB)
    if idx.size < REPET_K + 4:
        return A
    V = A[:, idx]
    V = V / (np.linalg.norm(V, axis=0, keepdims=True) + 1e-12)
    sim = V.T @ V
    tsub = idx * hop / SR
    sim[np.abs(tsub[:, None] - tsub[None, :]) < REPET_EXCLUDE_SEC] = -1.0
    k = min(REPET_K, idx.size - 1)
    nbr = np.argpartition(sim, -k, axis=1)[:, -k:]
    bg_sub = np.empty((nb, idx.size), np.float32)
    for j in range(idx.size):
        bg_sub[:, j] = np.median(A[:, idx[nbr[j]]], axis=1)
    near = np.minimum(np.round(np.arange(nf) / REPET_SUB).astype(int), idx.size - 1)
    bg = np.minimum(A, bg_sub[:, near])
    res = np.maximum(A - bg, 0.0)
    mask = res ** 2 / (res ** 2 + (2 * bg) ** 2 + 1e-16)
    return A * mask


def salience_block(x, mode: str, side_k: float, pitches, hop: int = HOP, repet: bool = False):
    """Whitened harmonic sums for one block of audio (n, ch). Returns (S1, S2, level_db, n_frames)."""
    import librosa
    import numpy as np
    from scipy.ndimage import maximum_filter1d, median_filter

    if mode == "mix" and x.shape[1] >= 2:
        mid = 0.5 * (x[:, 0] + x[:, 1])
        side = 0.5 * (x[:, 0] - x[:, 1])
        M = np.abs(librosa.stft(mid, n_fft=NFFT, hop_length=hop, center=True))
        Sd = np.abs(librosa.stft(side, n_fft=NFFT, hop_length=hop, center=True))
        A = np.maximum(M - side_k * Sd, 0.0)
    else:
        A = np.abs(librosa.stft(x.mean(axis=1), n_fft=NFFT, hop_length=hop, center=True))
    df = SR / NFFT
    top = int(min(A.shape[0], math.ceil(9000.0 / df)))
    A = A[:top]
    if repet:
        A = repet_residual(A, hop)
    eps = 1e-6 * float(A.max() + 1e-12) + 1e-9
    floor = median_filter(A, size=(WHITEN_BINS, 1), mode="nearest")
    W = np.log1p(A / (floor + eps)).astype(np.float32)
    Wm = maximum_filter1d(W, size=3, axis=0)
    S1 = np.zeros((pitches.size, A.shape[1]), np.float32)
    S2 = np.zeros_like(S1)
    for hs, S in ((H1, S1), (H2, S2)):
        for h, lo, fr, ok in _harm_index(pitches, hs, df, top):
            v = (1 - fr[:, None]) * Wm[lo] + fr[:, None] * Wm[lo + 1]
            S += _w(h) * np.where(ok[:, None], v, 0.0)
    band = (np.arange(top) * df >= 150) & (np.arange(top) * df <= 5000)
    lev = 10 * np.log10(np.sum(A[band] ** 2, axis=0) + 1e-12)
    return S1, S2, lev, A.shape[1]


def analyse_span(path: Path, t0: float, t1: float, mode: str, side_k: float, lo: int, hi: int, hop: int = HOP,
                 repet: bool = False):
    """Salience over [t0, t1] of the original in chunks. Returns dict t (sec), pitches, S1, S2, level_db."""
    import numpy as np

    pitches = np.arange(lo * BPS, hi * BPS + 1) / BPS
    info = io.probe(path)
    t0 = max(0.0, t0)
    t1 = min(info["duration_sec"], t1)
    ts, s1s, s2s, levs = [], [], [], []
    step = hop / SR
    a = t0
    pad = REPET_CONTEXT_SEC if repet else CHUNK_PAD
    while a < t1 - 1e-6:
        b = min(t1, a + CHUNK_SEC)
        x, start = load_stereo(path, max(0.0, a - pad), min(info["duration_sec"], b + pad))
        S1, S2, lev, nf = salience_block(x, mode, side_k, pitches, hop, repet)
        tt = start + np.arange(nf) * step
        keep = (tt >= a) & (tt < b)
        ts.append(tt[keep])
        s1s.append(S1[:, keep])
        s2s.append(S2[:, keep])
        levs.append(lev[keep])
        a = b
    t = np.concatenate(ts)
    return {"t": t, "pitches": pitches, "S1": np.concatenate(s1s, axis=1), "S2": np.concatenate(s2s, axis=1),
            "level_db": np.concatenate(levs), "channels": info["channels"], "hop": hop}


def ridge_dp(S, v, free: int, jump: float = 5.0, enter: float = 2.0, leave: float = 2.0, sharpen: float = 3.0):
    """Best line through S (bins x frames) with one unvoiced state, in log scores (not probabilities).

    Voiced emission = sharpen * log(S / frame max) + log v (0 at the frame's best bin when v = 1), unvoiced
    log(1 - v). Moving up to ``free`` bins per frame is free, any other move costs ``jump``, entering / leaving the
    unvoiced state costs ``enter`` / ``leave``. Returns the state per frame (-1 unvoiced)."""
    import numpy as np
    from numpy.lib.stride_tricks import sliding_window_view

    nb, nf = S.shape
    emv = sharpen * np.log(np.maximum(S, 1e-9) / (S.max(0, keepdims=True) + 1e-9)) + np.log(v)[None, :]
    emu = np.log(1.0 - v)
    back = np.zeros((nf, nb), np.int32)
    back_u = np.zeros(nf, np.int32)
    sv = emv[:, 0].copy()
    su = float(emu[0])
    w = 2 * free + 1
    for j in range(1, nf):
        pad = np.concatenate([np.full(free, -np.inf), sv, np.full(free, -np.inf)])
        win = sliding_window_view(pad, w)
        k = np.argmax(win, axis=1)
        near = win[np.arange(nb), k]
        near_idx = np.arange(nb) + k - free
        ibest = int(np.argmax(sv))
        far = sv[ibest] - jump
        cand = np.stack([near, np.full(nb, far), np.full(nb, su - enter)])
        ch = np.argmax(cand, axis=0)
        back[j] = np.where(ch == 0, near_idx, np.where(ch == 1, ibest, -1))
        nsv = cand[ch, np.arange(nb)] + emv[:, j]
        if su >= sv[ibest] - leave:
            back_u[j], nsu = -1, su + emu[j]
        else:
            back_u[j], nsu = ibest, sv[ibest] - leave + emu[j]
        sv, su = nsv, float(nsu)
    st = np.empty(nf, np.int64)
    cur = -1 if su >= sv.max() else int(np.argmax(sv))
    for j in range(nf - 1, -1, -1):
        st[j] = cur
        if j == 0:
            break
        cur = int(back_u[j]) if cur < 0 else int(back[j, cur])
    return st


def ridge(sp: dict, band: tuple[float, float] | None = None, level_mid_db: float = 30.0, peaky_mid: float = 1.8,
          jump_cost: float = 5.0, sharpen: float = 3.0):
    """Ridge through S1 inside ``band`` (MIDI lo, hi; default the whole grid). Adds 'ridge' (MIDI float, nan when
    unvoiced), 'voicing' and 'level_ref_db'."""
    import numpy as np

    p_all = sp["pitches"]
    sel = np.ones(p_all.size, bool) if band is None else (p_all >= band[0]) & (p_all <= band[1])
    S = sp["S1"][sel]
    p = p_all[sel]
    nb, nf = S.shape
    lev = sp["level_db"]
    ref = float(np.percentile(lev, 95))
    pk = S.max(0) / (np.median(S, 0) + 1e-9)
    v = (1 / (1 + np.exp(-(lev - (ref - level_mid_db)) / 3.0))) * (1 / (1 + np.exp(-(pk - peaky_mid) / 0.25)))
    v = np.clip(v, 0.001, 0.999)
    hop_s = sp["hop"] / SR
    free = max(1, int(round(BPS * 0.6 * hop_s / 0.0116 * 2)))  # about 2/3 semitone per 11.6 ms
    st = ridge_dp(S, v, free, jump_cost, sharpen=sharpen)
    rid = np.full(nf, np.nan)
    ok = st >= 0
    idx = np.clip(st, 1, nb - 2)
    cols = np.arange(nf)
    a_, b_, c_ = S[idx - 1, cols], S[idx, cols], S[idx + 1, cols]
    den = a_ - 2 * b_ + c_
    off = np.clip(np.where(np.abs(den) > 1e-12, 0.5 * (a_ - c_) / np.where(den == 0, 1, den), 0.0), -0.5, 0.5)
    rid[ok] = p[idx[ok]] + off[ok] / BPS
    sp["ridge"] = rid
    sp["voicing"] = v
    sp["level_ref_db"] = ref
    fr = rid[ok] - np.round(rid[ok])
    sp["ridge_tuning"] = float(np.angle(np.mean(np.exp(2j * np.pi * fr))) / (2 * np.pi)) if fr.size > 10 else 0.0
    sp["ridge_band"] = None if band is None else [float(band[0]), float(band[1])]
    return sp


def _pitch_rows(sp: dict, m: float, tol: float = 0.4):
    import numpy as np

    return np.abs(sp["pitches"] - m) <= tol


def register_and_tuning(sp: dict, windows: list[tuple[int, int, int]], shifts=(-24, -12, 0, 12)):
    """Octave relation of the original's lead to the score (semitones, a multiple of 12) and the tuning offset.

    For each shift (and tuning offsets -0.5..0.5 in 0.1 steps) the mean over notes of the S1 at the expected pitch
    (+-0.1) relative to the note window's median S1; the best (shift, tuning) wins. Most score notes are assumed
    right, so this is a fit to the score, robust to a few wrong notes. windows = [(f0, f1, midi), ...]."""
    import numpy as np

    p = sp["pitches"]
    S1 = sp["S1"]
    table = {}
    best = (-1.0, 0, 0.0)
    for sh in shifts:
        for tau in np.arange(-0.5, 0.501, 0.1):
            tot, k = 0.0, 0
            for f0, f1, m in windows:
                sel = np.abs(p - (m + sh + tau)) <= 0.11
                if not sel.any() or f1 - f0 < 2:
                    continue
                seg = S1[:, f0:f1]
                tot += float(seg[sel].max(0).mean() / (np.median(seg) + 1e-9))
                k += 1
            sc = tot / max(1, k) if k else -1.0
            table[(sh, round(float(tau), 1))] = sc
            if k and sc > best[0]:
                best = (sc, sh, float(tau))
    by_shift = {sh: max(v for (s, _), v in table.items() if s == sh) for sh in shifts}
    return best[1], round(best[2], 2), {str(k): r(v, 3) for k, v in by_shift.items()}


def pc_evidence(sp: dict, f0: int, f1: int, tuning: float, center: int):
    """Evidence for the 12 pitch classes in the octave window [center-6, center+5] (one candidate per class, in the
    register where the original's lead is): mean over frames of the best S1 / S2 within +-0.4 semitone of the
    candidate (tuning applied). Returns (e1[12], e2[12], {midi: e1})."""
    import numpy as np

    S1 = sp["S1"][:, f0:f1]
    S2 = sp["S2"][:, f0:f1]
    e1 = np.zeros(12)
    e2 = np.zeros(12)
    per = {}
    for m in range(center - 6, center + 6):
        sel = _pitch_rows(sp, m + tuning)
        if not sel.any():
            continue
        v1 = float(S1[sel].max(0).mean())
        e1[m % 12] = v1
        e2[m % 12] = float(S2[sel].max(0).mean())
        per[m] = v1
    return e1, e2, per


def ridge_share(sp: dict, f0: int, f1: int, tuning: float):
    import numpy as np

    rd = sp["ridge"][f0:f1]
    ok = np.isfinite(rd)
    share = np.zeros(12)
    if ok.sum():
        pcs = (np.round(rd[ok] - tuning).astype(int)) % 12
        for c in range(12):
            share[c] = float(np.mean(pcs == c))
    voiced = float(np.mean(ok)) if rd.size else 0.0
    return share, voiced, rd[ok]


def pyin_track(path: Path, t0: float, t1: float, lo: int, hi: int, hop: int = 256):
    import librosa
    import numpy as np

    x, start = load_stereo(path, max(0.0, t0), t1)
    y = x.mean(axis=1)
    f0, vflag, vp = librosa.pyin(y, fmin=float(midi_hz(lo - 0.5)), fmax=float(midi_hz(hi + 0.5)), sr=SR,
                                 frame_length=2048, hop_length=hop)
    t = start + np.arange(f0.size) * hop / SR
    m = np.where(np.isfinite(f0), librosa.hz_to_midi(np.nan_to_num(f0, nan=1.0)), np.nan)
    return {"t": t, "midi": m, "vprob": np.nan_to_num(vp)}


def _cls_name(c: int) -> str:
    return io.PITCH_NAMES[c % 12]


def judge(e1, e2, share, voiced, n_frames: int, level_rel, exp: int,
          params: dict) -> tuple[str, int | None, float, list[str]]:
    """Verdict for one window. Returns (verdict, heard_pc, confidence, reasons).

    q[c] = mean of the two passes' salience relative to their best class (1 = c leads both). The expected class
    with q >= agree_q (0.85) agrees; q < wrong_q (0.78) while another class leads is wrong; in between the ridge
    decides when it is clear (>= 50 % of the voiced frames on the expected class -> agree, >= 60 % on another
    class -> wrong), else uncertain. weak (too few voiced ridge frames or a quiet span) never gives 'wrong'.
    The pitch heard is the ridge's class when the ridge holds it on >= 30 % of the frames and it is nearly as salient
    as the best (q >= 0.85), else the class with the best q (ties: more ridge)."""
    import numpy as np

    reasons = []
    m1, m2 = float(e1.max()) + 1e-9, float(e2.max()) + 1e-9
    q = 0.5 * (e1 / m1 + e2 / m2)
    nv = voiced * n_frames
    weak = nv < max(params["min_voiced_frames"], params["min_voiced"] * n_frames) or level_rel < -params["weak_db"]
    qe = float(q[exp])
    others = [c for c in range(12) if c != exp]
    rtop = int(np.argmax(share)) if share.max() > 0 else None
    if rtop is not None and rtop != exp and share[rtop] >= 0.3 and q[rtop] >= 0.85:
        heard = rtop
    else:
        heard = max(others, key=lambda c: (round(float(q[c]), 3), share[c]))
    margin = float(q[heard]) - qe
    if not weak and (qe >= params["agree_q"] or (share[exp] >= 0.5 and qe >= params["wrong_q"])):
        conf = 0.5 + 0.3 * min(1.0, (qe - 0.7) / 0.3) + 0.2 * min(1.0, share[exp] / 0.6)
        return "agree", exp, float(min(1.0, conf)), reasons
    if weak and nv >= 3 and share[exp] >= 0.6 and qe >= params["agree_q"]:
        # little voice, but what there is sits on the expected class and it leads the salience
        return "agree", exp, 0.55, ["weak_but_consistent"]
    if weak:
        reasons.append("weak")
        return "uncertain", heard, 0.2, reasons
    ridge_other = rtop is not None and rtop != exp and share[rtop] >= 0.6
    if qe < params["wrong_q"] and margin >= params["wrong_margin"]:
        conf = 0.5 + 0.3 * min(1.0, margin / 0.4) + 0.2 * min(1.0, share[heard] / 0.6)
        return "wrong", heard, float(min(1.0, conf)), reasons
    if ridge_other and q[rtop] >= 0.95 and float(q[rtop]) - qe >= params["wrong_margin"]:
        # between the thresholds the ridge alone is not enough: on a dense mix it follows chord tones (a full-song
        # check had 25 false 'wrong' from this rule and no planted note needed it), so this stays uncertain
        return "uncertain", rtop, 0.4, reasons + ["ridge_other"]
    reasons.append("small_margin")
    if e1.argmax() != e2.argmax():
        reasons.append("passes_disagree")
    return "uncertain", heard, 0.3, reasons


DEFAULT_PARAMS = {"min_voiced": 0.25, "min_voiced_frames": 6, "weak_db": 30.0, "agree_q": 0.85, "wrong_q": 0.78, "wrong_margin": 0.1,
                  "attack_frac": 0.35, "attack_max_sec": 0.12, "start_margin_sec": 0.03, "end_margin_sec": 0.03,
                  "max_window_sec": 1.2}


def note_window(tmap, n: dict, t, pr: dict, dt: float | None = None):
    """(start sec, end sec, frame f0, frame f1, a, b) of one score note; a..b = the judged span."""
    import numpy as np

    dt = float(n.get("dt", 0.0) if dt is None else dt)
    s = float(tmap.sec(n["tick"])) + dt
    e = float(tmap.sec(n["tick"] + n["len"])) + dt
    dur = max(1e-3, e - s)
    # the stable middle: 15 % (at least 30 ms) after the start, 20 % (at least 30 ms) before the end, so a small timing
    # error in either direction stays inside the note
    a = s + min(max(pr["start_margin_sec"], 0.15 * dur), 0.4 * dur)
    b = min(e - min(max(pr["end_margin_sec"], 0.2 * dur), 0.4 * dur), s + pr["max_window_sec"])
    return s, e, int(np.searchsorted(t, a)), int(np.searchsorted(t, b)), a, b


OFFSET_RANGE_SEC = 0.12     # time-map error searched around the given map (both directions)
OFFSET_STEP_SEC = 0.01
PHRASE_GAP_SEC = 0.4
PHRASE_MAX_SEC = 3.0
PHRASE_RANGE_SEC = 0.04     # a phrase may move this much further from the global correction
PHRASE_MIN_NOTES = 4
PHRASE_PULL = 0.008         # fit units per 10 ms a phrase moves away from the global correction
GLOBAL_PULL = 0.002         # fit units per 10 ms the global correction moves away from 0


def _class_sums(sp: dict, tuning: float, lo: int, hi: int):
    """Cumulative sums over frames of the best S1 / S2 near every semitone lo..hi (tuning applied)."""
    import numpy as np

    c1, c2 = {}, {}
    for m in range(lo, hi + 1):
        sel = _pitch_rows(sp, m + tuning)
        if not sel.any():
            continue
        c1[m] = np.concatenate([[0.0], np.cumsum(sp["S1"][sel].max(0), dtype=np.float64)])
        c2[m] = np.concatenate([[0.0], np.cumsum(sp["S2"][sel].max(0), dtype=np.float64)])
    return c1, c2


def _q_expected(c1, c2, f0: int, f1: int, center: int, pc: int) -> float:
    import numpy as np

    n = max(1, f1 - f0)
    e1 = np.zeros(12)
    e2 = np.zeros(12)
    for m in range(center - 6, center + 6):
        if m in c1:
            e1[m % 12] = (c1[m][f1] - c1[m][f0]) / n
            e2[m % 12] = (c2[m][f1] - c2[m][f0]) / n
    # continuous contrast of the expected class against the window's typical class (not capped at 1 like q, so
    # the fit still sees how much of the note the window covers)
    return float(0.5 * (e1[pc] / (np.median(e1) + 1e-9) + e2[pc] / (np.median(e2) + 1e-9)))


def _robust(v) -> float:
    """Mean of the best 80 %: a few wrong notes in the score do not steer the fit."""
    import numpy as np

    v = np.sort(np.asarray(v, float))[::-1]
    k = max(1, int(np.ceil(0.8 * v.size)))
    return float(np.mean(v[:k])) if v.size else -1.0


def fit_offsets(sp: dict, notes: list[dict], tmap, pr: dict, shift: int, tuning: float):
    """Correction of the time map from the audio itself. Returns (dt per note, info).

    For every candidate correction d (-120..+120 ms, 10 ms steps) and note: q of the expected class in the note's
    judged window moved by d. The global correction maximises the mean of the best 80 % of the notes (ties: nearer to
    0); then each phrase (notes split at gaps > 0.4 s, at most 3 s long, >= 4 notes) may move up to 40 ms further with
    the same statistic and a pull towards the global value (0.008 per 10 ms; the global value is pulled to 0 by 0.002). The tempo drift of a slightly wrong
    map is followed phrase by phrase."""
    import numpy as np

    t = sp["t"]
    ds = np.round(np.arange(-OFFSET_RANGE_SEC, OFFSET_RANGE_SEC + 1e-9, OFFSET_STEP_SEC), 3)
    lo = min(n["pitch"] for n in notes) + shift - 7
    hi = max(n["pitch"] for n in notes) + shift + 7
    c1, c2 = _class_sums(sp, tuning, lo, hi)
    Q = np.full((len(notes), ds.size), np.nan)
    for i, n in enumerate(notes):
        for j, d in enumerate(ds):
            _, _, f0, f1, _, _ = note_window(tmap, n, t, pr, float(d))
            if f1 - f0 >= 3 and f1 < t.size:
                Q[i, j] = _q_expected(c1, c2, f0, f1, n["pitch"] + shift, n["pitch"] % 12)
    ok = ~np.all(np.isnan(Q), axis=1)

    def best_of(rows, js, center, pull):
        best = None
        for j in js:
            v = Q[rows, j]
            v = v[np.isfinite(v)]
            if v.size == 0:
                continue
            sc = _robust(v) - pull * abs(ds[j] - center) / OFFSET_STEP_SEC
            key = (round(sc, 6), -abs(ds[j] - center))
            if best is None or key > best[0]:
                best = (key, j)
        return None if best is None else best[1]

    allrows = np.nonzero(ok)[0]
    jg = best_of(allrows, range(ds.size), 0.0, GLOBAL_PULL) if allrows.size else int(np.argmin(np.abs(ds)))
    g = float(ds[jg]) if jg is not None else 0.0
    starts = [float(tmap.sec(n["tick"])) for n in notes]
    ends = [float(tmap.sec(n["tick"] + n["len"])) for n in notes]
    phrases, cur = [], [0]
    for i in range(1, len(notes)):
        if starts[i] - ends[i - 1] > PHRASE_GAP_SEC or starts[i] - starts[cur[0]] > PHRASE_MAX_SEC:
            phrases.append(cur)
            cur = []
        cur.append(i)
    phrases.append(cur)
    dt = np.full(len(notes), g)
    out = []
    near = [j for j in range(ds.size) if abs(ds[j] - g) <= PHRASE_RANGE_SEC + 1e-9]
    for ph in phrases:
        rows = np.array([i for i in ph if ok[i]], int)
        d = g
        if rows.size >= PHRASE_MIN_NOTES:
            j = best_of(rows, near, g, PHRASE_PULL)
            if j is not None:
                d = float(ds[j])
        dt[ph] = d
        out.append({"first_note": ph[0] + 1, "last_note": ph[-1] + 1, "from_sec": r(starts[ph[0]], 3),
                    "notes": len(ph), "offset_ms": int(round(1000 * d))})
    gi = int(np.argmin(np.abs(ds - g)))
    zi = int(np.argmin(np.abs(ds)))
    col = lambda j: _robust(Q[allrows, j][np.isfinite(Q[allrows, j])]) if allrows.size else None  # noqa: E731
    info = {"global_offset_ms": int(round(1000 * g)), "fit_at_global": r(col(gi), 3), "fit_at_zero": r(col(zi), 3),
            "search_ms": [int(-1000 * OFFSET_RANGE_SEC), int(1000 * OFFSET_RANGE_SEC)], "phrases": out,
            "meaning": "the original's lead sounds this many ms after the given time map (+ = later); notes are "
                       "judged with the phrase offset"}
    return dt, info


def check_notes(sp: dict, notes: list[dict], tmap, shift: int, params: dict | None = None, mode: str = "stem",
                pyin: dict | None = None) -> list[dict]:
    import numpy as np

    pr = {**DEFAULT_PARAMS, **(params or {})}
    t = sp["t"]
    tuning = sp["tuning"]
    step = sp["hop"] / SR
    out = []
    for i, n in enumerate(notes):
        s, e, f0, f1, a, b = note_window(tmap, n, t, pr)
        row = {"index": i + 1, "tick": n["tick"], "len": n["len"], "pitch": n["pitch"], "name": n["name"],
               "track": n.get("track"), "sec": r(s, 3), "sec_end": r(e, 3),
               "time_offset_ms": int(round(1000 * float(n.get("dt", 0.0))))}
        pos, meas, beat = tmap.pos(n["tick"])
        row.update({"pos": pos, "measure": meas, "beat": beat})
        if f1 - f0 < 3:
            row.update({"verdict": "uncertain", "heard_pc": None, "heard_name": None, "heard_midi": None,
                        "heard_note": None, "octave_relation": None, "confidence": 0.0,
                        "reasons": ["too_short_or_outside"], "evidence": None})
            out.append(row)
            continue
        exp = n["pitch"] % 12
        center = n["pitch"] + shift
        lev_rel = float(np.median(sp["level_db"][f0:f1]) - sp["level_ref_db"])
        fa = f0 + int(round(min(pr["attack_frac"] * (f1 - f0), pr["attack_max_sec"] / step)))
        fa = min(max(fa, f0 + 1), f1 - 3) if f1 - f0 >= 8 else f0
        e1, e2, per_semi = pc_evidence(sp, f0, f1, tuning, center)
        sh, vo, rd = ridge_share(sp, f0, f1, tuning)
        verdict, heard, conf, reasons = judge(e1, e2, sh, vo, f1 - f0, lev_rel, exp, pr)
        ev = {"voiced": r(vo, 2), "level_rel_db": r(lev_rel, 1),
              "ridge_share_expected": r(sh[exp], 2), "s1_rel_expected": r(e1[exp] / (e1.max() + 1e-9), 3),
              "s2_rel_expected": r(e2[exp] / (e2.max() + 1e-9), 3), "s1_top": _cls_name(int(np.argmax(e1))),
              "s2_top": _cls_name(int(np.argmax(e2))),
              "ridge_top": _cls_name(int(np.argmax(sh))) if sh.max() > 0 else None, "whole_verdict": verdict}
        if fa > f0:
            e1s, e2s, per_s = pc_evidence(sp, fa, f1, tuning, center)
            shs, vos, rds = ridge_share(sp, fa, f1, tuning)
            sha, voa, _ = ridge_share(sp, f0, fa, tuning)
            vs = judge(e1s, e2s, shs, vos, f1 - fa, lev_rel, exp, pr)
            att_pc = int(np.argmax(sha)) if sha.max() > 0 and voa >= 0.3 else None
            steady_weak = "weak" in vs[3]
            ev.update({"steady_ridge_top": _cls_name(int(np.argmax(shs))) if shs.max() > 0 else None,
                       "steady_s1_top": _cls_name(int(np.argmax(e1s))),
                       "attack_ridge_top": None if att_pc is None else _cls_name(att_pc),
                       "steady_verdict": vs[0]})
            if vs[0] == "agree" and verdict != "agree":
                verdict, heard, conf = "agree", exp, vs[2]
                reasons = vs[3] + (["scoop"] if att_pc is not None and att_pc != exp else [])
            elif vs[0] == "wrong" and verdict != "wrong":
                verdict, heard, conf = "wrong", vs[1], vs[2]
                reasons = vs[3] + (["slide"] if att_pc == exp else [])
            elif (vs[0] == "uncertain" and verdict == "agree" and att_pc == exp and not steady_weak
                  and shs.max() > 0 and int(np.argmax(shs)) != exp):
                # the attack is on our pitch but the voiced steady part sits elsewhere without a clear margin
                verdict, heard, conf, reasons = "uncertain", vs[1], 0.3, vs[3] + ["slide"]
            if rds.size:
                rd = rds
                per_semi = per_s
        if pyin is not None:
            sel = (pyin["t"] >= a) & (pyin["t"] < b) & (pyin["vprob"] >= 0.5) & np.isfinite(pyin["midi"])
            pm = pyin["midi"][sel]
            if pm.size >= 3:
                pcs = np.round(pm - tuning).astype(int) % 12
                cnt = np.bincount(pcs, minlength=12) / pm.size
                ptop = int(np.argmax(cnt))
                ev["pyin_frames"] = int(pm.size)
                ev["pyin_share_expected"] = r(cnt[exp], 2)
                ev["pyin_top"] = _cls_name(ptop)
                strong = pm.size >= 6 and cnt[ptop] >= 0.7
                if strong and verdict == "uncertain":
                    verdict, heard, conf = ("agree", exp, 0.55) if ptop == exp else ("wrong", ptop, 0.55)
                    reasons = reasons + ["pyin"]
                elif strong and ((verdict == "agree" and ptop != exp) or (verdict == "wrong" and ptop != heard)):
                    verdict, conf = "uncertain", 0.3
                    reasons = reasons + ["pyin_disagrees"]
        heard_midi = None
        if heard is not None:
            q = rd[(np.round(rd - tuning).astype(int)) % 12 == heard] if rd.size else rd
            if q.size:
                heard_midi = int(np.round(np.median(q) - tuning))
            else:
                cand = [m for m in per_semi if m % 12 == heard]
                heard_midi = cand[0] if cand else None
        # how much the evidence agrees on the class heard (both salience passes and the ridge, steady part first)
        votes = [ev.get("steady_s1_top") or ev["s1_top"], ev["s2_top"], ev.get("steady_ridge_top") or ev["ridge_top"]]
        hname = None if heard is None else _cls_name(heard)
        n_votes = sum(1 for v in votes if v is not None and v == hname)
        heard_conf = {3: 0.9, 2: 0.6, 1: 0.35, 0: 0.2}[n_votes] if heard is not None else None
        alts = sorted({v for v in votes if v is not None and v != hname and v != _cls_name(exp)})
        if verdict == "wrong":
            if mode == "mix":
                # a mix 'wrong' is a suspect, not a finding: chord tones of the accompaniment can lead the window
                conf = min(conf, 0.6 if n_votes == 3 else 0.45)
                heard_conf = min(heard_conf, 0.6)
            elif n_votes < 3:
                conf = min(conf, 0.7)
        row.update({"verdict": verdict, "heard_pc": heard, "heard_name": None if heard is None else _cls_name(heard),
                    "heard_confidence": None if heard_conf is None else r(heard_conf, 2),
                    "heard_alternatives": alts,
                    "heard_midi": heard_midi,
                    "heard_note": (None if heard_midi is None
                                   else io.PITCH_NAMES[heard_midi % 12] + str(heard_midi // 12 - 1)),
                    "octave_relation": (None if heard_midi is None or heard != exp
                                        else int(round((heard_midi - n["pitch"]) / 12))),
                    "confidence": r(conf, 2), "reasons": sorted(set(reasons)), "evidence": ev})
        out.append(row)
    return out


WEAK_ONLY_REASONS = {"weak", "too_short_or_outside"}
TRANS_MIN_SHARE = 0.3        # the transcription note must cover this share of the score note's head ...
TRANS_MIN_SEC = 0.06         # ... or this long, whichever is smaller
TRANS_MIN_HEARD_CONF = 0.6   # an 'uncertain' is upgraded only when the tool's own heard pitch is at least this sure


def apply_transcription(rows: list[dict], trans: list[dict], head_sec: float = 1.0) -> dict:
    """Cross-check with a vocal transcription (notes after its trust rules, recording seconds).

    Per note the transcribed note overlapping its first head_sec (with the fitted time correction) MOST is the reading
    (``transcription_pitch``); it counts only when it covers >= min(30 % of that span, 60 ms) — a neighbouring sung
    note that only touches the window is ignored (``transcription_covers: false``). A 'wrong' whose reading has the
    score's pitch class becomes 'uncertain' (``transcription_disagrees``). An 'uncertain' becomes 'wrong'
    (``transcription_confirms``) only when the reading equals the tool's heard class (not the score's), the tool's
    ``heard_confidence`` is >= 0.6 and its reasons are not only weak or absent signal. Returns the change counts."""
    import bisect

    ons = [g["on"] for g in trans]
    with_t = w2u = u2w = 0
    for x in rows:
        t0 = float(x["sec"])
        span = min(float(x["sec_end"]) - t0, head_sec)
        t1 = t0 + span
        i = bisect.bisect_left(ons, t0 - 10.0)
        cands = []
        for g in trans[i:]:
            if g["on"] > t1:
                break
            ov = min(g["off"], t1) - max(g["on"], t0)
            if ov > 0:
                cands.append((ov, g))
        if not cands:
            x["transcription_pitch"] = None
            x["transcription_agrees"] = None
            x["transcription_covers"] = False
            continue
        ov, best = max(cands, key=lambda c: c[0])
        covers = ov >= min(TRANS_MIN_SHARE * span, TRANS_MIN_SEC)
        exp = x["pitch"] % 12
        x["transcription_pitch"] = {"name": io.PITCH_NAMES[best["p"] % 12] + str(best["p"] // 12 - 1),
                                    "midi": r(best["pitch"], 2), "on_sec": r(best["on"], 3),
                                    "overlap_sec": r(ov, 3)}
        x["transcription_covers"] = bool(covers)
        if not covers:
            x["transcription_agrees"] = None
            continue
        with_t += 1
        agrees = best["p"] % 12 == exp
        x["transcription_agrees"] = bool(agrees)
        weak_only = set(x.get("reasons") or []) <= WEAK_ONLY_REASONS and bool(x.get("reasons"))
        if x["verdict"] == "wrong" and agrees:
            x["verdict"] = "uncertain"
            x["confidence"] = min(x["confidence"] or 0.0, 0.3)
            x["reasons"] = sorted(set(x["reasons"]) | {"transcription_disagrees"})
            w2u += 1
        elif (x["verdict"] == "uncertain" and not agrees and x.get("heard_pc") is not None
              and best["p"] % 12 == x["heard_pc"] and x["heard_pc"] != exp and not weak_only
              and (x.get("heard_confidence") or 0.0) >= TRANS_MIN_HEARD_CONF):
            x["verdict"] = "wrong"
            x["confidence"] = max(x["confidence"] or 0.0, 0.6)
            x["reasons"] = sorted(set(x["reasons"]) | {"transcription_confirms"})
            u2w += 1
    return {"notes_with_transcription": with_t, "wrong_to_uncertain": w2u, "uncertain_to_wrong": u2w}


def summarise(rows: list[dict], sections: list[dict]) -> dict:
    def cnt(xs):
        c = {v: 0 for v in VERDICTS}
        for x in xs:
            c[x["verdict"]] += 1
        c["notes"] = len(xs)
        return c

    secs = []
    for s in sections:
        xs = [x for x in rows if s["from_measure"] <= x["measure"] <= s["to_measure"]]
        secs.append({"name": s["name"], "from_measure": s["from_measure"], "to_measure": s["to_measure"], **cnt(xs),
                     "wrong_at": [x["pos"] for x in xs if x["verdict"] == "wrong"],
                     "uncertain_at": [x["pos"] for x in xs if x["verdict"] == "uncertain"]})
    return {"counts": cnt(rows), "sections": secs}


def auto_sections(rows: list[dict]) -> list[dict]:
    if not rows:
        return []
    a = min(x["measure"] for x in rows)
    z = max(x["measure"] for x in rows)
    return [{"name": f"m{m}-{min(m + 7, z)}", "from_measure": m, "to_measure": min(m + 7, z)} for m in range(a, z + 1, 8)]


def markdown(doc: dict) -> str:
    c = doc["summary"]["counts"]
    lines = [f"# 리드 음 대조 (lead_check)", "",
             f"원곡: `{Path(doc['inputs']['original_audio']).name}` ({doc['inputs']['source']}), "
             f"음 {c['notes']}개: **일치 {c['agree']} · 틀림 {c['wrong']} · 보류 {c['uncertain']}** "
             f"(음이름만 비교, 옥타브 무시). {doc['timing_sec']['total']:.1f}초 걸림.", "",
             "> 숫자로 잰 것이고 귀로 들은 것이 아니다. '틀림'은 원곡의 같은 시각에 다른 음이 뚜렷하다는 뜻이다.", ""]
    lines += [f"- 경고: {w}" for w in doc.get("warnings") or []] + ([""] if doc.get("warnings") else [])
    lines += ["## 구간별", "", "| 구간 | 마디 | 음 | 일치 | 틀림 | 보류 | 틀린 자리 |", "|---|---|---:|---:|---:|---:|---|"]
    for s in doc["summary"]["sections"]:
        lines.append(f"| {s['name']} | {s['from_measure']}–{s['to_measure']} | {s['notes']} | {s['agree']} | "
                     f"{s['wrong']} | {s['uncertain']} | {', '.join(s['wrong_at'])} |")
    lines += ["", "## 음마다", "", "| # | 위치 | 원곡 초 | 우리 음 | 원곡에서 들린 음 | 판정 | 확신 | 이유 |",
              "|---:|---|---:|---|---|---|---:|---|"]
    names = {"agree": "일치", "wrong": "**틀림**", "uncertain": "보류"}
    for x in doc["notes"]:
        lines.append(f"| {x['index']} | {x['pos']} | {x['sec']:.3f} | {x['name']} | {x.get('heard_note') or '-'} | "
                     f"{names[x['verdict']]} | {x['confidence']:.2f} | {', '.join(x['reasons'])} |")
    lines += ["", "## 한계", ""] + [f"- {t}" for t in LIMITS]
    return "\n".join(lines) + "\n"


def run_lead_check(original: Path, notes: list[dict], tmap, source: str, side_k: float, lo: int | None,
                   hi: int | None, sections: list[dict], use_pyin: bool, params: dict | None = None,
                   octave_shift: int | None = None, repet: bool | None = None, fit_time: bool = True,
                   transcription: dict | None = None, side_k_given: bool = False) -> dict:
    import numpy as np

    t_start = time.perf_counter()
    pr = {**DEFAULT_PARAMS, **(params or {})}
    starts = np.array([float(tmap.sec(n["tick"])) for n in notes])
    ends = np.array([float(tmap.sec(n["tick"] + n["len"])) for n in notes])
    info = io.probe(original)
    if ends.max() <= 0 or starts.min() >= info["duration_sec"]:
        raise EarError("BAD_ARGUMENT", "음들의 원곡 시각이 오디오 밖입니다(시간 지도를 확인).",
                       {"first_note_sec": r(starts.min()), "last_note_end_sec": r(ends.max()),
                        "duration_sec": r(info["duration_sec"])})
    pmin = min(n["pitch"] for n in notes)
    pmax = max(n["pitch"] for n in notes)
    lo_ = max(28, pmin - 31) if lo is None else lo
    hi_ = min(108, pmax + 19) if hi is None else hi
    t0, t1 = max(0.0, starts.min() - 1.0), min(info["duration_sec"], ends.max() + 1.0)
    mode = source if info["channels"] >= 2 else "stem"
    use_repet = bool(repet)
    shifts = [s for s in (-36, -24, -12, 0, 12, 24) if lo_ <= pmin + s - 6 and pmax + s + 6 <= hi_]
    if octave_shift is not None:
        shifts = [octave_shift]
    if not shifts:
        raise EarError("BAD_ARGUMENT", "음역(range_low~range_high)이 음들의 옥타브 창을 담지 못합니다.",
                       {"range_low": lo_, "range_high": hi_, "notes": [pmin, pmax]})

    notes = [dict(n, dt=0.0) for n in notes]

    def windows(sp_, ns):
        out = []
        for n in ns:
            _, _, f0, f1, _, _ = note_window(tmap, n, sp_["t"], pr)
            out.append((f0, f1, n["pitch"]))
        return out

    def calibrate(sp_, ns):
        """register/tuning, time offsets, register/tuning again with the offsets. Returns (notes, shift, tuning,
        by_shift, offset info)."""
        sh, tu, _ = register_and_tuning(sp_, windows(sp_, ns), shifts)
        if fit_time:
            dts, oinfo = fit_offsets(sp_, ns, tmap, pr, sh, tu)
            ns = [dict(n, dt=float(d)) for n, d in zip(ns, dts)]
        else:
            oinfo = None
        sh, tu, bys = register_and_tuning(sp_, windows(sp_, ns), shifts)
        return ns, sh, tu, bys, oinfo

    k_scores = None
    if mode == "mix" and side_k == "auto":
        # choose k on the densest 40 s of notes: the k under which the score's line is most salient
        mids = 0.5 * (starts + ends)
        best_i = max(range(len(mids)), key=lambda i: int(np.sum((mids >= mids[i]) & (mids < mids[i] + 40.0))))
        p0 = max(t0, mids[best_i] - 1.0)
        p1 = min(t1, mids[best_i] + 41.0)
        whole = p0 <= t0 + 1e-6 and p1 >= t1 - 1e-6
        sel = [n for n, m in zip(notes, mids) if p0 + 1.0 <= m < p1 - 1.0] or notes
        k_scores = {}
        k_spans = {}
        for kk in SIDE_K_CANDIDATES:
            spk = analyse_span(original, p0, p1, mode, kk, lo_, hi_, repet=use_repet)
            selk, shk, tuk, _, _ = calibrate(spk, sel)
            if whole:
                k_spans[kk] = spk
            qs = []
            for n in selk:
                _, _, f0, f1, _, _ = note_window(tmap, n, spk["t"], pr)
                if f1 - f0 < 3:
                    continue
                e1, e2, _ = pc_evidence(spk, f0, f1, tuk, n["pitch"] + shk)
                m = n["pitch"] % 12
                qs.append(0.5 * (e1[m] / (e1.max() + 1e-9) + e2[m] / (e2.max() + 1e-9)))
            # robust: the share of notes whose class leads its window (a wrong note counts at most once)
            k_scores[kk] = float(np.mean(np.asarray(qs) >= pr["agree_q"])) if qs else -1.0
        best = max(k_scores.values())
        # deterministic: the smallest k within SIDE_K_MARGIN of the best (more side removal must clearly pay)
        side_k = min(kk for kk, v in k_scores.items() if v >= best - SIDE_K_MARGIN)
        k_scores = {str(kk): r(v, 4) for kk, v in k_scores.items()}
    elif side_k == "auto":
        side_k = 0.0
    sp = (k_spans.get(side_k) if k_scores is not None else None) or \
        analyse_span(original, t0, t1, mode, float(side_k), lo_, hi_, repet=use_repet)
    notes, shift, tuning, by_shift, offset_info = calibrate(sp, notes)
    sp["tuning"] = tuning
    band = (pmin + shift - 7.0, pmax + shift + 7.0)
    ridge(sp, band)
    t_feat = time.perf_counter() - t_start
    py = pyin_track(original, t0, t1, int(band[0]), int(band[1]) + 1) if use_pyin else None
    rows = check_notes(sp, notes, tmap, shift, pr, mode, py)
    tr_info = apply_transcription(rows, transcription["notes"], transcription.get("head_sec", 1.0)) \
        if transcription is not None else None
    if tr_info is not None:
        tr_info.update({k: v for k, v in transcription.items() if k not in ("notes",)})
    secs = sections or auto_sections(rows)
    summ = summarise(rows, secs)
    outside = int(np.sum((starts < 0) | (starts >= info["duration_sec"])))
    warns = []
    cnt = summ["counts"]
    if mode == "mix":
        warns.append("전체 믹스로 판정했다: 반주의 화음 음이 리드보다 세면 맞는 음도 '틀림'이 될 수 있으니 결과는 의심 목록이다. "
                     "노래·리드 스템(source 'stem')으로, 노래 전사 파일이 있으면 transcription도 주어 확인한다")
        if side_k_given and isinstance(side_k, (int, float)) and side_k > 0.5:
            warns.append(f"side_k {side_k:g}를 직접 주었다: 0.5보다 크면 노래의 넓게 퍼진 소리까지 깎여 맞는 음도 '틀림'이 되기 쉽다 — "
                         "'auto'(기본)나 0.5 이하를 권한다")
        if cnt["notes"] and cnt["wrong"] / cnt["notes"] > 0.05:
            warns.append(f"틀림이 {cnt['wrong']}/{cnt['notes']}음({100 * cnt['wrong'] / cnt['notes']:.0f}%)으로 많다: 악보가 "
                         "정말 틀렸거나 믹스가 빽빽해 판정을 믿기 어렵다 — 스템으로 확인한다")
    if outside:
        warns.append(f"원곡 오디오 밖에 놓인 음 {outside}개(시간 지도를 확인)")
    if offset_info is not None:
        edge = int(round(1000 * OFFSET_RANGE_SEC))
        at_edge = abs(offset_info["global_offset_ms"]) >= edge or \
            any(abs(p["offset_ms"]) >= edge for p in offset_info["phrases"])
        offset_info["at_search_edge"] = bool(at_edge)
        if at_edge:
            warns.append(f"시간 보정이 찾는 범위 끝(±{edge}ms)에 닿았다(전체 {offset_info['global_offset_ms']:+d}ms): 시간 지도가 "
                         "도구가 고칠 수 있는 것보다 더 틀렸을 수 있다 — align으로 다시 맞추거나 first_downbeat_sec를 고쳐 다시 본다")
    if tr_info is not None and tr_info["notes_with_transcription"] == 0:
        warns.append("transcription의 음이 악보 리드 음과 하나도 겹치지 않는다(파일·시간 지도를 확인)")
    return {"notes": rows, "summary": {**summ, "sections_auto": not sections, "transcription": tr_info},
            "warnings": warns,
            "wrong": [{k: x[k] for k in ("index", "pos", "sec", "name", "heard_note", "confidence", "heard_confidence",
                                         "heard_alternatives", "reasons", "time_offset_ms")}
                      for x in rows if x["verdict"] == "wrong"],
            "uncertain": [{k: x[k] for k in ("index", "pos", "sec", "name", "heard_note", "reasons")}
                          for x in rows if x["verdict"] == "uncertain"],
            "analysis": {"mode_used": mode, "side_k": side_k if mode == "mix" else None,
                         "side_k_fit_scores": k_scores, "sr": SR, "n_fft": NFFT,
                         "hop": sp["hop"], "pitch_grid_bins_per_semitone": BPS, "range_low": lo_, "range_high": hi_,
                         "octave_shift": shift, "octave_shift_scores": by_shift, "time_offset": offset_info,
                         "octave_shift_meaning": "original lead = score pitch + this many semitones (centre of the "
                                                 "octave window that is judged)",
                         "tuning_cents": r(100 * tuning, 1), "ridge_tuning_cents": r(100 * sp["ridge_tuning"], 1),
                         "ridge_band_midi": sp["ridge_band"],
                         "harmonics_pass1": list(H1), "harmonics_pass2": list(H2), "whiten_bins": WHITEN_BINS,
                         "span_sec": [r(t0, 3), r(t1, 3)],
                         "voiced_pct": r(100 * float(np.mean(np.isfinite(sp["ridge"]))), 1),
                         "pyin": bool(use_pyin), "repet": use_repet, "notes_outside_audio": outside, "params": pr},
            "timing_sec": {"features": r(t_feat, 2), "total": r(time.perf_counter() - t_start, 2)},
            "limits": LIMITS}
