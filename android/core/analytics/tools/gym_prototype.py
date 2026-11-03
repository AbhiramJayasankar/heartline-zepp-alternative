"""Reference prototype tuned on the 2026-11-02 gym session (28 logged sets). Port this to Kotlin.

Set detection (per-second score, 25 Hz accelerometer, gravity included):
  1. Resample accel x/y/z to 25 Hz, band-pass 0.10-1.20 Hz (2nd-order Butterworth, zero-phase).
  2. For every second s, take the 20 s window starting at s-10 s. For each axis: normalised autocorrelation;
     score_axis = max ACF value at lags 1.5-8.0 s. periodicity[s] = max over axes.
     A second band, 0.08-0.50 Hz at lags 2.0-8.0 s, scores slow heavy reps (4-5 s each) whose small rep motion is
     otherwise lost in hand shake; periodicity[s] = max of both bands. (2026-11-04, from the 3 Nov bench sets.)
  3. Smooth periodicity with a 5 s moving average. "On" where > 0.45.
  4. Merge on-runs separated by gaps <= 6 s; keep runs >= 12 s. Exclude seconds already classed as walking/running.
     Then join runs <= 15 s apart with no excluded second between: one set whose middle reps dipped below the
     threshold (3 Nov chest fly, 2 Nov rear delt), never two sets, which are at least a minute apart.
  Result on 2 Nov: recall 0.84 (21/25), 2 unlogged detections. On 3 Nov: 14/18, 1 unlogged.
  Misses: hanging leg raises and reverse wrist curls (wrist barely rotates); slow 50 kg barbell bench and a 20 kg
  machine press, whose reps move the wrist about as much as the shake under load.

Rep counting for a detected set [a, b] (seconds):
  1. Core = [a, b]; window = [a-8, b+8] (detected runs end a rep or two inside the set).
  2. Accel band-passed 0.12-1.00 Hz. PCA on the core samples -> first principal direction v; y = (X - mean_core) . v.
  3. Period = lag of max ACF of y_core at 1.2-8.0 s.
  4. amp = 90th percentile of |y_core|. Count peaks of y and of -y over the window with
     min distance 0.55*period and prominence >= 0.9*amp; reps = max of the two counts.
  Result over 2 and 3 Nov (35 matched sets): mean error -0.3 reps, mean |error| 1.0, within 2 reps on 91%.
"""
import numpy as np
from scipy.signal import butter, filtfilt, find_peaks
from scipy.ndimage import uniform_filter1d

FS = 25.0


def resample(arr):
    t = np.arange(arr[0, 0], arr[-1, 0], 1 / FS)
    return t, np.column_stack([np.interp(t, arr[:, 0], arr[:, i]) for i in (1, 2, 3)])


BANDS = ((0.1, 1.2, 1.5), (0.08, 0.5, 2.0))  # (low Hz, high Hz, shortest lag s)


def periodicity(accel):
    t, A = resample(accel)
    filtered = []
    for lo, hi, lag in BANDS:
        b, c = butter(2, [lo / (FS / 2), hi / (FS / 2)], 'band')
        filtered.append((filtfilt(b, c, A, axis=0), int(lag * FS)))
    T = int(len(A) / FS); W = int(20 * FS); P = np.zeros(T)
    for s in range(T):
        best = 0
        for Af, lag in filtered:
            seg = Af[max(0, int((s - 10) * FS)):max(0, int((s - 10) * FS)) + W]
            if len(seg) < W // 2: continue
            for k in range(3):
                x = seg[:, k] - seg[:, k].mean()
                if x.std() < 0.002: continue  # 2 mg RMS: no motion, only filter ringing after a set
                ac = np.correlate(x, x, 'full')[len(x) - 1:]; ac /= ac[0]
                best = max(best, ac[lag:int(8 * FS)].max())
        P[s] = best
    return t[0], P


def detect_sets(P, excluded=None, th=0.45, minlen=12, gap=6, smooth=5, join=15):
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
    joined = []
    for a, b in segs:
        if joined and a - joined[-1][1] <= join and (excluded is None or not excluded[joined[-1][1]:a].any()):
            joined[-1] = (joined[-1][0], b)
        else: joined.append((a, b))
    return joined


def count_reps(accel, t0, a, b, ext=8, promf=0.9):
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
