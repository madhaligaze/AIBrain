package com.example.aibrain.ar

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.geometries.Cylinder
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * SceneView/Filament primitive helpers shared by the AR renderers (ruler,
 * scaffold cylinders, voxels). Centralises material creation and the tricky
 * "cylinder between two points" orientation so each renderer stays small.
 *
 * NOTE: SceneView's [Cylinder] is built along the local +Y axis; [cylinderBetween]
 * rotates that axis onto the A→B direction with a shortest-arc quaternion. If on
 * device the bars look rotated 90°, the geometry's default axis differs — change
 * [CYLINDER_AXIS] accordingly.
 */
object SceneGeometry {

    private val CYLINDER_AXIS = Float3(0f, 1f, 0f)

    fun material(materialLoader: MaterialLoader, colorInt: Int): MaterialInstance =
        materialLoader.createColorInstance(colorInt, 0.2f, 0.6f, 0f)

    fun sphere(
        engine: Engine,
        material: MaterialInstance,
        radius: Float,
        center: Float3 = Float3(0f, 0f, 0f),
    ): SphereNode {
        val geom = Sphere.Builder().radius(radius).center(center).build(engine)
        return SphereNode(engine, geom, material)
    }

    /**
     * Build a cylinder node spanning [a]→[b] (world space). Returns null if the
     * segment is degenerate. The returned node is positioned at the midpoint and
     * oriented along the segment; attach it to the scene/anchor by the caller.
     */
    fun cylinderBetween(
        engine: Engine,
        material: MaterialInstance,
        a: Float3,
        b: Float3,
        radius: Float,
    ): Node? {
        val dir = b - a
        val len = length(dir)
        if (len < 1e-4f) return null

        val geom = Cylinder.Builder()
            .radius(radius)
            .height(len)
            .center(Float3(0f, 0f, 0f))
            .build(engine)
        val node = CylinderNode(engine, geom, material)
        node.worldPosition = (a + b) * 0.5f
        node.quaternion = shortestArc(CYLINDER_AXIS, dir / len)
        return node
    }

    private fun length(v: Float3): Float = kotlin.math.sqrt(dot(v, v))

    /** Shortest-arc unit quaternion rotating unit vector [from] onto unit [to]. */
    private fun shortestArc(from: Float3, to: Float3): Quaternion {
        val d = dot(from, to).coerceIn(-1f, 1f)
        if (d > 0.99999f) return Quaternion(0f, 0f, 0f, 1f)
        if (d < -0.99999f) {
            // 180°: rotate about any axis perpendicular to `from`.
            val axis = normalize(cross(from, Float3(1f, 0f, 0f)).let {
                if (length(it) < 1e-4f) cross(from, Float3(0f, 0f, 1f)) else it
            })
            return Quaternion(axis.x, axis.y, axis.z, 0f)
        }
        val axis = normalize(cross(from, to))
        val angle = acos(d)
        val s = sin(angle * 0.5f)
        return Quaternion(axis.x * s, axis.y * s, axis.z * s, cos(angle * 0.5f))
    }
}
