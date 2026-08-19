package com.clifftseng.aicamera

import android.graphics.Bitmap
import androidx.annotation.StringRes

/**
 * AI 取景建議：這就是「打分模型沒辦法變成引導、取景推薦模型才可以」的落地——
 * 對目前取景與往各方向偏移的候選取景框分別打美感分數，
 * 哪個候選明顯更高分，就建議往那個方向調整。純風景（沒有人）也有效。
 */
class FramingAdvisor(private val scorer: AestheticScorer) {

    enum class Direction(@StringRes val textRes: Int) {
        LEFT(R.string.ai_dir_left),
        RIGHT(R.string.ai_dir_right),
        UP(R.string.ai_dir_up),
        DOWN(R.string.ai_dir_down),
        CLOSER(R.string.ai_dir_closer),
        STAY(R.string.ai_dir_stay),
    }

    data class Result(val currentScore: Float, val bestScore: Float, val direction: Direction)

    /** 在背景執行緒呼叫；一輪約 6 次推論。 */
    fun analyze(frame: Bitmap): Result {
        val w = frame.width
        val h = frame.height
        // 基準取景：置中 86% 視窗（留邊讓候選框有偏移空間）
        val cw = (w * 0.86f).toInt()
        val ch = (h * 0.86f).toInt()
        val cx = (w - cw) / 2
        val cy = (h - ch) / 2
        val off = (w * 0.07f).toInt()
        val offY = (h * 0.07f).toInt()

        fun crop(x: Int, y: Int, cwidth: Int = cw, cheight: Int = ch): Bitmap =
            Bitmap.createBitmap(
                frame,
                x.coerceIn(0, w - cwidth),
                y.coerceIn(0, h - cheight),
                cwidth,
                cheight,
            )

        val current = scorer.score(crop(cx, cy))
        var best = current
        var bestDir = Direction.STAY
        val candidates = listOf(
            Direction.LEFT to crop(cx - off, cy),
            Direction.RIGHT to crop(cx + off, cy),
            Direction.UP to crop(cx, cy - offY),
            Direction.DOWN to crop(cx, cy + offY),
            Direction.CLOSER to crop(
                (w * 0.14f).toInt(), (h * 0.14f).toInt(),
                (w * 0.72f).toInt(), (h * 0.72f).toInt(),
            ),
        )
        for ((dir, bmp) in candidates) {
            val s = scorer.score(bmp)
            if (s > best) {
                best = s
                bestDir = dir
            }
        }
        // 要贏過現況一個看得出來的差距才建議，避免雜訊來回跳
        return Result(current, best, if (best - current >= MIN_GAIN) bestDir else Direction.STAY)
    }

    private companion object {
        const val MIN_GAIN = 0.12f
    }
}
