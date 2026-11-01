package dev.heartline.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.heartline.app.quietNow
import dev.heartline.ble.ConnectionState
import dev.heartline.ble.WatchConnection
import dev.heartline.data.AppSettings
import dev.heartline.protocol.NotificationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalTime

class CallForwarder(private val context: Context, private val scope: CoroutineScope, private val settings: AppSettings, private val watch: WatchConnection) : AutoCloseable {
    private val manager = context.getSystemService(TelephonyManager::class.java)
    private var registered = false
    private var ringing = false
    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            scope.launch {
                val config = settings.notificationForwarding.value
                val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                val allow = config.enabled && config.forwardCalls && !quietNow(minute, config.quietHoursStart, config.quietHoursEnd)
                try {
                    if (watch.connection.value.state != ConnectionState.CONNECTED) { ringing = false; return@launch }
                    if (state == TelephonyManager.CALL_STATE_RINGING && allow && !ringing) {
                        ringing = true; watch.notify("Incoming call", "", NotificationKind.CALL)
                    } else if (state != TelephonyManager.CALL_STATE_RINGING && ringing) { ringing = false; watch.endCall() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { (context.applicationContext as dev.heartline.HeartlineApp).db.events.add("warn", "Call alert: ${e.message}") }
            }
        }
    }
    fun refresh() {
        val c = settings.notificationForwarding.value
        val enabled = c.enabled && c.forwardCalls && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (enabled && !registered) { manager.registerTelephonyCallback(context.mainExecutor, callback); registered = true }
        else if (!enabled && registered) { manager.unregisterTelephonyCallback(callback); registered = false }
    }
    override fun close() { if (registered) manager.unregisterTelephonyCallback(callback); registered = false }
}
