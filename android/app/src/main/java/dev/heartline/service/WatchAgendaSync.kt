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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                try { syncAlarms(forceAlarms) }
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
    private suspend fun syncAlarms(force: Boolean) = alarmLock.withLock {
        // On connecting, first take in what was switched or deleted on the watch meanwhile, so it isn't undone.
        if (force) try { followWatch() } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); db.events.add("warn", "Couldn't read the watch's alarms: ${e.message}")
        }
        val written = db.meta.readSlots(ALARMS_WRITTEN_KEY)
        val desired = alarmSlots(readAlarms(db.meta.get(ALARMS_KEY)))
        for (slot in written.changed(desired, force)) {
            service.command { it.writeAlarm(desired[slot]) }
            written.written(slot, desired[slot]); db.meta.saveSlots(ALARMS_WRITTEN_KEY, written)
        }
    }

    /** Alarm reads and writes, one at a time (the sync, the assistant's alarm tools, the watch's alarm events). */
    private val alarmLock = Mutex()

    /** Reads the watch's alarms and lets Heartline's list follow switches flipped and alarms deleted on the watch. */
    private suspend fun followWatch(): Pair<List<WatchAlarmSwitch>, List<AlarmUi>> {
        val switches = service.command { it.readAlarmSwitches() }
        val slots = db.meta.readSlots(ALARMS_WRITTEN_KEY)
        val stored = readAlarms(db.meta.get(ALARMS_KEY))
        val (next, record) = followWatchAlarms(stored, slots.snapshot(), switches)
        if (record.isNotEmpty()) {
            record.forEach { (slot, bytes) -> slots.written(slot, bytes) }
            db.meta.saveSlots(ALARMS_WRITTEN_KEY, slots)
            db.meta.set(ALARMS_KEY, alarmsJson(next))
            db.events.add("info", "Alarms changed on the watch: " + record.keys.joinToString { s ->
                next.firstOrNull { it.slot == s }?.let { "slot $s switched ${if (it.enabled) "on" else "off"}" } ?: "slot $s deleted" })
        }
        return switches to next
    }

    /** The watch said an alarm was switched or changed on it. */
    fun watchAlarmsChanged() {
        scope.launch {
            try { alarmLock.withLock { followWatch() } } catch (e: Exception) {
                currentCoroutineContext().ensureActive(); db.events.add("warn", "Couldn't read the watch's alarms: ${e.message}")
            }
        }
    }

    /** Every alarm on the watch now: Heartline's with their times, and the ones made on the watch (time unknown). */
    suspend fun watchAlarms(): List<WatchAlarm> = alarmLock.withLock {
        val (switches, stored) = followWatch()
        val mine = stored.map { a -> WatchAlarm(a.slot, switches.firstOrNull { it.slot == a.slot }?.on ?: a.enabled, a.hour, a.minute, a.days, false) }
        (mine + switches.filter { s -> stored.none { it.slot == s.slot } }.map { WatchAlarm(it.slot, it.on, null, null, emptySet(), true) }).sortedBy { it.slot }
    }

    /**
     * Sets an alarm on the watch and waits for the watch to take it. [slot] null picks a free one. Returns the slot used.
     * Alarms made on the watch (slots 10 and up) can't be changed from the phone: the watch acknowledges writes to them
     * and ignores them (tested 2026-11-09), so they are refused here.
     */
    suspend fun putAlarm(slot: Int?, hour: Int, minute: Int, days: Set<java.time.DayOfWeek>, on: Boolean): Int = alarmLock.withLock {
        if (slot != null && slot >= ALARM_SLOTS) error(WATCH_MADE.format(slot))
        val (switches, stored) = followWatch()
        val target = slot ?: freeAlarmSlot(stored, switches)
            ?: error("all $ALARM_SLOTS of Heartline's alarm slots are in use; delete one first")
        val cmd = alarmCommand(target, on, hour, minute, days)
        service.command { it.writeAlarm(cmd) }
        val slots = db.meta.readSlots(ALARMS_WRITTEN_KEY)
        slots.written(target, cmd)
        db.meta.saveSlots(ALARMS_WRITTEN_KEY, slots)
        db.meta.set(ALARMS_KEY, alarmsJson(stored.filter { it.slot != target } + AlarmUi(target, on, hour, minute, days)))
        target
    }

    /** Switches alarm [slot] on or off; only Heartline's alarms, since the watch doesn't share the time of its own. */
    suspend fun switchAlarm(slot: Int, on: Boolean) {
        val a = alarmLock.withLock { followWatch() }.second.firstOrNull { it.slot == slot }
            ?: error(WATCH_MADE.format(slot))
        putAlarm(slot, a.hour, a.minute, a.days, on)
    }

    /** Deletes one of Heartline's alarms from the watch, and checks with the watch that it's gone. */
    suspend fun deleteAlarm(slot: Int) = alarmLock.withLock {
        require(slot in 0..WATCH_ALARM_SLOT_MAX) { "slots are 0 to $WATCH_ALARM_SLOT_MAX" }
        val (switches, stored) = followWatch()
        if (switches.none { it.slot == slot } && stored.none { it.slot == slot }) error("there is no alarm $slot on the watch")
        if (slot >= ALARM_SLOTS) error(WATCH_MADE.format(slot))
        val cmd = alarmCommand(slot, unused = true)
        service.command { it.writeAlarm(cmd) }
        val slots = db.meta.readSlots(ALARMS_WRITTEN_KEY)
        slots.written(slot, cmd); db.meta.saveSlots(ALARMS_WRITTEN_KEY, slots)
        db.meta.set(ALARMS_KEY, alarmsJson(stored.filter { it.slot != slot }))
        if (service.command { it.readAlarmSwitches() }.any { it.slot == slot }) error("the watch still lists alarm $slot after deleting it")
    }

    private companion object {
        const val WATCH_MADE = "alarm %d isn't in Heartline's alarm slots (it was made on the watch or by an Alexa request), and the watch doesn't let the phone change or delete those; it can be switched on or off on the watch (Alarm app)"
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
