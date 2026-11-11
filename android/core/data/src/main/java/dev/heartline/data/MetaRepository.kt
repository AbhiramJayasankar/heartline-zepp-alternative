package dev.heartline.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow

class MetaRepository(private val db: HeartlineDatabase) {
    suspend fun delete(key: String) = db.write(setOf("meta")) {
        it.delete("meta", "key = ?", arrayOf(key)); Unit
    }
    suspend fun set(key: String, value: String) = db.write(setOf("meta")) {
        it.upsert("meta", values("key" to key, "value" to value)); Unit
    }
    suspend fun get(key: String): String? = db.read { read(it, key) }
    suspend fun all(): Map<String, String?> = db.read { sql ->
        // Short values come with the list; long ones are read on their own, in pieces.
        sql.select("SELECT key, CASE WHEN length(value) <= $PIECE THEN value END, length(value) FROM meta ORDER BY key") { c ->
            Triple(c.getString(0), c.getString(1), c.longOrNull(2))
        }.associate { (key, value, length) -> key to if (length != null && length > PIECE) read(sql, key) else value }
    }
    fun observe(key: String): Flow<String?> = db.observe("meta") { get(key) }
    fun observeAll(): Flow<Map<String, String?>> = db.observe("meta") { all() }

    /**
     * Android reads at most about 2 MB of one row at a time, so a longer value is read in pieces. One oversized value
     * (a long conversation log, say) would otherwise fail every read of it, at every start (audit, 2026-11-12).
     */
    private fun read(sql: SQLiteDatabase, key: String): String? {
        val length = sql.select("SELECT length(value) FROM meta WHERE key = ?", listOf(key)) { c -> c.longOrNull(0) ?: -1L }.firstOrNull() ?: return null
        if (length < 0) return null
        if (length <= PIECE) return sql.select("SELECT value FROM meta WHERE key = ?", listOf(key)) { c -> c.getString(0) }.firstOrNull()
        val out = StringBuilder(length.toInt())
        var at = 1L
        while (at <= length) {
            out.append(sql.select("SELECT substr(value, ?, ?) FROM meta WHERE key = ?", listOf(at, PIECE, key)) { c -> c.getString(0) }.firstOrNull().orEmpty())
            at += PIECE
        }
        return out.toString()
    }

    private companion object {
        /** Characters per piece: 400,000 characters stay under 2 MB even at 4 bytes each. */
        const val PIECE = 400_000
    }
}
