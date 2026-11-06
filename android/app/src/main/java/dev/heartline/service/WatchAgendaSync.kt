package dev.heartline.service

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import dev.heartline.app.*
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId

/** Service-owned scheduling continues when no activity is bound. Only successful slot writes enter the cache. */
internal class WatchAgendaSync(
    private val service: WatchService, private val scope: CoroutineScope, private val db: HeartlineDatabase,
    private val watch: WatchConnection,
) : AutoCloseable {
    private val provider = CalendarProvider(service)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var debounce: Job? = null
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope.launch {
                debounce?.cancel()
                debounce = launch { delay(1500); changes.trySend(Unit) }
            }
        }
    }
    private val settingsJob = scope.launch {
        db.meta.observeAll().map { listOf(it[ALARMS_KEY], it[REMINDERS_KEY], it[WatchAssistant.ALERTS]) }.distinctUntilChanged().collect { changes.trySend(Unit) }
    }
    private val syncJob = scope.launch {
        try {
            runAgendaSchedule(watch.ready.onEach { if (!it) stopObserving() }, changes) { forceAlarms ->
                var alarmsOk = true
                var remindersOk = true
                try { syncAlarms(db.meta.readSlots(ALARMS_WRITTEN_KEY), forceAlarms) }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive() // Local ACK timeouts are failures; service cancellation still propagates.
                    alarmsOk = false; db.events.add("warn", "Alarm sync failed: ${e.message}")
                }
                try { syncReminders(db.meta.readSlots(REMINDERS_WRITTEN_KEY)) }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    remindersOk = false; db.events.add("warn", "Calendar sync failed: ${e.message}")
                }
                AgendaRefresh(alarmsOk, remindersOk)
            }
        } finally { stopObserving() }
    }
    private suspend fun syncAlarms(slots: AgendaSlots, force: Boolean) {
        val desired = alarmSlots(readAlarms(db.meta.get(ALARMS_KEY)))
        for (slot in slots.changed(desired, force)) {
            service.command { it.writeAlarm(desired[slot]) }
            slots.written(slot, desired[slot]); db.meta.saveSlots(ALARMS_WRITTEN_KEY, slots)
        }
    }
    private suspend fun syncReminders(slots: AgendaSlots) {
        val prefs = readReminderPreferences(db.meta.get(REMINDERS_KEY))
        val permitted = provider.permitted()
        if (permitted && !observing) {
            service.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observing = true
        } else if (!permitted) stopObserving()
        // Losing permission must not turn a failed read into deletion of existing events.
        if (prefs.enabled && !permitted) return
        val now = Instant.now()
        // Reminders set with the Alexa button share the watch's reminder slots, so they show their title.
        val voice = WatchAssistant.voiceReminders(db.meta.get(WatchAssistant.ALERTS), now.epochSecond)
        if (!prefs.enabled && voice.isEmpty() && slots.snapshot().isEmpty()) return
        val zone = ZoneId.systemDefault()
        val calendar = if (prefs.enabled) withContext(Dispatchers.IO) {
            val selected = prefs.selected.intersect(provider.calendars().map { it.id }.toSet())
            upcomingReminders(provider.instances(selected, now), selected, now, zone)
        } else emptyList()
        val events = (voice + calendar).sortedBy { it.start }.take(REMINDER_SLOTS)
        val desired = (0 until REMINDER_SLOTS).map { calendarReminderCommand(it, events.getOrNull(it), zone) }
        for (slot in slots.changed(desired)) {
            service.command { it.writeCalendarReminder(desired[slot]) }
            slots.written(slot, desired[slot]); db.meta.saveSlots(REMINDERS_WRITTEN_KEY, slots)
            db.meta.set(REMINDERS_SYNC_KEY, Instant.now().epochSecond.toString())
        }
    }
    private fun stopObserving() {
        debounce?.cancel(); debounce = null
        if (observing) { service.contentResolver.unregisterContentObserver(observer); observing = false }
    }
    override fun close() { settingsJob.cancel(); syncJob.cancel(); changes.close() }
}
