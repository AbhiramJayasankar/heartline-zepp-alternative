package dev.heartline.data

import dev.heartline.data.model.Check
import kotlinx.coroutines.flow.Flow

class CheckRepository(private val db: HeartlineDatabase) {
    suspend fun upsert(check: Check): Boolean = db.write(setOf("checks")) {
        it.upsert("checks", values("start" to check.start, "file" to check.file, "hr" to check.hr,
            "rmssd" to check.rmssd, "sdnn" to check.sdnn, "rhythm" to check.rhythm, "json" to check.json))
    }
    suspend fun list(from: Double = -Double.MAX_VALUE, to: Double = Double.MAX_VALUE): List<Check> = db.read {
        it.select("SELECT start,file,hr,rmssd,sdnn,rhythm,json FROM checks WHERE start >= ? AND start <= ? ORDER BY start DESC",
            listOf(from, to)) { c -> Check(c.getDouble(0), c.getString(1), c.doubleOrNull(2), c.doubleOrNull(3),
            c.doubleOrNull(4), c.getString(5), c.getString(6)) }
    }
    suspend fun delete(start: Double) = db.write(setOf("checks")) {
        it.delete("checks", "start = ?", arrayOf(start.toString())); Unit
    }
    fun observeRange(from: Double = -Double.MAX_VALUE, to: Double = Double.MAX_VALUE): Flow<List<Check>> =
        db.observe("checks") { list(from, to) }
}
