package dev.heartline.service

import dev.heartline.analytics.CaptureActivity
import dev.heartline.data.model.Workout
import org.json.JSONObject
import kotlin.math.*

/** Retain the watch's original totals beside raw estimates; clipping changes time bounds only. */
internal fun captureActivity(row: Workout, start: Double, end: Double): CaptureActivity? = runCatching {
    val o = JSONObject(row.data)
    val a = o.optDouble("start", row.start.toDouble())
    val b = o.optDouble("end", Double.NaN)
    if (!a.isFinite() || !b.isFinite() || b <= start || a >= end || b <= a) return null
    fun number(key: String): Int? = if (o.isNull(key)) null else o.getInt(key)
    CaptureActivity(max(a, start) - start, min(b, end) - start, o.getString("type"), number("active_s"),
        number("distance_m"), number("steps"), number("calories"), number("avg_hr"), number("max_hr"))
}.getOrNull()
