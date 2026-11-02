package dev.heartline.protocol

import org.junit.Test
import kotlin.test.*

class MusicPhoneTest {
    @Test fun encodeMusicStateHuamiFlagsStateReservedPositionStringsDurationAllLittleEndian() {
        // HuamiSupport.encodeMusicState: flags 1|2|4|8|16, state 1, reserved 0, pos 0x1234,
        // artist/album/track NUL, duration 0x5678; old chunked endpoint type 3.
        assertContentEquals(bytes(31, 1, 0, 0x34, 0x12) + "Artist\u0000Album\u0000Title\u0000".toByteArray() + bytes(0x78, 0x56),
            musicPayload(WatchMusic(true, "Artist", "Album", "Title", 0x5678, 0x1234)))
    }
    @Test fun emptyPausedStateAsInHuamiSupportHasOnlyStateFlag() {
        assertContentEquals(bytes(1, 0, 0, 0, 0), musicPayload(WatchMusic()))
        assertContentEquals(bytes(0, 0xC3, 0, 1, 0, 0, 0, 0), chunkedWrites(musicPayload(WatchMusic()), type = 3).single())
    }
    @Test fun absentMetadataFieldsDoNotSetFlagsOrAddTerminators() {
        assertContentEquals(bytes(9, 0, 0, 0, 0, 84, 0), musicPayload(WatchMusic(title = "T")))
        assertContentEquals(bytes(3, 0, 0, 0, 0) + "艺术家\u0000".toByteArray(), musicPayload(WatchMusic(artist = "艺术家")))
    }
    @Test fun durationAndPositionFitUnsignedShortAndEmptyDurationIsOmitted() {
        assertContentEquals(bytes(17, 0, 0, 255, 255, 255, 255), musicPayload(WatchMusic(durationSeconds = 100000, positionSeconds = 100000)))
        assertContentEquals(bytes(1, 0, 0, 0, 0), musicPayload(WatchMusic(durationSeconds = -1, positionSeconds = -1)))
    }
    @Test fun gadgetbridgeEightyCharacterMetadataLimitPreservesUtf8() {
        val payload = musicPayload(WatchMusic(title = "A".repeat(79) + "🎵rest"))
        assertContentEquals(bytes(9, 0, 0, 0, 0) + "A".repeat(79).toByteArray() + bytes(0), payload)
        assertFalse(payload.toString(Charsets.UTF_8).contains('\uFFFD'))
    }
    @Test fun volumeFromHuamiSupportOnSetPhoneVolumeIsFlag40PlusPercent() {
        assertContentEquals(bytes(64, 67), musicVolumePayload(67))
        assertContentEquals(bytes(0, 0xC3, 0, 64, 67), chunkedWrites(musicVolumePayload(67), type = 3).single())
        assertContentEquals(bytes(64, 100), musicVolumePayload(200))
        assertContentEquals(bytes(64, 0), musicVolumePayload(-1))
    }
    @Test fun findPhoneEventsAndAcknowledgementFromHuamiSupportHandleDeviceEventAndAmazfitBipService() {
        assertEquals(PhoneEvent.FIND_START, phoneEvent(bytes(8)))
        assertEquals(PhoneEvent.FIND_STOP, phoneEvent(bytes(15)))
        assertContentEquals(bytes(6, 20, 0, 0), FIND_PHONE_ACK)
    }
    @Test fun everyMusicEventFromHuamiSupportHandleDeviceEvent() {
        mapOf(0 to PhoneEvent.PLAY, 1 to PhoneEvent.PAUSE, 3 to PhoneEvent.NEXT, 4 to PhoneEvent.PREVIOUS,
            5 to PhoneEvent.VOLUME_UP, 6 to PhoneEvent.VOLUME_DOWN, 224 to PhoneEvent.MUSIC_OPEN, 225 to PhoneEvent.MUSIC_CLOSE)
            .forEach { (command, event) -> assertEquals(event, phoneEvent(bytes(254, command))) }
    }
    @Test fun malformedAndUnrelatedEventsAreIgnoredWithoutAffectingLogging() {
        listOf(byteArrayOf(), bytes(254), bytes(254, 255), bytes(1), bytes(0, 8)).forEach { assertNull(phoneEvent(it)) }
        assertEquals("music_control:next", deviceEventName(bytes(254, 3)))
    }
}
