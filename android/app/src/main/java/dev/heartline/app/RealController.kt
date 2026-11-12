package dev.heartline.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.core.content.FileProvider
import dev.heartline.analytics.*
import dev.heartline.ble.*
import dev.heartline.data.*
import dev.heartline.data.model.Theme
import dev.heartline.data.model.Units
import dev.heartline.data.model.Sex
import dev.heartline.data.model.Watch
import dev.heartline.data.model.NotificationForwarding
import dev.heartline.protocol.*
import dev.heartline.service.WatchService
import dev.heartline.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*

class RealController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: HeartlineDatabase,
    private val settings: AppSettings,
    private val keys: KeyStore,
) : HeartlineController {
    override val connection = MutableStateFlow(ConnectionUi())
    override val battery = MutableStateFlow<BatteryUi?>(null)
    override val device = MutableStateFlow<DeviceUi?>(null)
    override val liveHr = MutableStateFlow(LiveHrUi())
    override val steps = MutableStateFlow<StepsUi?>(null)
    override val sync = MutableStateFlow(SyncUi())
    override val today = MutableStateFlow<TodayUi?>(null)
    override val trends = MutableStateFlow<TrendsUi?>(null)
    override val checks = MutableStateFlow<List<CheckUi>>(emptyList())
    override val checkRun = MutableStateFlow<CheckRunUi?>(null)
    override val sensors = MutableStateFlow(SensorsUi())
    override val recordings = MutableStateFlow<List<RecordingUi>>(emptyList())
    override val watchSettings = MutableStateFlow<List<WatchSettingUi>>(emptyList())
    override val events = MutableStateFlow<List<EventUi>>(emptyList())
    override val prefs = MutableStateFlow(PrefsUi())
    private val service = MutableStateFlow<WatchService?>(null)
    /** Heart checks, captures and watch workouts that were a friend's, and the watch workouts deleted here. */
    override val friends: StateFlow<Friends> = db.meta.observe(Friends.KEY).map { Friends.parse(it) }.stateIn(scope, SharingStarted.Eagerly, Friends())
    private var bound = false
    private var checkJob: Job? = null
    private var buffers = SensorBuffers()
    private val zone = ZoneId.systemDefault()
    var requestCallPermission: (() -> Unit)? = null
    private var suppressAutomaticConnection = false
    private var startupHandled = false
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service.value = (binder as WatchService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service.value = null; connection.value = ConnectionUi() }
        override fun onBindingDied(name: ComponentName?) { service.value = null; if (bound) context.unbindService(this); bound = false }
    }
    init {
        // Saved heart checks from an older analysis are worked out again from their recordings, once.
        scope.launch(Dispatchers.IO) { runCatching { reanalyseChecks() }.onFailure { db.events.add("warn", "Re-analysing heart checks failed: ${it.message}") } }
        scope.launch { settings.rawSensorDefaultSelection.collect { selected ->
            if (!sensors.value.streaming) sensors.update { it.copy(selected = selected.mapNotNull { sensorFromName(it.name) }.toSet()) }
        } }
        scope.launch {
            combine(settings.theme, settings.units, settings.profile, settings.notificationForwarding, settings.onboardingDone) { theme, units, p, f, onboarded ->
                PrefsUi(ThemeMode.valueOf(theme.name), units == Units.METRIC, ProfileUi(p.age, p.sex?.name?.lowercase(), p.weightKg, p.heightCm, p.maxHr, p.restingHr),
                    ForwardingUi(f.enabled, f.forwardCalls, f.allowedPackages, f.quietHoursStart?.div(60), f.quietHoursEnd?.div(60)), onboarded)
            }.combine(settings.watch) { p, w -> p.copy(watchMac = w.mac, hasKey = keys.load() != null) }.collect { prefs.value = it }
        }
        scope.launch { settings.lastWrittenWatchSettings.collect { values ->
            watchSettings.value = SETTINGS.map { s -> WatchSettingUi(s.key, s.label, s.type, (values[s.key]?.let { settingValue(s.key, it) } ?: dev.heartline.protocol.settingDefault(s.key)), s.options.map { it.value to it.label }, s.min, s.max,
                dev.heartline.protocol.settingSection(s)) }
        } }
        scope.launch { runCatching { importDroppedGymLogs() } }
        scope.launch { db.events.observeRecent(200).collect { list -> events.value = list.map { EventUi(it.ts.toLong(), it.level == "warn", it.level == "error", it.msg) } } }
        scope.launch { combine(db.meta.observe("activity_last_sync"), db.meta.observe("health_last_sync")) { a, h -> a to h }.collect { (a, h) ->
            sync.update { it.copy(lastActivity = a?.toDoubleOrNull()?.toLong(), lastHealth = h?.toDoubleOrNull()?.toLong()) } } }
        scope.launch { db.checks.observeRange().combine(friends) { list, f -> list to f }.collect { (list, f) -> checks.value = list.map { c ->
            val duration = c.json?.let { runCatching { JSONObject(it).optDouble("duration_s").toInt() }.getOrNull() } ?: 0
            CheckUi(c.start.toString(), c.start.toLong(), duration, c.hr, c.rmssd, rhythmStatus(c.rhythm), friend = f.isFriend(FriendMark.Kind.CHECK, c.start.toString()))
        } } }
        scope.launch { db.recordings.observeAll().collect { list -> recordings.value = list.map { r ->
            val names = r.sensors?.let { runCatching { JSONArray(it).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() }.orEmpty()
            RecordingUi(r.file, r.start?.toLong() ?: 0, r.durationS ?: 0.0, names.mapNotNull { name -> sensorFromName(name) }.toSet(), r.hasAudio)
        } } }
        scope.launch { service.collectLatest { s -> if (s != null) observeWatch(s.watch) } }
        scope.launch { observeDaily() }
    }
    private suspend fun observeWatch(w: WatchConnection) = coroutineScope {
        launch { w.connection.collect { c ->
            connection.value = ConnectionUi(LinkState.valueOf(c.state.name), c.since, c.error)
            if (c.state != ConnectionState.CONNECTED && checkJob?.isActive == true) {
                checkJob?.cancel(); checkRun.update { it?.copy(analysing = false, error = "The watch disconnected during the check") }
            }
        } }
        launch { w.battery.collect { b -> battery.value = b?.let { BatteryUi(it.level, it.charging, chargedLabel(it.lastChargeB ?: it.lastChargeA)) } } }
        launch { w.deviceInfo.collect { i -> device.value = DeviceUi("Amazfit GTR 2e", settings.watch.value.mac.orEmpty(), i["software"], i["hardware"], i["serial"]) } }
        launch { w.heartRate.collect { h -> liveHr.update { it.copy(streaming = h.streaming, bpm = h.bpm, ts = h.ts) } } }
        launch { db.heartRate.observeSince(System.currentTimeMillis() / 1000.0 - 86400).combine(friends) { list, f -> list.filter { !f.covers(it.ts.toLong()) } }
            .collect { list -> liveHr.update { it.copy(recent = list.takeLast(600).map { HrPoint(it.ts.toLong(), it.bpm) }) } } }
        launch { w.steps.collect { s -> steps.value = s?.let { StepsUi(it.steps, it.distanceM, it.calories) } } }
        launch { w.sync.collect { state -> sync.update { it.copy(running = state.running, message = state.message) } } }
        launch { w.raw.collect { r ->
            if (r.file?.name != sensors.value.file) buffers = SensorBuffers()
            sensors.update { it.copy(streaming = r.streaming,
                selected = if (r.file == null) settings.rawSensorDefaultSelection.value.mapNotNull { sensorFromName(it.name) }.toSet() else r.sensors.map { Sensor.valueOf(it.name) }.toSet(), file = r.file?.name) }
        } }
        launch { w.rawUpdates.collect { buffers.feed(it) } }
        launch { while (isActive) { delay(200); sensors.value = buffers.snapshot(sensors.value) } }
        launch { w.gps.collect { g -> sensors.update { s -> s.copy(gps = g?.let {
            GpsUi(it.fix, it.satsUsed, it.satellites.filter { sat -> sat.elev != null && sat.az != null }.map { sat -> SatUi(sat.id, sat.system, sat.elev!!, sat.az!!, sat.snr) },
                it.lat, it.lon, it.altM, it.speedMps, it.hdop, it.utc)
        }) } } }
    }
    private suspend fun observeDaily() {
        val from = LocalDate.now(zone).minusDays(40).atStartOfDay(zone).toEpochSecond().toDouble()
        // The clock only moves "today" along; new data wakes it at once. A sync writes in many small batches, so the
        // 40-day sums are worked out at most every 5 s while one runs, not once per batch.
        val tick = flow { while (currentCoroutineContext().isActive) { emit(ZonedDateTime.now(zone)); delay(5 * 60_000L) } }
        combine(db.activity.observeRange(from, Double.MAX_VALUE).throttleLatest(5_000), db.health.observeByType(from).throttleLatest(5_000),
            db.insights.observeAll(), settings.profile,
            tick.combine(friends) { now, f -> now to f }) { rows, allHealth, allInsights, p, (now, f) ->
            val previousKind = db.activity.prevKind(from)
            val health = allHealth.mapValues { (_, list) -> list.filter { !f.covers(it.ts) } }
            val insights = allInsights.filter { i -> i.start?.let { !f.covers(it.toLong()) } ?: true }
            val tp = System.nanoTime()
            withContext(Dispatchers.Default) {
                val minutes = analyticsMinutes(friendFree(rows, f, previousKind), previousKind)
                // Skin temperature only: readings off the wrist, settling or spiking are left out of the daily values.
                val hrMinutes = minutes.filter { it.hr != null && it.hr in 30..220 }.map { it.ts / 60 }.toHashSet()
                val skin = skinTemperatures(health["temperature"].orEmpty().mapNotNull { h -> h.value?.let { TempReading(h.ts.toLong(), it, sensor2(h.extra)) } }, hrMinutes)
                // Overnight SpO2 without the readings that are pressure on the sensor (cleanSpo2).
                val spo2Aside = cleanSleepSpo2(health["spo2_sleep"].orEmpty().mapNotNull { h -> h.value?.let { DayHealthValue(h.type, h.ts, it, h.extra) } }, minutes)
                    .second.map { it.ts }.toHashSet()
                val values = health.filterKeys { it != "temperature" }.values.flatten().filter { !(it.type == "spo2_sleep" && it.ts in spo2Aside) }
                    .mapNotNull { it.value?.let { value -> HealthValue(it.type, it.ts, value) } } +
                    skin.map { HealthValue("temperature", it.ts, it.value) }
                val summaries = insights.mapNotNull { i -> i.start?.let { CheckSummary(it, i.rmssd, i.hr, i.quality ?: 0.0, i.usableS ?: 0.0, i.rhythm) } }
                val profile = Profile(p.age, p.sex?.name?.lowercase(), p.weightKg, p.heightCm, p.maxHr, p.restingHr)
                val watchRows = db.health.workouts(from).filter { !f.dropsWorkout(it.start) }
                val log = gymLogFromJson(db.meta.get(GYM_LOG_KEY))
                val efforts = watchRows.mapNotNull { w -> runCatching { org.json.JSONObject(w.data) }.getOrNull()?.let { j ->
                    WorkoutEffort(j.optLong("start", w.start), j.optString("type"), j.optInt("active_s", j.optInt("duration_s")),
                        j.optInt("distance_m").takeIf { it > 0 }, j.optInt("avg_hr").takeIf { it > 0 }) to (j.optLong("start", w.start) to j.optLong("end", w.start))
                } }
                val result = computeDaily(minutes, values, summaries, profile, 28, now, workouts = efforts.map { it.second } + loggedSpans(log))
                // Workout captures score their load against the same resting heart rate, so it is kept for them.
                result.trends.hrRestUsed?.let { kotlin.math.round(it).toInt().toString() }?.takeIf { it != db.meta.get(dev.heartline.service.HR_REST_KEY) }
                    ?.let { db.meta.set(dev.heartline.service.HR_REST_KEY, it) }
                // VO2max: outdoor runs and walks when there are any, else the heart-rate estimate from awake resting heart rate.
                val awakeRest = result.days.takeLast(7).mapNotNull { it.awakeRestingHr }.takeIf { it.isNotEmpty() }?.average()
                val vo2 = vo2max(efforts.map { it.first }, now.toEpochSecond(), awakeRest, maxHeartRate(profile), profile.sex,
                    treadmill = treadmillEfforts(log, watchRefs(watchRows), zone))
                perfLog("observeDaily", tp)
                result.todayUi(now.toLocalDate()) to result.trendsUi(healthByDay(values, zone = zone)).copy(vo2max = vo2.value, fitnessAge = vo2.fitnessAge, hrRest = result.trends.hrRestUsed,
                    vo2FromWorkouts = vo2.fromWorkouts, vo2StandIn = vo2.fromHeartRates, vo2Used = vo2.used,
                    // Runs, walks and treadmill sessions that didn't qualify say why; other workouts (gym and so on) are just counted.
                    vo2Skipped = vo2.skipped.filter { it.first.type in setOf("Outdoor running", "Walking", "Hiking", "Treadmill", "Treadmill (logged)") }.map { (w, why) ->
                        "${w.type.lowercase()} on ${java.time.Instant.ofEpochSecond(w.start).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}: $why" } +
                        vo2.skipped.count { it.first.type !in setOf("Outdoor running", "Walking", "Hiking", "Treadmill", "Treadmill (logged)") }.takeIf { it > 0 }
                            ?.let { listOf("$it gym and other workouts, which have no pace to measure") }.orEmpty())
            }
        }.collect { (t, tr) -> today.value = t; trends.value = tr }
    }
    private val healthDays = java.util.concurrent.ConcurrentHashMap<LocalDate, Flow<HealthDayUi>>()
    private val activityDays = java.util.concurrent.ConcurrentHashMap<LocalDate, Flow<ActivityDayUi>>()
    /** What a day's page needs that is slow to work out: kept until the day's own data or the profile changes. */
    private class DayBase(val day: ActivityDayUi, val values: List<DayHealthValue>, val spo2Aside: List<DayHealthValue>, val restingHr: Int?,
        val awakeRestingHr: Int?, val caloriesActive: Int?, val maxHr: Int, val stride: Double)

    /**
     * One flow per day, shared by every screen showing it (Today and the Heart page asked for today three times over),
     * kept for 15 s after the last one closes so going back and forth doesn't start again.
     */
    override fun healthDay(date: LocalDate): Flow<HealthDayUi> = healthDays.getOrPut(date) {
        val from = date.atStartOfDay(zone).toEpochSecond(); val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val base = combine(activityDay(date), db.health.observeByType(from.toDouble(), to - 1.0).throttleLatest(2_000),
            settings.profile.combine(friends) { p, f -> p to f }) { day, health, (p, f) ->
            val tp = System.nanoTime()
            try {
                val minutes = analyticsMinutes(friendFree(db.activity.range(from - 86_400.0, to - 1.0), f))
                val (values, spo2Aside) = cleanSleepSpo2(health.values.flatten().filter { !f.covers(it.ts) }.mapNotNull { h -> h.value?.let { DayHealthValue(h.type, h.ts, it, h.extra) } }, minutes)
                val daily = computeDaily(minutes,
                    values.map { HealthValue(it.type, it.ts, it.value) }, emptyList(),
                    Profile(p.age, p.sex?.name?.lowercase(), p.weightKg, p.heightCm, p.maxHr, p.restingHr), 2, date.atTime(23, 59).atZone(zone))
                val d = daily.days.lastOrNull { it.date == date.toString() }
                DayBase(day, values, spo2Aside, (d?.restingHr ?: p.restingHr?.toDouble())?.let { kotlin.math.round(it).toInt() },
                    d?.awakeRestingHr?.let { kotlin.math.round(it).toInt() }, d?.caloriesActive, profileMaxHr(p.maxHr, p.age) ?: 190,
                    p.heightCm?.takeIf { it > 0 }?.let { it / 100 * 0.415 } ?: 0.72)
            } finally { perfLog("healthDay $date base", tp) }
        }.flowOn(Dispatchers.Default)
        val isToday = date == LocalDate.now(zone)
        combine(base, db.heartRate.observeRange(from.toDouble(), to - 1.0).throttleLatest(2_000), friends,
            if (isToday) steps.map { it?.distanceM }.distinctUntilChanged() else flowOf(null)) { b, allLive, f, distance ->
            val live = allLive.filter { !f.covers(it.ts.toLong()) }
            buildHealthDay(date, from, b.day, live.map { HrPoint(it.ts.toLong(), it.bpm) }, b.values, b.maxHr, b.restingHr, b.caloriesActive, b.stride, distance)
                .copy(awakeRestingHr = b.awakeRestingHr, naps = b.day.naps, spo2SetAside = b.spo2Aside.map { SeriesPoint(it.ts.toDouble(), it.value) })
        }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(15_000), 1)
    }
    /** Every watch workout's stored details, parsed once for all days and again only when a new transfer arrives. */
    private val workoutDetails by lazy {
        db.health.observeRawFetches(-Double.MAX_VALUE, types = setOf("workouts", "workout_details")).throttleLatest(2_000)
            .distinctUntilChangedBy { l -> Triple(l.size, l.lastOrNull()?.ts, l.firstOrNull()?.ts) }
            .map { storedWorkoutDetails(it) }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(30_000), 1)
    }
    override fun activityDay(date: LocalDate): Flow<ActivityDayUi> = activityDays.getOrPut(date) {
        val from = date.atStartOfDay(zone).toEpochSecond().toDouble()
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble() - 1
        // The day's active minutes come from the trends once they're worked out; only that number is watched.
        val activeFromTrends = trends.map { t -> t?.series?.firstOrNull { it.key == "active" }?.points?.firstOrNull { it.date == date }?.value?.toInt() }.distinctUntilChanged()
        combine(db.activity.observeRange(from, to).throttleLatest(2_000), db.health.observeWorkouts(from, to), activeFromTrends,
            workoutDetails, settings.profile.combine(friends) { p, f -> p to f }) { rows, allWorkouts, activeTrend, details, (profile, f) ->
            val tp = System.nanoTime()
            val prevKind = db.activity.prevKind(from)
            val minutes = mappedMinutes(friendFree(rows, f, prevKind), prevKind)
            val hr = minutes.mapNotNull { it.hr }
            val sleepFrom = from - 86400
            val allRows = friendFree(db.activity.range(sleepFrom, to), f)
            // Deleted workouts go; a friend's stays on the day's list, marked, and out of the totals.
            val workouts = allWorkouts.filter { it.start !in f.hiddenWorkouts }
            val daily = computeDaily(analyticsMinutes(allRows), emptyList(), emptyList(), Profile(), 2, date.atTime(23, 59).atZone(zone))
            // Workout load on the daily scale wants the same resting heart rate the daily figures use.
            val dailyRest = dev.heartline.service.captureRest(db)
            val active = activeTrend
                ?: daily.days.lastOrNull { it.date == date.toString() }?.activeMinutes ?: 0
            ActivityDayUi(date, minutes, minutes.sumOf { it.steps }, active,
                minutes.count { it.state in setOf(MinuteState.SLEEP_DEEP, MinuteState.SLEEP_LIGHT, MinuteState.SLEEP_REM) },
                minutes.count { it.state !in setOf(MinuteState.NOT_WORN, MinuteState.CHARGING) }, hr.minOrNull(), hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), hr.maxOrNull(),
                daily.days.lastOrNull { it.date == date.toString() }?.sleepUi(), workouts.map { workout ->
                    workout.ui().withWorkoutDetail(details[workout.start], ProfileUi(age = profile.age, maxHr = profile.maxHr,
                        sex = profile.sex?.name?.lowercase(), restingHr = profile.restingHr ?: dailyRest)).copy(friend = f.covers(workout.start))
                }, naps = daily.days.lastOrNull { it.date == date.toString() }?.naps.orEmpty()).also { perfLog("activityDay $date", tp) }
        }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(15_000), 1)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override val agps: StateFlow<dev.heartline.service.AgpsStatus> = service.flatMapLatest { it?.agps?.status ?: flowOf(dev.heartline.service.AgpsStatus()) }
        .stateIn(scope, SharingStarted.Eagerly, dev.heartline.service.AgpsStatus())
    override suspend fun zeppSignIn(email: String, password: String) {
        require(email.isNotBlank() && password.isNotEmpty()) { "Enter the email and password of your Zepp account" }
        ensureService().agps.signIn(email, password)
    }
    override fun zeppSignOut() = launchAction { ensureService().agps.signOut() }
    override fun updateAgps() = launchAction { ensureService().agps.update() }
    override fun setAgpsAuto(on: Boolean) = launchAction { ensureService().agps.setAuto(on) }

    override suspend fun checkDetail(id: String): SessionUi? = withContext(Dispatchers.IO) {
        val c = db.checks.list().firstOrNull { it.start.toString() == id } ?: return@withContext null
        c.json?.let { sessionFromJson(it, id) } ?: c.file?.let { file ->
            val path = recordingFile(file); if (path.isFile) analyzeSession(readRecording(path), file).sessionUi(id) else null
        }
    }
    /** The watch service, started without the app's screen, hands itself over (see WatchService.onCreate). */
    fun attach(s: WatchService) { if (service.value == null) service.value = s }
    fun detach(s: WatchService) { if (service.value === s) { service.value = null; connection.value = ConnectionUi() } }

    fun startFromActivity(idle: Boolean = false) {
        if (startupHandled) return
        suppressAutomaticConnection = idle
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (idle || keys.load() != null) { startupHandled = true; launchAction { ensureService(connect = !idle) } }
    }
    private suspend fun ensureService(connect: Boolean = false): WatchService {
        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Allow Bluetooth access first" }
        val intent = Intent(context, WatchService::class.java).apply { if (!connect) action = WatchService.IDLE }
        context.startForegroundService(intent)
        if (!bound) { bound = context.bindService(Intent(context, WatchService::class.java), binding, Context.BIND_AUTO_CREATE); check(bound) { "Unable to bind watch service" } }
        return withTimeout(10_000) { service.filterNotNull().first() }
    }
    private fun launchAction(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("error", e.message ?: e.javaClass.simpleName) } }
    }
    private fun action(block: suspend (WatchConnection) -> Unit) = launchAction { ensureService().command(block) }
    override fun connect() {
        suppressAutomaticConnection = false; startupHandled = true
        launchAction { check(keys.load() != null && settings.watch.value.mac != null) { "Save the watch key and address first" }; ensureService(connect = true) }
    }
    override fun disconnect() { cancelCheck(); action { it.disconnect() } }
    override fun setLiveHr(on: Boolean) { if (!captureTask.running || on) action { if (!captureTask.running || on) it.setLiveHr(on) } }
    override fun setRawSensors(sensors: Set<Sensor>, streaming: Boolean) {
        if (checkJob?.isActive == true || captureTask.running) return
        val selected = sensors.map { RawSensor.valueOf(it.name) }.toSet()
        settings.setRawSensorDefaultSelection(sensors.map { dataSensor(it) }.toSet())
        this.sensors.update { it.copy(selected = sensors) }
        action { if (!captureTask.running) { if (streaming) it.startRaw(selected) else it.stopRaw() } }
    }
    override fun startCheck(seconds: Int, forFriend: Boolean) {
        if (checkJob?.isActive == true || captureTask.running) return
        require(seconds in 10..600)
        checkRun.value = CheckRunUi(seconds, System.currentTimeMillis() / 1000, false, null, null)
        checkJob = scope.launch {
            var s: WatchService? = null
            var ownsRecording = false
            try {
                s = ensureService()
                check(!captureTask.running) { "Stop workout capture before starting a heart check" }
                check(!s.watch.raw.value.streaming) { "Stop raw recording before starting a check" }
                ownsRecording = true
                val file = s.command { it.startRaw(setOf(RawSensor.PPG, RawSensor.ACCEL)) }
                checkRun.update { it?.copy(startedAt = System.currentTimeMillis() / 1000) }
                delay(seconds * 1000L)
                s.command { it.stopRaw() }
                checkRun.update { it?.copy(analysing = true) }
                val result = withContext(Dispatchers.Default) { analyzeSession(readRecording(file), file.name) }
                val json = result.json()
                db.checks.upsert(dev.heartline.data.model.Check(result.start, file.name, result.hrBpm, result.hrv.rmssdMs, result.hrv.sdnnMs, result.rhythm.status, json))
                db.recordings.setAnalysis(file.name, json)
                db.insights.save(dev.heartline.data.model.InsightSession(file.name, file.lastModified() / 1000.0, result.start, result.durationS, result.signalQuality,
                    result.usableS, result.hrBpm, result.hrv.rmssdMs, result.hrv.sdnnMs, result.rhythm.status, result.respiratoryRate, json))
                if (forFriend) FriendStore.update(db) { it.with(FriendMark(FriendMark.Kind.CHECK, result.start.toString(), result.start.toLong(),
                    (result.start + result.durationS).toLong() + 1), true) }
                checkRun.update { it?.copy(analysing = false, result = result.sessionUi(result.start.toString())) }
            } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); checkRun.update { it?.copy(analysing = false, error = e.message) }; db.events.add("error", "Heart check failed: ${e.message}") }
            finally { withContext(NonCancellable) { if (ownsRecording && s?.watch?.raw?.value?.streaming == true) runCatching { s.command { it.stopRaw() } } } }
        }
    }
    override fun setFriend(kind: FriendMark.Kind, ref: String, from: Long, to: Long, friend: Boolean) = launchAction {
        FriendStore.update(db) { it.with(FriendMark(kind, ref, from, to), friend) }
        db.events.add("info", (if (friend) "Marked as a friend's: " else "Marked as yours again: ") + kind.name.lowercase() + " from " +
            java.time.Instant.ofEpochSecond(from).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16))
    }
    /** Deletes a heart check: its result, its analysis and its recording. */
    override fun deleteCheck(id: String) = launchAction {
        val c = db.checks.list().firstOrNull { it.start.toString() == id } ?: return@launchAction
        db.checks.delete(c.start)
        c.file?.let { name -> db.insights.delete(name); deleteRecordingFiles(name) }
        FriendStore.update(db) { it.without(FriendMark.Kind.CHECK, id) }
        db.events.add("info", "Deleted the heart check from ${dateLabel(c.start.toLong())}")
    }
    /** Hides a watch workout: the watch keeps its own copy and would send it again, so it is remembered as deleted. */
    override fun deleteWatchWorkout(start: Long) = launchAction {
        FriendStore.update(db) { f -> f.without(FriendMark.Kind.WORKOUT, start.toString()).copy(hiddenWorkouts = f.hiddenWorkouts + start) }
        db.events.add("info", "Deleted the watch workout from ${dateLabel(start)}")
    }
    /** Deletes a raw recording that isn't part of a workout capture (delete the capture for those). */
    override fun deleteRecording(file: String) = launchAction {
        val name = File(file).name
        val inCapture = db.meta.all().filterKeys { it.startsWith(dev.heartline.service.CAPTURE_SESSION) }.values.any { it?.contains("\"$name\"") == true }
        check(!inCapture) { "That recording is part of a workout capture: delete the capture instead" }
        db.checks.list().firstOrNull { it.file == name }?.let { c -> db.checks.delete(c.start); db.insights.delete(name); FriendStore.update(db) { it.without(FriendMark.Kind.CHECK, c.start.toString()) } }
        deleteRecordingFiles(name)
        db.events.add("info", "Deleted the recording $name")
    }
    private suspend fun deleteRecordingFiles(name: String) {
        val csv = recordingFile(name)
        withContext(Dispatchers.IO) { csv.delete(); File(csv.parentFile, csv.nameWithoutExtension + ".wav").delete() }
        db.recordings.delete(name)
    }
    private fun dateLabel(ts: Long) = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16)
    override fun cancelCheck() { checkJob?.cancel(); checkJob = null; checkRun.value = null }
    override fun vibrate() = action { it.vibrate() }
    override fun sendNotification(title: String, body: String, kind: NotifyKind, asPackage: String) = action {
        if (asPackage.isBlank() || kind == NotifyKind.CALL) { it.notify(title, body, NotificationKind.valueOf(kind.name)); return@action }
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(asPackage, 0)).toString() }
            .getOrDefault(SAMPLE_APPS.firstOrNull { a -> a.first == asPackage }?.second ?: asPackage)
        val sms = dev.heartline.protocol.isSmsApp(asPackage)
        it.notify(title, body, if (sms) NotificationKind.GENERIC else NotificationKind.MESSAGE, label, dev.heartline.protocol.watchIconFor(asPackage))
    }
    /** Battery first (its charging flag), then activity and health. The watch sometimes drops the link mid-sync; then
     * wait for the automatic reconnect and run it once more. */
    override fun syncNow() = launchAction {
        for (attempt in 1..2) {
            try { ensureService().command { it.readBattery(); it.syncActivity(); it.syncHealth() }; return@launchAction }
            catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
                if (attempt == 2) throw e
                db.events.add("warn", "Sync interrupted (${e.message}); retrying once the watch reconnects")
                delay(2000)
                withTimeoutOrNull(60_000) { connection.first { it.state == LinkState.CONNECTED } } ?: throw e
            }
        }
    }
    override fun refreshTemperature() = action { it.syncQuick() }
    override fun setWatchClock() = action { it.syncTime() }
    override fun applyWatchSetting(key: String, value: Any) = action { it.applySetting(key, value) }
    override fun saveProfile(profile: ProfileUi) { settings.setProfile(dev.heartline.data.model.Profile(profile.age, profile.sex?.uppercase()?.let { Sex.valueOf(it) }, profile.weightKg, profile.heightCm, profile.maxHr, profile.restingHr)) }
    override fun setTheme(mode: ThemeMode) = settings.setTheme(Theme.valueOf(mode.name))
    override fun setMetric(metric: Boolean) = settings.setUnits(if (metric) Units.METRIC else Units.IMPERIAL)
    override fun setForwarding(update: ForwardingUi) {
        settings.setNotificationForwarding(NotificationForwarding(update.enabled, update.allowed, update.quietStart?.times(60), update.quietEnd?.times(60), update.calls))
        if (update.enabled && update.calls && listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
                .any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestCallPermission?.invoke()
        service.value?.refreshCalls()
    }
    fun callPermissionResult(granted: Boolean) {
        if (!granted) settings.setNotificationForwarding(settings.notificationForwarding.value.copy(forwardCalls = false))
        service.value?.refreshCalls()
    }
    override fun saveWatchKey(mac: String, keyHex: String): String? {
        val address = mac.trim().uppercase(); val hex = keyHex.trim().removePrefix("0x")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "Enter a Bluetooth address such as C1:2A:3B:4C:5D:6E"
        if (!KeyStore.validateHex(hex)) return "The key must contain 32 hex characters"
        return try {
            val key = KeyStore.parseHex(hex); try { keys.save(key) } finally { key.fill(0) }
            settings.setWatch(Watch(address, "Amazfit GTR 2e")); prefs.update { it.copy(hasKey = true, watchMac = address) }; null
        } catch (e: Exception) { e.message ?: "Unable to store the watch key" }
    }
    override fun finishOnboarding() { settings.setOnboardingDone(true); if (!suppressAutomaticConnection) connect() }
    override val gymLog: StateFlow<List<LoggedSet>> = db.meta.observe(GYM_LOG_KEY).map { gymLogFromJson(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val gymImport = MutableStateFlow<String?>(null)
    override fun importGymLog(uris: List<Uri>) = launchAction {
        withContext(Dispatchers.IO) {
            importGymFiles(uris.map { u -> (u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file") to
                { context.contentResolver.openInputStream(u).use { checkNotNull(it).bufferedReader().readText() } } })
        }
    }
    /** Reads, merges and stores gym log files; returns the names read cleanly. */
    private suspend fun importGymFiles(files: List<Pair<String, () -> String>>): Set<String> {
        var all = gymLogFromJson(db.meta.get(GYM_LOG_KEY)); var read = 0; var added = 0; val failed = ArrayList<String>(); val ok = HashSet<String>()
        for ((name, text) in files) {
            val sets = runCatching { parseGymLog(text(), settings.profile.value.weightKg) }.getOrElse { failed.add("$name (${it.message})"); continue }
            if (sets.isEmpty()) { failed.add("$name (no sets found)"); continue }
            val (merged, n) = mergeGymLog(all, sets); all = merged; read += sets.size; added += n; ok.add(name)
        }
        db.meta.set(GYM_LOG_KEY, gymLogJson(all))
        val msg = "Read $read sets from ${ok.size} file${if (ok.size == 1) "" else "s"}: $added new, ${read - added} already here." +
            (if (failed.isNotEmpty()) " Couldn't read ${failed.joinToString()}." else "")
        gymImport.value = msg
        db.events.add("info", "Gym log import: $msg")
        return ok
    }
    /**
     * Log files dropped in the app's own folder (Android/data/dev.heartline/files/import, reachable over USB) are
     * imported at start and then removed.
     */
    private suspend fun importDroppedGymLogs() = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("import") ?: return@withContext
        val found = dir.listFiles { f -> f.isFile && (f.name.endsWith(".csv", true) || f.name.endsWith(".json", true)) }.orEmpty().sortedBy { it.name }
        if (found.isEmpty()) return@withContext
        val ok = importGymFiles(found.map { f -> f.name to { f.readText() } })
        found.filter { it.name in ok }.forEach { it.delete() }
    }
    override fun saveTreadmill(workoutStart: Long, workoutEnd: Long, speedKmh: Double, inclinePct: Double) = launchAction {
        val all = gymLogFromJson(db.meta.get(GYM_LOG_KEY))
        val mine = setsForWorkout(all, workoutStart, workoutEnd, "Treadmill", zone).firstOrNull { it.cardio }
        val updated = if (mine != null) all.map { if (it.key == mine.key) it.copy(speedKmh = speedKmh, inclinePct = inclinePct) else it }
        else all + LoggedSet(sessionDate(workoutStart, zone).toString(), "Typed in", "Treadmill", "cardio", 1, "working", "completed",
            workoutStart, workoutEnd, speedKmh = speedKmh, inclinePct = inclinePct, durationS = (workoutEnd - workoutStart).toDouble())
        db.meta.set(GYM_LOG_KEY, gymLogJson(updated))
        db.events.add("info", "Treadmill settings saved: %.1f km/h, %.0f%% incline".format(speedKmh, inclinePct))
    }
    override fun importPcData(uri: Uri) = launchAction {
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("pc-import-", ".db", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri).use { input -> checkNotNull(input).use { source -> temp.outputStream().use { source.copyTo(it) } } }
                val report = PcImporter(db).import(temp); db.events.add("info", "Imported ${report.added} records; updated ${report.updated}")
            } finally { temp.delete() }
        }
    }
    override fun exportData(uri: Uri) = launchAction {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt").use { out -> Exporter.exportCsvZip(db, checkNotNull(out), -Double.MAX_VALUE, Double.MAX_VALUE, SECRET_META) }
            db.events.add("info", "Exported data")
        }
    }
    private suspend fun reanalyseChecks() {
        var done = 0
        for (c in db.checks.list()) {
            val version = c.json?.let { runCatching { org.json.JSONObject(it).optInt("analysis_version", 1) }.getOrDefault(1) } ?: 1
            if (version >= ANALYSIS_VERSION) continue
            val file = c.file ?: continue
            val path = runCatching { recordingFile(file) }.getOrNull()?.takeIf { it.isFile } ?: continue
            val result = analyzeSession(readRecording(path), file)
            val json = result.json()
            db.checks.upsert(dev.heartline.data.model.Check(result.start, file, result.hrBpm, result.hrv.rmssdMs, result.hrv.sdnnMs, result.rhythm.status, json))
            db.recordings.setAnalysis(file, json)
            db.insights.save(dev.heartline.data.model.InsightSession(file, path.lastModified() / 1000.0, result.start, result.durationS, result.signalQuality,
                result.usableS, result.hrBpm, result.hrv.rmssdMs, result.hrv.sdnnMs, result.rhythm.status, result.respiratoryRate, json))
            done++
        }
        if (done > 0) db.events.add("info", "Re-analysed $done heart check${if (done > 1) "s" else ""} with the improved breathing and beat analysis")
    }
    private fun recordingFile(name: String): File {
        val root = File(context.filesDir, "raw").canonicalFile
        val file = File(root, File(name).name).canonicalFile
        require(file.parentFile == root) { "Invalid recording path" }
        return file
    }
    override fun recordingAudio(file: String): Uri? {
        val csv = recordingFile(file)
        val wav = File(csv.parentFile, csv.nameWithoutExtension + ".wav")
        return if (wav.isFile) FileProvider.getUriForFile(context, "${context.packageName}.recordings", wav) else null
    }
    // ---- Weather on the watch, find phone, music controls ----
    private val phone = PhoneLinkController(context, scope, db, service) { ensureService() }
    override val weather = phone.weather
    private val alexaLock = kotlinx.coroutines.sync.Mutex()
    override val alexaSettings: StateFlow<dev.heartline.service.AlexaSettings> = db.meta.observe(dev.heartline.service.AlexaSettings.KEY)
        .map { dev.heartline.service.AlexaSettings.parse(it) }.stateIn(scope, SharingStarted.Eagerly, dev.heartline.service.AlexaSettings())
    override fun updateAlexaSettings(change: (dev.heartline.service.AlexaSettings) -> dev.heartline.service.AlexaSettings) {
        scope.launch {
            alexaLock.withLock {
                val saved = dev.heartline.service.AlexaSettings.parse(db.meta.get(dev.heartline.service.AlexaSettings.KEY))
                db.meta.set(dev.heartline.service.AlexaSettings.KEY, change(saved).clamped().json())
            }
        }
    }
    override val alexaTurns: StateFlow<List<dev.heartline.service.TurnRecord>> = db.meta.observe(dev.heartline.service.AlexaLog.KEY)
        .map { dev.heartline.service.AlexaLog.parse(it) }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun clearAlexaTurns() { scope.launch { db.meta.set(dev.heartline.service.AlexaLog.KEY, "[]") } }
    private class InsightsRun(val running: Boolean = false, val error: String? = null, val attempts: List<dev.heartline.service.InsightAttempt> = emptyList())
    /** The AI providers, models and router every AI feature shares. */
    private val ai get() = (context.applicationContext as dev.heartline.HeartlineApp).ai
    private val insightsRun = MutableStateFlow(InsightsRun())
    override val insights: StateFlow<InsightsUi> = db.meta.observe(dev.heartline.service.InsightReport.KEY)
        .map { dev.heartline.service.InsightReport.parse(it) }
        .combine(insightsRun) { report, r -> InsightsUi(report, r.running, r.error, r.attempts) }
        .stateIn(scope, SharingStarted.Eagerly, InsightsUi())
    override val insightsSettings: StateFlow<dev.heartline.service.InsightsSettings> = db.meta.observe(dev.heartline.service.InsightsSettings.KEY)
        .map { dev.heartline.service.InsightsSettings.parse(it) }.stateIn(scope, SharingStarted.Eagerly, dev.heartline.service.InsightsSettings())
    override fun setInsightsSettings(s: dev.heartline.service.InsightsSettings) { scope.launch { db.meta.set(dev.heartline.service.InsightsSettings.KEY, s.json()) } }
    private fun masked(s: dev.heartline.service.ai.AiSetup) = s.copy(providers = s.providers.map { p -> p.copy(keys = p.keys.map { "…" + it.takeLast(4) }) })
    override val aiSetup: StateFlow<dev.heartline.service.ai.AiSetup> = db.meta.observe(dev.heartline.service.ai.AiSetup.KEY)
        .map { masked(dev.heartline.service.ai.AiSetup.parse(it) ?: ai.setup()) }.stateIn(scope, SharingStarted.Eagerly, dev.heartline.service.ai.AiSetup())
    override val aiCatalog: StateFlow<dev.heartline.service.ai.AiCatalog> = db.meta.observe(dev.heartline.service.ai.AiCatalog.KEY)
        .map { dev.heartline.service.ai.AiCatalog.parse(it) }.stateIn(scope, SharingStarted.Eagerly, dev.heartline.service.ai.AiCatalog())
    override val aiActivity: StateFlow<List<dev.heartline.service.ai.AiAttempt>> = db.meta.observe(dev.heartline.service.ai.AiAttempt.KEY)
        .map { dev.heartline.service.ai.AiAttempt.parseAll(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private fun aiProvider(id: String, change: (dev.heartline.service.ai.AiProvider) -> dev.heartline.service.ai.AiProvider) {
        scope.launch { ai.update { s -> s.provider(id)?.let { s.withProvider(change(it)) } ?: s } }
    }
    override fun aiAddKey(provider: String, key: String) { key.trim().takeIf { it.isNotEmpty() }?.let { k -> aiProvider(provider) { it.copy(keys = (it.keys + k).distinct()) } } }
    override fun aiRemoveKey(provider: String, index: Int) = aiProvider(provider) { it.copy(keys = it.keys.filterIndexed { i, _ -> i != index }) }
    override fun aiMoveKey(provider: String, index: Int, up: Boolean) = aiProvider(provider) { p ->
        val j = if (up) index - 1 else index + 1
        if (index !in p.keys.indices || j !in p.keys.indices) p else p.copy(keys = p.keys.toMutableList().also { java.util.Collections.swap(it, index, j) })
    }
    override fun aiSaveProvider(id: String?, kind: dev.heartline.service.ai.ProviderKind, name: String, baseUrl: String): String {
        val current = aiSetup.value
        val pid = id ?: generateSequence(0) { it + 1 }.map { n -> name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "custom" } + if (n == 0) "" else "$n" }
            .first { c -> current.provider(c) == null }
        scope.launch { ai.update { s ->
            val old = s.provider(pid)
            s.withProvider(old?.copy(name = name.trim().ifEmpty { old.name }, baseUrl = if (old.builtIn) old.baseUrl else baseUrl.trim().trimEnd('/').ifEmpty { old.baseUrl })
                ?: dev.heartline.service.ai.AiProvider(pid, kind, name.trim().ifEmpty { pid }, baseUrl.trim().trimEnd('/').ifEmpty { dev.heartline.service.ai.AiProvider.defaultUrl(kind) }))
        } }
        return pid
    }
    override fun aiDeleteProvider(id: String) { scope.launch { ai.update { s ->
        if (s.provider(id)?.builtIn != false) s
        else s.copy(providers = s.providers.filter { it.id != id }, picked = s.picked.filter { it.provider != id },
            sections = s.sections.mapValues { (_, v) -> v.copy(main = v.main?.takeIf { it.provider != id }, fallback1 = v.fallback1?.takeIf { it.provider != id },
                fallback2 = v.fallback2?.takeIf { it.provider != id }) })
    } } }
    override fun aiSetPicked(ref: dev.heartline.service.ai.ModelRef, on: Boolean) { scope.launch { ai.update { s -> s.copy(picked = if (on) (s.picked + ref).distinct() else s.picked - ref) } } }
    override fun aiSetSection(section: dev.heartline.service.ai.AiSection, setup: dev.heartline.service.ai.SectionSetup) {
        scope.launch { ai.update { it.withSection(section, setup.copy(policy = setup.policy.clamped())) } }
    }
    override suspend fun aiRefreshModels(provider: String?) = withContext(Dispatchers.IO) { ai.refreshCatalog(provider) }
    override suspend fun aiTestKeys(provider: String) = withContext(Dispatchers.IO) { ai.testKeys(provider) }
    override suspend fun aiTrySection(section: dev.heartline.service.ai.AiSection): String = withContext(Dispatchers.IO) {
        if (section == dev.heartline.service.ai.AiSection.VOICE) return@withContext testSpeech().substringBefore('\n')
        val t0 = System.currentTimeMillis()
        fun failures(list: List<dev.heartline.service.ai.AiAttempt>) = list.filter { !it.ok }.joinToString("\n") { a ->
            "• ${a.model}" + (if (a.keyIndex >= 0) ", key ${a.keyIndex + 1}" else "") + ": " + (a.kind?.let { "${it.label}, " } ?: "") + (a.error ?: a.note ?: "") }
        try {
            val out = ai.router.run(section) { t ->
                ai.api.chat(t.provider, t.key, t.model, dev.heartline.service.ai.ChatRequest("You are being tested. Reply with only the word OK.",
                    listOf(dev.heartline.service.ai.ChatMsg.User("Test")), temperature = 0.0, maxTokens = 400), t.timeoutMs).text
                    ?: throw dev.heartline.service.ai.badAnswer("${t.model} gave no answer")
            }
            val t = out.target
            "\"${out.value.trim().take(60)}\" from ${t.provider.name}: ${t.model} (" + listOf("main model", "fallback 1", "fallback 2")[t.level] +
                ", key ${t.keyIndex + 1}) in ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s." +
                failures(out.attempts).takeIf { it.isNotEmpty() }?.let { "\nOn the way:\n$it" }.orEmpty()
        } catch (e: dev.heartline.service.ai.AiFailed) { "No model answered.\n" + failures(e.attempts) }
    }

    /** Heart checks for the insights, newest first (they aren't among the Alexa tools). */
    private fun checksSummary(): String = checks.value.filter { !it.friend }.take(8).takeIf { it.isNotEmpty() }?.joinToString("\n", "## heart checks (HRV from a 1 to 5 minute rest recording, newest first)\n") { c ->
        val t = java.time.Instant.ofEpochSecond(c.start).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ENGLISH))
        "$t: HRV (RMSSD) ${c.rmssd?.let { "%.0f ms".format(it) } ?: "not measured"}, heart rate ${c.hr?.let { "%.0f bpm".format(it) } ?: "-"}, rhythm ${c.rhythm.name.lowercase().replace('_', ' ')}"
    }.orEmpty()

    override fun refreshInsights() = runInsights(auto = false)
    private fun runInsights(auto: Boolean) {
        if (insightsRun.value.running) return
        insightsRun.value = InsightsRun(running = true)
        scope.launch {
            val failure = try {
                val writer = dev.heartline.service.HealthInsights(dev.heartline.service.HealthTools(this@RealController), ai, extra = { checksSummary() })
                db.meta.set(dev.heartline.service.InsightReport.KEY, writer.generate(auto).json()); null
            } catch (e: dev.heartline.service.InsightsFailed) { e
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                dev.heartline.service.InsightsFailed(listOf(dev.heartline.service.InsightAttempt("heartline", "-", 0, "timed out")))
            } catch (e: Exception) { dev.heartline.service.InsightsFailed(listOf(dev.heartline.service.InsightAttempt("heartline", "-", 0, e.message ?: e.toString()))) }
            insightsRun.value = InsightsRun(false, failure?.message, failure?.attempts ?: emptyList())
            if (auto) autoInsights.lastTry = System.currentTimeMillis()
            if (failure != null) db.events.add("warn", "AI insights failed: ${failure.message}") else db.events.add("info", "AI insights written" + if (auto) " (daily)" else "")
        }
    }

    /**
     * Daily insights: from the set hour, if today's haven't been written, try; if every model fails (Gemini's free
     * tier is often busy), try again every 30 minutes, up to 10 times a day.
     */
    private object autoInsights { var lastTry = 0L; var day = 0L; var tries = 0 }
    private val autoInsightsJob = scope.launch {
        while (true) {
            delay(5 * 60_000L)
            runCatching {
                val s = insightsSettings.value
                if (!s.auto || insightsRun.value.running) return@runCatching
                val now = java.time.ZonedDateTime.now()
                val today = now.toLocalDate().toEpochDay()
                if (autoInsights.day != today) { autoInsights.day = today; autoInsights.tries = 0 }
                val due = now.toLocalDate().atTime(s.autoHour, 0).atZone(now.zone).toEpochSecond()
                val last = insights.value.report?.at ?: 0L
                if (now.toEpochSecond() < due || last >= due || autoInsights.tries >= 10) return@runCatching
                if (System.currentTimeMillis() - autoInsights.lastTry < 30 * 60_000L) return@runCatching
                autoInsights.tries++; autoInsights.lastTry = System.currentTimeMillis()
                runInsights(auto = true)
            }
        }
    }
    override val alexaTools: List<AlexaToolUi> by lazy {
        testAssistant.allTools.map { t ->
            val group = when {
                t.name == "web_search" -> "Web"
                t.name == "get_info" -> "Lookups"
                t.name == "phone" -> "Phone"
                else -> "Watch and Heartline actions"
            }
            AlexaToolUi(t.name, group, t.description, t.parameters.toString(2))
        }
    }
    override suspend fun alexaPromptPreview(template: String): String = testAssistant.promptPreview(template)
    override val alexaAlerts: StateFlow<List<AlexaAlertUi>> = db.meta.observe(dev.heartline.service.WatchAssistant.ALERTS)
        .map { json ->
            val now = java.time.ZonedDateTime.now()
            dev.heartline.service.WatchAssistant.parseAlerts(json).filter { it.at > now.toEpochSecond() }.sortedBy { it.at }.map { a ->
                AlexaAlertUi(a.id, when (a.kind) { dev.heartline.protocol.Alexa.ALERT_TIMER -> "Timer"; dev.heartline.protocol.Alexa.ALERT_ALARM -> "Alarm"; else -> "Reminder" },
                    a.describe(now), a.at)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun cancelAlexaAlert(id: Int) {
        scope.launch {
            val all = dev.heartline.service.WatchAssistant.parseAlerts(db.meta.get(dev.heartline.service.WatchAssistant.ALERTS))
            val a = all.firstOrNull { it.id == id } ?: return@launch
            db.meta.set(dev.heartline.service.WatchAssistant.ALERTS, org.json.JSONArray().apply { (all - a).forEach { put(it.json()) } }.toString())
            // Reminders live in the watch's reminder slots, which the agenda sync rewrites; alarms and timers are deleted on the watch.
            // The watch ignores deletes outside an Alexa conversation, so it's also queued for the next one.
            if (a.kind != dev.heartline.protocol.Alexa.ALERT_REMINDER) {
                dev.heartline.service.AlexaDeletes.queue(File(context.filesDir, "alexa"), a.kind, a.token)
                action { it.send2021(dev.heartline.protocol.Endpoint2021.ALEXA, dev.heartline.protocol.Alexa.deleteAlert(a.kind, a.token)) }
            }
        }
    }
    override fun alexaRecording(name: String): Uri? {
        val wav = File(File(context.filesDir, "alexa"), "$name.wav")
        return if (wav.isFile) FileProvider.getUriForFile(context, "${context.packageName}.recordings", wav) else null
    }
    override val webSearch: StateFlow<WebSearchUi> = db.meta.observe(dev.heartline.service.WebSearchConfig.KEY)
        .map { dev.heartline.service.WebSearchConfig.parse(it).let { c ->
            WebSearchUi(c.tavilyKey.takeIf { k -> k.isNotBlank() }?.takeLast(4), c.exaKey.takeIf { k -> k.isNotBlank() }?.takeLast(4)) } }
        .stateIn(scope, SharingStarted.Eagerly, WebSearchUi())
    override fun setWebSearchKey(service: String, key: String) {
        scope.launch {
            val saved = dev.heartline.service.WebSearchConfig.parse(db.meta.get(dev.heartline.service.WebSearchConfig.KEY))
            val next = if (service == "exa") saved.copy(exaKey = key.trim()) else saved.copy(tavilyKey = key.trim())
            db.meta.set(dev.heartline.service.WebSearchConfig.KEY, next.json())
        }
    }
    /** The same assistant the watch uses; the test buttons run it as a dry run. */
    private val testAssistant by lazy { dev.heartline.service.watchAssistant(context, db, settings, this) }
    override suspend fun testSpeech(): String = withContext(Dispatchers.IO) {
        val wav = java.io.File(context.filesDir, "alexa").listFiles { f -> f.name.endsWith("-16k.wav") }?.maxByOrNull { it.lastModified() }
            ?: return@withContext "No Alexa recording yet: ask the watch something first."
        val d = wav.readBytes()
        val pcm = ShortArray((d.size - 44) / 2) { i -> ((d[44 + 2 * i].toInt() and 255) or (d[45 + 2 * i].toInt() shl 8)).toShort() }
        val started = System.nanoTime()
        val heard = try { dev.heartline.service.VoiceToText(ai).transcribe(pcm, alexaSettings.value) } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
            return@withContext "Voice to text failed after ${"%.1f".format((System.nanoTime() - started) / 1e9)} s: ${e.message}"
        }
        "${heard.engine} heard \"${heard.text}\" (${"%.1f".format(pcm.size / 16_000.0)} s of audio, ${"%.1f".format((System.nanoTime() - started) / 1e9)} s).\n" + testAssistantAi(heard.text)
    }
    override suspend fun testAssistantAi(question: String): String {
        val started = System.nanoTime()
        val answer = testAssistant.answer(question, dryRun = true)
        val ms = (System.nanoTime() - started) / 1_000_000
        runCatching {
            val a = answer.trace
            val record = dev.heartline.service.TurnRecord(System.currentTimeMillis(), "Tried in the app (nothing sent to the watch)", question, null, null,
                a?.prompt.orEmpty(), a?.memory ?: 0, a?.rounds?.toList().orEmpty(), answer.text ?: answer.summary,
                if (answer.text != null) "Would show a reply" else "Would show a card", answer.error, ms, null)
            db.meta.set(dev.heartline.service.AlexaLog.KEY, dev.heartline.service.AlexaLog.added(db.meta.get(dev.heartline.service.AlexaLog.KEY), record, alexaSettings.value.keepTurns))
        }
        return "The watch would show: ${answer.summary}\n${testAssistant.lastModel ?: "OpenRouter"} took ${"%.1f".format(ms / 1000.0)} s."
    }
    override val phoneLink = phone.phoneLink
    override suspend fun searchPlaces(query: String): List<PlaceUi> = phone.searchPlaces(query)
    override fun setWeather(enabled: Boolean, place: PlaceUi?) = phone.setWeather(enabled, place)
    override fun setWeatherLocation(use: Boolean) = phone.setWeatherLocation(use)
    override fun refreshWeather() = phone.refreshWeather()
    override fun setMusicControl(enabled: Boolean) = phone.setMusicControl(enabled)
    private val customize = WatchCustomizeController(scope, db) { block -> action(block) }
    override val watchMenu = customize.watchMenu
    override val autoCapture = db.meta.observe(dev.heartline.service.WatchService.AUTO_CAPTURE).map { it == "true" }.stateIn(scope, SharingStarted.Eagerly, false)
    override fun setAutoCapture(enabled: Boolean) { scope.launch { db.meta.set(dev.heartline.service.WatchService.AUTO_CAPTURE, enabled.toString()) } }
    override fun setWatchMenu(apps: List<MenuAppUi>) { customize.setWatchMenu(apps) }
    override fun sendWatchMenu() = customize.sendWatchMenu()
    override val vibrations = customize.vibrations
    override fun setVibration(kind: String, profile: String, repeat: Int) = customize.setVibration(kind, profile, repeat)
    override fun tryVibration(kind: String, profile: String, repeat: Int) { customize.tryVibration(kind, profile, repeat) }
    override fun stopFindPhone() = phone.stopFindPhone()

    // ---- Alarms and calendar reminders ----
    private val agenda = AgendaController(context, scope, db)
    override val alarms = agenda.alarms
    override val reminders = agenda.reminders
    override fun saveAlarm(alarm: AlarmUi) = agenda.saveAlarm(alarm)
    override fun deleteAlarm(slot: Int) = agenda.deleteAlarm(slot)
    override fun setReminderSync(enabled: Boolean, calendars: Set<Long>) = agenda.setReminderSync(enabled, calendars)

    // ---- Health Connect and background state ----
    private val healthExporter = HealthConnectExporter.get(context)
    override val healthConnect = healthExporter.status
    override val background = MutableStateFlow(BackgroundUi())
    override val healthConnectPermissions: Set<String> get() = healthExporter.permissions
    override fun setHealthConnect(enabled: Boolean) = healthExporter.setEnabled(enabled)
    override fun exportHealthConnectNow() = healthExporter.exportNow()
    override fun refreshBackground() {
        background.value = dev.heartline.service.backgroundState(context)
        healthExporter.refresh()
    }
    init { refreshBackground() }

    // ---- Workout capture ----
    private val captureTask = CaptureController(scope, db, service, ai) { ensureService() }
    override val captureRun = captureTask.run
    override val captures = captureTask.items
    override fun startCapture(label: String, minutes: Int, forFriend: Boolean) { if (checkJob?.isActive != true) captureTask.start(label, minutes, forFriend) }
    override fun stopCapture() = captureTask.stop()
    override suspend fun captureDetail(id: String): CaptureAnalysisUi? = captureTask.detail(id)
    override fun deleteCapture(id: String) = captureTask.delete(id)
    override suspend fun captureCoach(id: String, facts: String, refresh: Boolean): CoachUi = captureTask.coach(id, facts, refresh)
    override suspend fun savedCaptureCoach(id: String): CoachUi? = captureTask.savedCoach(id)
    override suspend fun sleepMovement(start: Long, end: Long): SleepMovement? = withContext(Dispatchers.IO) {
        val minutes = analyticsMinutes(friendFree(db.activity.range(start - 60.0, end + 60.0), friends.value))
        if (minutes.isEmpty()) return@withContext null
        val rawDir = File(context.filesDir, "raw")
        // Each overnight HRV sample's facing; samples saved before it was kept get it from their raw file while that exists.
        val poses = db.health.series(dev.heartline.service.OvernightHrv.SAMPLE, start - 60.0, end.toDouble()).mapNotNull { p ->
            val j = runCatching { JSONObject(p.extra ?: "{}") }.getOrNull() ?: return@mapNotNull null
            j.optJSONArray("grav")?.let { a -> return@mapNotNull WristPose(p.ts, a.getDouble(0), a.getDouble(1), a.getDouble(2)) }
            val f = j.optString("file").takeIf { it.isNotEmpty() }?.let { File(rawDir, File(it).name) }?.takeIf { it.isFile } ?: return@mapNotNull null
            val pose = runCatching { gravityOf(readRecording(f).acc, p.ts) }.getOrNull() ?: return@mapNotNull null
            db.health.upsert(listOf(p.copy(extra = j.put("grav", dev.heartline.service.OvernightHrv.poseJson(pose)).toString())))
            pose
        } + db.health.series(dev.heartline.service.OvernightHrv.POSE, start - 60.0, end.toDouble()).mapNotNull { p ->
            runCatching { JSONObject(p.extra ?: "{}").getJSONArray("grav") }.getOrNull()?.let { a -> WristPose(p.ts, a.getDouble(0), a.getDouble(1), a.getDouble(2)) }
        }
        sleepMovement(minutes, start, end, poses.sortedBy { it.ts })
    }

    override val probe = MutableStateFlow<List<String>>(emptyList())
    override val watchReadback = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private fun sampleUi(p: dev.heartline.data.model.HealthPoint): OvernightSampleUi {
        val j = runCatching { org.json.JSONObject(p.extra ?: "{}") }.getOrDefault(org.json.JSONObject())
        fun num(k: String) = if (j.isNull(k)) null else j.optDouble(k).takeIf { it.isFinite() }
        return OvernightSampleUi(p.ts, j.optString("night").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            p.value, num("hr"), num("resp"), j.optDouble("quality", 0.0), j.optDouble("usable_s", 0.0), j.optString("reason").takeIf { it.isNotEmpty() && it != "null" },
            j.optInt("attempt", 0), j.optInt("length_s", 0))
    }
    override val hrvCheck: StateFlow<HrvCheckUi?> = dev.heartline.service.OvernightHrv.running
    override val overnight: StateFlow<OvernightUi> = combine(db.meta.observe(dev.heartline.service.OvernightHrv.KEY), dev.heartline.service.OvernightHrv.status,
        dev.heartline.service.OvernightHrv.dayStatus,
        db.health.observeSeries(dev.heartline.service.OvernightHrv.SAMPLE, System.currentTimeMillis() / 1000.0 - 40 * 86_400),
        db.health.observeSeries(dev.heartline.service.OvernightHrv.DAY_SAMPLE, System.currentTimeMillis() / 1000.0 - 40 * 86_400)) { cfg, status, dayStatus, rows, dayRows ->
        val s = dev.heartline.service.OvernightSettings.parse(cfg)
        OvernightUi(s.enabled, status, rows.map(::sampleUi), s.allDay, dayStatus, dayRows.map(::sampleUi), s.nightEvery, s.nightLen, s.dayEvery, s.dayLen, s.positions)
    }.combine(friends) { o, f -> if (f.marks.isEmpty()) o else o.copy(samples = o.samples.filter { !f.covers(it.ts) }, daySamples = o.daySamples.filter { !f.covers(it.ts) }) }
        .stateIn(scope, SharingStarted.Eagerly, OvernightUi())
    override val notes: StateFlow<String> = db.meta.observe("notes").map { it.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, "")
    override fun saveNotes(text: String) { scope.launch { db.meta.set("notes", text) } }
    override fun setHrvSchedule(night: Boolean, everyMin: Int, lengthMin: Int) {
        scope.launch {
            val s = dev.heartline.service.OvernightSettings.parse(db.meta.get(dev.heartline.service.OvernightHrv.KEY))
            db.meta.set(dev.heartline.service.OvernightHrv.KEY, (if (night) s.copy(nightEvery = everyMin, nightLen = lengthMin) else s.copy(dayEvery = everyMin, dayLen = lengthMin)).json())
            db.events.add("info", "${if (night) "Overnight" else "Daytime"} HRV: $lengthMin-minute samples every $everyMin minutes")
        }
    }
    override fun setAccuratePositions(enabled: Boolean) {
        scope.launch {
            db.meta.set(dev.heartline.service.OvernightHrv.KEY, dev.heartline.service.OvernightSettings.parse(db.meta.get(dev.heartline.service.OvernightHrv.KEY)).copy(positions = enabled).json())
            db.events.add("info", "Accurate positions turned ${if (enabled) "on" else "off"}")
        }
    }
    override fun setAllDayHrv(enabled: Boolean) {
        scope.launch {
            db.meta.set(dev.heartline.service.OvernightHrv.KEY, dev.heartline.service.OvernightSettings.parse(db.meta.get(dev.heartline.service.OvernightHrv.KEY)).copy(allDay = enabled).json())
            db.events.add("info", "Daytime HRV turned ${if (enabled) "on" else "off"}")
        }
    }
    override fun setOvernight(enabled: Boolean) {
        scope.launch {
            db.meta.set(dev.heartline.service.OvernightHrv.KEY, dev.heartline.service.OvernightSettings.parse(db.meta.get(dev.heartline.service.OvernightHrv.KEY)).copy(enabled = enabled).json())
            db.events.add("info", "Overnight HRV turned ${if (enabled) "on" else "off"}")
        }
    }
    override fun readWatchHealth() = action { w ->
        // Zepp 6.3 two-way reads (`FE id 01`, answer `10 FE id 01 01 value`). The GTR 2e answers only Pressure (stress, id 6);
        // HrInterval 1, HighHrReminder 3, Osa 5, Rem 8, AtrialFib 10 and RelaxationPromptSwitch 35 come back `10 FE 05`, unsupported.
        watchReadback.value = listOf(6 to "All-day stress").mapNotNull { (id, name) ->
            val a = runCatching { w.queryConfig(byteArrayOf(0xFE.toByte(), id.toByte(), 1)) }.getOrNull() ?: return@mapNotNull null
            if (a.size >= 6 && a[0] == 0x10.toByte() && a[1] == 0xFE.toByte() && a[2] == id.toByte() && a[4] == 1.toByte())
                name to (if (a[5].toInt() != 0) "on" else "off") else null
        }
    }
    override fun probeUnknownSensors() = action { w ->
        probe.value = listOf("Probing…")
        // Probe 2 (3 Nov): 0x80 gives ~50 Hz PPG. Probe 3: does the start command's last byte (0x19 = 25) set the rate,
        // what does 0x04 change, and does 0x80 work beside gyro and compass (the capture mask 0x53 -> 0xd3)? 12 s each.
        val runs = listOf(0x03 to 0x19, 0x03 to 0x32, 0x03 to 0x64, 0x03 to 0x0a, 0x83 to 0x32, 0x7b to 0x19, 0x7f to 0x19,
            0x07 to 0x19, 0x16 to 0x19, 0x53 to 0x19, 0xd3 to 0x19)
        val results = runs.mapIndexed { i, (mask, rate) ->
            probe.value = listOf("Probing ${i + 1} of ${runs.size}…")
            runCatching { w.probeRawMask(mask, 12, rate) }.getOrElse { e -> "mask 0x%02x rate 0x%02x: failed, ".format(mask, rate) + e.message }
        }
        probe.value = results
    }

    fun forwardNotification(title: String, body: String, kind: NotificationKind, app: String, icon: Int = dev.heartline.protocol.WatchIcon.APP) {
        val s = service.value ?: return
        if (s.watch.connection.value.state != ConnectionState.CONNECTED) return
        launchAction { s.command { it.notify(title, body, kind, app, icon) } }
    }
}

/** Saved settings that hold API keys, left out of data exports. */
internal val SECRET_META = setOf(dev.heartline.service.ai.AiSetup.KEY, dev.heartline.service.AssistantAiConfig.KEY,
    dev.heartline.service.SpeechConfig.KEY, dev.heartline.service.WebSearchConfig.KEY)

private fun sensorFromName(name: String): Sensor? = when (name.lowercase()) {
    "gyro", "gyroscope" -> Sensor.GYRO; "mag", "magnetometer" -> Sensor.MAG; "mic", "microphone" -> Sensor.MIC
    else -> Sensor.entries.firstOrNull { it.name.equals(name, true) }
}
private fun dataSensor(sensor: Sensor): dev.heartline.data.model.RawSensor = when (sensor) {
    Sensor.GYRO -> dev.heartline.data.model.RawSensor.GYROSCOPE
    Sensor.MAG -> dev.heartline.data.model.RawSensor.MAGNETOMETER
    Sensor.MIC -> dev.heartline.data.model.RawSensor.MICROPHONE
    else -> dev.heartline.data.model.RawSensor.valueOf(sensor.name)
}
