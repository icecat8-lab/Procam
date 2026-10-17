package com.procamera

import android.net.Uri
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    var activeMode: String = "NONE"
    
    var afProgress: Int = 0
    var isoProgress: Int = 0
    var wbProgress: Int = 0
    var sProgress: Int = 0
    var evProgress: Int = 50

    var latestImageUri: Uri? = null
}
