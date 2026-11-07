package dev.heartline.app

import dev.heartline.analytics.analyzeSession
import dev.heartline.service.OvernightHrv
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** An automatic sample's row must hold everything the app uses, so its raw recording can be deleted. */
class SampleExtractTest {
    @Test
    fun rowKeepsEveryNumberAndTheBeatIntervals() {
        val f = File("../core/analytics/src/test/resources/recordings/20261101-023830.csv")
        val r = analyzeSession(readRecording(f), f.name)
        val j = JSONObject(OvernightHrv.extract(r, JSONObject().put("file", f.name)).toString())

        for (k in listOf("hr", "resp", "quality", "usable_s", "rmssd", "duration_s", "beats", "hrv", "rhythm", "ectopic", "resp_parts", "rr")) assertTrue(k, j.has(k))
        val rr = j.getJSONArray("rr")
        assertEquals(r.rr.size, rr.length())
        assertTrue(rr.length() > 20)

        // RMSSD recomputed from the stored intervals (successive non-ectopic pairs) matches the analysis.
        val iv = (0 until rr.length()).map { rr.getJSONArray(it) }
        val diffs = iv.zipWithNext().filter { (a, b) -> a.getInt(2) == 0 && b.getInt(2) == 0 }.map { (a, b) -> b.getDouble(1) - a.getDouble(1) }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val stored = j.getJSONObject("hrv").getDouble("rmssd")
        assertTrue("recomputed $rmssd vs $stored", abs(rmssd - stored) / stored < 0.15)
        assertTrue(iv.first().getDouble(0) < 10.0) // seconds from the start of the recording
    }
}
