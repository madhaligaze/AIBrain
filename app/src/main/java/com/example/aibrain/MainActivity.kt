package com.example.aibrain

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color as SceneColor
import com.example.aibrain.assets.ModelAssets
import com.example.aibrain.managers.ARSessionManager
import com.example.aibrain.scene.PhysicsAnimator
import com.example.aibrain.scene.SceneBuilder
import com.example.aibrain.scene.LightingSetup
import com.example.aibrain.scene.LayerGlbManager
import com.example.aibrain.network.NetworkStateController
import com.google.gson.Gson
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.sceneform.ArSceneView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import com.example.aibrain.measurement.ARRuler
import com.example.aibrain.offline.OfflineQueue
import com.example.aibrain.diagnostics.CrashReporter
import com.example.aibrain.util.HeavyOps
import com.example.aibrain.measurement.MeasurementType
import com.example.aibrain.measurement.Measurement
import com.example.aibrain.visualization.VoxelData
import com.example.aibrain.visualization.VoxelVisualizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private fun getDefaultVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }


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
        private const val AUTO_RELOAD_COOLDOWN_MS: Long = 12_000L
        private const val MIN_POINTS_FOR_MODEL = 2
        private const val MAX_POINTS = 20
        private const val PREFS_NAME = "app_settings"
        private const val PREF_SERVER_BASE_URL = "server_base_url"
        private const val KEY_SESSION_HISTORY = "session_history_json"
        private const val PREF_CAMERA_SWAP_UV = "camera_swap_uv"
        private const val DEPTH_SEND_EVERY = 5
        private const val VOXEL_AUTO_REFRESH_MS = 30_000L
        private const val MIN_RELEASE_API_LEVEL = Build.VERSION_CODES.Q
        private const val MIN_RELEASE_RAM_GB = 6.0
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI ЭЛЕМЕНТЫ - ОСНОВНЫЕ
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var sceneView: ArSceneView
    private var arCoreInstallRequested: Boolean = false
    private lateinit var arManager: ARSessionManager
    private lateinit var tvAiHint: TextView
    private lateinit var tvFrameCounter: TextView
    private lateinit var tvCoordX: TextView
    private lateinit var tvCoordY: TextView
    private lateinit var tvCoordZ: TextView
    private lateinit var tvPointsCount: TextView
    private lateinit var tvModeStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var tvSystemStatus: TextView
    private lateinit var connectionDot: View
    private lateinit var pbQuality: ProgressBar
    private lateinit var tvQuality: TextView
    private lateinit var pbReadiness: ProgressBar
    private lateinit var tvReadiness: TextView
    private lateinit var tvReadinessDetail: TextView
    private lateinit var tvAiCritique: TextView

    // Основные кнопки
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnScan: Button
    private lateinit var btn3DModel: Button
    private lateinit var btnAnalyze: Button

    // Варианты конструкции
    private lateinit var rvVariants: RecyclerView
    private lateinit var variantAdapter: VariantOptionAdapter
    private lateinit var btnPhysics: Button
    private lateinit var btnAccept: Button

    // Дополнительные кнопки
    private lateinit var btnSaveSession: Button
    private lateinit var btnExport: Button
    private lateinit var btnSettings: Button
    private lateinit var btnRulerMode: Button
    private var btnSendReportNow: Button? = null
    private lateinit var fabEyeOfAI: FloatingActionButton
    private lateinit var voxelLegend: LinearLayout
    private var tvFieldDiag: TextView? = null

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
    private var streamJob: Job? = null
    private var healthJob: Job? = null
    private var voxelPollJob: Job? = null
    private var lastConnectionDetail: String? = null
    private var consecutiveFailures = 0
    private var isReconnecting = false
    private var frameCount = 0
    private var lastQualityScore = 0.0
    private val qualityMinForAnalyze = 40
    private val hintHistory: ArrayDeque<String> = ArrayDeque()
    private val tutorialPrefs by lazy { getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE) }
    private val tutorialDoneKey = "tutorial_done_v1"
    private var tutorialOverlay: TutorialOverlay? = null

    // Hint ticker (queue instead of overwrite)
    private val hintQueue: ArrayDeque<String> = ArrayDeque()
    private var hintTickerJob: Job? = null

    // Results state
    private var lastAcceptedOption: ScaffoldOption? = null
    private var lastRevisionId: String? = null
    private val userMarkers = mutableListOf<PlacedAnchor>()
    private val anchorNodes = mutableListOf<AnchorNode>()
    private val anchorMarkerNodes: MutableMap<String, Node> = mutableMapOf()
    private var lightingSetup = false
    private var mainAnchorNode: AnchorNode? = null

    // 3D Модель
    private var current3DModel: ModelingResponse? = null
    private var selectedVariantIndex = 0
    private var show3DPreview = false
    private val modelNodes = mutableListOf<Node>()
    private lateinit var sceneBuilder: SceneBuilder
    private lateinit var physicsAnimator: PhysicsAnimator
    private lateinit var viewModel: StructureViewModel
    private lateinit var soundManager: SoundManager
    private lateinit var modeIndicator: LinearLayout
    private lateinit var modeIcon: TextView
    private lateinit var modeText: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnRedo: Button
    private var hasRedZones = false
    private lateinit var voxelVisualizer: VoxelVisualizer
    private var currentVoxelData: List<VoxelData>? = null
    private var eyeOfAIActive = false
    private var layerGlbManager: LayerGlbManager? = null
    private var exportedLayers: List<UiLayer> = emptyList()
    private val exportedLayerPaths: MutableMap<String, String> = mutableMapOf()
    private var loadedExportRevId: String? = null
    private var currentConnStatus: ConnectionStatus = ConnectionStatus.UNKNOWN
    @Volatile private var streamPendingTick: Boolean = false
    @Volatile private var streamImmediateNextTick: Boolean = false
    private var streamIntervalMs: Long = STREAM_INTERVAL_MS
    private var streamJpegQuality: Int = 72
    private var streamPointCap: Int = 300
    private var sendTimeEwmaMs: Double = 0.0
    private var lastSendMs: Long = 0L
    private var lastReadinessReady: Boolean? = null
    private var lastReadinessScore: Double? = null
    private var lastReadinessMetrics: ReadinessMetrics? = null
    private var nextStreamAttemptAtMs: Long = 0L
    private var exportPollJob: Job? = null
    private var readinessPollJob: Job? = null
    @Volatile private var exportPollInFlight: Boolean = false
    @Volatile private var pendingExportRevId: String? = null
    private val exportLoadMutex = Mutex()
    private val lockExportMutex = Mutex()
    private var exportPollFailures: Int = 0
    private var readinessPollFailures: Int = 0
    @Volatile private var autoReportInFlight: Boolean = false
    private var streamErrorStreak: Int = 0
    private var exportFailStreak: Int = 0
    private var nextExportPollAtMs: Long = 0L
    private var nextReadinessPollAtMs: Long = 0L
    private var exportNotReady409: Boolean = false
    private var lastAutoReloadAtMs: Long = 0L
    private var pendingAutoReloadRev: String? = null
    private var isUiActive: Boolean = false
    private var pollingSessionId: String? = null
    private var originAnchorNode: AnchorNode? = null
    private var streamSendJob: Job? = null
    private var isArSceneReady = false
    private var isRulerReady = false
    private var depthUnavailableStreak = 0
    private var depthHintShown = false
    private var arcoreHintShown = false
    private var depthFrameCounter = 0
    private var depthUnavailableWarned: Boolean = false
    private var lastDepthWarningMs: Long = 0L
    private var currentScanHints: List<String> = emptyList()
    private var scanHintsVisible = false
    private var autoVoxelRefreshJob: Job? = null
    private val gson by lazy { Gson() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startArIfReady()
        } else {
            showError("Нет доступа к камере. AR и рулетка недоступны.")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЕ - AR RULER
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var arRuler: ARRuler
    private var rulerMode = false
    private var currentMeasurementType = MeasurementType.LINEAR

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loadingDialog: AlertDialog? = null

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var api: ApiService
    private lateinit var offlineQueue: OfflineQueue
    private lateinit var crashReporter: CrashReporter
    private lateinit var netState: NetworkStateController
    @Volatile private var offlineFlushInFlight: Boolean = false

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════



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
        setContentView(R.layout.activity_main)

        settingsPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        rebuildApiClient()
        offlineQueue = OfflineQueue(this)
        crashReporter = CrashReporter(this)
        netState = NetworkStateController()
        if (crashReporter.readCrashMarkerSnippet() != null) {
            maybeAutoReport("crash_marker")
        }

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashReporter.recordException("UNCAUGHT:${t.name}", e) }
            runCatching {
                val body = buildString {
                    append(System.currentTimeMillis())
                    append("\n")
                    append(e.javaClass.name)
                    append(": ")
                    append(e.message ?: "")
                    append("\n")
                    append(android.util.Log.getStackTraceString(e))
                }
                File(filesDir, "crash_marker.txt").writeText(body.take(8192))
            }
            prevHandler?.uncaughtException(t, e)
        }

        initViews()
        setupClickListeners()

        if (!passesReleaseDeviceGate()) {
            showReleaseDeviceUnsupportedDialog()
            return
        }

        // Camera permission is required for ARCore / ruler.
        if (hasCameraPermission()) {
            startArIfReady()
        } else {
            requestCameraPermission()
        }
        sceneBuilder = SceneBuilder(sceneView)
        // physicsAnimator is initialized below, after soundManager is created

        showLoadingDialog("Загрузка моделей...")
        lifecycleScope.launch {
            val result = ModelAssets.loadAll(this@MainActivity)
            result.onSuccess {
                hideLoadingDialog()
                if (ModelAssets.isReady()) {
                    Log.d("ModelAssets", "✅ Все модели загружены успешно")
                } else {
                    Log.w("ModelAssets", "⚠️ 3D модели не найдены в assets/. Используется упрощенный режим.")
                    showError("3D модели не найдены. Используется упрощенный режим.")
                }
            }
            result.onFailure { error ->
                hideLoadingDialog()
                Log.e("ModelAssets", "❌ Ошибка загрузки моделей: ${error.message}")
                showError("Не удалось загрузить 3D модели. Используется упрощенный режим.")
            }
        }
        viewModel = StructureViewModel(api)
        soundManager = SoundManager(this)
        voxelVisualizer = VoxelVisualizer(sceneView, lifecycleScope)
        // Pass shared soundManager to avoid double SoundPool instance
        physicsAnimator = PhysicsAnimator(sceneView, sceneBuilder, this, soundManager)

        lifecycleScope.launch {
            viewModel.structureState.collect { state ->
                handleStructureState(state)
            }
        }


        lifecycleScope.launch {
            viewModel.editMode.collect { mode ->
                updateModeUI(mode)
            }
        }

        btnUndo.setOnClickListener { performUndo() }
        btnRedo.setOnClickListener { performRedo() }

        scope.launch {
            while (isActive) {
                updateUndoRedoButtons()
                delay(100)
            }
        }

        viewModel.saveSnapshot(sceneBuilder.getAllElements(), "Исходное состояние")

        // UI статуса соединения слушает ViewModel (единый источник правды)
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { st ->
                updateConnectionUi(st.status, st.detail)
            }
        }

        startHealthLoop()
        viewModel.setConnectionState(ConnectionStatus.UNKNOWN, "")
        maybeShowTutorial()

        transitionTo(AppState.IDLE)

        // Start hint ticker after views are ready
        startHintTicker()

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
        statusIndicator = findViewById(R.id.status_indicator)
        tvSystemStatus = findViewById(R.id.tv_system_status)

        // legacy dot (из аудита) - держим в синхроне
        connectionDot = findViewById(R.id.connection_dot)
        pbQuality = findViewById(R.id.pb_quality)
        tvQuality = findViewById(R.id.tv_quality)
        pbReadiness = findViewById(R.id.pb_readiness)
        tvReadiness = findViewById(R.id.tv_readiness)
        tvReadinessDetail = findViewById(R.id.tv_readiness_detail)
        tvAiCritique = findViewById(R.id.tv_ai_critique)

        // Основные кнопки
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnScan = findViewById(R.id.btn_scan)
        btn3DModel = findViewById(R.id.btn_3d_model)
        btnAnalyze = findViewById(R.id.btn_analyze)

        // Variants list
        rvVariants = findViewById(R.id.rv_variants)
        variantAdapter = VariantOptionAdapter { idx -> onVariantSelected(idx) }
        rvVariants.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rvVariants.adapter = variantAdapter
        btnPhysics = findViewById(R.id.btn_physics)
        btnAccept = findViewById(R.id.btn_accept)

        // Дополнительные
        btnSaveSession = findViewById(R.id.btn_save_session)
        btnExport = findViewById(R.id.btn_export)
        btnSettings = findViewById(R.id.btn_settings)
        btnRulerMode = findViewById(R.id.btn_ruler_mode)
        val reportBtnId = resources.getIdentifier("btn_send_report_now", "id", packageName)
        if (reportBtnId != 0) btnSendReportNow = findViewById(reportBtnId)
        val fieldDiagId = resources.getIdentifier("tv_field_diag", "id", packageName)
        if (fieldDiagId != 0) tvFieldDiag = findViewById(fieldDiagId)
        fabEyeOfAI = findViewById(R.id.fab_eye_of_ai)
        voxelLegend = findViewById(R.id.voxel_legend)

        // Панели
        controlPanel = findViewById(R.id.control_panel)
        variantPanel = findViewById(R.id.variant_panel)
        modeIndicator = findViewById(R.id.mode_indicator)
        modeIcon = findViewById(R.id.mode_icon)
        modeText = findViewById(R.id.mode_text)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)

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


    private fun startArIfReady() {
        if (!::sceneView.isInitialized) return
        if (!hasCameraPermission()) return
        if (!isArSceneReady) {
            isArSceneReady = setupARScene()
        }
        if (isArSceneReady && !isRulerReady) {
            initializeRuler()
            isRulerReady = true
        }
    }

    private fun setupARScene(): Boolean {
        // Конфигурацию ARCore Session делаем через ARSessionManager (без SceneView-специфичных API).

        if (!::arManager.isInitialized) {
            arManager = ARSessionManager(this, sceneView)
        }
        val sessionOk = arManager.setupSession()
        if (!sessionOk) {
            showError("ARCore сессия не запустилась. Убедитесь что ARCore обновлён и камера доступна.")
            return false
        }
        if (arManager.depthMode == Config.DepthMode.DISABLED && !depthHintShown) {
            depthHintShown = true
            showHint("ℹ️ Устройство не поддерживает Depth API — depth-данные недоступны")
        }


        if (mainAnchorNode == null) {
            mainAnchorNode = AnchorNode().also { anchor ->
                anchor.setParent(sceneView.scene)
                anchorNodes.add(anchor)
            }
        }

        sceneView.scene.addOnUpdateListener { _: com.google.ar.sceneform.FrameTime ->
            val anchor = mainAnchorNode
            if (anchor != null && !lightingSetup) {
                LightingSetup.setupLighting(sceneView, anchor)
                lightingSetup = true
            }
        }

        // Обновление координат камеры в реальном времени
        scope.launch {
            while (isActive) {
                updateCameraCoordinates()
                delay(100)
            }
        }
        return true
    }

    private fun setupClickListeners() {
        // Основные действия
        btnStart.setOnClickListener { onStartClicked() }
        btnAddPoint.setOnClickListener { onAddPointClicked() }
        btnAddPoint.setOnLongClickListener {
            if (originAnchorNode == null) {
                showHint("ℹ️ Origin ещё не задан. Долгое нажатие работает после установки первой опоры")
                true
            } else {
                confirmResetOrigin()
                true
            }
        }
        btnScan.setOnClickListener { onScanClicked() }
        btn3DModel.setOnClickListener { on3DModelClicked() }
        btnAnalyze.setOnClickListener { onAnalyzeClicked() }
        tvAiHint.setOnClickListener { showHintHistoryDialog() }

        btnPhysics.setOnClickListener { onPhysicsClicked() }
        btnAccept.setOnClickListener { onAcceptClicked() }

        // Дополнительные
        btnSaveSession.setOnClickListener { onSaveSessionClicked() }
        btnExport.setOnClickListener { onExportClicked() }
        btnSettings.setOnClickListener { onSettingsClicked() }
        btnSendReportNow?.setOnClickListener { onSendReportNowClicked() }
        fabEyeOfAI.setOnClickListener { toggleEyeOfAI() }
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
        stopStreaming()
        stopHealthLoop()
        stopAutoVoxelRefresh()
        voxelPollJob?.cancel()
        voxelPollJob = null
        scope.cancel()
        clearARAnchors()
        layerGlbManager?.clearNodes()

        if (::arRuler.isInitialized) {
            arRuler.clearAll()
        }
        if (::physicsAnimator.isInitialized) {
            physicsAnimator.release()
        }
        if (::soundManager.isInitialized) {
            soundManager.release()
        }
        if (::voxelVisualizer.isInitialized) {
            voxelVisualizer.hideVoxels()
        }

        hideLoadingDialog()
        ModelAssets.clear()
    }

    // ══════════════════════════════════════════════════════════════════════
    // ОСНОВНОЙ WORKFLOW - ОБРАБОТКА КНОПОК
    // ══════════════════════════════════════════════════════════════════════

    private fun onStartClicked() {
        if (appState == AppState.RESULTS) {
            // restart flow from RESULTS
            lastAcceptedOption = null
            lastRevisionId = null
            current3DModel = null
            selectedVariantIndex = 0
            show3DPreview = false
            clearARAnchors()
            layerGlbManager?.clearNodes()
            sceneBuilder.clearScene()
            transitionTo(AppState.IDLE)
        }
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
                sendFrameWith(80, 500)
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

        if (lastQualityScore >= 1.0 && lastQualityScore < qualityMinForAnalyze.toDouble()) {
            AlertDialog.Builder(this)
                .setTitle("Недостаточное качество")
                .setMessage("Quality=${lastQualityScore.toInt()}%. Нужно >= $qualityMinForAnalyze% для анализа. Продолжить всё равно?")
                .setPositiveButton("Продолжить") { _, _ ->
                    showHint("🧠 Запуск анализа структуры...")
                    stopStreaming()
                    transitionTo(AppState.MODELING)
                    val mJson = runCatching { if (::arRuler.isInitialized) arRuler.exportMeasurements() else "" }.getOrDefault("")
                    val mList = runCatching { if (::arRuler.isInitialized) arRuler.getSavedMeasurements().map { m -> MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), m.label, m.timestamp) } else emptyList<MeasurementConstraint>() }.getOrDefault(emptyList())
                    scope.launch { doRequestModeling(mJson, mList) }
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        showHint("🧠 Запуск анализа структуры...")
        stopStreaming()
        transitionTo(AppState.MODELING)

        val measurementsJson = runCatching { if (::arRuler.isInitialized) arRuler.exportMeasurements() else "" }.getOrDefault("")
        val measurementConstraints = runCatching { if (::arRuler.isInitialized) arRuler.getSavedMeasurements().map { m -> MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), m.label, m.timestamp) } else emptyList<MeasurementConstraint>() }.getOrDefault(emptyList())
        scope.launch { doRequestModeling(measurementsJson, measurementConstraints) }
    }

    private fun onVariantSelected(index: Int) {
        if (appState != AppState.SELECTING) return

        selectedVariantIndex = index
        variantAdapter.setSelected(index)

        visualizeScaffoldVariant(selectedVariantIndex)

        val option = current3DModel?.options?.get(index)
        if (option != null) {
            showHint("✓ Вариант ${index + 1}: ${option.variant_name} | Надёжность: ${option.safety_score}%")
            val critique = option.ai_critique?.joinToString("\n")?.trim().orEmpty()
            if (critique.isNotBlank()) {
                tvAiCritique.visibility = View.VISIBLE
                tvAiCritique.text = critique
            } else {
                tvAiCritique.visibility = View.GONE
            }

            scope.launch {
                sendLogEvent(
                    "VARIANT_SELECTED",
                    mapOf("variant_index" to index, "variant_name" to option.variant_name, "safety_score" to option.safety_score)
                )
            }
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
        lastAcceptedOption = option
        lastRevisionId = null

        scope.launch {
            sendLogEvent(
                "VARIANT_ACCEPTED",
                mapOf("variant_index" to selectedVariantIndex, "variant_name" to option.variant_name)
            )
            delay(300)
            currentSessionId?.let { sid ->
                doLockSession(sid, option)
                // Auto-refresh export layers after locking (if origin is set).
                loadExportLayersInternal(sid, showDialog = false, showOkHint = false)
            }
            delay(450)
            showResultsBottomSheet()
        }
    }


    private suspend fun doLockSession(sid: String, option: ScaffoldOption) {
        lockExportMutex.withLock {
        val measurementsJson = runCatching { if (::arRuler.isInitialized) arRuler.exportMeasurements() else "" }.getOrDefault("")
        val measurementConstraints = runCatching {
            if (::arRuler.isInitialized) {
                arRuler.getSavedMeasurements().map { m ->
                    MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), m.label, m.timestamp)
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        val lockPayload = LockPayload(
            session_id = sid,
            selected_variant = option.variant_name,
            measurements_json = measurementsJson.ifBlank { null },
            manual_measurements = measurementConstraints
        )
        try {
            val resp = api.lockSession(lockPayload)
            if (resp.isSuccessful && resp.body() != null) {
                lastRevisionId = resp.body()!!.rev_id
                return
            }
            offlineQueue.enqueueLock(sid, getCurrentServerUrl())
            crashReporter.recordError("lockSession", "HTTP ${resp.code()}")
        } catch (e: Exception) {
            offlineQueue.enqueueLock(sid, getCurrentServerUrl())
            crashReporter.recordException("lockSession", e)
        }

        runCatching { api.exportLatest(sid) }.onSuccess { exp ->
            val rev = exp.body()?.revision_id ?: exp.body()?.rev_id.orEmpty()
            if (rev.isNotBlank()) lastRevisionId = rev
        }
        }
    }

    private suspend fun loadExportLayersInternal(
        sid: String,
        showDialog: Boolean,
        showOkHint: Boolean
    ) {
        exportLoadMutex.withLock {
            try {
                val response = lockExportMutex.withLock { api.exportLatest(sid) }
                if (response.code() == 409) {
                    exportNotReady409 = true
                    // Quiet: export not ready yet.
                    return
                }
                if (!response.isSuccessful || response.body() == null) {
                    throw IllegalStateException("HTTP ${response.code()}")
                }
                exportNotReady409 = false
                val bundle = response.body()!!
                val rev = bundle.revision_id ?: bundle.rev_id.orEmpty()
                if (rev.isNotBlank() && loadedExportRevId != null && loadedExportRevId != rev) {
                    layerGlbManager?.clearAll()
                }
                if (rev.isNotBlank()) {
                    loadedExportRevId = rev
                    crashReporter.setLastExportRev(rev)
                    updateFieldDiag()
                }

                // Revision-aware caching for layers
                if (layerGlbManager == null) {
                    layerGlbManager = LayerGlbManager(this@MainActivity, sceneView, getCurrentServerUrl())
                }
                layerGlbManager?.setCurrentRevision(rev)

                val layers = bundle.ui?.layers.orEmpty()
                exportedLayers = layers
                exportedLayerPaths.clear()
                for (layer in layers) {
                    val path = layer.file?.glb?.path ?: layer.file?.path
                    if (!path.isNullOrBlank()) exportedLayerPaths[layer.id] = path
                }

                if (originAnchorNode == null) {
                    if (showDialog) showLayersDialog()
                    showHint("⚠️ Сначала поставь origin anchor (кнопка опоры), потом загружай слои")
                    return
                }

                layerGlbManager?.setLayersRoot(originAnchorNode)

                for (layer in layers) {
                    val path = exportedLayerPaths[layer.id]
                    if (path.isNullOrBlank()) continue
                    val key = "layer_visible_${layer.id}"
                    val def = layer.default_on ?: true
                    val wantVisible = settingsPrefs.getBoolean(key, def)
                    if (wantVisible) {
                        runCatching { layerGlbManager?.loadOrShowLayer(layer.id, path) }
                    } else {
                        layerGlbManager?.setVisible(layer.id, false)
                    }
                }

                if (showDialog) showLayersDialog()
                if (showOkHint) showHint("✓ Слои обновлены")
            } catch (e: Exception) {
                if (showOkHint) showHint("❌ Ошибка загрузки слоёв: ${e.message}")
            }
        }
    }

    private fun onSaveSessionClicked() {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            showHint("⚠️ Нет активной сессии")
            return
        }

        showHint("💾 Формирование export/latest...")
        scope.launch {
            try {
                val resp = lockExportMutex.withLock { api.exportLatest(sid) }
                if (!resp.isSuccessful || resp.body() == null) {
                    showError("export/latest: HTTP ${resp.code()}")
                    return@launch
                }

                val rev = resp.body()!!.revision_id ?: resp.body()!!.rev_id.orEmpty()
                sendLogEvent("SESSION_SAVED", mapOf("revision_id" to rev))
                showHint("✓ Сессия сохранена: ${rev.take(8)}")
            } catch (e: Exception) {
                showError("Ошибка сохранения: ${e.message}")
            }
        }
    }

    private fun onExportClicked() {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            showHint("⚠️ Нет активной сессии")
            return
        }

        showHint("📦 Загрузка export/latest...")
        scope.launch {
            loadExportLayersInternal(sid, showDialog = true, showOkHint = true)
        }
    }

    private fun showLayersDialog() {
        if (exportedLayers.isEmpty()) {
            showHint("⚠️ Нет доступных слоёв")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        exportedLayers.forEach { layer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = (resources.displayMetrics.density * 8).toInt()
                setPadding(0, p, 0, p)
            }
            val label = TextView(this).apply {
                text = layer.label ?: layer.id
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(this).apply {
                val key = "layer_visible_${layer.id}"
                val def = layer.default_on ?: true
                isChecked = settingsPrefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, checked ->
                    settingsPrefs.edit().putBoolean(key, checked).apply()
                    if (checked) {
                        val path = exportedLayerPaths[layer.id]
                        if (path.isNullOrBlank()) {
                            showHint("⚠️ Нет пути для слоя ${layer.id}")
                        } else {
                            scope.launch { runCatching { layerGlbManager?.loadOrShowLayer(layer.id, path) } }
                        }
                    } else {
                        layerGlbManager?.setVisible(layer.id, false)
                    }
                }
            }
            row.addView(label)
            row.addView(sw)
            container.addView(row)
            layerGlbManager?.setVisible(layer.id, sw.isChecked)
            if (sw.isChecked) {
                val path = exportedLayerPaths[layer.id]
                if (!path.isNullOrBlank()) {
                    scope.launch { runCatching { layerGlbManager?.loadOrShowLayer(layer.id, path) } }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Layers")
            .setView(container)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onSettingsClicked() {
        startActivity(android.content.Intent(this, SettingsActivity::class.java))
    }

    private fun onSendReportNowClicked() {
        val sid = currentSessionId
        scope.launch {
            val baseUrl = getCurrentServerUrl()
            val status = offlineQueue.getStatus(sid ?: "", baseUrl)
            val queued = mapOf(
                "anchors_queued" to status.anchorsQueued,
                "lock_queued" to status.lockQueued,
                "baseurl_mismatch" to status.mismatchedBaseUrlItems,
                "base_url" to baseUrl,
                "conn_status" to currentConnStatus.name,
            )
            val ok = crashReporter.sendNow(api, sid, buildClientStats(), queued)
            withContext(Dispatchers.Main) {
                if (ok) showHint("✅ Report sent") else showHint("⚠️ Report not sent")
                updateFieldDiag()
            }
        }
    }

    private fun updateFieldDiag() {
        val v = tvFieldDiag ?: return
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            v.text = "Q A0 L0 | REPORT -"
            return
        }
        val baseUrl = getCurrentServerUrl()
        val st = offlineQueue.getStatus(sid, baseUrl)
        val lastSent = crashReporter.getLastSentMs()
        val report = if (lastSent <= 0L) "-" else "${(System.currentTimeMillis() - lastSent) / 1000L}s"
        val exportState = if (exportNotReady409) "EXPORT 409" else "EXPORT OK"
        val mismatch = if (st.mismatchedBaseUrlItems > 0) " | BASEURL!" else ""
        v.text = "Q A${st.anchorsQueued} L${st.lockQueued} | ${exportState} | R ${report}${mismatch}"
    }

    private fun rebuildApiClient() {
        val baseUrl = getCurrentServerUrl()

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        api = retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)

        if (::viewModel.isInitialized) {
            viewModel.updateApiService(api)
        }
        if (::statusIndicator.isInitialized && ::tvSystemStatus.isInitialized) {
            viewModel.setConnectionState(ConnectionStatus.UNKNOWN, "${baseUrl}")
        }
    }

    private fun getCurrentServerUrl(): String {
        val saved = settingsPrefs.getString(PREF_SERVER_BASE_URL, null)
        return normalizeBaseUrl(saved) ?: BuildConfig.BACKEND_BASE_URL
    }

    private fun normalizeBaseUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null

        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "http://$value"
        }

        val withTrailingSlash = if (withScheme.endsWith('/')) withScheme else "$withScheme/"
        return withTrailingSlash.toHttpUrlOrNull()?.toString()
    }


    // ══════════════════════════════════════════════════════════════════════
    // СЕТЬ / СТАТУС СЕРВЕРА
    // ══════════════════════════════════════════════════════════════════════

    private fun updateConnectionUi(status: ConnectionStatus, detail: String? = null) {
        currentConnStatus = status
        lastConnectionDetail = detail

        val (dotRes, label) = when (status) {
            ConnectionStatus.ONLINE -> R.drawable.ic_status_dot_green to "SYSTEM ONLINE"
            ConnectionStatus.RECONNECTING -> R.drawable.ic_status_dot_orange to "RECONNECTING..."
            ConnectionStatus.OFFLINE -> R.drawable.ic_status_dot_red to "SYSTEM OFFLINE"
            ConnectionStatus.UNKNOWN -> R.drawable.ic_status_dot_cyan to "SYSTEM"
        }

        statusIndicator.setBackgroundResource(dotRes)
        connectionDot.setBackgroundResource(dotRes)
        tvSystemStatus.text = if (detail.isNullOrBlank()) label else (label + " | " + detail)
    }

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val base = getCurrentServerUrl().trimEnd('/')
                netState.waitIfNeeded("health")
                val ok = try {
                    val r = api.healthCheck()
                    r.isSuccessful
                } catch (_: Exception) {
                    false
                }
                netState.reportResult(
                    tag = "health",
                    success = ok,
                    baseMs = 15_000L,
                    maxMs = 90_000L,
                    errorDetail = if (ok) null else "health_failed"
                )
                withContext(Dispatchers.Main) {
                    val st = netState.getStatus()
                    currentConnStatus = st
                    viewModel.setConnectionState(st, base)
                }
                val sid = currentSessionId
                if (sid != null && netState.getStatus() == ConnectionStatus.ONLINE) {
                    scope.launch {
                        maybeFlushOfflineAndTelemetry(sid, base)
                    }
                }
            }
        }
    }

    private fun stopHealthLoop() {
        healthJob?.cancel()
        healthJob = null
    }

    private suspend fun maybeFlushOfflineAndTelemetry(sessionId: String, baseUrl: String) {
        // Shared backoff gate for flush operations.
        netState.waitIfNeeded("flush")

        // Quick check: if there's nothing to flush and no crash marker - skip.
        val q = runCatching { offlineQueue.getStatus(sessionId, baseUrl) }.getOrNull()
        val hasQueue = (q != null && (q.anchorsQueued > 0 || q.lockQueued > 0))
        val hasCrashMarker = (crashReporter.readCrashMarkerSnippet() != null)
        if (!hasQueue && !hasCrashMarker) {
            netState.reportResult(tag = "flush", success = true, baseMs = 20_000L, maxMs = 60_000L)
            return
        }

        flushOfflineAndTelemetry(sessionId, baseUrl)
    }

    private suspend fun flushOfflineAndTelemetry(sessionId: String, baseUrl: String) {
        if (offlineFlushInFlight) return
        offlineFlushInFlight = true
        try {
            // Avoid rare races: flush uses the same mutex as user Lock/Export actions.
            lockExportMutex.withLock {
                offlineQueue.flushForSession(api, sessionId, baseUrl)
            }
            crashReporter.sendNow(
                api = api,
                sessionId = sessionId,
                clientStats = buildClientStats(),
                queuedActions = buildQueuedActionsForReport(),
                trigger = "flush"
            )
            netState.reportResult(tag = "flush", success = true, baseMs = 20_000L, maxMs = 60_000L)
        } catch (e: Exception) {
            crashReporter.recordException("flushOfflineAndTelemetry", e)
            netState.reportResult(tag = "flush", success = false, baseMs = 5_000L, maxMs = 90_000L, errorDetail = e.message)
        } finally {
            offlineFlushInFlight = false
        }
    }

    private fun buildClientStats(): Map<String, Any> {
        val map = HashMap<String, Any>()
        map["state"] = appState.name
        map["frame_counter"] = frameCount
        map["is_streaming"] = isStreaming
        map["anchors_local"] = userMarkers.size
        map["last_revision_id"] = (lastRevisionId ?: "")
        return map
    }

    private fun buildQueuedActionsForReport(): Map<String, Any> {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) return emptyMap()
        val baseUrl = getCurrentServerUrl()
        val queued = HashMap<String, Any>()
        queued["offline_queue"] = offlineQueue.getStatus(sid, baseUrl)
        queued["base_url"] = baseUrl
        queued["conn_status"] = currentConnStatus.name
        return queued
    }

    private fun maybeAutoReport(trigger: String) {
        if (autoReportInFlight) return
        autoReportInFlight = true
        val sid = currentSessionId
        scope.launch(Dispatchers.IO) {
            try {
                crashReporter.maybeAutoSend(api, sid, buildClientStats(), buildQueuedActionsForReport(), trigger)
            } finally {
                autoReportInFlight = false
            }
        }
    }

    private suspend fun syncAnchorsToServer(allowEmpty: Boolean = false): Boolean {
        val sid = currentSessionId ?: return false
        val anchors = userMarkers.map { marker ->
            AnchorPointRequest(
                id = marker.id,
                kind = "support",
                position = listOf(marker.x, marker.y, marker.z),
                confidence = 1.0f
            )
        }
        if (anchors.isEmpty() && !allowEmpty) return true
        val payload = AnchorPayload(session_id = sid, anchors = anchors)
        return try {
            val resp = api.postAnchors(payload)
            if (resp.isSuccessful) {
                true
            } else {
                offlineQueue.enqueueAnchors(sid, getCurrentServerUrl(), anchors)
                crashReporter.recordError("postAnchors", "HTTP ${resp.code()}")
                false
            }
        } catch (e: Exception) {
            offlineQueue.enqueueAnchors(sid, getCurrentServerUrl(), anchors)
            crashReporter.recordException("postAnchors", e)
            false
        }
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
            val frame = sceneView.arFrame ?: return

            val hits = frame.hitTest(
                sceneView.width / 2f,
                sceneView.height / 2f
            )

            val hit = hits.firstOrNull { it.trackable is Plane } ?: return

            val success = arRuler.addMeasurementPoint(hit)

            if (success) {
                vibrate(30)

                val pointCount = arRuler.getPointCount()
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
            val frame = sceneView.arFrame ?: return 0.5f
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

    private fun updateQualityUI(score: Double?) {
        val v = score ?: return
        lastQualityScore = v
        val clamped = v.coerceIn(0.0, 100.0)
        pbQuality.progress = clamped.toInt()
        tvQuality.text = "${clamped.toInt()}%"
        val colorRes = when {
            clamped >= qualityMinForAnalyze -> android.R.color.holo_green_light
            clamped >= qualityMinForAnalyze * 0.6 -> android.R.color.holo_orange_light
            else -> android.R.color.holo_red_light
        }
        pbQuality.progressTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        if (!scanHintsVisible && clamped >= 1.0 && clamped < qualityMinForAnalyze.toDouble()) {
            showHint("⚠️ Качество: ${clamped.toInt()}%. Нужно >= $qualityMinForAnalyze%")
        }
    }

    private fun updateReadinessUI(
        ready: Boolean?,
        score: Double?,
        metrics: ReadinessMetrics?
    ) {
        if (originAnchorNode == null) {
            pbReadiness.progress = 0
            tvReadiness.text = "0%"
            tvReadinessDetail.text = "Place origin anchor"
            pbReadiness.progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_orange_light)
            )
            return
        }

        val s0 = (score ?: 0.0).coerceIn(0.0, 1.0)
        pbReadiness.progress = (s0 * 100.0).toInt()
        tvReadiness.text = "${(s0 * 100.0).toInt()}%"

        val obsPct = ((metrics?.observed_ratio ?: 0.0) * 100.0).toInt()
        val vdiv = metrics?.view_diversity ?: 0
        val minViews = metrics?.min_views_per_anchor ?: 0
        val vp = metrics?.viewpoints ?: 0
        val minVp = metrics?.min_viewpoints ?: 0

        val netSuffix = when (currentConnStatus) {
            ConnectionStatus.OFFLINE -> " | NET OFFLINE"
            ConnectionStatus.RECONNECTING -> " | NET RECONNECT"
            else -> ""
        }
        val pollSuffix = buildString {
            if (exportNotReady409) append(" | NO_EXPORT")
            if (exportPollFailures > 0) append(" | EXP RETRY")
            if (readinessPollFailures > 0) append(" | RDY RETRY")
        }

        tvReadinessDetail.text =
            "OBS ${obsPct}% | VDIV ${vdiv}/${minViews} | VP ${vp}/${minVp}" +
                (if (ready == true) " | READY" else "") +
                netSuffix +
                pollSuffix

        val colorRes =
            if (ready == true) android.R.color.holo_green_light else android.R.color.holo_orange_light
        pbReadiness.progressTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun maybeShowTutorial() {
        val done = tutorialPrefs.getBoolean(tutorialDoneKey, false)
        if (done) return

        tutorialOverlay = TutorialOverlay(
            activity = this,
            onDone = {
                tutorialPrefs.edit().putBoolean(tutorialDoneKey, true).apply()
                tutorialOverlay?.dismiss()
                tutorialOverlay = null
            }
        ).also { it.show() }
    }

    private fun confirmDeleteAnchor(anchorId: String) {
        AlertDialog.Builder(this)
            .setTitle("Удалить маркер?")
            .setMessage("Маркер будет удалён локально и отправлен на сервер при следующем sync.")
            .setPositiveButton("Удалить") { _, _ -> removeAnchorById(anchorId) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun removeAnchorById(anchorId: String) {
        val before = userMarkers.size
        userMarkers.removeAll { it.id == anchorId }
        if (before == userMarkers.size) return

        val markerNode = anchorMarkerNodes.remove(anchorId)
        runCatching { markerNode?.setParent(null) }

        val iterator = anchorNodes.iterator()
        while (iterator.hasNext()) {
            val anchorNode = iterator.next()
            if ((anchorNode.name ?: "") == anchorId) {
                runCatching { anchorNode.anchor?.detach() }
                anchorNode.setParent(null)
                iterator.remove()
                break
            }
        }

        if ((originAnchorNode?.name ?: "") == anchorId) {
            originAnchorNode = null
            layerGlbManager?.setLayersRoot(null)
            layerGlbManager?.clearAll()
            voxelVisualizer.setRootParent(null)
            currentVoxelData = null
            showHint("⚠️ Origin anchor удалён. Поставь новую опору, чтобы закрепить слои")
        }

        updatePointsCount()
        btnAnalyze.isEnabled = userMarkers.size >= MIN_POINTS_FOR_MODEL
        showHint("🗑 Маркер удалён")

        scope.launch {
            runCatching { syncAnchorsToServer() }
        }
    }

    private fun showHintHistoryDialog() {
        if (hintHistory.isEmpty()) return
        val items = hintHistory.toList().reversed().take(12)
        AlertDialog.Builder(this)
            .setTitle("AI Log")
            .setItems(items.toTypedArray(), null)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHint(text: String) {
        // enqueue instead of overwriting
        hintQueue.addLast(text)
        hintHistory.addLast(text)
        while (hintHistory.size > 10) hintHistory.removeFirst()
    }

    private fun startHintTicker() {
        if (hintTickerJob != null) return
        hintTickerJob = scope.launch {
            while (isActive) {
                val next = if (hintQueue.isNotEmpty()) hintQueue.removeFirst() else null
                if (next == null) {
                    delay(250)
                    continue
                }

                // fade out -> swap text -> fade in
                tvAiHint.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction {
                        tvAiHint.text = next
                        tvAiHint.animate().alpha(1f).setDuration(180).start()
                    }
                    .start()

                delay(2800)
            }
        }
    }

    private fun showResultsBottomSheet() {
        val opt = lastAcceptedOption ?: return
        val sid = currentSessionId.orEmpty()
        val rev = lastRevisionId.orEmpty()
        val critique = opt.ai_critique?.joinToString("\n")?.trim().orEmpty()

        val sheet = ResultsBottomSheet.newInstance(
            sessionId = sid,
            revisionId = rev,
            variantName = opt.variant_name,
            safetyScore = opt.safety_score,
            physicsStatus = opt.physics?.status ?: "UNKNOWN",
            critique = critique
        )
        sheet.listener = object : ResultsBottomSheet.Listener {
            override fun onExportRequested() {
                onExportClicked()
            }

            override fun onNewScanRequested() {
                // trigger restart flow
                transitionTo(AppState.IDLE)
                onStartClicked()
            }
        }
        sheet.show(supportFragmentManager, "results_sheet")
    }

    private fun updateFrameCounter() {
        tvFrameCounter.text = "FRM:${frameCount.toString().padStart(4, '0')}"
    }

    private fun updatePointsCount() {
        tvPointsCount.text = "PTS:${userMarkers.size}"
    }

    private fun updateCameraCoordinates() {
        try {
            val frame = sceneView.arFrame ?: return
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
        try {
            val vibrator = getDefaultVibrator() ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun rememberSessionInHistory(sessionId: String) {
        try {
            val raw = settingsPrefs.getString(KEY_SESSION_HISTORY, "") ?: ""
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
            val rec = JSONObject().apply {
                put("session_id", sessionId)
                put("timestamp_ms", System.currentTimeMillis())
            }
            val out = JSONArray()
            out.put(rec)
            for (i in 0 until minOf(arr.length(), 50)) out.put(arr.get(i))
            settingsPrefs.edit().putString(KEY_SESSION_HISTORY, out.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private suspend fun doStartSession() {
        val base = getCurrentServerUrl().trimEnd('/')
        viewModel.setConnectionState(ConnectionStatus.UNKNOWN, base)

        var lastError: String? = null
        for (attempt in 1..MAX_SESSION_RETRY) {
            try {
                // Быстрый health-check до создания сессии
                val healthOk = try {
                    api.healthCheck().isSuccessful
                } catch (_: Exception) {
                    false
                }

                if (!healthOk) {
                    viewModel.setConnectionState(ConnectionStatus.OFFLINE, base)
                    lastError = "HEALTH_FAIL"
                    delay(SESSION_RETRY_DELAY_MS * attempt)
                    continue
                }

                val response = api.startSession()
                if (response.isSuccessful && response.body() != null) {
                    val sessionId = response.body()!!.session_id
                    currentSessionId = sessionId
                    viewModel.setSessionId(sessionId)
                    rememberSessionInHistory(sessionId)

                    // Reset export/layer state for new session.
                    loadedExportRevId = null
                    exportNotReady409 = false
                    pendingAutoReloadRev = null
                    lastAutoReloadAtMs = 0L
                    exportedLayers = emptyList()
                    exportedLayerPaths.clear()
                    layerGlbManager?.clearAll()

                    consecutiveFailures = 0
                    frameCount = 0

                    viewModel.setConnectionState(ConnectionStatus.ONLINE, base)
                    showHint("✓ Сессия создана")
                    transitionTo(AppState.SCANNING)
                    startStreamingLoop()
                    return
                } else {
                    lastError = "HTTP " + response.code()
                }
            } catch (e: Exception) {
                lastError = e.message
            }

            viewModel.setConnectionState(ConnectionStatus.RECONNECTING, base)
            delay(SESSION_RETRY_DELAY_MS * attempt)
        }

        showError("Не удалось создать сессию: " + (lastError ?: "UNKNOWN"))
        transitionTo(AppState.IDLE)

    }

    private fun startStreamingLoop() {
        if (isStreaming) return
        val sid = currentSessionId ?: return

        isStreaming = true
        netState.setStreaming(true)
        streamJob?.cancel()
        streamSendJob?.cancel()
        ensureReleasePollingRunning(sid)
        streamJob = scope.launch {
            while (isActive && isStreaming && currentSessionId == sid) {
                val nowMs = System.currentTimeMillis()
                if (nowMs < nextStreamAttemptAtMs) {
                    delay(min(streamIntervalMs, nextStreamAttemptAtMs - nowMs))
                    continue
                }
                if (streamSendJob?.isActive == true) {
                    // Backpressure: remember that we need one more tick once current send finishes.
                    streamPendingTick = true
                } else {
                    streamSendJob = launch(Dispatchers.IO) {
                        val t0 = System.nanoTime()
                        val ok = try {
                            withTimeout(2_500L) { sendFrameWith(streamJpegQuality, streamPointCap) }
                        } catch (_: Exception) {
                            false
                        }
                        val sendMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
                        lastSendMs = sendMs

                        withContext(Dispatchers.Main) {
                            if (!ok) {
                                consecutiveFailures += 1
                                streamErrorStreak += 1
                            } else {
                                consecutiveFailures = 0
                                streamErrorStreak = 0
                            }

                            // Centralized network state update (shared backoff/jitter).
                            val baseUrl = getCurrentServerUrl().trimEnd('/')
                            scope.launch(Dispatchers.IO) {
                                netState.reportResult(
                                    tag = "stream",
                                    success = ok,
                                    baseMs = RECONNECT_BASE_MS,
                                    maxMs = RECONNECT_MAX_MS,
                                    errorDetail = if (ok) null else "stream_failed"
                                )
                                withContext(Dispatchers.Main) {
                                    val st = netState.getStatus()
                                    currentConnStatus = st
                                    viewModel.setConnectionState(st, baseUrl)
                                }
                            }

                            // Auto-telemetry trigger: N stream errors in a row.
                            if (!ok && streamErrorStreak >= 5) {
                                maybeAutoReport("stream_errors_streak")
                            }

                            tuneStreaming(ok, lastSendMs)
                            if (streamPendingTick) {
                                streamPendingTick = false
                            }
                        }
                    }
                }

                if (consecutiveFailures > 0) {
                    // Shared policy schedules when we may retry heavy stream sends.
                    val snap = netState.snapshot()
                    nextStreamAttemptAtMs = snap.nextAllowedAtMsByTag["stream"] ?: (nowMs + RECONNECT_BASE_MS)
                } else {
                    nextStreamAttemptAtMs = 0L
                }

                updateFrameCounter()
                updateCameraCoordinates()
                val waitMs = if (streamImmediateNextTick) 0L else streamIntervalMs
                streamImmediateNextTick = false
                delay(waitMs)
            }
        }
    }

    private fun ensureReleasePollingRunning(sessionId: String) {
        // Avoid double start on rotation/resume; restart only if session changed or jobs are not active.
        if (pollingSessionId != sessionId || exportPollJob?.isActive != true || readinessPollJob?.isActive != true) {
            stopReleasePolling()
            pollingSessionId = sessionId
            startExportLatestPolling(sessionId)
            startReadinessPolling(sessionId)
        }
    }

    private fun startExportLatestPolling(sessionId: String) {
        exportPollJob?.cancel()
        exportPollInFlight = false
        exportPollFailures = 0
        exportFailStreak = 0
        nextExportPollAtMs = 0L

        exportPollJob = lifecycleScope.launch {
            // Poll export/latest so layers update without manual actions.
            while (isActive && isStreaming && currentSessionId == sessionId) {
                if (!isUiActive) {
                    delay(500L)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now < nextExportPollAtMs) {
                    delay(min(2000L, nextExportPollAtMs - now))
                    continue
                }

                if (!isStreaming || currentSessionId != sessionId) break
                if (exportPollInFlight) continue
                exportPollInFlight = true
                try {
                    // Shared backoff gate (export/latest participates in the same policy).
                    netState.waitIfNeeded("export_latest")

                    val resp = runCatching { lockExportMutex.withLock { api.exportLatest(sessionId) } }.getOrNull()
                    if (resp == null) {
                        exportPollFailures += 1
                        exportFailStreak += 1
                        val nextAt = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = "export_null")
                        nextExportPollAtMs = nextAt
                        crashReporter.recordReproError(endpoint = "/session/" + sessionId + "/export/latest", errorSnippet = "export/latest: null resp")
                        continue
                    }
                    // 409 NO_EXPORT is expected early - ignore quietly.
                    if (resp.code() == 409) {
                        exportNotReady409 = true
                        exportPollFailures = 0
                        exportFailStreak = 0
                        crashReporter.recordReproResponse(
                            endpoint = "/session/" + sessionId + "/export/latest",
                            httpCode = resp.code(),
                            bodySnippet = "409 NO_EXPORT"
                        )
                        val nextAt = netState.reportResult(tag = "export_latest", success = true, baseMs = 6500L, maxMs = 30_000L)
                        nextExportPollAtMs = nextAt
                        withContext(Dispatchers.Main) {
                            updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics)
                        }
                        continue
                    }
                    if (!resp.isSuccessful || resp.body() == null) {
                        exportPollFailures += 1
                        exportFailStreak += 1
                        crashReporter.recordReproError(
                            endpoint = "/session/" + sessionId + "/export/latest",
                            httpCode = resp.code(),
                            errorSnippet = ("export/latest failed: " + resp.code()).take(2048)
                        )
                        val nextAt = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = "export_http_" + resp.code())
                        nextExportPollAtMs = nextAt
                        if (exportFailStreak >= 3) {
                            maybeAutoReport("export_latest_failures")
                            exportFailStreak = 0
                        }
                        continue
                    }

                    exportNotReady409 = false
                    exportPollFailures = 0
                    exportFailStreak = 0
                    val bundle = resp.body()!!
                    val rev = bundle.revision_id ?: bundle.rev_id.orEmpty()
                    if (rev.isBlank()) continue

                    crashReporter.recordReproResponse(
                        endpoint = "/session/" + sessionId + "/export/latest",
                        httpCode = resp.code(),
                        bodySnippet = safeJsonSnippet(bundle)
                    )

                    val nextAt = netState.reportResult(tag = "export_latest", success = true, baseMs = 6500L, maxMs = 30_000L)
                    nextExportPollAtMs = nextAt

                    if (originAnchorNode == null) {
                        pendingExportRevId = rev
                        continue
                    }

                    val now2 = System.currentTimeMillis()
                    if (loadedExportRevId == null) {
                        // First seen rev, try loading if origin exists.
                        pendingExportRevId = rev
                        lastAutoReloadAtMs = now2
                        pendingAutoReloadRev = null
                        loadExportLayersInternal(sessionId, showDialog = false, showOkHint = false)
                    } else if (loadedExportRevId != rev) {
                        // New revision - auto reload, but with cooldown to avoid thrashing.
                        val dt = now2 - lastAutoReloadAtMs
                        if (dt < AUTO_RELOAD_COOLDOWN_MS) {
                            pendingAutoReloadRev = rev
                        } else {
                            lastAutoReloadAtMs = now2
                            pendingAutoReloadRev = null
                            loadExportLayersInternal(sessionId, showDialog = false, showOkHint = false)
                        }
                    }

                    // If we delayed reload due to cooldown, apply it once cooldown passes.
                    val pending = pendingAutoReloadRev
                    if (pending != null && pending.isNotBlank() && (System.currentTimeMillis() - lastAutoReloadAtMs) >= AUTO_RELOAD_COOLDOWN_MS) {
                        lastAutoReloadAtMs = System.currentTimeMillis()
                        pendingAutoReloadRev = null
                        loadExportLayersInternal(sessionId, showDialog = false, showOkHint = false)
                    }
                } catch (e: Exception) {
                    exportPollFailures += 1
                    exportFailStreak += 1
                    crashReporter.recordReproError(endpoint = "/session/" + sessionId + "/export/latest", errorSnippet = (e.message ?: "exception").take(2048))
                    val nextAt = netState.reportResult(tag = "export_latest", success = false, baseMs = 6500L, maxMs = 30_000L, errorDetail = e.message)
                    nextExportPollAtMs = nextAt
                } finally {
                    exportPollInFlight = false
                    withContext(Dispatchers.Main) {
                        val st = netState.getStatus()
                        currentConnStatus = st
                        viewModel.setConnectionState(st, getCurrentServerUrl().trimEnd('/'))
                        runCatching { updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics) }
                    }
                }
            }
        }
    }

    private fun startReadinessPolling(sessionId: String) {
        readinessPollJob?.cancel()
        readinessPollFailures = 0
        nextReadinessPollAtMs = 0L
        readinessPollJob = lifecycleScope.launch {
            while (isActive && isStreaming && currentSessionId == sessionId) {
                if (!isUiActive) {
                    delay(500L)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now < nextReadinessPollAtMs) {
                    delay(min(750L, nextReadinessPollAtMs - now))
                    continue
                }

                if (!isStreaming || currentSessionId != sessionId) break

                // Shared backoff gate (readiness participates in the same policy).
                netState.waitIfNeeded("readiness")

                val resp = runCatching { api.getReadiness(sessionId) }.getOrNull()
                if (resp == null || !resp.isSuccessful || resp.body() == null) {
                    readinessPollFailures += 1
                    withContext(Dispatchers.Main) {
                        updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics)
                    }
                    val nextAt = netState.reportResult(
                        tag = "readiness",
                        success = false,
                        baseMs = 1500L,
                        maxMs = 12_000L,
                        errorDetail = "readiness_http"
                    )
                    nextReadinessPollAtMs = nextAt

                    crashReporter.recordReproError(
                        endpoint = "/session/" + sessionId + "/readiness",
                        httpCode = resp?.code(),
                        errorSnippet = "readiness failed"
                    )

                    withContext(Dispatchers.Main) {
                        val st = netState.getStatus()
                        currentConnStatus = st
                        viewModel.setConnectionState(st, getCurrentServerUrl().trimEnd('/'))
                        updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics)
                    }
                    continue
                }

                readinessPollFailures = 0
                val body = resp.body()!!

                withContext(Dispatchers.Main) {
                    lastReadinessReady = body.ready
                    lastReadinessScore = body.score
                    lastReadinessMetrics = body.readiness_metrics
                    updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics)
                }

                crashReporter.recordReproResponse(
                    endpoint = "/session/" + sessionId + "/readiness",
                    httpCode = resp.code(),
                    bodySnippet = safeJsonSnippet(body)
                )

                val nextAt = netState.reportResult(tag = "readiness", success = true, baseMs = 1500L, maxMs = 12_000L)
                nextReadinessPollAtMs = nextAt

                withContext(Dispatchers.Main) {
                    val st = netState.getStatus()
                    currentConnStatus = st
                    viewModel.setConnectionState(st, getCurrentServerUrl().trimEnd('/'))
                    lastReadinessReady = body.ready
                    lastReadinessScore = body.score
                    lastReadinessMetrics = body.readiness_metrics
                    updateReadinessUI(lastReadinessReady, lastReadinessScore, lastReadinessMetrics)
                }
            }
        }
    }

    private fun stopReleasePolling() {
        exportPollJob?.cancel()
        exportPollJob = null
        readinessPollJob?.cancel()
        readinessPollJob = null
        exportPollInFlight = false
        exportPollFailures = 0
        readinessPollFailures = 0
        nextExportPollAtMs = 0L
        nextReadinessPollAtMs = 0L
        exportNotReady409 = false
        pendingAutoReloadRev = null
        pollingSessionId = null
    }

    private fun tuneStreaming(ok: Boolean, sendMs: Long) {
        // EWMA send time for adaptive throttling.
        val x = sendMs.toDouble()
        sendTimeEwmaMs = if (sendTimeEwmaMs <= 0.0) x else (0.8 * sendTimeEwmaMs + 0.2 * x)

        val minInterval = 300L
        val maxInterval = 1500L

        if (!ok) {
            // Back off: reduce quality and point cap quickly.
            streamJpegQuality = (streamJpegQuality - 6).coerceAtLeast(45)
            streamPointCap = (streamPointCap - 40).coerceAtLeast(120)
            streamIntervalMs = (streamIntervalMs + 150L).coerceAtMost(maxInterval)
            return
        }

        // Success: slowly restore quality/cap, and adapt interval to keep CPU/network stable.
        streamJpegQuality = (streamJpegQuality + 1).coerceAtMost(80)
        streamPointCap = (streamPointCap + 10).coerceAtMost(450)

        val target = (sendTimeEwmaMs * 1.3).toLong().coerceIn(minInterval, maxInterval)
        streamIntervalMs = ((0.85 * streamIntervalMs.toDouble()) + (0.15 * target.toDouble())).toLong()
            .coerceIn(minInterval, maxInterval)
    }

    private suspend fun sendFrame(): Boolean = sendFrameWith(streamJpegQuality, streamPointCap)

    private suspend fun sendFrameWith(jpegQuality: Int, pointCap: Int): Boolean {
        val sid = currentSessionId ?: return false

        data class FramePacket(
            val payload: HashMap<String, Any>,
            val yuv: ImageUtils.Yuv420Copy,
            val swapUv: Boolean,
            val depth: DepthUtils.DepthFrame?
        )

        val packet = withContext(Dispatchers.Main) {
            val manualMeasurements = runCatching {
                arRuler.getSavedMeasurements().map { m ->
                    mapOf(
                        "id" to m.id,
                        "type" to m.type.name,
                        "distance_m" to m.distance,
                        "label" to m.label,
                        "timestamp_ms" to m.timestamp
                    )
                }
            }.getOrDefault(emptyList())

            val frame = sceneView.arFrame ?: return@withContext null
            try {
                val cam = frame.camera
                if (cam.trackingState != TrackingState.TRACKING) return@withContext null

                val image = try {
                    frame.acquireCameraImage()
                } catch (_: Exception) {
                    null
                } ?: return@withContext null

                val swapUv = settingsPrefs.getBoolean(PREF_CAMERA_SWAP_UV, false)
                val yuvCopy = try {
                    // Copy planes quickly on main thread while Image is valid.
                    ImageUtils.copyYuv420(image)
                } finally {
                    runCatching { image.close() }
                }

                val shouldSendDepth = (depthFrameCounter % DEPTH_SEND_EVERY == 0)
                depthFrameCounter++
                val acquiredDepth = if (shouldSendDepth) DepthUtils.tryAcquireDepth16(frame) else null
                val depthFrame = acquiredDepth?.let { acquired ->
                    try {
                        DepthUtils.copyDepth16(acquired.image, acquired.isRaw)
                    } finally {
                        runCatching { acquired.image.close() }
                    }
                }
                if (shouldSendDepth && acquiredDepth == null) {
                    val now = System.currentTimeMillis()
                    if (!depthUnavailableWarned || (now - lastDepthWarningMs) > 10_000L) {
                        depthUnavailableWarned = true
                        lastDepthWarningMs = now
                        showHint("ℹ️ Depth недоступен на устройстве - отправляем только point cloud")
                    }
                }

                val intr = cam.imageIntrinsics
                val focal = intr.focalLength
                val pp = intr.principalPoint
                val dims = intr.imageDimensions

                val pose = cam.pose
                val q = FloatArray(4)
                pose.getRotationQuaternion(q, 0)
                val position = listOf(pose.tx(), pose.ty(), pose.tz())
                val quaternion = listOf(q[0], q[1], q[2], q[3])

                val pc = try {
                    frame.acquirePointCloud()
                } catch (_: Exception) {
                    null
                }

                val points: List<List<Float>> = if (pc != null) {
                    try {
                        val buf = pc.points
                        val pointCount = buf.remaining() / 4
                        val cap = pointCap
                        val step = maxOf(1, pointCount / cap)
                        val out = ArrayList<List<Float>>(min(pointCount, cap))
                        var i = 0
                        while (i < pointCount) {
                            val baseIdx = i * 4
                            out.add(listOf(buf.get(baseIdx), buf.get(baseIdx + 1), buf.get(baseIdx + 2)))
                            i += step
                        }
                        out
                    } finally {
                        runCatching { pc.release() }
                    }
                } else {
                    emptyList()
                }

                val basePayload = hashMapOf<String, Any>(
                    "frame_id" to ("frm_" + frameCount),
                    "timestamp" to (System.currentTimeMillis() / 1000.0),
                    "measurements_json" to runCatching { arRuler.exportMeasurements() }.getOrDefault(""),
                    "intrinsics" to mapOf(
                        "fx" to focal[0].toDouble(),
                        "fy" to focal[1].toDouble(),
                        "cx" to pp[0].toDouble(),
                        "cy" to pp[1].toDouble(),
                        "width" to dims[0].toInt(),
                        "height" to dims[1].toInt()
                    ),
                    "rgb_width" to yuvCopy.width,
                    "rgb_height" to yuvCopy.height,
                    "pose" to mapOf(
                        "position" to position,
                        "quaternion" to quaternion
                    ),
                    "point_cloud" to points
                )

                val originPose = originAnchorNode?.anchor?.pose
                if (originPose != null) {
                    val oq = FloatArray(4)
                    originPose.getRotationQuaternion(oq, 0)
                    basePayload["origin_anchor_pose"] = mapOf(
                        "position" to listOf(originPose.tx(), originPose.ty(), originPose.tz()),
                        "quaternion" to listOf(oq[0], oq[1], oq[2], oq[3])
                    )
                }

                if (manualMeasurements.isNotEmpty()) {
                    basePayload["manual_measurements"] = manualMeasurements
                }

                if (shouldSendDepth && depthFrame == null) {
                    basePayload["depth_unavailable"] = true
                }

                FramePacket(basePayload, yuvCopy, swapUv, depthFrame)
            } catch (_: Exception) {
                null
            }
        }

        if (packet == null) return true

        withContext(Dispatchers.Main) {
            if (packet.depth == null) {
                depthUnavailableStreak += 1
                if (!depthHintShown && depthUnavailableStreak >= 5) {
                    depthHintShown = true
                    showHint("⚠️ Depth недоступен на устройстве или отключён в ARCore")
                }
            } else {
                depthUnavailableStreak = 0
            }
        }

        val payload = withContext(Dispatchers.Default) {
            packet.payload.apply {
                this["client_stats"] = mapOf(
                    "jpeg_quality" to jpegQuality,
                    "point_cap" to pointCap,
                    "send_interval_ms" to streamIntervalMs,
                    "last_send_ms" to lastSendMs,
                    "conn" to currentConnStatus.name,
                )
                HeavyOps.withPermit {
                    val nv21 = ImageUtils.yuvCopyToNv21(packet.yuv, swapUv = packet.swapUv)
                    this["rgb_base64"] = ImageUtils.nv21ToJpegBase64(nv21.data, nv21.width, nv21.height, jpegQuality)
                    val depth = packet.depth
                    if (depth != null) {
                        this["depth_base64"] = DepthUtils.depthBytesToBase64(depth.bytes)
                        this["depth_width"] = depth.width
                        this["depth_height"] = depth.height
                        this["depth_scale_m_per_unit"] = depth.scaleMPerUnit
                        this["depth_scale"] = depth.scaleMPerUnit
                        this["depth_is_raw"] = depth.isRaw
                        this["depth_format"] = depth.format
                        this["depth_invalid_value"] = depth.invalidValue
                    }
                }
            }
        }

        val resp = try {
            api.streamData(sid, payload)
        } catch (e: Exception) {
            crashReporter.recordException("streamData", e)
            crashReporter.recordReproError(endpoint = "/session/" + sid + "/stream", errorSnippet = (e.message ?: "exception").take(2048))
            return false
        }

        if (!resp.isSuccessful) {
            val errSnippet = runCatching { resp.errorBody()?.string() }.getOrNull()?.take(2048)
            crashReporter.recordError("streamData", "HTTP ${resp.code()}")
            crashReporter.recordReproError(endpoint = "/session/" + sid + "/stream", httpCode = resp.code(), errorSnippet = (errSnippet ?: ("HTTP " + resp.code())).take(2048))
            return false
        }

        val body = resp.body() ?: return true

        withContext(Dispatchers.Main) {
            frameCount += 1
            val hints = body.ai_hints
            when (body.status) {
                "NEEDS_SCAN" -> {
                    val scanDirections = buildList {
                        hints?.scan_plan?.let { addAll(it) }
                        hints?.next_best_views?.let { addAll(it) }
                    }.distinct()
                    if (scanDirections.isNotEmpty()) showScanHintsBar(scanDirections)
                }
                "READY" -> {
                    hideScanHintsBar()
                    if (userMarkers.size >= MIN_POINTS_FOR_MODEL && !hasRedZones) startPlayButtonPulse()
                }
                else -> if (hints?.is_scan_complete == true) hideScanHintsBar()
            }
            if (hints != null) {
                updateQualityUI(hints.quality_score)
                val msg = when {
                    !hints.warnings.isNullOrEmpty() -> hints.warnings!!.joinToString("\n")
                    !hints.scan_plan.isNullOrEmpty() && body.status == "NEEDS_SCAN" -> "📍 Нужно досканировать: " + hints.scan_plan!!.take(2).joinToString(" | ")
                    !hints.instructions.isNullOrEmpty() -> hints.instructions!!.joinToString("\n")
                    else -> null
                }
                if (!msg.isNullOrBlank()) showHint(msg)
                if (userMarkers.size >= MIN_POINTS_FOR_MODEL) btnAnalyze.isEnabled = true
                if (hints.is_ready == true && !hasRedZones) startPlayButtonPulse()
            }
        }

        return true
    }

    private fun stopStreaming() {
        isStreaming = false
        runCatching { netState.setStreaming(false) }
        streamJob?.cancel()
        streamJob = null
        streamSendJob?.cancel()
        streamSendJob = null
        stopReleasePolling()
    }

    private fun startAutoVoxelRefresh(sessionId: String) {
        stopAutoVoxelRefresh()
        autoVoxelRefreshJob = lifecycleScope.launch {
            while (isActive && eyeOfAIActive) {
                delay(VOXEL_AUTO_REFRESH_MS)
                if (!eyeOfAIActive || currentSessionId != sessionId) break
                runCatching { api.getVoxels(sessionId) }.onSuccess { response ->
                    if (response.isSuccessful && response.body() != null) {
                        val voxelResponse = response.body()!!
                        if (voxelResponse.total_count > 0) {
                            currentVoxelData = voxelResponse.voxels.map { v ->
                                VoxelData(v.position, v.type, v.color, v.alpha.toFloat(), voxelResponse.resolution.toFloat(), v.radius)
                            }
                            voxelVisualizer.setRootParent(originAnchorNode)
                            voxelVisualizer.showVoxels(currentVoxelData!!)
                        }
                    }
                }
            }
        }
    }

    private fun stopAutoVoxelRefresh() {
        autoVoxelRefreshJob?.cancel()
        autoVoxelRefreshJob = null
    }

    private fun showScanHintsBar(hints: List<String>) {
        if (appState != AppState.SCANNING) return
        currentScanHints = hints
        scanHintsVisible = true
        tvAiCritique.visibility = View.VISIBLE
        tvAiCritique.text = hints.take(3).joinToString("\n") { "📍 $it" }
        tvAiCritique.setTextColor(android.graphics.Color.parseColor("#FF6600"))
    }

    private fun hideScanHintsBar() {
        currentScanHints = emptyList()
        scanHintsVisible = false
        if (tvAiCritique.text.toString().startsWith("📍")) tvAiCritique.visibility = View.GONE
    }

    private suspend fun doRequestModeling(measurementsJson: String = "", measurementConstraints: List<MeasurementConstraint> = emptyList()) {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                showError("Сессия не активна")
                transitionTo(AppState.IDLE)
            }
            return
        }

        stopStreaming()

        try {
            // На всякий случай отправляем anchors
            syncAnchorsToServer()
        } catch (_: Exception) {
            // Ignore
        }

        val response = try {
            if (measurementsJson.isNotBlank() || measurementConstraints.isNotEmpty()) api.startModelingWithMeasurements(sid, ModelingWithMeasurementsPayload(measurementsJson, measurementConstraints)) else api.startModeling(sid)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showError("Ошибка моделирования: " + (e.message ?: "UNKNOWN"))
                transitionTo(AppState.SCANNING)
            }
            return
        }

        if (!response.isSuccessful || response.body() == null) {
            withContext(Dispatchers.Main) {
                showError("Моделирование не удалось: HTTP " + response.code())
                transitionTo(AppState.SCANNING)
            }
            return
        }

        val model = response.body()!!
        withContext(Dispatchers.Main) {
            current3DModel = model
            selectedVariantIndex = 0
            transitionTo(AppState.SELECTING)

            val opts = model.options.orEmpty()
            variantAdapter.submit(opts, selected = 0)

            if (opts.isNotEmpty()) onVariantSelected(0)
            if (measurementConstraints.isNotEmpty()) showHint("📐 ${measurementConstraints.size} измерений использовано как ограничения")
        }
    }

    private fun placeAnchor() {
        if (userMarkers.size >= MAX_POINTS) {
            showHint("⚠️ Достигнут лимит точек")
            return
        }

        val frame = sceneView.arFrame ?: return

        val x = sceneView.width / 2f
        val y = sceneView.height / 2f

        val hit = frame.hitTest(x, y).firstOrNull { hitResult ->
            val trackable = hitResult.trackable
            (trackable is Plane) && trackable.isPoseInPolygon(hitResult.hitPose)
        }

        if (hit == null) {
            showHint("⚠️ Не найдено место для точки")
            return
        }

        val anchor = hit.createAnchor()
        val anchorNode = AnchorNode(anchor).apply {
            setParent(sceneView.scene)
        }

        // Маленький визуальный маркер
        val marker = Node().apply {
            setParent(anchorNode)
            localScale = Vector3(0.05f, 0.05f, 0.05f)
            renderable = ModelAssets.getCopy(ModelAssets.ModelType.WEDGE_NODE)
        }

        anchorNodes.add(anchorNode)
        if (originAnchorNode == null) {
            originAnchorNode = anchorNode
            layerGlbManager?.setLayersRoot(originAnchorNode)
            voxelVisualizer.setRootParent(originAnchorNode)
        }

        // If export/latest was already produced (server-side) before origin was set, auto-load now.
        if (originAnchorNode == anchorNode) {
            val sid = currentSessionId
            if (!sid.isNullOrBlank() && !pendingExportRevId.isNullOrBlank()) {
                scope.launch { loadExportLayersInternal(sid, showDialog = false, showOkHint = false) }
            }
        }

        val p = anchor.pose
        val markerId = "a-${UUID.randomUUID().toString().take(8)}"
        userMarkers.add(
            PlacedAnchor(
                id = markerId,
                x = p.tx(),
                y = p.ty(),
                z = p.tz()
            )
        )
        anchorNode.name = markerId
        anchorMarkerNodes[markerId] = marker
        marker.setOnTapListener { _, _ -> confirmDeleteAnchor(markerId) }

        updatePointsCount()
        btnAnalyze.isEnabled = userMarkers.size >= MIN_POINTS_FOR_MODEL
        vibrate(35)

        // Синхронизируем anchors фоном
        scope.launch {
            try {
                syncAnchorsToServer()
            } catch (_: Exception) {
                // Ignore
            }
        }

        showHint("✓ Точка добавлена: ${userMarkers.size}")
    }

    private fun confirmResetOrigin() {
        AlertDialog.Builder(this)
            .setTitle("Сброс origin")
            .setMessage("Сбросить origin и удалить все точки опоры? Это нужно, если origin поставили неверно.")
            .setPositiveButton("Сбросить") { _, _ ->
                resetOriginAndAnchors()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun resetOriginAndAnchors() {
        clearARAnchors()
        loadedExportRevId = null
        pendingExportRevId = null
        exportedLayers = emptyList()
        exportedLayerPaths.clear()
        layerGlbManager?.clearAll()
        currentVoxelData = null
        voxelVisualizer.hideVoxels()
        showHint("✓ Origin сброшен. Поставь новую опору")
        scope.launch { runCatching { syncAnchorsToServer() } }
    }


    private suspend fun sendLogEvent(event: String, data: Map<String, Any?> = emptyMap()) {
        val sid = currentSessionId ?: return
        runCatching {
            api.logEvent(
                sid,
                LogPayload(
                    event = event,
                    timestamp_ms = System.currentTimeMillis(),
                    data = data,
                    device = LogDeviceInfo(
                        model = Build.MODEL,
                        manufacturer = Build.MANUFACTURER,
                        sdk = Build.VERSION.SDK_INT
                    )
                )
            )
        }
    }

    private fun request3DReconstruction() {
        val option = current3DModel?.options?.getOrNull(selectedVariantIndex)
        val elements = option?.elements.orEmpty().ifEmpty { option?.full_structure.orEmpty() }

        if (elements.isEmpty()) {
            showHint("⚠️ Пока нет элементов для 3D превью")
            return
        }

        sceneBuilder.buildScene(elements)
        viewModel.saveSnapshot(elements, "Построена структура")
        transitionTo(AppState.PREVIEW_3D)
        showHint("✓ Построено элементов: ${elements.size}")
    }

    private fun hide3DPreview() {
        sceneBuilder.clearScene()
        show3DPreview = false
        showHint("👁️ Превью скрыто")
    }

    private fun visualizeScaffoldVariant(index: Int) {
        val option = current3DModel?.options?.getOrNull(index) ?: return
        val elements = option.elements.orEmpty().ifEmpty { option.full_structure.orEmpty() }
        if (elements.isNotEmpty()) {
            sceneBuilder.buildScene(elements)
            viewModel.saveSnapshot(elements, "Выбран вариант ${index + 1}")
        }
    }

    private fun showPhysicsHeatmap() {
        val option = current3DModel?.options?.getOrNull(selectedVariantIndex)
        val elements = option?.elements.orEmpty().ifEmpty { option?.full_structure.orEmpty() }
        if (elements.isEmpty()) {
            showHint("⚠️ Нет данных physics для отображения")
            return
        }

        val heatmap = elements.map {
            mapOf(
                "id" to it.id,
                "color" to (it.stress_color ?: "gray")
            )
        }
        sceneBuilder.updateColors(heatmap)
        showHint("📊 Карта нагрузок обновлена")
    }




    private fun handleStructureState(state: StructureState) {
        when (state) {
            is StructureState.Idle -> Unit
            is StructureState.Updating -> showLoadingIndicator()
            is StructureState.Updated -> {
                hideLoadingIndicator()
                handleUpdateResponse(state.response)
            }
            is StructureState.Error -> {
                hideLoadingIndicator()
                showError(state.message)
            }
        }
    }

    private fun handleUpdateResponse(response: UpdateResponse) {
        sceneBuilder.updateHeatmap(response.heatmap)

        if (viewModel.editMode.value == EditMode.SIMULATION) {
            if (response.collapsed.elements.isNotEmpty()) {
                physicsAnimator.animateFall(response.collapsed.elements)
                showCollapsedNotification(response.collapsed.elements.size)

                Handler(Looper.getMainLooper()).postDelayed({
                    response.collapsed.elements.forEach { elementId ->
                        sceneBuilder.removeElement(elementId)
                    }
                }, 2000)
            }
        } else if (response.collapsed.elements.isNotEmpty()) {
            highlightWouldCollapse(response.collapsed.elements)
        }

        if (!response.is_stable) {
            showWarning("⚠️ Структура нестабильна!")
        } else {
            hasRedZones = false
            btnAnalyze.animate().cancel()
            btnAnalyze.scaleX = 1.0f
            btnAnalyze.scaleY = 1.0f
        }
    }

    private fun onElementTapped(elementId: String) {
        viewModel.previewRemoveElement(elementId) { preview ->
            runOnUiThread {
                if (preview.is_critical) {
                    showConfirmDialog(
                        title = "⚠️ Критический элемент!",
                        message = preview.warning,
                        onConfirm = { removeElementWithAnimation(elementId) }
                    )
                } else {
                    removeElementWithAnimation(elementId)
                }
            }
        }
    }

    private fun removeElementWithAnimation(elementId: String) {
        viewModel.saveSnapshot(sceneBuilder.getAllElements(), "Удален $elementId")
        soundManager.play(SoundType.REMOVE)
        viewModel.removeElement(
            elementId = elementId,
            onSuccess = { response ->
                val removedIds = response.collapsed.elements.toSet() + elementId
                val nextElements = sceneBuilder.getAllElements().filterNot { it.id in removedIds }
                viewModel.saveSnapshot(nextElements, "Удален $elementId")
            },
            onError = { error ->
                runOnUiThread { showError("Не удалось удалить элемент: $error") }
            }
        )
    }

    private fun showLoadingIndicator() = showHint("⏳ Обновление структуры...")

    private fun hideLoadingIndicator() = Unit



    private fun toggleEyeOfAI() {
        if (eyeOfAIActive) {
            voxelVisualizer.hideVoxels()
            voxelLegend.visibility = View.GONE
            fabEyeOfAI.setImageResource(R.drawable.ic_eye)
            eyeOfAIActive = false
            stopAutoVoxelRefresh()
            soundManager.play(SoundType.WHOOSH, volume = 0.3f, pitch = 0.8f)
            return
        }

        val sessionId = currentSessionId
        if (sessionId.isNullOrBlank()) {
            showError("Сессия не активна. Сначала нажмите START")
            return
        }

        showLoadingDialog("Загрузка вокселей...")
        lifecycleScope.launch {
            try {
                val response = api.getVoxels(sessionId)
                if (response.isSuccessful && response.body() != null) {
                    val voxelResponse = response.body()!!
                    currentVoxelData = voxelResponse.voxels.map { v ->
                        VoxelData(
                            position = v.position,
                            type = v.type,
                            color = v.color,
                            alpha = v.alpha.toFloat(),
                            size = voxelResponse.resolution.toFloat(),
                            radius = v.radius
                        )
                    }

                    voxelVisualizer.showVoxels(currentVoxelData!!)
                    voxelLegend.visibility = View.VISIBLE
                    fabEyeOfAI.setImageResource(R.drawable.ic_eye_off)
                    eyeOfAIActive = true
                    soundManager.play(SoundType.WHOOSH, volume = 0.5f, pitch = 1.5f)
                    showToast("👁️ Теперь вы видите глазами ИИ! Вокселей: ${voxelResponse.total_count}")
                    startAutoVoxelRefresh(sessionId)
                } else {
                    showError("Не удалось загрузить воксели")
                }
            } catch (e: Exception) {
                showError("Ошибка загрузки Eye of AI: ${e.message}")
            } finally {
                hideLoadingDialog()
            }
        }
    }

    private fun showLoadingDialog(message: String) {
        hideLoadingDialog()
        loadingDialog = AlertDialog.Builder(this)
            .setTitle("Подождите")
            .setMessage(message)
            .setCancelable(false)
            .create()
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showError(message: String) {
        showHint("❌ $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showWarning(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(android.R.color.holo_orange_dark))
            .setTextColor(getColor(android.R.color.white))
            .show()
        showHint(message)
    }

    private fun highlightWouldCollapse(elementIds: List<String>) {
        hasRedZones = elementIds.isNotEmpty()
        if (hasRedZones) {
            startPlayButtonPulse()
        }
        showHint("⚠️ Могут упасть элементы: ${elementIds.size}")
        elementIds.forEach { id ->
            sceneBuilder.findNodeById(id)?.let { node ->
                animateBlink(node, SceneColor(android.graphics.Color.RED))
            }
        }
    }

    private fun animateBlink(node: Node, color: SceneColor) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 500
        animator.repeatCount = 3
        animator.repeatMode = ValueAnimator.REVERSE
        animator.addUpdateListener {
            val alpha = it.animatedValue as Float
            node.localScale = Vector3.one().scaled(1f + alpha * 0.05f)
        }
        animator.start()
    }

    private fun showCollapsedNotification(count: Int) {
        val message = when {
            count == 1 -> "💥 1 элемент обрушился!"
            count < 5 -> "💥 $count элемента обрушились!"
            else -> "💥 $count элементов обрушились!"
        }
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(android.R.color.holo_red_dark))
            .setTextColor(getColor(android.R.color.white))
            .show()

        val vibrator = getDefaultVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Удалить") { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun updateModeUI(mode: EditMode) {
        modeIndicator.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(100)
            .withEndAction {
                when (mode) {
                    EditMode.EDIT -> {
                        modeIndicator.setBackgroundResource(R.drawable.mode_edit_bg)
                        modeIcon.text = "✏️"
                        modeText.text = "Режим редактирования"
                        tvModeStatus.text = "MODE: EDIT"
                        soundManager.play(SoundType.WHOOSH, volume = 0.3f, pitch = 1.2f)
                        physicsAnimator.stopAll()
                    }
                    EditMode.SIMULATION -> {
                        modeIndicator.setBackgroundResource(R.drawable.mode_simulation_bg)
                        modeIcon.text = "⚡"
                        modeText.text = "Режим симуляции"
                        tvModeStatus.text = "MODE: SIMULATION"
                        soundManager.play(SoundType.WHOOSH, volume = 0.5f, pitch = 0.8f)
                        vibrateShort()
                        checkStructureStability()
                    }
                }

                modeIndicator.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun startPlayButtonPulse() {
        btnAnalyze.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(500)
            .withEndAction {
                btnAnalyze.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(500)
                    .withEndAction {
                        if (hasRedZones) {
                            startPlayButtonPulse()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun vibrateShort() {
        val vibrator = getDefaultVibrator() ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(70, 120))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(70)
        }
    }

    private fun checkStructureStability() {
        viewModel.removeElement(
            elementId = "__CHECK_ONLY__",
            onSuccess = { response ->
                if (response.collapsed.elements.isNotEmpty()) {
                    showWarning(
                        "⚠️ Обнаружено ${response.collapsed.elements.size} висящих элементов!\nОни будут удалены при первом изменении."
                    )
                    highlightWouldCollapse(response.collapsed.elements)
                }
            },
            onError = { }
        )
    }

    private fun updateUndoRedoButtons() {
        btnUndo.isEnabled = viewModel.canUndo()
        btnRedo.isEnabled = viewModel.canRedo()
        if (viewModel.canUndo()) {
            val description = viewModel.getUndoDescription()
            btnUndo.tooltipText = "Отменить: $description"
        }
    }

    private fun performUndo() {
        viewModel.undo { snapshot ->
            sceneBuilder.buildScene(snapshot.elements)
            showToast("↶ Отменено: ${snapshot.description}")
        }
    }

    private fun performRedo() {
        viewModel.redo { snapshot ->
            sceneBuilder.buildScene(snapshot.elements)
            showToast("↷ Повторено: ${snapshot.description}")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.edit_menu, menu)

        lifecycleScope.launch {
            viewModel.editMode.collect { mode ->
                menu.findItem(R.id.action_toggle_mode)?.title = when (mode) {
                    EditMode.EDIT -> "🎬 Режим: EDIT"
                    EditMode.SIMULATION -> "⚡ Режим: SIMULATION"
                }
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_mode -> {
                viewModel.toggleEditMode()
                true
            }
            R.id.action_layers -> {
                showLayersDialog()
                true
            }
            R.id.action_reset_origin -> {
                if (originAnchorNode == null) {
                    showHint("ℹ️ Origin ещё не задан")
                } else {
                    confirmResetOrigin()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearARAnchors() {
        anchorNodes.forEach { it.anchor?.detach(); it.setParent(null) }
        anchorNodes.clear()
        anchorMarkerNodes.clear()
        userMarkers.clear()
        originAnchorNode = null
        layerGlbManager?.setLayersRoot(null)
        voxelVisualizer.setRootParent(null)
        updatePointsCount()
        btnAnalyze.isEnabled = false
        sceneBuilder.clearScene()

        currentSessionId?.let { sid ->
            scope.launch {
                syncAnchorsToServer(allowEmpty = true)
                if (currentConnStatus == ConnectionStatus.ONLINE) {
                    flushOfflineAndTelemetry(sid, getCurrentServerUrl().trimEnd('/'))
                }
            }
        }
    }


    private fun passesReleaseDeviceGate(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_RELEASE_API_LEVEL) return false

        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isUnsupported) return false

        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { info ->
            am?.getMemoryInfo(info)
        }
        val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (totalRamGb < MIN_RELEASE_RAM_GB) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mediaClass = Build.VERSION.MEDIA_PERFORMANCE_CLASS
            if (mediaClass != 0 && mediaClass < Build.VERSION_CODES.S) return false
        }

        return true
    }

    private fun showReleaseDeviceUnsupportedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Устройство не подходит для AR релиза")
            .setMessage(
                "Для стабильной AR-работы нужен совместимый ARCore смартфон уровня флагмана: " +
                    "Android 10+, минимум 6 ГБ RAM и современный GPU/камера."
            )
            .setCancelable(false)
            .setPositiveButton("Закрыть") { _, _ -> finish() }
            .show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        isUiActive = true
        if (isStreaming && !currentSessionId.isNullOrBlank()) {
            ensureReleasePollingRunning(currentSessionId!!)
        }
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        // Ensure ARCore is installed / updated.
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    return
                }

                ArCoreApk.InstallStatus.INSTALLED -> {
                    // continue
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "ARCore install/request failed: ${e.message}", e)
            if (!arcoreHintShown) {
                arcoreHintShown = true
                showError("ARCore не установлен или не поддерживается.")
            }
            return
        }

        startArIfReady()

        try {
            sceneView.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("MainActivity", "Camera not available on resume", e)
            showError("Камера недоступна. Закройте другие приложения, использующие камеру.")
        }

        if (eyeOfAIActive) {
            val sid = currentSessionId
            if (!sid.isNullOrBlank()) startAutoVoxelRefresh(sid)
        }
    }

    override fun onPause() {
        isUiActive = false
        stopReleasePolling()
        super.onPause()
        stopStreaming()
        stopAutoVoxelRefresh()
        runCatching { sceneView.pause() }
    }

    private fun safeJsonSnippet(obj: Any): String {
        return try {
            gson.toJson(obj).take(2048)
        } catch (e: Exception) {
            ("<json_error:" + (e.message ?: "unknown") + ">").take(256)
        }
    }

}
