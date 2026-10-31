"""Beat-to-beat intervals, ectopic/artifact flags and HRV metrics (Task Force 1996 conventions)."""
from dataclasses import dataclass

import numpy as np
from scipy import signal

RR_MIN_S, RR_MAX_S = 0.3, 2.0      # 30-200 bpm


@dataclass
class Intervals:
    t: np.ndarray          # time of the beat that ends each interval (s)
    rr: np.ndarray         # seconds
    ectopic: np.ndarray    # bool: premature beat or its compensatory pause
    artifact: np.ndarray   # bool: implausible interval, not physiological

    @property
    def nn(self) -> np.ndarray:
        """Normal-to-normal intervals: drop ectopic and artifact intervals."""
        return self.rr[~(self.ectopic | self.artifact)]


def intervals(times: np.ndarray, good: np.ndarray) -> Intervals:
    """Intervals between consecutive *usable* beats only; a rejected beat breaks the chain."""
    t, rr = [], []
    for i in range(1, len(times)):
        if good[i] and good[i - 1]:
            d = times[i] - times[i - 1]
            if RR_MIN_S <= d <= RR_MAX_S:
                t.append(times[i])
                rr.append(d)
    t, rr = np.array(t), np.array(rr)
    ectopic, artifact = flag_ectopic(rr)
    return Intervals(t, rr, ectopic, artifact)


def flag_ectopic(rr: np.ndarray, window: int = 5) -> tuple[np.ndarray, np.ndarray]:
    """Premature beat: an interval < 80% of the local median followed by one > 110% (compensatory pause).
    Artifact: any other interval deviating > 30% from the local median (missed or extra detection)."""
    n = len(rr)
    ectopic = np.zeros(n, bool)
    artifact = np.zeros(n, bool)
    if n < 5:
        return ectopic, artifact
    med = np.array([np.median(rr[max(0, i - window):i + window + 1]) for i in range(n)])
    for i in range(n - 1):
        if rr[i] < 0.8 * med[i] and rr[i + 1] > 1.1 * med[i]:
            ectopic[i] = ectopic[i + 1] = True
    dev = np.abs(rr - med) / med
    artifact = (dev > 0.30) & ~ectopic
    return ectopic, artifact


def time_domain(nn: np.ndarray) -> dict:
    out = dict.fromkeys(("mean_rr_ms", "sdnn_ms", "rmssd_ms", "pnn50", "sd1_ms", "sd2_ms"))
    if len(nn) < 10:
        return out
    ms = nn * 1000
    diff = np.diff(ms)
    sdnn = float(np.std(ms, ddof=1))
    sd1 = float(np.sqrt(0.5) * np.std(diff, ddof=1))
    sd2_sq = 2 * sdnn ** 2 - sd1 ** 2
    out.update(
        mean_rr_ms=round(float(ms.mean()), 1),
        sdnn_ms=round(sdnn, 1),
        rmssd_ms=round(float(np.sqrt(np.mean(diff ** 2))), 1),
        pnn50=round(float(np.mean(np.abs(diff) > 50) * 100), 1),
        sd1_ms=round(sd1, 1),
        sd2_ms=round(float(np.sqrt(sd2_sq)), 1) if sd2_sq > 0 else None,
    )
    return out


def frequency_domain(t: np.ndarray, nn_mask_rr: np.ndarray) -> dict:
    """Lomb-Scargle on the unevenly sampled NN series: LF 0.04-0.15 Hz, HF 0.15-0.40 Hz (ms^2).
    HF needs >= 60 s of intervals, LF >= 120 s (shorter windows cannot resolve 0.04 Hz)."""
    out = {"lf": None, "hf": None, "lf_hf": None}
    if len(nn_mask_rr) < 20:
        return out
    span = t[-1] - t[0]
    if span < 60:
        return out
    ms = nn_mask_rr * 1000
    y = ms - ms.mean()
    f = np.linspace(0.01, 0.5, 491)
    pgram = signal.lombscargle(t, y, 2 * np.pi * f, normalize=False)
    # Scale so the integral over frequency approximates variance (ms^2).
    psd = pgram * 2 / len(y)
    total = np.trapezoid(psd, f)
    psd = psd * (np.var(y) / total) if total > 0 else psd
    hf = float(np.trapezoid(psd[(f >= 0.15) & (f < 0.40)], f[(f >= 0.15) & (f < 0.40)]))
    out["hf"] = round(hf, 1)
    if span >= 120:
        lf = float(np.trapezoid(psd[(f >= 0.04) & (f < 0.15)], f[(f >= 0.04) & (f < 0.15)]))
        out["lf"] = round(lf, 1)
        out["lf_hf"] = round(lf / hf, 2) if hf > 0 else None
    return out


def stress_index(nn: np.ndarray) -> float | None:
    """Baevsky SI = AMo / (2 * Mo * MxDMn) with a 50 ms histogram (AMo in %, Mo and MxDMn in s)."""
    if len(nn) < 30:
        return None
    idx = np.floor(nn / 0.05 + 1e-9).astype(int)      # 50 ms bins on a fixed grid
    vals, counts = np.unique(idx, return_counts=True)
    k = int(np.argmax(counts))
    mo = (vals[k] + 0.5) * 0.05
    amo = counts[k] / len(nn) * 100
    mxdmn = nn.max() - nn.min()
    if mxdmn <= 0:
        return None
    return round(float(amo / (2 * mo * mxdmn)), 1)


def hrv_metrics(iv: Intervals) -> dict:
    keep = ~(iv.ectopic | iv.artifact)
    out = time_domain(iv.rr[keep])
    out.update(frequency_domain(iv.t[keep], iv.rr[keep]))
    out["stress_index"] = stress_index(iv.rr[keep])
    return out
