#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>

extern "C" JNIEXPORT void JNICALL
Java_com_procamera_ImageProcessor_processRawPro(
    JNIEnv* env, jobject,
    jbyteArray raw_data, jint width, jint height, jint rowStride,
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
    int stride16 = rowStride / 2;

    #pragma omp parallel for
    for (int y = 0; y < height - 1; y += 2) {
        for (int x = 0; x < width - 1; x += 2) {
            
            int idxR  = y * stride16 + x;
            int idxGr = y * stride16 + (x + 1);
            int idxGb = (y + 1) * stride16 + x;
            int idxB  = (y + 1) * stride16 + (x + 1);

            float r_lin  = std::max(0.0f, static_cast<float>(rawPixels[idxR]) - blackLevel);
            float gr_lin = std::max(0.0f, static_cast<float>(rawPixels[idxGr]) - blackLevel);
            float gb_lin = std::max(0.0f, static_cast<float>(rawPixels[idxGb]) - blackLevel);
            float b_lin  = std::max(0.0f, static_cast<float>(rawPixels[idxB]) - blackLevel);

            r_lin  *= rGain;
            gr_lin *= gGain;
            gb_lin *= gGain;
            b_lin  *= bGain;

            float r_norm = std::min(r_lin / whiteLevel, 1.0f);
            float gr_norm = std::min(gr_lin / whiteLevel, 1.0f);
            float gb_norm = std::min(gb_lin / whiteLevel, 1.0f);
            float b_norm = std::min(b_lin / whiteLevel, 1.0f);
            float g_avg = (gr_norm + gb_norm) / 2.0f;

            float r_out = r_norm * ccm[0] + g_avg * ccm[1] + b_norm * ccm[2];
            float gr_out = r_norm * ccm[3] + gr_norm * ccm[4] + b_norm * ccm[5];
            float gb_out = r_norm * ccm[3] + gb_norm * ccm[4] + b_norm * ccm[5];
            float b_out = r_norm * ccm[6] + g_avg * ccm[7] + b_norm * ccm[8];

            r_out = std::max(0.0f, std::min(1.0f, r_out));
            gr_out = std::max(0.0f, std::min(1.0f, gr_out));
            gb_out = std::max(0.0f, std::min(1.0f, gb_out));
            b_out = std::max(0.0f, std::min(1.0f, b_out));

            r_out = std::pow(r_out, 1.0f / 2.2f);
            gr_out = std::pow(gr_out, 1.0f / 2.2f);
            gb_out = std::pow(gb_out, 1.0f / 2.2f);
            b_out = std::pow(b_out, 1.0f / 2.2f);

            rawPixels[idxR]  = static_cast<uint16_t>(r_out * whiteLevel);
            rawPixels[idxGr] = static_cast<uint16_t>(gr_out * whiteLevel);
            rawPixels[idxGb] = static_cast<uint16_t>(gb_out * whiteLevel);
            rawPixels[idxB]  = static_cast<uint16_t>(b_out * whiteLevel);
        }
    }

    env->ReleaseFloatArrayElements(ccm_array, ccm, JNI_ABORT);
    env->ReleaseByteArrayElements(raw_data, pData, 0); 
}
