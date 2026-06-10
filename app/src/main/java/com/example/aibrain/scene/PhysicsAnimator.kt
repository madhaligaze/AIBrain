package com.example.aibrain.scene

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.aibrain.SoundManager
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * TODO(sceneview-migration): re-implement the falling/collapse node animation with
 * SceneView nodes (position tween + particles). For now it keeps the haptic
 * feedback (engine-independent) and no-ops the visual part so the build compiles.
 * See SCENEVIEW_MIGRATION.md.
 */
class PhysicsAnimator(
    private val sceneView: ARSceneView,
    private val sceneBuilder: SceneBuilder,
    context: Context,
    private val soundManager: SoundManager? = null,
) {
    private val effectScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun animateFall(collapsedIds: List<String>) {
        if (collapsedIds.isEmpty()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun stopAll() { /* no visual animations to stop */ }

    fun release() {
        runCatching { effectScope.cancel() }
    }
}
