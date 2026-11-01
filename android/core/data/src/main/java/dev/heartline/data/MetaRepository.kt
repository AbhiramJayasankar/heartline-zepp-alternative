package dev.heartline.data

import kotlinx.coroutines.flow.Flow

class MetaRepository(private val db: HeartlineDatabase) {
    suspend fun set(key: String, value: String) = db.write(setOf("meta")) {
        it.upsert("meta", values("key" to key, "value" to value)); Unit
    }
    suspend fun get(key: String): String? = db.read {
        it.select("SELECT value FROM meta WHERE key = ?", listOf(key)) { c -> c.getString(0) }.firstOrNull()
    }
    suspend fun all(): Map<String, String?> = db.read {
        it.select("SELECT key,value FROM meta ORDER BY key") { c -> c.getString(0) to c.getString(1) }.toMap()
    }
    fun observe(key: String): Flow<String?> = db.observe("meta") { get(key) }
    fun observeAll(): Flow<Map<String, String?>> = db.observe("meta") { all() }
}
