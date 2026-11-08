package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.WatchWeather
import dev.heartline.protocol.WeatherDay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchAssistantTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 11, 6, 15, 40, 0, 0, zone)
    private val saved = HashMap<String, String>()
    private val store = object : AssistantStore {
        override suspend fun get(key: String) = saved[key]
        override suspend fun set(key: String, value: String) { saved[key] = value }
    }
    private var weatherError: String? = null
    private val weather = object : AssistantWeather {
        override suspend fun latest(): FetchedWeather {
            weatherError?.let { error(it) }
            return FetchedWeather(0, WatchWeather(0, 19_800, "Noida", 24, 2, listOf(WeatherDay(2, 29, 19), WeatherDay(61, 26, 18), WeatherDay(0, 28, 17), WeatherDay(3, 27, 18)), 60, 8.0), false)
        }
        override val fahrenheit = false
    }
    private var key = "sk-test"
    private val did = ArrayList<String>()
    private var running: String? = null
    private val actions = object : AssistantActions {
        override fun startWorkout(label: String, minutes: Int) { check(running == null) { "A workout capture is already running" }; running = label; did += "start $label $minutes" }
        override fun stopWorkout(): String? = running?.let { did += "stop"; running = null; "$it after 12 minutes" }
        override fun sync() { did += "sync" }
        val alarms = mutableListOf(WatchAlarm(0, true, 7, 0, setOf(java.time.DayOfWeek.MONDAY), false), WatchAlarm(10, false, null, null, emptySet(), true))
        override suspend fun watchAlarms() = alarms.toList()
        override suspend fun setWatchAlarm(slot: Int?, hour: Int, minute: Int, days: Set<java.time.DayOfWeek>, on: Boolean): Int {
            did += "set $slot %02d:%02d ${days.sorted()} $on".format(hour, minute); return slot?.takeIf { it < 10 } ?: 1
        }
        override suspend fun switchWatchAlarm(slot: Int, on: Boolean) { did += "switch $slot $on" }
        override suspend fun deleteWatchAlarm(slot: Int) { check(alarms.any { it.slot == slot }) { "there is no alarm $slot on the watch" }; did += "delete $slot" }
        override suspend fun sendToWatch(title: String, parts: List<String>): Int { did += "send $title ${parts.size}"; return parts.size }
        override suspend fun media(action: String, percent: Int?): String { did += "media $action $percent"; return "Spotify playing" }
        override suspend fun ringer(mode: String?, percent: Int?): String { did += "ringer $mode $percent"; return "ringer ${mode ?: "normal"}" }
        override suspend fun addNote(text: String) { did += "note $text" }
    }
    private val data = object : DataTools {
        override val tools = listOf(Tool("get_sleep", "Sleep for a night.", JSONObject().put("type", "object").put("properties", JSONObject())))
        override suspend fun run(name: String, args: JSONObject) = if (name == "get_sleep") "Asleep 4 h 49 min, deep 32 min" else null
    }

    /** The brain's replies, one per OpenRouter request; and every request body sent. */
    private val replies = ArrayDeque<String>()
    private val bodies = ArrayList<JSONObject>()
    private fun say(text: String) = JSONObject().put("model", "m").put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", text)))).toString()
    private fun call(name: String, args: String = "{}") = JSONObject().put("model", "m").put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c${bodies.size}").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args))))))).toString()
    private fun brain(vararg r: String) { replies.clear(); replies.addAll(r) }
    private fun system() = bodies.last().getJSONArray("messages").getJSONObject(0).getString("content")
    /** The tool results the model was given in the last request. */
    private fun toolResults(): List<String> = bodies.last().getJSONArray("messages").let { m -> (0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("role") == "tool" }.map { it.getString("content") } }

    private fun assistant() = WatchAssistant(
        AssistantAi { _, _, body -> bodies += JSONObject(body); replies.removeFirstOrNull() ?: say("(no more replies)") },
        { AssistantAiConfig(key) }, { WatchAssistant.Facts("72 bpm at 3:39 PM", 4200, health = "Readiness: 62 out of 100") }, weather, store, actions, data, { now }, { alexa })

    private var alexa = AlexaSettings()

    @Test fun theOwnersSettingsShapeTheRequest() = runBlocking {
        alexa = AlexaSettings(prompt = "Be brief. Now {NOW}. Steps {STEPS}.", disabledTools = setOf("timers_and_reminders"),
            toolDescriptions = mapOf("get_sleep" to "Sleep data."), fallbackModels = listOf("x/y"), temperature = 0.7, maxTokens = 900, memoryTurns = 0)
        brain(call("timers_and_reminders", """{"action":"timer","seconds":60}"""), say("Done."))
        val a = assistant().answer("Timer for a minute")
        assertEquals("Be brief. Now Friday 6 November 2026, 15:40 (Asia/Kolkata). Steps 4200.", system())
        val body = bodies.first()
        val tools = body.getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function") } }
        assertFalse(tools.any { it.getString("name") == "timers_and_reminders" })
        assertEquals("Sleep data.", tools.first { it.getString("name") == "get_sleep" }.getString("description"))
        assertEquals(listOf(AssistantAiConfig.DEFAULT_MODEL, "x/y"), body.getJSONArray("models").let { m -> (0 until m.length()).map { m.getString(it) } })
        assertEquals(0.7, body.getDouble("temperature"), 0.0)
        assertEquals(900, body.getInt("max_tokens"))
        // A tool that's off isn't run even if the model calls it.
        assertEquals(listOf("Error: the timers_and_reminders tool is turned off in Heartline"), toolResults())
        assertTrue(a.frames.isEmpty())
    }

    @Test fun theTraceRecordsEveryRequestAndToolCall() = runBlocking {
        brain(call("get_sleep", """{"date":"2026-11-06"}"""), say("You slept 4 h 49 min."))
        val trace = assistant().answer("How did I sleep?").trace!!
        assertTrue(trace.prompt.contains("Steps today: 4200"))
        assertEquals(2, trace.rounds.size)
        val call = trace.rounds[0].calls.single()
        assertEquals("get_sleep", call.name)
        assertEquals("2026-11-06", JSONObject(call.args).getString("date"))
        assertEquals("Asleep 4 h 49 min, deep 32 min", call.result)
        assertEquals("You slept 4 h 49 min.", trace.rounds[1].text)
        assertEquals("off", trace.rounds[1].reasoning)
    }

    @Test fun reasoningCanBeAskedFor() = runBlocking {
        alexa = AlexaSettings(reasoning = "medium", reasoningMaxTokens = 9000)
        brain(say("Hi."))
        assistant().answer("Hello")
        assertEquals("medium", bodies.last().getJSONObject("reasoning").getString("effort"))
        assertEquals(9000, bodies.last().getInt("max_tokens"))
    }

    @Test fun answersAreShownAsText() = runBlocking {
        brain(say("Your heart rate was 72 bpm."))
        val a = assistant().answer("What's my heart rate?")
        assertEquals("Your heart rate was 72 bpm.", a.text)
        assertTrue(a.frames.isEmpty())
        assertFalse(a.listenAgain)
        listOf("Steps today: 4200", "show_on_watch", "- Readiness: 62 out of 100").forEach { assertTrue(it, system().contains(it)) }
        val tools = bodies.last().getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertEquals(listOf("get_sleep") + WatchAssistant.ACTION_TOOLS.map { it.name }, tools)
    }

    @Test fun healthDetailComesFromTools() = runBlocking {
        brain(call("get_sleep", """{"date":"2026-11-06"}"""), say("You slept 4 h 49 min with little deep sleep."))
        val a = assistant().answer("Analyse my sleep")
        assertEquals("You slept 4 h 49 min with little deep sleep.", a.text)
        assertEquals(listOf("Asleep 4 h 49 min, deep 32 min"), toolResults())
        assertTrue(a.summary.endsWith("(looked up get_sleep)"))
    }

    @Test fun aQuestionBackListensAgain() = runBlocking {
        brain(say("Knock knock."))
        assertFalse(assistant().answer("Tell me a joke").listenAgain)
        brain(say("Knock knock. Who's there?"))
        assertTrue(assistant().answer("Tell me a joke").listenAgain)
    }

    @Test fun failuresAreToldToTheWatch() = runBlocking {
        val a = WatchAssistant(AssistantAi { _, _, _ -> throw AssistantAi.OpenRouterError("OpenRouter 429: Rate limit exceeded") },
            { AssistantAiConfig(key) }, { WatchAssistant.Facts() }, weather, store, actions, data, { now })
        assertEquals("OpenRouter isn't working: OpenRouter 429: Rate limit exceeded", a.answer("hello").text)
        key = ""
        assertTrue(assistant().answer("hello").text!!.startsWith("Add an OpenRouter key"))
        assertEquals("I didn't hear any words. Try again.", assistant().answer(" ").text)
    }

    @Test fun timersGoToTheWatchAndCanBeCancelled() = runBlocking {
        val a = assistant()
        brain(call("timers_and_reminders", """{"action":"timer","seconds":300}"""), say("Timer set for 5 minutes."))
        val timer = a.answer("Timer for 5 minutes")
        assertEquals("Timer set for 5 minutes.", timer.text)
        assertEquals(listOf("Done: timer for 5 minutes, ringing at 15:45"), toolResults())
        assertArrayEquals(Alexa.timer("heartline-timer-${now.toEpochSecond()}-0", 0, 5, 0), timer.frames.single())
        brain(call("timers_and_reminders", """{"action":"cancel","ids":[1]}"""), say("Cancelled your timer."))
        val cancel = a.answer("Cancel my timer")
        assertTrue(system().contains("id 1: timer ringing at 15:45"))
        assertArrayEquals(Alexa.deleteAlert(Alexa.ALERT_TIMER, "heartline-timer-${now.toEpochSecond()}-0"), cancel.frames.single())
        assertEquals(0, WatchAssistant.parseAlerts(saved[WatchAssistant.ALERTS]).size)
        brain(call("timers_and_reminders", """{"action":"cancel","ids":[9]}"""), say("Nothing to cancel."))
        a.answer("Cancel it")
        assertTrue(toolResults().last().startsWith("Error: none of those ids are set"))
    }

    @Test fun aClaimedActionWithoutItsToolIsAskedAgain() = runBlocking {
        val a = assistant()
        brain(call("timers_and_reminders", """{"action":"reminder","label":"Drink water","in_seconds":120}"""), say("Reminder set for 15:42."))
        a.answer("Remind me to drink water in two minutes")
        // The model says it cancelled without calling the tool; told nothing happened, it calls the tool.
        brain(say("Cancelled."), call("timers_and_reminders", """{"action":"cancel","ids":[1]}"""), say("Cancelled your water reminder."))
        val cancel = a.answer("Cancel my water reminder")
        assertEquals("Cancelled your water reminder.", cancel.text)
        assertTrue(WatchAssistant.parseAlerts(saved[WatchAssistant.ALERTS]).isEmpty())
        assertEquals(3, cancel.trace!!.rounds.size)
        // An answer that only mentions an alarm isn't asked again.
        brain(say("Your alarm is at 7:00."), say("(asked again)"))
        assertEquals("Your alarm is at 7:00.", a.answer("When is my alarm?").text)
    }

    @Test fun alarmsInMinutesRingOnTheNextWholeMinute() = runBlocking {
        now = now.plusSeconds(20) // 15:40:20
        brain(call("alarms", """{"action":"set","in_seconds":60}"""), say("Alarm set."))
        assistant().answer("Set an alarm for one minute from now")
        assertEquals("Done: alarm 1 set for 15:42, once, on (rings 15:42)", toolResults().single())
        assertEquals("set null 15:42 [] true", did.single())
    }

    @Test fun remindersUseTheWatchsReminderSlots() = runBlocking {
        brain(call("timers_and_reminders", """{"action":"reminder","label":"drink water","in_seconds":120}"""), say("I'll remind you to drink water at 15:42."))
        val a = assistant().answer("Remind me in 2 minutes to drink water")
        assertTrue(a.frames.isEmpty()) // the agenda sync writes the slot
        val r = WatchAssistant.voiceReminders(saved[WatchAssistant.ALERTS], now.toEpochSecond()).single()
        assertEquals("drink water", r.title)
        assertEquals(now.plusMinutes(2).toEpochSecond(), r.start)
        assertTrue(WatchAssistant.voiceReminders(saved[WatchAssistant.ALERTS], now.plusMinutes(3).toEpochSecond()).isEmpty())
        brain(call("timers_and_reminders", """{"action":"reminder","time":"18:00"}"""), say("What should I remind you about?"))
        assistant().answer("Remind me at 6")
        assertEquals("Error: a reminder needs a label", toolResults().single())
    }

    @Test fun workoutCaptureStartsAndStopsByVoice() = runBlocking {
        val a = assistant()
        brain(call("recording", """{"action":"start_workout","label":"Gym","minutes":45}"""), say("Started your gym capture."))
        assertEquals("Started your gym capture.", a.answer("Start a gym workout for 45 minutes").text)
        brain(call("recording", """{"action":"start_workout","label":"Gym"}"""), say("One is already running."))
        a.answer("Start a gym workout")
        assertEquals("Error: A workout capture is already running", toolResults().single())
        brain(call("recording", """{"action":"stop_workout"}"""), say("Stopped."))
        a.answer("Stop my workout")
        assertEquals("Done: stopped Gym after 12 minutes; Heartline is working out the sets and reps", toolResults().single())
        brain(call("app", """{"action":"sync_watch"}"""), say("Syncing."))
        a.answer("Sync my watch")
        assertEquals(listOf("start Gym 45", "stop", "sync"), did)
    }

    @Test fun dryRunsChangeNothing() = runBlocking {
        brain(call("timers_and_reminders", """{"action":"timer","seconds":60}"""), call("recording", """{"action":"start_workout","label":"Run"}"""), say("Done."))
        val a = assistant().answer("Timer and a run", dryRun = true)
        assertEquals("Done. [timer for 1 minute, ringing at 15:41; started the Run capture for 1 hour]", a.summary)
        assertNull(saved[WatchAssistant.ALERTS])
        assertTrue(did.isEmpty())
    }

    @Test fun weatherIsARealCard() = runBlocking {
        brain(call("show_on_watch", """{"action":"weather"}"""), say("Here's the weather."))
        val a = assistant().answer("What's the weather like?")
        assertNull(a.text)
        val body = String(a.frames.single(), Charsets.UTF_8)
        listOf("Noida", "Partly cloudy", "/currentWeatherIcon/partly_cloudy.png", "/weatherForecast/rainy.png", "24", "29", "19").forEach { assertTrue(it, body.contains(it)) }
        weatherError = "choose a place in Heartline's weather settings"
        brain(call("show_on_watch", """{"action":"weather"}"""), say("I couldn't get the weather."))
        assertEquals("I couldn't get the weather.", assistant().answer("Weather?").text)
        assertEquals("Error: couldn't get the weather: choose a place in Heartline's weather settings", toolResults().single())
    }

    @Test fun historyCarriesTheReplies() = runBlocking {
        val a = assistant()
        brain(say("Knock knock."))
        a.answer("Tell me a knock knock joke")
        brain(say("Lettuce."))
        a.answer("Who's there?")
        val m = bodies.last().getJSONArray("messages")
        assertEquals("Knock knock.", m.getJSONObject(2).getString("content"))
        assertEquals("Who's there?", m.getJSONObject(3).getString("content"))
    }

    @Test fun longAnswersContinueAsNotifications() {
        val story = (1..120).joinToString(" ") { "Sentence number $it of the story goes here." }
        val (shown, rest) = WatchAssistant.splitReply(story, 700)
        assertTrue(shown!!.length <= 700)
        assertTrue(shown.endsWith(". (continued in notifications)"))
        assertTrue(rest.all { it.toByteArray().size <= WatchAssistant.NOTIFICATION_TEXT })
        assertEquals(story, (shown.removeSuffix(" (continued in notifications)") + " " + rest.joinToString(" ")))
        assertEquals("Short." to emptyList<String>(), WatchAssistant.splitReply("Short.", 700))
        assertEquals(null to emptyList<String>(), WatchAssistant.splitReply(null, 700))
        val word = "x".repeat(500) // no spaces at all still splits
        assertEquals(word, WatchAssistant.notificationChunks(word).joinToString(""))
    }

    @Test fun textIsCleanedForTheWatch() {
        assertEquals("I don't have a preference - both are good.", WatchAssistant.watchText("I don’t have a preference — both are good."))
        assertEquals("Lettuce in!", WatchAssistant.watchText("Lettuce in! 🤔"))
        assertEquals("Lettuce in! 😄", WatchAssistant.watchText("Lettuce in! 😄"))
        assertEquals("74 bpm at 5:19 PM, 27°C", WatchAssistant.watchText("74 bpm at 5:19 PM, 27°C"))
        assertEquals("\"Hi\"... bold", WatchAssistant.watchText("“Hi”… **bold**"))
        // What the watch draws stays: symbols, other scripts, new lines and the emoji it has.
        assertEquals("✓ 31° → ★ - Rs 500 नमस्ते 你好\n\nNext 👍 ☀", WatchAssistant.watchText("✓ 31° → ★ • ₹500 नमस्ते 你好\n\n\n\nNext 👍🏽 ☀️"))
        assertEquals("Good", WatchAssistant.watchText("Good ❤️ ✔ ⏰ 🔥"))
        assertEquals("😷🙌 ♥ ‰ ① - x", WatchAssistant.watchText("😩😷🙌😸 ♥ ‰ ① ▸ x"))
        assertEquals("Family 👩👧, flag , key 1", WatchAssistant.watchText("Family 👨‍👩‍👧, flag 🇮🇳, key 1️⃣").replace("  ", " "))
    }

    @Test fun markdownBecomesPlainText() {
        val md = "## Today\n**Sleep** was *good*, see [the log](https://x.y/z).\n* one\n- two\n> quoted\n\n| Day | High |\n|---|:---:|\n| Mon | 31° |\n~~old~~ `code`"
        assertEquals("Today\nSleep was good, see the log.\n- one\n- two\nquoted\n\nDay | High\nMon | 31°\nold code", WatchAssistant.watchText(md))
        assertEquals("5 * 3 = 15", WatchAssistant.watchText("5 * 3 = 15"))
    }

    @Test fun longLeftColumnsMoveRight() {
        val short = listOf("1. Everest" to "8,849 m", "Mon" to "31°")
        assertEquals(short, WatchAssistant.fitLeft(short))
        assertEquals(listOf("1." to "Everest - 8,849 m", "3." to "Kangchenjunga - 8,586 m", "" to "Base camp - 5,364 m"),
            WatchAssistant.fitLeft(listOf("1. Everest" to "8,849 m", "3. Kangchenjunga" to "8,586 m", "Base camp" to "5,364 m")))
        assertEquals(listOf("" to "Kangchenjunga"), WatchAssistant.fitLeft(listOf("Kangchenjunga" to "")))
    }

    @Test fun showListSendsATable() = runBlocking {
        brain(call("show_on_watch", """{"action":"table","title":"Forecast","rows":[{"left":"Mon","right":"31° / 22° **Sunny**"},{"left":"","right":""},{"left":"Tue","right":"Rain"}]}"""), say("Here it is."))
        val a = assistant().answer("Forecast as a table")
        assertArrayEquals(Alexa.list("Forecast", "", listOf("Mon" to "31° / 22° Sunny", "Tue" to "Rain")), a.frames.single())
        assertEquals(null, a.text) // the card is the answer
        assertEquals(listOf("Done: showed a list of 2 rows"), toolResults())
        brain(call("show_on_watch", """{"action":"table","rows":[${(1..60).joinToString(",") { """{"left":"$it","right":"${"word ".repeat(30)}"}""" }}]}"""), say("Done."))
        val big = assistant().answer("A long table")
        assertTrue(big.frames.single().size <= WatchAssistant.LIST_BYTES + 7)
        assertTrue(toolResults().single().contains("didn't fit"))
    }

    @Test fun alarmsAreListedSetSwitchedAndDeleted() = runBlocking {
        val a = assistant()
        brain(call("alarms", """{"action":"list"}"""), say("You have two."))
        a.answer("What alarms do I have?")
        assertEquals("Alarms on the watch, by slot:\nalarm 0: 07:00, Mon, on\nalarm 10: time unknown (made on the watch), once, off", toolResults().single())
        brain(call("alarms", """{"action":"set","time":"6:30","days":["weekdays"]}"""), say("Set."))
        a.answer("Alarm at 6:30 on weekdays")
        assertEquals("Done: alarm 1 set for 06:30, weekdays, on", toolResults().single())
        brain(call("alarms", """{"action":"set","slot":0,"on":false}"""), say("Off."))
        a.answer("Turn off my 7 am alarm")
        brain(call("alarms", """{"action":"set","slot":0,"time":"07:15"}"""), say("Moved."))
        a.answer("Move it to 7:15")
        assertEquals("Done: alarm 0 set for 07:15, Mon, on", toolResults().single()) // the days are kept
        brain(call("alarms", """{"action":"delete","slots":[10,4]}"""), say("Deleted."))
        a.answer("Delete the others")
        assertEquals("Done: deleted alarm 10; failed: alarm 4: there is no alarm 4 on the watch", toolResults().single())
        assertEquals(listOf("set null 06:30 [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY] true", "switch 0 false", "set 0 07:15 [MONDAY] true", "delete 10"), did)
        brain(call("alarms", """{"action":"snooze"}"""), say("?"))
        a.answer("Snooze")
        assertEquals("Error: action for alarms is one of list, set, delete", toolResults().single())
    }

    @Test fun thePhoneNotesAndLongTextsAreControlled() = runBlocking {
        val a = assistant()
        brain(call("phone", """{"action":"pause"}"""), call("phone", """{"action":"ringer_vibrate"}"""), call("phone", """{"action":"set_volume"}"""), say("Done."))
        a.answer("Pause the music and put my phone on vibrate")
        assertEquals(listOf("Done: pause. Now: Spotify playing", "Done: ringer vibrate. Now: ringer vibrate", "Error: set_volume needs percent"), toolResults())
        brain(call("app", """{"action":"add_note","text":"buy milk"}"""), say("Noted."))
        a.answer("Note buy milk")
        val story = "Once upon a time. ".repeat(120)
        brain(call("show_on_watch", """{"action":"notifications","title":"Story","text":"$story"}"""), say("Sent to your watch."))
        val sent = a.answer("Send me a long story")
        assertEquals("Sent to your watch.", sent.text)
        assertEquals(listOf("media pause null", "ringer vibrate null", "note buy milk", "send Story 3"), did)
    }
}
