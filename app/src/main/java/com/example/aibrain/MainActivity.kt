package com.example.aibrain

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MAX_SESSION_RETRY_ATTEMPTS = 5
        private const val SESSION_RETRY_DELAY_MS = 1_500L
        private const val MAX_FAILURES_BEFORE_WARN = 3
        private const val MAX_FAILURES_BEFORE_RECONNECT = 6
        private const val RECONNECT_DELAY_BASE_MS = 2_000L
        private const val RECONNECT_DELAY_MAX_MS = 30_000L
        private const val STREAM_INTERVAL_MS = 1_000L
    }

    private lateinit var sceneView: ArSceneView
    private lateinit var tvAiHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnModel: Button

    private var currentSessionId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isStreaming = false
    private var consecutiveFailures = 0
    private var isReconnecting = false

    private val userMarkers = mutableListOf<Map<String, Float>>()

    private val api = Retrofit.Builder()
        .baseUrl("http://100.119.60.35:8000")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)
        tvAiHint = findViewById(R.id.tv_ai_hint)
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnModel = findViewById(R.id.btn_model)

        sceneView.configureSession { _, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        btnStart.setOnClickListener { startSession() }
        btnAddPoint.setOnClickListener { placeAnchor() }
        btnModel.setOnClickListener {
            stopStreaming()
            requestModeling()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startSession() {
        scope.launch {
            tvAiHint.text = getString(R.string.hint_connecting)
            val response = establishSessionWithRetry()
            if (response != null && response.isSuccessful) {
                currentSessionId = response.body()?.session_id
                tvAiHint.text = getString(R.string.hint_session_active)

                btnStart.visibility = View.GONE
                btnAddPoint.visibility = View.VISIBLE
                btnModel.visibility = View.VISIBLE

                startStreaming()
            } else if (response != null) {
                tvAiHint.text = getString(R.string.hint_server_error_code, response.code())
            } else {
                tvAiHint.text = getString(R.string.hint_retry_failed)
            }
        }
    }

    private suspend fun establishSessionWithRetry(): retrofit2.Response<SessionResponse>? {
        repeat(MAX_SESSION_RETRY_ATTEMPTS) { attempt ->
            try {
                return withContext(Dispatchers.IO) { api.startSession() }
            } catch (_: Exception) {
                val attemptNumber = attempt + 1
                if (attemptNumber >= MAX_SESSION_RETRY_ATTEMPTS) {
                    return null
                }

                tvAiHint.text = getString(
                    R.string.hint_reconnecting_attempt,
                    attemptNumber,
                    MAX_SESSION_RETRY_ATTEMPTS
                )
                delay(SESSION_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun startStreaming() {
        if (isStreaming) return

        isStreaming = true
        consecutiveFailures = 0
        scope.launch(Dispatchers.IO) {
            var shouldReconnect = false
            while (isStreaming && currentSessionId != null && !shouldReconnect) {
                val frame = try {
                    sceneView.arSession?.update()
                } catch (_: Exception) {
                    null
                }

                if (frame == null) {
                    delay(STREAM_INTERVAL_MS)
                    continue
                }

                val cameraImage = try {
                    frame.acquireCameraImage()
                } catch (_: Exception) {
                    null
                }

                if (cameraImage != null) {
                    val base64Image = ImageUtils.convertYuvToJpegBase64(cameraImage)
                    cameraImage.close()

                    val pose = frame.camera.pose
                    val poseList = listOf(
                        pose.tx(), pose.ty(), pose.tz(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw()
                    )

                    val payload: Map<String, Any> = mapOf(
                        "image" to base64Image,
                        "pose" to poseList,
                        "markers" to userMarkers
                    )

                    val success = try {
                        val sessionId = currentSessionId ?: return@launch
                        val response = api.streamData(sessionId, payload)
                        if (response.isSuccessful) {
                            val hints = response.body()?.hints
                            withContext(Dispatchers.Main) {
                                if (!hints.isNullOrEmpty()) {
                                    tvAiHint.text = getString(R.string.hint_ai_prefix) +
                                            " " + hints.values.first().joinToString()
                                }
                            }
                            true
                        } else {
                            false
                        }
                    } catch (_: Exception) {
                        false
                    }

                    if (success) {
                        if (consecutiveFailures > 0) {
                            consecutiveFailures = 0
                            withContext(Dispatchers.Main) {
                                tvAiHint.text = getString(R.string.hint_session_active)
                            }
                        }
                    } else {
                        consecutiveFailures++
                        when {
                            consecutiveFailures >= MAX_FAILURES_BEFORE_RECONNECT -> {
                                shouldReconnect = true
                                withContext(Dispatchers.Main) { scheduleReconnect() }
                            }

                            consecutiveFailures >= MAX_FAILURES_BEFORE_WARN -> {
                                withContext(Dispatchers.Main) {
                                    tvAiHint.text = getString(
                                        R.string.hint_network_unstable,
                                        consecutiveFailures
                                    )
                                }
                            }
                        }
                    }
                }

                if (!shouldReconnect) {
                    delay(STREAM_INTERVAL_MS)
                }
            }
            isStreaming = false
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting || !isStreaming) return
        isReconnecting = true

        scope.launch {
            var attempt = 0
            while (isStreaming) {
                val delayMs = min(
                    RECONNECT_DELAY_BASE_MS * (1L shl attempt),
                    RECONNECT_DELAY_MAX_MS
                )

                tvAiHint.text = getString(
                    R.string.hint_reconnect_wait,
                    attempt + 1,
                    delayMs / 1000
                )
                delay(delayMs)

                val sessionId = currentSessionId
                if (sessionId != null) {
                    val pingOk = try {
                        val ping = withContext(Dispatchers.IO) {
                            api.streamData(
                                sessionId,
                                mapOf(
                                    "image" to "",
                                    "pose" to emptyList<Float>(),
                                    "markers" to emptyList<Map<String, Float>>()
                                )
                            )
                        }
                        ping.isSuccessful
                    } catch (_: Exception) {
                        false
                    }

                    if (pingOk) {
                        consecutiveFailures = 0
                        isReconnecting = false
                        tvAiHint.text = getString(R.string.hint_session_active)
                        startStreaming()
                        return@launch
                    }
                }

                if (attempt >= 3) {
                    val newSession = establishSessionWithRetry()
                    if (newSession != null && newSession.isSuccessful) {
                        currentSessionId = newSession.body()?.session_id
                        consecutiveFailures = 0
                        isReconnecting = false
                        tvAiHint.text = getString(R.string.hint_session_restored)
                        startStreaming()
                        return@launch
                    }
                }

                attempt++
            }
            isReconnecting = false
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        isReconnecting = false
        consecutiveFailures = 0
    }

    private fun placeAnchor() {
        val frame = try {
            sceneView.arSession?.update()
        } catch (_: Exception) {
            null
        } ?: return

        val hitResult = frame.hitTest(
            sceneView.width / 2f,
            sceneView.height / 2f
        ).firstOrNull()

        if (hitResult != null) {
            val anchor = hitResult.createAnchor()
            val pose = anchor.pose
            userMarkers.add(
                mapOf("x" to pose.tx(), "y" to pose.ty(), "z" to pose.tz())
            )
            Toast.makeText(this, R.string.toast_point_added, Toast.LENGTH_SHORT).show()
            tvAiHint.text = getString(R.string.hint_points_count, userMarkers.size)
        } else {
            Toast.makeText(this, R.string.toast_move_closer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestModeling() {
        scope.launch {
            val sessionId = currentSessionId
            if (sessionId == null) {
                tvAiHint.text = getString(R.string.hint_session_not_started)
                return@launch
            }

            tvAiHint.text = getString(R.string.hint_ai_thinking)
            var attempt = 0
            val maxAttempts = 3
            while (attempt < maxAttempts) {
                try {
                    val response = withContext(Dispatchers.IO) { api.startModeling(sessionId) }
                    if (response.isSuccessful) {
                        val count = response.body()?.options?.size ?: 0
                        tvAiHint.text = getString(R.string.hint_modeling_done_options, count)
                    } else {
                        tvAiHint.text = getString(R.string.hint_modeling_error)
                    }
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    if (attempt < maxAttempts) {
                        val retryDelay = RECONNECT_DELAY_BASE_MS * attempt
                        tvAiHint.text = getString(
                            R.string.hint_modeling_retry,
                            attempt,
                            maxAttempts,
                            retryDelay / 1000
                        )
                        delay(retryDelay)
                    } else {
                        tvAiHint.text = getString(
                            R.string.hint_failure_message,
                            e.message ?: getString(R.string.unknown_error)
                        )
                    }
                }
            }
        }
    }
}