package com.example.applicationtest.detector

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
        private val TARGET_CLASSES = setOf(2, 39, 67)  // car, bottle, cell phone
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false
    private var inputIsNHWC = true
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var outputShape: IntArray = intArrayOf()
    private var totalOutputElements = 0
    var debugInfo: String = ""
        private set

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
            @Suppress("DEPRECATION")
            setUseNNAPI(true)  // 하드웨어 가속 (GPU/DSP/NPU)
        }

        interpreter = Interpreter(modelBuffer, options)
        Log.d(TAG, "모델 로딩 완료: $MODEL_FILE")

        val inputTensor = interpreter!!.getInputTensor(0)
        val outputTensor = interpreter!!.getOutputTensor(0)
        val inputShape = inputTensor.shape()
        inputIsNHWC = inputShape.size == 4 && inputShape[3] == 3

        // ByteBuffer 사전할당 (매 프레임 GC 방지)
        val inputSize = inputShape.fold(1) { acc, v -> acc * v }
        inputBuffer = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())

        outputShape = outputTensor.shape()
        totalOutputElements = outputShape.fold(1) { acc, v -> acc * v }
        outputBuffer = ByteBuffer.allocateDirect(totalOutputElements * 4).order(ByteOrder.nativeOrder())

        Log.d(TAG, "입력 shape: ${inputShape.contentToString()}, 포맷: ${if (inputIsNHWC) "NHWC" else "NCHW"}")
        Log.d(TAG, "출력 shape: ${outputShape.contentToString()}")
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        return fileDescriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun loadLabels() {
        labels = context.assets.open(LABELS_FILE).use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
        }
        Log.d(TAG, "라벨 로딩 완료: ${labels.size}개 클래스")
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (!isInitialized || interpreter == null) return emptyList()

        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val letterboxScale = minOf(INPUT_SIZE.toFloat() / width, INPUT_SIZE.toFloat() / height)
            val scaledW = (width * letterboxScale).toInt()
            val scaledH = (height * letterboxScale).toInt()
            val padX = (INPUT_SIZE - scaledW) / 2f
            val padY = (INPUT_SIZE - scaledH) / 2f

            val preprocessed: FloatArray = try {
                val chwData = NativeLib.preprocessImage(pixels, width, height, INPUT_SIZE)
                if (inputIsNHWC) chwToHwc(chwData, INPUT_SIZE) else chwData
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "C++ 전처리 실패, Kotlin fallback 사용: ${e.message}")
                preprocessFallback(bitmap)
            }

            // 사전할당 버퍼 재사용 (매 프레임 GC 방지)
            val inBuf = inputBuffer!!
            inBuf.rewind()
            inBuf.asFloatBuffer().put(preprocessed)

            val outBuf = outputBuffer!!
            outBuf.rewind()
            interpreter!!.run(inBuf, outBuf)
            outBuf.rewind()
            val flatOutput = FloatArray(totalOutputElements)
            outBuf.asFloatBuffer().get(flatOutput)

            val results = parseFlat(flatOutput, outputShape, padX, padY, scaledW.toFloat(), scaledH.toFloat())
            val filtered = results.filter { it.classId in TARGET_CLASSES }

            if (filtered.isNotEmpty()) {
                val best = filtered.maxBy { it.confidence }
                debugInfo = "감지 ${filtered.size}개, best: ${best.label} ${(best.confidence * 100).toInt()}%"
            } else {
                debugInfo = ""
            }

            return filtered

        } catch (e: Exception) {
            Log.e(TAG, "감지 오류: ${e.message}", e)
            return emptyList()
        }
    }

    private fun parseFlat(
        data: FloatArray,
        shape: IntArray,
        padX: Float,
        padY: Float,
        scaledW: Float,
        scaledH: Float
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        // shape: [1, 84, 8400] 또는 [1, 8400, 84]
        val dim1 = shape[1]
        val dim2 = shape[2]
        val numClasses = 80
        val numAttrs = 4 + numClasses  // 84

        // dim1=84, dim2=8400 → [1,84,8400]: 각 열이 하나의 detection
        // dim1=8400, dim2=84 → [1,8400,84]: 각 행이 하나의 detection
        val isRowPerDetection = dim2 == numAttrs  // [1, 8400, 84]
        val numDetections = if (isRowPerDetection) dim1 else dim2

        for (i in 0 until numDetections) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            var maxConf = 0f
            var maxClassId = 0

            if (isRowPerDetection) {
                // [1, 8400, 84]: row i, columns 0..83
                val base = i * numAttrs
                cx = data[base + 0]
                cy = data[base + 1]
                w = data[base + 2]
                h = data[base + 3]
                for (c in 4 until numAttrs) {
                    if (data[base + c] > maxConf) {
                        maxConf = data[base + c]
                        maxClassId = c - 4
                    }
                }
            } else {
                // [1, 84, 8400]: row r = attribute, column i = detection
                cx = data[0 * numDetections + i]
                cy = data[1 * numDetections + i]
                w = data[2 * numDetections + i]
                h = data[3 * numDetections + i]
                for (c in 4 until numAttrs) {
                    val v = data[c * numDetections + i]
                    if (v > maxConf) {
                        maxConf = v
                        maxClassId = c - 4
                    }
                }
            }

            if (maxConf < CONFIDENCE_THRESHOLD) continue

            // letterbox 좌표 → 원본 이미지 좌표 (0~1) 역변환
            val ncx: Float
            val ncy: Float
            val nw: Float
            val nh: Float
            if (maxOf(cx, cy, w, h) > 1.0f) {
                // 픽셀 좌표: letterbox 패딩 제거 후 원본 비율로 정규화
                ncx = (cx - padX) / scaledW
                ncy = (cy - padY) / scaledH
                nw = w / scaledW
                nh = h / scaledH
            } else {
                // 이미 0~1: letterbox 640 기준으로 패딩 제거
                ncx = (cx * INPUT_SIZE - padX) / scaledW
                ncy = (cy * INPUT_SIZE - padY) / scaledH
                nw = (w * INPUT_SIZE) / scaledW
                nh = (h * INPUT_SIZE) / scaledH
            }

            val left = (ncx - nw / 2f).coerceIn(0f, 1f)
            val top = (ncy - nh / 2f).coerceIn(0f, 1f)
            val right = (ncx + nw / 2f).coerceIn(0f, 1f)
            val bottom = (ncy + nh / 2f).coerceIn(0f, 1f)

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
        val w = bitmap.width
        val h = bitmap.height
        val scale = minOf(INPUT_SIZE.toFloat() / w, INPUT_SIZE.toFloat() / h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        // letterbox: 114/255 gray padding (matches C++ preprocess)
        val padded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val dx = (INPUT_SIZE - newW) / 2f
        val dy = (INPUT_SIZE - newH) / 2f
        canvas.drawBitmap(resized, dx, dy, null)
        resized.recycle()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        padded.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        padded.recycle()

        val area = INPUT_SIZE * INPUT_SIZE
        val floatArray = FloatArray(3 * area)

        if (inputIsNHWC) {
            // HWC: [R,G,B, R,G,B, ...] - TFLite 기본 포맷
            for (i in pixels.indices) {
                val pixel = pixels[i]
                floatArray[i * 3 + 0] = ((pixel shr 16) and 0xFF) / 255.0f
                floatArray[i * 3 + 1] = ((pixel shr 8) and 0xFF) / 255.0f
                floatArray[i * 3 + 2] = (pixel and 0xFF) / 255.0f
            }
        } else {
            // CHW: [R...R, G...G, B...B] - ONNX/PyTorch 포맷
            for (i in pixels.indices) {
                val pixel = pixels[i]
                floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f
                floatArray[area + i] = ((pixel shr 8) and 0xFF) / 255.0f
                floatArray[2 * area + i] = (pixel and 0xFF) / 255.0f
            }
        }
        return floatArray
    }

    private fun chwToHwc(chw: FloatArray, size: Int): FloatArray {
        val area = size * size
        val hwc = FloatArray(3 * area)
        for (i in 0 until area) {
            hwc[i * 3 + 0] = chw[i]              // R
            hwc[i * 3 + 1] = chw[area + i]        // G
            hwc[i * 3 + 2] = chw[2 * area + i]    // B
        }
        return hwc
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}