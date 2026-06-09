package com.example.aibrain.logic

import com.google.ar.sceneform.math.Vector3

object OfflineScaffolder {

    data class ScaffoldSpec(
        val posts: List<Vector3>,
        val levels: Int,
        val height: Float,
        val totalHeight: Float,
    )

    fun generateFromTwoPoints(
        p1: Vector3,
        p2: Vector3,
        levels: Int = 3,
        levelH: Float = 2.0f,
        spacing: Float = 1.5f,
    ): ScaffoldSpec {
        val minX = minOf(p1.x, p2.x)
        val maxX = maxOf(p1.x, p2.x)
        val minZ = minOf(p1.z, p2.z)
        val maxZ = maxOf(p1.z, p2.z)
        val baseY = minOf(p1.y, p2.y)

        val posts = mutableListOf<Vector3>()
        val stepsX = maxOf(2, ((maxX - minX) / spacing).toInt() + 1)
        val stepsZ = maxOf(2, ((maxZ - minZ) / spacing).toInt() + 1)

        for (i in 0 until stepsX) posts.add(Vector3(minX + i * (maxX - minX) / (stepsX - 1), baseY, minZ))
        for (i in 1 until stepsZ) posts.add(Vector3(maxX, baseY, minZ + i * (maxZ - minZ) / (stepsZ - 1)))
        for (i in stepsX - 2 downTo 0) posts.add(Vector3(minX + i * (maxX - minX) / (stepsX - 1), baseY, maxZ))
        for (i in stepsZ - 2 downTo 1) posts.add(Vector3(minX, baseY, minZ + i * (maxZ - minZ) / (stepsZ - 1)))

        return ScaffoldSpec(posts, levels, levelH, levels * levelH)
    }

    fun generateFromSinglePoint(origin: Vector3, sideM: Float = 3.0f, levels: Int = 3, levelH: Float = 2.0f): ScaffoldSpec {
        val h = sideM / 2f
        return ScaffoldSpec(
            posts = listOf(
                Vector3(origin.x - h, origin.y, origin.z - h),
                Vector3(origin.x + h, origin.y, origin.z - h),
                Vector3(origin.x + h, origin.y, origin.z + h),
                Vector3(origin.x - h, origin.y, origin.z + h),
            ),
            levels = levels,
            height = levelH,
            totalHeight = levels * levelH,
        )
    }

    fun getFallbackAssetPath(): String = "models/fallback_scaffold_2x2.glb"
}
