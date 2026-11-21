package dev.heartline.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import dev.heartline.analytics.GeoFix
import dev.heartline.analytics.findLapLine
import dev.heartline.analytics.metresBetween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import kotlin.math.roundToInt

/** One phone GPS fix: epoch milliseconds, degrees, its accuracy in metres, and its speed (m/s) and altitude where given. */
data class PhoneFix(val ms: Long, val lat: Double, val lon: Double, val accuracyM: Float, val speedMs: Float?, val altM: Double?)

/** Fixes less sure than this are left out: a phone with a clear sky manages 3 to 8 metres. */
const val PHONE_FIX_ACCURACY_M = 25f

/** The phone track file for a capture, next to its raw recordings. */
internal fun phoneTrackName(captureId: String) = "gps-$captureId.csv"

/** Reads a phone track file written by [PhoneTrackRecorder]; lines it can't read are skipped. */
fun readPhoneTrack(file: File): List<PhoneFix> = if (!file.isFile) emptyList() else file.readLines().mapNotNull { line ->
    val f = line.split(',')
    runCatching { PhoneFix(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toFloat(), f[4].toFloatOrNull(), f[5].toDoubleOrNull()) }.getOrNull()
}

/** The fixes good enough to use, as seconds from [startS] (epoch seconds), one a second. */
fun List<PhoneFix>.geoFixes(startS: Long): List<GeoFix> = filter { it.accuracyM <= PHONE_FIX_ACCURACY_M }
    .map { GeoFix(((it.ms / 1000.0) - startS).roundToInt(), it.lat, it.lon) }.filter { it.t >= 0 }.distinctBy { it.t }

/**
 * What the phone's GPS has so far in a capture: good fixes, the distance (counting a move once the phone is 5 m from
 * where it was last counted, so standing still doesn't add the fix's jitter), the pace over the last 30 seconds, the
 * latest fix's accuracy, and laps found so far with the last one's time.
 */
data class PhoneTrackLive(val fixes: Int, val distanceM: Double, val paceSPerKm: Double?, val accuracyM: Float?, val laps: Int, val lastLapS: Double?)

/**
 * Records the phone's GPS once a second to a file while a run or walk is captured. The phone sees the sky better than a
 * watch on a wrist and has a bigger antenna, so its track keeps to the path where the watch's can wander (19 and 21 Nov
 * 2026). The file is appended to, so a capture resumed after the service restarts keeps its earlier fixes.
 */
class PhoneTrackRecorder(private val context: Context, private val scope: CoroutineScope) {
    val live = MutableStateFlow<PhoneTrackLive?>(null)
    private var thread: HandlerThread? = null
    private var listener: LocationListener? = null
    private var writer: FileWriter? = null
    private var lapJob: Job? = null
    private val fixes = ArrayList<PhoneFix>()
    private var counted: PhoneFix? = null
    private var distance = 0.0
    private val trail = ArrayDeque<Pair<Long, Double>>()
    @Volatile var recording = false
        private set

    fun permitted(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts recording into [file] for a capture that began at [startS] (epoch seconds). Throws when it can't. */
    @Synchronized fun start(file: File, startS: Long) {
        if (recording) return
        check(permitted()) { "Heartline needs precise location for the phone's GPS" }
        val lm = context.getSystemService(LocationManager::class.java) ?: error("No location service")
        check(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Turn on location to use the phone's GPS" }
        fixes.clear(); counted = null; distance = 0.0; trail.clear()
        readPhoneTrack(file).forEach { add(it) }
        writer = FileWriter(file, true)
        val t = HandlerThread("phone-gps").also { it.start() }
        thread = t
        val l = LocationListener { loc -> onFix(loc) }
        listener = l
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, t.looper) }
        catch (e: SecurityException) { stop(); throw IllegalStateException("Heartline needs precise location for the phone's GPS") }
        recording = true
        publish(null)
        lapJob = scope.launch(Dispatchers.Default) {
            // Laps so far, every 20 seconds: finding them takes well under a second even an hour in.
            while (isActive) {
                delay(20_000)
                val track = synchronized(this@PhoneTrackRecorder) { fixes.toList() }.geoFixes(startS)
                val line = findLapLine(track)
                synchronized(this@PhoneTrackRecorder) {
                    lapsSoFar = line?.passes?.size?.minus(1) ?: 0
                    lastLap = line?.passes?.takeLast(2)?.takeIf { it.size == 2 }?.let { it[1] - it[0] }
                }
                publish(fixes.lastOrNull()?.accuracyM)
            }
        }
    }

    @Volatile private var lapsSoFar = 0
    @Volatile private var lastLap: Double? = null

    @Synchronized fun stop() {
        lapJob?.cancel(); lapJob = null
        val lm = context.getSystemService(LocationManager::class.java)
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        thread?.quitSafely(); thread = null
        runCatching { writer?.close() }; writer = null
        recording = false
        live.value = null
        lapsSoFar = 0; lastLap = null
    }

    private fun onFix(loc: Location) {
        val f = PhoneFix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 99f,
            if (loc.hasSpeed()) loc.speed else null, if (loc.hasAltitude()) loc.altitude else null)
        synchronized(this) {
            val w = writer ?: return
            runCatching { w.write("${f.ms},${f.lat},${f.lon},${f.accuracyM},${f.speedMs ?: ""},${f.altM ?: ""}\n"); w.flush() }
            add(f)
        }
        publish(f.accuracyM)
    }

    private fun add(f: PhoneFix) {
        if (f.accuracyM > PHONE_FIX_ACCURACY_M) return
        fixes += f
        val c = counted
        if (c == null) counted = f
        else {
            val d = metresBetween(GeoFix(0, c.lat, c.lon), GeoFix(0, f.lat, f.lon))
            if (d >= 5) { distance += d; counted = f }
        }
        trail.addLast(f.ms to distance)
        while (trail.size > 1 && f.ms - trail.first().first > 30_000) trail.removeFirst()
    }

    private fun publish(accuracy: Float?) {
        val pace = synchronized(this) {
            val a = trail.firstOrNull(); val b = trail.lastOrNull()
            if (a == null || b == null || b.first - a.first < 15_000) null
            else (b.second - a.second).takeIf { it >= 10 }?.let { (b.first - a.first) / it }
        }
        live.value = if (!recording) null else PhoneTrackLive(fixes.size, distance, pace, accuracy, lapsSoFar, lastLap)
    }
}
