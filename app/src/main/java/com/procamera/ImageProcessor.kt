package com.procamera

class ImageProcessor {
    companion object {
        init {
            System.loadLibrary("procamera")
        }
    }

    external fun processRawPro(
        rawData: ByteArray,
        outputData: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        blackLevel: Float,
        rGain: Float,
        gGain: Float,
        bGain: Float,
        ccm: FloatArray
    )
}
