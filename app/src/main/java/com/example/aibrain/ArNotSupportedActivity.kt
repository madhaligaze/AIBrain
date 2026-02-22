package com.example.aibrain

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ArNotSupportedActivity : AppCompatActivity() {
    companion object {
        const val AR_REASON_KEY = "ar_reason"
        const val REASON_API_TOO_LOW = "api_too_low"
        const val REASON_NOT_SUPPORTED = "not_supported"
        const val REASON_NOT_INSTALLED = "not_installed"
        const val REASON_SESSION_FAIL = "session_fail"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_not_supported)

        val reason = intent.getStringExtra(AR_REASON_KEY) ?: REASON_NOT_SUPPORTED
        val tvTitle: TextView = findViewById(R.id.tv_ar_ns_title)
        val tvBody: TextView = findViewById(R.id.tv_ar_ns_body)
        val btnInstall: Button = findViewById(R.id.btn_ar_ns_install)
        val btnBack: Button = findViewById(R.id.btn_ar_ns_back)

        when (reason) {
            REASON_API_TOO_LOW -> {
                tvTitle.text = getString(R.string.ar_ns_title_api)
                tvBody.text = getString(R.string.ar_ns_body_api)
                btnInstall.visibility = View.GONE
            }
            REASON_NOT_SUPPORTED -> {
                tvTitle.text = getString(R.string.ar_ns_title_unsupported)
                tvBody.text = getString(R.string.ar_ns_body_unsupported)
                btnInstall.visibility = View.GONE
            }
            REASON_NOT_INSTALLED -> {
                tvTitle.text = getString(R.string.ar_ns_title_install)
                tvBody.text = getString(R.string.ar_ns_body_install)
                btnInstall.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.ar.core")))
                }
            }
            else -> {
                tvTitle.text = getString(R.string.ar_ns_title_session)
                tvBody.text = getString(R.string.ar_ns_body_session)
                btnInstall.visibility = View.GONE
            }
        }

        btnBack.setOnClickListener { finish() }
    }
}
