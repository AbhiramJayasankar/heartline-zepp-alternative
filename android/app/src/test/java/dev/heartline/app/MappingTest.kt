package dev.heartline.app

import dev.heartline.analytics.*
import dev.heartline.data.model.ActivityRow
import dev.heartline.ble.RawUpdate
import dev.heartline.protocol.RawPacket
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MappingTest {
    private val now = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private fun daily() = computeDaily(emptyList(), emptyList(), emptyList(), Profile(), 28, now)
    @Test fun trendKeysTitlesAndUnitsMatchDemo() {
        val series = daily().trendsUi(emptyMap()).series
        assertEquals(listOf("readiness", "rhr", "awake_rhr", "hrv", "hrv_sleep", "sleep", "sleep_score", "steps", "strain", "active", "spo2", "stress", "pai", "temp"), series.map { it.key })
        assertEquals(listOf("Readiness", "Sleeping heart rate", "Awake resting heart rate", "HRV", "Overnight HRV", "Sleep", "Sleep score", "Steps", "Strain", "Active time", "Blood oxygen", "Stress", "PAI", "Skin temperature"), series.map { it.title })
        assertEquals("h", series.single { it.key == "sleep" }.unit)
    }
    @Test fun sleepMinutesBecomeHoursAndAbsentValuesStayNull() {
        val original = daily(); val day = original.days.last().copy(sleepMinutes = 450, hrvRmssd = null)
        val trends = original.copy(days = listOf(day)).trendsUi(emptyMap())
        assertEquals(7.5, trends.series.single { it.key == "sleep" }.points.single().value!!, 0.0)
        assertNull(trends.series.single { it.key == "hrv" }.points.single().value)
    }
    @Test fun healthSeriesUseLocalDateKeys() {
        val d = now.toLocalDate(); val trends = daily().trendsUi(mapOf("temperature" to mapOf(d to 32.4), "pai" to mapOf(d to 58.0)))
        assertEquals(32.4, trends.series.single { it.key == "temp" }.points.last().value!!, 0.0)
        assertEquals(58.0, trends.series.single { it.key == "pai" }.points.last().value!!, 0.0)
    }
    @Test fun previousReadinessHasDatedLabel() {
        val sleep = dev.heartline.analytics.ReadinessFactor("Sleep", "neutral", "sleep score 76", 0.4, 0.25, 2.0)
        val d = daily(); val t = d.copy(today = d.today.copy(readiness = null, lastReadiness = LastReadiness("2026-10-31", 72, listOf(sleep)))).todayUi(now.toLocalDate())
        assertEquals(72, t.readiness); assertEquals("Readiness, 31 Oct", t.readinessLabel)
        // The breakdown is that day's too, so it adds up to the number shown.
        assertEquals(listOf("Sleep"), t.factors.map { it.name }); assertEquals(2.0, t.factors.single().points, 0.0)
    }
    @Test fun invalidHeartRateIsRemovedButRawActivityIsPreserved() {
        val row = ActivityRow(1, 0, 0, 3, 255, 0, 0, 0, 0)
        assertEquals(255, row.protocolMinute().hr); assertNull(mappedMinutes(listOf(row)).single().hr)
    }
    @Test fun activityUsesPreviousKindAcrossDayBoundary() {
        val row = ActivityRow(1, 0, 0, 0, 52, 0, 0, 0, 0)
        assertEquals(MinuteState.SLEEP_DEEP, mappedMinutes(listOf(row), 11).single().state)
        assertEquals("sleep_deep", analyticsMinutes(listOf(row), 11).single().state)
    }
    @Test fun sessionMapsMetricsIntervalsPreviewAndBeatTimesWithoutUnitChanges() {
        val result = SessionResult("check.csv", 1790000000.5, 90.0, 0.94, 80.0, 72.8, 100,
            HrvResult(824.0, 48.0, 44.0, 21.0, 31.0, 53.0, 300.0, 200.0, 1.5, 96.0),
            RhythmResult("irregular", 1, 0.1, listOf("One premature beat"), null), 14.2, RespirationComponents(null, null, null),
            listOf(RrSample(1.0, 824.0, true)), listOf(0.25, -0.5), 20.0, listOf(0.2, 1.0), listOf(true, false), emptyList())
        val s = result.sessionUi("id")
        assertEquals("id", s.id); assertEquals(1790000000L, s.start); assertEquals(44.0, s.rmssd!!, 0.0)
        assertEquals(21.0, s.pnn50!!, 0.0); assertEquals(1.5, s.lfHf!!, 0.0); assertEquals(RhythmStatus.IRREGULAR, s.rhythm)
        assertEquals(listOf(RrUi(1.0, 824.0, true)), s.rr); assertArrayEquals(floatArrayOf(0.25f, -0.5f), s.ppgPreview, 0f)
        assertEquals(20.0, s.previewFs, 0.0); assertArrayEquals(doubleArrayOf(0.2, 1.0), s.beatTimes, 0.0)
    }
    @Test fun rhythmUnknownAndInsufficientStayInsufficient() {
        assertEquals(RhythmStatus.POSSIBLE_AF, rhythmStatus("possible_af")); assertEquals(RhythmStatus.REGULAR, rhythmStatus("regular"))
        assertEquals(RhythmStatus.IRREGULAR, rhythmStatus("irregular")); assertEquals(RhythmStatus.INSUFFICIENT, rhythmStatus(null))
    }
    @Test fun quietHoursWrapMidnightWithExclusiveEnd() {
        assertTrue(quietNow(23 * 60, 23 * 60, 7 * 60)); assertTrue(quietNow(0, 23 * 60, 7 * 60))
        assertFalse(quietNow(7 * 60, 23 * 60, 7 * 60)); assertFalse(quietNow(100, null, null)); assertFalse(quietNow(100, 0, 0))
    }
    @Test fun daytimeQuietHours() { assertTrue(quietNow(750, 720, 780)); assertFalse(quietNow(780, 720, 780)) }
    @Test fun dedupeExpiresAtTenSecondsAndAllowsChangedText() {
        val d = NotificationDeduplicator(); assertTrue(d.accept("A", "B", 0)); assertFalse(d.accept("A", "B", 9999))
        assertTrue(d.accept("A", "C", 9999)); assertTrue(d.accept("A", "B", 10000))
    }
    @Test fun ringBuffersKeepNewestValuesWithoutMutatingSnapshots() {
        val ring = RingBuffer<Int>(3); repeat(3) { ring.add(it) }; val old = ring.snapshot(); ring.add(3)
        assertEquals(listOf(0, 1, 2), old); assertEquals(listOf(1, 2, 3), ring.snapshot())
    }
    @Test fun compassHardIronOffsetAndCalibrationMatchWeb() {
        val c = CompassCalibration(); c.add(floatArrayOf(-900f, -800f, -500f)); c.add(floatArrayOf(1100f, 1200f, 500f))
        assertEquals(1.0, c.fraction, 0.0)
        assertEquals(0.0, c.heading(floatArrayOf(1100f, 200f, 0f), floatArrayOf(0f, 0f, 1f))!!, 0.001)
        assertEquals(270.0, c.heading(floatArrayOf(100f, 1200f, 0f), null)!!, 0.001)
    }
    @Test fun compassTiltCompensationMatchesWebEquations() {
        val c = CompassCalibration(); c.add(floatArrayOf(-1000f, -1000f, -1000f)); c.add(floatArrayOf(1000f, 1000f, 1000f))
        assertEquals(324.73561, c.heading(floatArrayOf(1000f, 0f, 1000f), floatArrayOf(0f, -0.7071f, 0.7071f))!!, 0.01)
    }
    @Test fun sensorBuffersAreEightSecondsAt25Hz() {
        val buffers = SensorBuffers(); buffers.feed(RawUpdate(RawPacket.Ppg(IntArray(300) { it })))
        val s = buffers.snapshot(SensorsUi()); assertEquals(200, s.ppg.size); assertEquals(100f, s.ppg.first(), 0f)
    }

    @Test fun lastChargeIsShownInThePhonesZone() {
        assertEquals("31 Oct, 04:10", chargedLabel("2026-10-31T04:10:58+05:30", java.time.ZoneId.of("Asia/Kolkata")))
        assertEquals("not a date", chargedLabel("not a date"))
        assertEquals(null, chargedLabel(null))
    }

    @Test fun exportLabelsSingularise() {
        assertEquals("night of sleep", singular("nights of sleep"))
        assertEquals("oxygen reading", singular("oxygen readings"))
        assertEquals("HRV measurement", singular("HRV measurements"))
        assertEquals("daily resting heart rate", singular("daily resting heart rates"))
        assertEquals("skin temperature bucket", singular("skin temperature buckets"))
    }
}
