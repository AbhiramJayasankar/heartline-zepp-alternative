package dev.heartline.service

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.heartline.HeartlineApp
import dev.heartline.app.NotificationDeduplicator
import dev.heartline.app.quietNow
import dev.heartline.protocol.NotificationKind
import dev.heartline.protocol.isSmsApp
import dev.heartline.protocol.watchIconFor
import androidx.core.app.NotificationCompat
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
        val (title, text) = senderAndText(sbn.notification)
        if (title.isBlank() && text.isBlank() || !dedupe.accept(title, text, SystemClock.elapsedRealtime())) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val kind = if (isSmsApp(sbn.packageName)) NotificationKind.GENERIC else NotificationKind.MESSAGE
        app.realController.forwardNotification(title, text, kind, label, watchIconFor(sbn.packageName))
    }
}

/**
 * Who it is from and what they said. Chat apps (WhatsApp, Telegram, Messages) post a MessagingStyle notification whose
 * newest message carries the real sender; in a group the heading becomes "Sender · Group". Other apps use title/text.
 */
internal fun senderAndText(n: Notification): Pair<String, String> {
    val extras = n.extras
    val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
    val last = style?.messages?.lastOrNull()
    if (style != null && last != null) {
        val sender = last.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: style.user.name?.toString().orEmpty()
        val conversation = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() && it != sender }
        val heading = if (style.isGroupConversation && conversation != null) "$sender · $conversation" else sender.ifBlank { conversation.orEmpty() }
        return heading.ifBlank { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() } to last.text?.toString().orEmpty()
    }
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    return title to text
}
