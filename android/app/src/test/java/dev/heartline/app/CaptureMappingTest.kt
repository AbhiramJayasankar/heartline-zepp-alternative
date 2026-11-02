package dev.heartline.app

import dev.heartline.analytics.*
import dev.heartline.service.*
import org.junit.Assert.*
import org.junit.Test

class CaptureMappingTest {
    @Test fun resultMappingPreservesEveryContractFieldAndNulls() {
        val result = CaptureResult("Gym", 1000.0, 120.0, 130, 160, 80,
            listOf(CaptureZone(5, 171, null, 30)), 4.5, 70, 15,
            listOf(CapturePoint(5.0, 130.0)), listOf(CapturePoint(5.0, .12)),
            listOf(CaptureSegment(0.0, 30.0, "lifting", 130, null)),
            listOf(CaptureSet(0.0, 30.0, 10, 130, 160, 3.0)),
            listOf(CaptureRest(30.0, 100.0, 150, 110, 35)), "watch", listOf("quality"))
        val ui = result.captureUi("id")
        assertEquals("id", ui.id); assertEquals("Gym", ui.label); assertEquals(1000L, ui.start); assertEquals(120, ui.durationS)
        assertEquals(130, ui.avgHr); assertEquals(160, ui.maxHr); assertEquals(80, ui.minHr)
        assertEquals(ZoneTimeUi(5, "Max", 171, null, 30), ui.zones.single())
        assertEquals(4.5, ui.load!!, 0.0); assertEquals(70, ui.calories); assertEquals(15, ui.steps)
        assertEquals(SeriesPoint(5.0, 130.0), ui.hr.single()); assertEquals(SeriesPoint(5.0, .12), ui.motion.single())
        assertEquals(SegmentUi(0.0, 30.0, "lifting", 130, null), ui.segments.single())
        assertEquals(SetUi(0.0, 30.0, 10, 130, 160, 3.0), ui.sets.single())
        assertEquals(RestUi(30.0, 100.0, 150, 110, 35), ui.rests.single()); assertEquals("watch", ui.hrSource); assertEquals(listOf("quality"), ui.notes)
    }

    @Test fun pendingCaptureHasUnknownSummaryUntilCachedAnalysisExists() {
        val s = CaptureSession("id", "Gym", 100, 600)
        assertEquals(CaptureItemUi("id", "Gym", 100, 20, null, null, null), s.captureItem(null, 120))
        assertEquals(CaptureRunUi("Gym", 100, 600, 130, 20, 0), s.captureProgress(120, 130))
    }

    @Test fun reconnectPartsPreserveSensorOffsetsAndFullCaptureDuration() {
        val s = CaptureSession("id", "Gym", 100, 120, end = 220)
        val a = Recording(101.0, doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(0.0), arrayOf(floatArrayOf(0f, 0f, 1f)))
        val b = a.copy(start = 180.0, gyroT = doubleArrayOf(.5), gyro = arrayOf(floatArrayOf(1f, 2f, 3f)))
        val r = mergeCapture(s, listOf(a, b))
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 80.0, 81.0), r.ppgT, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 80.0), r.accArrivalT, 0.0)
        assertArrayEquals(doubleArrayOf(80.5), r.gyroT, 0.0); assertEquals(120.0, r.captureDurationS!!, 0.0)
    }
}
