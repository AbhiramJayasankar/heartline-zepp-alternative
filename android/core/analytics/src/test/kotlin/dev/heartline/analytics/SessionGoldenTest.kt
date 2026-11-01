package dev.heartline.analytics

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.*

@RunWith(Parameterized::class)
class SessionGoldenTest(private val name: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun names(): List<Array<String>> = fixture("manifest.json").obj()["sessions"].array().map { arrayOf(it as String) }
    }

    @Test fun matchesPythonSession() {
        val rec = checkNotNull(javaClass.getResourceAsStream("/recordings/$name")).bufferedReader().use(::readRecordingCsv)
        val result = analyzeSession(rec, name)
        val expected = fixture("goldens/${name.removeSuffix(".csv")}.json").obj()
        println("COMPARISON $name | HR ${expected["hr_bpm"]}/${result.hrBpm} | " +
            "RMSSD ${expected["hrv"].obj()["rmssd_ms"]}/${result.hrv.rmssdMs} | SDNN ${expected["hrv"].obj()["sdnn_ms"]}/${result.hrv.sdnnMs} | " +
            "pNN50 ${expected["hrv"].obj()["pnn50"]}/${result.hrv.pnn50} | beats ${expected["beats"]}/${result.beats} | " +
            "SQI ${expected["signal_quality"]}/${result.signalQuality} | rhythm ${expected["rhythm"].obj()["status"]}/${result.rhythm.status} | " +
            "ectopic ${expected["rhythm"].obj()["ectopic_beats"]}/${result.rhythm.ectopicBeats} | respiration ${expected["respiratory_rate"]}/${result.respiratoryRate}")
        assertTree(expected, result.tree()) { path, v -> when {
            path == ".hr_bpm" -> 0.5
            path == ".beats" -> 1.0
            path == ".signal_quality" -> 0.02
            path == ".hrv.pnn50" -> 0.02 // percentage points, as returned by Python
            path in setOf(".hrv.rmssd_ms", ".hrv.sdnn_ms", ".hrv.sd1_ms", ".hrv.sd2_ms") -> abs(v) * 0.03
            path == ".respiratory_rate" || path.startsWith(".respiration.") -> 0.5
            path.startsWith(".ppg_preview[") -> 0.001
            path.startsWith(".rr[") && path.endsWith(".rr_ms") -> 0.1
            path == ".hrv.lf" || path == ".hrv.hf" || path == ".hrv.stress_index" -> max(0.1, abs(v) * .01)
            else -> 1e-8
        } }
    }
}
