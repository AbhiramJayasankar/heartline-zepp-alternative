package dev.heartline.data

import dev.heartline.data.model.Event
import kotlinx.coroutines.flow.Flow

class EventRepository(private val db: HeartlineDatabase) {
    suspend fun add(level: String, msg: String) = add(System.currentTimeMillis() / 1000.0, level, msg)
    suspend fun add(ts: Double, level: String, msg: String) = db.write(setOf("events")) {
        it.insertOrThrow("events", null, values("ts" to ts, "level" to level, "msg" to msg)); Unit
    }
    suspend fun recent(limit: Int): List<Event> = db.read {
        require(limit >= 0)
        it.select("SELECT ts,level,msg FROM events ORDER BY ts DESC,rowid DESC LIMIT ?", listOf(limit)) { c ->
            Event(c.getDouble(0), c.getString(1), c.getString(2))
        }
    }
    fun observeRecent(limit: Int): Flow<List<Event>> = db.observe("events") { recent(limit) }
}
