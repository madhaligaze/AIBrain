package com.example.aibrain

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("/session/start")
    suspend fun startSession(): Response<SessionResponse>

    @POST("/session/stream/{session_id}")
    suspend fun streamData(
        @Path("session_id") sessionId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<StreamResponse>

    @POST("/session/model/{session_id}")
    suspend fun startModeling(
        @Path("session_id") sessionId: String
    ): Response<ModelingResponse>

    @POST("/session/model/{session_id}")
    suspend fun startModelingWithMeasurements(
        @Path("session_id") sessionId: String,
        @Body payload: ModelingWithMeasurementsPayload
    ): Response<ModelingResponse>

    @POST("/session/update/{session_id}")
    suspend fun updateStructure(
        @Path("session_id") sessionId: String,
        @Body action: UpdateAction
    ): Response<UpdateResponse>

    @POST("/session/preview_remove/{session_id}")
    suspend fun previewRemove(
        @Path("session_id") sessionId: String,
        @Query("element_id") elementId: String
    ): Response<PreviewResponse>

    @GET("/health")
    suspend fun healthCheck(): Response<HealthResponse>

    @GET("/session/voxels/{session_id}")
    suspend fun getVoxels(
        @Path("session_id") sessionId: String
    ): Response<VoxelResponse>

    @POST("/session/anchors")
    suspend fun postAnchors(
        @Body payload: AnchorPayload
    ): Response<AnchorsResponse>

    @POST("/session/lock")
    suspend fun lockSession(
        @Body payload: LockPayload
    ): Response<LockResponse>

    @GET("/session/{session_id}/readiness")
    suspend fun getReadiness(
        @Path("session_id") sessionId: String
    ): Response<ReadinessResponse>

    @GET("/session/{session_id}/export/latest")
    suspend fun exportLatest(
        @Path("session_id") sessionId: String
    ): Response<SceneBundleResponse>

    @POST("/session/log/{session_id}")
    suspend fun logEvent(
        @Path("session_id") sessionId: String,
        @Body payload: LogPayload
    ): Response<Unit>

    @POST("/session/report/{session_id}")
    suspend fun postSessionCrashReport(
        @Path("session_id") sessionId: String,
        @Body payload: CrashEnvelope
    ): Response<Unit>

    @POST("/telemetry/client_report")
    suspend fun postClientReport(
        @Body payload: ClientReportEnvelope
    ): Response<SimpleStatusResponse>
}

data class SessionResponse(
    val session_id: String,
    val status: String
)

data class StreamResponse(
    val status: String,
    val ai_hints: AiHints?
)

data class AiHints(
    val instructions: List<String>?,
    val warnings: List<String>?,
    val quality_score: Double?,
    val is_ready: Boolean?,
    val scan_plan: List<String>? = null,
    val next_best_views: List<String>? = null,
    val is_scan_complete: Boolean? = null
)

data class ModelingResponse(
    val status: String,
    val options: List<ScaffoldOption>?
)

data class ScaffoldOption(
    @SerializedName(value = "variant_name", alternate = ["name"])
    val variant_name: String = "Option",
    val material_info: String = "",
    val safety_score: Int = 0,
    val ai_critique: List<String>?,
    val elements: List<ScaffoldElement>? = null,
    val full_structure: List<ScaffoldElement>? = null,
    val stats: ScaffoldStats?,
    val physics: PhysicsResult?
)

data class ScaffoldElement(
    val id: String,
    val type: String,
    val start: ElementPoint,
    val end: ElementPoint,
    val stress_color: String? = null,
    val load_ratio: Double? = null
)

data class ElementPoint(
    val x: Float,
    val y: Float,
    val z: Float
)

data class ScaffoldStats(
    val total_nodes: Int,
    val total_beams: Int,
    val total_weight_kg: Int,
    val collisions_fixed: Int?
)

data class PhysicsResult(
    val status: String
)

data class ModelingWithMeasurementsPayload(
    val measurements_json: String,
    val manual_measurements: List<MeasurementConstraint> = emptyList()
)

data class MeasurementConstraint(
    val id: String,
    val type: String,
    val distance_m: Double,
    val label: String,
    val timestamp_ms: Long
)

data class HealthResponse(
    val status: String,
    val version: String,
    val modules: Map<String, Boolean>?
)

data class UpdateAction(
    val action: String,
    val element_id: String? = null,
    val element_data: ScaffoldElement? = null
)

data class UpdateResponse(
    val status: String,
    val is_stable: Boolean,
    val physics_status: String,
    val heatmap: List<HeatmapItem>,
    val affected_elements: List<String>,
    val collapsed: CollapsedData,
    val processing_time_ms: Int
)

data class HeatmapItem(
    val id: String,
    val color: String,
    val load_ratio: Double
)

data class CollapsedData(
    val nodes: List<String>,
    val elements: List<String>
)

data class PreviewResponse(
    val status: String,
    val element_id: String,
    val is_critical: Boolean,
    val would_collapse: List<String>,
    val collapse_count: Int,
    val warning: String
)

data class VoxelResponse(
    val status: String,
    val voxels: List<VoxelItem>,
    val bounds: Bounds,
    val resolution: Double,
    val total_count: Int
)

data class VoxelItem(
    val position: List<Float>,
    val type: String,
    val color: String,
    val alpha: Double,
    val radius: Float? = null
)

data class Bounds(
    val min: List<Float>,
    val max: List<Float>
)

data class SceneBundleResponse(
    val session_id: String,
    val revision_id: String? = null,
    val rev_id: String? = null,
    val env_mesh: EnvMeshFile? = null,
    val ui: UiConfig? = null
)

data class EnvMeshFile(
    val glb: LayerFile? = null,
    val obj: LayerFile? = null,
    val path: String? = null
)

data class UiConfig(
    val layers: List<UiLayer>? = null
)

data class UiLayer(
    val id: String,
    val label: String? = null,
    val kind: String? = null,
    val default_on: Boolean? = null,
    val file: UiLayerFile? = null
)

data class UiLayerFile(
    val glb: LayerFile? = null,
    val path: String? = null
)

data class LayerFile(
    val path: String? = null
)

data class LogPayload(
    val event: String,
    val timestamp_ms: Long,
    val data: Map<String, Any?> = emptyMap(),
    val device: LogDeviceInfo? = null
)

data class LogDeviceInfo(
    val model: String,
    val manufacturer: String,
    val sdk: Int
)

data class AnchorPayload(
    val session_id: String,
    val anchors: List<AnchorPointRequest>
)

data class AnchorPointRequest(
    val id: String,
    val kind: String,
    val position: List<Float>,
    val confidence: Float = 1.0f
)

data class AnchorsResponse(
    val status: String,
    val count: Int
)

data class LockPayload(
    val session_id: String,
    val selected_variant: String? = null,
    val measurements_json: String? = null,
    val manual_measurements: List<MeasurementConstraint> = emptyList()
)

data class LockResponse(
    val session_id: String,
    val rev_id: String,
    val env_mesh_present: Boolean? = null,
    val trace_ndjson: String? = null,
    val tsdf_available: Boolean? = null,
    val tsdf_reason: String? = null
)

data class ReadinessResponse(
    val session_id: String,
    val ready: Boolean,
    val score: Double,
    val reasons: List<String> = emptyList(),
    val readiness_metrics: ReadinessMetrics? = null
)

data class ReadinessMetrics(
    val observed_ratio: Double = 0.0,
    val view_diversity: Int = 0,
    val viewpoints: Int = 0,
    val min_observed_ratio: Double = 0.0,
    val min_views_per_anchor: Int = 0,
    val min_viewpoints: Int = 0,
    val anchor_count: Int = 0
)


data class CrashErrorItem(
    val where: String,
    val message: String,
    val timestamp_ms: Long,
    val stack: String? = null,
    val fatal: Boolean = false
)

data class CrashDeviceInfo(
    val model: String? = null,
    val manufacturer: String? = null,
    val sdk: Int? = null
)

data class CrashEnvelope(
    val session_id: String? = null,
    val timestamp_ms: Long,
    val app_version: String? = null,
    val build: String? = null,
    val platform: String? = "android",
    val device: CrashDeviceInfo? = null,
    val connection_status: String? = null,
    val server_base_url: String? = null,
    val last_export_rev: String? = null,
    val loaded_export_rev: String? = null,
    val last_revision_id: String? = null,
    val client_stats: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
    val errors: List<CrashErrorItem> = emptyList()
)


data class SimpleStatusResponse(
    val status: String
)

data class ClientErrorItem(
    val timestamp_ms: Long,
    val tag: String,
    val message: String,
    val stack: String? = null
)

data class ClientReproItem(
    val timestamp_ms: Long,
    val endpoint: String,
    val http_code: Int? = null,
    val body_snippet: String? = null,
    val error_snippet: String? = null
)

data class ClientReportEnvelope(
    val session_id: String? = null,
    val timestamp_ms: Long,
    val client_stats: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
    val last_export_rev: String? = null,
    val queued_actions: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
    val last_errors: List<ClientErrorItem> = emptyList(),
    val device: LogDeviceInfo? = null,
    val trigger: String? = null,
    val crash_marker: String? = null,
    val repro_pack: List<ClientReproItem> = emptyList()
)
