package dev.heartline.analytics

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a check of correctness: replays heart-check recordings kept outside the repository and prints how many would
 * pass the automatic-check rule (OvernightHrv: RMSSD, 70% of quiet-time beats passing, 45 s of clean beats). Skipped
 * unless HEARTLINE_RECORDINGS names a folder of the app's raw CSVs. Personal recordings never go into git.
 */
class LocalRecordingsReport {
    @Test fun report() {
        val dir = System.getenv("HEARTLINE_RECORDINGS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val rows = dir!!.listFiles { f -> f.name.endsWith(".csv") }!!.sorted().mapNotNull { f ->
            val rec = f.bufferedReader().use(::readRecordingCsv)
            if (rec.ppg.size < 100) return@mapNotNull null
            val r = analyzeSession(rec, f.name)
            var quiet = 0; var good = 0
            r.beatTimes.forEachIndexed { i, t ->
                val g = r.motion.minByOrNull { kotlin.math.abs(it.t - t) }?.g ?: 0.0
                if (g <= MOVING_G) { quiet++; if (r.beatGood[i]) good++ }
            }
            val q = if (r.motion.isEmpty()) r.signalQuality else if (quiet == 0) 0.0 else good.toDouble() / quiet
            val moving = if (r.motion.isEmpty()) 0.0 else r.motion.count { it.g > MOVING_G }.toDouble() / r.motion.size
            val need = minOf(r.durationS / 2, 45.0)
            val clean = r.hrv.rmssdMs != null && q >= .7 && r.usableS >= need
            println("REC ${f.name} dur ${r.durationS} moving ${"%.2f".format(moving)} quality ${"%.2f".format(q)} usable ${r.usableS} rmssd ${r.hrv.rmssdMs} hr ${r.hrBpm} clean $clean")
            Triple(r.durationS < 150, clean, moving)
        }
        for (day in listOf(true, false)) {
            val g = rows.filter { it.first == day }
            println("SUMMARY ${if (day) "day" else "night"}: ${g.count { it.second }} of ${g.size} clean; median moving ${g.map { it.third }.sorted().getOrNull(g.size / 2)}")
        }
    }
}
