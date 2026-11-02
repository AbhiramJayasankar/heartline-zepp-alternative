@file:Suppress("MissingPermission")
package dev.heartline.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import dev.heartline.data.KeyStore
import dev.heartline.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One instance belongs to WatchService. All commands run on the service's serial dispatcher. */
class WatchConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val persistence: WatchPersistence,
    private val keys: KeyStore = KeyStore(context),
) {
    private val link = MutableStateFlow(ConnectionStatus())
    val connection = link.asStateFlow()
    private val readyState = MutableStateFlow(false)
    val ready = readyState.asStateFlow()
    private val batteryState = MutableStateFlow<BatteryInfo?>(null)
    val battery = batteryState.asStateFlow()
    private val infoState = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo = infoState.asStateFlow()
    private val hrState = MutableStateFlow(HrStatus())
    val heartRate = hrState.asStateFlow()
    private val stepsState = MutableStateFlow<StepsNow?>(null)
    val steps = stepsState.asStateFlow()
    private val syncState = MutableStateFlow(SyncStatus())
    val sync = syncState.asStateFlow()
    private val rawState = MutableStateFlow(RawStatus())
    val raw = rawState.asStateFlow()
    private val rawEvents = MutableSharedFlow<RawUpdate>(extraBufferCapacity = 1024)
    val rawUpdates = rawEvents.asSharedFlow()
    private val gpsState = MutableStateFlow<GpsState?>(null)
    val gps = gpsState.asStateFlow()
    private val watchEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val deviceEvents = watchEvents.asSharedFlow()
    private val actions = Mutex()
    private val syncLock = Mutex()
    private val replies = mutableMapOf<UUID, Channel<GattNotification>>()
    private var client: GattClient? = null
    private var runner: Job? = null
    private var ping: Job? = null
    private var watchdog: Job? = null
    private var callEnd: Job? = null
    private var recording: RawRecording? = null
    private var wantConnected = false
    private var wantHr = false
    private var wantRaw = false
    private var mac: String? = null
    private val quietReconnects = QuietReconnects()
    private var reconnectNotice: Job? = null
    init {
        scope.launch {
            while (isActive) {
                delay(60 * 60_000L)
                quietReconnects.summary()?.let { log("info", it) }
            }
        }
    }
    /** Successful transfers only; WatchService launches export outside the BLE command queue. */
    var onSuccessfulSync: (() -> Unit)? = null

    fun connect(mac: String) {
        wantConnected = true; this.mac = mac
        if (runner?.isActive == true) return
        runner = scope.launch {
            var failures = 0
            var lastError: String? = null
            while (isActive && wantConnected) {
                var receiver: Job? = null
                try {
                    link.value = ConnectionStatus(ConnectionState.CONNECTING, error = lastError)
                    val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth unavailable")
                    if (!adapter.isEnabled) {
                        // Wait quietly instead of failing every few seconds; one log line per outage.
                        link.value = ConnectionStatus(ConnectionState.ERROR, error = BLUETOOTH_OFF)
                        if (lastError != BLUETOOTH_OFF) { log("warn", "Bluetooth is off. Heartline reconnects when it's back on."); lastError = BLUETOOTH_OFF }
                        while (!adapter.isEnabled) delay(2000)
                        link.value = ConnectionStatus(ConnectionState.CONNECTING, error = lastError)
                    }
                    val key = keys.load() ?: error("Save the watch key first")
                    var c = GattClient(context); client = c
                    try { c.connect(remoteDevice(adapter, mac)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        c.close()
                        link.value = ConnectionStatus(ConnectionState.SCANNING, error = lastError)
                        val found = scan(mac)
                        // While its bonded phone (Zepp) is connected the watch still advertises, but non-connectably:
                        // scanners see it and every connection attempt times out. Say so instead of retrying blind.
                        if (!found.isConnectable) throw IllegalStateException(WATCH_BUSY)
                        val device = found.device
                        link.value = ConnectionStatus(ConnectionState.CONNECTING, error = lastError)
                        c = GattClient(context); client = c
                        // Connectable and in range, yet refused: most often another phone grabbed it in between.
                        try { c.connect(device) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw IllegalStateException(WATCH_BUSY, e) }
                    }
                    c.discoverServices(); c.connectionPriority()
                    receiver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try { c.notifications.collect { receive(it) } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { log("error", "Notification processing failed: ${e.message}"); c.close() }
                    }
                    link.value = ConnectionStatus(ConnectionState.AUTHENTICATING)
                    route(setOf(Uuids.AUTH)) { channel ->
                        c.enableNotification(Uuids.AUTH)
                        try {
                            c.write(Uuids.AUTH, Auth.REQUEST_RANDOM)
                            c.write(Uuids.AUTH, Auth.response(key, reply(c, channel, 10_000).value))
                            check(Auth.isSuccess(reply(c, channel, 10_000).value)) { "Authentication rejected; the key may be stale" }
                        } finally { key.fill(0); c.enableNotification(Uuids.AUTH, false) }
                    }
                    link.value = ConnectionStatus(ConnectionState.CONNECTED, now())
                    failures = 0; lastError = null
                    optional("Battery read") { readBattery() }
                    val info = linkedMapOf<String, String>()
                    for ((name, uuid) in DEVICE_INFO) optional("Read $name") { info[name] = decodeInfo(c.read(uuid)) }
                    infoState.value = info
                    optional("Live steps/events") {
                        stepsState.value = parseRealtimeSteps(c.read(Uuids.REALTIME_STEPS))
                        c.enableNotification(Uuids.REALTIME_STEPS); c.enableNotification(Uuids.DEVICE_EVENT)
                    }
                    if (wantHr) setLiveHr(true)
                    if (wantRaw) startRaw(rawState.value.sensors)
                    readyState.value = true
                    reconnectNotice?.cancel(); reconnectNotice = null
                    for (message in quietReconnects.authenticated(android.os.SystemClock.elapsedRealtime())) log("info", message)
                    c.disconnected.await()
                    quietReconnects.dropped(android.os.SystemClock.elapsedRealtime())
                    reconnectNotice?.cancel()
                    reconnectNotice = scope.launch {
                        delay(30_001)
                        quietReconnects.overdue(android.os.SystemClock.elapsedRealtime())?.let { log("warn", it) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    link.value = ConnectionStatus(ConnectionState.ERROR, error = e.message)
                    // Retries repeat the same failure every backoff step; log it once until something changes.
                    if (e.message != lastError) log("error", "Connection failed: ${e.message}")
                    lastError = e.message
                } finally {
                    withContext(NonCancellable) {
                        readyState.value = false
                        ping?.cancel(); watchdog?.cancel(); callEnd?.cancel()
                        client?.close(); client = null; receiver?.cancelAndJoin()
                        closeRecording()
                        hrState.update { it.copy(streaming = false) }
                        if (link.value.state != ConnectionState.ERROR) link.value = ConnectionStatus()
                    }
                }
                if (wantConnected) delay(SyncPolicy.reconnectDelay(failures++))
            }
        }
    }
    suspend fun disconnect() {
        reconnectNotice?.cancel(); reconnectNotice = null; quietReconnects.clearPending()
        wantConnected = false; wantHr = false; wantRaw = false
        if (link.value.state == ConnectionState.CONNECTED) {
            try {
                withTimeout(5000) {
                    if (hrState.value.streaming) setLiveHr(false)
                    if (rawState.value.streaming) stopRaw()
                }
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (e: Exception) { log("warn", "Stopping streams: ${e.message}") }
        }
        runner?.cancelAndJoin(); runner = null
        link.value = ConnectionStatus()
        log("info", "Disconnected")
    }
    /** The watch uses a random static address (top two bits set); a plain getRemoteDevice assumes a public one. */
    private fun remoteDevice(adapter: android.bluetooth.BluetoothAdapter, mac: String): BluetoothDevice {
        val random = (mac.substring(0, 2).toInt(16) and 0xC0) == 0xC0
        return if (random && android.os.Build.VERSION.SDK_INT >= 33) adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        else adapter.getRemoteDevice(mac)
    }
    private suspend fun scan(mac: String): ScanResult = withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            val scanner = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner) { "Bluetooth scanner unavailable" }
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    if (continuation.isActive) { scanner.stopScan(this); continuation.resume(result) }
                }
                override fun onScanFailed(code: Int) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Scan failed: $code")) }
            }
            continuation.invokeOnCancellation { scanner.stopScan(callback) }
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(mac).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }
    }
    private fun requireClient(): GattClient {
        check(link.value.state == ConnectionState.CONNECTED) { "The watch is not connected" }
        return client ?: error("The watch is not connected")
    }
    private suspend fun receive(n: GattNotification) {
        replies[n.uuid]?.let { it.send(n); return }
        when (n.uuid) {
            Uuids.HR_MEASURE -> {
                val bpm = parseHeartRate(n.value)
                if (bpm > 0) { val ts = System.currentTimeMillis() / 1000.0; persistence.heartRate(ts, bpm); hrState.update { it.copy(bpm = bpm, ts = ts.toLong()) } }
            }
            Uuids.REALTIME_STEPS -> parseRealtimeSteps(n.value)?.let { stepsState.value = it }
            Uuids.DEVICE_EVENT -> {
                log("info", "Watch event: ${deviceEventName(n.value).replace('_', ' ')}")
                watchEvents.emit(n.value.copyOf())
            }
            Uuids.RAW_DATA -> {
                probeSink?.invoke(n.value)
                val r = recording ?: return
                val packet = parseRaw(n.value)
                // Raw packets are saved to the recording; ones the parser doesn't know are logged so they can be studied.
                if (packet is RawPacket.Unknown) BleLog.note("raw type ${packet.type}: ${n.value.joinToString("") { "%02x".format(it) }}")
                r.feed(packet)
                if (packet is RawPacket.GpsText) gpsState.value = r.gps.state
                rawEvents.emit(RawUpdate(packet))
            }
        }
    }
    private suspend fun <T> route(uuids: Set<UUID>, block: suspend (Channel<GattNotification>) -> T): T {
        val channel = Channel<GattNotification>(Channel.UNLIMITED)
        check(uuids.none { it in replies }) { "A transfer is already listening" }
        uuids.forEach { replies[it] = channel }
        try { return block(channel) } finally { uuids.forEach { replies.remove(it) }; channel.close() }
    }
    private suspend fun reply(c: GattClient, channel: Channel<GattNotification>, timeoutMs: Long): GattNotification = withTimeout(timeoutMs) {
        select {
            channel.onReceive { it }
            c.disconnected.onAwait { error("The watch disconnected during transfer") }
        }
    }
    suspend fun readBattery() = actions.withLock { batteryState.value = parseBattery(requireClient().read(Uuids.BATTERY)) }
    suspend fun setLiveHr(on: Boolean) = actions.withLock {
        wantHr = on
        val c = requireClient()
        if (on == hrState.value.streaming) return@withLock
        ping?.cancel()
        if (on) {
            c.enableNotification(Uuids.HR_MEASURE)
            c.write(Uuids.HR_CONTROL, HrCommands.STOP_MANUAL, true)
            c.write(Uuids.HR_CONTROL, HrCommands.START_CONTINUOUS, true)
            ping = scope.launch {
                while (isActive) { delay(10_000); optional("Heart-rate keep-alive") { actions.withLock { c.write(Uuids.HR_CONTROL, HrCommands.PING, true) } } }
            }
        } else { c.write(Uuids.HR_CONTROL, HrCommands.STOP_CONTINUOUS, true); c.enableNotification(Uuids.HR_MEASURE, false) }
        hrState.update { it.copy(streaming = on) }
        log("info", "Live heart rate ${if (on) "started" else "stopped"}")
    }
    suspend fun startRaw(sensors: Set<RawSensor>): File = actions.withLock {
        require(sensors.isNotEmpty()) { "Select at least one sensor" }
        val c = requireClient()
        if (recording != null && rawState.value.sensors == sensors) return@withLock recording!!.file
        if (recording != null) stopRawLocked(c)
        wantRaw = true
        val r = RawRecording(File(context.filesDir, "raw"), sensors) { rawEvents.tryEmit(RawUpdate(levels = listOf(it))) }
        recording = r; gpsState.value = null
        rawState.value = RawStatus(false, sensors, r.file)
        try {
            c.enableNotification(Uuids.RAW_DATA); c.enableNotification(Uuids.RAW_CTRL)
            rawBegin(c, sensors)
            rawState.value = RawStatus(true, sensors, r.file)
            watchdog = scope.launch {
                while (isActive) {
                    delay(200); r.flush()
                    if (r.hasAudio && System.nanoTime() - r.lastMic >= 4_000_000_000 && System.nanoTime() - r.lastKick >= 4_000_000_000) {
                        r.lastKick = System.nanoTime()
                        log("warn", "Microphone went quiet; restarting the raw stream")
                        optional("Microphone restart") { actions.withLock { c.write(Uuids.RAW_CTRL, RAW_STOP); delay(300); rawBegin(c, sensors) } }
                    }
                }
            }
            log("info", "Raw stream started: ${r.file.name}")
            r.file
        } catch (e: Throwable) {
            wantRaw = false
            // Cancellation between the three start writes must still stop the watch and index the partial file.
            withContext(NonCancellable) {
                try {
                    if (!c.disconnected.isCompleted) {
                        c.write(Uuids.RAW_CTRL, RAW_STOP)
                        c.enableNotification(Uuids.RAW_CTRL, false); c.enableNotification(Uuids.RAW_DATA, false)
                    }
                } catch (cleanup: Exception) { log("warn", "Raw startup cleanup: ${cleanup.message}") }
                finally { closeRecording() }
            }
            throw e
        }
    }
    private suspend fun rawBegin(c: GattClient, sensors: Set<RawSensor>, commands: () -> List<ByteArray> = { rawStartCommands(rawMask(sensors)) }) {
        for (cmd in commands()) { c.write(Uuids.RAW_CTRL, cmd); delay(400) }
    }
    @Volatile private var probeSink: ((ByteArray) -> Unit)? = null

    /**
     * Starts the raw stream with an arbitrary sensor [mask] for [seconds] and reports what came back: packet types,
     * counts, sizes and a sample of each. Used to find what the undocumented mask bits switch on.
     */
    suspend fun probeRawMask(mask: Int, seconds: Long = 8): String = actions.withLock {
        val c = requireClient()
        check(recording == null) { "Stop raw streaming first" }
        val packets = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        probeSink = { packets.add(it) }
        try {
            c.enableNotification(Uuids.RAW_DATA); c.enableNotification(Uuids.RAW_CTRL)
            rawBegin(c, setOf()) { rawStartCommands(mask) }
            delay(seconds * 1000)
        } finally {
            withContext(NonCancellable) {
                runCatching { c.write(Uuids.RAW_CTRL, RAW_STOP); c.enableNotification(Uuids.RAW_CTRL, false); c.enableNotification(Uuids.RAW_DATA, false) }
                probeSink = null
            }
        }
        val byType = packets.groupBy { it[0].toInt() and 255 }
        val summary = if (byType.isEmpty()) "mask 0x%02x: nothing".format(mask) else "mask 0x%02x: ".format(mask) + byType.entries.sortedBy { it.key }.joinToString("; ") { (t, list) ->
            "type 0x%02x x%d (%.0f/s, %s bytes)".format(t, list.size, list.size / seconds.toDouble(), list.map { it.size }.distinct().sorted().take(3).joinToString("/"))
        }
        BleLog.note("probe $summary")
        byType.values.forEach { list -> BleLog.note("probe sample: " + list.first().joinToString("") { "%02x".format(it) }) }
        log("info", "Sensor probe, $summary")
        summary
    }
    suspend fun stopRaw(): File? = actions.withLock { wantRaw = false; val file = recording?.file; stopRawLocked(requireClient()); file }
    private suspend fun stopRawLocked(c: GattClient) {
        watchdog?.cancel(); watchdog = null
        if (recording == null) return
        try { c.write(Uuids.RAW_CTRL, RAW_STOP); c.enableNotification(Uuids.RAW_CTRL, false); c.enableNotification(Uuids.RAW_DATA, false) }
        finally { withContext(NonCancellable) { closeRecording() } }
    }
    private suspend fun closeRecording() {
        val r = recording ?: return
        recording = null
        rawState.update { it.copy(streaming = false) }
        try { r.close() } catch (e: Exception) { log("warn", "Audio close: ${e.message}") }
        persistence.recording(r.file, r.start, System.currentTimeMillis() / 1000.0 - r.start, r.sensors, r.hasAudio)
    }
    suspend fun vibrate() {
        val c = requireClient()
        actions.withLock { c.write(Uuids.ALERT_LEVEL, ALERT_FIND) }; delay(3000)
        actions.withLock { c.write(Uuids.ALERT_LEVEL, ALERT_OFF) }
    }
    suspend fun notify(title: String, body: String, kind: NotificationKind = NotificationKind.MESSAGE, app: String = "Heartline") {
        val c = requireClient()
        actions.withLock { chunks(c, notificationPayload(title, body, kind, app)) }
        if (kind == NotificationKind.CALL) {
            callEnd?.cancel(); callEnd = scope.launch { delay(15_000); callEnd = null; if (client === c) optional("End call alert") { endCall() } }
        }
        log("info", "Sent notification: $title")
    }
    suspend fun endCall() { callEnd?.cancel(); callEnd = null; actions.withLock { chunks(requireClient(), CALL_END) } }
    suspend fun sendWeather(weather: WatchWeather, fahrenheit: Boolean = false) = actions.withLock {
        val c = requireClient()
        for (packet in weatherPackets(weather, fahrenheit)) chunks(c, packet.payload, packet.type)
    }
    suspend fun sendMusic(music: WatchMusic, volumePercent: Int) = actions.withLock {
        val c = requireClient()
        chunks(c, musicPayload(music), 3); chunks(c, musicVolumePayload(volumePercent), 3)
    }
    suspend fun acknowledgeFindPhone() = actions.withLock { requireClient().write(Uuids.CONFIG, FIND_PHONE_ACK) }
    private suspend fun chunks(c: GattClient, payload: ByteArray, type: Int = 0) {
        // watch.py:notify: keep MTU 23 here even after negotiating 247 for raw data.
        for (chunk in chunkedWrites(payload, mtu = 23, type = type)) c.write(Uuids.CHUNKED, chunk)
    }
    suspend fun syncTime() = actions.withLock {
        val c = requireClient(); c.write(Uuids.CURRENT_TIME, currentTimeBytes(ZonedDateTime.now()), true)
        log("info", "Watch clock set: ${parseCurrentTime(c.read(Uuids.CURRENT_TIME))}")
    }
    suspend fun applySetting(key: String, value: Any) = actions.withLock {
        val c = requireClient()
        val valid = validateSetting(key, value)
        val (target, cmd) = settingCommand(key, valid, persistence.settingValues())
        if (target == SettingTarget.HR) c.write(Uuids.HR_CONTROL, cmd, true)
        else route(setOf(Uuids.CONFIG)) { channel ->
            c.enableNotification(Uuids.CONFIG)
            try {
                c.write(Uuids.CONFIG, cmd)
                val answer = reply(c, channel, 3000).value
                check(answer.size >= 2 && answer[0] == 0x10.toByte() && answer[1] == cmd[0]) { "Watch did not accept $key" }
            } finally { c.enableNotification(Uuids.CONFIG, false) }
        }
        persistence.settingWritten(key, valid)
        log("info", "Set $key to $valid")
    }
    suspend fun writeAlarm(command: ByteArray) = actions.withLock {
        require(command.size == 5 && command[0] == 2.toByte())
        val c = requireClient()
        route(setOf(Uuids.CONFIG)) { channel ->
            c.enableNotification(Uuids.CONFIG)
            try {
                c.write(Uuids.CONFIG, command)
                withTimeout(3000) {
                    while (true) {
                        val answer = reply(c, channel, 3000).value
                        if (answer.size < 2 || answer[0] != 0x10.toByte() || answer[1] != 2.toByte()) continue
                        check(alarmWriteAccepted(answer)) { "Watch rejected alarm slot ${command[1].toInt() and 15}" }
                        break
                    }
                }
            } finally {
                withContext(NonCancellable) { if (!c.disconnected.isCompleted) c.enableNotification(Uuids.CONFIG, false) }
            }
        }
    }
    suspend fun writeCalendarReminder(payload: ByteArray) = actions.withLock {
        require(payload.size >= 20 && payload[0] == 0x0b.toByte())
        val c = requireClient()
        // HuamiSupport.sendCalendarEventsAsReminders/writeToChunkedOld: type 2 has no notification extra header.
        for (chunk in chunkedWrites(payload, mtu = 23, type = 2)) c.write(Uuids.CHUNKED, chunk)
    }
    private suspend fun fetch(type: FetchType, since: Long): HistoryFetch {
        val c = requireClient()
        return route(setOf(Uuids.ACT_CTRL, Uuids.ACT_DATA)) { channel ->
            try {
                actions.withLock {
                    c.enableNotification(Uuids.ACT_CTRL); c.enableNotification(Uuids.ACT_DATA)
                }
                transferHistory(type, Instant.ofEpochSecond(since).atZone(ZoneId.systemDefault()),
                    send = { cmd -> actions.withLock { c.write(Uuids.ACT_CTRL, cmd) } },
                    receive = { timeout -> reply(c, channel, timeout) })
            } finally {
                // Never send the 0x03 acknowledgement: it deletes data from the watch.
                withContext(NonCancellable) {
                    if (!c.disconnected.isCompleted) actions.withLock {
                        c.enableNotification(Uuids.ACT_DATA, false); c.enableNotification(Uuids.ACT_CTRL, false)
                    }
                }
            }
        }
    }
    suspend fun syncActivity() = syncLock.withLock {
        requireClient(); syncState.value = SyncStatus(true, "Syncing activity…")
        try {
            val now = now(); var since = SyncPolicy.activitySince(persistence.lastActivity(), now); var added = 0
            for (round in 0 until 20) {
                if (since > now) break
                val f = fetch(FetchType.ACTIVITY, since)
                val rows = f.start?.let { parseActivity(f.data, it) }.orEmpty()
                if (rows.isEmpty()) break
                added += persistence.activity(rows)
                since = SyncPolicy.next(since, rows.last().ts) ?: break
            }
            persistence.meta("activity_last_sync", now().toString())
            log("info", "Activity sync done: $added new minutes")
            onSuccessfulSync?.invoke()
        } finally { syncState.value = SyncStatus() }
    }
    suspend fun syncHealth() = syncLock.withLock {
        requireClient(); syncState.value = SyncStatus(true, "Syncing health…")
        try {
            for (type in FetchType.entries.filter { it != FetchType.ACTIVITY && it != FetchType.WORKOUT_DETAILS }) {
                currentCoroutineContext().ensureActive()
                requireClient()
                optional("Sync ${type.key}") { if (type == FetchType.WORKOUTS) syncWorkouts() else syncHealthType(type) }
            }
            persistence.meta("health_last_sync", now().toString()); log("info", "Health sync done")
            onSuccessfulSync?.invoke()
        } finally { syncState.value = SyncStatus() }
    }
    private suspend fun syncHealthType(type: FetchType) {
        var since = SyncPolicy.healthSince(persistence.lastHealth(type.key), now())
        for (round in 0 until 5) {
            val f = fetch(type, since)
            if (f.data.isEmpty()) break
            persistence.rawFetch(type.key, f.reply, f.start, f.data)
            val rows = if (type == FetchType.RESTING_HR) parseRestingHeartRate(f.data) else parseHealth(type, f.data, f.start, now())
            if (rows.all { it.value == null }) break
            persistence.health(rows)
            if (type.consumedOnTransfer) break
            since = SyncPolicy.next(since, rows.last().ts) ?: break
            if (since > now()) break
        }
        persistence.meta("health_${type.key}_last_sync", now().toString())
    }
    private suspend fun syncWorkouts() {
        var since = SyncPolicy.healthSince(persistence.lastWorkout(), now())
        for (round in 0 until 20) {
            val f = fetch(FetchType.WORKOUTS, since)
            val summary = parseWorkoutSummary(f.data) ?: break
            persistence.rawFetch("workouts", f.reply, f.start, f.data)
            val d = fetch(FetchType.WORKOUT_DETAILS, summary.start)
            if (d.data.isNotEmpty()) persistence.rawFetch("workout_details", d.reply, d.start, d.data)
            persistence.workout(summary.copy(track = parseWorkoutDetails(d.data)))
            since = SyncPolicy.next(since, summary.start) ?: break
        }
        persistence.meta("health_workouts_last_sync", now().toString())
    }
    private suspend fun optional(name: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("warn", "$name failed: ${e.message}") }
    }
    private suspend fun log(level: String, message: String) { BleLog.note("$level: $message"); persistence.event(level, message) }
    private fun now() = System.currentTimeMillis() / 1000

    companion object {
        const val BLUETOOTH_OFF = "Turn on Bluetooth"
        const val WATCH_BUSY = "The watch is nearby but won't accept a connection. Another phone or the Zepp app is probably connected to it: turn off Bluetooth on that phone."
    }
}
