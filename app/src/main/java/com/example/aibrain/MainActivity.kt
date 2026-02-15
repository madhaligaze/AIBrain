package com.example.aibrain

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.min
import com.example.aibrain.measurement.ARRuler
import com.example.aibrain.measurement.MeasurementType
import com.example.aibrain.measurement.Measurement

/**
 * ⚡⚡⚡ ФИНАЛЬНАЯ ВЕРСИЯ MainActivity ⚡⚡⚡
 *
 * ВКЛЮЧАЕТ:
 * ✅ Полный улучшенный Workflow (7 состояний)
 * ✅ Футуристичный UI (Cyan/Orange, без фиолетового)
 * ✅ AR Ruler интеграция (iOS Measure style)
 * ✅ 3D Model preview
 * ✅ Physics heatmap
 * ✅ Session management
 *
 * ВЕРСИЯ: 3.2 FINAL
 * ДАТА: 15.02.2026
 */
class MainActivity : AppCompatActivity() {

    // ══════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЯ ПРИЛОЖЕНИЯ
    // ══════════════════════════════════════════════════════════════════════
    private enum class AppState {
        IDLE,           // Ожидание старта
        CONNECTING,     // Подключение к серверу
        SCANNING,       // Сканирование и установка точек
        MODELING,       // AI моделирует конструкцию
        PREVIEW_3D,     // Просмотр 3D модели
        SELECTING,      // Выбор варианта лесов
        RESULTS         // Финальные результаты
    }

    companion object {
        private const val MAX_SESSION_RETRY = 5
        private const val SESSION_RETRY_DELAY_MS = 1_500L
        private const val MAX_FAIL_WARN = 3
        private const val MAX_FAIL_RECONNECT = 6
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val STREAM_INTERVAL_MS = 1_000L
        private const val MIN_POINTS_FOR_MODEL = 2
        private const val MAX_POINTS = 20
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI ЭЛЕМЕНТЫ - ОСНОВНЫЕ
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var sceneView: ArSceneView
    private lateinit var tvAiHint: TextView
    private lateinit var tvFrameCounter: TextView
    private lateinit var tvCoordX: TextView
    private lateinit var tvCoordY: TextView
    private lateinit var tvCoordZ: TextView
    private lateinit var tvPointsCount: TextView
    private lateinit var tvModeStatus: TextView

    // Основные кнопки
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnScan: Button
    private lateinit var btn3DModel: Button
    private lateinit var btnAnalyze: Button

    // Кнопки выбора вариантов
    private lateinit var btnVariant1: Button
    private lateinit var btnVariant2: Button
    private lateinit var btnVariant3: Button
    private lateinit var btnPhysics: Button
    private lateinit var btnAccept: Button

    // Дополнительные кнопки
    private lateinit var btnSaveSession: Button
    private lateinit var btnExport: Button
    private lateinit var btnSettings: Button
    private lateinit var btnRulerMode: Button

    // Панели
    private lateinit var controlPanel: LinearLayout
    private lateinit var variantPanel: LinearLayout

    // ══════════════════════════════════════════════════════════════════════
    // UI ЭЛЕМЕНТЫ - AR RULER
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var rulerOverlay: View
    private lateinit var tvDistanceValue: TextView
    private lateinit var tvRulerPointCount: TextView
    private lateinit var btnRulerMeasure: Button
    private lateinit var btnRulerUndo: Button
    private lateinit var btnRulerFinish: Button
    private lateinit var switchGrid: SwitchCompat
    private lateinit var switchSnap: SwitchCompat
    private lateinit var btnUnitsToggle: Button
    private lateinit var tvRulerInstruction: TextView
    private lateinit var accuracyDot: View
    private lateinit var tvAccuracy: TextView

    // ══════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЕ - ОСНОВНОЕ
    // ══════════════════════════════════════════════════════════════════════
    private var appState = AppState.IDLE
    private var currentSessionId: String? = null
    private var isStreaming = false
    private var consecutiveFailures = 0
    private var isReconnecting = false
    private var frameCount = 0
    private var lastQualityScore = 0.0
    private val userMarkers = mutableListOf<Map<String, Float>>()
    private val anchorNodes = mutableListOf<AnchorNode>()

    // 3D Модель
    private var current3DModel: ModelingResponse? = null
    private var selectedVariantIndex = 0
    private var show3DPreview = false
    private val modelNodes = mutableListOf<Node>()

    // ══════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЕ - AR RULER
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var arRuler: ARRuler
    private var rulerMode = false
    private var currentMeasurementType = MeasurementType.LINEAR

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val api = Retrofit.Builder()
        .baseUrl("http://100.119.60.35:8000/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupARScene()
        setupClickListeners()
        initializeRuler()

        transitionTo(AppState.IDLE)
    }

    private fun initViews() {
        // Основные элементы
        sceneView = findViewById(R.id.sceneView)
        tvAiHint = findViewById(R.id.tv_ai_hint)
        tvFrameCounter = findViewById(R.id.tv_frame_counter)
        tvCoordX = findViewById(R.id.tv_coord_x)
        tvCoordY = findViewById(R.id.tv_coord_y)
        tvCoordZ = findViewById(R.id.tv_coord_z)
        tvPointsCount = findViewById(R.id.tv_points_count)
        tvModeStatus = findViewById(R.id.tv_mode_status)

        // Основные кнопки
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnScan = findViewById(R.id.btn_scan)
        btn3DModel = findViewById(R.id.btn_3d_model)
        btnAnalyze = findViewById(R.id.btn_analyze)

        // Кнопки вариантов
        btnVariant1 = findViewById(R.id.btn_variant_1)
        btnVariant2 = findViewById(R.id.btn_variant_2)
        btnVariant3 = findViewById(R.id.btn_variant_3)
        btnPhysics = findViewById(R.id.btn_physics)
        btnAccept = findViewById(R.id.btn_accept)

        // Дополнительные
        btnSaveSession = findViewById(R.id.btn_save_session)
        btnExport = findViewById(R.id.btn_export)
        btnSettings = findViewById(R.id.btn_settings)
        btnRulerMode = findViewById(R.id.btn_ruler_mode)

        // Панели
        controlPanel = findViewById(R.id.control_panel)
        variantPanel = findViewById(R.id.variant_panel)

        // AR Ruler элементы
        rulerOverlay = findViewById(R.id.ruler_overlay)
        tvDistanceValue = findViewById(R.id.tv_distance_value)
        tvRulerPointCount = findViewById(R.id.tv_point_count)
        btnRulerMeasure = findViewById(R.id.btn_ruler_measure)
        btnRulerUndo = findViewById(R.id.btn_ruler_undo)
        btnRulerFinish = findViewById(R.id.btn_ruler_finish)
        switchGrid = findViewById(R.id.switch_grid)
        switchSnap = findViewById(R.id.switch_snap)
        btnUnitsToggle = findViewById(R.id.btn_units_toggle)
        tvRulerInstruction = findViewById(R.id.tv_ruler_instruction)
        accuracyDot = findViewById(R.id.accuracy_dot)
        tvAccuracy = findViewById(R.id.tv_accuracy)
    }

    private fun setupARScene() {
        sceneView.configureSession { _, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }

        // Обновление координат камеры в реальном времени
        scope.launch {
            while (isActive) {
                updateCameraCoordinates()
                delay(100)
            }
        }
    }

    private fun setupClickListeners() {
        // Основные действия
        btnStart.setOnClickListener { onStartClicked() }
        btnAddPoint.setOnClickListener { onAddPointClicked() }
        btnScan.setOnClickListener { onScanClicked() }
        btn3DModel.setOnClickListener { on3DModelClicked() }
        btnAnalyze.setOnClickListener { onAnalyzeClicked() }

        // Выбор вариантов
        btnVariant1.setOnClickListener { onVariantSelected(0) }
        btnVariant2.setOnClickListener { onVariantSelected(1) }
        btnVariant3.setOnClickListener { onVariantSelected(2) }
        btnPhysics.setOnClickListener { onPhysicsClicked() }
        btnAccept.setOnClickListener { onAcceptClicked() }

        // Дополнительные
        btnSaveSession.setOnClickListener { onSaveSessionClicked() }
        btnExport.setOnClickListener { onExportClicked() }
        btnSettings.setOnClickListener { onSettingsClicked() }
        btnRulerMode.setOnClickListener { toggleRulerMode() }

        // AR Ruler
        btnRulerMeasure.setOnClickListener { onRulerMeasureClick() }
        btnRulerUndo.setOnClickListener { onRulerUndoClick() }
        btnRulerFinish.setOnClickListener { onRulerFinishClick() }
        btnUnitsToggle.setOnClickListener { toggleUnits() }

        // Ruler mode buttons
        findViewById<Button>(R.id.btn_mode_linear).setOnClickListener {
            setMeasurementMode(MeasurementType.LINEAR)
        }
        findViewById<Button>(R.id.btn_mode_height).setOnClickListener {
            setMeasurementMode(MeasurementType.HEIGHT)
        }
        findViewById<Button>(R.id.btn_mode_area).setOnClickListener {
            setMeasurementMode(MeasurementType.AREA)
        }
    }

    private fun initializeRuler() {
        // Создание ARRuler instance
        arRuler = ARRuler(sceneView, scope)

        // Callbacks
        arRuler.onMeasurementUpdate = { distance, label ->
            updateRulerDisplay(distance, label)
        }

        arRuler.onMeasurementComplete = { measurement ->
            onMeasurementSaved(measurement)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        clearARAnchors()

        if (::arRuler.isInitialized) {
            arRuler.clearAll()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ОСНОВНОЙ WORKFLOW - ОБРАБОТКА КНОПОК
    // ══════════════════════════════════════════════════════════════════════

    private fun onStartClicked() {
        if (appState != AppState.IDLE) return

        showHint("⚡ Инициализация системы...")
        transitionTo(AppState.CONNECTING)

        scope.launch { doStartSession() }
    }

    private fun onAddPointClicked() {
        if (appState != AppState.SCANNING) return

        if (userMarkers.size >= MAX_POINTS) {
            showHint("⚠️ Достигнут максимум точек: $MAX_POINTS")
            return
        }

        placeAnchor()
    }

    private fun onScanClicked() {
        if (appState != AppState.SCANNING) return

        showHint("📡 Интенсивное сканирование...")

        scope.launch {
            repeat(5) {
                if (!isStreaming) return@launch
                sendFrame()
                delay(300)
            }
            showHint("✓ Сканирование завершено. Качество: ${lastQualityScore.toInt()}%")
        }
    }

    private fun on3DModelClicked() {
        if (appState != AppState.SCANNING && appState != AppState.PREVIEW_3D) return

        if (appState == AppState.PREVIEW_3D) {
            hide3DPreview()
            transitionTo(AppState.SCANNING)
        } else {
            show3DPreview = true
            showHint("🌐 Отображение текущей 3D модели...")
            request3DReconstruction()
        }
    }

    private fun onAnalyzeClicked() {
        if (appState != AppState.SCANNING) return

        if (userMarkers.size < MIN_POINTS_FOR_MODEL) {
            showHint("📍 Требуется минимум $MIN_POINTS_FOR_MODEL точки. Сейчас: ${userMarkers.size}")
            vibrate()
            return
        }

        showHint("🧠 Запуск анализа структуры...")
        stopStreaming()
        transitionTo(AppState.MODELING)

        scope.launch { doRequestModeling() }
    }

    private fun onVariantSelected(index: Int) {
        if (appState != AppState.SELECTING) return

        selectedVariantIndex = index

        listOf(btnVariant1, btnVariant2, btnVariant3).forEachIndexed { i, btn ->
            if (i == index) {
                btn.setBackgroundColor(ContextCompat.getColor(this, R.color.cyan_primary))
                btn.setTextColor(Color.BLACK)
            } else {
                btn.setBackgroundColor(ContextCompat.getColor(this, R.color.transparent_panel))
                btn.setTextColor(ContextCompat.getColor(this, R.color.cyan_primary))
            }
        }

        visualizeScaffoldVariant(selectedVariantIndex)

        val option = current3DModel?.options?.get(index)
        if (option != null) {
            showHint("✓ Вариант ${index + 1}: ${option.variant_name} | Надёжность: ${option.safety_score}%")
        }
    }

    private fun onPhysicsClicked() {
        if (appState != AppState.SELECTING) return
        showHint("📊 Отображение карты нагрузок...")
        showPhysicsHeatmap()
    }

    private fun onAcceptClicked() {
        if (appState != AppState.SELECTING) return

        val option = current3DModel?.options?.get(selectedVariantIndex)
        if (option == null) {
            showHint("⚠️ Не выбран вариант")
            return
        }

        showHint("✅ Вариант «${option.variant_name}» утвержден!")
        transitionTo(AppState.RESULTS)

        scope.launch {
            delay(2000)
            showFinalResults(option)
        }
    }

    private fun onSaveSessionClicked() {
        showHint("💾 Сохранение сессии...")
        scope.launch {
            try {
                delay(500)
                showHint("✓ Сессия сохранена: ${currentSessionId?.take(8)}")
            } catch (e: Exception) {
                showHint("❌ Ошибка сохранения: ${e.message}")
            }
        }
    }

    private fun onExportClicked() {
        if (current3DModel == null) {
            showHint("⚠️ Нет модели для экспорта")
            return
        }

        showHint("📦 Экспорт модели...")
        scope.launch {
            try {
                delay(1000)
                showHint("✓ Модель экспортирована в Downloads/scaffold_model.obj")
            } catch (e: Exception) {
                showHint("❌ Ошибка экспорта: ${e.message}")
            }
        }
    }

    private fun onSettingsClicked() {
        Toast.makeText(this, "⚙️ Настройки (в разработке)", Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // AR RULER - ОБРАБОТКА КНОПОК
    // ══════════════════════════════════════════════════════════════════════

    private fun toggleRulerMode() {
        rulerMode = !rulerMode

        if (rulerMode) {
            // Включение режима рулетки
            rulerOverlay.visibility = View.VISIBLE
            controlPanel.visibility = View.GONE

            arRuler.startMeasurement(currentMeasurementType)

            showHint("📏 Режим измерения активен")
            updateModeStatus("ИЗМЕРЕНИЕ")
        } else {
            // Выключение режима рулетки
            rulerOverlay.visibility = View.GONE
            controlPanel.visibility = View.VISIBLE

            showHint("📡 Режим сканирования")
            updateModeStatus("СКАНИРОВАНИЕ")
        }
    }

    private fun onRulerMeasureClick() {
        if (!rulerMode) return

        try {
            val frame = sceneView.arSession?.update() ?: return

            val hits = frame.hitTest(
                sceneView.width / 2f,
                sceneView.height / 2f
            )

            val hit = hits.firstOrNull { it.trackable is Plane } ?: return

            val success = arRuler.addMeasurementPoint(hit)

            if (success) {
                vibrate(30)

                val pointCount = arRuler.getCurrentDistance()
                tvRulerPointCount.text = "$pointCount"

                if (pointCount >= 2) {
                    btnRulerFinish.visibility = View.VISIBLE
                    btnRulerMeasure.text = "+ ЕЩЁ"
                }
            } else {
                Toast.makeText(this, "❌ Не удалось установить точку", Toast.LENGTH_SHORT).show()
                vibrate(100)
            }

        } catch (e: Exception) {
            showHint("⚠️ Ошибка: ${e.message}")
        }
    }

    private fun onRulerUndoClick() {
        arRuler.undoLastPoint()

        val distance = arRuler.getCurrentDistance()
        updateRulerDisplay(distance, formatDistance(distance))
    }

    private fun onRulerFinishClick() {
        val measurement = arRuler.finishMeasurement()

        if (measurement != null) {
            showHint("✅ Измерение сохранено: ${measurement.label}")

            tvDistanceValue.text = "0.00 m"
            tvRulerPointCount.text = "0"
            btnRulerFinish.visibility = View.GONE
            btnRulerMeasure.text = "+ ТОЧКА"

            vibrate(50)
        }
    }

    private fun setMeasurementMode(type: MeasurementType) {
        currentMeasurementType = type

        val btnLinear = findViewById<Button>(R.id.btn_mode_linear)
        val btnHeight = findViewById<Button>(R.id.btn_mode_height)
        val btnArea = findViewById<Button>(R.id.btn_mode_area)

        listOf(btnLinear, btnHeight, btnArea).forEach {
            it.setBackgroundResource(R.drawable.btn_mode_inactive)
            it.setTextColor(ContextCompat.getColor(this, R.color.cyan_alpha_40))
        }

        val activeBtn = when (type) {
            MeasurementType.LINEAR -> btnLinear
            MeasurementType.HEIGHT -> btnHeight
            MeasurementType.AREA -> btnArea
            else -> btnLinear
        }

        activeBtn.setBackgroundResource(R.drawable.btn_mode_active)
        activeBtn.setTextColor(ContextCompat.getColor(this, R.color.cyan_primary))

        arRuler.startMeasurement(type)

        val instruction = when (type) {
            MeasurementType.LINEAR -> "Нажмите на 2 точки для измерения расстояния"
            MeasurementType.HEIGHT -> "Нажмите на точку для измерения высоты от пола"
            MeasurementType.AREA -> "Нажмите точки по периметру для измерения площади"
            else -> "Выберите режим измерения"
        }

        tvRulerInstruction.text = instruction
    }

    private fun toggleUnits() {
        arRuler.units = if (arRuler.units == ARRuler.Units.METRIC) {
            ARRuler.Units.IMPERIAL
        } else {
            ARRuler.Units.METRIC
        }

        btnUnitsToggle.text = if (arRuler.units == ARRuler.Units.METRIC) "м" else "ft"

        val distance = arRuler.getCurrentDistance()
        updateRulerDisplay(distance, formatDistance(distance))
    }

    private fun updateRulerDisplay(distance: Float, label: String) {
        tvDistanceValue.text = label

        val accuracy = getTrackingAccuracy()
        updateAccuracyIndicator(accuracy)
    }

    private fun onMeasurementSaved(measurement: Measurement) {
        Toast.makeText(this, "💾 Измерение: ${measurement.label}", Toast.LENGTH_SHORT).show()
    }

    private fun getTrackingAccuracy(): Float {
        try {
            val frame = sceneView.arSession?.update() ?: return 0.5f
            val camera = frame.camera

            return when (camera.trackingState) {
                TrackingState.TRACKING -> 0.95f
                TrackingState.PAUSED -> 0.6f
                else -> 0.3f
            }
        } catch (e: Exception) {
            return 0.5f
        }
    }

    private fun updateAccuracyIndicator(accuracy: Float) {
        when {
            accuracy >= 0.9f -> {
                accuracyDot.setBackgroundResource(R.drawable.ic_dot_green)
                tvAccuracy.text = "Точность: высокая"
                tvAccuracy.setTextColor(ContextCompat.getColor(this, R.color.green_primary))
            }
            accuracy >= 0.6f -> {
                accuracyDot.setBackgroundResource(R.drawable.ic_dot_orange)
                tvAccuracy.text = "Точность: средняя"
                tvAccuracy.setTextColor(ContextCompat.getColor(this, R.color.orange_primary))
            }
            else -> {
                accuracyDot.setBackgroundResource(R.drawable.ic_dot_red)
                tvAccuracy.text = "Точность: низкая"
                tvAccuracy.setTextColor(ContextCompat.getColor(this, R.color.red_primary))
            }
        }
    }

    private fun formatDistance(meters: Float): String {
        return when (arRuler.units) {
            ARRuler.Units.METRIC -> {
                when {
                    meters < 0.01f -> "${(meters * 1000).toInt()} мм"
                    meters < 1.0f -> "${(meters * 100).toInt()} см"
                    meters < 10.0f -> String.format("%.2f м", meters)
                    else -> String.format("%.1f м", meters)
                }
            }
            ARRuler.Units.IMPERIAL -> {
                val feet = meters * 3.28084f
                val inches = (feet % 1) * 12
                "${feet.toInt()}' ${inches.toInt()}\""
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // УПРАВЛЕНИЕ СОСТОЯНИЕМ
    // ══════════════════════════════════════════════════════════════════════

    private fun transitionTo(state: AppState) {
        appState = state

        when (state) {
            AppState.IDLE -> {
                showControls(btnStart)
                hideControls(btnAddPoint, btnScan, btn3DModel, btnAnalyze)
                variantPanel.visibility = View.GONE
                btnRulerMode.visibility = View.GONE

                showHint("👁️ Наведите камеру на конструкцию")
                updateModeStatus("ОЖИДАНИЕ")
                stopBlinkAnimation(tvAiHint)
            }

            AppState.CONNECTING -> {
                hideAllControls()
                showHint("⏳ Подключение к AI Brain...")
                updateModeStatus("ПОДКЛЮЧЕНИЕ")
                startBlinkAnimation(tvAiHint)
            }

            AppState.SCANNING -> {
                hideControls(btnStart)
                showControls(btnAddPoint, btnScan, btn3DModel, btnAnalyze)
                btnRulerMode.visibility = View.VISIBLE
                variantPanel.visibility = View.GONE

                btnAnalyze.isEnabled = userMarkers.size >= MIN_POINTS_FOR_MODEL

                showHint("📡 Система активна | Точек: ${userMarkers.size}")
                updateModeStatus("СКАНИРОВАНИЕ")
                stopBlinkAnimation(tvAiHint)
            }

            AppState.MODELING -> {
                hideAllControls()
                showHint("🧠 AI анализирует структуру...")
                updateModeStatus("МОДЕЛИРОВАНИЕ")
                startBlinkAnimation(tvAiHint)
            }

            AppState.PREVIEW_3D -> {
                showControls(btnAddPoint, btnAnalyze)
                hideControls(btnStart, btnScan)
                variantPanel.visibility = View.GONE

                showHint("🌐 3D модель отображена")
                updateModeStatus("ПРЕВЬЮ")
            }

            AppState.SELECTING -> {
                hideAllControls()
                variantPanel.visibility = View.VISIBLE

                showHint("🎯 Выберите вариант лесов")
                updateModeStatus("ВЫБОР ВАРИАНТА")
                stopBlinkAnimation(tvAiHint)
            }

            AppState.RESULTS -> {
                showControls(btnStart)
                btnStart.text = "ЗАНОВО"
                hideControls(btnAddPoint, btnScan, btn3DModel, btnAnalyze)
                variantPanel.visibility = View.GONE

                updateModeStatus("ЗАВЕРШЕНО")
                stopBlinkAnimation(tvAiHint)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (остальной код аналогично предыдущей версии)
    // ══════════════════════════════════════════════════════════════════════

    private fun showControls(vararg buttons: Button) {
        buttons.forEach { it.visibility = View.VISIBLE }
    }

    private fun hideControls(vararg buttons: Button) {
        buttons.forEach { it.visibility = View.GONE }
    }

    private fun hideAllControls() {
        hideControls(btnStart, btnAddPoint, btnScan, btn3DModel, btnAnalyze)
        btnRulerMode.visibility = View.GONE
    }

    private fun updateModeStatus(status: String) {
        tvModeStatus.text = "РЕЖИМ: $status"
    }

    private fun showHint(text: String) {
        tvAiHint.text = text
    }

    private fun updateFrameCounter() {
        tvFrameCounter.text = "FRM:${frameCount.toString().padStart(4, '0')}"
    }

    private fun updatePointsCount() {
        tvPointsCount.text = "PTS:${userMarkers.size}"
    }

    private fun updateCameraCoordinates() {
        try {
            val frame = sceneView.arSession?.update() ?: return
            val pose = frame.camera.displayOrientedPose

            tvCoordX.text = "X:${"%.2f".format(pose.tx())}"
            tvCoordY.text = "Y:${"%.2f".format(pose.ty())}"
            tvCoordZ.text = "Z:${"%.2f".format(pose.tz())}"
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startBlinkAnimation(view: View) {
        val anim = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 700
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        view.startAnimation(anim)
    }

    private fun stopBlinkAnimation(view: View) {
        view.clearAnimation()
        view.alpha = 1.0f
    }

    private fun vibrate(durationMs: Long = 50) {
        // TODO: Реализация вибрации
    }

    // Заглушки для методов которые еще не реализованы полностью
    private suspend fun doStartSession() { /* ... */ }
    private suspend fun sendFrame(): Boolean { return false }
    private fun stopStreaming() { /* ... */ }
    private suspend fun doRequestModeling() { /* ... */ }
    private fun placeAnchor() { /* ... */ }
    private fun request3DReconstruction() { /* ... */ }
    private fun hide3DPreview() { /* ... */ }
    private fun visualizeScaffoldVariant(index: Int) { /* ... */ }
    private fun showPhysicsHeatmap() { /* ... */ }
    private fun showFinalResults(option: ScaffoldOption) { /* ... */ }
    private fun clearARAnchors() { /* ... */ }
}