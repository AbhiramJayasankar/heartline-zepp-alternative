package dev.heartline.app

import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.VibrationPatterns
import dev.heartline.protocol.WatchMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class MenuAppUi(val key: String, val label: String, val shown: Boolean)
data class VibrationUi(val kind: String, val label: String, val profile: String?, val repeat: Int)

/** The watch's app list and vibration patterns: saved in meta, sent only when the owner asks (the watch cannot report them). */
class WatchCustomizeController(private val scope: CoroutineScope, private val db: HeartlineDatabase,
                               private val action: (suspend (WatchConnection) -> Unit) -> Unit) {
    val watchMenu: StateFlow<List<MenuAppUi>> = db.meta.observe(MENU_KEY).map { parseMenu(it) }.stateIn(scope, SharingStarted.Eagerly, parseMenu(null))
    val vibrations: StateFlow<List<VibrationUi>> = db.meta.observe(VIBRATION_KEY).map { parseVibrations(it) }.stateIn(scope, SharingStarted.Eagerly, parseVibrations(null))

    fun setWatchMenu(apps: List<MenuAppUi>) = scope.launch { db.meta.set(MENU_KEY, encodeMenu(apps)) }
    fun sendWatchMenu() {
        val keys = watchMenu.value.filter { it.shown }.map { it.key }
        action { it.writeChunkedType2(WatchMenu.command(keys), "watch app list (${keys.size} apps)") }
    }

    fun setVibration(kind: String, profile: String, repeat: Int) {
        scope.launch { db.meta.set(VIBRATION_KEY, encodeVibrations(vibrations.value.map { if (it.kind == kind) it.copy(profile = profile, repeat = repeat) else it })) }
        send(kind, profile, repeat, test = false)
    }
    /** The watch ignores a trial buzz for "find watch", so that one is saved and then rung the way Find watch rings it. */
    fun tryVibration(kind: String, profile: String, repeat: Int) {
        if (kind != "find_band") return send(kind, profile, repeat, test = true)
        scope.launch { db.meta.set(VIBRATION_KEY, encodeVibrations(vibrations.value.map { if (it.kind == kind) it.copy(profile = profile, repeat = repeat) else it })) }
        val k = VibrationPatterns.KINDS.first { it.key == kind }; val p = VibrationPatterns.PROFILES.first { it.key == profile }
        action { it.writeChunkedType2(VibrationPatterns.command(k, p, repeat), "vibration for ${k.label}"); it.vibrate() }
    }

    private fun send(kind: String, profile: String, repeat: Int, test: Boolean) {
        val k = VibrationPatterns.KINDS.first { it.key == kind }; val p = VibrationPatterns.PROFILES.first { it.key == profile }
        action { it.writeChunkedType2(VibrationPatterns.command(k, p, repeat, test), (if (test) "trial " else "") + "vibration for ${k.label}") }
    }

    companion object {
        const val MENU_KEY = "watch_menu"
        const val VIBRATION_KEY = "vibration_patterns"
        /** Apps shown before anything is saved: the GTR 2e's own set as far as Zepp's menu ids go. Owners can trim it. */
        val DEFAULT_SHOWN = setOf("status", "pai", "hr", "spo2", "stress", "breathing", "sleep", "workout", "activity", "weather",
            "notifications", "eventreminder", "alarm", "worldclock", "timer", "stopwatch", "compass", "barometer", "music", "alexa",
            "findphone", "mutephone", "settings", "more")

        fun parseMenu(text: String?): List<MenuAppUi> {
            val saved = runCatching { JSONArray(text) }.getOrNull()
                ?: return WatchMenu.APPS.map { MenuAppUi(it.key, it.label, it.key in DEFAULT_SHOWN) }
            val labels = WatchMenu.APPS.associate { it.key to it.label }
            val listed = (0 until saved.length()).mapNotNull { i -> saved.optJSONObject(i)?.let { o ->
                o.optString("key").takeIf { it in labels }?.let { MenuAppUi(it, labels.getValue(it), o.optBoolean("shown", true)) } } }
            // Apps added to Heartline later go to the end, hidden.
            return listed + WatchMenu.APPS.filter { a -> listed.none { it.key == a.key } }.map { MenuAppUi(it.key, it.label, false) }
        }
        fun encodeMenu(apps: List<MenuAppUi>): String = JSONArray(apps.map { JSONObject().put("key", it.key).put("shown", it.shown) }).toString()

        fun parseVibrations(text: String?): List<VibrationUi> {
            val saved = runCatching { JSONObject(text ?: "{}") }.getOrElse { JSONObject() }
            return VibrationPatterns.KINDS.map { k ->
                val o = saved.optJSONObject(k.key)
                VibrationUi(k.key, k.label, o?.optString("profile")?.takeIf { p -> VibrationPatterns.PROFILES.any { it.key == p } }, o?.optInt("repeat", 2) ?: 2)
            }
        }
        fun encodeVibrations(list: List<VibrationUi>): String = JSONObject().apply {
            list.filter { it.profile != null }.forEach { put(it.kind, JSONObject().put("profile", it.profile).put("repeat", it.repeat)) }
        }.toString()
    }
}
