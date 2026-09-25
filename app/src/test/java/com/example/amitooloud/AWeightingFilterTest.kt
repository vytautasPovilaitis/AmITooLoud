package com.example.amitooloud

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AWeightingFilterTest {

    private val filter = AWeightingFilter(48_000)

    // IEC 61672-1 nominal A-weighting values (dB). Tight up to 4 kHz; above that a 48 kHz
    // bilinear filter drifts as it nears Nyquist, so allow the IEC Class 1 tolerance instead.
    @Test
    fun matchesIecTable() {
        val table = listOf(
            Triple(31.5, -39.4, 0.3),
            Triple(63.0, -26.2, 0.3),
            Triple(100.0, -19.1, 0.3),
            Triple(250.0, -8.6, 0.3),
            Triple(500.0, -3.2, 0.3),
            Triple(1000.0, 0.0, 0.01),
            Triple(2000.0, 1.2, 0.3),
            Triple(4000.0, 1.0, 0.3),
            Triple(8000.0, -1.1, 1.0),
            Triple(10000.0, -2.5, 1.0),
            Triple(16000.0, -6.6, 3.5)
        )
        for ((freq, expected, tolerance) in table) {
            assertEquals("A-weighting at $freq Hz", expected, filter.responseDb(freq), tolerance)
        }
    }

    @Test
    fun attenuatesLowFrequencySignal() {
        assertEquals(0.0, sineGainDb(1000.0), 0.1)
        assertEquals(-19.1, sineGainDb(100.0), 0.3)
    }

    /** Runs one second of a sine through the filter and compares output to input RMS. */
    private fun sineGainDb(freq: Double): Double {
        filter.reset()
        var inSq = 0.0
        var outSq = 0.0
        for (n in 0 until 48_000) {
            val x = sin(2 * PI * freq * n / 48_000)
            val y = filter.process(x)
            if (n >= 4_800) { // skip the filter's settling time
                inSq += x * x
                outSq += y * y
            }
        }
        return 20 * log10(sqrt(outSq / inSq))
    }
}
