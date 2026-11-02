package dev.heartline.app

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*
import dev.heartline.data.model.ActivityMinute
import dev.heartline.data.model.HealthPoint
import dev.heartline.data.model.HrSample
import java.time.*

internal data class ExportCheck(val start: Double, val rmssd: Double?, val respiratoryRate: Double?)
internal data class ExportWorkout(val start: Long, val end: Long, val type: String, val distance: Double?, val calories: Double?)
internal data class ExportSleep(val day: LocalDate, val minutes: List<ActivityMinute>)

/** One stable noon-to-noon night ID survives a corrected start or an extended night. Unknown,
 * unworn and missing minutes never become awake stages. Only observed minutes get stages. */
internal fun exportSleepNights(minutes: List<ActivityMinute>, zone: ZoneId): List<ExportSleep> =
    minutes.groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).minusHours(12).toLocalDate() }
        .mapNotNull { (day, rows) ->
            val sleep = rows.filter { it.state.startsWith("sleep_") }
            if (sleep.isEmpty()) null else ExportSleep(day, rows.filter { it.ts in sleep.first().ts..sleep.last().ts })
        }

internal class HealthRecordMapping(private val version: Long, private val zone: ZoneId) {
    private val watch = Device(manufacturer = "Amazfit", model = "GTR 2e", type = Device.TYPE_WATCH)
    private fun time(ts: Long) = Instant.ofEpochSecond(ts)
    private fun offset(ts: Long) = zone.rules.getOffset(time(ts))
    private fun item(type: String, key: String, ts: Long, payload: Any,
        manual: Boolean = false, build: (Metadata) -> Record): ExportItem<Record> {
        val id = "heartline:$type:$key"
        val meta = if (manual) Metadata.activelyRecorded(watch, id, version) else Metadata.autoRecorded(watch, id, version)
        return ExportItem(id, ts, exportFingerprint("$payload|${offset(ts)}"), build(meta))
    }
    fun heartRate(minutes: List<ActivityMinute>, live: List<HrSample>): List<ExportItem<Record>> {
        val points = sortedMapOf<Long, Int>()
        for (m in minutes) m.hr?.takeIf { it in 1..254 }?.let { points[m.ts * 1000] = it }
        for (h in live) if (h.bpm in 1..254 && h.ts.isFinite()) points[(h.ts * 1000).toLong()] = h.bpm
        return points.entries.groupBy { Math.floorDiv(it.key, 900_000) * 900 }.map { (start, rows) ->
            item("hr", "$start", start, rows.map { it.key to it.value }) { meta ->
                val end = Instant.ofEpochMilli(rows.last().key + 1)
                HeartRateRecord(time(start), offset(start), end, zone.rules.getOffset(end),
                    rows.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.key), it.value.toLong()) }, meta)
            }
        }
    }
    fun steps(minutes: List<ActivityMinute>): List<ExportItem<Record>> = minutes
        .groupBy { Math.floorDiv(it.ts, 900) * 900 }.mapNotNull { (start, rows) ->
            val count = rows.sumOf { it.steps.coerceAtLeast(0).toLong() }
            val end = rows.maxOf { it.ts } + 60
            if (count == 0L) null else item("steps", "$start", start, "$count:$end") {
                StepsRecord(time(start), offset(start), time(end), offset(end), count, it)
            }
        }
    fun sleep(minutes: List<ActivityMinute>): List<ExportItem<Record>> = exportSleepNights(minutes, zone).map { night ->
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        for (m in night.minutes) {
            val stage = when (m.state) {
                "sleep_light" -> SleepSessionRecord.STAGE_TYPE_LIGHT
                "sleep_deep" -> SleepSessionRecord.STAGE_TYPE_DEEP
                "sleep_rem" -> SleepSessionRecord.STAGE_TYPE_REM
                "idle", "active" -> SleepSessionRecord.STAGE_TYPE_AWAKE
                else -> continue
            }
            val prev = stages.lastOrNull()
            if (prev != null && prev.stage == stage && prev.endTime == time(m.ts)) {
                stages[stages.lastIndex] = SleepSessionRecord.Stage(prev.startTime, time(m.ts + 60), stage)
            } else stages.add(SleepSessionRecord.Stage(time(m.ts), time(m.ts + 60), stage))
        }
        val start = night.minutes.first().ts; val end = night.minutes.last().ts + 60
        item("sleep", night.day.toString(), start, night.minutes.map { it.ts to it.state }) {
            SleepSessionRecord(time(start), offset(start), time(end), offset(end), it, title = "Watch sleep", stages = stages)
        }
    }
    fun oxygen(points: List<HealthPoint>): List<ExportItem<Record>> = points.filter { it.type in setOf("spo2", "spo2_sleep") }
        .filter { p -> p.value?.let { it.isFinite() && it > 0 && it <= 100 } == true }
        .groupBy { it.ts }.map { (ts, values) ->
            val value = values.firstOrNull { it.type == "spo2" }?.value ?: values.first().value!!
            item("oxygen", "$ts", ts, value) { OxygenSaturationRecord(time(ts), offset(ts), Percentage(value), it) }
        }
    fun resting(points: List<HealthPoint>): List<ExportItem<Record>> = points
        .filter { p -> p.type == "resting_hr" && p.value?.let { it in 1.0..254.0 } == true }
        .groupBy { time(it.ts).atZone(zone).toLocalDate() }.map { (day, rows) ->
            val best = rows.minBy { it.value!! }; val ts = best.ts; val bpm = best.value!!.toLong()
            item("resting", day.toString(), ts, "$ts:$bpm") { RestingHeartRateRecord(time(ts), offset(ts), bpm, it) }
        }
    fun checks(checks: List<ExportCheck>, respiratory: Boolean): List<ExportItem<Record>> = checks.mapNotNull { c ->
        val value = if (respiratory) c.respiratoryRate else c.rmssd
        // Stable client 1.1.0 validates RMSSD 1..200 ms, respiration 0..1000 breaths/min.
        if (value == null || !value.isFinite() || value <= 0 || (!respiratory && value < 1) || value > (if (respiratory) 1000 else 200)) null
        else item(if (respiratory) "respiratory" else "hrv", c.start.toString(), c.start.toLong(), value, true) {
            val instant = Instant.ofEpochMilli((c.start * 1000).toLong())
            if (respiratory) RespiratoryRateRecord(instant, offset(c.start.toLong()), value, it)
            else HeartRateVariabilityRmssdRecord(instant, offset(c.start.toLong()), value, it)
        }
    }
    fun workouts(rows: List<ExportWorkout>, type: String): List<ExportItem<Record>> = rows.filter { it.end > it.start }.mapNotNull { w ->
        val start = w.start; val end = w.end
        val value = when (type) { "distance" -> w.distance; "calories" -> w.calories; else -> 1.0 }
        if (value == null || !value.isFinite() || value < 0) null else item(type, "$start", start, w, true) { meta ->
            when (type) {
                "distance" -> DistanceRecord(time(start), offset(start), time(end), offset(end), Length.meters(value), meta)
                // The watch reports total workout calories; do not relabel these as active calories.
                "calories" -> TotalCaloriesBurnedRecord(time(start), offset(start), time(end), offset(end), Energy.kilocalories(value), meta)
                else -> ExerciseSessionRecord(time(start), offset(start), time(end), offset(end), meta, exerciseType(w.type), title = w.type)
            }
        }
    }
    fun temperature(points: List<HealthPoint>): List<ExportItem<Record>> = points
        .filter { p -> p.type == "temperature" && p.value?.let { it.isFinite() && it in 0.0..100.0 } == true }
        .groupBy { Math.floorDiv(it.ts, 900) * 900 }.map { (start, rows) ->
            val baseline = rows.first().value!!
            val valid = rows.filter { kotlin.math.abs(it.value!! - baseline) <= 30 }
            // Gadgetbridge FetchTemperatureOperation.handleActivityData identifies these as wrist skin Celsius.
            item("temperature", "$start", start, valid.map { it.ts to it.value }) { meta ->
                val end = valid.maxOf { it.ts } + 1
                SkinTemperatureRecord(time(start), offset(start), time(end), offset(end), meta,
                    valid.map { SkinTemperatureRecord.Delta(time(it.ts), TemperatureDelta.celsius(it.value!! - baseline)) },
                    Temperature.celsius(baseline), SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST)
            }
        }
}

internal fun exerciseType(type: String): Int = with(ExerciseSessionRecord) {
    when (type) {
        "Outdoor running" -> EXERCISE_TYPE_RUNNING; "Treadmill" -> EXERCISE_TYPE_RUNNING_TREADMILL
        "Walking" -> EXERCISE_TYPE_WALKING; "Cycling" -> EXERCISE_TYPE_BIKING
        "Indoor cycling" -> EXERCISE_TYPE_BIKING_STATIONARY; "Swimming" -> EXERCISE_TYPE_SWIMMING_POOL
        "Open water swimming" -> EXERCISE_TYPE_SWIMMING_OPEN_WATER; "Elliptical trainer" -> EXERCISE_TYPE_ELLIPTICAL
        "Climbing" -> EXERCISE_TYPE_ROCK_CLIMBING; "Hiking" -> EXERCISE_TYPE_HIKING
        "Soccer" -> EXERCISE_TYPE_SOCCER; "Jump rope" -> EXERCISE_TYPE_OTHER_WORKOUT
        "Rowing machine" -> EXERCISE_TYPE_ROWING_MACHINE; "Strength training" -> EXERCISE_TYPE_STRENGTH_TRAINING
        "Yoga" -> EXERCISE_TYPE_YOGA; "Cricket" -> EXERCISE_TYPE_CRICKET; "Basketball" -> EXERCISE_TYPE_BASKETBALL
        "Table tennis" -> EXERCISE_TYPE_TABLE_TENNIS; "Badminton" -> EXERCISE_TYPE_BADMINTON
        else -> EXERCISE_TYPE_OTHER_WORKOUT
    }
}
