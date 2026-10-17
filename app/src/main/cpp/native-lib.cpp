#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstring>

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
        jfloatArray ccmArray,
        jint cfaPattern,
        jint whiteLevel) {

    // Get buffers; use JNI_ABORT for read-only inputs so the JVM doesn't copy back
    jbyte *rawBytes = env->GetByteArrayElements(rawData, nullptr);
    jbyte *outputBytes = env->GetByteArrayElements(outputData, nullptr);
    jfloat *ccm = env->GetFloatArrayElements(ccmArray, nullptr);

    if (!rawBytes || !outputBytes || !ccm) {
        if (rawBytes) env->ReleaseByteArrayElements(rawData, rawBytes, JNI_ABORT);
        if (outputBytes) env->ReleaseByteArrayElements(outputData, outputBytes, JNI_ABORT);
        if (ccm) env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
        return;
    }

    uint16_t *raw16 = reinterpret_cast<uint16_t *>(rawBytes);
    uint32_t *outPixels = reinterpret_cast<uint32_t *>(outputBytes);

    int stridePixels = rowStride / 2; // raw16 is uint16

    // Sanity check bounds: raw buffer must hold at least stridePixels * height pixels
    jsize rawLen = env->GetArrayLength(rawData);
    if (rawLen < stridePixels * height * 2) {
        env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
        env->ReleaseByteArrayElements(rawData, rawBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(outputData, outputBytes, JNI_ABORT);
        return;
    }

    // Helper to read raw pixel safely
    auto getRaw = [&](int x, int y) -> float {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0.0f;
        int idx = y * stridePixels + x;
        return std::max(0.0f, static_cast<float>(raw16[idx]) - blackLevel);
    };

    // Helper for clipping after gamma
    auto clamp8 = [](float v) -> uint8_t {
        if (v < 0.0f) return 0;
        if (v > 255.0f) return 255;
        return static_cast<uint8_t>(v);
    };

    // Precompute clamped white-balance gains (avoid div-by-zero)
    float rg = (rGain > 0.001f) ? rGain : 1.0f;
    float gg = (gGain > 0.001f) ? gGain : 1.0f;
    float bg = (bGain > 0.001f) ? bGain : 1.0f;

    // FIX: Bayer pattern was hardcoded RGGB — sensors with GRBG/GBRG/BGGR produced
    // green/magenta-tinted images. Now supports all 4 arrangements from
    // CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT.
    // Returns 0 = R, 1 = G, 2 = B for the given Bayer site.
    auto colorAt = [&](int x, int y) -> int {
        int px = ((x % 2) + 2) % 2;
        int py = ((y % 2) + 2) % 2;
        switch (cfaPattern) {
            case 1: // GRBG: row0 G R G R / row1 B G B G
                return (py == 0) ? (px == 1 ? 0 : 1) : (px == 0 ? 2 : 1);
            case 2: // GBRG: row0 G B G B / row1 R G R G
                return (py == 0) ? (px == 1 ? 2 : 1) : (px == 0 ? 0 : 1);
            case 3: // BGGR: (0,0)=B (1,1)=R
                return (px == 0 && py == 0) ? 2 : (px == 1 && py == 1 ? 0 : 1);
            default: // 0 = RGGB: row0 R G R G / row1 G B G B
                return (px == 0 && py == 0) ? 0 : (px == 1 && py == 1 ? 2 : 1);
        }
    };

    auto isRedAt = [&](int x, int y) { return colorAt(x, y) == 0; };
    auto isBlueAt = [&](int x, int y) { return colorAt(x, y) == 2; };
    // green elsewhere

    // Output: ARGB_8888. Android's copyPixelsFromBuffer expects bytes in [R, G, B, A] order.
    // On little-endian ARM, an int (A<<24)|(B<<16)|(G<<8)|R is stored in memory as
    // [R, G, B, A] — which is what ARGB_8888 wants. So the formula below is correct:
    //   int = (A<<24) | (B<<16) | (G<<8) | R
    // (Previously the code used R<<16, B at low byte => Red & Blue were swapped.)

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            // Bilinear demosaic: average neighbours of same channel
            float r, g, b;

            if (isRedAt(x, y)) {
                r = getRaw(x, y) * rg;
                g = (getRaw(x - 1, y) + getRaw(x + 1, y) +
                     getRaw(x, y - 1) + getRaw(x, y + 1)) * 0.25f * gg;
                b = (getRaw(x - 1, y - 1) + getRaw(x + 1, y - 1) +
                     getRaw(x - 1, y + 1) + getRaw(x + 1, y + 1)) * 0.25f * bg;
            } else if (isBlueAt(x, y)) {
                b = getRaw(x, y) * bg;
                g = (getRaw(x - 1, y) + getRaw(x + 1, y) +
                     getRaw(x, y - 1) + getRaw(x, y + 1)) * 0.25f * gg;
                r = (getRaw(x - 1, y - 1) + getRaw(x + 1, y - 1) +
                     getRaw(x - 1, y + 1) + getRaw(x + 1, y + 1)) * 0.25f * rg;
            } else {
                // Green site — detect which channel sits on the horizontal neighbours
                g = getRaw(x, y) * gg;
                if (colorAt(x + 1, y) == 0) {
                    // Horizontal neighbours are R, vertical are B
                    r = (getRaw(x - 1, y) + getRaw(x + 1, y)) * 0.5f * rg;
                    b = (getRaw(x, y - 1) + getRaw(x, y + 1)) * 0.5f * bg;
                } else {
                    // Horizontal neighbours are B, vertical are R
                    b = (getRaw(x - 1, y) + getRaw(x + 1, y)) * 0.5f * bg;
                    r = (getRaw(x, y - 1) + getRaw(x, y + 1)) * 0.5f * rg;
                }
            }

            // Apply color correction matrix (3x3, row-major from camera2 ColorSpaceTransform)
            // Matrix layout from Kotlin:
            //   index 0..2 = row 0 (R-out coefficients for R_in, G_in, B_in)
            //   index 3..5 = row 1 (G-out)
            //   index 6..8 = row 2 (B-out)
            float nr = ccm[0] * r + ccm[1] * g + ccm[2] * b;
            float ng = ccm[3] * r + ccm[4] * g + ccm[5] * b;
            float nb = ccm[6] * r + ccm[7] * g + ccm[8] * b;

            // sRGB gamma encoding (linear → sRGB curve)
            auto toSrgb = [](float v) -> float {
                if (v <= 0.0031308f) return v * 12.92f;
                return 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
            };

            // FIX: white level was hardcoded 4095 — 10-bit sensors produced dark images.
            // Now uses the real value from SENSOR_INFO_WHITE_LEVEL passed from Kotlin.
            float scale = (whiteLevel > 0) ? 1.0f / static_cast<float>(whiteLevel)
                                           : (1.0f / 4095.0f);
            float lr = nr * scale;
            float lg = ng * scale;
            float lb = nb * scale;

            // Apply gamma
            float gr = toSrgb(lr) * 255.0f;
            float gg2 = toSrgb(lg) * 255.0f;
            float gb = toSrgb(lb) * 255.0f;

            uint8_t orr = clamp8(gr);
            uint8_t og = clamp8(gg2);
            uint8_t ob = clamp8(gb);

            // Pack as ARGB_8888 in memory order [R, G, B, A] on little-endian:
            // int = (A<<24) | (B<<16) | (G<<8) | R
            uint32_t color = (0xFFu << 24) | (static_cast<uint32_t>(ob) << 16)
                             | (static_cast<uint32_t>(og) << 8)
                             | static_cast<uint32_t>(orr);
            outPixels[y * width + x] = color;
        }
    }

    env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
    env->ReleaseByteArrayElements(rawData, rawBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(outputData, outputBytes, 0);
}
