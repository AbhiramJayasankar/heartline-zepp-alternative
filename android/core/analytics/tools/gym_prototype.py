"""Reference prototype tuned on the 2026-11-02 gym session (28 logged sets). Port this to Kotlin.

Set detection (per-second score, 25 Hz accelerometer, gravity included):
  1. Resample accel x/y/z to 25 Hz, band-pass 0.10-1.20 Hz (2nd-order Butterworth, zero-phase).
  2. For every second s, take the 20 s window starting at s-10 s. For each axis: normalised autocorrelation;
     score_axis = max ACF value at lags 1.5-8.0 s. periodicity[s] = max over axes.
  3. Smooth periodicity with a 5 s moving average. "On" where > 0.45.
  4. Merge on-runs separated by gaps <= 6 s; keep runs >= 12 s. Exclude seconds already classed as walking/running.
  Result on this session: recall 0.80, precision 0.84 (one 'false positive' is pull-up set 2 that has no logged start).
  Misses: hanging leg raises and reverse wrist curls (wrist barely rotates).

Rep counting for a detected set [a, b] (seconds):
  1. Core = [a, b]; window = [a-10, b+10].
  2. Accel band-passed 0.12-1.00 Hz. PCA on the core samples -> first principal direction v; y = (X - mean_core) . v.
  3. Period = lag of max ACF of y_core at 1.2-8.0 s.
  4. amp = 90th percentile of |y_core|. Count peaks of y and of -y over the window with
     min distance 0.55*period and prominence >= 0.9*amp; reps = max of the two counts.
  Result: mean error +0.0 reps, mean |error| 0.9, within 2 reps on 19/20 detected sets.
"""
import numpy as np
from scipy.signal import butter, filtfilt, find_peaks
from scipy.ndimage import uniform_filter1d

FS = 25.0


def resample(arr):
    t = np.arange(arr[0, 0], arr[-1, 0], 1 / FS)
    return t, np.column_stack([np.interp(t, arr[:, 0], arr[:, i]) for i in (1, 2, 3)])


def periodicity(accel):
    t, A = resample(accel)
    b, c = butter(2, [0.1 / (FS / 2), 1.2 / (FS / 2)], 'band')
    Af = filtfilt(b, c, A, axis=0)
    T = int(len(Af) / FS); W = int(20 * FS); P = np.zeros(T)
    for s in range(T):
        seg = Af[max(0, int((s - 10) * FS)):max(0, int((s - 10) * FS)) + W]
        if len(seg) < W // 2: continue
        best = 0
        for k in range(3):
            x = seg[:, k] - seg[:, k].mean()
            if x.std() < 1e-6: continue
            ac = np.correlate(x, x, 'full')[len(x) - 1:]; ac /= ac[0]
            best = max(best, ac[int(1.5 * FS):int(8 * FS)].max())
        P[s] = best
    return t[0], P


def detect_sets(P, excluded=None, th=0.45, minlen=12, gap=6, smooth=5):
    p = uniform_filter1d(P, smooth)
    on = p > th
    if excluded is not None: on[excluded] = False
    segs, i, T = [], 0, len(on)
    while i < T:
        if on[i]:
            j = i
            while j < T and (on[j] or (j + gap < T and on[j:j + gap].any())): j += 1
            if j - i >= minlen: segs.append((i, j))
            i = j
        else: i += 1
    return segs


def count_reps(accel, t0, a, b, ext=10, promf=0.9):
    t, A = resample(accel)
    bb, cc = butter(2, [0.12 / (FS / 2), 1.0 / (FS / 2)], 'band')
    X = filtfilt(bb, cc, A, axis=0)
    off = t[0] - t0
    def idx(sec): return int(max(0, (sec - off) * FS))
    core = X[idx(a):idx(b)]; win = X[idx(a - ext):idx(b + ext)]
    mu = core.mean(0); _, _, vt = np.linalg.svd(core - mu, full_matrices=False); v = vt[0]
    y = (win - mu) @ v; yc = (core - mu) @ v
    ac = np.correlate(yc, yc, 'full')[len(yc) - 1:]; ac /= ac[0]
    per = (np.argmax(ac[int(1.2 * FS):int(8 * FS)]) + int(1.2 * FS)) / FS
    amp = np.percentile(np.abs(yc), 90)
    return max(len(find_peaks(s * y, distance=int(0.55 * per * FS), prominence=promf * amp)[0]) for s in (1, -1)), per
