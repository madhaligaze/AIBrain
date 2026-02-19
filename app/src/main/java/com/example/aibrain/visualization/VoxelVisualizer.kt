package com.example.aibrain.visualization

import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import com.google.ar.sceneform.rendering.Color as SceneColor

/**
 * Визуализатор воксельной сетки ("Глаз ИИ").
 */
class VoxelVisualizer(
    private val sceneView: ArSceneView,
    private val coroutineScope: CoroutineScope
) {
    private val voxelNodes = mutableListOf<Node>()
    private var isVisible = false
    private val materials = mutableMapOf<String, Material>()

    fun showVoxels(voxelData: List<VoxelData>) {
        if (isVisible) {
            hideVoxels()
        }

        coroutineScope.launch {
            if (materials.isEmpty()) {
                loadMaterials()
            }

            voxelData.forEach { voxel ->
                createVoxelNode(voxel)
            }

            isVisible = true
        }
    }

    fun hideVoxels() {
        voxelNodes.forEach { it.setParent(null) }
        voxelNodes.clear()
        isVisible = false
    }

    fun toggleVisibility(voxelData: List<VoxelData>? = null) {
        if (isVisible) {
            hideVoxels()
        } else {
            voxelData?.let { showVoxels(it) }
        }
    }

    private suspend fun loadMaterials() {
        val context = sceneView.context

        materials["obstacle"] = MaterialFactory.makeTransparentWithColor(
            context,
            SceneColor(1.0f, 0.2f, 0.2f, 0.7f)
        ).await()

        materials["structure"] = MaterialFactory.makeTransparentWithColor(
            context,
            SceneColor(0.2f, 0.5f, 1.0f, 0.5f)
        ).await()

        materials["available"] = MaterialFactory.makeTransparentWithColor(
            context,
            SceneColor(0.2f, 1.0f, 0.2f, 0.3f)
        ).await()

        materials["forbidden"] = MaterialFactory.makeTransparentWithColor(
            context,
            SceneColor(1.0f, 1.0f, 0.2f, 0.5f)
        ).await()

        materials["ground"] = MaterialFactory.makeTransparentWithColor(
            context,
            SceneColor(0.5f, 0.5f, 0.5f, 0.3f)
        ).await()
    }

    private fun createVoxelNode(voxel: VoxelData) {
        val material = materials[voxel.type] ?: materials["available"] ?: return

        val renderable = if (voxel.type == "forbidden" && voxel.radius != null) {
            ShapeFactory.makeCube(Vector3(voxel.radius * 2f, voxel.radius * 2f, voxel.radius * 2f), Vector3.zero(), material)
        } else {
            val size = voxel.size ?: 0.25f
            ShapeFactory.makeCube(Vector3(size, size, size), Vector3.zero(), material)
        }

        val node = Node().apply {
            this.renderable = renderable
            worldPosition = Vector3(voxel.position[0], voxel.position[1], voxel.position[2])
        }

        node.setParent(sceneView)
        voxelNodes.add(node)
    }

    fun animatePulse() {
        // TODO: Анимация вокселей при необходимости.
    }
}

data class VoxelData(
    val position: List<Float>,
    val type: String,
    val color: String,
    val alpha: Float,
    val size: Float? = null,
    val radius: Float? = null
)
