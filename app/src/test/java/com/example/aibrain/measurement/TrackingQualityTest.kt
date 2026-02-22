package com.example.aibrain.measurement

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingQualityTest {

    @Test
    fun `zero jitter gives full plane score 90`() {
        val score = 90 - (0f * 40f).toInt()
        assertEquals(90, score)
        assertEquals(TrackingQuality.Level.HIGH, levelFor(score))
    }

    @Test
    fun `high jitter reduces score`() {
        val baseScore = 90
        val jitter = 0.5f
        val score = baseScore - (jitter * 40f).toInt()
        assertEquals(70, score)
        assertEquals(TrackingQuality.Level.HIGH, levelFor(score))
    }

    @Test
    fun `extreme jitter drops to MEDIUM`() {
        val baseScore = 90
        val jitter = 0.8f
        val score = (baseScore - (jitter * 40f).toInt()).coerceIn(0, 100)
        assertEquals(58, score)
        assertEquals(TrackingQuality.Level.MEDIUM, levelFor(score))
    }

    @Test
    fun `point trackable gives score 55`() {
        val score = 55 - (0f * 40f).toInt()
        assertEquals(TrackingQuality.Level.MEDIUM, levelFor(score))
    }

    @Test
    fun `score below 45 is LOW`() {
        assertEquals(TrackingQuality.Level.LOW, levelFor(44))
        assertEquals(TrackingQuality.Level.LOW, levelFor(0))
    }

    private fun levelFor(score: Int): TrackingQuality.Level = when {
        score >= 70 -> TrackingQuality.Level.HIGH
        score >= 45 -> TrackingQuality.Level.MEDIUM
        else -> TrackingQuality.Level.LOW
    }
}
