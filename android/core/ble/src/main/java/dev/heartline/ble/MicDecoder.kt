package dev.heartline.ble

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frames come from protocol.OpusFrameSplitter; Android decodes SILK NB to mono PCM at 48 kHz. */
class MicDecoder(file: File, private val onPcm: (ShortArray) -> Unit = {}, private val onLevel: (Float) -> Unit = {}) : AutoCloseable {
    private val wav = RandomAccessFile(file, "rw")
    private val codec = MediaCodec.createDecoderByType("audio/opus")
    private var samples = 0L
    private var frames = 0L
    private var closed = false
    private val info = MediaCodec.BufferInfo()
    init {
        try {
            wav.setLength(0); wav.write(AudioHeaders.wav(0))
            val format = MediaFormat.createAudioFormat("audio/opus", AudioHeaders.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setByteBuffer("csd-0", ByteBuffer.wrap(AudioHeaders.opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(AudioHeaders.nanos(0)))
                setByteBuffer("csd-2", ByteBuffer.wrap(AudioHeaders.nanos(80_000_000)))
            }
            codec.configure(format, null, null, 0); codec.start()
        } catch (e: Throwable) { codec.release(); wav.close(); throw e }
    }
    fun feed(frame: ByteArray) {
        check(!closed)
        var index = codec.dequeueInputBuffer(10_000)
        if (index < 0) { drain(false); index = codec.dequeueInputBuffer(100_000) }
        check(index >= 0) { "Opus decoder input timed out" }
        codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
        codec.queueInputBuffer(index, 0, frame.size, frames++ * 20_000, 0)
        drain(false)
    }
    private fun drain(eos: Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == AudioHeaders.SAMPLE_RATE)
                check(codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                continue
            }
            if (index >= 0) {
                try {
                    val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size); buffer.get(bytes)
                    val pcm = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    wav.write(bytes); samples += pcm.size
                    if (pcm.isNotEmpty()) { onPcm(pcm); onLevel(AudioHeaders.dbfs(pcm)) }
                } finally { codec.releaseOutputBuffer(index, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else if (!eos) return
            check(System.nanoTime() < deadline) { "Opus decoder output timed out" }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            val index = codec.dequeueInputBuffer(100_000)
            check(index >= 0) { "Opus EOS input timed out" }
            codec.queueInputBuffer(index, 0, 0, frames * 20_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            try { codec.stop() } finally { codec.release(); wav.seek(0); wav.write(AudioHeaders.wav(samples * 2)); wav.close() }
        }
    }
}
