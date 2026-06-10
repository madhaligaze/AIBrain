package com.example.aibrain.assets

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TODO(sceneview-migration): load bundled Layher GLBs as SceneView
 * `FilamentInstance`s via `sceneView.modelLoader.loadModelInstance("models/x.glb")`.
 * Stubbed for now — these local models are optional (the primary scaffold
 * visualisation is the server-exported GLB layers). See SCENEVIEW_MIGRATION.md.
 */
object ModelAssets {

    enum class ModelType {
        LAYHER_STANDARD_2M,
        LAYHER_LEDGER_207,
        LAYHER_DIAGONAL_300,
        LAYHER_DECK_STEEL,
        LAYHER_DECK_WOOD,
        WEDGE_NODE
    }

    suspend fun loadAll(context: Context): Result<Unit> = withContext(Dispatchers.Main) {
        Result.success(Unit)
    }

    fun isReady(): Boolean = false

    fun clear() { /* no-op */ }
}
