package com.procamera

import android.graphics.Bitmap
import java.nio.ByteBuffer

class ImageProcessor {
    companion object {
        init {
            System.loadLibrary("procamera")
        }
    }

    external fun processRawPro(
        rawBuffer: ByteBuffer,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        rowStride: Int,
        blackLevel: Float,
        rGain: Float,
        gGain: Float,
        bGain: Float,
        ccm: FloatArray,
        cfaPattern: Int,
        whiteLevel: Int
    ): Boolean
}
