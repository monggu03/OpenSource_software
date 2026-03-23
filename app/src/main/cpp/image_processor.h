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