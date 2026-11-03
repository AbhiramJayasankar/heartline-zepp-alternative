package dev.heartline.service

import dev.heartline.ble.MicDecoder
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.Endpoint2021
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Answers the watch's Alexa button. The watch sends start (with its settings as JSON), voice, then end; it waits
 * about 5 s for a start acknowledgement before giving up ("Connecting..."). Each session is saved under files/alexa/
 * as the raw voice messages (.bin) and decoded audio (.wav). Speech-to-text and an AI come next.
 *
 * Found on the GTR 2e (2026-11-03): once acked, the watch sends exactly 2 s of voice (100 SILK packets) in one burst
 * ~4.3 s after the ack, ends, and starts again 5.1 s later, over and over, whatever the reply: a card, plain text, an
 * error, 0b "voice more" (after the ack, every 0.5 s, or on the first voice) or a reply before the end all behave the
 * same. 06 "triggered" after the ack makes it send no voice. Without an ack it ends once and stops, so a start that
 * comes within [RESTART_S] of the last end is treated as the automatic restart and left unanswered.
 */
class AlexaBridge(private val scope: CoroutineScope, private val watch: WatchConnection, private val db: HeartlineDatabase, private val folder: File) {
    private var voice = ByteArrayOutputStream()
    private val packets = ArrayList<ByteArray>()
    private val arrivals = ArrayList<Long>()
    private var started = 0L
    private var acked = 0L
    private var ended = 0L
    private var active = false
    private var probed = false

    fun start() = scope.launch {
        launch(Dispatchers.IO) { decodeOld() }
        watch.chunked2021.collect { m ->
            if (m.endpoint != Endpoint2021.ALEXA || m.payload.isEmpty()) return@collect
            try {
                when (m.payload[0].toInt() and 255) {
                    Alexa.START -> {
                        val since = (System.currentTimeMillis() - ended) / 1000.0
                        if (ended > 0 && since < RESTART_S) {
                            active = false
                            db.events.add("info", "Alexa restarted by itself %.1f s after the last end; left unanswered".format(since))
                            return@collect
                        }
                        active = true; voice = ByteArrayOutputStream(); packets.clear(); arrivals.clear(); started = System.currentTimeMillis()
                        if (!probed) {
                            probed = true
                            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.CAPABILITIES_REQUEST.toByte()))
                            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.LANGUAGES_REQUEST.toByte()))
                        }
                        // Gadgetbridge waits ~700 ms so the link has switched to its fast interval before acking.
                        delay(700); watch.send2021(Endpoint2021.ALEXA, Alexa.startAck); acked = System.currentTimeMillis()
                        db.events.add("info", "Alexa started on the watch; acknowledged")
                    }
                    Alexa.VOICE -> if (active) {
                        voice.write(m.payload, 1, m.payload.size - 1)
                        packets += Alexa.voicePackets(m.payload); arrivals += System.currentTimeMillis() - acked
                    }
                    Alexa.END -> { ended = System.currentTimeMillis(); if (active) { active = false; finish() } }
                    else -> db.events.add("info", "Alexa message: " + m.payload.take(160).joinToString("") { "%02x".format(it) } +
                        " \"" + String(m.payload, 1, m.payload.size - 1, Charsets.UTF_8).filter { it in ' '..'~' }.take(200) + "\"")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { db.events.add("warn", "Alexa bridge: ${e.message}") }
        }
    }

    private suspend fun finish() {
        val name = "alexa-%d".format(started / 1000)
        folder.mkdirs()
        File(folder, "$name.bin").writeBytes(voice.toByteArray())
        val audio = packets.toList()
        val decoded = withContext(Dispatchers.IO) { runCatching { decode(audio, File(folder, "$name.wav")) } }
        val timing = if (arrivals.isEmpty()) "no voice" else "voice ${arrivals.first()} to ${arrivals.last()} ms after the ack"
        db.events.add("info", "Alexa voice: ${audio.size} packets = %.1f s, $timing, saved $name%s"
            .format(audio.size * 0.02, decoded.exceptionOrNull()?.let { " (decode failed: ${it.message})" } ?: ".wav"))
        watch.send2021(Endpoint2021.ALEXA, Alexa.replyRich("Heartline", "Heard you", "Got %.1f s of audio. Speech-to-text and AI answers are coming next.".format(audio.size * 0.02)))
    }

    private fun decode(audio: List<ByteArray>, wav: File) = MicDecoder(wav).use { d -> audio.forEach(d::feed) }

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
            decode(audio, wav)
        }
    }

    private companion object { const val RESTART_S = 12.0 }
}
