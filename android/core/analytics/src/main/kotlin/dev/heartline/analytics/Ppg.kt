package dev.heartline.analytics

import java.io.Reader
import kotlin.math.*

/** ppg.retime: integrate a local clock from the running maximum of burst arrival stamps. */
fun retime(t: DoubleArray, window: Int = 125): DoubleArray {
    if (t.size < 4) return DoubleArray(t.size) { t[it] - t[0] }
    require(window >= 2)
    val m = t.copyOf()
    for (i in 1 until m.size) m[i] = max(m[i], m[i - 1])
    val out = DoubleArray(t.size)
    val half = window / 2
    for (i in 1 until t.size) {
        val a = max(0, i - half)
        val b = min(t.lastIndex, i + half)
        val span = m[b] - m[a]
        val rate = (if (span > 0) (b - a) / span else 25.0).coerceIn(10.0, 80.0)
        out[i] = out[i - 1] + 1 / rate
    }
    val total = m.last() - m[0]
    if (out.last() > 0 && total > 0) {
        val scale = total / out.last()
        for (i in out.indices) out[i] *= scale
    }
    return out
}

/** Arrival timestamps are Unix seconds, accelerometer samples are already in g (CSV units). */
fun makeRecording(
    ppgT: DoubleArray, ppg: DoubleArray, accT: DoubleArray = doubleArrayOf(),
    acc: Array<FloatArray> = emptyArray(),
): Recording {
    require(ppgT.size == ppg.size && accT.size == acc.size && acc.all { it.size == 3 })
    val start = ppgT.firstOrNull() ?: 0.0
    if (ppg.size < 2) return Recording(start, DoubleArray(ppg.size), ppg.copyOf(), doubleArrayOf(), emptyArray())
    val at = if (accT.size > 3) retime(accT).also { t ->
        val offset = accT[0] - start
        for (i in t.indices) t[i] += offset
    } else DoubleArray(accT.size) { accT[it] - start }
    return Recording(start, retime(ppgT), ppg.copyOf(), at, Array(acc.size) { acc[it].copyOf() })
}

fun readRecordingCsv(reader: Reader): Recording {
    val lines = reader.buffered()
    val header = lines.readLine()?.removePrefix("\uFEFF")?.let(::csvFields) ?: emptyList()
    if (header.isEmpty()) return makeRecording(doubleArrayOf(), doubleArrayOf())
    val columns = header.withIndex().associate { it.value to it.index }
    val pt = ArrayList<Double>()
    val p = ArrayList<Double>()
    val at = ArrayList<Double>()
    val a = ArrayList<FloatArray>()
    lines.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val row = csvFields(line)
        fun field(name: String) = row.getOrElse(columns.getValue(name)) { "" }
        when (field("kind")) {
            "ppg" -> if (field("ppg").isNotEmpty()) { pt.add(field("ts").toDouble()); p.add(field("ppg").toDouble()) }
            "accel" -> if (field("ax").isNotEmpty()) {
                at.add(field("ts").toDouble())
                a.add(floatArrayOf(field("ax").toFloat(), field("ay").toFloat(), field("az").toFloat()))
            }
        }
    }
    return makeRecording(pt.toDoubleArray(), p.toDoubleArray(), at.toDoubleArray(), a.toTypedArray())
}

private fun csvFields(line: String): List<String> {
    val fields = ArrayList<String>(6)
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i++]
        when {
            c == '"' && quoted && i < line.length && line[i] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { fields.add(field.toString()); field.setLength(0) }
            else -> field.append(c)
        }
    }
    fields.add(field.toString())
    return fields
}

internal data class Motion(val t: DoubleArray, val g: DoubleArray)
internal fun motionSeries(rec: Recording): Motion {
    if (rec.acc.size < 10) return Motion(doubleArrayOf(), doubleArrayOf())
    val mag = DoubleArray(rec.acc.size) { i ->
        val a = rec.acc[i]
        sqrt(a[0].toDouble().pow(2) + a[1].toDouble().pow(2) + a[2].toDouble().pow(2))
    }
    val t = grid(0.0, max(rec.accT.last(), 0.0) + 0.5, 0.5)
    var left = 0
    var right = 0
    val g = DoubleArray(t.size) { i ->
        while (left < rec.accT.size && rec.accT[left] < t[i] - 1) left++
        while (right < rec.accT.size && rec.accT[right] < t[i] + 1) right++
        if (right - left < 5) 0.0 else {
            var sum = 0.0
            for (j in left until right) sum += mag[j]
            val m = sum / (right - left)
            var ss = 0.0
            for (j in left until right) ss += (mag[j] - m).pow(2)
            sqrt(ss / (right - left))
        }
    }
    return Motion(t, g)
}

internal data class Beats(
    val times: DoubleArray = doubleArrayOf(), val good: BooleanArray = booleanArrayOf(),
    val amplitude: DoubleArray = doubleArrayOf(), val baseline: DoubleArray = doubleArrayOf(),
    val filtered: DoubleArray = doubleArrayOf(), val notes: List<String> = emptyList(),
)

/** scipy.find_peaks order: local maxima (plateau midpoint), distance by height, then prominence. */
internal fun findPeaks(y: DoubleArray, distance: Int, prominence: Double): IntArray {
    val candidates = ArrayList<Int>()
    var i = 1
    while (i < y.lastIndex) {
        if (y[i] > y[i - 1]) {
            var end = i
            while (end < y.lastIndex && y[end + 1] == y[i]) end++
            if (end < y.lastIndex && y[end] > y[end + 1]) candidates.add((i + end) / 2)
            i = end + 1
        } else i++
    }
    val keep = BooleanArray(candidates.size) { true }
    // Resolve exact height ties right-to-left. NumPy's default quicksort tie order is
    // unspecified (visible on constant nonzero inputs containing only filter roundoff).
    val priority = candidates.indices.sortedWith(compareBy<Int> { y[candidates[it]] }.thenBy { it })
    for (k in priority.asReversed()) {
        if (!keep[k]) continue
        var j = k - 1
        while (j >= 0 && candidates[k] - candidates[j] < distance) { keep[j] = false; j-- }
        j = k + 1
        while (j < candidates.size && candidates[j] - candidates[k] < distance) { keep[j] = false; j++ }
    }
    val out = ArrayList<Int>()
    for (k in candidates.indices) {
        if (!keep[k]) continue
        val p = candidates[k]
        var lmin = y[p]
        var rmin = y[p]
        var j = p
        while (j >= 0 && y[j] <= y[p]) { lmin = min(lmin, y[j]); j-- }
        j = p
        while (j < y.size && y[j] <= y[p]) { rmin = min(rmin, y[j]); j++ }
        if (y[p] - max(lmin, rmin) >= prominence) out.add(p)
    }
    return out.toIntArray()
}

internal fun detectBeats(rec: Recording, motion: Motion): Beats {
    if (rec.durationS < 8) return Beats(notes = listOf("Recording too short to find heartbeats (needs at least 8 s)."))
    val t = grid(0.0, rec.ppgT.last(), 0.01)
    val x = interpolate(rec.ppgT, rec.ppg, t)
    val baseline = zeroPhase(x, butterworth(100.0, 0.5))
    val y = zeroPhase(x, butterworth(100.0, 0.5, 4.0))
    val d = DoubleArray(y.size - 1) { y[it + 1] - y[it] }
    val dm = mean(d)
    var skew = 0.0
    for (v in d) skew += (v - dm).pow(3)
    skew /= d.size * (variance(d).pow(1.5) + 1e-12)
    if (skew < 0) for (i in y.indices) y[i] = -y[i]
    val spread = percentile(y, 95.0) - percentile(y, 5.0)
    val peaks = findPeaks(y, 33, 0.25 * spread)
    if (peaks.size < 4) return Beats(filtered = y,
        notes = listOf("Too few clear heartbeats; the watch may be loose or off the wrist."))
    val times = DoubleArray(peaks.size) { i ->
        val p = peaks[i]
        val den = y[p - 1] - 2 * y[p] + y[p + 1]
        (p + if (den != 0.0) 0.5 * (y[p - 1] - y[p + 1]) / den else 0.0) / 100
    }
    val amplitude = DoubleArray(peaks.size) { i ->
        val p = peaks[i]
        val lo = if (i > 0) peaks[i - 1] else max(0, p - 60)
        var low = y[lo]
        for (j in lo..p) low = min(low, y[j])
        y[p] - low
    }
    val valid = BooleanArray(peaks.size) { peaks[it] - 30 >= 0 && peaks[it] + 45 < y.size }
    val windows = Array(peaks.size) { i -> if (valid[i]) y.copyOfRange(peaks[i] - 30, peaks[i] + 45) else null }
    val stack = windows.filterNotNull()
    val template = if (stack.size >= 3) DoubleArray(75) { j -> median(DoubleArray(stack.size) { stack[it][j] }) } else null
    val medAmp = median(amplitude)
    val good = BooleanArray(peaks.size) { i ->
        valid[i] && template != null && correlation(windows[i]!!, template) >= 0.80 &&
            amplitude[i] > 0.3 * medAmp && amplitude[i] < 3.0 * medAmp
    }
    val notes = ArrayList<String>()
    if (motion.t.isNotEmpty()) {
        val mv = interpolate(motion.t, motion.g, times)
        var dropped = 0
        for (i in good.indices) if (mv[i] > 0.05) { good[i] = false; dropped++ }
        if (dropped > 0) notes.add("$dropped beats dropped because the wrist was moving.")
    }
    return Beats(times, good, amplitude, interpolate(t, baseline, times), y, notes)
}
