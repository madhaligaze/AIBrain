package com.example.aibrain.scene

import com.example.aibrain.HeatmapItem
import com.example.aibrain.ScaffoldElement
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.Node

/**
 * Tracks the scaffold elements and (TODO) renders local Layher model nodes.
 *
 * The element bookkeeping (used by undo/redo snapshots via [getAllElements]) is
 * fully preserved. The node rendering is stubbed for the SceneView migration —
 * the *primary* scaffold visualisation is the server-exported GLB layers
 * ([LayerGlbManager]); re-implement local model nodes with SceneView `ModelNode`s
 * + a shortest-arc orientation when needed. See SCENEVIEW_MIGRATION.md.
 */
class SceneBuilder(private val sceneView: ARSceneView) {

    private val allElements = mutableListOf<ScaffoldElement>()

    fun preloadModels(onReady: (() -> Unit)? = null) {
        onReady?.invoke()
    }

    fun buildScene(elements: List<ScaffoldElement>) {
        allElements.clear()
        allElements.addAll(elements)
        // TODO(sceneview-migration): render local Layher model nodes here.
    }

    fun clearScene() {
        allElements.clear()
    }

    fun findNodeById(id: String): Node? = null

    fun getAllElements(): List<ScaffoldElement> = allElements.toList()

    fun removeElement(elementId: String) {
        allElements.removeAll { it.id == elementId }
    }

    fun updateHeatmap(heatmap: List<HeatmapItem>) { /* TODO: per-element material tint */ }

    fun updateColors(heatmap: List<Map<String, Any>>) { /* TODO */ }

    fun updateElementColor(elementId: String, loadRatio: Double) { /* TODO */ }
}

data class FlangeOffset(
    val bottom: Float = 0f,
    val top: Float = 0f,
    val start: Float = 0f,
    val end: Float = 0f,
    val nodePositions: List<Float> = emptyList()
)
