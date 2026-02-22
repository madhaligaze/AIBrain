package com.example.aibrain

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.TextView

class MessageCenter(
    private val activity: Activity,
    private val hudView: TextView,
    private val hintQueue: ArrayDeque<String>,
    private val hintHistory: ArrayDeque<String>
) {
    enum class Level { INFO, WARN, BLOCKER }

    enum class Source(val defaultCooldownMs: Long) {
        AR(5_000L),
        DEPTH(30_000L),
        NETWORK(4_000L),
        READINESS(8_000L),
        ASSETS(0L),
        UI(0L)
    }

    private data class SourceState(var lastShownMs: Long = 0L, var lastText: String = "")

    private val sourceState: MutableMap<Source, SourceState> = Source.values()
        .associateWith { SourceState() }
        .toMutableMap()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(text: String, level: Level = Level.INFO, source: Source = Source.UI, setAsHud: Boolean = false) {
        val now = System.currentTimeMillis()
        val state = sourceState[source] ?: return
        val cooldown = if (level == Level.BLOCKER) 0L else source.defaultCooldownMs

        if (level != Level.BLOCKER && source != Source.UI) {
            if (state.lastText == text && now - state.lastShownMs < cooldown) return
            if (now - state.lastShownMs < cooldown) return
        }

        state.lastShownMs = now
        state.lastText = text

        mainHandler.post {
            hintQueue.addLast(text)
            hintHistory.addLast(text)
            while (hintHistory.size > 20) hintHistory.removeFirst()
            if (setAsHud) hudView.text = text
        }
    }

    fun setHud(text: String) {
        mainHandler.post { hudView.text = text }
    }

    fun resetSource(source: Source) {
        sourceState[source]?.apply {
            lastShownMs = 0L
            lastText = ""
        }
    }

    fun resetAll() {
        sourceState.values.forEach {
            it.lastShownMs = 0L
            it.lastText = ""
        }
    }
}
