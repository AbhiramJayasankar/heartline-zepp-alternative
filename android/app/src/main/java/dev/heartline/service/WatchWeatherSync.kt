package dev.heartline.service

import dev.heartline.app.*
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.AppSettings
import dev.heartline.data.model.Units
import dev.heartline.protocol.wmoCondition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WatchWeatherSync(private val scope: CoroutineScope, private val db: HeartlineDatabase, private val watch: WatchConnection, private val settings: AppSettings,
                       private val location: PhoneLocation) {
    val state = MutableStateFlow(WeatherUi())
    private val client = OpenMeteo()
    private val lock = Mutex()
    private var config = WeatherConfig()
    private var cached: FetchedWeather? = null
    /** The place [cached] is for, to about 1 km, so small location changes keep the forecast. */
    private var cachedFor: String? = null

    init {
        scope.launch {
            combine(db.meta.observe(WeatherConfig.KEY).map(WeatherConfig::parse).distinctUntilChanged(), watch.ready, settings.units) { c, ready, units -> Triple(c, ready, units) }
                .collectLatest { (c, ready, _) ->
                    if (config != c) {
                        val samePlace = config.place == c.place && config.useLocation == c.useLocation
                        config = c
                        if (!samePlace) cached = null
                        state.update { if (samePlace) it.copy(enabled = c.enabled, error = null) else WeatherUi(c.enabled, c.place, c.useLocation) }
                    }
                    if (c.enabled && c.place == null && !c.useLocation) state.update { it.copy(error = "Choose a place for weather") }
                    if (ready && c.enabled && (c.place != null || c.useLocation)) while (currentCoroutineContext().isActive) {
                        refresh(false)
                        delay(WeatherPolicy.SEND_INTERVAL_MS)
                    }
                }
        }
    }

    suspend fun refresh(force: Boolean = true) = lock.withLock {
        // Read persisted settings too: an explicit refresh may arrive before the meta observer.
        val c = WeatherConfig.parse(db.meta.get(WeatherConfig.KEY))
        if (!c.enabled) return@withLock
        if (config != c) { config = c; cached = null; state.value = WeatherUi(c.enabled, c.place, c.useLocation) }
        val place = weatherPlace(c, location) ?: run {
            state.update { it.copy(error = if (c.useLocation) "Heartline doesn't know where the phone is yet. Allow location, or pick a place." else "Choose a place for weather") }
            return@withLock
        }
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, place.lat, place.lon)
        if (key != cachedFor) cached = null
        val fetched = try {
            cached?.takeUnless { force || WeatherPolicy.needsFetch(it.fetchedAt, System.currentTimeMillis() / 1000) }
                ?: client.fetch(place).also { cachedFor = key }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            state.update { it.copy(error = "Couldn't reach the weather service. Try again later.") }; return@withLock
        }
        currentCoroutineContext().ensureActive()
        if (WeatherConfig.parse(db.meta.get(WeatherConfig.KEY)) != c) return@withLock
        cached = fetched
        val w = fetched.weather
        state.update { it.copy(enabled = c.enabled, place = place, useLocation = c.useLocation, tempC = w.tempC, condition = wmoCondition(w.wmo).text,
            highC = w.days.first().highC, lowC = w.days.first().lowC, updatedAt = fetched.fetchedAt,
            error = if (fetched.airQualityUnavailable) "Weather updated, but air quality is unavailable." else null) }
        if (watch.ready.value) try {
            // A settings change must not cancel an ATT write and invalidate the shared connection.
            // The existing GATT operation deadlines still bound a failed transfer.
            withContext(NonCancellable) { watch.sendWeather(w, settings.units.value == Units.IMPERIAL) }
            state.update { it.copy(sentToWatchAt = System.currentTimeMillis() / 1000) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            state.update { it.copy(error = "Couldn't send weather to the watch. Reconnect and try again.") }
        }
    }
}
