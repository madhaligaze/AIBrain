package com.example.aibrain.measurement

import com.google.ar.core.Camera
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

object TrackingQuality {

    enum class Level { HIGH, MEDIUM, LOW }

    data class Result(
        val level: Level,
        val score: Int,
        val hint: String,
        val canAddPoint: Boolean
    )

    fun evaluate(
        camera: Camera,
        hit: HitResult?,
        poseJitter: Float = 0f
    ): Result {
        if (camera.trackingState != TrackingState.TRACKING) {
            val reason = runCatching { camera.trackingFailureReason }.getOrNull()
            val hint = when (reason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Мало света. Переместитесь в более освещённое место."
                TrackingFailureReason.EXCESSIVE_MOTION -> "Слишком быстрое движение. Держите телефон спокойнее."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Мало деталей. Наведите на текстурированную поверхность."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "Камера недоступна."
                TrackingFailureReason.BAD_STATE -> "Сбой трекинга. Перезапустите AR."
                else -> "Трекинг нестабилен. Подождите секунду."
            }
            return Result(Level.LOW, 0, hint, canAddPoint = false)
        }

        if (hit == null) {
            return Result(Level.LOW, 10, "Нет поверхности. Наведите на пол или стену.", canAddPoint = false)
        }

        val trackable = hit.trackable
        var score = when {
            trackable is Plane && trackable.trackingState == TrackingState.TRACKING
                && trackable.isPoseInPolygon(hit.hitPose) -> 90
            trackable is Plane && trackable.trackingState == TrackingState.TRACKING -> 70
            trackable is Point && trackable.trackingState == TrackingState.TRACKING -> 55
            else -> 30
        }

        score -= (poseJitter * 40f).toInt().coerceIn(0, 40)

        return when {
            score >= 70 -> Result(Level.HIGH, score.coerceIn(0, 100), "Точность высокая", canAddPoint = true)
            score >= 45 -> Result(Level.MEDIUM, score.coerceIn(0, 100), "Точность средняя. Держите телефон ровнее.", canAddPoint = true)
            else -> Result(Level.LOW, score.coerceIn(0, 100), "Точность низкая. Подождите стабилизации.", canAddPoint = false)
        }
    }
}
