package dev.heartline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEnergyTest {
    private val t0 = 1_800_000_000L
    private val day = sessionDate(t0, java.time.ZoneId.systemDefault()).toString()
    private val profile = ProfileUi(age = 22, sex = "male", weightKg = 70.0)

    /** 10 minutes of lifting at 130 bpm under a watch strength workout, then 10 minutes on the treadmill at 170. */
    private val capture = CaptureAnalysisUi(
        "c1", "Gym", t0, 1200, 150, 170, 130, emptyList(), null, null, null,
        hr = (0 until 1200 step 5).map { SeriesPoint(it.toDouble(), if (it < 600) 130.0 else 170.0) }, motion = emptyList(), segments = emptyList(),
        sets = listOf(
            SetUi(100.0, 160.0, 8, null, null, null, exercise = "Lat pulldown"),
            SetUi(280.0, 340.0, 8, null, null, null, exercise = "Lat pulldown"),
            SetUi(450.0, 500.0, 10, null, null, null, exercise = "Preacher curl"),
        ),
        rests = emptyList(), hrSource = "", notes = emptyList(),
        activities = listOf(CaptureActivityUi(0.0, 600.0, "Strength training", 600, null, null, 50, null, null)),
        cardio = listOf(CardioUi(600.0, 1200.0, "walking", "Treadmill", 165, 170, 172, 60, null, null, null, null, 98, null, null)),
    )
    private val tread = LoggedSet(day, "Gym", "Treadmill incline walk", "cardio", 1, "working", "completed",
        speedKmh = 3.8, inclinePct = 20.0, durationS = 600.0, caloriesKcal = 45.0)

    @Test fun acsmWalkingAtTwentyPercent() {
        // 3.8 km/h = 63.3 m/min: 3.5 + 6.33 + 1.8 x 63.3 x 0.20.
        assertEquals(32.63, acsmVo2(3.8, 20.0), 0.01)
        assertEquals(3.5 + 0.2 * 166.67, acsmVo2(10.0, 0.0), 0.01)
    }

    @Test fun treadmillUsesTheEquationAndTheWatchIsMetHalfway() {
        val e = captureEnergy(capture, listOf(tread), profile, hrRest = 70.0, vo2max = 45.0)!!
        val perStep = 32.63 * 70 / 1000 * 5 * 5 / 60
        assertEquals(1, e.treadmill.size)
        assertEquals(perStep * 120, e.treadmill[0].kcal, 0.2)
        assertEquals(45.0, e.treadmill[0].display!!, 0.0)
        assertEquals(e.treadmill[0].kcal, e.between(600.0, 1200.0), 0.01)
        val w = e.watchSpans.single()
        assertTrue(w.blended)
        assertEquals((w.watch + w.heartRate) / 2, w.used, 0.01)
        assertEquals(w.used + e.treadmill[0].kcal, e.total, 0.01)
        // Exercises own their sets and the rests after them; the last one a usual rest.
        assertEquals(listOf("Lat pulldown", "Preacher curl"), e.exercises.map { it.exercise })
        assertEquals(e.between(100.0, 450.0), e.exercises[0].kcal, 0.01)
        assertTrue(e.exercises.all { it.inSets < it.kcal })
        // Heart rate alone counts the walk from 170 bpm instead.
        assertTrue(e.heartRateOnly > 0 && e.heartRateOnly != e.total)
    }

    @Test fun heldHandrailsFallBackToHeartRate() {
        val e = captureEnergy(capture, listOf(tread.copy(handrails = "held")), profile, hrRest = 70.0, vo2max = 45.0)!!
        assertTrue(e.treadmill.isEmpty())
    }

    @Test fun needsWeight() {
        assertNull(captureEnergy(capture, emptyList(), profile.copy(weightKg = null), 70.0, 45.0))
    }
}
