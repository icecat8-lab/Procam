#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>

extern "C" JNIEXPORT void JNICALL
Java_com_procamera_ImageProcessor_processRawPro(
    JNIEnv* env, jobject,
    jbyteArray raw_data, jint width, jint height,
    jfloat blackLevel, jfloat rGain, jfloat gGain, jfloat bGain,
    jfloatArray ccm_array) {

    jbyte* pData = env->GetByteArrayElements(raw_data, nullptr);
    jfloat* ccm = env->GetFloatArrayElements(ccm_array, nullptr);
    
    if (pData == nullptr || ccm == nullptr) {
        if (pData) env->ReleaseByteArrayElements(raw_data, pData, JNI_ABORT);
        if (ccm) env->ReleaseFloatArrayElements(ccm_array, ccm, JNI_ABORT);
        return;
    }

    uint16_t* rawPixels = reinterpret_cast<uint16_t*>(pData);
    const float whiteLevel = 1023.0f;

    #pragma omp parallel for
    for (int y = 0; y < height - 1; y += 2) {
        for (int x = 0; x < width - 1; x += 2) {
            
            int idxR  = y * width + x;
            int idxGr = y * width + (x + 1);
            int idxGb = (y + 1) * width + x;
            int idxB  = (y + 1) * width + (x + 1);

            float r_lin  = std::max(0.0f, static_cast<float>(rawPixels[idxR]) - blackLevel);
            float gr_lin = std::max(0.0f, static_cast<float>(rawPixels[idxGr]) - blackLevel);
            float gb_lin = std::max(0.0f, static_cast<float>(rawPixels[idxGb]) - blackLevel);
            float b_lin  = std::max(0.0f, static_cast<float>(rawPixels[idxB]) - blackLevel);

            r_lin  *= rGain;
            gr_lin *= gGain;
            gb_lin *= gGain;
            b_lin  *= bGain;

            float g_avg = (gr_lin + gb_lin) / 2.0f;

            float r_norm = std::min(r_lin / whiteLevel, 1.0f);
            float g_norm = std::min(g_avg / whiteLevel, 1.0f);
            float b_norm = std::min(b_lin / whiteLevel, 1.0f);

            float r_ccm = std::max(0.0f, std::min(1.0f, r_norm * ccm[0] + g_norm * ccm[1] + b_norm * ccm[2]));
            float g_ccm = std::max(0.0f, std::min(1.0f, r_norm * ccm[3] + g_norm * ccm[4] + b_norm * ccm[5]));
            float b_ccm = std::max(0.0f, std::min(1.0f, r_norm * ccm[6] + g_norm * ccm[7] + b_norm * ccm[8]));

            float r_gamma = std::pow(r_ccm, 1.0f / 2.2f);
            float g_gamma = std::pow(g_ccm, 1.0f / 2.2f);
            float b_gamma = std::pow(b_ccm, 1.0f / 2.2f);

            uint8_t final_r = static_cast<uint8_t>(r_gamma * 255.0f);
            uint8_t final_g = static_cast<uint8_t>(g_gamma * 255.0f);
            uint8_t final_b = static_cast<uint8_t>(b_gamma * 255.0f);

        }
    }

    env->ReleaseFloatArrayElements(ccm_array, ccm, JNI_ABORT);
    env->ReleaseByteArrayElements(raw_data, pData, JNI_ABORT);
}
