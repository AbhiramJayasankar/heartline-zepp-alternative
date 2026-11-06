package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.WatchWeather
import dev.heartline.protocol.WeatherDay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchAssistantTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 11, 6, 15, 40, 0, 0, zone)
    private val saved = HashMap<String, String>()
    private val store = object : AssistantStore {
        override suspend fun get(key: String) = saved[key]
        override suspend fun set(key: String, value: String) { saved[key] = value }
    }
    private var weatherError: String? = null
    private val weather = object : AssistantWeather {
        override suspend fun latest(): FetchedWeather {
            weatherError?.let { error(it) }
            return FetchedWeather(0, WatchWeather(0, 19_800, "Bengaluru", 24, 2, listOf(WeatherDay(2, 29, 19), WeatherDay(61, 26, 18), WeatherDay(0, 28, 17), WeatherDay(3, 27, 18)), 60, 8.0), false)
        }
        override val fahrenheit = false
    }
    /** The brain's reply for the next question, and the context it was given. */
    private var brain: () -> String = { """{"action":"answer","reply":"Hi."}""" }
    private var lastSystem = ""
    private var key = "sk-test"
    private fun assistant() = WatchAssistant(
        AssistantAi { _, _, body ->
            lastSystem = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", brain())))).toString()
        },
        { AssistantAiConfig(key) }, { WatchAssistant.Facts("72 bpm at 3:39 PM", 4200) }, weather, store, { now })

    @Test fun answersAreShownAsText() = runBlocking {
        brain = { """{"action":"answer","reply":"Your heart rate was 72 bpm.","follow_up":false}""" }
        val a = assistant().answer("What's my heart rate?")
        assertEquals("Your heart rate was 72 bpm.", a.text)
        assertTrue(a.frames.isEmpty())
        assertFalse(a.listenAgain)
        assertTrue(lastSystem.contains("Steps today: 4200"))
        assertTrue(lastSystem.contains("Bengaluru: 24°C, partly cloudy now"))
        assertTrue(lastSystem.contains("tomorrow light rain, 26°C / 18°C"))
    }

    @Test fun failuresAreToldToTheWatch() = runBlocking {
        brain = { throw AssistantAi.OpenRouterError("OpenRouter 429: Rate limit exceeded") }
        assertEquals("OpenRouter isn't working: OpenRouter 429: Rate limit exceeded", assistant().answer("hello").text)
        key = ""
        assertTrue(assistant().answer("hello").text!!.startsWith("Add an OpenRouter key"))
        assertEquals("I didn't hear any words. Try again.", assistant().answer(" ").text)
    }

    @Test fun alarmsAreSetAndCancelledByVoice() = runBlocking {
        val a = assistant()
        brain = { """{"action":"alarm","time":"07:00"}""" }
        val set = a.answer("Wake me at 7")
        assertEquals("Alarm set for Sat 7:00 AM.", set.text)
        // Wall-clock seconds: Saturday 7:00 counted as if it were UTC.
        assertArrayEquals(Alexa.alarm("heartline-alarm-${now.toEpochSecond()}", LocalDateTime.of(2026, 11, 7, 7, 0).toEpochSecond(java.time.ZoneOffset.UTC)), set.frames.single())
        brain = { """{"action":"cancel","ids":[1]}""" }
        now = now.plusMinutes(1)
        val cancel = a.answer("Cancel my alarm")
        assertTrue(lastSystem.contains("id 1: alarm at Sat 07:00"))
        assertEquals("Cancelled the alarm at Sat 07:00.", cancel.text)
        assertArrayEquals(Alexa.deleteAlert(Alexa.ALERT_ALARM, "heartline-alarm-${now.minusMinutes(1).toEpochSecond()}"), cancel.frames.single())
        assertEquals("[]", saved[WatchAssistant.ALERTS])
        assertEquals("There's nothing like that to cancel.", a.answer("Cancel it").text)
    }

    @Test fun timersAndRemindersAreKeptUntilTheyRing() = runBlocking {
        val a = assistant()
        brain = { """{"action":"timer","seconds":300}""" }
        assertEquals("Timer set for 5 minutes.", a.answer("Timer for 5 minutes").text)
        brain = { """{"action":"reminder","time":"18:00","label":"call home"}""" }
        assertEquals("I'll remind you to call home at 6:00 PM.", a.answer("Remind me at 6 pm to call home").text)
        assertTrue(lastSystem.contains("id 1: timer ringing at 15:45"))
        now = now.plusMinutes(10) // the timer has rung
        brain = { """{"action":"answer","reply":"ok"}""" }
        a.answer("anything set?")
        assertFalse(lastSystem.contains("timer ringing"))
        assertTrue(lastSystem.contains("id 2: reminder at 18:00 to call home"))
    }

    @Test fun dryRunsChangeNothing() = runBlocking {
        brain = { """{"action":"timer","seconds":60}""" }
        val a = assistant().answer("Timer for a minute", dryRun = true)
        assertEquals("Timer set for 1 minute (sets a watch timer).", a.summary)
        assertNull(saved[WatchAssistant.ALERTS])
    }

    @Test fun weatherIsARealCard() = runBlocking {
        brain = { """{"action":"weather"}""" }
        val a = assistant().answer("What's the weather like?")
        assertNull(a.text)
        val card = a.frames.single()
        assertEquals(Alexa.REPLY_COMPLEX, card[0].toInt())
        val body = String(card, Charsets.UTF_8)
        listOf("Bengaluru", "Partly cloudy", "Partly Cloudy", "24", "29", "19").forEach { assertTrue(it, body.contains(it)) }
        assertFalse(body.contains("Sample"))
        weatherError = "choose a place in Heartline's weather settings"
        assertEquals("Couldn't get the weather: choose a place in Heartline's weather settings", assistant().answer("Weather?").text)
    }
}
