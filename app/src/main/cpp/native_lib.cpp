#include <jni.h>
#include <android/log.h>
#include "image_processor.h"

#define LOG_TAG "SafeWalk_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_example_applicationtest_detector_NativeLib_preprocessImage(
    JNIEnv *env,
    jobject,
    jintArray pixels,
    jint width,
    jint height,
    jint targetSize
) {
    jint* pixelData = env->GetIntArrayElements(pixels, nullptr);
    if (pixelData == nullptr) {
        LOGE("픽셀 데이터 접근 실패");
        return nullptr;
    }

    int outputSize = 3 * targetSize * targetSize;
    float* outputData = new float[outputSize];

    ImageProcessor::preprocess(
        (const int*)pixelData,
        (int)width,
        (int)height,
        (int)targetSize,
        outputData
    );

    env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(outputSize);
    env->SetFloatArrayRegion(result, 0, outputSize, outputData);

    delete[] outputData;

    LOGD("전처리 완료: %dx%d -> %dx%d", (int)width, (int)height, (int)targetSize, (int)targetSize);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_applicationtest_detector_NativeLib_postprocessDetections(
    JNIEnv *env,
    jobject,
    jfloatArray rawOutput,
    jint numDetections,
    jint numClasses,
    jfloat confThreshold,
    jfloat iouThreshold
) {
    LOGD("postprocessDetections called (placeholder)");
    return env->NewFloatArray(0);
}

JNIEXPORT jboolean JNICALL
Java_com_example_applicationtest_detector_NativeLib_isOpenCVReady(
    JNIEnv *env,
    jobject
) {
    LOGD("OpenCV is ready");
    return JNI_TRUE;
}

}