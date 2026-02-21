package com.example.aibrain.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout

/**
 * Global limiter for heavy operations to reduce rare ANR / jank on weak devices.
 *
 * Heavy ops: JPEG+Base64, GLB download+IO, voxel rebuild.
 */
object HeavyOps {
    private val sem = Semaphore(permits = 1)

    suspend fun <T> withPermit(timeoutMs: Long = 12_000L, block: suspend () -> T): T {
        try {
            withTimeout(timeoutMs) { sem.acquire() }
        } catch (_: TimeoutCancellationException) {
            // If we cannot acquire in time - proceed without permit (better than deadlock).
            return block()
        }
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}
