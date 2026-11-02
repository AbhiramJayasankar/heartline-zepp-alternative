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
            listOf(CaptureSet(0.0, 30.0, 10, 130, 160, 3.0,
                listOf(CaptureRep(1.0, 3.0, 2.0, 1.0, 140.0)), 2, "Strength training", .2, true, 120, 40)),
            listOf(CaptureRest(30.0, 100.0, 150, 110, 35)), "watch", listOf("quality"),
            listOf(CaptureActivity(0.0, 120.0, "Strength training", 120, 0, 20, 70, 130, 160)),
            listOf(CaptureRecoveryPoint(60, -10.5, 4)), listOf(CapturePoint(0.0, 95.0)), 30, 70,
            listOf(CaptureCardio(10.0, 100.0, "walking", "Treadmill", 140, 150, 160, 100, .8, 25, 40, 73, 91, 683, 4.0)),
            CapturePulse("regular", 30, listOf(CapturePulseWindow(30.0, 60.0, 50, 100, 101, 20.0, .03, "regular")),
                .03, .5, 0, listOf(600.0 to 605.0), listOf(.2, -.1), 25.0, 38.0, listOf(.5, 1.1), listOf("disclaimer")))
        val ui = result.captureUi("id")
        assertEquals("id", ui.id); assertEquals("Gym", ui.label); assertEquals(1000L, ui.start); assertEquals(120, ui.durationS)
        assertEquals(130, ui.avgHr); assertEquals(160, ui.maxHr); assertEquals(80, ui.minHr)
        assertEquals(ZoneTimeUi(5, "Max", 171, null, 30), ui.zones.single())
        assertEquals(4.5, ui.load!!, 0.0); assertEquals(70, ui.calories); assertEquals(15, ui.steps)
        assertEquals(SeriesPoint(5.0, 130.0), ui.hr.single()); assertEquals(SeriesPoint(5.0, .12), ui.motion.single())
        assertEquals(SegmentUi(0.0, 30.0, "lifting", 130, null), ui.segments.single())
        assertEquals(SetUi(0.0, 30.0, 10, 130, 160, 3.0, listOf(RepUi(1.0, 3.0, 2.0, 1.0, 140.0)), 2,
            "Strength training", .2, true, 120, 40), ui.sets.single())
        assertEquals(RestUi(30.0, 100.0, 150, 110, 35), ui.rests.single()); assertEquals("watch", ui.hrSource); assertEquals(listOf("quality"), ui.notes)
        assertEquals(CaptureActivityUi(0.0, 120.0, "Strength training", 120, 0, 20, 70, 130, 160), ui.activities.single())
        assertEquals(listOf(RecoveryPointUi(60, -10.5, 4)), ui.recovery); assertEquals(listOf(SeriesPoint(0.0, 95.0)), ui.floor)
        assertEquals(30, ui.workS); assertEquals(70, ui.restS)
        assertEquals(CardioUi(10.0, 100.0, "walking", "Treadmill", 140, 150, 160, 100, .8, 25, 40, 73, 91, 683, 4.0), ui.cardio.single())
        assertEquals(PulseUi("regular", 30, listOf(PulseWindowUi(30.0, 60.0, 50, 100, 101, 20.0, .03, "regular")),
            .03, .5, 0, listOf(600.0 to 605.0), listOf(.2, -.1), 25.0, 38.0, listOf(.5, 1.1), listOf("disclaimer")), ui.pulse)
        assertEquals(ui, captureFromJson(ui.captureJson()))
    }

    @Test fun oldJsonUsesDefaultsAndNullsRoundTrip() {
        val old = """{"id":"old","label":"Gym","start":1000,"duration":120,"avg":null,"max":null,"min":null,
            "load":null,"calories":null,"steps":null,"source":"watch","notes":[],"zones":[[5,171,null,0]],
            "hr":[],"motion":[],"segments":[[0,30,"lifting",null,null]],"sets":[[0,30,10,null,null,null]],
            "rests":[[30,100,null,null,null]]}"""
        val ui = captureFromJson(old)
        assertEquals(SetUi(0.0, 30.0, 10, null, null, null), ui.sets.single())
        assertTrue(ui.activities.isEmpty()); assertTrue(ui.recovery.isEmpty()); assertTrue(ui.floor.isEmpty())
        assertTrue(ui.cardio.isEmpty()); assertNull(ui.workS); assertNull(ui.restS); assertNull(ui.pulse)
        assertEquals(ui, captureFromJson(ui.captureJson()))
        val nullable = ui.copy(cardio = listOf(CardioUi(0.0, 120.0, "walking", null, null, null, null, null, null, null, null, null, null, null, null)),
            activities = listOf(CaptureActivityUi(0.0, 120.0, "Walking", null, null, null, null, null, null)),
            pulse = PulseUi("insufficient_data", 0, emptyList(), null, null, 0, emptyList(), emptyList(), 25.0, 0.0, emptyList(), emptyList()))
        assertEquals(nullable, captureFromJson(nullable.captureJson()))
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
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 80.0, 81.0), r.ppgArrivalT, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 80.0), r.accArrivalT, 0.0)
        assertArrayEquals(doubleArrayOf(80.5), r.gyroT, 0.0); assertEquals(120.0, r.captureDurationS!!, 0.0)
    }
}
