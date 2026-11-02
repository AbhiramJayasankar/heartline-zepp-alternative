package dev.heartline.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthExportPolicyTest {
    private val now = 10_000_000L
    @Test fun firstAndManualExportsCoverThirtyDays() {
        assertEquals(now - 30 * 86400, exportFrom(now, null, false))
        assertEquals(now - 30 * 86400, exportFrom(now, now, true))
    }
    @Test fun incrementalReplayCatchesSevenDaysOfLateWatchDataAndBoundsClockRollback() {
        assertEquals(now - 7 * 86400, exportFrom(now, now, false))
        assertEquals(now - 7 * 86400, exportFrom(now, now + 1000, false))
        assertEquals(now - 30 * 86400, exportFrom(now, 0, false))
    }
    @Test fun unchangedRecordsAndDuplicateInputDoNotWrite() = runBlocking {
        var calls = 0; var committed = false
        val item = ExportItem("a", now, "same", 42)
        val count = writeExportType(listOf(item, item), mapOf("a" to ExportStamp(now, "same")), now,
            { calls++ }, { _, watermark -> committed = true; assertEquals(now, watermark) })
        assertEquals(0, count); assertEquals(0, calls); assertTrue(committed)
    }
    @Test fun batchesAreAtMostOneThousandAndChangedContentReplacesExistingId() = runBlocking {
        val batches = mutableListOf<Int>()
        val items = (0..2000).map { ExportItem("$it", now, "new", it) }
        var ledger: Map<String, ExportStamp> = emptyMap()
        assertEquals(2001, writeExportType(items, mapOf("0" to ExportStamp(now, "old")), now,
            { batches += it.size }, { records, _ -> ledger = records }))
        assertEquals(listOf(1000, 1000, 1), batches)
        assertEquals("new", ledger["0"]?.fingerprint)
    }
    @Test fun partialFailureNeverAdvancesWatermarkAndCanRetry() = runBlocking {
        val items = (0..1000).map { ExportItem("$it", now, "hash", it) }
        var committed = false; var calls = 0
        try {
            writeExportType(items, emptyMap(), now, { if (++calls == 2) error("provider stopped") }, { _, _ -> committed = true })
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertFalse(committed)
        assertEquals(1001, writeExportType(items, emptyMap(), now, {}, { _, _ -> committed = true }))
        assertTrue(committed)
    }
    @Test fun ledgerPrunesOnlyDataOlderThanThirtyDays() = runBlocking<Unit> {
        writeExportType(emptyList<ExportItem<Int>>(), mapOf("old" to ExportStamp(0, "h"), "recent" to ExportStamp(now, "h")), now,
            { fail("No inserts") }, { ledger, _ -> assertEquals(setOf("recent"), ledger.keys) })
    }
}
