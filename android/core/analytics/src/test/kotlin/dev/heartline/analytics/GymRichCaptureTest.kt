package dev.heartline.analytics

import java.io.File
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class GymRichCaptureTest {
    private fun recording(): Recording {
        fun read(name: String) = GZIPInputStream(javaClass.getResourceAsStream("/recordings/$name.csv.gz")!!).bufferedReader().use(::readRecordingCsv)
        val acc = read("gym_20261102")
        val rich = read("gym_20261102_rich")
        val shift = rich.start - acc.start
        return acc.copy(ppgT = rich.ppgT.map { it + shift }.toDoubleArray(), ppg = rich.ppg,
            ppgArrivalT = rich.ppgArrivalT.map { it + shift }.toDoubleArray(),
            accT = acc.accT + rich.accArrivalT.map { it + shift }.toDoubleArray(),
            accArrivalT = acc.accArrivalT + rich.accArrivalT.map { it + shift }.toDoubleArray(), acc = acc.acc + rich.acc,
            gyroT = rich.gyroT.map { it + shift }.toDoubleArray(), gyro = rich.gyro,
            captureDurationS = fixture("recordings/gym_20261102_hr.json").obj()["end"].number() - acc.start)
    }

    @Test fun richSessionPreservesDetectionAndMeasuresRecoveryCardioAndPulse() {
        val fixtureRec = recording()
        // Optional reproduction against both complete, read-only input files (including all PPG/mag).
        val rec = System.getenv("HEARTLINE_GYM_RAW")?.let { dir ->
            val parts = listOf("20261102-221348.csv", "20261102-231527.csv").map { File(dir, it).bufferedReader().use(::readRecordingCsv) }
            fun times(f: (Recording) -> DoubleArray) = parts.flatMap { p -> f(p).map { it + p.start - fixtureRec.start } }.toDoubleArray()
            fixtureRec.copy(ppgT = times { it.ppgT }, ppgArrivalT = times { it.ppgArrivalT }, ppg = parts.flatMap { it.ppg.toList() }.toDoubleArray(),
                accT = times { it.accT }, accArrivalT = times { it.accArrivalT }, acc = parts.flatMap { it.acc.toList() }.toTypedArray(),
                gyroT = times { it.gyroT }, gyro = parts.flatMap { it.gyro.toList() }.toTypedArray(),
                magT = times { it.magT }, mag = parts.flatMap { it.mag.toList() }.toTypedArray())
        } ?: fixtureRec
        val data = fixture("recordings/gym_20261102_hr.json").obj()
        val hr = data["hr"].array().map { it.array().let { r -> r[0].number() to r[1].number().toInt() } }
        val activities = data["workouts"].array().map { it.obj() }.map { o ->
            fun int(k: String) = o[k]?.number()?.toInt()
            CaptureActivity(max(0.0, o["start"].number() - rec.start), min(rec.captureDurationS!!, o["end"].number() - rec.start),
                o["type"] as String, int("active_s"), int("distance_m"), int("steps"), int("calories"), int("avg_hr"), int("max_hr"))
        }
        val clock = System.nanoTime()
        val result = analyzeCapture(rec, hr, Profile(), "Gym", activities)
        val elapsed = (System.nanoTime() - clock) / 1e9
        val oracle = fixture("recordings/gym_20261102_python.json").obj()["sets"].array()
        assertEquals(oracle.size, result.sets.size)
        result.sets.zip(oracle).forEach { (s, row) ->
            val o = row.obj()
            assertEquals(o["start"].number(), s.start, .001); assertEquals(o["end"].number(), s.end, .001)
            assertEquals(o["reps"].number().toInt(), s.reps); assertEquals(o["tempo"].number(), s.tempoS!!, .001)
            assertEquals(s.reps - 1, s.repDetail.size)
            s.repDetail.forEach { assertEquals(it.durationS, it.slowS + it.fastS, 1e-10) }
        }
        val log = fixture("recordings/gym_20261102_log.json").array().map { it.obj() }.filter { it["type"] != "cardio" }
        fun offset(o: Map<String, Any?>, key: String) = (o[key] as? String)?.let { OffsetDateTime.parse(it).toEpochSecond() - rec.start }
        val matches = result.sets.mapNotNull { s -> log.maxByOrNull { row ->
            val a = offset(row, "start") ?: return@maxByOrNull 0.0
            min(s.end, offset(row, "end")!!) - max(s.start, a)
        }?.takeIf { min(s.end, offset(it, "end")!!) > max(s.start, offset(it, "start") ?: Double.POSITIVE_INFINITY) }?.let { s to it } }
        val labels = matches.map { it.second["exercise"] as String }
        val groups = matches.map { it.first.group }
        fun choose2(n: Int) = n * (n - 1) / 2.0
        val cells = matches.indices.groupBy { labels[it] to groups[it] }.values.sumOf { choose2(it.size) }
        val rows = labels.groupingBy { it }.eachCount().values.sumOf(::choose2)
        val cols = groups.groupingBy { it }.eachCount().values.sumOf(::choose2)
        val expected = rows * cols / choose2(matches.size)
        val ari = (cells - expected) / ((rows + cols) / 2 - expected)
        val boundaries = (1 until matches.size).count { (labels[it] != labels[it - 1]) == (groups[it] != groups[it - 1]) }
        val summary = buildString {
            appendLine("analysisSeconds=$elapsed sets=${result.sets.size} ARI=$ari boundaries=$boundaries/${matches.size - 1}")
            result.sets.forEach { s -> appendLine("set ${s.start}: group=${s.group} reps=${s.reps} arc=${median(s.repDetail.map { it.arcDeg }.toDoubleArray())} last=${s.repDetail.lastOrNull()?.arcDeg} slope=${s.slowdownS} partial=${s.partialLast} startHr=${s.startHr} rise=${s.hrRise} activity=${s.activity}") }
            appendLine("recovery=${result.recovery}"); appendLine("floor=${result.floor} work=${result.workS} rest=${result.restS}")
            appendLine("cardio=${result.cardio}"); appendLine("pulse=${result.pulse?.copy(pairs=emptyList(),excerpt=emptyList(),excerptBeats=emptyList())}")
            appendLine("pairs=${result.pulse?.pairs?.size} excerpt=${result.pulse?.excerpt?.size} excerptBeats=${result.pulse?.excerptBeats?.size}")
        }
        File("build/gym_rich_metrics.txt").writeText(summary)
        File("build/gym_group_features.csv").writeText("start,end,tempo,gx,gy,gz,ax,ay,az,exercise\n" +
            captureRhythm(rec, result.segments.filter { it.kind == "walking" || it.kind == "running" }, rec.captureDurationS!!).joinToString("\n") { s ->
                "${s.start},${s.end},${s.tempoS},${s.gravity.joinToString(",")},${s.axis.joinToString(",")},${matches.firstOrNull { it.first.start == s.start }?.second?.get("exercise") ?: "?"}"
            })
        println(summary)
        // 22 since the slow band (2026-11-04) also finds the 10 kg x 9 reverse forearm curl.
        assertEquals(22, matches.size)
        assertTrue("grouping ARI=$ari", ari >= .9)
        assertEquals("correct adjacent matched-set decisions", 20, boundaries)
        val preacher = matches.filter { (it.second["exercise"] as String).startsWith("Preacher") }
        assertEquals(4, preacher.size)
        preacher.forEach { (s, _) -> assertEquals(140.0, median(s.repDetail.map { it.arcDeg }.toDoubleArray()), 12.0) }
        assertTrue(result.sets[8].partialLast)
        assertEquals(.2, result.sets[2].slowdownS!!, .1); assertEquals(.2, result.sets[3].slowdownS!!, .1)
        assertEquals(19, result.sets.count { it.activity == "Strength training" })
        // Forearm sets after the walk sit in the still-open treadmill record but past its active time: unlabelled.
        assertEquals(0, result.sets.count { it.activity == "Treadmill" })
        assertTrue(result.sets.count { it.startHr != null && it.hrRise != null } >= 20)
        assertTrue(result.sets.all { it.hrRise == null || it.hrRise >= 0 })
        fun delta(t: Int) = result.recovery.single { it.t == t }.delta
        assertEquals(-6.0, delta(20), 3.0); assertEquals(-10.0, delta(60), 4.0)
        assertEquals(delta(60), delta(120), 4.0); assertTrue(delta(180) > delta(120) + 3)
        assertTrue(result.recovery.all { it.n >= 5 })
        assertEquals(647, result.workS); assertEquals(4032, result.restS)
        assertEquals(22, result.floor.size); assertEquals(88.0, result.floor.first().v, 0.0)
        val cardio = result.cardio.single()
        assertEquals("Treadmill", cardio.name); assertEquals("walking", cardio.kind)
        assertEquals(179.0, cardio.steadyHr!!.toDouble(), 3.0); assertEquals(184.0, cardio.peakHr!!.toDouble(), 2.0)
        assertEquals(160.0, cardio.riseTauS!!.toDouble(), 60.0); assertEquals(.8, cardio.driftPerMin!!, 1.0)
        assertEquals(25.0, cardio.drop60!!.toDouble(), 10.0); assertEquals(40.0, cardio.drop120!!.toDouble(), 5.0)
        assertEquals(73.0, cardio.recoveryTauS!!.toDouble(), 30.0); assertEquals(91.0, cardio.cadenceSpm!!.toDouble(), 8.0)
        assertEquals(683, cardio.distanceM); assertEquals(4.02, cardio.speedKmh!!, .01)
        val pulse = result.pulse!!
        assertEquals("regular", pulse.status)
        val valid = pulse.windows.filter { it.status != "unreliable" }
        assertTrue("valid pulse windows=${valid.size}", valid.size >= 5)
        valid.forEach { assertTrue(abs(it.hrPpg - it.hrWatch!!) <= 8); assertTrue(it.nrmssd < .1) }
        assertEquals(0, pulse.ectopic); assertTrue(pulse.notes.any { it.contains("not a medical diagnosis") })
        assertTrue(pulse.pairs.size in 30..600); assertEquals(300, pulse.excerpt.size); assertTrue(pulse.excerptBeats.size >= 10)
    }

    @Test fun threeHourReplayKeepsPulseWorkBounded() {
        val r = recording()
        val duration = 10800.0
        fun times(x: DoubleArray) = x + x.map { it + 6500 }.filter { it < duration }.toDoubleArray()
        fun vectors(t: DoubleArray, x: Array<FloatArray>) = x + x.take(t.count { it + 6500 < duration }).toTypedArray()
        val rec = r.copy(captureDurationS = duration, accT = times(r.accArrivalT), accArrivalT = times(r.accArrivalT),
            acc = vectors(r.accArrivalT, r.acc), gyroT = times(r.gyroT), gyro = vectors(r.gyroT, r.gyro),
            ppgT = times(r.ppgArrivalT), ppgArrivalT = times(r.ppgArrivalT),
            ppg = r.ppg + r.ppg.take(r.ppgArrivalT.count { it + 6500 < duration }).toDoubleArray())
        val hr = fixture("recordings/gym_20261102_hr.json").obj()["hr"].array().map { it.array().let { p -> p[0].number() to p[1].number().toInt() } }
        val fullHr = hr + hr.map { it.first + 6500 to it.second }.filter { it.first < r.start + duration }
        val start = System.nanoTime()
        val result = analyzeCapture(rec, fullHr, Profile(), "Three-hour replay")
        val elapsed = (System.nanoTime() - start) / 1e9
        File("build/gym_three_hour_seconds.txt").writeText("$elapsed\n")
        assertEquals(duration, result.durationS, 0.0)
        assertTrue(result.sets.size > 23)
        val pulse = result.pulse!!
        assertTrue(pulse.windows.size <= 40); assertTrue(pulse.windows.all { it.end - it.start <= 120 })
        assertTrue(pulse.pairs.size <= 600); assertTrue(pulse.excerpt.size <= 300)
        println("Three-hour replay analysis seconds=$elapsed")
    }
}
