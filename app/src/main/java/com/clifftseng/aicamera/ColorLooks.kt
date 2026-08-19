package com.clifftseng.aicamera

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * 色彩風格：GPU 效果（Media3）同時套在預覽與拍出的照片上。
 * AUTO 不是一種風格，而是依場景（人像／風景／夜景）自動挑一種。
 */
enum class ColorMode(@StringRes val nameRes: Int) {
    AUTO(R.string.color_auto),
    PORTRAIT(R.string.color_portrait),
    LANDSCAPE(R.string.color_landscape),
    FOOD(R.string.color_food),
    NIGHT(R.string.color_night),
    MONO(R.string.color_mono),
    OFF(R.string.color_off),
}

/** 一幀畫面的色彩統計（值皆 0..1，warmth 為 -1..1，正 = 偏暖） */
data class FrameStats(
    val luma: Float,
    val saturation: Float,
    val contrast: Float,
    val warmth: Float,
)

object ColorLooks {

    /** 各風格的基準參數；自適應是在這些基準上調整「強度」 */
    private class Params(
        var sat: Float = 0f,        // HslAdjustment 飽和度增減（百分比）
        var contrast: Float = 0f,   // Contrast（-1..1）
        var red: Float = 1f,
        var green: Float = 1f,
        var blue: Float = 1f,
        var brightness: Float = 0f, // Brightness（-1..1）
    )

    private fun base(mode: ColorMode): Params = when (mode) {
        ColorMode.PORTRAIT -> Params(sat = 6f, contrast = 0.05f, red = 1.05f, blue = 0.95f)
        ColorMode.LANDSCAPE -> Params(sat = 18f, contrast = 0.10f)
        ColorMode.FOOD -> Params(sat = 15f, contrast = 0.06f, red = 1.08f, green = 1.02f, blue = 0.90f)
        ColorMode.NIGHT -> Params(contrast = 0.12f, red = 0.97f, blue = 1.05f, brightness = 0.06f)
        ColorMode.MONO -> Params(sat = -100f, contrast = 0.12f)
        ColorMode.AUTO, ColorMode.OFF -> Params()
    }

    /**
     * 統計自適應：看這一幀本來的樣子調整強度——
     * 本來就鮮豔就少加飽和、平淡就多加；已經偏暖就收斂暖色偏移；夜景提亮看實際暗度。
     */
    private fun adapt(p: Params, s: FrameStats) {
        if (p.sat > 0f) {
            val f = (1f + (0.35f - s.saturation) / 0.35f * 0.8f).coerceIn(0.3f, 1.6f)
            p.sat *= f
        }
        if (p.contrast > 0f) {
            val f = (1f + (0.18f - s.contrast) / 0.18f * 0.8f).coerceIn(0.3f, 1.6f)
            p.contrast = (p.contrast * f).coerceAtMost(0.25f)
        }
        val warmShift = p.red - 1f
        if (warmShift > 0f) {
            val f = when {
                s.warmth > 0.06f -> 0.4f   // 畫面已經暖了，收斂
                s.warmth < -0.06f -> 1.3f  // 偏冷，加強
                else -> 1f
            }
            p.red = 1f + warmShift * f
            p.blue = 1f - (1f - p.blue) * f
        }
        if (p.brightness > 0f) {
            val f = ((0.25f - s.luma) / 0.25f).coerceIn(0.5f, 1.5f)
            p.brightness *= f
        }
    }

    @OptIn(UnstableApi::class)
    fun effectsFor(mode: ColorMode, stats: FrameStats? = null): List<Effect> {
        val p = base(mode)
        if (mode == ColorMode.AUTO || mode == ColorMode.OFF) return emptyList()
        if (stats != null) adapt(p, stats)

        val fx = mutableListOf<Effect>()
        if (p.red != 1f || p.green != 1f || p.blue != 1f) {
            fx += RgbAdjustment.Builder()
                .setRedScale(p.red).setGreenScale(p.green).setBlueScale(p.blue).build()
        }
        if (p.sat > 0.5f || p.sat < -0.5f) {
            fx += HslAdjustment.Builder().adjustSaturation(p.sat.coerceIn(-100f, 100f)).build()
        }
        if (p.contrast != 0f) fx += Contrast(p.contrast)
        if (p.brightness != 0f) fx += Brightness(p.brightness)
        return fx
    }
}
