package dev.heartline.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.heartline.data.HeartlineDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Speech-to-text for the watch's Alexa button: [pcm] is 16 kHz mono 16-bit audio. */
fun interface SpeechToText {
    suspend fun transcribe(pcm: ShortArray): VoskSpeech.Transcript
}

/** Which recognizer answers the Alexa button, and the owner's Gemini key (typed into the app, stored in meta). */
data class SpeechConfig(val source: String = VOSK, val geminiKey: String = "") {
    fun json(): String = JSONObject().put("source", source).put("gemini_key", geminiKey).toString()
    companion object {
        const val KEY = "alexa_speech"
        const val VOSK = "vosk"; const val DICTATE = "dictate"; const val GEMINI = "gemini"
        fun parse(text: String?): SpeechConfig = runCatching {
            val o = JSONObject(text ?: "{}")
            SpeechConfig(o.optString("source").takeIf { it in setOf(VOSK, DICTATE, GEMINI) } ?: VOSK, o.optString("gemini_key").trim())
        }.getOrDefault(SpeechConfig())
    }
}

/** The chosen recognizer, falling back to Vosk (with a log line saying why) so the button always answers. */
class SpeechRouter(private val db: HeartlineDatabase, private val vosk: VoskSpeech, private val dictate: DictateSpeech,
                   private val gemini: GeminiSpeech = GeminiSpeech()) : SpeechToText {
    override suspend fun transcribe(pcm: ShortArray): VoskSpeech.Transcript {
        val config = SpeechConfig.parse(db.meta.get(SpeechConfig.KEY))
        val chosen: SpeechToText? = when (config.source) {
            SpeechConfig.DICTATE -> dictate
            SpeechConfig.GEMINI -> if (config.geminiKey.isNotBlank()) SpeechToText { gemini.transcribe(it, config.geminiKey) } else null
            else -> null
        }
        if (chosen != null) {
            runCatching { chosen.transcribe(pcm) }.onSuccess { return it }
                .onFailure { db.events.add("warn", "${config.source.replaceFirstChar(Char::uppercase)} speech-to-text failed (${it.message}); used Vosk") }
        }
        return vosk.transcribe(pcm)
    }
}

/**
 * The speech model picked in Dictate (the owner's dictation app, same signing key), through its RecognizerProvider:
 * no second copy of the model on the phone. The audio goes over a file descriptor because a 30 s clip is near
 * Binder's 1 MB transaction limit.
 */
class DictateSpeech(private val context: Context) : SpeechToText {
    /** Whether Dictate is installed with its recognizer and this app holds the permission (null), or why not. */
    fun problem(): String? {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return "Dictate isn't installed"
        if (context.packageManager.resolveContentProvider(AUTHORITY, 0) == null) return "Dictate ${info.versionName} has no recognizer; update it to 0.5.1 or newer"
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return "Heartline can't use Dictate yet: reinstall Heartline after Dictate (both must share a signing key)"
        return null
    }

    fun modelName(): String? = runCatching { context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "model", null, null)?.getString("model") }.getOrNull()

    override suspend fun transcribe(pcm: ShortArray): VoskSpeech.Transcript = withContext(Dispatchers.IO) {
        problem()?.let { error(it) }
        val file = File.createTempFile("dictate", ".pcm", context.cacheDir)
        try {
            file.writeBytes(ByteArray(pcm.size * 2).also { b -> pcm.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } })
            val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "transcribe", null, Bundle().apply { putParcelable("pcm", fd) })
            } ?: error("Dictate didn't answer")
            result.getString("error")?.let { error(it) }
            VoskSpeech.Transcript(result.getString("text").orEmpty().trim(), "Dictate ${result.getString("model") ?: ""}".trim())
        } finally { file.delete() }
    }

    companion object {
        const val PACKAGE = "com.chaitu.androidstt"
        const val AUTHORITY = "com.chaitu.androidstt.recognizer"
        const val PERMISSION = "com.chaitu.androidstt.permission.TRANSCRIBE"
    }
}

/**
 * Gemini's Live transcription model over the Live API WebSocket (free tier: no request limit, 2026-11).
 * One session per question: setup with automatic activity detection off and SMART formatting (numbers and times as
 * digits, fillers dropped), then activityStart, the audio in 100 ms chunks, activityEnd; the final text arrives as
 * serverContent.inputTranscription. The key goes in the URL's key parameter, as the Live API documents; it's never logged.
 */
class GeminiSpeech(private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()) {
    suspend fun transcribe(pcm: ShortArray, key: String): VoskSpeech.Transcript = withTimeout(15_000) {
        val done = CompletableDeferred<String>()
        val session = Session(pcm)
        val socket = client.newWebSocket(Request.Builder().url("$URL?key=$key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupMessage()) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
            // The Live API sends its JSON in binary frames too.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())
            fun handle(webSocket: WebSocket, text: String) {
                session.receive(text).forEach { webSocket.send(it) }
                session.error?.let { done.completeExceptionally(IllegalStateException("Gemini: $it")) }
                session.finished?.let { done.complete(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(IllegalStateException("Gemini ${response?.code ?: ""} ${response?.message ?: t.message}".trim()))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) session.closed().let { if (it != null) done.complete(it) else done.completeExceptionally(IllegalStateException("Gemini closed: $code $reason")) }
            }
        })
        try {
            // Final text can come in pieces at pauses; take what has arrived if the turn never formally completes.
            val text = runCatching { withTimeout(10_000) { done.await() } }.getOrElse { session.closed() ?: throw it }
            VoskSpeech.Transcript(text, "Gemini transcribe live")
        } finally { socket.close(1000, null) }
    }

    /** The protocol as a state machine over JSON messages, so it can be tested without a socket. */
    class Session(private val pcm: ShortArray) {
        private val finals = ArrayList<String>()
        private var sentAudio = false
        var finished: String? = null; private set
        var error: String? = null; private set

        /** Messages to send in reply to [message] from the server. */
        fun receive(message: String): List<String> {
            val o = runCatching { JSONObject(message) }.getOrNull() ?: return emptyList()
            o.optJSONObject("error")?.let { error = it.optString("message").ifEmpty { it.toString() } }
            if (o.has("setupComplete") && !sentAudio) { sentAudio = true; return audioMessages(pcm) }
            val content = o.optJSONObject("serverContent") ?: return emptyList()
            content.optJSONObject("inputTranscription")?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { finals += it }
            if (content.optBoolean("turnComplete") || content.optBoolean("generationComplete")) finished = finals.joinToString(" ")
            return emptyList()
        }

        /** What was transcribed so far when the socket ends without a completed turn, or null if nothing was. */
        fun closed(): String? = finals.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    companion object {
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MODEL = "models/gemini-3.5-transcribe-live"

        fun setupMessage(): String = JSONObject().put("setup", JSONObject()
            .put("model", MODEL)
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
            .put("inputAudioTranscription", JSONObject().put("mode", "SMART").put("languageCodes", JSONArray().put("en-IN").put("en-US"))
                .put("customVocabulary", JSONArray(listOf("Alexa", "timer", "alarm", "reminder", "heart rate", "steps"))))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)))).toString()

        /** activityStart, the audio as 100 ms chunks of base64 little-endian PCM, activityEnd. */
        fun audioMessages(pcm: ShortArray): List<String> {
            fun input(o: JSONObject) = JSONObject().put("realtimeInput", o).toString()
            val chunks = (pcm.indices step 1600).map { start ->
                val part = pcm.copyOfRange(start, minOf(pcm.size, start + 1600))
                val bytes = ByteArray(part.size * 2).also { b -> part.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } }
                input(JSONObject().put("audio", JSONObject().put("data", java.util.Base64.getEncoder().encodeToString(bytes)).put("mimeType", "audio/pcm;rate=16000")))
            }
            return listOf(input(JSONObject().put("activityStart", JSONObject()))) + chunks + input(JSONObject().put("activityEnd", JSONObject()))
        }
    }
}
