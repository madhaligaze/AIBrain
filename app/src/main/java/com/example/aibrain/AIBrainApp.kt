package com.example.aibrain

import android.app.Application
import android.util.Log
import java.io.File

class AIBrainApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = File(filesDir, "crash_marker.txt")
                val body = buildString {
                    append(System.currentTimeMillis())
                    append("\n")
                    append(e.javaClass.name)
                    append(": ")
                    append(e.message ?: "")
                    append("\n")
                    append(Log.getStackTraceString(e))
                }
                f.writeText(body.take(8192))
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
