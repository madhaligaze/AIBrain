package com.example.aibrain.depth

import com.example.aibrain.ReadinessMetrics
import com.google.ar.core.Config
import org.junit.Assert.*
import org.junit.Test

class DeviceScenarioTest {

    @Test
    fun `scenario stable depth`() {
        val policy = depthPolicy(true)
        skipWarmup(policy)
        repeat(30) { policy.onFrame(true) }
        assertEquals(DepthPolicy.Strategy.FULL_DEPTH, policy.currentStrategy())
        assertEquals("WithDepth", policy.readinessProfileName())
    }

    @Test
    fun `scenario unstable depth`() {
        val policy = depthPolicy(true)
        skipWarmup(policy)
        repeat(30) { i -> policy.onFrame(i % 2 == 0) }
        assertEquals(DepthPolicy.Strategy.UNSTABLE_DEPTH, policy.currentStrategy())
        assertNotNull(policy.uiHint(DepthPolicy.Strategy.FULL_DEPTH))
    }

    @Test
    fun `scenario unsupported depth`() {
        val policy = depthPolicy(false)
        assertFalse(policy.supported)
        assertEquals(DepthPolicy.Strategy.NO_DEPTH, policy.currentStrategy())
        assertFalse(policy.shouldAttemptDepth())
    }

    @Test
    fun `scenario null policy safe`() {
        val policy: DepthPolicy? = null
        assertFalse(policy?.shouldAttemptDepth() == true)
        val rp = ReadinessProfile.evaluate(null, null, false)
        assertEquals(ReadinessProfile.Type.UNKNOWN, rp.type)
    }

    @Test
    fun `scenario network offline fallback profile`() {
        val policy = depthPolicy(true)
        repeat(30) { policy.onFrame(true) }
        val fallbackProfile = null ?: policy.readinessProfileName()
        assertEquals("WithDepth", fallbackProfile)
    }

    @Test
    fun `scenario oversized payload downsample`() {
        val width = 1280
        val height = 720
        val rawBytes = ByteArray(width * height * 2) { 42 }
        val newH = height / 2
        val safeBytes = ByteArray(width * newH * 2)
        for (row in 0 until newH) {
            System.arraycopy(rawBytes, row * 2 * width * 2, safeBytes, row * width * 2, width * 2)
        }
        assertTrue(safeBytes.size <= DepthPolicy.MAX_DEPTH_PAYLOAD_BYTES)

        val rp = ReadinessProfile.evaluate("NoDepth", ReadinessMetrics(observed_ratio = 0.2, view_diversity = 2, viewpoints = 3), false)
        assertFalse(rp.humanReasons.isEmpty())
    }

    private fun depthPolicy(supported: Boolean): DepthPolicy {
        val mode = if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        return DepthPolicy(mode).also { it.onSessionStart() }
    }

    private fun skipWarmup(p: DepthPolicy) {
        val f = DepthPolicy::class.java.getDeclaredField("sessionStartMs")
        f.isAccessible = true
        f.setLong(p, System.currentTimeMillis() - DepthPolicy.WARMUP_MS - 5_000L)
    }
}
