package dev.heartline.service

import dev.heartline.analytics.Recording
import dev.heartline.analytics.readRecordingCsv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal const val CAPTURE_SESSION = "capture_session_"
internal const val CAPTURE_RESULT = "capture_result_"
/** Stored analyses from an older engine are recomputed when opened. Bump when analysis changes. */
internal const val CAPTURE_ENGINE = "capture_engine_"
internal const val CAPTURE_ENGINE_VERSION = "4"

internal fun CaptureSession.json(): String = JSONObject().apply {
    put("id", id); put("label", label); put("start", start); put("end", end ?: JSONObject.NULL); put("planned", plannedS)
    put("files", JSONArray(files)); put("samples", samples); put("restoreHr", restoreHr); put("error", error ?: JSONObject.NULL)
    put("gaps", JSONArray(gaps.map { JSONObject().put("start", it.start).put("end", it.end ?: JSONObject.NULL) }))
}.toString()

internal fun captureSession(json: String): CaptureSession = JSONObject(json).let { o ->
    val files = o.getJSONArray("files"); val gaps = o.getJSONArray("gaps")
    CaptureSession(o.getString("id"), o.getString("label"), o.getLong("start"), o.getInt("planned"),
        if (o.isNull("end")) null else o.getLong("end"), (0 until files.length()).map { files.getString(it) },
        (0 until gaps.length()).map { i -> gaps.getJSONObject(i).let { CaptureGap(it.getLong("start"), if (it.isNull("end")) null else it.getLong("end")) } },
        o.optLong("samples"), o.optBoolean("restoreHr"), if (o.isNull("error")) null else o.getString("error"))
}

internal fun captureFile(folder: File, name: String): File {
    require(name == File(name).name && name.endsWith(".csv")) { "Invalid capture recording name" }
    val file = File(folder, name).canonicalFile
    require(file.parentFile == folder.canonicalFile) { "Invalid capture recording path" }
    return file
}

/** Reconnect parts retain their epoch offsets; no concatenation can close a gap in the time axis. */
internal fun mergeCapture(session: CaptureSession, parts: List<Recording>): Recording {
    fun times(values: (Recording) -> DoubleArray) = parts.flatMap { r -> values(r).map { it + r.start - session.start } }.toDoubleArray()
    return Recording(session.start.toDouble(), times { it.ppgT }, parts.flatMap { it.ppg.toList() }.toDoubleArray(),
        times { it.accT }, parts.flatMap { it.acc.toList() }.toTypedArray(),
        times { it.gyroT }, parts.flatMap { it.gyro.toList() }.toTypedArray(),
        times { it.magT }, parts.flatMap { it.mag.toList() }.toTypedArray(), times { it.accArrivalT },
        session.elapsed(session.end ?: session.start).toDouble(), times { it.ppgArrivalT }, parts.any { it.watchClock })
}

internal fun readCaptureFiles(folder: File, session: CaptureSession) = session.files.mapNotNull { name ->
    captureFile(folder, name).takeIf { it.isFile }?.bufferedReader()?.use(::readRecordingCsv)
}
