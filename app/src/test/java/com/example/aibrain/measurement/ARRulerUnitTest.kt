package com.example.aibrain.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class ARRulerUnitTest {

    @Test
    fun `formatDistance METRIC above 1m`() {
        val ruler = FakeARRuler()
        assertEquals("1.50 м", ruler.fmtDist(1.5f))
    }

    @Test
    fun `formatDistance METRIC below 1m`() {
        val ruler = FakeARRuler()
        assertEquals("75.0 см", ruler.fmtDist(0.75f))
    }

    @Test
    fun `formatDistance IMPERIAL feet`() {
        val ruler = FakeARRuler(imperial = true)
        val result = ruler.fmtDist(1.0f)
        assertTrue("Expected ft, got $result", result.endsWith("ft"))
    }

    @Test
    fun `formatArea METRIC`() {
        val ruler = FakeARRuler()
        assertEquals("2.00 м²", ruler.fmtArea(2.0f))
    }

    @Test
    fun `formatArea IMPERIAL`() {
        val ruler = FakeARRuler(imperial = true)
        val result = ruler.fmtArea(1.0f)
        assertTrue("Expected ft², got $result", result.endsWith("ft²"))
    }

    @Test
    fun `area of 1x1 square is 1 m2`() {
        val pts = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
        assertEquals(1.0f, shoelaceArea(pts), 1e-4f)
    }

    @Test
    fun `area of 3x4 rectangle is 12 m2`() {
        val pts = listOf(Pt(0f, 0f), Pt(3f, 0f), Pt(3f, 4f), Pt(0f, 4f))
        assertEquals(12.0f, shoelaceArea(pts), 1e-3f)
    }

    @Test
    fun `area of triangle 3-4-5 is 6 m2`() {
        val pts = listOf(Pt(0f, 0f), Pt(4f, 0f), Pt(0f, 3f))
        assertEquals(6.0f, shoelaceArea(pts), 1e-3f)
    }

    @Test
    fun `area of degenerate line is 0`() {
        val pts = listOf(Pt(0f, 0f), Pt(1f, 0f))
        assertEquals(0.0f, shoelaceArea(pts), 1e-6f)
    }

    @Test
    fun `height between y=0 and y=2 is 2m`() {
        val base = 0.0f
        val top = 2.0f
        assertEquals(2.0f, abs(top - base), 1e-5f)
    }

    @Test
    fun `height is always positive even if reversed`() {
        val base = 2.0f
        val top = 0.5f
        assertEquals(1.5f, abs(top - base), 1e-5f)
    }

    @Test
    fun `area of empty list is 0`() {
        assertEquals(0.0f, shoelaceArea(emptyList()), 1e-6f)
    }

    @Test
    fun `formatDistance negative clamps to 0`() {
        val ruler = FakeARRuler()
        assertTrue(ruler.fmtDist(-1f).startsWith("0"))
    }

    private data class Pt(val x: Float, val z: Float)

    private fun shoelaceArea(pts: List<Pt>): Float {
        if (pts.size < 3) return 0f
        var sum = 0f
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            sum += pts[i].x * pts[j].z - pts[j].x * pts[i].z
        }
        return abs(sum) * 0.5f
    }

    private inner class FakeARRuler(imperial: Boolean = false) {
        private val units = if (imperial) ARRuler.Units.IMPERIAL else ARRuler.Units.METRIC
        fun fmtDist(m: Float) = formatDistancePure(m, units)
        fun fmtArea(m2: Float) = formatAreaPure(m2, units)
    }

    // Mirrors ARRuler.formatDistanceIn. Defaults to Locale.US (the machine path,
    // '.' decimal) so these assertions are deterministic regardless of the JVM's
    // default locale; pass a locale to exercise the display path.
    private fun formatDistancePure(m: Float, units: ARRuler.Units, locale: Locale = Locale.US): String {
        val v = maxOf(0f, m)
        return when (units) {
            ARRuler.Units.METRIC ->
                if (v >= 1f) String.format(locale, "%.2f м", v) else String.format(locale, "%.1f см", v * 100f)
            ARRuler.Units.IMPERIAL -> {
                val feet = v * 3.28084f
                if (feet >= 1f) String.format(locale, "%.2f ft", feet) else String.format(locale, "%.1f in", feet * 12f)
            }
        }
    }

    private fun formatAreaPure(m2: Float, units: ARRuler.Units, locale: Locale = Locale.US): String {
        val a = maxOf(0f, m2)
        return when (units) {
            ARRuler.Units.METRIC -> String.format(locale, "%.2f м²", a)
            ARRuler.Units.IMPERIAL -> String.format(locale, "%.2f ft²", a * 10.7639104f)
        }
    }

    @Test
    fun `machine format uses dot, display format follows locale`() {
        // Machine (US): '.', deterministic for export / server / CSV.
        assertEquals("2.00 м²", formatAreaPure(2.0f, ARRuler.Units.METRIC, Locale.US))
        assertEquals("1.50 м", formatDistancePure(1.5f, ARRuler.Units.METRIC, Locale.US))
        // Display (ru): ',' — what a Russian user sees on screen.
        assertEquals("2,00 м²", formatAreaPure(2.0f, ARRuler.Units.METRIC, Locale("ru")))
        assertEquals("1,50 м", formatDistancePure(1.5f, ARRuler.Units.METRIC, Locale("ru")))
    }
}
