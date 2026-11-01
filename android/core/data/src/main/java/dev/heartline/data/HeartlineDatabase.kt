package dev.heartline.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HeartlineDatabase private constructor(context: Context, name: String?) {
    private val helper = Helper(context.applicationContext, name)
    private val mutex = Mutex()
    private val versions = Schema.tables.associate { it.name to MutableStateFlow(0L) }

    val heartRate = HeartRateRepository(this)
    val activity = ActivityRepository(this)
    val health = HealthRepository(this)
    val recordings = RecordingRepository(this)
    val checks = CheckRepository(this)
    val events = EventRepository(this)
    val meta = MetaRepository(this)
    val insights = InsightRepository(this)

    internal suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(helper.writableDatabase) }
    }

    internal suspend fun <T> write(tables: Set<String>, block: (SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sql = helper.writableDatabase
                sql.beginTransaction()
                val result = try {
                    block(sql).also { sql.setTransactionSuccessful() }
                } finally {
                    sql.endTransaction()
                }
                tables.forEach { versions.getValue(it).value++ }
                result
            }
        }

    internal fun <T> observe(table: String, query: suspend () -> T): Flow<T> =
        versions.getValue(table).map { query() }.flowOn(Dispatchers.IO)

    internal suspend fun closeForTests() = withContext(Dispatchers.IO) { mutex.withLock { helper.close() } }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, Schema.VERSION) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            Schema.create.forEach(db::execSQL)
            Schema.tables.filter { it.time != null }.forEach {
                db.execSQL("CREATE INDEX ${it.name}_${it.time} ON ${it.name} (${it.time})")
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Add explicit v -> v+1 migrations here when VERSION increases. Never discard health history.
            for (version in oldVersion until newVersion) {
                when (version) {
                    else -> error("Missing Heartline schema migration $version -> ${version + 1}")
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: HeartlineDatabase? = null
        fun get(context: Context): HeartlineDatabase = instance ?: synchronized(this) {
            instance ?: HeartlineDatabase(context, "heartline.db").also { instance = it }
        }
        internal fun forTests(context: Context, name: String? = null) = HeartlineDatabase(context, name)
    }
}
