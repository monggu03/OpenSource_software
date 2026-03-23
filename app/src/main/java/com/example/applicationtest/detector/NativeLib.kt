package com.example.applicationtest.detector

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