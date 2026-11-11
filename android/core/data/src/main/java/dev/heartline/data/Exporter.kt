package dev.heartline.data

import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object Exporter {
    /** Inclusive epoch-second bounds. Metadata has no timestamp and is always included, except the keys in [skipMeta]
     * (the saved API keys: an export is a file people share). Rows with unknown start times are included. Leaves the
     * caller's stream open.
     */
    suspend fun exportCsvZip(db: HeartlineDatabase, out: OutputStream, from: Double, to: Double, skipMeta: Set<String> = emptySet()) {
        require(from.isFinite() && to.isFinite() && from <= to)
        val job = coroutineContext
        // One read transaction gives a consistent snapshot for all ten tables.
        db.read { sql ->
            sql.beginTransactionNonExclusive()
            try {
                val nonClosing = object : FilterOutputStream(out) { override fun close() { flush() } }
                ZipOutputStream(nonClosing).use { zip ->
                    val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                    Schema.tables.forEach { table ->
                        job.ensureActive()
                        zip.putNextEntry(ZipEntry("${table.name}.csv"))
                        Csv.writeRow(writer, table.columns)
                        val skip = skipMeta.takeIf { table.name == "meta" && it.isNotEmpty() }
                        val filter = table.time?.let { " WHERE $it BETWEEN ? AND ? OR $it IS NULL" }
                            ?: skip?.let { s -> " WHERE key NOT IN (" + s.joinToString(",") { "?" } + ")" } ?: ""
                        val args = table.time?.let { arrayOf(from.toString(), to.toString()) } ?: skip?.toTypedArray()
                        val order = (listOfNotNull(table.time) + table.keys).distinct().ifEmpty { listOf("rowid") }.joinToString()
                        sql.rawQuery("SELECT ${table.columns.joinToString()} FROM ${table.name}$filter ORDER BY $order", args).use { c ->
                            while (c.moveToNext()) {
                                job.ensureActive()
                                Csv.writeRow(writer, table.columns.indices.map(c::cell))
                            }
                        }
                        writer.flush()
                        zip.closeEntry()
                    }
                }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
    }
}
