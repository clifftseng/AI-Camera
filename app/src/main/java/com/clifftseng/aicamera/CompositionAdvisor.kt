package com.clifftseng.aicamera

import android.graphics.PointF
import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.min

/**
 * 構圖規則引擎：吃「view 座標系的人體關鍵點」，一次只給一個最重要的提示。
 *
 * 規則優先序：切到頭 > 切在小腿 > 主體對三分線 > 頭上空白太多 > 眼睛對上三分線。
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

    /** @param pts MediaPipe 33 點，已映射到 view 座標；每點 [x, y, visibility] */
    fun update(pts: List<FloatArray>?, w: Float, h: Float): Advice? {
        val raw = if (pts != null && pts.size >= 33 && w > 0 && h > 0) {
            evaluate(pts, w, h)
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

    private fun evaluate(pts: List<FloatArray>, w: Float, h: Float): Advice {
        fun x(i: Int) = pts[i][0]
        fun y(i: Int) = pts[i][1]
        fun vis(i: Int) = pts[i][2]
        fun inFrame(i: Int) = vis(i) > 0.5f && y(i) in 0f..h && x(i) in 0f..w

        // 頭頂估計：臉部點的最高處再往上抓半個頭寬
        val headWidth = abs(x(7) - x(8)).coerceAtLeast(h * 0.02f)
        var faceTop = Float.MAX_VALUE
        for (i in 0..10) faceTop = min(faceTop, y(i))
        val headTop = faceTop - headWidth * 0.6f

        val eyeY = (y(2) + y(5)) / 2f
        val eyeX = (x(2) + x(5)) / 2f
        val centerX = (x(11) + x(12) + x(23) + x(24)) / 4f
        val chestY = (y(11) + y(12) + y(23) + y(24)) / 4f

        // 人物高度：頭頂到最低的可見下肢點
        var bottom = (y(23) + y(24)) / 2f
        for (i in intArrayOf(25, 26, 27, 28)) if (inFrame(i)) bottom = maxOf(bottom, y(i))
        val personH = bottom - headTop

        // 1. 切到頭
        if (headTop < h * 0.015f) {
            return Advice(
                Hint.HEAD_CUT,
                PointF(eyeX, h * 0.06f),
                PointF(eyeX, h * 0.16f),
            )
        }

        // 2. 膝蓋在畫面內、腳踝不在 → 切在小腿
        val kneeIn = inFrame(25) || inFrame(26)
        val ankleOut = !inFrame(27) && !inFrame(28)
        if (kneeIn && ankleOut) {
            return Advice(Hint.LEG_CUT, null, null)
        }

        // 3. 主體對三分線（也接受正中央——居中構圖也是合法選擇）
        val anchors = floatArrayOf(w / 3f, w / 2f, w * 2f / 3f)
        var nearest = anchors[0]
        for (a in anchors) if (abs(centerX - a) < abs(centerX - nearest)) nearest = a
        if (abs(centerX - nearest) > w * 0.08f) {
            return Advice(
                Hint.MOVE_TO_THIRD,
                PointF(centerX, chestY),
                PointF(nearest, chestY),
            )
        }

        // 4. 頭上空白太多（人物偏小、頭頂離畫面頂端太遠）
        if (headTop > h * 0.30f && personH < h * 0.65f) {
            return Advice(
                Hint.HEADROOM,
                PointF(eyeX, headTop - h * 0.02f),
                PointF(eyeX, h * 0.10f),
            )
        }

        // 5. 人夠大時，眼睛對上三分線
        if (personH > h * 0.5f && abs(eyeY - h / 3f) > h * 0.12f) {
            return Advice(
                Hint.EYE_LINE,
                PointF(eyeX, eyeY),
                PointF(eyeX, h / 3f),
            )
        }

        return Advice(Hint.GOOD, null, null)
    }

    private companion object {
        // 偵測約 15–30fps，8 幀 ≈ 0.3–0.5 秒
        const val STABLE_FRAMES = 8
    }
}
