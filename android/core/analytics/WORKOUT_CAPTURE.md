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

Capture motion retimes each contiguous stream separately to remove overlapping packet arrival
stamps. A gap over one second breaks retiming, so it cannot be stretched into artificial samples.
Vectors are linearly resampled at 25 Hz only across intervals at most 500 ms apart. Missing data
remains missing. Reconnect CSVs are shifted by their epoch offsets, including the leading interval
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

Lifting looks for 0.15–1.2 Hz periodic acceleration on the strongest axis over sixteen-second
windows. It requires whole-signal autocorrelation >0.65, spectral concentration >0.65, axis RMS
>0.045 g and gyro corroboration when present. Gyro axes **and magnitude** must show periodicity
at the same or twice the rep frequency, with correlation >0.55. Missing gyro permits a lower
confidence accelerometer-only result with an explicit note. Bouts must last 8–120 seconds.
The dominant accelerometer axis is band-passed at 0.15–1.2 Hz; prominence/distance-limited local
maxima give reps (at least three). Tempo is mean peak-to-peak time. Set HR uses its covered window.
Only classified rest intervals between consecutive sets become rests; walking between sets is
not a rest. Recovery is start HR minus HR 60 seconds later, null if the rest is shorter than
60 seconds or either observation is missing. Negative recovery is retained.

This is a heuristic, not an exercise recognizer. Window edges are quantized to five seconds.
Wrist position, holding treadmill rails, static lifts, asymmetric reps, alternating arms, rotations,
transitions and the first/last partial rep can change classification and counts. Strict periodicity
helps reject incidental movement but can miss irregular sets. Sets longer than two minutes are
left `other` rather than split into fabricated sets. Magnetometer values are retained for later
analysis but not used to infer exercise type without calibration. There is no GPS or distance estimate.

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

`WorkoutCaptureTest` covers known rep rates (0.2/0.333/0.5/1 Hz), two sets with recovery, 108-spm
walking, 168-spm running, exact HR zones/Edwards load, both Keytel equations, HR/motion gaps,
CSV vector retention, isolated impacts and overlong bouts. `CaptureTimelineTest` covers planned
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
