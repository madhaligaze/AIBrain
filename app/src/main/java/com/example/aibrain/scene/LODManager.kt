package com.example.aibrain.scene

import io.github.sceneview.node.Node

/**
 * TODO(sceneview-migration): re-implement level-of-detail swapping with SceneView
 * model instances. Temporarily a no-op so the build compiles — LOD is an
 * optimisation, not core functionality. See SCENEVIEW_MIGRATION.md.
 */
class LODManager(private val cameraNode: Node?) {

    fun updateLOD(nodes: List<Pair<Node, LODSet>>) {
        // no-op pending SceneView re-implementation
    }

    data class LODSet(
        val high: Any?,
        val medium: Any?,
        val low: Any?,
    )
}
