package dev.heartline.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.SystemClock
import android.os.VibratorManager
import dev.heartline.MainActivity
import dev.heartline.app.FindPhoneTimer
import kotlinx.coroutines.*

class FindPhone(private val context: Context, private val scope: CoroutineScope, private val update: (Boolean) -> Unit) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var player: MediaPlayer? = null
    private var timeout: Job? = null
    private val timer = FindPhoneTimer()

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Find phone", NotificationManager.IMPORTANCE_HIGH).apply {
            // The alarm player and vibrator own the signal; stopping releases both immediately.
            setSound(null, null); enableVibration(false)
        })
    }
    fun start() {
        if (!timer.start(SystemClock.elapsedRealtime())) return
        update(true)
        timeout = scope.launch { delay(timer.remaining(SystemClock.elapsedRealtime())); stop() }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val p = MediaPlayer(); player = p
            p.apply {
                p.setAudioAttributes(attributes); p.setDataSource(context, uri); p.isLooping = true
                p.setOnErrorListener { _, _, _ -> scope.launch { stop() }; true }
                p.prepare(); p.start()
            }
        } catch (_: Exception) { player?.release(); player = null }
        try { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0), attributes) } catch (_: SecurityException) { }
        val stop = PendingIntent.getService(context, 21, Intent(context, WatchService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, 22, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            notifications.notify(ID, Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Find phone").setContentText("Your watch is ringing this phone").setContentIntent(open)
                .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        } catch (_: SecurityException) { }
    }
    fun stop() {
        timeout?.cancel(); timeout = null
        player?.release(); player = null
        vibrator.cancel(); notifications.cancel(ID)
        timer.stop(); update(false)
    }
    companion object {
        const val STOP = "dev.heartline.STOP_FIND_PHONE"
        private const val CHANNEL = "find_phone"
        private const val ID = 21
    }
}
