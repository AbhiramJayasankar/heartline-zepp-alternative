package dev.heartline.protocol

enum class NotificationKind { MESSAGE, CALL, GENERIC }
const val NOTIFICATION_MAX = 230
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

/** health.notification_payload / HuamiSupport: the GTS-family extra header prevents watch reboots. */
fun notificationPayload(title: String, body: String, kind: NotificationKind = NotificationKind.MESSAGE, app: String = "Heartline"): ByteArray {
    val heading = title.ifEmpty { " " }.codePointPrefix(32)
    val message = body.ifEmpty { " " }.codePointPrefix(512)
    if (kind == NotificationKind.CALL) return bytes(3, 0, 0, 0, 0, 0) + fit(heading, 64) + bytes(0, 0, 0, 2)
    val prefix = if (kind == NotificationKind.GENERIC) bytes(5) + EXTRA_HEADER + bytes(1)
        else bytes(0xFA) + EXTRA_HEADER + bytes(1, 11)
    val suffix = if (kind == NotificationKind.GENERIC) bytes(0, 32, 0) else "\u0000$app\u0000".toByteArray(Charsets.UTF_8)
    return prefix + fit("$heading\u0000$message\n\n", NOTIFICATION_MAX - prefix.size) + suffix
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
