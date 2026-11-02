package dev.heartline.service

/** Epoch seconds throughout. A gap stays open until raw samples can stream again, not merely until authentication. */
data class CaptureGap(val start: Long, val end: Long? = null)
data class CaptureSession(
    val id: String, val label: String, val start: Long, val plannedS: Int, val end: Long? = null,
    val files: List<String> = emptyList(), val gaps: List<CaptureGap> = emptyList(),
    val samples: Long = 0, val restoreHr: Boolean = false, val error: String? = null,
) {
    fun due(now: Long): Boolean = end == null && now >= start + plannedS
    fun elapsed(now: Long): Int = ((end ?: now) - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    fun availability(now: Long, streaming: Boolean, file: String? = null): CaptureSession {
        val nextFiles = if (file != null && file !in files) files + file else files
        val open = gaps.lastOrNull()?.end == null && gaps.isNotEmpty()
        val nextGaps = when {
            !streaming && !open -> gaps + CaptureGap(maxOf(start, now))
            streaming && open -> gaps.dropLast(1) + gaps.last().copy(end = maxOf(gaps.last().start, now))
            else -> gaps
        }
        return copy(files = nextFiles, gaps = nextGaps)
    }
    fun finish(now: Long): CaptureSession = availability(now, true).copy(end = maxOf(start, now))
}
