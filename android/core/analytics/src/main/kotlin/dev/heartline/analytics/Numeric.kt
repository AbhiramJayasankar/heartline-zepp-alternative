package dev.heartline.analytics

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.*

// Python round uses nearest-even on the actual binary float, including decimal ties.
internal fun rounded(x: Double, digits: Int = 0): Double =
    if (x.isFinite()) BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble() else x
internal fun mean(x: DoubleArray): Double = x.sum() / x.size
internal fun variance(x: DoubleArray, ddof: Int = 0): Double {
    val m = mean(x)
    var s = 0.0
    for (v in x) s += (v - m) * (v - m)
    return s / (x.size - ddof)
}
internal fun median(x: DoubleArray): Double = percentile(x, 50.0)
internal fun percentile(x: DoubleArray, p: Double): Double {
    if (x.isEmpty()) return Double.NaN
    val s = x.sortedArray()
    val i = (s.size - 1) * p / 100
    val a = i.toInt()
    return s[a] + (s[min(a + 1, s.lastIndex)] - s[a]) * (i - a)
}
internal fun grid(start: Double, stop: Double, step: Double): DoubleArray =
    DoubleArray(max(0, ceil((stop - start) / step).toInt())) { start + it * step }
internal fun interpolate(t: DoubleArray, x: DoubleArray, at: DoubleArray): DoubleArray {
    if (t.isEmpty()) return DoubleArray(at.size)
    var j = 0
    return DoubleArray(at.size) { i ->
        val a = at[i]
        while (j < t.lastIndex && t[j + 1] <= a) j++
        when {
            a <= t[0] -> x[0]
            a >= t.last() -> x.last()
            else -> x[j] + (x[j + 1] - x[j]) * ((a - t[j]) / (t[j + 1] - t[j]))
        }
    }
}
internal fun correlation(a: DoubleArray, b: DoubleArray): Double {
    val am = mean(a)
    val bm = mean(b)
    var ab = 0.0
    var aa = 0.0
    var bb = 0.0
    for (i in a.indices) {
        val x = a[i] - am
        val y = b[i] - bm
        ab += x * y
        aa += x * x
        bb += y * y
    }
    return ab / sqrt(aa * bb)
}

internal data class Complex(val re: Double, val im: Double = 0.0) {
    operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
    operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
    operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }
    fun abs() = hypot(re, im)
    fun sqrt(): Complex = Complex(sqrt(max(0.0, (abs() + re) / 2)),
        sqrt(max(0.0, (abs() - re) / 2)).withSign(im))
}

internal data class Sos(
    val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double,
    val zi0: Double? = null, val zi1: Double? = null,
)

// The three fixed designs used by the Python analytics, including scipy.sosfilt_zi.
// Cache these to avoid redesigning on every check, and preserve the exact binary
// coefficients/states: one ulp changes the reference's roundoff peaks on constant PPG.
private val ppgLowpass = listOf(Sos(
    0.00024135904904198073, 0.00048271809808396145, 0.00024135904904198073,
    -1.9555782403150352, 0.9565436765112031, 0.9997586409510377, -0.9563023174622374))
private val ppgBandpass = listOf(
    Sos(0.010432413371093414, 0.02086482674218683, 0.010432413371093414,
        -1.717844586580564, 0.7634885625932664, 0.903809883854737, -0.6875811239998215),
    Sos(1.0, -2.0, 1.0, -1.9585309426687274, 0.9597079331261205, -0.9142422972258303, 0.9142422972258303))
private val respirationBandpass = listOf(
    Sos(0.06745527388907191, 0.13491054777814382, 0.06745527388907191,
        -1.141465663703612, 0.49628553076498416, 0.692990012872781, -0.30994271886926483),
    Sos(1.0, -2.0, 1.0, -1.8030661124614102, 0.8317824568850278, -0.7604452867618529, 0.7604452867618529))

/** Order-2 Butterworth prototype, prewarping, LP->BP and bilinear transforms, as scipy.signal.butter.
 * Only the order used by ppg.py/respiration.py is needed. */
internal fun butterworth(fs: Double, lo: Double, hi: Double? = null): List<Sos> {
    if (fs == 100.0 && lo == 0.5 && hi == null) return ppgLowpass
    if (fs == 100.0 && lo == 0.5 && hi == 4.0) return ppgBandpass
    if (fs == 4.0 && lo == 0.1 && hi == 0.5) return respirationBandpass
    val k = 2 * fs
    val w1 = k * tan(PI * lo / fs)
    val p = Complex(-sqrt(0.5), sqrt(0.5))
    if (hi == null) {
        val analog = p * Complex(w1)
        val z = (Complex(k) + analog) / (Complex(k) - analog)
        val a1 = -2 * z.re
        val a2 = z.abs().pow(2)
        val gain = (1 + a1 + a2) / 4
        return listOf(Sos(gain, 2 * gain, gain, a1, a2))
    }
    val w2 = k * tan(PI * hi / fs)
    val wo = sqrt(w1 * w2)
    val bp = p * Complex((w2 - w1) / 2)
    val root = (bp * bp - Complex(wo * wo)).sqrt()
    val poles = listOf(bp + root, bp - root).map { (Complex(k) + it) / (Complex(k) - it) }
        .sortedBy { it.abs() }
    val sections = poles.mapIndexed { i, z -> Sos(1.0, if (i == 0) 2.0 else -2.0,
        1.0, -2 * z.re, z.abs().pow(2)) }
    val theta = 2 * atan(wo / k)
    val z1 = Complex(cos(theta), -sin(theta))
    val z2 = z1 * z1
    var response = Complex(1.0)
    for (s in sections) response = response *
        ((Complex(s.b0) + Complex(s.b1) * z1 + Complex(s.b2) * z2) /
            (Complex(1.0) + Complex(s.a1) * z1 + Complex(s.a2) * z2))
    val gain = 1 / response.abs()
    return listOf(sections[0].let { it.copy(b0 = gain, b1 = 2 * gain, b2 = gain) }, sections[1])
}

/** scipy.sosfiltfilt: odd extension, default padlen, steady-state zi, forward then backward. */
internal fun zeroPhase(x: DoubleArray, sos: List<Sos>): DoubleArray {
    val pad = 3 * (2 * sos.size + 1)
    require(x.size > pad) { "Signal must be longer than filter padding ($pad)." }
    val y = DoubleArray(x.size + 2 * pad)
    for (i in 0 until pad) y[i] = 2 * x[0] - x[pad - i]
    x.copyInto(y, pad)
    for (i in 0 until pad) y[pad + x.size + i] = 2 * x.last() - x[x.lastIndex - 1 - i]
    fun filter() {
        val x0 = y[0]
        var scale = 1.0
        for (s in sos) {
            val dc = (s.b0 + s.b1 + s.b2) / (1 + s.a1 + s.a2)
            var z0 = (s.zi0 ?: ((dc - s.b0) * scale)) * x0
            var z1 = (s.zi1 ?: ((s.b2 - s.a2 * dc) * scale)) * x0
            scale *= dc
            for (i in y.indices) {
                val v = y[i]
                val result = s.b0 * v + z0
                z0 = s.b1 * v - s.a1 * result + z1
                z1 = s.b2 * v - s.a2 * result
                y[i] = result
            }
        }
    }
    filter()
    y.reverse()
    filter()
    y.reverse()
    return y.copyOfRange(pad, pad + x.size)
}

internal fun detrend(x: DoubleArray): DoubleArray {
    val center = (x.size - 1) / 2.0
    val m = mean(x)
    var num = 0.0
    var den = 0.0
    for (i in x.indices) {
        num += (i - center) * (x[i] - m)
        den += (i - center).pow(2)
    }
    val slope = num / den
    return DoubleArray(x.size) { x[it] - m - slope * (it - center) }
}

internal fun powerSpectrum(x: DoubleArray, n: Int): DoubleArray {
    val re = x.copyOf(n)
    val im = DoubleArray(n)
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { val v = re[i]; re[i] = re[j]; re[j] = v }
    }
    var size = 2
    while (size <= n) {
        val angle = -2 * PI / size
        val wr = cos(angle)
        val wi = sin(angle)
        for (a in 0 until n step size) {
            var ur = 1.0
            var ui = 0.0
            for (b in 0 until size / 2) {
                val l = a + b
                val r = l + size / 2
                val tr = re[r] * ur - im[r] * ui
                val ti = re[r] * ui + im[r] * ur
                re[r] = re[l] - tr; im[r] = im[l] - ti
                re[l] += tr; im[l] += ti
                val next = ur * wr - ui * wi
                ui = ur * wi + ui * wr; ur = next
            }
        }
        size *= 2
    }
    return DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
}
