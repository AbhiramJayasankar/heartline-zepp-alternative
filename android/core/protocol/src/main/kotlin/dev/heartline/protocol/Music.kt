package dev.heartline.protocol

import java.io.ByteArrayOutputStream

data class WatchMusic(
    val playing: Boolean = false, val artist: String = "", val album: String = "", val title: String = "",
    val durationSeconds: Int = 0, val positionSeconds: Int = 0,
)

/** HuamiSupport.encodeMusicState(..., includeVolume=false); HuamiService.MUSIC_FLAG_*.
 * Send on old chunked type 3. Volume is a separate command as in onSetPhoneVolume.
 */
fun musicPayload(music: WatchMusic): ByteArray {
    fun field(s: String): ByteArray {
        val clean = s.replace("\u0000", "").take(80)
        // Avoid splitting a supplementary character at Gadgetbridge's 80 UTF-16 character limit.
        return (if (clean.lastOrNull()?.isHighSurrogate() == true) clean.dropLast(1) else clean).toByteArray(Charsets.UTF_8)
    }
    val artist = field(music.artist); val album = field(music.album); val title = field(music.title)
    val duration = music.durationSeconds.coerceIn(0, 65535)
    val position = music.positionSeconds.coerceIn(0, 65535)
    var flags = 1
    if (artist.isNotEmpty()) flags = flags or 2
    if (album.isNotEmpty()) flags = flags or 4
    if (title.isNotEmpty()) flags = flags or 8
    if (duration != 0) flags = flags or 16
    return ByteArrayOutputStream().apply {
        write(flags); write(if (music.playing) 1 else 0); write(0)
        write(position and 255); write(position shr 8)
        for (s in listOf(artist, album, title)) if (s.isNotEmpty()) { write(s); write(0) }
        if (duration != 0) { write(duration and 255); write(duration shr 8) }
    }.toByteArray()
}

// HuamiSupport.onSetPhoneVolume: MUSIC_FLAG_VOLUME, Math.round(volume percent).
fun musicVolumePayload(percent: Int): ByteArray = bytes(0x40, percent.coerceIn(0, 100))
