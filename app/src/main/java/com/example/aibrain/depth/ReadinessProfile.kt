package com.example.aibrain.depth

import com.example.aibrain.ReadinessMetrics

object ReadinessProfile {

    enum class Type { WITH_DEPTH, NO_DEPTH, UNSTABLE_DEPTH, UNKNOWN }

    data class Thresholds(
        val minObservedRatio: Double,
        val minViewDiversity: Int,
        val minViewpoints: Int,
        val label: String,
        val scanHintExtra: String = ""
    )

    data class ProfileResult(
        val type: Type,
        val thresholds: Thresholds,
        val explanation: String,
        val humanReasons: List<String>
    )

    private val PROFILES = mapOf(
        "WithDepth" to Thresholds(0.40, 4, 6, "Режим с глубиной", ""),
        "UnstableDepth" to Thresholds(
            0.55,
            6,
            9,
            "Глубина нестабильна",
            " (нужно больше ракурсов из-за нестабильной глубины)"
        ),
        "NoDepth" to Thresholds(
            0.70,
            8,
            12,
            "Без датчика глубины",
            " (нужно больше ракурсов — нет датчика глубины)"
        )
    )

    private val FALLBACK = Thresholds(0.50, 5, 8, "Стандартный")

    fun evaluate(
        profileName: String?,
        metrics: ReadinessMetrics?,
        ready: Boolean
    ): ProfileResult {
        val thresholds = PROFILES[profileName] ?: FALLBACK
        val type = when (profileName) {
            "WithDepth" -> Type.WITH_DEPTH
            "NoDepth" -> Type.NO_DEPTH
            "UnstableDepth" -> Type.UNSTABLE_DEPTH
            else -> Type.UNKNOWN
        }

        val explanation = buildString {
            append(thresholds.label)
            if (profileName != null && profileName != "WithDepth") {
                append(" — ")
                append(
                    when (type) {
                        Type.NO_DEPTH -> "пороги снижены т.к. нет depth"
                        Type.UNSTABLE_DEPTH -> "пороги повышены т.к. depth нестабилен"
                        else -> "стандартный режим"
                    }
                )
            }
        }

        val reasons = buildHumanReasons(thresholds, metrics, ready)
        return ProfileResult(type, thresholds, explanation, reasons)
    }

    private fun buildHumanReasons(
        t: Thresholds,
        m: ReadinessMetrics?,
        ready: Boolean
    ): List<String> {
        if (ready || m == null) return emptyList()
        val list = mutableListOf<String>()

        val obsPct = (m.observed_ratio * 100).toInt()
        val minObsPct = (t.minObservedRatio * 100).toInt()
        val vd = m.view_diversity
        val minVd = t.minViewDiversity
        val vp = m.viewpoints
        val minVp = t.minViewpoints

        if (obsPct < minObsPct) {
            val gap = minObsPct - obsPct
            list += "Покрытие $obsPct% из нужных $minObsPct%${t.scanHintExtra}. " +
                "Обойдите опору полукругом — нужно ещё ≈${gap}% покрытия."
        }
        if (vd < minVd) {
            val miss = minVd - vd
            list += "Разнообразие ракурсов: $vd/$minVd. " +
                "Сделайте ещё $miss позиций с шагом 45°${t.scanHintExtra}."
        }
        if (vp < minVp) {
            val miss = minVp - vp
            list += "Точек обзора: $vp/$minVp. " +
                "Переместитесь $miss раз${if (miss == 1) "" else "а"} (шаг ~0.5 м)${t.scanHintExtra}."
        }
        return list
    }
}
