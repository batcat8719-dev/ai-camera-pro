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

    private var subjectBoxes: List<RectF> = emptyList()
    private var shotScore: Int = 0
    private var rollDeg: Float = 0f
    private var isAiMode: Boolean = true
    private var isRecording: Boolean = false
    private var show45Guide: Boolean = false
    private var isObjectMode: Boolean = false
    private var subjectLabel: String = ""

    private var focusX: Float = -1f
    private var focusY: Float = -1f
    private var focusExpireTime: Long = 0L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 1.6f
        style = Paint.Style.STROKE
    }

    private val crop45Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 204, 255, 0)
        strokeWidth = 2.4f
        style = Paint.Style.STROKE
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 204, 255, 0)
        strokeWidth = 2.4f
        style = Paint.Style.STROKE
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2E4D")
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    private val gaugeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val gaugeFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        textSize = 27f
        isFakeBoldText = true
    }

    fun updateOverlay(
        boxes: List<RectF>,
        score: Int,
        roll: Float,
        aiActive: Boolean,
        recording: Boolean = false,
        tag: String = "",
        guide45: Boolean = false,
        objectTarget: Boolean = false
    ) {
        subjectBoxes = boxes
        shotScore = score
        rollDeg = roll
        isAiMode = aiActive
        isRecording = recording
        subjectLabel = tag
        show45Guide = guide45
        isObjectMode = objectTarget
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

        if (isRecording) {
            canvas.drawRect(5f, 5f, w - 5f, h - 5f, recPaint)
        }

        // Subtle 4:5 Instagram Portrait Frame Guide
        if (show45Guide && !isRecording) {
            val targetH = (w * 5f / 4f).coerceAtMost(h * 0.76f)
            val targetW = targetH * 4f / 5f
            val left = (w - targetW) / 2f
            val top = (h - targetH) / 2f - h * 0.04f
            val rect = RectF(
                left,
                top.coerceAtLeast(110f),
                left + targetW,
                (top + targetH).coerceAtMost(h - 240f)
            )
            canvas.drawRoundRect(rect, 18f, 18f, crop45Paint)
        }

        // Tap-to-Focus Ring
        if (System.currentTimeMillis() < focusExpireTime && focusX >= 0f && focusY >= 0f) {
            canvas.drawCircle(focusX, focusY, 52f, focusPaint)
            canvas.drawCircle(focusX, focusY, 6f, focusPaint)
        }

        if (!isAiMode) return

        // 1. Minimalist Rule of Thirds Grid
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)

        // 2. Sweet-spot indicator (Eye-line for human, center for object)
        val targetY = if (isObjectMode) h * 0.48f else h * 0.34f
        canvas.drawCircle(w * 0.5f, targetY, 11f, targetPaint)

        // 3. Sleek Center Horizon Level
        val isLevel = abs(rollDeg) <= 4.5f
        levelPaint.color = if (isLevel) Color.parseColor("#CCFF00") else Color.argb(175, 255, 255, 255)
        canvas.save()
        canvas.rotate(-rollDeg.coerceIn(-45f, 45f), w / 2f, h * 0.48f)
        canvas.drawLine(w / 2f - 58f, h * 0.48f, w / 2f - 16f, h * 0.48f, levelPaint)
        canvas.drawLine(w / 2f + 16f, h * 0.48f, w / 2f + 58f, h * 0.48f, levelPaint)
        canvas.restore()

        // 4. Right-Edge Slim Shot Quality Meter
        val barX = w - 22f
        val barTop = h * 0.28f
        val barBottom = h * 0.54f
        canvas.drawLine(barX, barTop, barX, barBottom, gaugeBgPaint)
        val fillTop = barBottom - (barBottom - barTop) * (shotScore.coerceIn(0, 100) / 100f)
        val scoreColor = when {
            isObjectMode && shotScore >= 80 -> Color.parseColor("#38BDF8")
            shotScore >= 85 -> Color.parseColor("#CCFF00")
            shotScore >= 65 -> Color.parseColor("#FFD166")
            else -> Color.parseColor("#FF4D6D")
        }
        gaugeFgPaint.color = scoreColor
        canvas.drawLine(barX, barBottom, barX, fillTop, gaugeFgPaint)

        // 5. Face or Object Lock Corner Brackets
        boxPaint.color = scoreColor
        labelPaint.color = scoreColor

        for (i in subjectBoxes.indices) {
            val norm = subjectBoxes[i]
            val l = (norm.left * w).coerceIn(0f, w)
            val t = (norm.top * h).coerceIn(0f, h)
            val r = (norm.right * w).coerceIn(0f, w)
            val b = (norm.bottom * h).coerceIn(0f, h)
            val corner = ((r - l) * 0.20f).coerceIn(18f, 44f)

            canvas.drawLine(l, t, l + corner, t, boxPaint)
            canvas.drawLine(l, t, l, t + corner, boxPaint)
            canvas.drawLine(r, t, r - corner, t, boxPaint)
            canvas.drawLine(r, t, r, t + corner, boxPaint)
            canvas.drawLine(l, b, l + corner, b, boxPaint)
            canvas.drawLine(l, b, l, b - corner, boxPaint)
            canvas.drawLine(r, b, r - corner, b, boxPaint)
            canvas.drawLine(r, b, r, b - corner, boxPaint)

            if (i == 0 && subjectLabel.isNotEmpty()) {
                canvas.drawText(subjectLabel, l, (t - 12f).coerceAtLeast(42f), labelPaint)
            }
        }
    }
}
