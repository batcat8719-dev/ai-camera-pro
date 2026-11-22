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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
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
        color = Color.argb(120, 0, 255, 170)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    fun updateOverlay(faces: List<RectF>, score: Int, roll: Float, aiActive: Boolean) {
        faceBoxes = faces
        shotScore = score
        rollDeg = roll
        isAiMode = aiActive
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAiMode) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Rule of Thirds Grid
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)

        // 2. Ideal Face Sweet-Spot Target (Top-Third Center)
        val tx = w * 0.5f
        val ty = h * 0.36f
        canvas.drawCircle(tx, ty, 18f, targetPaint)

        // 3. Horizon Spirit Level Bar in Center
        val isLevel = abs(rollDeg) <= 4.5f
        levelPaint.color = if (isLevel) Color.parseColor("#00FFAA") else Color.parseColor("#FFD54F")
        canvas.save()
        canvas.rotate(-rollDeg.coerceIn(-45f, 45f), w / 2f, h / 2f)
        canvas.drawLine(w / 2f - 70f, h / 2f, w / 2f - 18f, h / 2f, levelPaint)
        canvas.drawLine(w / 2f + 18f, h / 2f, w / 2f + 70f, h / 2f, levelPaint)
        canvas.restore()

        // 4. Face Lock Corner Brackets
        boxPaint.color = when {
            shotScore >= 85 -> Color.parseColor("#00FFAA")
            shotScore >= 60 -> Color.parseColor("#FFD54F")
            else -> Color.parseColor("#FF5252")
        }

        for (norm in faceBoxes) {
            val l = (norm.left * w).coerceIn(0f, w)
            val t = (norm.top * h).coerceIn(0f, h)
            val r = (norm.right * w).coerceIn(0f, w)
            val b = (norm.bottom * h).coerceIn(0f, h)
            val corner = ((r - l) * 0.22f).coerceIn(18f, 48f)

            // Top-left
            canvas.drawLine(l, t, l + corner, t, boxPaint)
            canvas.drawLine(l, t, l, t + corner, boxPaint)
            // Top-right
            canvas.drawLine(r, t, r - corner, t, boxPaint)
            canvas.drawLine(r, t, r, t + corner, boxPaint)
            // Bottom-left
            canvas.drawLine(l, b, l + corner, b, boxPaint)
            canvas.drawLine(l, b, l, b - corner, boxPaint)
            // Bottom-right
            canvas.drawLine(r, b, r - corner, b, boxPaint)
            canvas.drawLine(r, b, r, b - corner, boxPaint)
        }
    }
}
