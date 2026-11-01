# Heartline BLE and real controller

`dev.heartline.ble` ports the connection behavior of the read-only Python `server/watch.py`.
The app uses the existing protocol, analytics and data APIs. No UI screen was edited.

## Files

BLE production (`src/main/java/dev/heartline/ble/`):

- `GattClient.kt`: Android callbacks, characteristic lookup, API 31–32 fallbacks and API 33 writes.
- `OperationQueue.kt`: coroutine serialization and in-flight cancellation/timeout invalidation.
- `WatchConnection.kt`: connection, authentication, reconnect, HR, raw streams, fetch loops and controls.
- `WatchState.kt`: state models, persistence boundary, sync overlap and backoff rules.
- `HistoryTransfer.kt`: bounded transfer state machine using protocol `FetchAssembler`.
- `RawRecording.kt`: Python-compatible sensor CSV, NMEA and microphone recording lifecycle.
- `MicDecoder.kt`, `AudioHeaders.kt`: platform Opus decoding, PCM, WAV finalization and dBFS.

BLE tests: `AudioHeadersTest.kt`, `OperationQueueTest.kt`, `SyncPolicyTest.kt`,
`HistoryTransferTest.kt`, instrumented `MicDecoderTest.kt`. Its `mic_2400hz.json` asset is an
unchanged copy of the protocol test fixture.

App production additions (`app/src/main/java/dev/heartline/`):

- `app/RealController.kt`: implements every `HeartlineController` member, binds the service,
  observes repositories and analytics, runs checks, imports/exports, and serves recording audio.
- `app/Mapping.kt`: activity, daily/trend/session mappings and notification filtering helpers.
- `app/SensorBuffers.kt`: bounded sensor histories and the Python web compass calculation.
- `app/StoredJson.kt`: explicit Python-compatible session/workout JSON mappings.
- `app/WatchStore.kt`: protocol-to-data model adapter and `EventRepository` logging.
- `service/WatchService.kt`: service, local binder, scheduling, ongoing notification, boot receiver.
- `service/NotificationForwarder.kt`: notification listener with allowlist, quiet hours and de-duplication.
- `service/CallForwarder.kt`: optional `TelephonyCallback`, call alerts and end-call handling.

Other app changes: `HeartlineApp.kt`, `MainActivity.kt`, manifest, `res/xml/recording_paths.xml`,
`app/build.gradle.kts`, plus JVM `app/src/test/java/dev/heartline/app/MappingTest.kt`.
`core/ble/build.gradle.kts` adds the instrumentation runner/test dependencies already used by data.
There are no new production dependencies. No commits were made.

## Public API

All suspend watch commands must run through the owning service's `command` method. The service
uses one `Dispatchers.IO.limitedParallelism(1)` dispatcher; Bluetooth callbacks only complete
pending operations or publish copied notification bytes. Commands may suspend concurrently;
the ATT queue and command-sequence mutex control wire access.

```kotlin
data class GattNotification(val uuid: UUID, val value: ByteArray)
class GattClient(context: Context) : AutoCloseable {
    val notifications: SharedFlow<GattNotification>
    val disconnected: CompletableDeferred<Throwable?>
    val mtu: Int
    suspend fun connect(device: BluetoothDevice) // requests MTU 247 immediately
    suspend fun discoverServices()
    suspend fun requestMtu(value: Int): Int
    suspend fun connectionPriority(priority: Int = BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    suspend fun read(uuid: UUID): ByteArray
    suspend fun write(uuid: UUID, value: ByteArray, response: Boolean = false)
    suspend fun enableNotification(uuid: UUID, enabled: Boolean = true)
    override fun close()
}
class OperationQueue(invalidate: () -> Unit) {
    suspend fun <T> run(timeoutMs: Long, operation: suspend () -> T): T
}
class WatchConnection(
    context: Context, scope: CoroutineScope, persistence: WatchPersistence,
    keys: KeyStore = KeyStore(context),
) {
    val connection: StateFlow<ConnectionStatus>
    val ready: StateFlow<Boolean>
    val battery: StateFlow<BatteryInfo?>
    val deviceInfo: StateFlow<Map<String, String>>
    val heartRate: StateFlow<HrStatus>
    val steps: StateFlow<StepsNow?>
    val sync: StateFlow<SyncStatus>
    val raw: StateFlow<RawStatus>
    val rawUpdates: SharedFlow<RawUpdate>
    val gps: StateFlow<GpsState?>
    fun connect(mac: String)
    suspend fun disconnect()
    suspend fun readBattery()
    suspend fun setLiveHr(on: Boolean)
    suspend fun startRaw(sensors: Set<RawSensor>): File
    suspend fun stopRaw(): File?
    suspend fun vibrate()
    suspend fun notify(title: String, body: String,
        kind: NotificationKind = NotificationKind.MESSAGE, app: String = "Heartline")
    suspend fun endCall()
    suspend fun syncTime()
    suspend fun applySetting(key: String, value: Any)
    suspend fun syncActivity()
    suspend fun syncHealth()
}
class MicDecoder(file: File, onPcm: (ShortArray) -> Unit = {},
    onLevel: (Float) -> Unit = {}) : AutoCloseable {
    fun feed(frame: ByteArray)
    override fun close()
}
object AudioHeaders {
    const val SAMPLE_RATE = 48_000
    fun opusHead(preSkip: Int = 0): ByteArray
    fun nanos(value: Long): ByteArray
    fun wav(pcmBytes: Long): ByteArray
    fun dbfs(pcm: ShortArray): Float
}
class RealController(context: Context, scope: CoroutineScope,
    db: HeartlineDatabase, settings: AppSettings, keys: KeyStore) : HeartlineController
class WatchService : Service {
    val watch: WatchConnection
    suspend fun <T> command(block: suspend (WatchConnection) -> T): T
}
```

The `WatchPersistence` interface and state-model constructor signatures are in `WatchState.kt`.
All UI-facing signatures remain the existing `app/Controller.kt` contract. Mapping helpers
are explicit top-level functions in `Mapping.kt`; no reflective serialization is used.

## Connection and storage behavior

One ATT operation is in flight at a time, including writes without response (Android still
provides completion callbacks). Operations have deadlines, and cancellation/timeout closes
the GATT object so stale callbacks cannot satisfy subsequent operations. Queued cancellation
does not invalidate another operation. Returned GATT failures leave the queue usable.
Notification overload invalidates the link instead of silently corrupting fetch data.
The modern read/change callbacks copy the supplied bytes; older callbacks copy characteristic values.
See the [Android GATT contract](https://developer.android.com/reference/android/bluetooth/BluetoothGatt).

Connection uses `getRemoteDevice(mac)`, then a 15-second address-filtered scan on failure.
It negotiates MTU 247 before discovery, authenticates, reads battery/info and installs live
steps/events before restoring requested streams and releasing the scheduling-ready signal.
Unwanted disconnects reconnect while wanted, backing off 3/6/12/24/48/60 seconds. Desired
HR/raw selections survive link loss; a new raw file is created after each reconnection.
Explicit disconnect sends stream stops before closing the link. HR pings run every 10 seconds.
Raw startup retains the three commands and 400 ms pauses; the mic watchdog sends stop,
waits 300 ms, then repeats startup after four seconds without microphone packets.

The fetch mutex serializes activity/health syncs while allowing HR pings between transfer
commands. Activity starts seven days back or overlaps the last stored minute by 240 seconds,
advances by 60 seconds, and stops at now/no progress/20 rounds. Health uses the last sample
plus 60 seconds (seven-day default), up to five rounds per scalar type and 20 workout rounds.
Consumed-on-transfer types run once. Per-type and aggregate last-sync metadata are persisted;
raw health bytes are committed before parsing. Unsupported formats remain lossless raw blobs.
**No fetch path sends the destructive 0x03 acknowledgement.**

The service schedules activity every 15 minutes, health every 60, and battery every 30 while
ready, with an initial activity/health sync. Explicit syncs share the same fetch mutex. It
returns START_STICKY, and BOOT_COMPLETED starts it when a usable encrypted key and Bluetooth
permission exist. Notification chunks always use protocol `chunkedWrites(..., mtu = 23)`
and the mandatory GTS header. Calls end on answer/hang-up or after 15 seconds. Settings are
stored only after the required write/config acknowledgement succeeds.

CSV files live at `files/raw/yyyyMMdd-HHmmss.csv` with the Python header, lowercase kinds,
accelerometer g values, and reconstructed 25 Hz timestamps. GPS and audio share the stem
as `.nmea`/`.wav`. The platform Opus decoder uses identification, pre-skip and seek-pre-roll
CSD buffers as specified by [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
WAV sizes are patched even if decoder finalization fails. Recordings are indexed on close.

App buffers retain 200 samples per 25 Hz sensor and 400 microphone levels. Compass extrema
cover the full recording; tilt compensation/hard-iron centering match `web/sensors.js`.
`estimateBpm` remains the UI component's existing implementation. Check CSV loading uses
analytics `readRecordingCsv`, preserving burst retiming. Complete analysis JSON, the check,
and the qualified insight-cache entry are stored after analysis.

READ_PHONE_STATE is requested through MainActivity only from `setForwarding` when calls are
enabled on the forwarding screen. It is not requested on startup. Allowed packages, quiet
hours (stored in minutes), own/ongoing/summary exclusion and a monotonic 10-second duplicate
window apply to forwarded notifications. A FileProvider exposes only `files/raw` recordings.

Debug APK extras: `--ez demo true` selects DemoController; `--ez verify_idle true` starts
the real controller/service without initiating Bluetooth. Release APKs ignore both extras.

## Verification

From `android/`:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon :core:ble:test :app:testDebugUnitTest :app:assembleDebug
```

Restricted Windows sessions may additionally need a writable GRADLE_USER_HOME,
`-Pkotlin.compiler.execution.strategy=in-process`, a writable `-Duser.home`, and
`-PheartlineTestKeystore=C:/Users/abhir/.android/debug.keystore`. The keystore option is
optional and changes only the existing debug signing configuration. The local, ignored
SDK properties file needs an escaped drive colon for lint.

Install each APK with the supplied MIUI timed-install/tap procedure, then run:

```bash
adb -s 3a3c6381 shell am instrument -w dev.heartline.ble.test/androidx.test.runner.AndroidJUnitRunner
adb -s 3a3c6381 shell am start -W -n dev.heartline/.MainActivity --ez verify_idle true
```

Validated on 2026-11-01: BLE JVM **23 tests passed**; app mappings **15 tests passed**.
`:core:ble:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:core:ble:lintDebug` and
`:app:lintDebug` passed. Lint retains the existing dependency/version/UI/manifest notices;
there are no lint errors.
The phone microphone test passed: **55 frames, 52,800 PCM samples, 2400.87890625 Hz**,
with all WAV sizes and one dBFS value per decoded frame checked. The APK was installed
on serial `3a3c6381`; the real app launched without a crash and dumpsys confirmed the
idle WatchService was bound and foreground (`isForeground=true`, quiet notification).

No watch connection was attempted, as requested. Live watch authentication, reconnect,
watch controls, history sync, notification forwarding and call forwarding have not been
validated end to end on the real watch in this worktree. Their wire bytes/parsers come from
the already verified protocol module. The unit transfer tests additionally cover captured
start headers, wraparound, refusal, empty transfers, corrupt completion, counter gaps,
cancellation, and absence of acknowledgement. Nothing in the brief is intentionally omitted;
undecoded watch formats remain undecoded, matching the Python reference.
