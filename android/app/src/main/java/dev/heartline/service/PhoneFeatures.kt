package dev.heartline.service

import android.content.Context
import dev.heartline.app.PhoneLinkUi
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.AppSettings
import dev.heartline.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owned by the foreground service, including when the activity is absent. */
class PhoneFeatures(private val context: Context, private val scope: CoroutineScope, private val db: HeartlineDatabase, private val watch: WatchConnection, settings: AppSettings) {
    val weather = WatchWeatherSync(scope, db, watch, settings, dev.heartline.app.PhoneLocation(context, db))
    val phoneLink = MutableStateFlow(PhoneLinkUi())
    private val find = FindPhone(context, scope) { ringing -> phoneLink.update { it.copy(findPhoneRinging = ringing) } }
    private val music = MusicControls(context, scope, db, watch) { enabled, access, text ->
        phoneLink.update { it.copy(musicEnabled = enabled, musicAccess = access, nowPlaying = text) }
    }
    init {
        scope.launch {
            watch.deviceEvents.collect { data ->
                when (val event = phoneEvent(data)) {
                    PhoneEvent.FIND_START -> {
                        find.start()
                        // Do not block event collection: a watch stop must work even during a long GATT write.
                        scope.launch {
                            try { watch.acknowledgeFindPhone() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { db.events.add("warn", "Find phone acknowledgement failed: ${e.message}") }
                        }
                    }
                    PhoneEvent.FIND_STOP -> find.stop()
                    PhoneEvent.SILENT_ON, PhoneEvent.SILENT_OFF -> scope.launch { silence(event == PhoneEvent.SILENT_ON) }
                    null -> Unit
                    else -> music.event(event)
                }
            }
        }
    }
    /**
     * The watch's "mute phone" app (HuamiDeviceEvent.SILENT_MODE). Full silence needs do-not-disturb access on Android,
     * so without it the phone goes to vibrate. The watch is then told the phone's actual state.
     */
    private suspend fun silence(on: Boolean) {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val target = when {
            !on -> android.media.AudioManager.RINGER_MODE_NORMAL
            nm.isNotificationPolicyAccessGranted -> android.media.AudioManager.RINGER_MODE_SILENT
            else -> android.media.AudioManager.RINGER_MODE_VIBRATE
        }
        runCatching { audio.ringerMode = target }.onFailure { db.events.add("warn", "Could not change the ringer: ${it.message}") }
        db.events.add("info", "Watch asked to " + (if (on) "silence" else "unmute") + " the phone; ringer is now " +
            when (audio.ringerMode) { android.media.AudioManager.RINGER_MODE_SILENT -> "silent"; android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
        sendSilentState()
    }

    /** Tells the watch whether the phone is silenced (vibrate counts as silenced), as Gadgetbridge does on connect. */
    suspend fun sendSilentState() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        runCatching { watch.writeConfig(phoneSilentCommand(audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL), "phone silent state") }
    }

    fun stopFindPhone() { scope.launch { find.stop() } }
    fun close() { find.stop(); music.close() }
}
