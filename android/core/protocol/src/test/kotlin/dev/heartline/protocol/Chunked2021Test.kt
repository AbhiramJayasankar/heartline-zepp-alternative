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
        assertContentEquals(hex("04 00 63 01 00"), r.ack)
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
}
