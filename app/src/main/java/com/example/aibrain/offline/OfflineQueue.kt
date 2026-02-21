package com.example.aibrain.offline

import android.content.Context
import android.util.Log
import com.example.aibrain.AnchorPayload
import com.example.aibrain.AnchorPointRequest
import com.example.aibrain.ApiService
import com.example.aibrain.LockPayload
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfflineQueue(private val context: Context) {

    data class QueueItem(
        val type: String,
        val sessionId: String,
        val baseUrl: String,
        val createdAtMs: Long,
        val payloadJson: String
    )

    data class QueueStatus(
        val anchorsQueued: Int,
        val lockQueued: Int,
        val mismatchedBaseUrlItems: Int
    )

    data class FlushResult(
        val anchorsOk: Boolean,
        val lockOk: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun loadItems(): MutableList<QueueItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return mutableListOf()
        return try {
            val t = object : TypeToken<List<QueueItem>>() {}.type
            (gson.fromJson<List<QueueItem>>(raw, t) ?: emptyList()).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse queue, resetting: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveItems(items: List<QueueItem>) {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    fun getStatus(sessionId: String, currentBaseUrl: String): QueueStatus {
        val items = loadItems()
        val anchors = items.count { it.sessionId == sessionId && it.type == TYPE_ANCHORS && it.baseUrl == currentBaseUrl }
        val lock = items.count { it.sessionId == sessionId && it.type == TYPE_LOCK && it.baseUrl == currentBaseUrl }
        val mismatch = items.count { it.sessionId == sessionId && it.baseUrl != currentBaseUrl }
        return QueueStatus(anchorsQueued = anchors, lockQueued = lock, mismatchedBaseUrlItems = mismatch)
    }

    fun enqueueAnchors(sessionId: String, baseUrl: String, anchors: List<AnchorPointRequest>) {
        val items = loadItems()
        items.removeAll { it.sessionId == sessionId && it.baseUrl == baseUrl && it.type == TYPE_ANCHORS }
        val payload = AnchorPayload(session_id = sessionId, anchors = anchors)
        items.add(
            QueueItem(
                type = TYPE_ANCHORS,
                sessionId = sessionId,
                baseUrl = baseUrl,
                createdAtMs = System.currentTimeMillis(),
                payloadJson = gson.toJson(payload)
            )
        )
        saveItems(items)
    }

    fun enqueueLock(sessionId: String, baseUrl: String) {
        val items = loadItems()
        items.removeAll { it.sessionId == sessionId && it.baseUrl == baseUrl && it.type == TYPE_LOCK }
        val payload = LockPayload(session_id = sessionId)
        items.add(
            QueueItem(
                type = TYPE_LOCK,
                sessionId = sessionId,
                baseUrl = baseUrl,
                createdAtMs = System.currentTimeMillis(),
                payloadJson = gson.toJson(payload)
            )
        )
        saveItems(items)
    }

    suspend fun flushForSession(api: ApiService, sessionId: String, currentBaseUrl: String): QueueStatus = withContext(Dispatchers.IO) {
        val items = loadItems()
        if (items.isEmpty()) return@withContext getStatus(sessionId, currentBaseUrl)

        val remaining = mutableListOf<QueueItem>()
        for (it in items) {
            if (it.sessionId != sessionId || it.baseUrl != currentBaseUrl) {
                remaining.add(it)
                continue
            }

            try {
                when (it.type) {
                    TYPE_ANCHORS -> {
                        val payload = gson.fromJson(it.payloadJson, AnchorPayload::class.java)
                        val resp = api.postAnchors(payload)
                        if (!resp.isSuccessful) remaining.add(it)
                    }

                    TYPE_LOCK -> {
                        val payload = gson.fromJson(it.payloadJson, LockPayload::class.java)
                        val resp = api.lockSession(payload)
                        if (!resp.isSuccessful) remaining.add(it)
                    }

                    else -> remaining.add(it)
                }
            } catch (_: Exception) {
                remaining.add(it)
            }
        }

        saveItems(remaining)
        return@withContext getStatus(sessionId, currentBaseUrl)
    }

    // Backward-compatible wrappers used by existing MainActivity.
    fun enqueueAnchors(sessionId: String, payload: AnchorPayload) {
        enqueueAnchors(sessionId, "", payload.anchors)
    }

    fun enqueueLock(sessionId: String, payload: LockPayload) {
        val items = loadItems()
        items.removeAll { it.sessionId == sessionId && it.baseUrl == "" && it.type == TYPE_LOCK }
        items.add(
            QueueItem(
                type = TYPE_LOCK,
                sessionId = sessionId,
                baseUrl = "",
                createdAtMs = System.currentTimeMillis(),
                payloadJson = gson.toJson(payload)
            )
        )
        saveItems(items)
    }

    suspend fun flushForSession(api: ApiService, sessionId: String): FlushResult = withContext(Dispatchers.IO) {
        val st = flushForSession(api, sessionId, "")
        FlushResult(anchorsOk = st.anchorsQueued == 0, lockOk = st.lockQueued == 0)
    }

    companion object {
        private const val TAG = "OfflineQueue"
        private const val PREFS = "offline_queue"
        private const val KEY_ITEMS = "items_json"
        private const val TYPE_ANCHORS = "anchors"
        private const val TYPE_LOCK = "lock"
    }
}
