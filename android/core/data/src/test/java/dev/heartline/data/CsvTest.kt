package dev.heartline.data

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun simpleFieldsAndNull() {
        val out = StringWriter()
        Csv.writeRow(out, listOf(123L, 36.25, null, ""))
        assertEquals("123,36.25,,\r\n", out.toString())
    }
    @Test fun quotesCommasQuotesAndNewlines() {
        val out = StringWriter()
        Csv.writeRow(out, listOf("a,b", "say \"hi\"", "a\nb", "a\rb"))
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"a\nb\",\"a\rb\"\r\n", out.toString())
    }
    @Test fun preservesUnicodeAndJson() {
        val out = StringWriter()
        Csv.writeRow(out, listOf("心率", "{\"x\":1}"))
        assertEquals("心率,\"{\"\"x\"\":1}\"\r\n", out.toString())
    }
    @Test fun blobsAreLosslessHex() {
        val out = StringWriter()
        Csv.writeRow(out, listOf(byteArrayOf(0, -1, 10), byteArrayOf()))
        assertEquals("00ff0a,\r\n", out.toString())
    }
    @Test fun emptyRowAndConsecutiveRows() {
        val out = StringWriter()
        Csv.writeRow(out, emptyList())
        Csv.writeRow(out, listOf("x"))
        assertEquals("\r\nx\r\n", out.toString())
    }
}
