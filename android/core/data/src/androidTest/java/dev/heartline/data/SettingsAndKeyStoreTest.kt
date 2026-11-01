package dev.heartline.data

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.heartline.data.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndKeyStoreTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() {
        context.getSharedPreferences("heartline_settings", Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore(context).clear()
    }
    @After fun cleanup() { setup() }

    @Test fun allSettingsPersistAndPublishState() {
        val profile = Profile(31, Sex.FEMALE, 62.5, 170.25, 189, 58)
        val watch = Watch("AA:BB:CC:DD:EE:FF", "GTR 2e")
        val forwarding = NotificationForwarding(true, setOf("dev.example", "com.calls"), 1320, 420, true)
        AppSettings(context).use { s ->
            assertEquals(Theme.SYSTEM, s.theme.value)
            assertEquals(Units.METRIC, s.units.value)
            assertEquals(Profile(), s.profile.value)
            assertFalse(s.onboardingDone.value)
            assertFalse(s.notificationForwarding.value.enabled)
            assertEquals(setOf(RawSensor.ACCEL, RawSensor.PPG), s.rawSensorDefaultSelection.value)
            s.setTheme(Theme.DARK); s.setUnits(Units.IMPERIAL); s.setProfile(profile); s.setWatch(watch)
            s.setLastWrittenWatchSettings(mapOf("hr_interval" to "5"))
            s.setLastWrittenWatchSetting("screen", "10")
            s.setNotificationForwarding(forwarding)
            s.setRawSensorDefaultSelection(setOf(RawSensor.PPG, RawSensor.MICROPHONE))
            s.setOnboardingDone(true); s.setAutoSyncIntervalMinutes(15)
            assertEquals(Theme.DARK, s.theme.value)
            assertEquals(profile, s.profile.value)
        }
        AppSettings(context).use { s ->
            assertEquals(Theme.DARK, s.theme.value)
            assertEquals(Units.IMPERIAL, s.units.value)
            assertEquals(profile, s.profile.value)
            assertEquals(watch, s.watch.value)
            assertEquals(mapOf("hr_interval" to "5", "screen" to "10"), s.lastWrittenWatchSettings.value)
            assertEquals(forwarding, s.notificationForwarding.value)
            assertEquals(setOf(RawSensor.PPG, RawSensor.MICROPHONE), s.rawSensorDefaultSelection.value)
            assertTrue(s.onboardingDone.value)
            assertEquals(15L, s.autoSyncIntervalMinutes.value)
            s.setProfile(Profile()); s.setNotificationForwarding(NotificationForwarding())
            s.setRawSensorDefaultSelection(emptySet()); s.setAutoSyncIntervalMinutes(0)
            assertEquals(Profile(), s.profile.value)
            assertEquals(NotificationForwarding(), s.notificationForwarding.value)
            assertTrue(s.rawSensorDefaultSelection.value.isEmpty())
        }
    }

    @Test fun validatesSettingsAndAuthHex() {
        AppSettings(context).use { s ->
            assertThrows(IllegalArgumentException::class.java) { s.setAutoSyncIntervalMinutes(-1) }
            assertThrows(IllegalArgumentException::class.java) { s.setProfile(Profile(weightKg = Double.NaN)) }
            assertThrows(IllegalArgumentException::class.java) { s.setNotificationForwarding(NotificationForwarding(quietHoursStart = 60)) }
            assertThrows(IllegalArgumentException::class.java) {
                s.setNotificationForwarding(NotificationForwarding(quietHoursStart = -1, quietHoursEnd = 1440))
            }
        }
        assertTrue(KeyStore.validateHex("00112233445566778899aAbBcCdDeEfF"))
        assertFalse(KeyStore.validateHex("secret"))
        assertArrayEquals(ByteArray(16), KeyStore.parseHex("0".repeat(32)))
    }

    @Test fun keystoreRoundTripUsesRandomIvAndNoPlaintextPreferences() {
        val store = KeyStore(context)
        val key = ByteArray(16) { it.toByte() }
        assertNull(store.load())
        store.save(key)
        assertArrayEquals(key, KeyStore(context).load())
        val prefs = context.getSharedPreferences("heartline_auth", Context.MODE_PRIVATE)
        val first = prefs.getString("encrypted", null)!!
        assertFalse(first.contains(Hex.encode(key)))
        assertEquals(44, Base64.decode(first, Base64.NO_WRAP).size)
        store.save(key)
        assertNotEquals(first, prefs.getString("encrypted", null))
        assertArrayEquals(key, store.load())
        val platform = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(platform.getKey("heartline_auth", null).encoded)
        store.clear()
        assertNull(store.load())
        assertFalse(platform.containsAlias("heartline_auth"))
    }

    @Test fun rejectsBadKeyLengthAndAuthenticatedCiphertextTampering() {
        val store = KeyStore(context)
        assertThrows(IllegalArgumentException::class.java) { store.save(ByteArray(15)) }
        store.save(ByteArray(16))
        val prefs = context.getSharedPreferences("heartline_auth", Context.MODE_PRIVATE)
        val bytes = Base64.decode(prefs.getString("encrypted", null), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        assertNull(store.load())
        prefs.edit().putString("encrypted", "corrupt").commit()
        assertNull(store.load())
        store.save(ByteArray(16) { 42 })
        assertArrayEquals(ByteArray(16) { 42 }, store.load())
    }
}
