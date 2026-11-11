package dev.heartline.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.heartline.app.*
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

fun musicAccess(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
    .isNotificationListenerAccessGranted(ComponentName(context, NotificationForwarder::class.java))

class MusicControls(private val context: Context, private val scope: CoroutineScope, private val db: HeartlineDatabase,
    private val watch: WatchConnection, private val update: (Boolean, Boolean, String?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val component = ComponentName(context, NotificationForwarder::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var access = false
    private var listening = false
    private var closed = false
    private var controllers = emptyList<MediaController>()
    private var selected: MediaController? = null
    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sendJob: Job? = null
    private val sends = Channel<Unit>(Channel.CONFLATED)
    private var lastVolume = -1
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { scope.launch { refreshSessions() } }

    init {
        scope.launch { db.meta.observe("music_enabled").collect { enabled = it == "true"; refreshAccess(); scheduleSend() } }
        scope.launch { watch.ready.collect { ready -> if (ready) { refreshAccess(); scheduleSend() } else sendJob?.cancel() } }
        scope.launch { for (request in sends) sendCurrent() }
        // Access can be granted or taken away in Android's settings while the service runs.
        scope.launch {
            while (isActive) { refreshAccess(); delay(30_000) }
        }
    }

    /** The phone's volume buttons: the watch's music screen shows the new volume. */
    private val volumeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            scope.launch { val v = volume(); if (enabled && access && v != lastVolume) { lastVolume = v; scheduleSend() } }
        }
    }
    init {
        androidx.core.content.ContextCompat.registerReceiver(context, volumeChanged, android.content.IntentFilter(VOLUME_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshAccess() {
        if (closed) return
        val hadAccess = access
        access = musicAccess(context)
        if (enabled && access) {
            try {
                if (!listening) { manager.addOnActiveSessionsChangedListener(sessions, component, handler); listening = true }
                refreshSessions()
            } catch (_: SecurityException) { access = false; detach() }
        } else detach()
        publish()
        if (access && !hadAccess) scheduleSend()
    }

    private fun refreshSessions() {
        if (!enabled || !access || closed) return
        try {
            controllers = manager.getActiveSessions(component)
            val tokens = controllers.map { it.sessionToken }.toSet()
            callbacks.keys.filter { it !in tokens }.forEach { token -> callbacks.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) } }
            controllers.forEach { c -> if (c.sessionToken !in callbacks) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) { scope.launch { refreshSessions(); scheduleSend() } }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { scope.launch { refreshSessions(); scheduleSend() } }
                    override fun onSessionDestroyed() { scope.launch { refreshSessions(); scheduleSend() } }
                }
                c.registerCallback(callback, handler); callbacks[c.sessionToken] = c to callback
            } }
            val previous = selected?.sessionToken
            selected = preferredMusicIndex(controllers.map { it.playbackState?.state == PlaybackState.STATE_PLAYING })?.let { controllers[it] }
            publish()
            if (previous != selected?.sessionToken) scheduleSend()
        } catch (_: SecurityException) { access = false; detach(); publish() }
    }

    private fun publish() {
        val metadata = selected?.metadata
        update(enabled, access, if (enabled && access) nowPlayingText(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)) else null)
    }

    private fun volume() = musicVolumePercent(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    private fun scheduleSend(delayMs: Long = 250) {
        sendJob?.cancel()
        if (!enabled || !access || !watch.ready.value || closed) return
        sendJob = scope.launch {
            delay(delayMs)
            sends.trySend(Unit)
        }
    }

    private suspend fun sendCurrent() {
        if (!enabled || !access || !watch.ready.value || closed) return
        val c = selected; val m = c?.metadata; val playback = c?.playbackState
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        val music = WatchMusic(playback?.state == PlaybackState.STATE_PLAYING,
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), (duration / 1000).coerceIn(0, 65535).toInt(),
            musicPositionSeconds(playback?.position ?: 0, playback?.lastPositionUpdateTime ?: 0, SystemClock.elapsedRealtime(),
                playback?.playbackSpeed ?: 0f, playback?.state == PlaybackState.STATE_PLAYING, duration))
        try { watch.sendMusic(music, volume()) }
        catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("warn", "Couldn't send music to the watch: ${e.message}") }
    }

    fun event(event: PhoneEvent) {
        refreshAccess()
        if (!enabled || !access || closed) return
        try {
            when (event) {
                PhoneEvent.MUSIC_OPEN -> scheduleSend(100) // HuamiSupport.sendMusicStateDelayed.
                PhoneEvent.MUSIC_CLOSE -> sendJob?.cancel()
                PhoneEvent.PLAY -> selected?.transportControls?.play()
                PhoneEvent.PAUSE -> selected?.transportControls?.pause()
                PhoneEvent.NEXT -> selected?.transportControls?.skipToNext()
                PhoneEvent.PREVIOUS -> selected?.transportControls?.skipToPrevious()
                PhoneEvent.VOLUME_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                PhoneEvent.VOLUME_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                else -> return
            }
            if (event == PhoneEvent.VOLUME_UP || event == PhoneEvent.VOLUME_DOWN) scheduleSend()
        } catch (_: SecurityException) { refreshAccess() }
    }

    private fun detach() {
        sendJob?.cancel()
        if (listening) { manager.removeOnActiveSessionsChangedListener(sessions); listening = false }
        callbacks.values.forEach { (c, cb) -> c.unregisterCallback(cb) }; callbacks.clear()
        controllers = emptyList(); selected = null
    }
    fun close() { closed = true; detach(); runCatching { context.unregisterReceiver(volumeChanged) } }

    private companion object { const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION" }
}
