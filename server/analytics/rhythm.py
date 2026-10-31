"""Irregular-rhythm screening from PPG beat intervals.

Uses the three RR-irregularity statistics of Dash et al. 2009 (normalized RMSSD, Shannon entropy,
turning point ratio). Thresholds are the values commonly used in reimplementations, not re-derived
here. This is a wellness screen from a wrist PPG, not a diagnosis.
"""
import math

import numpy as np

from .hrv import Intervals

MIN_INTERVALS = 30
NRMSSD_AF = 0.10      # RMSSD / mean RR
SHE_AF = 0.70         # normalized Shannon entropy of a 16-bin RR histogram
DISCLAIMER = ("Wellness estimate from the watch's optical sensor, not a medical diagnosis. "
              "If you have symptoms (palpitations, dizziness, chest pain, breathlessness) see a doctor.")


def shannon_entropy(rr: np.ndarray, bins: int = 16) -> float:
    """Dash-style: drop the 8 longest and 8 shortest intervals (when long enough), 16-bin histogram,
    entropy normalized to 0..1 by log(bins)."""
    x = np.sort(rr)
    if len(x) > 48:
        x = x[8:-8]
    hist, _ = np.histogram(x, bins=bins)
    p = hist[hist > 0] / hist.sum()
    return float(-(p * np.log(p)).sum() / math.log(bins))


def turning_point_ratio(rr: np.ndarray) -> tuple[float, bool]:
    """Turning points / expected for a random series (2(N-2)/3); randomness accepted when the count is
    within the 95% band mu +/- 1.96 sigma, sigma = sqrt((16N - 29) / 90)."""
    n = len(rr)
    tp = int(np.sum(((rr[1:-1] > rr[:-2]) & (rr[1:-1] > rr[2:])) | ((rr[1:-1] < rr[:-2]) & (rr[1:-1] < rr[2:]))))
    mu = 2 * (n - 2) / 3
    sigma = math.sqrt((16 * n - 29) / 90)
    return tp / mu if mu else 0.0, abs(tp - mu) <= 1.96 * sigma


def assess(iv: Intervals, hr_bpm: float | None) -> dict:
    notes = [DISCLAIMER]
    rr = iv.rr[~iv.artifact]          # keep ectopic beats: they are part of the rhythm
    n_ect = int(iv.ectopic.sum() // 2) if iv.ectopic.any() else 0
    if len(rr) < MIN_INTERVALS:
        notes.insert(0, f"Only {len(rr)} clean beat intervals; at least {MIN_INTERVALS} are needed. "
                        "Sit still with the watch snug for a minute and measure again.")
        return {"status": "insufficient_data", "ectopic_beats": n_ect, "irregularity": None, "notes": notes,
                "features": None}

    nrmssd = float(np.sqrt(np.mean(np.diff(rr) ** 2)) / rr.mean())
    she = shannon_entropy(rr)
    tpr, random_like = turning_point_ratio(rr)
    features = {"nrmssd": round(nrmssd, 3), "shannon_entropy": round(she, 3), "tpr": round(tpr, 3),
                "random_like": bool(random_like), "intervals": int(len(rr))}

    ect_frac = n_ect / max(len(rr), 1)
    if nrmssd > NRMSSD_AF and she > SHE_AF and random_like:
        status = "possible_af"
        notes.insert(0, "Beat-to-beat timing was highly irregular in a random pattern, which is what atrial "
                        "fibrillation looks like on a pulse sensor. Movement and a loose strap can mimic this; "
                        "repeat sitting still, and if it recurs, ask a doctor for an ECG.")
    elif nrmssd > NRMSSD_AF or n_ect >= 3 or ect_frac > 0.05:
        status = "irregular"
        if n_ect:
            notes.insert(0, f"{n_ect} early (ectopic) beats followed by a pause. Occasional ones are common and "
                            "usually harmless.")
        else:
            notes.insert(0, "Beat timing varied more than usual but not in a random pattern; deep breathing "
                            "(sinus arrhythmia) often does this.")
    else:
        status = "regular"
        notes.insert(0, "Beat-to-beat timing looked regular.")

    if hr_bpm is not None:
        if hr_bpm < 40:
            notes.insert(1, f"Heart rate {hr_bpm:.0f} bpm is very low for a resting measurement.")
        elif hr_bpm > 120:
            notes.insert(1, f"Heart rate {hr_bpm:.0f} bpm is high for a resting measurement.")
    return {"status": status, "ectopic_beats": n_ect, "irregularity": round(nrmssd, 3), "notes": notes,
            "features": features}
