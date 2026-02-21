package com.example.aibrain.scene

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.aibrain.util.HeavyOps
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.NodeParent
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LayerGlbManager(
    private val context: Context,
    private val sceneView: ArSceneView,
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
    private val nodesByLayerId = mutableMapOf<String, Node>()
    private val renderableCache = mutableMapOf<String, ModelRenderable>()
    private val cachedFilePath = mutableMapOf<String, File>()
    private var layersRoot: AnchorNode? = null

    private var currentRevisionSafe: String = "rev_none"
    private val keepLatestRevs: Int = 5
    private val keepLatestLayerFilesPerRev: Int = 3

    fun setCurrentRevision(revId: String?) {
        currentRevisionSafe = sanitizeRevId(revId)
        // Opportunistic cleanup
        cleanupOldRevisions()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun setLayersRoot(anchor: AnchorNode?) {
        layersRoot = anchor
        val parent: NodeParent = anchor ?: sceneView.scene
        nodesByLayerId.values.forEach { node ->
            node.setParent(parent)
            // Ensure layers are aligned to the origin anchor (identity local transform).
            node.localPosition = Vector3(0f, 0f, 0f)
            node.localRotation = Quaternion(0f, 0f, 0f, 1f)
            node.localScale = Vector3(1f, 1f, 1f)
        }
    }

    suspend fun loadOrShowLayer(layerId: String, relativePath: String) {
        when (val st = layerStates[layerId]) {
            is LayerState.Loaded -> {
                setVisible(layerId, true)
                return
            }
            is LayerState.Loading -> return
            is LayerState.Error -> Log.w(TAG, "Layer $layerId had error '${st.reason}', retrying")
            else -> Unit
        }
        loadLayerSafe(layerId, relativePath)
    }

    // Backward compatibility for existing callers.
    suspend fun loadLayer(layerId: String, relativePath: String): Node {
        loadOrShowLayer(layerId, relativePath)
        return nodesByLayerId[layerId]
            ?: throw IllegalStateException("Layer $layerId failed to load")
    }

    private suspend fun loadLayerSafe(layerId: String, relativePath: String) {
        setState(layerId, LayerState.Loading)

        renderableCache[layerId]?.let {
            placeNode(layerId, it)
            setState(layerId, LayerState.Loaded(visible = true))
            return
        }

        val cacheFile = try {
            withContext(Dispatchers.IO) { downloadToCache(layerId, relativePath) }
        } catch (e: Exception) {
            setState(layerId, LayerState.Error("Download failed: ${e.message}"))
            return
        }

        val renderable = try {
            withContext(Dispatchers.Main) {
                ModelRenderable.builder()
                    .setSource(context, Uri.fromFile(cacheFile))
                    .setRegistryId("${cacheFile.absolutePath}:${cacheFile.length()}")
                    .build()
                    .await()
            }
        } catch (e: Exception) {
            setState(layerId, LayerState.Error("Renderable build failed: ${e.message}"))
            return
        }

        renderableCache[layerId] = renderable
        cachedFilePath[layerId] = cacheFile

        withContext(Dispatchers.Main) {
            placeNode(layerId, renderable)
            setState(layerId, LayerState.Loaded(visible = true))
        }
    }

    private fun placeNode(layerId: String, renderable: ModelRenderable) {
        nodesByLayerId[layerId]?.setParent(null)
        val parent: NodeParent = layersRoot ?: sceneView.scene
        val node = Node().apply {
            this.renderable = renderable
            isEnabled = true
            setParent(parent)
            localPosition = Vector3(0f, 0f, 0f)
            localRotation = Quaternion(0f, 0f, 0f, 1f)
            localScale = Vector3(1f, 1f, 1f)
        }
        nodesByLayerId[layerId] = node
    }

    fun setVisible(layerId: String, visible: Boolean) {
        val node = nodesByLayerId[layerId] ?: return
        node.isEnabled = visible
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
        nodesByLayerId.remove(layerId)?.setParent(null)
        renderableCache.remove(layerId)
        cachedFilePath.remove(layerId)?.let { runCatching { it.delete() } }
        layerStates.remove(layerId)
        loadLayerSafe(layerId, relativePath)
    }

    fun clearNodes() {
        nodesByLayerId.values.forEach { it.setParent(null) }
        nodesByLayerId.clear()
        layerStates.clear()
    }

    fun clearAll() {
        // Clears nodes + in-memory renderables, but keeps on-disk cache (rev-aware).
        clearNodes()
        renderableCache.clear()
        cachedFilePath.clear()
    }

    fun clearAllHard() {
        // Clears everything including cached files.
        clearNodes()
        renderableCache.clear()
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

                // Cleanup policies:
                // - keep latest N revisions
                // - keep latest K layer files per layerId inside this revision dir
                cleanupOldRevisions()
                cleanupLayerCacheInDir(revDir, layerId, keepLatest = keepLatestLayerFilesPerRev)

                out
            }
        }
    }

    private fun getRevisionDir(): File {
        // cacheDir/layers_rev_<revSafe>/
        return File(context.cacheDir, "layers_rev_${currentRevisionSafe}")
    }

    private fun sanitizeRevId(revId: String?): String {
        val raw = (revId ?: "").trim()
        if (raw.isBlank()) return "rev_none"
        val safe = raw
            .lowercase()
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .take(48)
        return if (safe.isBlank()) "rev_none" else safe
    }

    private fun cleanupOldRevisions(keep: Int = keepLatestRevs) {
        val dirs = context.cacheDir
            .listFiles { f -> f.isDirectory && f.name.startsWith("layers_rev_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        dirs.drop(keep).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun cleanupLayerCacheInDir(revDir: File, layerId: String, keepLatest: Int) {
        val files = revDir
            .listFiles { f -> f.isFile && f.name.startsWith("layer_${layerId}_") && f.name.endsWith(".glb") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keepLatest).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val TAG = "LayerGlbManager"
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(value)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
