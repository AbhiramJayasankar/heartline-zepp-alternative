package dev.heartline.data.model

enum class Theme { SYSTEM, LIGHT, DARK }
enum class Units { METRIC, IMPERIAL }
enum class Sex { MALE, FEMALE }
enum class RawSensor { ACCEL, PPG, GPS, GYROSCOPE, MAGNETOMETER, MICROPHONE }
data class Profile(
    val age: Int? = null, val sex: Sex? = null, val weightKg: Double? = null,
    val heightCm: Double? = null, val maxHr: Int? = null, val restingHr: Int? = null,
)
data class Watch(val mac: String? = null, val name: String? = null)
/** Quiet hour endpoints are minutes after midnight; both null disables the window. */
data class NotificationForwarding(
    val enabled: Boolean = false, val allowedPackages: Set<String> = emptySet(),
    val quietHoursStart: Int? = null, val quietHoursEnd: Int? = null, val forwardCalls: Boolean = false,
)
