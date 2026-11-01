package dev.heartline.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.heartline.data.model.ImportReport
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class PcImporter(private val db: HeartlineDatabase) {
    /** One atomic merge, reading the PC file read-only. Missing optional tables count as zero. */
    suspend fun import(file: File): ImportReport = withContext(Dispatchers.IO) {
        require(file.isFile) { "PC database does not exist: ${file.name}" }
        SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { source ->
            val available = source.select("SELECT name FROM sqlite_master WHERE type='table'") { it.getString(0) }.toSet()
            val job = coroutineContext
            db.write(Schema.tables.map { it.name }.toSet()) { target ->
                val added = linkedMapOf<String, Int>()
                val updated = linkedMapOf<String, Int>()
                Schema.tables.forEach { table ->
                    job.ensureActive()
                    added[table.name] = 0
                    updated[table.name] = 0
                    if (table.name !in available) return@forEach
                    val columns = source.select("PRAGMA table_info(${table.name})") { it.getString(1) }.toSet()
                    require(columns.containsAll(table.columns)) { "Unsupported PC table schema: ${table.name}" }
                    val counts = if (table.keys.isEmpty()) mergeAppendOnly(source, target, table) { job.ensureActive() }
                    else mergeKeyed(source, target, table) { job.ensureActive() }
                    added[table.name] = counts.first
                    updated[table.name] = counts.second
                }
                ImportReport(added, updated)
            }
        }
    }

    private fun mergeKeyed(source: SQLiteDatabase, target: SQLiteDatabase, table: Schema.Table, checkActive: () -> Unit): Pair<Int, Int> {
        var added = 0
        var updated = 0
        val cols = table.columns.joinToString()
        source.rawQuery("SELECT $cols FROM ${table.name}", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val incoming = table.columns.indices.map(cursor::cell).toMutableList()
                val keys = table.keys.map { incoming[table.columns.indexOf(it)] }
                val existing = target.select("SELECT $cols FROM ${table.name} WHERE " +
                    table.keys.joinToString(" AND ") { "$it IS ?" }, keys) { c -> table.columns.indices.map(c::cell) }.firstOrNull()
                if (table.name == "workouts" && incoming[2] == null && existing != null) incoming[2] = existing[2]
                if (existing == incoming) continue
                val row = ContentValues().apply { table.columns.forEachIndexed { i, name -> putValue(name, incoming[i]) } }
                target.upsert(table.name, row)
                if (existing == null) added++ else updated++
            }
        }
        return added to updated
    }

    /** Compare multiplicities, not just existence: Python allows identical append-only records. */
    private fun mergeAppendOnly(source: SQLiteDatabase, target: SQLiteDatabase, table: Schema.Table, checkActive: () -> Unit): Pair<Int, Int> {
        var added = 0
        val cols = table.columns.joinToString()
        data class Occurrences(val present: Long, var seen: Long = 0)
        val occurrences = mutableMapOf<List<Any?>, Occurrences>()
        var lastTs: Any? = null
        target.compileStatement("SELECT COUNT(*) FROM ${table.name} WHERE " + table.columns.joinToString(" AND ") { "$it IS ?" }).use { count ->
            target.compileStatement("INSERT INTO ${table.name} ($cols) VALUES (${table.columns.joinToString { "?" }})").use { insert ->
                // Source order matters for events(ts DESC, rowid DESC), even for interleaved duplicates.
                source.rawQuery("SELECT $cols FROM ${table.name} ORDER BY ts,rowid", null).use { c ->
                    while (c.moveToNext()) {
                        checkActive()
                        val row = table.columns.indices.map(c::cell)
                        if (lastTs != row[0]) { occurrences.clear(); lastTs = row[0] }
                        // ByteBuffer provides content equality without hex expansion or copying BLOBs.
                        // Retain keys only for this timestamp, bounding memory on long histories.
                        val key = row.map { if (it is ByteArray) ByteBuffer.wrap(it).asReadOnlyBuffer() else it }
                        val seen = occurrences.getOrPut(key) { count.bind(row); Occurrences(count.simpleQueryForLong()) }
                        seen.seen++
                        if (seen.seen > seen.present) {
                            insert.bind(row)
                            insert.executeInsert()
                            added++
                        }
                    }
                }
            }
        }
        return added to 0
    }
}
