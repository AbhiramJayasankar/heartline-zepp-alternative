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
    private lateinit var calls: CallForwarder
    inner class LocalBinder : Binder() { val service get() = this@WatchService }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        watch = WatchConnection(this, scope, app.watchStore)
        watch.onSuccessfulSync = { dev.heartline.app.HealthConnectExporter.get(this).afterSync() }
        calls = CallForwarder(this, scope, app.settings, watch)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        scope.launch {
            combine(watch.connection, watch.battery) { state, battery ->
                state.state.name.lowercase().replaceFirstChar { it.uppercase() } + (battery?.let { ", battery ${it.level}%" } ?: "")
            }.collect { manager.notify(ID, notification(it)) }
        }
        scope.launch {
            watch.ready.collectLatest { connected ->
                if (connected) coroutineScope {
                    // The watch drops idle links every minute or two, so schedule from the last sync, not from each reconnect.
                    launch { periodic(15 * 60_000L, "activity_last_sync") { watch.syncActivity() } }
                    launch { periodic(60 * 60_000L, "health_last_sync") { watch.syncHealth() } }
                    launch { periodic(30 * 60_000L, "battery_last_read") { watch.readBattery(); app.db.meta.set("battery_last_read", (System.currentTimeMillis() / 1000).toString()) } }
                }
            }
        }
        scope.launch { app.settings.notificationForwarding.collect { calls.refresh() } }
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
        calls.close()
        // Keep the dispatcher alive until ATT and recording resources have been closed.
        scope.launch { try { watch.disconnect() } finally { scope.cancel() } }
        super.onDestroy()
    }
    companion object {
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
