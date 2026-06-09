package com.example.aibrain.controller

import com.example.aibrain.ApiService
import com.example.aibrain.LockPayload
import com.example.aibrain.diagnostics.CrashReporter
import com.example.aibrain.offline.OfflineQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Performs the "lock revision" network action, serialised against the export
 * poll via [lockMutex]. On any failure the lock is queued offline; either way we
 * then try `export/latest` to recover the committed revision id.
 *
 * Returns the revision id, or null if it could not be determined. Measurement
 * gathering stays in the caller — it reads AR-local state, not server state, so
 * it doesn't belong inside the network critical section.
 */
class SessionLockClient(
    private val api: ApiService,
    private val offlineQueue: OfflineQueue,
    private val crashReporter: CrashReporter,
    private val lockMutex: Mutex,
    private val serverUrl: () -> String,
) {
    suspend fun lock(sid: String, payload: LockPayload): String? = lockMutex.withLock {
        try {
            val resp = api.lockSession(payload)
            if (resp.isSuccessful && resp.body() != null) {
                return@withLock resp.body()!!.rev_id
            }
            offlineQueue.enqueueLock(sid, serverUrl())
            crashReporter.recordError("lockSession", IllegalStateException("HTTP ${resp.code()}"))
        } catch (e: Exception) {
            offlineQueue.enqueueLock(sid, serverUrl())
            crashReporter.recordError("lockSession", e)
        }

        // Best-effort recovery of the committed revision after a failed/queued lock.
        val exp = runCatching { api.exportLatest(sid) }.getOrNull()
        val rev = exp?.body()?.revision_id ?: exp?.body()?.rev_id.orEmpty()
        if (rev.isNotBlank()) rev else null
    }
}
