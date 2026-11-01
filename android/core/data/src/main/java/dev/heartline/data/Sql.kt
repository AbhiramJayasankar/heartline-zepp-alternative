package dev.heartline.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram

internal fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
    pairs.forEach { (key, value) -> putValue(key, value) }
}

internal fun ContentValues.putValue(key: String, value: Any?) {
    when (value) {
        null -> putNull(key)
        is String -> put(key, value)
        is ByteArray -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Double -> put(key, value)
        is Boolean -> put(key, value)
        else -> error("Unsupported SQL value ${value.javaClass}")
    }
}

internal fun SQLiteProgram.bind(values: List<Any?>) {
    values.forEachIndexed { index, value ->
        when (value) {
            null -> bindNull(index + 1)
            is ByteArray -> bindBlob(index + 1, value)
            is Double -> bindDouble(index + 1, value)
            is Float -> bindDouble(index + 1, value.toDouble())
            is Number -> bindLong(index + 1, value.toLong())
            is Boolean -> bindLong(index + 1, if (value) 1 else 0)
            else -> bindString(index + 1, value.toString())
        }
    }
}

internal fun Cursor.cell(index: Int): Any? = when (getType(index)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
    Cursor.FIELD_TYPE_BLOB -> getBlob(index)
    else -> getString(index)
}
internal fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
internal fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
internal fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
internal fun <T> SQLiteDatabase.select(sql: String, args: List<Any?> = emptyList(), row: (Cursor) -> T): List<T> =
    rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(row(cursor)) }
    }

internal fun SQLiteDatabase.count(table: String): Int =
    compileStatement("SELECT COUNT(*) FROM $table").use { it.simpleQueryForLong().toInt() }

/** Returns true only when the primary key was absent, as Store.upsert_* does. */
internal fun SQLiteDatabase.upsert(table: String, row: ContentValues): Boolean {
    val spec = Schema.tables.first { it.name == table }
    require(spec.keys.isNotEmpty())
    val exists = compileStatement("SELECT COUNT(*) FROM $table WHERE ${spec.keys.joinToString(" AND ") { "$it IS ?" }}").use {
        it.bind(spec.keys.map(row::get))
        it.simpleQueryForLong() > 0
    }
    val columns = spec.columns.filter(row::containsKey)
    val updates = columns.filterNot { it in spec.keys }.joinToString { column ->
        // Store.upsert_workout retains a previously downloaded track when a summary-only update arrives.
        if (table == "workouts" && column == "track") "track=COALESCE(excluded.track,workouts.track)"
        else "$column=excluded.$column"
    }
    val conflict = if (updates.isEmpty()) "DO NOTHING" else "DO UPDATE SET $updates"
    compileStatement("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }}) " +
        "ON CONFLICT(${spec.keys.joinToString()}) $conflict").use {
        it.bind(columns.map(row::get))
        it.executeInsert()
    }
    return !exists
}
