package com.example.applicationtest.detector

import android.graphics.RectF

class ObjectTracker {

    companion object {
        private const val IOU_MATCH_THRESHOLD = 0.3f
        private const val SMOOTHING = 0.4f  // 낮을수록 부드러움 (0.0~1.0, 1.0 = 스무딩 없음)
        private const val MAX_LOST_FRAMES = 3  // 이 프레임 동안 매칭 안 되면 트랙 제거
    }

    private val tracks = mutableListOf<Track>()
    private var nextId = 0

    data class Track(
        val id: Int,
        var detection: DetectionResult,
        var smoothedBox: RectF,
        var lostFrames: Int = 0
    )

    fun update(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) {
            // 감지 없으면 lost 카운트 증가, 초과 시 제거
            tracks.forEach { it.lostFrames++ }
            tracks.removeAll { it.lostFrames > MAX_LOST_FRAMES }
            // 아직 살아있는 트랙은 마지막 위치 유지
            return tracks.map { it.detection.copy(boundingBox = RectF(it.smoothedBox)) }
        }

        val unmatched = detections.toMutableList()
        val matchedTracks = mutableSetOf<Track>()

        // 기존 트랙과 새 감지를 IoU로 매칭
        for (track in tracks) {
            var bestIou = 0f
            var bestDet: DetectionResult? = null

            for (det in unmatched) {
                if (det.classId != track.detection.classId) continue
                val iou = calculateIou(track.smoothedBox, det.boundingBox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestDet = det
                }
            }

            if (bestIou >= IOU_MATCH_THRESHOLD && bestDet != null) {
                // 매칭 성공: EMA 스무딩 적용
                track.smoothedBox = smoothBox(track.smoothedBox, bestDet.boundingBox)
                track.detection = bestDet
                track.lostFrames = 0
                matchedTracks.add(track)
                unmatched.remove(bestDet)
            }
        }

        // 매칭 안 된 트랙: lost 증가
        for (track in tracks) {
            if (track !in matchedTracks) {
                track.lostFrames++
            }
        }
        tracks.removeAll { it.lostFrames > MAX_LOST_FRAMES }

        // 매칭 안 된 새 감지: 새 트랙 생성
        for (det in unmatched) {
            tracks.add(Track(
                id = nextId++,
                detection = det,
                smoothedBox = RectF(det.boundingBox)
            ))
        }

        // 스무딩된 박스로 결과 반환
        return tracks.filter { it.lostFrames == 0 }.map {
            it.detection.copy(boundingBox = RectF(it.smoothedBox))
        }
    }

    private fun smoothBox(prev: RectF, curr: RectF): RectF {
        return RectF(
            lerp(prev.left, curr.left, SMOOTHING),
            lerp(prev.top, curr.top, SMOOTHING),
            lerp(prev.right, curr.right, SMOOTHING),
            lerp(prev.bottom, curr.bottom, SMOOTHING)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun calculateIou(a: RectF, b: RectF): Float {
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

    fun clear() {
        tracks.clear()
    }
}
