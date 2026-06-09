package com.example.aibrain.controller

import com.example.aibrain.ApiService
import com.example.aibrain.ReadinessResponse
import com.example.aibrain.StructureViewModel
import com.example.aibrain.diagnostics.CrashReporter
import com.example.aibrain.diagnostics.ReportSanitizer
import com.example.aibrain.network.NetworkStateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Polls the server's scan-readiness for the active session and pushes the result
 * into [StructureViewModel] (readiness + connection state). Extracted from
 * MainActivity so the loop's lifecycle, backoff and shared network state are in
 * one place; MainActivity only supplies its session/UI predicates and renders
 * via [onResult].
 *
 * Pacing/backoff is shared with the rest of the app through [NetworkStateController]
 * (tag "readiness").
 */
class ReadinessPoller(
    private val scope: CoroutineScope,
    private val api: ApiService,
    private val netState: NetworkStateController,
    private val viewModel: StructureViewModel,
    private val crashReporter: CrashReporter,
    private val serverUrl: () -> String,
    private val isUiActive: () -> Boolean,
    private val isActiveSession: (String) -> Boolean,
    /** Invoked on the main thread after each poll; body is null on failure. */
    private val onResult: (ReadinessResponse?) -> Unit,
) {
    private var job: Job? = null

    var failures: Int = 0
        private set

    fun isRunning(): Boolean = job?.isActive == true

    fun start(sessionId: String) {
        job?.cancel()
        failures = 0
        var nextAtMs = 0L
        job = scope.launch {
            while (isActive && isActiveSession(sessionId)) {
                if (!isUiActive()) {
                    delay(500L)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now < nextAtMs) {
                    delay(min(750L, nextAtMs - now))
                    continue
                }
                if (!isActiveSession(sessionId)) break

                netState.waitIfNeeded("readiness")
                val endpoint = "/session/$sessionId/readiness"
                val resp = runCatching { api.getReadiness(sessionId) }.getOrNull()

                if (resp == null || !resp.isSuccessful || resp.body() == null) {
                    failures += 1
                    nextAtMs = netState.reportResult(
                        tag = "readiness", success = false,
                        baseMs = 1500L, maxMs = 12_000L, errorDetail = "readiness_http"
                    )
                    crashReporter.recordReproError(endpoint, resp?.code(), "readiness failed")
                    withContext(Dispatchers.Main) {
                        viewModel.setConnectionState(netState.getStatus(), serverUrl())
                        onResult(null)
                    }
                    continue
                }

                failures = 0
                val body = resp.body()!!
                crashReporter.recordReproResponse(
                    endpoint, resp.code(), ReportSanitizer.sanitizeReproBody(endpoint, body)
                )
                nextAtMs = netState.reportResult(tag = "readiness", success = true, baseMs = 1500L, maxMs = 12_000L)
                withContext(Dispatchers.Main) {
                    viewModel.setReadiness(body.ready, body.score, body.readiness_metrics, body.reasons)
                    viewModel.setConnectionState(netState.getStatus(), serverUrl())
                    onResult(body)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
