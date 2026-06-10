package com.example.aibrain.scene

import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.Node

/**
 * SceneView/Filament provides a default main light + image-based environment and
 * applies ARCore light estimation automatically, so the old manual Sceneform
 * directional/point/spot light rig is no longer needed. Kept as no-ops to preserve
 * the call sites. See SCENEVIEW_MIGRATION.md. Custom lights, if wanted later, are
 * `io.github.sceneview.node.LightNode`.
 */
object LightingSetup {

    fun setupLighting(sceneView: ARSceneView, anchorNode: Node) {
        // no-op: SceneView handles lighting + AR light estimation
    }

    fun updateLightingForTimeOfDay(hour: Int) {
        // no-op
    }
}
