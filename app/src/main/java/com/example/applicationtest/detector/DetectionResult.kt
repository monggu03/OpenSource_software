package com.example.applicationtest.detector

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