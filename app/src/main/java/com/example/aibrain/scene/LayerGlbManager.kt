package com.example.aibrain.scene

import android.content.Context
import android.util.Log
import com.example.aibrain.util.HeavyOps
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Loads scaffold export layers (GLB) and renders them under the origin anchor.
 * Migrated to SceneView/Filament: GLB → `modelLoader.createModelInstance(buffer)`
 * → [ModelNode]; nodes attach under the origin [AnchorNode] (or the scene). All
 * download/disk-cache/cleanup logic is unchanged.
 */
class LayerGlbManager(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val baseUrl: String,
) {
    sealed class LayerState {
        object NotLoaded : LayerState()
        object Loading : LayerState()
        data class Loaded(val visible: Boolean) : LayerState()
        data class Error(val reason: String) : LayerState()
    }

    var onStateChanged: ((layerId: String, state: LayerState) -> Unit)? = null

    private val layerStates = mutableMapOf<String, LayerState>()
    private val nodesByLayerId = mutableMapOf<String, ModelNode>()
    private val cachedFilePath = mutableMapOf<String, File>()
    private var layersRoot: AnchorNode? = null

    private var currentRevisionSafe: String = "rev_none"
    private val keepLatestRevs: Int = 5
    private val keepLatestLayerFilesPerRev: Int = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun setCurrentRevision(revId: String?) {
        currentRevisionSafe = sanitizeRevId(revId)
        cleanupOldRevisions()
    }

    private fun attach(node: Node) {
        val root = layersRoot
        if (root != null) root.addChildNode(node) else sceneView.addChildNode(node)
    }

    private fun detach(node: Node) {
        node.parent?.removeChildNode(node) ?: runCatching { sceneView.removeChildNode(node) }
    }

    fun setLayersRoot(anchor: AnchorNode?) {
        layersRoot = anchor
        nodesByLayerId.values.forEach { node ->
            detach(node)
            node.position = Float3(0f, 0f, 0f)
            node.quaternion = Quaternion(0f, 0f, 0f, 1f)
            node.scale = Float3(1f, 1f, 1f)
            attach(node)
        }
    }

    suspend fun loadOrShowLayer(layerId: String, relativePath: String) {
        when (val st = layerStates[layerId]) {
            is LayerState.Loaded -> { setVisible(layerId, true); return }
            is LayerState.Loading -> return
            is LayerState.Error -> Log.w(TAG, "Layer $layerId had error '${st.reason}', retrying")
            else -> Unit
        }
        loadLayerSafe(layerId, relativePath)
    }

    suspend fun loadLayer(layerId: String, relativePath: String): Node {
        loadOrShowLayer(layerId, relativePath)
        return nodesByLayerId[layerId] ?: throw IllegalStateException("Layer $layerId failed to load")
    }

    private suspend fun loadLayerSafe(layerId: String, relativePath: String) {
        setState(layerId, LayerState.Loading)

        val cacheFile = cachedFilePath[layerId]?.takeIf { it.exists() } ?: try {
            withContext(Dispatchers.IO) { downloadToCache(layerId, relativePath) }
        } catch (e: Exception) {
            setState(layerId, LayerState.Error("Download failed: ${e.message}"))
            return
        }
        cachedFilePath[layerId] = cacheFile

        val node = try {
            withContext(Dispatchers.Main) {
                val bytes = cacheFile.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                val instance = sceneView.modelLoader.createModelInstance(buffer)
                    ?: throw IllegalStateException("createModelInstance returned null")
                ModelNode(modelInstance = instance).apply {
                    position = Float3(0f, 0f, 0f)
                    quaternion = Quaternion(0f, 0f, 0f, 1f)
                    scale = Float3(1f, 1f, 1f)
                }
            }
        } catch (e: Exception) {
            setState(layerId, LayerState.Error("Model build failed: ${e.message}"))
            return
        }

        withContext(Dispatchers.Main) {
            nodesByLayerId.remove(layerId)?.let { detach(it) }
            attach(node)
            nodesByLayerId[layerId] = node
            setState(layerId, LayerState.Loaded(visible = true))
        }
    }

    fun setVisible(layerId: String, visible: Boolean) {
        val node = nodesByLayerId[layerId] ?: return
        node.isVisible = visible
        if (layerStates[layerId] is LayerState.Loaded) {
            val state = LayerState.Loaded(visible)
            layerStates[layerId] = state
            onStateChanged?.invoke(layerId, state)
        }
    }

    fun toggleLayer(layerId: String): Boolean {
        val current = (layerStates[layerId] as? LayerState.Loaded)?.visible ?: false
        setVisible(layerId, !current)
        return !current
    }

    fun showAllLayers() = nodesByLayerId.keys.forEach { setVisible(it, true) }
    fun hideAllLayers() = nodesByLayerId.keys.forEach { setVisible(it, false) }

    suspend fun refreshLayer(layerId: String, relativePath: String) {
        nodesByLayerId.remove(layerId)?.let { detach(it) }
        cachedFilePath.remove(layerId)?.let { runCatching { it.delete() } }
        layerStates.remove(layerId)
        loadLayerSafe(layerId, relativePath)
    }

    fun clearNodes() {
        nodesByLayerId.values.forEach { detach(it) }
        nodesByLayerId.clear()
        layerStates.clear()
    }

    fun clearAll() {
        clearNodes()
        cachedFilePath.clear()
    }

    fun clearAllHard() {
        clearNodes()
        cachedFilePath.values.forEach { runCatching { it.delete() } }
        cachedFilePath.clear()
        cleanupOldRevisions(keep = 0)
    }

    fun getLayerState(layerId: String): LayerState = layerStates[layerId] ?: LayerState.NotLoaded
    fun isLayerVisible(layerId: String): Boolean = (layerStates[layerId] as? LayerState.Loaded)?.visible == true
    fun isLayerLoaded(layerId: String): Boolean = layerStates[layerId] is LayerState.Loaded

    private fun setState(layerId: String, state: LayerState) {
        layerStates[layerId] = state
        onStateChanged?.invoke(layerId, state)
    }

    private suspend fun downloadToCache(layerId: String, relativePath: String): File {
        return HeavyOps.withPermit {
            val cleanPath = relativePath.removePrefix("/")
            val fullUrl = baseUrl.trimEnd('/') + "/" + cleanPath
            val req = Request.Builder().url(fullUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} for layer=$layerId url=$fullUrl")
                }
                val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty body for layer=$layerId")
                val crc = CRC32().apply { update(bytes) }.value.toString(16)

                val revDir = getRevisionDir()
                if (!revDir.exists()) revDir.mkdirs()

                val out = File(revDir, "layer_${layerId}_$crc.glb")
                if (!out.exists() || out.length() != bytes.size.toLong()) {
                    out.writeBytes(bytes)
                }

                cleanupOldRevisions()
                cleanupLayerCacheInDir(revDir, layerId, keepLatest = keepLatestLayerFilesPerRev)
                out
            }
        }
    }

    private fun getRevisionDir(): File = File(context.cacheDir, "layers_rev_${currentRevisionSafe}")

    private fun sanitizeRevId(revId: String?): String {
        val raw = (revId ?: "").trim()
        if (raw.isBlank()) return "rev_none"
        val safe = raw.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(48)
        return if (safe.isBlank()) "rev_none" else safe
    }

    private fun cleanupOldRevisions(keep: Int = keepLatestRevs) {
        val dirs = context.cacheDir
            .listFiles { f -> f.isDirectory && f.name.startsWith("layers_rev_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(keep).forEach { dir -> runCatching { dir.deleteRecursively() } }
    }

    private fun cleanupLayerCacheInDir(revDir: File, layerId: String, keepLatest: Int) {
        val files = revDir
            .listFiles { f -> f.isFile && f.name.startsWith("layer_${layerId}_") && f.name.endsWith(".glb") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keepLatest).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val TAG = "LayerGlbManager"
    }
}
