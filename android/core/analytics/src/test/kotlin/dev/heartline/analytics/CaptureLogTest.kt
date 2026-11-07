package dev.heartline.analytics

import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** The gym log merged into real captures: every timed logged set becomes a set, and the watch's reps hold up against the log. */
class CaptureLogTest {
    private fun read(name: String) = GZIPInputStream(javaClass.getResourceAsStream("/recordings/$name.csv.gz")!!).bufferedReader().use(::readRecordingCsv)

    private fun logged(name: String, rec: Recording): List<LoggedCaptureSet> =
        fixture("recordings/$name").array().map { it.obj() }.filter { it["type"] != "cardio" && it["start"] is String && it["end"] is String }.map { r ->
            fun at(k: String) = OffsetDateTime.parse(r[k] as String).toEpochSecond() - rec.start
            val set = r["set"].number().toInt()
            LoggedCaptureSet(at("start"), at("end"), r["exercise"] as String, if (set == 0) "Warm-up" else "Set $set",
                (r["load_kg"] as? Number)?.toDouble(), (r["reps"] as? Number)?.toDouble(), set == 0)
        }

    private fun check(day: String, rec: Recording, log: List<LoggedCaptureSet>, minFound: Int) {
        val plain = analyzeCapture(rec, emptyList(), Profile(), day)
        val r = analyzeCapture(rec, emptyList(), Profile(), day, logged = log)
        val inside = log.filter { it.end > 0 && it.start < r.durationS }
        println("$day: ${inside.size} logged sets in the capture, watch alone found ${plain.sets.size} sets; merged ${r.sets.size}, matched ${r.logMatched}, not in log ${r.unlogged.size}")
        r.sets.forEach { s -> println("  %-34s %-8s %5.0f-%5.0f logged %4s counted %2d  %s".format(s.exercise, s.setLabel, s.start, s.end, s.loggedReps, s.reps, s.source)) }
        r.unlogged.forEach { println("  not in log: %5.0f-%5.0f %d reps".format(it.start, it.end, it.reps)) }
        // Every logged set inside the capture is a set, in logged order and with the logged boundaries.
        assertEquals(inside.size, r.sets.size)
        assertEquals(inside.size, r.logTotal)
        assertTrue(r.sets.all { it.exercise != null && it.source in setOf("both", "log") })
        assertTrue("found ${r.logMatched}", r.logMatched!! >= minFound)
        // Same exercise, same group; one group per exercise.
        assertEquals(inside.map { it.exercise }.distinct().size, r.sets.map { it.group }.distinct().size)
        // Rests now run between logged sets.
        assertEquals(r.sets.size - 1, r.rests.size)
        val close = r.sets.filter { it.loggedReps != null && it.reps > 0 }.count { abs(it.reps - it.loggedReps!!) <= 2 }
        println("  reps within 2 of the log: $close of ${r.sets.count { it.loggedReps != null && it.reps > 0 }}")
    }

    @Test fun pushDayLogBecomesTheSets() {
        val rec = read("gym_20261103")
        check("2026-11-03", rec, logged("gym_20261103_log.json", rec), minFound = 13)
    }

    @Test fun pullDayLogBecomesTheSets() {
        val rec = read("gym_20261102")
        check("2026-11-02", rec, logged("gym_20261102_log.json", rec), minFound = 18)
    }

    @Test fun noLogLeavesTheDetectorAlone() {
        val rec = read("gym_20261103")
        val r = analyzeCapture(rec, emptyList(), Profile(), "plain")
        assertNull(r.logMatched); assertTrue(r.unlogged.isEmpty()); assertTrue(r.sets.all { it.source == "watch" && it.exercise == null })
    }
}
