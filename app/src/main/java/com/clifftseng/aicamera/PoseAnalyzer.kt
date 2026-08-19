package com.clifftseng.aicamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * 把 CameraX 的影格餵給 MediaPipe Pose Landmarker（LIVE_STREAM 模式），
 * 偵測結果以「正規化座標 + 影像尺寸」回呼給 UI 執行緒。
 */
class PoseAnalyzer(
    context: Context,
    private val onResult: (persons: List<List<FloatArray>>, imageWidth: Int, imageHeight: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    /** 前鏡頭要鏡像，讓座標跟預覽畫面一致；由 MainActivity 依鏡頭方向設定 */
    @Volatile
    var mirror: Boolean = false

    private val landmarker: PoseLandmarker

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            // 支援多人合照；路人由 OverlayView 用相對大小過濾
            .setNumPoses(4)
            .setResultListener { result: PoseLandmarkerResult, input ->
                // 每點 [x, y, visibility]，構圖規則要靠 visibility 判斷肢體有沒有入鏡
                val persons = result.landmarks().map { person ->
                    person.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(1f)) }
                }
                onResult(persons, input.width, input.height)
            }
            .setErrorListener { e -> Log.e(TAG, "pose landmarker error", e) }
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.use { proxy ->
            // OUTPUT_IMAGE_FORMAT_RGBA_8888：單一 plane 直接拷進 Bitmap
            val raw = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(proxy.planes[0].buffer)

            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f, proxy.width / 2f, proxy.height / 2f)
            }
            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)

            landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        }
    }

    fun close() = landmarker.close()

    private companion object {
        const val TAG = "PoseAnalyzer"
    }
}
