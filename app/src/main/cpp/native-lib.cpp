#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "ProCameraNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kLutSize = 4096;

n   if (i < 0) return -i;
    if (i >= n) return 2 * (n - 1) - i;
    return i;
}


void buildSiteTable(int cfa, int site[2][2]) {
    switch (cfa) {
        case 1: site[0][0] = 1; site[0][1] = 0; site[1][0] = 2; site[1][1] = 1; break; // GRBG
        case 2: site[0][0] = 1; site[0][1] = 2; site[1][0] = 0; site[1][1] = 1; break; // GBRG
        case 3: site[0][0] = 2; site[0][1] = 1; site[1][0] = 1; site[1][1] = 0; break; // BGGR
        default: site[0][0] = 0; site[0][1] = 1; site[1][0] = 1; site[1][1] = 2; break; // RGGB
    }
}

struct Params {
    const uint8_t *raw;
    int width;
    int height;
    int rowStride;      
    float black;
    float wr, wg, wb;   
    float ccm[9];       
    int site[2][2];
    uint8_t *dst;
    int dstStride;      
    const uint8_t *lut;
};

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

void processRows(const Params &p, int yBegin, int yEnd) {
    const int w = p.width;
    const int h = p.height;

    for (int y = yBegin; y < yEnd; y++) {
        const uint16_t *rowC = reinterpret_cast<const uint16_t *>(p.raw + static_cast<size_t>(y) * p.rowStride);
        const uint16_t *rowU = reinterpret_cast<const uint16_t *>(p.raw + static_cast<size_t>(mirror(y - 1, h)) * p.rowStride);
        const uint16_t *rowD = reinterpret_cast<const uint16_t *>(p.raw + static_cast<size_t>(mirror(y + 1, h)) * p.rowStride);
        uint8_t *out = p.dst + static_cast<size_t>(y) * p.dstStride;
        const int py = y & 1;

        for (int x = 0; x < w; x++) {
            const int xl = mirror(x - 1, w);
            const int xr = mirror(x + 1, w);
            const int px = x & 1;

            auto S = [&](const uint16_t *row, int xx) -> float {
                float v = static_cast<float>(row[xx]) - p.black;
                return v > 0.0f ? v : 0.0f;
            };

            float r, g, b;
            const int c = p.site[py][px];
            if (c == 0) {                       
                r = S(rowC, x);
                g = (S(rowC, xl) + S(rowC, xr) + S(rowU, x) + S(rowD, x)) * 0.25f;
                b = (S(rowU, xl) + S(rowU, xr) + S(rowD, xl) + S(rowD, xr)) * 0.25f;
            } else if (c == 2) {                
                b = S(rowC, x);
                g = (S(rowC, xl) + S(rowC, xr) + S(rowU, x) + S(rowD, x)) * 0.25f;
                r = (S(rowU, xl) + S(rowU, xr) + S(rowD, xl) + S(rowD, xr)) * 0.25f;
            } else {                            
                g = S(rowC, x);
                const float horiz = (S(rowC, xl) + S(rowC, xr)) * 0.5f;
                const float vert = (S(rowU, x) + S(rowD, x)) * 0.5f;
                if (p.site[py][(x + 1) & 1] == 0) { 
                    r = horiz;
                    b = vert;
                } else {
                    b = horiz;
                    r = vert;
                }
            }

            
            r = clamp01(r * p.wr);
            g = clamp01(g * p.wg);
            b = clamp01(b * p.wb);

            
            const float nr = p.ccm[0] * r + p.ccm[1] * g + p.ccm[2] * b;
            const float ng = p.ccm[3] * r + p.ccm[4] * g + p.ccm[5] * b;
            const float nb = p.ccm[6] * r + p.ccm[7] * g + p.ccm[8] * b;

            
            uint8_t *o = out + static_cast<size_t>(x) * 4;
            o[0] = p.lut[static_cast<int>(clamp01(nr) * (kLutSize - 1) + 0.5f)];
            o[1] = p.lut[static_cast<int>(clamp01(ng) * (kLutSize - 1) + 0.5f)];
            o[2] = p.lut[static_cast<int>(clamp01(nb) * (kLutSize - 1) + 0.5f)];
            o[3] = 255;
        }
    }
}

} // namespace


extern "C" JNIEXPORT jboolean JNICALL
Java_com_procamera_ImageProcessor_processRawPro(
        JNIEnv *env,
        jobject /* this */,
        jobject rawBuffer,
        jobject bitmap,
        jint width,
        jint height,
        jint rowStride,
        jfloat blackLevel,
        jfloat rGain,
        jfloat gGain,
        jfloat bGain,
        jfloatArray ccmArray,
        jint cfaPattern,
        jint whiteLevel) {

    if (rawBuffer == nullptr || bitmap == nullptr || ccmArray == nullptr) {
        LOGE("null argument");
        return JNI_FALSE;
    }
    if (width < 2 || height < 2 || rowStride < width * 2) {
        LOGE("bad dimensions %dx%d stride=%d", width, height, rowStride);
        return JNI_FALSE;
    }
    if (env->GetArrayLength(ccmArray) < 9) {
        LOGE("ccm needs 9 floats");
        return JNI_FALSE;
    }

    const uint8_t *raw = static_cast<const uint8_t *>(env->GetDirectBufferAddress(rawBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(rawBuffer);
    if (raw == nullptr || capacity < 0) {
        LOGE("raw buffer is not a direct ByteBuffer");
        return JNI_FALSE;
    }
    const int64_t needed = static_cast<int64_t>(height - 1) * rowStride + static_cast<int64_t>(width) * 2;
    if (capacity < needed) {
        LOGE("raw buffer too small: %lld < %lld", (long long) capacity, (long long) needed);
        return JNI_FALSE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width < static_cast<uint32_t>(width) || info.height < static_cast<uint32_t>(height)) {
        LOGE("bitmap mismatch: format=%d %ux%u", info.format, info.width, info.height);
        return JNI_FALSE;
    }

    Params p;
    p.raw = raw;
    p.width = width;
    p.height = height;
    p.rowStride = rowStride;
    p.black = blackLevel;

    const float range = static_cast<float>(whiteLevel) - blackLevel;
    const float norm = range > 1.0f ? 1.0f / range : 1.0f / 1023.0f;
    p.wr = (rGain > 0.001f ? rGain : 1.0f) * norm;
    p.wg = (gGain > 0.001f ? gGain : 1.0f) * norm;
    p.wb = (bGain > 0.001f ? bGain : 1.0f) * norm;

    env->GetFloatArrayRegion(ccmArray, 0, 9, p.ccm);
    buildSiteTable(cfaPattern, p.site);

    
    std::vector<uint8_t> lut(kLutSize);
    for (int i = 0; i < kLutSize; i++) {
        const float v = static_cast<float>(i) / (kLutSize - 1);
        const float s = (v <= 0.0031308f) ? v * 12.92f : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
        lut[i] = static_cast<uint8_t>(std::min(255.0f, std::max(0.0f, s * 255.0f + 0.5f)));
    }
    p.lut = lut.data();

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        LOGE("AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }
    p.dst = static_cast<uint8_t *>(pixels);
    p.dstStride = static_cast<int>(info.stride);

    unsigned hw = std::thread::hardware_concurrency();
    int threads = static_cast<int>(std::min(4u, std::max(1u, hw)));
    if (height < 64) threads = 1;

    if (threads == 1) {
        processRows(p, 0, height);
    } else {
        std::vector<std::thread> pool;
        const int chunk = (height + threads - 1) / threads;
        int started = 0;
        try {
            for (int t = 1; t < threads; t++) {
                const int y0 = t * chunk;
                const int y1 = std::min(height, y0 + chunk);
                if (y0 >= y1) break;
                pool.emplace_back([&p, y0, y1]() { processRows(p, y0, y1); });
                started++;
            }
        } catch (...) {
            
        }
        processRows(p, 0, std::min(height, chunk));
        for (auto &th : pool) th.join();
        
        const int doneUntil = std::min(height, chunk * (started + 1));
        if (doneUntil < height) processRows(p, doneUntil, height);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
