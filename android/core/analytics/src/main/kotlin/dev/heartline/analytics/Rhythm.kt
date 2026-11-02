package dev.heartline.analytics

import kotlin.math.*

private const val DISCLAIMER = "Wellness estimate from the watch's optical sensor, not a medical diagnosis. " +
    "If you have symptoms (palpitations, dizziness, chest pain, breathlessness) see a doctor."

internal fun shannonEntropy(rr: DoubleArray, bins: Int = 16): Double {
    val sorted = rr.sortedArray()
    val x = if (rr.size > 48) sorted.copyOfRange(8, rr.size - 8) else sorted
    var low = x.first()
    var high = x.last()
    if (high == low) { low -= 0.5; high += 0.5 }
    val counts = IntArray(bins)
    val edges = DoubleArray(bins + 1) { low + it * ((high - low) / bins) }
    edges[bins] = high
    for (v in x) {
        var k = min(bins - 1, ((v - low) * (bins / (high - low))).toInt())
        // numpy.histogram corrects rounding at the linearly spaced bin edges.
        if (v < edges[k]) k--
        if (k < bins - 1 && v >= edges[k + 1]) k++
        counts[k]++
    }
    var h = 0.0
    for (n in counts) if (n > 0) { val p = n.toDouble() / x.size; h -= p * ln(p) }
    return h / ln(bins.toDouble())
}

internal fun turningPointRatio(rr: DoubleArray): Pair<Double, Boolean> {
    var tp = 0
    for (i in 1 until rr.lastIndex) if ((rr[i] > rr[i - 1] && rr[i] > rr[i + 1]) ||
        (rr[i] < rr[i - 1] && rr[i] < rr[i + 1])) tp++
    val mu = 2.0 * (rr.size - 2) / 3
    val sigma = sqrt((16.0 * rr.size - 29) / 90)
    return (if (mu != 0.0) tp / mu else 0.0) to (abs(tp - mu) <= 1.96 * sigma)
}

internal fun assessRhythm(iv: Intervals, hr: Double?, beatNotes: List<String> = emptyList(),
    ectopicBeats: Int? = null): RhythmResult {
    val rr = iv.rr.filterIndexed { i, _ -> !iv.artifact[i] }.toDoubleArray()
    // Capture pooling removes ectopic intervals from timing statistics, but retains the
    // independently counted candidates from the original windows for status and notes.
    val ect = ectopicBeats ?: (iv.ectopic.count { it } / 2)
    if (rr.size < 30) return RhythmResult("insufficient_data", ect, null, beatNotes + listOf(
        "Only ${rr.size} clean beat intervals; at least 30 are needed. " +
            "Sit still with the watch snug for a minute and measure again.", DISCLAIMER), null)
    var ss = 0.0
    for (i in 1 until rr.size) ss += (rr[i] - rr[i - 1]).pow(2)
    val nr = sqrt(ss / (rr.size - 1)) / mean(rr)
    val she = shannonEntropy(rr)
    val (tpr, randomLike) = turningPointRatio(rr)
    val status: String
    val note: String
    if (nr > 0.10 && she > 0.70 && randomLike) {
        status = "possible_af"
        note = "Beat-to-beat timing was highly irregular in a random pattern, which is what atrial " +
            "fibrillation looks like on a pulse sensor. Movement and a loose strap can mimic this; " +
            "repeat sitting still, and if it recurs, ask a doctor for an ECG."
    } else if (nr > 0.10 || ect >= 3 || ect.toDouble() / max(rr.size, 1) > 0.05) {
        status = "irregular"
        note = if (ect > 0) "$ect early (ectopic) beats followed by a pause. Occasional ones are common and " +
            "usually harmless." else "Beat timing varied more than usual but not in a random pattern; deep breathing " +
            "(sinus arrhythmia) often does this."
    } else { status = "regular"; note = "Beat-to-beat timing looked regular." }
    val notes = arrayListOf(note, DISCLAIMER)
    if (hr != null) {
        val text = rounded(hr).toLong()
        if (hr < 40) notes.add(1, "Heart rate $text bpm is very low for a resting measurement.")
        else if (hr > 120) notes.add(1, "Heart rate $text bpm is high for a resting measurement.")
    }
    return RhythmResult(status, ect, rounded(nr, 3), beatNotes + notes,
        RhythmFeatures(rounded(nr, 3), rounded(she, 3), rounded(tpr, 3), randomLike, rr.size))
}
