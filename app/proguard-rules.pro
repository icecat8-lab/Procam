# ProGuard rules for ProCamera

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ImageProcessor (used by JNI)
-keep class com.procamera.ImageProcessor { *; }

# Keep CameraController callbacks (used by Camera2)
-keep class com.procamera.CameraController$* { *; }

# Keep ViewModel
-keep class com.procamera.CameraViewModel { *; }

# Don't warn about unused JNI classes
-dontwarn android.hardware.camera2.**
