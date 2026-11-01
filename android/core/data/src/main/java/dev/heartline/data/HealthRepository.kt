package dev.heartline.data

import dev.heartline.data.model.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class HealthRepository(private val db: HeartlineDatabase) {
    suspend fun upsert(points: List<HealthPoint>): Int = db.write(setOf("health")) { sql ->
        points.count { p -> sql.upsert("health", values("type" to p.type, "ts" to p.ts, "value" to p.value, "extra" to p.extra)) }
    }
    suspend fun series(type: String, from: Double, to: Double = Double.MAX_VALUE): List<HealthPoint> = db.read {
        it.select("SELECT type,ts,value,extra FROM health WHERE type = ? AND ts >= ? AND ts <= ? ORDER BY ts",
            listOf(type, from, to)) { c -> HealthPoint(c.getString(0), c.getLong(1), c.doubleOrNull(2), c.getString(3)) }
    }
    suspend fun byType(from: Double, to: Double = Double.MAX_VALUE): Map<String, List<HealthPoint>> = db.read {
        it.select("SELECT type,ts,value,extra FROM health WHERE ts >= ? AND ts <= ? ORDER BY type,ts",
            listOf(from, to)) { c -> HealthPoint(c.getString(0), c.getLong(1), c.doubleOrNull(2), c.getString(3)) }
            .groupBy { p -> p.type }
    }
    suspend fun lastTs(type: String): Long? = db.read {
        it.select("SELECT MAX(ts) FROM health WHERE type = ?", listOf(type)) { c -> c.longOrNull(0) }.first()
    }
    fun observeSeries(type: String, from: Double, to: Double = Double.MAX_VALUE): Flow<List<HealthPoint>> =
        db.observe("health") { series(type, from, to) }
    fun observeByType(from: Double, to: Double = Double.MAX_VALUE): Flow<Map<String, List<HealthPoint>>> =
        db.observe("health") { byType(from, to) }

    /** InsightsDB.health_by_day: resting_hr uses the day's minimum; other types use the mean. */
    suspend fun byDay(from: Double, zone: ZoneId = ZoneId.systemDefault()): Map<String, Map<LocalDate, Double>> =
        byType(from).mapValues { (type, points) ->
            points.filter { it.value != null }.groupBy { day(it.ts.toDouble(), zone) }.mapValues { (_, rows) ->
                roundOne(if (type == "resting_hr") rows.minOf { it.value!! } else rows.map { it.value!! }.average())
            }
        }.filterValues { it.isNotEmpty() }

    suspend fun upsertWorkout(workout: Workout): Boolean = db.write(setOf("workouts")) {
        it.upsert("workouts", values("start" to workout.start, "data" to workout.data, "track" to workout.track))
    }
    suspend fun workouts(from: Double, to: Double = Double.MAX_VALUE): List<Workout> = db.read {
        it.select("SELECT start,data,track FROM workouts WHERE start >= ? AND start <= ? ORDER BY start",
            listOf(from, to)) { c -> Workout(c.getLong(0), c.getString(1), c.getString(2)) }
    }
    suspend fun workoutLastStart(): Long? = db.read {
        it.select("SELECT MAX(start) FROM workouts") { c -> c.longOrNull(0) }.first()
    }
    fun observeWorkouts(from: Double, to: Double = Double.MAX_VALUE): Flow<List<Workout>> =
        db.observe("workouts") { workouts(from, to) }

    suspend fun addRawFetch(fetch: RawFetch) = db.write(setOf("raw_fetches")) {
        it.insertOrThrow("raw_fetches", null, values("ts" to fetch.ts, "type" to fetch.type,
            "start" to fetch.start, "reply" to fetch.reply, "data" to fetch.data)); Unit
    }
    suspend fun addRawFetch(ts: Double, type: String, start: Double?, reply: ByteArray, data: ByteArray) =
        addRawFetch(RawFetch(ts, type, start, Hex.encode(reply), data))
    suspend fun rawFetches(from: Double, to: Double = Double.MAX_VALUE): List<RawFetch> = db.read {
        it.select("SELECT ts,type,start,reply,data FROM raw_fetches WHERE ts >= ? AND ts <= ? ORDER BY ts,rowid",
            listOf(from, to)) { c -> RawFetch(c.getDouble(0), c.getString(1), c.doubleOrNull(2), c.getString(3), c.getBlob(4)) }
    }
    fun observeRawFetches(from: Double, to: Double = Double.MAX_VALUE): Flow<List<RawFetch>> =
        db.observe("raw_fetches") { rawFetches(from, to) }
}
