package com.teya.agent.voice.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Local barge-in speech detector: runs Silero VAD (`silero_vad.onnx`, MIT, from
 * https://github.com/snakers4/silero-vad — see THIRD_PARTY_MODELS.md) directly via ONNX Runtime.
 *
 * This is an original implementation of the streaming algorithm Silero documents in its own
 * `OnnxWrapper`/`VADIterator` reference code (`src/silero_vad/utils_vad.py` in that repo), not a
 * port of any third-party Android wrapper — verified straight from the model's ONNX graph
 * (`input`/`state`/`sr` in, `output`/state out) rather than assumed from another project's code:
 *
 *   - `input`: float32 [1, 576] for 16kHz — the new 512-sample frame with the *previous* frame's
 *     last 64 samples ("context") prepended. Silero's model was trained expecting this overlap;
 *     without it accuracy degrades at frame boundaries.
 *   - `state`: float32 [2, 1, 128] — the model's recurrent hidden state, carried across calls and
 *     replaced (not accumulated) with the `state` output each call. Reset to zero at construction.
 *   - `sr`: int64 scalar, 16000.
 *   - outputs: `output` float32 [1,1] speech confidence in [0,1], and the next `state`.
 *
 * Stateful: frames MUST be fed in strict chronological order on one instance — construct fresh
 * per armed window (barge-in only runs while Teya is thinking/speaking) and [close] on disarm.
 *
 * Runs fully on-device (no network dependency/cost); does not by itself solve self-echo (Teya's
 * own voice bleeding into the mic) — see thoughts/shared/research/2026-07-08-barge-in-vad-options.md.
 */
class SileroVad(
    context: Context,
    private val threshold: Float = 0.5f,
    speechDurationMs: Int = 0,
    silenceDurationMs: Int = 0
) : Closeable {

    companion object {
        private const val SAMPLE_RATE = 16000L
        const val FRAME_SIZE = 512 // 32ms @ 16kHz — the only frame size callers may pass to isSpeech()
        private const val CONTEXT_SIZE = 64
        private const val STATE_SIZE = 128
        private const val MS_PER_FRAME = FRAME_SIZE * 1000 / 16000
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var state = FloatArray(2 * STATE_SIZE)
    private var context = FloatArray(CONTEXT_SIZE)

    private var speechFrames = 0
    private var silenceFrames = 0
    private val maxSpeechFrames = speechDurationMs / MS_PER_FRAME
    private val maxSilenceFrames = silenceDurationMs / MS_PER_FRAME

    /** Diagnostic: raw model confidence from the most recent [isSpeech] call, before thresholding. */
    var lastConfidence = 0f
        private set

    init {
        val model = context.assets.open("silero_vad.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(model, options)
    }

    /**
     * Feed exactly [FRAME_SIZE] samples per call, in order. Applies the speech/silence-run
     * debounce configured via [speechDurationMs]/[silenceDurationMs] so a one-frame spike doesn't
     * fire immediately (pass 0/0 for a raw, undebounced per-frame result).
     */
    fun isSpeech(frame: ShortArray): Boolean {
        require(frame.size == FRAME_SIZE) { "SileroVad frame must be $FRAME_SIZE samples, got ${frame.size}" }
        val floats = FloatArray(FRAME_SIZE) { frame[it] / 32768f }
        return debounce(predict(floats))
    }

    private fun predict(frame: FloatArray): Boolean {
        val input = context + frame // [context(64) ++ frame(512)] = 576 samples, per Silero's OnnxWrapper
        context = frame.copyOfRange(FRAME_SIZE - CONTEXT_SIZE, FRAME_SIZE)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { inputTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf()).use { srTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_SIZE.toLong())).use { stateTensor ->
                    val inputs = mapOf("input" to inputTensor, "sr" to srTensor, "state" to stateTensor)
                    session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val confidence = (result.get(0).value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val nextState = result.get(1).value as Array<Array<FloatArray>>
                        state = nextState.flatten().flatMap { it.asIterable() }.toFloatArray()
                        lastConfidence = confidence
                        return confidence > threshold
                    }
                }
            }
        }
    }

    /**
     * Requires [maxSpeechFrames] consecutive speech frames before reporting speech (debounces a
     * one-off spike), then keeps reporting speech until [maxSilenceFrames] consecutive silence
     * frames.
     */
    private fun debounce(isSpeech: Boolean): Boolean {
        if (isSpeech) {
            if (speechFrames <= maxSpeechFrames) speechFrames++
            if (speechFrames > maxSpeechFrames) {
                silenceFrames = 0
                return true
            }
        } else {
            if (silenceFrames <= maxSilenceFrames) silenceFrames++
            if (silenceFrames > maxSilenceFrames) {
                speechFrames = 0
                return false
            } else if (speechFrames > maxSpeechFrames) {
                return true
            }
        }
        return false
    }

    override fun close() {
        session.close()
        env.close() // OrtEnvironment is a ref-counted singleton; safe to close per-instance.
    }
}
