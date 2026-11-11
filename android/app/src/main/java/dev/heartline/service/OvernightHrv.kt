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
import kotlinx.coroutines.launch
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
    /** Accurate positions: a short motion-only recording every [OvernightHrv.POSE_EVERY_S] while asleep, for sleep positions. */
    val positions: Boolean = false,
) {
    fun json(): String = JSONObject().put("enabled", enabled).put("start", start ?: JSONObject.NULL).put("end", end ?: JSONObject.NULL).put("all_day", allDay)
        .put("night_every", nightEvery).put("night_len", nightLen).put("day_every", dayEvery).put("day_len", dayLen).put("positions", positions).toString()
    companion object {
        val NIGHT_EVERY = listOf(10, 15, 30, 60)
        val DAY_EVERY = listOf(15, 30, 60)
        val LENGTHS = listOf(2, 3, 5)
        fun parse(text: String?): OvernightSettings = runCatching {
            val j = JSONObject(text ?: "{}")
            fun pick(k: String, allowed: List<Int>, d: Int) = j.optInt(k, d).takeIf { it in allowed } ?: d
            OvernightSettings(j.optBoolean("enabled"), j.optString("start").takeIf { it.matches(Regex("\\d{2}:\\d{2}")) }, j.optString("end").takeIf { it.matches(Regex("\\d{2}:\\d{2}")) },
                j.optBoolean("all_day"), pick("night_every", NIGHT_EVERY, 15), pick("night_len", LENGTHS, 3), pick("day_every", DAY_EVERY, 30), pick("day_len", LENGTHS, 2),
                j.optBoolean("positions"))
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
        /** Accurate positions: which way the watch faced (extra "grav"), from a [POSE_LEN_S] motion-only recording. */
        const val POSE = "sleep_pose"
        const val POSE_EVERY_S = 300L
        const val POSE_LEN_S = 20
        /** Daytime samples, tried whenever the watch has counted no steps for a minute (see [DayHrvPlan]). */
        const val DAY_SAMPLE = "hrv_day_sample"
        /** After a sample that isn't clean: try again this many times, this far apart. */
        const val RETRIES = 2
        const val RETRY_S = 120
        /**
         * Raw recordings of automatic samples are deleted after this long. Everything the app uses is kept in the sample
         * row first (see [extract]): all HRV numbers, rhythm, breathing and every beat-to-beat interval. Only re-running
         * beat detection or breathing with a future algorithm needs the raw file.
         */
        const val KEEP_RAW_S = 7 * 86_400L

        /** What a sample keeps once its raw recording is gone: every number the app shows, plus the beat-to-beat intervals. */
        fun extract(r: dev.heartline.analytics.SessionResult, into: JSONObject): JSONObject {
            fun n(v: Double?) = v?.takeIf { it.isFinite() } ?: JSONObject.NULL
            val h = r.hrv
            return into.put("hr", n(r.hrBpm)).put("resp", n(r.respiratoryRate)).put("quality", r.signalQuality).put("usable_s", r.usableS)
                .put("rmssd", n(h.rmssdMs)).put("duration_s", r.durationS).put("beats", r.beats)
                .put("hrv", JSONObject().put("mean_rr", n(h.meanRrMs)).put("sdnn", n(h.sdnnMs)).put("rmssd", n(h.rmssdMs)).put("pnn50", n(h.pnn50))
                    .put("sd1", n(h.sd1Ms)).put("sd2", n(h.sd2Ms)).put("lf", n(h.lf)).put("hf", n(h.hf)).put("lf_hf", n(h.lfHf)).put("stress_index", n(h.stressIndex)))
                .put("rhythm", r.rhythm.status).put("ectopic", r.rhythm.ectopicBeats).put("irregularity", n(r.rhythm.irregularity))
                .put("resp_parts", JSONObject().put("riiv", n(r.respiration.riiv)).put("riav", n(r.respiration.riav)).put("rifv", n(r.respiration.rifv)))
                // Beat-to-beat intervals: [seconds from start, interval ms, 1 if ectopic]. Enough to recompute any HRV measure.
                .put("rr", org.json.JSONArray(r.rr.map { org.json.JSONArray().put(Math.round(it.t * 100) / 100.0).put(Math.round(it.rrMs * 10) / 10.0).put(if (it.ectopic) 1 else 0) }))
        }
        /** Which way the watch faced during a sample, as [x, y, z] of gravity (see WristPose), for sleep positions. */
        fun poseJson(p: dev.heartline.analytics.WristPose) = org.json.JSONArray(listOf(p.x, p.y, p.z).map { Math.round(it * 1000) / 1000.0 })

        /** What the overnight engine is doing now, for the Sleep page. */
        val status = MutableStateFlow("Off")
        /** What daytime sampling is doing now, for the Heart page. */
        val dayStatus = MutableStateFlow("Off")
        /** The automatic heart check recording right now, if any, for the app-wide banner and the notification. */
        val running = MutableStateFlow<dev.heartline.app.HrvCheckUi?>(null)
    }

    private val zone get() = ZoneId.systemDefault()
    private var lastSync = 0L
    private var lastSample = 0L
    private var lastDaySample = 0L
    private var lastPose = 0L
    /** A pending retry after a sample that wasn't clean: when, and how many have been used in this slot. */
    private var retryAt = 0L
    private var retriesUsed = 0
    /** Daytime: when the next check may run and how many in a row have failed. Null until first read from the database. */
    private var dayPlan: DayHrvPlan? = null
    /** When the watch's live step count last changed (epoch seconds). */
    @Volatile private var lastStepChange = 0L
    private var asleepNoted: LocalDate? = null
    private var nightStartedBattery: LocalDate? = null

    private var lastPrune = 0L

    /** What one automatic check found: clean or not, and the share of it the wrist was moving. */
    private data class Outcome(val clean: Boolean, val movingShare: Double)

    suspend fun run(): Unit = kotlinx.coroutines.coroutineScope {
        // The watch pushes its step count as it changes; a check waits for a minute with no new steps.
        launch {
            var last: Int? = null
            watch.steps.collect { st -> st?.steps?.let { n -> if (last != null && n != last) lastStepChange = System.currentTimeMillis() / 1000; last = n } }
        }
        while (true) {
            try { tick() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); status.value = "Error: ${e.message}" }
            val now = System.currentTimeMillis() / 1000
            if (now - lastPrune >= 3600) {
                lastPrune = now
                try { prune(now) } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); app.db.events.add("error", "Pruning HRV sample recordings failed: ${e.message}") }
            }
            delay(30_000)
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
        if (!s.enabled && !s.allDay && !s.positions) { status.value = "Off"; dayStatus.value = "Off"; return }
        val now = System.currentTimeMillis() / 1000
        val w = window(s)
        val night = if (s.enabled || s.positions) nightOf(now, w) else null
        if (!s.enabled && !s.positions) status.value = "Off" else if (night == null) status.value = "Waiting for the night window (${clock(w.first)}–${clock(w.second)})"
        // Asleep in the night window: overnight samples. Otherwise (awake, or outside it): daytime samples when still.
        if (night != null && nightTick(now, night, s, w)) { dayStatus.value = if (s.allDay) "Paused while you're asleep" else "Off"; return }
        if (s.allDay) dayTick(now, s, inNightWindow = night != null) else dayStatus.value = "Off"
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
        if (!s.enabled) { status.value = "Asleep; recording positions"; poseTick(now); return true }
        val every = s.nightEvery * 60L
        val retry = retryAt != 0L && now >= retryAt
        if (now - lastSample < every && !retry) {
            if (s.positions) poseTick(now)
            status.value = if (retryAt != 0L) "Asleep; last sample wasn't clean, retrying at ${clock(retryAt)}" else "Asleep; next sample at ${clock(lastSample + every)}"
            return true
        }
        if (busy()) { status.value = "Asleep; waiting for another recording to finish"; return true }
        if (!retry) { lastSample = now; retriesUsed = 0 }
        retryAt = 0L
        val clean = sample(night, SAMPLE, s.nightLen * 60, status, retriesUsed).clean
        lastPose = System.currentTimeMillis() / 1000 // an HRV recording measures the facing too
        if (!clean && retriesUsed < RETRIES) {
            retriesUsed++
            retryAt = System.currentTimeMillis() / 1000 + RETRY_S
            status.value = "Asleep; sample wasn't clean, retry $retriesUsed of $RETRIES at ${clock(retryAt)}"
        }
        return true
    }

    /**
     * Daytime: checks run whenever the watch has counted no steps for a minute, back to back until one is clean, then
     * not again for [OvernightSettings.dayEvery] minutes. Only checks you walked during back off; see [DayHrvPlan].
     */
    private suspend fun dayTick(now: Long, s: OvernightSettings, inNightWindow: Boolean) {
        val every = s.dayEvery * 60L
        if (now - lastSync >= 600) { lastSync = now; runCatching { watch.syncActivity() } }
        val plan = dayPlan ?: DayHrvPlan(nextAt = app.db.health.series(DAY_SAMPLE, now - 86_400.0, now.toDouble())
            .filter { it.value != null }.maxOfOrNull { it.ts }?.let { it + every } ?: 0L).also { dayPlan = it }
        if (now < plan.nextAt) {
            dayStatus.value = when {
                plan.backingOff -> "You were walking during the last check; next try at ${clock(plan.nextAt)}"
                plan.gaveUp -> "${DayHrvPlan.STILL_TRIES} checks weren't clean; trying again from ${clock(plan.nextAt)}"
                else -> "Clean reading taken; next checks from ${clock(plan.nextAt)}"
            }
            return
        }
        if (watch.battery.value?.charging == true) { dayStatus.value = "Waiting: the watch is charging"; return }
        val minutes = analyticsMinutes(app.db.activity.range(now - 6 * 3600.0, now.toDouble()))
        val newest = minutes.lastOrNull()?.takeIf { now - it.ts <= 25 * 60 }
        if (newest?.state == "not_worn") { dayStatus.value = "Waiting: the watch looks off your wrist (minute ${clock(newest.ts)})"; return }
        // In the night window the watch's sleep labels tell daytime checks from overnight ones.
        if (inNightWindow && (newest == null || newest.state.startsWith("sleep_"))) { dayStatus.value = "Waiting for the watch to show you're awake"; return }
        if (now - lastStepChange < DayHrvPlan.STEP_QUIET_S) { dayStatus.value = "Waiting for a minute without steps"; return }
        if (busy()) { dayStatus.value = "Waiting for another recording to finish"; return }
        val out = sample(Instant.ofEpochSecond(now).atZone(zone).toLocalDate(), DAY_SAMPLE, s.dayLen * 60, dayStatus, plan.tries)
        val end = System.currentTimeMillis() / 1000
        // Steps during the check mean you were walking: back off. Wrist movement alone just gets another try.
        val walked = lastStepChange >= now
        val next = if (out.clean) plan.afterClean(end, every) else plan.afterFail(end, walked, every)
        dayPlan = next
        dayStatus.value = when {
            out.clean -> "Clean reading at ${clock(end)}; next checks from ${clock(next.nextAt)}"
            next.gaveUp -> "${DayHrvPlan.STILL_TRIES} checks weren't clean; trying again from ${clock(next.nextAt)}"
            next.nextAt > end -> "You walked during that check; next try at ${clock(next.nextAt)}"
            else -> "That check wasn't clean; trying again after a minute without steps"
        }
    }

    /**
     * Accurate positions: every [POSE_EVERY_S] while asleep, records only the motion sensor for [POSE_LEN_S] seconds and keeps
     * which way the watch faced, when the wrist was still. The recording is deleted afterwards; only the direction is kept.
     */
    private suspend fun poseTick(now: Long) {
        if (now - lastPose < POSE_EVERY_S || busy()) return
        lastPose = now
        var file: java.io.File? = null
        try {
            file = watch.startRaw(setOf(RawSensor.ACCEL))
            delay(POSE_LEN_S * 1000L)
        } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
            app.db.events.add("warn", "Accurate positions: recording failed: ${e.message}")
        } finally { withContext(NonCancellable) { runCatching { watch.stopRaw() } } }
        val f = file ?: return
        val rec = withContext(Dispatchers.Default) { runCatching { readRecording(f) }.getOrNull() }
        val pose = rec?.let { dev.heartline.analytics.stillGravityOf(it.acc, it.start.toLong()) }
        if (pose != null) app.db.health.upsert(listOf(HealthPoint(POSE, pose.ts, null, JSONObject().put("grav", poseJson(pose)).toString())))
        f.delete(); app.db.recordings.delete(f.name)
    }

    /** Records and analyses one sample; true when it was clean. [attempt] is 0 for a slot's first try, then 1, 2 for retries. */
    private suspend fun sample(night: LocalDate, type: String, seconds: Int, state: MutableStateFlow<String>, attempt: Int): Outcome {
        state.value = "Recording a ${seconds / 60}-minute sample" + when {
            attempt == 0 -> ""; type == SAMPLE -> " (retry $attempt of $RETRIES)"; else -> " (try ${attempt + 1} since the last clean one)"
        }
        var file: java.io.File? = null
        try {
            file = watch.startRaw(setOf(RawSensor.PPG, RawSensor.ACCEL))
            running.value = dev.heartline.app.HrvCheckUi(type == SAMPLE, System.currentTimeMillis() / 1000, seconds, attempt)
            delay(seconds * 1000L)
        } finally { running.value = null; withContext(NonCancellable) { runCatching { watch.stopRaw() } } }
        val f = file ?: return Outcome(false, 0.0)
        val recording = withContext(Dispatchers.Default) { readRecording(f) }
        val result = withContext(Dispatchers.Default) { analyzeSession(recording, f.name) }
        val pose = dev.heartline.analytics.gravityOf(recording.acc, result.start.toLong())
        // The share of the check the wrist was moving, by the same 0.05 g limit beat detection uses.
        val moving = result.motion.takeIf { it.isNotEmpty() }?.let { m -> m.count { it.g > 0.05 }.toDouble() / m.size } ?: 0.0
        val ts = result.start.toLong()
        val need = seconds / 2
        val clean = result.hrv.rmssdMs != null && result.signalQuality >= 0.7 && result.usableS >= need
        val movingNote = if (moving >= 0.25) "wrist moving ${(moving * 100).roundToInt()}% of the time, " else ""
        val reason = when {
            result.hrv.rmssdMs == null -> "${movingNote}too few clean beats"
            result.signalQuality < 0.7 -> "${movingNote}signal ${(result.signalQuality * 100).roundToInt()}% (needs 70%)"
            result.usableS < need -> "${movingNote}only ${result.usableS.roundToInt()} s of clean beats (needs $need)"
            else -> null
        }
        val extra = extract(result, JSONObject().put("file", f.name))
            .put("night", night.toString()).put("reason", reason ?: JSONObject.NULL).put("attempt", attempt).put("length_s", seconds)
            .put("moving_share", Math.round(moving * 1000) / 1000.0).apply { pose?.let { put("grav", poseJson(it)) } }.toString()
        app.db.health.upsert(listOf(HealthPoint(type, ts, if (clean) result.hrv.rmssdMs else null, extra)))
        app.db.events.add("info", (if (type == SAMPLE) "Overnight" else "Daytime") + " HRV sample at ${clock(ts)}${if (attempt > 0) " (retry $attempt)" else ""}: " + if (clean) "RMSSD ${result.hrv.rmssdMs?.roundToInt()} ms, " +
            "${result.hrBpm?.roundToInt()} bpm, breathing ${result.respiratoryRate ?: "–"}/min" else "set aside, $reason")
        if (type != SAMPLE) return Outcome(clean, moving)
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
        return Outcome(clean, moving)
    }

    /**
     * Deletes raw recordings of automatic samples older than [KEEP_RAW_S]. A sample saved before its intervals were kept
     * is re-analysed first so its row gets them; a recording that can't be read is left alone.
     */
    private suspend fun prune(now: Long) {
        var deleted = 0; var bytes = 0L
        for (type in listOf(SAMPLE, DAY_SAMPLE)) for (p in app.db.health.series(type, 0.0, (now - KEEP_RAW_S).toDouble())) {
            val j = runCatching { JSONObject(p.extra ?: "{}") }.getOrNull() ?: continue
            val name = j.optString("file").takeIf { it.isNotEmpty() } ?: continue
            val f = java.io.File(rawDir, java.io.File(name).name)
            if (!f.isFile) continue
            if (!j.has("rr") || !j.has("grav")) {
                val rec = runCatching { withContext(Dispatchers.Default) { readRecording(f) } }.getOrNull() ?: continue
                if (!j.has("rr")) extract(runCatching { withContext(Dispatchers.Default) { analyzeSession(rec, f.name) } }.getOrNull() ?: continue, j)
                dev.heartline.analytics.gravityOf(rec.acc, p.ts)?.let { j.put("grav", poseJson(it)) }
                app.db.health.upsert(listOf(HealthPoint(type, p.ts, p.value, j.toString())))
            }
            val size = f.length()
            if (f.delete()) {
                deleted++; bytes += size
                java.io.File(rawDir, f.nameWithoutExtension + ".wav").delete()
                app.db.recordings.delete(f.name)
            }
        }
        if (deleted > 0) app.db.events.add("info", "Deleted $deleted automatic HRV recording${if (deleted > 1) "s" else ""} older than ${KEEP_RAW_S / 86_400} days " +
            "(${bytes / 1_000_000} MB); their HRV, breathing and beat intervals stay saved")
    }

    private fun clock(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    private fun clock(h: Double): String { val m = Math.floorMod((h * 60).roundToInt(), 1440); return "%02d:%02d".format(m / 60, m % 60) }
}
