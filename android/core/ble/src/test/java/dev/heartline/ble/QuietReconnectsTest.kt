package dev.heartline.ble

import org.junit.Assert.*
import org.junit.Test

class QuietReconnectsTest {
    @Test fun firstConnectionStillLogs() { assertEquals(listOf("Connected and authenticated"), QuietReconnects().authenticated(0)) }
    @Test fun quickDropsOnlyAppearInHourlyCount() {
        val q = QuietReconnects()
        repeat(40) { q.dropped(it * 60_000L); assertTrue(q.authenticated(it * 60_000L + 3000).isEmpty()) }
        assertEquals("Reconnected quietly 40 times in the last hour", q.summary())
        assertNull(q.summary())
    }
    @Test fun exactlyThirtySecondsIsQuiet() {
        val q = QuietReconnects(); q.dropped(10)
        assertNull(q.overdue(30_010)); assertTrue(q.authenticated(30_010).isEmpty())
    }
    @Test fun slowReconnectWarnsOnceAndLogsRecovery() {
        val q = QuietReconnects(); q.dropped(0)
        assertEquals("Connection lost, reconnecting", q.overdue(30_001))
        assertNull(q.overdue(90_000))
        assertEquals(listOf("Connected and authenticated"), q.authenticated(100_000))
        assertNull(q.summary())
    }
    @Test fun delayedTimerDoesNotHideLongOutage() {
        val q = QuietReconnects(); q.dropped(0)
        assertEquals(listOf("Connection lost, reconnecting", "Connected and authenticated"), q.authenticated(40_000))
    }
    @Test fun manualDisconnectClearsPendingDrop() {
        val q = QuietReconnects(); q.dropped(0); q.clearPending()
        assertNull(q.overdue(90_000)); assertEquals(listOf("Connected and authenticated"), q.authenticated(100_000))
    }
}
