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
 * before "I didn't hear you clearly". A reply tour (2026-11-03) found nothing else it shows: a ~700-character simple
 * reply with newlines scrolls in full (a trailing byte after it is ignored and does not reopen the mic); the 08 cards
 * (subtype 06 as Gadgetbridge sends it, 1-byte or big-endian subtype, subtypes 03 to 05, 07, no length) are ignored or
 * answered "not support this function"; weather (08/01) and reminders (08/02) are ignored; commands 07, 0c, 0d, 0e too.
 *
 * With acks in the wrong layout the watch buffered 2 s, timed out and restarted every 10 s; with the right ones it
 * does not, so every start is answered. 0b after a reply (Alexa's ExpectSpeech) makes the watch listen again by itself
 * once the reply has shown (~2 s), with the reply kept above the listening icon: a continued conversation. The small Alexa button shown under a reply does not send a new start: the
 * watch just streams voice again (opening with its start tone), so voice after a reply begins a new turn.
 * There is no known "stop listening" command. 02 (end) from the phone does not stop it and even lifts its 30 s limit;
 * with the stalled acks, 06 (triggered) was the one command that made it drop its buffered voice. So once speech
 * ends the bridge works through [STOPS], moving on whenever voice is still arriving [ESCALATE_MS] later.
 */
class AlexaBridge(private val scope: CoroutineScope, private val watch: WatchConnection, private val db: HeartlineDatabase, private val folder: File,
                  private val speech: VoskSpeech, private val assistant: SampleAssistant) {
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
    private var turn = 0
    private var stop = 0
    private var name = ""

    fun start() = scope.launch {
        launch(Dispatchers.IO) { decodeOld() }
        launch { sttSelfTest() }
        watch.chunked2021.collect { m ->
            if (m.endpoint != Endpoint2021.ALEXA || m.payload.isEmpty()) return@collect
            try {
                when (m.payload[0].toInt() and 255) {
                    Alexa.START -> begin()
                    Alexa.VOICE -> if (active) voiceArrived(m.payload) else orphanVoice()
                    Alexa.END -> {
                        ended = System.currentTimeMillis()
                        if (orphanAt > 0) { db.events.add("info", "Alexa: the stuck session ended %d ms after sending ${ORPHAN_STOPS[orphanStep - 1]}".format(ended - orphanAt)); orphanAt = 0; orphanStep = 0 }
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
        session++; turn = 0
        newTurn()
        if (!probed) {
            probed = true
            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.CAPABILITIES_REQUEST.toByte()))
            watch.send2021(Endpoint2021.ALEXA, byteArrayOf(Alexa.LANGUAGES_REQUEST.toByte()))
        }
        // Gadgetbridge waits ~700 ms so the link has switched to its fast interval before acking.
        delay(700); watch.send2021(Endpoint2021.ALEXA, Alexa.startAck); acked = System.currentTimeMillis()
        db.events.add("info", "Alexa started on the watch ($gap); acknowledged")
    }

    /** Per-turn state. The follow-up Alexa button after a reply reopens the mic in the same session (voice again, no start). */
    private suspend fun newTurn() {
        answerJob?.cancel(); answerJob = null
        closeDecoder()
        synchronized(pcm16) { pcm16.reset() }; down = 0; downN = 0
        active = true; replied = false; stopSent = 0; voice = ByteArrayOutputStream(); packets = 0; pending.clear()
        started = System.currentTimeMillis(); name = "alexa-%d".format(started / 1000)
        stop = 0; turn++
        endpointer = SpeechEndpointer()
        folder.mkdirs()
        decoder = withContext(Dispatchers.IO) { MicDecoder(File(folder, "$name.wav"), onPcm = ::levels) }
    }

    private suspend fun voiceArrived(payload: ByteArray) {
        if (replied) {
            // Voice after our reply: the follow-up button. Save the last turn and listen to the new one.
            saveTurn()
            newTurn()
            db.events.add("info", "Alexa follow-up: turn $turn, %.1f s after the reply".format((System.currentTimeMillis() - repliedAt) / 1000.0))
        }
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
                replied = true
                answerJob = scope.launch { respond() }
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
        // Keep a 16 kHz copy (average of three 48 kHz samples; the audio is narrowband) for speech-to-text.
        synchronized(pcm16) {
            pcm.forEach { v -> down += v; if (++downN == 3) { pcm16.add((down / 3).toShort()); down = 0; downN = 0 } }
        }
        while (pending.size >= FRAME) {
            val frame = ShortArray(FRAME) { pending[it] }
            pending.subList(0, FRAME).clear()
            if (endpointer.feed(AudioHeaders.dbfs(frame))) stopRequested = true
        }
    }

    private suspend fun finish() {
        active = false
        val wasReplied = replied
        saveTurn()
        if (!wasReplied) reply()
    }

    private suspend fun saveTurn() {
        closeDecoder()
        File(folder, "$name.bin").writeBytes(voice.toByteArray())
        val after = if (stopSent > 0) ", stopped with ${STOPS[stop - 1]}" else ", no stop sent"
        db.events.add("info", "Alexa voice, turn $turn: $packets packets = %.1f s (speech %.1f s)$after, saved $name.wav".format(packets * 0.02, endpointer.speechFrames * 0.02))
    }

    private var repliedAt = 0L
    private var answerJob: Job? = null
    private val pcm16 = ShortBuffer()
    private var down = 0
    private var downN = 0

    /**
     * Debug: if files/alexa/.stt-test names a 16 kHz WAV, transcribe it once per recognizer mode at app start and log
     * each result, so speech-to-text can be tuned without the watch.
     */
    private suspend fun sttSelfTest() {
        val flag = File(folder, ".stt-test").takeIf { it.exists() } ?: return
        val wav = File(folder, flag.readText().trim()); flag.delete()
        val pcm = runCatching { val d = wav.readBytes(); ShortArray((d.size - 44) / 2) { i -> ((d[44 + 2 * i].toInt() and 255) or (d[45 + 2 * i].toInt() shl 8)).toShort() } }
            .getOrElse { db.events.add("warn", "STT test: ${it.message}"); return }
        repeat(2) { // the first run includes unpacking and loading the model
            val t0 = System.currentTimeMillis()
            val r = runCatching { speech.transcribe(pcm) }
            db.events.add("info", "STT test %d ms: ".format(System.currentTimeMillis() - t0) + r.fold({ "\"${it.text}\"" }, { "failed: ${it.message}" }))
        }
    }

    private fun wav16k(pcm: ShortArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16_000).putInt(32_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    /** A growable ShortArray. */
    private class ShortBuffer {
        private var a = ShortArray(16_000 * 8); var size = 0; private set
        fun add(v: Short) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun reset() { size = 0 }
        fun snapshot() = a.copyOf(size)
    }

    /** Speech-to-text on the turn's audio, then a sample answer (see [SampleAssistant]); the watch is on "Thinking". */
    private suspend fun respond() {
        val audio = synchronized(pcm16) { pcm16.snapshot() }
        runCatching { File(folder, "$name-16k.wav").writeBytes(wav16k(audio)) }
        val t0 = System.currentTimeMillis()
        val transcript = runCatching { speech.transcribe(audio) }
        val heard = transcript.getOrNull()?.text.orEmpty()
        db.events.add("info", transcript.fold(
            { "Alexa heard (${it.engine}, %.1f s audio, %d ms): \"${it.text}\"".format(audio.size / 16_000.0, System.currentTimeMillis() - t0) },
            { "Alexa speech-to-text failed after %d ms: ${it.message}".format(System.currentTimeMillis() - t0) }))
        val answer = if (transcript.isFailure) SampleAssistant.Answer("Sorry, speech-to-text failed: ${transcript.exceptionOrNull()?.message}")
            else runCatching { assistant.answer(heard) }.getOrElse { SampleAssistant.Answer("Sorry, that went wrong: ${it.message}") }
        answer.frames.forEach { watch.send2021(Endpoint2021.ALEXA, it) }
        answer.text?.let { watch.send2021(Endpoint2021.ALEXA, Alexa.replySimple(it)) }
        repliedAt = System.currentTimeMillis()
        db.events.add("info", "Alexa answered: ${answer.text ?: "(no text)"}; ${answer.frames.size} extra frame(s)" + if (answer.listenAgain) ", listening again" else "")
        if (answer.listenAgain) { delay(600); watch.send2021(Endpoint2021.ALEXA, Alexa.listenAgain) }
    }

    private suspend fun reply() {
        replied = true; repliedAt = System.currentTimeMillis()
        // The simple reply (09) shows on the GTR 2e; the card (08 06) is acked but left on "Thinking".
        watch.send2021(Endpoint2021.ALEXA, Alexa.replySimple("Heard %.1f s. Speech-to-text and AI answers are coming next.".format(packets * 0.02)))
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
