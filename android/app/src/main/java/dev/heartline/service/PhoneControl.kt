package dev.heartline.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.view.KeyEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The phone's media and ringer for the watch assistant. Media goes to the playing app's session when Heartline has
 * notification access (which also says what's playing), else as a media key to whichever app last played.
 */
class PhoneControl(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun sessions(): List<MediaController> = if (!musicAccess(context)) emptyList() else runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, NotificationForwarder::class.java))
    }.getOrDefault(emptyList())

    /** The session playing now, else the most recent one. */
    private fun current(): MediaController? = sessions().let { s -> s.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: s.firstOrNull() }

    private fun key(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun percentOf(stream: Int) = (audio.getStreamVolume(stream) * 100.0 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)).roundToInt()
    private fun setPercent(stream: Int, percent: Int) =
        audio.setStreamVolume(stream, (percent / 100.0 * audio.getStreamMaxVolume(stream)).roundToInt(), 0)

    suspend fun media(action: String, percent: Int?): String {
        val c = current()
        val t = c?.transportControls
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        when (action) {
            "play" -> t?.play() ?: key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> t?.pause() ?: key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "play_pause" -> if (t == null) key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else if (playing) t.pause() else t.play()
            "next" -> t?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> t?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> t?.stop() ?: key(KeyEvent.KEYCODE_MEDIA_STOP)
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "set_volume" -> setPercent(AudioManager.STREAM_MUSIC, requireNotNull(percent) { "set_volume needs a percent" })
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            else -> error("unknown media action $action")
        }
        // Players take a moment to report the new track or state.
        if (action in setOf("play", "pause", "play_pause", "next", "previous", "stop")) delay(800)
        return mediaState()
    }

    private fun mediaState(): String {
        val volume = if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) "muted" else "${percentOf(AudioManager.STREAM_MUSIC)}%"
        return "${nowPlaying()}; media volume $volume"
    }

    private fun nowPlaying(): String {
        if (!musicAccess(context)) return "what's playing is unknown (Heartline has no notification access)"
        val c = current() ?: return "nothing is playing"
        val m = c.metadata
        val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
        val what = listOfNotNull(m?.getString(MediaMetadata.METADATA_KEY_TITLE), m?.getString(MediaMetadata.METADATA_KEY_ARTIST)).filter { it.isNotBlank() }.joinToString(" by ")
        val state = when (c.playbackState?.state) { PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; PlaybackState.STATE_BUFFERING -> "loading"; else -> "stopped" }
        return "$app $state" + if (what.isNotEmpty()) ": $what" else ""
    }

    /**
     * Sets the ring volume, then the mode. Full silence needs Do Not Disturb access on Android; without it the phone
     * goes to vibrate and the answer says so.
     */
    fun ringer(mode: String?, percent: Int?): String {
        var note = ""
        percent?.let { p ->
            runCatching { setPercent(AudioManager.STREAM_RING, p) }.onFailure { note += " The ring volume couldn't be changed (${it.message})." }
        }
        when (mode) {
            null -> {}
            "normal" -> audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            "silent" -> if (notifications.isNotificationPolicyAccessGranted && runCatching { audio.ringerMode = AudioManager.RINGER_MODE_SILENT }.isSuccess) Unit
                else { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE; note += " Silent needs Do Not Disturb access for Heartline in Android settings, so it is on vibrate." }
            else -> error("mode is normal, vibrate or silent")
        }
        return ringerState() + note
    }

    private fun ringerState(): String {
        val mode = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        val dnd = notifications.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        return "ringer $mode, ring volume ${percentOf(AudioManager.STREAM_RING)}%" + if (dnd) ", Do Not Disturb on" else ""
    }

    fun status(): String {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Phone: ${ringerState()}; ${mediaState()}; battery $level%" + if (battery.isCharging) ", charging" else ""
    }
}
