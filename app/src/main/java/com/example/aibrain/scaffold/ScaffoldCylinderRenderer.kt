package com.example.aibrain.scaffold

import com.example.aibrain.ar.SceneGeometry
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope

/**
 * Local procedural scaffold preview (posts + ledgers + diagonal braces) rendered
 * as SceneView cylinders under the origin anchor. Secondary to the server GLB
 * layers; useful as an instant preview before the server export arrives.
 */
class ScaffoldCylinderRenderer(
    private val sceneView: ARSceneView,
    private val scope: CoroutineScope,
) {
    private val nodes = mutableListOf<Node>()
    private var rootParent: AnchorNode? = null

    fun setRootParent(anchor: AnchorNode?) {
        rootParent = anchor
    }

    fun buildScaffold(supports: List<Float3>, height: Float = 3.0f, levels: Int = 3) {
        clearAll()
        if (supports.size < 2) return
        val root = rootParent ?: return
        val engine = sceneView.engine
        val ml = sceneView.materialLoader
        val postMat = SceneGeometry.material(ml, COLOR_POST)
        val ledgerMat = SceneGeometry.material(ml, COLOR_LEDGER)
        val braceMat = SceneGeometry.material(ml, COLOR_BRACE)
        val levelH = height / levels
        val baseY = supports.first().y

        // Posts (vertical)
        supports.forEach { base ->
            SceneGeometry.cylinderBetween(engine, postMat, base, Float3(base.x, base.y + height, base.z), POST_RADIUS)
                ?.let { root.addChildNode(it); nodes.add(it) }
        }

        // Ledgers (horizontal hull edges at each level)
        val hull = convexHull2D(supports)
        for (level in 0..levels) {
            val y = baseY + level * levelH
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                SceneGeometry.cylinderBetween(engine, ledgerMat, Float3(a.x, y, a.z), Float3(b.x, y, b.z), LEDGER_RADIUS)
                    ?.let { root.addChildNode(it); nodes.add(it) }
            }
        }

        // Diagonal braces per bay per level
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            for (level in 0 until levels) {
                val yBot = baseY + level * levelH
                val yTop = yBot + levelH
                SceneGeometry.cylinderBetween(engine, braceMat, Float3(a.x, yBot, a.z), Float3(b.x, yTop, b.z), BRACE_RADIUS)
                    ?.let { root.addChildNode(it); nodes.add(it) }
            }
        }
    }

    fun clearAll() {
        nodes.forEach { runCatching { it.parent?.removeChildNode(it) } }
        nodes.clear()
    }

    private fun convexHull2D(points: List<Float3>): List<Float3> {
        if (points.size <= 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.z }))
        val hull = mutableListOf<Float3>()
        for (p in sorted) {
            while (hull.size >= 2 && cross2D(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeLast()
            hull.add(p)
        }
        val lower = hull.size + 1
        for (p in sorted.reversed()) {
            while (hull.size >= lower && cross2D(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeLast()
            hull.add(p)
        }
        if (hull.isNotEmpty()) hull.removeLast()
        return hull
    }

    private fun cross2D(o: Float3, a: Float3, b: Float3): Float =
        (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x)

    companion object {
        private const val POST_RADIUS = 0.025f
        private const val LEDGER_RADIUS = 0.018f
        private const val BRACE_RADIUS = 0.012f
        private val COLOR_POST = 0xFF5AA9E6.toInt()   // steel blue
        private val COLOR_LEDGER = 0xFFE0A046.toInt() // amber
        private val COLOR_BRACE = 0xCCE0A046.toInt()  // translucent amber
    }
}
