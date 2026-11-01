package dev.heartline.data

import android.content.Context
import android.content.SharedPreferences
import dev.heartline.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AppSettings(context: Context) : AutoCloseable {
    private val prefs = context.applicationContext.getSharedPreferences("heartline_settings", Context.MODE_PRIVATE)
    private val themeState = MutableStateFlow(enumValue(prefs.getString("theme", null), Theme.SYSTEM))
    private val unitsState = MutableStateFlow(enumValue(prefs.getString("units", null), Units.METRIC))
    private val profileState = MutableStateFlow(readProfile())
    private val watchState = MutableStateFlow(readWatch())
    private val watchSettingsState = MutableStateFlow(readWatchSettings())
    private val notificationsState = MutableStateFlow(readNotifications())
    private val sensorsState = MutableStateFlow(readSensors())
    private val onboardingState = MutableStateFlow(prefs.getBoolean("onboarding", false))
    private val syncState = MutableStateFlow(prefs.getLong("sync_minutes", 60))

    val theme: StateFlow<Theme> = themeState.asStateFlow()
    val units: StateFlow<Units> = unitsState.asStateFlow()
    val profile: StateFlow<Profile> = profileState.asStateFlow()
    val watch: StateFlow<Watch> = watchState.asStateFlow()
    val lastWrittenWatchSettings: StateFlow<Map<String, String>> = watchSettingsState.asStateFlow()
    val notificationForwarding: StateFlow<NotificationForwarding> = notificationsState.asStateFlow()
    val rawSensorDefaultSelection: StateFlow<Set<RawSensor>> = sensorsState.asStateFlow()
    val onboardingDone: StateFlow<Boolean> = onboardingState.asStateFlow()
    /** Zero disables periodic sync. */
    val autoSyncIntervalMinutes: StateFlow<Long> = syncState.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun setTheme(value: Theme) = edit { putString("theme", value.name) }
    fun setUnits(value: Units) = edit { putString("units", value.name) }
    fun setProfile(value: Profile) {
        require(value.age == null || value.age >= 0)
        require(value.weightKg == null || value.weightKg.isFinite() && value.weightKg > 0)
        require(value.heightCm == null || value.heightCm.isFinite() && value.heightCm > 0)
        require(value.maxHr == null || value.maxHr > 0)
        require(value.restingHr == null || value.restingHr > 0)
        val json = JSONObject().put("age", value.age).put("sex", value.sex?.name)
            .put("weight_kg", value.weightKg).put("height_cm", value.heightCm)
            .put("max_hr", value.maxHr).put("resting_hr", value.restingHr)
        edit { putString("profile", json.toString()) }
    }
    fun setWatch(value: Watch) = edit { putString("watch_mac", value.mac); putString("watch_name", value.name) }
    fun setLastWrittenWatchSettings(value: Map<String, String>) = edit {
        putString("watch_settings", JSONObject(value.toMap()).toString())
    }
    /** Record only successful writes: the GTR 2e has no settings readback (README). */
    @Synchronized fun setLastWrittenWatchSetting(key: String, value: String) {
        setLastWrittenWatchSettings(readWatchSettings() + (key to value))
    }
    fun setNotificationForwarding(value: NotificationForwarding) {
        require((value.quietHoursStart == null) == (value.quietHoursEnd == null))
        require(value.quietHoursStart == null || value.quietHoursStart in 0..1439)
        require(value.quietHoursEnd == null || value.quietHoursEnd in 0..1439)
        val json = JSONObject().put("enabled", value.enabled)
            .put("packages", JSONArray(value.allowedPackages.toList().sorted()))
            .put("quiet_start", value.quietHoursStart).put("quiet_end", value.quietHoursEnd)
            .put("calls", value.forwardCalls)
        edit { putString("notifications", json.toString()) }
    }
    fun setRawSensorDefaultSelection(value: Set<RawSensor>) = edit {
        putStringSet("sensors", value.map { it.name }.toSet())
    }
    fun setOnboardingDone(value: Boolean) = edit { putBoolean("onboarding", value) }
    fun setAutoSyncIntervalMinutes(value: Long) {
        require(value >= 0)
        edit { putLong("sync_minutes", value) }
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        reload()
    }
    @Synchronized private fun reload() {
        themeState.value = enumValue(prefs.getString("theme", null), Theme.SYSTEM)
        unitsState.value = enumValue(prefs.getString("units", null), Units.METRIC)
        profileState.value = readProfile()
        watchState.value = readWatch()
        watchSettingsState.value = readWatchSettings()
        notificationsState.value = readNotifications()
        sensorsState.value = readSensors()
        onboardingState.value = prefs.getBoolean("onboarding", false)
        syncState.value = prefs.getLong("sync_minutes", 60)
    }
    private fun json(key: String): JSONObject = try {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    } catch (_: JSONException) { JSONObject() }
    private fun readProfile(): Profile {
        val j = json("profile")
        return Profile(j.intOrNull("age"), j.optString("sex").takeIf { it.isNotEmpty() }?.let {
            Sex.entries.firstOrNull { sex -> sex.name == it }
        }, j.doubleOrNull("weight_kg"), j.doubleOrNull("height_cm"), j.intOrNull("max_hr"), j.intOrNull("resting_hr"))
    }
    private fun readWatch() = Watch(prefs.getString("watch_mac", null), prefs.getString("watch_name", null))
    private fun readWatchSettings(): Map<String, String> {
        val j = json("watch_settings")
        return j.keys().asSequence().associateWith { j.getString(it) }
    }
    private fun readNotifications(): NotificationForwarding {
        val j = json("notifications")
        val a = j.optJSONArray("packages") ?: JSONArray()
        return NotificationForwarding(j.optBoolean("enabled"), (0 until a.length()).map { a.getString(it) }.toSet(),
            j.intOrNull("quiet_start"), j.intOrNull("quiet_end"), j.optBoolean("calls"))
    }
    private fun readSensors(): Set<RawSensor> = prefs.getStringSet("sensors", null)?.mapNotNull { name ->
        RawSensor.entries.firstOrNull { it.name == name }
    }?.toSet() ?: setOf(RawSensor.ACCEL, RawSensor.PPG)
    override fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}

private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
