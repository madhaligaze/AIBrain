package com.example.aibrain

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.Manifest
import android.content.Context
import android.content.Intent
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
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingFailureReason
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
import kotlin.collections.ArrayDeque
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
import com.example.aibrain.diagnostics.ReportSanitizer
import com.example.aibrain.util.HeavyOps
import com.example.aibrain.measurement.MeasurementType
import com.example.aibrain.measurement.Measurement
import com.example.aibrain.measurement.MeasurementStore
import com.example.aibrain.measurement.TrackingQuality
import com.example.aibrain.depth.DepthPolicy
import com.example.aibrain.depth.ReadinessProfile
import com.example.aibrain.visualization.VoxelData
import com.example.aibrain.visualization.VoxelVisualizer
import com.example.aibrain.scaffold.ScaffoldCylinderRenderer
import com.example.aibrain.ai.YoloInferenceEngine
import com.example.aibrain.ai.AIVisionController
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
        private const val MAX_POINTS = 20
        private const val PREFS_NAME = "app_settings"
        private const val PREF_SERVER_BASE_URL = "server_base_url"
        private const val KEY_SESSION_HISTORY = "session_history_json"
        private const val PREF_CAMERA_SWAP_UV = "camera_swap_uv"
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
    private lateinit var tvScanCoach: TextView

    // Основные кнопки
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnAddWaypoint: Button
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
    private lateinit var aiVision: AIVisionController
    private lateinit var scaffoldRenderer: ScaffoldCylinderRenderer
    private var lastYoloDetections: List<YoloInferenceEngine.Detection> = emptyList()

    private lateinit var viewGridOverlay: View
    private lateinit var smartReticle: SmartReticleView

    // Scan-mode animation targets
    private lateinit var llHudMetrics: LinearLayout
    private lateinit var llCoordsPanel: LinearLayout
    private lateinit var llStatusPanel: LinearLayout
    private lateinit var undoRedoPanel: LinearLayout

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
    private lateinit var btnRulerExport: Button
    private lateinit var tvRulerInstruction: TextView
    private lateinit var accuracyDot: View
    private lateinit var tvAccuracy: TextView

    // ══════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЕ - ОСНОВНОЕ
    // ══════════════════════════════════════════════════════════════════════
    private var appState = AppState.IDLE
    // Read from the streaming coroutine (off the main thread) and written on the
    // main thread, so it must be volatile to avoid acting on a stale session id.
    // Session id is owned by the ViewModel (StateFlow). Read-only view here;
    // StateFlow.value is safe to read off the main thread (streaming loop).
    private val currentSessionId: String?
        get() = if (::viewModel.isInitialized) viewModel.sessionId.value else null
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
    private val layerController by lazy {
        com.example.aibrain.controller.LayerController(
            context = this,
            sceneView = sceneView,
            scope = scope,
            settingsPrefs = settingsPrefs,
            serverUrl = { getCurrentServerUrl() },
        )
    }
    // Connection status is owned by the ViewModel (single source of truth).
    // Exposed read-only here; StateFlow.value is safe to read off the main thread
    // (the stream payload's client_stats reads it).
    private val currentConnStatus: ConnectionStatus
        get() = if (::viewModel.isInitialized) viewModel.connectionState.value.status else ConnectionStatus.UNKNOWN
    @Volatile private var streamPendingTick: Boolean = false
    @Volatile private var streamImmediateNextTick: Boolean = false
    private val streamTuner = com.example.aibrain.streaming.StreamTuner(intervalMs = STREAM_INTERVAL_MS)
    private var lastSendMs: Long = 0L
    // Readiness UI state now lives in StructureViewModel.readiness (StateFlow).
    private var lastReadinessHintsHash: String? = null
    private var lastReadinessHintsAtMs: Long = 0L
    private var lastCompatHintsHash: String? = null
    private var lastCompatHintsAtMs: Long = 0L
    private var nextStreamAttemptAtMs: Long = 0L
    private val exportPoller by lazy {
        com.example.aibrain.controller.ExportPoller(
            scope = lifecycleScope,
            api = api,
            netState = netState,
            viewModel = viewModel,
            crashReporter = crashReporter,
            exportMutex = lockExportMutex,
            serverUrl = { getCurrentServerUrl().trimEnd('/') },
            isUiActive = { isUiActive },
            isActiveSession = { sid -> isStreaming && currentSessionId == sid },
            onAutoReport = { trigger -> maybeAutoReport(trigger) },
            onUiRefresh = { updateReadinessUI() },
            setNotReady409 = { v -> exportNotReady409 = v },
            onExportRevision = { sid, rev -> onExportLatestRevision(sid, rev) },
        )
    }
    private val readinessPoller by lazy {
        com.example.aibrain.controller.ReadinessPoller(
            scope = lifecycleScope,
            api = api,
            netState = netState,
            viewModel = viewModel,
            crashReporter = crashReporter,
            serverUrl = { getCurrentServerUrl().trimEnd('/') },
            isUiActive = { isUiActive },
            isActiveSession = { sid -> isStreaming && currentSessionId == sid },
            onResult = { body -> onReadinessResult(body) },
        )
    }
    private val sessionLockClient by lazy {
        com.example.aibrain.controller.SessionLockClient(
            api = api,
            offlineQueue = offlineQueue,
            crashReporter = crashReporter,
            lockMutex = lockExportMutex,
            serverUrl = { getCurrentServerUrl() },
        )
    }
    @Volatile private var pendingExportRevId: String? = null
    private val exportLoadMutex = Mutex()
    private val lockExportMutex = Mutex()
    @Volatile private var autoReportInFlight: Boolean = false
    private var streamErrorStreak: Int = 0
    private var exportNotReady409: Boolean = false
    private var lastAutoReloadAtMs: Long = 0L
    private var pendingAutoReloadRev: String? = null
    private var isUiActive: Boolean = false
    private var pollingSessionId: String? = null
    private var originAnchorNode: AnchorNode? = null
    private var streamSendJob: Job? = null
    private var isArSceneReady = false
    private var isRulerReady = false
    private var arResumed: Boolean = false

    private lateinit var messageCenter: MessageCenter

    // ── Depth ─────────────────────────────────────────────────────────────
    private var depthPolicy: DepthPolicy? = null
    private var depthPrevStrategy: DepthPolicy.Strategy? = null
    private var depthStartHintShown = false
    private var lastReadinessProfile: String? = null
    private var arcoreHintShown = false
    private var currentScanHints: List<String> = emptyList()
    private var scanHintsVisible = false
    private var autoVoxelRefreshJob: Job? = null
    private val gson by lazy { Gson() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Не стартуем AR прямо тут (на API 27 это может привести к падению до install-check).
            // Пусть onResume безопасно сделает install-check и запустит AR при возможности.
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
            runCatching { crashReporter.recordError("UNCAUGHT:${t.name}", e, fatal = true) }
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
        messageCenter = MessageCenter(this, tvAiHint, hintQueue, hintHistory)
        setupClickListeners()

        // Release-device gate should never hard-block dev/testing.
        // In release builds we show a warning, but keep the app alive.
        if (!BuildConfig.DEBUG && !passesReleaseDeviceGate()) {
            showReleaseDeviceUnsupportedDialog()
        }

        // Камера нужна для ARCore / рулетки.
        // Важно: не запускаем AR здесь, чтобы не падать на API 27 до проверок ARCore в onResume().
        if (!hasCameraPermission()) {
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
                    messageCenter.post(getString(R.string.hint_assets_missing), MessageCenter.Level.INFO, MessageCenter.Source.ASSETS)
                }
            }
            result.onFailure { error ->
                hideLoadingDialog()
                Log.e("ModelAssets", "❌ Ошибка загрузки моделей: ${error.message}")
                messageCenter.post(getString(R.string.hint_assets_load_fail), MessageCenter.Level.INFO, MessageCenter.Source.ASSETS)
            }
        }
        viewModel = StructureViewModel(api)
        soundManager = SoundManager(this)
        voxelVisualizer = VoxelVisualizer(sceneView, lifecycleScope)
        // Pass shared soundManager to avoid double SoundPool instance
        physicsAnimator = PhysicsAnimator(sceneView, sceneBuilder, this, soundManager)

        aiVision = AIVisionController(this, scope)
        aiVision.onYoloResults = { dets -> applyYoloResults(dets) }
        aiVision.onHazardDetected = { _ -> vibrate(300) }
        scaffoldRenderer = ScaffoldCylinderRenderer(sceneView, scope)

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
        transitionTo(AppState.IDLE)
        tutorialOverlay = TutorialOverlay(this, tutorialPrefs) { tutorialOverlay?.dismiss() }
        tutorialOverlay?.showIfNeeded()

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
        tvScanCoach = findViewById(R.id.tv_scan_coach)

        // Основные кнопки
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnAddWaypoint = findViewById(R.id.btn_add_waypoint)
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
        viewGridOverlay = findViewById(R.id.view_grid_overlay)
        smartReticle = findViewById(R.id.smart_reticle)
        llHudMetrics = findViewById(R.id.ll_hud_metrics)
        llCoordsPanel = findViewById(R.id.ll_coords_panel)
        llStatusPanel = findViewById(R.id.ll_status_panel)
        undoRedoPanel = findViewById(R.id.undo_redo_panel)

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
        btnRulerExport = findViewById(R.id.btn_ruler_export)
        tvRulerInstruction = findViewById(R.id.tv_ruler_instruction)
        accuracyDot = findViewById(R.id.accuracy_dot)
        tvAccuracy = findViewById(R.id.tv_accuracy)
    }



    private fun ensureArCoreReadyOrExplain(): Boolean {
        // 1) API 27: не падаем, просто показываем сообщение и отключаем AR.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            if (!arcoreHintShown) {
                arcoreHintShown = true
                startActivity(Intent(this, ArNotSupportedActivity::class.java).putExtra(ArNotSupportedActivity.AR_REASON_KEY, ArNotSupportedActivity.REASON_API_TOO_LOW))
            }
            return false
        }

        // 2) Проверка поддержки ARCore на устройстве.
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(this)
        } catch (t: Throwable) {
            Log.e("MainActivity", "ARCore availability check failed: ${t.message}", t)
            if (!arcoreHintShown) {
                arcoreHintShown = true
                startActivity(Intent(this, ArNotSupportedActivity::class.java).putExtra(ArNotSupportedActivity.AR_REASON_KEY, ArNotSupportedActivity.REASON_NOT_INSTALLED))
            }
            return false
        }

        // Иногда состояние transient - это нормально (ARCore еще "думает"). Не спамим ошибками, просто ждём следующий onResume.
        if (availability.isTransient) {
            Log.d("MainActivity", "ARCore availability is transient: $availability")
            return false
        }

        if (!availability.isSupported) {
            if (!arcoreHintShown) {
                arcoreHintShown = true
                startActivity(Intent(this, ArNotSupportedActivity::class.java).putExtra(ArNotSupportedActivity.AR_REASON_KEY, ArNotSupportedActivity.REASON_NOT_SUPPORTED))
            }
            return false
        }

        // 3) Установка/обновление ARCore (Google Play Services for AR).
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // continue
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "ARCore install/request failed: ${t.message}", t)
            if (!arcoreHintShown) {
                arcoreHintShown = true
                startActivity(Intent(this, ArNotSupportedActivity::class.java).putExtra(ArNotSupportedActivity.AR_REASON_KEY, ArNotSupportedActivity.REASON_NOT_INSTALLED))
            }
            return false
        }

        return true
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
            val reason = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) ArNotSupportedActivity.REASON_API_TOO_LOW else ArNotSupportedActivity.REASON_SESSION_FAIL
            startActivity(Intent(this, ArNotSupportedActivity::class.java).putExtra(ArNotSupportedActivity.AR_REASON_KEY, reason))
            return false
        }
        depthPolicy = DepthPolicy(arManager.depthMode).also { it.onSessionStart() }
        depthPrevStrategy = null
        messageCenter.resetSource(MessageCenter.Source.DEPTH)
        messageCenter.resetSource(MessageCenter.Source.AR)

        Log.i("Depth", "Session start: ${depthPolicy?.toMap()}")

        val dp = depthPolicy!!
        if (!dp.supported && !depthStartHintShown) {
            depthStartHintShown = true
            messageCenter.post(getString(R.string.hint_depth_not_supported), MessageCenter.Level.INFO, MessageCenter.Source.DEPTH)
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

            val frame = sceneView.arFrame ?: return@addOnUpdateListener
            val camera = frame.camera

            val centerHits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
            val planeHit = centerHits.firstOrNull { hr ->
                val t = hr.trackable
                t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hr.hitPose)
            }
            val pointHit = centerHits.firstOrNull { hr ->
                val t = hr.trackable
                t is Point && t.trackingState == TrackingState.TRACKING
            }
            val bestHit = planeHit ?: pointHit

            if (::smartReticle.isInitialized) {
                val reticleState: SmartReticleView.State
                val label: String
                val planeType: SmartReticleView.PlaneType

                if (camera.trackingState != TrackingState.TRACKING) {
                    reticleState = SmartReticleView.State.SEARCHING
                    label = "ПОИСК..."
                    planeType = SmartReticleView.PlaneType.UNKNOWN
                } else if (planeHit != null) {
                    val plane = planeHit.trackable as Plane
                    planeType = when (plane.type) {
                        Plane.Type.HORIZONTAL_UPWARD_FACING,
                        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SmartReticleView.PlaneType.FLOOR
                        Plane.Type.VERTICAL -> SmartReticleView.PlaneType.WALL
                        else -> SmartReticleView.PlaneType.UNKNOWN
                    }
                    label = when (planeType) {
                        SmartReticleView.PlaneType.FLOOR -> "ПОЛ"
                        SmartReticleView.PlaneType.WALL -> "СТЕНА"
                        else -> "ПЛОСКОСТЬ"
                    }
                    reticleState = SmartReticleView.State.READY_PLANE
                } else if (pointHit != null) {
                    reticleState = SmartReticleView.State.READY_POINT
                    label = "FEATURE"
                    planeType = SmartReticleView.PlaneType.UNKNOWN
                } else {
                    reticleState = SmartReticleView.State.NO_SURFACE
                    label = "НЕТ ПОВЕРХНОСТИ"
                    planeType = SmartReticleView.PlaneType.UNKNOWN
                }

                runOnUiThread {
                    smartReticle.updateState(reticleState, label, planeType)
                }
            }

            if (::arRuler.isInitialized) {
                arRuler.updateCameraState(camera, bestHit)
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
        btnStart.contentDescription = getString(R.string.btn_start_desc)
        btnAddPoint.contentDescription = getString(R.string.btn_support_desc)
        btnAddWaypoint.contentDescription = getString(R.string.btn_waypoint_desc)
        btnScan.contentDescription = getString(R.string.btn_scan_desc)
        btn3DModel.contentDescription = getString(R.string.btn_model_desc)
        btnAnalyze.contentDescription = getString(R.string.btn_analyze_desc)
        btnRulerMode.contentDescription = getString(R.string.btn_ruler_desc)

        // Основные действия
        btnStart.setOnClickListener { onStartClicked() }
        smartReticle.setOnClickListener { onReticleTapped() }
        btnAddPoint.setOnClickListener { onAddSupportClicked() }
        btnAddWaypoint.setOnClickListener { onAddWaypointClicked() }
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
        btnRulerExport.setOnClickListener { onRulerExportClick() }
        switchGrid.setOnCheckedChangeListener { _, isChecked ->
            viewGridOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (::arRuler.isInitialized) arRuler.setGridEnabled(isChecked)
        }
        switchSnap.setOnCheckedChangeListener { _, isChecked ->
            if (::arRuler.isInitialized) arRuler.setSnapEnabled(isChecked)
        }

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
            runOnUiThread { updateRulerDisplay(distance, label) }
        }
        arRuler.onTrackingQuality = { quality ->
            runOnUiThread { applyTrackingQualityToUI(quality) }
        }

        arRuler.onMeasurementComplete = { measurement ->
            onMeasurementSaved(measurement)
        }
    }

    override fun onDestroy() {
        stopReadinessPolling()
        if (::aiVision.isInitialized) aiVision.release()
        super.onDestroy()
        stopStreaming()
        stopHealthLoop()
        stopAutoVoxelRefresh()
        voxelPollJob?.cancel()
        voxelPollJob = null
        scope.cancel()
        clearARAnchors()
        layerController.clearNodes()

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
            layerController.clearNodes()
            sceneBuilder.clearScene()
            transitionTo(AppState.IDLE)
        }
        if (appState != AppState.IDLE) return

        showHint("⚡ Инициализация системы...")
        transitionTo(AppState.CONNECTING)

        scope.launch { doStartSession() }
    }


    /**
     * Умное размещение якоря по нажатию на прицел.
     * Если опор ещё нет — ставит опору (support), иначе — точку (point).
     * Работает только в состоянии SCANNING.
     */
    private fun onReticleTapped() {
        if (appState != AppState.SCANNING) {
            if (appState == AppState.IDLE) {
                showHint("ℹ️ Нажмите СТАРТ чтобы начать сканирование")
            }
            return
        }
        val currentState = smartReticle.state
        if (currentState == SmartReticleView.State.SEARCHING) {
            showHint("⏳ Подождите — AR ищет поверхности")
            return
        }
        if (currentState == SmartReticleView.State.NO_SURFACE) {
            showHint("⚠️ Наведите прицел на поверхность (пол, стену)")
            vibrate(80)
            return
        }

        val supportCount = userMarkers.count { it.kind == "support" }
        val kind = if (supportCount == 0) "support" else "point"
        placeAnchor(kind = kind)
    }

    private fun onAddSupportClicked() {
        if (appState != AppState.SCANNING) return

        if (userMarkers.size >= MAX_POINTS) {
            showHint("⚠️ Достигнут максимум точек: $MAX_POINTS")
            return
        }

        val supportCount = userMarkers.count { it.kind == "support" }

        if (supportCount == 0) {
            showHint(
                "📍 Первая опора! Теперь обойди вокруг неё (360° за 20-30 сек), " +
                    "держа камеру на 0.5–2 м. Потом ставь остальные опоры по периметру лесов."
            )
        }

        placeAnchor(kind = "support")

        val supports = userMarkers.filter { it.kind == "support" }
        if (supports.size >= 2 && ::scaffoldRenderer.isInitialized) {
            val positions = supports.map { Vector3(it.x, it.y, it.z) }
            scaffoldRenderer.setRootParent(originAnchorNode)
            scaffoldRenderer.buildScaffold(supports = positions, height = 3.0f, levels = 3)
            showHint("🏗️ Превью каркаса обновлено (${supports.size} опоры)")
        }
    }

    private fun onAddWaypointClicked() {
        if (appState != AppState.SCANNING) return

        if (userMarkers.size >= MAX_POINTS) {
            showHint("⚠️ Достигнут максимум точек: $MAX_POINTS")
            return
        }

        if (userMarkers.none { it.kind == "support" }) {
            showHint("ℹ️ Сначала поставь хотя бы 1 ОПОРУ — точки без опоры не дают AI якоря")
            return
        }

        placeAnchor(kind = "point")
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

        val supportCount = userMarkers.count { it.kind == "support" }
        if (supportCount < 1) {
            showHint("📍 Сначала добавьте хотя бы одну опору")
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
                    val mList = runCatching { if (::arRuler.isInitialized) arRuler.getSavedMeasurements().map { m -> MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), arRuler.machineLabel(m), m.timestamp) } else emptyList<MeasurementConstraint>() }.getOrDefault(emptyList())
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
        val measurementConstraints = runCatching { if (::arRuler.isInitialized) arRuler.getSavedMeasurements().map { m -> MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), arRuler.machineLabel(m), m.timestamp) } else emptyList<MeasurementConstraint>() }.getOrDefault(emptyList())
        scope.launch { doRequestModeling(measurementsJson, measurementConstraints) }
    }

    private fun onVariantSelected(index: Int) {
        if (appState != AppState.SELECTING) return

        selectedVariantIndex = index
        variantAdapter.setSelected(index)

        visualizeScaffoldVariant(selectedVariantIndex)

        val option = current3DModel?.options?.getOrNull(index) ?: return
        run {
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

        val option = current3DModel?.options?.getOrNull(selectedVariantIndex)
        if (option == null) {
            showHint("⚠️ Вариант не выбран или список пуст")
            transitionTo(AppState.SCANNING)
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
        // Gather AR-local measurements (outside the network critical section).
        val measurementsJson = runCatching { if (::arRuler.isInitialized) arRuler.exportMeasurements() else "" }.getOrDefault("")
        val measurementConstraints = runCatching {
            if (::arRuler.isInitialized) {
                arRuler.getSavedMeasurements().map { m ->
                    MeasurementConstraint(m.id, m.type.name, m.distance.toDouble(), arRuler.machineLabel(m), m.timestamp)
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        val lockPayload = LockPayload(
            session_id = sid,
            selected_variant = option.variant_name,
            measurements_json = measurementsJson.ifBlank { null },
            manual_measurements = measurementConstraints
        )
        sessionLockClient.lock(sid, lockPayload)?.let { lastRevisionId = it }
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
                if (rev.isNotBlank()) {
                    crashReporter.setLastExportRev(rev)
                    updateFieldDiag()
                }

                val originMissing = layerController.applyBundle(bundle, originAnchorNode)
                if (originMissing) {
                    if (showDialog) showLayersDialog()
                    showHint("⚠️ Сначала поставь origin anchor (кнопка опоры), потом загружай слои")
                    return
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
        val layers = layerController.layers()
        if (layers.isEmpty()) {
            showHint("⚠️ Нет доступных слоёв")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        layers.forEach { layer ->
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
                        val path = layerController.pathFor(layer.id)
                        if (path.isNullOrBlank()) {
                            showHint("⚠️ Нет пути для слоя ${layer.id}")
                        } else {
                            scope.launch { runCatching { layerController.loadOrShow(layer.id, path) } }
                        }
                    } else {
                        layerController.setVisible(layer.id, false)
                    }
                }
            }
            row.addView(label)
            row.addView(sw)
            container.addView(row)
            layerController.setVisible(layer.id, sw.isChecked)
            if (sw.isChecked) {
                val path = layerController.pathFor(layer.id)
                if (!path.isNullOrBlank()) {
                    scope.launch { runCatching { layerController.loadOrShow(layer.id, path) } }
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
            crashReporter.flush(
                api = api,
                sessionId = sessionId,
                connectionStatus = currentConnStatus.name,
                serverBaseUrl = baseUrl,
                lastExportRev = lastRevisionId,
                loadedExportRev = layerController.loadedRevId,
                lastRevisionId = lastRevisionId,
                clientStats = buildClientStats()
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
                kind = marker.kind,
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
                crashReporter.recordError("postAnchors", IllegalStateException("HTTP ${resp.code()}"))
                false
            }
        } catch (e: Exception) {
            offlineQueue.enqueueAnchors(sid, getCurrentServerUrl(), anchors)
            crashReporter.recordError("postAnchors", e)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AR RULER - ОБРАБОТКА КНОПОК
    // ══════════════════════════════════════════════════════════════════════

    private fun toggleRulerMode() {
        if (!::arRuler.isInitialized) {
            // The ruler is created once the AR scene is ready. Try to bring it up
            // now and, if it still isn't ready, tell the user why instead of
            // silently doing nothing (a common "the ruler doesn't work" report).
            startArIfReady()
            if (!::arRuler.isInitialized) {
                showHint("AR ещё не готов — наведите камеру на поверхность и подождите")
                return
            }
        }

        rulerMode = !rulerMode

        if (rulerMode) {
            // Включение режима рулетки
            rulerOverlay.visibility = View.VISIBLE
            controlPanel.visibility = View.GONE

            viewGridOverlay.visibility = if (switchGrid.isChecked) View.VISIBLE else View.GONE

            arRuler.startMeasurement(currentMeasurementType)

            showHint("📏 Режим измерения активен")
            updateModeStatus("ИЗМЕРЕНИЕ")
        } else {
            // Выключение режима рулетки
            rulerOverlay.visibility = View.GONE
            controlPanel.visibility = View.VISIBLE
            viewGridOverlay.visibility = View.VISIBLE

            showHint("📡 Режим сканирования")
            updateModeStatus("СКАНИРОВАНИЕ")
        }
    }

    private fun onRulerMeasureClick() {
        if (!rulerMode) return

        try {
            val frame = sceneView.arFrame ?: run {
            messageCenter.post(getString(R.string.hint_ar_frame_unavailable), MessageCenter.Level.WARN, MessageCenter.Source.AR)
            return
        }
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                val reason = try { camera.trackingFailureReason } catch (_: Exception) { null }
                val msg = when (reason) {
                    TrackingFailureReason.BAD_STATE -> "Трекинг: сбой состояния (перезапустите AR)"
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Трекинг: мало света"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Трекинг: слишком быстрое движение"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Трекинг: мало деталей (наведите на текстуры)"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Трекинг: камера недоступна"
                    else -> "Трекинг не готов - подождите"
                }
                tvRulerInstruction.text = msg
                vibrate(60)
                return
            }

            val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
            val hit = hits.firstOrNull { hr ->
                val t = hr.trackable
                when (t) {
                    is Plane -> t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hr.hitPose)
                    is Point -> t.trackingState == TrackingState.TRACKING
                    else -> false
                }
            }

            if (hit == null) {
                tvRulerInstruction.text = "Не найдено место - наведите на поверхность/детали"
                vibrate(60)
                return
            }

            val quality = arRuler.evaluateQuality(camera, hit)
            applyTrackingQualityToUI(quality)
            if (!quality.canAddPoint) {
                vibrate(80)
                return
            }

            val success = arRuler.addMeasurementPoint(hit, quality.level)

            if (success) {
                if (currentMeasurementType == MeasurementType.HEIGHT
                    && arRuler.getPointCount() == 2
                    && !arRuler.isHeightOrderCorrect()
                ) {
                    tvRulerInstruction.text =
                        "Вторая точка ниже первой. Нажмите «Отмена» и выберите точку выше."
                    arRuler.undoLastPoint()
                    vibrate(120)
                    return
                }

                vibrate(30)

                val pointCount = arRuler.getPointCount()
                tvRulerPointCount.text = "$pointCount"

                val needFinish = when (currentMeasurementType) {
                    MeasurementType.AREA -> false
                    else -> pointCount >= 2
                }
                val showAreaClose = currentMeasurementType == MeasurementType.AREA && pointCount >= 3

                if (needFinish) {
                    btnRulerFinish.visibility = View.VISIBLE
                    btnRulerMeasure.text = "+ ЕЩЁ"
                }
                if (showAreaClose) {
                    btnRulerFinish.visibility = View.VISIBLE
                    btnRulerFinish.text = "Замкнуть"
                    btnRulerMeasure.text = "+ ЕЩЁ"
                }
            } else {
                Toast.makeText(this, "Не удалось установить точку", Toast.LENGTH_SHORT).show()
                vibrate(100)
            }

        } catch (e: Exception) {
            showHint("Ошибка: ${e.message}")
        }
    }

    private fun onRulerUndoClick() {
        arRuler.undoLastPoint()

        val value = arRuler.getCurrentValue()
        updateRulerDisplay(value, arRuler.getCurrentLabel())
    }

    private fun onRulerFinishClick() {
        val measurement = if (currentMeasurementType == MeasurementType.AREA) {
            arRuler.closeAreaAndFinish()
        } else {
            arRuler.finishMeasurement()
        }

        if (measurement != null) {
            showHint("✅ Измерение сохранено: ${measurement.label}")

            tvDistanceValue.text = "0.00 м"
            tvRulerPointCount.text = "0"
            btnRulerFinish.visibility = View.GONE
            btnRulerFinish.text = "✓"
            btnRulerMeasure.text = "+ ТОЧКА"

            vibrate(50)
            arRuler.startMeasurement(currentMeasurementType)
        }
    }

    private fun onRulerExportClick() {
        if (!::arRuler.isInitialized) return
        val json = arRuler.exportMeasurements()
        if (json.isBlank() || json == "{}") {
            showToast(getString(R.string.toast_ruler_no_measurements))
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Экспорт измерений")
            .setItems(arrayOf("Скопировать JSON", "Сохранить в файл", "Поделиться")) { _, which ->
                when (which) {
                    0 -> copyJsonToClipboard(json)
                    1 -> saveJsonToFile(json)
                    2 -> shareJson()
                }
            }
            .show()
    }

    private fun copyJsonToClipboard(json: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("measurements.json", json))
            showToast("JSON скопирован в буфер")
        } catch (_: Exception) {
            showToast("Ошибка копирования")
        }
    }

    private fun saveJsonToFile(json: String) {
        try {
            val store = MeasurementStore(this)
            val file = store.exportToFile()
            showToast("Сохранено: ${file.name}")
        } catch (_: Exception) {
            showToast("Ошибка сохранения")
        }
    }

    private fun shareJson() {
        try {
            val intent = arRuler.buildShareIntent() ?: return
            startActivity(Intent.createChooser(intent, "Поделиться измерениями"))
        } catch (_: Exception) {
            showToast("Ошибка — нет приложения для share")
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
        }

        activeBtn.setBackgroundResource(R.drawable.btn_mode_active)
        activeBtn.setTextColor(ContextCompat.getColor(this, R.color.cyan_primary))

        arRuler.startMeasurement(type)

        val instruction = when (type) {
            MeasurementType.LINEAR ->
                "Нажмите на поверхность — точка A. Нажмите снова — точка B. Нажмите ✓ для сохранения."
            MeasurementType.HEIGHT ->
                "Шаг 1: нажмите на пол (основание). Шаг 2: нажмите на верхнюю точку. Держите телефон вертикально."
            MeasurementType.AREA ->
                "Нажимайте точки по периметру (мин. 3). Когда контур готов — нажмите «Замкнуть»."
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

        val value = arRuler.getCurrentValue()
        updateRulerDisplay(value, arRuler.getCurrentLabel())
    }

    private fun updateRulerDisplay(distance: Float, label: String) {
        tvDistanceValue.text = label
    }

    private fun applyTrackingQualityToUI(quality: TrackingQuality.Result) {
        val (dotColor, label) = when (quality.level) {
            TrackingQuality.Level.HIGH -> Pair(0xFF00FF88.toInt(), "Точность: высокая")
            TrackingQuality.Level.MEDIUM -> Pair(0xFFFFD700.toInt(), "Точность: средняя")
            TrackingQuality.Level.LOW -> Pair(0xFFFF4444.toInt(), quality.hint)
        }
        try { accuracyDot.setBackgroundColor(dotColor) } catch (_: Exception) {}
        try { tvAccuracy.text = label } catch (_: Exception) {}

        if (rulerMode) {
            btnRulerMeasure.isEnabled = quality.canAddPoint
            btnRulerMeasure.alpha = if (quality.canAddPoint) 1f else 0.4f
            if (!quality.canAddPoint) {
                tvRulerInstruction.text = quality.hint
            }
        }
    }

    private fun onMeasurementSaved(measurement: Measurement) {
        Toast.makeText(this, "💾 Измерение: ${measurement.label}", Toast.LENGTH_SHORT).show()
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
                hideControls(btnAddPoint, btnAddWaypoint, btnScan, btn3DModel, btnAnalyze)
                variantPanel.visibility = View.GONE
                btnRulerMode.visibility = View.GONE
                undoRedoPanel.visibility = View.GONE
                setHudScanMode(false)

                messageCenter.setHud(getString(R.string.state_idle))
                updateModeStatus(getString(R.string.state_idle).uppercase())
                stopBlinkAnimation(tvAiHint)
            }

            AppState.CONNECTING -> {
                hideAllControls()
                undoRedoPanel.visibility = View.GONE
                setHudScanMode(false)
                messageCenter.setHud(getString(R.string.state_connecting))
                updateModeStatus("ПОДКЛЮЧЕНИЕ")
                startBlinkAnimation(tvAiHint)
            }

            AppState.SCANNING -> {
                hideControls(btnStart)
                showControls(btnAddPoint, btnAddWaypoint, btnScan, btn3DModel, btnAnalyze)
                btnRulerMode.visibility = View.VISIBLE
                variantPanel.visibility = View.GONE
                undoRedoPanel.visibility = View.VISIBLE
                setHudScanMode(true)

                btnAnalyze.isEnabled = userMarkers.count { it.kind == "support" } >= 1

                messageCenter.setHud(getString(R.string.state_scanning, userMarkers.count { it.kind == "support" }))
                updateModeStatus("СКАНИРОВАНИЕ")
                stopBlinkAnimation(tvAiHint)
            }

            AppState.MODELING -> {
                hideAllControls()
                undoRedoPanel.visibility = View.GONE
                setHudScanMode(false)
                messageCenter.setHud(getString(R.string.state_modeling))
                updateModeStatus("МОДЕЛИРОВАНИЕ")
                startBlinkAnimation(tvAiHint)
            }

            AppState.PREVIEW_3D -> {
                showControls(btnAddPoint, btnAddWaypoint, btnAnalyze)
                hideControls(btnStart, btnScan)
                variantPanel.visibility = View.GONE
                if (::smartReticle.isInitialized) smartReticle.visibility = View.VISIBLE

                messageCenter.setHud(getString(R.string.state_preview))
                updateModeStatus("ПРЕВЬЮ")
            }

            AppState.SELECTING -> {
                hideAllControls()
                variantPanel.visibility = View.VISIBLE

                messageCenter.setHud(getString(R.string.state_selecting))
                updateModeStatus("ВЫБОР")
                stopBlinkAnimation(tvAiHint)
            }

            AppState.RESULTS -> {
                showControls(btnStart)
                btnStart.text = "ЗАНОВО"
                hideControls(btnAddPoint, btnAddWaypoint, btnScan, btn3DModel, btnAnalyze)
                variantPanel.visibility = View.GONE
                undoRedoPanel.visibility = View.GONE
                setHudScanMode(false)

                updateModeStatus("ЗАВЕРШЕНО")
                stopBlinkAnimation(tvAiHint)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (остальной код аналогично предыдущей версии)
    // ══════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // UI SCAN MODE ANIMATION
    // При сканировании сдвигаем боковые панели к краям и тускним метрики,
    // освобождая максимальную площадь камеры. При выходе — возвращаем.
    // ══════════════════════════════════════════════════════════════════════
    private fun setHudScanMode(scanning: Boolean) {
        val dur = 380L
        val density = resources.displayMetrics.density
        if (scanning) {
            // Боковые панели: сдвиг к краям + opacity 30%
            llCoordsPanel.animate()
                .alpha(0.28f)
                .translationX(-20f * density)
                .setDuration(dur)
                .start()
            llStatusPanel.animate()
                .alpha(0.28f)
                .translationX(20f * density)
                .setDuration(dur)
                .start()
            // HUD метрики: чуть тускнеем (сдвиг не нужен — они уже вверху)
            llHudMetrics.animate()
                .alpha(0.45f)
                .setDuration(dur)
                .start()
            // Индикатор режима — прячем, не нужен во время скана
            modeIndicator.animate()
                .alpha(0f)
                .setDuration(dur / 2)
                .withEndAction { modeIndicator.visibility = View.GONE }
                .start()
        } else {
            // Восстанавливаем все
            llCoordsPanel.animate()
                .alpha(0.85f)
                .translationX(0f)
                .setDuration(dur)
                .start()
            llStatusPanel.animate()
                .alpha(0.85f)
                .translationX(0f)
                .setDuration(dur)
                .start()
            llHudMetrics.animate()
                .alpha(1f)
                .setDuration(dur)
                .start()
            modeIndicator.visibility = View.VISIBLE
            modeIndicator.animate()
                .alpha(1f)
                .setDuration(dur / 2)
                .start()
        }
        if (::smartReticle.isInitialized) {
            smartReticle.visibility = if (scanning) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun showControls(vararg buttons: Button) {
        buttons.forEach { it.visibility = View.VISIBLE }
    }

    private fun hideControls(vararg buttons: Button) {
        buttons.forEach { it.visibility = View.GONE }
    }

    private fun hideAllControls() {
        hideControls(btnStart, btnAddPoint, btnAddWaypoint, btnScan, btn3DModel, btnAnalyze)
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

    private fun updateReadinessUI() {
        val r = viewModel.readiness.value
        updateReadinessUI(r.ready, r.score, r.metrics)
    }

    private fun updateReadinessUI(
        ready: Boolean?,
        score: Double?,
        metrics: ReadinessMetrics?
    ) {
        val profileResult = ReadinessProfile.evaluate(
            profileName = lastReadinessProfile ?: depthPolicy?.readinessProfileName(),
            metrics = metrics,
            ready = ready ?: false
        )
        if (originAnchorNode == null) {
            pbReadiness.progress = 0
            tvReadiness.text = "0%"
            tvReadinessDetail.text = "Поставьте опору (кнопка ОПОРА)"
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
        val minObsPct = ((metrics?.min_observed_ratio ?: 0.0) * 100.0).toInt()

        val metricLine =
            "OBS ${obsPct}%/${minObsPct}% | VDIV ${vdiv}/${minViews} | VP ${vp}/${minVp}" +
                    (if (ready == true) " ✅ READY" else "")

        val coachLine: String = if (ready == true) "" else buildReadinessScanHint(metrics)

        val netSuffix = when (currentConnStatus) {
            ConnectionStatus.OFFLINE -> " | NET OFFLINE"
            ConnectionStatus.RECONNECTING -> " | NET RECONNECT"
            else -> ""
        }
        val pollSuffix = buildString {
            if (exportNotReady409) append(" | NO_EXPORT")
            if (exportPoller.failures > 0) append(" | EXP RETRY")
            if (readinessPoller.failures > 0) append(" | RDY RETRY")
        }

        tvReadinessDetail.text = buildString {
            append(metricLine)
            if (netSuffix.isNotEmpty()) append(netSuffix)
            if (pollSuffix.isNotEmpty()) append(pollSuffix)
            append("\n").append(profileResult.explanation)
            if (coachLine.isNotEmpty()) append("\n").append(coachLine)
        }

        val colorRes =
            if (ready == true) android.R.color.holo_green_light else android.R.color.holo_orange_light
        pbReadiness.progressTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        if (ready != true && appState == AppState.SCANNING && coachLine.isNotEmpty()) {
            tvScanCoach.visibility = View.VISIBLE
            tvScanCoach.text = coachLine
        } else if (ready == true) {
            tvScanCoach.visibility = View.GONE
        }

        if (ready != true && s0 >= 0.5 && s0 < 0.9 && coachLine.isNotEmpty()) {
            showHint(coachLine)
        }
    }

    private fun buildReadinessScanHint(metrics: ReadinessMetrics?): String {
        if (metrics == null) return "📡 Сканируй медленно, держи камеру на уровне пояса"
        val obs = ((metrics.observed_ratio) * 100.0).toInt()
        val vdiv = metrics.view_diversity
        val vp = metrics.viewpoints
        val minObs = ((metrics.min_observed_ratio) * 100.0).toInt()
        val minVdiv = metrics.min_views_per_anchor
        val minVp = metrics.min_viewpoints
        return buildString {
            if (obs < minObs) append("👣 Обойди опору полукругом (OBS $obs%→$minObs% нужно)\n")
            if (vdiv < minVdiv) append("🔄 Нужно ещё ${minVdiv - vdiv} ракурсов (60°+ шаг)\n")
            if (vp < minVp) append("📸 Ещё ${minVp - vp} позиций — шагни в сторону")
        }.trim().ifEmpty { "✅ Данных достаточно — нажми АНАЛИЗ" }
    }

    private fun applyYoloResults(detections: List<YoloInferenceEngine.Detection>) {
        lastYoloDetections = detections
        val hazards = detections.filter { it.isHazard }
        if (hazards.isNotEmpty()) {
            vibrate(200)
            showHint("⚠️ ОПАСНОСТЬ: ${hazards.joinToString { it.label.uppercase() }} обнаружен!")
        }
        if (detections.isNotEmpty()) {
            val summary = detections.groupBy { it.label }.entries.joinToString(" | ") { "${it.key}×${it.value.size}" }
            tvFieldDiag?.text = "YOLO: $summary"
        }
    }

    private fun maybeEmitReadinessHints(
        ready: Boolean?,
        reasons: List<String>?,
        metrics: ReadinessMetrics?
    ) {
        if (ready == true) return
        if (metrics == null) return

        val now = System.currentTimeMillis()
        if (now - lastReadinessHintsAtMs < 15000L) return

        val profileResult = ReadinessProfile.evaluate(
            profileName = lastReadinessProfile ?: depthPolicy?.readinessProfileName(),
            metrics = metrics,
            ready = false
        )
        val hints = profileResult.humanReasons.toMutableList()

        for (rs in reasons.orEmpty()) {
            when {
                rs == "EMPTY_WORLD" || rs == "EMPTY_AABB" ->
                    hints += "Подойдите к опоре на 0.5–1.5 м и медленно пройдите рядом."
                rs == "NO_FRAMES" ->
                    hints += "Стрим не активен. Нажмите СТАРТ."
            }
        }

        if (hints.isEmpty()) return
        val hash = hints.joinToString("|")
        if (hash == lastReadinessHintsHash && now - lastReadinessHintsAtMs < 45000L) return
        lastReadinessHintsHash = hash
        lastReadinessHintsAtMs = now
        hints.take(2).forEach { messageCenter.post(it, MessageCenter.Level.INFO, MessageCenter.Source.READINESS) }
    }

    private suspend fun maybeFetchCompatWarnings(sessionId: String) {
        if (viewModel.readiness.value.ready != false) return
        val now = System.currentTimeMillis()
        if (now - lastCompatHintsAtMs < 20000L) return

        val resp = withTimeoutOrNull(1500L) {
            api.requestScaffoldCompat(sessionId)
        } ?: return

        if (!resp.isSuccessful || resp.body() == null) {
            crashReporter.recordReproError(
                endpoint = "/session/" + sessionId + "/request_scaffold",
                httpCode = resp.code(),
                errorSnippet = "request_scaffold failed: ${resp.code()}"
            )
            return
        }

        val body = resp.body()!!

        crashReporter.recordReproResponse(
            endpoint = "/session/" + sessionId + "/request_scaffold",
            httpCode = resp.code(),
            bodySnippet = ReportSanitizer.sanitizeReproBody("/session/" + sessionId + "/request_scaffold", body)
        )

        val warnings = runCatching { body.getAsJsonObject("compat_warnings") }.getOrNull()
        val scanPlan = runCatching { warnings?.getAsJsonArray("scan_plan") }.getOrNull()
        val reasons = runCatching { warnings?.getAsJsonArray("reasons") }.getOrNull()

        val plan = mutableListOf<String>()
        if (scanPlan != null) {
            for (i in 0 until minOf(3, scanPlan.size())) {
                val s = runCatching { scanPlan[i].asString }.getOrNull().orEmpty()
                if (s.isNotBlank()) plan.add(ReportSanitizer.sanitizeText(s, maxLen = 160))
            }
        }
        val rs = mutableListOf<String>()
        if (reasons != null) {
            for (i in 0 until minOf(3, reasons.size())) {
                val s = runCatching { reasons[i].asString }.getOrNull().orEmpty()
                if (s.isNotBlank()) rs.add(ReportSanitizer.sanitizeText(s, maxLen = 64))
            }
        }

        val hints = mutableListOf<String>()
        if (plan.isNotEmpty()) hints.add("📍 Досканируй: " + plan.joinToString(" | "))
        if (rs.isNotEmpty()) hints.add("⚠️ Readiness: " + rs.joinToString(", "))
        if (hints.isEmpty()) return

        val hash = hints.joinToString("|")
        if (hash == lastCompatHintsHash && now - lastCompatHintsAtMs < 60000L) return
        lastCompatHintsHash = hash
        lastCompatHintsAtMs = now

        withContext(Dispatchers.Main) {
            hints.take(2).forEach { showHint(it) }
        }
    }

    private fun maybeShowTutorial() {
        tutorialOverlay = TutorialOverlay(
            activity = this,
            prefs = tutorialPrefs,
            onDone = {
                tutorialOverlay?.dismiss()
                tutorialOverlay = null
            }
        ).also { it.showIfNeeded() }
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
            layerController.setLayersRoot(null)
            layerController.clearAll()
            if (::voxelVisualizer.isInitialized) {
                voxelVisualizer.setRootParent(null)
                voxelVisualizer.hideVoxels()
            }
            currentVoxelData = null
            showHint("⚠️ Origin anchor удалён. Поставь новую опору, чтобы закрепить слои")
        }

        updatePointsCount()
        btnAnalyze.isEnabled = userMarkers.count { it.kind == "support" } >= 1
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

    private fun setHudHint(text: String) {
        try {
            tvAiHint.text = text
        } catch (_: Exception) {
            // ignore
        }
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
        val sup = userMarkers.count { it.kind == "support" }
        val pts = userMarkers.size
        tvPointsCount.text = "ОПОР:$sup | ТОЧЕК:${pts - sup}"
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
                    viewModel.setSessionId(sessionId)
                    rememberSessionInHistory(sessionId)

                    // Reset export/layer state for new session.
                    layerController.reset()
                    exportNotReady409 = false
                    pendingAutoReloadRev = null
                    lastAutoReloadAtMs = 0L

                    consecutiveFailures = 0
                    frameCount = 0

                    viewModel.setConnectionState(ConnectionStatus.ONLINE, base)
                    showHint("✓ Сессия создана")
                    startReadinessPolling(sessionId)
                    syncAnchorsToServer(allowEmpty = true)
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
        streamJob = scope.launch {
            while (isActive && isStreaming && currentSessionId == sid) {
                val nowMs = System.currentTimeMillis()
                if (nowMs < nextStreamAttemptAtMs) {
                    delay(min(streamTuner.intervalMs, nextStreamAttemptAtMs - nowMs))
                    continue
                }
                if (streamSendJob?.isActive == true) {
                    // Backpressure: remember that we need one more tick once current send finishes.
                    streamPendingTick = true
                } else {
                    streamSendJob = launch(Dispatchers.IO) {
                        val t0 = System.nanoTime()
                        val ok = try {
                            withTimeout(2_500L) { sendFrameWith(streamTuner.jpegQuality, streamTuner.pointCap) }
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
                val waitMs = if (streamImmediateNextTick) 0L else streamTuner.intervalMs
                streamImmediateNextTick = false
                delay(waitMs)
            }
        }
    }

    private fun ensureReleasePollingRunning(sessionId: String) {
        // Avoid double start on rotation/resume; restart only if session changed or jobs are not active.
        if (pollingSessionId != sessionId || !exportPoller.isRunning() || !readinessPoller.isRunning()) {
            stopReleasePolling()
            pollingSessionId = sessionId
            startExportLatestPolling(sessionId)
            startReadinessPolling(sessionId)
        }
    }

    private fun startExportLatestPolling(sessionId: String) = exportPoller.start(sessionId)

    /**
     * Handle a confirmed export revision: gate on the origin anchor, then auto-reload
     * the scaffold layers with a cooldown to avoid thrashing. The loaded revision is
     * owned by `layerController`; the cooldown bookkeeping (`pendingAutoReloadRev`,
     * `lastAutoReloadAtMs`) stays here as it is specific to this auto-reload policy.
     */
    private suspend fun onExportLatestRevision(sessionId: String, rev: String) {
        if (originAnchorNode == null) {
            pendingExportRevId = rev
            return
        }

        val now2 = System.currentTimeMillis()
        if (layerController.loadedRevId == null) {
            // First seen rev, try loading if origin exists.
            pendingExportRevId = rev
            lastAutoReloadAtMs = now2
            pendingAutoReloadRev = null
            loadExportLayersInternal(sessionId, showDialog = false, showOkHint = false)
        } else if (layerController.loadedRevId != rev) {
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
    }

    private fun startReadinessPolling(sessionId: String) = readinessPoller.start(sessionId)

    /** Main-thread render after each readiness poll (body == null on failure). */
    private fun onReadinessResult(body: ReadinessResponse?) {
        if (body != null) lastReadinessProfile = body.readiness_profile
        updateReadinessUI()
        if (body != null) maybeEmitReadinessHints(body.ready, body.reasons, body.readiness_metrics)
    }

    private fun stopReadinessPolling() = readinessPoller.stop()

    private fun stopReleasePolling() {
        exportPoller.stop()
        readinessPoller.stop()
        exportNotReady409 = false
        pendingAutoReloadRev = null
        pollingSessionId = null
    }

    private fun tuneStreaming(ok: Boolean, sendMs: Long) = streamTuner.onResult(ok, sendMs)

    private suspend fun sendFrame(): Boolean = sendFrameWith(streamTuner.jpegQuality, streamTuner.pointCap)

    private suspend fun sendFrameWith(jpegQuality: Int, pointCap: Int): Boolean {
        val sid = currentSessionId ?: return false

        data class FramePacket(
            val payload: HashMap<String, Any>,
            val yuv: ImageUtils.Yuv420Copy,
            val swapUv: Boolean,
            val depthRequested: Boolean,
            val depth: DepthUtils.AcquiredDepthImage?
        )

        // ── ФАЗА 1: capture на Main thread (ARCore требует Main для frame API) ──────────
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
            var acquiredDepth: DepthUtils.AcquiredDepthImage? = null
            try {
                val cam = frame.camera
                if (cam.trackingState != TrackingState.TRACKING) return@withContext null

                val image = try {
                    frame.acquireCameraImage()
                } catch (_: Exception) {
                    null
                } ?: return@withContext null

                // YUV copy — единственная тяжёлая операция здесь.
                // Минимизируем время: копируем planes сразу пока Image жив, затем close().
                val swapUv = settingsPrefs.getBoolean(PREF_CAMERA_SWAP_UV, false)
                val yuvCopy = try {
                    // Copy planes quickly on main thread while Image is valid.
                    ImageUtils.copyYuv420(image)
                } finally {
                    runCatching { image.close() }
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

                if (lastYoloDetections.isNotEmpty()) {
                    basePayload["yolo_detections"] = aiVision.detectionsToServerPayload(lastYoloDetections)
                }

                val dp = depthPolicy
                val shouldSendDepth = dp?.shouldAttemptDepth() == true
                if (shouldSendDepth) {
                    acquiredDepth = DepthUtils.tryAcquireDepth16(frame)
                }
                val depthReceived = acquiredDepth != null
                dp?.onFrame(depthReceived)

                basePayload["depth_supported"] = dp?.supported == true
                basePayload["depth_strategy"] = dp?.currentStrategy()?.name ?: "UNKNOWN"
                if (!depthReceived && shouldSendDepth) basePayload["depth_unavailable"] = true
                basePayload["readiness_profile_hint"] = dp?.readinessProfileName() ?: "NoDepth"

                FramePacket(basePayload, yuvCopy, swapUv, shouldSendDepth, acquiredDepth)
            } catch (_: Exception) {
                runCatching { acquiredDepth?.image?.close() }
                null
            }
        }

        if (packet == null) return true

        withContext(Dispatchers.Main) {
            val dp = depthPolicy ?: return@withContext
            val newStrategy = dp.currentStrategy()
            val hint = dp.uiHint(depthPrevStrategy)
            if (hint != null) {
                messageCenter.post(hint, MessageCenter.Level.INFO, MessageCenter.Source.DEPTH)
                if (newStrategy == DepthPolicy.Strategy.NO_DEPTH) {
                    messageCenter.setHud("Без depth — нужно больше ракурсов")
                }
                Log.i("Depth", "Strategy changed: ${depthPrevStrategy} -> $newStrategy (rate=${dp.availableRate()})")
            }
            depthPrevStrategy = newStrategy
        }

        // ── ФАЗА 2: encode на Default (уже верно через HeavyOps) ─────────────────────
        val payload = withContext(Dispatchers.Default) {
            packet.payload.apply {
                this["client_stats"] = mapOf(
                    "jpeg_quality" to jpegQuality,
                    "point_cap" to pointCap,
                    "send_interval_ms" to streamTuner.intervalMs,
                    "last_send_ms" to lastSendMs,
                    "conn" to currentConnStatus.name,
                ) + (depthPolicy?.toMap() ?: emptyMap())
                HeavyOps.withPermit {
                    val nv21 = ImageUtils.yuvCopyToNv21(packet.yuv, swapUv = packet.swapUv)
                    this["rgb_base64"] = ImageUtils.nv21ToJpegBase64(nv21.data, nv21.width, nv21.height, jpegQuality)
                    if (::aiVision.isInitialized) aiVision.processFrame(this["rgb_base64"] as String)
                    val acquired = packet.depth
                    if (acquired != null) {
                        try {
                            val depthFrame = DepthUtils.copyDepth16(acquired.image, acquired.isRaw)
                            // Uniform downsample if oversized (preserves aspect ratio).
                            this.putAll(
                                DepthUtils.buildDepthPayload(depthFrame, DepthPolicy.MAX_DEPTH_PAYLOAD_BYTES)
                            )
                        } finally {
                            runCatching { acquired.image.close() }
                        }
                    }
                }
            }
        }

        val resp = try {
            api.streamData(sid, payload)
        } catch (e: Exception) {
            crashReporter.recordException("streamData", e)
            crashReporter.recordReproError(
                endpoint = "/session/" + sid + "/stream",
                errorSnippet = ReportSanitizer.sanitizeText(e.message ?: "exception", maxLen = 2048)
            )
            return false
        }

        if (!resp.isSuccessful) {
            val errSnippet = runCatching { resp.errorBody()?.string() }.getOrNull()?.take(2048)
            crashReporter.recordError("streamData", "HTTP ${resp.code()}")
            crashReporter.recordReproError(
                endpoint = "/session/" + sid + "/stream",
                httpCode = resp.code(),
                errorSnippet = ReportSanitizer.sanitizeText(errSnippet ?: ("HTTP " + resp.code()), maxLen = 2048)
            )
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
                    if (userMarkers.count { it.kind == "support" } >= 1 && !hasRedZones) startPlayButtonPulse()
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
                if (userMarkers.count { it.kind == "support" } >= 1) btnAnalyze.isEnabled = true
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
                            if (::voxelVisualizer.isInitialized) voxelVisualizer.setRootParent(originAnchorNode)
                            if (::voxelVisualizer.isInitialized) voxelVisualizer.showVoxels(currentVoxelData!!)
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

        // Optional: ask compat endpoint for scan_plan hints when readiness isn't met.
        runCatching { maybeFetchCompatWarnings(sid) }

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
            val opts = model.options.orEmpty()

            if (model.status == "NEEDS_SCAN" || opts.isEmpty()) {
                current3DModel = null
                val coachMsg = buildNeedsScanHint(model.reasons.orEmpty(), viewModel.readiness.value.metrics)
                showHint(coachMsg)
                updateReadinessUI(false, viewModel.readiness.value.score, viewModel.readiness.value.metrics)
                transitionTo(AppState.SCANNING)
                startStreamingLoop()
                return@withContext
            }

            current3DModel = model
            selectedVariantIndex = 0
            transitionTo(AppState.SELECTING)
            ensureReleasePollingRunning(sid)
            variantAdapter.submit(opts, selected = 0)
            onVariantSelected(0)
            if (measurementConstraints.isNotEmpty()) showHint("📐 ${measurementConstraints.size} измерений использовано")
        }
    }


    /**
     * Формирует конструктивное сообщение "что делать руками" при NEEDS_SCAN.
     * Избегает красных toast-ошибок; работает как обучающая подсказка.
     */
    private fun buildNeedsScanHint(
        reasons: List<String>,
        metrics: ReadinessMetrics?
    ): String {
        if (reasons.isEmpty()) return "📡 Продолжай сканировать — держи камеру на опоре 20-30 сек"

        val steps = reasons.mapNotNull { reason ->
            when {
                reason.startsWith("LOW_OBSERVED_RATIO") -> {
                    val obs = ((metrics?.observed_ratio ?: 0.0) * 100.0).toInt()
                    val minObs = ((metrics?.min_observed_ratio ?: 0.0) * 100.0).toInt()
                    "👣 Обойди опору полукругом (OBS ${obs}%→${minObs}% нужно)"
                }

                reason.startsWith("LOW_VIEW_DIVERSITY") -> {
                    val vd = metrics?.view_diversity ?: 0
                    val minVd = metrics?.min_views_per_anchor ?: 0
                    "🔄 Обойди с разных углов (60°+ шаг) — ${vd}/${minVd} ракурсов"
                }

                reason.startsWith("LOW_VIEWPOINTS") -> {
                    val vp = metrics?.viewpoints ?: 0
                    val minVp = metrics?.min_viewpoints ?: 0
                    "📸 Ещё ${(minVp - vp).coerceAtLeast(1)} позици${if ((minVp - vp) == 1) "я" else "и"}" +
                            " — шагни в сторону или наклони камеру вверх/вниз"
                }

                reason == "EMPTY_WORLD" || reason == "EMPTY_AABB" ->
                    "🚶 Подойди на 0.5-1.5 м к опоре и пройди вокруг"

                reason == "NO_FRAMES" ->
                    "📡 Стрим прерывался — нажми START и снова начни скан"

                else -> null
            }
        }

        return if (steps.isEmpty()) {
            "📡 Сканируй ещё 20-30 сек, держи камеру стабильно"
        } else {
            steps.take(2).joinToString("\n")
        }
    }

    private fun placeAnchor(kind: String) {
        if (userMarkers.size >= MAX_POINTS) {
            showHint("⚠️ Достигнут лимит точек")
            return
        }

        val frame = sceneView.arFrame ?: run {
            messageCenter.post(getString(R.string.hint_ar_frame_unavailable), MessageCenter.Level.WARN, MessageCenter.Source.AR)
            return
        }
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) {
            messageCenter.post(getString(R.string.hint_tracking_unstable), MessageCenter.Level.WARN, MessageCenter.Source.AR)
            vibrate(150)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val reason = camera.trackingFailureReason
            if (reason != com.google.ar.core.TrackingFailureReason.NONE) {
                val msg = when (reason) {
                    com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES ->
                        "Мало контрастных точек. Направьте камеру на текстурную поверхность."
                    com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION ->
                        "Слишком быстрое движение. Двигайтесь медленнее."
                    com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT ->
                        "Недостаточно освещения."
                    else -> "Трекинг нестабилен: $reason"
                }
                showHint("⚠️ $msg")
                return
            }
        }

        val x = sceneView.width / 2f
        val y = sceneView.height / 2f

        val hits = frame.hitTest(x, y)
        val planeHit = hits.firstOrNull { hr ->
            val t = hr.trackable
            (t is Plane) && t.isPoseInPolygon(hr.hitPose)
        }
        val pointHit = hits.firstOrNull { hr -> hr.trackable is Point }
        val hit = planeHit ?: pointHit

        if (hit == null) {
            showHint("⚠️ Не найдено место для ${if (kind == "support") "опоры" else "точки"}")
            return
        }
        if (kind == "support" && planeHit == null) {
            showHint("ℹ️ Плоскость не найдена. Опора поставлена по feature points (лучше наведитесь на пол/стену).")
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
        if (kind == "support" && originAnchorNode == null) {
            originAnchorNode = anchorNode
            layerController.setLayersRoot(originAnchorNode)
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
                kind = kind,
                x = p.tx(),
                y = p.ty(),
                z = p.tz()
            )
        )
        anchorNode.name = markerId
        anchorMarkerNodes[markerId] = marker
        marker.setOnTapListener { _, _ -> confirmDeleteAnchor(markerId) }

        updatePointsCount()
        btnAnalyze.isEnabled = userMarkers.count { it.kind == "support" } >= 1
        vibrate(35)
        if (::smartReticle.isInitialized) smartReticle.flashPlacement()

        // Синхронизируем anchors фоном
        scope.launch {
            try {
                syncAnchorsToServer()
            } catch (_: Exception) {
                // Ignore
            }
        }

        showHint("✓ " + (if (kind == "support") "Опора" else "Точка") + " добавлена: ${userMarkers.size}")
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
        layerController.reset()
        pendingExportRevId = null
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
        messageCenter.post(message, MessageCenter.Level.BLOCKER, MessageCenter.Source.UI, setAsHud = true)
    }

    private fun showWarning(message: String) {
        messageCenter.post(message, MessageCenter.Level.WARN, MessageCenter.Source.UI)
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
            showToast(getString(R.string.toast_undo, snapshot.description))
        }
    }

    private fun performRedo() {
        viewModel.redo { snapshot ->
            sceneBuilder.buildScene(snapshot.elements)
            showToast(getString(R.string.toast_redo, snapshot.description))
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
        layerController.setLayersRoot(null)
        // Can happen if activity is destroyed before voxelVisualizer init completes
        // (or if AR crashed during early startup). Never crash in onDestroy().
        if (::voxelVisualizer.isInitialized) {
            voxelVisualizer.setRootParent(null)
            voxelVisualizer.hideVoxels()
        }
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
            .setTitle("Предупреждение")
            .setMessage(
                "Этот билд рассчитан на ARCore-совместимые устройства (обычно Android 10+ и >=6ГБ RAM). " +
                        "Если ARCore на устройстве работает (другие AR-приложения запускаются) - можно продолжать; " +
                        "это предупреждение не должно блокировать тестирование."
            )
            .setCancelable(false)
            .setPositiveButton("Продолжить") { _, _ -> /* no-op */ }
            .setNegativeButton("Закрыть") { _, _ -> finish() }
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

        // Если arResumed уже true — значит AR не был запаузен (например, пришёл повторный onResume
        // без onPause, что теоретически возможно при configuration change). Не дёргаем resume повторно.
        if (arResumed) {
            Log.w("Lifecycle", "onResume called while arResumed=true — skipping AR resume to avoid double-resume crash")
            return
        }

        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        // API 27: не падаем, показываем ошибку и живём дальше.
        // Samsung/реальные девайсы: если ARCore отсутствует/недоступен - показываем ошибку и не стартуем камеру/сцену.
        if (!ensureArCoreReadyOrExplain()) {
            return
        }

        startArIfReady()

        // Не дергаем resume(), если AR так и не смог стартовать (например, setupSession() вернул false).
        if (isArSceneReady) {
            try {
                sceneView.resume()
                arResumed = true
            } catch (e: CameraNotAvailableException) {
                arResumed = false
                Log.e("MainActivity", "Camera not available on resume", e)
                // Не "ошибка" — пользователь просто переключился из другого приложения с камерой.
                showWarning("Камера занята. Закройте другое приложение с камерой и вернитесь.")
            }
        }

        if (eyeOfAIActive) {
            val sid = currentSessionId
            if (!sid.isNullOrBlank()) startAutoVoxelRefresh(sid)
        }
    }

    override fun onPause() {
        isUiActive = false
        stopReleasePolling()
        stopReadinessPolling()
        stopStreaming()
        stopAutoVoxelRefresh()
        super.onPause()
        if (arResumed) {
            // sceneView.pause() только если AR реально был в resumed-состоянии.
            // Двойной pause() → "RET_CHECK failure in scheduler.cc" в ARCore.
            runCatching {
                sceneView.pause()
                Log.d("Lifecycle", "sceneView.pause() OK")
            }.onFailure { e ->
                Log.e("Lifecycle", "sceneView.pause() failed: ${e.message}", e)
            }
            arResumed = false
        }
    }



}
