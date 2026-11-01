package dev.heartline.protocol

import org.junit.Test
import kotlin.test.*

class GpsTest {
    @Test fun fixAndSatellitesAcrossChunks() {
        val n = Nmea()
        val text = "\$GPGSV,1,1,02,05,45,120,38,12,10,300,,\r\n" +
            "\$GNRMC,215503.00,A,2830.0000,N,07700.0000,E,1.0,90.0,011126,,,A\r\n" +
            "\$GPGGA,215503.00,2830.0000,N,07700.0000,E,1,07,1.2,215.0,M,,M,,\r\n"
        assertFalse(n.feed(text.take(40)))
        assertTrue(n.feed(text.drop(40) + "\r\n"))
        val s = n.state
        assertTrue(s.fix)
        assertEquals(7, s.satsUsed)
        assertEquals(215.0, s.altM)
        assertEquals(28.5, s.lat)
        assertEquals(77.0, s.lon)
        assertEquals(0.51, s.speedMps)
        assertEquals("2026-11-01T21:55:03+00:00", s.utc)
        assertEquals(setOf("05", "12"), s.satellites.map { it.id }.toSet())
        assertNull(s.satellites.first { it.id == "12" }.snr)
    }
    @Test fun rejectsBadChecksum() {
        val n = Nmea()
        assertFalse(n.feed("\$GPGGA,215503.00,2830.0,N,07700.0,E,1,07,1.2,215.0,M,,M,,*FF\r\n"))
        assertEquals(0, n.state.satsUsed)
        assertFalse(n.feed("\$GPGGA,1*ZZ\n"))
        assertFalse(n.feed("\$GPGGA,1*\n"))
    }
    @Test fun acceptsRealChecksum() {
        val n = Nmea()
        assertTrue(n.feed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"))
        assertEquals(48.1173, n.state.lat)
        assertEquals(11.5166667, n.state.lon)
        assertEquals(8, n.state.satsUsed)
    }
    @Test fun matchesPythonStateForEverySentence() {
        val n = Nmea()
        oracle["gps"].arr().forEach { item ->
            val row = item.obj()
            assertEquals(row["updated"], n.feed(row["text"].str()))
            assertEquals(gps(row["state"].obj()), n.state, row["text"].str())
        }
    }
    @Test fun ignoresBrokenSentencesAndLimitsUnfinishedBuffer() {
        val n = Nmea()
        assertFalse(n.feed("\$\r\n\$GPGGA,1\n\$GNRMC,x,A\n\$GPGSV,1\n"))
        assertFalse(n.feed("x".repeat(4097)))
        assertTrue(n.feed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\n"))
    }
    @Test fun vtgOnlyFillsMissingSpeed() {
        val n = Nmea()
        assertFalse(n.feed("\$GPVTG,,,,,,,7.2,K\n"))
        assertEquals(2.0, n.state.speedMps)
        n.feed("\$GPVTG,,,,,,,3.6,K\n")
        assertEquals(2.0, n.state.speedMps)
    }
    @Test fun lostFixRetainsPosition() {
        val n = Nmea()
        n.feed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\n")
        assertTrue(n.feed("\$GPGGA,,,,,,0,00,99.9,,M,,M,,\n"))
        assertFalse(n.state.fix)
        assertEquals(48.1173, n.state.lat)
        assertNull(n.state.altM)
    }
    private fun gps(m: Map<String, Any?>): GpsState {
        fun number(key: String) = (m[key] as? Number)?.toDouble()
        return GpsState(m["fix"] as Boolean, m["quality"].num().toInt(), number("lat"), number("lon"), number("alt_m"),
            number("speed_mps"), number("course"), number("hdop"), m["sats_used"].num().toInt(), m["utc"] as String?,
            m["satellites"].arr().map { it.obj() }.map { s -> Satellite(s["id"].str(), s["system"].str(),
                (s["elev"] as? Number)?.toDouble(), (s["az"] as? Number)?.toDouble(), (s["snr"] as? Number)?.toDouble()) })
    }
}
