package dev.heartline.app

import android.net.Uri
import dev.heartline.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Everything the screens can see and do. The real implementation wires the BLE service, the database and the
 * analytics together; [DemoController] fakes it so the UI can be built and checked without a watch.
 */
interface HeartlineController {
    val connection: StateFlow<ConnectionUi>
    val battery: StateFlow<BatteryUi?>
    val device: StateFlow<DeviceUi?>
    val liveHr: StateFlow<LiveHrUi>
    val steps: StateFlow<StepsUi?>
    val sync: StateFlow<SyncUi>
    val today: StateFlow<TodayUi?>
    val trends: StateFlow<TrendsUi?>
    val checks: StateFlow<List<CheckUi>>
    val checkRun: StateFlow<CheckRunUi?>
    val sensors: StateFlow<SensorsUi>
    val recordings: StateFlow<List<RecordingUi>>
    val watchSettings: StateFlow<List<WatchSettingUi>>
    val events: StateFlow<List<EventUi>>
    val prefs: StateFlow<PrefsUi>

    fun activityDay(date: LocalDate): Flow<ActivityDayUi>
    /** Everything recorded for one local day, for the day detail pages (heart rate, stress, body, steps). */
    fun healthDay(date: LocalDate): Flow<HealthDayUi>
    suspend fun checkDetail(id: String): SessionUi?

    fun connect()
    fun disconnect()
    fun setLiveHr(on: Boolean)
    fun startCheck(seconds: Int)
    fun cancelCheck()
    fun setRawSensors(sensors: Set<Sensor>, streaming: Boolean)
    fun vibrate()
    /** [asPackage] makes it look like that app's notification (its watch icon and name), e.g. "com.whatsapp". */
    fun sendNotification(title: String, body: String, kind: NotifyKind, asPackage: String = "")
    fun syncNow()
    /** Fetches only temperature, stress and SpO2: quick enough to call every minute while a page shows them. */
    fun refreshTemperature()
    fun setWatchClock()
    fun applyWatchSetting(key: String, value: Any)
    fun saveProfile(profile: ProfileUi)
    fun setTheme(mode: ThemeMode)
    fun setMetric(metric: Boolean)
    fun setForwarding(update: ForwardingUi)
    fun saveWatchKey(mac: String, keyHex: String): String?
    fun finishOnboarding()
    fun importPcData(uri: Uri)
    fun exportData(uri: Uri)
    fun recordingAudio(file: String): Uri?

    // Weather on the watch, find phone, music controls
    val weather: StateFlow<WeatherUi>
    val phoneLink: StateFlow<PhoneLinkUi>
    /** Free-text place search (Open-Meteo geocoding); empty query returns nothing. */
    suspend fun searchPlaces(query: String): List<PlaceUi>
    fun setWeather(enabled: Boolean, place: PlaceUi?)
    fun refreshWeather()
    fun setMusicControl(enabled: Boolean)
    fun stopFindPhone()

    // Alarms and calendar reminders on the watch
    val alarms: StateFlow<List<AlarmUi>>
    val reminders: StateFlow<ReminderSyncUi>
    fun saveAlarm(alarm: AlarmUi)
    fun deleteAlarm(slot: Int)
    /** Call after READ_CALENDAR is granted (or to turn sync off); [calendars] are CalendarContract ids. */
    fun setReminderSync(enabled: Boolean, calendars: Set<Long>)

    // Health Connect and staying alive in the background
    val healthConnect: StateFlow<HealthConnectUi>
    val background: StateFlow<BackgroundUi>
    /** Health Connect permission strings the UI must request with PermissionController's contract. */
    val healthConnectPermissions: Set<String>
    fun setHealthConnect(enabled: Boolean)
    fun exportHealthConnectNow()
    /** Re-reads battery-optimisation, notification-access and permission state (call on resume). */
    fun refreshBackground()

    // Workout capture: every useful sensor at full rate for a session, analysed afterwards
    val captureRun: StateFlow<CaptureRunUi?>
    val captures: StateFlow<List<CaptureItemUi>>
    /** Streams pulse, motion, gyroscope and compass plus the watch's own live heart rate until [minutes] pass or [stopCapture]. */
    fun startCapture(label: String, minutes: Int)
    fun stopCapture()
    suspend fun captureDetail(id: String): CaptureAnalysisUi?
    fun deleteCapture(id: String)

    /** Tries the raw-stream mask bits nobody has mapped yet; one line of results per bit. */
    val probe: StateFlow<List<String>>
    fun probeUnknownSensors()
}

data class SeriesPoint(val t: Double, val v: Double)
/** Time in one heart-rate zone; zone 1..5, bounds in bpm (high null for the top zone). */
data class ZoneTimeUi(val zone: Int, val name: String, val lowBpm: Int, val highBpm: Int?, val seconds: Int)
/** A labelled value range and how long the day spent in it (stress bands, SpO2 bands). */
data class BandTimeUi(val name: String, val low: Int, val high: Int, val minutes: Int)
/** Per-hour summary; hour is 0-23 local, null fields mean no data that hour. */
data class HourStatUi(val hour: Int, val min: Double?, val avg: Double?, val max: Double?, val total: Double? = null)
/**
 * One local day. All series use epoch seconds in [SeriesPoint.t]. [hr] merges the watch's per-minute history with the
 * finer live readings; [stress] is the watch's automatic stress (0-100); [spo2] its SpO2 readings (%); [temperature] /
 * [temperature2] the two words of the per-minute temperature record (°C). [steps] is steps per minute.
 * Bands follow Zepp: stress relaxed 1-39, normal 40-59, medium 60-79, high 80-100.
 */
data class HealthDayUi(
    val date: LocalDate, val dayStart: Long,
    val hr: List<SeriesPoint>, val hrLiveSamples: Int, val restingHr: Int?, val hrMin: Int?, val hrAvg: Int?, val hrMax: Int?,
    val hrZones: List<ZoneTimeUi>, val hrByHour: List<HourStatUi>, val hrHighMinutes: Int,
    val stress: List<SeriesPoint>, val stressAvg: Int?, val stressMax: Int?, val stressBands: List<BandTimeUi>, val stressByHour: List<HourStatUi>,
    val spo2: List<SeriesPoint>, val spo2Avg: Double?, val spo2Min: Double?, val spo2Bands: List<BandTimeUi>,
    val temperature: List<SeriesPoint>, val temperature2: List<SeriesPoint>, val tempAvg: Double?, val tempMin: Double?, val tempMax: Double?,
    val steps: List<SeriesPoint>, val stepsTotal: Int, val stepsByHour: List<HourStatUi>, val distanceM: Int?, val caloriesKcal: Int?,
    val activeMinutes: Int, val wornMinutes: Int,
    val paiToday: Double?, val paiTotal: Double?,
    val minutes: List<MinuteUi>, val sleep: SleepNightUi?, val workouts: List<WorkoutUi>,
)
data class LapUi(val index: Int, val durationS: Int, val distanceM: Int?, val avgHr: Int?, val paceSPerKm: Double?)
/** Everything decoded beyond the basic summary. Any decoded field without a dedicated slot goes in [extras] as label to value. */
data class WorkoutDetailUi(
    val activeS: Int? = null, val avgPaceSPerKm: Double? = null, val bestPaceSPerKm: Double? = null,
    val avgSpeedKmh: Double? = null, val maxSpeedKmh: Double? = null, val avgCadence: Int? = null, val maxCadence: Int? = null,
    val avgStrideCm: Int? = null, val ascentM: Double? = null, val descentM: Double? = null, val minAltM: Double? = null, val maxAltM: Double? = null,
    val minHr: Int? = null, val trainingEffect: Double? = null, val load: Double? = null, val recoveryHours: Int? = null,
    val zones: List<ZoneTimeUi> = emptyList(), val speedKmh: List<SeriesPoint> = emptyList(), val cadence: List<SeriesPoint> = emptyList(),
    val altitude: List<SeriesPoint> = emptyList(), val laps: List<LapUi> = emptyList(), val extras: List<Pair<String, String>> = emptyList(),
)

data class CaptureRunUi(val label: String, val startedAt: Long, val plannedS: Int, val hr: Int?, val seconds: Int, val samples: Long, val error: String? = null)
data class CaptureItemUi(val id: String, val label: String, val start: Long, val durationS: Int, val avgHr: Int?, val sets: Int?, val load: Double?)
/** One repetition. [t] is seconds from the capture start; [slowS] + [fastS] = [durationS]; [arcDeg] is wrist rotation. */
data class RepUi(val t: Double, val durationS: Double, val slowS: Double, val fastS: Double, val arcDeg: Double)
/**
 * [start]/[end] are seconds from the capture start. [group] numbers runs of consecutive sets that look like the same
 * exercise (0, 1, 2…). [activity] is the overlapping watch workout's name, if any. [slowdownS] is the change in rep time
 * per rep (positive = slowing). [partialLast] means the last rep's arc fell well short of the set's typical arc.
 */
data class SetUi(
    val start: Double, val end: Double, val reps: Int, val avgHr: Int?, val peakHr: Int?, val tempoS: Double?,
    val repDetail: List<RepUi> = emptyList(), val group: Int = 0, val activity: String? = null,
    val slowdownS: Double? = null, val partialLast: Boolean = false, val startHr: Int? = null, val hrRise: Int? = null,
)
data class RestUi(val start: Double, val end: Double, val startHr: Int?, val endHr: Int?, val drop60: Int?)
/** kind: "lifting", "walking", "running", "rest" or "other". */
data class SegmentUi(val start: Double, val end: Double, val kind: String, val avgHr: Int?, val cadenceSpm: Int?)
/** A watch workout that overlaps the capture, clipped to it. Times are seconds from the capture start. */
data class CaptureActivityUi(
    val start: Double, val end: Double, val type: String, val activeS: Int?, val distanceM: Int?, val steps: Int?,
    val calories: Int?, val avgHr: Int?, val maxHr: Int?,
)
/** Average heart-rate change [delta] (bpm) [t] seconds after a set's peak, over [n] rests. */
data class RecoveryPointUi(val t: Int, val delta: Double, val n: Int)
/**
 * A sustained walking/running block (3 min or more). [name] comes from an overlapping watch workout (e.g. "Treadmill").
 * [riseTauS]/[recoveryTauS] are exponential time constants; [drop60]/[drop120] are bpm fallen after the block ends.
 */
data class CardioUi(
    val start: Double, val end: Double, val kind: String, val name: String?, val avgHr: Int?, val steadyHr: Int?, val peakHr: Int?,
    val riseTauS: Int?, val driftPerMin: Double?, val drop60: Int?, val drop120: Int?, val recoveryTauS: Int?,
    val cadenceSpm: Int?, val distanceM: Int?, val speedKmh: Double?,
)
/** Beat-level check of one still stretch. status: "regular", "irregular", "possible_af" or "unreliable". */
data class PulseWindowUi(val start: Double, val end: Double, val beats: Int, val hrPpg: Int, val hrWatch: Int?, val rmssdMs: Double, val nrmssd: Double, val status: String)
/**
 * Pulse regularity from raw PPG in still stretches. status: "regular", "irregular", "possible_af" or "insufficient_data".
 * [pairs] are consecutive clean beat intervals in ms (Poincaré). [excerpt] is band-passed PPG at [excerptFs] Hz starting
 * [excerptStart] s into the capture, with [excerptBeats] in seconds from the excerpt start.
 */
data class PulseUi(
    val status: String, val usableS: Int, val windows: List<PulseWindowUi>, val nrmssd: Double?, val entropy: Double?,
    val ectopic: Int, val pairs: List<Pair<Double, Double>>, val excerpt: List<Double>, val excerptFs: Double,
    val excerptStart: Double, val excerptBeats: List<Double>, val notes: List<String>,
)
data class CaptureAnalysisUi(
    val id: String, val label: String, val start: Long, val durationS: Int,
    val avgHr: Int?, val maxHr: Int?, val minHr: Int?, val zones: List<ZoneTimeUi>, val load: Double?, val calories: Int?,
    val steps: Int?, val hr: List<SeriesPoint>, val motion: List<SeriesPoint>, val segments: List<SegmentUi>,
    val sets: List<SetUi>, val rests: List<RestUi>, val hrSource: String, val notes: List<String>,
    val activities: List<CaptureActivityUi> = emptyList(), val recovery: List<RecoveryPointUi> = emptyList(),
    /** Lowest-decile heart rate per 5-minute window: the session's rising baseline. */
    val floor: List<SeriesPoint> = emptyList(), val workS: Int? = null, val restS: Int? = null,
    val cardio: List<CardioUi> = emptyList(), val pulse: PulseUi? = null,
)

data class PlaceUi(val name: String, val region: String?, val lat: Double, val lon: Double)
data class WeatherUi(
    val enabled: Boolean = false, val place: PlaceUi? = null, val tempC: Int? = null, val condition: String? = null,
    val highC: Int? = null, val lowC: Int? = null, val updatedAt: Long? = null, val sentToWatchAt: Long? = null, val error: String? = null,
)
data class PhoneLinkUi(val findPhoneRinging: Boolean = false, val musicEnabled: Boolean = false, val musicAccess: Boolean = false, val nowPlaying: String? = null)

/** The watch has a fixed number of alarm slots; [slot] is 0-based. */
data class AlarmUi(val slot: Int, val enabled: Boolean, val hour: Int, val minute: Int, val days: Set<java.time.DayOfWeek>, val smart: Boolean = false)
data class CalendarUi(val id: Long, val name: String, val account: String?)
data class ReminderUi(val title: String, val start: Long)
data class ReminderSyncUi(
    val enabled: Boolean = false, val permission: Boolean = false, val calendars: List<CalendarUi> = emptyList(),
    val selected: Set<Long> = emptySet(), val upcoming: List<ReminderUi> = emptyList(), val lastSync: Long? = null, val maxSlots: Int = 10,
)

enum class HealthConnectStatus { UNAVAILABLE, NEEDS_INSTALL, NEEDS_PERMISSION, READY }
data class HealthConnectUi(val status: HealthConnectStatus = HealthConnectStatus.UNAVAILABLE, val enabled: Boolean = false, val lastExport: Long? = null, val lastResult: String? = null)
data class BackgroundUi(
    val batteryUnrestricted: Boolean = false, val notificationAccess: Boolean = false, val miui: Boolean = false,
    val bluetoothOn: Boolean = true, val postNotifications: Boolean = true, val companionLinked: Boolean = false,
)

enum class LinkState { DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }

data class ConnectionUi(val state: LinkState = LinkState.DISCONNECTED, val since: Long? = null, val error: String? = null, val rssi: Int? = null)
data class BatteryUi(val level: Int, val charging: Boolean, val lastCharged: String?)
data class DeviceUi(val model: String, val mac: String, val firmware: String?, val hardware: String?, val serial: String?)
data class HrPoint(val ts: Long, val bpm: Int)
data class LiveHrUi(val streaming: Boolean = false, val bpm: Int? = null, val ts: Long? = null, val recent: List<HrPoint> = emptyList())
data class StepsUi(val steps: Int, val distanceM: Int?, val calories: Int?)
data class SyncUi(val running: Boolean = false, val lastActivity: Long? = null, val lastHealth: Long? = null, val message: String? = null)

enum class Impact { POSITIVE, NEUTRAL, NEGATIVE }
data class Factor(val name: String, val impact: Impact, val detail: String)
data class AlertUi(val warn: Boolean, val title: String, val detail: String)
data class SleepNightUi(val start: Long, val end: Long, val deepMin: Int, val lightMin: Int, val remMin: Int, val awakeMin: Int)

data class TodayUi(
    val readiness: Int?,
    val readinessLabel: String,          // "Readiness" or "Readiness, 31 Oct" before tonight's sleep is in
    val recommendation: String?,
    val strain: Double?,
    val sleepScore: Int?,
    val bodyBattery: Int?,
    val restingHr: Int?,
    val factors: List<Factor>,
    val alerts: List<AlertUi>,
    val lastNight: SleepNightUi?,
    val stepsToday: Int?,
    val activeMinutes: Int?,
)

data class DayPoint(val date: LocalDate, val value: Double?)
data class TrendSeries(val key: String, val title: String, val unit: String, val points: List<DayPoint>, val baseline: Double? = null, val bars: Boolean = false, val accent: Boolean = false)
data class TrendsUi(
    val vo2max: Double?, val fitnessAge: Int?, val sleepRegularity: Int?, val load7d: Double?, val acwr: Double?,
    val series: List<TrendSeries>,
)

enum class MinuteState { NOT_WORN, SLEEP_LIGHT, SLEEP_DEEP, SLEEP_REM, ACTIVE, IDLE, CHARGING, UNKNOWN }
data class MinuteUi(val ts: Long, val state: MinuteState, val steps: Int, val hr: Int?)
data class RoutePoint(val t: Int, val lat: Double, val lon: Double, val altM: Double? = null)
data class WorkoutUi(val start: Long, val durationS: Int, val type: String, val avgHr: Int?, val maxHr: Int?, val calories: Int?, val steps: Int?, val distanceM: Int?, val track: List<HrPoint>, val route: List<RoutePoint> = emptyList(), val detail: WorkoutDetailUi? = null)
data class ActivityDayUi(
    val date: LocalDate, val minutes: List<MinuteUi>, val steps: Int, val activeMinutes: Int, val sleepMinutes: Int,
    val wornMinutes: Int, val hrMin: Int?, val hrAvg: Int?, val hrMax: Int?, val sleep: SleepNightUi?, val workouts: List<WorkoutUi>,
)

enum class RhythmStatus { REGULAR, IRREGULAR, POSSIBLE_AF, INSUFFICIENT }
data class CheckUi(val id: String, val start: Long, val durationS: Int, val hr: Double?, val rmssd: Double?, val rhythm: RhythmStatus)
data class RrUi(val t: Double, val rrMs: Double, val ectopic: Boolean)
data class SessionUi(
    val id: String, val start: Long, val durationS: Double, val signalQuality: Double, val hr: Double?,
    val rmssd: Double?, val sdnn: Double?, val pnn50: Double?, val stressIndex: Double?, val lfHf: Double?,
    val respiratoryRate: Double?, val rhythm: RhythmStatus, val ectopic: Int, val notes: List<String>,
    val rr: List<RrUi>, val ppgPreview: FloatArray, val previewFs: Double, val beatTimes: DoubleArray,
)
data class CheckRunUi(val seconds: Int, val startedAt: Long, val analysing: Boolean, val result: SessionUi?, val error: String?)

enum class Sensor { PPG, ACCEL, GYRO, MAG, GPS, MIC }
data class SatUi(val id: String, val system: String, val elev: Double, val az: Double, val snr: Double?)
data class GpsUi(val fix: Boolean, val satsUsed: Int, val sats: List<SatUi>, val lat: Double?, val lon: Double?, val altM: Double?, val speedMps: Double?, val hdop: Double?, val utc: String?)

/** Live sensor buffers: the newest WINDOW seconds, oldest first. Arrays are replaced, never mutated after emit. */
data class SensorsUi(
    val streaming: Boolean = false,
    val selected: Set<Sensor> = setOf(Sensor.PPG, Sensor.ACCEL),
    val ppg: FloatArray = FloatArray(0),
    val accel: Array<FloatArray> = emptyArray(),
    val gyro: Array<FloatArray> = emptyArray(),
    val mag: Array<FloatArray> = emptyArray(),
    val headingDeg: Double? = null,
    val compassCalibration: Double = 0.0,
    val gps: GpsUi? = null,
    val micDb: FloatArray = FloatArray(0),
    val ppgBpm: Int? = null,
    val file: String? = null,
)

data class RecordingUi(val file: String, val start: Long, val durationS: Double, val sensors: Set<Sensor>, val hasAudio: Boolean)
data class WatchSettingUi(val key: String, val label: String, val type: String, val value: Any?, val options: List<Pair<Any, String>>, val min: Int?, val max: Int?)
data class EventUi(val ts: Long, val warn: Boolean, val error: Boolean, val message: String)
enum class NotifyKind { MESSAGE, CALL, GENERIC }

data class ProfileUi(val age: Int? = null, val sex: String? = null, val weightKg: Double? = null, val heightCm: Double? = null, val maxHr: Int? = null, val restingHr: Int? = null)
data class ForwardingUi(val enabled: Boolean = false, val calls: Boolean = true, val allowed: Set<String> = emptySet(), val quietStart: Int? = 23, val quietEnd: Int? = 7)
data class PrefsUi(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val metric: Boolean = true,
    val profile: ProfileUi = ProfileUi(),
    val forwarding: ForwardingUi = ForwardingUi(),
    val onboarded: Boolean = false,
    val hasKey: Boolean = false,
    val watchMac: String? = null,
)

/** Apps the test composer can imitate: package to display name. */
val SAMPLE_APPS = listOf("" to "Heartline", "com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram",
    "com.instagram.android" to "Instagram", "com.google.android.gm" to "Gmail", "com.samsung.android.messaging" to "Messages",
    "com.google.android.calendar" to "Calendar", "com.discord" to "Discord")
