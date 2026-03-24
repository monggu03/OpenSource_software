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
    private var sourceAspect = 0f  // 원본 이미지 width/height 비율

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

    fun setDetections(results: List<DetectionResult>, imgWidth: Int = 0, imgHeight: Int = 0) {
        detections = results
        if (imgWidth > 0 && imgHeight > 0) {
            sourceAspect = imgWidth.toFloat() / imgHeight
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // PreviewView FILL_CENTER와 동일한 좌표 변환 계산
        val drawW: Float
        val drawH: Float
        val offsetX: Float
        val offsetY: Float

        if (sourceAspect > 0f) {
            val viewAspect = viewW / viewH
            if (sourceAspect > viewAspect) {
                // 원본이 더 넓음: 높이 맞추고 좌우 크롭
                drawH = viewH
                drawW = viewH * sourceAspect
            } else {
                // 원본이 더 좁음: 너비 맞추고 상하 크롭
                drawW = viewW
                drawH = viewW / sourceAspect
            }
            offsetX = (drawW - viewW) / 2f
            offsetY = (drawH - viewH) / 2f
        } else {
            drawW = viewW
            drawH = viewH
            offsetX = 0f
            offsetY = 0f
        }

        for (detection in detections) {
            val box = detection.boundingBox

            val left = box.left * drawW - offsetX
            val top = box.top * drawH - offsetY
            val right = box.right * drawW - offsetX
            val bottom = box.bottom * drawH - offsetY

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