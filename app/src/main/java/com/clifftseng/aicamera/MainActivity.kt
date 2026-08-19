package com.clifftseng.aicamera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.media3.effect.Media3Effect
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var btnGallery: ImageButton
    private lateinit var txtPoseName: TextView
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var analysisExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

    /** -1 = 姿勢引導關閉；其餘是 PoseLibrary.POSES 的索引 */
    private var poseIndex = -1

    private var latestPhotoUri: Uri? = null

    // ── 色彩風格 ──
    private lateinit var txtColorName: TextView
    private var media3Effect: Media3Effect? = null
    private var colorMode = ColorMode.AUTO
    private var appliedLook = ColorMode.OFF   // AUTO 模式下實際套用的風格
    private var candidateLook: ColorMode? = null
    private var lookStreak = 0

    @Volatile
    private var lastLuma = 0.5f

    @Volatile
    private var lastStats: FrameStats? = null
    private var appliedStats: FrameStats? = null
    private var adaptCounter = 0

    // ── AI 取景建議（NIMA）＋ AI 調色（adaptive 3D LUT）──
    private lateinit var txtAiScore: TextView
    private var framingAdvisor: FramingAdvisor? = null
    private var lutEngine: LutColorEngine? = null
    private var lastLutWeights: FloatArray? = null
    private var lutBusy = false
    private var aiExecutor: ExecutorService? = null
    private var aiBusy = false
    private val aiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val aiTick = object : Runnable {
        override fun run() {
            scoreCurrentFraming()
            updateAiColor()
            aiHandler.postDelayed(this, 2000)
        }
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // 低通濾波後的重力向量，用來算側傾角，避免水平儀抖動
    private var gx = 0f
    private var gy = 9.8f

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.msg_need_camera_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        btnGallery = findViewById(R.id.btnGallery)
        txtPoseName = findViewById(R.id.txtPoseName)

        findViewById<ImageButton>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.btnSwitchCamera).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            overlayView.resetTracking()
            startCamera()
        }
        findViewById<ImageButton>(R.id.btnPose).setOnClickListener { cyclePose() }

        // 圓形縮圖
        btnGallery.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        btnGallery.clipToOutline = true
        btnGallery.setOnClickListener { openLatestPhoto() }

        txtColorName = findViewById(R.id.txtColorName)
        findViewById<ImageButton>(R.id.btnColor).setOnClickListener { cycleColorMode() }
        // Media3 GPU 效果：同一組色彩效果套在預覽與拍照輸出
        media3Effect = runCatching {
            Media3Effect(
                this,
                CameraEffect.PREVIEW or CameraEffect.IMAGE_CAPTURE,
                ContextCompat.getMainExecutor(this),
            ) { /* effect pipeline error：忽略，畫面退回原色 */ }
        }.getOrNull()
        updateColorLabel()

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseAnalyzer = PoseAnalyzer(
            this,
            onResult = { landmarks, imgW, imgH ->
                runOnUiThread {
                    overlayView.setDetectedPose(landmarks, imgW, imgH)
                    updateAutoLook(landmarks.isNotEmpty())
                    maybeReadapt()
                }
            },
            onStats = {
                lastStats = it
                lastLuma = it.luma
            },
        )

        // AI 取景建議：模型載入失敗（例如 assets 缺檔）就整組停用，App 照常運作
        txtAiScore = findViewById(R.id.txtAiScore)
        runCatching { AestheticScorer(this) }.onSuccess { scorer ->
            framingAdvisor = FramingAdvisor(scorer)
        }
        lutEngine = runCatching { LutColorEngine(this) }.getOrNull()
        if (framingAdvisor != null || lutEngine != null) {
            aiExecutor = Executors.newSingleThreadExecutor()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        loadLatestThumbnail()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (framingAdvisor != null || lutEngine != null) aiHandler.postDelayed(aiTick, 2000)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        aiHandler.removeCallbacks(aiTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        poseAnalyzer?.close()
        analysisExecutor.shutdown()
        aiExecutor?.shutdown()
        lutEngine?.close()
    }

    /** 每 2 秒抓最新影格，背景算美感分數與取景建議 */
    private fun scoreCurrentFraming() {
        val advisor = framingAdvisor ?: return
        val frame = poseAnalyzer?.latestFrame ?: return
        if (aiBusy) return
        aiBusy = true
        aiExecutor?.execute {
            val result = runCatching { advisor.analyze(frame) }.getOrNull()
            runOnUiThread {
                aiBusy = false
                if (result != null) {
                    txtAiScore.visibility = android.view.View.VISIBLE
                    txtAiScore.text =
                        getString(R.string.ai_score_fmt).format(result.currentScore) +
                        getString(result.direction.textRes)
                }
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            poseAnalyzer?.let { analyzer ->
                analyzer.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
                analysis.setAnalyzer(analysisExecutor, analyzer)
            }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.unbindAll()
            val capture = imageCapture!!
            val bound = runCatching {
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addUseCase(analysis)
                    .apply { media3Effect?.let { addEffect(it) } }
                    .build()
                provider.bindToLifecycle(this, selector, group)
            }.isSuccess
            if (!bound) {
                // 這台機器不支援效果 pipeline：退回無色彩風格模式
                media3Effect = null
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── 色彩風格 ──

    private fun cycleColorMode() {
        val modes = ColorMode.entries
        var next = modes[(colorMode.ordinal + 1) % modes.size]
        // 引擎沒載成功就跳過 AI 調色
        if (next == ColorMode.AI && lutEngine == null) {
            next = modes[(next.ordinal + 1) % modes.size]
        }
        colorMode = next
        candidateLook = null
        lookStreak = 0
        when (colorMode) {
            ColorMode.AUTO -> appliedLook = ColorMode.OFF // 讓下一幀的自動判斷立刻重套
            ColorMode.AI -> {
                appliedLook = ColorMode.AI
                lastLutWeights = null // 強制重新預測
                updateAiColor()
            }
            else -> applyLook(colorMode)
        }
        updateColorLabel()
    }

    /** AI 調色：預測這一幕的 LUT 權重，權重明顯變了才重建並套用 LUT */
    private fun updateAiColor() {
        if (colorMode != ColorMode.AI) return
        val engine = lutEngine ?: return
        val frame = poseAnalyzer?.latestFrame ?: return
        if (lutBusy) return
        lutBusy = true
        aiExecutor?.execute {
            val built = runCatching {
                val w = engine.predictWeights(frame)
                val old = lastLutWeights
                val changed = old == null ||
                    kotlin.math.abs(w[0] - old[0]) > 0.05f ||
                    kotlin.math.abs(w[1] - old[1]) > 0.05f ||
                    kotlin.math.abs(w[2] - old[2]) > 0.05f
                if (changed) w to engine.buildCube(w) else null
            }.getOrNull()
            runOnUiThread {
                lutBusy = false
                if (built != null && colorMode == ColorMode.AI) {
                    lastLutWeights = built.first
                    media3Effect?.setEffects(
                        listOf(
                            androidx.media3.effect.SingleColorLut.createFromCube(built.second),
                        ),
                    )
                }
            }
        }
    }

    /** AUTO 模式：暗 → 夜景；有人 → 人像；否則風景。連續 30 幀（約 1–2 秒）才切換，避免跳動。 */
    private fun updateAutoLook(personsPresent: Boolean) {
        if (colorMode != ColorMode.AUTO) return
        val target = when {
            lastLuma < 0.22f -> ColorMode.NIGHT
            personsPresent -> ColorMode.PORTRAIT
            else -> ColorMode.LANDSCAPE
        }
        if (target == appliedLook) {
            candidateLook = null
            lookStreak = 0
            return
        }
        if (target == candidateLook) lookStreak++ else { candidateLook = target; lookStreak = 1 }
        if (lookStreak >= 30) {
            applyLook(target)
            updateColorLabel()
        }
    }

    private fun applyLook(look: ColorMode) {
        appliedLook = look
        appliedStats = lastStats
        media3Effect?.setEffects(ColorLooks.effectsFor(look, lastStats))
    }

    /**
     * 統計自適應的重套：畫面色彩「本質」變了（換場景、光線變化）才重算強度，
     * 每 60 幀（約 2–4 秒）最多檢查一次，微小變化不動作，避免預覽閃爍。
     */
    private fun maybeReadapt() {
        if (colorMode == ColorMode.AI) return
        if (appliedLook == ColorMode.OFF || media3Effect == null) return
        if (++adaptCounter < 60) return
        adaptCounter = 0
        val now = lastStats ?: return
        val old = appliedStats
        val changed = old == null ||
            kotlin.math.abs(now.saturation - old.saturation) > 0.06f ||
            kotlin.math.abs(now.contrast - old.contrast) > 0.04f ||
            kotlin.math.abs(now.warmth - old.warmth) > 0.04f ||
            kotlin.math.abs(now.luma - old.luma) > 0.08f
        if (changed) applyLook(appliedLook)
    }

    private fun updateColorLabel() {
        txtColorName.text = if (colorMode == ColorMode.AUTO) {
            getString(R.string.color_auto_prefix) + getString(appliedLook.nameRes)
        } else {
            getString(colorMode.nameRes)
        }
    }

    private fun cyclePose() {
        poseIndex = if (poseIndex >= PoseLibrary.POSES.lastIndex) -1 else poseIndex + 1
        if (poseIndex < 0) {
            overlayView.guidePose = null
            txtPoseName.setText(R.string.pose_off)
        } else {
            val pose = PoseLibrary.POSES[poseIndex]
            overlayView.guidePose = pose
            txtPoseName.setText(pose.nameRes)
        }
        txtPoseName.visibility = View.VISIBLE
        txtPoseName.removeCallbacks(hidePoseName)
        // 「關」的提示 2 秒後淡出；選中的姿勢名稱留在畫面上
        if (poseIndex < 0) txtPoseName.postDelayed(hidePoseName, 2000)
    }

    private val hidePoseName = Runnable { txtPoseName.visibility = View.GONE }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val name = "AICam_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AICamera")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, R.string.msg_saved, Toast.LENGTH_SHORT).show()
                    result.savedUri?.let { showThumbnail(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, R.string.msg_save_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            },
        )
    }

    /** 開 App 時撈本 App 資料夾裡最新一張照片當縮圖（重灌後撈不到就留空） */
    private fun loadLatestThumbnail() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/AICamera%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                showThumbnail(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
    }

    private fun showThumbnail(uri: Uri) {
        latestPhotoUri = uri
        runCatching {
            val thumb = contentResolver.loadThumbnail(uri, Size(128, 128), null)
            btnGallery.setImageBitmap(thumb)
        }
    }

    private fun openLatestPhoto() {
        val uri = latestPhotoUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // 低通濾波：只留重力成分
        val alpha = 0.15f
        gx += alpha * (event.values[0] - gx)
        gy += alpha * (event.values[1] - gy)
        // 直立拿手機時 x≈0、y≈g；順時針傾斜為正
        overlayView.rollDegrees = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
