package com.example.applicationtest

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
import com.example.applicationtest.alert.TTSManager
import com.example.applicationtest.alert.VibrationManager
import com.example.applicationtest.camera.FrameAnalyzer
import com.example.applicationtest.danger.DangerEvaluator
import com.example.applicationtest.detector.DetectionResult
import com.example.applicationtest.detector.YoloDetector
import com.example.applicationtest.ui.OverlayView
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

        // 2초 후 TTS 초기화 상태 표시
        previewView.postDelayed({
            statusText.text = ttsManager.initStatus
            Log.d(TAG, "TTS 상태: ${ttsManager.initStatus}")
        }, 2000)

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
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

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
                        val debugInfo = detections.joinToString("\n") { d ->
                            val b = d.boundingBox
                            "${d.label} ${(d.confidence * 100).toInt()}% " +
                            "box(${String.format("%.2f", b.left)},${String.format("%.2f", b.top)}," +
                            "${String.format("%.2f", b.right)},${String.format("%.2f", b.bottom)}) " +
                            "area=${String.format("%.3f", d.areaRatio)}"
                        }
                        detectionText.text = debugInfo
                        statusText.text = yoloDetector.debugInfo
                        Log.d(TAG, "감지: $debugInfo")
                        Log.d(TAG, "위험도: ${dangerResult.level}, shouldAlert: ${dangerResult.shouldAlert}")
                    } else {
                        detectionText.text = "감지 대기 중"
                    }

                    if (dangerResult.shouldAlert) {
                        Log.d(TAG, "알림 발동: ${dangerResult.alertMessage}")
                        ttsManager.speak(dangerResult.alertMessage)
                        when (dangerResult.level) {
                            DangerEvaluator.DangerLevel.CRITICAL -> vibrationManager.vibrateUrgent()
                            DangerEvaluator.DangerLevel.HIGH -> vibrationManager.vibrateWarning()
                            DangerEvaluator.DangerLevel.MEDIUM -> vibrationManager.vibrateWarning()
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