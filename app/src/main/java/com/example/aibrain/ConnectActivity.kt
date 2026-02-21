package com.example.aibrain

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnContinue: Button



    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.navigation_bar)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        setContentView(R.layout.activity_connect)

        etUrl = findViewById(R.id.et_server_url)
        tvStatus = findViewById(R.id.tv_connect_status)
        val btnCheck: Button = findViewById(R.id.btn_check)
        btnContinue = findViewById(R.id.btn_continue)
        val btnSettings: Button = findViewById(R.id.btn_settings)

        val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        etUrl.setText(prefs.getString(AppPrefs.KEY_SERVER_BASE_URL, AppPrefs.defaultBaseUrl()).orEmpty())

        btnContinue.isEnabled = true
        btnCheck.setOnClickListener { doHealthCheck() }
        btnContinue.setOnClickListener {
            val url = normalizeBaseUrl(etUrl.text.toString())
            if (url != null) {
                prefs.edit().putString(AppPrefs.KEY_SERVER_BASE_URL, url).apply()
            }
            // Even if URL is not set / server is offline, allow opening MainActivity (camera + ruler can work offline).
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        doHealthCheck()
    }

    private fun doHealthCheck() {
        val url = normalizeBaseUrl(etUrl.text.toString())
        if (url == null) {
            setStatus(false, getString(R.string.connect_invalid_url))
            // Allow entering the app even if URL is invalid/empty; MainActivity will fall back to default URL.
            btnContinue.isEnabled = true
            return
        }

        setStatus(null, getString(R.string.connect_status_checking))
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val r = NetworkClient.buildApi(url).healthCheck()
                    r.isSuccessful && r.body()?.status == "ok"
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                setStatus(true, getString(R.string.connect_status_online))
            } else {
                setStatus(false, getString(R.string.connect_status_offline))
            }
            // Continue should be available even without server connection (offline mode).
            btnContinue.isEnabled = true
        }
    }

    private fun setStatus(ok: Boolean?, msg: String) {
        val color = when (ok) {
            true -> R.color.cyan_primary
            false -> R.color.orange_primary
            null -> R.color.text_white_dim
        }
        tvStatus.setTextColor(ContextCompat.getColor(this, color))
        tvStatus.text = msg
    }

    private fun normalizeBaseUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null
        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
