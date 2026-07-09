package com.teya.agent.voice.aec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3b smoke test: proves the real JNI glue (NDK -> CMake -> teya_aec3_core -> teya_aec3 ->
 * JNI -> Kotlin) runs end-to-end on-device without crashing and produces sane output for a
 * trivial input — NOT a correctness test of actual echo cancellation (that's Phase 4's
 * `NativeAec3EchoCancellationTest`, with real synthetic echo/speech signals).
 *
 * Deliberately asserts more than "no crash + right length": a silent render/capture frame in
 * should also come back near-silent, not NaN or garbage — a length-only check would still pass
 * if `processCapture` returned e.g. uninitialized buffer contents.
 */
@RunWith(AndroidJUnit4::class)
class NativeAec3SmokeTest {

    companion object {
        // Comfortably above float rounding noise, comfortably below "this looks like garbage" —
        // int16 range is +/-32768; 100 is well under 1% of full scale.
        private const val NEAR_SILENT_THRESHOLD = 100
    }

    @Test
    fun silentFramesInProduceSaneSilentOutput() {
        val aec3 = NativeAec3()
        try {
            val silentRender = ShortArray(NativeAec3.FRAME_SIZE) // all zero
            val silentCapture = ShortArray(NativeAec3.FRAME_SIZE) // all zero

            aec3.analyzeRender(silentRender)
            val cleaned = aec3.processCapture(silentCapture)

            assertEquals(NativeAec3.FRAME_SIZE, cleaned.size)
            cleaned.forEach { sample ->
                assertTrue(
                    "expected near-silent output for silent input, got sample=$sample",
                    kotlin.math.abs(sample.toInt()) < NEAR_SILENT_THRESHOLD
                )
            }
        } finally {
            aec3.close()
        }
    }
}
