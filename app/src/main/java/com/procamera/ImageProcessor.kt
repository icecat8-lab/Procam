package com.procamera

import android.graphics.Bitmap

class ImageProcessor {

    companion object {
        
        init {
            
            System.loadLibrary("procamera")
        }
    }

    
    external fun stringFromJNI(): String
    
    
    external fun processImage(bitmap: Bitmap)
}
