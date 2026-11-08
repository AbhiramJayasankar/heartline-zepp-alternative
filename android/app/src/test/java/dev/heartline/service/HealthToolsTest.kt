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
            assertEquals(listOf("get_info"), tools.tools.map { it.name })
            assertEquals("object", tools.tools.single().parameters.getString("type"))
            suspend fun info(topic: String, date: String? = null) = tools.run("get_info", JSONObject().put("topic", topic).apply { date?.let { put("date", it) } })
            tools.topics.forEach { (topic, _) ->
                val out = info(topic)!!
                println("== $topic\n$out")
                assertTrue(topic, out.isNotBlank())
                assertFalse("$topic: $out", out.startsWith("Error"))
            }
            assertTrue(info("heart_rate")!!.contains("By hour"))
            assertTrue(info("sleep", "today")!!.contains("asleep"))
            assertNull(info("weather")) // answered by the assistant itself
            assertNull(tools.run("alarms", JSONObject()))
            assertEquals("Error: date must be YYYY-MM-DD, today or yesterday", info("stress", "last week"))
            assertTrue(info("stress", "2999-01-01")!!.startsWith("Error: 2999-01-01 is in the future"))
        } finally { scope.cancel() }
    }
}
