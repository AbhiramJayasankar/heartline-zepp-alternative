package dev.heartline.app

import dev.heartline.data.MetaRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

internal const val ALARMS_KEY = "alarms_list"
internal const val ALARMS_WRITTEN_KEY = "alarms_written"
internal const val REMINDERS_KEY = "reminders_settings"
internal const val REMINDERS_WRITTEN_KEY = "reminders_written"
internal const val REMINDERS_SYNC_KEY = "reminders_last_sync"

internal data class ReminderPreferences(val enabled: Boolean = false, val selected: Set<Long> = emptySet())

internal fun readAlarms(text: String?): List<AlarmUi> = text?.let {
    JSONArray(it).let { array -> (0 until array.length()).map { index ->
        val a = array.getJSONObject(index)
        val days = a.getJSONArray("days")
        val weekdays = (0 until days.length()).map { day -> DayOfWeek.of(days.getInt(day)) }.toSet()
        normalizedAlarm(AlarmUi(a.getInt("slot"), a.getBoolean("enabled"), a.getInt("hour"), a.getInt("minute"), weekdays))
    } }.distinctBy { it.slot }.sortedBy { it.slot }
}.orEmpty()

internal fun alarmsJson(alarms: List<AlarmUi>): String = JSONArray(alarms.sortedBy { it.slot }.map {
    JSONObject().put("slot", it.slot).put("enabled", it.enabled).put("hour", it.hour).put("minute", it.minute)
        .put("days", JSONArray(it.days.map(DayOfWeek::getValue).sorted())).put("smart", false)
}).toString()

internal fun readReminderPreferences(text: String?): ReminderPreferences = text?.let {
    val j = JSONObject(it)
    val ids = j.getJSONArray("selected")
    ReminderPreferences(j.getBoolean("enabled"), (0 until ids.length()).map { index -> ids.getLong(index) }.toSet())
} ?: ReminderPreferences()

internal suspend fun MetaRepository.readSlots(key: String): AgendaSlots {
    val j = JSONObject(get(key) ?: "{}")
    return AgendaSlots(j.keys().asSequence().associate { it.toInt() to j.getString(it) }.toMutableMap())
}

internal suspend fun MetaRepository.saveSlots(key: String, slots: AgendaSlots) {
    set(key, JSONObject().apply { slots.snapshot().forEach { (slot, bytes) -> put(slot.toString(), bytes) } }.toString())
}
