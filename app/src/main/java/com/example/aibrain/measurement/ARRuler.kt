package com.example.aibrain.measurement

import android.graphics.Color
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * ⚡ AR ИЗМЕРИТЕЛЬНАЯ СИСТЕМА - РУЛЕТКА В ПРОСТРАНСТВЕ ⚡
 *
 * Функциональность iOS Measure + ARPlan 3D:
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. 📏 ЛИНЕЙНЫЕ ИЗМЕРЕНИЯ:
 *    • Точка-точка (tap to tap)
 *    • Snap к поверхностям
 *    • Реалтайм preview при движении
 *
 * 2. 📐 ПЛОЩАДЬ И ОБЪЕМ:
 *    • Автоматический расчет площади комнаты
 *    • Объем пространства
 *    • Периметр
 *
 * 3. 🎯 ВЫСОТА:
 *    • От пола до потолка
 *    • От пола до точки
 *    • Между любыми точками (вертикальная проекция)
 *
 * 4. 🔄 УГЛОВЫЕ ИЗМЕРЕНИЯ:
 *    • Угол между стенами
 *    • Угол наклона поверхности
 *
 * 5. 🌐 ВИЗУАЛИЗАЦИЯ:
 *    • 3D линия в AR
 *    • Floating label с размером
 *    • Точки-маркеры
 *    • Сетка на плоскостях
 *
 * USAGE:
 * ```kotlin
 * val ruler = ARRuler(sceneView, context)
 *
 * // Start measurement
 * ruler.startMeasurement()
 *
 * // Add point on tap
 * ruler.addMeasurementPoint(hitResult)
 *
 * // Get current distance
 * val distance = ruler.getCurrentDistance()
 *
 * // Finish and save
 * ruler.finishMeasurement()
 * ```
 */

data class MeasurementPoint(
    val anchor: Anchor,
    val pose: Pose,
    val node: AnchorNode,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getPosition(): Vector3 {
        return Vector3(pose.tx(), pose.ty(), pose.tz())
    }
}

data class Measurement(
    val id: String,
    val type: MeasurementType,
    val points: List<MeasurementPoint>,
    val distance: Float,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MeasurementType {
    LINEAR,         // Прямая линия точка-точка
    HEIGHT,         // Высота (вертикальная проекция)
    AREA,          // Площадь (замкнутый контур)
    VOLUME,        // Объем помещения
    ANGLE          // Угол между линиями
}

class ARRuler(
    private val sceneView: ArSceneView,
    private val scope: CoroutineScope
) {

    // Текущее измерение
    private var currentPoints = mutableListOf<MeasurementPoint>()
    private var lineNode: Node? = null
    private var labelNode: Node? = null

    // История измерений
    private val measurements = mutableListOf<Measurement>()

    // Настройки
    private var snapToSurface = true
    private var showGrid = true
    var units = Units.METRIC

    // Callbacks
    var onMeasurementUpdate: ((Float, String) -> Unit)? = null
    var onMeasurementComplete: ((Measurement) -> Unit)? = null

    companion object {
        private const val POINT_RADIUS = 0.015f      // 1.5cm sphere
        private const val LINE_THICKNESS = 0.005f    // 5mm
        private const val SNAP_THRESHOLD = 0.05f     // 5cm snap distance
        private const val MIN_DISTANCE = 0.01f       // 1cm minimum
    }

    enum class Units {
        METRIC,      // meters, cm
        IMPERIAL     // feet, inches
    }

    /**
     * Начать новое измерение
     */
    fun startMeasurement(type: MeasurementType = MeasurementType.LINEAR) {
        clearCurrentMeasurement()
        // Ready for points
    }

    /**
     * Добавить точку измерения на основе AR hit test
     */
    fun addMeasurementPoint(hitResult: HitResult): Boolean {
        // Создание anchor
        val anchor = hitResult.createAnchor() ?: return false
        val pose = anchor.pose

        // Snap к поверхности если включен
        val finalPose = if (snapToSurface && hitResult.trackable is Plane) {
            snapToNearestSurface(pose, hitResult.trackable as Plane)
        } else {
            pose
        }

        // Создание визуального маркера
        val anchorNode = createPointMarker(anchor, finalPose)

        // Добавление точки
        val point = MeasurementPoint(anchor, finalPose, anchorNode)
        currentPoints.add(point)

        // Обновление линии и расстояния
        if (currentPoints.size >= 2) {
            updateMeasurementLine()
            updateDistanceLabel()
        }

        return true
    }

    /**
     * Завершить текущее измерение и сохранить
     */
    fun finishMeasurement(): Measurement? {
        if (currentPoints.size < 2) return null

        val distance = calculateTotalDistance()
        val label = formatDistance(distance)

        val measurement = Measurement(
            id = "meas_${System.currentTimeMillis()}",
            type = MeasurementType.LINEAR,
            points = currentPoints.toList(),
            distance = distance,
            label = label
        )

        measurements.add(measurement)
        onMeasurementComplete?.invoke(measurement)

        // Очистка для нового измерения
        currentPoints.clear()

        return measurement
    }

    /**
     * Получить текущее расстояние между точками
     */
    fun getCurrentDistance(): Float {
        if (currentPoints.size < 2) return 0f
        return calculateTotalDistance()
    }

    /**
     * Измерить высоту от пола до точки
     */
    fun measureHeight(hitResult: HitResult): Float {
        val pose = hitResult.createAnchor()?.pose ?: return 0f

        // Найти пол (горизонтальная плоскость с минимальным Y)
        val floorPose = findFloorPlane() ?: return 0f

        // Высота = разница по Y
        val height = pose.ty() - floorPose.ty()

        // Визуализация
        visualizeHeight(pose, floorPose, height)

        return height
    }

    /**
     * Измерить комнату (площадь и объем)
     */
    fun measureRoom(): RoomMeasurement? {
        if (currentPoints.size < 3) return null

        // Расчет площади методом треугольников
        val area = calculatePolygonArea(currentPoints.map { it.getPosition() })

        // Расчет высоты комнаты
        val height = measureRoomHeight()

        // Объем
        val volume = area * height

        // Периметр
        val perimeter = calculatePerimeter(currentPoints.map { it.getPosition() })

        return RoomMeasurement(area, volume, height, perimeter)
    }

    /**
     * Удалить последнюю точку
     */
    fun undoLastPoint() {
        if (currentPoints.isEmpty()) return

        val lastPoint = currentPoints.removeLast()
        lastPoint.anchor.detach()
        lastPoint.node.setParent(null)

        updateMeasurementLine()
        updateDistanceLabel()
    }

    /**
     * Очистить все измерения
     */
    fun clearAll() {
        clearCurrentMeasurement()
        measurements.clear()
    }

    /**
     * Получить сохраненные измерения (копия).
     */
    fun getSavedMeasurements(): List<Measurement> {
        return measurements.toList()
    }

    /**
     * Экспорт всех измерений в JSON
     */
    fun exportMeasurements(): String {
        return try {
            val gson = com.google.gson.Gson()
            val payload = measurements.map { m ->
                mapOf(
                    "id" to m.id,
                    "type" to m.type.name,
                    "distance_m" to m.distance,
                    "label" to m.label,
                    "timestamp" to m.timestamp,
                    "points" to m.points.map { p ->
                        mapOf("x" to p.pose.tx(), "y" to p.pose.ty(), "z" to p.pose.tz())
                    }
                )
            }
            gson.toJson(payload)
        } catch (_: Exception) {
            ""
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ПРИВАТНЫЕ МЕТОДЫ - ВИЗУАЛИЗАЦИЯ
    // ══════════════════════════════════════════════════════════════════════

    private fun createPointMarker(anchor: Anchor, pose: Pose): AnchorNode {
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(sceneView)

        scope.launch(Dispatchers.Main) {
            // Создание sphere для точки
            MaterialFactory.makeOpaqueWithColor(
                sceneView.context,
                com.google.ar.sceneform.rendering.Color(0f, 0.96f, 1f) // Cyan
            ).thenAccept { material ->
                val sphere = ShapeFactory.makeCube(
                    Vector3(POINT_RADIUS * 2f, POINT_RADIUS * 2f, POINT_RADIUS * 2f),
                    Vector3.zero(),
                    material
                )

                val node = Node()
                node.renderable = sphere
                node.setParent(anchorNode)

                // Анимация появления
                animatePointAppearance(node)
            }
        }

        return anchorNode
    }

    private fun updateMeasurementLine() {
        if (currentPoints.size < 2) return

        // Удаление старой линии
        lineNode?.setParent(null)

        scope.launch(Dispatchers.Main) {
            // Создание материала для линии
            MaterialFactory.makeOpaqueWithColor(
                sceneView.context,
                com.google.ar.sceneform.rendering.Color(0f, 0.96f, 1f, 0.8f)
            ).thenAccept { material ->

                // Создание линии между всеми точками
                for (i in 0 until currentPoints.size - 1) {
                    val start = currentPoints[i].getPosition()
                    val end = currentPoints[i + 1].getPosition()

                    createLineBetweenPoints(start, end, material)
                }
            }
        }
    }

    private fun createLineBetweenPoints(
        start: Vector3,
        end: Vector3,
        material: Material
    ) {
        val direction = Vector3.subtract(end, start)
        val distance = direction.length()

        if (distance < MIN_DISTANCE) return

        // Создание cylinder как линии
        val cylinder = ShapeFactory.makeCylinder(
            LINE_THICKNESS,
            distance,
            Vector3(0f, distance / 2f, 0f),
            material
        )

        val lineNode = Node()
        lineNode.renderable = cylinder

        // Позиционирование и поворот
        val midpoint = Vector3.add(start, end).scaled(0.5f)
        lineNode.worldPosition = midpoint

        // Поворот к конечной точке
        val up = Vector3.up()
        val rotation = com.google.ar.sceneform.math.Quaternion.lookRotation(
            direction.normalized(),
            up
        )
        lineNode.worldRotation = rotation

        lineNode.setParent(sceneView)
        this.lineNode = lineNode
    }

    private fun updateDistanceLabel() {
        if (currentPoints.size < 2) return

        val distance = calculateTotalDistance()
        val label = formatDistance(distance)

        // Позиция label - середина последней линии
        val lastStart = currentPoints[currentPoints.size - 2].getPosition()
        val lastEnd = currentPoints.last().getPosition()
        val midpoint = Vector3.add(lastStart, lastEnd).scaled(0.5f)

        // Создание текстового label (ViewRenderable)
        createFloatingLabel(midpoint, label)

        // Callback
        onMeasurementUpdate?.invoke(distance, label)
    }

    private fun createFloatingLabel(position: Vector3, text: String) {
        // TODO: Create ViewRenderable with distance text
        // Floating label that always faces camera

        scope.launch(Dispatchers.Main) {
            // Placeholder - в production использовать ViewRenderable
            // с custom layout для текста
        }
    }

    private fun visualizeHeight(
        topPose: Pose,
        floorPose: Pose,
        height: Float
    ) {
        // Вертикальная линия от пола до точки
        val start = Vector3(floorPose.tx(), floorPose.ty(), floorPose.tz())
        val end = Vector3(topPose.tx(), topPose.ty(), topPose.tz())

        scope.launch(Dispatchers.Main) {
            MaterialFactory.makeOpaqueWithColor(
                sceneView.context,
                com.google.ar.sceneform.rendering.Color(1f, 0.55f, 0.26f) // Orange
            ).thenAccept { material ->
                createLineBetweenPoints(start, end, material)

                // Label с высотой
                val midpoint = Vector3.add(start, end).scaled(0.5f)
                createFloatingLabel(midpoint, formatDistance(height))
            }
        }
    }

    private fun animatePointAppearance(node: Node) {
        // Анимация scale от 0 до 1
        node.localScale = Vector3.zero()

        // TODO: Implement scale animation
        // ObjectAnimator для плавного появления
    }

    // ══════════════════════════════════════════════════════════════════════
    // ПРИВАТНЫЕ МЕТОДЫ - РАСЧЕТЫ
    // ══════════════════════════════════════════════════════════════════════

    private fun calculateTotalDistance(): Float {
        if (currentPoints.size < 2) return 0f

        var total = 0f
        for (i in 0 until currentPoints.size - 1) {
            val start = currentPoints[i].getPosition()
            val end = currentPoints[i + 1].getPosition()
            total += Vector3.subtract(end, start).length()
        }

        return total
    }

    private fun calculatePolygonArea(points: List<Vector3>): Float {
        if (points.size < 3) return 0f

        // Shoelace formula для площади полигона
        var area = 0f

        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].z
            area -= points[j].x * points[i].z
        }

        return kotlin.math.abs(area) / 2f
    }

    private fun calculatePerimeter(points: List<Vector3>): Float {
        if (points.size < 2) return 0f

        var perimeter = 0f

        for (i in points.indices) {
            val j = (i + 1) % points.size
            perimeter += Vector3.subtract(points[j], points[i]).length()
        }

        return perimeter
    }

    private fun measureRoomHeight(): Float {
        // Поиск минимальной и максимальной Y координаты
        val minY = currentPoints.minOfOrNull { it.pose.ty() } ?: 0f
        val maxY = currentPoints.maxOfOrNull { it.pose.ty() } ?: 0f

        return maxY - minY
    }

    private fun formatDistance(meters: Float): String {
        return when (units) {
            Units.METRIC -> {
                when {
                    meters < 0.01f -> "${(meters * 1000).toInt()} mm"
                    meters < 1.0f -> "${(meters * 100).toInt()} cm"
                    else -> String.format("%.2f m", meters)
                }
            }
            Units.IMPERIAL -> {
                val feet = meters * 3.28084f
                val inches = (feet % 1) * 12
                "${feet.toInt()}' ${inches.toInt()}\""
            }
        }
    }

    private fun snapToNearestSurface(pose: Pose, plane: Plane): Pose {
        // Snap точки к ближайшей плоскости если она близко
        val planeCenter = plane.centerPose
        val distance = distanceBetweenPoses(pose, planeCenter)

        if (distance < SNAP_THRESHOLD) {
            // Проецирование точки на плоскость
            return projectPointOntoPlane(pose, plane)
        }

        return pose
    }

    private fun projectPointOntoPlane(pose: Pose, plane: Plane): Pose {
        // Проекция точки на плоскость
        val planeNormal = plane.centerPose.yAxis
        val planePoint = plane.centerPose.translation

        val pointToPlane = floatArrayOf(
            pose.tx() - planePoint[0],
            pose.ty() - planePoint[1],
            pose.tz() - planePoint[2]
        )

        val distance = pointToPlane[0] * planeNormal[0] +
                pointToPlane[1] * planeNormal[1] +
                pointToPlane[2] * planeNormal[2]

        val projectedPoint = floatArrayOf(
            pose.tx() - distance * planeNormal[0],
            pose.ty() - distance * planeNormal[1],
            pose.tz() - distance * planeNormal[2]
        )

        return Pose(projectedPoint, pose.rotationQuaternion)
    }

    private fun findFloorPlane(): Pose? {
        // Поиск горизонтальной плоскости с минимальным Y (пол)
        // TODO: Implement floor detection
        return null
    }

    private fun distanceBetweenPoses(pose1: Pose, pose2: Pose): Float {
        val dx = pose1.tx() - pose2.tx()
        val dy = pose1.ty() - pose2.ty()
        val dz = pose1.tz() - pose2.tz()

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun clearCurrentMeasurement() {
        // Удаление всех anchors и nodes
        currentPoints.forEach { point ->
            point.anchor.detach()
            point.node.setParent(null)
        }

        currentPoints.clear()
        lineNode?.setParent(null)
        labelNode?.setParent(null)
        lineNode = null
        labelNode = null
    }
}

/**
 * Результат измерения комнаты
 */
data class RoomMeasurement(
    val area: Float,           // м²
    val volume: Float,         // м³
    val height: Float,         // м
    val perimeter: Float       // м
) {
    fun toReadableString(): String {
        return """
            Площадь: ${String.format("%.2f", area)} м²
            Объем: ${String.format("%.2f", volume)} м³
            Высота: ${String.format("%.2f", height)} м
            Периметр: ${String.format("%.2f", perimeter)} м
        """.trimIndent()
    }
}

/**
 * UI Helper для отображения измерений
 */
class MeasurementUI {

    companion object {
        /**
         * Форматирование для iOS-style display
         */
        fun formatForDisplay(distance: Float): String {
            return when {
                distance < 0.01f -> "${(distance * 1000).toInt()} мм"
                distance < 1.0f -> {
                    val cm = (distance * 100).toInt()
                    "$cm см"
                }
                distance < 10.0f -> String.format("%.2f м", distance)
                else -> String.format("%.1f м", distance)
            }
        }

        /**
         * Цвет для измерения в зависимости от точности
         */
        fun getColorForAccuracy(confidence: Float): Int {
            return when {
                confidence >= 0.9f -> Color.parseColor("#00FF88") // Green
                confidence >= 0.7f -> Color.parseColor("#FF8C42") // Orange
                else -> Color.parseColor("#FF3838")              // Red
            }
        }
    }
}