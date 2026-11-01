package dev.heartline.ble

import dev.heartline.protocol.*
import java.io.File

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }
data class ConnectionStatus(val state: ConnectionState = ConnectionState.DISCONNECTED, val since: Long? = null, val error: String? = null)
data class HrStatus(val streaming: Boolean = false, val bpm: Int? = null, val ts: Long? = null)
data class SyncStatus(val running: Boolean = false, val message: String? = null)
data class RawStatus(val streaming: Boolean = false, val sensors: Set<RawSensor> = setOf(RawSensor.PPG, RawSensor.ACCEL), val file: File? = null)
data class RawUpdate(val packet: RawPacket? = null, val levels: List<Float> = emptyList())

/** The app maps protocol models to the data module's independent storage models. */
interface WatchPersistence {
    suspend fun event(level: String, message: String)
    suspend fun heartRate(ts: Double, bpm: Int)
    suspend fun lastActivity(): Long?
    suspend fun activity(rows: List<ActivityMinute>): Int
    suspend fun lastHealth(type: String): Long?
    suspend fun health(rows: List<HealthPoint>): Int
    suspend fun lastWorkout(): Long?
    suspend fun workout(row: Workout)
    suspend fun rawFetch(type: String, reply: ByteArray, start: Long?, data: ByteArray)
    suspend fun meta(key: String, value: String)
    suspend fun meta(key: String): String?
    suspend fun recording(file: File, start: Double, duration: Double, sensors: Set<RawSensor>, hasAudio: Boolean)
    fun settingValues(): Map<String, Any>
    fun settingWritten(key: String, value: Any)
}

/** watch.py sync overlap and monotonic-progress rules, shared by all fetch loops. */
object SyncPolicy {
    fun activitySince(last: Long?, now: Long): Long = last?.minus(240) ?: (now - 7 * 86400)
    fun healthSince(last: Long?, now: Long): Long = last?.plus(60) ?: (now - 7 * 86400)
    fun next(since: Long, last: Long, step: Long = 60): Long? = (last + step).takeIf { it > since }
    fun reconnectDelay(attempt: Int): Long = minOf(60_000L, 3_000L * (1L shl attempt.coerceIn(0, 5)))
}
