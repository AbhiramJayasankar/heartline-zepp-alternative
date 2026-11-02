# Workout capture

`analyzeCapture(Recording, epoch-second HR samples, Profile, label)` is a pure JVM analysis.
`CaptureRunner` in the app's service owns collection, persistence and analysis. No watch workout
start/stop event is assumed, and capture does not start a workout on the watch.

## Clocks and recording

`Recording` adds gyro/mag timestamps and three-axis values with default empty arrays. The existing
PPG and accelerometer check clock, `durationS`, `makeRecording` and golden outputs are retained.
The CSV reader also retains accelerometer arrival timestamps for capture analysis. Gyro/mag rows
use the CSV's `ax,ay,az` columns, in the existing raw integer units; acceleration is already in g.
Motion-only CSVs can be analyzed without PPG.

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

At most one >=30-second rest interval (up to 60 seconds) is passed to the existing beat/SQI engine.
Its result is reported as an independent PPG cross-check only at SQI >=0.7 with simultaneous watch HR.
It never substitutes for, smooths, or corrects the watch's HR. Motion/noisy workout PPG cannot
contribute beats to the capture HR report.

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
3. Five-second mean with SciPy's reflect edges, strictly >0.45. Exclude existing walking/running
   seconds. Merge short gaps with the prototype's six-sample lookahead and keep runs >=12 s.
   The actual Python slice bridges **five off-seconds**, despite the docstring's “gaps <=6”.
   Excluded gait seconds and coverage gaps are hard barriers, including during bridging.
4. For each set, band-pass acceleration at 0.12–1.00 Hz. Center XYZ by the core mean and find
   its first principal direction (3x3 covariance Jacobi eigendecomposition, equivalent to the
   prototype's first right singular vector). Project the core and a window extended by 10 s
   on each side, clipped to the same coverage span.
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
Negative recovery is retained. Stillness **segments**, rather than these recovery intervals,
continue to gate the existing independent PPG cross-check.

This remains a wrist-rhythm heuristic, not an exercise recognizer. Hanging leg raises and reverse
wrist curls are known blind spots when the wrist barely rotates. Irregular/fast reps (periods
under 1.2 s can alias), static lifts, alternating arms, partial reps, long periodic non-exercise
movement and incidental rhythmic activity can change classification/counts. Gait exclusion can
trim a strength core. Magnetometer/gyro recordings are retained but do not gate lifting.
There is no GPS or distance estimate.

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

Owner hardware review should check: all four raw rates during a watch workout; stable ~1 Hz watch
HR; capture continuity with the UI closed; reconnect producing a new CSV and an accurate gap;
manual/planned stop while disconnected followed by reconnect producing no new capture file;
restoration of pre-existing live-HR mode; two counted sets at known tempo separated by >=60 s of
rest; a one-minute treadmill walk and run (including holding rails); and no false lifting sets during
ordinary wrist movement. Compare set boundaries/counts and HR recovery against a manual log.
The watch's workout flag is unavailable, so movement labels cannot confirm which workout was
selected on the watch. No phone or watch interaction is part of development validation.

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
