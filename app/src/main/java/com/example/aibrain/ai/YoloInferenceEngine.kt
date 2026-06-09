package com.example.aibrain.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "YoloEngine"
        private const val MODEL_FILE = "yolov8n_scaffold.tflite"
        private const val LABELS_FILE = "scaffold_labels.txt"
        private const val INPUT_SIZE = 640
        private const val MAX_DETECTIONS = 300
        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val NUM_THREADS = 4
    }

    data class Detection(
        val classId: Int,
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,
        val isHazard: Boolean = false,
    )

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputBuffer: ByteBuffer? = null
    private var isReady = false

    private val outputBoxes = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
    private val outputScores = Array(1) { Array(MAX_DETECTIONS) { FloatArray(80) } }

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                numThreads = NUM_THREADS
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelBuffer, options)
            labels = FileUtil.loadLabels(context, LABELS_FILE)
            inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            isReady = true
        } catch (e: Exception) {
            Log.e(TAG, "YOLO init failed: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isReady || interpreter == null) return emptyList()
        val buf = inputBuffer ?: return emptyList()
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        buf.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255f)
            buf.putFloat(((px shr 8) and 0xFF) / 255f)
            buf.putFloat((px and 0xFF) / 255f)
        }
        buf.rewind()

        val outputs = mapOf(0 to outputBoxes, 1 to outputScores)
        try {
            interpreter!!.runForMultipleInputsOutputs(arrayOf(buf), outputs)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            return emptyList()
        }

        val candidates = mutableListOf<Detection>()
        for (i in 0 until MAX_DETECTIONS) {
            val scores = outputScores[0][i]
            val classId = scores.indices.maxByOrNull { scores[it] } ?: continue
            val conf = scores[classId]
            if (conf < CONF_THRESHOLD) continue

            val box = outputBoxes[0][i]
            val cx = box[0]
            val cy = box[1]
            val w = box[2]
            val h = box[3]
            val rect = RectF(
                (cx - w / 2f).coerceIn(0f, 1f),
                (cy - h / 2f).coerceIn(0f, 1f),
                (cx + w / 2f).coerceIn(0f, 1f),
                (cy + h / 2f).coerceIn(0f, 1f),
            )
            val label = labels.getOrElse(classId) { "class_$classId" }
            candidates.add(
                Detection(
                    classId = classId,
                    label = label,
                    confidence = conf,
                    boundingBox = rect,
                    isHazard = label == "person" || label == "hazard_zone",
                )
            )
        }
        return nms(candidates)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > IOU_THRESHOLD }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f
        val unionArea = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - interArea
        return interArea / unionArea
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        isReady = false
    }
}
