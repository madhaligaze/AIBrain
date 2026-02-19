package com.example.aibrain.scene

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.aibrain.SoundManager
import com.example.aibrain.SoundType
import com.example.aibrain.effects.ParticleSystem
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.random.Random

/**
 * Физический аниматор с "сочными" эффектами.
 */
class PhysicsAnimator(
    private val sceneView: ArSceneView,
    private val sceneBuilder: SceneBuilder,
    context: Context
) {

    private val soundManager = SoundManager(context)
    private val effectScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val particleSystem = ParticleSystem(sceneView, effectScope)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val activeAnimations = mutableMapOf<String, FallingState>()

    fun animateFall(collapsedIds: List<String>) {
        if (collapsedIds.isEmpty()) {
            return
        }

        soundManager.play(SoundType.COLLAPSE, volume = 1.0f, pitch = 0.8f)
        vibrateCollapse(collapsedIds.size)

        collapsedIds.forEach { id ->
            val node = sceneBuilder.findNodeById(id) ?: return@forEach
            activeAnimations[id] = FallingState(node = node, initialHeight = node.worldPosition.y)
        }

        if (activeAnimations.isNotEmpty()) {
            sceneView.scene.addOnUpdateListener(::onUpdate)
        }
    }

    private fun onUpdate(frameTime: com.google.ar.sceneform.FrameTime) {
        val delta = frameTime.deltaSeconds.coerceAtLeast(0.016f)
        val iterator = activeAnimations.iterator()

        while (iterator.hasNext()) {
            val (_, state) = iterator.next()
            state.velocityY -= 9.81f * delta

            val currentPos = state.node.worldPosition
            val nextY = currentPos.y + state.velocityY * delta

            if (nextY <= groundY) {
                val impactIntensity = (-state.velocityY / 10f).coerceIn(0.3f, 1.0f)
                soundManager.play3D(
                    SoundType.DUST_IMPACT,
                    distance = currentPos.length(),
                    pitch = 0.8f + Random.nextFloat() * 0.4f
                )

                if (state.bouncesLeft == state.maxBounces) {
                    particleSystem.createSparks(currentPos, count = 30)
                    particleSystem.createDustCloud(currentPos, intensity = impactIntensity)
                    particleSystem.createShockwave(currentPos)
                } else {
                    particleSystem.createDustCloud(currentPos, intensity = impactIntensity * 0.5f)
                }

                state.bouncesLeft--

                if (state.bouncesLeft <= 0) {
                    state.node.worldPosition = Vector3(currentPos.x, groundY, currentPos.z)
                    iterator.remove()
                    continue
                }

                state.velocityY = -state.velocityY * 0.35f
                state.node.worldPosition = Vector3(currentPos.x, groundY, currentPos.z)
            } else {
                state.node.worldPosition = Vector3(currentPos.x, nextY, currentPos.z)
            }
        }

        if (activeAnimations.isEmpty()) {
            sceneView.scene.removeOnUpdateListener(::onUpdate)
        }
    }

    private fun vibrateCollapse(elementCount: Int) {
        if (!vibrator.hasVibrator()) {
            return
        }

        val duration = when {
            elementCount < 3 -> 100L
            elementCount < 10 -> 300L
            else -> 500L
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(
                duration,
                (255 * (elementCount / 50f).coerceAtMost(1f)).toInt().coerceAtLeast(40)
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    fun stopAll() {
        activeAnimations.clear()
        sceneView.scene.removeOnUpdateListener(::onUpdate)
        soundManager.stopAll()
    }

    fun release() {
        stopAll()
        effectScope.cancel()
        soundManager.release()
    }

    private data class FallingState(
        val node: Node,
        val initialHeight: Float,
        var velocityY: Float = 0f,
        var bouncesLeft: Int = 2,
        val maxBounces: Int = 2
    )

    private companion object {
        const val groundY = 0f
    }
}
