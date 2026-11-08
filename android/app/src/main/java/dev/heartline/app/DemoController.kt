package dev.heartline.app

import android.net.Uri
import dev.heartline.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic data that behaves like the real watch, for building and screenshotting the UI. */
class DemoController(private val scope: CoroutineScope) : HeartlineController {
    private val zone = ZoneId.systemDefault()
    private val rnd = Random(7)
    private fun now() = System.currentTimeMillis() / 1000

    override val connection = MutableStateFlow(ConnectionUi(LinkState.CONNECTED, since = now() - 754, rssi = -58))
    override val battery = MutableStateFlow<BatteryUi?>(BatteryUi(68, false, "31 Oct, 04:10"))
    override val device = MutableStateFlow<DeviceUi?>(DeviceUi("Amazfit GTR 2e", "C1:2A:3B:4C:5D:6E", "V1.7.3.1", "V0.72.19.17", "PVXB00 0000000000"))
    override val liveHr = MutableStateFlow(LiveHrUi(streaming = false, bpm = 72, ts = now() - 30, recent = demoHr()))
    override val steps = MutableStateFlow<StepsUi?>(StepsUi(6412, 4630, 288))
    override val sync = MutableStateFlow(SyncUi(lastActivity = now() - 300, lastHealth = now() - 300))
    override val today = MutableStateFlow<TodayUi?>(demoToday())
    override val trends = MutableStateFlow<TrendsUi?>(demoTrends())
    override val checks = MutableStateFlow(demoChecks())
    override val checkRun = MutableStateFlow<CheckRunUi?>(null)
    override val sensors = MutableStateFlow(SensorsUi())
    override val recordings = MutableStateFlow(listOf(RecordingUi("20261101-125835", now() - 3600, 20.6, setOf(Sensor.PPG, Sensor.ACCEL, Sensor.MIC), true)))
    override val watchSettings = MutableStateFlow(demoSettings())
    override val events = MutableStateFlow(listOf(
        EventUi(now() - 40, false, false, "Activity sync done: 12 new minutes"),
        EventUi(now() - 754, false, false, "Connected and authenticated"),
        EventUi(now() - 760, true, false, "Watch not found, retrying scan"),
    ))
    override val prefs = MutableStateFlow(PrefsUi(profile = ProfileUi(age = 27, sex = "male", weightKg = 70.0, heightCm = 175.0), onboarded = true, hasKey = true, watchMac = "C1:2A:3B:4C:5D:6E"))

    private var hrJob: Job? = null
    private var rawJob: Job? = null
    private var checkJob: Job? = null

    override fun activityDay(date: LocalDate): Flow<ActivityDayUi> = flowOf(demoDay(date))
    override fun healthDay(date: LocalDate): Flow<HealthDayUi> = flowOf(demoHealthDay(date))
    override suspend fun checkDetail(id: String): SessionUi = demoSession(id)

    override fun connect() { connection.value = ConnectionUi(LinkState.CONNECTED, since = now()) }
    override fun disconnect() { connection.value = ConnectionUi(); setLiveHr(false); setRawSensors(sensors.value.selected, false) }

    override fun setLiveHr(on: Boolean) {
        hrJob?.cancel()
        liveHr.update { it.copy(streaming = on) }
        if (!on) return
        hrJob = scope.launch {
            var bpm = 74.0
            while (isActive) {
                delay(1500)
                bpm = (bpm + rnd.nextDouble(-2.0, 2.0)).coerceIn(60.0, 95.0)
                val p = HrPoint(now(), bpm.roundToInt())
                liveHr.update { it.copy(bpm = p.bpm, ts = p.ts, recent = (it.recent + p).takeLast(600)) }
            }
        }
    }

    override fun setRawSensors(sensors: Set<Sensor>, streaming: Boolean) {
        rawJob?.cancel()
        this.sensors.update { it.copy(selected = sensors, streaming = streaming, file = if (streaming) "20261101-130512" else it.file) }
        if (!streaming) return
        rawJob = scope.launch {
            var n = 0
            val window = 8 * 25
            val ppg = ArrayDeque<Float>(); val acc = ArrayDeque<FloatArray>(); val gyro = ArrayDeque<FloatArray>()
            val mag = ArrayDeque<FloatArray>(); val mic = ArrayDeque<Float>()
            while (isActive) {
                repeat(5) {
                    val t = n / 25.0; n++
                    val ph = (t * 1.25) % 1.0
                    val beat = if (ph < 0.15) ph / 0.15 else exp(-(ph - 0.15) * 4) + 0.15 * exp(-((ph - 0.45) * (ph - 0.45)) / 0.003)
                    ppg.addLast((beat * 100 + rnd.nextDouble(-3.0, 3.0)).toFloat())
                    acc.addLast(floatArrayOf(-0.76f + rnd.nextFloat() * 0.01f, -0.17f, -0.63f))
                    gyro.addLast(floatArrayOf((300 * sin(t * 1.7)).toFloat(), (180 * sin(t * 0.9 + 1)).toFloat(), (90 * cos(t * 2.3)).toFloat()))
                    val turn = t * 0.4
                    mag.addLast(floatArrayOf((1500 * cos(turn)).toFloat(), (1500 * sin(turn)).toFloat(), -860f))
                    repeat(2) { mic.addLast((-55 + 18 * max(0.0, sin(t / 1.6)) + rnd.nextDouble(0.0, 4.0)).toFloat()) }
                }
                while (ppg.size > window) ppg.removeFirst()
                while (acc.size > window) acc.removeFirst()
                while (gyro.size > window) gyro.removeFirst()
                while (mag.size > window) mag.removeFirst()
                while (mic.size > 400) mic.removeFirst()
                val m = mag.last()
                val heading = ((atan2(-m[1].toDouble(), m[0].toDouble()) * 180 / PI) + 360) % 360
                this@DemoController.sensors.update {
                    it.copy(
                        ppg = ppg.toFloatArray(), accel = acc.toTypedArray(), gyro = gyro.toTypedArray(), mag = mag.toTypedArray(),
                        micDb = mic.toFloatArray(), ppgBpm = 75, headingDeg = heading, compassCalibration = (n / 600.0).coerceAtMost(1.0),
                        gps = if (Sensor.GPS in it.selected) demoGps() else null,
                    )
                }
                delay(200)
            }
        }
    }

    override fun startCheck(seconds: Int) {
        checkJob?.cancel()
        checkRun.value = CheckRunUi(seconds, now(), analysing = false, result = null, error = null)
        setRawSensors(setOf(Sensor.PPG, Sensor.ACCEL), true)
        checkJob = scope.launch {
            delay(seconds * 1000L)
            checkRun.update { it?.copy(analysing = true) }
            delay(1200)
            setRawSensors(sensors.value.selected, false)
            val s = demoSession("c-${now()}")
            checkRun.update { it?.copy(analysing = false, result = s) }
            checks.update { listOf(CheckUi(s.id, s.start, seconds, s.hr, s.rmssd, s.rhythm)) + it }
        }
    }

    override fun cancelCheck() { checkJob?.cancel(); checkRun.value = null; setRawSensors(sensors.value.selected, false) }
    override fun vibrate() = addEvent("Watch vibrated")
    override fun sendNotification(title: String, body: String, kind: NotifyKind, asPackage: String) = addEvent("Sent notification: $title")
    override fun refreshTemperature() {}
    override fun syncNow() {
        scope.launch {
            sync.update { it.copy(running = true, message = "Syncing activity…") }
            delay(1500)
            sync.update { SyncUi(false, now(), now(), "Up to date") }
        }
    }
    override fun setWatchClock() = addEvent("Watch clock set")
    override fun applyWatchSetting(key: String, value: Any) {
        watchSettings.update { list -> list.map { if (it.key == key) it.copy(value = value) else it } }
    }
    override fun saveProfile(profile: ProfileUi) = prefs.update { it.copy(profile = profile) }
    override fun setTheme(mode: ThemeMode) = prefs.update { it.copy(theme = mode) }
    override fun setMetric(metric: Boolean) = prefs.update { it.copy(metric = metric) }
    override fun setForwarding(update: ForwardingUi) = prefs.update { it.copy(forwarding = update) }
    override fun saveWatchKey(mac: String, keyHex: String): String? {
        if (!Regex("[0-9a-fA-F]{32}").matches(keyHex.trim().removePrefix("0x"))) return "The key is 32 hex characters, like 7ff5d4a1…"
        prefs.update { it.copy(hasKey = true, watchMac = mac) }
        return null
    }
    override fun finishOnboarding() = prefs.update { it.copy(onboarded = true) }
    override fun importPcData(uri: Uri) = addEvent("Imported PC data")
    override val gymLog = MutableStateFlow<List<LoggedSet>>(emptyList())
    override val gymImport = MutableStateFlow<String?>(null)
    override fun importGymLog(uris: List<Uri>) = addEvent("Imported gym log")
    override fun saveTreadmill(workoutStart: Long, workoutEnd: Long, speedKmh: Double, inclinePct: Double) = addEvent("Saved treadmill settings")
    override fun exportData(uri: Uri) = addEvent("Exported data")
    override fun recordingAudio(file: String): Uri? = null

    private val bengaluru = PlaceUi("Bengaluru", "Karnataka, India", 12.97, 77.59)
    override val weather = MutableStateFlow(WeatherUi(true, bengaluru, false, 24, "Partly cloudy", 29, 19, now() - 1200, now() - 1180))
    override val phoneLink = MutableStateFlow(PhoneLinkUi(musicEnabled = true, musicAccess = true, nowPlaying = "Kesariya, Arijit Singh"))
    override suspend fun searchPlaces(query: String): List<PlaceUi> =
        if (query.isBlank()) emptyList() else listOf(bengaluru, PlaceUi("Bengaluru Rural", "Karnataka, India", 13.28, 77.53)).filter { it.name.contains(query, true) }
    override val assistantAi = MutableStateFlow(AssistantAiUi(null, dev.heartline.service.AssistantAiConfig.DEFAULT_MODEL))
    override fun setAssistantAi(key: String?, model: String) { assistantAi.update { it.copy(keyEnd = key?.takeLast(4) ?: it.keyEnd, model = model) } }
    override fun setAssistantAiExtraKey(slot: Int, key: String) {
        val end = key.trim().takeLast(4).ifEmpty { null }
        assistantAi.update { if (slot == 3) it.copy(key3End = end) else it.copy(key2End = end) }
    }
    override val alexaSettings = MutableStateFlow(dev.heartline.service.AlexaSettings())
    override fun updateAlexaSettings(change: (dev.heartline.service.AlexaSettings) -> dev.heartline.service.AlexaSettings) { alexaSettings.update { change(it).clamped() } }
    override val alexaTurns = MutableStateFlow(listOf(dev.heartline.service.TurnRecord(System.currentTimeMillis() - 600_000, "Watch", "How did I sleep?", 2.1, 900,
        "You are the voice assistant...", 0, listOf(dev.heartline.service.RoundLog("nvidia/nemotron-3.5-super-vl:free", 1, "off", 2100, 2900, 40,
            listOf(dev.heartline.service.ToolCallLog("get_sleep", "{\"date\":\"today\"}", "Sleep 23:40 to 06:55: 6 h 50 min asleep", 12)), null),
            dev.heartline.service.RoundLog("nvidia/nemotron-3.5-super-vl:free", 1, "off", 1800, 3100, 60, emptyList(), "You slept 6 h 50 min with 1 h 20 min deep.")),
        "You slept 6 h 50 min with 1 h 20 min deep.", "Reply on the watch", null, 5200, null)))
    override fun clearAlexaTurns() { alexaTurns.value = emptyList() }
    override val insights = MutableStateFlow(InsightsUi(dev.heartline.service.InsightReport(System.currentTimeMillis() / 1000 - 3600, "demo", "Sleep is your lever this week.",
        listOf(dev.heartline.service.Insight("sleep", "Short nights add up", "You averaged 5.3 h over 14 nights, 1.5 h under your best weeks.", "Aim for lights out by 23:30.", "watch")))))
    override fun refreshInsights() {}
    override val insightsSettings = MutableStateFlow(dev.heartline.service.InsightsSettings())
    override fun setInsightsSettings(s: dev.heartline.service.InsightsSettings) { insightsSettings.value = s }
    override suspend fun geminiModels(): List<String> = listOf("gemini-3.8-flash", "gemini-3.8-flash-lite")
    private val demoRefs = listOf(dev.heartline.service.ai.ModelRef("openrouter", "nvidia/nemotron-3.5-super-vl:free"), dev.heartline.service.ai.ModelRef("openrouter", "google/gemma-4-31b-it:free"),
        dev.heartline.service.ai.ModelRef("gemini", "gemini-3.8-flash"), dev.heartline.service.ai.ModelRef("gemini", "gemini-3.8-flash-lite"), dev.heartline.service.ai.ModelRef("gemini", "gemini-3.5-transcribe-live"))
    override val aiSetup = MutableStateFlow(dev.heartline.service.ai.AiSetup(
        dev.heartline.service.ai.AiSetup.defaultProviders().map { if (it.id == "openrouter") it.copy(keys = listOf("…a1f3", "…77c0")) else it.copy(keys = listOf("…9q2e")) },
        demoRefs).let { s -> dev.heartline.service.ai.AiSection.entries.fold(s) { acc, sec ->
            val chain = when (sec) { dev.heartline.service.ai.AiSection.VOICE -> listOf(demoRefs[4]); dev.heartline.service.ai.AiSection.ASSISTANT -> listOf(demoRefs[0], demoRefs[2], demoRefs[1])
                else -> listOf(demoRefs[2], demoRefs[3], demoRefs[0]) }
            acc.withSection(sec, dev.heartline.service.ai.SectionSetup(chain.getOrNull(0), chain.getOrNull(1), chain.getOrNull(2), dev.heartline.service.ai.RetryPolicy.default(sec)))
        } })
    override val aiCatalog = MutableStateFlow(dev.heartline.service.ai.AiCatalog(demoRefs.map { r ->
        dev.heartline.service.ai.CatalogModel(r, r.model.substringAfter('/'), 131_072, tools = !r.model.contains("live"), audio = r.provider == "gemini", live = r.model.contains("live"), free = r.model.endsWith(":free"))
    } + (1..40).map { i -> dev.heartline.service.ai.CatalogModel(dev.heartline.service.ai.ModelRef("openrouter", "vendor$i/model-$i"), "Vendor $i Model", 32_768 * (1 + i % 4), tools = i % 3 != 0, audio = i % 7 == 0, free = i % 2 == 0) },
        mapOf("openrouter" to now() - 7200, "gemini" to now() - 7200)))
    override val aiActivity = MutableStateFlow(listOf(
        dev.heartline.service.ai.AiAttempt(System.currentTimeMillis() - 60_000, "assistant", "openrouter", "nvidia/nemotron-3.5-super-vl:free", 1, "77c0", 0, 1830),
        dev.heartline.service.ai.AiAttempt(System.currentTimeMillis() - 61_000, "assistant", "openrouter", "nvidia/nemotron-3.5-super-vl:free", 0, "a1f3", 0, 420, dev.heartline.service.ai.FailKind.QUOTA, "OpenRouter 429: Rate limit exceeded: free-models-per-day"),
        dev.heartline.service.ai.AiAttempt(System.currentTimeMillis() - 3_600_000, "insights", "gemini", "gemini-3.8-flash", 0, "9q2e", 0, 14_200)))
    override fun aiAddKey(provider: String, key: String) { aiSetup.update { s -> s.provider(provider)?.let { s.withProvider(it.copy(keys = it.keys + "…" + key.trim().takeLast(4))) } ?: s } }
    override fun aiRemoveKey(provider: String, index: Int) { aiSetup.update { s -> s.provider(provider)?.let { s.withProvider(it.copy(keys = it.keys.filterIndexed { i, _ -> i != index })) } ?: s } }
    override fun aiMoveKey(provider: String, index: Int, up: Boolean) {}
    override fun aiSaveProvider(id: String?, kind: dev.heartline.service.ai.ProviderKind, name: String, baseUrl: String): String {
        val pid = id ?: name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "custom" }
        aiSetup.update { s -> s.withProvider((s.provider(pid) ?: dev.heartline.service.ai.AiProvider(pid, kind, name, baseUrl)).copy(name = name, baseUrl = baseUrl)) }
        return pid
    }
    override fun aiDeleteProvider(id: String) { aiSetup.update { s -> s.copy(providers = s.providers.filter { it.id != id || it.builtIn }) } }
    override fun aiSetPicked(ref: dev.heartline.service.ai.ModelRef, on: Boolean) { aiSetup.update { s -> s.copy(picked = if (on) (s.picked + ref).distinct() else s.picked - ref) } }
    override fun aiSetSection(section: dev.heartline.service.ai.AiSection, setup: dev.heartline.service.ai.SectionSetup) { aiSetup.update { it.withSection(section, setup) } }
    override suspend fun aiRefreshModels(provider: String?) = aiCatalog.value
    override suspend fun aiTestKeys(provider: String): List<String?> = aiSetup.value.provider(provider)?.keys?.mapIndexed { i, _ -> if (i == 0) "Out of quota: Rate limit exceeded: free-models-per-day" else null }.orEmpty()
    override suspend fun aiTrySection(section: dev.heartline.service.ai.AiSection) = "OK from Gemini: gemini-3.8-flash with key 1 in 1.4 s."
    override val alexaTools = listOf(AlexaToolUi("get_sleep", "Health data", "The night's sleep that ended on the morning of a date.", "{}"),
        AlexaToolUi("web_search", "Web", "Search the web.", "{}"), AlexaToolUi("set_timer", "Watch and Heartline actions", "Start a countdown timer on the watch.", "{}"))
    override suspend fun alexaPromptPreview(template: String) = dev.heartline.service.AssistantAi.renderPrompt(template,
        dev.heartline.service.AiContext(java.time.ZonedDateTime.now(), "62 bpm at 10:02", 4210, null, emptyList(), "10:05", "Readiness: 78 out of 100"))
    override val alexaAlerts = MutableStateFlow(listOf(AlexaAlertUi(1, "Reminder", "reminder at 18:00 to call home", now() + 3600)))
    override fun cancelAlexaAlert(id: Int) { alexaAlerts.update { l -> l.filter { it.id != id } } }
    override fun alexaRecording(name: String): Uri? = null
    override val webSearch = MutableStateFlow(WebSearchUi())
    override fun setWebSearchKey(service: String, key: String) {
        val end = key.trim().takeLast(4).ifEmpty { null }
        webSearch.update { if (service == "exa") it.copy(exaEnd = end) else it.copy(tavilyEnd = end) }
    }
    override suspend fun testAssistantAi(question: String) = "The watch would show: Alarm set for Sat 9:30 AM. (sets a watch alarm)\nOpenRouter took 0.9 s."
    override val speech = MutableStateFlow(SpeechUi(null))
    override fun setGeminiKey(key: String) { speech.value = SpeechUi(key.trim().takeLast(4).ifEmpty { null }) }
    override suspend fun testSpeech() = "Gemini heard \"Set an alarm for 9:30 a.m.\" (3.4 s of audio, 0.6 s).\nThe watch would show: Alarm set for Sat 9:30 AM. (sets a watch alarm)"
    override fun setWeather(enabled: Boolean, place: PlaceUi?) = weather.update { it.copy(enabled = enabled, place = place ?: it.place, useLocation = if (place != null) false else it.useLocation) }
    override fun setWeatherLocation(use: Boolean) = weather.update { it.copy(useLocation = use) }
    override fun refreshWeather() = weather.update { it.copy(updatedAt = now(), sentToWatchAt = now()) }
    override fun setMusicControl(enabled: Boolean) = phoneLink.update { it.copy(musicEnabled = enabled) }
    override val autoCapture = MutableStateFlow(false)
    override fun setAutoCapture(enabled: Boolean) { autoCapture.value = enabled }
    override val watchMenu = MutableStateFlow(WatchCustomizeController.parseMenu(null))
    override fun setWatchMenu(apps: List<MenuAppUi>) { watchMenu.value = apps }
    override fun sendWatchMenu() = addEvent("Sent the watch app list")
    override val vibrations = MutableStateFlow(WatchCustomizeController.parseVibrations(null))
    override fun setVibration(kind: String, profile: String, repeat: Int) = vibrations.update { l -> l.map { if (it.kind == kind) it.copy(profile = profile, repeat = repeat) else it } }
    override fun tryVibration(kind: String, profile: String, repeat: Int) = addEvent("Watch tried a vibration pattern")
    override fun stopFindPhone() = phoneLink.update { it.copy(findPhoneRinging = false) }

    override val alarms = MutableStateFlow(listOf(
        AlarmUi(0, true, 6, 30, setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)),
        AlarmUi(1, false, 8, 0, setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)),
    ))
    override val reminders = MutableStateFlow(ReminderSyncUi(
        enabled = true, permission = true, calendars = listOf(CalendarUi(1, "Personal", "you@example.com"), CalendarUi(2, "Holidays in India", null)),
        selected = setOf(1L), upcoming = listOf(ReminderUi("Dentist", now() + 26 * 3600), ReminderUi("Team sync", now() + 50 * 3600)), lastSync = now() - 600,
    ))
    override fun saveAlarm(alarm: AlarmUi) = alarms.update { list -> (list.filter { it.slot != alarm.slot } + alarm).sortedBy { it.slot } }
    override fun deleteAlarm(slot: Int) = alarms.update { list -> list.filter { it.slot != slot } }
    override fun setReminderSync(enabled: Boolean, calendars: Set<Long>) = reminders.update { it.copy(enabled = enabled, selected = calendars) }

    override val healthConnect = MutableStateFlow(HealthConnectUi(HealthConnectStatus.READY, true, now() - 3600, "Wrote 1,212 heart rate samples, 3 nights of sleep and 7 days of steps"))
    override val background = MutableStateFlow(BackgroundUi(batteryUnrestricted = false, notificationAccess = true, miui = true))
    override val healthConnectPermissions: Set<String> = emptySet()
    override fun setHealthConnect(enabled: Boolean) = healthConnect.update { it.copy(enabled = enabled) }
    override fun exportHealthConnectNow() = healthConnect.update { it.copy(lastExport = now()) }
    override fun refreshBackground() {}

    override val captureRun = MutableStateFlow<CaptureRunUi?>(null)
    override val captures = MutableStateFlow(listOf(CaptureItemUi("demo-gym", "Gym", now() - 7200, 3600, 128, 14, 96.0)))
    override fun startCapture(label: String, minutes: Int) { captureRun.value = CaptureRunUi(label, now(), minutes * 60, 92, 0, 0) }
    override fun stopCapture() { captureRun.value = null }
    override suspend fun captureDetail(id: String): CaptureAnalysisUi? = demoCapture()
    override fun deleteCapture(id: String) = captures.update { l -> l.filter { it.id != id } }
    override suspend fun savedCaptureCoach(id: String): CoachUi? = null
    override suspend fun captureCoach(id: String, facts: String, refresh: Boolean) = CoachUi(
        "A steady session: every working set reached its target and rests stayed near three minutes. The last set of each lift was the hardest.",
        listOf("Bench press: stay at the same load and aim for one more rep on the last set."), "demo", System.currentTimeMillis() / 1000)
    override val probe = MutableStateFlow<List<String>>(emptyList())
    override val watchReadback = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    override val overnight = MutableStateFlow(OvernightUi())
    override val hrvCheck = MutableStateFlow<HrvCheckUi?>(null)
    override val notes = MutableStateFlow("")
    override fun saveNotes(text: String) { notes.value = text }
    override fun setHrvSchedule(night: Boolean, everyMin: Int, lengthMin: Int) {
        overnight.value = if (night) overnight.value.copy(nightEvery = everyMin, nightLen = lengthMin) else overnight.value.copy(dayEvery = everyMin, dayLen = lengthMin)
    }
    override fun setAllDayHrv(enabled: Boolean) { overnight.value = overnight.value.copy(allDay = enabled, dayStatus = if (enabled) "Waiting for a minute without steps" else "Off") }
    override fun setOvernight(enabled: Boolean) { overnight.value = overnight.value.copy(enabled = enabled, status = if (enabled) "Waiting for the night window" else "Off") }
    override fun readWatchHealth() { watchReadback.value = listOf("All-day stress" to "on") }
    override fun probeUnknownSensors() { probe.value = listOf("mask 0x04: nothing", "mask 0x80: nothing") }

    private fun addEvent(msg: String) = events.update { listOf(EventUi(now(), false, false, msg)) + it }

    // ---------- synthetic content ----------

    private fun demoHr(): List<HrPoint> {
        val t0 = now() - 3600
        return (0 until 3600 step 20).map { HrPoint(t0 + it, (66 + 8 * sin(it / 900.0) + rnd.nextDouble(-3.0, 3.0)).roundToInt()) }
    }

    private fun demoToday() = TodayUi(
        readiness = 74, readinessLabel = "Readiness",
        recommendation = "You recovered well. A moderate session fits today; keep the hard effort under an hour.",
        strain = 9.4, sleepScore = 81, bodyBattery = 63, restingHr = 56,
        factors = listOf(
            Factor("HRV", Impact.POSITIVE, "8% above your 28-day baseline."),
            Factor("Resting heart rate", Impact.NEUTRAL, "matches your baseline at 57 bpm."),
            Factor("Sleep", Impact.POSITIVE, "7 h 12 min with 1 h 24 min of deep sleep."),
            Factor("Training load", Impact.NEGATIVE, "the last 3 days were above your usual load."),
        ),
        alerts = listOf(AlertUi(false, "Late bedtimes", "Three of the last five nights started after 1 AM, which lowers sleep regularity.")),
        lastNight = SleepNightUi(now() - 8 * 3600, now() - 3600, 84, 248, 92, 18),
        stepsToday = 6412, activeMinutes = 47,
    )

    private fun demoTrends(): TrendsUi {
        val days = (13 downTo 0).map { LocalDate.now(zone).minusDays(it.toLong()) }
        fun s(key: String, title: String, unit: String, lo: Double, hi: Double, base: Double? = null, bars: Boolean = false, accent: Boolean = false) =
            TrendSeries(key, title, unit, days.map { DayPoint(it, rnd.nextDouble(lo, hi)) }, base, bars, accent)
        return TrendsUi(
            vo2max = 44.6, fitnessAge = 29, sleepRegularity = 78, load7d = 612.0, acwr = 1.18,
            series = listOf(
                s("readiness", "Readiness", "", 52.0, 86.0),
                s("rhr", "Resting heart rate", "bpm", 54.0, 61.0, base = 57.0),
                s("hrv", "HRV", "ms", 38.0, 62.0, base = 48.0),
                s("sleep", "Sleep", "h", 5.6, 7.9, bars = true),
                s("sleep_score", "Sleep score", "", 62.0, 88.0),
                s("steps", "Steps", "", 3500.0, 12500.0, bars = true),
                s("strain", "Strain", "", 6.0, 16.0, accent = true),
                s("active", "Active time", "min", 25.0, 110.0, bars = true, accent = true),
                s("spo2", "Blood oxygen", "%", 95.0, 98.0),
                s("stress", "Stress", "", 22.0, 48.0, accent = true),
                s("pai", "PAI", "", 40.0, 90.0, bars = true),
                s("temp", "Skin temperature", "°C", 31.0, 33.5),
            ),
        )
    }

    private fun demoChecks(): List<CheckUi> = (0 until 6).map {
        CheckUi("c$it", now() - it * 86400L - 3600, 120, rnd.nextDouble(62.0, 80.0), rnd.nextDouble(38.0, 60.0), if (it == 3) RhythmStatus.IRREGULAR else RhythmStatus.REGULAR)
    }

    fun demoSession(id: String): SessionUi {
        val rr = ArrayList<RrUi>()
        var t = 0.0
        for (i in 0 until 90) {
            val v = 820 + 45 * sin(i / 2.6) + rnd.nextDouble(-18.0, 18.0) + if (i == 31) -260.0 else if (i == 32) 240.0 else 0.0
            t += v / 1000
            rr += RrUi(t, v, i == 31)
        }
        val preview = FloatArray(60 * 25) { i ->
            val ph = (i / 25.0 * 1.22) % 1.0
            ((if (ph < 0.15) ph / 0.15 else exp(-(ph - 0.15) * 4)) * 100 + rnd.nextDouble(-4.0, 4.0)).toFloat()
        }
        return SessionUi(
            id, now() - 600, 75.0, 0.94, 72.8, 44.0, 48.0, 0.21, 96.0, 1.27, 14.2, RhythmStatus.REGULAR, 1,
            listOf("One premature beat with a compensatory pause."), rr, preview, 25.0, rr.map { it.t }.toDoubleArray(),
        )
    }

    private fun demoDay(date: LocalDate): ActivityDayUi {
        val start = date.atStartOfDay(zone).toEpochSecond()
        val mins = (0 until 1440).map { m ->
            val h = m / 60.0
            when {
                h < 7 -> {
                    val c = ((h * 60) % 90)
                    MinuteUi(start + m * 60, if (c < 20) MinuteState.SLEEP_LIGHT else if (c < 50) MinuteState.SLEEP_DEEP else if (c < 70) MinuteState.SLEEP_LIGHT else MinuteState.SLEEP_REM, 0, 52 + rnd.nextInt(6))
                }
                h in 8.0..8.6 || h in 18.0..19.2 -> MinuteUi(start + m * 60, MinuteState.ACTIVE, 70 + rnd.nextInt(50), 100 + rnd.nextInt(30))
                h in 22.5..23.2 -> MinuteUi(start + m * 60, MinuteState.NOT_WORN, 0, null)
                else -> MinuteUi(start + m * 60, MinuteState.IDLE, if (rnd.nextDouble() < 0.08) rnd.nextInt(30) else 0, 62 + rnd.nextInt(12))
            }
        }
        val track = (0 until 45 * 60 step 10).map { HrPoint(start + 18 * 3600 + it, (110 + 30 * sin(it / 400.0) + rnd.nextInt(8)).toInt()) }
        return ActivityDayUi(
            date, mins, mins.sumOf { it.steps }, mins.count { it.state == MinuteState.ACTIVE }, 420, 1395, 51, 71, 162,
            SleepNightUi(start, start + 7 * 3600, 84, 248, 92, 18),
            listOf(WorkoutUi(start + 18 * 3600, 2700, "Outdoor run", 148, 172, 412, 5980, 5210, track,
                (0..180).map { i -> val a = i / 180.0 * 2 * Math.PI; RoutePoint(i * 15, 12.9716 + 0.006 * Math.sin(a) + 0.0015 * Math.sin(3 * a), 77.5946 + 0.009 * Math.cos(a), 900.0 + 6 * Math.sin(2 * a)) })),
        )
    }

    /** A full day with the shapes a real one has: sleep dip, morning walk, desk stress, evening run. */
    private fun demoHealthDay(date: LocalDate): HealthDayUi {
        val day = demoDay(date); val start = date.atStartOfDay(zone).toEpochSecond()
        val r = Random(date.toEpochDay())
        val hr = day.minutes.mapNotNull { m -> m.hr?.let { SeriesPoint(m.ts.toDouble(), it.toDouble()) } } +
            day.workouts.flatMap { w -> w.track.map { SeriesPoint(it.ts.toDouble(), it.bpm.toDouble()) } }
        val hrs = hr.sortedBy { it.t }
        fun hours(series: List<SeriesPoint>, sum: Boolean = false) = (0 until 24).map { h ->
            val v = series.filter { ((it.t - start) / 3600).toInt() == h }.map { it.v }
            if (v.isEmpty()) HourStatUi(h, null, null, null, if (sum) 0.0 else null) else HourStatUi(h, v.min(), v.average(), v.max(), if (sum) v.sum() else null)
        }
        val stress = (7 * 60 until 23 * 60 step 5).map { m ->
            val h = m / 60.0
            val base = when { h < 9 -> 28.0; h < 13 -> 52.0; h < 14 -> 35.0; h < 18 -> 61.0; h < 19.5 -> 74.0; else -> 30.0 }
            SeriesPoint(start + m * 60.0, (base + 12 * sin(m / 37.0) + r.nextInt(-8, 9)).coerceIn(1.0, 100.0))
        }
        val spo2 = (0 until 7 * 60 step 10).map { m -> SeriesPoint(start + m * 60.0, (96.5 + 1.5 * sin(m / 50.0) - (if (m in 180..200) 4.0 else 0.0) + r.nextDouble(-1.0, 1.0)).coerceAtMost(100.0)) }
        val temp = (0 until 1440).filter { it !in (22 * 60 + 30)..(23 * 60 + 12) }.map { m ->
            val h = m / 60.0; val skin = if (h < 7) 34.2 + 0.4 * sin(h) else 32.3 + 0.8 * sin(h / 2.3) - (if (h in 18.0..19.2) 1.6 else 0.0)
            SeriesPoint(start + m * 60.0, skin + r.nextDouble(-0.15, 0.15))
        }
        val steps = day.minutes.filter { it.steps > 0 }.map { SeriesPoint(it.ts.toDouble(), it.steps.toDouble()) }
        fun bands(v: List<Double>, defs: List<Triple<String, Int, Int>>, perPoint: Int) = defs.map { (n, lo, hi) -> BandTimeUi(n, lo, hi, v.count { it >= lo && it <= hi } * perPoint) }
        val maxHr = 193
        val zones = listOf(1 to "Warm-up", 2 to "Fat burn", 3 to "Aerobic", 4 to "Anaerobic", 5 to "Max").map { (z, n) ->
            val lo = (maxHr * (z + 4) / 10.0).toInt(); val hi = if (z == 5) null else (maxHr * (z + 5) / 10.0).toInt()
            ZoneTimeUi(z, n, lo, hi, hrs.count { it.v >= lo && (hi == null || it.v < hi) } * 60)
        }
        return HealthDayUi(date, start, hrs, 1340, 54, hrs.minOf { it.v }.toInt(), hrs.map { it.v }.average().roundToInt(), hrs.maxOf { it.v }.toInt(),
            zones, hours(hrs), 6,
            stress, stress.map { it.v }.average().roundToInt(), stress.maxOf { it.v }.toInt(),
            bands(stress.map { it.v }, listOf(Triple("Relaxed", 1, 39), Triple("Normal", 40, 59), Triple("Medium", 60, 79), Triple("High", 80, 100)), 5), hours(stress),
            spo2, spo2.map { it.v }.average(), spo2.minOf { it.v }, bands(spo2.map { it.v }, listOf(Triple("Normal", 95, 100), Triple("Slightly low", 90, 94), Triple("Low", 70, 89)), 10),
            temp, temp.map { SeriesPoint(it.t, it.v - 2.1) }, temp.map { it.v }.average(), temp.minOf { it.v }, temp.maxOf { it.v },
            steps, day.steps, hours(steps, sum = true), 6420, 2140, day.activeMinutes, day.wornMinutes,
            8.4, 63.0, day.minutes, day.sleep, day.workouts)
    }

    private fun demoGps() = GpsUi(
        fix = false, satsUsed = 0,
        sats = listOf(SatUi("05", "GPS", 62.0, 40.0, 41.0), SatUi("12", "GPS", 28.0, 300.0, 33.0), SatUi("18", "GPS", 15.0, 120.0, null),
            SatUi("24", "GPS", 45.0, 210.0, 22.0), SatUi("65", "GLONASS", 35.0, 80.0, 19.0), SatUi("72", "GLONASS", 10.0, 250.0, null)),
        lat = null, lon = null, altM = null, speedMps = null, hdop = 2.2, utc = null,
    )

    private fun demoSettings(): List<WatchSettingUi> {
        val values = mapOf<String, Any>("hr_interval" to 1, "hr_sleep_detection" to true, "stress_monitoring" to true, "hr_alert" to false,
            "hr_alert_threshold" to 150, "lift_wrist" to true, "inactivity_warnings" to false, "time_24h" to true, "dnd" to "auto",
            "lift_wrist_when" to "always", "language" to "auto", "wear_location" to "left", "step_goal" to 8000)
        return dev.heartline.protocol.SETTINGS.map { s -> WatchSettingUi(s.key, s.label, s.type, values[s.key] ?: dev.heartline.protocol.settingDefault(s.key), s.options.map { it.value to it.label }, s.min, s.max,
            dev.heartline.protocol.settingSection(s)) }
    }
}

/** A plausible 60-minute gym session: 45 min of lifting sets, then 15 min on the treadmill. */
private fun demoCapture(): CaptureAnalysisUi {
    val start = System.currentTimeMillis() / 1000 - 7200
    val sets = (0 until 14).map { i ->
        val s = 120.0 + i * 180; val group = i / 4; val reps = 8 + (i % 3) * 2; val tempo = 2.6 + group * 0.8
        val arc = listOf(140.0, 40.0, 25.0, 12.0)[group]
        val detail = (0 until reps).map { k ->
            val d = tempo + k * (if (i % 4 == 3) 0.12 else 0.03)
            RepUi(s + 4 + k * tempo, d, d * 0.7, d * 0.3, arc * (if (k == reps - 1 && i % 4 == 3) 0.7 else 1.0 - 0.04 * kotlin.math.sin(k * 1.7)))
        }
        SetUi(s, s + 45, reps, 118 + i % 5 * 4, 132 + i % 4 * 6, tempo, detail, group, "Strength training",
            if (i % 4 == 3) 0.12 else 0.03, i % 4 == 3, 108 + group * 4, 10 + i % 4 * 3)
    }
    val rests = sets.zipWithNext { a, b -> RestUi(a.end, b.start, a.peakHr, 98 + (a.reps % 3) * 3, 24 + (a.reps % 4) * 3) }
    val hr = (0..720).map { k -> val t = k * 5.0; val inSet = sets.any { t in it.start..it.end }
        SeriesPoint(t, when { t > 2700 -> 128.0 + (t - 2700) / 60; inSet -> 130.0; else -> 104.0 } + 4 * kotlin.math.sin(k / 3.0)) }
    val motion = (0..720).map { k -> val t = k * 5.0; SeriesPoint(t, if (t > 2700) 0.45 else if (sets.any { t in it.start..it.end }) 0.6 else 0.05) }
    return CaptureAnalysisUi(
        "demo-gym", "Gym", start, 3600, 121, 158, 86,
        listOf(ZoneTimeUi(1, "Warm-up", 95, 114, 1320), ZoneTimeUi(2, "Fat burn", 114, 133, 1500), ZoneTimeUi(3, "Aerobic", 133, 152, 660), ZoneTimeUi(4, "Threshold", 152, 171, 120), ZoneTimeUi(5, "Max", 171, null, 0)),
        96.0, 412, 2140, hr, motion,
        listOf(SegmentUi(0.0, 2700.0, "lifting", 116, null), SegmentUi(2700.0, 3600.0, "walking", 134, 118)),
        sets, rests, "Watch heart rate, checked against the raw pulse", listOf("Pulse was noisy during sets; heart rate in sets comes from the watch's own reading."),
        activities = listOf(CaptureActivityUi(10.0, 2690.0, "Strength training", 2680, 0, 610, 402, 117, 150),
            CaptureActivityUi(2700.0, 3600.0, "Treadmill", 612, 683, 935, 137, 152, 171)),
        recovery = listOf(0 to 0.0, 20 to -6.2, 40 to -9.1, 60 to -10.3, 80 to -9.6, 100 to -8.8, 120 to -8.5, 140 to -6.9, 160 to -4.1).map { (t, d) -> RecoveryPointUi(t, d, 13) },
        floor = (0 until 12).map { k -> SeriesPoint(k * 300.0, if (k < 9) 100.0 + k * 3 else 140.0 + (k - 9) * 4) },
        workS = 14 * 45, restS = 13 * 135,
        cardio = listOf(CardioUi(2700.0, 3600.0, "walking", "Treadmill", 152, 165, 171, 160, 0.8, 25, 40, 73, 118, 683, 3.7)),
        pulse = demoPulse(),
    )
}

private fun demoPulse(): PulseUi {
    val fs = 25.0; val ibi = 0.52
    val beats = (0 until 23).map { k -> 0.2 + k * ibi + 0.015 * kotlin.math.sin(k * 0.9) }
    val wave = (0 until (12 * fs).toInt()).map { i -> val t = i / fs; val ph = beats.lastOrNull { it <= t }?.let { t - it } ?: 0.3
        -kotlin.math.exp(-ph / 0.09) + 0.35 * kotlin.math.exp(-((ph - 0.25) / 0.06).let { it * it }) }
    val pairs = (0 until 220).map { k -> val a = 500.0 + 25 * kotlin.math.sin(k / 9.0) + (k * 37 % 11 - 5); a to a + (k * 53 % 13 - 6) }
    return PulseUi("regular", 268, listOf(
        PulseWindowUi(810.0, 845.0, 64, 112, 114, 37.0, 0.07, "regular"), PulseWindowUi(1310.0, 1345.0, 73, 120, 121, 16.5, 0.033, "regular"),
        PulseWindowUi(1700.0, 1725.0, 40, 96, 117, 60.0, 0.21, "unreliable"), PulseWindowUi(2410.0, 2440.0, 55, 115, 116, 18.0, 0.034, "regular"),
    ), 0.05, 0.82, 0, pairs, wave, fs, 1318.0, beats, listOf("Wellness estimate from the watch's optical sensor, not a medical diagnosis."))
}
