# Public API

Types live in `dev.heartline.data`, except models/enums in
`dev.heartline.data.model`. All repository constructors take `HeartlineDatabase`.
`from`/`to` and `since` arguments are inclusive epoch seconds. Defaults shown below
are part of the API. SQL connections and schema internals are not public.

```kotlin
class HeartlineDatabase private constructor(...) {
    companion object { fun get(context: Context): HeartlineDatabase }
    val heartRate: HeartRateRepository
    val activity: ActivityRepository
    val health: HealthRepository
    val events: EventRepository
    val meta: MetaRepository
    val recordings: RecordingRepository
    val checks: CheckRepository
    val insights: InsightRepository
}

class HeartRateRepository(db: HeartlineDatabase) {
    suspend fun add(ts: Double, bpm: Int)
    suspend fun since(ts: Double): List<HrSample>
    suspend fun range(from: Double, to: Double): List<HrSample>
    suspend fun stats(since: Double): HrStats
    fun observeSince(ts: Double): Flow<List<HrSample>>
    fun observeStats(since: Double): Flow<HrStats>
}

class ActivityRepository(db: HeartlineDatabase) {
    suspend fun upsert(rows: List<ActivityRow>): Int // newly inserted minutes
    suspend fun lastTs(): Long?
    suspend fun prevKind(ts: Double): Int?
    suspend fun since(ts: Double): List<ActivityRow>
    suspend fun range(from: Double, to: Double): List<ActivityRow>
    suspend fun minutes(from: Double, to: Double): List<ActivityMinute>
    suspend fun summary(from: Double, to: Double): ActivitySummary
    fun observeRange(from: Double, to: Double): Flow<List<ActivityRow>>
    fun observeSummary(from: Double, to: Double): Flow<ActivitySummary>
}

class HealthRepository(db: HeartlineDatabase) {
    suspend fun upsert(points: List<HealthPoint>): Int // new (type, ts) pairs
    suspend fun series(type: String, from: Double, to: Double = Double.MAX_VALUE): List<HealthPoint>
    suspend fun byType(from: Double, to: Double = Double.MAX_VALUE): Map<String, List<HealthPoint>>
    suspend fun lastTs(type: String): Long?
    fun observeSeries(type: String, from: Double, to: Double = Double.MAX_VALUE): Flow<List<HealthPoint>>
    fun observeByType(from: Double, to: Double = Double.MAX_VALUE): Flow<Map<String, List<HealthPoint>>>
    suspend fun byDay(from: Double, zone: ZoneId = ZoneId.systemDefault()): Map<String, Map<LocalDate, Double>>
    suspend fun upsertWorkout(workout: Workout): Boolean // new primary key
    suspend fun workouts(from: Double, to: Double = Double.MAX_VALUE): List<Workout>
    suspend fun workoutLastStart(): Long?
    fun observeWorkouts(from: Double, to: Double = Double.MAX_VALUE): Flow<List<Workout>>
    suspend fun addRawFetch(fetch: RawFetch)
    suspend fun addRawFetch(ts: Double, type: String, start: Double?, reply: ByteArray, data: ByteArray)
    suspend fun rawFetches(from: Double, to: Double = Double.MAX_VALUE): List<RawFetch>
    fun observeRawFetches(from: Double, to: Double = Double.MAX_VALUE): Flow<List<RawFetch>>
}

class EventRepository(db: HeartlineDatabase) {
    suspend fun add(level: String, msg: String) // now
    suspend fun add(ts: Double, level: String, msg: String)
    suspend fun recent(limit: Int): List<Event>
    fun observeRecent(limit: Int): Flow<List<Event>>
}

class MetaRepository(db: HeartlineDatabase) {
    suspend fun set(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun all(): Map<String, String?>
    fun observe(key: String): Flow<String?>
    fun observeAll(): Flow<Map<String, String?>>
}

class RecordingRepository(db: HeartlineDatabase) {
    suspend fun upsert(recording: Recording): Boolean
    suspend fun list(): List<Recording>
    suspend fun delete(file: String)
    suspend fun setAnalysis(file: String, analysis: String?)
    fun observeAll(): Flow<List<Recording>>
}

class CheckRepository(db: HeartlineDatabase) {
    suspend fun upsert(check: Check): Boolean
    suspend fun list(from: Double = -Double.MAX_VALUE, to: Double = Double.MAX_VALUE): List<Check>
    suspend fun delete(start: Double)
    fun observeRange(from: Double = -Double.MAX_VALUE, to: Double = Double.MAX_VALUE): Flow<List<Check>>
}

class InsightRepository(db: HeartlineDatabase) {
    suspend fun save(session: InsightSession): Boolean
    suspend fun cached(file: String, mtime: Double): String? // result JSON
    suspend fun list(): List<InsightSession>
    suspend fun hrvByDay(zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double>
    suspend fun latestRhythm(): RhythmReading?
    fun observeAll(): Flow<List<InsightSession>>
    fun observeHrvByDay(zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, Double>>
    fun observeLatestRhythm(): Flow<RhythmReading?>
}

class AppSettings(context: Context) : AutoCloseable {
    val theme: StateFlow<Theme>
    val units: StateFlow<Units>
    val profile: StateFlow<Profile>
    val watch: StateFlow<Watch>
    val lastWrittenWatchSettings: StateFlow<Map<String, String>>
    val notificationForwarding: StateFlow<NotificationForwarding>
    val rawSensorDefaultSelection: StateFlow<Set<RawSensor>>
    val onboardingDone: StateFlow<Boolean>
    val autoSyncIntervalMinutes: StateFlow<Long>
    fun setTheme(value: Theme)
    fun setUnits(value: Units)
    fun setProfile(value: Profile)
    fun setWatch(value: Watch)
    fun setLastWrittenWatchSettings(value: Map<String, String>)
    fun setLastWrittenWatchSetting(key: String, value: String)
    fun setNotificationForwarding(value: NotificationForwarding)
    fun setRawSensorDefaultSelection(value: Set<RawSensor>)
    fun setOnboardingDone(value: Boolean)
    fun setAutoSyncIntervalMinutes(value: Long)
    override fun close()
}

class KeyStore(context: Context) {
    fun save(key: ByteArray)
    fun load(): ByteArray?
    fun clear()
    companion object {
        fun validateHex(hex: String): Boolean
        fun parseHex(hex: String): ByteArray // IllegalArgumentException on invalid hex
    }
}
class PcImporter(db: HeartlineDatabase) { suspend fun import(file: File): ImportReport }
object Exporter { suspend fun exportCsvZip(db: HeartlineDatabase, out: OutputStream, from: Double, to: Double) }
object Hex {
    fun isValidAuthKey(hex: String): Boolean
    fun authKey(hex: String): ByteArray
    fun encode(bytes: ByteArray): String
}
object Csv { fun writeRow(out: Writer, cells: List<Any?>) }
fun hrStats(samples: List<HrSample>): HrStats
fun activityMinutes(rows: List<ActivityRow>, prevKind: Int? = null): List<ActivityMinute>
fun activitySummary(samples: List<ActivityMinute>): ActivitySummary
```

Model constructor signatures are in [Models.kt](src/main/java/dev/heartline/data/model/Models.kt)
and [SettingsModels.kt](src/main/java/dev/heartline/data/model/SettingsModels.kt).
`ImportReport(counts: Map<String, Int>, updatedCounts: Map<String, Int>)` also has
`added: Int` and `updated: Int` totals. Lists and maps returned by queries are
detached from the database and never contain live cursors.
