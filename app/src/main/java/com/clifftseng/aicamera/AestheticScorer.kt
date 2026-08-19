package com.clifftseng.aicamera

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NIMA 美學評分（on-device）：MobileNet 在 AVA 資料集（25 萬張有人工美學評分的照片）
 * 上訓練的模型，輸出 1–10 分的分佈，取期望值當分數。
 * 模型來源：idealo/image-quality-assessment 的預訓練權重，轉成 float16 TFLite。
 */
class AestheticScorer(context: Context) {

    private val interpreter: Interpreter
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(10) }
    private val pixels = IntArray(SIZE * SIZE)

    init {
        val bytes = context.assets.open("nima_aesthetic.tflite").readBytes()
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** 回傳 1–10 的美感分數（10 檔分佈的期望值）。執行緒不安全，固定在同一條背景執行緒呼叫。 */
    fun score(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        // MobileNet 前處理：x / 127.5 - 1
        for (c in pixels) {
            input.putFloat((c shr 16 and 0xFF) / 127.5f - 1f)
            input.putFloat((c shr 8 and 0xFF) / 127.5f - 1f)
            input.putFloat((c and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        interpreter.run(input, output)
        var s = 0f
        for (i in 0 until 10) s += (i + 1) * output[0][i]
        return s
    }

    fun close() = interpreter.close()

    private companion object {
        const val SIZE = 224
    }
}
