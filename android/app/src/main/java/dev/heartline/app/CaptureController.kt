package dev.heartline.app

import dev.heartline.data.HeartlineDatabase
import dev.heartline.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class CaptureController(
    private val scope: CoroutineScope, private val db: HeartlineDatabase,
    private val service: StateFlow<WatchService?>, private val ensureService: suspend () -> WatchService,
) {
    val run = MutableStateFlow<CaptureRunUi?>(null)
    val items = MutableStateFlow<List<CaptureItemUi>>(emptyList())
    private var starting = false
    private var startJob: Job? = null
    val running: Boolean get() = starting || service.value?.captureRunner?.active == true
    init {
        scope.launch { service.collectLatest { s -> if (s != null) s.captureRunner.run.collect { run.value = it } } }
        scope.launch { db.meta.observeAll().collect { meta ->
            items.value = withContext(Dispatchers.Default) {
                meta.filterKeys { it.startsWith(CAPTURE_SESSION) }.values.mapNotNull { value ->
                    runCatching {
                        val session = captureSession(value ?: return@runCatching null)
                        val result = meta[CAPTURE_RESULT + session.id]?.let { runCatching { captureFromJson(it) }.getOrNull() }
                        session.captureItem(result, System.currentTimeMillis() / 1000)
                    }.getOrNull()
                }.sortedByDescending { it.start }
            }
        } }
    }
    fun start(label: String, minutes: Int) {
        if (running) return
        starting = true
        val pending = CaptureRunUi(label.trim().ifBlank { "Workout" }, System.currentTimeMillis() / 1000, minutes * 60, null, 0, 0)
        run.value = pending
        startJob = scope.launch {
            try { ensureService().captureRunner.start(label, minutes) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { run.value = (run.value ?: pending).copy(error = e.message ?: "Unable to start capture") }
            finally { starting = false }
        }
    }
    fun stop() {
        if (starting) { startJob?.cancel(); starting = false; run.value = null }
        service.value?.captureRunner?.stop()
    }
    suspend fun detail(id: String): CaptureAnalysisUi? {
        val session = db.meta.get(CAPTURE_SESSION + id)?.let { runCatching { captureSession(it) }.getOrNull() }
        db.meta.get(CAPTURE_RESULT + id)?.takeIf { session != null && db.meta.get(CAPTURE_ENGINE + id) == captureEngineKey(db, session).first }
            ?.let { return withContext(Dispatchers.Default) { captureFromJson(it) } }
        return ensureService().captureRunner.detail(id)
    }
    fun delete(id: String) { scope.launch {
        try { ensureService().captureRunner.delete(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { db.events.add("error", "Unable to delete capture: ${e.message}") }
    } }
}
