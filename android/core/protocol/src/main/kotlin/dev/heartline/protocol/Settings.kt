package dev.heartline.protocol

import java.math.BigDecimal
import java.math.BigInteger

enum class SettingTarget { CONFIG, HR }
data class SettingOption(val value: Any, val label: String)
data class SettingDef(
    val key: String, val label: String, val type: String, val options: List<SettingOption> = emptyList(),
    val min: Int? = null, val max: Int? = null, val target: SettingTarget = SettingTarget.CONFIG,
)

/** Same labels, choices and write targets as health.SETTINGS; these values cannot be read back. */
val SETTINGS: List<SettingDef> = listOf(
    SettingDef("hr_interval", "All-day heart rate", "enum", listOf(
        SettingOption(0, "Off"), SettingOption(1, "Every minute"), SettingOption(5, "Every 5 minutes"),
        SettingOption(10, "Every 10 minutes"), SettingOption(30, "Every 30 minutes")), target = SettingTarget.HR),
    SettingDef("hr_sleep_detection", "Heart rate during sleep", "bool", target = SettingTarget.HR),
    SettingDef("hr_activity_monitoring", "Heart rate during activity", "bool"),
    SettingDef("stress_monitoring", "All-day stress", "bool"),
    SettingDef("hr_alert", "High heart rate alert", "bool"),
    SettingDef("hr_alert_threshold", "High heart rate threshold", "int", min = 100, max = 180),
    SettingDef("expose_hr", "Share heart rate with other apps (BLE heart rate broadcast)", "bool"),
    SettingDef("goal_notification", "Step goal notification", "bool"),
    SettingDef("lift_wrist", "Lift wrist to wake", "bool"),
    SettingDef("inactivity_warnings", "Move reminders (9:00-21:00, after 60 min)", "bool"),
    SettingDef("dnd", "Do not disturb", "enum", listOf(SettingOption("off", "Off"), SettingOption("auto", "Automatic (while asleep)"))),
    SettingDef("time_24h", "24-hour clock", "bool"),
    SettingDef("metric_units", "Metric units", "bool"),
)

private fun truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is String -> value.isNotEmpty()
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    is ByteArray -> value.isNotEmpty()
    else -> true
}

private fun integer(value: Any): BigInteger = when (value) {
    is Boolean -> if (value) BigInteger.ONE else BigInteger.ZERO
    is BigInteger -> value
    is BigDecimal -> value.toBigInteger()
    is Byte, is Short, is Int, is Long -> BigInteger.valueOf((value as Number).toLong())
    is Number -> {
        require(value.toDouble().isFinite()) { if (value.toDouble().isNaN()) "cannot convert float NaN to integer" else "cannot convert float infinity to integer" }
        BigDecimal(value.toDouble()).toBigInteger()
    }
    is String -> {
        val stripped = value.trim()
        if (!stripped.matches(Regex("[+-]?\\p{Nd}+(?:_\\p{Nd}+)*")))
            throw IllegalArgumentException("invalid literal for int() with base 10: '$value'")
        BigInteger(stripped.replace("_", ""))
    }
    else -> throw IllegalArgumentException("int() argument must be a string, a bytes-like object or a real number")
}

/** health.validate_setting, integrated into settingCommand so all public writes validate before encoding. */
fun validateSetting(key: String, value: Any): Any {
    val spec = SETTINGS.find { it.key == key } ?: throw IllegalArgumentException("Unknown setting $key")
    return when (spec.type) {
        "bool" -> truthy(value)
        "int" -> integer(value).also {
            require(it >= BigInteger.valueOf(spec.min!!.toLong()) && it <= BigInteger.valueOf(spec.max!!.toLong())) {
                "$key must be between ${spec.min} and ${spec.max}"
            }
        }.toInt()
        else -> {
            val normalized = when (value) { is Boolean -> if (value) 1 else 0; is Number -> value.toDouble(); else -> value }
            require(spec.options.any { opt -> if (opt.value is Number && normalized is Number) opt.value.toDouble() == normalized.toDouble() else opt.value == normalized }) {
                val allowed = spec.options.joinToString(", ", "[", "]") { if (it.value is String) "'${it.value}'" else it.value.toString() }
                "$key must be one of $allowed"
            }
            value
        }
    }
}

/** health.setting_command / HuamiSupport.set*; alert writes combine the other last-written value. */
fun settingCommand(key: String, value: Any, current: Map<String, Any?> = emptyMap()): Pair<SettingTarget, ByteArray> {
    val v = validateSetting(key, value)
    fun b(value: Any?) = if (truthy(value)) 1 else 0
    val target = SETTINGS.first { it.key == key }.target
    val command = when (key) {
        "hr_interval" -> bytes(0x14, integer(v).toInt())
        "hr_sleep_detection" -> bytes(0x15, 0, b(v))
        "hr_activity_monitoring" -> bytes(6, 0x22, 0, b(v))
        "stress_monitoring" -> bytes(0xFE, 6, 0, b(v))
        "hr_alert", "hr_alert_threshold" -> {
            val on = if (key == "hr_alert") v else if (current.containsKey("hr_alert")) current["hr_alert"] else true
            val thr = if (key == "hr_alert_threshold") v else if (current.containsKey("hr_alert_threshold")) current["hr_alert_threshold"] else 150
            require(thr != null) { "int() argument must be a string, a bytes-like object or a real number, not 'NoneType'" }
            val threshold = integer(thr)
            require(threshold >= BigInteger.ZERO && threshold <= BigInteger.valueOf(255)) { "bytes must be in range(0, 256)" }
            bytes(6, 0x1A, 0, b(on), threshold.toInt())
        }
        "expose_hr" -> bytes(6, 0x1F, 0, b(v))
        "goal_notification" -> bytes(6, 6, 0, b(v))
        "lift_wrist" -> if (truthy(v)) bytes(6, 5, 0, 1, 0, 0, 0, 0) else bytes(6, 5, 0, 0)
        "inactivity_warnings" -> if (truthy(v)) bytes(8, 1, 0x3C, 0, 9, 0, 0x15, 0, 0, 0, 0, 0)
            else bytes(8, 0, 0x3C, 0, 4, 0, 0x15, 0, 0, 0, 0, 0)
        "dnd" -> bytes(9, if (v == "auto") 0x83 else 0x82)
        "time_24h" -> bytes(6, 2, 0, b(v))
        "metric_units" -> bytes(6, 3, 0, if (truthy(v)) 0 else 1)
        else -> error("Unknown setting $key")
    }
    return target to command
}
