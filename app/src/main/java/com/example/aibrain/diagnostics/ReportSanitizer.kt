package com.example.aibrain.diagnostics

import com.example.aibrain.ReadinessResponse
import com.example.aibrain.SceneBundleResponse
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Client-side defence-in-depth sanitization for telemetry payloads.
 *
 * Goal: repro_pack must not contain query params, request/response bodies, coordinates, images/base64, or file paths.
 */
object ReportSanitizer {

    private val gson = Gson()

    private val urlRegex = Regex("(https?://[^\\s]+)")

    // Rough patterns for coordinate-like arrays and numeric triples/quads.
    private val coordArrayRegex = Regex("\\[\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*-?\\d+(?:\\.\\d+)?){0,1}\\s*\\]")

    private val denyKeys = listOf(
        "rgb",
        "depth",
        "pose",
        "intrinsics",
        "point_cloud",
        "anchors",
        "position",
        "quaternion",
        "glb",
        "obj",
        "file",
        "files",
        "image",
        "bitmap",
        "base64",
        "url",
        "server",
        "base_url",
        "path"
    )

    fun sanitizeText(text: String, maxLen: Int = 2048): String {
        var s = text

        // Strip query params.
        s = urlRegex.replace(s) { m ->
            val url = m.groupValues[1]
            if (url.contains("?")) url.substringBefore("?") + "?<redacted>" else url
        }

        // Redact coordinate-like arrays.
        s = coordArrayRegex.replace(s, "<coords>")

        // Redact obvious sensitive keys if present in text.
        val lower = s.lowercase()
        if (denyKeys.any { lower.contains(it) }) {
            // Keep the message but remove likely sensitive fragments.
            for (k in denyKeys) {
                s = s.replace(Regex("(?i)\\b$k\\b"), "<redacted>")
            }
        }

        return s.take(maxLen)
    }

    fun sanitizeReproBody(endpoint: String, obj: Any?): String {
        if (obj == null) return ""
        return when {
            endpoint.contains("/readiness") && obj is ReadinessResponse -> sanitizeReadiness(obj)
            endpoint.contains("/export/latest") && obj is SceneBundleResponse -> sanitizeExportLatest(obj)
            endpoint.contains("/request_scaffold") && obj is JsonObject -> sanitizeRequestScaffoldCompat(obj)
            obj is String -> sanitizeText(obj, maxLen = 2048)
            else -> sanitizeText(runCatching { gson.toJson(obj) }.getOrDefault("<json_error>"), maxLen = 2048)
        }
    }

    fun sanitizeReadiness(body: ReadinessResponse): String {
        val m = body.readiness_metrics
        val out = mapOf(
            "ready" to body.ready,
            "score" to body.score,
            "reasons" to body.reasons.take(12),
            "metrics" to mapOf(
                "observed_ratio" to (m?.observed_ratio ?: 0.0),
                "view_diversity" to (m?.view_diversity ?: 0),
                "viewpoints" to (m?.viewpoints ?: 0),
                "min_observed_ratio" to (m?.min_observed_ratio ?: 0.0),
                "min_views_per_anchor" to (m?.min_views_per_anchor ?: 0),
                "min_viewpoints" to (m?.min_viewpoints ?: 0),
                "anchor_count" to (m?.anchor_count ?: 0)
            )
        )
        return sanitizeText(gson.toJson(out), maxLen = 2048)
    }

    fun sanitizeExportLatest(bundle: SceneBundleResponse): String {
        val layers = bundle.ui?.layers.orEmpty().map { l ->
            mapOf(
                "id" to l.id,
                "label" to (l.label ?: ""),
                "kind" to (l.kind ?: ""),
                "default_on" to (l.default_on ?: false)
            )
        }
        val out = mapOf(
            "revision_id" to (bundle.revision_id ?: bundle.rev_id ?: ""),
            "layers" to layers
        )
        return sanitizeText(gson.toJson(out), maxLen = 2048)
    }

    fun sanitizeRequestScaffoldCompat(root: JsonObject): String {
        val readiness = root.getAsJsonObject("readiness")
        val compatWarnings = root.getAsJsonObject("compat_warnings")
        val sceneBundle = root.getAsJsonObject("scene_bundle")
        val ui = sceneBundle?.getAsJsonObject("ui")
        val layers = ui?.getAsJsonArray("layers")

        val safeLayers = mutableListOf<Map<String, Any>>()
        if (layers != null) {
            for (el in layers) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                safeLayers.add(
                    mapOf(
                        "id" to (o.get("id")?.asString ?: ""),
                        "label" to (o.get("label")?.asString ?: ""),
                        "kind" to (o.get("kind")?.asString ?: ""),
                        "default_on" to (o.get("default_on")?.asBoolean ?: false)
                    )
                )
                if (safeLayers.size >= 16) break
            }
        }

        val out = linkedMapOf<String, Any>()
        out["revision_id"] = root.get("revision_id")?.asString ?: ""
        if (readiness != null) {
            out["readiness"] = mapOf(
                "ready" to (readiness.get("ready")?.asBoolean ?: false),
                "score" to (readiness.get("score")?.asDouble ?: 0.0),
                "reasons" to readiness.get("reasons")?.asJsonArray?.toSafeStringList(12).orEmpty(),
                "relaxed" to (readiness.get("relaxed")?.asBoolean ?: false)
            )
        }
        if (compatWarnings != null) {
            out["compat_warnings"] = mapOf(
                "status" to (compatWarnings.get("status")?.asString ?: ""),
                "score" to (compatWarnings.get("score")?.asDouble ?: 0.0),
                "reasons" to compatWarnings.get("reasons")?.asJsonArray?.toSafeStringList(12).orEmpty(),
                "scan_plan" to compatWarnings.get("scan_plan")?.asJsonArray?.toSafeStringList(12).orEmpty()
            )
        }
        if (safeLayers.isNotEmpty()) out["layers"] = safeLayers
        return sanitizeText(gson.toJson(out), maxLen = 2048)
    }

    private fun JsonArray.toSafeStringList(limit: Int): List<String> {
        val out = ArrayList<String>(minOf(limit, this.size()))
        for (i in 0 until minOf(limit, this.size())) {
            val el: JsonElement = this[i]
            if (el.isJsonPrimitive) {
                out.add(sanitizeText(el.asString, maxLen = 256))
            }
        }
        return out
    }
}
