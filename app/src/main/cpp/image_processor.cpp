#include "image_processor.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>

void ImageProcessor::preprocess(
    const int* pixels,
    int width,
    int height,
    int targetSize,
    float* output
) {
    cv::Mat argbMat(height, width, CV_8UC4, (void*)pixels);

    cv::Mat rgbMat;
    cv::cvtColor(argbMat, rgbMat, cv::COLOR_BGRA2RGB);

    cv::Mat resized;
    float scale = std::min(
        (float)targetSize / width,
        (float)targetSize / height
    );
    int newW = (int)(width * scale);
    int newH = (int)(height * scale);

    cv::resize(rgbMat, resized, cv::Size(newW, newH), 0, 0, cv::INTER_LINEAR);

    cv::Mat padded(targetSize, targetSize, CV_8UC3, cv::Scalar(114, 114, 114));
    int dx = (targetSize - newW) / 2;
    int dy = (targetSize - newH) / 2;
    resized.copyTo(padded(cv::Rect(dx, dy, newW, newH)));

    int area = targetSize * targetSize;
    for (int y = 0; y < targetSize; y++) {
        for (int x = 0; x < targetSize; x++) {
            cv::Vec3b pixel = padded.at<cv::Vec3b>(y, x);
            int idx = y * targetSize + x;
            output[idx]            = pixel[0] / 255.0f;
            output[area + idx]     = pixel[1] / 255.0f;
            output[2 * area + idx] = pixel[2] / 255.0f;
        }
    }
}

void ImageProcessor::nms(
    const std::vector<cv::Rect2f>& boxes,
    const std::vector<float>& confidences,
    float confThreshold,
    float iouThreshold,
    std::vector<int>& indices
) {
    indices.clear();

    std::vector<int> sortedIdx(boxes.size());
    for (int i = 0; i < (int)sortedIdx.size(); i++) sortedIdx[i] = i;

    std::sort(sortedIdx.begin(), sortedIdx.end(),
        [&confidences](int a, int b) {
            return confidences[a] > confidences[b];
        });

    std::vector<bool> suppressed(boxes.size(), false);

    for (int i : sortedIdx) {
        if (suppressed[i]) continue;
        if (confidences[i] < confThreshold) continue;

        indices.push_back(i);

        for (int j : sortedIdx) {
            if (suppressed[j] || j == i) continue;
            if (calculateIoU(boxes[i], boxes[j]) > iouThreshold) {
                suppressed[j] = true;
            }
        }
    }
}

float ImageProcessor::calculateIoU(const cv::Rect2f& a, const cv::Rect2f& b) {
    float interLeft = std::max(a.x, b.x);
    float interTop = std::max(a.y, b.y);
    float interRight = std::min(a.x + a.width, b.x + b.width);
    float interBottom = std::min(a.y + a.height, b.y + b.height);

    float interArea = std::max(0.0f, interRight - interLeft) *
                      std::max(0.0f, interBottom - interTop);
    float aArea = a.width * a.height;
    float bArea = b.width * b.height;
    float unionArea = aArea + bArea - interArea;

    return (unionArea > 0.0f) ? interArea / unionArea : 0.0f;
}