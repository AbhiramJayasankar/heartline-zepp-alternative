package dev.heartline.protocol

import org.junit.Test
import kotlin.test.*

class DeviceTest {
    @Test fun batteryCaptured() {
        val raw = hex("0f4400ea070a180a322316ea070a1f040a3a164f")
        val b = parseBattery(raw)
        assertEquals(68, b.level)
        assertFalse(b.charging)
        assertEquals("2026-10-24T10:50:35+05:30", b.lastChargeA)
        assertEquals("2026-10-31T04:10:58+05:30", b.lastChargeB)
        assertEquals(raw.hex(), b.raw)
    }
    @Test fun batteryChargingShort() {
        assertEquals(BatteryInfo(32, true, null, null, "0f2001"), parseBattery(hex("0f2001")))
    }
    @Test fun batteryInvalidDatesAndOffsets() {
        assertNull(parseBattery(hex("0f0000ea070d0100000016")).lastChargeA)
        assertNull(parseBattery(hex("0f00000000010100000000")).lastChargeA)
        assertEquals("2026-01-01T00:00:00-05:30", parseBattery(hex("0f0000ea070101000000ea")).lastChargeA)
        assertEquals("2026-01-01T00:00:00+00:00", parseBattery(hex("0f0000ea070101000000")).lastChargeA)
        assertEquals("2026-01-01T00:00:00+23:45", parseBattery(hex("0f0000ea0701010000005f")).lastChargeA)
        assertFailsWith<IllegalArgumentException> { parseBattery(hex("0f0000ea07010100000060")) }
    }
    @Test fun heartRateU8AndU16() {
        assertEquals(83, parseHeartRate(bytes(0, 83)))
        assertEquals(300, parseHeartRate(hex("012c01")))
        assertEquals(255, parseHeartRate(hex("00ff")))
        assertEquals(44, parseHeartRate(hex("012c")))
    }
    @Test fun authKnownAesVector() {
        val key = ByteArray(16) { it.toByte() }
        val reply = hex("108201") + ByteArray(16) { (100 + it).toByte() }
        assertContentEquals(hex("83009ebf6e9cbd1cee780d07158891e5849b"), Auth.response(key, reply))
        assertContentEquals(hex("83009ebf6e9cbd1cee780d07158891e5849b"), Auth.response(key, reply + bytes(99)))
        assertContentEquals(hex("8200020100"), Auth.REQUEST_RANDOM)
    }
    @Test fun authRejectsBadRepliesAndKeys() {
        val e = assertFailsWith<IllegalArgumentException> { Auth.response(ByteArray(16), hex("108204")) }
        assertEquals("unexpected random reply 108204", e.message)
        assertFailsWith<IllegalArgumentException> { Auth.response(ByteArray(16), hex("108201") + ByteArray(15)) }
        assertEquals("Incorrect AES key length (24 bytes)", assertFailsWith<IllegalArgumentException> {
            Auth.response(ByteArray(24), hex("108201") + ByteArray(16))
        }.message)
    }
    @Test fun authSuccessPrefix() {
        assertTrue(Auth.isSuccess(hex("108301")))
        assertTrue(Auth.isSuccess(hex("10830100")))
        assertFalse(Auth.isSuccess(hex("108304")))
        assertFalse(Auth.isSuccess(hex("1083")))
    }
    @Test fun infoAsciiAndBinary() {
        assertEquals("V1.7.3.1", decodeInfo("V1.7.3.1\u0000".toByteArray()))
        assertEquals("0102", decodeInfo(bytes(1, 2)))
        assertEquals("0000", decodeInfo(bytes(0, 0)))
        assertEquals("", decodeInfo(byteArrayOf()))
        assertEquals("410042", decodeInfo(bytes(65, 0, 66)))
    }
    @Test fun uuidNamesAndCommands() {
        val expected = mapOf("serial" to "2a25", "hardware" to "2a27", "software" to "2a28", "system_id" to "2a23", "pnp_id" to "2a50")
        assertEquals(expected.keys, DEVICE_INFO.keys)
        expected.forEach { (name, short) -> assertEquals("0000$short-0000-1000-8000-00805f9b34fb", DEVICE_INFO[name].toString()) }
        val huami = listOf(Uuids.AUTH to "0009", Uuids.BATTERY to "0006", Uuids.RAW_CTRL to "0001", Uuids.RAW_DATA to "0002",
            Uuids.ACT_CTRL to "0004", Uuids.ACT_DATA to "0005", Uuids.CONFIG to "0003", Uuids.REALTIME_STEPS to "0007",
            Uuids.DEVICE_EVENT to "0010", Uuids.CHUNKED to "0020")
        huami.forEach { (uuid, short) -> assertEquals("0000$short-0000-3512-2118-0009af100700", uuid.toString()) }
        listOf(Uuids.HR_MEASURE to "2a37", Uuids.HR_CONTROL to "2a39", Uuids.ALERT_LEVEL to "2a06", Uuids.CURRENT_TIME to "2a2b")
            .forEach { (uuid, short) -> assertEquals("0000$short-0000-1000-8000-00805f9b34fb", uuid.toString()) }
        assertContentEquals(hex("150200"), HrCommands.STOP_MANUAL)
        assertContentEquals(hex("150101"), HrCommands.START_CONTINUOUS)
        assertContentEquals(hex("150100"), HrCommands.STOP_CONTINUOUS)
        assertContentEquals(hex("16"), HrCommands.PING)
        assertContentEquals(bytes(3), ALERT_FIND)
        assertContentEquals(bytes(0), ALERT_OFF)
    }
    @Test fun commandArraysAreFresh() {
        Auth.REQUEST_RANDOM.fill(0)
        assertContentEquals(hex("8200020100"), Auth.REQUEST_RANDOM)
        HrCommands.PING.fill(0)
        assertContentEquals(bytes(0x16), HrCommands.PING)
    }
}
