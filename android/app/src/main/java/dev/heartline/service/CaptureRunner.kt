package dev.heartline.service

import dev.heartline.analytics.*
import dev.heartline.app.*
import dev.heartline.ble.WatchConnection
import dev.heartline.data.AppSettings
import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.RawPacket
import dev.heartline.protocol.RawSensor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

// HuamiSupport.setRawSensor / Huami2021Service.CMD_RAW_SENSOR_START_* use mask 0x03.
// Add this watch's verified gyro(0x10) + mag(0x40) bits: capture mask = 0x53. See WORKOUT_CAPTURE.md.
internal val CAPTURE_SENSORS = setOf(RawSensor.PPG, RawSensor.ACCEL, RawSensor.GYRO, RawSensor.MAG)

/** Owned exclusively by WatchService. UI cancellation/unbinding cannot cancel capture or analysis. */
class CaptureRunner(
    private val scope: CoroutineScope, private val db: HeartlineDatabase, private val watch: WatchConnection,
    private val settings: AppSettings, private val folder: File,
) {
    val run = MutableStateFlow<CaptureRunUi?>(null)
    @Volatile var active = false
        private set
    private val lock = Mutex()
    private var session: CaptureSession? = null
    private var ignoredFile: String? = null
    private var stopping = false
    private val initialized = CompletableDeferred<Unit>()
    private val selection = CAPTURE_SENSORS

    init {
        launchTask {
            try { lock.withLock {
                // A sticky service restart can resume an unfinished capture. Finished uncached sessions are retried.
                val stored = db.meta.all().filterKeys { it.startsWith(CAPTURE_SESSION) }.values.mapNotNull {
                    it?.let { value -> runCatching { captureSession(value) }.getOrNull() }
                }.sortedByDescending { it.start }
                stored.firstOrNull { it.end == null }?.let {
                    val lastData = it.files.mapNotNull { name -> captureFile(folder, name).takeIf { file -> file.isFile }?.lastModified()?.div(1000) }.maxOrNull() ?: it.start
                    session = it.availability(lastData, false); active = true; publish()
                    if (it.due(now())) finish("The service restarted after the planned end; recording may be incomplete.", it.start + it.plannedS)
                    else { save(); if (watch.ready.value) resumeStreams() }
                }
                for (s in stored.filter { it.end != null && (db.meta.get(CAPTURE_RESULT + it.id) == null || db.meta.get(CAPTURE_ENGINE + it.id) != captureEngineKey(db, it).first) }) {
                    try { analyze(s) } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("warn", "Capture ${s.id} needs analysis retry: ${e.message}") }
                }
            } } finally { initialized.complete(Unit) }
        }
        launchTask {
            watch.raw.collect { raw -> lock.withLock {
                val s = session ?: return@withLock
                if (stopping) return@withLock
                val file = raw.file?.name?.takeIf { it != ignoredFile || it in s.files }
                val next = s.availability(now(), raw.streaming && raw.sensors == selection, file)
                if (next != s) { session = next; save() }
                publish()
            } }
        }
        launchTask {
            watch.rawUpdates.collect { update ->
                // Same serial service dispatcher as the BLE receiver; keep this hot path free of database writes.
                session?.let { s -> if (!stopping) session = s.copy(samples = s.samples + captureSampleCount(update.packet)) }
            }
        }
        launchTask {
            watch.ready.collect { ready -> if (ready) lock.withLock {
                if (session != null && !stopping) {
                    try { resumeStreams() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); session = session?.copy(error = "Waiting to resume: ${e.message}"); save(); publish() }
                }
            } }
        }
        launchTask {
            while (currentCoroutineContext().isActive) {
                delay(1000)
                lock.withLock {
                    session?.let { s ->
                        publish()
                        if (s.due(now())) finish()
                        else if (s.elapsed(now()) % 10 == 0) save()
                    }
                }
            }
        }
    }

    /** [forFriend]: someone else wears the watch; the capture and its time are marked as theirs ([Friends]). */
    fun start(label: String, minutes: Int, forFriend: Boolean = false) {
        require(minutes in 1..720) { "Capture duration must be 1–720 minutes" }
        check(initialized.isCompleted) { "Capture history is still loading; try again shortly" }
        check(!active) { "A workout capture is already running" }
        check(!watch.raw.value.streaming) { "Stop the current raw recording or heart check first" }
        check(watch.ready.value) { "Connect the watch before starting a capture" }
        active = true
        run.value = CaptureRunUi(label.trim().ifBlank { "Workout" }, now(), minutes * 60, null, 0, 0)
        scope.launch { try { lock.withLock {
            ignoredFile = watch.raw.value.file?.name
            session = CaptureSession(UUID.randomUUID().toString(), run.value!!.label, now(), minutes * 60,
                restoreHr = watch.heartRate.value.streaming).availability(now(), false)
            save()
            session?.let { s -> if (forFriend) FriendStore.update(db) { it.with(FriendMark(FriendMark.Kind.CAPTURE, s.id, s.start, s.start + s.plannedS), true) } }
            try { resumeStreams() }
            catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
                // A startup link loss is resumable too. A connected command failure leaves a reviewable partial capture.
                if (!watch.ready.value) { session = session?.copy(error = "Watch disconnected; capture will resume after reconnect"); save(); publish() }
                else finish("Capture startup failed: ${e.message}")
            }
        } } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
            active = session != null
            run.update { it?.copy(error = "Unable to start capture: ${e.message}") }
            db.events.add("error", "Unable to start capture: ${e.message}")
        } }
    }

    fun stop() { launchTask { lock.withLock { if (session == null) run.value = null else finish() } } }

    private suspend fun resumeStreams() {
        if (session == null) return
        if (!watch.raw.value.streaming) watch.startRaw(selection)
        if (!watch.heartRate.value.streaming) watch.setLiveHr(true)
        session = session?.availability(now(), true, watch.raw.value.file?.name)?.copy(error = null)
        save(); publish()
    }

    private suspend fun finish(error: String? = null, endAt: Long = now()) {
        val current = session ?: return
        stopping = true
        try {
            // Both commands also clear wanted-stream flags while disconnected, preventing a post-stop restart.
            val failures = ArrayList<String>()
            try { watch.stopRaw() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); failures += "Stopping motion: ${e.message}" }
            try { watch.setLiveHr(current.restoreHr) } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); failures += "Restoring live HR: ${e.message}" }
            val s = (session ?: current).finish(endAt).copy(error = (listOfNotNull(error) + failures).takeIf { it.isNotEmpty() }?.joinToString(" "))
            session = s; save()
            // A friend's capture covers the time it really ran, not the time planned.
            if (FriendStore.read(db).isFriend(FriendMark.Kind.CAPTURE, s.id))
                FriendStore.update(db) { it.with(FriendMark(FriendMark.Kind.CAPTURE, s.id, s.start, s.end ?: endAt), true) }
            try { analyze(s) }
            catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
                session = s.copy(error = "Capture analysis failed: ${e.message}"); save()
                db.events.add("error", "Capture analysis failed: ${e.message}")
            }
            run.value = session?.error?.let { session!!.captureProgress(now(), null) }
        } finally { session = null; stopping = false; active = false }
    }

    private suspend fun analyze(s: CaptureSession) {
        val hr = db.heartRate.range(s.start.toDouble(), (s.end ?: now()).toDouble()).map { it.ts to it.bpm }
        // Include workouts starting before capture. JSON holds end; filter/clip after loading.
        val activities = db.health.workoutSummariesOverlapping(s.start.toDouble(), (s.end ?: now()).toDouble()).mapNotNull {
            captureActivity(it, s.start.toDouble(), (s.end ?: now()).toDouble())
        }
        val p = settings.profile.value
        val (key, logged) = captureEngineKey(db, s)
        val rest = captureRest(db)
        val result = withContext(Dispatchers.Default) {
            val parts = readCaptureFiles(folder, s)
            val analysis = analyzeCapture(mergeCapture(s, parts), hr,
                Profile(p.age, p.sex?.name?.lowercase(), p.weightKg, p.heightCm, p.maxHr, p.restingHr), s.label, activities, logged,
                hrRest = rest?.toDouble())
            val notes = analysis.notes + s.gaps.filter { (it.end ?: s.end ?: s.start) > it.start }.map {
                "Raw stream gap from ${it.start - s.start} to ${(it.end ?: s.end ?: it.start) - s.start} seconds after capture start."
            } + listOfNotNull(s.error) + if (parts.size < s.files.size) listOf("Some recording files are missing; analysis uses the remaining data.") else emptyList()
            analysis.copy(notes = notes).captureUi(s.id).captureJson()
        }
        db.meta.set(CAPTURE_RESULT + s.id, result)
        db.meta.set(CAPTURE_ENGINE + s.id, key)
    }

    suspend fun detail(id: String): CaptureAnalysisUi? = lock.withLock {
        val s = db.meta.get(CAPTURE_SESSION + id)?.let(::captureSession)
        db.meta.get(CAPTURE_RESULT + id)?.takeIf { s != null && db.meta.get(CAPTURE_ENGINE + id) == captureEngineKey(db, s).first }
            ?.let(::captureFromJson) ?: s?.let { if (it.end == null) null else { analyze(it); db.meta.get(CAPTURE_RESULT + id)?.let(::captureFromJson) } }
    }

    fun delete(id: String) { scope.launch { try { lock.withLock {
        check(session?.id != id) { "Stop the capture before deleting it" }
        // Its AI name goes too, even when the session itself is already gone.
        db.meta.delete(dev.heartline.app.CAPTURE_TITLE + id); db.meta.delete(dev.heartline.app.CAPTURE_COACH + id)
        FriendStore.update(db) { it.without(FriendMark.Kind.CAPTURE, id) }
        val s = db.meta.get(CAPTURE_SESSION + id)?.let(::captureSession) ?: return@withLock
        for (name in s.files) {
            val file = captureFile(folder, name)
            check(!file.exists() || file.delete()) { "Unable to delete recording $name" }
            db.recordings.delete(name)
        }
        db.meta.delete(CAPTURE_RESULT + id); db.meta.delete(CAPTURE_SESSION + id); db.meta.delete(CAPTURE_ENGINE + id)
    } } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); db.events.add("error", "Unable to delete capture: ${e.message}") } } }

    private suspend fun save() { session?.let { db.meta.set(CAPTURE_SESSION + it.id, it.json()) } }
    private fun launchTask(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
            run.update { it?.copy(error = "Capture task failed: ${e.message}") }
            runCatching { db.events.add("error", "Capture task failed: ${e.message}") }
        }
    }
    private fun publish() {
        val s = session ?: return
        val h = watch.heartRate.value
        run.value = s.captureProgress(now(), h.bpm?.takeIf { h.streaming && now() - (h.ts ?: 0) <= 5 })
    }
    private fun now() = System.currentTimeMillis() / 1000
}

internal fun captureSampleCount(packet: RawPacket?): Int = when (packet) {
    is RawPacket.Ppg -> packet.values.size
    is RawPacket.Accel -> packet.samples.size
    is RawPacket.Gyro -> packet.samples.size
    is RawPacket.Mag -> packet.samples.size
    else -> 0
}
