package dev.heartline.app

/** Active sessions are in Android priority order; prefer playback over a paused higher-priority app. */
fun preferredMusicIndex(playing: List<Boolean>): Int? = playing.indexOfFirst { it }.takeIf { it >= 0 } ?: playing.indices.firstOrNull()

fun nowPlayingText(title: String?, artist: String?): String? = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() },
    artist?.trim()?.takeIf { it.isNotEmpty() }).joinToString(", ").ifEmpty { null }

fun musicVolumePercent(volume: Int, max: Int): Int = if (max <= 0) 0 else Math.round(volume.coerceIn(0, max) * 100f / max)

fun musicPositionSeconds(positionMs: Long, updatedAtMs: Long, nowMs: Long, speed: Float, playing: Boolean, durationMs: Long): Int {
    val elapsed = if (playing && updatedAtMs > 0 && nowMs >= updatedAtMs && speed.isFinite()) ((nowMs - updatedAtMs) * speed).toLong() else 0
    val estimate = (positionMs.coerceAtLeast(0) + elapsed).coerceAtLeast(0)
    return ((if (durationMs > 0) estimate.coerceAtMost(durationMs) else estimate) / 1000).coerceAtMost(65535).toInt()
}
