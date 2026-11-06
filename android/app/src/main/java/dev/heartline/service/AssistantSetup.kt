package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.app.HeartlineController
import dev.heartline.app.OpenMeteo
import dev.heartline.app.PhoneLocation
import dev.heartline.app.weatherPlace
import dev.heartline.app.WeatherConfig
import dev.heartline.data.AppSettings
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.model.Units
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Open-Meteo for the place in Heartline's weather settings, the phone's location by default (whether or not weather is sent to the watch). */
class PlaceWeather(private val db: HeartlineDatabase, private val settings: AppSettings, private val location: PhoneLocation,
                   private val client: OpenMeteo = OpenMeteo()) : AssistantWeather {
    private var cached: FetchedWeather? = null
    private var cachedFor: Any? = null

    override val fahrenheit get() = settings.units.value == Units.IMPERIAL

    override suspend fun latest(): FetchedWeather {
        val place = weatherPlace(WeatherConfig.parse(db.meta.get(WeatherConfig.KEY)), location)
            ?: error("Heartline doesn't know where the phone is; allow location or pick a place in its weather settings")
        val key = "%.2f,%.2f".format(Locale.ROOT, place.lat, place.lon)
        cached?.takeIf { cachedFor == key && System.currentTimeMillis() / 1000 - it.fetchedAt < 30 * 60 }?.let { return it }
        val fresh = try { client.fetch(place) } catch (e: CancellationException) { throw e } catch (_: Exception) { error("couldn't reach Open-Meteo") }
        cached = fresh; cachedFor = key
        return fresh
    }
}

/**
 * The assistant the Alexa button and the app's test buttons share: Heartline's data (through [controller], the same
 * numbers the app's screens show: a short glance up front and [HealthTools] for detail), weather, [WebSearch] and saved keys.
 * [actions] and [workout] come from the watch service.
 */
fun watchAssistant(context: android.content.Context, db: HeartlineDatabase, settings: AppSettings, controller: HeartlineController,
                   actions: AssistantActions = AssistantActions.None, workout: () -> String? = { null }, ai: AssistantAi = AssistantAi()): WatchAssistant = WatchAssistant(
    ai = ai,
    aiConfig = { AssistantAiConfig.parse(db.meta.get(AssistantAiConfig.KEY)) },
    facts = { t ->
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val since = t.toEpochSecond() - 6 * 3600.0
        // Live readings (heart checks, workouts) and the per-minute heart rate synced with the activity data, whichever is newer.
        val live = db.heartRate.since(since).maxByOrNull { it.ts }?.let { it.ts.toLong() to it.bpm.toInt() }
        val minutes = db.activity.minutes(since, t.toEpochSecond().toDouble())
        val synced = minutes.lastOrNull { (it.hr ?: 0) in 30..220 }?.let { it.ts to it.hr!! }
        val hr = listOfNotNull(live, synced).maxByOrNull { it.first }
            ?.let { (ts, bpm) -> "$bpm bpm at ${Instant.ofEpochSecond(ts).atZone(t.zone).format(clock)}" }
        val steps = runCatching { db.activity.summary(t.toLocalDate().atStartOfDay(t.zone).toEpochSecond().toDouble(), t.toEpochSecond().toDouble()).steps.toInt() }.getOrNull()
        val asOf = db.activity.lastTs()?.let { Instant.ofEpochSecond(it).atZone(t.zone).format(clock) }
        val health = runCatching { healthSummary(controller, t) }.getOrNull()
        WatchAssistant.Facts(hr, steps, asOf, health, workout())
    },
    weather = PlaceWeather(db, settings, PhoneLocation(context, db)),
    store = object : AssistantStore {
        override suspend fun get(key: String) = db.meta.get(key)
        override suspend fun set(key: String, value: String) = db.meta.set(key, value)
    },
    actions = actions,
    data = object : DataTools {
        val health = HealthTools(controller)
        val web = WebSearch({ WebSearchConfig.parse(db.meta.get(WebSearchConfig.KEY)) }, { AlexaSettings.parse(db.meta.get(AlexaSettings.KEY)).webResults })
        override val tools = health.tools + web.tools
        override suspend fun run(name: String, args: org.json.JSONObject) = health.run(name, args) ?: web.run(name, args)
    },
    settings = { AlexaSettings.parse(db.meta.get(AlexaSettings.KEY)) },
)

/** A short glance at today for the brain's instructions (the detail is in [HealthTools]), from the app's Today and Health data. */
suspend fun healthSummary(controller: HeartlineController, t: ZonedDateTime): String {
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    fun at(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(t.zone).format(clock)
    fun hm(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    fun r(v: Double?, digits: Int = 0) = v?.let { if (digits == 0) Math.round(it).toString() else "%.${digits}f".format(Locale.ROOT, it) }
    val lines = ArrayList<String>()
    controller.today.value?.let { d ->
        d.readiness?.let { lines += "${d.readinessLabel}: $it out of 100" + (d.recommendation?.let { r -> ". Suggestion: $r" } ?: "") }
        listOfNotNull(d.sleepScore?.let { "sleep score $it" }, d.strain?.let { "strain ${r(it, 1)}" }, d.bodyBattery?.let { "body battery $it" },
            d.restingHr?.let { "resting heart rate $it bpm" }).takeIf { it.isNotEmpty() }?.let { lines += "Scores: " + it.joinToString(", ") }
        d.lastNight?.let { n ->
            val asleep = n.deepMin + n.lightMin + n.remMin
            lines += "Last night's sleep: ${at(n.start)} to ${at(n.end)}, ${hm(asleep)} asleep (deep ${hm(n.deepMin)}, light ${hm(n.lightMin)}, REM ${hm(n.remMin)}, awake ${hm(n.awakeMin)})"
        }
        d.alerts.forEach { lines += "Heartline alert: ${it.title}. ${it.detail}" }
    }
    withTimeoutOrNull(5_000) { controller.healthDay(t.toLocalDate()).first() }?.let { h ->
        listOfNotNull(h.restingHr?.let { "resting $it" }, h.hrMin?.let { "lowest $it" }, h.hrAvg?.let { "average $it" }, h.hrMax?.let { "highest $it" })
            .takeIf { it.isNotEmpty() }?.let { lines += "Heart rate today (bpm): " + it.joinToString(", ") + if (h.hrHighMinutes > 0) "; ${h.hrHighMinutes} min high" else "" }
        if (h.stressAvg != null) lines += "Stress today (0 to 100): average ${h.stressAvg}, highest ${h.stressMax ?: "?"}"
        lines += "Activity today: ${h.stepsTotal} steps" + listOfNotNull(h.distanceM?.let { "${r(it / 1000.0, 1)} km" }, h.caloriesKcal?.let { "$it kcal" },
            "${h.activeMinutes} active min", "watch worn ${hm(h.wornMinutes)}").joinToString(", ", ", ") +
            (h.paiToday?.let { "; PAI ${r(it)} today" } ?: "") + (h.paiTotal?.let { ", ${r(it)} this week" } ?: "")
        if (h.workouts.isNotEmpty()) lines += "Workouts today: " + h.workouts.joinToString(", ") { "${it.type} at ${at(it.start)}" }
    }
    return lines.joinToString("\n")
}
