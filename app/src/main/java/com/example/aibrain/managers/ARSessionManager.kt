package com.example.aibrain.managers

import android.content.Context
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
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
        val existingSession = sceneView.session
        if (existingSession != null) {
            applyConfig(existingSession)
            sceneView.planeRenderer.isVisible = true
            Log.d(TAG, "Re-configured existing session. depthMode=$depthMode")
            return true
        }

        val session = try {
            Session(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ARCore Session: ${e.message}", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "CameraConfig selection failed (non-fatal): ${e.message}")
        }

        applyConfig(session)

        return try {
            sceneView.session = session
            sceneView.planeRenderer.isVisible = true
            Log.i(TAG, "ARCore Session created. depthMode=$depthMode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sceneView.session assignment failed: ${e.message}", e)
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

        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            this.depthMode = this@ARSessionManager.depthMode
        }

        try {
            session.configure(config)
        } catch (e: Exception) {
            Log.e(TAG, "session.configure() failed: ${e.message}", e)
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
