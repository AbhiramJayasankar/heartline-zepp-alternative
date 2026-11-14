package dev.heartline.service

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.heartline.HeartlineApp
import dev.heartline.app.NotificationDeduplicator
import dev.heartline.app.PhoneMessage
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
        // The phone app's incoming call: its title is who is calling, for the watch's call alert (CallForwarder).
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { CallForwarder.callerSeen(it) }
            return
        }
        val config = app.settings.notificationForwarding.value
        if (!config.enabled || sbn.packageName == packageName || sbn.packageName !in config.allowedPackages) return
        val flags = sbn.notification.flags
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        if (quietNow(minute, config.quietHoursStart, config.quietHoursEnd)) return
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull()
        val fresh = if (style != null && style.messages.isNotEmpty()) {
            val messages = style.messages.map { m ->
                val sender = m.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: style.user.name?.toString().orEmpty()
                val conversation = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() && it != sender }
                val heading = if (style.isGroupConversation && conversation != null) "$sender · $conversation" else sender.ifBlank { conversation.orEmpty() }
                PhoneMessage(heading.ifBlank { sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() },
                    m.text?.toString().orEmpty(), m.timestamp)
            }
            dedupe.newMessages(sbn.key, messages)
        } else {
            val (title, text) = senderAndText(sbn.notification)
            val at = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
            if (dedupe.accept(sbn.key, at, title, text, SystemClock.elapsedRealtime())) listOf(PhoneMessage(title, text, at)) else emptyList()
        }
        // A repost of what the watch already has (apps repost on every read, mute and reorder): nothing to send, and
        // nothing worth a line in the event log each time.
        if (fresh.isEmpty()) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val kind = if (isSmsApp(sbn.packageName)) NotificationKind.GENERIC else NotificationKind.MESSAGE
        fresh.filter { it.title.isNotBlank() || it.text.isNotBlank() }.forEach {
            app.realController.forwardNotification(it.title, it.text, kind, label, watchIconFor(sbn.packageName))
        }
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
