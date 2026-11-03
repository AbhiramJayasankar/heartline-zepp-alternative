package dev.heartline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchEventsTest {
    @Test fun silentModeEvents() {
        assertEquals(PhoneEvent.SILENT_ON, phoneEvent(byteArrayOf(0x10, 1)))
        assertEquals(PhoneEvent.SILENT_OFF, phoneEvent(byteArrayOf(0x10, 0)))
        assertEquals("06190001", phoneSilentCommand(true).hex())
        assertEquals("06190000", phoneSilentCommand(false).hex())
    }

    @Test fun workoutStarting() {
        // HuamiSupport.handleDeviceEvent WORKOUT_STARTING: value[2] needs GPS, value[3] the activity type
        assertEquals(WatchWorkoutStart(0x02, false), watchWorkoutStart(byteArrayOf(0x14, 0, 0, 0x02)))
        assertEquals("Treadmill", watchWorkoutStart(byteArrayOf(0x14, 0, 0, 0x02))!!.name)
        assertEquals(true, watchWorkoutStart(byteArrayOf(0x14, 0, 1, 0x01))!!.needsGps)
        assertNull(watchWorkoutStart(byteArrayOf(0x04)))
    }
}
