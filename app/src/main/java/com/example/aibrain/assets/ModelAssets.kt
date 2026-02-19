package com.example.aibrain.assets

import android.content.Context
import android.net.Uri
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Singleton для управления 3D моделями.
 */
object ModelAssets {

    private val cache = mutableMapOf<ModelType, ModelRenderable>()
    private var isLoaded = false

    enum class ModelType {
        LAYHER_STANDARD_2M,
        LAYHER_LEDGER_207,
        LAYHER_DIAGONAL_300,
        LAYHER_DECK_STEEL,
        LAYHER_DECK_WOOD,
        WEDGE_NODE
    }

    suspend fun loadAll(context: Context): Result<Unit> = withContext(Dispatchers.Main) {
        if (isLoaded) return@withContext Result.success(Unit)

        try {
            cache[ModelType.LAYHER_STANDARD_2M] = loadModel(context, "models/layher_standard_2m.glb").await()
            cache[ModelType.LAYHER_LEDGER_207] = loadModel(context, "models/layher_ledger_207.glb").await()
            cache[ModelType.LAYHER_DIAGONAL_300] = loadModel(context, "models/layher_diagonal_300.glb").await()
            cache[ModelType.LAYHER_DECK_STEEL] = loadModel(context, "models/layher_deck_steel.glb").await()
            cache[ModelType.LAYHER_DECK_WOOD] = loadModel(context, "models/layher_deck_wood.glb").await()
            cache[ModelType.WEDGE_NODE] = loadModel(context, "models/wedge_node.glb").await()

            isLoaded = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCopy(type: ModelType): ModelRenderable? = cache[type]?.makeCopy()

    fun getOriginal(type: ModelType): ModelRenderable? = cache[type]

    fun isReady(): Boolean = isLoaded

    private suspend fun loadModel(context: Context, path: String): CompletableDeferred<ModelRenderable> {
        val deferred = CompletableDeferred<ModelRenderable>()

        ModelRenderable.builder()
            .setSource(context, Uri.parse(path))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable -> deferred.complete(renderable) }
            .exceptionally { throwable ->
                deferred.completeExceptionally(throwable)
                null
            }

        return deferred
    }

    fun clear() {
        cache.clear()
        isLoaded = false
    }
}
