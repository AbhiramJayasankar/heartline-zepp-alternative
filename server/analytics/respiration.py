"""Breathing rate from PPG, from the respiratory modulations of the pulse (Karlen et al. 2013 idea).

RIIV: baseline (intensity) at each beat; RIAV: beat amplitude; RIFV: beat interval (respiratory sinus arrhythmia).
On the GTR 2e a slow blood-pressure (Mayer) wave near 0.1 Hz (6/min) is much stronger than breathing in RIIV and
RIAV, and the old 0.1-0.5 Hz search locked onto it: every check read 6-9/min while the wearer breathed 12-15
(counted by hand, 7 Nov 2026). So each series is band-passed twice at 0.13-0.6 Hz, only true spectral peaks between
0.15 and 0.5 Hz (9-30/min) count, and RIAV and RIFV vote. RIIV is still reported but does not vote.
"""
import numpy as np
from scipy import signal

FS = 4.0
BAND = (0.15, 0.5)
NFFT = 4096


def _spectrum(t: np.ndarray, v: np.ndarray):
    """Welch-averaged power spectrum (64 s Hann windows, half overlap) with slow waves filtered out."""
    if len(v) < 10 or t[-1] - t[0] < 30:
        return None
    grid = np.arange(t[0], t[-1], 1 / FS)
    x = signal.detrend(np.interp(grid, t, v))
    sos = signal.butter(2, (0.13, 0.6), btype="band", fs=FS, output="sos")
    x = signal.sosfiltfilt(sos, signal.sosfiltfilt(sos, x))
    n = min(len(x), int(64 * FS))
    step = max(1, n // 2)
    total, windows, at = np.zeros(NFFT // 2 + 1), 0, 0
    while at + n <= len(x):
        total += np.abs(np.fft.rfft(x[at:at + n] * np.hanning(n), NFFT)) ** 2
        windows += 1
        at += step
    return total / windows if windows else None


def _in_band(n: int) -> np.ndarray:
    f = np.arange(n) * FS / NFFT
    return (f >= BAND[0]) & (f <= BAND[1])


def _best_peak(spec):
    """Strongest true peak (not a band edge) in 9-30/min: (breaths per minute, height over the band's median)."""
    band = _in_band(len(spec))
    best = -1
    for i in range(1, len(spec) - 1):
        if band[i] and spec[i] > spec[i - 1] and spec[i] >= spec[i + 1] and (best == -1 or spec[i] > spec[best]):
            best = i
    if best == -1:
        return None
    med = float(np.median(spec[band]))
    return best * FS / NFFT * 60, (float(spec[best] / med) if med > 0 else 0.0)


def _normalised(spec):
    top = spec[_in_band(len(spec))].max()
    return spec / top if top > 0 else spec


def breathing_rate(beat_t: np.ndarray, baseline: np.ndarray, amplitude: np.ndarray,
                   rr_t: np.ndarray, rr: np.ndarray) -> dict:
    spectra = {"riiv": _spectrum(beat_t, baseline), "riav": _spectrum(beat_t, amplitude), "rifv": _spectrum(rr_t, rr)}
    peaks = {k: (_best_peak(s) if s is not None else None) for k, s in spectra.items()}
    out = {"rate": None, "components": {k: (round(p[0], 1) if p else None) for k, p in peaks.items()}}
    voters = [s for s in (spectra["riav"], spectra["rifv"]) if s is not None]
    if not voters:
        return out
    h, r = peaks["riav"], peaks["rifv"]
    # Beat height and beat timing agreeing within 3/min is the strongest evidence: average them.
    if h and r and abs(h[0] - r[0]) <= 3 and max(h[1], r[1]) >= 2.0:
        out["rate"] = round((h[0] + r[0]) / 2, 1)
        return out
    best = _best_peak(sum(_normalised(s) for s in voters))
    if best is None or best[1] < 2.0:  # a flat spectrum has no breathing rhythm worth reporting
        return out
    out["rate"] = round(best[0], 1)
    return out
