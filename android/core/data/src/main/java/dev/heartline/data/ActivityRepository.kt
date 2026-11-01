package dev.heartline.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.heartline.data.model.*
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val db: HeartlineDatabase) {
    suspend fun upsert(rows: List<ActivityRow>): Int = db.write(setOf("activity")) { sql ->
        rows.count { row -> sql.upsert("activity", values(
            "ts" to row.ts, "kind" to row.kind, "intensity" to row.intensity, "steps" to row.steps,
            "hr" to row.hr, "unknown1" to row.unknown1, "sleep" to row.sleep, "deep" to row.deep, "rem" to row.rem,
        )) }
    }
    suspend fun lastTs(): Long? = db.read { it.select("SELECT MAX(ts) FROM activity") { c -> c.longOrNull(0) }.first() }
    suspend fun prevKind(ts: Double): Int? = db.read { previous(it, ts) }
    suspend fun since(ts: Double): List<ActivityRow> = range(ts, Double.MAX_VALUE)
    suspend fun range(from: Double, to: Double): List<ActivityRow> = db.read { rows(it, from, to) }
    suspend fun minutes(from: Double, to: Double): List<ActivityMinute> = db.read {
        activityMinutes(rows(it, from, to), previous(it, from))
    }
    suspend fun summary(from: Double, to: Double): ActivitySummary = activitySummary(minutes(from, to))
    fun observeRange(from: Double, to: Double): Flow<List<ActivityRow>> = db.observe("activity") { range(from, to) }
    fun observeSummary(from: Double, to: Double): Flow<ActivitySummary> = db.observe("activity") { summary(from, to) }

    private fun previous(sql: SQLiteDatabase, ts: Double): Int? = sql.select(
        "SELECT kind & 15 FROM activity WHERE ts < ? AND (kind & 15) NOT IN (0,10) ORDER BY ts DESC LIMIT 1",
        listOf(ts)) { it.getInt(0) }.firstOrNull()
    private fun rows(sql: SQLiteDatabase, from: Double, to: Double) = sql.select(
        "SELECT ts,kind,intensity,steps,hr,unknown1,sleep,deep,rem FROM activity WHERE ts >= ? AND ts <= ? ORDER BY ts",
        listOf(from, to), ::activityRow)
}

internal fun activityRow(c: Cursor) = ActivityRow(c.getLong(0), c.intOrNull(1), c.intOrNull(2),
    c.intOrNull(3), c.intOrNull(4), c.intOrNull(5), c.intOrNull(6), c.intOrNull(7), c.intOrNull(8))
