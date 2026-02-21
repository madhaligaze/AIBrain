package com.example.aibrain.visualization

import android.util.Log
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.NodeParent
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ArSceneView
import com.example.aibrain.util.HeavyOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.google.ar.sceneform.rendering.Color as SceneColor

/**
 * Визуализатор воксельной сетки ("Глаз ИИ").
 */
class VoxelVisualizer(
    private val sceneView: ArSceneView,
    private val coroutineScope: CoroutineScope,
) {
    private val voxelNodes = mutableListOf<Node>()
    private var isVisible = false
    private val materials = mutableMapOf<String, Material>()
    private var rootParent: NodeParent? = null

    fun setRootParent(parent: NodeParent?) {
        rootParent = parent
        val p: NodeParent = parent ?: sceneView.scene
        voxelNodes.forEach { it.setParent(p) }
    }

    fun showVoxels(voxelData: List<VoxelData>) {
        if (isVisible) hideVoxels()

        coroutineScope.launch(Dispatchers.Main) {
            HeavyOps.withPermit {
                if (materials.isEmpty()) loadMaterials()

                val source = if (voxelData.size > MAX_VOXELS) {
                    val step = voxelData.size / MAX_VOXELS
                    voxelData.filterIndexed { idx, _ -> idx % step == 0 }.take(MAX_VOXELS)
                } else {
                    voxelData
                }

                Log.d(TAG, "Rendering ${source.size} voxels (raw=${voxelData.size})")

                var batchCount = 0
                for (voxel in source) {
                    if (!isActive) break
                    createVoxelNode(voxel)
                    batchCount++
                    if (batchCount >= BATCH_SIZE) {
                        batchCount = 0
                        yield()
                    }
                }

                isVisible = true
            }
        }
    }

    fun hideVoxels() {
        voxelNodes.forEach { it.setParent(null) }
        voxelNodes.clear()
        isVisible = false
    }

    fun toggleVisibility(voxelData: List<VoxelData>? = null) {
        if (isVisible) hideVoxels() else voxelData?.let { showVoxels(it) }
    }

    private suspend fun loadMaterials() {
        val ctx = sceneView.context
        materials["obstacle"] = MaterialFactory.makeTransparentWithColor(ctx, SceneColor(1.0f, 0.2f, 0.2f, 0.7f)).await()
        materials["structure"] = MaterialFactory.makeTransparentWithColor(ctx, SceneColor(0.2f, 0.5f, 1.0f, 0.5f)).await()
        materials["available"] = MaterialFactory.makeTransparentWithColor(ctx, SceneColor(0.2f, 1.0f, 0.2f, 0.3f)).await()
        materials["forbidden"] = MaterialFactory.makeTransparentWithColor(ctx, SceneColor(1.0f, 1.0f, 0.2f, 0.5f)).await()
        materials["ground"] = MaterialFactory.makeTransparentWithColor(ctx, SceneColor(0.5f, 0.5f, 0.5f, 0.3f)).await()
    }

    private fun createVoxelNode(voxel: VoxelData) {
        val mat = materials[voxel.type] ?: materials["available"] ?: return

        val size = if (voxel.type == "forbidden" && voxel.radius != null) {
            voxel.radius * 2f
        } else {
            voxel.size ?: 0.25f
        }

        val renderable = ShapeFactory.makeCube(Vector3(size, size, size), Vector3.zero(), mat)
        val parent: NodeParent = rootParent ?: sceneView.scene

        val node = Node().apply {
            this.renderable = renderable
            worldPosition = Vector3(voxel.position[0], voxel.position[1], voxel.position[2])
            setParent(parent)
        }
        voxelNodes.add(node)
    }

    fun animatePulse() { /* TODO */ }

    private companion object {
        const val TAG = "VoxelVisualizer"
        const val MAX_VOXELS = 500
        const val BATCH_SIZE = 50
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
