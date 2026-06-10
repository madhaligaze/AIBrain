package com.example.aibrain.scaffold

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlinx.coroutines.CoroutineScope

/**
 * TODO(sceneview-migration): re-implement with SceneView `CylinderNode`
 * (geometry builder + `materialLoader.createColorInstance`) and a shortest-arc
 * quaternion to orient each member — see SCENEVIEW_MIGRATION.md and tune on device.
 *
 * Temporarily a no-op so the build compiles. The *primary* scaffold visualisation
 * is the server-exported GLB layers (rendered by [com.example.aibrain.scene.LayerGlbManager]),
 * which IS migrated; this local cylinder fallback is secondary.
 */
class ScaffoldCylinderRenderer(
    private val sceneView: ARSceneView,
    private val scope: CoroutineScope,
) {
    private var rootParent: AnchorNode? = null

    fun setRootParent(anchor: AnchorNode?) {
        rootParent = anchor
    }

    fun buildScaffold(supports: List<Float3>, height: Float = 3.0f, levels: Int = 3) {
        // no-op pending SceneView re-implementation
    }

    fun clearAll() {
        // no-op
    }
}
