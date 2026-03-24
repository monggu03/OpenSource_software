package com.example.applicationtest.danger
import com.example.applicationtest.detector.DetectionResult

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
            .maxBy { it.areaRatio * (if (it.isInPath) 2f else 1f) }

        val level = assessLevel(mostDangerous)
        val now = System.currentTimeMillis()

        val shouldAlert = level >= DangerLevel.LOW
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

        return when (detection.classId) {
            2 -> when {  // car
                area > 0.15f && inPath -> DangerLevel.CRITICAL
                area > 0.08f && inPath -> DangerLevel.HIGH
                area > 0.08f           -> DangerLevel.MEDIUM
                area > 0.02f && inPath -> DangerLevel.MEDIUM
                area > 0.01f           -> DangerLevel.LOW
                else                   -> DangerLevel.NONE
            }
            39 -> when {  // bottle
                area > 0.04f && inPath -> DangerLevel.MEDIUM
                area > 0.01f && inPath -> DangerLevel.LOW
                area > 0.01f           -> DangerLevel.LOW
                else                   -> DangerLevel.NONE
            }
            67 -> when {  // cell phone
                area > 0.04f && inPath -> DangerLevel.MEDIUM
                area > 0.01f && inPath -> DangerLevel.LOW
                area > 0.01f           -> DangerLevel.LOW
                else                   -> DangerLevel.NONE
            }
            else -> DangerLevel.NONE
        }
    }

    private fun getObjectName(classId: Int): String {
        return when (classId) {
            2 -> "자동차"
            39 -> "병"
            67 -> "휴대폰"
            else -> "물체"
        }
    }

    private fun buildAlertMessage(detection: DetectionResult, level: DangerLevel): String {
        val direction = when {
            detection.boundingBox.centerX() < 0.35f -> "왼쪽에"
            detection.boundingBox.centerX() > 0.65f -> "오른쪽에"
            else -> "전방에"
        }
        val name = getObjectName(detection.classId)

        return when (level) {
            DangerLevel.CRITICAL -> "위험! $direction ${name}가 매우 가깝습니다"
            DangerLevel.HIGH -> "주의! $direction ${name}가 접근 중입니다"
            DangerLevel.MEDIUM -> "$direction ${name}가 감지되었습니다"
            DangerLevel.LOW -> "$direction 멀리 ${name}가 있습니다"
            DangerLevel.NONE -> ""
        }
    }
}