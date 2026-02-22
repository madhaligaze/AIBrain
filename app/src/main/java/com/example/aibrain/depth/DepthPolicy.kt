package com.example.aibrain.depth

import android.util.Log
import com.google.ar.core.Config

class DepthPolicy(
    val arCoreDepthMode: Config.DepthMode
) {
    enum class Mode { RAW, AUTO, DISABLED }

    enum class Strategy {
        FULL_DEPTH,
        UNSTABLE_DEPTH,
        NO_DEPTH
    }

    companion object {
        const val SEND_EVERY_FULL = 5
        const val SEND_EVERY_UNSTABLE = 12
        const val SEND_EVERY_DISABLED = Int.MAX_VALUE

        const val RATE_WINDOW = 30
        const val STABLE_THRESHOLD = 0.75f
        const val DEGRADED_THRESHOLD = 0.20f
        const val DISABLE_AFTER_STREAK = 20
        const val WARMUP_MS = 3_500L

        const val MAX_DEPTH_PAYLOAD_BYTES = 1024 * 1024
    }

    val supported: Boolean = arCoreDepthMode != Config.DepthMode.DISABLED
    val mode: Mode = when (arCoreDepthMode) {
        Config.DepthMode.RAW_DEPTH_ONLY -> Mode.RAW
        Config.DepthMode.AUTOMATIC -> Mode.AUTO
        else -> Mode.DISABLED
    }

    private var strategy: Strategy = if (supported) Strategy.FULL_DEPTH else Strategy.NO_DEPTH
    private val rateWindow = ArrayDeque<Boolean>(RATE_WINDOW)
    private var unavailableStreak = 0
    private var everReceived = false
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var frameCounter = 0

    fun onSessionStart() {
        rateWindow.clear()
        unavailableStreak = 0
        everReceived = false
        sessionStartMs = System.currentTimeMillis()
        frameCounter = 0
        strategy = if (supported) Strategy.FULL_DEPTH else Strategy.NO_DEPTH
        Log.i("DepthPolicy", "Session start: mode=$mode supported=$supported")
    }

    fun onFrame(depthReceived: Boolean) {
        frameCounter++
        if (!supported) return

        if (rateWindow.size >= RATE_WINDOW) rateWindow.removeFirst()
        rateWindow.addLast(depthReceived)

        if (depthReceived) {
            unavailableStreak = 0
            everReceived = true
        } else {
            val pastWarmup = (System.currentTimeMillis() - sessionStartMs) > WARMUP_MS
            if (pastWarmup) unavailableStreak++
        }

        val rate = availableRate()
        strategy = when {
            !supported -> Strategy.NO_DEPTH
            !everReceived && unavailableStreak >= DISABLE_AFTER_STREAK -> {
                Log.w("DepthPolicy", "Forced NO_DEPTH after $unavailableStreak consecutive failures")
                Strategy.NO_DEPTH
            }
            rate >= STABLE_THRESHOLD -> Strategy.FULL_DEPTH
            rate >= DEGRADED_THRESHOLD -> Strategy.UNSTABLE_DEPTH
            else -> Strategy.NO_DEPTH
        }
    }

    fun availableRate(): Float {
        if (!supported || rateWindow.isEmpty()) return if (supported) 1f else 0f
        return rateWindow.count { it }.toFloat() / rateWindow.size
    }

    fun currentStrategy(): Strategy = strategy

    fun shouldAttemptDepth(): Boolean {
        if (strategy == Strategy.NO_DEPTH) return false
        val every = depthSendEvery()
        return frameCounter % every == 0
    }

    fun depthSendEvery(): Int = when (strategy) {
        Strategy.FULL_DEPTH -> SEND_EVERY_FULL
        Strategy.UNSTABLE_DEPTH -> SEND_EVERY_UNSTABLE
        Strategy.NO_DEPTH -> SEND_EVERY_DISABLED
    }

    fun readinessProfileName(): String = when (strategy) {
        Strategy.FULL_DEPTH -> "WithDepth"
        Strategy.UNSTABLE_DEPTH -> "UnstableDepth"
        Strategy.NO_DEPTH -> "NoDepth"
    }

    fun uiHint(prevStrategy: Strategy?): String? {
        if (strategy == prevStrategy) return null
        return when (strategy) {
            Strategy.FULL_DEPTH -> null
            Strategy.UNSTABLE_DEPTH ->
                "Данные глубины нестабильны. Улучшите освещение или замедлите движение."

            Strategy.NO_DEPTH -> when {
                !supported ->
                    "Датчик глубины не поддерживается. Сканирование продолжается — потребуется больше ракурсов."

                !everReceived ->
                    "Depth недоступен на этой сессии. Продолжайте — система переключилась на RGB+облако точек."

                else ->
                    "Depth временно потерян. Попробуйте улучшить освещение. Сканирование продолжается."
            }
        }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "depth_mode" to mode.name,
        "depth_supported" to supported,
        "depth_strategy" to strategy.name,
        "depth_rate" to "%.2f".format(availableRate()),
        "depth_ever_recv" to everReceived,
        "depth_streak" to unavailableStreak,
        "readiness_profile" to readinessProfileName()
    )
}
