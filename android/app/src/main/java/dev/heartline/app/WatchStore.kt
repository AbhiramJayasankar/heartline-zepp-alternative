package dev.heartline.app

import dev.heartline.ble.WatchPersistence
import dev.heartline.data.AppSettings
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.model.ActivityRow
import dev.heartline.data.model.Recording
import dev.heartline.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WatchStore(private val db: HeartlineDatabase, private val settings: AppSettings) : WatchPersistence {
    override suspend fun event(level: String, message: String) = db.events.add(level, message)
    override suspend fun heartRate(ts: Double, bpm: Int) = db.heartRate.add(ts, bpm)
    override suspend fun lastActivity() = db.activity.lastTs()
    override suspend fun activity(rows: List<ActivityMinute>) = db.activity.upsert(rows.map {
        ActivityRow(it.ts, it.kind, it.intensity, it.steps, it.hr, it.unknown1, it.sleep, it.deep, it.rem)
    })
    override suspend fun lastHealth(type: String) = db.health.lastTs(type)
    override suspend fun health(rows: List<HealthPoint>) = db.health.upsert(rows.map {
        dev.heartline.data.model.HealthPoint(it.type, it.ts, it.value, JSONObject(it.extra).toString())
    })
    override suspend fun lastWorkout() = db.health.workoutLastStart()
    override suspend fun workout(row: Workout) { db.health.upsertWorkout(row.stored()) }
    override suspend fun rawFetch(type: String, reply: ByteArray, start: Long?, data: ByteArray) =
        db.health.addRawFetch(System.currentTimeMillis() / 1000.0, type, start?.toDouble(), reply, data)
    override suspend fun meta(key: String, value: String) = db.meta.set(key, value)
    override suspend fun meta(key: String) = db.meta.get(key)
    override suspend fun recording(file: File, start: Double, duration: Double, sensors: Set<RawSensor>, hasAudio: Boolean) {
        db.recordings.upsert(Recording(file.name, start, duration, JSONArray(sensors.map { it.name.lowercase() }).toString(), hasAudio))
    }
    override fun settingValues() = settings.lastWrittenWatchSettings.value.mapValues { (k, v) -> settingValue(k, v) }
    override fun settingWritten(key: String, value: Any) = settings.setLastWrittenWatchSetting(key, value.toString())
}
