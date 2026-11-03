# Raw stream: fast PPG and sensor clocks

Probed on 2026-11-03 (IST), GTR 2e, wrist still, 20 s per mask. Commands on RAW_CTRL are
`01 mask 19`, `01 mask 00 00 00 19`, `02`; notifications arrive on RAW_DATA.

The checked-in fixture is `core/ble/src/test/resources/raw/probe-1103b.txt` (586 lines, 50 KB).
It contains the complete four runs from `probe/ble-1103b.log`, starting at 20:00:30.784,
with original payloads, timestamps and arrival order. Rates below are samples after the first
packet divided by the difference between the first and last packet's sensor stamps; this avoids
including the first packet's unknown preceding acquisition interval.

| Mask | Measured result on watch clock |
|---|---|
| 0x04 | nothing (earlier probe) |
| 0x80 | nothing (earlier probe) |
| 0x06 | nothing: 0x04 makes the watch ignore the request (earlier probe) |
| 0x02 | PPG **25.1002 Hz**, 511 samples, 83 data/stamp pairs |
| 0x82 | PPG **39.4849 Hz overall**, 794 samples, 84 pairs; rate changes during the run |
| 0x83 | PPG **50.1446 Hz**, 1,024 samples, 84 pairs; accel **26.2076 Hz**, 535 samples, 21 pairs |
| 0x81 | accel **26.1559 Hz**, 547 samples, 21 pairs |

0x82 is not a steady 39.5 Hz mode. Its first 7.840 watch seconds measure **25.1276 Hz**;
the final 11.564 seconds measure **50.1557 Hz**. At 20:01:00.843 (8.200 watch seconds
after its first stamp), a two-sample transition packet spans 360 ms. The next packets carry
6/18 samples rather than 3/9. Both data and timestamp counters are continuous, including
the timestamp counter wrap: there is no evidence of BLE packet loss. Three packets arrive
together around the transition, so arrival-only reconstruction would also obscure this change.
The reason for the watch's delayed mode change needs another device run.

## Packet interpretation

Byte 1 is a per-type, wrapping sequence counter. Types are 0x00 accel (i16 XYZ; 4100 = 1 g),
0x01 PPG (u16 LE), 0x04 gyro, 0x06 mag, 0x03 GPS NMEA and 0x05 microphone.

Type 0x07 is `07 | seq | sensor | u32 LE milliseconds | optional reserved bytes`.
The observed packets end with four zero bytes. `sensor` is the associated data type (0, 1, 4, 6
seen on the watch). **The timestamp arrives AFTER its data packet and marks that packet's LAST
sample.** Consecutive stamp differences correlate with the current packet's sample count (about
0.99), not the preceding packet's count. Timestamp and data counters have separate starting
values, with a stable offset per sensor. Parsing accepts the seven-byte meaningful prefix,
additional bytes, and unknown sensor IDs; a truncated prefix remains `Unknown`.

0x80 selects the same green PPG signal at about 50 Hz, with similar DC level (~27,000).
There is no second wavelength behind 0x04/0x80. True 50 Hz rather than interpolation remains
a device-level question. Accel is nominally 26.16 Hz. Gyro/mag have no claimed nominal rate:
their periods are measured from their own stamps, with 25 Hz as an unstamped fallback only.

## Recording and compatibility

`RawSampleClock` keeps one pending packet per sensor. On its stamp it writes sample `i` at
`watchEnd - (n-1-i)*period`. It estimates each sensor's period from up to 15 consecutive
stamp/count intervals, excludes outliers around the median and weights by sample count.
Three consistent changed intervals reset the estimate, allowing the 0x82 rate switch. The
initial rates are PPG 25/50 Hz and accel 26.16 Hz. Each authoritative end stamp also bounds
the packet period to prevent overlap with the preceding stamped packet.

A robust running affine fit maps watch seconds to phone epoch seconds using up to 120 s /
256 stamp-arrival pairs. It excludes delayed BLE bursts, bounds drift to +/-1000 ppm and
slews phase corrections at at most 1 ms/s. `ts` remains epoch seconds; trailing `wts` records
unwrapped watch seconds at full precision. Timestamps are monotonic **within each sensor**;
interleaved sensor batches are not globally sorted CSV rows.

Missing stamps fall back to the packet's original arrival time and measured/nominal period
when the next same-sensor packet arrives, after 1.5 s, or on stop. Those rows have empty `wts`.
Counter offsets reject late/duplicate stamps for an already emitted packet. Unsigned counters
and the 32-bit watch clock wrap naturally; skipped counters do not enter the rate estimator.
Stream restarts flush the pending tail and reset pairing/rate state. Reconnects get new CSVs
and a new epoch fit; capture merging retains their epoch gaps even if the watch rebooted.
A backward watch-clock reset without a new recording rejects stamps and uses arrival
fallback until a new recording; it does not reinterpret the reset as a 49-day forward jump.

The CSV reader prefers watch time, translates empty `wts` cells through nearby epoch/watch
anchors, and preserves sensor alignment. If a delayed fallback block overlaps later watch
stamps, only that block is interpolated between its neighboring anchors to preserve order.
It excludes the first 0.4 s of each PPG run and any
remaining low settling samples (<40% of the following second's median, within the first 2 s)
from analysis only. Every raw value, including the initial ~3,800-level samples, remains in CSV.
Files without `wts` use the unchanged legacy path, including retiming and warm-up behavior.

`FAST_PPG_ENABLED` in `RawPpgMode.kt` is the one default switch for captures, live raw view and
manual checks. After approximately 5 s with 0x80 and no PPG packet, the connection logs an
app warning and restarts once without 0x80. Microphone restarts retain the selected mode.
Explicit mask probes remain exact requests and never use this automatic fallback.

## Validation and device follow-up

Unit tests cover all four complete fixture runs, rate switching, last-sample assignment,
missing/late/duplicate stamps, wrapping counters/clocks, gaps/restart, stop-tail CSV output,
affine drift with burst jitter, old CSV loading, watch-clock warm-up, reconnect-file merging,
and clean synthetic 25/50 Hz beat timing. Existing real-session analytics regressions remain
unchanged and `CAPTURE_ENGINE_VERSION` remains 3.

Check fast PPG with gyro/mag (capture mask 0xd3), GPS and microphone on the real watch; only
PPG/accel combinations have been verified. Confirm the one-shot fallback on a rejecting mask,
long-run battery/throughput behavior, startup settling at 50 Hz, delayed mode changes, and gyro/
mag rates. Absolute epoch alignment still contains the minimum BLE delivery latency; watch
times remove jitter from relative sample/beat timing but cannot measure that fixed latency.

Final Windows verification, 2026-11-03: **all requested tasks passed**, 394 tests, no skips/failures.

| Task | Result |
|---|---|
| `:core:protocol:test` | 196 tests |
| `:core:ble:test` | 36 tests |
| `:core:analytics:test` | 66 tests, including unchanged real-session/golden regressions |
| `:app:testDebugUnitTest` | 96 tests |
| `:app:assembleDebug` | APK built |
| `:app:lintDebug` | 0 errors, 7 warnings, 1 hint |

The clean synthetic pulse test has maximum peak error 0.0056 ms at 50 Hz versus 0.0086 ms at
25 Hz (an ideal smooth signal with interpolation/parabolic refinement, not a device accuracy
claim); both meet the <10 ms target and 50 Hz improves average/max error.

Validation uses an ignored cache copy in `android/build/gradle-home`, `JAVA_HOME` pointing to
Android Studio's JBR, `ANDROID_USER_HOME` under that cache, in-process Kotlin compilation,
and `build/task-h/signing.gradle` pointing to a local copy of the existing debug keystore.
No tracked build/signing configuration changed. From `android/`, with those environment
variables set:

```powershell
./gradlew.bat --offline --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' `
  -I build/task-h/signing.gradle :core:protocol:test :core:ble:test :core:analytics:test `
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The final log is `android/build/task-h/validation.log`; APK is
`android/app/build/outputs/apk/debug/app-debug.apk`. Changes are limited to:

- Protocol: `Raw.kt`, `RawAudioTest.kt`.
- BLE: `RawRecording.kt`, `WatchConnection.kt`, new `RawSampleClock.kt`, `RawPpgMode.kt`,
  `RawSampleClockTest.kt`, and `src/test/resources/raw/{probe-1103b.txt,README.md}`.
- Analytics: `Models.kt`, `Ppg.kt`, `CaptureMotion.kt`, `CaptureRhythm.kt`, `CapturePulse.kt`,
  new `WatchClockTest.kt`, and `WORKOUT_CAPTURE.md`.
- App service: `CaptureStorage.kt`, new `CaptureWatchClockTest.kt`.
- This document. No UI files changed; no commit was made.
