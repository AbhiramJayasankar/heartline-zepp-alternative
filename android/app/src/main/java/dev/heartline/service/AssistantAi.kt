package dev.heartline.service

import dev.heartline.service.ai.AiSection
import dev.heartline.service.ai.AiStore
import dev.heartline.service.ai.ChatMsg
import dev.heartline.service.ai.ChatRequest
import dev.heartline.service.ai.badAnswer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * The owner's OpenRouter keys (typed into the app, stored in meta) and model; no key = no answers. [apiKey2] and
 * [apiKey3] are keys from other accounts, tried in turn when OpenRouter refuses one (such as its daily free-model limit).
 */
data class AssistantAiConfig(val apiKey: String = "", val model: String = DEFAULT_MODEL, val apiKey2: String = "", val apiKey3: String = "") {
    val keys get() = listOf(apiKey, apiKey2, apiKey3).filter { it.isNotBlank() }.distinct()
    val enabled get() = keys.isNotEmpty()
    fun json(): String = JSONObject().put("key", apiKey).put("model", model).put("key2", apiKey2).put("key3", apiKey3).toString()
    companion object {
        const val KEY = "assistant_ai"
        /** NVIDIA's newest free model on OpenRouter (2026-10-15), with tool calling. */
        const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl:free"
        /**
         * Tried in order when the chosen model fails (rate limits are common on free models). Picked by hand, all with
         * tool calling: the openrouter/free router chose any free model, including a coding model (2026-11-06).
         */
        val FALLBACK_MODELS = listOf("nvidia/nemotron-3.5-lightning:free", "google/gemma-4-31b-it:free")
        /** Defaults saved by earlier versions, moved to [DEFAULT_MODEL]. */
        private val OLD_DEFAULTS = setOf("google/gemma-4-31b-it:free", "openrouter/free")
        fun parse(text: String?): AssistantAiConfig = runCatching {
            val o = JSONObject(text ?: "{}")
            AssistantAiConfig(o.optString("key").trim(), o.optString("model").trim().takeUnless { it.isEmpty() || it in OLD_DEFAULTS } ?: DEFAULT_MODEL,
                o.optString("key2").trim(), o.optString("key3").trim())
        }.getOrDefault(AssistantAiConfig())
    }
}

/** What the brain is told up front: the clock, the latest numbers, the weather, what's set on the watch, a short health glance. */
data class AiContext(val now: ZonedDateTime, val heartRate: String? = null, val stepsToday: Int? = null, val weather: String? = null,
                     val alerts: List<AlexaAlert> = emptyList(), val dataAsOf: String? = null,
                     /** A few lines on today (readiness, last night, heart rate, stress, activity); detail comes from tools. */
                     val health: String? = null,
                     /** The workout capture running in Heartline, if any. */
                     val workout: String? = null)

/** The tools the brain may call and how to run them. */
interface ToolRunner {
    val tools: List<Tool>
    /** The result for the model, as text. An exception is passed to the model as an error, not thrown on. */
    suspend fun run(name: String, args: JSONObject): String
}

/**
 * The Alexa button's brain: the watch assistant's models (AI models, Watch assistant: a main model and two fallbacks, at
 * any provider) answer what was heard, calling Heartline's tools (health data lookups and watch actions) as they need,
 * and the final words are shown on the watch. Every request goes through [AiStore.router], which moves to another key
 * when one runs out of quota and to the next model when a model can't answer; a conversation that fell back stays on
 * that model for its remaining rounds. It throws with a readable message when no model can answer. Keys are never logged.
 */
class AssistantAi(private val store: AiStore) {

    /** The final words, and the tools called on the way (in order). */
    class Reply(val text: String, val toolCalls: List<String>)

    /**
     * Answers [transcript] with [settings] (the owner's prompt, reasoning and limits), filling [trace] as it goes so the
     * app can show every request and tool call, even when it fails. [section] picks whose models answer.
     */
    suspend fun converse(transcript: String, context: AiContext, history: List<Pair<String, String>>, runner: ToolRunner,
                         settings: AlexaSettings = AlexaSettings(), trace: TurnTrace = TurnTrace(), section: AiSection = AiSection.ASSISTANT,
                         system: String? = null): Reply {
        val prompt = system ?: renderPrompt(settings.promptTemplate, context)
        trace.prompt = prompt
        val messages = ArrayList<ChatMsg>()
        val memory = if (settings.memoryTurns == 0) emptyList() else history.takeLast(settings.memoryTurns)
        trace.memory = memory.size
        memory.forEach { (asked, said) -> messages += ChatMsg.User(asked); messages += ChatMsg.Assistant(said) }
        messages += ChatMsg.User(transcript)
        val called = ArrayList<String>()
        val effort = settings.reasoning.takeIf { it in listOf("low", "medium", "high") }
        var level = 0
        for (round in 1..settings.maxRounds) {
            val req = ChatRequest(prompt, messages.toList(), runner.tools, settings.temperature, settings.maxTokens, reasoning = effort,
                reasoningMaxTokens = settings.reasoningMaxTokens)
            val t0 = System.currentTimeMillis()
            val out = store.router.run(section, from = level) { t ->
                store.api.chat(t.provider, t.key, t.model, req, t.timeoutMs).also { r ->
                    if (r.text == null && r.calls.isEmpty())
                        throw badAnswer(if (r.finish == "length") "${t.model} ran out of tokens before it answered" else "${t.model} gave no answer")
                }
            }
            val reply = out.value
            level = out.target.level
            val ms = System.currentTimeMillis() - t0
            lastModel = reply.model
            lastKey = out.target.keyIndex + 1
            fun round(calls: List<ToolCallLog>, text: String?) = RoundLog("${out.target.provider.name}: ${reply.model}", lastKey, effort ?: "off", ms,
                reply.tokensIn, reply.tokensOut, calls, text)
            if (reply.calls.isNotEmpty()) {
                messages += reply.turn
                val logs = ArrayList<ToolCallLog>()
                for (call in reply.calls) {
                    called += call.name
                    val c0 = System.currentTimeMillis()
                    val result = try { runner.run(call.name, call.args) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    logs += ToolCallLog(call.name, call.args.toString(), result, System.currentTimeMillis() - c0)
                    messages += ChatMsg.ToolResult(call.id, call.name, result)
                }
                trace.rounds += round(logs, reply.text)
                continue
            }
            trace.rounds += round(emptyList(), reply.text)
            return Reply(reply.text!!.trim(), called)
        }
        error("${lastModel ?: "the model"} kept calling tools without answering (${settings.maxRounds} rounds)")
    }

    /** The model that gave the last answer. */
    var lastModel: String? = null
        private set

    /** Which of its provider's keys (from 1) answered last. */
    var lastKey = 1
        private set

    companion object {
        /** What each placeholder in the instructions becomes. */
        val PLACEHOLDERS = linkedMapOf(
            "{NOW}" to "the date, time and time zone",
            "{HEART_RATE}" to "the latest heart rate and when it was measured",
            "{STEPS}" to "steps today",
            "{SYNCED_UP_TO}" to "the time of the newest data synced from the watch",
            "{ALERTS}" to "alarms, timers and reminders set by voice, with their ids",
            "{WORKOUT}" to "the workout capture running in Heartline",
            "{HEALTH}" to "a short glance at today: readiness, last night, heart rate, stress, activity (one line each)",
        )

        val DEFAULT_PROMPT = """
            You are the voice assistant on the user's Amazfit smartwatch, answering what they say to its Alexa button. You run inside Heartline, the phone app that reads the watch's health data.

            Now: {NOW}.
            Latest heart rate: {HEART_RATE}.
            Steps today: {STEPS} (watch data synced up to {SYNCED_UP_TO}).
            Timers, reminders and older voice alarms set by voice: {ALERTS}.
            Workout capture in Heartline: {WORKOUT}.
            Today at a glance:
            {HEALTH}

            Use the tools. For questions about readiness, sleep, heart rate, HRV, stress, activity, blood oxygen, temperature, trends, workouts, the AI insights or their quick notes, call get_info with the topic (several times if useful) and answer from what it returns, with a short analysis when they ask how something was.

            The other tools each take an action: alarms (list, set, delete the watch's alarms; list first when they mean an existing one), timers_and_reminders, show_on_watch (a table, the weather card, things to ask, or long text as notifications), recording (a heart check or a workout capture), phone (media and the ringer) and app (sync the watch, add a note). When they ask what the weather is like, show the weather card; get_info weather is for a specific question such as whether it will rain tomorrow.

            For news, scores, prices, recent events or any fact you aren't sure of, or for research, call web_search as much as you want and answer from what it finds.

            Never say you did something without calling its tool.

            For an alarm or reminder time without am/pm, pick the next occurrence after now; for "in N minutes" use in_seconds.

            Your reply is shown on the watch as plain text: markdown isn't drawn (no bold, headings or tables), but new lines and blank lines are, and so are every language's script and symbols such as ° ± ½ × ÷ ≤ ² € £ © → ↑ ▲ ■ ● ○ ① ✓ ★ ♥ ♪ (not ✔ ✗ ❤ • or ₹: write Rs).

            Whenever the answer is several items that each have a value or detail (a ranking or top 10, heights, prices, scores, a schedule, times, a forecast by day, steps with timings), call show_on_watch with a table, even if they say "list" and not "table"; only items with nothing beside them (tips, a shopping list) go in the text, one per line starting with a fitting emoji or symbol, "- " or a number.

            The watch draws only these emoji: ${WatchAssistant.EMOJI_SHOWN}, and these symbols: ${WatchAssistant.SYMBOLS_SHOWN} ° → ↑ ↓ ▲ ▼ ■ ● ○ ① ② ③; anything else vanishes. Use them as much as you can, they make the small screen lively and easy to scan. Never use any other emoji.

            Keep it short by default unless they ask for something long, like a story, an analysis or a detailed explanation: then write all of it, in as much detail as requested. Length is never a limit.

            Put messages in show_on_watch notifications when they ask for answers as a notification.

            Take as many tool calls as a thorough answer needs.

            Follow-ups: after your reply to the user's query, the watch opens its mic again automatically for their answer only when your reply ends with the tag [listen]. Heartline removes the tag, so they never see it (otherwise the user has to press the button again to reopen the mic). Add it, as the very last thing, when you can't go on without their answer, for example: a question you must ask to do what they want (which alarm? what time?), a choice between options you just gave, their turn in a game or quiz, or the next line of a joke they answer back. Putting a question last, just before the tag, lets it stay on the screen while they answer.

            Leave the tag out if the answer is complete. The tag goes only at the end of your reply.
        """.trimIndent()

        /** [template] with its placeholders filled from [c]. */
        fun renderPrompt(template: String, c: AiContext): String = template
            .replace("{NOW}", "${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id})")
            .replace("{HEART_RATE}", c.heartRate ?: "unknown")
            .replace("{STEPS}", c.stepsToday?.toString() ?: "unknown")
            .replace("{SYNCED_UP_TO}", c.dataAsOf ?: "unknown")
            .replace("{ALERTS}", if (c.alerts.isEmpty()) "none" else c.alerts.joinToString("; ") { "id ${it.id}: ${it.describe(c.now)}" })
            .replace("{WORKOUT}", c.workout ?: "not running")
            .replace("{HEALTH}", c.health?.lines()?.filter { it.isNotBlank() }?.joinToString("\n") { "- $it" } ?: "- not available")

        fun systemPrompt(c: AiContext): String = renderPrompt(DEFAULT_PROMPT, c)
    }
}
