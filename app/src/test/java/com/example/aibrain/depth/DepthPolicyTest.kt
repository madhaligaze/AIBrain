package com.example.aibrain.depth

import com.google.ar.core.Config
import org.junit.Assert.*
import org.junit.Test

class DepthPolicyTest {

    @Test
    fun `supported device starts as FULL_DEPTH`() {
        val policy = policy(supported = true)
        assertEquals(DepthPolicy.Strategy.FULL_DEPTH, policy.currentStrategy())
    }

    @Test
    fun `stable depth keeps FULL_DEPTH`() {
        val policy = policy(supported = true)
        repeat(30) { policy.onFrame(true) }
        assertEquals(DepthPolicy.Strategy.FULL_DEPTH, policy.currentStrategy())
    }

    @Test
    fun `rate 50 percent moves to UNSTABLE_DEPTH`() {
        val policy = policy(supported = true)
        skipWarmup(policy)
        repeat(30) { i -> policy.onFrame(i % 2 == 0) }
        assertEquals(DepthPolicy.Strategy.UNSTABLE_DEPTH, policy.currentStrategy())
        assertEquals("UnstableDepth", policy.readinessProfileName())
    }

    @Test
    fun `unsupported device is NO_DEPTH`() {
        val policy = policy(supported = false)
        assertEquals(DepthPolicy.Strategy.NO_DEPTH, policy.currentStrategy())
        assertFalse(policy.shouldAttemptDepth())
    }

    @Test
    fun `streak after warmup forces NO_DEPTH`() {
        val policy = policy(supported = true)
        skipWarmup(policy)
        repeat(DepthPolicy.DISABLE_AFTER_STREAK + 2) { policy.onFrame(false) }
        assertEquals(DepthPolicy.Strategy.NO_DEPTH, policy.currentStrategy())
    }

    @Test
    fun `toMap has keys`() {
        val policy = policy(supported = true)
        val map = policy.toMap()
        assertTrue(map.containsKey("depth_mode"))
        assertTrue(map.containsKey("depth_strategy"))
        assertTrue(map.containsKey("readiness_profile"))
    }

    private fun policy(supported: Boolean): DepthPolicy {
        val mode = if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        return DepthPolicy(mode).also { it.onSessionStart() }
    }

    private fun skipWarmup(policy: DepthPolicy) {
        val field = DepthPolicy::class.java.getDeclaredField("sessionStartMs")
        field.isAccessible = true
        field.setLong(policy, System.currentTimeMillis() - DepthPolicy.WARMUP_MS - 5_000L)
    }
}
