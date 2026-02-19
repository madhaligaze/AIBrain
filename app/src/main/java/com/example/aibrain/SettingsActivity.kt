package com.example.aibrain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class SettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etUrl: EditText = findViewById(R.id.et_server_url)
        val tvHealth: TextView = findViewById(R.id.tv_health)
        val tvHistory: TextView = findViewById(R.id.tv_history)
        val btnSave: Button = findViewById(R.id.btn_save_url)
        val btnCheck: Button = findViewById(R.id.btn_check_health)
        val btnClear: Button = findViewById(R.id.btn_clear_history)
        val btnCopy: Button = findViewById(R.id.btn_copy_latest)

        val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        etUrl.setText(prefs.getString(AppPrefs.KEY_SERVER_BASE_URL, AppPrefs.defaultBaseUrl()).orEmpty())

        fun renderHistory() {
            val raw = prefs.getString(AppPrefs.KEY_SESSION_HISTORY, "") ?: ""
            val arr = runCatching { JSONArray(raw) }.getOrNull()
            if (arr == null || arr.length() == 0) {
                tvHistory.text = "No saved sessions yet."
                return
            }
            val lines = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optString("session_id", "")
                val ts = o.optLong("timestamp_ms", 0L)
                if (sid.isNotBlank()) lines.add("${i + 1}. $sid  (ts=$ts)")
            }
            tvHistory.text = if (lines.isEmpty()) "No saved sessions yet." else lines.joinToString("\n")
        }

        fun normalizeBaseUrl(raw: String): String? {
            val s = raw.trim()
            if (s.isBlank()) return null
            val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }

        btnSave.setOnClickListener {
            val url = normalizeBaseUrl(etUrl.text.toString())
            if (url == null) {
                AlertDialog.Builder(this).setMessage("Invalid URL").setPositiveButton("OK", null).show()
                return@setOnClickListener
            }
            prefs.edit().putString(AppPrefs.KEY_SERVER_BASE_URL, url).apply()
            AlertDialog.Builder(this).setMessage("Saved").setPositiveButton("OK", null).show()
        }

        btnCheck.setOnClickListener {
            val url = normalizeBaseUrl(etUrl.text.toString())
            if (url == null) {
                tvHealth.text = "Health: invalid url"
                return@setOnClickListener
            }
            tvHealth.text = "Health: checking..."
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val r = NetworkClient.buildApi(url).healthCheck()
                        if (!r.isSuccessful || r.body() == null) "HTTP ${r.code()}" else "ok | v=${r.body()!!.version}"
                    } catch (_: Exception) {
                        "offline"
                    }
                }
                tvHealth.text = "Health: $result"
            }
        }

        btnClear.setOnClickListener {
            prefs.edit().remove(AppPrefs.KEY_SESSION_HISTORY).apply()
            renderHistory()
        }

        btnCopy.setOnClickListener {
            val raw = prefs.getString(AppPrefs.KEY_SESSION_HISTORY, "") ?: ""
            val arr = runCatching { JSONArray(raw) }.getOrNull()
            // optString(name, fallback) expects String fallback, not null.
            val sid = arr
                ?.optJSONObject(0)
                ?.optString("session_id")
                ?.takeIf { it.isNotBlank() }
            if (!sid.isNullOrBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("session_id", sid))
                AlertDialog.Builder(this).setMessage("Copied: $sid").setPositiveButton("OK", null).show()
            } else {
                AlertDialog.Builder(this).setMessage("No session_id to copy").setPositiveButton("OK", null).show()
            }
        }

        renderHistory()
        btnCheck.performClick()
    }
}
