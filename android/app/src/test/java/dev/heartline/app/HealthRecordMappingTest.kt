package dev.heartline.app

import androidx.health.connect.client.records.*
import dev.heartline.data.model.ActivityMinute
import dev.heartline.data.model.HealthPoint
import dev.heartline.data.model.HrSample
import dev.heartline.service.isMiui
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HealthRecordMappingTest {
    private val zone = ZoneId.of("UTC")
    private val mapper = HealthRecordMapping(10, zone)
    private fun minute(ts: Long, state: String = "idle", hr: Int? = 65, steps: Int = 0) = ActivityMinute(ts, state, hr, steps)

    @Test fun hrIncludesMinuteAndLiveSamplesAndPrefersLiveAtIdenticalTimestamp() {
        val record = mapper.heartRate(listOf(minute(900), minute(960, hr = 255)),
            listOf(HrSample(900.0, 70), HrSample(901.123, 71), HrSample(902.0, 0))).single().value as HeartRateRecord
        assertEquals(listOf(70L, 71L), record.samples.map { it.beatsPerMinute })
        assertEquals(Instant.ofEpochMilli(901123), record.samples.last().time)
        assertEquals(Instant.ofEpochMilli(901124), record.endTime)
        assertEquals("heartline:hr:900", record.metadata.clientRecordId)
        assertEquals(10L, record.metadata.clientRecordVersion)
    }
    @Test fun bucketExtensionUsesSameIdAndDifferentFingerprint() {
        val a = mapper.heartRate(listOf(minute(900)), emptyList()).single()
        val b = HealthRecordMapping(11, zone).heartRate(listOf(minute(900), minute(960)), emptyList()).single()
        assertEquals(a.id, b.id); assertNotEquals(a.fingerprint, b.fingerprint)
        val c = HealthRecordMapping(12, zone).heartRate(listOf(minute(900)), emptyList()).single()
        assertEquals(a.fingerprint, c.fingerprint)
    }
    @Test fun stepsSumFifteenMinuteBucketsAndIgnoreNegativeCounts() {
        val result = mapper.steps(listOf(minute(900, steps = 4), minute(960, steps = 7), minute(1020, steps = -1), minute(1800)))
        val record = result.single().value as StepsRecord
        assertEquals(11L, record.count); assertEquals(Instant.ofEpochSecond(1080), record.endTime)
    }
    @Test fun sleepSpansMidnightMergesStagesAndDoesNotInventStagesForGaps() {
        val start = Instant.parse("2026-10-31T23:58:00Z").epochSecond
        val rows = listOf(minute(start, "sleep_light"), minute(start + 60, "sleep_light"),
            minute(start + 120, "sleep_deep"), minute(start + 180, "not_worn"),
            minute(start + 240, "idle"), minute(start + 360, "sleep_rem"))
        val record = mapper.sleep(rows).single().value as SleepSessionRecord
        assertEquals("heartline:sleep:2026-10-31", record.metadata.clientRecordId)
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_REM), record.stages.map { it.stage })
        assertEquals(120L, Duration.between(record.stages[0].startTime, record.stages[0].endTime).seconds)
        assertEquals(Instant.ofEpochSecond(start + 420), record.endTime)
    }
    @Test fun correctedSleepStartKeepsTheNightId() {
        val start = Instant.parse("2026-10-31T23:00:00Z").epochSecond
        val a = mapper.sleep(listOf(minute(start, "sleep_light"))).single()
        val b = mapper.sleep(listOf(minute(start - 60, "sleep_light"), minute(start, "sleep_light"))).single()
        assertEquals(a.id, b.id); assertNotEquals(a.fingerprint, b.fingerprint)
    }
    @Test fun emptyAndUnknownSleepIsNotExported() { assertTrue(mapper.sleep(listOf(minute(900, "unknown"))).isEmpty()) }
    @Test fun spo2ValidatesPercentagesAndDeduplicatesSleepAndManualAtSameInstant() {
        val result = mapper.oxygen(listOf(HealthPoint("spo2", 900, 97.0), HealthPoint("spo2_sleep", 900, 96.0),
            HealthPoint("spo2", 960, 255.0), HealthPoint("spo2", 970, Double.NaN)))
        assertEquals(97.0, (result.single().value as OxygenSaturationRecord).percentage.value, 0.0)
    }
    @Test fun restingExportsOneDailyMinimumWithoutInventingDataFromRawPlaceholders() {
        val result = mapper.resting(listOf(HealthPoint("resting_hr", 900, 62.0), HealthPoint("resting_hr", 960, 58.0), HealthPoint("resting_hr", 1000, null)))
        assertEquals(58L, (result.single().value as RestingHeartRateRecord).beatsPerMinute)
    }
    @Test fun hrvAndRespirationKeepUnitsAndFilterOutOfRangeValues() {
        val rows = listOf(ExportCheck(900.5, 42.2, 14.5), ExportCheck(960.0, 201.0, Double.NaN),
            ExportCheck(1020.0, 0.0, -1.0), ExportCheck(1080.0, 0.5, null))
        val hrv = mapper.checks(rows, false).single().value as HeartRateVariabilityRmssdRecord
        assertEquals(42.2, hrv.heartRateVariabilityMillis, 0.0)
        assertEquals(Instant.ofEpochMilli(900500), hrv.time)
        assertEquals(14.5, (mapper.checks(rows, true).single().value as RespiratoryRateRecord).rate, 0.0)
    }
    @Test fun workoutsExportKnownDistanceAndTotalCaloriesAndSkipUnknownsOrInvalidIntervals() {
        val rows = listOf(ExportWorkout(900, 1800, "Outdoor running", 2500.0, 200.0),
            ExportWorkout(2000, 3000, "New sport", null, null), ExportWorkout(4000, 4000, "Walking", 0.0, 0.0))
        assertEquals(2, mapper.workouts(rows, "exercise").size)
        assertEquals(2500.0, (mapper.workouts(rows, "distance").single().value as DistanceRecord).distance.inMeters, 0.0)
        assertEquals(200.0, (mapper.workouts(rows, "calories").single().value as TotalCaloriesBurnedRecord).energy.inKilocalories, 0.0)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, exerciseType("New sport"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, exerciseType("Outdoor running"))
    }
    @Test fun temperatureUsesAbsoluteBaselineAndRelativeDeltasAtWrist() {
        val record = mapper.temperature(listOf(HealthPoint("temperature", 900, 32.0), HealthPoint("temperature", 960, 32.5),
            HealthPoint("temperature", 1020, -100.0))).single().value as SkinTemperatureRecord
        assertEquals(32.0, record.baseline!!.inCelsius, 0.0)
        assertEquals(listOf(0.0, 0.5), record.deltas.map { it.delta.inCelsius })
        assertEquals(SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST, record.measurementLocation)
    }
    @Test fun offsetUsesHistoricalRulesAcrossDst() {
        val start = Instant.parse("2026-11-01T05:55:00Z").epochSecond
        val record = HealthRecordMapping(1, ZoneId.of("America/New_York"))
            .workouts(listOf(ExportWorkout(start, start + 3600, "Walking", null, null)), "exercise").single().value as ExerciseSessionRecord
        assertEquals(ZoneOffset.ofHours(-4), record.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(-5), record.endZoneOffset)
    }
    @Test fun xiaomiBrandDetectionIsCaseInsensitive() {
        assertTrue(isMiui("Xiaomi", "unknown")); assertTrue(isMiui("unknown", "POCO")); assertTrue(isMiui("Redmi", "unknown"))
        assertFalse(isMiui("Google", "google"))
    }
}
