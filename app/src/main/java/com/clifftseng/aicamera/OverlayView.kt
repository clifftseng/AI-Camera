package com.clifftseng.aicamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 取景輔助層：三分構圖虛線 + 水平儀。
 * 之後版本的姿勢虛線人形、構圖引導箭頭都會加畫在這個 View 上。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 手機側傾角（度）。0 = 水平；由 MainActivity 的感測器餵進來。 */
    var rollDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    // 水平儀在 ±這個角度以內視為「已水平」，變綠
    private val levelToleranceDeg = 1.5f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val levelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // ── 三分構圖虛線 ──
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)

        // ── 水平儀：畫在畫面中央，跟著世界水平線轉動 ──
        val level = abs(rollDegrees) <= levelToleranceDeg
        levelPaint.color = if (level) Color.rgb(76, 217, 100) else Color.argb(230, 255, 214, 10)

        val cx = w / 2f
        val cy = h / 2f
        val half = w * 0.16f

        canvas.save()
        // 手機順時針傾斜 θ 時，世界水平線在畫面上看起來逆時針轉 θ
        canvas.rotate(-rollDegrees, cx, cy)
        canvas.drawLine(cx - half, cy, cx - half * 0.35f, cy, levelPaint)
        canvas.drawLine(cx + half * 0.35f, cy, cx + half, cy, levelPaint)
        if (level) {
            // 水平時中段補起來，連成一直線
            canvas.drawLine(cx - half * 0.35f, cy, cx + half * 0.35f, cy, levelPaint)
        }
        canvas.restore()

        if (!level) {
            canvas.drawText("%+.0f°".format(rollDegrees), cx, cy - half * 0.45f, levelTextPaint)
        }
    }
}
