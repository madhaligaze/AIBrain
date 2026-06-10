package com.example.aibrain.measurement

import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.distance
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.CoroutineScope
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class MeasurementPoint(
    val anchor: Anchor,
    val pose: Pose,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getPosition(): Float3 = Float3(pose.tx(), pose.ty(), pose.tz())
}

data class Measurement(
    val id: String,
    val type: MeasurementType,
    val points: List<MeasurementPoint>,
    val distance: Float,
    val label: String,
    val timestamp: Long = System.currentTimeMillis(),
    val area: Float? = null,
    val perimeter: Float? = null,
    val height: Float? = null
)

enum class MeasurementType { LINEAR, HEIGHT, AREA }

/**
 * AR ruler. The measurement MATH, data, callbacks and on-disk store are fully
 * preserved (and the 2D HUD shows live values via [onMeasurementUpdate]).
 *
 * TODO(sceneview-migration): the 3D AR overlay — point markers, segment lines and
 * floating labels — is stubbed (it used Sceneform `ShapeFactory`/`ViewRenderable`).
 * Re-implement with SceneView `CubeNode`/`CylinderNode` + `ViewNode`, with a
 * shortest-arc orientation for segments, and tune on device. See SCENEVIEW_MIGRATION.md.
 */
class ARRuler(
    private val sceneView: ARSceneView,
    private val scope: CoroutineScope
) {

    enum class Units { METRIC, IMPERIAL }

    var units: Units = Units.METRIC

    private var currentType: MeasurementType = MeasurementType.LINEAR
    private val currentPoints = mutableListOf<MeasurementPoint>()
    private var lastTrackingConfidence: String = "UNKNOWN"
    private var poseJitter: Float = 0f
    private var prevCamPos: Float3? = null

    private var snapToSurface: Boolean = true
    private var showGrid: Boolean = true

    private val measurements = mutableListOf<Measurement>()

    var onMeasurementUpdate: ((Float, String) -> Unit)? = null
    var onMeasurementComplete: ((Measurement) -> Unit)? = null
    var onTrackingQuality: ((TrackingQuality.Result) -> Unit)? = null

    companion object {
        private const val MIN_DISTANCE = 0.01f
        private const val HEIGHT_TILT_WARN_DEG = 15f
    }

    fun setSnapEnabled(enabled: Boolean) { snapToSurface = enabled }
    fun setGridEnabled(enabled: Boolean) { showGrid = enabled }

    fun updateCameraState(camera: Camera, currentHit: HitResult?) {
        val camPos = Float3(camera.pose.tx(), camera.pose.ty(), camera.pose.tz())
        val prev = prevCamPos
        if (prev != null) {
            val delta = distance(camPos, prev)
            poseJitter = poseJitter * 0.85f + delta * 0.15f
        }
        prevCamPos = camPos
        onTrackingQuality?.invoke(TrackingQuality.evaluate(camera, currentHit, poseJitter))
    }

    fun startMeasurement(type: MeasurementType = MeasurementType.LINEAR) {
        currentType = type
        clearCurrentMeasurement()
    }

    fun getPointCount(): Int = currentPoints.size

    fun getCurrentValue(): Float = when (currentType) {
        MeasurementType.LINEAR -> calculateTotalDistance()
        MeasurementType.HEIGHT -> calculateHeight()
        MeasurementType.AREA -> calculateArea()
    }

    fun getCurrentLabel(): String = when (currentType) {
        MeasurementType.LINEAR -> formatDistance(getCurrentValue())
        MeasurementType.HEIGHT -> formatDistance(getCurrentValue())
        MeasurementType.AREA -> formatArea(getCurrentValue())
    }

    fun evaluateQuality(camera: Camera, hit: HitResult?): TrackingQuality.Result =
        TrackingQuality.evaluate(camera, hit, poseJitter)

    fun addMeasurementPoint(
        hitResult: HitResult,
        trackingLevel: TrackingQuality.Level = TrackingQuality.Level.HIGH
    ): Boolean {
        lastTrackingConfidence = trackingLevel.name
        val anchor = try { hitResult.createAnchor() } catch (_: Exception) { null } ?: return false

        val pose = anchor.pose
        val finalPose = if (snapToSurface && hitResult.trackable is Plane) {
            snapPoseToPlane(pose, hitResult.trackable as Plane)
        } else pose

        currentPoints.add(MeasurementPoint(anchor, finalPose))

        when (currentType) {
            MeasurementType.LINEAR -> if (currentPoints.size >= 2) {
                val v = calculateTotalDistance(); onMeasurementUpdate?.invoke(v, formatDistance(v))
            }
            MeasurementType.HEIGHT -> if (currentPoints.size >= 2) {
                checkHeightTilt()
                val h = calculateHeight(); onMeasurementUpdate?.invoke(h, formatDistance(h))
            } else onMeasurementUpdate?.invoke(0f, formatDistance(0f))
            MeasurementType.AREA -> if (currentPoints.size >= 3) {
                val a = calculateArea(); onMeasurementUpdate?.invoke(a, formatArea(a))
            } else onMeasurementUpdate?.invoke(0f, formatArea(0f))
        }
        return true
    }

    fun isHeightOrderCorrect(): Boolean {
        if (currentPoints.size < 2) return true
        return currentPoints.last().pose.ty() >= currentPoints.first().pose.ty()
    }

    fun closeAreaAndFinish(): Measurement? {
        if (currentType != MeasurementType.AREA || currentPoints.size < 3) return null
        return finishMeasurement()
    }

    fun undoLastPoint() {
        if (currentPoints.isEmpty()) return
        val last = currentPoints.removeLast()
        runCatching { last.anchor.detach() }
        val value = getCurrentValue()
        onMeasurementUpdate?.invoke(value, getCurrentLabel())
    }

    fun finishMeasurement(): Measurement? {
        val ok = when (currentType) {
            MeasurementType.LINEAR -> currentPoints.size >= 2
            MeasurementType.HEIGHT -> currentPoints.size >= 2
            MeasurementType.AREA -> currentPoints.size >= 3
        }
        if (!ok) return null

        val id = "meas_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()

        val measurement = when (currentType) {
            MeasurementType.LINEAR -> {
                val dist = calculateTotalDistance()
                Measurement(id, currentType, currentPoints.toList(), dist, formatDistance(dist), now)
            }
            MeasurementType.HEIGHT -> {
                val h = calculateHeight()
                Measurement(id, currentType, currentPoints.toList(), h, formatDistance(h), now, height = h)
            }
            MeasurementType.AREA -> {
                val a = calculateArea()
                val p = calculatePerimeter()
                Measurement(id, currentType, currentPoints.toList(), p, formatArea(a), now, area = a, perimeter = p)
            }
        }

        measurements.add(measurement)
        runCatching { MeasurementStore(sceneView.context).append(measurement, lastTrackingConfidence) }
        onMeasurementComplete?.invoke(measurement)

        currentPoints.clear()
        return measurement
    }

    fun clearAll() {
        clearCurrentMeasurement()
        measurements.clear()
        runCatching { MeasurementStore(sceneView.context).clear() }
    }

    fun getSavedMeasurements(): List<Measurement> = measurements.toList()

    fun exportMeasurements(): String = runCatching { MeasurementStore(sceneView.context).exportJson() }.getOrDefault("")

    fun buildShareIntent(): android.content.Intent? =
        runCatching { MeasurementStore(sceneView.context).buildShareIntent() }.getOrNull()

    private fun clearCurrentMeasurement() {
        currentPoints.forEach { runCatching { it.anchor.detach() } }
        currentPoints.clear()
    }

    private fun snapPoseToPlane(pose: Pose, plane: Plane): Pose {
        val center = plane.centerPose
        return Pose.makeTranslation(pose.tx(), center.ty(), pose.tz())
    }

    private fun checkHeightTilt() {
        if (currentPoints.size < 2) return
        val base = currentPoints.first().getPosition()
        val top = currentPoints.last().getPosition()
        val dx = top.x - base.x
        val dz = top.z - base.z
        val dy = abs(top.y - base.y)
        val horizontal = kotlin.math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
        if (dy < 0.01f) return
        val tiltDeg = Math.toDegrees(kotlin.math.atan2(horizontal.toDouble(), dy.toDouble())).toFloat()
        if (tiltDeg > HEIGHT_TILT_WARN_DEG) {
            onMeasurementUpdate?.invoke(calculateHeight(), "Наклон ${"%.0f".format(tiltDeg)}°. Держите телефон вертикальнее.")
        }
    }

    private fun calculateTotalDistance(): Float {
        if (currentPoints.size < 2) return 0f
        var total = 0f
        for (i in 0 until currentPoints.size - 1) {
            total += distance(currentPoints[i + 1].getPosition(), currentPoints[i].getPosition())
        }
        return total
    }

    private fun calculateHeight(): Float {
        if (currentPoints.size < 2) return 0f
        return abs(currentPoints.last().pose.ty() - currentPoints.first().pose.ty())
    }

    private fun calculatePerimeter(): Float {
        if (currentPoints.size < 2) return 0f
        var total = 0f
        for (i in 0 until currentPoints.size - 1) {
            total += distance(currentPoints[i + 1].getPosition(), currentPoints[i].getPosition())
        }
        if (currentPoints.size >= 3) {
            total += distance(currentPoints.first().getPosition(), currentPoints.last().getPosition())
        }
        return total
    }

    internal fun calculateArea(): Float {
        if (currentPoints.size < 3) return 0f
        var sum = 0f
        val pts = currentPoints.map { it.getPosition() }
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            sum += pts[i].x * pts[j].z - pts[j].x * pts[i].z
        }
        return abs(sum) * 0.5f
    }

    // Two formatting paths, intentionally:
    //  • display  → device locale (a Russian user sees "2,00 м²"),
    //  • machine  → Locale.US ('.' decimal) for export / server / CSV stability.
    internal fun formatDistance(meters: Float): String = formatDistanceIn(meters, Locale.getDefault())

    internal fun formatArea(m2: Float): String = formatAreaIn(m2, Locale.getDefault())

    fun formatDistanceMachine(meters: Float): String = formatDistanceIn(meters, Locale.US)

    fun formatAreaMachine(m2: Float): String = formatAreaIn(m2, Locale.US)

    /** Locale-stable label for a saved measurement (for export / server payloads). */
    fun machineLabel(m: Measurement): String = when (m.type) {
        MeasurementType.AREA -> formatAreaMachine(m.area ?: m.distance)
        else -> formatDistanceMachine(m.distance)
    }

    private fun formatDistanceIn(meters: Float, locale: Locale): String {
        val m = max(0f, meters)
        return when (units) {
            Units.METRIC ->
                if (m >= 1f) String.format(locale, "%.2f м", m) else String.format(locale, "%.1f см", m * 100f)
            Units.IMPERIAL -> {
                val feet = m * 3.28084f
                if (feet >= 1f) String.format(locale, "%.2f ft", feet) else String.format(locale, "%.1f in", feet * 12f)
            }
        }
    }

    private fun formatAreaIn(m2: Float, locale: Locale): String {
        val a = max(0f, m2)
        return when (units) {
            Units.METRIC -> String.format(locale, "%.2f м²", a)
            Units.IMPERIAL -> String.format(locale, "%.2f ft²", a * 10.7639104f)
        }
    }
}
