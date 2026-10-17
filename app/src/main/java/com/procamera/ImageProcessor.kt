package com.procamera

class ImageProcessor {
    companion object {
        init {
            System.loadLibrary("procamera")
        }
    }

    external fun stringFromJNI(): String
    external fun processImageNative(imageData: ByteArray, width: Int, height: Int)
}
