package dev.heartline.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import kotlin.test.*

class HealthTest {
    @Test fun capturedManualHr() {
        val rows = parseManualHr(blob("manual_hr"))
        assertEquals(listOf(113.0, 116.0, 105.0, 95.0), rows.map { it.value })
        assertEquals(1793471635L, rows[0].ts)
        assertEquals("22", rows[0].extra["tz_q"])
        assertTrue(rows.all { it.type == "manual_hr" })
    }
    @Test fun stressSkipsEmptyMinutes() {
        val rows = parseStress(blob("stress"), start("stress"))
        assertEquals(listOf(54.0, 31.0, 38.0), rows.map { it.value })
        assertTrue(rows.all { it.ts % 60 == 0L })
        assertEquals(300L, rows[1].ts - rows[0].ts)
    }
    @Test fun capturedPai() {
        val row = parsePai(blob("pai")).single()
        assertEquals(0.17, row.value)
        assertEquals("114.54", row.extra["total"])
        assertEquals(listOf("13", "1", "0"), listOf("minutes_low", "minutes_moderate", "minutes_high").map { row.extra[it] })
    }
    @Test fun spo2SixByteRecords() {
        val row = parseSpo2Sleep(blob("spo2_sleep")).single()
        assertEquals(99.0, row.value)
        assertEquals(0x6AE65476L, row.ts)
        assertEquals("spo2_sleep", row.type)
        assertEquals("0", row.extra["extra"])
    }
    @Test fun spo2Version2Normal() {
        val rec = buffer(65).putInt(1_700_000_000).put((-128 + 97).toByte()).array()
        assertEquals(HealthPoint("spo2", 1_700_000_000, 97.0, mapOf("auto" to "true")), parseSpo2(bytes(2) + rec).single())
        rec[4] = 98
        assertEquals(mapOf("auto" to "false"), parseSpo2(bytes(2) + rec).single().extra)
    }
    @Test fun spo2Version2SleepAndValidityBounds() {
        val rec = buffer(30).putInt(1_700_000_000).put(95).put(12).array()
        assertEquals(HealthPoint("spo2_sleep", 1_700_000_000, 95.0, mapOf("duration" to "12")), parseSpo2Sleep(bytes(2) + rec).single())
        val input = listOf(49, 50, 100, 101, 255).flatMap { (buffer(6).putInt(123).put(it.toByte()).put(7).array()).toList() }.toByteArray()
        assertEquals(listOf(50.0, 100.0), parseSpo2(input).map { it.value })
        assertEquals(emptyList(), parseSpo2(bytes(1, 2, 3)))
        assertEquals(emptyList(), parseSpo2(bytes(2))) // normal v2 zero records takes precedence
    }
    @Test fun temperatureAnchoredToFetchEnd() {
        val end = fixture["temperature"].obj()["end_ts"].num().toLong()
        val rows = parseTemperature(blob("temperature"), start("temperature"), end)
        assertEquals(100, rows.size)
        assertEquals(end / 60 * 60, rows.last().ts)
        assertTrue(rows.all { it.value!! > 15 && it.value < 40 })
        assertTrue(rows.last().value!! > 30)
    }
    @Test fun temperatureSkipsInvalidAndUsesConsistentStart() {
        val data = buffer(16).putShort(3100).putShort(3250).putShort(0x5A5A).putShort(0x5A5A)
            .putShort(0x5A5A).putShort(0x5A5A).putShort(0x5A5A).putShort(0x5A5A).array()
        assertEquals(listOf(HealthPoint("temperature", 6000, 32.5, mapOf("sensor2" to "31.0"))), parseTemperature(data, 6000, 6060))
    }
    @Test fun temperatureInvalidBoundsAndNullSensor() {
        val values = listOf(999, 1000, 4500, 4501, 0x5A5A, 0x7FFF, -1)
        val data = values.flatMap { buffer(8).putShort(0).putShort(it.toShort()).putInt(0x5A5A5A5A).array().toList() }.toByteArray()
        val rows = parseTemperature(data + bytes(1, 2, 3), null, 6000)
        assertEquals(listOf(10.0, 45.0), rows.map { it.value })
        assertEquals(listOf(5700L, 5760L), rows.map { it.ts })
        assertTrue(rows.all { it.extra["sensor2"] == "null" })
        assertTrue(parseTemperature(byteArrayOf(), null, 6000).isEmpty())
    }
    @Test fun temperatureConsistencyThresholdIsStrictlyGreaterThanOneHour() {
        val data = buffer(8).putShort(3100).putShort(3200).putInt(0x5A5A5A5A).array()
        assertEquals(6000L, parseTemperature(data, 6000, 9600).single().ts)
        assertEquals(9660L, parseTemperature(data, 6000, 9660).single().ts)
    }
    @Test fun manualFormatsKeepSentinelsAndSkipPartialRecords() {
        val manual = buffer(6).putInt(-1).put((-22).toByte()).put(255.toByte()).array()
        assertEquals(HealthPoint("manual_hr", 0xFFFFFFFFL, 255.0, mapOf("tz_q" to "-22")), parseManualHr(manual + bytes(3)).single())
        val stress = buffer(5).putInt(-1).put(255.toByte()).array()
        assertEquals(listOf(HealthPoint("stress_manual", 0xFFFFFFFFL, 255.0)), parseStressManual(stress + bytes(3)))
        assertEquals(listOf(HealthPoint("stress", -60, 0.0), HealthPoint("stress", 60, 100.0)), parseStress(bytes(0, 255, 100), -1))
    }
    @Test fun paiSkipsOldRecordsAndTrailingBytes() {
        val current = blob("pai")
        val old = current.copyOf().also { it[0] = 0 }
        assertEquals(parsePai(current), parsePai(old + current + bytes(1, 2, 3)))
        assertTrue(parsePai(ByteArray(101)).isEmpty())
    }
    @Test fun allCapturedHealthFieldsMatchPython() {
        val rows = mapOf("manual_hr" to parseManualHr(blob("manual_hr")), "stress" to parseStress(blob("stress"), start("stress")),
            "pai" to parsePai(blob("pai")), "spo2_sleep" to parseSpo2Sleep(blob("spo2_sleep")),
            "temperature" to parseTemperature(blob("temperature"), start("temperature"), fixture["temperature"].obj()["end_ts"].num().toLong()))
        rows.forEach { (kind, actual) ->
            val expected = oracle["health"].obj()[kind].arr().map { item ->
                val r = item.obj()
                HealthPoint(kind, r["ts"].num().toLong(), r["value"].num().toDouble(),
                    r.filterKeys { it != "ts" && it != "value" }.mapValues { (_, v) -> v?.toString() ?: "null" })
            }
            assertEquals(expected, actual, kind)
        }
    }
    @Test fun statisticsAndUnsupportedTypesRemainLossless() {
        assertEquals(listOf(HealthPoint("statistics", 6000, null, mapOf("raw" to "00ff0100"))), parseStatistics(hex("00ff0100"), 6000))
        assertTrue(parseStatistics(byteArrayOf(), 6000).isEmpty())
        for (type in listOf(FetchType.SLEEP_RESPIRATORY_RATE, FetchType.RESTING_HR, FetchType.MAX_HR, FetchType.SLEEP_SESSION, FetchType.HRV)) {
            assertEquals(listOf(HealthPoint(type.key, 6000, null, mapOf("raw" to "00ff"))), parseHealth(type, bytes(0, 255), 0, 6000))
            assertEquals(emptyList(), parseHealth(type, byteArrayOf(), null, 6000))
        }
    }
    @Test fun healthDispatcherPreservesTypeStrings() {
        val cases = mapOf(FetchType.MANUAL_HR to "manual_hr", FetchType.STRESS to "stress", FetchType.PAI to "pai", FetchType.SPO2_SLEEP to "spo2_sleep", FetchType.TEMPERATURE to "temperature")
        cases.forEach { (type, key) -> assertTrue(parseHealth(type, blob(key), start(key), 1793481707).all { it.type == key }) }
        assertTrue(parseHealth(FetchType.STRESS, bytes(20), null, 6000).isEmpty())
        assertTrue(parseHealth(FetchType.STRESS, bytes(20), 0, 6000).isEmpty())
        assertEquals("stress_manual", parseHealth(FetchType.STRESS_MANUAL, buffer(5).putInt(120).put(40).array(), null, 6000).single().type)
        assertEquals("spo2", parseHealth(FetchType.SPO2, blob("spo2_sleep"), null, 6000).single().type)
        assertEquals("statistics", parseHealth(FetchType.STATISTICS, bytes(3), null, 6000).single().type)
        assertFailsWith<IllegalArgumentException> { parseHealth(FetchType.WORKOUTS, byteArrayOf(), null, 6000) }
    }
}

internal fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
