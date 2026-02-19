package com.example.aibrain.models

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory

/**
 * Генератор 3D моделей для элементов Layher строительных лесов.
 */
object LayherModels {

    private const val TUBE_DIAMETER = 0.0483f
    private const val STANDARD_HEIGHT = 2.0f
    private const val LEDGER_LENGTH = 2.07f
    private const val DECK_WIDTH = 0.61f
    private const val DECK_LENGTH = 3.21f
    private const val WEDGE_STEP = 0.5f
    private const val WEDGE_NODE_RADIUS = 0.04f

    fun createStandard(
        context: Context,
        height: Float = STANDARD_HEIGHT,
        material: Material
    ): ModelRenderable {
        val normalizedHeight = height.coerceAtLeast(0.1f)
        return ShapeFactory.makeCylinder(
            TUBE_DIAMETER / 2,
            normalizedHeight,
            Vector3.zero(),
            material
        )
    }

    fun createLedger(
        context: Context,
        length: Float = LEDGER_LENGTH,
        material: Material
    ): ModelRenderable {
        val normalizedLength = length.coerceAtLeast(0.1f)
        return ShapeFactory.makeCylinder(
            TUBE_DIAMETER / 2,
            normalizedLength,
            Vector3.zero(),
            material
        )
    }

    fun createBracing(
        context: Context,
        length: Float,
        material: Material
    ): ModelRenderable {
        val normalizedLength = length.coerceAtLeast(0.1f)
        return ShapeFactory.makeCylinder(
            TUBE_DIAMETER / 2,
            normalizedLength,
            Vector3.zero(),
            material
        )
    }

    fun createDeck(
        context: Context,
        material: Material
    ): ModelRenderable {
        return ShapeFactory.makeCube(
            Vector3(DECK_LENGTH, 0.05f, DECK_WIDTH),
            Vector3.zero(),
            material
        )
    }

    fun createWedgeNode(
        context: Context,
        material: Material
    ): ModelRenderable {
        return ShapeFactory.makeSphere(
            WEDGE_NODE_RADIUS,
            Vector3.zero(),
            material
        )
    }

    /**
     * Смещения клиновых узлов от центра стойки.
     */
    fun getWedgeOffsets(height: Float): List<Float> {
        val normalizedHeight = height.coerceAtLeast(0.1f)
        val wedgeCount = (normalizedHeight / WEDGE_STEP).toInt()
        return (0..wedgeCount).map { i ->
            i * WEDGE_STEP - normalizedHeight / 2f
        }
    }

    fun createStandardWithWedges(
        context: Context,
        height: Float = STANDARD_HEIGHT,
        baseMaterial: Material,
        wedgeMaterial: Material
    ): Node {
        val containerNode = Node()

        val mainTube = createStandard(context, height, baseMaterial)
        Node().apply {
            renderable = mainTube
            setParent(containerNode)
        }

        for (yPos in getWedgeOffsets(height)) {
            val wedge = createWedgeNode(context, wedgeMaterial)
            Node().apply {
                renderable = wedge
                localPosition = Vector3(0f, yPos, 0f)
                setParent(containerNode)
            }
        }

        return containerNode
    }
}
