package dev.heartline.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class MicDecoderTest {
    @Test fun realWatchFixtureDecodesTo2400HzMonoWav() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("mic_2400hz.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("frames") }
        val pcm = ArrayList<Short>(); val levels = ArrayList<Float>()
        val file = File(context.cacheDir, "mic-2400hz.wav")
        MicDecoder(file, onPcm = { pcm.addAll(it.toList()) }, onLevel = { levels.add(it) }).use { decoder ->
            for (i in 0 until fixture.length()) {
                val hex = fixture.getString(i)
                decoder.feed(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            }
        }
        assertEquals(55, fixture.length())
        assertEquals(55 * 960, pcm.size)
        assertEquals(55, levels.size)
        assertTrue(levels.all { it.isFinite() && it <= 0 })
        val bytes = file.readBytes(); val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, header.getInt(4)); assertEquals(pcm.size * 2, header.getInt(40)); assertEquals(48000, header.getInt(24))
        val frequency = dominant(pcm.drop(4800).toShortArray())
        android.util.Log.i("HeartlineMicTest", "Decoded ${pcm.size} PCM samples, ${levels.size} frames; dominant frequency $frequency Hz")
        assertEquals(2400.0, frequency, 30.0)
    }
    private fun dominant(pcm: ShortArray): Double {
        var n = 1; while (n * 2 <= pcm.size) n *= 2
        val re = DoubleArray(n) { pcm[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }; val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val tmp = re[i]; re[i] = re[j]; re[j] = tmp }
        }
        var len = 2
        while (len <= n) {
            val wr = cos(-2 * PI / len); val wi = sin(-2 * PI / len)
            for (base in 0 until n step len) {
                var xr = 1.0; var xi = 0.0
                for (k in 0 until len / 2) {
                    val a = base + k; val b = a + len / 2; val tr = re[b] * xr - im[b] * xi; val ti = re[b] * xi + im[b] * xr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val next = xr * wr - xi * wi; xi = xr * wi + xi * wr; xr = next
                }
            }; len *= 2
        }
        val peak = (1 until n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        return peak * 48000.0 / n
    }
}
