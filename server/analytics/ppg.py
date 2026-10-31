"""Raw recording loading, PPG beat detection, per-beat signal quality and motion rejection."""
import csv
import pathlib
from dataclasses import dataclass, field

import numpy as np
from scipy import signal

NOMINAL_FS = 25.0      # the watch streams PPG and accel at ~25 Hz
WORK_FS = 100.0        # beats are located on a 100 Hz cubic resampling for timing precision
SQI_MIN_CORR = 0.80    # template-match correlation for a usable beat (Li & Clifford 2012 style)
MOTION_G = 0.05        # accel-magnitude std (g) over a 2 s window above which a beat is rejected


@dataclass
class Recording:
    start: float                 # unix seconds of the first PPG sample
    fs: float                    # median PPG sample rate
    ppg: np.ndarray              # raw PPG counts
    ppg_t: np.ndarray            # seconds from start, per PPG sample (see retime)
    acc_t: np.ndarray            # seconds from start
    acc: np.ndarray              # (n, 3) in g

    @property
    def duration_s(self) -> float:
        return float(self.ppg_t[-1]) if len(self.ppg_t) > 1 else 0.0


def load_csv(path: str | pathlib.Path) -> Recording:
    """Read a data/raw CSV (ts,kind,ppg,ax,ay,az) written by the watch backend."""
    ppg_t, ppg, acc_t, acc = [], [], [], []
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            if row["kind"] == "ppg" and row["ppg"]:
                ppg_t.append(float(row["ts"]))
                ppg.append(float(row["ppg"]))
            elif row["kind"] == "accel" and row["ax"]:
                acc_t.append(float(row["ts"]))
                acc.append((float(row["ax"]), float(row["ay"]), float(row["az"])))
    return make_recording(np.array(ppg_t), np.array(ppg), np.array(acc_t), np.array(acc).reshape(-1, 3))


def retime(t: np.ndarray, window: int = 125) -> np.ndarray:
    """Per-sample times from packet arrival stamps. Packets arrive in bursts (their reconstructed stamps
    overlap) and the watch's PPG rate is not constant (seen: ~50 Hz for the first ~50 s, then 25 Hz), so
    estimate the local rate over a sliding window of samples and integrate it."""
    n = len(t)
    if n < 4:
        return t - t[0] if n else t
    m = np.maximum.accumulate(t)
    half = window // 2
    rate = np.empty(n)
    for i in range(n):
        a, b = max(0, i - half), min(n - 1, i + half)
        span = m[b] - m[a]
        rate[i] = (b - a) / span if span > 0 else NOMINAL_FS
    rate = np.clip(rate, 10, 80)
    out = np.concatenate([[0.0], np.cumsum(1 / rate[1:])])
    # Anchor the integrated clock to the real span so drift does not accumulate.
    total = m[-1] - m[0]
    return out * (total / out[-1]) if out[-1] > 0 and total > 0 else out


def make_recording(ppg_t, ppg, acc_t, acc) -> Recording:
    """Build a Recording from per-sample arrival stamps (unix seconds)."""
    ppg_t, ppg = np.asarray(ppg_t, float), np.asarray(ppg, float)
    acc_t, acc = np.asarray(acc_t, float), np.asarray(acc, float).reshape(-1, 3)
    if len(ppg) < 2:
        return Recording(float(ppg_t[0]) if len(ppg_t) else 0.0, NOMINAL_FS, ppg, np.zeros(len(ppg)),
                         np.zeros(0), np.zeros((0, 3)))
    start = float(ppg_t[0])
    t = retime(ppg_t)
    fs = float(np.median(1 / np.diff(t))) if len(t) > 2 else NOMINAL_FS
    a_t = retime(acc_t) + (acc_t[0] - start) if len(acc_t) > 3 else acc_t - start
    return Recording(start, fs, ppg, t, a_t, acc)


@dataclass
class Beats:
    times: np.ndarray                     # beat (systolic peak) times, seconds from start
    good: np.ndarray                      # bool per beat: shape matches template and wrist was still
    corr: np.ndarray                      # template correlation per beat
    amplitude: np.ndarray                 # peak minus preceding trough, per beat (filtered units)
    baseline: np.ndarray                  # low-passed PPG level at each beat
    filtered: np.ndarray                  # band-passed, polarity-corrected PPG at WORK_FS
    fs: float = WORK_FS
    polarity: int = 1
    notes: list[str] = field(default_factory=list)


def _bandpass(x: np.ndarray, fs: float, lo: float, hi: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [lo, hi], btype="band", fs=fs, output="sos")
    return signal.sosfiltfilt(sos, x)


def _lowpass(x: np.ndarray, fs: float, hi: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, hi, btype="low", fs=fs, output="sos")
    return signal.sosfiltfilt(sos, x)


def motion_series(rec: Recording, step_s: float = 0.5) -> tuple[np.ndarray, np.ndarray]:
    """Rolling std of accel magnitude (g) over 2 s windows, sampled every step_s."""
    if len(rec.acc) < 10:
        return np.zeros(0), np.zeros(0)
    mag = np.linalg.norm(rec.acc, axis=1)
    t = rec.acc_t
    grid = np.arange(0, max(t[-1], 0) + step_s, step_s)
    out = np.empty(len(grid))
    for i, g in enumerate(grid):
        m = (t >= g - 1) & (t < g + 1)
        out[i] = float(np.std(mag[m])) if m.sum() >= 5 else 0.0
    return grid, out


def detect_beats(rec: Recording) -> Beats:
    notes: list[str] = []
    if rec.duration_s < 8:
        return Beats(np.zeros(0), np.zeros(0, bool), np.zeros(0), np.zeros(0), np.zeros(0), np.zeros(0),
                     notes=["Recording too short to find heartbeats (needs at least 8 s)."])
    t = np.arange(0, rec.ppg_t[-1], 1 / WORK_FS)
    x = np.interp(t, rec.ppg_t, rec.ppg)
    baseline = _lowpass(x, WORK_FS, 0.5)
    y = _bandpass(x, WORK_FS, 0.5, 4.0)

    # Systolic upstrokes are steeper than the diastolic fall; pick the sign that makes the first
    # derivative positively skewed so beats point up regardless of the sensor's polarity.
    d = np.diff(y)
    skew = float(np.mean((d - d.mean()) ** 3) / (np.std(d) ** 3 + 1e-12))
    polarity = 1 if skew >= 0 else -1
    y = y * polarity

    # Adaptive prominence: a fraction of the robust signal spread, so gain/baseline do not matter.
    spread = np.percentile(y, 95) - np.percentile(y, 5)
    peaks, props = signal.find_peaks(y, distance=int(0.33 * WORK_FS), prominence=0.25 * spread)
    if len(peaks) < 4:
        notes.append("Too few clear heartbeats; the watch may be loose or off the wrist.")
        return Beats(np.zeros(0), np.zeros(0, bool), np.zeros(0), np.zeros(0), np.zeros(0), y, polarity=polarity,
                     notes=notes)

    # Parabolic interpolation around each sample peak.
    times = []
    for p_ in peaks:
        if 0 < p_ < len(y) - 1:
            a, b, c = y[p_ - 1], y[p_], y[p_ + 1]
            den = a - 2 * b + c
            off = 0.5 * (a - c) / den if den != 0 else 0.0
            times.append((p_ + off) / WORK_FS)
        else:
            times.append(p_ / WORK_FS)
    times = np.array(times)

    # Per-beat amplitude = peak minus the minimum since the previous peak.
    amp = np.empty(len(peaks))
    for i, p_ in enumerate(peaks):
        lo = peaks[i - 1] if i else max(0, p_ - int(0.6 * WORK_FS))
        amp[i] = y[p_] - y[lo:p_ + 1].min()

    # Template matching SQI: correlate each beat window with the median beat.
    pre, post = int(0.30 * WORK_FS), int(0.45 * WORK_FS)
    idx = [p_ for p_ in peaks]
    windows, valid = [], []
    for p_ in idx:
        if p_ - pre >= 0 and p_ + post < len(y):
            windows.append(y[p_ - pre:p_ + post])
            valid.append(True)
        else:
            windows.append(None)
            valid.append(False)
    corr = np.zeros(len(peaks))
    stack = np.array([w for w in windows if w is not None])
    if len(stack) >= 3:
        template = np.median(stack, axis=0)
        for i, w in enumerate(windows):
            if w is not None:
                corr[i] = float(np.corrcoef(w, template)[0, 1])
    good = (corr >= SQI_MIN_CORR) & np.array(valid)

    # Amplitude outliers (clipping, pressure jumps) are not usable beats either.
    med_amp = np.median(amp)
    good &= (amp > 0.3 * med_amp) & (amp < 3.0 * med_amp)

    # Motion rejection from the accelerometer.
    mt, mv = motion_series(rec)
    if len(mt):
        moving = np.interp(times, mt, mv) > MOTION_G
        if moving.any():
            notes.append(f"{int(moving.sum())} beats dropped because the wrist was moving.")
        good &= ~moving

    return Beats(times, good, corr, amp, np.interp(times, t, baseline), y, polarity=polarity, notes=notes)


def signal_quality(beats: Beats) -> float:
    return float(beats.good.mean()) if len(beats.good) else 0.0


def preview(beats: Beats, max_points: int = 2000) -> list[float]:
    y = beats.filtered
    if not len(y):
        return []
    step = max(1, int(np.ceil(len(y) / max_points)))
    v = y[::step]
    scale = np.percentile(np.abs(v), 99) or 1.0
    return [round(float(a / scale), 4) for a in v]
