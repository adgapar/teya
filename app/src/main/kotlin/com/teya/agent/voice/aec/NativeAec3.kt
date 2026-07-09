package com.teya.agent.voice.aec

/**
 * JNI wrapper around Teya's vendored WebRTC AEC3 native module (`libteya_aec3.so`, built from
 * `app/src/main/cpp/`).
 *
 * Phase 2 skeleton: proves the NDK -> CMake -> .so -> JNI -> Kotlin toolchain end-to-end via
 * [ping] before any real AEC3 code exists. Replaced with the real `analyzeRender`/
 * `processCapture` API (mirroring `SileroVad`'s construct/feed-frames/close shape) once Phase
 * 3a/3b vendor and wire up the actual echo canceller.
 */
class NativeAec3 {

    companion object {
        init {
            System.loadLibrary("teya_aec3")
        }
    }

    external fun ping(): Int
}
