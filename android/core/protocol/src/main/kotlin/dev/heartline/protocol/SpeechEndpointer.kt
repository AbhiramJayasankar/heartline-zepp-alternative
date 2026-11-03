package dev.heartline.protocol

/**
 * Decides when someone has finished speaking, from the loudness of each 20 ms audio frame (dBFS). Alexa normally does
 * this ("stop capture"); without it the watch listens until its own 30 s limit.
 *
 * Tuned on the GTR 2e's Alexa audio (2026-11-03): the room sits near -63 dBFS, speech at -35 to -45, and natural pauses
 * between phrases run up to ~2 s. The first [skipFrames] hold the watch's start tone.
 */
class SpeechEndpointer(
    private val marginDb: Float = 15f,
    private val endSilenceFrames: Int = 75,
    private val minSpeechFrames: Int = 15,
    private val noSpeechFrames: Int = 350,
    private val maxFrames: Int = 750,
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

    /** Feeds one 20 ms frame's level; returns true once, on the frame where listening should stop. */
    fun feed(levelDb: Float): Boolean {
        if (state == State.DONE || state == State.NOTHING_SAID || state == State.TOO_LONG) return false
        frames++
        if (frames <= skipFrames) return false
        // The floor follows quiet frames quickly and loud ones slowly, so speech does not drag it up.
        floor = if (floor.isNaN()) levelDb else if (levelDb < floor) floor + (levelDb - floor) * 0.3f else floor + (levelDb - floor) * 0.0005f
        val speech = levelDb > floor + marginDb
        if (speech) { speechFrames++; silence = 0 } else silence++
        if (speechFrames >= minSpeechFrames) state = State.SPEAKING
        state = when {
            state == State.SPEAKING && silence >= endSilenceFrames -> State.DONE
            state == State.WAITING && frames >= noSpeechFrames -> State.NOTHING_SAID
            frames >= maxFrames -> State.TOO_LONG
            else -> return false
        }
        return true
    }
}
