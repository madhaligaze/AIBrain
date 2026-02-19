package com.example.aibrain.managers

import android.content.Context
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.sceneform.AnchorNode
import io.github.sceneview.ar.ArSceneView

class ARSessionManager(
    private val context: Context,
    private val sceneView: ArSceneView
) {
    private var currentAnchorNode: AnchorNode? = null

    fun setupSession() {
        sceneView.apply {
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            planeRenderer.isVisible = true
            depthEnabled = true
        }
    }

    fun addAnchor(anchor: Anchor) {
        currentAnchorNode?.let { it.setParent(null) }
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(sceneView)
        currentAnchorNode = anchorNode
    }

    fun renderModel(modelUrl: String, scale: Float = 1.0f) {
        // Model rendering is handled directly in MainActivity/SceneBuilder.
    }

    fun clearScene() {
        currentAnchorNode?.let { it.setParent(null) }
        currentAnchorNode = null
    }
}
