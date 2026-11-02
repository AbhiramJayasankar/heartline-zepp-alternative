# Task E implementation report

Workout capture is implemented through the fixed controller contract, with collection owned by
the foreground service. It records PPG, acceleration, gyro and compass plus the watch's live HR,
survives UI closure, resumes after link loss, stops manually or on plan, and caches a post-session
report. No UI files, controller contract, demo implementation, AppSettings, manifest, Gradle build
files or database schema were changed. No dependencies were added. No commit, installation,
phone operation or watch operation was performed.

## Files

Paths below are relative to `android/`.

Created:

- `core/analytics/src/main/kotlin/dev/heartline/analytics/WorkoutCapture.kt`: results, HR metrics,
  zones, Edwards load, Keytel calories, set/rest HR and still-period PPG cross-check.
- `core/analytics/src/main/kotlin/dev/heartline/analytics/CaptureMotion.kt`: gap-preserving motion
  processing, segmentation, cadence and repetition detection.
- `app/src/main/java/dev/heartline/service/CaptureRunner.kt`: service-owned capture lifecycle,
  stream restoration, planned/manual stop, background analysis, detail retry and deletion.
- `app/src/main/java/dev/heartline/service/CaptureTimeline.kt`: pure planned-stop and gap logic.
- `app/src/main/java/dev/heartline/service/CaptureStorage.kt`: session JSON, safe recording paths
  and reconnect CSV merging with epoch offsets.
- `app/src/main/java/dev/heartline/app/CaptureController.kt`: controller/service binding and history.
- `app/src/main/java/dev/heartline/app/CaptureMapping.kt`: pure analytics-to-contract mappings.
- `app/src/main/java/dev/heartline/app/CaptureJson.kt`: explicit cached result serialization.
- `core/analytics/src/test/kotlin/dev/heartline/analytics/WorkoutCaptureTest.kt`.
- `app/src/test/java/dev/heartline/app/CaptureMappingTest.kt`.
- `app/src/test/java/dev/heartline/service/CaptureTimelineTest.kt`.
- `app/src/test/java/dev/heartline/service/CaptureWireTest.kt`.
- `core/analytics/src/test/resources/recordings/capture_gym_trimmed.csv`: first 150 seconds of
  the supplied recording, retaining all four sensor types (15,054 unchanged data rows).
- `core/analytics/WORKOUT_CAPTURE.md`: methods, formulas, byte provenance and limitations.
- `CAPTURE_REPORT.md`: this report.

Changed:

- `core/analytics/.../Models.kt`: optional gyro/mag vectors, arrival clock and explicit capture span.
- `core/analytics/.../Ppg.kt`: CSV gyro/mag retention; preserves existing check/golden behavior.
- `app/.../RealController.kt`: replaces only Task E and adds minimal raw/check/live-HR guards.
- `app/.../service/WatchService.kt`: three localized lines to own and construct the runner.
- `core/ble/.../WatchConnection.kt`: offline stop clears wanted raw/HR flags; closes local raw file.
- `core/data/.../MetaRepository.kt` and `core/data/API.md`: a small delete API for real meta-row removal.

## Design and bytes

Live `HR_MEASURE` is the sole report HR source; moving PPG never fills gaps. Five-second HR and
motion bins retain missing coverage. Max HR uses profile, then `220-age`, then 190. Sets require
periodic acceleration and gyro corroboration; step estimates require sustained gait. HR recovery
requires a full 60-second rest. Compass data is retained without inventing exercise classifications.

Sessions use `capture_session_<id>`; result JSON uses `capture_result_<id>`. HR stays in the existing
`hr_samples` table. Multiple CSV parts are merged by epoch, without closing reconnect gaps. Capture
continues independently of the controller's coroutine scope. Stop restores prior live-HR mode.
Deletion removes the session, cached result, owned CSV files and recording-index rows.

Reused wire commands:

| Purpose | Bytes | Gadgetbridge provenance |
| --- | --- | --- |
| Start four sensors | `01 53 19`; `01 53 00 00 00 19`; `02` | `HuamiSupport.setRawSensor` and `Huami2021Service.CMD_RAW_SENSOR_START_1/2/3`. Existing GTR 2e mask extension: `03 | 10 | 40 = 53`. |
| Stop raw | `03` on RAW_CTRL | `HuamiSupport.setRawSensor`, `Huami2021Service.CMD_RAW_SENSOR_STOP` |
| Start live HR | Enable `2a37`; write `15 02 00`, `15 01 01` to `2a39` | `HuamiSupport.onEnableRealtimeHeartRateMeasurement` and manual/continuous constants |
| Stop live HR | `15 01 00` | Same method/constants |
| HR keepalive | `16` every ten seconds | Existing Heartline watch-verified behavior |

The original GATT queue, 400 ms raw-start pauses, notification MTU/header quirks and absence of
the destructive activity-fetch `03` acknowledgment remain intact. No new byte encoder or decoder
was introduced. The existing decoder's PPG `01`, gyro `04` and mag `06` formats are watch-verified
Heartline/Python formats; current upstream Gadgetbridge does not decode them. Full format/source
details and links are in [WORKOUT_CAPTURE.md](core/analytics/WORKOUT_CAPTURE.md#wire-bytes-and-provenance).
Upstream source was read because the supplied Gadgetbridge checkout path did not exist.

## Validation

The final verification runs the requested protocol/BLE/app tasks plus the analytics suite:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="C:/Users/abhir/.gradle"
./gradlew --no-daemon :core:analytics:test :core:protocol:test :core:ble:test \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

This restricted Windows environment additionally needs a writable `-Duser.home`,
`-Pkotlin.compiler.execution.strategy=in-process`, and a local init script that selects a copy of
the existing debug keystore (the default debug-keystore lock is denied here). These are build-only
overrides under ignored `android/build/capture-validation/`; signing/build configuration in source
is untouched. The exact local invocation is `bash build/capture-validation/verify.sh` from `android/`.

| Suite | Passing tests |
| --- | ---: |
| Analytics | 44 (15 new capture tests; all existing goldens preserved) |
| Protocol | 130 |
| BLE | 29 |
| App | 75 (9 new mapping/timeline/wire tests) |
| Total | 278; no failures or skips |

`assembleDebug` and `lintDebug` pass. Lint reports zero errors; existing notices remain.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Synthetic tests cover 0.2/0.333/0.5/1 Hz reps, tempo, two sets and 60-second recovery, 108-spm
walking, 168-spm running, short movements, isolated impacts, long uncertain bouts, HR zones,
Edwards load, both Keytel formulas, data gaps, planned stop and all result-to-UI fields.

The checked-in trim yields 30 seconds of rest in the first 40 seconds, walking from 55–110 seconds
at approximately 101 spm, and no lifting sets. An additional run against the entire original
366-second CSV also found the main roughly one-minute walk at approximately 100 spm and no
lifting sets. HR-derived metrics remain null for that CSV because it contains no live HR rows.

## Owner review on the watch

- Start capture, close the UI and reopen it: elapsed time and sample count should continue.
- During a watch workout, verify full-rate PPG/accel/gyro/mag and approximately one HR notification
  per second; inspect reported HR gaps instead of assuming raw PPG can replace missing live HR.
- Drop/reconnect the link mid-session: expect one capture, a second CSV, and a gap at the correct time.
- Stop manually and by planned deadline while disconnected, then reconnect: no new capture CSV
  should appear. Prior live-HR-on should be restored; prior live-HR-off should remain off.
- Perform two manually counted sets at known tempo with >=60 seconds of rest. Compare boundaries,
  reps, tempo and the first-minute HR drop. Repeat with an asymmetric lift or a static hold to see
  where the documented wrist-motion heuristic misses reps.
- Walk and run for one minute each, then repeat holding treadmill rails. Compare cadence/counts
  against a manual count; stationary/rail-held wrists can miss gait. Confirm ordinary arm movements
  do not become lifting sets. Gait shorter than 20 seconds is deliberately left uncertain.
- Delete a completed capture and confirm its list entry/result/files disappear without changing
  unrelated raw recordings or health history.

The largest uncertainty is exercise recognition from one wrist: labels and rep counts are estimates,
with five-second boundary resolution, not decoded watch workout markers. Keytel calories are an
HR-based estimate and are particularly uncertain for resistance exercise and elevated recovery HR.
