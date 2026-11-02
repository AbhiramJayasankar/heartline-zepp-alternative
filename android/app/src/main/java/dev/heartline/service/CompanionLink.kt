@file:Suppress("DEPRECATION")
package dev.heartline.service

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.heartline.data.KeyStore

/**
 * Heartline's own companion-device link to the watch. Once the user approves it, Android treats Heartline as the
 * watch's companion app: it wakes [HeartlineCompanionService] when the watch comes into range and lets the app start
 * its connection service from the background. Zepp holds a separate link of its own; each app gets one.
 */
object CompanionLink {
    private fun manager(context: Context): CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) context.getSystemService(CompanionDeviceManager::class.java) else null

    @androidx.annotation.RequiresApi(33)
    private fun association(context: Context, mac: String): AssociationInfo? = manager(context)?.myAssociations
        ?.firstOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }

    fun linked(context: Context, mac: String?): Boolean {
        if (mac == null) return false
        val m = manager(context) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) association(context, mac) != null
        else m.associations.any { it.equals(mac, ignoreCase = true) }
    }

    /**
     * Asks Android to show its "Allow Heartline to access your watch?" sheet. [onPending] receives the sheet to launch
     * with StartIntentSenderForResult; [onDone] gets null on success or a sentence explaining the failure.
     */
    fun request(context: Context, mac: String, onPending: (IntentSender) -> Unit, onDone: (String?) -> Unit) {
        val m = manager(context) ?: return onDone("This phone doesn't support companion devices.")
        // Plain address match: WatchConnection's fallback scan finds the watch's random static address this way too.
        val scan = ScanFilter.Builder().setDeviceAddress(mac).build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scan).build())
            .setSingleDevice(true)
            .build()
        val main = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 33) {
            m.associate(request, { main.post(it) }, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(sender: IntentSender) = onPending(sender)
                override fun onAssociationCreated(info: AssociationInfo) { observe(context, mac); onDone(null) }
                override fun onFailure(error: CharSequence?) = onDone(error?.toString() ?: "Android couldn't find the watch. Keep it close and try again.")
            })
        } else {
            m.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(sender: IntentSender) = onPending(sender)
                override fun onFailure(error: CharSequence?) = onDone(error?.toString() ?: "Android couldn't find the watch. Keep it close and try again.")
            }, main)
        }
    }

    /** Ask Android to report when the watch appears or disappears. Safe to call repeatedly. */
    fun observe(context: Context, mac: String) {
        val m = manager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = association(context, mac)?.id ?: return
                m.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else if (linked(context, mac)) m.startObservingDevicePresence(mac)
        }.onFailure { Log.w("Heartline", "Companion presence observation failed", it) }
    }
}

/** Android binds this when the linked watch comes into range; it brings the connection service up. */
class HeartlineCompanionService : CompanionDeviceService() {
    private fun wake() {
        if (KeyStore(this).load() == null) return
        // Companion apps holding REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND may start one here.
        try { startForegroundService(Intent(this, WatchService::class.java)) }
        catch (e: RuntimeException) { Log.w("Heartline", "Companion wake-up could not start the watch service", e) }
    }
    @androidx.annotation.RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) wake()
    }
    @androidx.annotation.RequiresApi(33)
    override fun onDeviceAppeared(info: AssociationInfo) = wake()
    override fun onDeviceAppeared(address: String) = wake()
}
