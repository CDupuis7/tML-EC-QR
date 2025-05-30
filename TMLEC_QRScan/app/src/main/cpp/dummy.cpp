#include <jni.h>

// Dummy function to satisfy build requirements
extern "C"
{
    JNIEXPORT jint JNICALL
    Java_com_example_tml_1ec_1qr_1scan_OpenCVManager_dummyNativeMethod(JNIEnv *env, jobject thiz)
    {
        return 0;
    }
}