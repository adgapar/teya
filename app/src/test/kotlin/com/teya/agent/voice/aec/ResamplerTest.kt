package com.teya.agent.voice.aec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 1: plain JVM tests for [Resampler] -- no Android/device dependency, no emulator needed.
 * Mirrors Plan A Phase 4's Goertzel-magnitude probe technique
 * (`NativeAec3EchoCancellationTest.goertzelMagnitude`) to verify frequency content survives
 * resampling, not just "produces the right number of samples".
 */
class ResamplerTest {

    companion object {
        private const val INPUT_RATE = Resampler.INPUT_RATE_HZ // 24000
        private const val OUTPUT_RATE = Resampler.OUTPUT_RATE_HZ // 16000

        private fun tone(freqHz: Double, amplitude: Double, sampleRate: Int, numSamples: Int): ShortArray =
            ShortArray(numSamples) { n ->
                (amplitude * sin(2.0 * PI * freqHz * n / sampleRate)).toInt().toShort()
            }

        /**
         * Goertzel algorithm: magnitude of a single frequency bin within a window of samples.
         * Same technique as `NativeAec3EchoCancellationTest.goertzelMagnitude` (Plan A, Phase 4) --
         * not normalized to absolute amplitude, only ever used to find the dominant frequency
         * among candidates or to compare relative energy at a known frequency.
         */
        private fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, targetFreqHz: Double): Double {
            val n = samples.size
            val k = 0.5 + n * targetFreqHz / sampleRate
            val w = 2.0 * PI * k / n
            val cosine = cos(w)
            val sine = sin(w)
            val coeff = 2.0 * cosine
            var q0 = 0.0
            var q1 = 0.0
            var q2 = 0.0
            for (i in 0 until n) {
                q0 = coeff * q1 - q2 + samples[i]
                q2 = q1
                q1 = q0
            }
            val real = q1 - q2 * cosine
            val imag = q2 * sine
            return sqrt(real * real + imag * imag)
        }

        /** Returns whichever of [candidateFreqsHz] has the strongest Goertzel magnitude. */
        private fun dominantFrequency(samples: ShortArray, sampleRate: Int, candidateFreqsHz: DoubleArray): Double =
            candidateFreqsHz.maxBy { f -> goertzelMagnitude(samples, sampleRate, f) }
    }

    /**
     * Generates a 500Hz tone at 24kHz, resamples it to 16kHz in one shot, and verifies the
     * dominant frequency among a set of candidates (some correct, some plausible-but-wrong -- e.g.
     * what you'd see from an aliasing or off-by-a-half-ratio bug) is still ~500Hz. Proves the
     * resampler preserves frequency content, not just "produces the right number of samples".
     */
    @Test
    fun resample_preservesToneFrequency() {
        val freqHz = 500.0
        val durationSeconds = 0.5
        val numInputSamples = (INPUT_RATE * durationSeconds).toInt()
        val input = tone(freqHz, 8000.0, INPUT_RATE, numInputSamples)

        val resampler = Resampler()
        val output = resampler.resample(input)

        val expectedOutputSamples = (OUTPUT_RATE * durationSeconds).toInt()
        // Allow a small tolerance for the couple of samples buffered/held back for interpolation.
        assertTrue(
            "expected ~$expectedOutputSamples output samples, got ${output.size}",
            kotlin.math.abs(output.size - expectedOutputSamples) <= 4
        )

        // Candidates: the real frequency, plus a few wrong-but-plausible ones a buggy resampler
        // could produce (e.g. treating the ratio as 1:1, or an off-by-a-half-ratio bug). Deliberately
        // excludes anything near the Nyquist boundary (8000Hz) or its mirror -- a real-valued
        // signal's spectrum is symmetric around Nyquist, and this Goertzel formula's non-integer-bin
        // leakage near that boundary would make such a candidate an unreliable "wrong answer" probe,
        // not evidence of an actual resampler defect.
        val candidates = doubleArrayOf(freqHz, 1000.0, 250.0, 750.0, 2000.0)
        val dominant = dominantFrequency(output, OUTPUT_RATE, candidates)
        assertTrue(
            "expected dominant frequency to remain ~${freqHz}Hz after resampling, got ${dominant}Hz",
            kotlin.math.abs(dominant - freqHz) < 1.0
        )
    }

    /**
     * Feeds the same 24kHz tone through the resampler as several independently-sized chunks
     * (simulating `streamToSpeaker`'s real chunk-by-chunk delivery) vs. one single call with all
     * the same samples, and asserts the two produce byte-identical output. Proves cross-chunk
     * phase-accumulator + trailing-sample state carries over correctly -- without it, every chunk
     * boundary would introduce a small click or duplicated/dropped sample.
     */
    @Test
    fun resample_chunkedDeliveryMatchesSingleCall() {
        val freqHz = 500.0
        val durationSeconds = 0.5
        val numInputSamples = (INPUT_RATE * durationSeconds).toInt()
        val fullInput = tone(freqHz, 8000.0, INPUT_RATE, numInputSamples)

        val singleCallOutput = Resampler().resample(fullInput)

        // Deliberately awkward, non-uniform, non-divisor chunk sizes.
        val chunkSizes = intArrayOf(1, 7, 13, 100, 999, 337, 50, 1)
        val chunkedResampler = Resampler()
        val chunkedOutput = ArrayList<Short>()
        var offset = 0
        var chunkIdx = 0
        while (offset < fullInput.size) {
            val size = if (chunkIdx < chunkSizes.size) chunkSizes[chunkIdx] else 500
            val end = kotlin.math.min(offset + size, fullInput.size)
            val chunk = fullInput.copyOfRange(offset, end)
            chunkedOutput.addAll(chunkedResampler.resample(chunk).toList())
            offset = end
            chunkIdx++
        }

        assertArrayEquals(
            "chunked delivery must produce identical output to a single call over the same samples",
            singleCallOutput,
            chunkedOutput.toShortArray()
        )
    }

    @Test
    fun resample_emptyChunkProducesEmptyOutput() {
        val resampler = Resampler()
        assertArrayEquals(ShortArray(0), resampler.resample(ShortArray(0)))
    }
}
