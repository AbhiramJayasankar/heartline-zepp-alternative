package dev.heartline.data

import org.junit.Assert.*
import org.junit.Test

class HexTest {
    @Test fun mixedCaseAuthKey() {
        val key = Hex.authKey("00112233445566778899AabBcCDdEeFf")
        assertEquals(16, key.size)
        assertEquals("00112233445566778899aabbccddeeff", Hex.encode(key))
    }
    @Test fun rejectsWrongLengths() {
        listOf("", "00", "0".repeat(31), "0".repeat(33), "0".repeat(64)).forEach {
            assertFalse(Hex.isValidAuthKey(it))
            assertThrows(IllegalArgumentException::class.java) { Hex.authKey(it) }
        }
    }
    @Test fun rejectsWhitespacePrefixesAndNonAscii() {
        listOf("0x" + "0".repeat(30), " " + "0".repeat(31), "0".repeat(31) + "\n",
            "g".repeat(32), "０".repeat(32)).forEach { assertFalse(Hex.isValidAuthKey(it)) }
    }
    @Test fun allByteValuesRoundTripAsHex() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = Hex.encode(bytes)
        assertEquals(512, encoded.length)
        assertTrue(encoded.startsWith("00010203"))
        assertTrue(encoded.endsWith("fcfdfeff"))
    }
}
