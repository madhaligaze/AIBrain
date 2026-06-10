package com.example.aibrain.visualization

import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope

/**
 * TODO(sceneview-migration): re-implement voxel debug rendering with SceneView
 * `CubeNode`/`SphereNode`. Temporarily a no-op so the build compiles — this is a
 * debug overlay ("Eye of AI"), not core functionality. See SCENEVIEW_MIGRATION.md.
 */
class VoxelVisualizer(
    private val sceneView: ARSceneView,
    private val coroutineScope: CoroutineScope,
) {
    private var rootParent: Node? = null

    fun setRootParent(parent: Node?) { rootParent = parent }
    fun showVoxels(voxelData: List<VoxelData>) { /* no-op pending SceneView reimpl */ }
    fun hideVoxels() { /* no-op */ }
    fun toggleVisibility(voxelData: List<VoxelData>? = null) { /* no-op */ }
    fun animatePulse() { /* no-op */ }
}

data class VoxelData(
    val position: List<Float>,
    val type: String,
    val color: String,
    val alpha: Float,
    val size: Float? = null,
    val radius: Float? = null,
)
