package dev.heartline.app

import dev.heartline.service.firstResolvable
import org.junit.Assert.*
import org.junit.Test

class SystemIntentPolicyTest {
    @Test fun usesFirstResolvableActivityInPreferenceOrder() {
        assertEquals("miui", firstResolvable(listOf("miui", "details", "settings")) { true })
        assertEquals("details", firstResolvable(listOf("miui", "details", "settings")) { it != "miui" })
    }
    @Test fun resolutionExceptionsFallBackToNextActivity() {
        assertEquals("details", firstResolvable(listOf("miui", "details")) { if (it == "miui") error("blocked") else true })
    }
    @Test fun absentSettingsActivitiesReturnNullWithoutThrowing() {
        assertNull(firstResolvable(listOf("miui", "details", "settings")) { false })
    }
}
