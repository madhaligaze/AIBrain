package com.example.aibrain.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.aibrain.ApiService
import com.example.aibrain.ClientErrorItem
import com.example.aibrain.ClientReportEnvelope
import com.example.aibrain.ClientReproItem
import com.example.aibrain.LogDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class CrashReporter(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val errors = ArrayDeque<ClientErrorItem>(MAX_ERRORS)
    private val repro = ArrayDeque<ClientReproItem>(MAX_REPRO)

    @Volatile
    private var lastExportRevId: String? = null

    fun setLastExportRev(revId: String?) {
        lastExportRevId = revId
    }

    fun recordError(tag: String, message: String, stack: String? = null) {
        val safeMsg = ReportSanitizer.sanitizeText(message, maxLen = 512)
        val safeStack = stack?.let { ReportSanitizer.sanitizeText(it, maxLen = 8000) }
        val item = ClientErrorItem(
            timestamp_ms = System.currentTimeMillis(),
            tag = tag.take(64),
            message = safeMsg,
            stack = safeStack
        )
        while (errors.size >= MAX_ERRORS) errors.removeFirst()
        errors.addLast(item)
        persistTail()
    }

    fun recordException(tag: String, t: Throwable) {
        recordError(tag, t.message ?: t.javaClass.simpleName, Log.getStackTraceString(t))
    }

    fun recordReproResponse(endpoint: String, httpCode: Int, bodySnippet: String) {
        val safeBody = ReportSanitizer.sanitizeText(bodySnippet, maxLen = MAX_SNIPPET)
        val item = ClientReproItem(
            timestamp_ms = System.currentTimeMillis(),
            endpoint = endpoint.take(64),
            http_code = httpCode,
            body_snippet = safeBody,
            error_snippet = null
        )
        while (repro.size >= MAX_REPRO) repro.removeFirst()
        repro.addLast(item)
        persistReproTail()
    }

    fun recordReproError(endpoint: String, httpCode: Int? = null, errorSnippet: String? = null) {
        val safeErr = ReportSanitizer.sanitizeText(errorSnippet ?: "", maxLen = MAX_SNIPPET)
        val item = ClientReproItem(
            timestamp_ms = System.currentTimeMillis(),
            endpoint = endpoint.take(64),
            http_code = httpCode,
            body_snippet = null,
            error_snippet = safeErr
        )
        while (repro.size >= MAX_REPRO) repro.removeFirst()
        repro.addLast(item)
        persistReproTail()
    }

    fun readCrashMarkerSnippet(): String? {
        return try {
            val f = File(context.filesDir, CRASH_MARKER_FILE)
            if (!f.exists()) return null
            f.readText().take(MAX_SNIPPET)
        } catch (_: Exception) {
            null
        }
    }

    fun clearCrashMarker() {
        runCatching { File(context.filesDir, CRASH_MARKER_FILE).delete() }
    }

    private fun persistReproTail() {
        try {
            val gson = com.google.gson.Gson()
            prefs.edit().putString(KEY_REPRO_JSON, gson.toJson(repro.toList())).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadReproIfNeeded() {
        if (repro.isNotEmpty()) return
        val raw = prefs.getString(KEY_REPRO_JSON, null) ?: return
        try {
            val gson = com.google.gson.Gson()
            val t = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ClientReproItem::class.java).type
            val list: List<ClientReproItem> = gson.fromJson(raw, t) ?: emptyList()
            for (e in list.takeLast(MAX_REPRO)) repro.addLast(e)
        } catch (_: Exception) {
        }
    }

    private fun persistTail() {
        try {
            val gson = com.google.gson.Gson()
            prefs.edit().putString(KEY_ERRORS_JSON, gson.toJson(errors.toList())).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadTailIfNeeded() {
        if (errors.isNotEmpty()) return
        val raw = prefs.getString(KEY_ERRORS_JSON, null) ?: return
        try {
            val gson = com.google.gson.Gson()
            val t = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ClientErrorItem::class.java).type
            val list: List<ClientErrorItem> = gson.fromJson(raw, t) ?: emptyList()
            for (e in list.takeLast(MAX_ERRORS)) errors.addLast(e)
        } catch (_: Exception) {
        }
    }

    fun getLastSentMs(): Long = prefs.getLong(KEY_LAST_SENT_MS, 0L)

    suspend fun sendNow(
        api: ApiService,
        sessionId: String?,
        clientStats: Map<String, Any>,
        queuedActions: Map<String, Any>,
        trigger: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        loadTailIfNeeded()
        loadReproIfNeeded()
        val crashMarker = readCrashMarkerSnippet()

        val payload = ClientReportEnvelope(
            session_id = sessionId,
            timestamp_ms = System.currentTimeMillis(),
            client_stats = clientStats,
            last_export_rev = lastExportRevId,
            queued_actions = queuedActions,
            last_errors = errors.toList(),
            device = LogDeviceInfo(
                model = Build.MODEL ?: "unknown",
                manufacturer = Build.MANUFACTURER ?: "unknown",
                sdk = Build.VERSION.SDK_INT
            ),
            trigger = trigger,
            crash_marker = crashMarker,
            repro_pack = repro.toList()
        )

        return@withContext try {
            val resp = api.postClientReport(payload)
            if (resp.isSuccessful) {
                prefs.edit()
                    .putLong(KEY_LAST_SENT_MS, System.currentTimeMillis())
                    .putLong(KEY_AUTO_BACKOFF_MS, AUTO_BACKOFF_BASE_MS)
                    .putLong(KEY_AUTO_NEXT_ALLOWED_MS, 0L)
                    .apply()
                clearCrashMarker()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun maybeAutoSend(
        api: ApiService,
        sessionId: String?,
        clientStats: Map<String, Any>,
        queuedActions: Map<String, Any>,
        trigger: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val nextAllowed = prefs.getLong(KEY_AUTO_NEXT_ALLOWED_MS, 0L)
        if (now < nextAllowed) return false

        loadTailIfNeeded()
        loadReproIfNeeded()

        val envelopeHash = sha256(
            (trigger + "|" + (lastExportRevId ?: "") + "|" + (errors.size) + "|" + (repro.size)).encodeToByteArray()
        )

        val lastHash = prefs.getString(KEY_AUTO_LAST_HASH, null)
        val lastSent = prefs.getLong(KEY_LAST_SENT_MS, 0L)
        if (lastHash != null && lastHash == envelopeHash && (now - lastSent) < DEDUP_WINDOW_MS) {
            return false
        }

        val ok = sendNow(api, sessionId, clientStats, queuedActions, trigger = trigger)
        if (ok) {
            prefs.edit().putString(KEY_AUTO_LAST_HASH, envelopeHash).apply()
            return true
        }

        val cur = prefs.getLong(KEY_AUTO_BACKOFF_MS, AUTO_BACKOFF_BASE_MS)
        val next = min(AUTO_BACKOFF_MAX_MS, max(AUTO_BACKOFF_BASE_MS, cur * 2))
        val jitter = (0.75 + Random.nextDouble() * 0.5)
        val delayMs = (next.toDouble() * jitter).toLong()
        prefs.edit()
            .putLong(KEY_AUTO_BACKOFF_MS, next)
            .putLong(KEY_AUTO_NEXT_ALLOWED_MS, now + delayMs)
            .apply()
        return false
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    companion object {
        private const val PREFS = "crash_reporter"
        private const val KEY_ERRORS_JSON = "errors_json"
        private const val KEY_REPRO_JSON = "repro_json"
        private const val KEY_LAST_SENT_MS = "last_sent_ms"

        private const val KEY_AUTO_BACKOFF_MS = "auto_backoff_ms"
        private const val KEY_AUTO_NEXT_ALLOWED_MS = "auto_next_allowed_ms"
        private const val KEY_AUTO_LAST_HASH = "auto_last_hash"

        private const val MAX_ERRORS = 12
        private const val MAX_REPRO = 12
        private const val MAX_SNIPPET = 2048

        private const val AUTO_BACKOFF_BASE_MS = 30_000L
        private const val AUTO_BACKOFF_MAX_MS = 10 * 60_000L
        private const val DEDUP_WINDOW_MS = 15 * 60_000L

        private const val CRASH_MARKER_FILE = "crash_marker.txt"
    }
}
