package dev.heartline.app

import android.content.Context
import dev.heartline.data.HeartlineDatabase
import dev.heartline.service.WatchService
import dev.heartline.service.musicAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
/** Weather on the watch, find phone and music controls, from the app's side (the service does the work). */
class PhoneLinkController(private val context: Context, private val scope: CoroutineScope, private val db: HeartlineDatabase,
    service: StateFlow<WatchService?>, private val ensureService: suspend () -> WatchService) {
    private val client = OpenMeteo()
    val weather = service.flatMapLatest { s ->
        s?.phoneFeatures?.weather?.state ?: db.meta.observe(WeatherConfig.KEY).map { value ->
            val c = WeatherConfig.parse(value); WeatherUi(enabled = c.enabled, place = c.place, useLocation = c.useLocation)
        }
    }.stateIn(scope, SharingStarted.Eagerly, WeatherUi())
    val phoneLink = service.flatMapLatest { s ->
        s?.phoneFeatures?.phoneLink ?: combine(db.meta.observe("music_enabled"), flow {
            while (currentCoroutineContext().isActive) { emit(musicAccess(context)); delay(10_000) }
        }) { enabled, access -> PhoneLinkUi(musicEnabled = enabled == "true", musicAccess = access) }
    }.stateIn(scope, SharingStarted.Eagerly, PhoneLinkUi())

    suspend fun searchPlaces(query: String): List<PlaceUi> = client.search(query)
    fun setWeather(enabled: Boolean, place: PlaceUi?) = launch {
        require(place == null || validPlace(place)) { "Choose a valid weather location" }
        val previous = WeatherConfig.parse(db.meta.get(WeatherConfig.KEY))
        db.meta.set(WeatherConfig.KEY, WeatherConfig(enabled, place ?: previous.place, if (place != null) false else previous.useLocation).json())
        ensureService()
    }
    fun setWeatherLocation(use: Boolean) = launch {
        db.meta.set(WeatherConfig.KEY, WeatherConfig.parse(db.meta.get(WeatherConfig.KEY)).copy(useLocation = use).json())
        val s = ensureService(); s.command { s.phoneFeatures.weather.refresh() }
    }
    fun refreshWeather() = launch { val s = ensureService(); s.command { s.phoneFeatures.weather.refresh() } }
    fun setMusicControl(enabled: Boolean) = launch { db.meta.set("music_enabled", enabled.toString()); ensureService() }
    fun stopFindPhone() = launch { ensureService().phoneFeatures.stopFindPhone() }
    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("error", e.message ?: "Phone feature failed") }
        }
    }
}
