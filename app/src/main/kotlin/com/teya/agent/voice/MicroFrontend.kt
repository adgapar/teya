package com.teya.agent.voice

/**
 * Thin JNI wrapper around the vendored TFLite Micro "microfrontend" (see
 * `app/src/main/cpp/microfrontend/`) — the fixed-point mel-filterbank feature extractor
 * `hey_teya.tflite` (microWakeWord) needs at inference time, since it isn't expressible as a
 * portable TFLite graph.
 *
 * One instance owns one native `MicroFrontendWrapper`; call [processSamples] with successive
 * chunks of 16 kHz mono PCM16 audio (chunk size doesn't need to align to any frame boundary — the
 * native frontend buffers internally) and get back zero or more completed 40-dim feature frames,
 * one per 10 ms step once the 30 ms analysis window has enough history.
 */
class MicroFrontend(private val sampleRate: Int = SAMPLE_RATE, private val stepSizeMs: Int = STEP_SIZE_MS) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val STEP_SIZE_MS = 10
        const val FEATURE_SIZE = 40

        init {
            System.loadLibrary("microfrontend")
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, stepSizeMs: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeProcessSamples(handle: Long, samples: ShortArray): ArrayList<FloatArray>?
        @JvmStatic private external fun nativeReset(handle: Long)
    }

    private var handle: Long = nativeCreate(sampleRate, stepSizeMs)

    val isInitialized: Boolean get() = handle != 0L

    /** Feeds audio and returns any newly completed 40-dim feature frames (may be empty). */
    fun processSamples(samples: ShortArray): List<FloatArray> {
        if (handle == 0L) return emptyList()
        return nativeProcessSamples(handle, samples) ?: emptyList()
    }

    /** Clears the frontend's internal window/noise/PCAN state (start of a fresh listening session). */
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /** Releases the native wrapper. Not safe to call [processSamples]/[reset] afterward. */
    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }
}
