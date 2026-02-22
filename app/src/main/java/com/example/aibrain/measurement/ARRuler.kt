package com.example.aibrain.measurement

import android.graphics.Color
import android.widget.TextView
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color as SceneColor
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.ViewRenderable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

data class MeasurementPoint(
    val anchor: Anchor,
    val pose: Pose,
    val node: AnchorNode,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getPosition(): Vector3 = Vector3(pose.tx(), pose.ty(), pose.tz())
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

enum class MeasurementType {
    LINEAR,
    HEIGHT,
    AREA
}

class ARRuler(
    private val sceneView: ArSceneView,
    private val scope: CoroutineScope
) {

    enum class Units { METRIC, IMPERIAL }

    var units: Units = Units.METRIC

    private var currentType: MeasurementType = MeasurementType.LINEAR
    private val currentPoints = mutableListOf<MeasurementPoint>()
    private val segmentNodes = mutableListOf<Node>()
    private var closingSegmentNode: Node? = null
    private var lastTrackingConfidence: String = "UNKNOWN"
    private var poseJitter: Float = 0f
    private var prevCamPos: Vector3? = null
    private var labelNode: Node? = null
    private var labelRenderable: ViewRenderable? = null
    private var labelTextView: TextView? = null
    private var updateListenerInstalled = false

    private var snapToSurface: Boolean = true
    private var showGrid: Boolean = true

    private val measurements = mutableListOf<Measurement>()

    var onMeasurementUpdate: ((Float, String) -> Unit)? = null
    var onMeasurementComplete: ((Measurement) -> Unit)? = null
    var onTrackingQuality: ((TrackingQuality.Result) -> Unit)? = null

    companion object {
        private const val POINT_RADIUS = 0.015f
        private const val LINE_THICKNESS = 0.0045f
        private const val MIN_DISTANCE = 0.01f
        private const val LABEL_MAX_DIST = 8f
        private const val HEIGHT_TILT_WARN_DEG = 15f
    }

    fun setSnapEnabled(enabled: Boolean) { snapToSurface = enabled }

    fun setGridEnabled(enabled: Boolean) { showGrid = enabled }

    fun updateCameraState(camera: Camera, currentHit: HitResult?) {
        val camPos = Vector3(camera.pose.tx(), camera.pose.ty(), camera.pose.tz())
        val prev = prevCamPos
        if (prev != null) {
            val delta = Vector3.subtract(camPos, prev).length()
            poseJitter = poseJitter * 0.85f + delta * 0.15f
        }
        prevCamPos = camPos

        val quality = TrackingQuality.evaluate(camera, currentHit, poseJitter)
        onTrackingQuality?.invoke(quality)
    }

    fun startMeasurement(type: MeasurementType = MeasurementType.LINEAR) {
        currentType = type
        clearCurrentMeasurement()
        installBillboardUpdater()
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
        } else {
            pose
        }

        val anchorNode = createPointMarker(anchor, trackingLevel)
        val point = MeasurementPoint(anchor, finalPose, anchorNode)
        currentPoints.add(point)

        when (currentType) {
            MeasurementType.LINEAR -> {
                if (currentPoints.size >= 2) {
                    updatePolyline(closed = false)
                    updateLabelForLinear()
                }
            }

            MeasurementType.HEIGHT -> {
                if (currentPoints.size >= 2) {
                    updateHeightVisual()
                    updateLabelForHeight()
                    checkHeightTilt()
                } else {
                    clearActiveLabel()
                    onMeasurementUpdate?.invoke(0f, "0.00 м")
                }
            }

            MeasurementType.AREA -> {
                if (currentPoints.size >= 2) updatePolyline(closed = false)
                updateAreaClosingPreview()
                if (currentPoints.size >= 3) updateLabelForAreaPreview()
                else {
                    clearActiveLabel()
                    onMeasurementUpdate?.invoke(0f, "0.00 м²")
                }
            }
        }
        return true
    }

    fun isHeightOrderCorrect(): Boolean {
        if (currentPoints.size < 2) return true
        val base = currentPoints.first().pose.ty()
        val top = currentPoints.last().pose.ty()
        return top >= base
    }

    fun closeAreaAndFinish(): Measurement? {
        if (currentType != MeasurementType.AREA || currentPoints.size < 3) return null
        updatePolyline(closed = true)
        closingSegmentNode?.setParent(null)
        closingSegmentNode = null
        return finishMeasurement()
    }

    fun undoLastPoint() {
        if (currentPoints.isEmpty()) return
        val last = currentPoints.removeLast()
        try { last.anchor.detach() } catch (_: Exception) {}
        last.node.setParent(null)

        when (currentType) {
            MeasurementType.LINEAR -> {
                updatePolyline(false)
                updateLabelForLinear()
            }

            MeasurementType.HEIGHT -> {
                updateHeightVisual()
                updateLabelForHeight()
            }

            MeasurementType.AREA -> {
                updatePolyline(false)
                updateAreaClosingPreview()
                if (currentPoints.size >= 3) updateLabelForAreaPreview()
                else {
                    clearActiveLabel()
                    onMeasurementUpdate?.invoke(0f, "0.00 м²")
                }
            }
        }
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
                Measurement(id = id, type = currentType, points = currentPoints.toList(), distance = dist, label = formatDistance(dist), timestamp = now)
            }

            MeasurementType.HEIGHT -> {
                val h = calculateHeight()
                Measurement(id = id, type = currentType, points = currentPoints.toList(), distance = h, label = formatDistance(h), timestamp = now, height = h)
            }

            MeasurementType.AREA -> {
                val a = calculateArea()
                val p = calculatePerimeter()
                Measurement(id = id, type = currentType, points = currentPoints.toList(), distance = p, label = formatArea(a), timestamp = now, area = a, perimeter = p)
            }
        }

        measurements.add(measurement)
        try {
            MeasurementStore(sceneView.context).append(measurement, lastTrackingConfidence)
        } catch (_: Exception) {}
        onMeasurementComplete?.invoke(measurement)

        currentPoints.clear()
        segmentNodes.clear()
        closingSegmentNode?.setParent(null)
        closingSegmentNode = null
        labelNode = null
        labelRenderable = null
        labelTextView = null

        return measurement
    }

    fun clearAll() {
        clearCurrentMeasurement()
        measurements.clear()
        try { MeasurementStore(sceneView.context).clear() } catch (_: Exception) {}
    }

    fun getSavedMeasurements(): List<Measurement> = measurements.toList()

    fun exportMeasurements(): String = try {
        MeasurementStore(sceneView.context).exportJson()
    } catch (_: Exception) {
        ""
    }

    fun buildShareIntent(): android.content.Intent? = try {
        MeasurementStore(sceneView.context).buildShareIntent()
    } catch (_: Exception) {
        null
    }

    private fun clearCurrentMeasurement() {
        currentPoints.forEach { p ->
            try { p.anchor.detach() } catch (_: Exception) {}
            p.node.setParent(null)
        }
        currentPoints.clear()
        segmentNodes.forEach { it.setParent(null) }
        segmentNodes.clear()
        closingSegmentNode?.setParent(null)
        closingSegmentNode = null
        clearActiveLabel()
    }

    private fun clearActiveLabel() {
        labelNode?.setParent(null)
        labelNode = null
        labelRenderable = null
        labelTextView = null
    }

    private fun createPointMarker(anchor: Anchor, quality: TrackingQuality.Level = TrackingQuality.Level.HIGH): AnchorNode {
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(sceneView.scene)

        scope.launch(Dispatchers.Main) {
            val color = when (quality) {
                TrackingQuality.Level.HIGH -> SceneColor(0f, 0.96f, 1f)
                TrackingQuality.Level.MEDIUM -> SceneColor(1f, 0.8f, 0f)
                TrackingQuality.Level.LOW -> SceneColor(1f, 0.2f, 0.2f)
            }
            MaterialFactory.makeOpaqueWithColor(sceneView.context, color).thenAccept { material ->
                val cube = ShapeFactory.makeCube(Vector3(POINT_RADIUS * 2f, POINT_RADIUS * 2f, POINT_RADIUS * 2f), Vector3.zero(), material)
                Node().apply {
                    renderable = cube
                    setParent(anchorNode)
                }
            }
        }
        return anchorNode
    }

    private fun updatePolyline(closed: Boolean) {
        segmentNodes.forEach { it.setParent(null) }
        segmentNodes.clear()
        if (currentPoints.size < 2) return

        scope.launch(Dispatchers.Main) {
            MaterialFactory.makeOpaqueWithColor(sceneView.context, SceneColor(0f, 0.96f, 1f, 0.85f)).thenAccept { material ->
                for (i in 0 until currentPoints.size - 1) {
                    createSegment(currentPoints[i].getPosition(), currentPoints[i + 1].getPosition(), material, addToList = true)
                }
                if (closed && currentPoints.size >= 3) {
                    createSegment(currentPoints.last().getPosition(), currentPoints.first().getPosition(), material, addToList = true)
                }
            }
        }
    }

    private fun updateAreaClosingPreview() {
        closingSegmentNode?.setParent(null)
        closingSegmentNode = null
        if (currentPoints.size < 3) return

        scope.launch(Dispatchers.Main) {
            MaterialFactory.makeOpaqueWithColor(sceneView.context, SceneColor(1f, 0.55f, 0.2f, 0.55f)).thenAccept { mat ->
                val start = currentPoints.last().getPosition()
                val end = currentPoints.first().getPosition()
                closingSegmentNode = createSegment(start, end, mat, addToList = false)
            }
        }
    }

    private fun createSegment(start: Vector3, end: Vector3, material: Material, addToList: Boolean): Node? {
        val dir = Vector3.subtract(end, start)
        val dist = dir.length()
        if (dist < MIN_DISTANCE) return null

        val cylinder = ShapeFactory.makeCylinder(LINE_THICKNESS, dist, Vector3(0f, dist / 2f, 0f), material)
        val node = Node().apply {
            renderable = cylinder
            worldPosition = Vector3.add(start, end).scaled(0.5f)
            worldRotation = Quaternion.lookRotation(dir.normalized(), Vector3.up())
        }
        node.setParent(sceneView.scene)
        if (addToList) segmentNodes.add(node)
        return node
    }

    private fun updateHeightVisual() {
        segmentNodes.forEach { it.setParent(null) }
        segmentNodes.clear()
        if (currentPoints.size < 2) return

        val base = currentPoints.first().getPosition()
        val top = currentPoints.last().getPosition()
        val verticalTop = Vector3(base.x, top.y, base.z)

        scope.launch(Dispatchers.Main) {
            MaterialFactory.makeOpaqueWithColor(sceneView.context, SceneColor(1f, 0.55f, 0.2f, 0.9f)).thenAccept { material ->
                createSegment(base, verticalTop, material, addToList = true)
            }
        }
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

    private fun updateLabelForLinear() {
        val value = calculateTotalDistance()
        if (currentPoints.size < 2) {
            clearActiveLabel(); onMeasurementUpdate?.invoke(0f, formatDistance(0f)); return
        }
        val a = currentPoints[currentPoints.size - 2].getPosition()
        val b = currentPoints.last().getPosition()
        val mid = Vector3.add(a, b).scaled(0.5f)
        createOrUpdateFloatingLabel(mid, formatDistance(value), forceUpdate = true)
        onMeasurementUpdate?.invoke(value, formatDistance(value))
    }

    private fun updateLabelForHeight() {
        val h = calculateHeight()
        if (currentPoints.size < 2) {
            clearActiveLabel(); onMeasurementUpdate?.invoke(0f, formatDistance(0f)); return
        }
        val base = currentPoints.first().getPosition()
        val top = currentPoints.last().getPosition()
        val verticalTop = Vector3(base.x, top.y, base.z)
        val mid = Vector3.add(base, verticalTop).scaled(0.5f)
        createOrUpdateFloatingLabel(mid, formatDistance(h), forceUpdate = true)
        onMeasurementUpdate?.invoke(h, formatDistance(h))
    }

    private fun updateLabelForAreaPreview() {
        if (currentPoints.size < 3) {
            clearActiveLabel(); onMeasurementUpdate?.invoke(0f, "0.00 м²"); return
        }
        val a = calculateArea()
        val centroid = calculateCentroidXZ()
        createOrUpdateFloatingLabel(centroid, formatArea(a), forceUpdate = true)
        onMeasurementUpdate?.invoke(a, formatArea(a))
    }

    private fun createOrUpdateFloatingLabel(position: Vector3, text: String, forceUpdate: Boolean = false) {
        scope.launch(Dispatchers.Main) {
            if (labelNode == null) {
                val tv = TextView(sceneView.context).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xCC000000.toInt())
                    textSize = 15f
                    setPadding(18, 10, 18, 10)
                    this.text = text
                }
                ViewRenderable.builder().setView(sceneView.context, tv).build().thenAccept { renderable ->
                    labelRenderable = renderable
                    labelTextView = tv
                    val node = Node().apply {
                        this.renderable = renderable
                        worldPosition = clampLabelPosition(position)
                        localScale = Vector3(0.65f, 0.65f, 0.65f)
                    }
                    node.setParent(sceneView.scene)
                    labelNode = node
                }
            } else {
                labelNode?.worldPosition = clampLabelPosition(position)
                if (forceUpdate) {
                    (labelRenderable?.view as? TextView)?.text = text
                }
            }
        }
    }

    private fun clampLabelPosition(pos: Vector3): Vector3 {
        val camPos = try {
            sceneView.scene.camera.worldPosition
        } catch (_: Exception) {
            return pos
        }
        val toLabel = Vector3.subtract(pos, camPos)
        val dist = toLabel.length()
        return if (dist > LABEL_MAX_DIST) {
            val clamped = toLabel.normalized().scaled(LABEL_MAX_DIST)
            Vector3.add(camPos, clamped)
        } else {
            pos
        }
    }

    private fun installBillboardUpdater() {
        if (updateListenerInstalled) return
        updateListenerInstalled = true

        sceneView.scene.addOnUpdateListener {
            val node = labelNode ?: return@addOnUpdateListener
            val camPos = sceneView.scene.camera.worldPosition
            val dir = Vector3.subtract(camPos, node.worldPosition)
            if (dir.length() > 0.0001f) {
                node.worldRotation = Quaternion.lookRotation(dir.normalized(), Vector3.up())
            }
        }
    }

    private fun snapPoseToPlane(pose: Pose, plane: Plane): Pose {
        val center = plane.centerPose
        return Pose.makeTranslation(pose.tx(), center.ty(), pose.tz())
    }

    private fun calculateTotalDistance(): Float {
        if (currentPoints.size < 2) return 0f
        var total = 0f
        for (i in 0 until currentPoints.size - 1) {
            total += Vector3.subtract(currentPoints[i + 1].getPosition(), currentPoints[i].getPosition()).length()
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
            total += Vector3.subtract(currentPoints[i + 1].getPosition(), currentPoints[i].getPosition()).length()
        }
        if (currentPoints.size >= 3) {
            total += Vector3.subtract(currentPoints.first().getPosition(), currentPoints.last().getPosition()).length()
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

    private fun calculateCentroidXZ(): Vector3 {
        val pts = currentPoints.map { it.getPosition() }
        if (pts.isEmpty()) return Vector3.zero()
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (p in pts) {
            sx += p.x
            sy += p.y
            sz += p.z
        }
        val n = max(1, pts.size)
        return Vector3(sx / n, sy / n, sz / n)
    }

    internal fun formatDistance(meters: Float): String {
        val m = max(0f, meters)
        return when (units) {
            Units.METRIC -> if (m >= 1f) "%.2f м".format(m) else "%.1f см".format(m * 100f)
            Units.IMPERIAL -> {
                val feet = m * 3.28084f
                if (feet >= 1f) "%.2f ft".format(feet) else "%.1f in".format(feet * 12f)
            }
        }
    }

    internal fun formatArea(m2: Float): String {
        val a = max(0f, m2)
        return when (units) {
            Units.METRIC -> "%.2f м²".format(a)
            Units.IMPERIAL -> "%.2f ft²".format(a * 10.7639104f)
        }
    }
}
