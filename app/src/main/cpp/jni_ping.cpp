// Trivial JNI passthrough — proves the NDK/CMake/Gradle toolchain end-to-end before any real AEC3
// code is compiled (Phase 2 of the WebRTC AEC3 native port plan). Replaced by real JNI glue for
// EchoCanceller3 in Phase 3b; see NativeAec3.kt for the Kotlin side of this call.

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_ping(JNIEnv* /* env */, jobject /* thiz */) {
    return 42;
}
