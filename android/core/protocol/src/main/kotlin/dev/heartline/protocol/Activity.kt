package dev.heartline.protocol

data class ActivityMinute(
    val ts: Long, val kind: Int, val intensity: Int, val steps: Int, val hr: Int?,
    val unknown1: Int, val sleep: Int, val deep: Int, val rem: Int,
)

/** protocol.parse_activity: retain HR sentinel values (0 and 255) in the raw minute. */
fun parseActivity(data: ByteArray, startEpoch: Long): List<ActivityMinute> = (0 until data.size / 8).map { n ->
    val i = n * 8
    ActivityMinute(minute(startEpoch) + 60L * n, data.u8(i), data.u8(i + 1), data.u8(i + 2), data.u8(i + 3),
        data.u8(i + 4), data.u8(i + 5), data.u8(i + 6), data.u8(i + 7))
}

enum class ActivityState { NOT_WORN, SLEEP_LIGHT, SLEEP_DEEP, SLEEP_REM, ACTIVE, IDLE, CHARGING, UNKNOWN }
fun validHr(hr: Int?): Boolean = hr != null && hr in 1..254

/** protocol.activity_states / MiBand2SampleProvider.postprocess; REM is the >55 fallback heuristic. */
fun activityStates(minutes: List<ActivityMinute>, prevKind: Int? = null): List<ActivityState> {
    var last = prevKind
    return minutes.map { row ->
        var kind: Int? = row.kind and 15
        if (kind == 0 || kind == 10) kind = last else last = kind
        when (kind) {
            1, 2, 4, 12 -> ActivityState.ACTIVE
            3 -> ActivityState.NOT_WORN
            6 -> ActivityState.CHARGING
            9 -> if (row.rem and 127 > 55) ActivityState.SLEEP_REM else ActivityState.SLEEP_LIGHT
            11 -> ActivityState.SLEEP_DEEP
            else -> if (validHr(row.hr)) ActivityState.IDLE else ActivityState.UNKNOWN
        }
    }
}
