package com.example.aibrain.scaffold

import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

class ScaffoldCylinderRenderer(
    private val sceneView: ArSceneView,
    private val scope: CoroutineScope,
) {
    private val nodes = mutableListOf<Node>()
    private var rootParent: AnchorNode? = null

    companion object {
        private const val POST_RADIUS = 0.025f
        private const val LEDGER_RADIUS = 0.018f
        private const val BRACE_RADIUS = 0.012f

        private val COLOR_POST = Color(0f, 1f, 0.8f)
        private val COLOR_LEDGER = Color(1f, 0.4f, 0f)
        private val COLOR_BRACE = Color(1f, 0.85f, 0f, 0.6f)
    }

    fun setRootParent(anchor: AnchorNode?) {
        rootParent = anchor
    }

    fun buildScaffold(supports: List<Vector3>, height: Float = 3.0f, levels: Int = 3) {
        clearAll()
        if (supports.size < 2) return
        val root = rootParent ?: return
        val levelHeight = height / levels

        scope.launch {
            val postMaterial = MaterialFactory.makeOpaqueWithColor(sceneView.context, COLOR_POST).await()
            supports.forEach { base ->
                val postNode = Node().apply {
                    renderable = ShapeFactory.makeCylinder(POST_RADIUS, height, Vector3.zero(), postMaterial)
                    localPosition = Vector3(base.x, base.y + height / 2f, base.z)
                    setParent(root)
                }
                nodes.add(postNode)
            }

            val ledgerMaterial = MaterialFactory.makeOpaqueWithColor(sceneView.context, COLOR_LEDGER).await()
            val hull = convexHull2D(supports)
            for (level in 0..levels) {
                val y = supports.first().y + level * levelHeight
                for (i in hull.indices) {
                    val a = hull[i]
                    val b = hull[(i + 1) % hull.size]
                    spawnCylinder(Vector3(a.x, y, a.z), Vector3(b.x, y, b.z), LEDGER_RADIUS, root, ledgerMaterial)
                }
            }

            val braceMaterial = MaterialFactory.makeTransparentWithColor(sceneView.context, COLOR_BRACE).await()
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                for (level in 0 until levels) {
                    val yBot = supports.first().y + level * levelHeight
                    val yTop = yBot + levelHeight
                    spawnCylinder(Vector3(a.x, yBot, a.z), Vector3(b.x, yTop, b.z), BRACE_RADIUS, root, braceMaterial)
                    spawnCylinder(Vector3(a.x, yTop, a.z), Vector3(b.x, yBot, b.z), BRACE_RADIUS, root, braceMaterial)
                }
            }
        }
    }

    private fun spawnCylinder(from: Vector3, to: Vector3, radius: Float, node: AnchorNode, material: Material) {
        val direction = Vector3.subtract(to, from)
        val length = direction.length()
        if (length < 0.001f) return
        val midpoint = Vector3.add(from, to).scaled(0.5f)
        val rotation = Quaternion.lookRotation(direction.normalized(), Vector3.up())
        val tilt = Quaternion.axisAngle(Vector3(1f, 0f, 0f), 90f)
        val finalRot = Quaternion.multiply(rotation, tilt)

        val cylinderNode = Node().apply {
            renderable = ShapeFactory.makeCylinder(radius, length, Vector3.zero(), material)
            localPosition = midpoint
            localRotation = finalRot
            setParent(node)
        }
        nodes.add(cylinderNode)
    }

    private fun convexHull2D(points: List<Vector3>): List<Vector3> {
        if (points.size <= 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.z }))
        val hull = mutableListOf<Vector3>()
        for (p in sorted) {
            while (hull.size >= 2 && cross2D(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeLast()
            hull.add(p)
        }
        val lower = hull.size + 1
        for (p in sorted.reversed()) {
            while (hull.size >= lower && cross2D(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeLast()
            hull.add(p)
        }
        hull.removeLast()
        return hull
    }

    private fun cross2D(o: Vector3, a: Vector3, b: Vector3): Float =
        (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x)

    fun clearAll() {
        nodes.forEach { it.setParent(null) }
        nodes.clear()
    }
}
