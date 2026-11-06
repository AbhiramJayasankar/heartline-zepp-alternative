package dev.heartline.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import dev.heartline.data.HeartlineDatabase
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The phone's approximate location for weather (coarse, network or fused, never GPS-precise), named with Android's
 * geocoder. Each fix is saved, so when Android withholds the location (Heartline in the background without "Allow all
 * the time") the last place seen while the app was open is used.
 */
class PhoneLocation(private val context: Context, private val db: HeartlineDatabase) {
    fun permitted(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun inBackground(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Where the phone is now, or the last place saved, or null if it has never been found. */
    suspend fun place(): PlaceUi? {
        val saved = saved()
        val fix = if (permitted()) runCatching { fix() }.getOrNull() else null
        if (fix == null) return saved?.first
        // Keep the saved name while the phone is within about 3 km of it; the geocoder is a network call.
        val near = saved?.first?.takeIf { distanceKm(it.lat, it.lon, fix.latitude, fix.longitude) < 3 }
        val place = near?.copy(lat = fix.latitude, lon = fix.longitude) ?: name(fix.latitude, fix.longitude)
        db.meta.set(KEY, JSONObject().put("name", place.name).put("region", place.region).put("lat", place.lat).put("lon", place.lon)
            .put("ts", System.currentTimeMillis() / 1000).toString())
        return place
    }

    /** The saved place and when it was found (epoch seconds). */
    suspend fun saved(): Pair<PlaceUi, Long>? = runCatching {
        val o = JSONObject(db.meta.get(KEY)!!)
        PlaceUi(o.getString("name"), o.optString("region").takeIf { it.isNotBlank() && it != "null" }, o.getDouble("lat"), o.getDouble("lon")) to o.getLong("ts")
    }.getOrNull()

    private suspend fun fix(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val l = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Location?> ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    try { lm.getCurrentLocation(p, cancel, context.mainExecutor) { cont.resume(it) } } catch (e: SecurityException) { cont.resume(null) }
                }
            }
            if (l != null) return l
        }
        @Suppress("MissingPermission")
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    private suspend fun name(lat: Double, lon: Double): PlaceUi = withContext(Dispatchers.IO) {
        val a = runCatching {
            if (!Geocoder.isPresent()) null
            else if (Build.VERSION.SDK_INT >= 33) withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont -> Geocoder(context).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<android.location.Address>) { cont.resume(list.firstOrNull()) }
                    override fun onError(message: String?) { cont.resume(null) }
                }) }
            }
            else @Suppress("DEPRECATION") Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull()
        val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: "Current location"
        PlaceUi(name, listOfNotNull(a?.adminArea?.takeIf { it != name }, a?.countryName).joinToString(", ").ifBlank { null }, lat, lon)
    }

    companion object {
        const val KEY = "phone_location"
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = FloatArray(1); Location.distanceBetween(lat1, lon1, lat2, lon2, r); return r[0] / 1000.0
        }
    }
}

/** The place weather is for: the phone's location when chosen (falling back to the picked place), else the picked place. */
suspend fun weatherPlace(config: WeatherConfig, location: PhoneLocation): PlaceUi? =
    if (config.useLocation) location.place() ?: config.place else config.place
