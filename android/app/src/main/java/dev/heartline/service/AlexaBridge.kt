package dev.heartline.service

import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.Endpoint2021
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Answers the watch's Alexa button. The watch sends start (with its settings as JSON), voice frames, then end; it
 * waits about 5 s for a start acknowledgement before giving up ("Connecting..."). For now the reply reports what
 * arrived and the voice is saved under files/alexa/ so its codec can be decoded; speech-to-text and an AI come next.
 */
class AlexaBridge(private val scope: CoroutineScope, private val watch: WatchConnection, private val db: HeartlineDatabase, private val folder: File) {
    private var voice = ByteArrayOutputStream()
    private var frames = 0
    private var started = 0L

    fun start() = scope.launch {
        watch.chunked2021.collect { m ->
            if (m.endpoint != Endpoint2021.ALEXA || m.payload.isEmpty()) return@collect
            try {
                when (m.payload[0].toInt() and 255) {
                    Alexa.START -> {
                        voice = ByteArrayOutputStream(); frames = 0; started = System.currentTimeMillis()
                        // Gadgetbridge waits ~700 ms so the link has switched to its fast interval before acking.
                        delay(700); watch.send2021(Endpoint2021.ALEXA, Alexa.startAck)
                        db.events.add("info", "Alexa started on the watch; acknowledged")
                    }
                    Alexa.VOICE -> { voice.write(m.payload, 1, m.payload.size - 1); frames++ }
                    Alexa.END -> finish()
                    else -> db.events.add("info", "Alexa message %02x (%d bytes)".format(m.payload[0].toInt() and 255, m.payload.size))
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { db.events.add("warn", "Alexa bridge: ${e.message}") }
        }
    }

    private suspend fun finish() {
        val bytes = voice.toByteArray()
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        folder.mkdirs()
        val file = File(folder, "alexa-%d.bin".format(started / 1000)).apply { writeBytes(bytes) }
        db.events.add("info", "Alexa voice: $frames frames, ${bytes.size} bytes in %.1f s, saved to ${file.name}".format(seconds))
        watch.send2021(Endpoint2021.ALEXA, Alexa.replyRich("Heartline", "Heard you", "Got $frames voice frames (${bytes.size} bytes). Speech-to-text and AI answers are coming next."))
    }
}
