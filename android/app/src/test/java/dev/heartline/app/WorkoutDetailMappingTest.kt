package dev.heartline.app

import dev.heartline.data.model.RawFetch
import dev.heartline.protocol.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WorkoutDetailMappingTest {
    private fun workout(duration: Int = 300, track: List<HrPoint> = emptyList()) =
        WorkoutUi(1000, duration, "Workout", null, null, null, null, null, track)
    private fun summary(start: Int = 1000, type: Int = 1) = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0, 515).putShort(2, type.toShort()).putInt(4, start).putInt(8, start + 300).putInt(20, -20000).array()
    private fun raw(type: String, start: Double?, data: ByteArray?, ts: Double = 1.0) = RawFetch(ts, type, start, null, data)

    @Test fun profileMaxHrThenAgeThenUnknownAgeFallback() {
        assertEquals(205, workoutMaxHr(ProfileUi(age = 40, maxHr = 205)))
        assertEquals(180, workoutMaxHr(ProfileUi(age = 40)))
        assertEquals(190, workoutMaxHr(ProfileUi()))
        assertEquals(190, workoutMaxHr(ProfileUi(age = -1, maxHr = 0)))
        assertEquals(190, workoutMaxHr(ProfileUi(age = 0)))
    }

    @Test fun exactZoneBoundariesAndEdwardsWeightedMinutes() {
        val track = listOf(100, 120, 140, 160, 180).mapIndexed { i, hr -> TrackPoint(i * 60, hr) }
        val zones = workoutZones(track, 300, 200)
        assertEquals(listOf(60, 60, 60, 60, 60), zones.map { it.seconds })
        assertEquals(listOf("Warm-up", "Fat burn", "Aerobic", "Anaerobic", "Max"), zones.map { it.name })
        assertEquals(listOf(100, 120, 140, 160, 180), zones.map { it.lowBpm })
        assertEquals(listOf(120, 140, 160, 180, null), zones.map { it.highBpm })
        assertEquals(15.0, edwardsTrimp(zones), 0.0)
    }

    @Test fun fractionalThresholdsRoundUpAndAboveMaxHrStaysInTopZone() {
        val zones = workoutZones(listOf(TrackPoint(0, 95), TrackPoint(10, 96), TrackPoint(20, 250)), 30, 191)
        assertEquals(listOf(96, 115, 134, 153, 172), zones.map { it.lowBpm })
        assertEquals(listOf(10, 0, 0, 0, 10), zones.map { it.seconds })
    }

    @Test fun sparseHrUsesTimeNotSampleCountsAndDoesNotBackfillFirstSample() {
        val zones = workoutZones(listOf(TrackPoint(10, 100), TrackPoint(70, 160)), 100, 200)
        assertEquals(listOf(60, 0, 0, 30, 0), zones.map { it.seconds })
        assertEquals(3.0, edwardsTrimp(zones), 0.0)
    }

    @Test fun unsortedDuplicateTimesClipToWorkoutAndLastDuplicateWins() {
        val track = listOf(TrackPoint(1000, 180), TrackPoint(20, 160), TrackPoint(0, 100),
            TrackPoint(20, 120), TrackPoint(-10, 180), TrackPoint(60, 180))
        assertEquals(listOf(20, 40, 0, 0, 0), workoutZones(track, 60, 200).map { it.seconds })
    }

    @Test fun belowFirstZoneInvalidHrAndEmptyTracksDoNotCreateLoad() {
        val track = listOf(TrackPoint(0, 99), TrackPoint(10, 0), TrackPoint(20, 255), TrackPoint(30, 100))
        assertEquals(listOf(10, 0, 0, 0, 0), workoutZones(track, 40, 200).map { it.seconds })
        assertEquals(0, workoutZones(emptyList(), 40, 200).sumOf { it.seconds })
        assertNull(workout().withWorkoutDetail(null, ProfileUi()).detail!!.load)
        assertNull(workout().withWorkoutDetail(null, ProfileUi()).detail!!.minHr)
    }

    @Test fun suppliedZoneTimesIncludingZeroOverrideTrackAndInvalidTimesFallBack() {
        val track = listOf(TrackPoint(0, 180))
        assertEquals(listOf(1, 2, 3, 4, 5), workoutZones(track, 60, 200, listOf(1, 2, 3, 4, 5)).map { it.seconds })
        assertEquals(listOf(0, 0, 0, 0, 0), workoutZones(track, 60, 200, List(5) { 0 }).map { it.seconds })
        assertEquals(listOf(0, 0, 0, 0, 60), workoutZones(track, 60, 200, listOf(-1, 0, 0, 0, 0)).map { it.seconds })
        assertEquals(listOf(0, 0, 0, 0, 60), workoutZones(track, 60, 200, listOf(1)).map { it.seconds })
    }

    @Test fun pausedIntervalsIncludingFinalUnresumedPauseDoNotCountTowardZonesOrTrimp() {
        // Real treadmill capture: duration 1000 s, last HR at 632 s, pause at 633 s, active 635 s.
        val track = listOf(TrackPoint(19, 100), TrackPoint(632, 160))
        val zones = workoutZones(track, 1000, 200, pauses = listOf(633))
        assertEquals(listOf(613, 0, 0, 1, 0), zones.map { it.seconds })
        assertEquals(617 / 60.0, edwardsTrimp(zones), 0.0)
        val mapped = workout(1000).withWorkoutDetail(StoredWorkoutDetail(null,
            WorkoutDetailsExt(hr = track, pauses = listOf(633)), emptyList()), ProfileUi(maxHr = 200))
        assertEquals(zones, mapped.detail!!.zones)
        assertEquals(edwardsTrimp(zones), mapped.detail!!.load!!, 0.0)
    }

    @Test fun repeatedPauseResumeEventsAndEventsOutsideWorkoutAreHandled() {
        val zones = workoutZones(listOf(TrackPoint(0, 100)), 100, 200,
            pauses = listOf(-1, 20, 25, 80, 120), resumes = listOf(40, 45))
        assertEquals(listOf(60, 0, 0, 0, 0), zones.map { it.seconds }) // [0,20) + [40,80)
    }

    @Test fun downsamplingIsBoundedOrderedAndKeepsBothEndpointsAndOriginalValues() {
        for (size in listOf(0, 1, 599, 600, 601, 10000)) {
            val points = (0 until size).map { SeriesPoint(it / 2.0, (it % 7).toDouble()) }
            val sampled = downsampleWorkoutSeries(points)
            assertEquals(minOf(size, 600), sampled.size)
            if (size <= 600) assertEquals(points, sampled)
            if (size > 0) { assertEquals(points.first(), sampled.first()); assertEquals(points.last(), sampled.last()) }
            assertTrue(sampled.zipWithNext().all { (a, b) -> a.t < b.t })
            assertTrue(sampled.all { it in points })
        }
    }

    @Test fun seriesUnitConversionsAndAllThreeSeriesAreLimitedTo600() {
        val points = (0..1200).map { WorkoutValuePoint(it, it / 100.0) }
        val decoded = StoredWorkoutDetail(null, WorkoutDetailsExt(speedMs = points, cadence = points, altitudeM = points), emptyList())
        val d = workout().withWorkoutDetail(decoded, ProfileUi()).detail!!
        assertEquals(listOf(600, 600, 600), listOf(d.speedKmh.size, d.cadence.size, d.altitude.size))
        assertEquals(43.2, d.speedKmh.last().v, 0.000001)
        assertEquals(12.0, d.cadence.last().v, 0.0)
        assertEquals(1200.0, d.altitude.last().t, 0.0)
    }

    @Test fun realTreadmillFixtureMapsPacesSpeedsStrideAndEveryExtraWithoutInventedEffects() {
        // Real capture 1793382094. Oracle offsets: speed 76/84 m/s, fastest pace 92 s/m,
        // avg pace 134 s/km, cadence 100/108 Hz*60, stride 136 cm (Gadgetbridge v515).
        val hex = "03020200ced6e46ab6dae46a0000000000000000e0b1ffff8a0300007b02000000000080ffffff7f00000080ffffff7fe151db4260582544000000000000000000409cc600409cc600409cc66067094044e13a3f7061853faf57af3fc57aee3e42ac753f0100c83ff876ae3f83b6b63f46e13a3f13d91e3f58e13a3f72860c44020300008d00bf034900ab000000000000000000000000000000000000000000000000000000000000000000a4670920f8970820000000004834002000000000a02f002004050000"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val raw = StoredWorkoutDetail(parseWorkoutSummaryExt(bytes), null, emptyList())
        val d = workout(track = listOf(HrPoint(1000, 72), HrPoint(1001, 141))).withWorkoutDetail(raw, ProfileUi()).detail!!
        assertEquals(635, d.activeS)
        assertEquals(959.0, d.avgPaceSPerKm!!, 0.0)
        assertEquals(465.7804071903229, d.bestPaceSPerKm!!, 0.000001)
        assertEquals(3.751329803466797, d.avgSpeedKmh!!, 0.000001)
        assertEquals(7.728964233398438, d.maxSpeedKmh!!, 0.000001)
        assertEquals(86, d.avgCadence); assertEquals(94, d.maxCadence); assertEquals(73, d.avgStrideCm)
        assertEquals(72, d.minHr); assertNull(d.minAltM); assertNull(d.maxAltM)
        assertNull(d.trainingEffect); assertNull(d.recoveryHours); assertTrue(d.laps.isEmpty())
        val extras = d.extras.toMap()
        assertEquals("2.628 km/h", extras["Minimum speed"])
        assertEquals("82 spm", extras["Minimum cadence"])
        assertEquals("62 cm", extras["Minimum stride"])
        assertEquals("562.101 m", extras["Secondary distance"])
    }

    @Test fun joinsOnWorkoutStartWithNewestCaptureAndPreservesRoute() {
        val older = summary().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 100) }
        val newer = summary().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 200) }
        val hr = byteArrayOf(1, 1, 70, 0, 0, 0, 0, 0)
        val data = storedWorkoutDetails(listOf(raw("workouts", 1.0, newer, 3.0), raw("workouts", 999.0, older),
            raw("workout_details", 1000.0, hr), raw("stress", 1000.0, hr), raw("workouts", 1.0, null)))
        assertEquals(setOf(1000L), data.keys)
        assertEquals(200, data.getValue(1000).summary!!.activeS)
        assertEquals(listOf(TrackPoint(1, 70)), data.getValue(1000).details!!.hr)
        assertTrue(data.getValue(1000).route.isEmpty())
    }

    @Test fun missingSummaryStillMapsDetailsAndSummaryOnlyStillMapsFields() {
        val hr = byteArrayOf(1, 1, 75, 0, 0, 0, 0, 0)
        val data = storedWorkoutDetails(listOf(raw("workout_details", 1000.0, hr), raw("workouts", 1.0, summary(2000))))
        assertNull(data.getValue(1000).summary)
        val d = workout().withWorkoutDetail(data[1000], ProfileUi()).detail!!
        assertEquals(75, d.minHr); assertNotNull(d.load)
        assertNull(data.getValue(2000).details)
        assertEquals(0, workout().withWorkoutDetail(data[2000], ProfileUi()).detail!!.activeS)
        assertTrue(storedWorkoutDetails(listOf(raw("workout_details", null, hr), raw("workouts", 1000.0, byteArrayOf(1)))).isEmpty())
    }

    @Test fun newestDetailFetchWinsRegardlessOfInputOrderAndDifferentWorkoutsDoNotMix() {
        val hr = byteArrayOf(1, 1, 75, 0, 0, 0, 0, 0)
        val newer = hr.copyOf().apply { this[2] = 85 }
        val data = storedWorkoutDetails(listOf(raw("workout_details", 1000.0, newer, 3.0), raw("workout_details", 1000.0, hr),
            raw("workout_details", 2000.0, hr, 2.0)))
        assertEquals(85, data.getValue(1000).details!!.hr.single().hr)
        assertEquals(75, data.getValue(2000).details!!.hr.single().hr)
    }

    @Test fun existingRouteWorksEvenWhenExtendedSummaryIsTooShort() {
        val b = summary().copyOf(24).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12, 216000000).putInt(16, 57000000).putInt(20, 10) }
        val gps = byteArrayOf(0, 1, 3, 0, 6, 0, 2, 0)
        val data = storedWorkoutDetails(listOf(raw("workouts", 1000.0, b), raw("workout_details", 1000.0, gps)))
        val mapped = workout().withWorkoutDetail(data[1000], ProfileUi())
        assertEquals(listOf(RoutePoint(1, 19.000002, 72.000001, 12.0)), mapped.route)
        assertEquals(listOf(SeriesPoint(1.0, 12.0)), mapped.detail!!.altitude)
    }

    @Test fun changingProfileRecomputesZonesAndLoadAndPreservesBasicWorkoutFields() {
        val base = workout(60, listOf(HrPoint(1000, 150)))
        val younger = base.withWorkoutDetail(null, ProfileUi(maxHr = 200))
        val older = base.withWorkoutDetail(null, ProfileUi(maxHr = 180))
        assertEquals(3.0, younger.detail!!.load!!, 0.0)
        assertEquals(4.0, older.detail!!.load!!, 0.0)
        assertEquals(base, younger.copy(detail = null))
        assertEquals(base, older.copy(detail = null))
    }

    @Test fun swimmingAndTerrainFieldsHaveReadableUnitsWithoutFabricatedLaps() {
        val b = ByteBuffer.wrap(summary(type = 6)).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(140, 1.75f).putFloat(164, 0.5f).putFloat(168, 129f)
            .putShort(176, 36).putShort(178, 92).put(180, 3).put(181, 12)
        val d = workout().withWorkoutDetail(StoredWorkoutDetail(parseWorkoutSummaryExt(b.array()), null, emptyList()), ProfileUi()).detail!!
        assertEquals("30 strokes/min", d.extras.toMap()["Average stroke rate"])
        assertEquals("Backstroke", d.extras.toMap()["Swim style"])
        assertEquals("12", d.extras.toMap()["Laps"])
        assertTrue(d.laps.isEmpty()) // Only a count is encoded; lap durations/distances are not available.
        val terrain = ByteBuffer.wrap(summary()).order(ByteOrder.LITTLE_ENDIAN).putFloat(144, 12.5f).putInt(148, 12500)
        val extras = workout().withWorkoutDetail(StoredWorkoutDetail(parseWorkoutSummaryExt(terrain.array()), null, emptyList()), ProfileUi()).detail!!.extras.toMap()
        assertEquals("12.5 m", extras["Uphill distance"]); assertEquals("12 s", extras["Uphill time"])
    }
}
