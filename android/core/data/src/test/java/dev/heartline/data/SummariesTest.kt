package dev.heartline.data

import dev.heartline.data.model.*
import org.junit.Assert.*
import org.junit.Test

class SummariesTest {
    @Test fun hrStatsMatchPythonStoreTest() {
        assertEquals(HrStats(70, 90, 80.0, 2), hrStats(listOf(HrSample(101.0, 70), HrSample(102.0, 90))))
        assertEquals(HrStats(null, null, null, 0), hrStats(emptyList()))
    }
    @Test fun pythonRoundingUsesHalfEvenAndBinaryInput() {
        assertEquals(2.2, roundOne(2.25), 0.0)
        assertEquals(2.8, roundOne(2.75), 0.0)
        assertEquals(2.5, roundOne(2.55), 0.0)
        assertEquals(2.6, roundOne(2.65), 0.0)
    }
    @Test fun emptySummary() {
        assertEquals(ActivitySummary(0, null, null, null, 0, 0, 0), activitySummary(emptyList()))
    }
    @Test fun summaryCountsStatesAndMeasuredHr() {
        val minutes = listOf(
            ActivityMinute(1, "active", 80, 10), ActivityMinute(2, "idle", 60, 1),
            ActivityMinute(3, "not_worn", null, 0), ActivityMinute(4, "charging", null, 0),
            ActivityMinute(5, "unknown", null, 0), ActivityMinute(6, "sleep_light", 50, 0),
            ActivityMinute(7, "sleep_deep", null, 0), ActivityMinute(8, "sleep_rem", 55, 0),
        )
        assertEquals(ActivitySummary(11, 50, 61.2, 80, 2, 5, 3), activitySummary(minutes))
    }
    @Test fun repeatsMaskedKindsAndRemThreshold() {
        val rows = listOf(row(0), row(10), row(0x89, rem = 55), row(0, rem = 56), row(10, rem = 128 + 56), row(11))
        assertEquals(listOf("charging", "charging", "sleep_light", "sleep_rem", "sleep_rem", "sleep_deep"),
            activityMinutes(rows, 6).map { it.state })
    }
    @Test fun invalidHrAndUnknownKinds() {
        val minutes = activityMinutes(listOf(row(0, 0), row(10, 255), row(5, 254), row(5, -1), row(5, 256)))
        assertEquals(listOf("unknown", "unknown", "idle", "unknown", "unknown"), minutes.map { it.state })
        assertEquals(listOf(null, null, 254, null, null), minutes.map { it.hr })
    }
    @Test fun allActiveKindsAndNullColumns() {
        assertTrue(activityMinutes(listOf(1, 2, 4, 12).map { row(it) }).all { it.state == "active" })
        assertEquals(ActivityMinute(0, "unknown", null, 0),
            activityMinutes(listOf(ActivityRow(0, null, null, null, null, null, null, null, null))).single())
    }
    private fun row(kind: Int, hr: Int = 70, rem: Int = 0) = ActivityRow(0, kind, 0, 0, hr, 0, 0, 0, rem)
}
