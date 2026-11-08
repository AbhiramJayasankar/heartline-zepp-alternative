package dev.heartline.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Chunked2021Test {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodesTheWatchesAlexaStartAndAcksIt() {
        // Captured from the GTR 2e on 2026-11-03 21:34:23 (JSON shortened).
        val json = "{\"countdown\":1440}".toByteArray()
        val payload = hex("01 02 06 01") + json
        val frame = hex("03 07 63 00") + byteArrayOf(payload.size.toByte(), 0, 0, 0) + hex("11 00") + payload
        val r = Chunked2021Decoder().feed(frame)
        assertContentEquals(hex("04 63 01 00"), r.ack) // the watch's own ack layout
        assertEquals(Endpoint2021.ALEXA, r.message!!.endpoint)
        assertContentEquals(payload, r.message!!.payload)
    }

    @Test fun reassemblesAcrossChunks() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = chunked2021Frames(Endpoint2021.ALEXA, payload, handle = 5)
        val d = Chunked2021Decoder()
        val results = frames.map { d.feed(it) }
        results.dropLast(1).forEach { assertNull(it.message) }
        assertContentEquals(payload, assertNotNull(results.last().message).payload)
    }

    @Test fun framesMatchGadgetbridgeLayout() {
        val f = chunked2021Frames(Endpoint2021.ALEXA, Alexa.startAck, handle = 1).single()
        assertContentEquals(hex("03 07 01 00 02 00 00 00 11 00 03 00"), f)
        val reply = Alexa.replySimple("Hi")
        assertContentEquals(hex("09 48 69 00"), reply)
    }

    @Test fun splitsVoiceIntoOpusPackets() {
        // Start of a voice message captured on 2026-11-03 21:40:52, cut to its first two packets.
        val packets = hex("0e 0885908d40b5056b3a135c604bb0") + hex("03 08bce2")
        val payload = hex("05") + byteArrayOf(packets.size.toByte(), 0, 0, 0) + packets
        val out = Alexa.voicePackets(payload)
        assertEquals(listOf(14, 3), out.map { it.size })
        assertEquals(0x08, out[1][0].toInt())
    }

    // Layouts follow the Zepp app 10.9.0 encoders (jq0/iq0 for alerts, h1r for lists, uav/zok for weather).
    @Test fun timerIsAnAlertWithHoursMinutesSeconds() {
        assertContentEquals(hex("08 02 00 08000000 01 03 74 31 00 00 05 1e"), Alexa.timer("t1", 0, 5, 30))
    }

    @Test fun deleteNamesTheKindAndToken() {
        assertContentEquals(hex("08 02 00 05000000 02 01 61 31 00"), Alexa.deleteAlert(Alexa.ALERT_ALARM, "a1"))
    }

    @Test fun alarmCarriesLocalTimeAndDays() {
        val a = Alexa.alarm("a", 0x01020304, Alexa.WEEKDAYS)
        assertContentEquals(hex("08 02 00 12000000 01 01 61 00 00 00 04030201 00000000 1f000000"), a)
    }

    @Test fun listRowsEndWithAnEmptyCode() {
        assertContentEquals(hex("08 03 00 0a000000 54 00 01 61 00 62 00 00 53 00"), Alexa.list("T", "S", listOf("a" to "b")))
    }

    @Test fun weatherStartsWithZeppsFieldMask() {
        val w = Alexa.weather(100, "L", "D", "Sunny", Alexa.Icon("Sunny"), "20", "25", "15", emptyList(), tzOffsetSec = 3600)
        assertContentEquals(hex("08 01 00"), w.copyOfRange(0, 3))
        assertContentEquals(hex("fd030000 740e0000"), w.copyOfRange(7, 15))
    }

    @Test fun userInfoModuleWritesFlagsThenFields() {
        assertContentEquals(hex("01 20000000 02"), wearLocation2021(right = false))
        assertContentEquals(hex("01 20000000 82"), wearLocation2021(right = true))
        assertContentEquals(hex("01 10000000 10270000"), stepGoal2021(10000))
        val classic = userInfoCommand(1999, 4, 7, "male", 175, 70.0, 1234)
        assertContentEquals(hex("01 0f000000 cf07 04 07 00 af00 b036"), userInfo2021FromClassic(classic))
    }

    @Test fun userInfoModuleAcceptsOnlyTwoOne() {
        assertEquals(true, userInfo2021Accepted(hex("0201")))
        assertEquals(false, userInfo2021Accepted(hex("0202")))
        assertEquals(false, userInfo2021Accepted(null))
    }
}
