"""Breathing rate from PPG: fuse the three respiratory modulations (Karlen et al. 2013 idea).

RIIV: baseline (intensity) at each beat; RIAV: beat amplitude; RIFV: beat interval (RSA).
Each is resampled at 4 Hz, band-passed 0.1-0.5 Hz (6-30 breaths/min) and its spectral peak taken.
Estimates are fused weighted by spectral peak sharpness, and rejected if they disagree by > 4 br/min.
"""
import numpy as np
from scipy import signal

FS = 4.0
BAND = (0.1, 0.5)


def _peak_rate(t: np.ndarray, v: np.ndarray) -> tuple[float | None, float]:
    if len(v) < 10 or t[-1] - t[0] < 30:
        return None, 0.0
    grid = np.arange(t[0], t[-1], 1 / FS)
    x = np.interp(grid, t, v)
    x = signal.detrend(x)
    sos = signal.butter(2, BAND, btype="band", fs=FS, output="sos")
    x = signal.sosfiltfilt(sos, x)
    nfft = max(1024, 1 << int(np.ceil(np.log2(len(x) * 4))))
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x)), nfft)) ** 2
    f = np.fft.rfftfreq(nfft, 1 / FS)
    band = (f >= BAND[0]) & (f <= BAND[1])
    if not band.any() or spec[band].sum() <= 0:
        return None, 0.0
    k = np.argmax(spec[band])
    fpk = f[band][k]
    near = band & (np.abs(f - fpk) <= 0.03)
    sharpness = float(spec[near].sum() / spec[band].sum())
    return float(fpk * 60), sharpness


def breathing_rate(beat_t: np.ndarray, baseline: np.ndarray, amplitude: np.ndarray,
                   rr_t: np.ndarray, rr: np.ndarray) -> dict:
    est = {
        "riiv": _peak_rate(beat_t, baseline),
        "riav": _peak_rate(beat_t, amplitude),
        "rifv": _peak_rate(rr_t, rr),
    }
    rates = [(r, w) for r, w in est.values() if r is not None and w > 0]
    out = {"rate": None, "components": {k: (round(r, 1) if r else None) for k, (r, _) in est.items()}}
    if len(rates) < 2:
        return out
    vals = np.array([r for r, _ in rates])
    if vals.max() - vals.min() > 4:
        # Keep the agreeing pair if one modulation is the odd one out.
        best = None
        for i in range(len(rates)):
            for j in range(i + 1, len(rates)):
                if abs(rates[i][0] - rates[j][0]) <= 4:
                    pair = (rates[i], rates[j])
                    w = pair[0][1] + pair[1][1]
                    if best is None or w > best[0]:
                        best = (w, pair)
        if best is None:
            return out
        rates = list(best[1])
    w = np.array([w for _, w in rates])
    r = np.array([r for r, _ in rates])
    out["rate"] = round(float((r * w).sum() / w.sum()), 1)
    return out
