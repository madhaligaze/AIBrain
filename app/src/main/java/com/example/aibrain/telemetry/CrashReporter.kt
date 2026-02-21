package com.example.aibrain.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.aibrain.ApiService
import com.example.aibrain.BuildConfig
import com.example.aibrain.CrashDeviceInfo
import com.example.aibrain.CrashEnvelope
import com.example.aibrain.CrashErrorItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores last N errors locally and can post an envelope to backend for a session.
 */
class CrashReporter(
    context: Context,
    prefsName: String = "crash_reporter"
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val gson = Gson()

    data class ErrorItem(
        val where: String,
        val message: String,
        val timestamp_ms: Long,
        val stack: String? = null,
        val fatal: Boolean = false
    )

    private fun loadErrors(): MutableList<ErrorItem> {
        val raw = prefs.getString("errors", null) ?: return mutableListOf()
        return try {
            val t = object : TypeToken<MutableList<ErrorItem>>() {}.type
            gson.fromJson(raw, t) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveErrors(items: List<ErrorItem>) {
        prefs.edit().putString("errors", gson.toJson(items)).apply()
    }

    fun recordError(where: String, throwable: Throwable, fatal: Boolean = false) {
        val stack = Log.getStackTraceString(throwable)
        val msg = throwable.message ?: throwable.javaClass.simpleName
        val now = System.currentTimeMillis()
        val list = loadErrors()
        list.add(ErrorItem(where = where, message = msg, timestamp_ms = now, stack = stack, fatal = fatal))
        while (list.size > 30) list.removeAt(0)
        saveErrors(list)
    }

    fun clear() {
        prefs.edit().remove("errors").apply()
    }

    suspend fun flush(
        api: ApiService,
        sessionId: String,
        connectionStatus: String?,
        serverBaseUrl: String?,
        lastExportRev: String?,
        loadedExportRev: String?,
        lastRevisionId: String?,
        clientStats: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        val errors = loadErrors()
        if (errors.isEmpty()) return@withContext true

        val env = CrashEnvelope(
            session_id = sessionId,
            timestamp_ms = System.currentTimeMillis(),
            app_version = BuildConfig.VERSION_NAME,
            build = BuildConfig.VERSION_CODE.toString(),
            platform = "android",
            device = CrashDeviceInfo(
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                sdk = Build.VERSION.SDK_INT
            ),
            connection_status = connectionStatus,
            server_base_url = serverBaseUrl,
            last_export_rev = lastExportRev,
            loaded_export_rev = loadedExportRev,
            last_revision_id = lastRevisionId,
            client_stats = clientStats,
            errors = errors.map {
                CrashErrorItem(
                    where = it.where,
                    message = it.message,
                    timestamp_ms = it.timestamp_ms,
                    stack = it.stack,
                    fatal = it.fatal
                )
            }
        )

        try {
            val r = api.postSessionCrashReport(sessionId, env)
            if (r.isSuccessful) {
                clear()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
