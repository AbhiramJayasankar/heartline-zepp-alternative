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
    put("sets", JSONArray(sets.map { it.json() }))
    put("unlogged", JSONArray(unlogged.map { it.json() }))
    put("logMatched", logMatched ?: JSONObject.NULL); put("logTotal", logTotal ?: JSONObject.NULL)
    put("hrSeconds", JSONArray(hrSeconds.entries.sortedBy { it.key }.map { JSONArray(listOf(it.key, it.value)) }))
    put("loadMaxHr", loadMaxHr ?: JSONObject.NULL); put("loadSex", loadSex ?: JSONObject.NULL)
    put("rests", JSONArray(rests.map { JSONArray(listOf(it.start, it.end, it.startHr ?: JSONObject.NULL, it.endHr ?: JSONObject.NULL, it.drop60 ?: JSONObject.NULL)) }))
    put("activities", JSONArray(activities.map { JSONArray(listOf(it.start, it.end, it.type, it.activeS ?: JSONObject.NULL,
        it.distanceM ?: JSONObject.NULL, it.steps ?: JSONObject.NULL, it.calories ?: JSONObject.NULL, it.avgHr ?: JSONObject.NULL, it.maxHr ?: JSONObject.NULL)) }))
    put("recovery", JSONArray(recovery.map { JSONArray(listOf(it.t, it.delta, it.n)) }))
    put("floor", JSONArray(floor.map { JSONArray(listOf(it.t, it.v)) }))
    put("workS", workS ?: JSONObject.NULL); put("restS", restS ?: JSONObject.NULL)
    put("cardio", JSONArray(cardio.map { JSONArray(listOf(it.start, it.end, it.kind, it.name ?: JSONObject.NULL,
        it.avgHr ?: JSONObject.NULL, it.steadyHr ?: JSONObject.NULL, it.peakHr ?: JSONObject.NULL, it.riseTauS ?: JSONObject.NULL,
        it.driftPerMin ?: JSONObject.NULL, it.drop60 ?: JSONObject.NULL, it.drop120 ?: JSONObject.NULL, it.recoveryTauS ?: JSONObject.NULL,
        it.cadenceSpm ?: JSONObject.NULL, it.distanceM ?: JSONObject.NULL, it.speedKmh ?: JSONObject.NULL)) }))
    put("pulse", pulse?.let { p -> JSONObject().apply {
        put("status", p.status); put("usableS", p.usableS); put("nrmssd", p.nrmssd ?: JSONObject.NULL)
        put("entropy", p.entropy ?: JSONObject.NULL); put("ectopic", p.ectopic)
        put("windows", JSONArray(p.windows.map { JSONArray(listOf(it.start, it.end, it.beats, it.hrPpg, it.hrWatch ?: JSONObject.NULL,
            it.rmssdMs, it.nrmssd, it.status)) }))
        put("pairs", JSONArray(p.pairs.map { JSONArray(listOf(it.first, it.second)) }))
        put("excerpt", JSONArray(p.excerpt)); put("excerptFs", p.excerptFs); put("excerptStart", p.excerptStart)
        put("excerptBeats", JSONArray(p.excerptBeats)); put("notes", JSONArray(p.notes))
    } } ?: JSONObject.NULL)
}.toString()

internal fun captureFromJson(json: String): CaptureAnalysisUi = JSONObject(json).let { o ->
    fun int(key: String): Int? = if (o.isNull(key)) null else o.getInt(key)
    fun double(key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)
    fun <T> rows(key: String, f: (JSONArray) -> T): List<T> = o.optJSONArray(key)?.rows(f) ?: emptyList()
    fun JSONArray.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
    fun JSONArray.double(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    CaptureAnalysisUi(o.getString("id"), o.getString("label"), o.getLong("start"), o.getInt("duration"), int("avg"), int("max"), int("min"),
        rows("zones") { ZoneTimeUi(it.getInt(0), zoneName(it.getInt(0)), it.getInt(1), it.int(2), it.getInt(3)) }, double("load"), int("calories"), int("steps"),
        rows("hr") { SeriesPoint(it.getDouble(0), it.getDouble(1)) }, rows("motion") { SeriesPoint(it.getDouble(0), it.getDouble(1)) },
        rows("segments") { SegmentUi(it.getDouble(0), it.getDouble(1), it.getString(2), it.int(3), it.int(4)) },
        rows("sets") { setFromJson(it) },
        rows("rests") { RestUi(it.getDouble(0), it.getDouble(1), it.int(2), it.int(3), it.int(4)) }, o.getString("source"),
        o.getJSONArray("notes").strings(),
        rows("activities") { CaptureActivityUi(it.getDouble(0), it.getDouble(1), it.getString(2), it.int(3), it.int(4), it.int(5), it.int(6), it.int(7), it.int(8)) },
        rows("recovery") { RecoveryPointUi(it.getInt(0), it.getDouble(1), it.getInt(2)) },
        rows("floor") { SeriesPoint(it.getDouble(0), it.getDouble(1)) }, int("workS"), int("restS"),
        rows("cardio") { CardioUi(it.getDouble(0), it.getDouble(1), it.getString(2), if (it.isNull(3)) null else it.getString(3),
            it.int(4), it.int(5), it.int(6), it.int(7), it.double(8), it.int(9), it.int(10), it.int(11), it.int(12), it.int(13), it.double(14)) },
        o.optJSONObject("pulse")?.let { p -> PulseUi(p.getString("status"), p.getInt("usableS"),
            p.getJSONArray("windows").rows { PulseWindowUi(it.getDouble(0), it.getDouble(1), it.getInt(2), it.getInt(3), it.int(4), it.getDouble(5), it.getDouble(6), it.getString(7)) },
            if (p.isNull("nrmssd")) null else p.getDouble("nrmssd"), if (p.isNull("entropy")) null else p.getDouble("entropy"), p.getInt("ectopic"),
            p.getJSONArray("pairs").rows { it.getDouble(0) to it.getDouble(1) }, p.getJSONArray("excerpt").doubles(),
            p.getDouble("excerptFs"), p.getDouble("excerptStart"), p.getJSONArray("excerptBeats").doubles(), p.getJSONArray("notes").strings()) },
        int("logMatched"), int("logTotal"), rows("unlogged") { setFromJson(it) },
        rows("hrSeconds") { it.getInt(0) to it.getInt(1) }.toMap(), if (o.has("loadMaxHr")) int("loadMaxHr") else null,
        if (o.has("loadSex") && !o.isNull("loadSex")) o.getString("loadSex") else null)
}

private fun SetUi.json() = JSONArray(listOf(start, end, reps, avgHr ?: JSONObject.NULL, peakHr ?: JSONObject.NULL, tempoS ?: JSONObject.NULL,
    JSONArray(repDetail.map { r -> JSONArray(listOf(r.t, r.durationS, r.slowS, r.fastS, r.arcDeg)) }),
    group, activity ?: JSONObject.NULL, slowdownS ?: JSONObject.NULL, partialLast, startHr ?: JSONObject.NULL, hrRise ?: JSONObject.NULL,
    exercise ?: JSONObject.NULL, setLabel ?: JSONObject.NULL, loadKg ?: JSONObject.NULL, loggedReps ?: JSONObject.NULL, warmUp, source))

private fun setFromJson(it: JSONArray): SetUi {
    fun int(i: Int): Int? = if (it.length() <= i || it.isNull(i)) null else it.getInt(i)
    fun double(i: Int): Double? = if (it.length() <= i || it.isNull(i)) null else it.getDouble(i)
    fun str(i: Int): String? = if (it.length() <= i || it.isNull(i)) null else it.getString(i)
    return SetUi(it.getDouble(0), it.getDouble(1), it.getInt(2), int(3), int(4), double(5),
        it.optJSONArray(6)?.rows { r -> RepUi(r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4)) } ?: emptyList(),
        it.optInt(7, 0), str(8), double(9), it.optBoolean(10, false), int(11), int(12),
        str(13), str(14), double(15), double(16), it.optBoolean(17, false), str(18) ?: "watch")
}

private fun <T> JSONArray.rows(f: (JSONArray) -> T) = (0 until length()).map { f(getJSONArray(it)) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONArray.doubles() = (0 until length()).map { getDouble(it) }
