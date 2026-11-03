package dev.heartline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchMenuTest {
    @Test fun menuListsAppsInOrder() {
        // setDisplayItemsNew: 1e, then index, 00, ff, HuamiMenuType id per app
        assertEquals("1e" + "0000ff01" + "0100ff02" + "0200ff13", WatchMenu.command(listOf("status", "hr", "settings")).hex())
    }

    @Test fun unknownAppsAreDropped() {
        assertEquals("1e0000ff04", WatchMenu.command(listOf("nope", "weather")).hex())
    }

    @Test fun moreIsForcedAfterSixteen() {
        val keys = WatchMenu.APPS.map { it.key }.filter { it != "more" }.take(18)
        val c = WatchMenu.command(keys)
        assertEquals(1 + 19 * 4, c.size)
        assertEquals(0x07, c[1 + 16 * 4 + 3].toInt()) // "more" sits at position 16
    }

    @Test fun vibrationPatternBytes() {
        val calls = VibrationPatterns.KINDS.first { it.key == "incoming_call" }
        val short = VibrationPatterns.PROFILES.first { it.key == "short" }
        // 20 01, flags = 2 pairs | 0x40, then 200 200 200 200 as u16
        assertEquals("200142" + "c800c800c800c800", VibrationPatterns.command(calls, short, 2).hex())
        assertEquals("2001c2" + "c800c800c800c800", VibrationPatterns.command(calls, short, 2, test = true).hex())
    }

    @Test fun vibrationIsCappedAtTenSeconds() {
        val long = VibrationPatterns.PROFILES.first { it.key == "long" }
        assertEquals(10_000, VibrationPatterns.onOff(long, 20).sum())
    }
}
