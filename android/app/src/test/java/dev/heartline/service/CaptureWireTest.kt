package dev.heartline.service

import dev.heartline.protocol.*
import org.junit.Assert.*
import org.junit.Test

class CaptureWireTest {
    @Test fun captureReusesHuamiThreeStageRawStartWithVerifiedGtr2eSensorMask() {
        // HuamiSupport.setRawSensor writes Huami2021Service.CMD_RAW_SENSOR_START_{1,2,3}.
        // GB's mask 0x03 = accel|PPG; measured GTR 2e gyro 0x10 + mag 0x40 => 0x53.
        assertEquals(0x53, rawMask(CAPTURE_SENSORS))
        val commands = rawStartCommands(rawMask(CAPTURE_SENSORS))
        assertArrayEquals(byteArrayOf(1, 0x53, 0x19), commands[0])
        assertArrayEquals(byteArrayOf(1, 0x53, 0, 0, 0, 0x19), commands[1])
        assertArrayEquals(byteArrayOf(2), commands[2]); assertArrayEquals(byteArrayOf(3), RAW_STOP)
    }
    @Test fun captureReusesHuamiLiveHrControlAndVerifiedKeepalive() {
        // HuamiSupport.onEnableRealtimeHeartRateMeasurement / stopHeartMeasurementManual,
        // startHeartMeasurementContinuous, stopHeartMeasurementContinuous. PING is the existing watch-verified quirk.
        assertArrayEquals(byteArrayOf(0x15, 2, 0), HrCommands.STOP_MANUAL)
        assertArrayEquals(byteArrayOf(0x15, 1, 1), HrCommands.START_CONTINUOUS)
        assertArrayEquals(byteArrayOf(0x15, 1, 0), HrCommands.STOP_CONTINUOUS)
        assertArrayEquals(byteArrayOf(0x16), HrCommands.PING)
    }
}
