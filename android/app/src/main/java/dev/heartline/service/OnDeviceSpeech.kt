package dev.heartline.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * Speech-to-text with Android's own recognizer, fed recorded audio instead of the phone's microphone
 * (EXTRA_AUDIO_SOURCE, Android 13+). Prefers the on-device recognizer, so it is free and works offline.
 */
class OnDeviceSpeech(private val context: Context) {
    class Transcript(val text: String, val engine: String)

    /** [pcm] is 16 kHz mono 16-bit audio. Throws with the recognizer's error name on failure. */
    suspend fun transcribe(pcm: ShortArray, language: String = "en-US"): Transcript {
        check(Build.VERSION.SDK_INT >= 33) { "needs Android 13 to recognise recorded audio" }
        return withTimeout(20_000) {
            withContext(Dispatchers.Main) {
                val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
                val (read, write) = ParcelFileDescriptor.createPipe()
                try {
                    suspendCancellableCoroutine { cont ->
                        recognizer.setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle) {
                                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                                if (cont.isActive) cont.resume(Result.success(Transcript(text, if (onDevice) "on-device" else "default")))
                            }
                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(Result.failure(IllegalStateException("recognizer error ${errorName(error)}")))
                            }
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onPartialResults(partialResults: Bundle?) {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                        })
                        // Write the audio, then a little silence so the recognizer hears the end, and close the pipe.
                        Thread {
                            runCatching {
                                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                                    val bytes = ByteBuffer.allocate((pcm.size + 8000) * 2).order(ByteOrder.LITTLE_ENDIAN)
                                    pcm.forEach { bytes.putShort(it) }
                                    out.write(bytes.array())
                                }
                            }
                        }.start()
                        cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
                    }.getOrThrow()
                } finally {
                    runCatching { read.close() }
                    recognizer.destroy()
                }
            }
        }
    }

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        else -> "code $code"
    }
}
