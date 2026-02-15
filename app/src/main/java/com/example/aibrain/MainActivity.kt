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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ArSceneView
    private lateinit var tvAiHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnModel: Button

    private var currentSessionId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isStreaming = false

    // Состояние соединения
    private var consecutiveFailures = 0
    private var isReconnecting = false

    companion object {
        private const val MAX_FAILURES_BEFORE_WARN = 3
        private const val MAX_FAILURES_BEFORE_RECONNECT = 6
        private const val RECONNECT_DELAY_BASE_MS = 2_000L
        private const val RECONNECT_DELAY_MAX_MS = 30_000L
        private const val STREAM_INTERVAL_MS = 1_000L
    }

    // Список точек, которые поставил пользователь (x, y, z)
    private val userMarkers = mutableListOf<Map<String, Float>>()

    // НАСТРОЙКА СЕТИ (Проверь IP!)
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

        // Настройка AR сцены
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

    private fun startSession() {
        scope.launch {
            try {
                tvAiHint.text = getString(R.string.hint_connecting)
                val response = api.startSession()
                if (response.isSuccessful) {
                    currentSessionId = response.body()?.session_id
                    tvAiHint.text = getString(R.string.hint_session_active)

                    btnStart.visibility = View.GONE
                    btnAddPoint.visibility = View.VISIBLE
                    btnModel.visibility = View.VISIBLE

                    startStreaming()
                } else {
                    tvAiHint.text = getString(R.string.hint_server_error_code, response.code())
                }
            } catch (e: Exception) {
                tvAiHint.text = getString(
                    R.string.hint_no_connection,
                    e.message ?: getString(R.string.unknown_error)
                )
            }
        }
    }

    // ЛОГИКА СТРИМИНГА: отправляем кадр каждые 1000мс
    // Используем флаг shouldReconnect вместо break,
    // чтобы не требовать Kotlin 2.2+ (break/continue в лямбдах)
    private fun startStreaming() {
        isStreaming = true
        consecutiveFailures = 0
        scope.launch(Dispatchers.IO) {
            var shouldReconnect = false

            while (isStreaming && currentSessionId != null && !shouldReconnect) {

                val frame = sceneView.arSession?.update()
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
                    // 1. Конвертируем картинку
                    val base64Image = ImageUtils.convertYuvToJpegBase64(cameraImage)
                    cameraImage.close() // Обязательно закрываем!

                    // 2. Берем позицию телефона (Pose)
                    val pose = frame.camera.pose
                    val poseList = listOf(
                        pose.tx(), pose.ty(), pose.tz(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw()
                    )

                    // 3. Формируем пакет данных
                    val payload = mapOf(
                        "image" to base64Image,
                        "pose" to poseList,
                        "markers" to userMarkers
                    )

                    // 4. Отправляем на сервер
                    val success = try {
                        val response = api.streamData(currentSessionId!!, payload)
                        if (response.isSuccessful) {
                            val hints = response.body()?.hints
                            withContext(Dispatchers.Main) {
                                if (!hints.isNullOrEmpty()) {
                                    tvAiHint.text = getString(
                                        R.string.hint_ai_message,
                                        hints.values.first().joinToString()
                                    )
                                }
                            }
                            true
                        } else {
                            false
                        }
                    } catch (_: Exception) {
                        false
                    }

                    // 5. Учитываем сбои и при необходимости переподключаемся
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
                                // Флаг вместо break — работает в любой версии Kotlin
                                shouldReconnect = true
                                withContext(Dispatchers.Main) { scheduleReconnect() }
                            }
                            consecutiveFailures >= MAX_FAILURES_BEFORE_WARN -> {
                                withContext(Dispatchers.Main) {
                                    tvAiHint.text = "⚠️ Нестабильная сеть ($consecutiveFailures сбоев)"
                                }
                            }
                        }
                    }
                }

                if (!shouldReconnect) delay(STREAM_INTERVAL_MS)
            }
        }
    }

    // Авто-переподключение с экспоненциальным backoff: 2с → 4с → 8с → … → max 30с
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
                tvAiHint.text = "🔄 Переподключение... Попытка ${attempt + 1} (ждём ${delayMs / 1000}с)"
                delay(delayMs)

                // Сначала пробуем возобновить существующую сессию
                try {
                    val ping = api.streamData(
                        currentSessionId!!,
                        mapOf(
                            "image" to "",
                            "pose" to emptyList<Float>(),
                            "markers" to emptyList<Map<String, Float>>()
                        )
                    )
                    if (ping.isSuccessful) {
                        consecutiveFailures = 0
                        isReconnecting = false
                        tvAiHint.text = getString(R.string.hint_session_active)
                        startStreaming()
                        return@launch
                    }
                } catch (_: Exception) { /* сервер недоступен, пробуем снова */ }

                // После 3 неудачных пингов — стартуем новую сессию (сервер мог перезапуститься)
                if (attempt >= 3) {
                    try {
                        val newSession = api.startSession()
                        if (newSession.isSuccessful) {
                            currentSessionId = newSession.body()?.session_id
                            consecutiveFailures = 0
                            isReconnecting = false
                            tvAiHint.text = "✅ Сессия восстановлена"
                            startStreaming()
                            return@launch
                        }
                    } catch (_: Exception) { /* сервер всё ещё недоступен */ }
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

    // Ставим 3D-точку в пространстве
    private fun placeAnchor() {
        val frame = sceneView.arSession?.update() ?: return
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
            tvAiHint.text = getString(R.string.hint_ai_thinking)
            var attempt = 0
            val maxAttempts = 3
            while (attempt < maxAttempts) {
                try {
                    val response = api.startModeling(currentSessionId!!)
                    if (response.isSuccessful) {
                        val count = response.body()?.options?.size ?: 0
                        tvAiHint.text = getString(R.string.hint_modeling_done_options, count)
                        return@launch
                    } else {
                        tvAiHint.text = getString(R.string.hint_modeling_error)
                        return@launch
                    }
                } catch (e: Exception) {
                    attempt++
                    if (attempt < maxAttempts) {
                        val retryDelay = RECONNECT_DELAY_BASE_MS * attempt
                        tvAiHint.text = "⏳ Попытка $attempt/$maxAttempts, следующая через ${retryDelay / 1000}с..."
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