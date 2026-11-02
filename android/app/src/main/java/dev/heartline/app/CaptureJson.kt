package dev.heartline.app

import org.json.JSONArray
import org.json.JSONObject

internal fun CaptureAnalysisUi.captureJson(): String = JSONObject().apply {
    put("id", id); put("label", label); put("start", start); put("duration", durationS)
    put("avg", avgHr ?: JSONObject.NULL); put("max", maxHr ?: JSONObject.NULL); put("min", minHr ?: JSONObject.NULL)
    put("load", load ?: JSONObject.NULL); put("calories", calories ?: JSONObject.NULL); put("steps", steps ?: JSONObject.NULL)
    put("source", hrSource); put("notes", JSONArray(notes))
    put("zones", JSONArray(zones.map { JSONArray(listOf(it.zone, it.lowBpm, it.highBpm ?: JSONObject.NULL, it.seconds)) }))
    put("hr", JSONArray(hr.map { JSONArray(listOf(it.t, it.v)) })); put("motion", JSONArray(motion.map { JSONArray(listOf(it.t, it.v)) }))
    put("segments", JSONArray(segments.map { JSONArray(listOf(it.start, it.end, it.kind, it.avgHr ?: JSONObject.NULL, it.cadenceSpm ?: JSONObject.NULL)) }))
    put("sets", JSONArray(sets.map { JSONArray(listOf(it.start, it.end, it.reps, it.avgHr ?: JSONObject.NULL, it.peakHr ?: JSONObject.NULL, it.tempoS ?: JSONObject.NULL)) }))
    put("rests", JSONArray(rests.map { JSONArray(listOf(it.start, it.end, it.startHr ?: JSONObject.NULL, it.endHr ?: JSONObject.NULL, it.drop60 ?: JSONObject.NULL)) }))
}.toString()

internal fun captureFromJson(json: String): CaptureAnalysisUi = JSONObject(json).let { o ->
    fun int(key: String): Int? = if (o.isNull(key)) null else o.getInt(key)
    fun double(key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)
    fun <T> rows(key: String, f: (JSONArray) -> T): List<T> = o.getJSONArray(key).let { a -> (0 until a.length()).map { f(a.getJSONArray(it)) } }
    fun JSONArray.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
    fun JSONArray.double(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    CaptureAnalysisUi(o.getString("id"), o.getString("label"), o.getLong("start"), o.getInt("duration"), int("avg"), int("max"), int("min"),
        rows("zones") { ZoneTimeUi(it.getInt(0), zoneName(it.getInt(0)), it.getInt(1), it.int(2), it.getInt(3)) }, double("load"), int("calories"), int("steps"),
        rows("hr") { SeriesPoint(it.getDouble(0), it.getDouble(1)) }, rows("motion") { SeriesPoint(it.getDouble(0), it.getDouble(1)) },
        rows("segments") { SegmentUi(it.getDouble(0), it.getDouble(1), it.getString(2), it.int(3), it.int(4)) },
        rows("sets") { SetUi(it.getDouble(0), it.getDouble(1), it.getInt(2), it.int(3), it.int(4), it.double(5)) },
        rows("rests") { RestUi(it.getDouble(0), it.getDouble(1), it.int(2), it.int(3), it.int(4)) }, o.getString("source"),
        o.getJSONArray("notes").let { a -> (0 until a.length()).map { a.getString(it) } })
}
