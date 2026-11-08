package dev.heartline.service

import dev.heartline.service.ai.AiSection
import dev.heartline.service.ai.AiStore
import dev.heartline.service.ai.Audio
import dev.heartline.service.ai.ChatMsg
import dev.heartline.service.ai.ChatRequest
import dev.heartline.service.ai.badAnswer

/**
 * Turns a question's recording into words with the Voice to text section's models (AI models). A Gemini Live model
 * (one that only streams, such as gemini-3.5-transcribe-live) is used over the Live API ([GeminiSpeech]); any other
 * model that takes audio is sent the recording as a WAV with instructions to write down exactly what was said.
 */
class VoiceToText(private val store: AiStore, private val live: GeminiSpeech = GeminiSpeech()) {
    /** Throws [dev.heartline.service.ai.AiFailed] when no model could do it. */
    suspend fun transcribe(pcm: ShortArray, settings: AlexaSettings = AlexaSettings()): Transcript {
        val catalog = store.catalog()
        val out = store.router.run(AiSection.VOICE) { t ->
            val isLive = catalog.of(t.ref)?.live ?: ("live" in t.model)
            if (isLive) live.transcribe(pcm, t.key, settings.copy(geminiModel = t.model)).let { it.text }
            else {
                val system = "Write down exactly what the speaker says, in the language they speak (expected: ${settings.languages.joinToString()}). " +
                    "Write numbers and times as digits and leave out filler words. Words that may come up: ${settings.vocabulary.joinToString()}. " +
                    "Reply with only the words said, nothing else; if nothing intelligible is said, reply with nothing."
                store.api.chat(t.provider, t.key, t.model, ChatRequest(system, listOf(ChatMsg.User("", Audio(pcm))), temperature = 0.0, maxTokens = 600), t.timeoutMs)
                    .text?.trim()?.removeSurrounding("\"") ?: ""
            }.also { if (it.isBlank()) throw badAnswer("${t.model} heard no words") }
        }
        return Transcript(out.value, "${out.target.provider.name}: ${out.target.model.removePrefix("models/")}")
    }
}
