package com.example.aibrain.managers

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlin.math.abs

/**
 * Thin helper over SceneView's [ARSceneView]. SceneView self-manages the ARCore
 * session (creation, lifecycle, camera config, light estimation), so this no
 * longer creates a Session by hand — it only supplies the session [Config] via
 * [configure] and offers anchor/surface helpers. The old Sceneform light-mode
 * reflection workarounds are gone (SceneView/Filament handles HDR estimation).
 */
class ARSessionManager(
    private val sceneView: ARSceneView,
) {
    var lastSurfaceType: SurfaceType = SurfaceType.UNKNOWN
        private set
    private var noSurfaceStreak = 0

    enum class SurfaceType { FLOOR, WALL, CEILING, UNKNOWN }

    private var currentAnchorNode: AnchorNode? = null

    var depthMode: Config.DepthMode = Config.DepthMode.DISABLED
        private set

    val isDepthCapable: Boolean get() = (depthMode != Config.DepthMode.DISABLED)

    /** Configure the session SceneView manages. Safe to call before AR resumes. */
    fun configure() {
        sceneView.configureSession { session, config ->
            depthMode = when {
                session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                else -> Config.DepthMode.DISABLED
            }
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.depthMode = depthMode
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            Log.i(TAG, "Session configured. depthMode=$depthMode")
        }
    }

    fun addAnchor(anchor: Anchor): AnchorNode {
        currentAnchorNode?.let { runCatching { sceneView.removeChildNode(it) } }
        val node = AnchorNode(sceneView.engine, anchor)
        sceneView.addChildNode(node)
        currentAnchorNode = node
        return node
    }

    fun clearScene() {
        currentAnchorNode?.let { runCatching { sceneView.removeChildNode(it) } }
        currentAnchorNode = null
    }

    fun classifySurface(frame: Frame, screenX: Float, screenY: Float): SurfaceType {
        val hits = frame.hitTest(screenX, screenY)
        val planeHit = hits.firstOrNull { hr ->
            val t = hr.trackable
            t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hr.hitPose)
        }
        if (planeHit == null) {
            noSurfaceStreak++
            return SurfaceType.UNKNOWN
        }

        noSurfaceStreak = 0
        val plane = planeHit.trackable as Plane
        val type = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> SurfaceType.FLOOR
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SurfaceType.CEILING
            Plane.Type.VERTICAL -> SurfaceType.WALL
            else -> {
                val pose = planeHit.hitPose
                val yAxis = pose.getYAxis()
                when {
                    abs(yAxis[1]) > 0.7f && yAxis[1] > 0f -> SurfaceType.FLOOR
                    abs(yAxis[1]) > 0.7f && yAxis[1] < 0f -> SurfaceType.CEILING
                    else -> SurfaceType.WALL
                }
            }
        }
        lastSurfaceType = type
        return type
    }

    fun isTrackingStable(frame: Frame): Boolean {
        if (frame.camera.trackingState != TrackingState.TRACKING) return false
        val pc = runCatching { frame.acquirePointCloud() }.getOrNull()
        val pointCount = pc?.points?.remaining()?.div(4) ?: 0
        runCatching { pc?.release() }
        return pointCount >= 5
    }

    fun isConsistentlyNoSurface(threshold: Int = 10): Boolean = noSurfaceStreak >= threshold

    private companion object {
        const val TAG = "ARSessionManager"
    }
}
