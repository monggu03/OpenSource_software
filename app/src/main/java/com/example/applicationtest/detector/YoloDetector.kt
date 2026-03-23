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

            // flat buffer로 출력 받기 (shape 무관하게 안전)
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val totalElements = outputShape.fold(1) { acc, v -> acc * v }
            val outputBuffer = ByteBuffer.allocateDirect(totalElements * 4)
                .order(ByteOrder.nativeOrder())
            interpreter!!.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val flatOutput = FloatArray(totalElements)
            outputBuffer.asFloatBuffer().get(flatOutput)

            Log.d(TAG, "출력 shape: ${outputShape.contentToString()}, total=$totalElements")

            // 디버그: 가장 높은 confidence를 가진 detection의 raw 값 표시
            val dim1 = outputShape[1]  // 84
            val dim2 = outputShape[2]  // 8400
            val numDet = dim2  // 8400 detections

            // ColMajor [1,84,8400]: conf for det i = max(data[(4..83)*8400+i])
            var bestIdx = 0
            var bestConf = 0f
            for (i in 0 until numDet) {
                for (c in 4 until 84) {
                    val v = flatOutput[c * numDet + i]
                    if (v > bestConf) {
                        bestConf = v
                        bestIdx = i
                    }
                }
            }
            val colCx = flatOutput[0 * numDet + bestIdx]
            val colCy = flatOutput[1 * numDet + bestIdx]
            val colW = flatOutput[2 * numDet + bestIdx]
            val colH = flatOutput[3 * numDet + bestIdx]

            // RowMajor [1,8400,84]: same detection at row bestIdx
            val rowBase = bestIdx * 84
            val rowCx = flatOutput[rowBase + 0]
            val rowCy = flatOutput[rowBase + 1]
            val rowW = flatOutput[rowBase + 2]
            val rowH = flatOutput[rowBase + 3]

            debugInfo = "shape[1,$dim1,$dim2] best#$bestIdx conf=${String.format("%.2f", bestConf)}\n" +
                "Col: ${String.format("%.1f,%.1f,%.1f,%.1f", colCx, colCy, colW, colH)}\n" +
                "Row: ${String.format("%.1f,%.1f,%.1f,%.1f", rowCx, rowCy, rowW, rowH)}"

            val results = parseFlat(flatOutput, outputShape)
            return results.filter { it.classId in TARGET_CLASSES }

        } catch (e: Exception) {
            Log.e(TAG, "감지 오류: ${e.message}", e)
            return emptyList()
        }
    }

    private fun parseFlat(
        data: FloatArray,
        shape: IntArray
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

        Log.d(TAG, "파싱: isRowPerDetection=$isRowPerDetection, numDetections=$numDetections")

        // 첫 번째 detection의 raw 값 로그
        if (isRowPerDetection) {
            Log.d(TAG, "det[0] raw: cx=${data[0]}, cy=${data[1]}, w=${data[2]}, h=${data[3]}, classes=${data.slice(4..9)}")
        } else {
            Log.d(TAG, "det[0] raw: cx=${data[0]}, cy=${data[dim2]}, w=${data[2*dim2]}, h=${data[3*dim2]}")
        }

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

            // 좌표가 픽셀이면 정규화, 이미 0~1이면 그대로
            val scale = if (maxOf(cx, cy, w, h) > 1.0f) INPUT_SIZE.toFloat() else 1.0f
            val ncx = cx / scale
            val ncy = cy / scale
            val nw = w / scale
            val nh = h / scale

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