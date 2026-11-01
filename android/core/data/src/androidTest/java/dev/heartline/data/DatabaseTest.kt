package dev.heartline.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.heartline.data.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var context: Context
    private lateinit var db: HeartlineDatabase
    private lateinit var name: String
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "data-${UUID.randomUUID()}.db"
        db = HeartlineDatabase.forTests(context, name)
    }
    @After fun teardown() = runBlocking {
        db.closeForTests()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun schemaWalAndIndicesSurviveReopen() = runBlocking<Unit> {
        db.meta.set("persisted", "yes")
        db.read { sql ->
            assertEquals(1, sql.version)
            assertTrue(sql.isWriteAheadLoggingEnabled)
            val indexed = sql.select("SELECT name FROM sqlite_master WHERE type='index'") { it.getString(0) }
            Schema.tables.filter { it.time != null }.forEach { assertTrue("index for ${it.name}", "${it.name}_${it.time}" in indexed) }
        }
        db.closeForTests()
        db = HeartlineDatabase.forTests(context, name)
        assertEquals("yes", db.meta.get("persisted"))
    }

    @Test fun hrAndEventQueriesMatchPython() = runBlocking<Unit> {
        listOf(0, -1, 70, 90, 255).forEachIndexed { i, bpm -> db.heartRate.add(100.0 + i, bpm) }
        assertEquals(listOf(HrSample(102.0, 70), HrSample(103.0, 90), HrSample(104.0, 255)), db.heartRate.since(100.5))
        assertEquals(HrStats(70, 255, 138.3, 3), db.heartRate.stats(100.0))
        assertEquals(listOf(HrSample(102.0, 70)), db.heartRate.range(102.0, 102.0))
        db.events.add(1.0, "info", "a")
        db.events.add(2.0, "warn", "b")
        db.events.add(2.0, "info", "c")
        assertEquals(listOf("c", "b", "a"), db.events.recent(10).map { it.msg })
        assertEquals(listOf("c"), db.events.recent(1).map { it.msg })
        assertTrue(db.events.recent(0).isEmpty())
    }

    @Test fun activityUpsertsCountNewMinutesAndSeedRepeatState() = runBlocking<Unit> {
        assertNull(db.activity.lastTs())
        assertNull(db.activity.prevKind(100.0))
        assertEquals(2, db.activity.upsert(listOf(row(60, 0x89), row(120, 0, rem = 56))))
        assertEquals(1, db.activity.upsert(listOf(row(120, 10, rem = 56), row(180, 11), row(180, 11, steps = 3))))
        assertEquals(180L, db.activity.lastTs())
        assertNull(db.activity.prevKind(60.0))
        assertEquals(9, db.activity.prevKind(180.0))
        assertEquals(11, db.activity.prevKind(181.0))
        assertEquals(listOf("sleep_rem", "sleep_deep"), db.activity.minutes(120.0, 180.0).map { it.state })
        assertEquals(ActivitySummary(3, 70, 70.0, 70, 1, 2, 2), db.activity.summary(120.0, 180.0))
        assertEquals(3, db.activity.since(0.0).size)
    }

    @Test fun healthCompositeKeysExtrasAndDailyAggregates() = runBlocking<Unit> {
        val points = listOf(HealthPoint("stress", 1, 20.0, "{\"unknown\":[1,2]}"), HealthPoint("temperature", 1, 33.5),
            HealthPoint("stress", 2, null, null), HealthPoint("resting_hr", 1, 70.0), HealthPoint("resting_hr", 2, 60.0))
        assertEquals(5, db.health.upsert(points))
        assertEquals(0, db.health.upsert(listOf(points[0].copy(value = 30.0))))
        assertEquals(points[0].extra, db.health.series("stress", 0.0).first().extra)
        assertEquals(2L, db.health.lastTs("stress"))
        assertNull(db.health.lastTs("missing"))
        val daily = db.health.byDay(0.0, ZoneId.of("UTC"))
        assertEquals(60.0, daily.getValue("resting_hr").getValue(LocalDate.of(1970, 1, 1)), 0.0)
        assertEquals(30.0, daily.getValue("stress").values.single(), 0.0)
        assertEquals(3, db.health.byType(0.0).size)
        db.health.upsert(listOf(HealthPoint("only_null", 1, null)))
        assertFalse("Python omits types without measured values", db.health.byDay(0.0).containsKey("only_null"))
    }

    @Test fun workoutSummaryUpdatesRetainTrackUnlessExplicitlyReplaced() = runBlocking<Unit> {
        assertNull(db.health.workoutLastStart())
        assertTrue(db.health.upsertWorkout(Workout(100, "{\"start\":100}", "[{\"hr\":90}]")))
        assertFalse(db.health.upsertWorkout(Workout(100, "{\"start\":100,\"type\":1}")))
        assertEquals("[{\"hr\":90}]", db.health.workouts(0.0).single().track)
        assertFalse(db.health.upsertWorkout(Workout(100, "{}", "[]")))
        assertEquals("[]", db.health.workouts(0.0).single().track)
        assertEquals(100L, db.health.workoutLastStart())
    }

    @Test fun rawBlobsAndMetaRoundTripExactly() = runBlocking<Unit> {
        val raw = ByteArray(500_000) { it.toByte() }
        db.health.addRawFetch(1.25, "temperature", null, byteArrayOf(0, -1), raw)
        val fetch = db.health.rawFetches(1.25, 1.25).single()
        assertEquals("00ff", fetch.reply)
        assertNull(fetch.start)
        assertArrayEquals(raw, fetch.data)
        db.meta.set("key'; DROP TABLE health; --", "{\"a\":\"心率\"}")
        assertEquals("{\"a\":\"心率\"}", db.meta.get("key'; DROP TABLE health; --"))
        db.meta.set("key'; DROP TABLE health; --", "updated")
        assertEquals(1, db.meta.all().size)
        assertTrue(db.health.byType(0.0).isEmpty())
    }

    @Test fun insightCacheQualityThresholdsAndTimezoneMatchPython() = runBlocking<Unit> {
        val start = 1_800_000_000.0
        db.insights.save(session("a", start, .7, 45.0, 20.0))
        db.insights.save(session("b", start + 1, .9, 60.0, 40.0))
        db.insights.save(session("c", start + 2, .69, 60.0, 200.0))
        db.insights.save(session("d", start + 3, .9, 44.99, 300.0))
        db.insights.save(session("e", start + 4, .5, 60.0, null))
        db.insights.save(session("f", start + 5, .49, 60.0, 400.0))
        assertEquals("{}", db.insights.cached("a", 123.0))
        assertNull(db.insights.cached("a", 124.0))
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals(mapOf(day(start, zone) to 30.0), db.insights.hrvByDay(zone))
        assertEquals(RhythmReading("regular", start + 4), db.insights.latestRhythm())
    }

    @Test fun recordingsAndChecksUpdateAndDelete() = runBlocking<Unit> {
        val recording = Recording("a.csv", 100.0, 60.5, "ppg,accel", true)
        assertTrue(db.recordings.upsert(recording))
        db.recordings.setAnalysis("a.csv", "{\"hr\":70}")
        assertEquals(recording.copy(analysis = "{\"hr\":70}"), db.recordings.list().single())
        assertFalse(db.recordings.upsert(recording))
        val check = Check(100.25, "a.csv", 70.0, 35.0, 25.0, "regular", "{}")
        assertTrue(db.checks.upsert(check))
        assertFalse(db.checks.upsert(check.copy(hr = 71.0)))
        assertEquals(listOf(check.copy(hr = 71.0)), db.checks.list(100.25, 100.25))
        db.recordings.delete("a.csv")
        assertTrue(db.recordings.list().isEmpty())
        // History survives removal of recording metadata.
        assertEquals(1, db.checks.list().size)
        db.checks.delete(100.25)
        assertTrue(db.checks.list().isEmpty())
    }

    @Test fun flowEmitsInitialAndCommittedChangesOnlyForItsTable() = runBlocking<Unit> {
        val received = Channel<List<HrSample>>(Channel.UNLIMITED)
        val collect = launch { db.heartRate.observeSince(0.0).collect { received.send(it) } }
        try {
            assertTrue(withTimeout(5000) { received.receive() }.isEmpty())
            db.heartRate.add(100.0, 70)
            assertEquals(listOf(HrSample(100.0, 70)), withTimeout(5000) { received.receive() })
            db.meta.set("other", "change")
            assertNull(withTimeoutOrNull(200) { received.receive() })
            db.heartRate.add(101.0, 90)
            assertEquals(2, withTimeout(5000) { received.receive() }.size)
        } finally { collect.cancelAndJoin() }
    }

    @Test fun failedWriteRollsBackAndDoesNotEmit() = runBlocking<Unit> {
        try {
            db.write(setOf("hr_samples")) {
                it.insertOrThrow("hr_samples", null, values("ts" to 1.0, "bpm" to 70))
                error("rollback")
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertTrue(db.heartRate.since(0.0).isEmpty())
        db.heartRate.add(2.0, 80)
        assertEquals(1, db.heartRate.since(0.0).size)
    }

    private fun row(ts: Long, kind: Int, steps: Int = 0, rem: Int = 0) = ActivityRow(ts, kind, 0, steps, 70, 0, 0, 0, rem)
    private fun session(file: String, start: Double, quality: Double, usable: Double, rmssd: Double?) =
        InsightSession(file, 123.0, start, 60.0, quality, usable, 70.0, rmssd, 20.0, "regular", null, "{}")
}
