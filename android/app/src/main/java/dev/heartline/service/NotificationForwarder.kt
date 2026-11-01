package dev.heartline.service

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.heartline.HeartlineApp
import dev.heartline.app.NotificationDeduplicator
import dev.heartline.app.quietNow
import dev.heartline.protocol.NotificationKind
import java.time.LocalTime

class NotificationForwarder : NotificationListenerService() {
    private val dedupe = NotificationDeduplicator()
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as HeartlineApp
        val config = app.settings.notificationForwarding.value
        if (!config.enabled || sbn.packageName == packageName || sbn.packageName !in config.allowedPackages) return
        val flags = sbn.notification.flags
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        if (quietNow(minute, config.quietHoursStart, config.quietHoursEnd)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank() || !dedupe.accept(title, text, SystemClock.elapsedRealtime())) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        app.realController.forwardNotification(title, text, NotificationKind.MESSAGE, label)
    }
}
