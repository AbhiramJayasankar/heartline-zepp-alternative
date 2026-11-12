package dev.heartline.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import dev.heartline.HeartlineApp
import dev.heartline.MainActivity
import dev.heartline.ble.*
import dev.heartline.data.KeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

class WatchService : Service() {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CrashGuard.handler)
    private val app get() = application as HeartlineApp
    lateinit var watch: WatchConnection
        private set
    lateinit var phoneFeatures: PhoneFeatures
        private set
    lateinit var captureRunner: CaptureRunner
        private set
    /** A-GPS data for the watch's GPS, from Zepp ([AgpsUpdater]). */
    lateinit var agps: AgpsUpdater
        private set
    private lateinit var calls: CallForwarder
    private lateinit var agenda: WatchAgendaSync
    inner class LocalBinder : Binder() { val service get() = this@WatchService }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        watch = WatchConnection(this, scope, app.watchStore)
        captureRunner = CaptureRunner(scope, app.db, watch, app.settings, java.io.File(filesDir, "raw"))
        agps = AgpsUpdater(this, app.db, watch, scope)
        scope.launch { runCatching { agps.load() } }
        app.settings.watch.value.mac?.let { CompanionLink.observe(this, it) }
        phoneFeatures = PhoneFeatures(this, scope, app.db, watch, app.settings)
        watch.onSuccessfulSync = { dev.heartline.app.HealthConnectExporter.get(this).afterSync() }
        calls = CallForwarder(this, scope, app.settings, watch)
        agenda = WatchAgendaSync(this, scope, app.db, watch)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { backfillWatchWorkouts() }
        val phone = PhoneControl(this)
        val alexaActions = object : AssistantActions {
            override fun startWorkout(label: String, minutes: Int, forFriend: Boolean) = captureRunner.start(label, minutes, forFriend)
            override fun stopWorkout(): String? = captureRunner.run.value?.let { r -> captureRunner.stop(); "${r.label} after ${WatchAssistant.spoken(r.seconds)}" }
            override fun sync() = app.realController.syncNow()
            override suspend fun watchAlarms() = agenda.watchAlarms()
            override suspend fun setWatchAlarm(slot: Int?, hour: Int, minute: Int, days: Set<java.time.DayOfWeek>, on: Boolean) = agenda.putAlarm(slot, hour, minute, days, on)
            override suspend fun switchWatchAlarm(slot: Int, on: Boolean) = agenda.switchAlarm(slot, on)
            override suspend fun deleteWatchAlarm(slot: Int) = agenda.deleteAlarm(slot)
            override suspend fun startHeartCheck(seconds: Int, forFriend: Boolean) = assistantHeartCheck(seconds, forFriend)
            override suspend fun addNote(text: String) {
                val old = app.db.meta.get("notes").orEmpty().trimEnd()
                app.db.meta.set("notes", if (old.isEmpty()) text else "$old\n$text")
            }
            override suspend fun sendToWatch(title: String, parts: List<String>): Int {
                val max = AlexaSettings.parse(app.db.meta.get(AlexaSettings.KEY)).maxNotifications.coerceAtLeast(1)
                val sent = parts.take(max)
                // After the spoken reply has shown, then a moment apart so they arrive in order.
                scope.launch {
                    delay(2_500)
                    sent.forEachIndexed { i, p ->
                        try { watch.notify(if (sent.size > 1) "$title ${i + 1}/${sent.size}" else title, p) }
                        catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); app.db.events.add("warn", "Send to watch: notification ${i + 1} failed: ${e.message}"); return@launch }
                        delay(1_500)
                    }
                    app.db.events.add("info", "Sent \"$title\" to the watch as ${sent.size} notification(s)")
                }
                return sent.size
            }
            override suspend fun media(action: String, percent: Int?) = phone.media(action, percent)
            override suspend fun ringer(mode: String?, percent: Int?) = phone.ringer(mode, percent).also { runCatching { phoneFeatures.sendSilentState() } }
            override suspend fun phoneStatus() = phone.status()
        }
        val workoutNow = { captureRunner.run.value?.let { r -> "${r.label}, ${r.seconds / 60} of ${r.plannedS / 60} minutes" + (r.hr?.let { ", heart rate $it" } ?: "") } }
        AlexaBridge(scope, watch, app.db, java.io.File(filesDir, "alexa"), VoiceToText(app.ai),
            watchAssistant(this, app.db, app.settings, app.realController, alexaActions, workoutNow)).start()
        // The offline Vosk model unpacked by older versions (about 70 MB).
        scope.launch(Dispatchers.IO) { java.io.File(filesDir, "vosk-en").takeIf { it.exists() }?.deleteRecursively() }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        scope.launch {
            combine(watch.connection, watch.battery, OvernightHrv.running) { state, battery, check ->
                // A heart check in progress leads, so it is plain from the notification shade that one is running.
                (check?.let { r -> "Taking a ${r.seconds / 60}-minute heart check until " +
                    java.time.Instant.ofEpochSecond(r.startedAt + r.seconds).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) } + ". " } ?: "") +
                state.state.name.lowercase().replaceFirstChar { it.uppercase() } + (battery?.let { ", battery ${it.level}%" } ?: "")
            }.collect { status = it; manager.notify(ID, notification(it)) }
        }
        scope.launch {
            watch.ready.collectLatest { connected ->
                if (connected) supervisorScope {
                    launch { phoneFeatures.sendSilentState() }
                    // An alarm switched or changed on the watch: let Heartline's list follow it, so the next sync keeps it.
                    launch {
                        watch.deviceEvents.collect { data ->
                            if (dev.heartline.protocol.deviceEventName(data) in setOf("alarm_toggled", "alarm_changed")) agenda.watchAlarmsChanged()
                        }
                    }
                    launch { followWatchWorkouts() }
                    // A workout opened on the watch starts a capture when the owner has turned that on.
                    launch {
                        watch.deviceEvents.collect { data ->
                            val w = dev.heartline.protocol.watchWorkoutStart(data) ?: return@collect
                            app.db.events.add("info", "Watch started a workout: ${w.name}" + if (w.needsGps) " (asks for phone GPS)" else "")
                            if (app.db.meta.get(AUTO_CAPTURE) == "true" && captureRunner.run.value == null) {
                                // Remembered like a start from the event log, so that start (arriving later) doesn't begin
                                // a second capture and the workout's end stops this one (audit, 2026-11-12).
                                val now = System.currentTimeMillis() / 1000
                                runCatching { captureRunner.start(w.name, 180) }
                                    .onSuccess { autoCaptureStartedAt = now; app.db.meta.set(AUTO_CAPTURE_HANDLED, now.toString()) }
                                    .onFailure { app.db.events.add("warn", "Auto capture did not start: ${it.message}") }
                            }
                        }
                    }
                    // Zepp sends the profile on every connection; resend when it changes.
                    launch { app.settings.profile.collect { p -> userInfo(p)?.let { runCatching { watch.writeUserInfo(it) } } } }
                    // The watch drops idle links every minute or two, so schedule from the last sync, not from each reconnect.
                    launch { periodic(15 * 60_000L, "activity_last_sync") { watch.syncActivity() } }
                    launch { periodic(60 * 60_000L, "health_last_sync") { watch.syncHealth() } }
                    launch { periodic(5 * 60_000L, "quick_last_sync") { watch.syncQuick(); app.db.meta.set("quick_last_sync", (System.currentTimeMillis() / 1000).toString()) } }
                    // The watch's clock, every 6 hours (Zepp sets it on connecting); a time zone change sends it at once.
                    launch { periodic(6 * 3600_000L, CLOCK_SET) { watch.syncTime(); app.db.meta.set(CLOCK_SET, (System.currentTimeMillis() / 1000).toString()) } }
                    launch { periodic(30 * 60_000L, "battery_last_read") { watch.readBattery(); app.db.meta.set("battery_last_read", (System.currentTimeMillis() / 1000).toString()) } }
                    // A-GPS every 3 days when signed in to Zepp, a few minutes after connecting and never during a recording.
                    launch {
                        delay(3 * 60_000L)
                        while (isActive) {
                            if (!captureRunner.active && !watch.raw.value.streaming) agps.maybeAuto()
                            delay(3600_000L)
                        }
                    }
                    // Short heart checks while asleep, when turned on (Sleep page).
                    launch { OvernightHrv(app, watch, { watch.raw.value.streaming || captureRunner.active }, java.io.File(filesDir, "raw")).run() }
                }
            }
        }
        scope.launch { app.settings.notificationForwarding.collect { calls.refresh() } }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { housekeeping() } catch (e: Exception) { rethrowIfCancelled(e); app.db.events.add("warn", "Housekeeping failed: ${e.message}") }
                delay(24 * 3600_000L)
            }
        }
        // Started without the app's screen (after a reboot, or the watch coming into range): the controller still needs
        // this service, or forwarded notifications go nowhere until the app is opened (audit, 2026-11-12).
        app.realController.attach(this)
        androidx.core.content.ContextCompat.registerReceiver(this, clockChanged,
            IntentFilter().apply { addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The phone's time zone or clock changed (travel, a manual change): the watch follows at once. */
    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                if (!watch.ready.value) { app.db.meta.delete(CLOCK_SET); return@launch }
                try { watch.syncTime(); app.db.meta.set(CLOCK_SET, (System.currentTimeMillis() / 1000).toString()) }
                catch (e: Exception) { rethrowIfCancelled(e); app.db.events.add("warn", "Setting the watch's clock failed: ${e.message}") }
            }
        }
    }

    /**
     * Once a day: the Bluetooth log keeps a week, the activity log 60 days, and the raw copies of the frequent syncs two
     * weeks (their readings are in the health table already). Workout transfers and resting heart rates are kept: the
     * workout pages and the Health Connect export read them.
     */
    private suspend fun housekeeping() {
        val now = System.currentTimeMillis() / 1000.0
        val logs = BleLog.prune()
        val keepRaw = setOf(dev.heartline.protocol.FetchType.WORKOUTS.key, dev.heartline.protocol.FetchType.WORKOUT_DETAILS.key,
            dev.heartline.protocol.FetchType.RESTING_HR.key)
        val raw = app.db.health.pruneRawFetches(dev.heartline.protocol.FetchType.entries.map { it.key }.toSet() - keepRaw, now - 14 * 86_400)
        val events = app.db.events.prune(now - 60 * 86_400)
        if (logs + raw + events > 0) app.db.events.add("info", "Housekeeping: removed $logs old Bluetooth log file(s), $raw raw sync copies and $events old log lines")
    }
    /**
     * A heart check started by the watch assistant: fails at once when one can't start, and sends the result to the
     * watch as a notification when it's done.
     */
    private suspend fun assistantHeartCheck(seconds: Int, forFriend: Boolean) {
        val c = app.realController
        c.checkRun.value?.let { if (it.result == null && it.error == null) error("a heart check is already running") }
        check(!captureRunner.active) { "a workout capture is running; stop it first" }
        check(OvernightHrv.running.value == null) { "an automatic HRV check is recording right now; try again in a few minutes" }
        c.startCheck(seconds, forFriend)
        delay(1_500)
        val run = c.checkRun.value ?: error("the heart check didn't start")
        run.error?.let { error(it) }
        scope.launch {
            val done = withTimeoutOrNull((seconds + 240) * 1000L) { c.checkRun.first { it == null || it.result != null || it.error != null } } ?: return@launch
            val r = done.result
            val text = when {
                done.error != null -> "The heart check failed: ${done.error}"
                r == null -> return@launch
                else -> listOfNotNull(r.hr?.let { "Heart rate ${it.roundToInt()} bpm" }, r.rmssd?.let { "HRV (RMSSD) ${it.roundToInt()} ms" } ?: "HRV not measured",
                    "Rhythm ${r.rhythm.name.lowercase().replace('_', ' ')}", r.respiratoryRate?.let { "Breathing ${it.roundToInt()}/min" },
                    "Signal ${(r.signalQuality * 100).roundToInt()}%").joinToString("\n")
            }
            // A full-screen heart says it's done; the numbers follow as a notification, which the watch keeps in its list.
            if (r != null && done.error == null) {
                runCatching { watch.nudge("Heart check done", listOfNotNull(r.hr?.let { "${it.roundToInt()} bpm" }, r.rmssd?.let { "HRV ${it.roundToInt()} ms" }).joinToString(", ")) }
                delay(4_000)
            }
            runCatching { watch.notify("Heart check", text) }
        }
    }

    /** The profile as the watch's user info, once age, height and weight are set; the birthday is 1 January of the birth year. */
    /** Once: workout starts and ends from saved event logs (version 2 replaces rows stored 5:30 h late, read as UTC). */
    private suspend fun backfillWatchWorkouts() {
        if (app.db.meta.get("watch_workout_backfill") == "2") return
        app.db.health.deleteType("watch_workout")
        val rows = app.db.health.rawFetches(0.0).filter { it.type == "statistics" && it.data != null }
            .flatMap { dev.heartline.protocol.watchWorkoutMarks(dev.heartline.protocol.parseStatisticsEvents(it.data!!)) }
            .map { dev.heartline.data.model.HealthPoint(it.type, it.ts, it.value, org.json.JSONObject(it.extra).toString()) }
        if (rows.isNotEmpty()) app.db.health.upsert(rows)
        app.db.meta.set("watch_workout_backfill", "2")
        app.db.events.add("info", "Found ${rows.size} watch workout starts and ends in saved event logs")
    }

    /** The capture this service started for a watch workout, so the workout's end can stop it. */
    private var autoCaptureStartedAt: Long? = null

    /**
     * Starts and ends of watch workouts arrive through the quick sync's statistics fetch as "watch_workout" rows. The
     * watch writes that log in batches of 50 events (one every 30-40 s), so a start can reach the phone up to about
     * 30 minutes late (2026-11-04); a start up to an hour old with no end yet still begins a capture. With auto capture on, a fresh start begins a capture named after the workout, and the
     * matching end stops a capture this started.
     *
     * Each start begins at most one capture, ever: the start's time is saved, so restarting Heartline (or deleting the
     * capture) doesn't begin another for the same workout. A workout discarded on the watch logs no end, and before
     * this every restart within the hour started a fresh capture for it (2026-11-09).
     */
    private suspend fun followWatchWorkouts() {
        val since = System.currentTimeMillis() / 1000.0 - 60 * 60
        app.db.health.observeSeries("watch_workout", since).collect { rows ->
            val last = rows.maxByOrNull { it.ts } ?: return@collect
            val now = System.currentTimeMillis() / 1000
            val name = runCatching { org.json.JSONObject(last.extra ?: "{}").optString("type", "Workout") }.getOrDefault("Workout")
            val start = runCatching { org.json.JSONObject(last.extra ?: "{}").optString("event") }.getOrNull() == "start"
            if (app.db.meta.get(AUTO_CAPTURE) != "true") return@collect
            val handled = app.db.meta.get(AUTO_CAPTURE_HANDLED)?.toLongOrNull()
            // A start within 15 minutes of the one already handled is the same workout (the watch's own start event, then its log).
            val seen = handled != null && kotlin.math.abs(handled - last.ts) <= 15 * 60
            if (start && now - last.ts < 60 * 60 && captureRunner.run.value == null && autoCaptureStartedAt != last.ts && !seen) {
                autoCaptureStartedAt = last.ts
                app.db.meta.set(AUTO_CAPTURE_HANDLED, last.ts.toString())
                runCatching { captureRunner.start(name, 180) }
                    .onSuccess { app.db.events.add("info", "Started a capture for the watch's $name, ${(now - last.ts) / 60} min after it began") }
                    .onFailure { app.db.events.add("warn", "Auto capture did not start: ${it.message}") }
            } else if (!start && autoCaptureStartedAt != null && last.ts > autoCaptureStartedAt!! && captureRunner.run.value != null) {
                autoCaptureStartedAt = null
                captureRunner.stop()
                app.db.events.add("info", "The watch's $name ended; stopped its capture")
            }
        }
    }

    private fun userInfo(p: dev.heartline.data.model.Profile): ByteArray? {
        val age = p.age ?: return null; val h = p.heightCm ?: return null; val w = p.weightKg ?: return null
        if (age !in 5..120 || h !in 50.0..250.0 || w !in 20.0..300.0) return null
        return dev.heartline.protocol.userInfoCommand(java.time.LocalDate.now().year - age, 1, 1, p.sex?.name, h.toInt(), w, "heartline".hashCode())
    }
    private suspend fun periodic(interval: Long, lastKey: String, action: suspend () -> Unit) {
        val last = app.db.meta.get(lastKey)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        if (last != null) delay((last + interval - System.currentTimeMillis()).coerceIn(0, interval))
        while (currentCoroutineContext().isActive) {
            try { action() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); app.db.events.add("warn", "Scheduled watch task failed: ${e.message}") }
            delay(interval)
        }
    }
    /** What the notification says: the link's state and battery, or "Starting" until the first update. */
    @Volatile private var status = "Starting"
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Opening the app starts the service again; the notification keeps the status it already shows.
        startForeground(ID, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (intent?.action == FindPhone.STOP) { phoneFeatures.stopFindPhone(); return START_STICKY }
        if (intent?.action != IDLE && KeyStore(this).load() != null && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            app.settings.watch.value.mac?.let { mac -> scope.launch { watch.connect(mac) } }
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Heartline").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    override fun onBind(intent: Intent?) = binder
    suspend fun <T> command(block: suspend (WatchConnection) -> T): T {
        val task = scope.async { block(watch) }
        try { return task.await() }
        finally { if (!currentCoroutineContext().isActive) withContext(NonCancellable) { task.cancelAndJoin() } }
    }
    fun refreshCalls() { scope.launch { calls.refresh() } }
    override fun onDestroy() {
        app.realController.detach(this)
        runCatching { unregisterReceiver(clockChanged) }
        agenda.close()
        calls.close()
        // Keep the dispatcher alive until ATT and recording resources have been closed.
        scope.launch { try { phoneFeatures.close(); watch.disconnect() } finally { scope.cancel() } }
        super.onDestroy()
    }
    companion object {
        /** meta key: start a capture when a workout is opened on the watch. */
        const val AUTO_CAPTURE = "auto_capture_watch_workouts"
        /** The watch workout start (epoch seconds) that already began a capture. */
        const val AUTO_CAPTURE_HANDLED = "auto_capture_handled_start"
        const val IDLE = "dev.heartline.IDLE"
        /** meta key: when the watch's clock was last set (epoch seconds). */
        private const val CLOCK_SET = "clock_last_set"
        private const val CHANNEL = "watch_connection"
        private const val ID = 1
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || KeyStore(context).load() == null) return
        // Android 12+ exempts BOOT_COMPLETED and MY_PACKAGE_REPLACED from background FGS start
        // restrictions. Android 15's boot type restrictions allow connectedDevice; the granted
        // BLUETOOTH_CONNECT permission satisfies its Android 14+ runtime prerequisite.
        // https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
        try { context.startForegroundService(Intent(context, WatchService::class.java)) }
        catch (e: RuntimeException) { android.util.Log.w("Heartline", "Watch service restart rejected", e) }
    }
}
