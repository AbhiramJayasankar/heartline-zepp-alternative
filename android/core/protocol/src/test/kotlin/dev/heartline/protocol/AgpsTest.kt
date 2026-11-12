package dev.heartline.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgpsTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val files = mapOf(
        "gps_alm.bin" to hex("a08008008b07") + "ALM".toByteArray(), "gln_alm.bin" to "GLN-almanac".toByteArray(),
        "lle_bds.lle" to "bds".repeat(5).toByteArray(), "lle_gps.lle" to "gps-lle".toByteArray(), "lle_glo.lle" to byteArrayOf(0, 1, 2),
        "lle_gal.lle" to "galileo".toByteArray(), "lle_qzss.lle" to "q".toByteArray(),
    )

    /** Byte for byte what huami-token's build_gps_uihh writes for the same files. */
    @Test fun bundleMatchesHuamiToken() {
        val expected = hex("5549484804000000000000015250f9290000000000007b000000000000000000010509000000163e611ca08008008b07414c4d010f0b00000078d28a3e474c4e2d616c6d616e616301860f0000004be240be62647362647362647362647362647301870700000010ce28816770732d6c6c650188030000007f8954080001020189070000004082738e67616c696c656f018a0100000027ae00f571")
        val b = Agps.bundle(files)
        assertArrayEquals(expected, b)
        assertEquals(155, b.size)
        assertEquals(0xb72a0e4aL, Agps.crc32(b))
    }

    @Test(expected = IllegalArgumentException::class) fun missingPartIsRefused() { Agps.bundle(files - "lle_gal.lle") }

    @Test fun infoCommandLayout() {
        assertArrayEquals(hex("d2fc9b0000004a0e2ab7f4000000019b000000"), FirmwareTransfer.info(0xFC, 155, 0xb72a0e4aL, 244))
    }

    @Test fun replies() {
        assertEquals(0x1000, FirmwareTransfer.chunkLength(hex("10d00100001000")))
        assertNull(FirmwareTransfer.chunkLength(hex("10d002")))
        assertEquals(0x01020304L, FirmwareTransfer.progress(hex("10d404030201")))
        assertNull(FirmwareTransfer.progress(hex("10d501")))
        assertTrue(FirmwareTransfer.ok(hex("10d601"), FirmwareTransfer.FINALIZE))
        assertFalse(FirmwareTransfer.ok(hex("10d647"), FirmwareTransfer.FINALIZE))
        assertEquals("the watch has no room for it", FirmwareTransfer.refusal(hex("10d347")))
        assertEquals("the watch battery is too low", FirmwareTransfer.refusal(hex("10d222")))
    }
}
