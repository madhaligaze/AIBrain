package com.example.aibrain.streaming

import com.example.aibrain.ConnectionStatus
import com.example.aibrain.network.NetworkStateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Owns the frame-streaming loop extracted from MainActivity: cadence, single-flight
 * backpressure, adaptive interval (via [StreamTuner]), failure counting and shared
 * reconnect/backoff (via [NetworkStateController]).
 *
 * The AR-coupled bits stay in MainActivity behind [Callbacks]: acquiring + encoding
 * a frame ([Callbacks.sendFrame]), publishing connection state, telemetry and the
 * per-tick UI refresh. This keeps the concurrency-heavy logic in one testable place.
 */
class StreamingController(
    private val scope: CoroutineScope,
    private val tuner: StreamTuner,
    private val netState: NetworkStateController,
    private val reconnectBaseMs: Long,
    private val reconnectMaxMs: Long,
    private val cb: Callbacks,
) {
    interface Callbacks {
        /** Current session id; the loop stops when it changes or goes null. */
        fun sessionId(): String?

        /** Acquire + encode + POST one frame. Returns true on success. */
        suspend fun sendFrame(jpegQuality: Int, pointCap: Int): Boolean

        /** Base server URL (for connection-state reporting). */
        fun serverUrl(): String

        /** Publish connection status (called on the main thread). */
        fun onConnectionStatus(status: ConnectionStatus, baseUrl: String)

        /** N consecutive stream errors — trigger auto-telemetry. */
        fun onErrorStreak()

        /** Per-tick UI refresh (frame counter, camera coords) — main thread. */
        fun onTick()
    }

    private var running = false
    val isStreaming: Boolean get() = running

    private var loopJob: Job? = null
    private var sendJob: Job? = null

    @Volatile private var pendingTick = false
    private var consecutiveFailures = 0
    private var errorStreak = 0
    private var nextAttemptAtMs = 0L

    var lastSendMs: Long = 0L
        private set

    /** Allows the activity to clear the failure count on an unrelated reconnect. */
    fun resetFailures() {
        consecutiveFailures = 0
    }

    fun start(sessionId: String) {
        if (running) return
        running = true
        netState.setStreaming(true)
        loopJob?.cancel()
        sendJob?.cancel()
        loopJob = scope.launch {
            while (running && isActive && cb.sessionId() == sessionId) {
                val nowMs = System.currentTimeMillis()
                if (nowMs < nextAttemptAtMs) {
                    delay(minOf(tuner.intervalMs, nextAttemptAtMs - nowMs))
                    continue
                }

                if (sendJob?.isActive == true) {
                    // Single-flight backpressure: a send is in flight; mark a pending tick.
                    pendingTick = true
                } else {
                    sendJob = launch(Dispatchers.IO) {
                        val t0 = System.nanoTime()
                        val ok = try {
                            withTimeout(2_500L) { cb.sendFrame(tuner.jpegQuality, tuner.pointCap) }
                        } catch (_: Exception) {
                            false
                        }
                        lastSendMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)

                        withContext(Dispatchers.Main) {
                            if (!ok) {
                                consecutiveFailures += 1
                                errorStreak += 1
                            } else {
                                consecutiveFailures = 0
                                errorStreak = 0
                            }

                            val baseUrl = cb.serverUrl().trimEnd('/')
                            scope.launch(Dispatchers.IO) {
                                netState.reportResult(
                                    tag = "stream",
                                    success = ok,
                                    baseMs = reconnectBaseMs,
                                    maxMs = reconnectMaxMs,
                                    errorDetail = if (ok) null else "stream_failed",
                                )
                                withContext(Dispatchers.Main) {
                                    cb.onConnectionStatus(netState.getStatus(), baseUrl)
                                }
                            }

                            if (!ok && errorStreak >= 5) cb.onErrorStreak()

                            tuner.onResult(ok, lastSendMs)
                            if (pendingTick) pendingTick = false
                        }
                    }
                }

                nextAttemptAtMs = if (consecutiveFailures > 0) {
                    netState.snapshot().nextAllowedAtMsByTag["stream"] ?: (nowMs + reconnectBaseMs)
                } else {
                    0L
                }

                cb.onTick()
                delay(tuner.intervalMs)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { netState.setStreaming(false) }
        loopJob?.cancel()
        loopJob = null
        sendJob?.cancel()
        sendJob = null
    }
}
