package com.clifftseng.aicamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 取景輔助層：三分構圖虛線、水平儀、偵測到的人體骨架、推薦姿勢虛線人形、構圖引導提示。
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

    // 已映射到 view 座標、且已過濾掉路人的主體們（每點 [x, y, visibility]）
    private var subjects: List<List<FloatArray>> = emptyList()
    private val advisor = CompositionAdvisor()
    private var advice: CompositionAdvisor.Advice? = null

    /**
     * MediaPipe 結果進來：把正規化座標映到 view 座標（PreviewView 預設 FILL_CENTER
     * 置中裁切，這裡用同一套縮放），過濾路人後餵給構圖規則引擎。
     *
     * 路人過濾：拍攝主體一定離鏡頭比較近、在畫面裡比較大——只保留
     * 「身高 ≥ 最高者 55% 且 ≥ 畫面高度 12%」的人，其餘視為背景路人，
     * 不畫骨架、構圖規則也不理會。
     */
    fun setDetectedPose(persons: List<List<FloatArray>>, imgW: Int, imgH: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        subjects = if (persons.isNotEmpty() && imgW > 0 && imgH > 0 && w > 0 && h > 0) {
            val scale = max(w / imgW, h / imgH)
            val dx = (w - imgW * scale) / 2f
            val dy = (h - imgH * scale) / 2f
            val mapped = persons.map { person ->
                person.map {
                    floatArrayOf(it[0] * imgW * scale + dx, it[1] * imgH * scale + dy, it[2])
                }
            }
            val heights = mapped.map { personHeight(it) }
            val tallest = heights.max()
            mapped.filterIndexed { i, _ ->
                heights[i] >= tallest * 0.55f && heights[i] >= h * 0.12f
            }
        } else {
            emptyList()
        }
        advice = advisor.update(subjects.ifEmpty { null }, w, h)
        invalidate()
    }

    private fun personHeight(pts: List<FloatArray>): Float {
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (i in BODY_BBOX_POINTS) {
            minY = min(minY, pts[i][1])
            maxY = max(maxY, pts[i][1])
        }
        return maxY - minY
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

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        style = Paint.Style.FILL
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val adviceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    private val adviceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawThirdsGrid(canvas, w, h)
        val personBox = drawDetectedSkeleton(canvas)
        drawGhostPose(canvas, w, h, personBox)
        drawAdvice(canvas, w, h)
        drawLevelIndicator(canvas, w, h)
    }

    private fun drawThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    /** 畫所有主體的骨架；回傳「最大那位主體」的外框，給姿勢虛線人形錨定用。 */
    private fun drawDetectedSkeleton(canvas: Canvas): FloatArray? {
        var largestBox: FloatArray? = null
        var largestH = 0f
        for (pts in subjects) {
            if (pts.size < 33) continue
            for ((a, b) in BODY_EDGES) {
                canvas.drawLine(pts[a][0], pts[a][1], pts[b][0], pts[b][1], skeletonPaint)
            }
            for (i in BODY_JOINTS) {
                canvas.drawCircle(pts[i][0], pts[i][1], 7f, jointPaint)
            }

            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
            for (i in BODY_BBOX_POINTS) {
                minX = min(minX, pts[i][0]); maxX = max(maxX, pts[i][0])
                minY = min(minY, pts[i][1]); maxY = max(maxY, pts[i][1])
            }
            if (maxY - minY > largestH) {
                largestH = maxY - minY
                largestBox = floatArrayOf(minX, minY, maxX, maxY)
            }
        }
        return largestBox
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
        val poseH = max(0.01f, pMaxY - pMinY)

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
        val headR = hypot(
            (gx(PoseLibrary.HEAD) - gx(PoseLibrary.NECK)).toDouble(),
            (gy(PoseLibrary.HEAD) - gy(PoseLibrary.NECK)).toDouble(),
        ).toFloat() * 0.62f
        canvas.drawCircle(gx(PoseLibrary.HEAD), gy(PoseLibrary.HEAD), headR, ghostPaint)
    }

    /** 構圖引導：提示文字泡泡 + 引導箭頭 + 目標點 */
    private fun drawAdvice(canvas: Canvas, w: Float, h: Float) {
        val adv = advice ?: return
        val good = adv.hint == CompositionAdvisor.Hint.GOOD

        // 箭頭與目標圈
        val from = adv.arrowFrom
        val to = adv.arrowTo
        if (from != null && to != null) {
            drawArrow(canvas, from.x, from.y, to.x, to.y)
            if (adv.hint == CompositionAdvisor.Hint.MOVE_TO_THIRD ||
                adv.hint == CompositionAdvisor.Hint.EYE_LINE
            ) {
                canvas.drawCircle(to.x, to.y, 20f, targetPaint)
            }
        }

        // 文字泡泡（構圖 OK 時縮小、變綠）
        val text = context.getString(adv.hint.textRes)
        adviceTextPaint.textSize = if (good) 36f else 42f
        adviceBgPaint.color = if (good) Color.argb(170, 27, 94, 32) else Color.argb(170, 0, 0, 0)

        val textW = adviceTextPaint.measureText(text)
        val cx = w / 2f
        val cy = h * 0.185f
        val padX = 28f
        val padY = 20f
        val fm = adviceTextPaint.fontMetrics
        val rect = RectF(
            cx - textW / 2f - padX,
            cy + fm.top - padY,
            cx + textW / 2f + padX,
            cy + fm.bottom + padY,
        )
        canvas.drawRoundRect(rect, 26f, 26f, adviceBgPaint)
        canvas.drawText(text, cx, cy, adviceTextPaint)
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (len < 30f) return
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
        // 線留一點空間給箭頭頭部
        val headLen = 34f
        val ex = x2 - headLen * 0.6f * cos(angle)
        val ey = y2 - headLen * 0.6f * sin(angle)
        canvas.drawLine(x1, y1, ex, ey, arrowPaint)

        val path = Path()
        path.moveTo(x2, y2)
        path.lineTo(
            x2 - headLen * cos(angle - ARROW_SPREAD),
            y2 - headLen * sin(angle - ARROW_SPREAD),
        )
        path.lineTo(
            x2 - headLen * cos(angle + ARROW_SPREAD),
            y2 - headLen * sin(angle + ARROW_SPREAD),
        )
        path.close()
        canvas.drawPath(path, arrowHeadPaint)
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
        const val ARROW_SPREAD = 0.5f // 弧度，箭頭開角的一半

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
