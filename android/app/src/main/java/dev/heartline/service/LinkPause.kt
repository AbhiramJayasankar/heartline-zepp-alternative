package dev.heartline.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pause of the watch link, so another app (Zepp) can connect to the watch for a while. Kept in preferences rather than
 * memory, so nothing that would reconnect (the app opening, the watch coming back in range, the service restarting)
 * does so before it ends. Epoch milliseconds; 0 when not paused.
 */
object LinkPause {
    private const val PREFS = "heartline_link"
    private const val KEY = "paused_until"
    private val state = MutableStateFlow<Long?>(null)

    /** When the pause ends, or null when the link isn't paused. */
    fun until(context: Context): StateFlow<Long?> { refresh(context); return state }

    fun active(context: Context): Boolean = refresh(context) != null

    fun set(context: Context, untilMs: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, untilMs ?: 0L).apply()
        state.value = untilMs?.takeIf { it > System.currentTimeMillis() }
    }

    private fun refresh(context: Context): Long? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L).takeIf { it > System.currentTimeMillis() }
        state.value = v
        return v
    }
}
