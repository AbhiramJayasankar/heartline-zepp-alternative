# Heartline GTR 2e protocol

Pure Kotlin/JVM port of `server/protocol.py`, the byte protocol in `server/health.py`, `server/gps.py`,
and Opus framing from `server/audio.py`. Package: `dev.heartline.protocol`. No Android, I/O, coroutines,
or new dependencies. Epoch values are **seconds**; `RawPacket.Timestamp.millis` is reserved for milliseconds.

## Files

Production, under `src/main/kotlin/dev/heartline/protocol/`:

| File | Responsibility |
| --- | --- |
| `Device.kt` | UUIDs, authentication, battery, HR, device info, find-watch commands |
| `Time.kt` | Watch timestamps and clock synchronization |
| `Raw.kt` | Raw sensor commands and packets |
| `Gps.kt` | Incremental NMEA state and satellites |
| `Audio.kt` | Incremental Opus frame splitting and resynchronization |
| `Fetch.kt` | Fetch types, commands, headers and packet assembly |
| `Activity.kt` | Activity minutes and state mapping |
| `Health.kt` | Scalar health parsers and opaque health blobs |
| `Workouts.kt` | Workout summaries and HR tracks |
| `Notifications.kt` | Notification/call payloads and chunking |
| `Settings.kt` | Settings definitions, validation and write commands |
| `Live.kt` | Realtime steps and device events |
| `Bytes.kt` | Internal endian, hex and Python-compatible rounding helpers |

Tests, under `src/test/kotlin/dev/heartline/protocol/`: `DeviceTest.kt`, `RawAudioTest.kt`,
`FetchActivityTest.kt`, `GpsTest.kt`, `HealthTest.kt`, `WorkoutsTest.kt`, `ControlsTest.kt`, and
test-only JSON/resource helpers in `TestFixtures.kt`.

Resources: unchanged `health_fetch.json` and `mic_2400hz.json`, copied from Python; generated
`python_reference.json` contains complete expected health/workout fields, settings and notification
bytes, GPS states, 512 raw vectors, and 150 framing vectors. No JSON library is needed.

Tools: `reference_vectors.py` regenerates the oracle, `scaffold.init.gradle.kts` handles the absent
sibling project during validation, and `test.sh` runs tests in this restricted Windows environment.

## Public API

All properties below are `val` to callers. Command ByteArrays are fresh copies on each access.

```kotlin
object Uuids // java.util.UUID properties:
// AUTH, BATTERY, RAW_CTRL, RAW_DATA, ACT_CTRL, ACT_DATA, CONFIG, REALTIME_STEPS,
// DEVICE_EVENT, CHUNKED, HR_MEASURE, HR_CONTROL, ALERT_LEVEL, CURRENT_TIME,
// SERIAL, HARDWARE, SOFTWARE, SYSTEM_ID, PNP_ID
val DEVICE_INFO: Map<String, UUID>
object Auth {
    val REQUEST_RANDOM: ByteArray
    fun response(key: ByteArray, randomReply: ByteArray): ByteArray
    fun isSuccess(reply: ByteArray): Boolean
}
data class BatteryInfo(val level: Int, val charging: Boolean, val lastChargeA: String?, val lastChargeB: String?, val raw: String)
fun parseBattery(data: ByteArray): BatteryInfo
fun parseHeartRate(data: ByteArray): Int
object HrCommands // STOP_MANUAL, START_CONTINUOUS, STOP_CONTINUOUS, PING: ByteArray
fun decodeInfo(data: ByteArray): String
val ALERT_FIND: ByteArray
val ALERT_OFF: ByteArray

enum class RawSensor(val mask: Int) // ACCEL, PPG, GPS, GYRO, MIC, MAG
fun rawMask(sensors: Set<RawSensor>): Int
fun rawStartCommands(mask: Int = 3): List<ByteArray>
val RAW_STOP: ByteArray
const val ACCEL_ONE_G: Int = 4100
const val RAW_RATE_HZ: Double = 25.0
data class Vec3f(val x: Float, val y: Float, val z: Float)
data class Vec3i(val x: Int, val y: Int, val z: Int)
sealed interface RawPacket {
    data class Accel(val samples: List<Vec3f>) : RawPacket
    data class Ppg(val values: IntArray) : RawPacket
    data class Gyro(val samples: List<Vec3i>) : RawPacket
    data class Mag(val samples: List<Vec3i>) : RawPacket
    data class GpsText(val text: String) : RawPacket
    data class Mic(val counter: Int, val payload: ByteArray) : RawPacket
    data class Timestamp(val type: Int, val index: Int, val millis: Long) : RawPacket
    data class Unknown(val type: Int, val size: Int) : RawPacket
}
fun parseRaw(data: ByteArray): RawPacket

data class Satellite(val id: String, val system: String, val elev: Double?, val az: Double?, val snr: Double?)
data class GpsState(
    val fix: Boolean = false, val quality: Int = 0, val lat: Double? = null, val lon: Double? = null,
    val altM: Double? = null, val speedMps: Double? = null, val course: Double? = null, val hdop: Double? = null,
    val satsUsed: Int = 0, val utc: String? = null, val satellites: List<Satellite> = emptyList(),
)
class Nmea { fun feed(text: String): Boolean; val state: GpsState }
data class MicStats(val packets: Int = 0, val gaps: Int = 0, val frames: Int = 0, val errors: Int = 0, val skippedBytes: Int = 0)
data class SplitFrames(val frames: List<ByteArray>, val tail: ByteArray, val skippedBytes: Int)
fun splitFrames(buffer: ByteArray): SplitFrames
class OpusFrameSplitter { fun feed(packet: RawPacket.Mic): List<ByteArray>; val stats: MicStats }

enum class FetchType(val code: Int, val key: String, val consumedOnTransfer: Boolean = false)
// ACTIVITY, MANUAL_HR, WORKOUTS, WORKOUT_DETAILS, PAI, STRESS_MANUAL, STRESS, SPO2,
// SPO2_SLEEP, STATISTICS, TEMPERATURE, SLEEP_RESPIRATORY_RATE, RESTING_HR, MAX_HR, SLEEP_SESSION, HRV
fun activityTimeBytes(t: ZonedDateTime): ByteArray
fun fetchStartCommand(code: Int, since: ZonedDateTime): ByteArray
data class FetchStart(val count: Long, val startEpoch: Long?)
fun parseFetchStart(reply: ByteArray, zone: ZoneId): FetchStart?
val FETCH_GO: ByteArray
class FetchAssembler {
    fun feedData(packet: ByteArray)
    fun feedControl(reply: ByteArray): Boolean
    fun reset()
    val complete: Boolean
    val payload: ByteArray
}
data class ActivityMinute(
    val ts: Long, val kind: Int, val intensity: Int, val steps: Int, val hr: Int?,
    val unknown1: Int, val sleep: Int, val deep: Int, val rem: Int,
)
fun parseActivity(data: ByteArray, startEpoch: Long): List<ActivityMinute>
enum class ActivityState // NOT_WORN, SLEEP_LIGHT, SLEEP_DEEP, SLEEP_REM, ACTIVE, IDLE, CHARGING, UNKNOWN
fun activityStates(minutes: List<ActivityMinute>, prevKind: Int? = null): List<ActivityState>
fun validHr(hr: Int?): Boolean

data class HealthPoint(val type: String, val ts: Long, val value: Double?, val extra: Map<String, String> = emptyMap())
fun parseManualHr(data: ByteArray): List<HealthPoint>
fun parseStress(data: ByteArray, startEpoch: Long): List<HealthPoint>
fun parseStressManual(data: ByteArray): List<HealthPoint>
fun parsePai(data: ByteArray): List<HealthPoint>
fun parseSpo2(data: ByteArray, type: String = "spo2"): List<HealthPoint>
fun parseSpo2Sleep(data: ByteArray): List<HealthPoint>
fun parseTemperature(data: ByteArray, startEpoch: Long?, endEpoch: Long): List<HealthPoint>
fun parseStatistics(data: ByteArray, fetchedAt: Long): List<HealthPoint>
fun parseHealth(type: FetchType, data: ByteArray, startEpoch: Long?, endEpoch: Long): List<HealthPoint>
data class TrackPoint(val t: Int, val hr: Int)
data class Workout(
    val start: Long, val end: Long, val type: String, val durationS: Int,
    val activeS: Int?, val avgHr: Int?, val maxHr: Int?, val calories: Int?, val steps: Int?, val distanceM: Int?,
    val track: List<TrackPoint>, val version: Int = 0, val typeCode: Int = 0,
    val avgSpeedMs: Double? = null, val avgCadence: Int? = null,
)
val WORKOUT_TYPES: Map<Int, String>
fun parseWorkoutSummary(data: ByteArray): Workout?
fun parseWorkoutDetails(data: ByteArray): List<TrackPoint>

enum class NotificationKind // MESSAGE, CALL, GENERIC
fun notificationPayload(title: String, body: String, kind: NotificationKind = NotificationKind.MESSAGE, app: String = "Heartline"): ByteArray
fun chunkedWrites(payload: ByteArray, mtu: Int = 23, type: Int = 0): List<ByteArray>
const val NOTIFICATION_MAX: Int = 230
val EXTRA_HEADER: ByteArray
val CALL_END: ByteArray
fun currentTimeBytes(now: ZonedDateTime): ByteArray
fun parseCurrentTime(data: ByteArray): String?
enum class SettingTarget // CONFIG, HR
data class SettingOption(val value: Any, val label: String)
data class SettingDef(
    val key: String, val label: String, val type: String, val options: List<SettingOption> = emptyList(),
    val min: Int? = null, val max: Int? = null, val target: SettingTarget = SettingTarget.CONFIG,
)
val SETTINGS: List<SettingDef>
fun validateSetting(key: String, value: Any): Any
fun settingCommand(key: String, value: Any, current: Map<String, Any?> = emptyMap()): Pair<SettingTarget, ByteArray>
data class StepsNow(val steps: Int, val distanceM: Int?, val calories: Int?)
fun parseRealtimeSteps(data: ByteArray): StepsNow?
val DEVICE_EVENTS: Map<Int, String>
val MUSIC_COMMANDS: Map<Int, String>
fun deviceEventName(data: ByteArray): String
```

## Integration details

- Do not send a `0x03` fetch acknowledgement: it deletes watch data. No fetch ack constant exists.
- `FetchAssembler` accepts any first counter, then requires modulo-256 increments. A gap/duplicate invalidates the
  transfer until `reset()`; partial data should be discarded. Feed ACT_DATA through `feedData`, ACT_CTRL through
  `feedControl`. Completion is the `10 02 01` **prefix**, including replies with trailing bytes. This counter check is
  requested by the Kotlin brief; Python's connection callback only strips the counter.
- `parseFetchStart` returns null for refused headers. The encoded timezone wins, even when zero; a missing timezone
  means UTC, as in Python. `zone` is retained for the required API and never overrides a wire timestamp.
- Raw HR sentinel values are retained in activity minutes; `activityStates` applies validity checks for fallback states.
- `HealthPoint.extra` retains Python's metadata keys. Booleans are lowercase `true`/`false`, numeric metadata uses
  decimal strings, and unavailable `sensor2` is the string `"null"`. Statistic/unsupported blobs use `value = null`
  and `extra["raw"]` containing lossless hex, timestamped at the supplied fetch end. Python stores these separately
  in `raw_fetches`; the Kotlin model carries them without inventing decoded measurements.
- Workout summary fields not in the fixed brief (`version`, `typeCode`, `avgSpeedMs`, `avgCadence`) are also retained.
  Attach details with `summary.copy(track = parseWorkoutDetails(details))`.
- `parseRaw` retains Python's opaque treatment of type `0x07` as `Unknown`. `Timestamp` is a reserved public model;
  the reference does not establish a timestamp decoder. No guessed byte interpretation is introduced.
- Opus frames are returned unchanged; PCM/PyAV decoding and decoder errors belong elsewhere. `MicStats.errors`
  therefore stays zero. Packet gaps drop partial frames; counter rollover is normal.
- Notifications include the mandatory four-zero extra header and default to MTU 23 / 17 payload bytes per chunk.
  `Heartline` is the required default app name; tests also reproduce Python's `Dashboard` suffix explicitly.
- Settings combine `validate_setting` and `setting_command` at the public entry point; invalid writes throw
  `IllegalArgumentException` with Python's validation messages. Boolean values preserve Python truthiness.
- `StepsNow` and workout integer fields use the brief's signed `Int` API; raw u32 timestamps and fetch counts use `Long`.
  ByteArray/IntArray properties have Kotlin's ordinary array equality semantics; compare their contents when needed.

## Validation and reproduction

Validated on 2026-11-01: **82 JUnit tests passed, 0 failures, 0 errors, 0 skipped**, including the real capture.

The root scaffold currently includes `:core:data` without a directory. An ordinary root Gradle invocation fails
during settings validation before compiling protocol. The module-local init script redirects only that missing,
empty project to `core/protocol/build/empty-data-project`, leaving sibling modules untouched. It becomes a no-op
when the real module exists.

From Git Bash in the worktree, run:

```bash
bash android/core/protocol/tools/test.sh
```

Equivalent task (from `android`, with JAVA_HOME and GRADLE_USER_HOME set as in the brief):

```bash
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -I core/protocol/tools/scaffold.init.gradle.kts :core:protocol:test
```

The launcher also places Android's auxiliary user files in this module's `build` directory, and uses in-process
Kotlin compilation because the sandbox blocks Kotlin's default daemon marker directory.

The optional capture test uses `Assume` if
`C:/Users/abhir/Desktop/projects/amazfit-gtr2e/data/capture_all.json` is absent. It was present during validation.
The baseline was obtained on 2026-11-01 by iterating `[timestamp, hex]` pairs through Python's `protocol.parse_raw`:

| Kind | Packets | Samples |
| --- | ---: | ---: |
| accel | 15 | 385 |
| ppg | 65 | 387 |
| gyro | 15 | 386 |
| mag | 128 | 384 |
| gps | 33 | 33 |
| mic | 142 | 142 |
| other | 223 | 0 |

Regenerate the oracle and print those capture counts, without writing to the reference project:

```powershell
& C:/Users/abhir/Desktop/projects/amazfit-gtr2e/.venv/Scripts/python.exe -B `
  android/core/protocol/tools/reference_vectors.py C:/Users/abhir/Desktop/projects/amazfit-gtr2e
```

All protocol assertions from `test_features.py`, `test_health.py`, and `test_protocol_store.py` are represented.
Store/API assertions and the PCM decode/FFT assertion are outside this module's scope. Additional tests cover
NMEA checksums/state, fetch gaps and rollover, chunk boundaries 17/18/34/35, Unicode cuts, signed-zero rounding,
opaque health preservation, workout variants, and the real 55-frame microphone fixture at every split boundary.
