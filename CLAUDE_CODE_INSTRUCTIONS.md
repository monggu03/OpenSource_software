# SafeWalk 프로젝트 셋업 명령서 (Claude Code용)

> 이 파일을 Claude Code에 넘겨서 프로젝트를 셋업하세요.
> 사용법: Claude Code 실행 후 "이 md 파일대로 프로젝트를 셋업해줘" 라고 입력

---

## 프로젝트 개요

- **앱 이름**: SafeWalk (시각장애인을 위한 오프라인 위험감지 앱)
- **패키지명**: com.example.safewalk
- **감지 대상**: 자동차(car) 1개 클래스 (MVP)
- **오프라인 동작**: AI 모델이 앱 내에서 실행됨

## 기술 스택

- Kotlin: UI, 카메라(CameraX), TTS, TFLite 추론, 생명주기
- C++ (NDK): OpenCV 이미지 전처리 (JNI로 연결)
- OpenCV Android SDK: `app/libs/OpenCV-android-sdk/`에 이미 배치됨
- TensorFlow Lite: Java API로 YOLO 추론
- AI 모델: YOLOv8n (.tflite, 640x640 입력)
- CameraX: 카메라 프레임 캡처
- Android TTS: 한국어 음성 안내

## 파이프라인 흐름

```
Kotlin(CameraX 프레임캡처) → Bitmap 생성
    → 픽셀 추출 → JNI로 C++ 호출
    → C++(OpenCV 전처리: letterbox 리사이즈, RGB변환, 정규화)
    → 전처리된 float[] 반환 → Kotlin
    → TFLite Interpreter로 YOLOv8n 추론
    → 후처리(NMS) → DetectionResult 리스트
    → DangerEvaluator로 위험도 판단
    → TTSManager로 "전방에 자동차" 음성 안내 + VibrationManager 진동
```

---

## 지시사항

아래 파일들을 **정확히 지정된 경로**에 생성해주세요.
기존에 파일이 있으면 덮어쓰세요.
폴더가 없으면 만들어주세요.

---

## 파일 1: app/build.gradle.kts

> 기존 app/build.gradle.kts를 이 내용으로 **덮어쓰기**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.safewalk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.safewalk"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
```

---

## 파일 2: app/src/main/AndroidManifest.xml

> 기존 AndroidManifest.xml **덮어쓰기**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="SafeWalk"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.NoActionBar"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:keepScreenOn="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 파일 3: app/src/main/res/layout/activity_main.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <androidx.camera.view.PreviewView
        android:id="@+id/previewView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.example.safewalk.ui.OverlayView
        android:id="@+id/overlayView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="SafeWalk 시작 중..."
        android:textColor="#FFFFFF"
        android:textSize="18sp"
        android:background="#80000000"
        android:padding="16dp"
        android:gravity="center"
        app:layout_constraintTop_toTopOf="parent"
        android:contentDescription="앱 상태 표시" />

    <TextView
        android:id="@+id/detectionText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="감지 대기 중"
        android:textColor="#FFFFFF"
        android:textSize="24sp"
        android:background="#80000000"
        android:padding="20dp"
        android:gravity="center"
        app:layout_constraintBottom_toBottomOf="parent"
        android:contentDescription="감지 결과 표시" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

---

## 파일 4: app/src/main/cpp/CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("safewalk")

set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../../libs/OpenCV-android-sdk/sdk/native/jni")
find_package(OpenCV REQUIRED)

message(STATUS "OpenCV found: ${OpenCV_LIBS}")

add_library(
    safewalk_native
    SHARED
    native_lib.cpp
    image_processor.cpp
)

target_include_directories(safewalk_native PRIVATE
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_SOURCE_DIR}
)

target_link_libraries(
    safewalk_native
    ${OpenCV_LIBS}
    log
    android
    jnigraphics
)
```

---

## 파일 5: app/src/main/cpp/image_processor.h

```cpp
#ifndef SAFEWALK_IMAGE_PROCESSOR_H
#define SAFEWALK_IMAGE_PROCESSOR_H

#include <opencv2/core.hpp>
#include <vector>

class ImageProcessor {
public:
    static void preprocess(
        const int* pixels,
        int width,
        int height,
        int targetSize,
        float* output
    );

    static void nms(
        const std::vector<cv::Rect2f>& boxes,
        const std::vector<float>& confidences,
        float confThreshold,
        float iouThreshold,
        std::vector<int>& indices
    );

private:
    static float calculateIoU(const cv::Rect2f& a, const cv::Rect2f& b);
};

#endif
```

---

## 파일 6: app/src/main/cpp/image_processor.cpp

```cpp
#include "image_processor.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>

void ImageProcessor::preprocess(
    const int* pixels,
    int width,
    int height,
    int targetSize,
    float* output
) {
    cv::Mat argbMat(height, width, CV_8UC4, (void*)pixels);

    cv::Mat rgbMat;
    cv::cvtColor(argbMat, rgbMat, cv::COLOR_BGRA2RGB);

    cv::Mat resized;
    float scale = std::min(
        (float)targetSize / width,
        (float)targetSize / height
    );
    int newW = (int)(width * scale);
    int newH = (int)(height * scale);

    cv::resize(rgbMat, resized, cv::Size(newW, newH), 0, 0, cv::INTER_LINEAR);

    cv::Mat padded(targetSize, targetSize, CV_8UC3, cv::Scalar(114, 114, 114));
    int dx = (targetSize - newW) / 2;
    int dy = (targetSize - newH) / 2;
    resized.copyTo(padded(cv::Rect(dx, dy, newW, newH)));

    int area = targetSize * targetSize;
    for (int y = 0; y < targetSize; y++) {
        for (int x = 0; x < targetSize; x++) {
            cv::Vec3b pixel = padded.at<cv::Vec3b>(y, x);
            int idx = y * targetSize + x;
            output[idx]            = pixel[0] / 255.0f;
            output[area + idx]     = pixel[1] / 255.0f;
            output[2 * area + idx] = pixel[2] / 255.0f;
        }
    }
}

void ImageProcessor::nms(
    const std::vector<cv::Rect2f>& boxes,
    const std::vector<float>& confidences,
    float confThreshold,
    float iouThreshold,
    std::vector<int>& indices
) {
    indices.clear();

    std::vector<int> sortedIdx(boxes.size());
    for (int i = 0; i < (int)sortedIdx.size(); i++) sortedIdx[i] = i;

    std::sort(sortedIdx.begin(), sortedIdx.end(),
        [&confidences](int a, int b) {
            return confidences[a] > confidences[b];
        });

    std::vector<bool> suppressed(boxes.size(), false);

    for (int i : sortedIdx) {
        if (suppressed[i]) continue;
        if (confidences[i] < confThreshold) continue;

        indices.push_back(i);

        for (int j : sortedIdx) {
            if (suppressed[j] || j == i) continue;
            if (calculateIoU(boxes[i], boxes[j]) > iouThreshold) {
                suppressed[j] = true;
            }
        }
    }
}

float ImageProcessor::calculateIoU(const cv::Rect2f& a, const cv::Rect2f& b) {
    float interLeft = std::max(a.x, b.x);
    float interTop = std::max(a.y, b.y);
    float interRight = std::min(a.x + a.width, b.x + b.width);
    float interBottom = std::min(a.y + a.height, b.y + b.height);

    float interArea = std::max(0.0f, interRight - interLeft) *
                      std::max(0.0f, interBottom - interTop);
    float aArea = a.width * a.height;
    float bArea = b.width * b.height;
    float unionArea = aArea + bArea - interArea;

    return (unionArea > 0.0f) ? interArea / unionArea : 0.0f;
}
```

---

## 파일 7: app/src/main/cpp/native_lib.cpp

```cpp
#include <jni.h>
#include <android/log.h>
#include "image_processor.h"

#define LOG_TAG "SafeWalk_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_example_safewalk_detector_NativeLib_preprocessImage(
    JNIEnv *env,
    jobject,
    jintArray pixels,
    jint width,
    jint height,
    jint targetSize
) {
    jint* pixelData = env->GetIntArrayElements(pixels, nullptr);
    if (pixelData == nullptr) {
        LOGE("픽셀 데이터 접근 실패");
        return nullptr;
    }

    int outputSize = 3 * targetSize * targetSize;
    float* outputData = new float[outputSize];

    ImageProcessor::preprocess(
        (const int*)pixelData,
        (int)width,
        (int)height,
        (int)targetSize,
        outputData
    );

    env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(outputSize);
    env->SetFloatArrayRegion(result, 0, outputSize, outputData);

    delete[] outputData;

    LOGD("전처리 완료: %dx%d -> %dx%d", (int)width, (int)height, (int)targetSize, (int)targetSize);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_safewalk_detector_NativeLib_postprocessDetections(
    JNIEnv *env,
    jobject,
    jfloatArray rawOutput,
    jint numDetections,
    jint numClasses,
    jfloat confThreshold,
    jfloat iouThreshold
) {
    LOGD("postprocessDetections called (placeholder)");
    return env->NewFloatArray(0);
}

JNIEXPORT jboolean JNICALL
Java_com_example_safewalk_detector_NativeLib_isOpenCVReady(
    JNIEnv *env,
    jobject
) {
    LOGD("OpenCV is ready");
    return JNI_TRUE;
}

}
```

---

## 파일 8: app/src/main/java/com/example/safewalk/MainActivity.kt

```kotlin
package com.example.safewalk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.safewalk.alert.TTSManager
import com.example.safewalk.alert.VibrationManager
import com.example.safewalk.camera.FrameAnalyzer
import com.example.safewalk.danger.DangerEvaluator
import com.example.safewalk.detector.DetectionResult
import com.example.safewalk.detector.YoloDetector
import com.example.safewalk.ui.OverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SafeWalk"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var detectionText: TextView

    private lateinit var yoloDetector: YoloDetector
    private lateinit var dangerEvaluator: DangerEvaluator
    private lateinit var ttsManager: TTSManager
    private lateinit var vibrationManager: VibrationManager

    private lateinit var cameraExecutor: ExecutorService
    private var isDetecting = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        detectionText = findViewById(R.id.detectionText)

        yoloDetector = YoloDetector(this)
        dangerEvaluator = DangerEvaluator()
        ttsManager = TTSManager(this)
        vibrationManager = VibrationManager(this)

        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView.setOnClickListener {
            isDetecting = !isDetecting
            val msg = if (isDetecting) "위험 감지를 시작합니다" else "위험 감지를 중지합니다"
            ttsManager.speak(msg)
            statusText.text = if (isDetecting) "감지 중" else "일시정지"
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val frameAnalyzer = FrameAnalyzer { bitmap ->
                if (!isDetecting) return@FrameAnalyzer

                val detections = yoloDetector.detect(bitmap)
                val dangerResult = dangerEvaluator.evaluate(detections)

                runOnUiThread {
                    overlayView.setDetections(detections)

                    if (detections.isNotEmpty()) {
                        detectionText.text = detections.joinToString("\n") {
                            "${it.label} (${(it.confidence * 100).toInt()}%)"
                        }
                    } else {
                        detectionText.text = "감지 대기 중"
                    }

                    if (dangerResult.shouldAlert) {
                        ttsManager.speak(dangerResult.alertMessage)
                        when (dangerResult.level) {
                            DangerEvaluator.DangerLevel.CRITICAL -> vibrationManager.vibrateUrgent()
                            DangerEvaluator.DangerLevel.HIGH -> vibrationManager.vibrateWarning()
                            else -> {}
                        }
                    }
                }
            }

            imageAnalysis.setAnalyzer(cameraExecutor, frameAnalyzer)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                statusText.text = "감지 중"
                ttsManager.speak("세이프워크가 시작되었습니다")
                Log.d(TAG, "카메라 바인딩 성공")
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패: ${e.message}")
                statusText.text = "카메라 오류"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ttsManager.speak("카메라 권한이 필요합니다")
                Toast.makeText(this, "카메라 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ttsManager.shutdown()
        yoloDetector.close()
    }
}
```

---

## 파일 9: app/src/main/java/com/example/safewalk/camera/FrameAnalyzer.kt

```kotlin
package com.example.safewalk.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class FrameAnalyzer(
    private val onFrame: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCount = 0
    private val analyzeEveryNFrames = 3

    override fun analyze(imageProxy: ImageProxy) {
        frameCount++

        if (frameCount % analyzeEveryNFrames != 0) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                onFrame(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bitmap = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
}
```

---

## 파일 10: app/src/main/java/com/example/safewalk/detector/NativeLib.kt

```kotlin
package com.example.safewalk.detector

object NativeLib {

    init {
        System.loadLibrary("safewalk_native")
    }

    external fun preprocessImage(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetSize: Int
    ): FloatArray

    external fun postprocessDetections(
        rawOutput: FloatArray,
        numDetections: Int,
        numClasses: Int,
        confThreshold: Float,
        iouThreshold: Float
    ): FloatArray

    external fun isOpenCVReady(): Boolean
}
```

---

## 파일 11: app/src/main/java/com/example/safewalk/detector/DetectionResult.kt

```kotlin
package com.example.safewalk.detector

import android.graphics.RectF

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val classId: Int
) {
    val areaRatio: Float
        get() = boundingBox.width() * boundingBox.height()

    val isInPath: Boolean
        get() = boundingBox.centerX() in 0.2f..0.8f
}
```

---

## 파일 12: app/src/main/java/com/example/safewalk/detector/YoloDetector.kt

```kotlin
package com.example.safewalk.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FILE = "yolov8n.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f
        private val TARGET_CLASSES = setOf(2)  // car (COCO class ID)
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false

    init {
        try {
            loadModel()
            loadLabels()
            isInitialized = true
            Log.d(TAG, "YoloDetector 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "YoloDetector 초기화 실패: ${e.message}")
        }
    }

    private fun loadModel() {
        val modelBuffer = loadModelFile(MODEL_FILE)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
        Log.d(TAG, "모델 로딩 완료: $MODEL_FILE")

        val inputTensor = interpreter!!.getInputTensor(0)
        val outputTensor = interpreter!!.getOutputTensor(0)
        Log.d(TAG, "입력 shape: ${inputTensor.shape().contentToString()}")
        Log.d(TAG, "출력 shape: ${outputTensor.shape().contentToString()}")
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadLabels() {
        val inputStream = context.assets.open(LABELS_FILE)
        labels = BufferedReader(InputStreamReader(inputStream)).readLines()
        Log.d(TAG, "라벨 로딩 완료: ${labels.size}개 클래스")
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (!isInitialized || interpreter == null) return emptyList()

        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val preprocessed: FloatArray = try {
                NativeLib.preprocessImage(pixels, width, height, INPUT_SIZE)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "C++ 전처리 실패, Kotlin fallback 사용: ${e.message}")
                preprocessFallback(bitmap)
            }

            val inputBuffer = floatArrayToByteBuffer(preprocessed)
            // YOLOv8 출력: [1, 84, 8400]
            val outputArray = Array(1) { Array(84) { FloatArray(8400) } }
            interpreter!!.run(inputBuffer, outputArray)

            val results = parseYoloV8Output(outputArray[0], width, height)
            return results.filter { it.classId in TARGET_CLASSES }

        } catch (e: Exception) {
            Log.e(TAG, "감지 오류: ${e.message}")
            return emptyList()
        }
    }

    private fun parseYoloV8Output(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val numDetections = output[0].size

        for (i in 0 until numDetections) {
            val cx = output[0][i] / INPUT_SIZE
            val cy = output[1][i] / INPUT_SIZE
            val w = output[2][i] / INPUT_SIZE
            val h = output[3][i] / INPUT_SIZE

            var maxConf = 0f
            var maxClassId = 0
            for (c in 4 until 84) {
                if (output[c][i] > maxConf) {
                    maxConf = output[c][i]
                    maxClassId = c - 4
                }
            }

            if (maxConf < CONFIDENCE_THRESHOLD) continue

            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            val label = if (maxClassId < labels.size) labels[maxClassId] else "unknown"

            results.add(
                DetectionResult(
                    label = label,
                    confidence = maxConf,
                    boundingBox = RectF(left, top, right, bottom),
                    classId = maxClassId
                )
            )
        }

        return nms(results)
    }

    private fun nms(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)

            sorted.removeAll { other ->
                iou(best.boundingBox, other.boundingBox) > IOU_THRESHOLD
                        && best.classId == other.classId
            }
        }

        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun preprocessFallback(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val floatArray = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f
            floatArray[INPUT_SIZE * INPUT_SIZE + i] = ((pixel shr 8) and 0xFF) / 255.0f
            floatArray[2 * INPUT_SIZE * INPUT_SIZE + i] = (pixel and 0xFF) / 255.0f
        }
        return floatArray
    }

    private fun floatArrayToByteBuffer(array: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(array.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(array)
        return buffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
```

---

## 파일 13: app/src/main/java/com/example/safewalk/danger/DangerEvaluator.kt

```kotlin
package com.example.safewalk.danger

import com.example.safewalk.detector.DetectionResult

class DangerEvaluator {

    enum class DangerLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    data class DangerResult(
        val level: DangerLevel,
        val shouldAlert: Boolean,
        val alertMessage: String
    )

    private var lastAlertTime = 0L
    private val alertCooldownMs = 2000L

    fun evaluate(detections: List<DetectionResult>): DangerResult {
        if (detections.isEmpty()) {
            return DangerResult(DangerLevel.NONE, false, "")
        }

        val mostDangerous = detections
            .sortedByDescending { it.areaRatio * (if (it.isInPath) 2f else 1f) }
            .first()

        val level = assessLevel(mostDangerous)
        val now = System.currentTimeMillis()

        val shouldAlert = level >= DangerLevel.MEDIUM
                && (now - lastAlertTime > alertCooldownMs)

        if (shouldAlert) {
            lastAlertTime = now
        }

        val message = buildAlertMessage(mostDangerous, level)

        return DangerResult(level, shouldAlert, message)
    }

    private fun assessLevel(detection: DetectionResult): DangerLevel {
        val area = detection.areaRatio
        val inPath = detection.isInPath

        return when {
            area > 0.25f && inPath -> DangerLevel.CRITICAL
            area > 0.15f && inPath -> DangerLevel.HIGH
            area > 0.15f           -> DangerLevel.MEDIUM
            area > 0.05f && inPath -> DangerLevel.MEDIUM
            area > 0.05f           -> DangerLevel.LOW
            else                   -> DangerLevel.NONE
        }
    }

    private fun buildAlertMessage(detection: DetectionResult, level: DangerLevel): String {
        val direction = when {
            detection.boundingBox.centerX() < 0.35f -> "왼쪽에"
            detection.boundingBox.centerX() > 0.65f -> "오른쪽에"
            else -> "전방에"
        }

        return when (level) {
            DangerLevel.CRITICAL -> "위험! $direction 자동차가 매우 가깝습니다"
            DangerLevel.HIGH -> "주의! $direction 자동차가 접근 중입니다"
            DangerLevel.MEDIUM -> "$direction 자동차가 감지되었습니다"
            DangerLevel.LOW -> "$direction 멀리 자동차가 있습니다"
            DangerLevel.NONE -> ""
        }
    }
}
```

---

## 파일 14: app/src/main/java/com/example/safewalk/alert/TTSManager.kt

```kotlin
package com.example.safewalk.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) {

    companion object {
        private const val TAG = "TTSManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "한국어 TTS를 지원하지 않습니다")
                } else {
                    isReady = true
                    tts?.setSpeechRate(1.2f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, "TTS 초기화 성공")
                }
            } else {
                Log.e(TAG, "TTS 초기화 실패")
            }
        }
    }

    fun speak(message: String) {
        if (!isReady || message.isBlank()) return

        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "safewalk_${System.currentTimeMillis()}"
        )
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
```

---

## 파일 15: app/src/main/java/com/example/safewalk/alert/VibrationManager.kt

```kotlin
package com.example.safewalk.alert

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun vibrateUrgent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 300, 100, 300, 100, 300)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 100, 300, 100, 300), -1)
        }
    }

    fun vibrateWarning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }
}
```

---

## 파일 16: app/src/main/java/com/example/safewalk/ui/OverlayView.kt

```kotlin
package com.example.safewalk.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.safewalk.detector.DetectionResult

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<DetectionResult> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isFakeBoldText = true
        isAntiAlias = true
    }

    fun setDetections(results: List<DetectionResult>) {
        detections = results
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        for (detection in detections) {
            val box = detection.boundingBox

            val left = box.left * w
            val top = box.top * h
            val right = box.right * w
            val bottom = box.bottom * h

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val labelText = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val textWidth = labelPaint.measureText(labelText)
            val textHeight = 48f

            canvas.drawRect(
                left, top - textHeight - 8f,
                left + textWidth + 16f, top,
                labelBgPaint
            )

            canvas.drawText(labelText, left + 8f, top - 8f, labelPaint)
        }
    }
}
```

---

## 파일 17: app/src/main/assets/labels.txt

```
person
bicycle
car
motorcycle
airplane
bus
train
truck
boat
traffic light
fire hydrant
stop sign
parking meter
bench
bird
cat
dog
horse
sheep
cow
elephant
bear
zebra
giraffe
backpack
umbrella
handbag
tie
suitcase
frisbee
skis
snowboard
sports ball
kite
baseball bat
baseball glove
skateboard
surfboard
tennis racket
bottle
wine glass
cup
fork
knife
spoon
bowl
banana
apple
sandwich
orange
broccoli
carrot
hot dog
pizza
donut
cake
chair
couch
potted plant
bed
dining table
toilet
tv
laptop
mouse
remote
keyboard
cell phone
microwave
oven
toaster
sink
refrigerator
book
clock
vase
scissors
teddy bear
hair drier
toothbrush
```

---

## 셋업 완료 후 확인사항

모든 파일을 생성한 후:

1. `app/src/main/assets/` 폴더에 `yolov8n.tflite` 모델 파일이 필요합니다. 아직 없다면 나중에 추가하세요.
2. `app/libs/OpenCV-android-sdk/` 가 존재하는지 확인하세요.
3. Gradle Sync를 실행하세요.
4. 빌드 에러가 있으면 CMakeLists.txt의 OpenCV_DIR 경로를 확인하세요.
