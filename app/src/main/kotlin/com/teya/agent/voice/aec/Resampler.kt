package com.teya.agent.voice.aec

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Streaming 24kHz -> 16kHz PCM16 mono decimator (fixed 3:2 ratio), via linear interpolation at the
 * exact 2/3 resampling positions -- chosen over a FIR decimator as the simplest correct method for
 * this one fixed ratio (see `2026-07-09-webrtc-aec3-voicepipeline-integration.md`, Phase 1:
 * `NativeAec3` needs a reasonably faithful *farend reference* for its adaptive filter to track, not
 * broadcast-quality audio, so interpolation error at this ratio is not a real concern).
 *
 * Deliberately **not** a general-purpose resampler -- no arbitrary sample-rate support, per the
 * plan's "What We're NOT Doing". No Android dependency: pure JVM-testable math.
 *
 * ## Cross-chunk state
 * `streamToSpeaker` delivers TTS audio as independent network-chunk callbacks whose lengths don't
 * evenly divide the 3:2 ratio. Without carried state, every chunk boundary would introduce a small
 * click or duplicated/dropped sample. This class carries two pieces of state across [resample]
 * calls:
 * - [buffer]: the tail of previously-seen input samples not yet fully consumed (at most a couple of
 *   samples in steady state -- interpolation always needs one sample *past* the last output
 *   position, which may not have arrived yet within the current chunk).
 * - [pos]: the fractional index (into [buffer]) of the *next* output sample.
 *
 * Each call conceptually processes `combined = buffer + input` as a continuing slice of one
 * infinite virtual input stream: output sample `i`'s position in that stream advances by exactly
 * `INPUT_RATE_HZ / OUTPUT_RATE_HZ` (= 1.5) samples from output sample `i - 1`, matching downsampling
 * 24000 samples of input to 16000 samples of output per second. This makes chunking invisible to
 * the math -- feeding the same total samples via many small calls vs. one big call produces
 * identical output (see `ResamplerTest`), because `pos` is only ever adjusted by exact integer
 * subtraction (no floating-point drift) when shifting to the next call's local index space.
 */
class Resampler {

    companion object {
        const val INPUT_RATE_HZ = 24000
        const val OUTPUT_RATE_HZ = 16000

        // Step, in input samples, between successive output samples: 24000/16000 = 1.5.
        private const val STEP = INPUT_RATE_HZ.toDouble() / OUTPUT_RATE_HZ.toDouble()
    }

    // Unconsumed tail of previously-seen input, prepended to the next chunk. Empty until the first
    // call needs to carry state past a chunk boundary.
    private var buffer: ShortArray = ShortArray(0)

    // Fractional index into `buffer` (index-space of the *next* concatenation, i.e. `buffer + input`
    // for the upcoming call) of the next output sample to produce.
    private var pos: Double = 0.0

    /**
     * Resamples one chunk of 24kHz PCM16 mono [input] to 16kHz PCM16 mono, carrying fractional
     * position + trailing-sample state forward for the next call. Safe to call with
     * independently-sized chunks (including very short ones); state accumulates correctly across
     * calls either way.
     */
    fun resample(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        val combined = ShortArray(buffer.size + input.size)
        buffer.copyInto(combined)
        input.copyInto(combined, buffer.size)

        val output = ArrayList<Short>(combined.size * OUTPUT_RATE_HZ / INPUT_RATE_HZ + 2)
        var p = pos
        while (true) {
            val idx = floor(p).toInt()
            if (idx + 1 >= combined.size) break // not enough data yet to interpolate further
            val frac = p - idx
            val s0 = combined[idx].toDouble()
            val s1 = combined[idx + 1].toDouble()
            val interpolated = s0 + frac * (s1 - s0)
            output.add(
                interpolated.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )
            p += STEP
        }

        // Carry forward: keep the tail starting at the next unconsumed position (an exact integer
        // number of samples, so `p -= leftoverStart` introduces no floating-point drift), so the
        // next call's combined array continues the same virtual stream seamlessly.
        val leftoverStart = floor(p).toInt().coerceIn(0, combined.size)
        buffer = combined.copyOfRange(leftoverStart, combined.size)
        pos = p - leftoverStart

        return output.toShortArray()
    }
}
