package com.example.aibrain.effects

import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.future.await
import kotlin.random.Random

/**
 * Система частиц для визуальных эффектов.
 */
class ParticleSystem(
    private val sceneView: ArSceneView,
    private val coroutineScope: CoroutineScope
) {

    fun createSparks(position: Vector3, count: Int = 30) {
        coroutineScope.launch {
            val sparkMaterial = MaterialFactory.makeOpaqueWithColor(
                sceneView.context,
                Color(1.0f, 0.7f, 0.2f)
            ).await()

            repeat(count) {
                createSingleSpark(position, sparkMaterial)
            }
        }
    }

    private suspend fun createSingleSpark(origin: Vector3, material: Material) {
        val spark = Node()
        val sparkRenderable = ShapeFactory.makeCube(Vector3(0.01f, 0.01f, 0.01f), Vector3.zero(), material)

        spark.renderable = sparkRenderable
        spark.worldPosition = origin
        spark.setParent(sceneView.scene)

        val velocity = Vector3(
            Random.nextFloat() * 2 - 1,
            Random.nextFloat() * 2,
            Random.nextFloat() * 2 - 1
        ).normalized().scaled(Random.nextFloat() * 3 + 1)

        animateSpark(spark, velocity)
    }

    private suspend fun animateSpark(spark: Node, initialVelocity: Vector3) {
        var velocity = initialVelocity
        val gravity = Vector3(0f, -9.81f, 0f)
        var lifetime = 0f
        val maxLifetime = Random.nextFloat() * 0.3f + 0.2f

        while (lifetime < maxLifetime) {
            delay(16)

            velocity = Vector3.add(velocity, gravity.scaled(0.016f))
            val newPos = Vector3.add(spark.worldPosition, velocity.scaled(0.016f))
            if (newPos.y <= 0) {
                break
            }

            spark.worldPosition = newPos
            lifetime += 0.016f
        }

        spark.setParent(null)
    }

    fun createDustCloud(position: Vector3, intensity: Float = 1.0f) {
        coroutineScope.launch {
            val dustMaterial = MaterialFactory.makeTransparentWithColor(
                sceneView.context,
                Color(0.6f, 0.6f, 0.5f, 0.3f)
            ).await()

            val particleCount = (20 * intensity).toInt().coerceAtLeast(1)
            repeat(particleCount) {
                createDustParticle(position, dustMaterial)
            }
        }
    }

    private suspend fun createDustParticle(origin: Vector3, material: Material) {
        val particle = Node()

        val size = Random.nextFloat() * 0.15f + 0.05f
        val dustRenderable = ShapeFactory.makeCube(Vector3(size, size, size), Vector3.zero(), material)

        particle.renderable = dustRenderable
        particle.worldPosition = Vector3(
            origin.x + Random.nextFloat() * 0.5f - 0.25f,
            0.05f,
            origin.z + Random.nextFloat() * 0.5f - 0.25f
        )
        particle.setParent(sceneView.scene)

        animateDust(particle)
    }

    private suspend fun animateDust(particle: Node) {
        var lifetime = 0f
        val maxLifetime = Random.nextFloat() * 1.0f + 0.5f

        while (lifetime < maxLifetime) {
            delay(16)

            val progress = lifetime / maxLifetime
            val scale = 1.0f + progress * 3.0f
            particle.localScale = Vector3(scale, scale, scale)

            val newY = particle.worldPosition.y + 0.01f
            particle.worldPosition = Vector3(
                particle.worldPosition.x,
                newY,
                particle.worldPosition.z
            )

            lifetime += 0.016f
        }

        particle.setParent(null)
    }

    fun createShockwave(position: Vector3) {
        coroutineScope.launch {
            val shockwaveMaterial = MaterialFactory.makeTransparentWithColor(
                sceneView.context,
                Color(0.8f, 0.8f, 0.8f, 0.2f)
            ).await()

            val ring = Node()
            val ringRenderable = ShapeFactory.makeCylinder(
                0.1f,
                0.01f,
                Vector3.zero(),
                shockwaveMaterial
            )

            ring.renderable = ringRenderable
            ring.worldPosition = Vector3(position.x, 0.01f, position.z)
            ring.setParent(sceneView.scene)

            animateShockwave(ring)
        }
    }

    private suspend fun animateShockwave(ring: Node) {
        var lifetime = 0f
        val maxLifetime = 0.5f

        while (lifetime < maxLifetime) {
            delay(16)

            val progress = lifetime / maxLifetime
            val scale = 1.0f + progress * 20.0f
            ring.localScale = Vector3(scale, 1f, scale)

            lifetime += 0.016f
        }

        ring.setParent(null)
    }
}
