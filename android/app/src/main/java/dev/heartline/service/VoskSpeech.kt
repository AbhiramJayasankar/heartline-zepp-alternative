package dev.heartline.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline speech-to-text with Vosk and its small English model, bundled in the app's assets (vosk-en) and unpacked
 * to files/ on first use. Android's own recognizer accepts recorded audio (EXTRA_AUDIO_SOURCE) but returned nothing
 * for the watch's Alexa clips on the S24 Ultra, on-device or not.
 */
class VoskSpeech(private val context: Context) : SpeechToText {
    class Transcript(val text: String, val engine: String)

    private val lock = Mutex()
    private var model: Model? = null

    /** [pcm] is 16 kHz mono 16-bit audio. */
    override suspend fun transcribe(pcm: ShortArray): Transcript = withContext(Dispatchers.IO) {
        val m = lock.withLock { model ?: load().also { model = it } }
        Recognizer(m, 16_000f).use { r ->
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
            r.acceptWaveForm(bytes, bytes.size)
            Transcript(JSONObject(r.finalResult).optString("text"), "vosk small en")
        }
    }

    private fun load(): Model {
        val dir = File(context.filesDir, ASSET)
        val marker = File(dir, ".unpacked-$VERSION")
        if (!marker.exists()) {
            dir.deleteRecursively()
            copyAssets(ASSET, dir)
            marker.writeText("")
        }
        return Model(dir.path)
    }

    private fun copyAssets(path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        children.forEach { copyAssets("$path/$it", File(to, it)) }
    }

    private companion object {
        const val ASSET = "vosk-en"
        const val VERSION = "small-en-us-0.15"
    }
}
