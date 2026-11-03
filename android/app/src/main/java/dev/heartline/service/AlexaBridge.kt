package dev.heartline.service

import dev.heartline.ble.AudioHeaders
import dev.heartline.ble.MicDecoder
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.Endpoint2021
import dev.heartline.protocol.SpeechEndpointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Answers the watch's Alexa button. The watch sends start (with its settings as JSON), then streams voice live, ten
 * 20 ms SILK packets every 200 ms, until it is told to stop or reaches 30 s, then sends end. The voice is decoded as it
 * arrives, [SpeechEndpointer] decides when the speaker has finished, and each session is saved under files/alexa/ as
 * the raw voice messages (.bin) and audio (.wav). Speech-to-text and an AI come next.
 *
 * Found on the GTR 2e (2026-11-03): with acks in the wrong layout the watch buffered 2 s, timed out and restarted
 * every 10 s; a start within [RESTART_S] of the last end is still treated as such a restart and left unanswered.
 * There is no known "stop listening" command, so sessions cycle through [STOPS] and log what the watch does next.
 */
class AlexaBridge(private val scope: CoroutineScope, private val watch: WatchConnection, private val db: HeartlineDatabase, private val folder: File) {
    private var voice = ByteArrayOutputStream()
    private var packets = 0
    private var decoder: MicDecoder? = null
    private var endpointer = SpeechEndpointer()
    private val pending = ArrayList<Short>()
    private var started = 0L
    private var acked = 0L
    private var ended = 0L
    private var stopSent = 0L
    private var active = false
    private var replied = false
    private var probed = false
    private var session = 0
    private var stop = 0
    private var name = ""

    fun start() = scope.launch {
        launch(Dispatchers.IO) { decodeOld() }
        watch.chunked2021.collect { m ->
            if (m.endpoint != Endpoint2021.ALEXA || m.payload.isEmpty()) return@collect
            try {
                when (m.payload[0].toInt() and 255) {
                    Alexa.START -> begin()
                    Alexa.VOICE -> if (active) voiceArrived(m.payload)
                    Alexa.END -> { ended = System.currentTimeMillis(); if (active) finish() }
                    else -> db.events.add("info", "Alexa message: " + m.payload.take(160).joinToString("") { "%02x".format(it) } +
                        " \"" + String(m.payload, 1, m.payload.size - 1, Charsets.UTF_8).filter { it in ' '..'~' }.take(200) + "\"")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { db.events.add("warn", "Alexa bridge: ${e.message}") }
        }
    }

    private suspend fun begin() {
        val since = (System.currentTimeMillis() - ended) / 1000.0
        if (ended > 0 && since < RESTART_S) {
            db.events.add("info", "Alexa restarted by itself %.1f s after the last end; left unanswered".format(since))
            return
        }
        closeDecoder()
        active = true; replied = false; stopSent = 0; voice = ByteArrayOutputStream(); packets = 0; pending.clear()
        started = System.currentTimeMillis(); name = "alexa-%d".format(started / 1000)
        stop = session++ % STOPS.size
        endpointer = SpeechEndpointer()
        folder.mkdirs()
        decoder = withContext(Dispatchers.IO) { MicDecoder(File(folder, "$name.wav"), onPcm = ::levels) }
        if (!probed) {
            probed = true
            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.CAPABILITIES_REQUEST.toByte()))
            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.LANGUAGES_REQUEST.toByte()))
        }
        // Gadgetbridge waits ~700 ms so the link has switched to its fast interval before acking.
        delay(700); watch.send2021(Endpoint2021.ALEXA, Alexa.startAck); acked = System.currentTimeMillis()
        db.events.add("info", "Alexa started on the watch; acknowledged (will stop with: ${STOPS[stop]})")
    }

    private suspend fun voiceArrived(payload: ByteArray) {
        voice.write(payload, 1, payload.size - 1)
        val audio = Alexa.voicePackets(payload)
        packets += audio.size
        val d = decoder ?: return
        withContext(Dispatchers.IO) { audio.forEach(d::feed) }
        if (stopSent > 0 || !stopRequested) return
        stopSent = System.currentTimeMillis()
        db.events.add("info", "Alexa: ${endpointer.state.name.lowercase().replace('_', ' ')} after %.1f s of audio; sending ${STOPS[stop]}".format(packets * 0.02))
        when (stop) {
            0 -> watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.END.toByte()))
            1 -> reply()
            2 -> watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.TRIGGERED.toByte()))
        }
    }

    /** Set from [levels] (on the decoder's thread) when the endpointer says to stop. */
    @Volatile private var stopRequested = false

    private fun levels(pcm: ShortArray) {
        // The decoder's buffers need not be 20 ms long; regroup into 960-sample frames (20 ms at 48 kHz).
        pcm.forEach { pending += it }
        while (pending.size >= FRAME) {
            val frame = ShortArray(FRAME) { pending[it] }
            pending.subList(0, FRAME).clear()
            if (endpointer.feed(AudioHeaders.dbfs(frame))) stopRequested = true
        }
    }

    private suspend fun finish() {
        active = false
        closeDecoder()
        File(folder, "$name.bin").writeBytes(voice.toByteArray())
        val after = if (stopSent > 0) ", end came %d ms after the stop".format(System.currentTimeMillis() - stopSent) else ", no stop sent"
        db.events.add("info", "Alexa voice: $packets packets = %.1f s (speech %.1f s)$after, saved $name.wav".format(packets * 0.02, endpointer.speechFrames * 0.02))
        if (!replied) reply()
    }

    private suspend fun reply() {
        replied = true
        watch.send2021(Endpoint2021.ALEXA, Alexa.replyRich("Heartline", "Heard you",
            "Got %.1f s of audio, %.1f s of it speech. Speech-to-text and AI answers are coming next.".format(packets * 0.02, endpointer.speechFrames * 0.02)))
    }

    private suspend fun closeDecoder() {
        val d = decoder ?: return
        decoder = null; stopRequested = false
        withContext(Dispatchers.IO) { runCatching { d.close() } }
    }

    /** Recordings saved before the bridge decoded audio: the .bin is the voice messages without their 05 byte. */
    private fun decodeOld() = folder.listFiles { f -> f.name.endsWith(".bin") }?.forEach { bin ->
        val wav = File(bin.path.removeSuffix(".bin") + ".wav")
        if (wav.exists()) return@forEach
        runCatching {
            val d = bin.readBytes(); val audio = ArrayList<ByteArray>(); var i = 0
            while (i + 4 <= d.size) {
                val n = (d[i].toInt() and 255) or ((d[i + 1].toInt() and 255) shl 8) or ((d[i + 2].toInt() and 255) shl 16) or ((d[i + 3].toInt() and 255) shl 24)
                audio += Alexa.voicePackets(byteArrayOf(Alexa.VOICE.toByte()) + d.copyOfRange(i, minOf(d.size, i + 4 + n))); i += 4 + n
            }
            MicDecoder(wav).use { dec -> audio.forEach(dec::feed) }
        }
    }

    private companion object {
        const val RESTART_S = 12.0
        const val FRAME = 960
        val STOPS = listOf("end (02) from the phone", "the reply", "triggered (06)")
    }
}
