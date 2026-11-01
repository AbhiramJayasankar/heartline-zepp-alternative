package dev.heartline.data

import java.io.Writer

object Csv {
    /** RFC 4180 escaping, CRLF records, empty field for SQL NULL, lowercase hex for BLOBs. */
    fun writeRow(out: Writer, cells: List<Any?>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) out.write(",")
            val text = when (cell) { null -> ""; is ByteArray -> Hex.encode(cell); else -> cell.toString() }
            if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                out.write("\""); out.write(text.replace("\"", "\"\"")); out.write("\"")
            } else out.write(text)
        }
        out.write("\r\n")
    }
}
