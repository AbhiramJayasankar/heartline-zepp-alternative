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

class WatchService : Service() {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val app get() = application as HeartlineApp
    lateinit var watch: WatchConnection
        private set
    lateinit var phoneFeatures: PhoneFeatures
        private set
    lateinit var captureRunner: CaptureRunner
        private set
    private lateinit var calls: CallForwarder
    private lateinit var agenda: WatchAgendaSync
    inner class LocalBinder : Binder() { val service get() = this@WatchService }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        watch = WatchConnection(this, scope, app.watchStore)
        captureRunner = CaptureRunner(scope, app.db, watch, app.settings, java.io.File(filesDir, "raw"))
        app.settings.watch.value.mac?.let { CompanionLink.observe(this, it) }
        phoneFeatures = PhoneFeatures(this, scope, app.db, watch, app.settings)
        watch.onSuccessfulSync = { dev.heartline.app.HealthConnectExporter.get(this).afterSync() }
        calls = CallForwarder(this, scope, app.settings, watch)
        agenda = WatchAgendaSync(this, scope, app.db, watch)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { backfillWatchWorkouts() }
        val alexaActions = object : AssistantActions {
            override fun startWorkout(label: String, minutes: Int) = captureRunner.start(label, minutes)
            override fun stopWorkout(): String? = captureRunner.run.value?.let { r -> captureRunner.stop(); "${r.label} after ${WatchAssistant.spoken(r.seconds)}" }
            override fun sync() = app.realController.syncNow()
        }
        val workoutNow = { captureRunner.run.value?.let { r -> "${r.label}, ${r.seconds / 60} of ${r.plannedS / 60} minutes" + (r.hr?.let { ", heart rate $it" } ?: "") } }
        AlexaBridge(scope, watch, app.db, java.io.File(filesDir, "alexa"), GeminiSpeech(),
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
            }.collect { manager.notify(ID, notification(it)) }
        }
        scope.launch {
            watch.ready.collectLatest { connected ->
                if (connected) coroutineScope {
                    launch { phoneFeatures.sendSilentState() }
                    launch { followWatchWorkouts() }
                    // A workout opened on the watch starts a capture when the owner has turned that on.
                    launch {
                        watch.deviceEvents.collect { data ->
                            val w = dev.heartline.protocol.watchWorkoutStart(data) ?: return@collect
                            app.db.events.add("info", "Watch started a workout: ${w.name}" + if (w.needsGps) " (asks for phone GPS)" else "")
                            if (app.db.meta.get(AUTO_CAPTURE) == "true" && captureRunner.run.value == null)
                                runCatching { captureRunner.start(w.name, 180) }.onFailure { app.db.events.add("warn", "Auto capture did not start: ${it.message}") }
                        }
                    }
                    // Zepp sends the profile on every connection; resend when it changes.
                    launch { app.settings.profile.collect { p -> userInfo(p)?.let { runCatching { watch.writeUserInfo(it) } } } }
                    // The watch drops idle links every minute or two, so schedule from the last sync, not from each reconnect.
                    launch { periodic(15 * 60_000L, "activity_last_sync") { watch.syncActivity() } }
                    launch { periodic(60 * 60_000L, "health_last_sync") { watch.syncHealth() } }
                    launch { periodic(5 * 60_000L, "quick_last_sync") { watch.syncQuick(); app.db.meta.set("quick_last_sync", (System.currentTimeMillis() / 1000).toString()) } }
                    launch { periodic(30 * 60_000L, "battery_last_read") { watch.readBattery(); app.db.meta.set("battery_last_read", (System.currentTimeMillis() / 1000).toString()) } }
                    // Short heart checks while asleep, when turned on (Sleep page).
                    launch { OvernightHrv(app, watch, { watch.raw.value.streaming || captureRunner.active }, java.io.File(filesDir, "raw")).run() }
                }
            }
        }
        scope.launch { app.settings.notificationForwarding.collect { calls.refresh() } }
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
     */
    private suspend fun followWatchWorkouts() {
        val since = System.currentTimeMillis() / 1000.0 - 60 * 60
        app.db.health.observeSeries("watch_workout", since).collect { rows ->
            val last = rows.maxByOrNull { it.ts } ?: return@collect
            val now = System.currentTimeMillis() / 1000
            val name = runCatching { org.json.JSONObject(last.extra ?: "{}").optString("type", "Workout") }.getOrDefault("Workout")
            val start = runCatching { org.json.JSONObject(last.extra ?: "{}").optString("event") }.getOrNull() == "start"
            if (app.db.meta.get(AUTO_CAPTURE) != "true") return@collect
            if (start && now - last.ts < 60 * 60 && captureRunner.run.value == null && autoCaptureStartedAt != last.ts) {
                autoCaptureStartedAt = last.ts
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
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.db.events.add("warn", "Scheduled watch task failed: ${e.message}") }
            delay(interval)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
        agenda.close()
        calls.close()
        // Keep the dispatcher alive until ATT and recording resources have been closed.
        scope.launch { try { phoneFeatures.close(); watch.disconnect() } finally { scope.cancel() } }
        super.onDestroy()
    }
    companion object {
        /** meta key: start a capture when a workout is opened on the watch. */
        const val AUTO_CAPTURE = "auto_capture_watch_workouts"
        const val IDLE = "dev.heartline.IDLE"
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
