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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseAnalyzer = PoseAnalyzer(this) { landmarks, imgW, imgH ->
            runOnUiThread { overlayView.setDetectedPose(landmarks, imgW, imgH) }
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
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        poseAnalyzer?.close()
        analysisExecutor.shutdown()
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
            provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
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
