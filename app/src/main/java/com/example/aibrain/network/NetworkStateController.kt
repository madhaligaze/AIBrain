package com.example.aibrain.network

import com.example.aibrain.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Centralized network state machine + shared backoff/jitter policy.
 *
 * Goals:
 * - Single source of truth: OFFLINE / RECONNECTING / ONLINE.
 * - Shared exponential backoff (global failure streak) used by all pollers + flush loops.
 * - Jitter to avoid thundering herd / "lockstep" retries.
 */
class NetworkStateController {
    data class Snapshot(
        val status: ConnectionStatus,
        val failureStreak: Int,
        val streaming: Boolean,
        val lastError: String?,
        val nextAllowedAtMsByTag: Map<String, Long>
    )

    private val mu = Mutex()

    @Volatile
    private var streaming: Boolean = false

    @Volatile
    private var status: ConnectionStatus = ConnectionStatus.UNKNOWN

    @Volatile
    private var globalFailureStreak: Int = 0

    @Volatile
    private var lastError: String? = null

    private val nextAtMs = HashMap<String, Long>()

    fun setStreaming(value: Boolean) {
        streaming = value
        if (!value && status == ConnectionStatus.RECONNECTING) {
            status = ConnectionStatus.OFFLINE
        }
    }

    fun getStatus(): ConnectionStatus = status

    suspend fun waitIfNeeded(tag: String) {
        val now = System.currentTimeMillis()
        val waitMs = mu.withLock {
            val at = nextAtMs[tag] ?: 0L
            max(0L, at - now)
        }
        if (waitMs > 0L) delay(waitMs)
    }

    /**
     * Update shared backoff state and schedule next attempt for this tag.
     *
     * baseMs/maxMs define the nominal schedule for this loop (when ONLINE).
     */
    suspend fun reportResult(
        tag: String,
        success: Boolean,
        baseMs: Long,
        maxMs: Long,
        errorDetail: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        return mu.withLock {
            if (success) {
                globalFailureStreak = 0
                lastError = null
                status = ConnectionStatus.ONLINE
                val next = now + jitter(baseMs, 0.85, 1.25)
                nextAtMs[tag] = next
                next
            } else {
                globalFailureStreak = min(12, globalFailureStreak + 1)
                lastError = errorDetail?.take(256)
                status = if (streaming) ConnectionStatus.RECONNECTING else ConnectionStatus.OFFLINE
                val penalty = when (status) {
                    ConnectionStatus.OFFLINE -> 4.0
                    ConnectionStatus.RECONNECTING -> 2.0
                    else -> 1.0
                }
                val exp = min(6, globalFailureStreak)
                val raw = (baseMs.toDouble() * (1 shl exp).toDouble() * penalty).toLong()
                val backoff = min(maxMs, max(baseMs, raw))
                val next = now + jitter(backoff, 0.85, 1.35)
                nextAtMs[tag] = next
                next
            }
        }
    }

    suspend fun snapshot(): Snapshot = mu.withLock {
        Snapshot(
            status = status,
            failureStreak = globalFailureStreak,
            streaming = streaming,
            lastError = lastError,
            nextAllowedAtMsByTag = HashMap(nextAtMs)
        )
    }

    private fun jitter(ms: Long, lo: Double, hi: Double): Long {
        val j = lo + (Random.nextDouble() * (hi - lo))
        return (ms.toDouble() * j).toLong().coerceAtLeast(0L)
    }
}
