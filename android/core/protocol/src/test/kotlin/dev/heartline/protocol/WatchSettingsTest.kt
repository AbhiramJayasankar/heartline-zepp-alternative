package dev.heartline.protocol

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings added from Gadgetbridge's HuamiSupport for the GTR 2e; vectors follow the named methods and HuamiService constants. */
class WatchSettingsTest {
    private fun cmd(key: String, value: Any, current: Map<String, Any?> = emptyMap()) = settingCommand(key, value, current).let { it.first to it.second.hex() }

    @Test fun liftWristSchedules() {
        assertEquals(SettingTarget.CONFIG to "0605000100000000", cmd("lift_wrist", true))
        assertEquals(SettingTarget.CONFIG to "06050000", cmd("lift_wrist", false))
        // setActivateDisplayOnLiftWrist SCHEDULED: cmd[4..7] = start h, m, end h, m
        assertEquals(SettingTarget.CONFIG to "0605000107001600", cmd("lift_wrist_when", "scheduled", mapOf("lift_wrist" to true, "lift_wrist_start" to "07:00", "lift_wrist_end" to "22:00")))
        assertEquals(SettingTarget.CONFIG to "06050001071e1600", cmd("lift_wrist_start", "07:30", mapOf("lift_wrist_when" to "scheduled", "lift_wrist_end" to "22:00")))
        assertEquals("06050000", cmd("lift_wrist_end", "23:00", mapOf("lift_wrist" to false)).second)
        assertEquals("06230001", cmd("lift_wrist_sensitive", true).second) // COMMAND_DISPLAY_ON_LIFT_WRIST_SPEED_SENSITIVE
    }

    @Test fun doNotDisturbModes() {
        assertEquals("0982", cmd("dnd", "off").second)
        assertEquals("0983", cmd("dnd", "auto").second)
        assertEquals("09811700061e", cmd("dnd", "scheduled", mapOf("dnd_start" to "23:00", "dnd_end" to "06:30")).second)
        // setDoNotDisturb: lift-wrist exemption clears bit 7 of the mode byte, except when off
        assertEquals("0903", cmd("dnd_lift_wrist", true, mapOf("dnd" to "auto")).second)
        assertEquals("0982", cmd("dnd_lift_wrist", true, mapOf("dnd" to "off")).second)
    }

    @Test fun inactivityWindows() {
        // The Python port's fixed command stays the default.
        assertEquals("08013c000900150000000000", cmd("inactivity_warnings", true).second)
        assertEquals("08003c000400150000000000", cmd("inactivity_warnings", false).second)
        assertEquals("08012d00081e140000000000", cmd("inactivity_minutes", 45, mapOf("inactivity_start" to "08:30", "inactivity_end" to "20:00")).second)
        // With a pause: interval 1 ends when the pause starts, interval 2 starts when it ends (setInactivityWarnings)
        assertEquals("08013c0009000c000e001500", cmd("inactivity_quiet", true).second)
    }

    @Test fun disconnectAlert() {
        assertEquals("060c000000000000", cmd("disconnect_alert", "off").second)
        assertEquals("060c000100000000", cmd("disconnect_alert", "on").second)
        assertEquals("060c000108001600", cmd("disconnect_start", "08:00", mapOf("disconnect_alert" to "scheduled")).second)
    }

    @Test fun languageAndUserSettings() {
        assertEquals("061700" + "en_US".toByteArray().hex(), cmd("language", "en_US").second)
        assertEquals("en_IN", phoneLocaleTag(Locale.forLanguageTag("en-IN")))
        assertEquals(SettingTarget.USER to "20000002", cmd("wear_location", "left"))
        assertEquals(SettingTarget.USER to "20000082", cmd("wear_location", "right"))
        assertEquals(SettingTarget.USER to "1000001027" + "0000", cmd("step_goal", 10000))
    }

    @Test fun userInfo() {
        // 1999-01-01, male, 175 cm, 70 kg (x200 = 14000 = 0x36b0), id 0x01020304
        assertEquals("4f0000cf07010100af00b03604030201", userInfoCommand(1999, 1, 1, "male", 175, 70.0, 0x01020304).hex())
    }

    @Test fun storedTimesRoundTrip() {
        assertEquals(48, HALF_HOURS.size)
        assertEquals("23:30", HALF_HOURS.last().value)
        assertEquals("Display", settingSection(SETTINGS.first { it.key == "lift_wrist" }))
    }

    @Test fun passcode() {
        // Zepp 6.3 setUnwearPassword: 06 21 00, on/off, the digits as ASCII, 00
        assertEquals(SettingTarget.CONFIG to "062100013132333400", cmd("passcode_pin", "1234", mapOf("passcode" to true)))
        assertEquals("062100003132333400", cmd("passcode", false, mapOf("passcode_pin" to "1234")).second)
        assertEquals("062100003030303000", cmd("passcode", false).second)
        assertEquals("062100013930373000", cmd("passcode", true, mapOf("passcode_pin" to "9070")).second)
        assertEquals("Choose a 4-digit passcode first", runCatching { cmd("passcode", true) }.exceptionOrNull()?.message)
        assertEquals("passcode_pin must be 4 digits", runCatching { cmd("passcode_pin", "12a4") }.exceptionOrNull()?.message)
        assertEquals("passcode_pin must be 4 digits", runCatching { cmd("passcode_pin", "12345") }.exceptionOrNull()?.message)
    }
}
