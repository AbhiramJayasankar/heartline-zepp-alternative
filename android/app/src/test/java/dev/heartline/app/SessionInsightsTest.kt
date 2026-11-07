package dev.heartline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInsightsTest {
    private val t0 = 1_800_000_000L
    private val earlier = t0 - 4 * 86400

    private fun row(date: String, ex: String, n: Int, start: Long?, load: Double?, reps: Double?, warm: Boolean = false, rest: Double? = null,
                    failure: Boolean = false, status: String = "completed", notes: String = "", session: String = "Pull day") =
        LoggedSet(date, session, ex, "strength", n, if (warm) "warm-up" else "working", status, start, start?.let { it + 45 }, rest, load, null, reps,
            failure = failure, notes = notes)

    private val log = listOf(
        // Four days earlier, under slightly different names.
        row("2026-01-11", "Lat pulldown", 1, earlier, 36.0, 8.0), row("2026-01-11", "Lat pulldown", 2, earlier + 200, 36.0, 8.0),
        row("2026-01-11", "Preacher curl (machine)", 1, earlier + 600, 20.0, 10.0), row("2026-01-11", "Preacher curl (machine)", 2, earlier + 800, 20.0, 10.0),
        row("2026-01-11", "Forearm curl, reverse (machine)", 1, earlier + 1000, 10.0, 12.0),
        // This session.
        row("2026-01-15", "Lat pulldown", 0, t0 + 100, 25.0, 10.0, warm = true),
        row("2026-01-15", "Lat pulldown", 1, t0 + 300, 39.0, 8.0, rest = 117.0),
        row("2026-01-15", "Lat pulldown", 2, t0 + 500, 39.0, 10.0, rest = 199.0),
        row("2026-01-15", "Preacher curl machine", 1, t0 + 800, 25.0, 8.0, failure = true, rest = 150.0),
        row("2026-01-15", "Preacher curl machine", 2, null, 25.0, 5.5, failure = true),
        row("2026-01-15", "Back extension", 1, null, 5.0, null, status = "skipped", notes = "Lower-back fatigue"),
    )

    private fun set(start: Int, ex: String, label: String, load: Double, logged: Double, reps: Int, group: Int, startHr: Int, rise: Int, warm: Boolean = false, source: String = "both") =
        SetUi(start.toDouble(), start + 45.0, reps, null, startHr + rise - 2, 2.5, group = group, startHr = startHr, hrRise = rise,
            exercise = ex, setLabel = label, loadKg = load, loggedReps = logged, warmUp = warm, source = source)

    private val capture = CaptureAnalysisUi(
        "c1", "Gym", t0, 1200, 110, 140, 80, emptyList(), null, null, null,
        hr = (0..1200 step 5).map { SeriesPoint(it.toDouble(), 90.0 + (it % 200) / 10.0) }, motion = emptyList(), segments = emptyList(),
        sets = listOf(
            set(100, "Lat pulldown", "Warm-up", 25.0, 10.0, 10, 0, 92, 6, warm = true),
            set(300, "Lat pulldown", "Set 1", 39.0, 8.0, 8, 0, 95, 8),
            set(500, "Lat pulldown", "Set 2", 39.0, 10.0, 11, 0, 96, 9),
            set(800, "Preacher curl machine", "Set 1", 25.0, 8.0, 0, 1, 99, 2, source = "log"),
        ),
        rests = emptyList(), hrSource = "", notes = emptyList(),
        recovery = listOf(RecoveryPointUi(0, 0.0, 3), RecoveryPointUi(60, -12.0, 3)),
        logMatched = 3, logTotal = 4, unlogged = listOf(SetUi(1000.0, 1020.0, 5, null, null, null)),
    )

    @Test fun namesMatchAcrossLogs() {
        assertTrue(sameExercise("Preacher curl machine", "Preacher curl (machine)"))
        assertTrue(sameExercise("Assisted pull-up", "Assisted pull-ups (machine)"))
        assertTrue(sameExercise("Rear delt fly machine", "Rear delt machine"))
        assertTrue(sameExercise("Reverse forearm curl / wrist extension", "Forearm curl, reverse (machine)"))
        assertFalse(sameExercise("Machine forearm curl", "Forearm curl, reverse (machine)"))
        assertFalse(sameExercise("Incline bench press", "Bench press"))
        assertFalse(sameExercise("Lat pulldown", "Preacher curl machine"))
    }

    @Test fun exercisesCompareWithTheLastSessionDoingThem() {
        val ins = sessionInsights(capture, log, previousRecovery = listOf(RecoveryPointUi(60, -15.0, 4)))!!
        assertEquals("2026-01-11", ins.previous!!.date.toString())
        val pd = ins.exercises.first { it.exercise == "Lat pulldown" }
        assertEquals(2, pd.workingSets); assertEquals(1, pd.warmUps)
        assertEquals(39.0 * 18, pd.volumeKg, 1e-9)
        assertEquals(52.0, pd.best!!.e1rmKg, 0.01) // 39 × (1 + 10/30)
        assertEquals(45.6, pd.prev!!.best!!.e1rmKg, 0.01)
        // The untimed preacher set still counts towards volume, and is listed as untimed.
        val pc = ins.exercises.first { it.exercise == "Preacher curl machine" }
        assertEquals(25.0 * 13.5, pc.volumeKg, 1e-9)
        assertEquals(1, pc.untimed.size)
        assertEquals("Preacher curl (machine)", pc.prev!!.name)
        // The skipped exercise is listed with its note.
        assertEquals(1, ins.exercises.first { it.exercise == "Back extension" }.skipped.size)
        assertEquals(39.0 * 18 + 25.0 * 13.5, ins.volumeKg, 1e-9)
    }

    @Test fun setsGetTheirLogRowsRestsAndFloors() {
        val ins = sessionInsights(capture, log)!!
        val s2 = ins.sets[2]
        assertEquals(199.0, s2.restBeforeS!!, 0.0)
        assertNotNull(s2.row)
        assertEquals(105, s2.peakHr)
        // Floor: lowest reading between the previous set's end (345 s) and this set's start (500 s).
        assertEquals(ins.sets[2].floorHr, capture.hr.filter { it.t >= 345 && it.t < 500 }.minOf { it.v }.toInt())
        val g = ins.restReps.single()
        assertEquals(39.0, g.loadKg, 0.0)
        assertTrue(g.verdict!!.startsWith("The longest rest came before the most reps: 10 after 3:19"))
    }

    @Test fun findingsAndScore() {
        val ins = sessionInsights(capture, log, previousRecovery = listOf(RecoveryPointUi(60, -15.0, 4)))!!
        val titles = ins.findings.map { it.title }
        assertTrue(titles.toString(), titles.contains("Two new bests."))
        assertTrue(titles.toString(), titles.any { it.startsWith("Recovery was shallower") })
        assertTrue(titles.contains("Two sets went to failure."))
        assertTrue(ins.findings.first { it.title == "One set was skipped." }.text.contains("Lower-back fatigue"))
        val s = ins.score!!
        assertEquals(3, s.found); assertEquals(4, s.total); assertEquals(4, s.finds)
        assertEquals(3, s.counted); assertEquals(3, s.within1)
        assertNull(s.handTotal)
    }

    @Test fun noLogNoInsights() {
        assertNull(sessionInsights(capture.copy(logTotal = null), log))
        assertNull(sessionInsights(capture, emptyList()))
    }

    @Test fun captureForSessionPicksTheLongestOverlap() {
        val s = sessionsOf(log).first { it.date.toString() == "2026-01-11" }
        val caps = listOf(CaptureItemUi("a", "", earlier - 3000, 3100, null, null, null), CaptureItemUi("b", "", earlier - 60, 2000, null, null, null))
        assertEquals("b", captureForSession(s, caps)!!.id)
    }
}
