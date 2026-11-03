package dev.heartline.ble

/** Single switch for captures, live raw streaming and manual checks. Off: probe 3 (3 Nov) showed mask bit 0x80 has
 * no effect; the watch switches PPG to ~50 Hz on its own, and the sample clock follows that from the timestamps. */
internal const val FAST_PPG_ENABLED = false

internal class RawPpgMode(requested: Boolean, private var startedMs: Long) {
    private var baseline = 0L
    var fast = requested
        private set
    fun began(nowMs: Long, ppgPackets: Long) { startedMs = nowMs; baseline = ppgPackets }
    fun hasPpg(ppgPackets: Long): Boolean = ppgPackets > baseline
    fun retrySlow(nowMs: Long, ppgPackets: Long): Boolean {
        if (!fast || hasPpg(ppgPackets) || nowMs - startedMs < 5000) return false
        fast = false
        return true
    }
}
