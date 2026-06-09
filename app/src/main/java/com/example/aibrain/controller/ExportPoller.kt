package com.example.aibrain.controller

import com.example.aibrain.ApiService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Polls `export/latest` for the active session so scaffold layers refresh without
 * manual actions. Owns the loop, the in-flight guard, fail counters and shared
 * backoff (via [NetworkStateController] tag "export_latest"). The export call is
 * serialised against the user's lock/export via [exportMutex].
 *
 * What stays in MainActivity (injected as callbacks): the `NO_EXPORT` (409) flag,
 * and the "handle a confirmed revision" state machine ([onExportRevision]) — origin
 * check, auto-reload cooldown and GLB layer loading — because that state is shared
 * with the export dialog and scene rendering.
 */
class ExportPoller(
    private val scope: CoroutineScope,
    private val api: ApiService,
    private val netState: NetworkStateController,
    private val viewModel: StructureViewModel,
    private val crashReporter: CrashReporter,
    private val exportMutex: Mutex,
    private val serverUrl: () -> String,
    private val isUiActive: () -> Boolean,
    private val isActiveSession: (String) -> Boolean,
    private val onAutoReport: (String) -> Unit,
    private val onUiRefresh: () -> Unit,
    private val setNotReady409: (Boolean) -> Unit,
    private val onExportRevision: suspend (sessionId: String, rev: String) -> Unit,
) {
    private var job: Job? = null
    private var inFlight = false
    private var failStreak = 0
    private var nextAtMs = 0L

    var failures: Int = 0
        private set

    fun isRunning(): Boolean = job?.isActive == true

    fun start(sessionId: String) {
        job?.cancel()
        inFlight = false
        failures = 0
        failStreak = 0
        nextAtMs = 0L
        job = scope.launch {
            while (isActive && isActiveSession(sessionId)) {
                if (!isUiActive()) {
                    delay(500L)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now < nextAtMs) {
                    delay(min(2000L, nextAtMs - now))
                    continue
                }
                if (!isActiveSession(sessionId)) break
                if (inFlight) continue
                inFlight = true
                val endpoint = "/session/$sessionId/export/latest"
                try {
                    netState.waitIfNeeded("export_latest")
                    val resp = runCatching { exportMutex.withLock { api.exportLatest(sessionId) } }.getOrNull()
                    if (resp == null) {
                        failures += 1; failStreak += 1
                        nextAtMs = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = "export_null")
                        crashReporter.recordReproError(endpoint = endpoint, errorSnippet = "export/latest: null resp")
                        continue
                    }
                    // 409 NO_EXPORT is expected early - ignore quietly.
                    if (resp.code() == 409) {
                        setNotReady409(true)
                        failures = 0; failStreak = 0
                        crashReporter.recordReproResponse(endpoint, resp.code(), "409 NO_EXPORT")
                        nextAtMs = netState.reportResult(tag = "export_latest", success = true, baseMs = 6500L, maxMs = 30_000L)
                        withContext(Dispatchers.Main) { onUiRefresh() }
                        continue
                    }
                    if (!resp.isSuccessful || resp.body() == null) {
                        failures += 1; failStreak += 1
                        crashReporter.recordReproError(endpoint = endpoint, httpCode = resp.code(), errorSnippet = ("export/latest failed: " + resp.code()).take(2048))
                        nextAtMs = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = "export_http_" + resp.code())
                        if (failStreak >= 3) {
                            onAutoReport("export_latest_failures")
                            failStreak = 0
                        }
                        continue
                    }

                    setNotReady409(false)
                    failures = 0; failStreak = 0
                    val bundle = resp.body()!!
                    val rev = bundle.revision_id ?: bundle.rev_id.orEmpty()
                    if (rev.isBlank()) continue

                    crashReporter.recordReproResponse(endpoint, resp.code(), ReportSanitizer.sanitizeReproBody(endpoint, bundle))
                    nextAtMs = netState.reportResult(tag = "export_latest", success = true, baseMs = 6500L, maxMs = 30_000L)

                    onExportRevision(sessionId, rev)
                } catch (e: Exception) {
                    failures += 1; failStreak += 1
                    crashReporter.recordReproError(endpoint = endpoint, errorSnippet = (e.message ?: "exception").take(2048))
                    nextAtMs = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = e.message)
                } finally {
                    inFlight = false
                    withContext(Dispatchers.Main) {
                        viewModel.setConnectionState(netState.getStatus(), serverUrl())
                        runCatching { onUiRefresh() }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        inFlight = false
        failures = 0
        failStreak = 0
        nextAtMs = 0L
    }
}
