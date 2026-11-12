package dev.heartline.protocol

import java.util.UUID
import java.util.zip.CRC32

/**
 * A-GPS for the watch: satellite almanacs and a week of predicted orbits, so its GPS finds a fix in seconds instead of
 * minutes. The data comes from Zepp's servers in two archives, `cep_7days.zip` (almanacs) and `lle_1week.zip` (orbit
 * predictions), and goes to the watch as one "UIHH" bundle over the firmware-transfer service.
 *
 * Sources: huami-token `build_gps_uihh` (the bundle) and Gadgetbridge `UpdateFirmwareOperation2020`,
 * `HuamiFirmwareType.AGPS_UIHH` and `AmazfitGTR2eFirmwareInfo` (the transfer, and that the GTR 2e takes this type).
 */
object Agps {
    /** The bundle's parts: file name in the archives, and its type byte in the bundle. Order as huami-token writes them. */
    val PARTS: List<Pair<String, Int>> = listOf(
        "gps_alm.bin" to 0x05, "gln_alm.bin" to 0x0F,
        "lle_bds.lle" to 0x86, "lle_gps.lle" to 0x87, "lle_glo.lle" to 0x88, "lle_gal.lle" to 0x89, "lle_qzss.lle" to 0x8A,
    )

    /**
     * The UIHH bundle from the archives' files (by name; [PARTS] says which). Each part is `01 type len32 crc32 data`, after
     * a 32-byte header: `UIHH 04 00 00 00 00 00 00 01`, the parts' CRC-32, 6 zero bytes, their length, 6 zero bytes.
     * All numbers little-endian.
     */
    fun bundle(files: Map<String, ByteArray>): ByteArray {
        val missing = PARTS.map { it.first }.filter { it !in files }
        require(missing.isEmpty()) { "The A-GPS download is missing ${missing.joinToString()}" }
        val body = java.io.ByteArrayOutputStream()
        for ((name, type) in PARTS) {
            val data = files.getValue(name)
            body.write(1); body.write(type); body.write(u32(data.size.toLong())); body.write(u32(crc32(data))); body.write(data)
        }
        val content = body.toByteArray()
        return byteArrayOf('U'.code.toByte(), 'I'.code.toByte(), 'H'.code.toByte(), 'H'.code.toByte(), 4, 0, 0, 0, 0, 0, 0, 1) +
            u32(crc32(content)) + ByteArray(6) + u32(content.size.toLong()) + ByteArray(6) + content
    }

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
    internal fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

/**
 * The firmware-transfer service as Gadgetbridge's `UpdateFirmwareOperation2020` drives it. Commands go to [CONTROL] and
 * the watch answers there as `10 cmd status ...`; file data goes to [DATA] in chunks the watch acknowledges with
 * `10 d4 offset32` (the next offset it wants).
 */
object FirmwareTransfer {
    val CONTROL: UUID = UUID.fromString("00001531-0000-3512-2118-0009af100700")
    val DATA: UUID = UUID.fromString("00001532-0000-3512-2118-0009af100700")
    const val REQUEST_PARAMETERS = 0xD0
    const val SEND_INFO = 0xD2
    const val START = 0xD3
    const val PROGRESS = 0xD4
    const val COMPLETE = 0xD5
    const val FINALIZE = 0xD6
    /** HuamiFirmwareType.AGPS_UIHH, (byte) -4. */
    const val TYPE_AGPS_UIHH = 0xFC

    /** `d2 type size32 crc32 chunk16 00 00 01 size32`: in the foreground, file 0 of 1. */
    fun info(type: Int, size: Int, crc32: Long, chunk: Int): ByteArray {
        val s = Agps.u32(size.toLong())
        return byteArrayOf(SEND_INFO.toByte(), type.toByte()) + s + Agps.u32(crc32) +
            byteArrayOf(chunk.toByte(), (chunk shr 8).toByte(), 0, 0, 1) + s
    }

    /** Whether [reply] is `10 cmd 01`. */
    fun ok(reply: ByteArray, cmd: Int): Boolean =
        reply.size >= 3 && reply[0] == 0x10.toByte() && reply[1] == cmd.toByte() && reply[2] == 1.toByte()

    /** The chunk length the watch takes, from its answer to [REQUEST_PARAMETERS] (`10 d0 01 xx lo hi ...`). */
    fun chunkLength(reply: ByteArray): Int? =
        if (ok(reply, REQUEST_PARAMETERS) && reply.size >= 6) (reply[4].toInt() and 0xFF) or ((reply[5].toInt() and 0xFF) shl 8) else null

    /** The offset the watch wants next, from `10 d4 offset32`. */
    fun progress(reply: ByteArray): Long? =
        if (reply.size >= 6 && reply[0] == 0x10.toByte() && reply[1] == PROGRESS.toByte())
            (0 until 4).fold(0L) { acc, i -> acc or ((reply[2 + i].toLong() and 0xFF) shl (8 * i)) } else null

    /** A readable reason for a refusal, with Gadgetbridge's two known codes. */
    fun refusal(reply: ByteArray): String = when {
        reply.size >= 3 && reply[1] == START.toByte() && reply[2] == 0x47.toByte() -> "the watch has no room for it"
        reply.size >= 3 && reply[1] == SEND_INFO.toByte() && reply[2] == 0x22.toByte() -> "the watch battery is too low"
        else -> "the watch refused it (" + reply.joinToString("") { "%02x".format(it) } + ")"
    }
}
