package dev.heartline.app

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PhoneFeaturesPolicyTest {
    @Test fun findPhoneDuplicateStartsDoNotExtendTheSixtySecondDeadline() {
        val timer = FindPhoneTimer()
        assertTrue(timer.start(1000))
        assertFalse(timer.start(31_000))
        assertEquals(30_000L, timer.remaining(31_000))
        assertEquals(1L, timer.remaining(60_999))
        assertEquals(0L, timer.remaining(61_000))
        timer.stop()
        assertEquals(0L, timer.remaining(61_000))
        assertTrue(timer.start(61_000))
        assertEquals(60_000L, timer.remaining(61_000))
    }
    @Test fun openMeteoLocalTimesConvertUsingResponseOffsetAndPhoneZone() {
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").epochSecond, weatherTimestamp("2026-11-02T12:00", 19800))
        assertEquals(LocalTime.of(1, 0), weatherSolarTime("2026-11-02T06:30", 19800, ZoneOffset.UTC))
        assertEquals(LocalTime.of(6, 30), weatherSolarTime("2026-11-02T06:30", 19800, ZoneId.of("Asia/Kolkata")))
        assertNull(weatherSolarTime("null", 19800, ZoneOffset.UTC))
        assertNull(weatherSolarTime(null, 19800, ZoneOffset.UTC))
    }
    @Test fun chosenPlaceMustHaveFiniteCoordinatesWithinEarthBounds() {
        assertTrue(validPlace(PlaceUi("Zero", null, 0.0, 0.0)))
        assertFalse(validPlace(PlaceUi("", null, 0.0, 0.0)))
        assertFalse(validPlace(PlaceUi("Bad", null, Double.NaN, 0.0)))
        assertFalse(validPlace(PlaceUi("Bad", null, 91.0, 0.0)))
        assertFalse(validPlace(PlaceUi("Bad", null, 0.0, -181.0)))
    }
    @Test fun reconnectUsesCacheUntilOlderThanThirtyMinutes() {
        assertTrue(WeatherPolicy.needsFetch(null, 100))
        assertFalse(WeatherPolicy.needsFetch(100, 100))
        assertFalse(WeatherPolicy.needsFetch(100, 1900))
        assertTrue(WeatherPolicy.needsFetch(100, 1901))
        assertTrue(WeatherPolicy.needsFetch(100, 99)) // Phone clock moved backwards.
        assertEquals(3_600_000L, WeatherPolicy.SEND_INTERVAL_MS)
    }
    @Test fun plumeThresholdsFromPlume2023Table3MatchGadgetbridgeScale() {
        assertEquals(20, plumeAqi(5.0, 15.0, 10.0, 50.0))
        assertEquals(50, plumeAqi(15.0, 45.0, 25.0, 100.0))
        assertEquals(100, plumeAqi(30.0, 80.0, 200.0, 160.0))
        assertEquals(150, plumeAqi(60.0, 160.0, 400.0, 240.0))
        assertEquals(250, plumeAqi(150.0, 400.0, 1000.0, 480.0))
    }
    @Test fun plumeInterpolatesAndUsesWorstPollutant() {
        assertEquals(35, plumeAqi(10.0, 0.0, 0.0, 0.0))
        assertEquals(125, plumeAqi(0.0, 0.0, 300.0, 0.0))
        assertEquals(300, plumeAqi(195.0, 0.0, 0.0, 0.0))
        assertEquals(0, plumeAqi(0.0, 0.0, 0.0, 0.0))
    }
    @Test fun missingOrInvalidPollutantNeverBecomesCleanAir() {
        assertEquals(-1, plumeAqi(null, 0.0, 0.0, 0.0))
        assertEquals(-1, plumeAqi(Double.NaN, 0.0, 0.0, 0.0))
        assertEquals(-1, plumeAqi(0.0, -1.0, 0.0, 0.0))
    }
    @Test fun musicChoosesPlayingSessionAheadOfPausedAndFallsBackToAndroidPriority() {
        assertEquals(1, preferredMusicIndex(listOf(false, true, true)))
        assertEquals(0, preferredMusicIndex(listOf(false, false)))
        assertNull(preferredMusicIndex(emptyList()))
    }
    @Test fun nowPlayingHandlesMissingTitleArtistWithoutDanglingComma() {
        assertEquals("Title, Artist", nowPlayingText(" Title ", "Artist"))
        assertEquals("Title", nowPlayingText("Title", null))
        assertEquals("Artist", nowPlayingText("", "Artist"))
        assertNull(nowPlayingText(null, " "))
    }
    @Test fun musicVolumeIsRoundedPercentAsInHuamiSupportOnSetPhoneVolume() {
        assertEquals(67, musicVolumePercent(10, 15))
        assertEquals(0, musicVolumePercent(0, 0))
        assertEquals(100, musicVolumePercent(20, 15))
    }
    @Test fun positionAdvancesWithSpeedWhilePlayingAndStopsWhenPaused() {
        assertEquals(14, musicPositionSeconds(10_000, 1000, 3000, 2f, true, 60_000))
        assertEquals(10, musicPositionSeconds(10_000, 1000, 3000, 2f, false, 60_000))
        assertEquals(11, musicPositionSeconds(10_000, 1000, 3000, 1f, true, 11_000))
        assertEquals(0, musicPositionSeconds(-1, 0, 1000, 1f, true, 0))
        assertEquals(65535, musicPositionSeconds(Long.MAX_VALUE, 0, 0, 0f, false, 0))
    }
}
