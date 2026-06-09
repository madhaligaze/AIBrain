package com.example.aibrain

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * SmartReticleView — живой AR-прицел.
 */
class SmartReticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { SEARCHING, READY_PLANE, READY_POINT, NO_SURFACE }

    enum class PlaneType { FLOOR, WALL, UNKNOWN }

    var state: State = State.SEARCHING
        private set
    var surfaceLabel: String = ""
        private set

    private val colorSearching = Color.parseColor("#5500F5FF")
    private val colorReadyPlane = Color.parseColor("#FF00FF88")
    private val colorReadyPoint = Color.parseColor("#FFFFB800")
    private val colorNoSurface = Color.parseColor("#AAFF3838")

    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintPulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }

    private val rotAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
    }

    private val pulseAnim = ValueAnimator.ofFloat(1f, 1.22f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { pulseScale = it.animatedValue as Float; invalidate() }
    }

    private val flashAnim = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 500
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { flashAlpha = it.animatedValue as Float; invalidate() }
    }

    private var rotation = 0f
    private var pulseScale = 1f
    private var flashAlpha = 0f

    private var ovalScaleY = 1f
    private var targetOvalScaleY = 1f

    private val oval = RectF()
    private val pulseR = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    fun updateState(newState: State, label: String = "", planeType: PlaneType = PlaneType.UNKNOWN) {
        val changed = (newState != state)
        state = newState
        surfaceLabel = label

        targetOvalScaleY = when {
            newState == State.READY_PLANE && planeType == PlaneType.FLOOR -> 0.38f
            newState == State.READY_PLANE && planeType == PlaneType.WALL -> 0.75f
            newState == State.READY_PLANE -> 0.55f
            else -> 1.0f
        }

        ovalScaleY += (targetOvalScaleY - ovalScaleY) * 0.12f

        if (changed) {
            when (newState) {
                State.SEARCHING, State.NO_SURFACE -> {
                    pulseAnim.cancel(); rotAnim.start()
                }

                State.READY_PLANE, State.READY_POINT -> {
                    rotAnim.cancel(); pulseAnim.start()
                }
            }
        }
        invalidate()
    }

    fun flashPlacement() {
        flashAlpha = 1f
        flashAnim.cancel()
        flashAnim.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rotAnim.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotAnim.cancel(); pulseAnim.cancel(); flashAnim.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = min(width, height) / 2f * 0.70f

        val color = currentColor()

        ovalScaleY += (targetOvalScaleY - ovalScaleY) * 0.12f
        val rx = baseR
        val ry = baseR * ovalScaleY
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)

        if (flashAlpha > 0f) {
            paintFill.color = Color.argb((flashAlpha * 60).toInt(), 255, 255, 255)
            canvas.drawOval(oval, paintFill)
        }

        canvas.save()
        canvas.rotate(if (state == State.SEARCHING) rotation else 0f, cx, cy)

        if (state == State.READY_PLANE || state == State.READY_POINT) {
            val pr = baseR * pulseScale * 1.30f
            val py = pr * ovalScaleY
            pulseR.set(cx - pr, cy - py, cx + pr, cy + py)
            paintPulse.color = applyAlpha(color, (60 * (2f - pulseScale)).toInt().coerceIn(0, 60))
            canvas.drawOval(pulseR, paintPulse)
        }

        paintFill.color = applyAlpha(
            color, when (state) {
                State.READY_PLANE -> 28
                State.READY_POINT -> 20
                State.SEARCHING -> 12
                State.NO_SURFACE -> 18
            }
        )
        canvas.drawOval(oval, paintFill)

        paintRing.color = applyAlpha(
            color, when (state) {
                State.READY_PLANE -> 220
                State.READY_POINT -> 180
                State.SEARCHING -> 100
                State.NO_SURFACE -> 130
            }
        )
        paintRing.pathEffect = if (state == State.SEARCHING)
            android.graphics.DashPathEffect(floatArrayOf(12f, 12f), rotation % 24f)
        else null
        canvas.drawOval(oval, paintRing)

        if (state == State.READY_PLANE || state == State.READY_POINT) {
            paintCross.color = applyAlpha(color, 160)
            val armH = rx * 0.38f
            val armV = ry * 0.38f
            canvas.drawLine(cx - armH, cy, cx + armH, cy, paintCross)
            canvas.drawLine(cx, cy - armV, cx, cy + armV, paintCross)
        }

        paintTick.color = applyAlpha(color, 140)
        val tickLen = baseR * 0.15f
        drawTick(canvas, cx, cy, rx, ry, 0f, tickLen, paintTick)
        drawTick(canvas, cx, cy, rx, ry, 90f, tickLen, paintTick)
        drawTick(canvas, cx, cy, rx, ry, 180f, tickLen, paintTick)
        drawTick(canvas, cx, cy, rx, ry, 270f, tickLen, paintTick)

        val dotR = when (state) {
            State.READY_PLANE -> 5f
            State.READY_POINT -> 4f
            else -> 3f
        }
        paintDot.color = applyAlpha(color, if (state == State.SEARCHING) 120 else 240)
        canvas.drawCircle(cx, cy, dotR, paintDot)

        canvas.restore()

        if (surfaceLabel.isNotEmpty()) {
            paintLabel.color = applyAlpha(color, 200)
            val labelY = cy + ry + 34f
            canvas.drawText(surfaceLabel, cx, labelY, paintLabel)
        }
    }

    private fun currentColor(): Int = when (state) {
        State.READY_PLANE -> colorReadyPlane
        State.READY_POINT -> colorReadyPoint
        State.NO_SURFACE -> colorNoSurface
        State.SEARCHING -> colorSearching
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun drawTick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        angleDeg: Float,
        tickLen: Float,
        paint: Paint
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = Math.cos(rad).toFloat()
        val sinA = Math.sin(rad).toFloat()

        val px = cx + rx * cosA
        val py = cy + ry * sinA

        val nx = rx * cosA
        val ny = ry * sinA
        val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat().coerceAtLeast(0.001f)

        val ex = px + (nx / len) * tickLen
        val ey = py + (ny / len) * tickLen

        canvas.drawLine(px, py, ex, ey, paint)
    }
}
