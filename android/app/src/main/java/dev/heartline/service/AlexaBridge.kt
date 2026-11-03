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
import kotlinx.coroutines.Job
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
 * The working sequence (2026-11-03): ack the start, take the live voice, send 06 (triggered) when speech ends, which
 * turns "Listening" into "Thinking", then a simple reply (09), which the watch shows. It waits ~40 s for the reply
 * before "I didn't hear you clearly". The card (08 06) is acked but never shown; neither is one without its length.
 *
 * With acks in the wrong layout the watch buffered 2 s, timed out and restarted every 10 s; with the right ones it
 * does not, so every start is answered (the follow-up Alexa button after a reply starts a new session).
 * There is no known "stop listening" command. 02 (end) from the phone does not stop it and even lifts its 30 s limit;
 * with the stalled acks, 06 (triggered) was the one command that made it drop its buffered voice. So once speech
 * ends the bridge works through [STOPS], moving on whenever voice is still arriving [ESCALATE_MS] later.
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
                    Alexa.VOICE -> if (active) voiceArrived(m.payload) else orphanVoice()
                    Alexa.END -> {
                        ended = System.currentTimeMillis()
                        if (orphanAt > 0) { db.events.add("info", "Alexa: the stuck session ended %d ms after sending ${ORPHAN_STOPS[orphanStep - 1]}".format(ended - orphanAt)); orphanAt = 0; orphanStep = 0 }
                        tourJob?.let { if (it.isActive) { it.cancel(); db.events.add("info", "Alexa: the watch ended the session %.1f s after the last reply".format((ended - repliedAt) / 1000.0)) } }
                        if (active) finish()
                    }
                    else -> db.events.add("info", "Alexa message: " + m.payload.take(160).joinToString("") { "%02x".format(it) } +
                        " \"" + String(m.payload, 1, m.payload.size - 1, Charsets.UTF_8).filter { it in ' '..'~' }.take(200) + "\"")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { db.events.add("warn", "Alexa bridge: ${e.message}") }
        }
    }

    private suspend fun begin() {
        val now = System.currentTimeMillis()
        val gap = listOfNotNull(
            if (repliedAt > 0) "%.1f s after the last reply".format((now - repliedAt) / 1000.0) else null,
            if (ended > 0) "%.1f s after the last end".format((now - ended) / 1000.0) else null,
        ).joinToString(", ").ifEmpty { "first this run" }
        tourJob?.cancel()
        closeDecoder()
        active = true; replied = false; stopSent = 0; voice = ByteArrayOutputStream(); packets = 0; pending.clear()
        started = System.currentTimeMillis(); name = "alexa-%d".format(started / 1000)
        stop = 0; session++
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
        db.events.add("info", "Alexa started on the watch ($gap); acknowledged")
    }

    private suspend fun voiceArrived(payload: ByteArray) {
        voice.write(payload, 1, payload.size - 1)
        val audio = Alexa.voicePackets(payload)
        packets += audio.size
        val d = decoder ?: return
        withContext(Dispatchers.IO) { audio.forEach(d::feed) }
        if (!stopRequested) return
        val now = System.currentTimeMillis()
        if (stopSent > 0 && now - stopSent < ESCALATE_MS) return
        if (stop >= STOPS.size) return
        // Still streaming: escalate to the next way of telling the watch to stop.
        stopSent = now
        db.events.add("info", "Alexa: ${endpointer.state.name.lowercase().replace('_', ' ')}, %.1f s of audio; sending ${STOPS[stop]}".format(packets * 0.02))
        when (stop++) {
            0 -> {
                // 06 moves the watch from "Listening" to "Thinking" and stops the voice; it then waits for the reply.
                watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.TRIGGERED.toByte()))
                delay(600); if (!replied) reply()
            }
            1 -> if (!replied) reply()
            2 -> watch.send2021(Endpoint2021.ALEXA, Alexa.error(3, "Heartline could not stop listening"))
        }
    }

    private var orphanAt = 0L
    private var orphanStep = 0

    /** Voice with no session open here (e.g. after the app restarted mid-session): the watch is stuck listening. */
    private suspend fun orphanVoice() {
        val now = System.currentTimeMillis()
        if (orphanAt > 0 && now - orphanAt < ESCALATE_MS) return
        if (orphanStep >= ORPHAN_STOPS.size) return
        if (orphanStep > 0) db.events.add("info", "Alexa: voice still arriving %d ms after sending ${ORPHAN_STOPS[orphanStep - 1]}".format(now - orphanAt))
        orphanAt = now
        db.events.add("info", "Alexa: stuck session, sending ${ORPHAN_STOPS[orphanStep]}")
        watch.send2021(Endpoint2021.ALEXA, when (orphanStep++) {
            0 -> byteArrayOf(Alexa.TRIGGERED.toByte())
            1 -> Alexa.replySimple("Listening was stuck.")
            2 -> Alexa.replySimple("Listening was stuck.")
            3 -> Alexa.error(3, "Heartline could not stop listening")
            else -> byteArrayOf(Alexa.END.toByte())
        })
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
        val after = if (stopSent > 0) ", end came %d ms after sending ${STOPS[stop - 1]}".format(System.currentTimeMillis() - stopSent) else ", no stop sent"
        db.events.add("info", "Alexa voice: $packets packets = %.1f s (speech %.1f s)$after, saved $name.wav".format(packets * 0.02, endpointer.speechFrames * 0.02))
        if (!replied) reply()
    }

    private var repliedAt = 0L
    private var tourJob: Job? = null
    private val tourFile get() = File(folder, ".tour")

    private suspend fun reply() {
        replied = true; repliedAt = System.currentTimeMillis()
        val step = runCatching { tourFile.readText().trim().toInt() }.getOrDefault(0)
        if (step < AlexaReplyTour.stops.size) {
            tourFile.writeText("${step + 1}")
            tourJob = scope.launch { tour(AlexaReplyTour.stops[step], step) }
            return
        }
        // The simple reply (09) shows on the GTR 2e; the card (08 06) is acked but left on "Thinking".
        watch.send2021(Endpoint2021.ALEXA, Alexa.replySimple("Heard %.1f s. Speech-to-text and AI answers are coming next.".format(packets * 0.02)))
    }

    /** One stop of [AlexaReplyTour]: lettered candidates 3 s apart, then the plain fallback. */
    private suspend fun tour(stop: AlexaReplyTour.Stop, step: Int) {
        db.events.add("info", "Alexa reply tour ${step + 1}/${AlexaReplyTour.stops.size}: ${stop.title}")
        for (c in stop.candidates) {
            watch.send2021(Endpoint2021.ALEXA, c.payload); repliedAt = System.currentTimeMillis()
            db.events.add("info", "Alexa reply tour: sent ${c.letter} (${c.what})")
            delay(3000)
        }
        stop.fallback?.let { watch.send2021(Endpoint2021.ALEXA, Alexa.replySimple(it)); repliedAt = System.currentTimeMillis(); db.events.add("info", "Alexa reply tour: sent the fallback") }
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
        const val FRAME = 960
        const val ESCALATE_MS = 1500L
        val ORPHAN_STOPS = listOf("triggered (06)", "a card", "a simple reply", "an error (0f)", "end (02)")
        val STOPS = listOf("triggered (06)", "the reply again", "an error (0f)")
    }
}
