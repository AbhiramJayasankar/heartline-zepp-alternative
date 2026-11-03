package dev.heartline.service

import dev.heartline.analytics.readRecordingCsv
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CaptureWatchClockTest {
    @Test fun reconnectFilesKeepEpochGapAndWatchTimeDespiteDifferentWatchOrigins() {
        val folder = File(System.getProperty("java.io.tmpdir"), "capture-clock-${System.nanoTime()}")
        folder.mkdirs()
        try {
            fun part(name: String, epoch: Double, watch: Double) {
                File(folder, name).writeText(buildString {
                    appendLine("ts,kind,ppg,ax,ay,az,wts")
                    repeat(100) { i -> appendLine("${epoch + i * .02},ppg,27000,,,,${watch + i * .02}") }
                })
            }
            part("a.csv", 1000.0, 200.0); part("b.csv", 1010.0, 2.0) // Reboot/reconnect.
            val session = CaptureSession("id", "Clock", 1000, 60, end = 1012, files = listOf("a.csv", "b.csv"))
            val merged = mergeCapture(session, readCaptureFiles(folder, session))
            assertTrue(merged.watchClock)
            assertEquals(160, merged.ppg.size)
            assertEquals(.4, merged.ppgT.first(), 1e-8)
            assertEquals(10.4, merged.ppgT[80], 1e-8)
            assertEquals(50.0, merged.fs, 1e-7)
            assertEquals(12.0, merged.captureDurationS!!, 0.0)
            val old = readRecordingCsv("ts,kind,ppg,ax,ay,az\n1000,ppg,27000,,,\n1000.04,ppg,27000,,,\n".reader())
            assertFalse(mergeCapture(session, listOf(old)).watchClock)
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
}
