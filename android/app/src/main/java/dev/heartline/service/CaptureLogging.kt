package dev.heartline.service

import dev.heartline.analytics.LoggedCaptureSet
import dev.heartline.app.GYM_LOG_KEY
import dev.heartline.app.LoggedSet
import dev.heartline.app.gymLogFromJson
import dev.heartline.data.HeartlineDatabase

/** Timed, completed strength sets from the gym log that fall inside a finished capture, in seconds from its start. */
internal fun loggedForCapture(log: List<LoggedSet>, s: CaptureSession): List<LoggedCaptureSet> {
    val end = s.end ?: return emptyList()
    return log.filter { it.completed && !it.cardio && it.start != null && it.start < end && (it.end ?: it.start) > s.start }.map {
        val warm = it.setType.startsWith("warm")
        // A set logged with a start but no end gets a typical set length.
        LoggedCaptureSet((it.start!! - s.start).toDouble(), ((it.end ?: (it.start + 45)) - s.start).toDouble(), it.exercise,
            if (warm) "Warm-up" else "Set ${it.setNumber}", it.loadKg, it.reps, warm)
    }.sortedBy { it.start }
}

/**
 * The engine version a stored analysis must carry to be current. With logged sets in the capture it also names them,
 * so importing or correcting a log makes the capture analyse again with it.
 */
internal suspend fun captureEngineKey(db: HeartlineDatabase, s: CaptureSession): Pair<String, List<LoggedCaptureSet>> {
    val logged = loggedForCapture(gymLogFromJson(db.meta.get(GYM_LOG_KEY)), s)
    // Not the resting heart rate: load is scored again for it when read (withRest), so a one-beat change to it no longer
    // means analysing every recording again.
    return (if (logged.isEmpty()) CAPTURE_ENGINE_VERSION else "$CAPTURE_ENGINE_VERSION+log${Integer.toHexString(logged.hashCode())}") to logged
}

/** Meta key for the resting heart rate behind the daily load and strain (profile, else median awake resting), whole bpm. */
internal const val HR_REST_KEY = "hr_rest_daily"
internal suspend fun captureRest(db: HeartlineDatabase): Int? = db.meta.get(HR_REST_KEY)?.toIntOrNull()?.takeIf { it in 30..120 }
