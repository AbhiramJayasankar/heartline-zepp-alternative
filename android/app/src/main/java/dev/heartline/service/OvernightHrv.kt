package dev.heartline.service

import dev.heartline.HeartlineApp
import dev.heartline.analytics.analyzeSession
import dev.heartline.app.analyticsMinutes
import dev.heartline.app.readRecording
import dev.heartline.ble.WatchConnection
import dev.heartline.data.model.HealthPoint
import dev.heartline.protocol.RawSensor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Overnight HRV settings, stored as JSON in meta [OvernightHrv.KEY]. A null start/end follows your recent nights. */
data class OvernightSettings(
    val enabled: Boolean = false, val start: String? = null, val end: String? = null, val allDay: Boolean = false,
    /** Night: a sample of [nightLen] minutes every [nightEvery] minutes. Day: [dayLen] minutes every [dayEvery]. */
    val nightEvery: Int = 15, val nightLen: Int = 3, val dayEvery: Int = 30, val dayLen: Int = 2,
) {
    fun json(): String = JSONObject().put("enabled", enabled).put("start", start ?: JSONObject.NULL).put("end", end ?: JSONObject.NULL).put("all_day", allDay)
        .put("night_every", nightEvery).put("night_len", nightLen).put("day_every", dayEvery).put("day_len", dayLen).toString()
    companion object {
        val NIGHT_EVERY = listOf(10, 15, 30, 60)
        val DAY_EVERY = listOf(15, 30, 60)
        val LENGTHS = listOf(2, 3, 5)
        fun parse(text: String?): OvernightSettings = runCatching {
            val j = JSONObject(text ?: "{}")
            fun pick(k: String, allowed: List<Int>, d: Int) = j.optInt(k, d).takeIf { it in allowed } ?: d
            OvernightSettings(j.optBoolean("enabled"), j.optString("start").takeIf { it.matches(Regex("\\d{2}:\\d{2}")) }, j.optString("end").takeIf { it.matches(Regex("\\d{2}:\\d{2}")) },
                j.optBoolean("all_day"), pick("night_every", NIGHT_EVERY, 15), pick("night_len", LENGTHS, 3), pick("day_every", DAY_EVERY, 30), pick("day_len", LENGTHS, 2))
        }.getOrDefault(OvernightSettings())
    }
}

/**
 * Overnight HRV: short heart checks while you sleep.
 *
 * Once a minute it asks: is this inside the night window (earliest bedtime − 1 h to latest wake + 1 h over your last
 * two weeks, or the times you set)? Inside it, the watch's minute data is synced every 10 minutes
 * and, when the newest 15 minutes are all labelled asleep with no steps, a raw pulse and motion recording (3 minutes
 * every 15 by default, both configurable) is taken and analysed like a heart check. A sample that isn't clean is retried
 * up to [RETRIES] times, [RETRY_S] apart, before waiting for the next slot. Every sample is stored as health type [SAMPLE] (RMSSD, or no
 * value when rejected, with the reason), and the night's median of clean samples as [NIGHT]. What it decides is logged
 * to the event log, including how far behind the watch's sleep labels were, so the first nights can be checked.
 */
class OvernightHrv(private val app: HeartlineApp, private val watch: WatchConnection, private val busy: () -> Boolean, private val rawDir: java.io.File) {
    companion object {
        const val KEY = "overnight_hrv"
        const val SAMPLE = "hrv_sleep_sample"
        const val NIGHT = "hrv_sleep"
        /** Daytime samples, taken only after 10 still, awake minutes. */
        const val DAY_SAMPLE = "hrv_day_sample"
        /** After a sample that isn't clean: try again this many times, this far apart. */
        const val RETRIES = 2
        const val RETRY_S = 120
        /** What the overnight engine is doing now, for the Sleep page. */
        val status = MutableStateFlow("Off")
        /** What daytime sampling is doing now, for the Heart page. */
        val dayStatus = MutableStateFlow("Off")
    }

    private val zone get() = ZoneId.systemDefault()
    private var lastSync = 0L
    private var lastSample = 0L
    private var lastDaySample = 0L
    /** A pending retry after a sample that wasn't clean: when, and how many have been used in this slot. */
    private var retryAt = 0L
    private var retriesUsed = 0
    private var dayRetryAt = 0L
    private var dayRetriesUsed = 0
    private var asleepNoted: LocalDate? = null
    private var nightStartedBattery: LocalDate? = null

    suspend fun run() {
        while (true) {
            try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { status.value = "Error: ${e.message}" }
            delay(60_000)
        }
    }

    /**
     * The night window as hours from midnight of the morning it ends: from settings, else from your earliest bedtime − 1 h
     * to your latest wake + 1 h over the last two weeks (kept between 18:00 and 16:00). It is generous on purpose: samples
     * only run once the watch says you're asleep, and while you're awake inside it daytime sampling still runs.
     */
    private fun window(s: OvernightSettings): Pair<Double, Double> {
        fun hours(t: String) = t.substring(0, 2).toInt() + t.substring(3, 5).toInt() / 60.0
        if (s.start != null && s.end != null) {
            val a = hours(s.start); val b = hours(s.end)
            return (if (a > b) a - 24 else a) to b
        }
        val nights = app.realController.trends.value?.nights.orEmpty().takeLast(14).filter { it.start != null && it.end != null }
        if (nights.size < 3) return -3.0 to 11.0 // 21:00 to 11:00 until there are three nights to learn from
        fun h(ts: Long, d: LocalDate) = (ts - d.atStartOfDay(zone).toEpochSecond()) / 3600.0
        val bed = nights.minOf { h(it.start!!, it.date) }
        val wake = nights.maxOf { h(it.end!!, it.date) }
        return (bed - 1).coerceAtLeast(-6.0) to (wake + 1).coerceAtMost(16.0)
    }

    /** The morning (wake date) whose night window contains [now], or null outside every window. */
    private fun nightOf(now: Long, w: Pair<Double, Double>): LocalDate? {
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1)).firstOrNull { d ->
            val mid = d.atStartOfDay(zone).toEpochSecond()
            now >= mid + w.first * 3600 && now <= mid + w.second * 3600
        }
    }

    private suspend fun tick() {
        val s = OvernightSettings.parse(app.db.meta.get(KEY))
        if (!s.enabled && !s.allDay) { status.value = "Off"; dayStatus.value = "Off"; return }
        val now = System.currentTimeMillis() / 1000
        val w = window(s)
        val night = if (s.enabled) nightOf(now, w) else null
        if (!s.enabled) status.value = "Off" else if (night == null) status.value = "Waiting for the night window (${clock(w.first)}–${clock(w.second)})"
        // Asleep in the night window: overnight samples. Otherwise (awake, or outside it): daytime samples when still.
        if (night != null && nightTick(now, night, s, w)) { dayStatus.value = if (s.allDay) "Paused while you're asleep" else "Off"; return }
        if (s.allDay) dayTick(now, s) else dayStatus.value = "Off"
    }

    /** Inside the night window. Returns false when you're not asleep by the watch's labels, so daytime sampling can run. */
    private suspend fun nightTick(now: Long, night: LocalDate, s: OvernightSettings, w: Pair<Double, Double>): Boolean {
        if (nightStartedBattery != night) {
            nightStartedBattery = night
            app.db.events.add("info", "Overnight HRV: night window ${clock(w.first)}–${clock(w.second)} started; watch battery ${watch.battery.value?.level ?: "?"}%")
        }
        if (now - lastSync >= 600) {
            lastSync = now
            runCatching { watch.syncActivity() }
        }
        // Six hours back so the watch's "same as before" minute codes resolve to a state.
        val rows = app.db.activity.range(now - 6 * 3600.0, now.toDouble())
        val minutes = analyticsMinutes(rows)
        val newest = minutes.lastOrNull()
        if (newest == null || now - newest.ts > 25 * 60) { status.value = "In the night window; waiting for fresh minute data from the watch"; return false }
        val recent = minutes.filter { it.ts > newest.ts - 15 * 60 }
        val asleep = recent.size >= 12 && recent.all { it.state.startsWith("sleep_") && it.steps == 0 }
        if (!asleep) {
            status.value = "In the night window; not asleep by the watch's labels yet (latest minute ${clock(newest.ts)})"
            return false
        }
        if (asleepNoted != night) {
            asleepNoted = night
            val firstSleep = minutes.takeLastWhile { it.state.startsWith("sleep_") }.firstOrNull()?.ts ?: newest.ts
            app.db.events.add("info", "Overnight HRV: asleep since ${clock(firstSleep)} by the watch's labels; noticed at ${clock(now)} " +
                "(${(now - firstSleep) / 60} min later; the newest synced minute was ${(now - newest.ts) / 60} min old)")
        }
        val every = s.nightEvery * 60L
        val retry = retryAt != 0L && now >= retryAt
        if (now - lastSample < every && !retry) {
            status.value = if (retryAt != 0L) "Asleep; last sample wasn't clean, retrying at ${clock(retryAt)}" else "Asleep; next sample at ${clock(lastSample + every)}"
            return true
        }
        if (busy()) { status.value = "Asleep; waiting for another recording to finish"; return true }
        if (!retry) { lastSample = now; retriesUsed = 0 }
        retryAt = 0L
        val clean = sample(night, SAMPLE, s.nightLen * 60, status, retriesUsed)
        if (!clean && retriesUsed < RETRIES) {
            retriesUsed++
            retryAt = System.currentTimeMillis() / 1000 + RETRY_S
            status.value = "Asleep; sample wasn't clean, retry $retriesUsed of $RETRIES at ${clock(retryAt)}"
        }
        return true
    }

    /** Daytime: after 10 still, awake minutes (worn, no steps, movement intensity 30 or less), a sample at most every [OvernightSettings.dayEvery] minutes. */
    private suspend fun dayTick(now: Long, s: OvernightSettings) {
        val every = s.dayEvery * 60L
        val retry = dayRetryAt != 0L && now >= dayRetryAt
        // A retry wants to know you're still still, so it syncs the watch's minutes first.
        if (now - lastSync >= 600 || retry) { lastSync = now; runCatching { watch.syncActivity() } }
        if (now - lastDaySample < every && !retry) {
            dayStatus.value = if (dayRetryAt != 0L) "Last sample wasn't clean, retrying at ${clock(dayRetryAt)} if you're still"
                else "Next sample after ${clock(lastDaySample + every)}, once you've been still for 10 minutes"
            return
        }
        val minutes = analyticsMinutes(app.db.activity.range(now - 6 * 3600.0, now.toDouble()))
        val newest = minutes.lastOrNull()
        if (newest == null || now - newest.ts > 25 * 60) { dayStatus.value = "Waiting for fresh minute data from the watch"; return }
        val recent = minutes.filter { it.ts > newest.ts - 10 * 60 }
        val still = recent.size >= 8 && recent.all { it.state != "not_worn" && it.state != "charging" && !it.state.startsWith("sleep_") && it.steps == 0 && it.intensity <= 30 }
        if (!still) {
            // A retry needs you still; if you've moved, give it up and wait for the next slot.
            if (retry) { dayRetryAt = 0L; dayRetriesUsed = 0 }
            dayStatus.value = "Waiting for 10 still minutes (latest minute ${clock(newest.ts)})"; return
        }
        if (busy()) { dayStatus.value = "Waiting for another recording to finish"; return }
        if (!retry) { lastDaySample = now; dayRetriesUsed = 0 }
        dayRetryAt = 0L
        val clean = sample(Instant.ofEpochSecond(now).atZone(zone).toLocalDate(), DAY_SAMPLE, s.dayLen * 60, dayStatus, dayRetriesUsed)
        if (!clean && dayRetriesUsed < RETRIES) {
            dayRetriesUsed++
            dayRetryAt = System.currentTimeMillis() / 1000 + RETRY_S
            dayStatus.value = "Sample wasn't clean, retry $dayRetriesUsed of $RETRIES at ${clock(dayRetryAt)}"
        }
    }

    /** Records and analyses one sample; true when it was clean. [attempt] is 0 for a slot's first try, then 1, 2 for retries. */
    private suspend fun sample(night: LocalDate, type: String, seconds: Int, state: MutableStateFlow<String>, attempt: Int): Boolean {
        state.value = "Recording a ${seconds / 60}-minute sample" + if (attempt > 0) " (retry $attempt of $RETRIES)" else ""
        var file: java.io.File? = null
        try {
            file = watch.startRaw(setOf(RawSensor.PPG, RawSensor.ACCEL))
            delay(seconds * 1000L)
        } finally { withContext(NonCancellable) { runCatching { watch.stopRaw() } } }
        val f = file ?: return false
        val result = withContext(Dispatchers.Default) { analyzeSession(readRecording(f), f.name) }
        val ts = result.start.toLong()
        val need = seconds / 2
        val clean = result.hrv.rmssdMs != null && result.signalQuality >= 0.7 && result.usableS >= need
        val reason = when {
            result.hrv.rmssdMs == null -> "too few clean beats"
            result.signalQuality < 0.7 -> "signal ${(result.signalQuality * 100).roundToInt()}% (needs 70%)"
            result.usableS < need -> "only ${result.usableS.roundToInt()} s of clean beats (needs $need)"
            else -> null
        }
        val extra = JSONObject().put("file", f.name).put("hr", result.hrBpm ?: JSONObject.NULL).put("resp", result.respiratoryRate ?: JSONObject.NULL)
            .put("quality", result.signalQuality).put("usable_s", result.usableS).put("rmssd", result.hrv.rmssdMs ?: JSONObject.NULL)
            .put("night", night.toString()).put("reason", reason ?: JSONObject.NULL).put("attempt", attempt).put("length_s", seconds).toString()
        app.db.health.upsert(listOf(HealthPoint(type, ts, if (clean) result.hrv.rmssdMs else null, extra)))
        app.db.events.add("info", (if (type == SAMPLE) "Overnight" else "Daytime") + " HRV sample at ${clock(ts)}${if (attempt > 0) " (retry $attempt)" else ""}: " + if (clean) "RMSSD ${result.hrv.rmssdMs?.roundToInt()} ms, " +
            "${result.hrBpm?.roundToInt()} bpm, breathing ${result.respiratoryRate ?: "–"}/min" else "set aside, $reason")
        if (type != SAMPLE) { state.value = "Sample taken at ${clock(ts)}"; return clean }
        // The night's value: the median of its clean samples, stamped at the latest one.
        val samples = app.db.health.series(SAMPLE, night.minusDays(1).atStartOfDay(zone).toEpochSecond().toDouble(), night.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble())
            .filter { p -> p.value != null && runCatching { JSONObject(p.extra ?: "{}").optString("night") }.getOrNull() == night.toString() }
        if (samples.isNotEmpty()) {
            val v = samples.map { it.value!! }.sorted()
            val median = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
            app.db.health.deleteTypeRange(NIGHT, night.minusDays(1).atStartOfDay(zone).toEpochSecond(), night.plusDays(1).atStartOfDay(zone).toEpochSecond())
            app.db.health.upsert(listOf(HealthPoint(NIGHT, samples.maxOf { it.ts }, median, JSONObject().put("night", night.toString()).put("samples", samples.size).toString())))
        }
        status.value = "Asleep; sample taken at ${clock(ts)}"
        return clean
    }

    private fun clock(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    private fun clock(h: Double): String { val m = Math.floorMod((h * 60).roundToInt(), 1440); return "%02d:%02d".format(m / 60, m % 60) }
}
