package com.example.aibrain.streaming

/**
 * Adaptive stream throttling. Holds the current JPEG quality, point-cloud cap and
 * send interval, and adjusts them from the measured round-trip ([onResult]):
 * back off fast on failure, recover slowly on success, and keep the interval near
 * an EWMA of the observed send time so CPU/network stay stable.
 *
 * Pure logic (no Android dependencies) so it is unit-testable in isolation — this
 * was previously inlined as MainActivity.tuneStreaming over four mutable fields.
 */
class StreamTuner(
    var jpegQuality: Int = 72,
    var pointCap: Int = 300,
    var intervalMs: Long = 1_000L,
) {
    var sendTimeEwmaMs: Double = 0.0
        private set

    fun onResult(ok: Boolean, sendMs: Long) {
        val x = sendMs.toDouble()
        sendTimeEwmaMs = if (sendTimeEwmaMs <= 0.0) x else (0.8 * sendTimeEwmaMs + 0.2 * x)

        if (!ok) {
            // Back off: reduce quality and point cap quickly, lengthen interval.
            jpegQuality = (jpegQuality - 6).coerceAtLeast(MIN_JPEG_QUALITY)
            pointCap = (pointCap - 40).coerceAtLeast(MIN_POINT_CAP)
            intervalMs = (intervalMs + 150L).coerceAtMost(MAX_INTERVAL_MS)
            return
        }

        // Success: slowly restore quality/cap, adapt interval toward 1.3× EWMA.
        jpegQuality = (jpegQuality + 1).coerceAtMost(MAX_JPEG_QUALITY)
        pointCap = (pointCap + 10).coerceAtMost(MAX_POINT_CAP)
        val target = (sendTimeEwmaMs * 1.3).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        intervalMs = ((0.85 * intervalMs.toDouble()) + (0.15 * target.toDouble())).toLong()
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    companion object {
        const val MIN_INTERVAL_MS = 300L
        const val MAX_INTERVAL_MS = 1_500L
        const val MIN_JPEG_QUALITY = 45
        const val MAX_JPEG_QUALITY = 80
        const val MIN_POINT_CAP = 120
        const val MAX_POINT_CAP = 450
    }
}
