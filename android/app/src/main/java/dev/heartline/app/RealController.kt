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
            watchSettings.value = SETTINGS.map { s -> WatchSettingUi(s.key, s.label, s.type, values[s.key]?.let { settingValue(s.key, it) }, s.options.map { it.value to it.label }, s.min, s.max) }
        } }
        scope.launch { db.events.observeRecent(200).collect { list -> events.value = list.map { EventUi(it.ts.toLong(), it.level == "warn", it.level == "error", it.msg) } } }
        scope.launch { db.meta.observeAll().collect { meta -> sync.update { it.copy(lastActivity = meta["activity_last_sync"]?.toDoubleOrNull()?.toLong(), lastHealth = meta["health_last_sync"]?.toDoubleOrNull()?.toLong()) } } }
        scope.launch { db.checks.observeRange().collect { list -> checks.value = list.map { c ->
            val duration = c.json?.let { runCatching { JSONObject(it).optDouble("duration_s").toInt() }.getOrNull() } ?: 0
            CheckUi(c.start.toString(), c.start.toLong(), duration, c.hr, c.rmssd, rhythmStatus(c.rhythm))
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
        launch { db.heartRate.observeSince(System.currentTimeMillis() / 1000.0 - 86400).collect { list -> liveHr.update { it.copy(recent = list.takeLast(600).map { HrPoint(it.ts.toLong(), it.bpm) }) } } }
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
        val tick = flow { while (currentCoroutineContext().isActive) { emit(ZonedDateTime.now(zone)); delay(60_000) } }
        combine(db.activity.observeRange(from, Double.MAX_VALUE), db.health.observeByType(from), db.insights.observeAll(), settings.profile, tick) { rows, health, insights, p, now ->
            val previousKind = db.activity.prevKind(from)
            withContext(Dispatchers.Default) {
                val values = health.values.flatten().mapNotNull { it.value?.let { value -> HealthValue(it.type, it.ts, value) } }
                val summaries = insights.mapNotNull { i -> i.start?.let { CheckSummary(it, i.rmssd, i.hr, i.quality ?: 0.0, i.usableS ?: 0.0, i.rhythm) } }
                val result = computeDaily(analyticsMinutes(rows, previousKind), values, summaries, Profile(p.age, p.sex?.name?.lowercase(), p.weightKg, p.heightCm, p.maxHr, p.restingHr), 28, now)
                result.todayUi(now.toLocalDate()) to result.trendsUi(healthByDay(values, zone = zone))
            }
        }.collect { (t, tr) -> today.value = t; trends.value = tr }
    }
    // Placeholder from the activity history only; the full implementation (live HR, stress, SpO2, temperature, PAI) replaces it.
    override fun healthDay(date: LocalDate): Flow<HealthDayUi> = activityDay(date).map { d ->
        val start = date.atStartOfDay(zone).toEpochSecond()
        val hr = d.minutes.mapNotNull { m -> m.hr?.let { SeriesPoint(m.ts.toDouble(), it.toDouble()) } }
        HealthDayUi(date, start, hr, 0, null, d.hrMin, d.hrAvg, d.hrMax, emptyList(), emptyList(), 0, emptyList(), null, null, emptyList(), emptyList(),
            emptyList(), null, null, emptyList(), emptyList(), emptyList(), null, null, null,
            d.minutes.filter { it.steps > 0 }.map { SeriesPoint(it.ts.toDouble(), it.steps.toDouble()) }, d.steps, emptyList(), null, null,
            d.activeMinutes, d.wornMinutes, null, null, d.minutes, d.sleep, d.workouts)
    }
    override fun activityDay(date: LocalDate): Flow<ActivityDayUi> {
        val from = date.atStartOfDay(zone).toEpochSecond().toDouble()
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble() - 1
        return combine(db.activity.observeRange(from, to), db.health.observeWorkouts(from, to), today,
            db.health.observeRawFetches(-Double.MAX_VALUE).map { withContext(Dispatchers.Default) { storedWorkoutDetails(it) } },
            settings.profile) { rows, workouts, _, details, profile ->
            val minutes = mappedMinutes(rows, db.activity.prevKind(from))
            val hr = minutes.mapNotNull { it.hr }
            val sleepFrom = from - 86400
            val allRows = db.activity.range(sleepFrom, to)
            val daily = withContext(Dispatchers.Default) { computeDaily(analyticsMinutes(allRows), emptyList(), emptyList(), Profile(), 2, date.atTime(23, 59).atZone(zone)) }
            ActivityDayUi(date, minutes, minutes.sumOf { it.steps }, minutes.count { it.steps > 0 || it.state == MinuteState.ACTIVE },
                minutes.count { it.state in setOf(MinuteState.SLEEP_DEEP, MinuteState.SLEEP_LIGHT, MinuteState.SLEEP_REM) },
                minutes.count { it.state !in setOf(MinuteState.NOT_WORN, MinuteState.CHARGING) }, hr.minOrNull(), hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), hr.maxOrNull(),
                daily.days.lastOrNull { it.date == date.toString() }?.sleepUi(), workouts.map { workout ->
                    workout.ui().withWorkoutDetail(details[workout.start], ProfileUi(age = profile.age, maxHr = profile.maxHr))
                })
        }
    }
    override suspend fun checkDetail(id: String): SessionUi? = withContext(Dispatchers.IO) {
        val c = db.checks.list().firstOrNull { it.start.toString() == id } ?: return@withContext null
        c.json?.let { sessionFromJson(it, id) } ?: c.file?.let { file ->
            val path = recordingFile(file); if (path.isFile) analyzeSession(readRecording(path), file).sessionUi(id) else null
        }
    }
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
        scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { db.events.add("error", e.message ?: e.javaClass.simpleName) } }
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
    override fun startCheck(seconds: Int) {
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
                checkRun.update { it?.copy(analysing = false, result = result.sessionUi(result.start.toString())) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { checkRun.update { it?.copy(analysing = false, error = e.message) }; db.events.add("error", "Heart check failed: ${e.message}") }
            finally { withContext(NonCancellable) { if (ownsRecording && s?.watch?.raw?.value?.streaming == true) runCatching { s.command { it.stopRaw() } } } }
        }
    }
    override fun cancelCheck() { checkJob?.cancel(); checkJob = null; checkRun.value = null }
    override fun vibrate() = action { it.vibrate() }
    override fun sendNotification(title: String, body: String, kind: NotifyKind, asPackage: String) = action {
        if (asPackage.isBlank() || kind == NotifyKind.CALL) { it.notify(title, body, NotificationKind.valueOf(kind.name)); return@action }
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(asPackage, 0)).toString() }
            .getOrDefault(SAMPLE_APPS.firstOrNull { a -> a.first == asPackage }?.second ?: asPackage)
        val sms = dev.heartline.protocol.isSmsApp(asPackage)
        it.notify(title, body, if (sms) NotificationKind.GENERIC else NotificationKind.MESSAGE, label, dev.heartline.protocol.watchIconFor(asPackage))
    }
    override fun syncNow() = action { it.syncActivity(); it.syncHealth() }
    override fun setWatchClock() = action { it.syncTime() }
    override fun applyWatchSetting(key: String, value: Any) = action { it.applySetting(key, value) }
    override fun saveProfile(profile: ProfileUi) { settings.setProfile(dev.heartline.data.model.Profile(profile.age, profile.sex?.uppercase()?.let { Sex.valueOf(it) }, profile.weightKg, profile.heightCm, profile.maxHr, profile.restingHr)) }
    override fun setTheme(mode: ThemeMode) = settings.setTheme(Theme.valueOf(mode.name))
    override fun setMetric(metric: Boolean) = settings.setUnits(if (metric) Units.METRIC else Units.IMPERIAL)
    override fun setForwarding(update: ForwardingUi) {
        settings.setNotificationForwarding(NotificationForwarding(update.enabled, update.allowed, update.quietStart?.times(60), update.quietEnd?.times(60), update.calls))
        if (update.enabled && update.calls && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) requestCallPermission?.invoke()
        service.value?.refreshCalls()
    }
    fun callPermissionResult(granted: Boolean) {
        if (!granted) settings.setNotificationForwarding(settings.notificationForwarding.value.copy(forwardCalls = false))
        service.value?.refreshCalls()
    }
    override fun saveWatchKey(mac: String, keyHex: String): String? {
        val address = mac.trim().uppercase(); val hex = keyHex.trim().removePrefix("0x")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "Enter a Bluetooth address such as C1:0B:05:00:0A:46"
        if (!KeyStore.validateHex(hex)) return "The key must contain 32 hex characters"
        return try {
            val key = KeyStore.parseHex(hex); try { keys.save(key) } finally { key.fill(0) }
            settings.setWatch(Watch(address, "Amazfit GTR 2e")); prefs.update { it.copy(hasKey = true, watchMac = address) }; null
        } catch (e: Exception) { e.message ?: "Unable to store the watch key" }
    }
    override fun finishOnboarding() { settings.setOnboardingDone(true); if (!suppressAutomaticConnection) connect() }
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
            context.contentResolver.openOutputStream(uri, "wt").use { out -> Exporter.exportCsvZip(db, checkNotNull(out), -Double.MAX_VALUE, Double.MAX_VALUE) }
            db.events.add("info", "Exported data")
        }
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
    // ---- Task A: weather on the watch, find phone, music controls. Replace these stubs. ----
    private val taskA = TaskAController(context, scope, db, service) { ensureService() }
    override val weather = taskA.weather
    override val phoneLink = taskA.phoneLink
    override suspend fun searchPlaces(query: String): List<PlaceUi> = taskA.searchPlaces(query)
    override fun setWeather(enabled: Boolean, place: PlaceUi?) = taskA.setWeather(enabled, place)
    override fun refreshWeather() = taskA.refreshWeather()
    override fun setMusicControl(enabled: Boolean) = taskA.setMusicControl(enabled)
    override fun stopFindPhone() = taskA.stopFindPhone()
    // ---- end Task A ----





    // ---- Task B: alarms and calendar reminders. Replace these stubs. ----
    private val agenda = AgendaController(context, scope, db)
    override val alarms = agenda.alarms
    override val reminders = agenda.reminders
    override fun saveAlarm(alarm: AlarmUi) = agenda.saveAlarm(alarm)
    override fun deleteAlarm(slot: Int) = agenda.deleteAlarm(slot)
    override fun setReminderSync(enabled: Boolean, calendars: Set<Long>) = agenda.setReminderSync(enabled, calendars)
    // ---- end Task B ----





    // ---- Task C: Health Connect and background state. Replace these stubs. ----
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
    // ---- end Task C ----





    // ---- Task E: workout capture. Replace these stubs. ----
    private val captureTask = CaptureController(scope, db, service) { ensureService() }
    override val captureRun = captureTask.run
    override val captures = captureTask.items
    override fun startCapture(label: String, minutes: Int) { if (checkJob?.isActive != true) captureTask.start(label, minutes) }
    override fun stopCapture() = captureTask.stop()
    override suspend fun captureDetail(id: String): CaptureAnalysisUi? = captureTask.detail(id)
    override fun deleteCapture(id: String) = captureTask.delete(id)
    // ---- end Task E ----


    override val probe = MutableStateFlow<List<String>>(emptyList())
    override fun probeUnknownSensors() = action { w ->
        probe.value = listOf("Probing…")
        // Probe 2 (3 Nov): 0x80 gives ~50 Hz PPG. Probe 3: does the start command's last byte (0x19 = 25) set the rate,
        // what does 0x04 change, and does 0x80 work beside gyro and compass (the capture mask 0x53 -> 0xd3)? 12 s each.
        val runs = listOf(0x03 to 0x19, 0x03 to 0x32, 0x03 to 0x64, 0x03 to 0x0a, 0x83 to 0x32, 0x7b to 0x19, 0x7f to 0x19,
            0x07 to 0x19, 0x16 to 0x19, 0x53 to 0x19, 0xd3 to 0x19)
        val results = runs.mapIndexed { i, (mask, rate) ->
            probe.value = listOf("Probing ${i + 1} of ${runs.size}…")
            runCatching { w.probeRawMask(mask, 12, rate) }.getOrElse { e -> "mask 0x%02x rate 0x%02x: failed, ${e.message}".format(mask, rate) }
        }
        probe.value = results
    }

    fun forwardNotification(title: String, body: String, kind: NotificationKind, app: String, icon: Int = dev.heartline.protocol.WatchIcon.APP) {
        val s = service.value ?: return
        if (s.watch.connection.value.state != ConnectionState.CONNECTED) return
        launchAction { s.command { it.notify(title, body, kind, app, icon) } }
    }
}

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
