package dev.heartline.app

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.model.HealthPoint
import dev.heartline.protocol.parseRestingHeartRate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.*

/** App-scoped, write-only exporter. No Health Connect data is read, including for de-duplication. */
internal class HealthConnectExporter(private val context: Application, private val db: HeartlineDatabase,
    private val scope: CoroutineScope) {
    companion object {
        @Volatile private var instance: HealthConnectExporter? = null
        fun get(context: Context): HealthConnectExporter = instance ?: synchronized(this) {
            instance ?: (context.applicationContext as dev.heartline.HeartlineApp).let { app ->
                HealthConnectExporter(app, app.db, app.scope).also { instance = it }
            }
        }
    }
    private val lock = Mutex()
    private val state = MutableStateFlow(HealthConnectUi())
    val status = state.asStateFlow()
    private var client: HealthConnectClient? = null
    private var skin = false
    private val recordTypes = listOf(HeartRateRecord::class, StepsRecord::class, SleepSessionRecord::class,
        OxygenSaturationRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        ExerciseSessionRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class, RespiratoryRateRecord::class)
    val permissions: Set<String> get() = recordTypes.map { HealthPermission.getWritePermission(it) }.toSet() +
        if (skin) setOf(HealthPermission.getWritePermission(SkinTemperatureRecord::class)) else emptySet()

    init { refresh() }
    fun refresh() { scope.launch(Dispatchers.IO) { lock.withLock { refreshLocked() } } }
    fun setEnabled(enabled: Boolean) { scope.launch(Dispatchers.IO) {
        lock.withLock { db.meta.set("health_connect_enabled", enabled.toString()); refreshLocked() }
        if (enabled) export()
    } }
    fun exportNow() { scope.launch(Dispatchers.IO) { export(full = true) } }
    fun afterSync() { scope.launch(Dispatchers.IO) { export() } }

    private suspend fun refreshLocked(): Boolean {
        val enabled = db.meta.get("health_connect_enabled") == "true"
        val last = db.meta.get("health_connect_last_export")?.toLongOrNull()
        val result = db.meta.get("health_connect_last_result")
        var availability = HealthConnectStatus.UNAVAILABLE
        try {
            availability = when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectStatus.UNAVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_INSTALL
                else -> HealthConnectStatus.READY
            }
            if (availability == HealthConnectStatus.READY) {
                val hc = client ?: HealthConnectClient.getOrCreate(context).also { client = it }
                skin = hc.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
                if (!hc.permissionController.getGrantedPermissions().containsAll(permissions)) availability = HealthConnectStatus.NEEDS_PERMISSION
            } else { client = null; skin = false }
        } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); availability = HealthConnectStatus.UNAVAILABLE }
        state.value = HealthConnectUi(availability, enabled, last, result)
        return enabled && availability == HealthConnectStatus.READY
    }

    private suspend fun export(full: Boolean = false) = lock.withLock {
        if (!refreshLocked()) return@withLock
        val hc = client ?: return@withLock
        val now = System.currentTimeMillis() / 1000
        // Persist before insertion. A crash or a wall-clock rollback still yields increasing versions.
        val version = maxOf(System.currentTimeMillis(), (db.meta.get("health_connect_version")?.toLongOrNull() ?: 0) + 1)
        db.meta.set("health_connect_version", version.toString())
        val mapper = HealthRecordMapping(version, ZoneId.systemDefault())
        // A friend's time on the watch, and deleted watch workouts, never go to Health Connect.
        val friends = FriendStore.read(db)
        val counts = linkedMapOf<String, Int>()
        try {
            suspend fun write(type: String, label: String, load: suspend (Long) -> List<ExportItem<Record>>) {
                currentCoroutineContext().ensureActive()
                if (db.meta.get("health_connect_enabled") != "true") return
                val saved = db.meta.get("health_connect_$type")?.let(::JSONObject) ?: JSONObject()
                val watermark = saved.optLong("watermark").takeIf { saved.has("watermark") }
                val from = exportFrom(now, watermark, full)
                val previous = mutableMapOf<String, ExportStamp>()
                saved.optJSONObject("records")?.let { records ->
                    for (id in records.keys()) {
                        val value = records.getJSONObject(id)
                        previous[id] = ExportStamp(value.getLong("time"), value.getString("hash"))
                    }
                }
                // A manual replay also restores records the user cleared in Health Connect.
                if (full) previous.clear()
                var written = 0
                writeExportType(load(from), previous, now, insert = { records ->
                    hc.insertRecords(records)
                    written += records.sumOf { if (it is HeartRateRecord) it.samples.size else 1 }
                }, commit = { ledger, high ->
                    val records = JSONObject()
                    for ((id, stamp) in ledger) records.put(id, JSONObject().put("time", stamp.time).put("hash", stamp.fingerprint))
                    db.meta.set("health_connect_$type", JSONObject().put("watermark", high).put("records", records).toString())
                })
                counts[label] = written
            }
            // Read complete buckets and whole sleep nights even if the replay window begins mid-bucket/night.
            suspend fun minutes(from: Long): List<dev.heartline.data.model.ActivityMinute> {
                val start = Math.floorDiv(from, 86400).toDouble() * 86400
                val prev = db.activity.prevKind(start)
                return dev.heartline.data.activityMinutes(friendFree(db.activity.range(start, (now - 60).toDouble()), friends, prev), prev)
            }
            write("hr", "heart rate samples") { from ->
                mapper.heartRate(minutes(from), db.heartRate.range(Math.floorDiv(from, 86400).toDouble() * 86400, now.toDouble()).filter { !friends.covers(it.ts.toLong()) })
            }
            write("steps", "step buckets") { mapper.steps(minutes(it)) }
            write("sleep", "nights of sleep") { from ->
                val complete = Instant.ofEpochSecond(from).atZone(ZoneId.systemDefault()).minusDays(1).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                mapper.sleep(minutes(complete))
            }
            write("oxygen", "oxygen readings") { mapper.oxygen(db.health.byType(it.toDouble(), now.toDouble()).values.flatten().filter { p -> !friends.covers(p.ts) }) }
            write("resting", "daily resting heart rates") { from ->
                val dayStart = Instant.ofEpochSecond(from).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                val points = db.health.series("resting_hr", dayStart.toDouble(), now.toDouble()).toMutableList()
                // Older builds stored this payload losslessly before the scalar decoder existed.
                for (raw in db.health.rawFetches(dayStart.toDouble(), now.toDouble()).filter { it.type == "resting_hr" }) {
                    raw.data?.let { bytes ->
                        points += runCatching { parseRestingHeartRate(bytes) }.getOrDefault(emptyList()).filter { it.ts in dayStart..now }
                            .map { HealthPoint(it.type, it.ts, it.value) }
                    }
                }
                mapper.resting(ownHealth(points.distinctBy { it.ts }, friends, ZoneId.systemDefault()))
            }
            suspend fun checks(from: Long): List<ExportCheck> = db.checks.list(from.toDouble(), now.toDouble())
                .filter { !friends.isFriend(FriendMark.Kind.CHECK, it.start.toString()) && !friends.covers(it.start.toLong()) }.map { c ->
                val j = c.json?.let { JSONObject(it) }
                ExportCheck(c.start, c.rmssd, j?.number("respiratory_rate"))
            }
            write("hrv", "HRV measurements") { mapper.checks(checks(it), false) }
            write("respiratory", "breathing measurements") { mapper.checks(checks(it), true) }
            suspend fun workouts(from: Long) = db.health.workouts(from.toDouble(), now.toDouble()).filter { !friends.dropsWorkout(it.start) }.map { row ->
                val j = JSONObject(row.data)
                ExportWorkout(row.start, j.number("end")?.toLong() ?: (row.start + (j.number("duration_s")?.toLong() ?: 0)),
                    j.optString("type", "Workout"), j.number("distance_m"), j.number("calories"))
            }.filter { it.end <= now }
            for ((type, label) in listOf("exercise" to "workouts", "distance" to "workout distances", "calories" to "workout calorie totals")) {
                write(type, label) { mapper.workouts(workouts(it), type) }
            }
            if (skin) write("temperature", "skin temperature buckets") {
                mapper.temperature(db.health.series("temperature", Math.floorDiv(it, 900).toDouble() * 900, now.toDouble()).filter { p -> !friends.covers(p.ts) })
            }
            val written = counts.filterValues { it > 0 }
            val result = if (written.isEmpty()) "Health Connect is up to date." else "Wrote " + written.entries.joinToString(", ") { (label, count) ->
                // Labels are plural; a count of one drops the trailing "s" from the noun ("1 oxygen reading").
                "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(count)} ${if (count == 1) singular(label) else label}"
            } + "."
            db.meta.set("health_connect_last_export", now.toString())
            db.meta.set("health_connect_last_result", result)
            refreshLocked()
        } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
            val result = "Health Connect export stopped; completed batches are safe to retry. ${e.message ?: e.javaClass.simpleName}"
            db.meta.set("health_connect_last_result", result)
            refreshLocked()
            if (e is SecurityException) state.update { it.copy(status = HealthConnectStatus.NEEDS_PERMISSION) }
            db.events.add("warn", result)
        }
    }
}

/** "nights of sleep" -> "night of sleep", "HRV measurements" -> "HRV measurement". */
internal fun singular(label: String): String {
    val words = label.split(" ").toMutableList()
    val i = words.indexOfFirst { it.endsWith("s") && it.length > 3 && it != "HRV" }
    if (i >= 0) words[i] = words[i].removeSuffix("s")
    return words.joinToString(" ")
}
