package dev.heartline.analytics

import java.io.File
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class GymCaptureTest {
    @Test fun realGymSessionMatchesPythonAndLoggedResistanceSets() {
        val rec = GZIPInputStream(javaClass.getResourceAsStream("/recordings/gym_20261102.csv.gz")!!)
            .bufferedReader().use(::readRecordingCsv)
        val result = analyzeCapture(rec, emptyList(), Profile(), "2026-11-02 gym")
        val gait = result.segments.filter { it.kind in setOf("walking", "running") }
        File("build/gym_gait.json").writeText(gait.joinToString(",", "[", "]") { "[${it.start},${it.end}]" })
        File("build/gym_kotlin.csv").writeText("start,end,reps,tempo\n" + result.sets.joinToString("\n") { "${it.start},${it.end},${it.reps},${it.tempoS}" })
        println("Kotlin sets (${result.sets.size}): ${result.sets}")
        val oracle = fixture("recordings/gym_20261102_python.json").obj()
        assertEquals(oracle["rows"].number().toInt(), rec.acc.size)
        val expectedGait = oracle["gait"].array().map { it.array().map { bound -> bound.number() } }
        assertEquals(expectedGait, gait.map { listOf(it.start, it.end) })
        val expected = oracle["sets"].array()
        assertEquals("set count versus Python", expected.size, result.sets.size)
        for ((actual, value) in result.sets.zip(expected)) {
            val s = value.obj()
            assertEquals("start", s["start"].number(), actual.start, 2.0)
            assertEquals("end", s["end"].number(), actual.end, 2.0)
            assertEquals("reps at ${actual.start}", s["reps"].number().toInt(), actual.reps)
            assertEquals("ACF tempo", s["tempo"].number(), actual.tempoS!!, .04)
        }
        val logged = fixture("recordings/gym_20261102_log.json").array().map { it.obj() }
        assertEquals(28, logged.size)
        fun offset(value: Any?) = (value as? String)?.let { OffsetDateTime.parse(it).toEpochSecond() - rec.start }
        // One-to-one positive temporal overlap. Logged messages include latency, so no IoU threshold is implied.
        val remaining = logged.filter { it["type"] != "cardio" }.toMutableList()
        val errors = ArrayList<Double>()
        for (s in result.sets) {
            val match = remaining.maxByOrNull { row ->
                val a = offset(row["start"]) ?: return@maxByOrNull 0.0
                min(s.end, offset(row["end"])!!) - max(s.start, a)
            } ?: continue
            val a = offset(match["start"]) ?: continue
            if (min(s.end, offset(match["end"])!!) <= max(s.start, a)) continue
            errors += abs(s.reps - match["reps"].number())
            remaining.remove(match)
        }
        val resistance = logged.count { it["type"] != "cardio" }
        val recall = errors.size.toDouble() / resistance
        val precision = errors.size.toDouble() / result.sets.size
        val repAccuracy = errors.count { it <= 2 }.toDouble() / errors.size
        println("Gym: matched=${errors.size}/$resistance, detections=${result.sets.size}, recall=$recall precision=$precision within2=$repAccuracy MAE=${errors.average()}")
        assertTrue("recall $recall", recall >= .75)
        // Even the brief's conservative 28-row denominator (including cardio) meets the requested floor.
        assertTrue(errors.size.toDouble() / logged.size >= .75)
        assertTrue("precision $precision", precision >= .8)
        assertTrue("reps within two: $repAccuracy", repAccuracy >= .8)
        val treadmillStart = OffsetDateTime.parse("2026-11-02T23:22:37+05:30").toEpochSecond() - rec.start
        val treadmillEnd = treadmillStart + 603
        val walking = gait.filter { it.kind == "walking" }.sumOf { max(0.0, min(it.end, treadmillEnd) - max(it.start, treadmillStart)) }
        assertTrue("treadmill walking seconds=$walking", walking >= 570)
        assertTrue(result.sets.none { it.start < treadmillEnd && it.end > treadmillStart })
        for (i in 1 until rec.accArrivalT.size) {
            val a = rec.accArrivalT[i - 1]; val b = rec.accArrivalT[i]
            if (b - a > .5) assertTrue("coverage gap $a..$b", result.sets.none { it.start < b && it.end > a })
        }
        assertEquals(result.sets.size - 1, result.rests.size)
        for ((rest, pair) in result.rests.zip(result.sets.zipWithNext())) {
            assertEquals(pair.first.end, rest.start, 0.0)
            assertEquals(pair.second.start, rest.end, 0.0)
        }
    }

    /** 2026-11-03 push day on the watch clock (bench, triceps, chest fly, shoulder press; 18 of 19 logged sets captured). */
    @Test fun pushDayFindsSlowSetsAndCountsRepsAgainstTheLog() {
        val rec = GZIPInputStream(javaClass.getResourceAsStream("/recordings/gym_20261103.csv.gz")!!)
            .bufferedReader().use(::readRecordingCsv)
        assertTrue(rec.watchClock)
        val result = analyzeCapture(rec, emptyList(), Profile(), "2026-11-03 push")
        val logged = fixture("recordings/gym_20261103_log.json").array().map { it.obj() }
        fun offset(value: Any?) = (value as? String)?.let { OffsetDateTime.parse(it).toEpochSecond() - rec.start }
        val captured = logged.filter { offset(it["end"])!! > 0 }.toMutableList()
        assertEquals(18, captured.size)
        val errors = ArrayList<Double>(); var unmatched = 0
        for (s in result.sets) {
            val match = captured.maxByOrNull { min(s.end, offset(it["end"])!! + 8) - max(s.start, offset(it["start"])!! - 8) }
                ?.takeIf { min(s.end, offset(it["end"])!! + 8) > max(s.start, offset(it["start"])!! - 8) }
            if (match == null) { unmatched++; continue }
            errors += s.reps - match["reps"].number(); captured.remove(match)
        }
        println("Push day: matched=${errors.size}/18 unmatched=$unmatched missed=${captured.map { "${it["exercise"]} ${it["load_kg"]}x${it["reps"]}" }} errors=$errors cardio=${result.cardio.map { it.start to it.end }}")
        // Before 2026-11-04: 13/18, reps within two on 11/13, 3.5 minutes of walking between sets reported as cardio.
        assertTrue("matched ${errors.size}", errors.size >= 14)
        assertTrue("unlogged detections $unmatched", unmatched <= 1)
        assertTrue("reps within two: $errors", errors.count { abs(it) <= 2 } >= errors.size - 1)
        assertTrue("walking between sets is not cardio: ${result.cardio}", result.cardio.isEmpty())
    }
}
