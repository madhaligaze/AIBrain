package com.example.aibrain.scene

import com.google.ar.sceneform.Camera
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable

/**
 * Автопереключение детализации в зависимости от расстояния до камеры.
 */
class LODManager(private val camera: Camera) {

    companion object {
        private const val LOD_HIGH_DISTANCE = 3.0f
        private const val LOD_MEDIUM_DISTANCE = 10.0f
        private const val LOD_LOW_DISTANCE = 20.0f
    }

    fun updateLOD(nodes: List<Pair<Node, LODSet>>) {
        val cameraPos = camera.worldPosition

        nodes.forEach { (node, lodSet) ->
            val distance = Vector3.subtract(node.worldPosition, cameraPos).length()
            val target = when {
                distance < LOD_HIGH_DISTANCE -> lodSet.high
                distance < LOD_MEDIUM_DISTANCE -> lodSet.medium
                distance < LOD_LOW_DISTANCE -> lodSet.low
                else -> null
            }

            if (node.renderable != target) {
                node.renderable = target
            }
        }
    }

    data class LODSet(
        val high: ModelRenderable,
        val medium: ModelRenderable,
        val low: ModelRenderable
    )
}
