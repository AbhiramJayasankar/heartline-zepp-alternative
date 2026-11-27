package dev.heartline.app

import dev.heartline.analytics.LiveCheckSamples
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A made-up heart check streamed second by second for the demo: a resting pulse near 62 bpm with breathing in its
 * beat gaps, and the hand moving from 14 to 20 s, so the live chart shows beats set aside for movement.
 */
internal class DemoLiveStream {
    private val rnd = Random(11)
    private val beats = ArrayList<Double>().apply { var t = .5; while (t < 700) { add(t); t += .97 + .05 * sin(2 * PI * .24 * t) + rnd.nextDouble(-.008, .008) } }
    private var second = 0
    val samples = LiveCheckSamples()
    private fun moving(t: Double) = t in 14.0..20.0
    private fun swing(t: Double) = if (moving(t)) .3 * sin(2 * PI * 1.4 * t) + .12 * sin(2 * PI * 3.1 * t) else 0.0
    private fun pulse(t: Double): Double {
        var v = 0.0
        for (b in beats) { val d = t - b; if (d < -.2) break; if (d < 1) v += exp(-(d / .07).pow(2) / 2) + .35 * exp(-((d - .3) / .09).pow(2) / 2) }
        return v
    }
    /** Adds the next second of PPG (50 Hz) and accelerometer (26 Hz). */
    fun next() {
        val t0 = second.toDouble(); second++
        val e0 = 1_800_000_000.0; val w0 = 1000.0
        val pt = DoubleArray(50) { t0 + it / 50.0 }
        samples.addPpg(DoubleArray(50) { e0 + pt[it] }, DoubleArray(50) { w0 + pt[it] },
            DoubleArray(50) { (26800 - 230 * pulse(pt[it]) + 35 * sin(2 * PI * .05 * pt[it]) + rnd.nextDouble(-6.0, 6.0) + 2600 * swing(pt[it] - .04)).toInt().toDouble() })
        val at = DoubleArray(26) { t0 + it / 26.16 }
        samples.addAccel(DoubleArray(26) { e0 + at[it] }, DoubleArray(26) { w0 + at[it] }, at.map { t ->
            floatArrayOf((swing(t) + rnd.nextDouble(-.004, .004)).toFloat(), (.5 * swing(t + .2)).toFloat(), (1 + .4 * swing(t + .1)).toFloat())
        })
    }
}
