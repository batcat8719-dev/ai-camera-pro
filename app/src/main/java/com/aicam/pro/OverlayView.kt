package com.aicam.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faceBoxes: List<RectF> = emptyList()
    private var shotScore: Int = 0
    private var rollDeg: Float = 0f
    private var isAiMode: Boolean = true
    private var isRecording: Boolean = false
    private var faceLabel: String = ""

    private var focusX: Float = -1f
    private var focusY: Float = -1f
    private var focusExpireTime: Long = 0L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 255, 170)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D00")
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }

    private val gaugeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val gaugeFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFAA")
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFAA")
        textSize = 30f
        isFakeBoldText = true
    }

    fun updateOverlay(
        faces: List<RectF>,
        score: Int,
        roll: Float,
        aiActive: Boolean,
        recording: Boolean = false,
        tag: String = ""
    ) {
        faceBoxes = faces
        shotScore = score
        rollDeg = roll
        isAiMode = aiActive
        isRecording = recording
        faceLabel = tag
        postInvalidate()
    }

    fun showFocusRing(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusExpireTime = System.currentTimeMillis() + 1400L
        postInvalidate()
        postDelayed({ postInvalidate() }, 1450L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Red frame glow while recording video
        if (isRecording) {
            canvas.drawRect(6f, 6f, w - 6f, h - 6f, recPaint)
        }

        // Tap-to-Focus Ring
        if (System.currentTimeMillis() < focusExpireTime && focusX >= 0f && focusY >= 0f) {
            canvas.drawCircle(focusX, focusY, 58f, focusPaint)
            canvas.drawCircle(focusX, focusY, 8f, focusPaint)
        }

        if (!isAiMode) return

        // 1. Rule of Thirds Grid
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)

        // 2. Ideal Subject Sweet-Spot (Upper-Third Center)
        canvas.drawCircle(w * 0.5f, h * 0.36f, 16f, targetPaint)

        // 3. Horizon Spirit Level Bar
        val isLevel = abs(rollDeg) <= 4.5f
        levelPaint.color = if (isLevel) Color.parseColor("#00FFAA") else Color.parseColor("#FFD54F")
        canvas.save()
        canvas.rotate(-rollDeg.coerceIn(-45f, 45f), w / 2f, h / 2f)
        canvas.drawLine(w / 2f - 70f, h / 2f, w / 2f - 18f, h / 2f, levelPaint)
        canvas.drawLine(w / 2f + 18f, h / 2f, w / 2f + 70f, h / 2f, levelPaint)
        canvas.restore()

        // 4. Right-Side Live Shot Quality Score Meter
        val barX = w - 26f
        val barTop = h * 0.30f
        val barBottom = h * 0.62f
        canvas.drawLine(barX, barTop, barX, barBottom, gaugeBgPaint)
        val fillTop = barBottom - (barBottom - barTop) * (shotScore.coerceIn(0, 100) / 100f)
        val scoreColor = when {
            shotScore >= 85 -> Color.parseColor("#00FFAA")
            shotScore >= 65 -> Color.parseColor("#FFD54F")
            else -> Color.parseColor("#FF5252")
        }
        gaugeFgPaint.color = scoreColor
        canvas.drawLine(barX, barBottom, barX, fillTop, gaugeFgPaint)

        // 5. Face Lock Brackets
        boxPaint.color = scoreColor
        labelPaint.color = scoreColor

        for (i in faceBoxes.indices) {
            val norm = faceBoxes[i]
            val l = (norm.left * w).coerceIn(0f, w)
            val t = (norm.top * h).coerceIn(0f, h)
            val r = (norm.right * w).coerceIn(0f, w)
            val b = (norm.bottom * h).coerceIn(0f, h)
            val corner = ((r - l) * 0.22f).coerceIn(18f, 48f)

            canvas.drawLine(l, t, l + corner, t, boxPaint)
            canvas.drawLine(l, t, l, t + corner, boxPaint)
            canvas.drawLine(r, t, r - corner, t, boxPaint)
            canvas.drawLine(r, t, r, t + corner, boxPaint)
            canvas.drawLine(l, b, l + corner, b, boxPaint)
            canvas.drawLine(l, b, l, b - corner, boxPaint)
            canvas.drawLine(r, b, r - corner, b, boxPaint)
            canvas.drawLine(r, b, r, b - corner, boxPaint)

            if (i == 0 && faceLabel.isNotEmpty()) {
                canvas.drawText(faceLabel, l, (t - 12f).coerceAtLeast(40f), labelPaint)
            }
        }
    }
}
