package com.example.aibrain.effects

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.CoroutineScope

/**
 * TODO(sceneview-migration): re-implement particle effects with SceneView
 * `SphereNode`s (or a custom Filament renderable). Temporarily a no-op so the
 * build compiles — purely cosmetic. See SCENEVIEW_MIGRATION.md.
 */
class ParticleSystem(
    private val sceneView: ARSceneView,
    private val coroutineScope: CoroutineScope,
) {
    fun createSparks(position: Float3, count: Int = 30) { /* no-op pending SceneView reimpl */ }
    fun createDustCloud(position: Float3, intensity: Float = 1.0f) { /* no-op */ }
    fun createShockwave(position: Float3) { /* no-op */ }
}
