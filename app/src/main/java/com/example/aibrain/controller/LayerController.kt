package com.example.aibrain.controller

import android.content.Context
import android.content.SharedPreferences
import com.example.aibrain.SceneBundleResponse
import com.example.aibrain.UiLayer
import com.example.aibrain.scene.LayerGlbManager
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlinx.coroutines.CoroutineScope

/**
 * Owns the scaffold export "layers": the [LayerGlbManager] (GLB rendering),
 * the parsed layer list/paths and the loaded revision id. MainActivity keeps the
 * network fetch and the layers dialog (Android UI), but all layer state and scene
 * rendering goes through here, so the dozens of scattered `layerGlbManager?.…`
 * calls collapse to a single owner.
 */
class LayerController(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val scope: CoroutineScope,
    private val settingsPrefs: SharedPreferences,
    private val serverUrl: () -> String,
) {
    private var manager: LayerGlbManager? = null
    private var layers: List<UiLayer> = emptyList()
    private val paths = mutableMapOf<String, String>()

    var loadedRevId: String? = null
        private set

    fun layers(): List<UiLayer> = layers
    fun pathFor(id: String): String? = paths[id]

    private fun ensureManager(): LayerGlbManager =
        manager ?: LayerGlbManager(context, sceneView, serverUrl()).also { manager = it }

    /**
     * Parse [bundle], cache its layers, and render the default-on layers under
     * [origin]. Returns true if [origin] is null (caller should prompt the user to
     * place the origin anchor first).
     */
    suspend fun applyBundle(bundle: SceneBundleResponse, origin: AnchorNode?): Boolean {
        val rev = bundle.revision_id ?: bundle.rev_id.orEmpty()
        if (rev.isNotBlank() && loadedRevId != null && loadedRevId != rev) {
            manager?.clearAll()
        }
        if (rev.isNotBlank()) loadedRevId = rev

        val m = ensureManager()
        m.setCurrentRevision(rev)

        val ls = bundle.ui?.layers.orEmpty()
        layers = ls
        paths.clear()
        for (layer in ls) {
            val p = layer.file?.glb?.path ?: layer.file?.path
            if (!p.isNullOrBlank()) paths[layer.id] = p
        }

        if (origin == null) return true

        m.setLayersRoot(origin)
        for (layer in ls) {
            val p = paths[layer.id]
            if (p.isNullOrBlank()) continue
            val wantVisible = settingsPrefs.getBoolean("layer_visible_${layer.id}", layer.default_on ?: true)
            if (wantVisible) runCatching { m.loadOrShowLayer(layer.id, p) }
            else m.setVisible(layer.id, false)
        }
        return false
    }

    fun setLayersRoot(node: AnchorNode?) {
        manager?.setLayersRoot(node)
    }

    suspend fun loadOrShow(id: String, path: String) {
        ensureManager().loadOrShowLayer(id, path)
    }

    fun setVisible(id: String, visible: Boolean) {
        manager?.setVisible(id, visible)
    }

    fun clearNodes() {
        manager?.clearNodes()
    }

    fun clearAll() {
        manager?.clearAll()
    }

    /** Full reset of layer state and scene nodes (new session / origin reset). */
    fun reset() {
        loadedRevId = null
        layers = emptyList()
        paths.clear()
        manager?.clearAll()
    }
}
