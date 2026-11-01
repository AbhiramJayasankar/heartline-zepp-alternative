package dev.heartline.data

import dev.heartline.data.model.Recording
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val db: HeartlineDatabase) {
    suspend fun upsert(recording: Recording): Boolean = db.write(setOf("recordings")) {
        it.upsert("recordings", values("file" to recording.file, "start" to recording.start,
            "duration_s" to recording.durationS, "sensors" to recording.sensors,
            "has_audio" to recording.hasAudio, "analysis" to recording.analysis))
    }
    suspend fun list(): List<Recording> = db.read {
        it.select("SELECT file,start,duration_s,sensors,has_audio,analysis FROM recordings ORDER BY start DESC,file DESC") { c ->
            Recording(c.getString(0), c.doubleOrNull(1), c.doubleOrNull(2), c.getString(3), c.getInt(4) != 0, c.getString(5))
        }
    }
    suspend fun delete(file: String) = db.write(setOf("recordings")) {
        it.delete("recordings", "file = ?", arrayOf(file)); Unit
    }
    suspend fun setAnalysis(file: String, analysis: String?) = db.write(setOf("recordings")) {
        it.update("recordings", values("analysis" to analysis), "file = ?", arrayOf(file)); Unit
    }
    fun observeAll(): Flow<List<Recording>> = db.observe("recordings") { list() }
}
