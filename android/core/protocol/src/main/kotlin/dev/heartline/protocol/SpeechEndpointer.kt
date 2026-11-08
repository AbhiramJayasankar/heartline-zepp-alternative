package dev.heartline.protocol

/**
 * Decides when someone has finished speaking, from the loudness of each 20 ms audio frame (dBFS). Alexa normally does
 * this ("stop capture"); without it the watch listens until its own 30 s limit.
 *
 * Tuned on the GTR 2e's Alexa audio (2026-11-03): the room sits near -63 dBFS, speech at -35 to -45, and natural pauses
 * between phrases run up to ~2 s. The first [skipFrames] hold the watch's start tone.
 *
 * Once someone is speaking, a frame only keeps listening going if its level over the last [smoothFrames] is within
 * [speakerDropDb] of the loudest they've been. Without that, room sounds 15 to 20 dB over the floor (about -45 dBFS,
 * a few frames each) kept resetting the quiet count, so 5 of the user's 6 questions from 02:09 to 02:16 ran to the 15 s cap
 * (2026-11-09). Replayed on 30 saved questions and transcribed again, 18 dB ends 15 of them sooner (104 s in all)
 * and drops no words.
 */
class SpeechEndpointer(
    private val marginDb: Float = 15f,
    private val speakerDropDb: Float = 18f,
    private val smoothFrames: Int = 5,
    private val endSilenceFrames: Int = 75,
    private val minSpeechFrames: Int = 15,
    private val noSpeechFrames: Int = 350,
    /** 29 s: just under the watch's own 30 s, so a long, detailed question is heard in full. */
    private val maxFrames: Int = 1450,
    private val skipFrames: Int = 20,
) {
    enum class State { WAITING, SPEAKING, DONE, NOTHING_SAID, TOO_LONG }

    var state = State.WAITING
        private set
    var frames = 0
        private set
    var speechFrames = 0
        private set
    private var floor = Float.NaN
    private var silence = 0
    private val recent = DoubleArray(smoothFrames)
    private var recentCount = 0
    private var peak = Double.NEGATIVE_INFINITY

    /** Feeds one 20 ms frame's level; returns true once, on the frame where listening should stop. */
    fun feed(levelDb: Float): Boolean {
        if (state == State.DONE || state == State.NOTHING_SAID || state == State.TOO_LONG) return false
        frames++
        if (frames <= skipFrames) return false
        // The floor follows quiet frames quickly and loud ones slowly, so speech does not drag it up.
        floor = if (floor.isNaN()) levelDb else if (levelDb < floor) floor + (levelDb - floor) * 0.3f else floor + (levelDb - floor) * 0.0005f
        recent[recentCount++ % smoothFrames] = Math.pow(10.0, levelDb / 10.0)
        val smooth = 10 * kotlin.math.log10(recent.take(minOf(recentCount, smoothFrames)).average())
        var speech = levelDb > floor + marginDb
        if (speech && state == State.SPEAKING) peak = maxOf(peak, smooth)
        if (speech && state == State.SPEAKING && smooth < peak - speakerDropDb) speech = false
        if (speech) { speechFrames++; silence = 0 } else silence++
        if (speechFrames >= minSpeechFrames && state == State.WAITING) { state = State.SPEAKING; peak = maxOf(peak, smooth) }
        state = when {
            state == State.SPEAKING && silence >= endSilenceFrames -> State.DONE
            state == State.WAITING && frames >= noSpeechFrames -> State.NOTHING_SAID
            frames >= maxFrames -> State.TOO_LONG
            else -> return false
        }
        return true
    }
}
