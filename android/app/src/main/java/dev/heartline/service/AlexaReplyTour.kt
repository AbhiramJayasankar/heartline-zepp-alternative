package dev.heartline.service

import dev.heartline.protocol.Alexa
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exploration: which Alexa reply forms does the GTR 2e show? The watch ignores forms it does not understand and shows
 * the first one it does (2026-11-03: the 08/06 card was ignored, the 09 simple reply shown), so each session sends a
 * list of lettered candidates a few seconds apart and ends with a plain fallback. The letter on the watch names the
 * form that works. Gadgetbridge only documents the simple reply and the 08/06 rich-text card; the rest are guesses.
 */
object AlexaReplyTour {
    class Candidate(val letter: String, val what: String, val payload: ByteArray)
    class Stop(val title: String, val candidates: List<Candidate>, val fallback: String?)

    private fun z(s: String) = s.toByteArray(Charsets.UTF_8) + 0
    private fun complex(subtype: Int, body: ByteArray, subtypeBytes: Int = 2, bigEndian: Boolean = false, withLength: Boolean = true): ByteArray {
        val b = ByteBuffer.allocate(1 + subtypeBytes + (if (withLength) 4 else 0) + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(Alexa.REPLY_COMPLEX.toByte())
        when {
            subtypeBytes == 1 -> b.put(subtype.toByte())
            bigEndian -> { b.put(0); b.put(subtype.toByte()) }
            else -> b.putShort(subtype.toShort())
        }
        if (withLength) b.putInt(body.size)
        b.put(body)
        return b.array()
    }
    private fun card(letter: String) = z(letter) + z("Subtitle $letter") + z("Reply $letter, a card.")
    private fun cmd(c: Int, vararg parts: ByteArray) = byteArrayOf(c.toByte()) + parts.fold(ByteArray(0)) { a, p -> a + p }

    private val LONG = "Reply I: a long simple reply. Line one ends here.\nLine two after a newline.\n\n" +
        "Paragraph two. " + (1..30).joinToString(" ") { "word$it" } + ". " +
        "Paragraph three, still going so the watch has to scroll or cut it: " + (31..80).joinToString(" ") { "word$it" } + ". The end."

    val stops: List<Stop> = listOf(
        Stop("card layouts", listOf(
            Candidate("A", "08, 1-byte subtype 06, length, title/subtitle/text", complex(6, card("A"), subtypeBytes = 1)),
            Candidate("B", "08, subtype 06 big-endian, length", complex(6, card("B"), bigEndian = true)),
            Candidate("C", "08 subtype 03 card", complex(3, card("C"))),
            Candidate("D", "08 subtype 04 card", complex(4, card("D"))),
            Candidate("E", "08 subtype 05 card", complex(5, card("E"))),
            Candidate("F", "08 subtype 07 card", complex(7, card("F"))),
        ), "Z: none of the card layouts showed."),
        Stop("weather and reminder", listOf(
            Candidate("G", "08 subtype 01 weather (Gadgetbridge's partial layout)", complex(1,
                byteArrayOf(0xfd.toByte(), 3, 0, 0) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((System.currentTimeMillis() / 1000).toInt()).array() +
                    z("G Weather town") + z("Tuesday") + z("Sunny") + z("Clear all day") + byteArrayOf(0))),
            Candidate("H", "08 subtype 02 reminder, title then text", complex(2, z("H Reminder") + z("Reply H, a reminder."))),
            Candidate("H2", "08 subtype 02 reminder, title, time, text", complex(2, z("H2 Reminder") +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((System.currentTimeMillis() / 1000 + 3600).toInt()).array() + z("Reply H2, a reminder."))),
        ), "Z: no weather or reminder showed."),
        Stop("long text", listOf(Candidate("I", "09 simple reply, ~700 characters with newlines", Alexa.replySimple(LONG))), null),
        Stop("follow-up flag", listOf(Candidate("J", "09 simple reply with a trailing 01 byte (keep listening?)",
            Alexa.replySimple("Reply J: does the watch listen again by itself?") + byteArrayOf(1))), null),
        Stop("unknown commands", listOf(
            Candidate("K", "command 07 with text", cmd(0x07, z("Reply K, command 07."))),
            Candidate("L", "command 0c with text", cmd(0x0c, z("Reply L, command 0c."))),
            Candidate("M", "command 0d with text", cmd(0x0d, z("Reply M, command 0d."))),
            Candidate("N", "command 0e with text", cmd(0x0e, z("Reply N, command 0e."))),
        ), "Z: none of the unknown commands showed."),
    )
}
