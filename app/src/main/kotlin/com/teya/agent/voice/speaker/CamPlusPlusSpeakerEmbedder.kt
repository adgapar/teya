package com.teya.agent.voice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * [SpeakerEmbedder] backed by CAM++ (Alibaba 3D-Speaker/ModelScope, English VoxCeleb checkpoint --
 * see THIRD_PARTY_MODELS.md), run directly via ONNX Runtime -- the same runtime already vendored
 * for [com.teya.agent.voice.vad.SileroVad], no second native dependency.
 *
 * Original implementation against the model's own ONNX graph (verified via `onnx.load()`, not
 * assumed from documentation): input `x` is float32 `[1, T, 80]` -- precomputed 80-dim log-mel
 * fbank (see [Fbank]), globally mean-normalized per the model's `feature_normalize_type=
 * global-mean` metadata -- output `embedding` is float32 `[1, 512]`.
 *
 * Unlike Silero VAD, this model is stateless per call (no recurrent state to reset), so one
 * instance can be long-lived for the process lifetime rather than reconstructed per window.
 */
class CamPlusPlusSpeakerEmbedder(context: Context) : SpeakerEmbedder {

    override val dim: Int = EMBEDDING_DIM

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val fbank = Fbank()

    init {
        val model = context.assets.open(ASSET_NAME).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(model, options)
    }

    override fun embed(pcm: ShortArray): FloatArray {
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val frames = fbank.compute(samples)
        require(frames.isNotEmpty()) { "audio window too short to produce any fbank frames" }
        globalMeanNormalize(frames)

        val flat = FloatArray(frames.size * frames[0].size)
        for (t in frames.indices) System.arraycopy(frames[t], 0, flat, t * frames[0].size, frames[0].size)

        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1, frames.size.toLong(), frames[0].size.toLong()),
        ).use { inputTensor ->
            session.run(mapOf("x" to inputTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val embedding = (result.get(0).value as Array<FloatArray>)[0]
                return embedding
            }
        }
    }

    private fun globalMeanNormalize(frames: Array<FloatArray>) {
        val featDim = frames[0].size
        val mean = FloatArray(featDim)
        for (frame in frames) for (i in 0 until featDim) mean[i] += frame[i]
        for (i in 0 until featDim) mean[i] /= frames.size
        for (frame in frames) for (i in 0 until featDim) frame[i] -= mean[i]
    }

    override fun close() {
        session.close()
        env.close() // OrtEnvironment is a ref-counted singleton; safe to close per-instance.
    }

    companion object {
        private const val ASSET_NAME = "speaker_embedding.onnx"
        const val EMBEDDING_DIM = 512
    }
}
