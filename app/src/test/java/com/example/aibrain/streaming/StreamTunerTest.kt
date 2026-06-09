package com.example.aibrain.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTunerTest {

    @Test
    fun `failure backs off quality, cap and interval`() {
        val t = StreamTuner(jpegQuality = 72, pointCap = 300, intervalMs = 1_000L)
        t.onResult(ok = false, sendMs = 800)
        assertEquals(66, t.jpegQuality)   // 72 - 6
        assertEquals(260, t.pointCap)     // 300 - 40
        assertEquals(1_150L, t.intervalMs) // 1000 + 150
    }

    @Test
    fun `repeated failures clamp at the floor, never below`() {
        val t = StreamTuner(jpegQuality = 50, pointCap = 130, intervalMs = 1_400L)
        repeat(20) { t.onResult(ok = false, sendMs = 1_000) }
        assertEquals(StreamTuner.MIN_JPEG_QUALITY, t.jpegQuality)
        assertEquals(StreamTuner.MIN_POINT_CAP, t.pointCap)
        assertEquals(StreamTuner.MAX_INTERVAL_MS, t.intervalMs)
    }

    @Test
    fun `success recovers quality and cap, capped at the ceiling`() {
        val t = StreamTuner(jpegQuality = 79, pointCap = 445, intervalMs = 800L)
        repeat(10) { t.onResult(ok = true, sendMs = 200) }
        assertEquals(StreamTuner.MAX_JPEG_QUALITY, t.jpegQuality)
        assertEquals(StreamTuner.MAX_POINT_CAP, t.pointCap)
        assertTrue(t.intervalMs in StreamTuner.MIN_INTERVAL_MS..StreamTuner.MAX_INTERVAL_MS)
    }

    @Test
    fun `interval tracks the send-time EWMA on success`() {
        val t = StreamTuner(intervalMs = 1_000L)
        repeat(30) { t.onResult(ok = true, sendMs = 250) }
        // EWMA converges to ~250ms; target interval = 1.3*EWMA ≈ 325ms, clamped ≥ 300.
        assertTrue("interval=${t.intervalMs}", t.intervalMs in StreamTuner.MIN_INTERVAL_MS..600L)
        assertTrue(t.sendTimeEwmaMs in 240.0..260.0)
    }
}
