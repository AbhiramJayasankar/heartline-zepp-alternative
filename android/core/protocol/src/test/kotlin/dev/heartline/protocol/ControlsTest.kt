package dev.heartline.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.*

class ControlsTest {
    @Test fun realtimeStepsCaptured() {
        assertEquals(StepsNow(536, 389, 37), parseRealtimeSteps(hex(fixture["realtime_steps"].str())))
        assertNull(parseRealtimeSteps(ByteArray(12)))
        assertEquals(StepsNow(0, 0, 0), parseRealtimeSteps(ByteArray(13)))
    }
    @Test fun deviceEventsCapturedAndUnknown() {
        assertEquals("button_pressed", deviceEventName(bytes(4)))
        assertEquals("music_control:next", deviceEventName(bytes(0xFE, 3)))
        assertEquals("empty", deviceEventName(byteArrayOf()))
        assertEquals("unknown_13", deviceEventName(bytes(0x13)))
        assertEquals("music_control:ff", deviceEventName(bytes(0xFE, 0xFF)))
        assertEquals("music_control", deviceEventName(bytes(0xFE)))
        DEVICE_EVENTS.forEach { (key, value) -> assertEquals(value, deviceEventName(bytes(key))) }
        MUSIC_COMMANDS.forEach { (key, value) -> assertEquals("music_control:$value", deviceEventName(bytes(0xFE, key))) }
    }
    @Test fun currentTimeCapturedAndEncoded() {
        assertEquals("2026-11-01T02:56:21", parseCurrentTime(hex(fixture["current_time"].str())))
        val now = ZonedDateTime.of(2026, 11, 1, 2, 56, 23, 0, ZoneOffset.ofHoursMinutes(5, 30))
        assertEquals("ea070b0102381707000016", currentTimeBytes(now).hex())
        assertEquals("2026-11-01T02:56:23", parseCurrentTime(currentTimeBytes(now)))
    }
    @Test fun currentTimeInvalidAndNegativeOffset() {
        assertNull(parseCurrentTime(ByteArray(6)))
        assertNull(parseCurrentTime(hex("00000101000000")))
        assertNull(parseCurrentTime(hex("ea07021e000000")))
        val now = ZonedDateTime.parse("2024-02-29T23:59:59-03:30")
        assertEquals("e807021d173b3b040000f2", currentTimeBytes(now).hex())
        assertEquals("2024-02-29T23:59:59", parseCurrentTime(currentTimeBytes(now)))
    }
    @Test fun notificationPythonVectorsWithDashboardSuffix() {
        assertContentEquals(bytes(0xFA, 0, 0, 0, 0, 1, 11) + "Hi\u0000there\n\n\u0000Dashboard\u0000".toByteArray(),
            notificationPayload("Hi", "there", app = "Dashboard"))
        assertContentEquals(bytes(5, 0, 0, 0, 0, 1) + "Hi\u0000there\n\n\u0000 \u0000".toByteArray(), notificationPayload("Hi", "there", NotificationKind.GENERIC))
        val long = notificationPayload("T", "é".repeat(400), app = "Dashboard")
        assertTrue(long.size <= NOTIFICATION_MAX + "\u0000Dashboard\u0000".toByteArray().size)
        strictUtf8(long.copyOfRange(7, long.size - "\u0000Dashboard\u0000".toByteArray().size))
        assertContentEquals(bytes(3, 0, 0, 0, 0, 0), notificationPayload("Mum", "", NotificationKind.CALL).take(6).toByteArray())
    }
    @Test fun notificationDefaultAppAndCallEnd() {
        assertEquals("fa00000000010b48690074686572650a0a0048656172746c696e6500", notificationPayload("Hi", "there").hex())
        assertContentEquals(bytes(3, 3, 0, 0, 0, 0), CALL_END)
        assertContentEquals(bytes(3, 0, 0, 0, 0, 0) + "Mum".toByteArray() + bytes(0, 0, 0, 2), notificationPayload("Mum", "", NotificationKind.CALL))
    }
    @Test fun allNotificationBytesMatchPython() {
        oracle["notifications"].arr().forEach { item ->
            val row = item.obj()
            val kind = NotificationKind.valueOf(row["kind"].str().uppercase())
            assertEquals(row["payload"], notificationPayload(row["title"].str(), row["body"].str(), kind).hex())
        }
    }
    @Test fun utf8CutAtEachCharacterWidthAndTitleLimit() {
        for (glyph in listOf("é", "中", "🙂")) for (n in 210..225) {
            val payload = notificationPayload("T", "x".repeat(n) + glyph.repeat(5))
            assertTrue(payload.size <= NOTIFICATION_MAX + 11)
            val message = payload.copyOfRange(7, payload.size - 11)
            strictUtf8(message)
            assertFalse(message.toString(Charsets.UTF_8).contains('\uFFFD'))
        }
        val call = notificationPayload("🙂".repeat(40), "", NotificationKind.CALL)
        assertEquals("🙂".repeat(16), strictUtf8(call.copyOfRange(6, call.size - 4)))
    }
    @Test fun chunkHeadersCaptured() {
        val chunks = chunkedWrites(ByteArray(50))
        assertEquals(listOf("000000", "004001", "008002"), chunks.map { it.take(3).toByteArray().hex() })
        assertEquals(50, chunks.sumOf { it.size - 3 })
        assertContentEquals(bytes(0, 0xC0, 0, 120), chunkedWrites(bytes(120), 247).single())
    }
    @Test fun chunkBoundaries17_18_34_35() {
        val headers = mapOf(17 to listOf(0xC0), 18 to listOf(0, 0x80), 34 to listOf(0, 0x80), 35 to listOf(0, 0x40, 0x80))
        for ((length, flags) in headers) {
            val payload = ByteArray(length) { it.toByte() }
            val chunks = chunkedWrites(payload)
            assertEquals(flags, chunks.map { it.u8(1) })
            assertTrue(chunks.all { it.size <= 20 })
            assertEquals(payload.toList(), chunks.flatMap { it.drop(3) })
            assertEquals(chunks.indices.toList(), chunks.map { it.u8(2) })
        }
    }
    @Test fun chunkEmptyMinMtuTypeAndCounterWrap() {
        assertContentEquals(bytes(0, 0xC0, 0), chunkedWrites(byteArrayOf()).single())
        assertContentEquals(bytes(0, 0xC5, 0, 7), chunkedWrites(bytes(7), mtu = 1, type = 5).single())
        val chunks = chunkedWrites(ByteArray(17 * 257))
        assertEquals(257, chunks.size)
        assertEquals(255, chunks[255].u8(2))
        assertEquals(0, chunks[256].u8(2))
        assertEquals(0x80, chunks.last().u8(1))
    }
    @Test fun settingsPythonCommandVectors() {
        assertEquals(SettingTarget.HR to "1401", settingCommand("hr_interval", 1).let { it.first to it.second.hex() })
        assertEquals(SettingTarget.CONFIG to "fe060001", settingCommand("stress_monitoring", true).let { it.first to it.second.hex() })
        assertEquals(SettingTarget.CONFIG to "061a00018c", settingCommand("hr_alert_threshold", 140, mapOf("hr_alert" to true)).let { it.first to it.second.hex() })
        assertEquals(5, validateSetting("hr_interval", 5))
    }
    @Test fun allSettingCommandsMatchPython() {
        oracle["settings"].arr().forEach { item ->
            val row = item.obj()
            val result = settingCommand(row["key"].str(), row["value"]!!, row["current"].obj())
            assertEquals(row["target"].str().uppercase(), result.first.name)
            assertEquals(row["command"], result.second.hex(), row["key"].str())
        }
    }
    @Test fun settingsDefinitionsExactlyMatchPython() {
        val expected = oracle["setting_specs"].arr().map { it.obj() }
        assertEquals(expected.map { it["key"] }, SETTINGS.map { it.key })
        SETTINGS.zip(expected).forEach { (s, e) ->
            assertEquals(e["label"], s.label)
            assertEquals(e["type"], s.type)
            assertEquals(e["target"].str().uppercase(), s.target.name)
            assertEquals((e["min"] as? Number)?.toInt(), s.min)
            assertEquals((e["max"] as? Number)?.toInt(), s.max)
            val options = (e["options"] as? List<*>) ?: emptyList<Any>()
            assertEquals(options.map { it.obj()["label"] }, s.options.map { it.label })
            assertEquals(options.map { it.obj()["value"].toString() }, s.options.map { it.value.toString() })
        }
    }
    @Test fun settingsValidationUsesPythonMessages() {
        fun message(key: String, value: Any) = assertFailsWith<IllegalArgumentException> { settingCommand(key, value) }.message
        assertEquals("hr_interval must be one of [0, 1, 5, 10, 30]", message("hr_interval", 7))
        assertEquals("Unknown setting factory_reset", message("factory_reset", true))
        assertEquals("dnd must be one of ['off', 'auto']", message("dnd", "on"))
        assertEquals("hr_alert_threshold must be between 100 and 180", message("hr_alert_threshold", 99))
        assertEquals("hr_alert_threshold must be between 100 and 180", message("hr_alert_threshold", 181))
        assertEquals("invalid literal for int() with base 10: 'nope'", message("hr_alert_threshold", "nope"))
    }
    @Test fun settingsAlertDefaultsAndPythonTruthiness() {
        assertEquals("061a000196", settingCommand("hr_alert", true).second.hex())
        assertEquals("061a00018c", settingCommand("hr_alert_threshold", 140).second.hex())
        assertEquals("061a00008c", settingCommand("hr_alert_threshold", 140, mapOf("hr_alert" to false)).second.hex())
        assertEquals("1401", settingCommand("hr_interval", true).second.hex())
        assertEquals("1401", settingCommand("hr_interval", 1.0).second.hex())
        assertEquals("fe060001", settingCommand("stress_monitoring", "false").second.hex())
        assertEquals("fe060000", settingCommand("stress_monitoring", emptyList<Int>()).second.hex())
        assertEquals("061a000196", settingCommand("hr_alert_threshold", "150").second.hex())
    }
    @Test fun settingsIntegerCoercionAndLargeValueErrors() {
        assertEquals("061a000196", settingCommand("hr_alert_threshold", "  +1_50  ").second.hex())
        assertEquals("061a000196", settingCommand("hr_alert_threshold", 150.99).second.hex())
        for (value in listOf<Any>(Long.MAX_VALUE, "999999999999999999999999999999999999999", -1e100)) {
            assertEquals("hr_alert_threshold must be between 100 and 180", assertFailsWith<IllegalArgumentException> {
                settingCommand("hr_alert_threshold", value)
            }.message)
        }
        assertEquals("bytes must be in range(0, 256)", assertFailsWith<IllegalArgumentException> {
            settingCommand("hr_alert", true, mapOf("hr_alert_threshold" to Long.MAX_VALUE))
        }.message)
    }
    private fun strictUtf8(data: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
}
