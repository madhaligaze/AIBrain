package com.example.aibrain.depth

import com.example.aibrain.ReadinessMetrics

object ReadinessProfile {

    enum class Type { WITH_DEPTH, NO_DEPTH, UNSTABLE_DEPTH, WALL, UNKNOWN }

    data class Thresholds(
        val minObservedRatio: Double,
        val minViewDiversity: Int,
        val minViewpoints: Int,
        val label: String,
        val scanHintExtra: String = "",
    )

    data class ProfileResult(
        val type: Type,
        val thresholds: Thresholds,
        val explanation: String,
        val humanReasons: List<String>,
    )

    private val PROFILES = mapOf(
        "WithDepth" to Thresholds(0.40, 4, 6, "Режим с глубиной"),
        "UnstableDepth" to Thresholds(0.55, 6, 9, "Глубина нестабильна", " (нужно больше ракурсов из-за нестабильной глубины)"),
        "NoDepth" to Thresholds(0.70, 8, 12, "Без датчика глубины", " (нужно больше ракурсов — нет датчика глубины)"),
        "Wall" to Thresholds(0.35, 3, 5, "Профиль стены", " (для стены достаточно прохода вдоль поверхности)"),
    )

    private val FALLBACK = Thresholds(0.50, 5, 8, "Стандартный")

    fun evaluate(profileName: String?, metrics: ReadinessMetrics?, ready: Boolean): ProfileResult {
        val normalized = when {
            profileName?.contains("Wall", ignoreCase = true) == true -> "Wall"
            else -> profileName
        }
        val thresholds = PROFILES[normalized] ?: FALLBACK
        val type = when (normalized) {
            "WithDepth" -> Type.WITH_DEPTH
            "NoDepth" -> Type.NO_DEPTH
            "UnstableDepth" -> Type.UNSTABLE_DEPTH
            "Wall" -> Type.WALL
            else -> Type.UNKNOWN
        }

        val explanation = when (type) {
            Type.WALL -> if (ready) "✅ Стена отсканирована достаточно" else "Для стены: пройди вдоль неё 2–3 м в обе стороны, меняй расстояние"
            Type.NO_DEPTH -> "Без датчика глубины — пороги повышены"
            Type.UNSTABLE_DEPTH -> "Глубина нестабильна — требуется больше ракурсов"
            Type.WITH_DEPTH -> "Режим с глубиной"
            Type.UNKNOWN -> thresholds.label
        }

        return ProfileResult(type, thresholds, explanation, buildHumanReasons(thresholds, metrics, ready, type))
    }

    private fun buildHumanReasons(t: Thresholds, m: ReadinessMetrics?, ready: Boolean, type: Type): List<String> {
        if (ready || m == null) return emptyList()
        val list = mutableListOf<String>()

        val obsPct = (m.observed_ratio * 100).toInt()
        val minObsPct = (t.minObservedRatio * 100).toInt()
        val vd = m.view_diversity
        val minVd = t.minViewDiversity
        val vp = m.viewpoints
        val minVp = t.minViewpoints

        if (obsPct < minObsPct) {
            list += if (type == Type.WALL) {
                "Покрытие стены $obsPct%/$minObsPct%. Пройдите вдоль поверхности влево и вправо."
            } else {
                "Покрытие $obsPct% из нужных $minObsPct%${t.scanHintExtra}. Обойдите опору — нужно ещё ≈${minObsPct - obsPct}% покрытия."
            }
        }
        if (vd < minVd) list += "Разнообразие ракурсов: $vd/$minVd. Добавьте ещё ${minVd - vd} ракурса(ов)."
        if (vp < minVp) list += "Точек обзора: $vp/$minVp. Сделайте ещё ${minVp - vp} перемещений."
        return list
    }
}
