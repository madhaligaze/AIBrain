package com.example.aibrain.assets

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Singleton для управления 3D моделями.
 *
 * Важно:
 * - Пути вида "models/foo.glb" должны лежать в assets/, а не в файловой системе устройства.
 * - Если ассетов нет (например, в репо пока заглушки), приложение не должно падать:
 *   просто работает в упрощённом режиме (процедурные примитивы).
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

    private val modelMap: List<Pair<ModelType, String>> = listOf(
        ModelType.LAYHER_STANDARD_2M to "models/layher_standard_2m.glb",
        ModelType.LAYHER_LEDGER_207 to "models/layher_ledger_207.glb",
        ModelType.LAYHER_DIAGONAL_300 to "models/layher_diagonal_300.glb",
        ModelType.LAYHER_DECK_STEEL to "models/layher_deck_steel.glb",
        ModelType.LAYHER_DECK_WOOD to "models/layher_deck_wood.glb",
        ModelType.WEDGE_NODE to "models/wedge_node.glb"
    )

    suspend fun loadAll(context: Context): Result<Unit> = withContext(Dispatchers.Main) {
        if (isLoaded) return@withContext Result.success(Unit)

        cache.clear()

        var loadedAny = false

        for ((type, path) in modelMap) {
            if (!assetExists(context, path)) {
                // Not an error in dev builds: just means assets not bundled yet.
                Log.w("ModelAssets", "⚠️ asset not found: $path (skipping)")
                continue
            }
            try {
                cache[type] = loadModelFromAssets(context, path).await()
                loadedAny = true
            } catch (e: Exception) {
                Log.e("ModelAssets", "❌ Failed to load $path", e)
            }
        }

        isLoaded = loadedAny

        // Never hard-fail the app because models are optional (procedural fallback exists).
        Result.success(Unit)
    }

    fun getCopy(type: ModelType): ModelRenderable? = cache[type]?.makeCopy()

    fun getOriginal(type: ModelType): ModelRenderable? = cache[type]

    fun isReady(): Boolean = isLoaded

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadModelFromAssets(context: Context, assetPath: String): CompletableDeferred<ModelRenderable> {
        val deferred = CompletableDeferred<ModelRenderable>()

        // Sceneform: asset URIs must use "file:///android_asset/"
        val assetUri = Uri.parse("file:///android_asset/$assetPath")

        ModelRenderable.builder()
            .setSource(context, assetUri)
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable: ModelRenderable -> deferred.complete(renderable) }
            .exceptionally { throwable: Throwable ->
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
