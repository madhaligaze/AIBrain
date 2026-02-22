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

class ARSessionManager(
    private val context: Context,
    private val sceneView: ArSceneView,
) {
    private var currentAnchorNode: AnchorNode? = null
    var depthMode: Config.DepthMode = Config.DepthMode.DISABLED
        private set

    fun setupSession(): Boolean {
        // Жесткая защита от падений на API 27 (Android 8.1) и ниже.
        // На этих версиях в некоторых связках ARCore/Camera2 возможны NoSuchFieldError и нативные краши.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "AR disabled: SDK_INT=${Build.VERSION.SDK_INT} < 28")
            return false
        }

        val existingSession = sceneView.session
        if (existingSession != null) {
            applyConfig(existingSession)
            applySceneViewLightEstimationMode(selectSafeLightEstimationMode())
            sceneView.planeRenderer.isVisible = true
            Log.d(TAG, "Re-configured existing session. depthMode=$depthMode")
            return true
        }

        val session = try {
            Session(context)
        } catch (t: Throwable) {
            // Важно: ловим Throwable, т.к. NoSuchFieldError/UnsatisfiedLinkError и т.п. не являются Exception.
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

        // Sceneform 1.23.0 может падать на некоторых версиях ARCore при ENVIRONMENTAL_HDR.
        // Поэтому выставляем безопасный режим прямо в ArSceneView (через reflection, чтобы не зависеть от конкретной реализации).
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
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
            else -> Config.DepthMode.DISABLED
        }

        // Sceneform 1.23.0 может падать с NoSuchMethodError на ENVIRONMENTAL_HDR из-за
        // несовпадения сигнатуры LightEstimate.acquireEnvironmentalHdrCubeMap() в разных версиях ARCore.
        // Самый надежный способ: включать HDR только если в текущем ARCore реально есть ожидаемый метод
        // (именно с return type = com.google.ar.core.ArImage[]).
        val canUseEnvironmentalHdr = try {
            val m = LightEstimate::class.java.getMethod("acquireEnvironmentalHdrCubeMap")
            val rt = m.returnType
            rt.isArray && rt.componentType?.name == "com.google.ar.core.ArImage"
        } catch (t: Throwable) {
            false
        }

        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            lightEstimationMode = if (canUseEnvironmentalHdr) {
                Log.i(TAG, "LightEstimation: ENVIRONMENTAL_HDR enabled (compatible ARCore detected)")
                Config.LightEstimationMode.ENVIRONMENTAL_HDR
            } else {
                // Радикально надежно: исключаем путь, который роняет приложение на старте.
                Log.w(TAG, "LightEstimation: ENVIRONMENTAL_HDR disabled (incompatible ARCore). Using AMBIENT_INTENSITY")
                Config.LightEstimationMode.AMBIENT_INTENSITY
            }
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            this.depthMode = this@ARSessionManager.depthMode
        }

        try {
            session.configure(config)
        } catch (t: Throwable) {
            Log.e(TAG, "session.configure() failed: ${t.message}", t)
        }
    }

    private fun selectSafeLightEstimationMode(): Config.LightEstimationMode {
        // В Sceneform Maintained 1.23.0 вызов Environmental HDR идёт через
        // LightEstimate.acquireEnvironmentalHdrCubeMap() с сигнатурой, зависящей от версии ARCore.
        // Если сигнатура не совпадает (часто возвращает Image[] вместо ArImage[]), получаем NoSuchMethodError.
        return try {
            val m = com.google.ar.core.LightEstimate::class.java.methods.firstOrNull { it.name == "acquireEnvironmentalHdrCubeMap" }
            val rt = m?.returnType
            val ok = rt != null && rt.isArray && rt.componentType?.name == "com.google.ar.core.ArImage"
            if (ok) Config.LightEstimationMode.ENVIRONMENTAL_HDR else Config.LightEstimationMode.AMBIENT_INTENSITY
        } catch (_: Throwable) {
            Config.LightEstimationMode.AMBIENT_INTENSITY
        }
    }

    private fun applySceneViewLightEstimationMode(mode: Config.LightEstimationMode) {
        // Пытаемся вызвать public API, если оно есть.
        try {
            val m = sceneView.javaClass.methods.firstOrNull {
                it.name == "setLightEstimationMode" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "com.google.ar.core.Config\$LightEstimationMode"
            }
            if (m != null) {
                m.invoke(sceneView, mode)
                Log.d(TAG, "ArSceneView.setLightEstimationMode($mode)")
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLightEstimationMode failed (non-fatal): ${t.message}")
        }

        // Fallback: пробуем напрямую выставить поле, если библиотека хранит его как field.
        try {
            val f = sceneView.javaClass.declaredFields.firstOrNull { it.name == "lightEstimationMode" }
            if (f != null) {
                f.isAccessible = true
                f.set(sceneView, mode)
                Log.d(TAG, "ArSceneView.lightEstimationMode = $mode (reflection)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "lightEstimationMode field set failed (non-fatal): ${t.message}")
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
