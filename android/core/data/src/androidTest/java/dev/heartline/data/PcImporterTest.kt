package dev.heartline.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.heartline.data.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcImporterTest {
    private lateinit var context: Context
    private lateinit var db: HeartlineDatabase
    private lateinit var source: File
    private lateinit var oracle: JSONObject
    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        db = HeartlineDatabase.forTests(context)
        source = File(context.cacheDir, "pc-${UUID.randomUUID()}.db")
        instrumentation.context.assets.open("watch-trimmed.db").use { input -> source.outputStream().use { input.copyTo(it) } }
        oracle = JSONObject(instrumentation.context.assets.open("python-oracle.json").bufferedReader().use { it.readText() })
    }
    @After fun teardown() = runBlocking<Unit> { db.closeForTests(); source.delete() }

    @Test fun realPcDatabaseMatchesPythonAndImportIsIdempotent() = runBlocking<Unit> {
        val before = hash(source.readBytes())
        assertEquals(oracle.getString("fixture_sha256"), before)
        val report = PcImporter(db).import(source)
        val expectedCounts = oracle.getJSONObject("counts")
        Schema.tables.forEach { table ->
            val expected = expectedCounts.optInt(table.name, 0)
            assertEquals(table.name, expected, report.counts[table.name])
            assertEquals(table.name, expected, db.read { it.count(table.name) })
        }
        assertEquals(0, report.updated)
        assertEveryStoredCellMatchesSource()

        val stats = oracle.getJSONObject("hr_stats")
        assertEquals(HrStats(stats.getInt("min"), stats.getInt("max"), stats.getDouble("avg"), stats.getInt("count")), db.heartRate.stats(0.0))
        assertEquals(oracle.getLong("activity_last_ts"), db.activity.lastTs())
        assertEquals(oracle.getInt("activity_prev_kind"), db.activity.prevKind(oracle.getDouble("activity_middle")))
        val summary = oracle.getJSONObject("activity_summary")
        assertEquals(ActivitySummary(summary.getLong("steps"), summary.getInt("hr_min"), summary.getDouble("hr_avg"), summary.getInt("hr_max"),
            summary.getInt("active_minutes"), summary.getInt("worn_minutes"), summary.getInt("sleep_minutes")), db.activity.summary(0.0, Double.MAX_VALUE))
        val health = oracle.getJSONObject("health")
        health.keys().forEach { kind ->
            val expected = health.getJSONObject(kind)
            val actual = db.health.series(kind, 0.0)
            assertEquals(kind, expected.getInt("count"), actual.size)
            assertEquals(expected.getLong("first"), actual.first().ts)
            assertEquals(expected.getLong("last"), db.health.lastTs(kind))
        }
        val zone = ZoneId.of("Asia/Kolkata")
        val hrv = oracle.getJSONObject("hrv_by_day")
        assertEquals(hrv.keys().asSequence().associateWith { hrv.getDouble(it) }, db.insights.hrvByDay(zone).mapKeys { it.key.toString() })
        val healthDays = oracle.getJSONObject("health_by_day")
        val expectedDays = healthDays.keys().asSequence().associateWith { type ->
            val days = healthDays.getJSONObject(type)
            days.keys().asSequence().associateWith { days.getDouble(it) }
        }
        assertEquals(expectedDays, db.health.byDay(0.0, zone).mapValues { (_, days) -> days.mapKeys { it.key.toString() } })
        val rhythm = oracle.getJSONObject("latest_rhythm")
        assertEquals(RhythmReading(rhythm.getString("status"), rhythm.getDouble("start")), db.insights.latestRhythm())
        val raw = oracle.getJSONArray("raw_sha256")
        assertEquals((0 until raw.length()).map { raw.getString(it) }, db.health.rawFetches(0.0).map { hash(it.data!!) })

        val second = PcImporter(db).import(source)
        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEveryStoredCellMatchesSource()
        assertEquals(before, hash(source.readBytes()))
    }

    @Test fun importMergesUpdatesAndKeepsDownloadedWorkoutTrack() = runBlocking<Unit> {
        PcImporter(db).import(source)
        val activity = db.activity.since(0.0).first()
        db.activity.upsert(listOf(activity.copy(steps = (activity.steps ?: 0) + 100)))
        val workout = db.health.workouts(0.0).first()
        db.health.upsertWorkout(workout.copy(data = "{}"))
        val report = PcImporter(db).import(source)
        assertEquals(0, report.added)
        assertEquals(1, report.updatedCounts["activity"])
        assertEquals(1, report.updatedCounts["workouts"])
        assertEquals(activity, db.activity.since(0.0).first())
        assertEquals(workout, db.health.workouts(0.0).first())

        // A PC summary-only row must not erase a track already on the phone.
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE workouts SET track=NULL WHERE start=?", arrayOf(workout.start))
        }
        assertEquals(0, PcImporter(db).import(source).updated)
        assertEquals(workout.track, db.health.workouts(0.0).first().track)
    }

    @Test fun duplicateAppendOnlyRowsRetainSourceMultiplicity() = runBlocking<Unit> {
        source.delete()
        SQLiteDatabase.openOrCreateDatabase(source, null).use { sql ->
            Schema.create.take(2).forEach(sql::execSQL)
            sql.execSQL("INSERT INTO hr_samples VALUES (1.5,70),(1.5,70),(1.5,80)")
            sql.execSQL("INSERT INTO events VALUES (2,'info','same'),(2,'warn','middle'),(2,'info','same')")
        }
        db.heartRate.add(1.5, 70)
        val report = PcImporter(db).import(source)
        assertEquals(2, report.counts["hr_samples"])
        assertEquals(3, report.counts["events"])
        assertEquals(3, db.heartRate.since(0.0).size)
        assertEquals(listOf("same", "middle", "same"), db.events.recent(3).map { it.msg })
        assertEquals(0, PcImporter(db).import(source).added)
    }

    @Test fun malformedTableRollsBackEarlierTables() = runBlocking<Unit> {
        source.delete()
        SQLiteDatabase.openOrCreateDatabase(source, null).use {
            it.execSQL(Schema.create.first())
            it.execSQL("INSERT INTO hr_samples VALUES (1,70)")
            it.execSQL("CREATE TABLE events (ts REAL)")
        }
        try { PcImporter(db).import(source); fail("Expected schema failure") }
        catch (_: IllegalArgumentException) { }
        assertTrue(db.heartRate.since(0.0).isEmpty())
        assertTrue(db.events.recent(10).isEmpty())
    }

    @Test fun importNotifiesExistingFlowCollectors() = runBlocking<Unit> {
        val results = Channel<Int>(Channel.UNLIMITED)
        val collector = launch { db.health.observeSeries("temperature", 0.0).collect { results.send(it.size) } }
        try {
            assertEquals(0, withTimeout(5000) { results.receive() })
            PcImporter(db).import(source)
            assertEquals(oracle.getJSONObject("health").getJSONObject("temperature").getInt("count"), withTimeout(10000) { results.receive() })
        } finally { collector.cancelAndJoin() }
    }

    @Test fun csvZipExportsEveryTableAndHonorsInclusiveRange() = runBlocking<Unit> {
        db.heartRate.add(99.0, 60); db.heartRate.add(100.0, 70); db.heartRate.add(101.0, 80)
        db.events.add(100.0, "info", "comma, quote\" and\nnew line 心")
        db.meta.set("profile", "{\"age\":31}")
        db.health.addRawFetch(RawFetch(100.0, "raw", null, "00ff", byteArrayOf(0, -1)))
        db.recordings.upsert(Recording("unknown.csv", null, null, null, false))
        db.checks.upsert(Check(100.0, "unknown.csv", null, null, null, null, "{}"))
        db.health.upsertWorkout(Workout(101, "{}", "[]"))
        val out = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        Exporter.exportCsvZip(db, out, 100.0, 100.0)
        assertFalse(out.closed)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(Schema.tables.map { "${it.name}.csv" }.toSet(), entries.keys)
        assertEquals("ts,bpm\r\n100.0,70\r\n", entries["hr_samples.csv"])
        assertEquals("ts,level,msg\r\n100.0,info,\"comma, quote\"\" and\nnew line 心\"\r\n", entries["events.csv"])
        assertEquals("start,data,track\r\n", entries["workouts.csv"])
        assertTrue(entries.getValue("meta.csv").contains("profile,\"{\"\"age\"\":31}\""))
        assertTrue(entries.getValue("raw_fetches.csv").contains("100.0,raw,,00ff,00ff\r\n"))
        assertTrue(entries.getValue("recordings.csv").contains("unknown.csv"))
        assertTrue(entries.getValue("checks.csv").contains("100.0,unknown.csv"))
    }

    private suspend fun assertEveryStoredCellMatchesSource() {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { pc ->
            Schema.tables.filter { it.name !in setOf("checks", "recordings") }.forEach { table ->
                val query = "SELECT ${table.columns.joinToString()} FROM ${table.name} ORDER BY ${table.columns.joinToString()}"
                fun digest(sql: SQLiteDatabase): String {
                    val md = MessageDigest.getInstance("SHA-256")
                    sql.rawQuery(query, null).use { c ->
                        while (c.moveToNext()) table.columns.indices.forEach { i ->
                            val cell = c.cell(i)
                            val text = if (cell is ByteArray) Hex.encode(cell) else cell.toString()
                            md.update((c.getType(i).toString() + ":" + text.length + ":" + text + "\n").toByteArray())
                        }
                    }
                    return Hex.encode(md.digest())
                }
                assertEquals(table.name, digest(pc), db.read { digest(it) })
            }
        }
    }
    private fun hash(bytes: ByteArray) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
}
