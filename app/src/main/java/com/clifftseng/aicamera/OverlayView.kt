package com.clifftseng.aicamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 取景輔助層：三分構圖虛線、水平儀、偵測到的人體骨架、推薦姿勢虛線人形。
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

    /** 目前選擇的推薦姿勢；null = 姿勢引導關閉 */
    var guidePose: GuidePose? = null
        set(value) {
            field = value
            invalidate()
        }

    // MediaPipe 偵測結果（正規化座標）與其影像尺寸
    private var detected: List<Pair<Float, Float>>? = null
    private var imageWidth = 1
    private var imageHeight = 1

    fun setDetectedPose(landmarks: List<Pair<Float, Float>>?, imgW: Int, imgH: Int) {
        detected = landmarks
        imageWidth = max(1, imgW)
        imageHeight = max(1, imgH)
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

    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 120, 230, 130)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 120, 230, 130)
        style = Paint.Style.FILL
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 102, 217, 255)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 16f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawThirdsGrid(canvas, w, h)
        val personBox = drawDetectedSkeleton(canvas, w, h)
        drawGhostPose(canvas, w, h, personBox)
        drawLevelIndicator(canvas, w, h)
    }

    private fun drawThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    /**
     * 畫偵測到的骨架；回傳人物在 view 座標的外框（沒偵測到人回 null）。
     * PreviewView 預設 FILL_CENTER（置中裁切），這裡用同一套縮放把影像座標映到 view。
     */
    private fun drawDetectedSkeleton(canvas: Canvas, w: Float, h: Float): FloatArray? {
        val pts = detected ?: return null
        if (pts.size < 33) return null

        val scale = max(w / imageWidth, h / imageHeight)
        val dx = (w - imageWidth * scale) / 2f
        val dy = (h - imageHeight * scale) / 2f
        fun px(p: Pair<Float, Float>) = p.first * imageWidth * scale + dx
        fun py(p: Pair<Float, Float>) = p.second * imageHeight * scale + dy

        for ((a, b) in BODY_EDGES) {
            canvas.drawLine(px(pts[a]), py(pts[a]), px(pts[b]), py(pts[b]), skeletonPaint)
        }
        for (i in BODY_JOINTS) {
            canvas.drawCircle(px(pts[i]), py(pts[i]), 7f, jointPaint)
        }

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (i in BODY_BBOX_POINTS) {
            minX = min(minX, px(pts[i])); maxX = max(maxX, px(pts[i]))
            minY = min(minY, py(pts[i])); maxY = max(maxY, py(pts[i]))
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** 推薦姿勢虛線人形：有人就貼齊人物外框，沒人就放畫面中央。 */
    private fun drawGhostPose(canvas: Canvas, w: Float, h: Float, personBox: FloatArray?) {
        val pose = guidePose ?: return

        var pMinX = Float.MAX_VALUE; var pMinY = Float.MAX_VALUE
        var pMaxX = Float.MIN_VALUE; var pMaxY = Float.MIN_VALUE
        for ((x, y) in pose.points) {
            pMinX = min(pMinX, x); pMaxX = max(pMaxX, x)
            pMinY = min(pMinY, y); pMaxY = max(pMaxY, y)
        }
        val poseW = max(0.01f, pMaxX - pMinX)
        val poseH = max(0.01f, pMaxY - pMinY)

        // 目標高度與中心：跟著人物走，或畫面中央 60% 高
        val targetH: Float
        val centerX: Float
        val bottomY: Float
        if (personBox != null) {
            targetH = (personBox[3] - personBox[1]).coerceAtLeast(h * 0.2f)
            centerX = (personBox[0] + personBox[2]) / 2f
            bottomY = personBox[3]
        } else {
            targetH = h * 0.6f
            centerX = w / 2f
            bottomY = h / 2f + targetH / 2f
        }
        val s = targetH / poseH
        fun gx(i: Int) = centerX + (pose.points[i].first - (pMinX + pMaxX) / 2f) * s
        fun gy(i: Int) = bottomY - (pMaxY - pose.points[i].second) * s

        for ((a, b) in PoseLibrary.EDGES) {
            canvas.drawLine(gx(a), gy(a), gx(b), gy(b), ghostPaint)
        }
        // 頭：以頭頸距離抓一個圓
        val headR = hypot(
            (gx(PoseLibrary.HEAD) - gx(PoseLibrary.NECK)).toDouble(),
            (gy(PoseLibrary.HEAD) - gy(PoseLibrary.NECK)).toDouble(),
        ).toFloat() * 0.62f
        canvas.drawCircle(gx(PoseLibrary.HEAD), gy(PoseLibrary.HEAD), headR, ghostPaint)
    }

    private fun drawLevelIndicator(canvas: Canvas, w: Float, h: Float) {
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
            canvas.drawLine(cx - half * 0.35f, cy, cx + half * 0.35f, cy, levelPaint)
        }
        canvas.restore()

        if (!level) {
            canvas.drawText("%+.0f°".format(rollDegrees), cx, cy - half * 0.45f, levelTextPaint)
        }
    }

    private companion object {
        /** MediaPipe 33 點模型的軀幹＋四肢連線（臉部細節不畫） */
        val BODY_EDGES = listOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28,
        )
        val BODY_JOINTS = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

        /** 外框計算含鼻子（0）跟腳跟（29,30），高度才會涵蓋頭到腳 */
        val BODY_BBOX_POINTS = listOf(0, 11, 12, 15, 16, 23, 24, 27, 28, 29, 30)
    }
}
