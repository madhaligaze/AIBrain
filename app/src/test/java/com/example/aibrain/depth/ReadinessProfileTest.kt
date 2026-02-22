package com.example.aibrain.depth

import com.example.aibrain.ReadinessMetrics
import org.junit.Assert.*
import org.junit.Test

class ReadinessProfileTest {

    @Test
    fun `WithDepth ready has no reasons`() {
        val result = ReadinessProfile.evaluate("WithDepth", perfectMetrics(), ready = true)
        assertEquals(ReadinessProfile.Type.WITH_DEPTH, result.type)
        assertTrue(result.humanReasons.isEmpty())
    }

    @Test
    fun `NoDepth reason mentions depth sensor absence`() {
        val result = ReadinessProfile.evaluate("NoDepth", metricsPartial(), ready = false)
        assertEquals(ReadinessProfile.Type.NO_DEPTH, result.type)
        assertTrue(result.humanReasons.joinToString(" ").contains("нет датчика глубины", true))
    }

    @Test
    fun `UnstableDepth thresholds between WithDepth and NoDepth`() {
        val withD = ReadinessProfile.evaluate("WithDepth", metricsPartial(), false)
        val unstable = ReadinessProfile.evaluate("UnstableDepth", metricsPartial(), false)
        val noD = ReadinessProfile.evaluate("NoDepth", metricsPartial(), false)
        assertTrue(unstable.thresholds.minViewpoints > withD.thresholds.minViewpoints)
        assertTrue(unstable.thresholds.minViewpoints < noD.thresholds.minViewpoints)
    }

    @Test
    fun `null profile falls back to unknown`() {
        val result = ReadinessProfile.evaluate(null, metricsPartial(), false)
        assertEquals(ReadinessProfile.Type.UNKNOWN, result.type)
    }

    private fun perfectMetrics() = ReadinessMetrics(
        observed_ratio = 0.85,
        view_diversity = 10,
        viewpoints = 15
    )

    private fun metricsPartial() = ReadinessMetrics(
        observed_ratio = 0.30,
        view_diversity = 3,
        viewpoints = 4
    )
}
