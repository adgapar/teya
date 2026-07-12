package com.teya.agent.voice.speaker

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Kaldi-compatible 80-dim log-mel filterbank extractor. Original implementation against Kaldi's
 * own published algorithm (`feature-window.cc`/`mel-computations.cc`), not a port of any
 * third-party wrapper -- see THIRD_PARTY_MODELS.md. Exact config matches what
 * `speaker_embedding.onnx` (CAM++, English VoxCeleb checkpoint) expects, reverse-engineered from
 * sherpa-onnx's `FeatureExtractorConfig` defaults and verified numerically against the reference
 * Python `kaldi_native_fbank` output (see `FbankTest`): 16kHz, 25ms/10ms framing (povey window,
 * `snip_edges=false` with edge reflection), dither=0, preemphasis 0.97, remove-DC, 80 mel bins
 * from 20Hz to (Nyquist - 400Hz).
 *
 * Stateless, deterministic, pure Kotlin -- no Android/ONNX dependency, so it's independently
 * testable as a plain JVM unit test (`FbankTest`).
 */
class Fbank(
    private val sampleRate: Int = SAMPLE_RATE,
    private val numMelBins: Int = NUM_MEL_BINS,
    private val lowFreq: Double = LOW_FREQ,
    private val highFreq: Double = sampleRate / 2.0 - HIGH_FREQ_OFFSET,
    private val frameLengthMs: Double = FRAME_LENGTH_MS,
    private val frameShiftMs: Double = FRAME_SHIFT_MS,
    private val preemphCoeff: Double = PREEMPH_COEFF,
) {
    private val frameLength = ((sampleRate * frameLengthMs) / 1000.0).toInt()
    private val frameShift = ((sampleRate * frameShiftMs) / 1000.0).toInt()
    private val fftSize = nextPowerOfTwo(frameLength)
    private val window = poveyWindow(frameLength)
    private val melFilters = melFilterbank(numMelBins, fftSize, sampleRate, lowFreq, highFreq)

    /** Returns one row of [numMelBins] log-mel energies per frame. Input: float32 samples in [-1, 1]. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val numFrames = numFrames(samples.size)
        return Array(numFrames) { frameIndex -> computeFrame(samples, frameIndex) }
    }

    private fun numFrames(numSamples: Int): Int {
        if (numSamples <= 0) return 0
        return (numSamples + frameShift / 2) / frameShift
    }

    private fun computeFrame(samples: FloatArray, frameIndex: Int): FloatArray {
        val frame = extractWindow(samples, frameIndex)

        // remove DC offset
        val mean = frame.average()
        for (i in frame.indices) frame[i] -= mean

        // preemphasis (Kaldi convention: sample[0] uses itself as its own predecessor)
        for (i in frame.size - 1 downTo 1) frame[i] -= preemphCoeff * frame[i - 1]
        frame[0] -= preemphCoeff * frame[0]

        // apply window function
        for (i in frame.indices) frame[i] *= window[i]

        // zero-pad to FFT size, compute power spectrum (one-sided, fftSize/2 + 1 bins)
        val padded = DoubleArray(fftSize)
        System.arraycopy(frame, 0, padded, 0, frame.size)
        val powerSpectrum = Fft.powerSpectrum(padded)

        // apply mel filterbank + log with an epsilon floor
        val out = FloatArray(numMelBins)
        for (bin in 0 until numMelBins) {
            var energy = 0.0
            val filter = melFilters[bin]
            for (i in filter.indices) energy += filter[i] * powerSpectrum[i]
            out[bin] = ln(max(energy, MEL_ENERGY_FLOOR)).toFloat()
        }
        return out
    }

    private fun extractWindow(samples: FloatArray, frameIndex: Int): DoubleArray {
        val numSamples = samples.size
        val midpoint = frameShift * frameIndex + frameShift / 2
        val start = midpoint - frameLength / 2
        val out = DoubleArray(frameLength)
        for (s in 0 until frameLength) {
            var idx = start + s
            // reflect at both boundaries (Kaldi's snip_edges=false convention)
            while (idx < 0 || idx >= numSamples) {
                idx = if (idx < 0) -idx - 1 else 2 * numSamples - 1 - idx
            }
            out[s] = samples[idx].toDouble()
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val NUM_MEL_BINS = 80
        const val LOW_FREQ = 20.0
        const val HIGH_FREQ_OFFSET = 400.0 // high_freq = Nyquist - this
        const val FRAME_LENGTH_MS = 25.0
        const val FRAME_SHIFT_MS = 10.0
        const val PREEMPH_COEFF = 0.97
        private const val MEL_ENERGY_FLOOR = 1.1920928955078125e-7 // Float.MIN_VALUE-ish epsilon, matches Kaldi

        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        private fun poveyWindow(length: Int): DoubleArray = DoubleArray(length) { i ->
            Math.pow(0.5 - 0.5 * cos(2.0 * PI * i / (length - 1)), 0.85)
        }

        private fun melScale(freqHz: Double): Double = 1127.0 * ln(1.0 + freqHz / 700.0)

        /** One triangular filter (over the fftSize/2+1 one-sided power-spectrum bins) per mel bin. */
        private fun melFilterbank(
            numBins: Int,
            fftSize: Int,
            sampleRate: Int,
            lowFreq: Double,
            highFreq: Double,
        ): Array<DoubleArray> {
            val numFftBins = fftSize / 2 + 1
            val melLow = melScale(lowFreq)
            val melHigh = melScale(highFreq)
            val melDelta = (melHigh - melLow) / (numBins + 1)

            return Array(numBins) { bin ->
                val leftMel = melLow + bin * melDelta
                val centerMel = melLow + (bin + 1) * melDelta
                val rightMel = melLow + (bin + 2) * melDelta
                val filter = DoubleArray(numFftBins)
                for (i in 0 until numFftBins) {
                    val freqHz = i.toDouble() * sampleRate / fftSize
                    val mel = melScale(freqHz)
                    if (mel > leftMel && mel < rightMel) {
                        filter[i] = if (mel <= centerMel) {
                            (mel - leftMel) / (centerMel - leftMel)
                        } else {
                            (rightMel - mel) / (rightMel - centerMel)
                        }
                    }
                }
                filter
            }
        }
    }
}

/** Minimal iterative radix-2 Cooley-Tukey FFT -- just enough for [Fbank]'s power spectrum. */
internal object Fft {
    /** Returns the one-sided power spectrum (`size/2 + 1` bins) of a real, zero-padded signal. */
    fun powerSpectrum(real: DoubleArray): DoubleArray {
        val n = real.size
        val re = real.copyOf()
        val im = DoubleArray(n)
        fft(re, im)
        val numBins = n / 2 + 1
        return DoubleArray(numBins) { i -> re[i] * re[i] + im[i] * im[i] }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        if (n <= 1) return

        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
