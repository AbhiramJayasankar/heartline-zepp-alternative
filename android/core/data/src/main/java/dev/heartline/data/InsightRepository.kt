package dev.heartline.data

import dev.heartline.data.model.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

internal fun day(ts: Double, zone: ZoneId): LocalDate = Instant.ofEpochSecond(floor(ts).toLong()).atZone(zone).toLocalDate()

class InsightRepository(private val db: HeartlineDatabase) {
    suspend fun save(session: InsightSession): Boolean = db.write(setOf("insight_sessions")) {
        it.upsert("insight_sessions", values("file" to session.file, "mtime" to session.mtime,
            "start" to session.start, "duration_s" to session.durationS, "quality" to session.quality,
            "usable_s" to session.usableS, "hr" to session.hr, "rmssd" to session.rmssd,
            "sdnn" to session.sdnn, "rhythm" to session.rhythm, "resp" to session.resp, "result" to session.result))
    }
    suspend fun cached(file: String, mtime: Double): String? = db.read {
        it.select("SELECT result FROM insight_sessions WHERE file = ? AND mtime = ?", listOf(file, mtime)) { c -> c.getString(0) }
            .firstOrNull()
    }
    suspend fun list(): List<InsightSession> = db.read {
        it.select("SELECT file,mtime,start,duration_s,quality,usable_s,hr,rmssd,sdnn,rhythm,resp,result FROM insight_sessions ORDER BY start") { c ->
            InsightSession(c.getString(0), c.doubleOrNull(1), c.doubleOrNull(2), c.doubleOrNull(3),
                c.doubleOrNull(4), c.doubleOrNull(5), c.doubleOrNull(6), c.doubleOrNull(7), c.doubleOrNull(8),
                c.getString(9), c.doubleOrNull(10), c.getString(11))
        }
    }
    /** InsightsDB.hrv_by_day: SQI >= .7 and usable seconds >= 45, then the day's median. */
    suspend fun hrvByDay(zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> = db.read {
        it.select("SELECT start,rmssd FROM insight_sessions WHERE rmssd IS NOT NULL AND quality >= 0.7 AND usable_s >= 45") { c ->
            day(c.getDouble(0), zone) to c.getDouble(1)
        }.groupBy({ pair -> pair.first }, { pair -> pair.second }).mapValues { (_, values) ->
            val sorted = values.sorted()
            val middle = sorted.size / 2
            roundOne(if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2)
        }
    }
    suspend fun latestRhythm(): RhythmReading? = db.read {
        it.select("SELECT rhythm,start FROM insight_sessions WHERE quality >= 0.5 ORDER BY start DESC LIMIT 1") { c ->
            RhythmReading(c.getString(0), c.doubleOrNull(1))
        }.firstOrNull()
    }
    fun observeAll(): Flow<List<InsightSession>> = db.observe("insight_sessions") { list() }
    fun observeHrvByDay(zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, Double>> =
        db.observe("insight_sessions") { hrvByDay(zone) }
    fun observeLatestRhythm(): Flow<RhythmReading?> = db.observe("insight_sessions") { latestRhythm() }
}
