package com.example.aibrain

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
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

/**
 * Основной экран AR-приложения.
 *
 * Userflow:
 *  IDLE → [Нажать СТАРТ] → CONNECTING → SCANNING → [Нажать ОПОРА × N] →
 *  [Нажать АНАЛИЗ] → MODELING → RESULTS
 *
 * Workflow:
 *  1. СТАРТ     — создаёт сессию на сервере, начинает стриминг кадров
 *  2. SCANNING  — каждую секунду отправляет JPEG + pose → AI возвращает подсказки
 *  3. ОПОРА     — пользователь ставит AR-маркер на конструктивный элемент
 *  4. АНАЛИЗ    — стриминг останавливается, сервер генерирует 3 варианта лесов
 *  5. RESULTS   — показывает варианты с safety_score и критикой ИИ
 *  Автореконнект: экспоненциальный backoff при потере Tailscale/VPN
 */
class MainActivity : AppCompatActivity() {

    // ── Состояния приложения ──────────────────────────────────────────────────
    private enum class AppState {
        IDLE, CONNECTING, SCANNING, MODELING, RESULTS
    }

    companion object {
        private const val MAX_SESSION_RETRY      = 5
        private const val SESSION_RETRY_DELAY_MS = 1_500L
        private const val MAX_FAIL_WARN          = 3
        private const val MAX_FAIL_RECONNECT     = 6
        private const val RECONNECT_BASE_MS      = 2_000L
        private const val RECONNECT_MAX_MS       = 30_000L
        private const val STREAM_INTERVAL_MS     = 1_000L
        private const val MIN_POINTS_FOR_MODEL   = 2
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var sceneView:       ArSceneView
    private lateinit var tvAiHint:        TextView
    private lateinit var tvFrameCounter:  TextView
    private lateinit var btnStart:        Button
    private lateinit var btnAddPoint:     Button
    private lateinit var btnModel:        Button

    // ── Состояние ─────────────────────────────────────────────────────────────
    private var appState            = AppState.IDLE
    private var currentSessionId:   String? = null
    private var isStreaming         = false
    private var consecutiveFailures = 0
    private var isReconnecting      = false
    private var frameCount          = 0
    private var lastQualityScore    = 0.0
    private val userMarkers         = mutableListOf<Map<String, Float>>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val api = Retrofit.Builder()
        .baseUrl("http://100.119.60.35:8000/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView      = findViewById(R.id.sceneView)
        tvAiHint       = findViewById(R.id.tv_ai_hint)
        tvFrameCounter = findViewById(R.id.tv_frame_counter)
        btnStart       = findViewById(R.id.btn_start)
        btnAddPoint    = findViewById(R.id.btn_add_point)
        btnModel       = findViewById(R.id.btn_model)

        sceneView.configureSession { _, config ->
            config.focusMode          = Config.FocusMode.AUTO
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        btnStart.setOnClickListener    { onStartClicked() }
        btnAddPoint.setOnClickListener { onAddPointClicked() }
        btnModel.setOnClickListener    { onModelClicked() }

        transitionTo(AppState.IDLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Кнопки ────────────────────────────────────────────────────────────────

    private fun onStartClicked() {
        if (appState != AppState.IDLE) return
        transitionTo(AppState.CONNECTING)
        scope.launch { doStartSession() }
    }

    private fun onAddPointClicked() {
        if (appState != AppState.SCANNING) return
        placeAnchor()
    }

    private fun onModelClicked() {
        if (appState != AppState.SCANNING) return
        if (userMarkers.size < MIN_POINTS_FOR_MODEL) {
            showHint("📍 Нужно минимум $MIN_POINTS_FOR_MODEL опорных точки. Сейчас: ${userMarkers.size}")
            return
        }
        stopStreaming()
        transitionTo(AppState.MODELING)
        scope.launch { doRequestModeling() }
    }

    // ── Управление состоянием ─────────────────────────────────────────────────

    private fun transitionTo(state: AppState) {
        appState = state
        when (state) {
            AppState.IDLE -> {
                btnStart.visibility    = View.VISIBLE
                btnAddPoint.visibility = View.GONE
                btnModel.visibility    = View.GONE
                showHint("Наведите камеру на конструкцию и нажмите СТАРТ")
            }
            AppState.CONNECTING -> {
                btnStart.visibility    = View.GONE
                btnAddPoint.visibility = View.GONE
                btnModel.visibility    = View.GONE
                showHint("⏳ Подключение к серверу...")
                startBlinkAnimation(tvAiHint)
            }
            AppState.SCANNING -> {
                stopAnimation(tvAiHint)
                btnStart.visibility    = View.GONE
                btnAddPoint.visibility = View.VISIBLE
                btnModel.visibility    = View.VISIBLE
                btnModel.isEnabled     = false
                showHint("✅ Сессия активна. Ходите вокруг — ставьте точки опор.")
            }
            AppState.MODELING -> {
                btnAddPoint.visibility = View.GONE
                btnModel.visibility    = View.GONE
                showHint("🧠 ИИ моделирует конструкцию...")
                startBlinkAnimation(tvAiHint)
            }
            AppState.RESULTS -> {
                stopAnimation(tvAiHint)
                btnStart.visibility    = View.VISIBLE
                btnStart.text          = "ЗАНОВО"
                btnAddPoint.visibility = View.GONE
                btnModel.visibility    = View.GONE
            }
        }
    }

    // ── Сессия ────────────────────────────────────────────────────────────────

    private suspend fun doStartSession() {
        val response = establishSessionWithRetry()
        if (response != null && response.isSuccessful) {
            currentSessionId = response.body()?.session_id
            userMarkers.clear()
            frameCount = 0
            transitionTo(AppState.SCANNING)
            startStreaming()
        } else {
            transitionTo(AppState.IDLE)
            showHint(if (response != null)
                "❌ Ошибка сервера: ${response.code()}"
            else
                "❌ Не удалось подключиться. Проверьте VPN (Tailscale)")
        }
    }

    private suspend fun establishSessionWithRetry(): retrofit2.Response<SessionResponse>? {
        repeat(MAX_SESSION_RETRY) { attempt ->
            try {
                return withContext(Dispatchers.IO) { api.startSession() }
            } catch (_: Exception) {
                if (attempt + 1 >= MAX_SESSION_RETRY) return null
                showHint("⏳ Попытка ${attempt + 2}/$MAX_SESSION_RETRY...")
                delay(SESSION_RETRY_DELAY_MS)
            }
        }
        return null
    }

    // ── Стриминг ──────────────────────────────────────────────────────────────

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        consecutiveFailures = 0

        scope.launch(Dispatchers.IO) {
            var shouldReconnect = false
            while (isStreaming && currentSessionId != null && !shouldReconnect) {
                val frame = try { sceneView.arSession?.update() } catch (_: Exception) { null }

                if (frame == null) { delay(STREAM_INTERVAL_MS); continue }

                val cameraImage = try { frame.acquireCameraImage() } catch (_: Exception) { null }

                if (cameraImage != null) {
                    val base64Image = ImageUtils.convertYuvToJpegBase64(cameraImage)
                    cameraImage.close()

                    val pose = frame.camera.pose
                    val poseList = listOf(pose.tx(), pose.ty(), pose.tz(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw())

                    val payload: Map<String, Any> = mapOf(
                        "image"   to base64Image,
                        "pose"    to poseList,
                        "markers" to userMarkers
                    )

                    val success = trySendFrame(payload)

                    if (success) {
                        if (consecutiveFailures > 0) {
                            consecutiveFailures = 0
                            withContext(Dispatchers.Main) {
                                if (appState == AppState.SCANNING)
                                    showHint("✅ Связь восстановлена. Продолжайте сканирование.")
                            }
                        }
                        frameCount++
                        withContext(Dispatchers.Main) {
                            tvFrameCounter.text = "FRM:${frameCount.toString().padStart(4, '0')}"
                        }
                    } else {
                        consecutiveFailures++
                        when {
                            consecutiveFailures >= MAX_FAIL_RECONNECT -> {
                                shouldReconnect = true
                                withContext(Dispatchers.Main) { scheduleReconnect() }
                            }
                            consecutiveFailures >= MAX_FAIL_WARN -> {
                                withContext(Dispatchers.Main) {
                                    showHint("⚠️ Нестабильная сеть ($consecutiveFailures сбоев)...")
                                }
                            }
                        }
                    }
                }
                if (!shouldReconnect) delay(STREAM_INTERVAL_MS)
            }
            isStreaming = false
        }
    }

    private suspend fun trySendFrame(payload: Map<String, Any>): Boolean {
        return try {
            val sessionId = currentSessionId ?: return false
            val response = api.streamData(sessionId, payload)
            if (response.isSuccessful) {
                val hints = response.body()?.ai_hints
                withContext(Dispatchers.Main) { processAiHints(hints) }
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun processAiHints(hints: AiHints?) {
        if (hints == null || appState != AppState.SCANNING) return

        lastQualityScore = hints.quality_score ?: lastQualityScore

        // Обновляем подсказку
        val primary = hints.instructions?.firstOrNull()
            ?: hints.warnings?.firstOrNull()
        if (!primary.isNullOrEmpty()) {
            showHint(primary)
        } else {
            val score = lastQualityScore.toInt()
            showHint("📊 Качество: $score% | Точек: ${userMarkers.size}")
        }

        // Разблокируем кнопку АНАЛИЗ когда данных достаточно
        val ready = (hints.is_ready == true) && userMarkers.size >= MIN_POINTS_FOR_MODEL
        btnModel.isEnabled = ready
        if (ready && !btnModel.isEnabled) {
            showHint("✅ Данных достаточно! Нажмите АНАЛИЗ.")
        }
    }

    // ── Реконнект ─────────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (isReconnecting || !isStreaming) return
        isReconnecting = true

        scope.launch {
            var attempt = 0
            while (isStreaming) {
                val delayMs = min(RECONNECT_BASE_MS * (1L shl attempt), RECONNECT_MAX_MS)
                showHint("🔄 Переподключение... попытка ${attempt + 1} (${delayMs / 1000}с)")
                delay(delayMs)

                val sessionId = currentSessionId
                if (sessionId != null) {
                    val pingOk = try {
                        val ping = withContext(Dispatchers.IO) {
                            api.streamData(sessionId, mapOf(
                                "image" to "", "pose" to emptyList<Float>(), "markers" to emptyList<Any>()
                            ))
                        }
                        ping.isSuccessful
                    } catch (_: Exception) { false }

                    if (pingOk) {
                        consecutiveFailures = 0; isReconnecting = false
                        showHint("✅ Связь восстановлена")
                        startStreaming()
                        return@launch
                    }
                }

                if (attempt >= 3) {
                    val newSession = establishSessionWithRetry()
                    if (newSession?.isSuccessful == true) {
                        currentSessionId = newSession.body()?.session_id
                        consecutiveFailures = 0; isReconnecting = false
                        showHint("✅ Сессия пересоздана")
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
        isStreaming = false; isReconnecting = false; consecutiveFailures = 0
    }

    // ── Якоря ─────────────────────────────────────────────────────────────────

    private fun placeAnchor() {
        val frame = try { sceneView.arSession?.update() } catch (_: Exception) { null } ?: return

        val hit = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f).firstOrNull()
        if (hit != null) {
            val anchor = hit.createAnchor()
            val pose = anchor.pose
            userMarkers.add(mapOf("x" to pose.tx(), "y" to pose.ty(), "z" to pose.tz()))
            val count = userMarkers.size
            Toast.makeText(this, "📍 Точка $count добавлена", Toast.LENGTH_SHORT).show()
            showHint("📍 Точек: $count${if (count >= MIN_POINTS_FOR_MODEL) " — можно моделировать!" else ""}")
            if (count >= MIN_POINTS_FOR_MODEL && lastQualityScore >= 60) {
                btnModel.isEnabled = true
            }
        } else {
            Toast.makeText(this, "Подойдите ближе к поверхности", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Моделирование ─────────────────────────────────────────────────────────

    private suspend fun doRequestModeling() {
        val sessionId = currentSessionId
        if (sessionId == null) {
            transitionTo(AppState.IDLE)
            showHint("❌ Сессия не активна. Нажмите СТАРТ.")
            return
        }

        var attempt = 0
        val maxAttempts = 3
        while (attempt < maxAttempts) {
            try {
                val response = withContext(Dispatchers.IO) { api.startModeling(sessionId) }
                if (response.isSuccessful) {
                    val result = response.body()
                    transitionTo(AppState.RESULTS)
                    showModelingResults(result)
                    return
                } else {
                    attempt++
                    if (attempt < maxAttempts) {
                        showHint("⏳ Повтор ${attempt + 1}/$maxAttempts...")
                        delay(RECONNECT_BASE_MS * attempt)
                    } else {
                        transitionTo(AppState.RESULTS)
                        showHint("❌ Ошибка моделирования (код ${response.code()})")
                    }
                }
            } catch (e: Exception) {
                attempt++
                if (attempt < maxAttempts) {
                    showHint("⏳ Сбой, повтор ${attempt + 1}/$maxAttempts через ${attempt}с...")
                    delay(RECONNECT_BASE_MS * attempt)
                } else {
                    transitionTo(AppState.RESULTS)
                    showHint("❌ Не удалось подключиться: ${e.message}")
                }
            }
        }
    }

    private fun showModelingResults(result: ModelingResponse?) {
        if (result == null || result.status != "SUCCESS") {
            showHint("⚠️ Сервер вернул неожиданный ответ")
            return
        }
        val options = result.options
        if (options.isNullOrEmpty()) {
            showHint("⚠️ ИИ не смог сгенерировать варианты. Добавьте больше опорных точек.")
            return
        }

        // Формируем читаемый отчёт
        val sb = StringBuilder()
        sb.appendLine("✅ Моделирование завершено! Вариантов: ${options.size}")
        sb.appendLine()
        options.forEachIndexed { idx, opt ->
            val badge = when {
                opt.safety_score >= 80 -> "🟢"
                opt.safety_score >= 55 -> "🟡"
                else -> "🔴"
            }
            sb.appendLine("$badge [${idx + 1}] ${opt.variant_name}")
            sb.appendLine("   Надёжность: ${opt.safety_score}% | ${opt.material_info}")
            opt.stats?.let { sb.appendLine("   Балок: ${it.total_beams} | ~${it.total_weight_kg} кг") }
            opt.ai_critique?.firstOrNull()?.let { sb.appendLine("   ИИ: $it") }
            sb.appendLine()
        }

        // Лучший вариант — первый (сервер сортирует по safety_score)
        val best = options.first()
        sb.appendLine("⭐ Рекомендован: «${best.variant_name}»")

        showHint(sb.toString().trim())
    }

    // ── Вспомогательные ──────────────────────────────────────────────────────

    private fun showHint(text: String) {
        tvAiHint.text = text
    }

    private fun startBlinkAnimation(view: View) {
        val anim = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 700; repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        view.startAnimation(anim)
    }

    private fun stopAnimation(view: View) {
        view.clearAnimation()
    }
}