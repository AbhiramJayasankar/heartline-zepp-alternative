package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.app.OpenMeteo
import dev.heartline.app.WeatherConfig
import dev.heartline.data.AppSettings
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.model.Units
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Open-Meteo for the place chosen in Heartline's weather settings (whether or not weather is sent to the watch). */
class PlaceWeather(private val db: HeartlineDatabase, private val settings: AppSettings, private val client: OpenMeteo = OpenMeteo()) : AssistantWeather {
    private var cached: FetchedWeather? = null
    private var cachedFor: Any? = null

    override val fahrenheit get() = settings.units.value == Units.IMPERIAL

    override suspend fun latest(): FetchedWeather {
        val place = WeatherConfig.parse(db.meta.get(WeatherConfig.KEY)).place ?: error("choose a place in Heartline's weather settings")
        cached?.takeIf { cachedFor == place && System.currentTimeMillis() / 1000 - it.fetchedAt < 30 * 60 }?.let { return it }
        val fresh = try { client.fetch(place) } catch (e: CancellationException) { throw e } catch (_: Exception) { error("couldn't reach Open-Meteo") }
        cached = fresh; cachedFor = place
        return fresh
    }
}

/** The assistant the Alexa button and the app's test buttons share: Heartline's data, weather and saved keys. */
fun watchAssistant(db: HeartlineDatabase, settings: AppSettings, ai: AssistantAi = AssistantAi()): WatchAssistant = WatchAssistant(
    ai = ai,
    aiConfig = { AssistantAiConfig.parse(db.meta.get(AssistantAiConfig.KEY)) },
    facts = { t ->
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val hr = db.heartRate.since(t.toEpochSecond() - 6 * 3600.0).maxByOrNull { it.ts }
            ?.let { "${it.bpm} bpm at ${Instant.ofEpochSecond(it.ts.toLong()).atZone(t.zone).format(clock)}" }
        val steps = runCatching { db.activity.summary(t.toLocalDate().atStartOfDay(t.zone).toEpochSecond().toDouble(), t.toEpochSecond().toDouble()).steps.toInt() }.getOrNull()
        WatchAssistant.Facts(hr, steps)
    },
    weather = PlaceWeather(db, settings),
    store = object : AssistantStore {
        override suspend fun get(key: String) = db.meta.get(key)
        override suspend fun set(key: String, value: String) = db.meta.set(key, value)
    },
)
