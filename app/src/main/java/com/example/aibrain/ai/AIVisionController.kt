package com.example.aibrain.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AIVisionController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val yolo = YoloInferenceEngine(context)
    private var yoloJob: Job? = null
    private var frameCounter = 0

    var yoloEveryNFrames: Int = 6
    var onYoloResults: ((List<YoloInferenceEngine.Detection>) -> Unit)? = null
    var onHazardDetected: ((List<YoloInferenceEngine.Detection>) -> Unit)? = null

    fun processFrame(jpegBase64: String) {
        frameCounter++
        if (frameCounter % yoloEveryNFrames != 0) return
        if (yoloJob?.isActive == true) return

        yoloJob = scope.launch(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(jpegBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                val detections = yolo.detect(bitmap)
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    onYoloResults?.invoke(detections)
                    val hazards = detections.filter { it.isHazard }
                    if (hazards.isNotEmpty()) onHazardDetected?.invoke(hazards)
                }
            } catch (e: Exception) {
                Log.w("AIVision", "YOLO frame error: ${e.message}")
            }
        }
    }

    fun detectionsToServerPayload(detections: List<YoloInferenceEngine.Detection>): List<Map<String, Any>> =
        detections.map { d ->
            mapOf(
                "class_id" to d.classId,
                "label" to d.label,
                "confidence" to d.confidence,
                "bbox" to listOf(d.boundingBox.left, d.boundingBox.top, d.boundingBox.right, d.boundingBox.bottom),
                "is_hazard" to d.isHazard,
            )
        }

    fun release() {
        yoloJob?.cancel()
        yolo.release()
    }
}
