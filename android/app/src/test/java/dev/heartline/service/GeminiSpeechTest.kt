package dev.heartline.service

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiSpeechTest {
    @Test fun setupAsksForSmartTextTranscriptionWithManualActivity() {
        val setup = JSONObject(GeminiSpeech.setupMessage()).getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        assertEquals("TEXT", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("SMART", setup.getJSONObject("inputAudioTranscription").getString("mode"))
        assertTrue(setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
    }

    @Test fun audioIsFramedByActivityInHundredMillisecondChunks() {
        val pcm = ShortArray(4000) { (it % 100 - 50).toShort() } // 250 ms
        val messages = GeminiSpeech.audioMessages(pcm).map { JSONObject(it).getJSONObject("realtimeInput") }
        assertEquals(5, messages.size) // start, 3 chunks (1600, 1600, 800 samples), end
        assertTrue(messages.first().has("activityStart")); assertTrue(messages.last().has("activityEnd"))
        val audio = messages[1].getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val bytes = java.util.Base64.getDecoder().decode(audio.getString("data"))
        assertEquals(3200, bytes.size)
        assertEquals(pcm[1], ((bytes[2].toInt() and 255) or (bytes[3].toInt() shl 8)).toShort())
        assertEquals(1600, java.util.Base64.getDecoder().decode(messages[3].getJSONObject("audio").getString("data")).size)
    }

    @Test fun sessionSendsAudioAfterSetupAndJoinsFinalPieces() {
        val s = GeminiSpeech.Session(ShortArray(1600))
        assertTrue(s.receive("""{"serverContent":{"interimInputTranscription":{"text":"set an"}}}""").isEmpty())
        assertEquals(3, s.receive("""{"setupComplete":{}}""").size)
        assertTrue(s.receive("""{"setupComplete":{}}""").isEmpty()) // audio goes once
        s.receive("""{"serverContent":{"inputTranscription":{"text":"Set an alarm"}}}""")
        assertNull(s.finished)
        assertEquals("Set an alarm", s.closed())
        s.receive("""{"serverContent":{"inputTranscription":{"text":"for 9:30 a.m."},"turnComplete":true}}""")
        assertEquals("Set an alarm for 9:30 a.m.", s.finished)
    }

    @Test fun serverErrorsAreReported() {
        val s = GeminiSpeech.Session(ShortArray(10))
        s.receive("""{"error":{"code":403,"message":"API key not valid"}}""")
        assertEquals("API key not valid", s.error)
        assertNull(GeminiSpeech.Session(ShortArray(10)).closed())
    }

    @Test fun speechConfigRoundTripsTheKey() {
        assertEquals(SpeechConfig(), SpeechConfig.parse(null))
        // Settings saved before Vosk was removed still carry a "source": the key survives.
        assertEquals(SpeechConfig("k"), SpeechConfig.parse("""{"source":"gemini","gemini_key":" k "}"""))
        assertEquals(SpeechConfig("k"), SpeechConfig.parse(SpeechConfig("k").json()))
    }
}
