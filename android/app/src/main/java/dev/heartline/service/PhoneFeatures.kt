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
class PhoneFeatures(context: Context, private val scope: CoroutineScope, private val db: HeartlineDatabase, private val watch: WatchConnection, settings: AppSettings) {
    val weather = WatchWeatherSync(scope, db, watch, settings)
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
                    null -> Unit
                    else -> music.event(event)
                }
            }
        }
    }
    fun stopFindPhone() { scope.launch { find.stop() } }
    fun close() { find.stop(); music.close() }
}
