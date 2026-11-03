# Workout capture

`analyzeCapture(Recording, epoch-second HR samples, Profile, label, activities = emptyList())` is a pure JVM analysis.
`CaptureRunner` in the app's service owns collection, persistence and analysis. No watch workout
start/stop event is assumed, and capture does not start a workout on the watch.

## Clocks and recording

`Recording` adds gyro/mag timestamps and three-axis values with default empty arrays. The existing
PPG and accelerometer check clock, `durationS`, `makeRecording` and golden outputs are retained.
The CSV reader also retains accelerometer arrival timestamps for capture analysis. Gyro/mag rows
use the CSV's `ax,ay,az` columns, in the existing raw integer units; acceleration is already in g.
Motion-only CSVs can be analyzed without PPG.

### Watch-clock CSVs (Task H)

New recordings append `wts` (unwrapped watch seconds) to `ts,kind,ppg,ax,ay,az`.
Type-7 stamps follow their data packet and anchor its **last** sample. The recorder measures
periods independently per sensor; PPG nominally runs at 25/50 Hz and accel at 26.16 Hz.
Gyro/mag rates are measured rather than asserted. `ts` remains epoch seconds through a robust
running affine watch/phone fit. Unstamped packets retain arrival-based fallback with empty `wts`.
See `../../docs/raw-stream-bits.md` for pairing, wraps, rate switching and fit limits.

`readRecordingCsv` puts watch-relative times on a shared origin aligned with the file's epoch
start and marks `Recording.watchClock`. Empty `wts` cells use nearby epoch/watch anchors.
Watch-clock PPG bypasses both `retime` and `capturePpgClock`; accel bypasses motion retiming.
The motion and rep grids remain 25 Hz, with true sensor times as interpolation inputs. New
rep grids use relative seconds; the epoch-rounded increment below remains for legacy files.
Reconnect files retain their independent epoch offsets, even across a watch-clock reboot.

PPG processing has no assumption of 25 input samples/second. Beat detection interpolates actual
sample times to the existing 100 Hz analysis grid; filter coefficients, refractory distances,
templates and parabolic peak refinement refer to that grid, independently of input rate.
HRV uses the resulting beat times. Capture pulse coverage gates use seconds for watch-clock
files, including runs whose rate changes. Its 25 Hz excerpt is a downsampled display waveform,
not an asserted sensor rate. `Recording.fs` measures the median reciprocal sample interval.

Only the new format excludes PPG LED warm-up from analysis: the first 0.4 s after each run
start/gap and low settling values below 40% of the following second's median during the first
2 s. Raw CSV values are retained. Interpolated beats across missing coverage cannot qualify.
Files without a `wts` column take the original code paths, including all legacy clocks, gates,
filter/template behavior and warm-up handling. Real-session/golden fixtures are unchanged;
`CAPTURE_ENGINE_VERSION` stays **3** because existing capture analyses do not change.

Fast PPG is requested by default through the connection's single `FAST_PPG_ENABLED` flag.
No PPG within about 5 s triggers one logged restart without 0x80. The 2026-11-03 probe fixture
measures 25.1002 Hz (0x02), 50.1446 Hz PPG / 26.2076 Hz accel (0x83), and 26.1559 Hz accel
(0x81). 0x82 changes from 25.1276 to 50.1557 Hz during the run, averaging 39.4849 Hz;
there are no data/stamp counter gaps. Mixed-sensor fast masks still need a watch check.

Gait/intensity analysis retimes each contiguous stream separately to remove overlapping packet arrival
stamps. A gap over one second breaks retiming, so it cannot be stretched into artificial samples.
Vectors are linearly resampled at 25 Hz only across intervals at most 500 ms apart. Missing data
remains missing. Set/rep analysis instead interpolates the arrival clock directly, matching the gym
prototype: retiming the overlapping packets changes the rhythm score. It runs separately on spans
split by invalid samples or arrival gaps over 500 ms. Reconnect CSVs are shifted by their epoch offsets, including the leading interval
between the user's start and the first sample. Capture end supplies the full report duration.

## HR, zones, load and calories

The only HR source is stored live `HR_MEASURE` notifications, not raw PPG or synced minute HR.
Valid values are 25–240 bpm. Sorted, deduplicated notifications supply a one-second clock by holding
the latest observation for at most five seconds, without filling longer gaps. Statistics, five-second
HR bins, zone seconds, load and calories use this observed coverage. A missing five-second bin is
omitted, not emitted as zero. A partial final second is counted as a second; uncertainty is below
one second per session boundary. Missing HR gives null statistics/load/calories and a coverage note.

Maximum HR is the supplied profile maximum (80–240), otherwise `220 - age` for a valid age,
otherwise 190. The five zones are [50,60), [60,70), [70,80), [80,90), and >=90 percent of maximum;
time below 50 percent is excluded. Bounds in the result are lower-inclusive, upper-exclusive.
Edwards TRIMP is `sum(zone_number * seconds_in_zone / 60)`, in weighted minutes.

Calories use Keytel et al.'s equation without a fitness test: male kJ/min =
`-55.0969 + 0.6309*HR + 0.1988*kg + 0.2017*age`; female kJ/min =
`-20.4022 + 0.4472*HR - 0.1263*kg + 0.074*age`. Each covered second contributes
`max(0, kJ/min)/(4.184*60)` kcal. Missing age, recognized sex or positive weight gives null.
These are total HR-based estimates for the observed window, not an estimate of active calories
above basal metabolism. The model is less reliable for lifting and post-exercise elevated HR.
Source: [Keytel et al., J Sports Sci 2005, 23:289–297](https://doi.org/10.1080/02640410470001730089).

Still-window pulse regularity is an independent PPG check, described in Task G below. It never
substitutes for, smooths, or corrects the watch's HR. Moving PPG cannot contribute beats to the
capture HR report. Synced watch workout summaries are retained separately from raw-derived metrics.

## Motion and segmentation

Five-second motion intensity is RMS of the 0.1–8 Hz zero-phase Butterworth-filtered `|a| - 1 g`.
Filters run independently inside contiguous coverage. A bin needs >=80% valid motion data.
Missing bins are `other` and have no motion-series point. Rest requires norm RMS <0.03 g and
every filtered axis RMS <0.065 g, limiting false rest during slow orientation changes.

Walking/running uses acceleration magnitude as an orientation-independent proxy for vertical
impact, looking for spectral energy at 1.4–3.5 Hz in a ten-second window. A Hann-windowed FFT
locates the frequency; >=15% spectral concentration within +/-0.15 Hz and normalized band-limited
autocorrelation >=0.25 supply evidence, with norm RMS >=0.045 g. At most ten seconds of uncertain
but present motion between consistent gait bins can be bridged (cadence difference <=30 spm).
Gait must last >=20 seconds to reduce false positives from short arm movements. Running means cadence >140 spm or high impact (RMS >0.65 g).
Cadence is the mean of the accepted per-bin frequency estimates * 60; steps integrate cadence
over the segment. This estimates footfalls from the wrist and is not the watch's pedometer count.

Lifting now follows the supplied `prototype_detector.py`, tuned on the 2026-11-02 gym session.
Machine/cable sets can have less wrist movement than walking or re-racking between sets, so
neither amplitude nor gyro corroboration gates a set. `CaptureRhythm.kt` implements:

1. Linear arrival-clock interpolation at 25 Hz; order-2 Butterworth band-pass 0.10–1.20 Hz,
   forward/backward with odd padding. Existing SOS filter primitives are numerically equivalent
   to the prototype's `butter(2, ..., 'band')` and `filtfilt` for these bands.
2. Each second uses 500 samples starting at `max(0, (second-10)*25)`, truncated at the span end;
   fewer than 250 samples gives zero. For each centered axis, normalize ACF by **full zero-lag
   energy**, then take the maximum over lags 37..199 (Python's `int(1.5*25):int(8*25)`). The
   per-second score is the maximum over the three axes. No detrending or overlap normalization.
   Since 2026-11-04 a second band, 0.08–0.50 Hz at lags 50..199 (2–8 s), is scored the same way
   and the score is the larger of the two; windows under 2 mg RMS score zero (see Engine 4).
3. Five-second mean with SciPy's reflect edges, strictly >0.45. Exclude existing walking/running
   seconds. Merge short gaps with the prototype's six-sample lookahead and keep runs >=12 s.
   The actual Python slice bridges **five off-seconds**, despite the docstring's “gaps <=6”.
   Excluded gait seconds and coverage gaps are hard barriers, including during bridging.
   Runs at most 15 s apart, with no excluded second between, are then joined into one set.
4. For each set, band-pass acceleration at 0.12–1.00 Hz. Center XYZ by the core mean and find
   its first principal direction (3x3 covariance Jacobi eigendecomposition, equivalent to the
   prototype's first right singular vector). Project the core and a window extended by 8 s
   (10 s before 2026-11-04) on each side, clipped to the same coverage span.
5. Tempo is the lag of the core's maximum normalized ACF over lags 30..199 /25 (1.2–8 s).
   Amplitude is the 90th percentile of absolute projected core values. On the extended window,
   count local maxima for both signal signs with distance `int(.55*period*25)` and prominence
   >=0.9*amplitude; reps is the larger count. Thus counted peaks can precede/follow the core
   boundaries. Set HR still uses the core. There is no arbitrary upper set-duration limit.

The epoch-sized `numpy.arange` increment is preserved, including its floating-point rounding;
a few milliseconds of resampling drift can change a near-threshold run. Existing gait/intensity
and stillness bins remain five-second estimates; rhythm boundaries use one-second steps relative
to each continuous span. Sensor stalls/reconnects never become interpolated artificial sets.

Rests are now the entire gaps between successive detected cores, including walking/re-racking.
This is an inter-set recovery interval, not proof of stillness or complete motion coverage.
Recovery remains start HR minus HR 60 seconds later, null for shorter gaps or missing HR.
Negative recovery is retained. The independent pulse check uses per-second accel/gyro still stretches, rather than these
recovery intervals; the new peak-aligned aggregate curve is described in Task G below.

This remains a wrist-rhythm heuristic, not an exercise recognizer. Hanging leg raises and reverse
wrist curls are known blind spots when the wrist barely rotates. Irregular/fast reps (periods
under 1.2 s can alias), static lifts, alternating arms, partial reps, long periodic non-exercise
movement and incidental rhythmic activity can change classification/counts. Gait exclusion can
trim a strength core. Magnetometer/gyro recordings are retained but do not gate lifting.
There is no raw-sensor GPS or distance estimate. A matching synced watch workout may supply distance.

## Service and persistence

The foreground `WatchService` owns the runner and its coroutine scope, so closing the UI does not
stop capture. Start requires a ready watch and exclusive raw recording ownership. Raw/check start
commands and live-HR-off are guarded while capture is reserved, running or being analyzed.
Stop is manual or on the planned epoch deadline, checked once per second. A link loss leaves the
session open. Existing wanted-stream reconnect behavior plus the runner's ready observer restore
all four raw sensors and live HR. Gaps close when raw streaming is available, not at authentication.
Live HR is restored to its pre-capture state on stop. Offline raw/HR stops clear wanted flags, so
stopping during a disconnect cannot unexpectedly restart capture after reconnect.

Meta keys are `capture_session_<UUID>` (id, label, start/end, planned seconds, CSV names, sample
count, gaps, prior live-HR state, error) and `capture_result_<UUID>` (explicit result JSON matching
the fixed UI contract). The file list is saved on every change and counts at least every ten seconds;
HR remains in `hr_samples`. Analysis and CSV loading run on `Dispatchers.Default`. Deletion removes
both meta rows, capture CSVs and their recording index rows, with canonical path checks. A small
`MetaRepository.delete` method supplies actual row deletion without schema changes.

A sticky service recreation resumes the unfinished session if still within its plan, using the last
recording modification time to bound downtime; overdue sessions end at the planned deadline and
are analyzed with a restart note. Cached missing analyses are retried and can be requested on demand.
OS force-stop is outside the UI-survival guarantee: no samples can be collected while Android has
killed the service, and a session cannot auto-stop on the CPU until the service runs again.

## Wire bytes and provenance

No new encoder/decoder is introduced. Commands reuse `WatchConnection`, `GattClient` and the
existing protocol functions, preserving all GATT serialization, timing and notification quirks.

| Operation | Bytes/format | Source |
| --- | --- | --- |
| Raw start | `01 53 19`, `01 53 00 00 00 19`, `02`, with existing 400 ms pauses | `HuamiSupport.setRawSensor`, `Huami2021Service.CMD_RAW_SENSOR_START_1/2/3`; GB's original mask is `03`. This watch's previously verified gyro `10` and mag `40` mask bits extend it to `53`. |
| Raw stop | `03` on **RAW_CTRL**, never activity fetch control | `HuamiSupport.setRawSensor`, `Huami2021Service.CMD_RAW_SENSOR_STOP` |
| Live HR on/off | Notify standard `2a37`; write `15 02 00`, `15 01 01` to `2a39`; off `15 01 00` | `HuamiSupport.onEnableRealtimeHeartRateMeasurement` and its `stopHeartMeasurementManual/startHeartMeasurementContinuous/stopHeartMeasurementContinuous` constants |
| HR keepalive | `16` every ten seconds | Existing Heartline `HrCommands.PING` / `WatchConnection.setLiveHr`, verified watch-specific behavior; not claimed as new Gadgetbridge behavior |
| HR decode | Standard flags bit 0 selects unsigned 8-bit or little-endian 16-bit BPM | Existing `parseHeartRate`; Gadgetbridge `HuamiSupport.handleHeartrate` handles the watch's usual `00 <u8 BPM>` pair. The existing Heartline parser additionally supports the standard LE16 form. |
| Raw records | Two-byte type/index header; type `00`: signed LE16 XYZ /4100 g; watch-specific type `01`: unsigned LE16 PPG; `04` gyro / `06` mag: signed LE16 XYZ | `HuamiSupport.handleRawSensorData` establishes header and type `00` scale; the remaining types reuse this watch's verified `protocol.parse_raw` in `server/protocol.py` and Heartline `parseRaw`. **Current Gadgetbridge does not decode this watch's PPG/gyro/mag types.** |

References inspected upstream because the brief's sparse checkout path was absent:
[HuamiSupport.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiSupport.java),
[Huami2021Service.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/Huami2021Service.java),
`AmazfitGTR2eSupport` (only firmware helper override) -> `AmazfitGTR2Support` -> `AmazfitGTRSupport` -> Huami base,
and `devices/huami/amazfitgtr2/AmazfitGTR2eCoordinator` (supports HR; no capture-specific format override).
The existing `0x03` destructive activity-fetch acknowledgment remains absent. Notification chunking,
its extra GTS header and MTU 23 writes are unchanged; capture does not send notification chunks.

## Validation and hardware review

`WorkoutCaptureTest` covers known rep rates (0.2/0.333/0.5/0.8 Hz), two sets with recovery, 108-spm
walking, 168-spm running, exact HR zones/Edwards load, both Keytel equations, HR/motion gaps,
CSV vector retention, isolated impacts and long periodic bouts. The old 1 Hz synthetic test was
changed to 0.8 Hz because the tuned ACF starts at a 1.2 s period; the old >2 minute rejection
test now expects a sustained set, matching the prototype's lack of an upper-duration gate.
`CaptureRhythmTest` pins ACF normalization, threshold/minimum duration, gap lookahead, gait
barriers, rotated PCA, low-amplitude gyro-free sets, extended-window peaks, coverage gaps and
capture deadlines. `CaptureTimelineTest` covers planned
stop boundaries, repeated gap updates, stop during disconnect, file deduplication and raw counts.
Mapping tests exercise every UI result field and reconnect offsets. Wire tests pin reused commands.

`capture_gym_trimmed.csv` contains the first 150 seconds (15,054 unchanged rows plus header) of
the supplied 366-second recording. It includes initial stillness, the complete walking interval and
post-walk movements. Expected output: rest in 30 of the first 40 seconds (initial movement/brief
orientation changes remain `other`), walking 55–110 seconds at 101 spm, no lifting sets. HR is absent
from the CSV; the test correctly leaves HR-derived summaries null rather than using moving PPG.

Owner hardware review should check: all four raw rates during a watch workout; stable live watch HR (about one notification
per 2.6 s on this session); capture continuity with the UI closed; reconnect producing a new CSV and an accurate gap;
manual/planned stop while disconnected followed by reconnect producing no new capture file;
restoration of pre-existing live-HR mode; two counted sets at known tempo separated by >=60 s of
rest; a one-minute treadmill walk and run (including holding rails); and no false lifting sets during
ordinary wrist movement. Compare set boundaries/counts and HR recovery against a manual log.
A live watch workout flag is unavailable; synced watch workout names/totals are compared separately
from raw movement labels. No phone or watch interaction is part of development validation.

## Task F real-session regression (2026-11-02)

`src/test/resources/recordings/gym_20261102.csv.gz` contains 149,806 original accelerometer rows
through 23:51 IST, with original timestamps and axis precision, no PPG/gyro/mag or decimation.
Gzip size is 1,389,546 bytes. The first sample is 22:13:49.964 IST. The JSON log preserves all
28 workout rows: **27 resistance sets and one treadmill bout**. Pull-up 1 ended at 22:13:41,
before capture; its start remains null. Pull-up 2 uses the owner-authorized 22:15:00–22:15:48
interval. Partial rep ground truth (8.5 and 9.75) is retained rather than rounded.

Matching is one-to-one positive temporal overlap with a logged resistance interval; logged
message timestamps have latency, so this is bout detection accuracy, not boundary IoU accuracy.
No extra time tolerance is added. The frozen Python oracle also includes the existing gait mask;
the regression asserts that mask as well as sets, boundaries (<=2 s), reps (exact), and tempo.

Measured result: **23 detections, 21 matches, recall 21/27 = 77.78%, precision 21/23 = 91.30%**.
The pre-capture first set remains in the recall denominator. Among in-capture resistance sets,
recall is 21/26 = 80.77%. Conservatively counting all 28 logged workout rows, including cardio,
gives 21/28 = 75.00%; the regression also asserts that floor. Reps are within two on **19/21
(90.48%)** matched sets; mean absolute error is **1.06 reps**. The two larger errors are preacher
curl set 2 (7 vs 10, trimmed by gait exclusion) and rear-delt set 3 (7 vs 9.75, truncated by the
recording gap). Walking covers 590.04/603 logged treadmill seconds (97.85%); the existing gait
classifier starts approximately 13 s after the logged start. There is no lifting overlap.

The unmodified Python prototype is preserved in `tools/gym_prototype.py`. `tools/gym_reference.py`
rebuilds fixtures/oracle from the supplied raw directory/log; `tools/gym_compare.py` independently
replays the checked-in fixture and compares with `build/gym_kotlin.csv` emitted by the JVM test.
Python needs NumPy/SciPy only for reproduction; JVM tests use the frozen JSON and add no dependency.
Reference generation used NumPy 2.5.4 / SciPy 1.18.1. Both implementations apply the existing
no-gap-interpolation rule by running the unchanged prototype separately on continuous spans
(100.493 s reconnect and 4.895 s stall), and exclude the same existing gait seconds. This avoids
the original whole-session interpolation's fabricated gap detections. Gait exclusion is also a
hard merge barrier in Kotlin; on this session it leaves the Python output unchanged.

Reproduce from `android/` after `:core:analytics:test`:

```text
python core/analytics/tools/gym_compare.py
```

Both engines return **23 sets, identical boundaries (maximum difference 0 s), identical reps
and identical ACF tempos**. These figures describe the tuning session, not held-out validation.
Owner review should specifically check the unlogged 23:00:18–23:00:31 and 23:17:27–23:17:57
bouts (false positives under the current log), quiet hanging leg raises, reverse wrist curls,
preacher curls that resemble gait, the truncated rear-delt set, and fast/irregular reps in another
session. No new protocol bytes, persistence keys, schema, UI or BLE changes are introduced.

| # | Python interval IST | Kotlin interval IST | Reps Python/Kotlin | Logged reps | Tempo s |
|---|---|---|---|---|---|
| 1 | 22:15:03–22:15:26 | 22:15:03–22:15:26 | 10/10 | 8 | 4.56 |
| 2 | 22:17:41–22:17:59 | 22:17:41–22:17:59 | 9/9 | 8 | 4.36 |
| 3 | 22:20:21–22:20:35 | 22:20:21–22:20:35 | 8/8 | 8 | 4.32 |
| 4 | 22:24:16–22:24:37 | 22:24:16–22:24:37 | 8/8 | 8 | 4.88 |
| 5 | 22:26:47–22:27:30 | 22:26:47–22:27:30 | 13/13 | 12 | 4.84 |
| 6 | 22:29:42–22:29:57 | 22:29:42–22:29:57 | 7/7 | 7 | 4.80 |
| 7 | 22:32:35–22:33:16 | 22:32:35–22:33:16 | 11/11 | 10 | 5.12 |
| 8 | 22:36:23–22:37:07 | 22:36:23–22:37:07 | 12/12 | 12 | 4.80 |
| 9 | 22:40:22–22:40:42 | 22:40:22–22:40:42 | 8/8 | 8.5 | 4.28 |
| 10 | 22:44:19–22:44:49 | 22:44:19–22:44:49 | 8/8 | 8 | 3.64 |
| 11 | 22:46:54–22:47:19 | 22:46:54–22:47:19 | 7/7 | 10 | 3.92 |
| 12 | 22:50:35–22:51:19 | 22:50:35–22:51:19 | 13/13 | 12 | 3.80 |
| 13 | 22:54:48–22:55:15 | 22:54:48–22:55:15 | 7/7 | 6 | 5.40 |
| 14 | 23:00:18–23:00:31 | 23:00:18–23:00:31 | 7/7 | unmatched | 4.64 |
| 15 | 23:01:57–23:02:21 | 23:01:57–23:02:21 | 10/10 | 12 | 4.48 |
| 16 | 23:07:19–23:07:45 | 23:07:19–23:07:45 | 10/10 | 12 | 5.24 |
| 17 | 23:10:10–23:10:47 | 23:10:10–23:10:47 | 13/13 | 12 | 4.08 |
| 18 | 23:13:25–23:13:47 | 23:13:25–23:13:47 | 7/7 | 9.75 | 4.56 |
| 19 | 23:17:27–23:17:57 | 23:17:27–23:17:57 | 24/24 | unmatched | 1.52 |
| 20 | 23:35:16–23:35:29 | 23:35:16–23:35:29 | 14/14 | 12 | 2.16 |
| 21 | 23:38:01–23:38:16 | 23:38:01–23:38:16 | 13/13 | 12 | 2.56 |
| 22 | 23:41:12–23:41:28 | 23:41:12–23:41:28 | 10/10 | 10 | 3.28 |
| 23 | 23:50:02–23:50:22 | 23:50:02–23:50:22 | 7/7 | 8 | 4.64 |

23 sets; maximum boundary difference 0 s; all rep counts identical.
21 matches / 27 resistance sets (including the pre-capture first set); recall 0.7778; precision 0.9130; within 2 reps 19/21; MAE 1.0595.

Task F changes (all tracked changes remain inside `core/analytics`):

- Updated `CaptureMotion.kt` to retain gait/intensity/stillness and overlay the rhythm cores;
  updated `WorkoutCapture.kt` to consume per-set rep/ACF results and recover over inter-set gaps.
- Added `CaptureRhythm.kt` for arrival interpolation, periodicity, core detection, PCA and rep count.
- Updated `WorkoutCaptureTest.kt`; added `CaptureRhythmTest.kt` and `GymCaptureTest.kt`.
- Added the gzip fixture, 28-row log JSON and frozen Python oracle JSON in `src/test/resources/recordings/`.
- Added `tools/gym_prototype.py`, `tools/gym_reference.py`, `tools/gym_compare.py`; updated this document.

Validation on 2026-11-03: analytics **54**, protocol **196**, BLE **29**, app **93** JVM tests;
**372 total, zero failures/errors/skips**. `:core:analytics:test :core:protocol:test :core:ble:test
:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed. App lint has no errors.
Python replay also passed with the comparison table above. No commit, install or device interaction.

The restricted Windows environment required writable Android/JVM user directories and in-process
Kotlin compilation. AGP's default debug-keystore lock was denied, so an ignored build-only init
script points debug signing at a workspace copy of the existing debug keystore. No project build
file or signing identity changed. Exact verification invocation from `android/` (Git Bash):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="/c/Users/abhir/.gradle"
export ANDROID_USER_HOME="C:/Users/abhir/Desktop/projects/hl-f/android/build/android-user"
./gradlew --no-daemon \
  -Duser.home=C:/Users/abhir/Desktop/projects/hl-f/android/build/user-home \
  -Pkotlin.compiler.execution.strategy=in-process -I build/gym-signing.init.gradle \
  :core:analytics:test :app:testDebugUnitTest :app:assembleDebug \
  :core:protocol:test :core:ble:test :app:lintDebug
```


## Task G: capture engine 3 (2026-11-03)

All new times are seconds from the capture start, except `CaptureResult.start` (epoch seconds).
The existing 23 set cores, gait mask, rep counts and ACF tempos remain unchanged. No log is
available to production analysis. The manual log is used only by regression/tuning tools.
`CaptureResult` adds activities, recovery, floor, work/rest seconds, cardio and pulse; each set
adds complete-cycle rep details, a group, watch activity name, slowdown, partial-last flag and HR rise.
The app maps and persists every field against the existing UI contract; Controller, DemoController
and UI sources are unchanged. Engine version `3` invalidates cached results.

### Rep cycles, orientation and exercise groups

`rhythmReps` returns the winning peak indices, projection, sign, window offset and PCA direction.
Its count is still exactly `max(positive peaks, negative peaks)`. Previously the sign was discarded;
when counts tie, select the sign with lower variance of peak-to-peak periods. This deterministic
phase choice avoids PCA eigenvector sign determining the reported cycle. Detection thresholds,
filter bands, extended windows, peak finding, counts and tempos are unchanged.

Consecutive peaks define one complete cycle, so `repDetail.size == reps - 1`; no final cycle is
invented after the last peak. The signed projection's minimum between peaks splits the phases;
slow/fast are their maximum/minimum, without claiming eccentric/concentric identity. Independently
low-pass raw XYZ at 1.5 Hz inside the same coverage span. Normalize its vectors and take the angle
at the first peak versus the trough. These are wrist arcs, not joint range of motion. Regress
complete-cycle durations against zero-based cycle index for slowdown when there are >=5 cycles.
Flag the last cycle below 80% of the median arc when there are >=4 cycles. Extended-window
incidental movement and asymmetric cycles can affect the last flag and slope.

Grouping uses adjacent set cores and a deliberately small rule: start a new group if the gap is
>360 s, any derived cardio block intervenes, the mean gravity directions differ by >25 degrees,
the tempo ratio exceeds exp(0.4) in either direction, or the gravity difference is >10 degrees AND
median wrist arc changes by both >15 degrees and a factor >1.6. Otherwise retain the group. This
uses gravity, tempo and arc; PCA sign/axis is not needed to separate the tuned exercises. It does
not assign exercise names. The arc condition separates low-rotation pull-ups from pulldowns while
avoiding a split inside the slower final preacher-curl set.

Measured on both the compact fixture and the two complete raw CSVs:

- 23 sets, exact frozen boundaries (maximum difference 0 s), exact counts and ACF tempos.
  The previous 21/27 logged resistance matches and rep accuracy remain unchanged.
- Four preacher-curl median arcs: **139.62, 139.72, 137.36, 141.39 degrees**.
- 22:40 pulldown: median **33.46 degrees**, last **25.44 degrees**, ratio **0.760**; partial-last true.
- 22:20 and 22:24 assisted pull-ups: slowdown **+0.2414 and +0.2186 s/cycle**.
- Eight runs across all 23 detections, including two unmatched bouts. Among 21 positive-overlap
  log matches spanning six observed exercises, **adjusted Rand index 1.000**. All **20/20 adjacent
  matched-set decisions** are correct: **5/5 exercise boundaries** and **15/15 non-boundaries**.
  The two unmatched bouts have no exercise label and are excluded from this score. The regression
  requires ARI >=0.90 and all 20 decisions. This is tuning-session accuracy, not held-out validation.

### Live HR, recovery and work/rest

`startHr` is the latest real notification at/before the exact set start, valid for <5 s. HR rise is
the maximum observed HR from start through end+15 s, including the held start value, minus start HR.
Missing start HR keeps both fields null. Lookup uses binary search and bounded ranges, not a full
HR scan for every set. The existing set average and core peak fields retain their old meanings.
Activity is the synced watch workout containing the set midpoint, irrespective of motion label.

**22/23** set starts have HR: **102-139 bpm**; observed rises span **0-14 bpm**. The 22:29 set
has missing start HR under the five-second freshness rule. Nineteen sets fall inside the watch's
Strength training record, two forearm sets inside its still-open Treadmill record, and two outside
both records. This disagreement is intentionally retained instead of using watch names to change
raw set classification.

For recovery, consider each inter-set rest >=60 s with no cardio block. Find the first maximum
on the one-second observed HR clock in [set start, end+15), bounded by the next set. Align to that
peak, then sample at five-second offsets through 240 s and strictly before the next set. Offset
zero is the alignment anchor; other samples before the core ends are excluded. Missing HR never
fills a point. Retain offsets with n >= max(3, ceil(0.20 * eligible rests)). There are **21 eligible
rests**, minimum support **5**. Measured deltas: **-4.92 bpm at 20 s (n=12), -7.63 at 60 s (n=19),
-5.00 at 120 s (n=18), +1.00 at 180 s (n=11)**. The curve falls early, stays fairly flat to two
minutes and rises later. Its level differs from the log-aligned Python curve because detector
cores/peak times differ from typed set intervals and absent HR is excluded.

The HR floor is the linearly interpolated 10th percentile of covered one-second HR in each
five-minute capture-relative window, including the final partial window. **22 points**: first
**88 bpm**, late-strength **128 bpm**, treadmill **173 bpm**, final **108 bpm**. Work is the sum
of detected core lengths: **581 s (9:41)**. Rest sums consecutive-core gaps <=600 s without an
intervening derived cardio block: **4098 s (68:18)**, work/(work+rest) **12.4%**. These exclude
undetected/extended rep time and are not manual-log workout totals.

### Cardio and watch comparison

Merge existing walking/running segments across gaps <=20 s; require >=180 s. A block under 6 min
with a set ending within 30 s of its start and another starting within 30 s of its end, and no
cardio workout on the watch over it, is walking during a rest and is dropped. Split at overlapping
cardio-workout boundaries, so a watch name only applies to gait inside that workout. Keywords
(case-insensitive) include treadmill, walk, run, cycl, bike, ellipt, row, stair and cardio. Gait,
set exclusion and cadence classification are unchanged. Cadence is duration-weighted existing gait
cadence. Keep original watch distance and active seconds; speed is 3.6 * distance_m / active_s.
Clipping a watch record changes only bounds, never prorates its totals.

Average HR uses all covered seconds; steady HR and drift use the last half (drift is least-squares
slope *60). Fit b+a*(1-exp(-t/tau)) for rise and asym+d*exp(-t/tau) for recovery, searching integer
tau 10..600 with analytic linear least squares. Fits require >=20 observations spanning >=30 s,
positive amplitude >=3 bpm, >=5% variance explained, and an interior optimum; otherwise null.
Recovery uses up to 240 s after gait ends, truncated by another cardio block. Drop60/120 uses
observed HR at the block end minus HR at the offset and is null beyond coverage or at/after the
next block. An intervening strength effort can still contaminate a recovery fit.

The raw block is **23:22:49.964-23:33:29.964 IST (640 s)**, named **Treadmill**:

| Metric | Raw/gait-derived | Synced watch record |
|---|---:|---:|
| Average HR | 169 bpm | 166 bpm |
| Steady / peak HR | 179 / 184 bpm | peak 184 bpm |
| Rise tau | 119 s | unavailable |
| Last-half drift | -0.059 bpm/min | unavailable |
| Drop at 60 / 120 s | 33 / 40 bpm | unavailable |
| Recovery tau | 48 s | unavailable |
| Cadence | 96 spm | 92 spm |
| Active time | gait block 640 s | 612 s |
| Steps | approximately 1024 from rounded block cadence | 935 |
| Distance / speed | no independent raw estimate | 683 m / 4.018 km/h |

The watch record runs 23:22:34-23:39:58, longer than its active time. The unchanged gait classifier
continues through cooldown about 50 s after the logged treadmill stop. Therefore rise tau,
drift, drop60 and recovery tau differ from the brief's log-bounded Python estimates (160 s,
+0.8 bpm/min, 25 bpm, 73 s). The regression tolerances explicitly allow those boundary effects;
no log or guessed active-time cutoff is injected into production segmentation. Distance/speed
fields on Cardio are clearly the matching watch record's values. The second preserved activity is
Strength training, 4098 active seconds, average/max 118/152 bpm, 777 steps and 519 kcal.

### Pulse regularity

Per capture-relative second require >=15 accel AND gyro samples, accel-magnitude SD <0.03 g,
and mean gyro magnitude <0.6 times the session median of covered per-second magnitudes. Subtract
session median XYZ gyro bias before the norm. Require the surrounding three still seconds; bridge
an isolated one-second hole, then require >=20 s. Analyze at most 40 stretches, longest first,
with at most 120 s per stretch. PPG gaps >0.5 s reject the window; a >1 s arrival gap breaks its
clock fit. A per-run least-squares sample-index/arrival-time clock removes packet jitter without
stretching reconnects. `Recording.ppgArrivalT` retains raw timing separately from the unchanged
legacy PPG/check clock, and capture merging preserves its epoch offsets.

Reuse `detectBeats`, `intervals`/`flagEctopic` and `assessRhythm`. An optional live-HR capture mode
in detectBeats uses the polarity-corrected band-passed waveform's systolic derivative peak, with
refractory distance 0.55 * live RR and a one-RR morphology template; normal check-session defaults
and golden results are unchanged. Live HR selects refractory/template lengths, never beat times,
intervals or HR estimates. Saturation >2% rejects a window; interpolate smaller glitches. Require
>=30 clean intervals and mean clean-interval PPG HR within 10 bpm of live HR; other measurable
windows are unreliable. Per-window RMSSD/nRMSSD uses clean intervals. Pool clean intervals at their
original scale for assessRhythm, preserving original per-window ectopic candidate counts via an
optional override; never search for compensatory pauses across window joins. Pooling can still
include a difference between adjacent windows' HR scales. Preserve the original medical disclaimer.

Only adjacent originally consecutive clean intervals enter Poincare pairs (milliseconds, cap 600).
The best clean-interval yield supplies a 12 s, 25 Hz filtered excerpt with beat offsets from its
start. Original waveform/beat pairs are retained, not synthesized from live HR.

Full raw result: **9 candidate stretches**, 8 measurable windows, **6 valid / 183 usable seconds**,
overall **regular**, **nRMSSD 0.042**, entropy **0.925**, **zero ectopic candidates**. The other two
measurable windows have <30 clean intervals; one candidate has too little clean PPG. Valid-window
PPG/watch differences: **1, 1, 5, 2, 1, 0 bpm** (mean **1.67**, max **5**). Valid nRMSSD is
**0.0223-0.0558**, RMSSD **11.66-29.70 ms**. There are **211 Poincare pairs**, **300 excerpt
samples**, **25 excerpt beats**. The compact fixture gives the same status, usable seconds, counts
and agreement; entropy 0.923 and very small timing differences reflect local versus complete-run
clock fits. This is more conservative than the separate Python pulse prototype's 9-11 windows.
It is an optical wellness estimate, not ECG evidence or a medical diagnosis.

### Fixtures, data loading, performance and validation

`gym_rich_fixture.py` reads the supplied CSVs and SQLite with mode=ro, using no manual log. It keeps
all gyro rows, original PPG around candidate still windows (+/-5 s), and the remaining accel tail
past the existing fixture's 23:51 cutoff. Gyro/PPG retain their source values; the accel tail keeps nine significant digits, preserving engine Float precision.
The new gzip is **1,709,882 bytes**, HR/watch JSON **41,582 bytes**; total new fixtures **1,751,464
bytes**, below 3 MB. Existing accel/oracle/log fixtures are untouched. Regenerate from android/:

```text
python core/analytics/tools/gym_rich_fixture.py PATH_TO_GYM2
```

Full raw analysis reads **166,655 accel, 166,657 gyro, 159,581 PPG and 149,793 mag samples** over
**6473.97 s**. Measured engine-only time **1.34 s** on this Windows JVM (CSV loading excluded),
compact-fixture runs **1.17-1.70 s** while building, and a **3-hour replay 1.77 s**. Phone execution
and memory are not measured. New sensor passes/clock fitting are linear, medians use sorting,
HR range queries use binary search, pulse work is bounded by 40*120 s, and exponential fitting
uses a fixed 591-candidate tau grid (linear in block length) with at most 240 s of recovery.

The service loads overlapping workout summaries (including earlier starts) through
`HealthRepository.workoutSummariesOverlapping`, excluding large track JSON. JSON end times supply
the overlap predicate without a schema change; malformed summaries are ignored. Mapping tests
assert every new field and null, full JSON equality after round trip, and old six-column set JSON
defaults. Synthetic tests cover identifiable/flat/wrong-sign exponential fits, gait merging/watch
clipping, missing and interrupted recovery, support thresholds, stillness, clocks, absent data and
ectopic count preservation. Real tests require unchanged sets, grouping, rep arcs/slopes/partial,
HR/activity, recovery shape, work/rest/floor, named cardio values and >=5 valid regular pulse windows.

Verification on 2026-11-03: **62 analytics + 95 app JVM tests (157 total), zero failures/errors/skips**.
The requested `:core:analytics:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes. App lint has no errors; pre-existing UI warnings remain.
The full-CSV replay also passes the same real-session assertions. To run it, set HEARTLINE_GYM_RAW
to the read-only input directory, then run `:core:analytics:cleanTest :core:analytics:test --tests
'*GymRichCaptureTest.richSession*'`. Unset the variable for self-contained fixture regression.

The restricted environment needs workspace-local Gradle/Android/JVM homes and a workspace copy
of the existing debug keystore. Maven sockets are denied, so a build-only init script supplies
Android Studio layoutlib's bundled org.json runtime to the JVM tests instead of downloading the
normal test-only `org.json:json:20250517` dependency. The init script and extracted jar are ignored
build artifacts; app runtime still uses Android org.json. Exact final invocation from android/:

```bash
export JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'
export GRADLE_USER_HOME='C:/Users/abhir/Desktop/projects/hl-g/android/build/gradle-home'
export ANDROID_USER_HOME='C:/Users/abhir/Desktop/projects/hl-g/android/build/android-user'
./gradlew --offline --no-daemon \
  -Duser.home=C:/Users/abhir/Desktop/projects/hl-g/android/build/user-home \
  -Pkotlin.compiler.execution.strategy=in-process -I build/gym-checks.init.gradle \
  :core:analytics:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Changed production files: WorkoutCapture, CaptureRhythm, new CaptureRecovery/CapturePulse,
Models (raw PPG clock), Ppg (optional capture mode), Rhythm (optional pooled candidate count),
CaptureMapping, CaptureJson, CaptureRunner, CaptureStorage, new CaptureActivity and HealthRepository.
Changed/added tests: CaptureMappingTest, new CaptureActivityTest/CaptureRichTest/GymRichCaptureTest;
plus app's test-only JSON dependency, this document, the fixture tool and two compact fixtures.
No commit, device interaction, protocol or UI change.

## Engine 4: heavy sets, split sets and walking rests (2026-11-04)

Tuned on the 3 Nov push day (19 logged sets, 18 inside a 64-minute watch-clock capture) and kept
against 2 Nov. Engine 3 found 13/18 on 3 Nov, missing every 50 kg and 40 kg bench set and the
20 kg machine press, over-counted bench and tricep sets by 2–3 reps, split the first chest-fly set
in two (−4 reps each half) and reported 3.5 minutes of walking between tricep sets as cardio.

What the data showed (scratch analysis, not in the repo):

- The missed sets reached a smoothed score of 0.50–0.68 but held it for only 6–10 s, short of the
  12 s run. Slow reps (4–5 s each) move the wrist about 0.1 g, the same as the shake under a heavy
  bar, and unracking and re-racking add ±1 g spikes that dominate the normalised ACF window.
- Lowering the threshold or the minimum run, loudness-normalising the signal (AGC or per-window
  clipping), a gyro score, and a heart-rate rise gate each found 2–4 more sets but added as many
  unlogged detections in rests on one day or the other, so none was adopted.
- A second, slower band (0.08–0.50 Hz, lags 2–8 s) found 2 more sets over both days with no new
  unlogged detection (2 Nov reverse forearm curl 10 x 9; 3 Nov bench 40 x 10).
- The ±10 s rep window compensated for runs ending inside the set; ±8 s with split runs joined
  (<= 15 s apart) gave the best counts.
- Its filter rings after a set; in perfectly still synthetic data the ringing is a clean, tiny
  sinusoid, so windows under 2 mg RMS now score zero (real rests are well above it).

| | 2 Nov | 3 Nov (watch clock) |
|---|---|---|
| Sets found, engine 3 | 21/26 in capture, 2 unlogged | 13/18, 0 unlogged |
| Sets found, engine 4 | 22/26, 2 unlogged | 14/18, 0 unlogged |
| Reps within 1 / 2, engine 3 | 15/21, 19/21 | 4/13, 11/13 |
| Reps within 1 / 2, engine 4 | 18/22, 18/22 | 11/14, 14/14 |
| Cardio reported | treadmill only | none (was 3.5 min of rest walking) |

Still missed: 50 kg bench x 7, x 8, x 4 and the 20 kg machine press x 6 on 3 Nov, and hanging leg
raises on 2 Nov. Finding them needs a cue other than rhythm (for barbell work, the unrack/re-rack
pair around a quiet stretch), which one session is too little to tune without false sets.

`tools/gym_prototype.py` holds the reference; `python tools/gym_compare.py --write` rebuilds the
2 Nov oracle after a deliberate change, then the Kotlin must match it exactly (it does: 24 sets, 0 s
boundary difference, identical reps). `GymCaptureTest.pushDayFindsSlowSetsAndCountsRepsAgainstTheLog`
runs the 3 Nov fixture (`gym_20261103.csv.gz`, accelerometer with watch clock, and its log).
