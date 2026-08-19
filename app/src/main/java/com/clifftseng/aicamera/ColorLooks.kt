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

object ColorLooks {

    @OptIn(UnstableApi::class)
    fun effectsFor(mode: ColorMode): List<Effect> = when (mode) {
        // 人像：微暖膚色 + 一點點飽和與對比
        ColorMode.PORTRAIT -> listOf(
            RgbAdjustment.Builder().setRedScale(1.05f).setBlueScale(0.95f).build(),
            HslAdjustment.Builder().adjustSaturation(6f).build(),
            Contrast(0.05f),
        )
        // 風景：拉飽和與對比，天更藍草更綠
        ColorMode.LANDSCAPE -> listOf(
            HslAdjustment.Builder().adjustSaturation(18f).build(),
            Contrast(0.10f),
        )
        // 食物：暖色調 + 飽和，看起來更好吃
        ColorMode.FOOD -> listOf(
            RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.02f).setBlueScale(0.90f).build(),
            HslAdjustment.Builder().adjustSaturation(15f).build(),
            Contrast(0.06f),
        )
        // 夜景：提亮暗部 + 對比 + 微冷色
        ColorMode.NIGHT -> listOf(
            Brightness(0.06f),
            Contrast(0.12f),
            RgbAdjustment.Builder().setRedScale(0.97f).setBlueScale(1.05f).build(),
        )
        // 黑白：去飽和 + 對比
        ColorMode.MONO -> listOf(
            HslAdjustment.Builder().adjustSaturation(-100f).build(),
            Contrast(0.12f),
        )
        ColorMode.AUTO, ColorMode.OFF -> emptyList()
    }
}
