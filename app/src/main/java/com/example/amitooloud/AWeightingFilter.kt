package com.example.amitooloud

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.tan

/**
 * IEC 61672 A-weighting as three biquads, so readings follow perceived loudness
 * (and dBA sound level meters) instead of counting low-frequency hum at full weight.
 *
 * Analog prototype: s⁴ / ((s+ω1)² (s+ω2)(s+ω3)(s+ω4)²), turned digital with the bilinear
 * transform. Pole frequencies are pre-warped so they land exactly where they should,
 * and the whole cascade is normalised to 0 dB at 1 kHz.
 */
class AWeightingFilter(private val sampleRate: Int) {

    private class Biquad(
        var b0: Double, var b1: Double, var b2: Double,
        val a1: Double, val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            // Direct form II transposed
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        /** |H(e^jω)| at the given frequency. */
        fun magnitude(freq: Double, sampleRate: Int): Double {
            val w = 2 * PI * freq / sampleRate
            val (c1, s1) = cos(w) to -sin(w)
            val (c2, s2) = cos(2 * w) to -sin(2 * w)
            val num = hypot(b0 + b1 * c1 + b2 * c2, b1 * s1 + b2 * s2)
            val den = hypot(1 + a1 * c1 + a2 * c2, a1 * s1 + a2 * s2)
            return num / den
        }
    }

    private val sections: List<Biquad>

    init {
        val c = 2.0 * sampleRate
        fun warp(f: Double) = c * tan(PI * f / sampleRate)
        val w1 = warp(20.598997)
        val w2 = warp(107.65265)
        val w3 = warp(737.86223)
        val w4 = warp(12194.217)

        // Bilinear transform of N(s) / ((s+p)(s+q)); numerator is s² (high-pass) or 1 (low-pass)
        fun section(p: Double, q: Double, highPass: Boolean): Biquad {
            val a0 = (c + p) * (c + q)
            val a1 = ((c + p) * (q - c) + (p - c) * (c + q)) / a0
            val a2 = (p - c) * (q - c) / a0
            return if (highPass) {
                val g = c * c / a0
                Biquad(g, -2 * g, g, a1, a2)
            } else {
                val g = 1 / a0
                Biquad(g, 2 * g, g, a1, a2)
            }
        }

        sections = listOf(
            section(w1, w1, highPass = true),
            section(w2, w3, highPass = true),
            section(w4, w4, highPass = false)
        )

        val gainAt1k = sections.fold(1.0) { acc, s -> acc * s.magnitude(1000.0, sampleRate) }
        sections[0].apply {
            b0 /= gainAt1k
            b1 /= gainAt1k
            b2 /= gainAt1k
        }
    }

    fun process(x: Double): Double {
        var y = x
        for (s in sections) y = s.process(y)
        return y
    }

    fun reset() = sections.forEach { it.reset() }

    /** Response in dB at [freq] Hz, for testing against the IEC table. */
    fun responseDb(freq: Double): Double =
        20 * log10(sections.fold(1.0) { acc, s -> acc * s.magnitude(freq, sampleRate) })
}
