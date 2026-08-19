plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clifftseng.aicamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clifftseng.aicamera"
        // minSdk 29：MediaStore RELATIVE_PATH / loadThumbnail 都是 API 29 起，
        // 相簿存取才能不要任何儲存權限
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"

        // MediaPipe 原生庫很肥，只留 arm64（近年 Android 手機都是）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 姿勢偵測（on-device，模型檔在 assets/pose_landmarker_lite.task）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // 色彩風格：CameraX + Media3 GPU 效果，同一組效果套在預覽與拍照輸出
    implementation("androidx.camera.media3:media3-effect:1.0.0-alpha04")
    implementation("androidx.media3:media3-effect:1.6.0")

    // NIMA 美學評分模型（assets/nima_aesthetic.tflite）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
