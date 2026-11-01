package dev.heartline.data

import dev.heartline.data.model.HrSample
import dev.heartline.data.model.HrStats
import kotlinx.coroutines.flow.Flow

class HeartRateRepository(private val db: HeartlineDatabase) {
    suspend fun add(ts: Double, bpm: Int) = db.write(setOf("hr_samples")) {
        it.insertOrThrow("hr_samples", null, values("ts" to ts, "bpm" to bpm)); Unit
    }
    suspend fun since(ts: Double): List<HrSample> = range(ts, Double.MAX_VALUE)
    suspend fun range(from: Double, to: Double): List<HrSample> = db.read {
        it.select("SELECT ts,bpm FROM hr_samples WHERE ts >= ? AND ts <= ? AND bpm > 0 ORDER BY ts",
            listOf(from, to)) { c -> HrSample(c.getDouble(0), c.getInt(1)) }
    }
    suspend fun stats(since: Double): HrStats = hrStats(since(since))
    fun observeSince(ts: Double): Flow<List<HrSample>> = db.observe("hr_samples") { since(ts) }
    fun observeStats(since: Double): Flow<HrStats> = db.observe("hr_samples") { stats(since) }
}
