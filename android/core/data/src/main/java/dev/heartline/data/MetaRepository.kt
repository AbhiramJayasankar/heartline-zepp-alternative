package dev.heartline.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Settings and app state, one value per key. Changes are announced per key: an observer of one key wakes only when
 * that key changes, not on every write to the table (dozens of observers each re-reading on every write, some of them
 * hundreds of kilobytes, was one of the app's main slowdowns).
 */
class MetaRepository(private val db: HeartlineDatabase) {
    private val keyVersions = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val anyVersion = MutableStateFlow(0L)
    private val matchers = java.util.concurrent.CopyOnWriteArrayList<Pair<(String) -> Boolean, MutableStateFlow<Long>>>()
    private fun keyVersion(key: String) = keyVersions.getOrPut(key) { MutableStateFlow(0L) }
    private fun changed(key: String) {
        keyVersions[key]?.let { it.value++ }
        anyVersion.value++
        for ((matches, v) in matchers) if (matches(key)) v.value++
    }

    suspend fun delete(key: String) { db.write(emptySet()) { it.delete("meta", "key = ?", arrayOf(key)); Unit }; changed(key) }
    suspend fun set(key: String, value: String) { db.write(emptySet()) { it.upsert("meta", values("key" to key, "value" to value)); Unit }; changed(key) }
    suspend fun get(key: String): String? {
        // One query for an ordinary value; a long one is read in pieces with writes held off, so it can't come out torn.
        val (value, length) = db.read { sql ->
            sql.select("SELECT CASE WHEN length(value) <= $PIECE THEN value END, length(value) FROM meta WHERE key = ?", listOf(key)) { c ->
                c.getString(0) to c.longOrNull(1)
            }.firstOrNull() ?: (null to null)
        }
        return if (length != null && length > PIECE) db.readLocked { read(it, key) } else value
    }
    suspend fun all(): Map<String, String?> = matching(null, null)

    /** The keys starting with one of [prefixes] or named in [keys] (both null: every key). */
    suspend fun matching(prefixes: List<String>?, keys: Set<String>?): Map<String, String?> {
        val where = if (prefixes == null && keys == null) "" else " WHERE " + (prefixes.orEmpty().map { "key LIKE ? ESCAPE '!'" } + keys.orEmpty().map { "key = ?" }).joinToString(" OR ")
        val args = prefixes.orEmpty().map { it.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%" } + keys.orEmpty()
        val rows = db.read { sql ->
            sql.select("SELECT key, CASE WHEN length(value) <= $PIECE THEN value END, length(value) FROM meta$where ORDER BY key", args) { c ->
                Triple(c.getString(0), c.getString(1), c.longOrNull(2))
            }
        }
        val long = rows.filter { (_, _, length) -> length != null && length > PIECE }.map { it.first }
        val pieces = if (long.isEmpty()) emptyMap() else db.readLocked { sql -> long.associateWith { read(sql, it) } }
        return rows.associate { (key, value, _) -> key to if (key in pieces) pieces[key] else value }
    }

    /** [key]'s value now and after each change to it. */
    fun observe(key: String): Flow<String?> =
        combine(keyVersion(key), db.version("meta")) { _, _ -> }.map { get(key) }.distinctUntilChanged().flowOn(Dispatchers.IO)
    /** Every key, after any change: costly with large values; prefer [observe] or [observeMatching]. */
    fun observeAll(): Flow<Map<String, String?>> = combine(anyVersion, db.version("meta")) { _, _ -> }.map { all() }.flowOn(Dispatchers.IO)
    /** The keys [matching] picks, read again only when one of them changes. */
    fun observeMatching(prefixes: List<String>, keys: Set<String> = emptySet()): Flow<Map<String, String?>> {
        val version = MutableStateFlow(0L)
        matchers += ({ k: String -> k in keys || prefixes.any { k.startsWith(it) } } to version)
        return combine(version, db.version("meta")) { _, _ -> }.map { matching(prefixes, keys) }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

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
