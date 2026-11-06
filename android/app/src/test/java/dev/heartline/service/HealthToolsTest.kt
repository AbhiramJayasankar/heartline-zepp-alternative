package dev.heartline.service

import dev.heartline.app.DemoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthToolsTest {
    @Test fun everyToolAnswersFromTheAppsData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tools = HealthTools(DemoController(scope))
            tools.tools.forEach { t ->
                assertEquals("object", t.parameters.getString("type"))
                val out = tools.run(t.name, JSONObject())!!
                println("== ${t.name}\n$out")
                assertTrue(t.name, out.isNotBlank())
                assertFalse("${t.name}: $out", out.startsWith("Error"))
            }
            assertTrue(tools.run("get_heart_rate", JSONObject())!!.contains("By hour"))
            assertTrue(tools.run("get_sleep", JSONObject().put("date", "today"))!!.contains("asleep"))
            assertNull(tools.run("set_timer", JSONObject()))
            assertEquals("Error: date must be YYYY-MM-DD, today or yesterday", tools.run("get_stress", JSONObject().put("date", "last week")))
            assertTrue(tools.run("get_stress", JSONObject().put("date", "2999-01-01"))!!.startsWith("Error: 2999-01-01 is in the future"))
        } finally { scope.cancel() }
    }
}
