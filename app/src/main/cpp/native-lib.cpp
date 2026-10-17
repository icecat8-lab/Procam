#include <jni.h>
#include <string>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_com_procamera_ImageProcessor_stringFromJNI(
    JNIEnv* env,
    jobject /* this */) {
    std::string hello = "NDK Native Engine Active";
    return env->NewStringUTF(hello.c_str());
}


extern "C" JNIEXPORT void JNICALL
Java_com_procamera_ImageProcessor_processImageNative(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray image_data,
    jint width,
    jint height) {
    
    jbyte* pData = env->GetByteArrayElements(image_data, nullptr);
    if (pData == nullptr) return;

    int totalPixels = width * height;


    env->ReleaseByteArrayElements(image_data, pData, 0);
}
