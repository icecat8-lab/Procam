package com.procamera

class ImageProcessor {
    companion object {
        init {
            System.loadLibrary("procamera")
        }
    }

    external fun processRawPro(
        rawData: ByteArray,
        width: Int,
        height: Int,
        blackLevel: Float,
        rGain: Float,
        gGain: Float,
        bGain: Float,
        ccm: FloatArray
    )
}
