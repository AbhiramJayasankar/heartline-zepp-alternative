package dev.heartline.app

import dev.heartline.analytics.SessionResult
import dev.heartline.analytics.readRecordingCsv
import dev.heartline.protocol.Workout
import dev.heartline.data.model.Workout as StoredWorkout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.numbers(): List<Double> = (0 until length()).map { getDouble(it) }
internal fun settingValue(key: String, text: String): Any = when (dev.heartline.protocol.SETTINGS.firstOrNull { it.key == key }?.type) {
    "bool" -> text == "true"
    "int" -> text.toIntOrNull() ?: text
    else -> text.toIntOrNull() ?: text
}
internal fun Workout.stored(): StoredWorkout {
    val j = JSONObject().put("start", start).put("end", end).put("type", type).put("duration_s", durationS)
        .put("active_s", activeS).put("avg_hr", avgHr).put("max_hr", maxHr).put("calories", calories)
        .put("steps", steps).put("distance_m", distanceM).put("version", version).put("type_code", typeCode)
        .put("avg_speed_ms", avgSpeedMs).put("avg_cadence", avgCadence)
    return StoredWorkout(start, j.toString(), JSONArray(track.map { JSONObject().put("t", it.t).put("hr", it.hr) }).toString())
}
internal fun StoredWorkout.ui(): WorkoutUi {
    val j = JSONObject(data)
    val points = track?.let { JSONArray(it).objects().map { p -> HrPoint(start + p.getLong("t"), p.getInt("hr")) } }.orEmpty()
    return WorkoutUi(start, j.optInt("duration_s"), j.optString("type", "Workout"), j.number("avg_hr")?.toInt(), j.number("max_hr")?.toInt(),
        j.number("calories")?.toInt(), j.number("steps")?.toInt(), j.number("distance_m")?.toInt(), points)
}
internal fun SessionResult.json(): String = JSONObject().apply {
    put("file", file); put("start", start); put("duration_s", durationS); put("signal_quality", signalQuality); put("usable_s", usableS)
    put("hr_bpm", hrBpm); put("beats", beats); put("respiratory_rate", respiratoryRate); put("preview_fs", previewFs)
    put("hrv", JSONObject().put("mean_rr_ms", hrv.meanRrMs).put("sdnn_ms", hrv.sdnnMs).put("rmssd_ms", hrv.rmssdMs)
        .put("pnn50", hrv.pnn50).put("sd1_ms", hrv.sd1Ms).put("sd2_ms", hrv.sd2Ms).put("lf", hrv.lf).put("hf", hrv.hf).put("lf_hf", hrv.lfHf).put("stress_index", hrv.stressIndex))
    put("rhythm", JSONObject().put("status", rhythm.status).put("ectopic_beats", rhythm.ectopicBeats).put("irregularity", rhythm.irregularity)
        .put("notes", JSONArray(rhythm.notes)).put("features", rhythm.features?.let { JSONObject().put("nrmssd", it.nrmssd).put("shannon_entropy", it.shannonEntropy).put("tpr", it.tpr).put("random_like", it.randomLike).put("intervals", it.intervals) }))
    put("respiration", JSONObject().put("riiv", respiration.riiv).put("riav", respiration.riav).put("rifv", respiration.rifv))
    put("rr", JSONArray(rr.map { JSONObject().put("t", it.t).put("rr_ms", it.rrMs).put("ectopic", it.ectopic) }))
    put("ppg_preview", JSONArray(ppgPreview)); put("beat_times", JSONArray(beatTimes)); put("beat_good", JSONArray(beatGood))
    put("motion", JSONArray(motion.map { JSONObject().put("t", it.t).put("g", it.g) }))
}.toString()

internal fun sessionFromJson(text: String, id: String): SessionUi {
    val j = JSONObject(text); val h = j.getJSONObject("hrv"); val r = j.getJSONObject("rhythm")
    return SessionUi(id, j.getDouble("start").toLong(), j.getDouble("duration_s"), j.getDouble("signal_quality"), j.number("hr_bpm"),
        h.number("rmssd_ms"), h.number("sdnn_ms"), h.number("pnn50"), h.number("stress_index"), h.number("lf_hf"), j.number("respiratory_rate"),
        rhythmStatus(r.optString("status")), r.optInt("ectopic_beats"), r.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        j.optJSONArray("rr")?.objects()?.map { RrUi(it.getDouble("t"), it.getDouble("rr_ms"), it.optBoolean("ectopic")) }.orEmpty(),
        j.optJSONArray("ppg_preview")?.numbers()?.map { it.toFloat() }?.toFloatArray() ?: FloatArray(0), j.number("preview_fs") ?: 25.0,
        j.optJSONArray("beat_times")?.numbers()?.toDoubleArray() ?: DoubleArray(0))
}

internal fun readRecording(file: File) = file.bufferedReader().use(::readRecordingCsv)
