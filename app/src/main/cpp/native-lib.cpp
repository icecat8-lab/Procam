#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>

extern "C" JNIEXPORT void JNICALL
Java_com_procamera_ImageProcessor_processRawPro(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray rawData,
        jbyteArray outputData,
        jint width,
        jint height,
        jint rowStride,
        jfloat blackLevel,
        jfloat rGain,
        jfloat gGain,
        jfloat bGain,
        jfloatArray ccmArray) {

    jbyte *rawBytes = env->GetByteArrayElements(rawData, nullptr);
    jbyte *outputBytes = env->GetByteArrayElements(outputData, nullptr);
    jfloat *ccm = env->GetFloatArrayElements(ccmArray, nullptr);

    uint16_t *raw16 = reinterpret_cast<uint16_t *>(rawBytes);
    uint32_t *outPixels = reinterpret_cast<uint32_t *>(outputBytes);

    int stridePixels = rowStride / 2;

    for (int y = 0; y < height - 1; y += 2) {
        for (int x = 0; x < width - 1; x += 2) {
            int idx00 = y * stridePixels + x;
            int idx01 = y * stridePixels + (x + 1);
            int idx10 = (y + 1) * stridePixels + x;
            int idx11 = (y + 1) * stridePixels + (x + 1);

            float p00 = std::max(0.0f, (float)(raw16[idx00] - blackLevel));
            float p01 = std::max(0.0f, (float)(raw16[idx01] - blackLevel));
            float p10 = std::max(0.0f, (float)(raw16[idx10] - blackLevel));
            float p11 = std::max(0.0f, (float)(raw16[idx11] - blackLevel));

            float r = p11 * rGain;
            float g = ((p01 + p10) / 2.0f) * gGain;
            float b = p00 * bGain;

            r = std::min(255.0f, std::sqrt(r / 4.0f) * 16.0f);
            g = std::min(255.0f, std::sqrt(g / 4.0f) * 16.0f);
            b = std::min(255.0f, std::sqrt(b / 4.0f) * 16.0f);

            uint8_t ir = static_cast<uint8_t>(r);
            uint8_t ig = static_cast<uint8_t>(g);
            uint8_t ib = static_cast<uint8_t>(b);

            int outIdx00 = y * width + x;
            int outIdx01 = y * width + (x + 1);
            int outIdx10 = (y + 1) * width + x;
            int outIdx11 = (y + 1) * width + (x + 1);

            uint32_t color = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
            outPixels[outIdx00] = color;
            outPixels[outIdx01] = color;
            outPixels[outIdx10] = color;
            outPixels[outIdx11] = color;
        }
    }

    env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
    env->ReleaseByteArrayElements(rawData, rawBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(outputData, outputBytes, 0);
}
