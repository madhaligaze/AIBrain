package com.example.aibrain

object AppPrefs {
    const val PREFS_NAME = "app_settings"
    const val KEY_SERVER_BASE_URL = "server_base_url"
    const val KEY_SESSION_HISTORY = "session_history_json"
    const val KEY_TUTORIAL_DONE = "tutorial_done_v2"

    fun defaultBaseUrl(): String = BuildConfig.BACKEND_BASE_URL
}
