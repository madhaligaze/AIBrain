package com.example.aibrain.scene

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.RenderableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LayerGlbManager(
    private val context: Context,
    private val scene: Scene,
    private val baseUrl: String,
) {
    private val client = OkHttpClient()
    private val nodesByLayerId = mutableMapOf<String, Node>()

    suspend fun loadLayer(layerId: String, relativePath: String): Node = withContext(Dispatchers.IO) {
        val local = downloadToCache(layerId, relativePath)
        val renderable = ModelRenderable.builder()
            .setSource(
                context,
                RenderableSource.builder()
                    .setSource(context, android.net.Uri.fromFile(local), RenderableSource.SourceType.GLB)
                    .setScale(1.0f)
                    .setRecenterMode(RenderableSource.RecenterMode.ROOT)
                    .build()
            )
            .setRegistryId(local.absolutePath)
            .build()
            .await()

        withContext(Dispatchers.Main) {
            nodesByLayerId[layerId]?.setParent(null)
            val node = Node().apply {
                this.renderable = renderable
                this.isEnabled = true
                setParent(scene)
            }
            nodesByLayerId[layerId] = node
            node
        }
    }

    fun setVisible(layerId: String, visible: Boolean) {
        nodesByLayerId[layerId]?.isEnabled = visible
    }

    fun clearAll() {
        nodesByLayerId.values.forEach { it.setParent(null) }
        nodesByLayerId.clear()
    }

    private fun downloadToCache(layerId: String, relativePath: String): File {
        val cleanPath = relativePath.removePrefix("/")
        val full = baseUrl.trimEnd('/') + "/" + cleanPath
        val req = Request.Builder().url(full).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to download $cleanPath: HTTP ${resp.code}")
            }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty response body for $cleanPath")
            val out = File(context.cacheDir, "layer_${layerId}.glb")
            out.writeBytes(bytes)
            return out
        }
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (error != null) cont.resumeWithException(error)
        else cont.resume(value)
    }
    cont.invokeOnCancellation { cancel(true) }
}
