package com.example.aibrain.scene

import com.example.aibrain.ElementPoint
import com.example.aibrain.HeatmapItem
import com.example.aibrain.ScaffoldElement
import com.example.aibrain.assets.ModelAssets
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialInstance
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlin.math.sqrt

/**
 * SceneBuilder с учетом Layher offset и кэшированием материалов.
 */
class SceneBuilder(private val scene: Scene) {

    private val sceneNodes = mutableListOf<Node>()
    private val nodeById = mutableMapOf<String, Node>()
    private val allElements = mutableListOf<ScaffoldElement>()
    private val materialCache = mutableMapOf<String, MaterialInstance>()

    private val flangeOffsets = mapOf(
        "standard" to FlangeOffset(bottom = 0.0f, top = 2.0f, nodePositions = listOf(0.0f, 0.5f, 1.0f, 1.5f, 2.0f)),
        "ledger" to FlangeOffset(start = 0.035f, end = 0.035f),
        "diagonal" to FlangeOffset(start = 0.05f, end = 0.05f)
    )

    fun preloadModels(onReady: (() -> Unit)? = null) {
        onReady?.invoke()
    }

    fun buildScene(elements: List<ScaffoldElement>) {
        clearScene()
        allElements.clear()
        allElements.addAll(elements)
        elements.forEach { createElementWithOffset(it) }
    }

    private fun createElementWithOffset(element: ScaffoldElement) {
        val modelType = getModelType(element.type)
        val renderable = ModelAssets.getCopy(modelType) ?: return createPrimitiveElement(element)
        val (position, rotation, scale) = calculateTransform(element)

        val node = Node().apply {
            this.renderable = renderable
            worldPosition = position
            worldRotation = rotation
            localScale = scale
        }

        applyStressColor(node, element.stress_color ?: colorFromLoadRatio(element.load_ratio ?: 0.0))

        node.setParent(scene)
        sceneNodes.add(node)
        nodeById[element.id] = node
    }

    private fun calculateTransform(element: ScaffoldElement): Triple<Vector3, Quaternion, Vector3> {
        val start = Vector3(element.start.x, element.start.y, element.start.z)
        val end = Vector3(element.end.x, element.end.y, element.end.z)
        val offset = flangeOffsets[element.type]

        val adjustedStart: Vector3
        val adjustedEnd: Vector3

        when (element.type) {
            "standard", "vertical" -> {
                val bottomOffset = offset?.bottom ?: 0f
                adjustedStart = Vector3(start.x, start.y + bottomOffset, start.z)
                adjustedEnd = end
            }

            "ledger", "horizontal", "diagonal", "bracing" -> {
                val direction = Vector3.subtract(end, start).normalized()
                val startOffset = offset?.start ?: 0f
                val endOffset = offset?.end ?: 0f
                adjustedStart = Vector3.add(start, direction.scaled(startOffset))
                adjustedEnd = Vector3.subtract(end, direction.scaled(endOffset))
            }

            else -> {
                adjustedStart = start
                adjustedEnd = end
            }
        }

        val center = Vector3(
            (adjustedStart.x + adjustedEnd.x) / 2f,
            (adjustedStart.y + adjustedEnd.y) / 2f,
            (adjustedStart.z + adjustedEnd.z) / 2f
        )

        val direction = Vector3.subtract(adjustedEnd, adjustedStart)
        val length = direction.length().coerceAtLeast(0.05f)

        val rotation = if (length > 0.01f) {
            Quaternion.lookRotation(direction.normalized(), Vector3.up())
        } else {
            Quaternion.identity()
        }

        val scale = when (element.type) {
            "standard", "vertical" -> Vector3(1f, length / 2.0f, 1f)
            "ledger", "horizontal" -> Vector3(length / 2.07f, 1f, 1f)
            "diagonal", "bracing" -> Vector3(length / 3.0f, 1f, 1f)
            else -> Vector3(1f, 1f, 1f)
        }

        return Triple(center, rotation, scale)
    }

    private fun getModelType(elementType: String): ModelAssets.ModelType {
        return when (elementType) {
            "standard", "vertical" -> ModelAssets.ModelType.LAYHER_STANDARD_2M
            "ledger", "horizontal" -> ModelAssets.ModelType.LAYHER_LEDGER_207
            "diagonal", "bracing" -> ModelAssets.ModelType.LAYHER_DIAGONAL_300
            "deck_steel" -> ModelAssets.ModelType.LAYHER_DECK_STEEL
            "deck", "deck_wood", "platform" -> ModelAssets.ModelType.LAYHER_DECK_WOOD
            else -> ModelAssets.ModelType.LAYHER_LEDGER_207
        }
    }

    private fun applyStressColor(node: Node, colorName: String) {
        val renderable = node.renderable as? ModelRenderable ?: return
        val material = materialCache.getOrPut(colorName) {
            val color = getStressColor(colorName)
            renderable.material.makeCopy().apply {
                setFloat4("baseColorFactor", color)
                setFloat("metallic", 0.9f)
                setFloat("roughness", 0.3f)
            }
        }
        renderable.material = material
    }

    private fun getStressColor(colorName: String): Color {
        return when (colorName) {
            "green" -> Color(0.2f, 0.8f, 0.2f)
            "yellow" -> Color(0.9f, 0.9f, 0.2f)
            "orange" -> Color(1.0f, 0.65f, 0.0f)
            "red" -> Color(0.9f, 0.2f, 0.2f)
            else -> Color(0.7f, 0.7f, 0.7f)
        }
    }

    private fun createPrimitiveElement(element: ScaffoldElement) {
        val start = element.start
        val end = element.end
        val direction = Vector3(end.x - start.x, end.y - start.y, end.z - start.z)
        val length = calculateLength(start, end).coerceAtLeast(0.05f)

        val fallbackRenderable = com.google.ar.sceneform.rendering.ShapeFactory.makeCube(
            Vector3(0.06f, 1.0f, 0.06f),
            Vector3.zero(),
            com.google.ar.sceneform.rendering.MaterialFactory.makeOpaqueWithColor(
                scene.view.context,
                Color(0.6f, 0.6f, 0.6f)
            ).get()
        )

        val node = Node().apply {
            renderable = fallbackRenderable
            worldPosition = Vector3(
                (start.x + end.x) / 2f,
                (start.y + end.y) / 2f,
                (start.z + end.z) / 2f
            )
            worldRotation = if (length > 0.01f) {
                Quaternion.lookRotation(direction.normalized(), Vector3.up())
            } else {
                Quaternion.identity()
            }
        }

        node.setParent(scene)
        sceneNodes.add(node)
        nodeById[element.id] = node
    }

    fun clearScene() {
        sceneNodes.forEach { it.setParent(null) }
        sceneNodes.clear()
        nodeById.clear()
        materialCache.clear()
    }

    fun findNodeById(id: String): Node? = nodeById[id]

    fun getAllElements(): List<ScaffoldElement> = allElements.toList()

    fun removeElement(elementId: String) {
        nodeById[elementId]?.let { node ->
            node.setParent(null)
            sceneNodes.remove(node)
            nodeById.remove(elementId)
            allElements.removeAll { it.id == elementId }
        }
    }

    fun updateHeatmap(heatmap: List<HeatmapItem>) {
        heatmap.forEach { updateElementColor(it.id, it.load_ratio) }
    }

    fun updateColors(heatmap: List<Map<String, Any>>) {
        val colorById = heatmap.associate {
            val id = it["id"] as? String ?: ""
            val color = it["color"] as? String ?: "gray"
            id to color
        }

        colorById.forEach { (id, color) -> nodeById[id]?.let { node -> applyStressColor(node, color) } }
    }

    fun updateElementColor(elementId: String, loadRatio: Double) {
        val colorName = colorFromLoadRatio(loadRatio)
        nodeById[elementId]?.let { node -> applyStressColor(node, colorName) }
    }

    private fun colorFromLoadRatio(loadRatio: Double): String = when {
        loadRatio >= 0.9 -> "red"
        loadRatio >= 0.7 -> "orange"
        loadRatio >= 0.4 -> "yellow"
        else -> "green"
    }

    private fun calculateLength(start: ElementPoint, end: ElementPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

data class FlangeOffset(
    val bottom: Float = 0f,
    val top: Float = 0f,
    val start: Float = 0f,
    val end: Float = 0f,
    val nodePositions: List<Float> = emptyList()
)
