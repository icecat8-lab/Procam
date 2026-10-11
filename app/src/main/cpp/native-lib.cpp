#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_myname_procamera_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "NDK C++ is Working!";
    return env->NewStringUTF(hello.c_str());
}
