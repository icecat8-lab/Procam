package com.procamera

class ImageProcessor {
    companion object {
        init {
            System.loadLibrary("procamera")
        }
    }

    external fun processRawImageNative(rawData: ByteArray, width: Int, height: Int)
}
