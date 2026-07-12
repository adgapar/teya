package com.teya.agent.voice.speaker

import java.io.Closeable

/**
 * Turns a short raw-audio window into a fixed-length voiceprint vector, comparable to other
 * embeddings from the same implementation via cosine similarity.
 *
 * Deliberately narrow so the concrete backend (currently [CamPlusPlusSpeakerEmbedder]) can be
 * swapped for a different model/runtime later without touching any caller (`SpeakerIdManager`,
 * the Admin enrollment UI, `HarnessService`) -- construct one implementation at a single call
 * site and pass it around as this interface type.
 */
interface SpeakerEmbedder : Closeable {
    /** Embedding vector length this implementation produces. */
    val dim: Int

    /** [pcm]: mono 16kHz int16 samples. Returns a [dim]-length embedding. */
    fun embed(pcm: ShortArray): FloatArray
}
