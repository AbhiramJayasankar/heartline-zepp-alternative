package dev.heartline.protocol

/**
 * The watch's app list (Gadgetbridge HuamiSupport.setDisplayItemsNew, which the GTR 2 and 2e use): `1e`, then four
 * bytes per shown app in order: position, 00, ff (menu), the app's id from HuamiMenuType. Apps left out are hidden.
 * Sent as a chunked type-2 write. Which ids a given firmware knows is not documented; unknown ones are skipped by the watch.
 */
object WatchMenu {
    class App(val key: String, val id: Int, val label: String)

    /** HuamiMenuType ids for the apps a GTR 2e-class watch can have. */
    val APPS: List<App> = listOf(
        App("status", 0x01, "Activity status"), App("pai", 0x19, "PAI"), App("hr", 0x02, "Heart rate"), App("spo2", 0x24, "SpO2"),
        App("stress", 0x1c, "Stress"), App("breathing", 0x33, "Breathing"), App("sleep", 0x23, "Sleep"), App("workout", 0x03, "Workout"),
        App("activity", 0x14, "Activity history"), App("weather", 0x04, "Weather"), App("notifications", 0x06, "Notifications"),
        App("eventreminder", 0x15, "Event reminders"), App("alarm", 0x09, "Alarm"), App("worldclock", 0x1a, "World clock"),
        App("timer", 0x0d, "Timer"), App("stopwatch", 0x0c, "Stopwatch"), App("pomodoro", 0x38, "Pomodoro"), App("compass", 0x16, "Compass"),
        App("barometer", 0x41, "Barometer"), App("temperature", 0x40, "Temperature"), App("music", 0x0b, "Music"), App("alexa", 0x39, "Alexa"),
        App("findphone", 0x0e, "Find phone"), App("mutephone", 0x0f, "Mute phone"), App("period", 0x1d, "Cycle tracking"),
        App("dnd", 0x08, "Do not disturb"), App("battery", 0x3a, "Battery"), App("flashlight", 0x43, "Flashlight"),
        App("settings", 0x13, "Settings"), App("more", 0x07, "More"),
    )
    private val byKey = APPS.associateBy { it.key }

    fun command(shown: List<String>): ByteArray {
        val list = shown.filter { it in byKey }.toMutableList()
        // setDisplayItemsNew: with a "more" section, at most 16 apps go before it or some get cut off.
        val more = list.indexOf("more")
        if (more == -1 && list.size > 16) list.add(16, "more")
        else if (more != -1 && list.size > 17) { list.removeAt(more); list.add(16, "more") }
        val out = java.io.ByteArrayOutputStream()
        out.write(0x1e)
        list.forEachIndexed { i, key -> out.write(i); out.write(0); out.write(0xff); out.write(byKey.getValue(key).id) }
        return out.toByteArray()
    }
}

/**
 * Vibration patterns per alert type (Gadgetbridge HuamiSupport.setVibrationPattern): `20 type flags` then on/off
 * durations in ms as u16, where flags = pairs | 0x40, plus 0x80 to try the pattern without saving it. Chunked type 2.
 */
object VibrationPatterns {
    class Kind(val key: String, val code: Int, val label: String)
    class Profile(val key: String, val label: String, val onOff: List<Int>)

    /** HuamiVibrationPatternNotificationType. */
    val KINDS: List<Kind> = listOf(
        Kind("app_alerts", 0x00, "App notifications"), Kind("incoming_call", 0x01, "Calls"), Kind("incoming_sms", 0x02, "Text messages"),
        Kind("goal_notification", 0x04, "Goal reached"), Kind("alarm", 0x05, "Alarms"), Kind("idle_alerts", 0x06, "Move reminders"),
        Kind("event_reminder", 0x08, "Event reminders"), Kind("find_band", 0x09, "Find watch"),
    )

    /** Gadgetbridge VibrationProfile presets (on, off, ... in ms). */
    val PROFILES: List<Profile> = listOf(
        Profile("staccato", "Staccato", listOf(100, 0)), Profile("short", "Short", listOf(200, 200)), Profile("medium", "Medium", listOf(500, 1000)),
        Profile("long", "Long", listOf(100, 1500)), Profile("waterdrop", "Water drop", listOf(300, 200, 600, 2000)),
        Profile("ring", "Ring", listOf(30, 35, 30, 35, 30, 35, 30, 800)), Profile("alarm_clock", "Alarm clock", listOf(300, 600)),
    )

    /** [repeat] copies of the profile, cut at 10 s total like Gadgetbridge (and Mi Fit). */
    fun onOff(profile: Profile, repeat: Int): List<Int> {
        val out = ArrayList<Int>(); var total = 0
        loop@ for (r in 0 until repeat.coerceAtLeast(1)) for (i in profile.onOff.indices step 2) {
            val on = profile.onOff[i]; val off = profile.onOff[i + 1]
            if (total + on > 10_000) break@loop
            out += on; total += on
            val o = off.coerceAtMost(10_000 - total); out += o; total += o
            if (total >= 10_000) break@loop
        }
        return out
    }

    fun command(kind: Kind, profile: Profile, repeat: Int, test: Boolean = false): ByteArray {
        val seq = onOff(profile, repeat)
        val out = java.io.ByteArrayOutputStream()
        out.write(0x20); out.write(kind.code)
        out.write((seq.size / 2) or 0x40 or if (test) 0x80 else 0)
        seq.forEach { out.write(it and 255); out.write((it shr 8) and 255) }
        return out.toByteArray()
    }
}
