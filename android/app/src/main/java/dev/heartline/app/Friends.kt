package dev.heartline.app

import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.model.ActivityRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Something recorded on someone else: a heart check taken for a friend, a capture or watch workout while a friend wore
 * the watch. [from] to [to] (epoch seconds) is when the watch was on them.
 */
data class FriendMark(val kind: Kind, val ref: String, val from: Long, val to: Long) {
    enum class Kind { CHECK, CAPTURE, WORKOUT }
    fun json(): JSONObject = JSONObject().put("kind", kind.name).put("ref", ref).put("from", from).put("to", to)
}

/**
 * The friend marks, and the watch workouts deleted in Heartline (the watch keeps its own copy, so they are hidden rather
 * than removed). Every minute inside a mark counts as the watch not being worn: its heart rate, steps, sleep, stress and
 * SpO2 stay out of the owner's days, trends, readiness, Health Connect and the assistant's answers.
 */
data class Friends(val marks: List<FriendMark> = emptyList(), val hiddenWorkouts: Set<Long> = emptySet()) {
    fun isFriend(kind: FriendMark.Kind, ref: String): Boolean = marks.any { it.kind == kind && it.ref == ref }
    /** Whether the moment [ts] was on a friend's wrist. */
    fun covers(ts: Long): Boolean = marks.any { ts >= it.from && ts <= it.to }
    /** Whether any of [from] to [to] was. */
    fun overlaps(from: Long, to: Long): Boolean = marks.any { from <= it.to && to >= it.from }
    /** A watch workout to leave out of the owner's figures: deleted, or a friend's. */
    fun dropsWorkout(start: Long): Boolean = start in hiddenWorkouts || covers(start)

    fun with(mark: FriendMark, friend: Boolean): Friends =
        copy(marks = marks.filterNot { it.kind == mark.kind && it.ref == mark.ref } + listOfNotNull(mark.takeIf { friend }))
    fun without(kind: FriendMark.Kind, ref: String): Friends = copy(marks = marks.filterNot { it.kind == kind && it.ref == ref })

    fun json(): String = JSONObject().put("marks", JSONArray(marks.map { it.json() })).put("hidden_workouts", JSONArray(hiddenWorkouts.sorted())).toString()

    companion object {
        const val KEY = "friend_marks"
        fun parse(text: String?): Friends = runCatching {
            val o = JSONObject(text ?: "{}")
            val m = o.optJSONArray("marks") ?: JSONArray()
            val h = o.optJSONArray("hidden_workouts") ?: JSONArray()
            Friends((0 until m.length()).mapNotNull { i -> m.optJSONObject(i)?.let { j ->
                runCatching { FriendMark(FriendMark.Kind.valueOf(j.getString("kind")), j.getString("ref"), j.getLong("from"), j.getLong("to")) }.getOrNull()
            } }, (0 until h.length()).map { h.getLong(it) }.toSet())
        }.getOrDefault(Friends())
    }
}

/** Reads and changes [Friends] in meta, one change at a time (the app and the watch service both change it). */
object FriendStore {
    private val lock = Mutex()
    suspend fun read(db: HeartlineDatabase): Friends = Friends.parse(db.meta.get(Friends.KEY))
    suspend fun update(db: HeartlineDatabase, change: (Friends) -> Friends): Friends = lock.withLock {
        change(read(db)).also { db.meta.set(Friends.KEY, it.json()) }
    }
}

/**
 * [rows] with the minutes a friend wore the watch turned into "not worn" (no steps, no heart rate), so they drop out of
 * every daily figure. The minute after a friend's span keeps the state it had: the watch's "same as before" codes
 * would otherwise carry "not worn" on into the owner's own time.
 */
fun friendFree(rows: List<ActivityRow>, friends: Friends, prevKind: Int? = null): List<ActivityRow> {
    if (friends.marks.isEmpty()) return rows
    var last = prevKind
    var after = false
    return rows.map { row ->
        val kind = row.kind?.and(15)
        val resolved = if (kind == 0 || kind == 10 || kind == null) last else kind.also { last = it }
        val friend = friends.marks.any { row.ts + 59 >= it.from && row.ts <= it.to }
        when {
            friend -> { after = true; row.copy(kind = NOT_WORN, intensity = 0, steps = 0, hr = null) }
            after && (kind == 0 || kind == 10) -> row.copy(kind = resolved ?: row.kind)
            else -> { after = false; row }
        }
    }
}

/** The watch's activity code for "not worn". */
private const val NOT_WORN = 3
