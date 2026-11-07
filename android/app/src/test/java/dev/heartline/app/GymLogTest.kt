package dev.heartline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymLogTest {
    private val older = """row_id,date,session,exercise,exercise_type,set_number,set_type,start_time,end_time,duration_sec,rest_before_sec,rest_context,load_kg,load_basis,load_per_side_kg,effective_load_kg,reps,reps_note,volume_kg,sec_per_rep,attachment_or_grip,difficulty_0_10,incline_pct,speed_kmh,distance_km,calories_kcal,steps,hr_bpm_min,hr_bpm_max,time_source,notes
1,2026-11-02,Pull day,Assisted pull-up (machine),bodyweight,3,working,2026-11-02T22:17:25+05:30,2026-11-02T22:18:18+05:30,53,97,same_exercise,60,assist_weight,,19,8,,,6.6,,,,,,,,,,measured,
2,2026-11-02,Pull day,Treadmill (incline walk),cardio,1,working,2026-11-02T23:22:37+05:30,2026-11-02T23:32:40+05:30,603,67,after_other_exercise,,,,,,,,,,,20,3.7,0.614,44,935,,,start_measured_end_derived,"Display final 10:03, ""Hr"" not shown"
"""
    private val newer = """session_id,exercise,set_number,start_datetime_ist,end_datetime_ist,duration_seconds,weight_kg,weight_type,reps_reported,full_reps,partial_rep_fraction,average_seconds_per_rep,rest_before_seconds,difficulty_out_of_10,reps_in_reserve,reached_failure,set_type,status,notes,volume_kg_reps,rest_before_context
2026-11-06_back_biceps_forearms,Assisted pull-up,2,2026-11-06T23:16:48+05:30,2026-11-06T23:17:43+05:30,55.0,55.0,assistance,8.0,8.0,0.0,6.875,107.0,6.3,,False,working,completed,6.3/10,,same exercise
2026-11-06_back_biceps_forearms,Preacher curl machine,2,2026-11-06T23:59:17+05:30,2026-11-07T00:00:19+05:30,62.0,25.0,external_load,8.0,8.0,0.0,7.75,187.0,,0.0,True,working,completed,Failure,200.0,same exercise
2026-11-06_back_biceps_forearms,45-degree weighted back extension,1,2026-11-07T00:06:57+05:30,,,5.0,external_load,,,,,167.0,,,False,attempted,skipped,"Plate held in front, skipped",,transition
"""
    private val json = """{"sessions":[{"date":"2026-11-03","name":"Chest day","exercises":[{"exercise":"Bench press (barbell, flat)","sets":[
        {"exercise_type":"strength","set_number":0,"set_type":"warmup","start_time":"2026-11-03T23:21:43.856+05:30","end_time":"2026-11-03T23:22:28.968+05:30","load_kg":30,"load_basis":"bar_plus_plates","reps":10},
        {"exercise_type":"strength","set_number":2,"set_type":"working","start_time":"2026-11-03T23:27:27.690+05:30","end_time":"2026-11-03T23:28:23.680+05:30","load_kg":45,"reps":10}]}]}]}"""

    @Test fun olderCsvReadsAssistanceAndTreadmill() {
        val sets = parseGymLog(older, 79.0)
        assertEquals(2, sets.size)
        val pull = sets[0]
        assertEquals(19.0, pull.loadKg!!, 0.0); assertEquals(60.0, pull.assistKg!!, 0.0); assertEquals(8.0, pull.reps!!, 0.0)
        assertEquals(53L, pull.end!! - pull.start!!)
        val tread = sets[1]
        assertTrue(tread.cardio)
        assertEquals(3.7, tread.speedKmh!!, 0.0); assertEquals(20.0, tread.inclinePct!!, 0.0); assertEquals(603.0, tread.durationS!!, 0.0)
        assertEquals("Display final 10:03, \"Hr\" not shown", tread.notes)
    }

    @Test fun newerCsvReadsTimesAcrossMidnightFailureAndSkips() {
        val sets = parseGymLog(newer, 79.0)
        assertEquals(3, sets.size)
        assertEquals("2026-11-06", sets[1].date)
        assertEquals("back biceps forearms", sets[0].session)
        assertEquals(24.0, sets[0].loadKg!!, 0.0) // 79 - 55 assistance
        assertTrue(sets[1].failure); assertEquals(62L, sets[1].end!! - sets[1].start!!)
        assertEquals("skipped", sets[2].status); assertNull(sets[2].end)
    }

    @Test fun jsonExportAndMergeKeepTheRicherCopy() {
        val fromJson = parseGymLog(json, 79.0)
        assertEquals(2, fromJson.size)
        assertEquals("warm-up", fromJson[0].setType)
        val untimed = fromJson.map { it.copy(start = null, end = null) }
        val (merged, added) = mergeGymLog(untimed, fromJson)
        assertEquals(0, added)
        assertTrue(merged.all { it.start != null })
        val (again, added2) = mergeGymLog(merged, untimed)
        assertEquals(0, added2)
        assertTrue(again.all { it.start != null })
        assertEquals(merged, gymLogFromJson(gymLogJson(merged)))
    }

    @Test fun sessionsGroupAndTotal() {
        val s = sessionsOf(parseGymLog(newer, 79.0)).single()
        assertEquals(24.0 * 8 + 25.0 * 8, s.volumeKg, 0.01)
        assertEquals(2, s.workingSets)
    }

    @Test fun untimedTreadmillFindsItsStretchInTheWatchWorkout() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val start = java.time.ZonedDateTime.of(2026, 10, 31, 23, 0, 0, 0, zone).toEpochSecond()
        // 5 easy minutes, then the 10-minute incline walk at 172.
        val track = (0 until 900 step 5).map { (start + it) to if (it < 300) 120 else 172 }
        // The watch kept the workout open 6 minutes after the track ends; the search must stay on the readings.
        val watch = listOf(WatchWorkoutRef(start, start + 1260, "Treadmill", track))
        val set = LoggedSet("2026-10-31", "Leg day", "Treadmill (incline walk)", "cardio", 1, "working", "completed",
            speedKmh = 3.6, inclinePct = 20.0, durationS = 600.0)
        val e = treadmillEfforts(listOf(set), watch, zone).single()
        assertEquals(start + 300, e.start)
        assertEquals(172, e.steadyHr)
        assertEquals(listOf(set), setsForWorkout(listOf(set), start, start + 900, "Treadmill", zone))
        assertTrue(setsForWorkout(listOf(set), start, start + 900, "Strength training", zone).isEmpty())
    }
}
