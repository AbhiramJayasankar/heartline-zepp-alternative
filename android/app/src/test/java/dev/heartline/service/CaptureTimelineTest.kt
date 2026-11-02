package dev.heartline.service

import dev.heartline.protocol.*
import org.junit.Assert.*
import org.junit.Test

class CaptureTimelineTest {
    @Test fun plannedStopIsDueAtExactDeadlineAndNeverAfterFinish() {
        val s = CaptureSession("id", "Gym", 100, 60)
        assertFalse(s.due(159)); assertTrue(s.due(160)); assertTrue(s.due(200))
        assertFalse(s.finish(140).due(200)); assertEquals(40, s.finish(140).elapsed(200))
        assertEquals(0, s.elapsed(90))
    }
    @Test fun repeatedDisconnectUpdatesOpenOnlyOneGapAndReconnectClosesIt() {
        var s = CaptureSession("id", "Gym", 100, 600)
        s = s.availability(120, false).availability(125, false).availability(130, false)
        assertEquals(listOf(CaptureGap(120)), s.gaps)
        s = s.availability(140, true, "a.csv").availability(141, true, "a.csv")
        assertEquals(listOf(CaptureGap(120, 140)), s.gaps); assertEquals(listOf("a.csv"), s.files)
    }
    @Test fun stopDuringGapClosesGapAtActualEndAndRetainsAllFileParts() {
        val s = CaptureSession("id", "Gym", 100, 600).availability(100, true, "a.csv")
            .availability(130, false).availability(140, true, "b.csv").availability(170, false).finish(190)
        assertEquals(listOf("a.csv", "b.csv"), s.files)
        assertEquals(listOf(CaptureGap(130, 140), CaptureGap(170, 190)), s.gaps); assertEquals(190L, s.end)
    }
    @Test fun everyRecordedPpgAndMotionSampleCountsButMetadataDoesNot() {
        assertEquals(3, captureSampleCount(RawPacket.Ppg(intArrayOf(1, 2, 3))))
        assertEquals(1, captureSampleCount(RawPacket.Accel(listOf(Vec3f(0f, 0f, 1f)))))
        assertEquals(1, captureSampleCount(RawPacket.Gyro(listOf(Vec3i(1, 2, 3)))))
        assertEquals(1, captureSampleCount(RawPacket.Mag(listOf(Vec3i(1, 2, 3)))))
        assertEquals(0, captureSampleCount(RawPacket.Timestamp(0, 0, 1))); assertEquals(0, captureSampleCount(null))
    }
}
