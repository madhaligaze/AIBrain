package com.example.aibrain.managers

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.gorisse.thomas.sceneform.light.LightEstimationConfig

class ARSessionManager(
    private val context: Context,
    private val sceneView: ArSceneView,
) {
    private var currentAnchorNode: AnchorNode? = null
    var depthMode: Config.DepthMode = Config.DepthMode.DISABLED
        private set

    /** True если устройство способно выдавать depth — не зависит от текущего frame state. */
    val isDepthCapable: Boolean get() = (depthMode != Config.DepthMode.DISABLED)

    fun setupSession(): Boolean {
        // Жёсткая защита от падений на API 27 (Android 8.1) и ниже.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "AR disabled: SDK_INT=${Build.VERSION.SDK_INT} < 28")
            return false
        }

        val existingSession = sceneView.session
        if (existingSession != null) {
            applyConfig(existingSession)
            // [FIXED] Применяем безопасный режим освещения ПОСЛЕ конфигурации сессии
            applySceneViewLightEstimationMode(selectSafeLightEstimationMode())
            sceneView.planeRenderer.isVisible = true
            Log.d(TAG, "Re-configured existing session. depthMode=$depthMode")
            return true
        }

        val session = try {
            Session(context)
        } catch (t: Throwable) {
            // Ловим Throwable, т.к. NoSuchFieldError/UnsatisfiedLinkError не являются Exception.
            Log.e(TAG, "Failed to create ARCore Session: ${t.message}", t)
            return false
        }

        try {
            val filter = CameraConfigFilter(session)
            val configs = session.getSupportedCameraConfigs(filter)
            val best = configs.firstOrNull { it.fpsRange.upper >= 30 } ?: configs.firstOrNull()
            if (best != null) {
                session.cameraConfig = best
                Log.d(TAG, "CameraConfig: ${best.imageSize} @ ${best.fpsRange} FPS")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CameraConfig selection failed (non-fatal): ${t.message}")
        }

        applyConfig(session)

        // [FIXED] Применяем режим освещения Sceneform до установки session в sceneView.
        // Без этого Sceneform использует ENVIRONMENTAL_HDR по умолчанию и крашится
        // при первом кадре на ARCore версиях, где acquireEnvironmentalHdrCubeMap() отсутствует.
        applySceneViewLightEstimationMode(selectSafeLightEstimationMode())

        return try {
            sceneView.session = session
            sceneView.planeRenderer.isVisible = true
            Log.i(TAG, "ARCore Session created. depthMode=$depthMode")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "sceneView.session assignment failed: ${t.message}", t)
            session.close()
            false
        }
    }

    private fun applyConfig(session: Session) {
        depthMode = when {
            session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)      -> Config.DepthMode.AUTOMATIC
            else -> Config.DepthMode.DISABLED
        }

        // Логируем полную информацию о depth capability один раз при конфигурации сессии.
        Log.i(TAG, buildString {
            append("Depth capability check: ")
            append("RAW_DEPTH_ONLY=${session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)} ")
            append("AUTOMATIC=${session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)} ")
            append("→ selected depthMode=$depthMode")
        })

        // Проверяем наличие acquireEnvironmentalHdrCubeMap() в текущем ARCore рантайме.
        // ARCore 1.34.0+ deprecated этот метод; 1.39.0 может его не иметь совсем.
        val canUseEnvironmentalHdr = try {
            val m = LightEstimate::class.java.getMethod("acquireEnvironmentalHdrCubeMap")
            val rt = m.returnType
            rt.isArray && rt.componentType?.name == "com.google.ar.core.ArImage"
        } catch (t: Throwable) {
            false
        }

        val config = Config(session).apply {
            focusMode           = Config.FocusMode.AUTO
            lightEstimationMode = if (canUseEnvironmentalHdr) {
                Log.i(TAG, "LightEstimation: ENVIRONMENTAL_HDR enabled (compatible ARCore detected)")
                Config.LightEstimationMode.ENVIRONMENTAL_HDR
            } else {
                Log.w(TAG, "LightEstimation: ENVIRONMENTAL_HDR DISABLED — acquireEnvironmentalHdrCubeMap() not found. Using AMBIENT_INTENSITY.")
                Config.LightEstimationMode.AMBIENT_INTENSITY
            }
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode       = Config.UpdateMode.LATEST_CAMERA_IMAGE
            this.depthMode   = this@ARSessionManager.depthMode
        }

        try {
            session.configure(config)
        } catch (t: Throwable) {
            Log.e(TAG, "session.configure() failed: ${t.message}", t)
        }
    }

    private fun selectSafeLightEstimationMode(): Config.LightEstimationMode {
        return try {
            val m = LightEstimate::class.java.methods
                .firstOrNull { it.name == "acquireEnvironmentalHdrCubeMap" }
            val rt = m?.returnType
            val ok = rt != null && rt.isArray &&
                    rt.componentType?.name == "com.google.ar.core.ArImage"
            if (ok) Config.LightEstimationMode.ENVIRONMENTAL_HDR
            else    Config.LightEstimationMode.AMBIENT_INTENSITY
        } catch (_: Throwable) {
            Config.LightEstimationMode.AMBIENT_INTENSITY
        }
    }

    /**
     * [FIXED] Применяет режим освещения в Sceneform ArSceneView.
     *
     * СТАРЫЙ КОД ПРОБЛЕМА: Искал метод setLightEstimationMode(Config$LightEstimationMode) —
     * такого метода нет! Sceneform использует свой тип SceneformLightMode.
     * В результате reflection возвращал null, режим не менялся, Sceneform оставался
     * в ENVIRONMENTAL_HDR → краш при первом AR-кадре.
     *
     * НОВЫЙ КОД: Прямой вызов sceneView.lightEstimationMode = SceneformLightMode.X,
     * с fallback на reflection с правильным типом параметра.
     */
    private fun applySceneViewLightEstimationMode(mode: Config.LightEstimationMode) {
        val lightConfig = when (mode) {
            Config.LightEstimationMode.AMBIENT_INTENSITY -> LightEstimationConfig.AMBIENT_INTENSITY
            Config.LightEstimationMode.ENVIRONMENTAL_HDR -> LightEstimationConfig.AMBIENT_INTENSITY
            else -> LightEstimationConfig.DISABLED
        }

        try {
            sceneView._lightEstimationConfig = lightConfig
            Log.i(TAG, "sceneView._lightEstimationConfig = $lightConfig ✅")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "Direct _lightEstimationConfig set failed (${t.message}), trying reflection fallback")
        }

        applyViaReflectionFallback(lightConfig)
    }

    /**
     * [FIXED] Reflection fallback с правильным типом параметра.
     * Старый код: parameterTypes[0].name == "com.google.ar.core.Config$LightEstimationMode" — НЕВЕРНО
     * Новый код: isAssignableFrom(SceneformLightMode::class.java) — ВЕРНО
     */
    private fun applyViaReflectionFallback(lightConfig: LightEstimationConfig) {
        try {
            val setter = sceneView.javaClass.methods.firstOrNull {
                it.name == "setLightEstimationConfig" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(LightEstimationConfig::class.java)
            }
            if (setter == null) {
                Log.e(TAG, "setLightEstimationConfig(LightEstimationConfig) not found in ArSceneView! " +
                        "HDR crash risk if Sceneform defaults to ENVIRONMENTAL_HDR.")
                val anyGetter = sceneView.javaClass.methods
                    .firstOrNull { it.name == "setLightEstimationConfig" && it.parameterCount == 1 }
                if (anyGetter != null) {
                    anyGetter.invoke(sceneView, lightConfig)
                    Log.d(TAG, "setLightEstimationConfig via any-type reflection OK")
                }
                return
            }
            setter.invoke(sceneView, lightConfig)
            Log.d(TAG, "setLightEstimationConfig($lightConfig) via typed reflection OK ✅")
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection fallback FAILED: ${t.message}. App may crash on first AR frame!", t)
        }
    }

    fun addAnchor(anchor: Anchor): AnchorNode {
        currentAnchorNode?.setParent(null)
        val anchorNode = AnchorNode(anchor).also { it.setParent(sceneView.scene) }
        currentAnchorNode = anchorNode
        return anchorNode
    }

    fun clearScene() {
        currentAnchorNode?.setParent(null)
        currentAnchorNode = null
    }

    private companion object {
        const val TAG = "ARSessionManager"
    }
}