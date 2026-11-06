package dev.heartline.protocol

enum class NotificationKind { MESSAGE, CALL, GENERIC }
/**
 * The most bytes one notification may take, from the first byte to the app name's terminating zero. The GTR 2e showed
 * 1,024 bytes in full and restarted on 1,025 (tested 2026-11-06); Gadgetbridge's 230 was only a cautious limit.
 */
const val NOTIFICATION_MAX = 1024
val EXTRA_HEADER: ByteArray get() = ByteArray(4)
val CALL_END: ByteArray get() = bytes(3, 3, 0, 0, 0, 0)

// Python slices Unicode code points; Kotlin's take() alone would slice UTF-16 surrogate pairs.
private fun String.codePointPrefix(limit: Int): String = substring(0, offsetByCodePoints(0, minOf(limit, codePointCount(0, length))))
private fun fit(text: String, limit: Int): ByteArray {
    val raw = text.toByteArray(Charsets.UTF_8)
    if (raw.size <= limit) return raw
    var end = limit
    while (end > 0 && raw.u8(end) and 0xC0 == 0x80) end--
    return raw.copyOf(end)
}

/** Built-in watch icons (Gadgetbridge HuamiIcon). The byte after the alert count picks one for custom alerts. */
object WatchIcon {
    const val WECHAT = 0; const val FACEBOOK = 3; const val TWITTER = 4; const val SNAPCHAT = 6; const val WHATSAPP = 7
    const val ALARM_CLOCK = 10; const val APP = 11; const val INSTAGRAM = 12; const val CHAT_BLUE = 13; const val CALENDAR = 21
    const val MESSENGER = 22; const val VIBER = 23; const val LINE = 24; const val TELEGRAM = 25; const val KAKAOTALK = 26
    const val SKYPE = 27; const val VKONTAKTE = 28; const val HANGOUTS = 30; const val EMAIL = 34
}

/** Icon for an Android package, following Gadgetbridge's app-to-icon mapping; unknown apps get the generic icon. */
fun watchIconFor(packageName: String): Int = when (packageName) {
    "com.whatsapp", "com.whatsapp.w4b" -> WatchIcon.WHATSAPP
    "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus" -> WatchIcon.TELEGRAM
    "com.instagram.android", "com.instagram.barcelona", "com.google.android.apps.photos" -> WatchIcon.INSTAGRAM
    "com.facebook.orca", "com.facebook.mlite", "org.thoughtcrime.securesms" -> WatchIcon.MESSENGER
    "com.facebook.katana", "com.facebook.lite" -> WatchIcon.FACEBOOK
    "com.snapchat.android" -> WatchIcon.SNAPCHAT
    "com.twitter.android", "com.x.android" -> WatchIcon.TWITTER
    "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
    "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "com.google.android.gm.lite" -> WatchIcon.EMAIL
    "com.google.android.calendar", "com.samsung.android.calendar" -> WatchIcon.CALENDAR
    "com.viber.voip", "com.discord" -> WatchIcon.VIBER
    "jp.naver.line.android" -> WatchIcon.LINE
    "com.kakao.talk" -> WatchIcon.KAKAOTALK
    "com.skype.raider", "com.microsoft.teams" -> WatchIcon.SKYPE
    "com.vkontakte.android" -> WatchIcon.VKONTAKTE
    "com.google.android.apps.dynamite", "com.Slack" -> WatchIcon.HANGOUTS
    "com.wire", "ch.threema.app" -> WatchIcon.CHAT_BLUE
    "com.tencent.mm" -> WatchIcon.WECHAT
    "com.google.android.deskclock", "com.sec.android.app.clockpackage" -> WatchIcon.ALARM_CLOCK
    else -> WatchIcon.APP
}

/** Text-message apps use the watch's SMS alert, which has its own icon (no custom icon byte). */
fun isSmsApp(packageName: String): Boolean = packageName in setOf(
    "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging", "com.textra")

/**
 * health.notification_payload / HuamiSupport: the GTS-family extra header prevents watch reboots. [icon] is a [WatchIcon].
 * The whole payload stays within [max] bytes, the message cut at a UTF-8 character boundary.
 */
fun notificationPayload(title: String, body: String, kind: NotificationKind = NotificationKind.MESSAGE, app: String = "Heartline",
                        icon: Int = WatchIcon.APP, max: Int = NOTIFICATION_MAX): ByteArray {
    val heading = title.ifEmpty { " " }.codePointPrefix(32)
    val message = body.ifEmpty { " " }.codePointPrefix(max)
    if (kind == NotificationKind.CALL) return bytes(3, 0, 0, 0, 0, 0) + fit(heading, 64) + bytes(0, 0, 0, 2)
    val prefix = if (kind == NotificationKind.GENERIC) bytes(5) + EXTRA_HEADER + bytes(1)
        else bytes(0xFA) + EXTRA_HEADER + bytes(1, icon and 255)
    val suffix = if (kind == NotificationKind.GENERIC) bytes(0, 32, 0) else "\u0000$app\u0000".toByteArray(Charsets.UTF_8)
    return prefix + fit("$heading\u0000$message\n\n", max - prefix.size - suffix.size) + suffix
}

/** health.chunked_writes / HuamiSupport.writeToChunkedOld; keep MTU 23 unless the watch requests more. */
fun chunkedWrites(payload: ByteArray, mtu: Int = 23, type: Int = 0): List<ByteArray> {
    require(type in 0..255) { "bytes must be in range(0, 256)" }
    val size = maxOf(17L, mtu.toLong() - 6).toInt()
    val count = if (payload.isEmpty()) 1 else ((payload.size.toLong() + size - 1) / size).toInt()
    return (0 until count).map { n ->
        val last = n == count - 1
        val flags = if (last) 0x80 or (if (n == 0) 0x40 else 0) else if (n != 0) 0x40 else 0
        bytes(0, flags or type, n and 255) + payload.copyOfRange(n * size, minOf(payload.size.toLong(), (n + 1L) * size).toInt())
    }
}
