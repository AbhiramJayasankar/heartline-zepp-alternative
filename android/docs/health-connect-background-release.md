# Task C implementation report

No commits, installations, phone operations or watch operations were performed. Task A/B blocks, UI sources, Controller.kt, DemoController.kt, AppSettings and the database schema are unchanged.

## Files

New production files:

- `app/src/main/java/dev/heartline/app/HealthConnectExporter.kt`: application-scoped exporter, availability/permissions, persistence and sync integration.
- `app/src/main/java/dev/heartline/app/HealthExportPolicy.kt`: bounded replay, fingerprint ledger, batching and successful-write checkpoints.
- `app/src/main/java/dev/heartline/app/HealthRecordMapping.kt`: Health Connect records, stable IDs, units, stages and exercise mapping.
- `app/src/main/java/dev/heartline/service/BackgroundState.kt`: system-state snapshot and Xiaomi/Redmi/POCO detection.
- `app/src/main/java/dev/heartline/service/SystemIntents.kt`: ordered settings intents and resolver fallbacks.
- `app/src/main/java/dev/heartline/service/HealthRationaleActivity.kt`: scrolling privacy explanation using the existing theme/components.
- `core/ble/src/main/java/dev/heartline/ble/QuietReconnects.kt`: monotonic reconnect logging policy.
- `core/protocol/src/main/kotlin/dev/heartline/protocol/RestingHeartRate.kt`: resting-HR decoder.

New JVM tests: `HealthExportPolicyTest.kt`, `HealthRecordMappingTest.kt`, `SystemIntentPolicyTest.kt`, `QuietReconnectsTest.kt`, `RestingHeartRateTest.kt` in the corresponding app/BLE/protocol test directories.

Changed production files:

- `app/build.gradle.kts`: the single permitted dependency, `androidx.health.connect:connect-client:1.1.0`.
- `app/src/main/AndroidManifest.xml`: WRITE health permissions, provider/OEM package queries, battery-exemption permission, rationale activity/alias, package-replaced broadcast.
- `app/src/main/java/dev/heartline/app/RealController.kt`: only Task C's block.
- `app/src/main/java/dev/heartline/service/WatchService.kt`: successful-sync export callback and package-replaced restart handling/documentation.
- `core/ble/src/main/java/dev/heartline/ble/WatchConnection.kt`: logging integration, successful-sync callback and resting-HR decode dispatch.

The ignored `local.properties` drive colon was escaped to fix the existing lint `PropertyEscape` error. No ProGuard rules were added or changed.

## Export design

Client **1.1.0** is the first stable Health Connect release, published **2025-10-08**, over a year before this task. No experimental or alpha APIs are used. [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/health-connect#1.1.0).

All export state lives in `db.meta`, under `health_connect_*`. Each type stores its watermark and a bounded SHA-256 fingerprint ledger together in one JSON value. First export covers 30 days. Automatic passes replay seven days behind the watermark to catch delayed watch samples; bucket/night queries include enough earlier context to avoid truncating an existing record. Data imported or backfilled beyond that overlap can be exported using the manual 30-day replay. Manual replay also restores data cleared in Health Connect.

IDs use `heartline:<type>:<source timestamp/bucket/date>`. HR and steps use fixed 15-minute bucket IDs; extending a bucket updates that record. HR includes activity-minute and live samples, retaining live millisecond timestamps and preferring live samples at identical timestamps. Adjacent equal sleep stages are merged. One sleep-session ID per local noon-to-noon night allows corrected start/end times to update the same record; gaps and unworn minutes remain unstaged. Resting HR exports the minimum valid watch resting-HR reading per local date; it is not inferred from live HR. Old lossless resting-HR fetches are also decoded for export.

HRV RMSSD and breathing rate come from stored heart checks, with milliseconds and breaths/minute preserved. Values outside the stable client's valid range are omitted. Workouts retain their known type, fall back to OTHER_WORKOUT for unknown types, and write separate distance/total-calorie records over the exercise interval. Unknown distances/calories stay absent; the watch's calorie field is not relabeled as active calories. Skin temperature is a stable 1.1.0 API, guarded by `FEATURE_SKIN_TEMPERATURE`; it uses wrist location, an absolute Celsius baseline and relative Celsius deltas. Its WRITE permission is requested only when the provider supports it.

Each run reserves a monotonically increasing `clientRecordVersion` in meta before inserting. Every record has a deterministic `clientRecordId`. There are at most 1,000 records per insert. A type's ledger/watermark advances only after all its batches succeed, so partial failure/process death can be retried without duplicates. Automatic passes omit unchanged fingerprints. Manual passes deliberately reinsert the whole window with the same IDs. `lastExport` advances only after a complete pass; `lastResult` reports actual submitted sample/record counts or a plain failure sentence.

The service invokes the application-scoped exporter after successful activity and health syncs, including manual and scheduled syncs. Export runs on IO outside the BLE command queue and does not require an open screen. SDK availability and granted WRITE permissions are refreshed on enable, export and `refreshBackground()`. No Health Connect READ permissions, background-read permission or Health Connect data queries are used. The rationale entry points follow [Android's Health Connect manifest guidance](https://developer.android.com/health-and-fitness/health-connect/get-started).

## Background and reconnect behavior

Background state reads the battery exemption, exact enabled NotificationForwarder component, manufacturer/brand, Bluetooth state and POST_NOTIFICATIONS grant. Settings helpers resolve preferred intents before returning them, catch resolver exceptions and fall back to app details/general settings. They return null if even general Settings is unavailable; callers should handle that case. MIUI pages depend on firmware and need device verification.

Reconnect timing and sync scheduling are unchanged. A disconnect warning is deferred 30 seconds. A recovery reaching ready within 30 seconds suppresses the drop/authentication pair and increments an in-memory counter, summarized at most once per hour. Longer outages still warn; connection failures still log immediately. Explicit disconnect clears a pending warning. Counters reset with the service/process lifetime.

BOOT_COMPLETED and MY_PACKAGE_REPLACED restart the service only with a stored usable key and BLUETOOTH_CONNECT permission. Both broadcasts are explicit exemptions from Android 12+ background FGS-start restrictions. The service remains `connectedDevice`, which is allowed by Android 15's boot restrictions; the granted Bluetooth permission meets Android 14+ connected-device prerequisites and is not a while-in-use sensor permission. Sources: [background-start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android 15 boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed), [service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device). OEM rejection is caught and logged to logcat.

## Byte formats and Gadgetbridge source

The only newly decoded wire format is resting HR. `FetchHeartRateRestingOperation.handleActivityData` reads repeated **six-byte** records:

| Offset | Size | Meaning |
| --- | --- | --- |
| 0 | 4 | Unsigned little-endian Unix timestamp, seconds |
| 4 | 1 | Signed UTC offset in quarter-hours; multiply by 900 for seconds |
| 5 | 1 | Unsigned heart rate, BPM |

Payload length must be divisible by six. Invalid 0/255 BPM sentinels are omitted. Tests derive bytes directly from Gadgetbridge's `ByteBuffer.order(LITTLE_ENDIAN)`, `getInt()`, signed `get()` and unsigned `get() & 0xff`, including negative offsets and unsigned timestamps. [Gadgetbridge source](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchHeartRateRestingOperation.java).

Skin temperature reuses existing database values and does not introduce a byte decoder. Its wrist/skin/Celsius interpretation is confirmed by `FetchTemperatureOperation.handleActivityData`: `temperature / 100f`, `LOCATION_WRIST`, `TYPE_SKIN`. [Gadgetbridge source](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchTemperatureOperation.java).

Other exports consume existing decoded database models. No new watch commands, notification payloads or chunk framing were introduced. Activity fetch still never sends the destructive 0x03 acknowledgement; MTU-23 notification writes/GTS extra header and GattClient serialization are untouched. GTR 2e's support class inherits GTR 2 support; no Zepp OS formats were used.

## Validation

All required tasks plus release packaging pass:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon :core:protocol:test :core:ble:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

The restricted Windows environment additionally used the shared `GRADLE_USER_HOME=C:/Users/abhir/.gradle`, `-Duser.home=C:/Users/abhir/.gradle/heartline-build-home`, `-Pkotlin.compiler.execution.strategy=in-process`, and a local init script pointing the existing debug signing configuration at `C:/Users/abhir/.android/debug.keystore`. The release build retains the repository's debug signing, R8 minification and resource shrinking. No production signing change was needed.

| Suite | Passed | New tests |
| --- | ---: | ---: |
| Protocol | 85 | 3 |
| BLE | 29 | 6 |
| App | 38 | 23 |
| Total | 152 | 32 |

Zero failures, errors or skipped tests. Tests cover byte layout, batch boundaries, unchanged-record de-duplication, failed-batch checkpoint safety/retry, overlap and clock rollback, sleep stages/IDs, sample timestamps, units/ranges, workout types, DST offsets, OEM selection and reconnect thresholds/summaries.

Final build: **BUILD SUCCESSFUL**, all seven requested tasks completed. The final XML lint report contains **0 errors, 7 warnings, 1 hint**. One warning is introduced by the explicitly requested direct battery-exemption intent (`BatteryLife`); the remaining warnings/hint concern existing onboarding/API, target SDK, dependency, backup and icon settings. No lint suppressions or baselines were added. R8 completed without missing-class or other R8 warnings. No extra keep or dontwarn rules were needed; the SDK's consumer rules are sufficient for compilation/minification. Gradle retains its existing deprecated-feature notice.

| APK | Bytes | MiB |
| --- | ---: | ---: |
| `app/build/outputs/apk/debug/app-debug.apk` | 39,221,361 | 37.40 |
| `app/build/outputs/apk/release/app-release.apk` | 3,419,143 | 3.26 |

Release is **91.28% smaller**. Both APKs pass `apksigner verify`; both use the same existing Android Debug certificate (SHA-256 `c4d2ea8534d3d6c29c0d014f76489b922bfaa29d869aa9e5391ae27991cf8f62`). Release startup/export behavior still requires the owner test below; a successful shrink/build is not device runtime verification.

The ignored `build/task-c-validation/` directory retains the final Gradle log (`final.log`), local build/signing helper scripts, and the stable client's downloaded source archive used to inspect API support. The helpers do not modify production signing configuration.

## Owner verification

1. Grant the displayed WRITE permissions on Android 13 and Android 14+; verify both privacy entry points and NEEDS_INSTALL/NEEDS_PERMISSION/READY transitions. If the provider lacks skin-temperature support, that permission should be absent.
2. Enable export, sync, then manually export twice. Health Connect should contain one HR record per 15-minute bucket, one step total per bucket and one sleep session per local noon-to-noon night, without duplicate IDs. Check live HR's finer timestamps and total step counts. Manual export intentionally reports upserts again.
3. Check a night spanning midnight, a night with a nap, and a night extended by a later sync. The session ID should stay stable; compare the noon-to-noon grouping and unstaged gaps with the watch's sleep view.
4. Verify firmware V1.7.3.1 supplies the documented six-byte resting-HR records and daily values/UTC dates agree with the watch. Unsupported/empty payloads remain in raw fetch storage and must not become invented resting-HR values.
5. Compare one known heart check's RMSSD and respiratory rate, and one workout's duration/meters/calories against their exported records. Check skin temperature's absolute baseline plus deltas when supported.
6. With the UI closed and export enabled, allow scheduled activity/health sync and confirm lastExport advances. For backfills/imports older than the seven-day automatic overlap, use manual export.
7. Observe several idle drops under 30 seconds: no routine drop/auth pair, at most one hourly count. Leave the watch out of range over 30 seconds: the warning and failed attempts must remain visible. Reconnect should restore normal sync scheduling.
8. On MIUI/HyperOS, verify autostart and per-app battery pages resolve correctly and their fallback works. Resume after changing battery exemption, listener access, Bluetooth and notification permission; confirm the background snapshot changes.
9. After owner-approved installation, test a real reboot and app replacement with a key present; confirm the connectedDevice FGS notification/reconnect resumes. With no key or Bluetooth permission it must not start from those broadcasts. Verify the release APK launches and completes permission/export flow after R8.
