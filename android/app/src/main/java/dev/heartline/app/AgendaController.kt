package dev.heartline.app

import android.content.Context
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.ALARM_SLOTS
import dev.heartline.protocol.REMINDER_SLOTS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal class AgendaController(context: Context, private val scope: CoroutineScope, private val db: HeartlineDatabase) {
    private val provider = CalendarProvider(context.applicationContext)
    private val edits = Mutex()
    private val alarmState = MutableStateFlow<List<AlarmUi>>(emptyList())
    val alarms = alarmState.asStateFlow()
    private val reminderState = MutableStateFlow(ReminderSyncUi(maxSlots = REMINDER_SLOTS))
    val reminders = reminderState.asStateFlow()

    init {
        scope.launch {
            val changes = db.meta.observeAll().map { Triple(it[ALARMS_KEY], it[REMINDERS_KEY], it[REMINDERS_SYNC_KEY]) }.distinctUntilChanged().map { Unit }
            val tick = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(5 * 60_000L) } }
            merge(changes, tick).conflate().collect { runAction { refresh() } }
        }
    }
    private suspend fun refresh() = withContext(Dispatchers.IO) {
        val meta = db.meta.all()
        alarmState.value = readAlarms(meta[ALARMS_KEY])
        val prefs = readReminderPreferences(meta[REMINDERS_KEY])
        var permission = provider.permitted()
        var calendars = emptyList<CalendarUi>()
        var upcoming = emptyList<ReminderUi>()
        if (permission) try {
            calendars = provider.calendars()
            val now = Instant.now()
            val selected = prefs.selected.intersect(calendars.map { it.id }.toSet())
            upcoming = upcomingReminders(provider.instances(selected, now), selected, now, ZoneId.systemDefault()).map { ReminderUi(it.title, it.start) }
        } catch (_: SecurityException) { permission = false; calendars = emptyList() }
        reminderState.value = ReminderSyncUi(prefs.enabled, permission, calendars, prefs.selected, upcoming,
            meta[REMINDERS_SYNC_KEY]?.toLongOrNull(), REMINDER_SLOTS)
    }
    fun saveAlarm(alarm: AlarmUi) = edit {
        val valid = normalizedAlarm(alarm)
        db.meta.set(ALARMS_KEY, alarmsJson(readAlarms(db.meta.get(ALARMS_KEY)).filter { it.slot != valid.slot } + valid))
    }
    fun deleteAlarm(slot: Int) = edit {
        require(slot in 0 until ALARM_SLOTS)
        db.meta.set(ALARMS_KEY, alarmsJson(readAlarms(db.meta.get(ALARMS_KEY)).filter { it.slot != slot }))
    }
    fun setReminderSync(enabled: Boolean, calendars: Set<Long>) = edit {
        // Repeating the same selection after a permission grant must also wake the service.
        val revision = db.meta.get(REMINDERS_KEY)?.let { JSONObject(it).optLong("revision") } ?: 0
        db.meta.set(REMINDERS_KEY, JSONObject().put("enabled", enabled).put("selected", JSONArray(calendars.sorted())).put("revision", revision + 1).toString())
        refresh()
    }
    private fun edit(block: suspend () -> Unit) { scope.launch { runAction { withContext(Dispatchers.IO) { edits.withLock { block() } } } } }
    private suspend fun runAction(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("error", "Alarms/calendar: ${e.message}") }
    }
}
