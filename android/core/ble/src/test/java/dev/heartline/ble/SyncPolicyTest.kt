package dev.heartline.ble

import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    @Test fun initialFetchStartsSevenDaysAgo() {
        assertEquals(395200L, SyncPolicy.activitySince(null, 1_000_000))
        assertEquals(395200L, SyncPolicy.healthSince(null, 1_000_000))
    }
    @Test fun activityOverlapsFiveMinutesIncludingLastMinute() { assertEquals(760L, SyncPolicy.activitySince(1000, 2000)) }
    @Test fun healthStartsAfterLastSample() { assertEquals(1060L, SyncPolicy.healthSince(1000, 2000)) }
    @Test fun repeatedOrBackwardFetchStops() { assertNull(SyncPolicy.next(1000, 940)); assertNull(SyncPolicy.next(1000, 900)); assertEquals(1060L, SyncPolicy.next(1000, 1000)) }
    @Test fun reconnectBackoffIsBounded() { assertEquals(listOf(3000L, 6000, 12000, 24000, 48000, 60000, 60000), (0..6).map { SyncPolicy.reconnectDelay(it) }) }
}
