package dev.heartline.app

import dev.heartline.data.HeartlineDatabase
import dev.heartline.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

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
            val log = gymLogFromJson(meta[GYM_LOG_KEY])
            val facts = HashMap<String, TitleFacts>()
            items.value = withContext(Dispatchers.Default) {
                meta.filterKeys { it.startsWith(CAPTURE_SESSION) }.values.mapNotNull { value ->
                    runCatching {
                        val session = captureSession(value ?: return@runCatching null)
                        val result = meta[CAPTURE_RESULT + session.id]?.let { runCatching { captureFromJson(it) }.getOrNull() }
                        val item = session.captureItem(result, System.currentTimeMillis() / 1000)
                        if (result != null && genericLabel(item.label)) facts[item.id] = titleFacts(result, log)
                        // The AI's name once it has one; until then the local name, worked out fresh so rule fixes show at once.
                        val stored = meta[CAPTURE_TITLE + item.id]?.let { runCatching { JSONObject(it) }.getOrNull() }
                        val name = facts[item.id]?.let { f -> stored?.takeIf { it.optBoolean("ai") && it.optString("basis") == f.basis }?.optString("title") ?: localTitle(f) }
                        name?.takeIf { it.isNotBlank() }?.let { item.copy(label = it) } ?: item
                    }.getOrNull()
                }.sortedByDescending { it.start }
            }
            nameCaptures(facts, meta)
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
        val a = db.meta.get(CAPTURE_RESULT + id)?.takeIf { session != null && db.meta.get(CAPTURE_ENGINE + id) == captureEngineKey(db, session).first }
            ?.let { withContext(Dispatchers.Default) { captureFromJson(it) } } ?: ensureService().captureRunner.detail(id)
        return a?.let { r -> titleOf(db.meta.get(CAPTURE_TITLE + id))?.takeIf { genericLabel(r.label) }?.let { r.copy(label = it) } ?: r }
    }

    private fun titleOf(json: String?): String? = json?.let { runCatching { JSONObject(it).optString("title") }.getOrNull() }?.takeIf { it.isNotBlank() }

    private var naming: Job? = null
    /**
     * Gives each capture still labelled "Gym" or "Workout" a short name such as "Back and biceps day": at once from its
     * exercises, then from the AI with the owner's Gemini key. A name is redone only when what it was made from changes
     * (a log imported later, say), and a failed AI request is retried at most every 2 hours, to spare the daily quota.
     */
    private fun nameCaptures(facts: Map<String, TitleFacts>, meta: Map<String, String?>) {
        if (naming?.isActive == true) return
        val now = System.currentTimeMillis() / 1000
        val todo = facts.filter { (id, f) ->
            val j = meta[CAPTURE_TITLE + id]?.let { runCatching { JSONObject(it) }.getOrNull() }
            j == null || j.optString("basis") != f.basis || (!j.optBoolean("ai") && now - j.optLong("tried") > 2 * 3600)
        }
        if (todo.isEmpty()) return
        naming = scope.launch(Dispatchers.IO) {
            val key = SpeechConfig.parse(db.meta.get(SpeechConfig.KEY)).geminiKey
            val model = InsightsSettings.parse(db.meta.get(InsightsSettings.KEY)).geminiModel
            // Newest first, a few per pass: each one written triggers the next pass.
            for ((id, f) in todo.entries.sortedByDescending { it.value.start }.take(4)) {
                val old = meta[CAPTURE_TITLE + id]?.let { runCatching { JSONObject(it) }.getOrNull() }
                // A name from the exercises right away; the AI's, which can take a minute, replaces it.
                if (old == null || old.optString("basis") != f.basis)
                    db.meta.set(CAPTURE_TITLE + id, JSONObject().put("title", localTitle(f)).put("basis", "").put("ai", false).put("tried", 0).toString())
                // Nothing to go on (an empty capture): the local name is all there is, and it spares the quota.
                val empty = f.exercises.isEmpty() && f.sets == 0 && f.watchWorkouts.isEmpty() && f.cardio.isEmpty()
                suspend fun ask(m: String = model) = parseTitle(GeminiText().generate(key, m, TITLE_SYSTEM, f.prompt(), json = true, temperature = 0.2, maxTokens = 1024).text)
                // When the main model is overloaded or slow, the newest flash-lite model the key offers: naming is an easy job.
                suspend fun lite(): String? = runCatching { GeminiText().listModels(key).firstOrNull { it.contains("flash-lite") } }.getOrNull()?.let { ask(it) }
                val ai = if (key.isBlank() || empty) null else runCatching {
                    // A 503 ("high demand") is usually gone a few seconds later: one more try before giving up.
                    try { ask() } catch (e: Exception) {
                        val busy = e.message?.let { m -> "503" in m || "timeout" in m.lowercase() || "timed out" in m.lowercase() } == true
                        if (busy) lite() ?: throw e else throw e
                    }
                }.onFailure { db.events.add("warn", "Naming a workout capture with AI failed: ${it.message}") }.getOrNull()
                val title = ai ?: old?.optString("title")?.takeIf { old.optString("basis") == f.basis && old.optBoolean("ai") && it.isNotBlank() } ?: localTitle(f)
                db.meta.set(CAPTURE_TITLE + id, JSONObject().put("title", title).put("basis", f.basis).put("ai", ai != null || empty).put("tried", now).toString())
            }
        }
    }
    fun delete(id: String) { scope.launch {
        try { ensureService().captureRunner.delete(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { db.events.add("error", "Unable to delete capture: ${e.message}") }
    } }
}
