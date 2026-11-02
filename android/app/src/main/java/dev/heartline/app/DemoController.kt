package dev.heartline.app

import android.net.Uri
import dev.heartline.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic data that behaves like the real watch, for building and screenshotting the UI. */
class DemoController(private val scope: CoroutineScope) : HeartlineController {
    private val zone = ZoneId.systemDefault()
    private val rnd = Random(7)
    private fun now() = System.currentTimeMillis() / 1000

    override val connection = MutableStateFlow(ConnectionUi(LinkState.CONNECTED, since = now() - 754, rssi = -58))
    override val battery = MutableStateFlow<BatteryUi?>(BatteryUi(68, false, "31 Oct, 04:10"))
    override val device = MutableStateFlow<DeviceUi?>(DeviceUi("Amazfit GTR 2e", "C1:0B:05:00:0A:46", "V1.7.3.1", "V0.72.19.17", "PVXB33 1400160015"))
    override val liveHr = MutableStateFlow(LiveHrUi(streaming = false, bpm = 72, ts = now() - 30, recent = demoHr()))
    override val steps = MutableStateFlow<StepsUi?>(StepsUi(6412, 4630, 288))
    override val sync = MutableStateFlow(SyncUi(lastActivity = now() - 300, lastHealth = now() - 300))
    override val today = MutableStateFlow<TodayUi?>(demoToday())
    override val trends = MutableStateFlow<TrendsUi?>(demoTrends())
    override val checks = MutableStateFlow(demoChecks())
    override val checkRun = MutableStateFlow<CheckRunUi?>(null)
    override val sensors = MutableStateFlow(SensorsUi())
    override val recordings = MutableStateFlow(listOf(RecordingUi("20261101-125835", now() - 3600, 20.6, setOf(Sensor.PPG, Sensor.ACCEL, Sensor.MIC), true)))
    override val watchSettings = MutableStateFlow(demoSettings())
    override val events = MutableStateFlow(listOf(
        EventUi(now() - 40, false, false, "Activity sync done: 12 new minutes"),
        EventUi(now() - 754, false, false, "Connected and authenticated"),
        EventUi(now() - 760, true, false, "Watch not found, retrying scan"),
    ))
    override val prefs = MutableStateFlow(PrefsUi(profile = ProfileUi(age = 27, sex = "male", weightKg = 70.0, heightCm = 175.0), onboarded = true, hasKey = true, watchMac = "C1:0B:05:00:0A:46"))

    private var hrJob: Job? = null
    private var rawJob: Job? = null
    private var checkJob: Job? = null

    override fun activityDay(date: LocalDate): Flow<ActivityDayUi> = flowOf(demoDay(date))
    override suspend fun checkDetail(id: String): SessionUi = demoSession(id)

    override fun connect() { connection.value = ConnectionUi(LinkState.CONNECTED, since = now()) }
    override fun disconnect() { connection.value = ConnectionUi(); setLiveHr(false); setRawSensors(sensors.value.selected, false) }

    override fun setLiveHr(on: Boolean) {
        hrJob?.cancel()
        liveHr.update { it.copy(streaming = on) }
        if (!on) return
        hrJob = scope.launch {
            var bpm = 74.0
            while (isActive) {
                delay(1500)
                bpm = (bpm + rnd.nextDouble(-2.0, 2.0)).coerceIn(60.0, 95.0)
                val p = HrPoint(now(), bpm.roundToInt())
                liveHr.update { it.copy(bpm = p.bpm, ts = p.ts, recent = (it.recent + p).takeLast(600)) }
            }
        }
    }

    override fun setRawSensors(sensors: Set<Sensor>, streaming: Boolean) {
        rawJob?.cancel()
        this.sensors.update { it.copy(selected = sensors, streaming = streaming, file = if (streaming) "20261101-130512" else it.file) }
        if (!streaming) return
        rawJob = scope.launch {
            var n = 0
            val window = 8 * 25
            val ppg = ArrayDeque<Float>(); val acc = ArrayDeque<FloatArray>(); val gyro = ArrayDeque<FloatArray>()
            val mag = ArrayDeque<FloatArray>(); val mic = ArrayDeque<Float>()
            while (isActive) {
                repeat(5) {
                    val t = n / 25.0; n++
                    val ph = (t * 1.25) % 1.0
                    val beat = if (ph < 0.15) ph / 0.15 else exp(-(ph - 0.15) * 4) + 0.15 * exp(-((ph - 0.45) * (ph - 0.45)) / 0.003)
                    ppg.addLast((beat * 100 + rnd.nextDouble(-3.0, 3.0)).toFloat())
                    acc.addLast(floatArrayOf(-0.76f + rnd.nextFloat() * 0.01f, -0.17f, -0.63f))
                    gyro.addLast(floatArrayOf((300 * sin(t * 1.7)).toFloat(), (180 * sin(t * 0.9 + 1)).toFloat(), (90 * cos(t * 2.3)).toFloat()))
                    val turn = t * 0.4
                    mag.addLast(floatArrayOf((1500 * cos(turn)).toFloat(), (1500 * sin(turn)).toFloat(), -860f))
                    repeat(2) { mic.addLast((-55 + 18 * max(0.0, sin(t / 1.6)) + rnd.nextDouble(0.0, 4.0)).toFloat()) }
                }
                while (ppg.size > window) ppg.removeFirst()
                while (acc.size > window) acc.removeFirst()
                while (gyro.size > window) gyro.removeFirst()
                while (mag.size > window) mag.removeFirst()
                while (mic.size > 400) mic.removeFirst()
                val m = mag.last()
                val heading = ((atan2(-m[1].toDouble(), m[0].toDouble()) * 180 / PI) + 360) % 360
                this@DemoController.sensors.update {
                    it.copy(
                        ppg = ppg.toFloatArray(), accel = acc.toTypedArray(), gyro = gyro.toTypedArray(), mag = mag.toTypedArray(),
                        micDb = mic.toFloatArray(), ppgBpm = 75, headingDeg = heading, compassCalibration = (n / 600.0).coerceAtMost(1.0),
                        gps = if (Sensor.GPS in it.selected) demoGps() else null,
                    )
                }
                delay(200)
            }
        }
    }

    override fun startCheck(seconds: Int) {
        checkJob?.cancel()
        checkRun.value = CheckRunUi(seconds, now(), analysing = false, result = null, error = null)
        setRawSensors(setOf(Sensor.PPG, Sensor.ACCEL), true)
        checkJob = scope.launch {
            delay(seconds * 1000L)
            checkRun.update { it?.copy(analysing = true) }
            delay(1200)
            setRawSensors(sensors.value.selected, false)
            val s = demoSession("c-${now()}")
            checkRun.update { it?.copy(analysing = false, result = s) }
            checks.update { listOf(CheckUi(s.id, s.start, seconds, s.hr, s.rmssd, s.rhythm)) + it }
        }
    }

    override fun cancelCheck() { checkJob?.cancel(); checkRun.value = null; setRawSensors(sensors.value.selected, false) }
    override fun vibrate() = addEvent("Watch vibrated")
    override fun sendNotification(title: String, body: String, kind: NotifyKind) = addEvent("Sent notification: $title")
    override fun syncNow() {
        scope.launch {
            sync.update { it.copy(running = true, message = "Syncing activity…") }
            delay(1500)
            sync.update { SyncUi(false, now(), now(), "Up to date") }
        }
    }
    override fun setWatchClock() = addEvent("Watch clock set")
    override fun applyWatchSetting(key: String, value: Any) {
        watchSettings.update { list -> list.map { if (it.key == key) it.copy(value = value) else it } }
    }
    override fun saveProfile(profile: ProfileUi) = prefs.update { it.copy(profile = profile) }
    override fun setTheme(mode: ThemeMode) = prefs.update { it.copy(theme = mode) }
    override fun setMetric(metric: Boolean) = prefs.update { it.copy(metric = metric) }
    override fun setForwarding(update: ForwardingUi) = prefs.update { it.copy(forwarding = update) }
    override fun saveWatchKey(mac: String, keyHex: String): String? {
        if (!Regex("[0-9a-fA-F]{32}").matches(keyHex.trim().removePrefix("0x"))) return "The key is 32 hex characters, like 7ff5d4a1…"
        prefs.update { it.copy(hasKey = true, watchMac = mac) }
        return null
    }
    override fun finishOnboarding() = prefs.update { it.copy(onboarded = true) }
    override fun importPcData(uri: Uri) = addEvent("Imported PC data")
    override fun exportData(uri: Uri) = addEvent("Exported data")
    override fun recordingAudio(file: String): Uri? = null

    private val bengaluru = PlaceUi("Bengaluru", "Karnataka, India", 12.97, 77.59)
    override val weather = MutableStateFlow(WeatherUi(true, bengaluru, 24, "Partly cloudy", 29, 19, now() - 1200, now() - 1180))
    override val phoneLink = MutableStateFlow(PhoneLinkUi(musicEnabled = true, musicAccess = true, nowPlaying = "Kesariya, Arijit Singh"))
    override suspend fun searchPlaces(query: String): List<PlaceUi> =
        if (query.isBlank()) emptyList() else listOf(bengaluru, PlaceUi("Bengaluru Rural", "Karnataka, India", 13.28, 77.53)).filter { it.name.contains(query, true) }
    override fun setWeather(enabled: Boolean, place: PlaceUi?) = weather.update { it.copy(enabled = enabled, place = place ?: it.place) }
    override fun refreshWeather() = weather.update { it.copy(updatedAt = now(), sentToWatchAt = now()) }
    override fun setMusicControl(enabled: Boolean) = phoneLink.update { it.copy(musicEnabled = enabled) }
    override fun stopFindPhone() = phoneLink.update { it.copy(findPhoneRinging = false) }

    override val alarms = MutableStateFlow(listOf(
        AlarmUi(0, true, 6, 30, setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)),
        AlarmUi(1, false, 8, 0, setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)),
    ))
    override val reminders = MutableStateFlow(ReminderSyncUi(
        enabled = true, permission = true, calendars = listOf(CalendarUi(1, "Personal", "you@example.com"), CalendarUi(2, "Holidays in India", null)),
        selected = setOf(1L), upcoming = listOf(ReminderUi("Dentist", now() + 26 * 3600), ReminderUi("Team sync", now() + 50 * 3600)), lastSync = now() - 600,
    ))
    override fun saveAlarm(alarm: AlarmUi) = alarms.update { list -> (list.filter { it.slot != alarm.slot } + alarm).sortedBy { it.slot } }
    override fun deleteAlarm(slot: Int) = alarms.update { list -> list.filter { it.slot != slot } }
    override fun setReminderSync(enabled: Boolean, calendars: Set<Long>) = reminders.update { it.copy(enabled = enabled, selected = calendars) }

    override val healthConnect = MutableStateFlow(HealthConnectUi(HealthConnectStatus.READY, true, now() - 3600, "Wrote 1,212 heart rate samples, 3 nights of sleep and 7 days of steps"))
    override val background = MutableStateFlow(BackgroundUi(batteryUnrestricted = false, notificationAccess = true, miui = true))
    override val healthConnectPermissions: Set<String> = emptySet()
    override fun setHealthConnect(enabled: Boolean) = healthConnect.update { it.copy(enabled = enabled) }
    override fun exportHealthConnectNow() = healthConnect.update { it.copy(lastExport = now()) }
    override fun refreshBackground() {}

    private fun addEvent(msg: String) = events.update { listOf(EventUi(now(), false, false, msg)) + it }

    // ---------- synthetic content ----------

    private fun demoHr(): List<HrPoint> {
        val t0 = now() - 3600
        return (0 until 3600 step 20).map { HrPoint(t0 + it, (66 + 8 * sin(it / 900.0) + rnd.nextDouble(-3.0, 3.0)).roundToInt()) }
    }

    private fun demoToday() = TodayUi(
        readiness = 74, readinessLabel = "Readiness",
        recommendation = "You recovered well. A moderate session fits today; keep the hard effort under an hour.",
        strain = 9.4, sleepScore = 81, bodyBattery = 63, restingHr = 56,
        factors = listOf(
            Factor("HRV", Impact.POSITIVE, "8% above your 28-day baseline."),
            Factor("Resting heart rate", Impact.NEUTRAL, "matches your baseline at 57 bpm."),
            Factor("Sleep", Impact.POSITIVE, "7 h 12 min with 1 h 24 min of deep sleep."),
            Factor("Training load", Impact.NEGATIVE, "the last 3 days were above your usual load."),
        ),
        alerts = listOf(AlertUi(false, "Late bedtimes", "Three of the last five nights started after 1 AM, which lowers sleep regularity.")),
        lastNight = SleepNightUi(now() - 8 * 3600, now() - 3600, 84, 248, 92, 18),
        stepsToday = 6412, activeMinutes = 47,
    )

    private fun demoTrends(): TrendsUi {
        val days = (13 downTo 0).map { LocalDate.now(zone).minusDays(it.toLong()) }
        fun s(key: String, title: String, unit: String, lo: Double, hi: Double, base: Double? = null, bars: Boolean = false, accent: Boolean = false) =
            TrendSeries(key, title, unit, days.map { DayPoint(it, rnd.nextDouble(lo, hi)) }, base, bars, accent)
        return TrendsUi(
            vo2max = 44.6, fitnessAge = 29, sleepRegularity = 78, load7d = 612.0, acwr = 1.18,
            series = listOf(
                s("readiness", "Readiness", "", 52.0, 86.0),
                s("rhr", "Resting heart rate", "bpm", 54.0, 61.0, base = 57.0),
                s("hrv", "HRV", "ms", 38.0, 62.0, base = 48.0),
                s("sleep", "Sleep", "h", 5.6, 7.9, bars = true),
                s("sleep_score", "Sleep score", "", 62.0, 88.0),
                s("steps", "Steps", "", 3500.0, 12500.0, bars = true),
                s("strain", "Strain", "", 6.0, 16.0, accent = true),
                s("active", "Active time", "min", 25.0, 110.0, bars = true, accent = true),
                s("spo2", "Blood oxygen", "%", 95.0, 98.0),
                s("stress", "Stress", "", 22.0, 48.0, accent = true),
                s("pai", "PAI", "", 40.0, 90.0, bars = true),
                s("temp", "Skin temperature", "°C", 31.0, 33.5),
            ),
        )
    }

    private fun demoChecks(): List<CheckUi> = (0 until 6).map {
        CheckUi("c$it", now() - it * 86400L - 3600, 120, rnd.nextDouble(62.0, 80.0), rnd.nextDouble(38.0, 60.0), if (it == 3) RhythmStatus.IRREGULAR else RhythmStatus.REGULAR)
    }

    fun demoSession(id: String): SessionUi {
        val rr = ArrayList<RrUi>()
        var t = 0.0
        for (i in 0 until 90) {
            val v = 820 + 45 * sin(i / 2.6) + rnd.nextDouble(-18.0, 18.0) + if (i == 31) -260.0 else if (i == 32) 240.0 else 0.0
            t += v / 1000
            rr += RrUi(t, v, i == 31)
        }
        val preview = FloatArray(60 * 25) { i ->
            val ph = (i / 25.0 * 1.22) % 1.0
            ((if (ph < 0.15) ph / 0.15 else exp(-(ph - 0.15) * 4)) * 100 + rnd.nextDouble(-4.0, 4.0)).toFloat()
        }
        return SessionUi(
            id, now() - 600, 75.0, 0.94, 72.8, 44.0, 48.0, 0.21, 96.0, 1.27, 14.2, RhythmStatus.REGULAR, 1,
            listOf("One premature beat with a compensatory pause."), rr, preview, 25.0, rr.map { it.t }.toDoubleArray(),
        )
    }

    private fun demoDay(date: LocalDate): ActivityDayUi {
        val start = date.atStartOfDay(zone).toEpochSecond()
        val mins = (0 until 1440).map { m ->
            val h = m / 60.0
            when {
                h < 7 -> {
                    val c = ((h * 60) % 90)
                    MinuteUi(start + m * 60, if (c < 20) MinuteState.SLEEP_LIGHT else if (c < 50) MinuteState.SLEEP_DEEP else if (c < 70) MinuteState.SLEEP_LIGHT else MinuteState.SLEEP_REM, 0, 52 + rnd.nextInt(6))
                }
                h in 8.0..8.6 || h in 18.0..19.2 -> MinuteUi(start + m * 60, MinuteState.ACTIVE, 70 + rnd.nextInt(50), 100 + rnd.nextInt(30))
                h in 22.5..23.2 -> MinuteUi(start + m * 60, MinuteState.NOT_WORN, 0, null)
                else -> MinuteUi(start + m * 60, MinuteState.IDLE, if (rnd.nextDouble() < 0.08) rnd.nextInt(30) else 0, 62 + rnd.nextInt(12))
            }
        }
        val track = (0 until 45 * 60 step 10).map { HrPoint(start + 18 * 3600 + it, (110 + 30 * sin(it / 400.0) + rnd.nextInt(8)).toInt()) }
        return ActivityDayUi(
            date, mins, mins.sumOf { it.steps }, mins.count { it.state == MinuteState.ACTIVE }, 420, 1395, 51, 71, 162,
            SleepNightUi(start, start + 7 * 3600, 84, 248, 92, 18),
            listOf(WorkoutUi(start + 18 * 3600, 2700, "Outdoor run", 148, 172, 412, 5980, 5210, track,
                (0..180).map { i -> val a = i / 180.0 * 2 * Math.PI; RoutePoint(i * 15, 12.9716 + 0.006 * Math.sin(a) + 0.0015 * Math.sin(3 * a), 77.5946 + 0.009 * Math.cos(a), 900.0 + 6 * Math.sin(2 * a)) })),
        )
    }

    private fun demoGps() = GpsUi(
        fix = false, satsUsed = 0,
        sats = listOf(SatUi("05", "GPS", 62.0, 40.0, 41.0), SatUi("12", "GPS", 28.0, 300.0, 33.0), SatUi("18", "GPS", 15.0, 120.0, null),
            SatUi("24", "GPS", 45.0, 210.0, 22.0), SatUi("65", "GLONASS", 35.0, 80.0, 19.0), SatUi("72", "GLONASS", 10.0, 250.0, null)),
        lat = null, lon = null, altM = null, speedMps = null, hdop = 2.2, utc = null,
    )

    private fun demoSettings() = listOf(
        WatchSettingUi("hr_interval", "All-day heart rate", "enum", 1, listOf(0 to "Off", 1 to "Every minute", 5 to "Every 5 minutes", 10 to "Every 10 minutes", 30 to "Every 30 minutes"), null, null),
        WatchSettingUi("hr_sleep_detection", "Heart rate during sleep", "bool", true, emptyList(), null, null),
        WatchSettingUi("stress_monitoring", "All-day stress", "bool", true, emptyList(), null, null),
        WatchSettingUi("hr_alert", "High heart rate alert", "bool", false, emptyList(), null, null),
        WatchSettingUi("hr_alert_threshold", "High heart rate threshold", "int", 150, emptyList(), 100, 200),
        WatchSettingUi("lift_wrist", "Lift wrist to wake", "bool", true, emptyList(), null, null),
        WatchSettingUi("inactivity_warnings", "Move reminders", "bool", false, emptyList(), null, null),
        WatchSettingUi("time_24h", "24-hour clock", "bool", true, emptyList(), null, null),
    )
}
