package dev.heartline.protocol

import java.math.BigDecimal
import java.math.BigInteger

/** CONFIG (0003) replies `10 cmd status`; HR goes to the heart-rate control point; USER is the user-settings characteristic (0008). */
enum class SettingTarget { CONFIG, HR, USER }
data class SettingOption(val value: Any, val label: String)
data class SettingDef(
    val key: String, val label: String, val type: String, val options: List<SettingOption> = emptyList(),
    val min: Int? = null, val max: Int? = null, val target: SettingTarget = SettingTarget.CONFIG,
    /** Groups rows on the settings screen. */ val section: String = "Health",
    /** Added after the Python port, so the parity tests skip it. */ val androidOnly: Boolean = false,
)

/** Times of day in half-hour steps, as "HH:MM", for the schedule settings. */
val HALF_HOURS: List<SettingOption> = (0 until 48).map { i -> "%02d:%02d".format(i / 2, i % 2 * 30).let { SettingOption(it, it) } }

/** Gadgetbridge AmazfitGTR2eCoordinator.getSupportedLanguageSettings; "auto" follows the phone. */
val WATCH_LANGUAGES: List<SettingOption> = listOf(
    "auto" to "Same as phone", "en_US" to "English", "es_ES" to "Español", "de_DE" to "Deutsch", "it_IT" to "Italiano",
    "fr_FR" to "Français", "pt_BR" to "Português (Brasil)", "tr_TR" to "Türkçe", "cs_CZ" to "Čeština", "ru_RU" to "Русский",
    "zh_CN" to "简体中文", "zh_TW" to "繁體中文",
).map { (v, l) -> SettingOption(v, l) }

/** Same labels, choices and write targets as health.SETTINGS; these values cannot be read back. */
val SETTINGS: List<SettingDef> = listOf(
    SettingDef("hr_interval", "All-day heart rate", "enum", listOf(
        SettingOption(0, "Off"), SettingOption(1, "Every minute"), SettingOption(5, "Every 5 minutes"),
        SettingOption(10, "Every 10 minutes"), SettingOption(30, "Every 30 minutes")), target = SettingTarget.HR),
    SettingDef("hr_sleep_detection", "Heart rate during sleep", "bool", target = SettingTarget.HR),
    SettingDef("hr_activity_monitoring", "Heart rate during activity", "bool"),
    SettingDef("stress_monitoring", "All-day stress", "bool"),
    // Zepp 6.3 ExtendCmd.Osa: the GTR 2e answers only the older 06-prefixed form (its two-way FE 05 reads come back "not supported").
    SettingDef("sleep_breathing", "Sleep breathing quality (blood oxygen while asleep)", "bool", androidOnly = true),
    SettingDef("hr_alert", "High heart rate alert", "bool"),
    SettingDef("hr_alert_threshold", "High heart rate threshold", "int", min = 100, max = 180),
    SettingDef("expose_hr", "Share heart rate with other apps (BLE heart rate broadcast)", "bool"),
    SettingDef("connected_advertising", "Stay visible to other devices while connected (needed for heart rate sharing)", "bool"),
    SettingDef("goal_notification", "Step goal notification", "bool"),
    SettingDef("lift_wrist", "Lift wrist to wake", "bool"),
    SettingDef("inactivity_warnings", "Move reminders", "bool"),
    SettingDef("dnd", "Do not disturb", "enum", listOf(SettingOption("off", "Off"), SettingOption("auto", "Automatic (while asleep)"), SettingOption("scheduled", "Between set times"))),
    SettingDef("time_24h", "24-hour clock", "bool"),
    SettingDef("metric_units", "Metric units", "bool"),
    // Gadgetbridge HuamiSupport settings the GTR 2e supports, beyond the Python port.
    SettingDef("lift_wrist_when", "Lift to wake: when", "enum", listOf(SettingOption("always", "Always"), SettingOption("scheduled", "Between set times")),
        section = "Display", androidOnly = true),
    SettingDef("lift_wrist_start", "Lift to wake from", "enum", HALF_HOURS, section = "Display", androidOnly = true),
    SettingDef("lift_wrist_end", "Lift to wake until", "enum", HALF_HOURS, section = "Display", androidOnly = true),
    SettingDef("lift_wrist_sensitive", "More sensitive lift to wake", "bool", section = "Display", androidOnly = true),
    SettingDef("dnd_start", "Scheduled do not disturb from", "enum", HALF_HOURS, section = "Do not disturb", androidOnly = true),
    SettingDef("dnd_end", "Scheduled do not disturb until", "enum", HALF_HOURS, section = "Do not disturb", androidOnly = true),
    SettingDef("dnd_lift_wrist", "Lift to wake still works during do not disturb", "bool", section = "Do not disturb", androidOnly = true),
    SettingDef("inactivity_minutes", "Move reminder after sitting for", "enum",
        listOf(30, 45, 60, 90, 120).map { SettingOption(it, "$it minutes") }, section = "Move reminders", androidOnly = true),
    SettingDef("inactivity_start", "Move reminders from", "enum", HALF_HOURS, section = "Move reminders", androidOnly = true),
    SettingDef("inactivity_end", "Move reminders until", "enum", HALF_HOURS, section = "Move reminders", androidOnly = true),
    SettingDef("inactivity_quiet", "Pause move reminders for a while each day", "bool", section = "Move reminders", androidOnly = true),
    SettingDef("inactivity_quiet_start", "Pause from", "enum", HALF_HOURS, section = "Move reminders", androidOnly = true),
    SettingDef("inactivity_quiet_end", "Pause until", "enum", HALF_HOURS, section = "Move reminders", androidOnly = true),
    SettingDef("disconnect_alert", "Vibrate when the phone disconnects", "enum",
        listOf(SettingOption("off", "Off"), SettingOption("on", "Always"), SettingOption("scheduled", "Between set times")), section = "Connection", androidOnly = true),
    SettingDef("disconnect_start", "Disconnect alert from", "enum", HALF_HOURS, section = "Connection", androidOnly = true),
    SettingDef("disconnect_end", "Disconnect alert until", "enum", HALF_HOURS, section = "Connection", androidOnly = true),
    // Zepp 6.3 setUnwearPassword and Gadgetbridge setPassword: the watch asks for it when it is put back on after coming off.
    SettingDef("passcode", "Ask for a passcode when you put the watch on", "bool", section = "Passcode", androidOnly = true),
    SettingDef("passcode_pin", "Passcode", "pin", section = "Passcode", androidOnly = true),
    SettingDef("language", "Watch language", "enum", WATCH_LANGUAGES, section = "Watch", androidOnly = true),
    SettingDef("wear_location", "Worn on", "enum", listOf(SettingOption("left", "Left wrist"), SettingOption("right", "Right wrist")),
        target = SettingTarget.USER, section = "Watch", androidOnly = true),
    SettingDef("step_goal", "Daily step goal", "int", min = 1000, max = 50000, target = SettingTarget.USER, section = "Watch", androidOnly = true),
)

/** Sections for the settings that predate [SettingDef.section]. */
fun settingSection(def: SettingDef): String = when (def.key) {
    "lift_wrist" -> "Display"
    "dnd" -> "Do not disturb"
    "inactivity_warnings" -> "Move reminders"
    "expose_hr", "connected_advertising" -> "Connection"
    "time_24h", "metric_units", "goal_notification" -> "Watch"
    else -> def.section
}

/** Section order on the settings screen. */
val SETTING_SECTIONS = listOf("Health", "Display", "Do not disturb", "Move reminders", "Connection", "Passcode", "Watch")

/** What the encoders assume until a value is set, so the screen can show it instead of "Not set". */
fun settingDefault(key: String): Any? = when (key) {
    "lift_wrist_when" -> "always"; "lift_wrist_start" -> "08:00"; "lift_wrist_end" -> "22:00"
    "dnd" -> "off"; "dnd_start" -> "22:00"; "dnd_end" -> "07:00"
    "inactivity_minutes" -> 60; "inactivity_start" -> "09:00"; "inactivity_end" -> "21:00"
    "inactivity_quiet_start" -> "12:00"; "inactivity_quiet_end" -> "14:00"
    "disconnect_alert" -> "off"; "disconnect_start" -> "08:00"; "disconnect_end" -> "22:00"
    "language" -> "auto"
    else -> null
}

private val HH_MM = Regex("""\d{2}:\d{2}""")
private val PASSCODE = Regex("""\d{4}""")

private fun hm(v: Any?, default: String): Pair<Int, Int> {
    val t = (v as? String)?.takeIf { it.matches(HH_MM) } ?: default
    return t.substring(0, 2).toInt() to t.substring(3, 5).toInt()
}

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
        // The GTR 2e's keypad takes four digits (Zepp's six-digit passcodes are for the Zepp OS watches).
        "pin" -> value.toString().also { require(it.matches(PASSCODE)) { "$key must be 4 digits" } }
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
        "sleep_breathing" -> bytes(6, 0x3D, 0, b(v))
        "hr_alert", "hr_alert_threshold" -> {
            val on = if (key == "hr_alert") v else if (current.containsKey("hr_alert")) current["hr_alert"] else true
            val thr = if (key == "hr_alert_threshold") v else if (current.containsKey("hr_alert_threshold")) current["hr_alert_threshold"] else 150
            require(thr != null) { "int() argument must be a string, a bytes-like object or a real number, not 'NoneType'" }
            val threshold = integer(thr)
            require(threshold >= BigInteger.ZERO && threshold <= BigInteger.valueOf(255)) { "bytes must be in range(0, 256)" }
            bytes(6, 0x1A, 0, b(on), threshold.toInt())
        }
        "expose_hr" -> bytes(6, 0x1F, 0, b(v))
        "connected_advertising" -> bytes(6, 1, 0, b(v))
        "goal_notification" -> bytes(6, 6, 0, b(v))
        // HuamiSupport.setActivateDisplayOnLiftWrist: on 06 05 00 01 + 00 00 00 00, scheduled the same with start/end times.
        "lift_wrist", "lift_wrist_when", "lift_wrist_start", "lift_wrist_end" -> {
            val m = current + (key to v)
            if (!truthy(m["lift_wrist"] ?: true)) bytes(6, 5, 0, 0)
            else if (m["lift_wrist_when"] == "scheduled") {
                val (sh, sm) = hm(m["lift_wrist_start"], "08:00"); val (eh, em) = hm(m["lift_wrist_end"], "22:00")
                bytes(6, 5, 0, 1, sh, sm, eh, em)
            } else bytes(6, 5, 0, 1, 0, 0, 0, 0)
        }
        "lift_wrist_sensitive" -> bytes(6, 0x23, 0, b(v))
        // HuamiSupport.setInactivityWarnings: threshold, then one or two active intervals around an optional pause.
        "inactivity_warnings", "inactivity_minutes", "inactivity_start", "inactivity_end", "inactivity_quiet", "inactivity_quiet_start", "inactivity_quiet_end" -> {
            val m = current + (key to v)
            if (!truthy(m["inactivity_warnings"] ?: true)) bytes(8, 0, 0x3C, 0, 4, 0, 0x15, 0, 0, 0, 0, 0)
            else {
                val minutes = (m["inactivity_minutes"] as? Number)?.toInt() ?: (m["inactivity_minutes"] as? String)?.toIntOrNull() ?: 60
                val (sh, sm) = hm(m["inactivity_start"], "09:00"); val (eh, em) = hm(m["inactivity_end"], "21:00")
                if (truthy(m["inactivity_quiet"])) {
                    val (qh, qm) = hm(m["inactivity_quiet_start"], "12:00"); val (rh, rm) = hm(m["inactivity_quiet_end"], "14:00")
                    bytes(8, 1, minutes, 0, sh, sm, qh, qm, rh, rm, eh, em)
                } else bytes(8, 1, minutes, 0, sh, sm, eh, em, 0, 0, 0, 0)
            }
        }
        // HuamiSupport.setDoNotDisturb: 09 82 off, 09 83 automatic, 09 81 hh mm hh mm scheduled; clearing 0x80 lets lift-to-wake work.
        "dnd", "dnd_start", "dnd_end", "dnd_lift_wrist" -> {
            val m = current + (key to v)
            val mode = m["dnd"] ?: "off"
            val data = when (mode) {
                "auto" -> bytes(9, 0x83)
                "scheduled" -> { val (sh, sm) = hm(m["dnd_start"], "22:00"); val (eh, em) = hm(m["dnd_end"], "07:00"); bytes(9, 0x81, sh, sm, eh, em) }
                else -> bytes(9, 0x82)
            }
            if (mode != "off" && truthy(m["dnd_lift_wrist"])) data[1] = (data[1].toInt() and 0x7F).toByte()
            data
        }
        // HuamiSupport.setDisconnectNotification: 06 0c 00 on/off, then start/end times when scheduled.
        "disconnect_alert", "disconnect_start", "disconnect_end" -> {
            val m = current + (key to v)
            when (m["disconnect_alert"] ?: "off") {
                "on" -> bytes(6, 0x0C, 0, 1, 0, 0, 0, 0)
                "scheduled" -> { val (sh, sm) = hm(m["disconnect_start"], "08:00"); val (eh, em) = hm(m["disconnect_end"], "22:00"); bytes(6, 0x0C, 0, 1, sh, sm, eh, em) }
                else -> bytes(6, 0x0C, 0, 0, 0, 0, 0, 0)
            }
        }
        // HuamiSupport.setLanguage (COMMAND_SET_LANGUAGE_NEW_TEMPLATE): 06 17 00 then the locale as ASCII, e.g. en_US.
        "language" -> {
            val tag = if (v == "auto") phoneLocaleTag() else v.toString()
            val out = bytes(6, 0x17, 0, 0, 0, 0, 0, 0)
            tag.toByteArray(Charsets.US_ASCII).take(5).forEachIndexed { i, c -> out[3 + i] = c }
            out
        }
        // Zepp 6.3 setUnwearPassword: 06 21 00 on/off, the digits as ASCII, 00. The GTR 2e answered 10 06 21 00 01 (2026-11-07).
        // Turning it off still sends a passcode, as Zepp and Gadgetbridge do; "0000" when none was ever set.
        "passcode", "passcode_pin" -> {
            val m = current + (key to v)
            val on = truthy(m["passcode"])
            val pin = m["passcode_pin"]?.toString()?.takeIf { it.matches(PASSCODE) }
            require(!on || pin != null) { "Choose a 4-digit passcode first" }
            bytes(6, 0x21, 0, if (on) 1 else 0) + (pin ?: "0000").toByteArray(Charsets.US_ASCII) + bytes(0)
        }
        // HuamiService.WEAR_LOCATION_LEFT_WRIST / RIGHT_WRIST, written to the user-settings characteristic.
        "wear_location" -> if (v == "right") bytes(0x20, 0, 0, 0x82) else bytes(0x20, 0, 0, 2)
        // HuamiSupport.setFitnessGoal: 10 00 00, the goal as u16, 00 00.
        "step_goal" -> integer(v).toInt().let { bytes(0x10, 0, 0, it and 255, (it shr 8) and 255, 0, 0) }
        "time_24h" -> bytes(6, 2, 0, b(v))
        "metric_units" -> bytes(6, 3, 0, if (truthy(v)) 0 else 1)
        else -> error("Unknown setting $key")
    }
    return target to command
}

/** The phone's locale as the watch expects it, e.g. en_US (HuamiSupport.setLanguage "auto"). */
fun phoneLocaleTag(locale: java.util.Locale = java.util.Locale.getDefault()): String =
    locale.language + "_" + (locale.country.ifEmpty { locale.language }).uppercase()

/**
 * HuamiSupport.setUserInfo: 4f 00 00, birth year u16, month, day, sex (0 male, 1 female, 2 other), height cm u16,
 * weight kg x 200 u16, then a user id u32. The watch uses these for calories, distance and heart-rate zones.
 */
fun userInfoCommand(birthYear: Int, birthMonth: Int, birthDay: Int, sex: String?, heightCm: Int, weightKg: Double, userId: Int): ByteArray {
    val s = when (sex?.lowercase()) { "male" -> 0; "female" -> 1; else -> 2 }
    val w = (weightKg * 200).toInt()
    return bytes(0x4F, 0, 0, birthYear and 255, (birthYear shr 8) and 255, birthMonth, birthDay, s, heightCm and 255, (heightCm shr 8) and 255,
        w and 255, (w shr 8) and 255, userId and 255, (userId shr 8) and 255, (userId shr 16) and 255, (userId ushr 24) and 255)
}
