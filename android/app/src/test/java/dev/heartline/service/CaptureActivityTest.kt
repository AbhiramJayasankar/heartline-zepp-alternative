package dev.heartline.service

import dev.heartline.analytics.CaptureActivity
import dev.heartline.data.model.Workout
import org.junit.Assert.*
import org.junit.Test

class CaptureActivityTest {
    @Test fun workoutsBeginningBeforeCaptureAreClippedAndWatchTotalsRetained() {
        val row = Workout(50, """{"start":50,"end":300,"type":"Treadmill","active_s":200,"distance_m":683,
            "steps":935,"calories":137,"avg_hr":166,"max_hr":184}""")
        assertEquals(CaptureActivity(0.0, 100.0, "Treadmill", 200, 683, 935, 137, 166, 184), captureActivity(row, 100.0, 200.0))
        assertNull(captureActivity(row, 300.0, 400.0)); assertNull(captureActivity(row, 0.0, 50.0))
        assertNull(captureActivity(Workout(100, "{}"), 0.0, 200.0))
        assertNull(captureActivity(Workout(100, "corrupt"), 0.0, 200.0))
    }
}
