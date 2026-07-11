package com.teya.agent.voice.aec

import java.io.Closeable

/**
 * JNI wrapper around Teya's vendored WebRTC AEC3 (`libteya_aec3.so`, built from
 * `app/src/main/cpp/`, real glue in `jni_aec3.cpp` around a real `EchoCanceller3` instance).
 *
 * Mirrors `SileroVad`'s construct -> feed-frames -> `close()` shape. Configured for mono 16kHz,
 * full-band (no band-splitting) operation, matching this device's single-mic, single-speaker
 * setup and `EchoCanceller3Config`'s shipped defaults (no custom tuning yet — see the plan's
 * "What We're NOT Doing").
 *
 * - [analyzeRender]: feed the known farend (TTS) reference signal. Per AEC3's own documented
 *   threading contract this may be called from a different thread than [processCapture].
 * - [processCapture]: feed the nearend (mic) signal, get back the echo-cancelled version.
 * - Not yet wired into `VoicePipeline`/`HarnessService` — that's Plan B (see the plan's Appendix).
 */
class NativeAec3(sampleRateHz: Int = 16000) : Closeable {

    companion object {
        const val FRAME_SIZE = 160 // 10ms @ 16kHz — the only frame size callers may pass
        private const val CLOSED_HANDLE = 0L

        init {
            System.loadLibrary("teya_aec3")
        }
    }

    // Guards handle create/use/close: analyzeRender/processCapture may run on different threads
    // (render vs. capture, per AEC3's own threading contract), while close() can race either from
    // a disarm path. A single lock is a coarser, safer superset of that contract — mirrors
    // VoicePipeline's vadLock discipline around SileroVad (guarding against a disarm's close()
    // racing a concurrent native call and crashing on use-after-free), baked in here instead of
    // left to every caller since this class owns a raw native pointer directly.
    private val lock = Any()
    @Volatile private var handle: Long = nativeCreate(sampleRateHz)

    /** Feed exactly [FRAME_SIZE] samples of the known farend (TTS) reference signal, in order. */
    fun analyzeRender(frame: ShortArray) {
        require(frame.size == FRAME_SIZE) { "NativeAec3 render frame must be $FRAME_SIZE samples, got ${frame.size}" }
        synchronized(lock) {
            val h = handle
            check(h != CLOSED_HANDLE) { "NativeAec3 used after close()" }
            nativeAnalyzeRender(h, frame)
        }
    }

    /**
     * Feed exactly [FRAME_SIZE] samples of the nearend (mic) signal, in order; returns the
     * echo-cancelled version, also [FRAME_SIZE] samples.
     */
    fun processCapture(frame: ShortArray): ShortArray {
        require(frame.size == FRAME_SIZE) { "NativeAec3 capture frame must be $FRAME_SIZE samples, got ${frame.size}" }
        synchronized(lock) {
            val h = handle
            check(h != CLOSED_HANDLE) { "NativeAec3 used after close()" }
            return nativeProcessCapture(h, frame)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (handle == CLOSED_HANDLE) return
            nativeDestroy(handle)
            handle = CLOSED_HANDLE
        }
    }

    /** AEC3's own [EchoCanceller3::GetMetrics] snapshot — see [Metrics] for field meanings. */
    fun getMetrics(): Metrics {
        synchronized(lock) {
            val h = handle
            check(h != CLOSED_HANDLE) { "NativeAec3 used after close()" }
            val raw = nativeGetMetrics(h)
            return Metrics(echoReturnLossDb = raw[0], echoReturnLossEnhancementDb = raw[1], delayMs = raw[2].toInt())
        }
    }

    /**
     * AEC3's self-reported view of render/capture alignment and cancellation quality, straight
     * from `EchoControl::Metrics` (see `api/audio/echo_control.h`):
     * - [echoReturnLossDb]: how much the acoustic path itself (room/speaker/mic) already attenuates
     *   the echo, before AEC3 does anything — reflects physical setup, not AEC3's own work.
     * - [echoReturnLossEnhancementDb]: how much *more* AEC3's adaptive filter removes on top of
     *   that — near zero means the filter isn't actually cancelling anything.
     * - [delayMs]: AEC3's current best estimate of the render-to-capture delay it's tracking.
     *   Unstable or implausibly large values here point at a delay-estimation problem rather than
     *   a suppression-strength problem.
     */
    data class Metrics(val echoReturnLossDb: Double, val echoReturnLossEnhancementDb: Double, val delayMs: Int)

    private external fun nativeCreate(sampleRateHz: Int): Long
    private external fun nativeAnalyzeRender(handle: Long, frame: ShortArray)
    private external fun nativeProcessCapture(handle: Long, frame: ShortArray): ShortArray
    private external fun nativeGetMetrics(handle: Long): DoubleArray
    private external fun nativeDestroy(handle: Long)
}
