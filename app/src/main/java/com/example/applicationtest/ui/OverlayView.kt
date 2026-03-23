package com.example.applicationtest.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.applicationtest.detector.DetectionResult

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