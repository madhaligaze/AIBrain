package com.example.aibrain.visualization

import android.graphics.Color
import com.example.aibrain.ar.SceneGeometry
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope

/**
 * Voxel debug overlay ("Eye of AI") — renders the scanned occupancy/unknown
 * voxels as small SceneView spheres. Debug aid (not core), so it caps the count
 * to keep Filament happy.
 */
class VoxelVisualizer(
    private val sceneView: ARSceneView,
    private val coroutineScope: CoroutineScope,
) {
    private val nodes = mutableListOf<Node>()
    private var rootParent: Node? = null
    private var visible = false

    fun setRootParent(parent: Node?) {
        rootParent = parent
    }

    fun showVoxels(voxelData: List<VoxelData>) {
        hideVoxels()
        val engine = sceneView.engine
        val parent = rootParent
        voxelData.asSequence().take(MAX_VOXELS).forEach { v ->
            val p = v.position
            if (p.size < 3) return@forEach
            val baseColor = runCatching { Color.parseColor(v.color) }.getOrDefault(DEFAULT_COLOR)
            val a = (v.alpha.coerceIn(0f, 1f) * 255f).toInt()
            val argb = (a shl 24) or (baseColor and 0x00FFFFFF)
            val mat = SceneGeometry.material(sceneView.materialLoader, argb)
            val r = v.radius ?: v.size ?: 0.04f
            val node = SceneGeometry.sphere(engine, mat, r)
            val pos = Float3(p[0], p[1], p[2])
            if (parent != null) {
                node.position = pos
                parent.addChildNode(node)
            } else {
                node.worldPosition = pos
                sceneView.addChildNode(node)
            }
            nodes.add(node)
        }
        visible = true
    }

    fun hideVoxels() {
        nodes.forEach { n -> runCatching { n.parent?.removeChildNode(n) ?: sceneView.removeChildNode(n) } }
        nodes.clear()
        visible = false
    }

    fun toggleVisibility(voxelData: List<VoxelData>? = null) {
        if (visible) hideVoxels() else voxelData?.let { showVoxels(it) }
    }

    fun animatePulse() { /* optional cosmetic — left as a no-op */ }

    private companion object {
        const val MAX_VOXELS = 400
        val DEFAULT_COLOR = 0xFF5AA9E6.toInt()
    }
}

data class VoxelData(
    val position: List<Float>,
    val type: String,
    val color: String,
    val alpha: Float,
    val size: Float? = null,
    val radius: Float? = null,
)
