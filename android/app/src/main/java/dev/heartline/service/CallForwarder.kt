package dev.heartline.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.heartline.app.quietNow
import dev.heartline.ble.ConnectionState
import dev.heartline.ble.WatchConnection
import dev.heartline.ble.rethrowIfCancelled
import dev.heartline.data.AppSettings
import dev.heartline.protocol.NotificationKind
import dev.heartline.protocol.deviceEventName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Incoming calls on the watch: who is calling (from the phone app's call notification, which [NotificationForwarder]
 * hands over), ringing until the phone stops ringing, and the watch's reject and ignore buttons acting on the phone.
 */
class CallForwarder(private val context: Context, private val scope: CoroutineScope, private val settings: AppSettings, private val watch: WatchConnection) : AutoCloseable {
    private val manager = context.getSystemService(TelephonyManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var registered = false
    private var ringing = false
    private var alert: Job? = null
    /** The ringer mode before "ignore" on the watch silenced it, put back when the call ends. */
    private var mutedFrom: Int? = null
    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) { scope.launch { callState(state) } }
    }
    private val events = scope.launch {
        watch.deviceEvents.collect { data ->
            when (deviceEventName(data)) {
                "call_reject" -> reject()
                "call_ignore" -> ignore()
            }
        }
    }

    private suspend fun callState(state: Int) {
        val config = settings.notificationForwarding.value
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val allow = config.enabled && config.forwardCalls && !quietNow(minute, config.quietHoursStart, config.quietHoursEnd)
        if (state != TelephonyManager.CALL_STATE_RINGING) restoreRinger()
        try {
            if (watch.connection.value.state != ConnectionState.CONNECTED) { ringing = false; return }
            if (state == TelephonyManager.CALL_STATE_RINGING && allow && !ringing) {
                ringing = true
                alert = scope.launch {
                    // The phone app's call notification, with the caller's name, comes a moment after the ringing starts.
                    val since = SystemClock.elapsedRealtime() - 3_000
                    var name = caller(since)
                    for (i in 0 until 15) { if (name != null) break; delay(100); name = caller(since) }
                    try { watch.notify(name ?: "Incoming call", "", NotificationKind.CALL, autoEndCall = false) }
                    catch (e: Exception) { rethrowIfCancelled(e); log("Call alert: ${e.message}") }
                }
            } else if (state != TelephonyManager.CALL_STATE_RINGING && ringing) {
                ringing = false; alert?.cancel(); watch.endCall()
            }
        } catch (e: Exception) { rethrowIfCancelled(e); log("Call alert: ${e.message}") }
    }

    /** The watch's reject button: ends the call when Heartline may (the call-control permission), else silences it. */
    private suspend fun reject() {
        if (!ringing) return
        val telecom = context.getSystemService(TelecomManager::class.java)
        val ended = context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED &&
            runCatching { @Suppress("DEPRECATION") telecom.endCall() }.getOrDefault(false)
        if (ended) log("Rejected the call from the watch", "info")
        else { ignore(); log("The watch's reject button only silenced the call: allow Heartline to end calls when it asks (Notifications, Calls)") }
    }

    /** The watch's ignore button: silences the phone for this call; the ringer comes back when the call ends. */
    private suspend fun ignore() {
        if (!ringing || mutedFrom != null) return
        val before = audio.ringerMode
        val done = runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }.isSuccess ||
            runCatching { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE }.isSuccess
        if (done) { mutedFrom = before; log("Silenced the call from the watch", "info") }
        runCatching { watch.endCall() }
    }

    private fun restoreRinger() {
        val before = mutedFrom ?: return
        mutedFrom = null
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
        runCatching { if (audio.ringerMode != before) audio.ringerMode = before }
    }

    private suspend fun log(message: String, level: String = "warn") {
        runCatching { (context.applicationContext as dev.heartline.HeartlineApp).db.events.add(level, message) }
    }

    fun refresh() {
        val c = settings.notificationForwarding.value
        val enabled = c.enabled && c.forwardCalls && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (enabled && !registered) { manager.registerTelephonyCallback(context.mainExecutor, callback); registered = true }
        else if (!enabled && registered) { manager.unregisterTelephonyCallback(callback); registered = false }
    }
    override fun close() { events.cancel(); if (registered) manager.unregisterTelephonyCallback(callback); registered = false; restoreRinger() }

    companion object {
        /** The caller's name from the phone app's call notification, and when it was seen (elapsed realtime). */
        @Volatile private var lastCaller: Pair<String, Long>? = null
        fun callerSeen(name: String) { if (name.isNotBlank()) lastCaller = name.trim() to SystemClock.elapsedRealtime() }
        private fun caller(since: Long): String? = lastCaller?.takeIf { it.second >= since }?.first
    }
}
