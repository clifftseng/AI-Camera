package com.clifftseng.aicamera

import android.graphics.PointF
import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.min

/**
 * 構圖規則引擎：吃「view 座標系的主體們（路人已被過濾）」，一次只給一個最重要的提示。
 *
 * 規則優先序：切到頭 > 切在小腿 > 群體中心對三分線 > 頭上空白太多 > 眼睛對上三分線（僅單人）。
 * 內建防抖：新提示要連續成立 [STABLE_FRAMES] 幀才會顯示，避免畫面一直閃。
 */
class CompositionAdvisor {

    enum class Hint(@StringRes val textRes: Int) {
        HEAD_CUT(R.string.advice_head_cut),
        LEG_CUT(R.string.advice_leg_cut),
        MOVE_TO_THIRD(R.string.advice_move_to_third),
        HEADROOM(R.string.advice_headroom),
        EYE_LINE(R.string.advice_eye_line),
        GOOD(R.string.advice_good),
    }

    /** arrowFrom/arrowTo 為 null 時只顯示文字 */
    data class Advice(val hint: Hint, val arrowFrom: PointF?, val arrowTo: PointF?)

    private var current: Advice? = null
    private var candidateHint: Hint? = null
    private var streak = 0

    /** @param persons 每位主體 MediaPipe 33 點，已映射到 view 座標；每點 [x, y, visibility] */
    fun update(persons: List<List<FloatArray>>?, w: Float, h: Float): Advice? {
        val valid = persons?.filter { it.size >= 33 }
        val raw = if (!valid.isNullOrEmpty() && w > 0 && h > 0) {
            evaluate(valid, w, h)
        } else {
            null
        }

        val hint = raw?.hint
        if (hint == current?.hint) {
            // 同一個提示：直接更新箭頭位置（跟著人動），不重新計時
            candidateHint = null
            streak = 0
            if (raw != null) current = raw
            return current
        }
        if (hint == candidateHint) {
            streak++
        } else {
            candidateHint = hint
            streak = 1
        }
        if (streak >= STABLE_FRAMES) {
            current = raw
            candidateHint = null
            streak = 0
        }
        return current
    }

    /** 單一主體的構圖相關量測 */
    private class Subject(pts: List<FloatArray>, w: Float, h: Float) {
        val headTop: Float
        val eyeX = (pts[2][0] + pts[5][0]) / 2f
        val eyeY = (pts[2][1] + pts[5][1]) / 2f
        val centerX = (pts[11][0] + pts[12][0] + pts[23][0] + pts[24][0]) / 4f
        val chestY = (pts[11][1] + pts[12][1] + pts[23][1] + pts[24][1]) / 4f
        val bottom: Float
        val height: Float
        val kneeIn: Boolean
        val ankleOut: Boolean

        init {
            fun inFrame(i: Int) =
                pts[i][2] > 0.5f && pts[i][1] in 0f..h && pts[i][0] in 0f..w

            // 頭頂估計：臉部點的最高處再往上抓半個頭寬
            val headWidth = abs(pts[7][0] - pts[8][0]).coerceAtLeast(h * 0.02f)
            var faceTop = Float.MAX_VALUE
            for (i in 0..10) faceTop = min(faceTop, pts[i][1])
            headTop = faceTop - headWidth * 0.6f

            var b = (pts[23][1] + pts[24][1]) / 2f
            for (i in intArrayOf(25, 26, 27, 28)) if (inFrame(i)) b = maxOf(b, pts[i][1])
            bottom = b
            height = bottom - headTop

            kneeIn = inFrame(25) || inFrame(26)
            ankleOut = !inFrame(27) && !inFrame(28)
        }
    }

    private fun evaluate(persons: List<List<FloatArray>>, w: Float, h: Float): Advice {
        val subjects = persons.map { Subject(it, w, h) }

        // 1. 有任何主體切到頭
        subjects.firstOrNull { it.headTop < h * 0.015f }?.let {
            return Advice(Hint.HEAD_CUT, PointF(it.eyeX, h * 0.06f), PointF(it.eyeX, h * 0.16f))
        }

        // 2. 有任何主體膝蓋在畫面內、腳踝不在 → 切在小腿
        if (subjects.any { it.kneeIn && it.ankleOut }) {
            return Advice(Hint.LEG_CUT, null, null)
        }

        // 3. 群體中心對三分線（也接受正中央——居中構圖也是合法選擇）
        val groupCenterX = subjects.map { it.centerX }.average().toFloat()
        val groupChestY = subjects.map { it.chestY }.average().toFloat()
        val anchors = floatArrayOf(w / 3f, w / 2f, w * 2f / 3f)
        var nearest = anchors[0]
        for (a in anchors) if (abs(groupCenterX - a) < abs(groupCenterX - nearest)) nearest = a
        if (abs(groupCenterX - nearest) > w * 0.08f) {
            return Advice(
                Hint.MOVE_TO_THIRD,
                PointF(groupCenterX, groupChestY),
                PointF(nearest, groupChestY),
            )
        }

        // 4. 頭上空白太多（以最高的頭為準；群體都偏小偏低才成立）
        val groupHeadTop = subjects.minOf { it.headTop }
        val groupBottom = subjects.maxOf { it.bottom }
        if (groupHeadTop > h * 0.30f && groupBottom - groupHeadTop < h * 0.65f) {
            val ref = subjects.minBy { it.headTop }
            return Advice(
                Hint.HEADROOM,
                PointF(ref.eyeX, groupHeadTop - h * 0.02f),
                PointF(ref.eyeX, h * 0.10f),
            )
        }

        // 5. 單人且人夠大時，眼睛對上三分線（多人眼睛高度不一，不適用）
        if (subjects.size == 1) {
            val s = subjects[0]
            if (s.height > h * 0.5f && abs(s.eyeY - h / 3f) > h * 0.12f) {
                return Advice(Hint.EYE_LINE, PointF(s.eyeX, s.eyeY), PointF(s.eyeX, h / 3f))
            }
        }

        return Advice(Hint.GOOD, null, null)
    }

    private companion object {
        // 偵測約 15–30fps，8 幀 ≈ 0.3–0.5 秒
        const val STABLE_FRAMES = 8
    }
}
