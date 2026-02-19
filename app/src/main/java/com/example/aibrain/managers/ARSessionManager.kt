package com.example.aibrain.managers

import android.content.Context
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.sceneform.AnchorNode
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.node.ModelNode

class ARSessionManager(
    private val context: Context,
    private val sceneView: ArSceneView
) {
    private var currentAnchorNode: AnchorNode? = null
    private val placedModels = mutableListOf<ModelNode>()

    fun setupSession() {
        sceneView.apply {
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            planeRenderer.isVisible = true
            depthEnabled = true
        }
    }

    fun addAnchor(anchor: Anchor) {
        currentAnchorNode?.let { sceneView.scene.removeChild(it) }
        val anchorNode = AnchorNode(anchor)
        sceneView.scene.addChild(anchorNode)
        currentAnchorNode = anchorNode
    }

    fun renderModel(modelUrl: String, scale: Float = 1.0f) {
        currentAnchorNode?.let { anchor ->
            val modelNode = ModelNode(
                context = context,
                assetFileLocation = modelUrl,
                scaleToUnits = scale
            )
            anchor.addChild(modelNode)
            placedModels.add(modelNode)
        }
    }

    fun clearScene() {
        placedModels.forEach { it.destroy() }
        placedModels.clear()
        currentAnchorNode?.let { sceneView.scene.removeChild(it) }
        currentAnchorNode = null
    }
}
