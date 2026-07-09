package com.teya.agent.voice.aec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 toolchain proof: loads `libteya_aec3.so` on-device and calls its trivial [NativeAec3.ping]
 * passthrough, confirming NDK -> CMake -> .so -> JNI -> Kotlin actually works end-to-end before any
 * real AEC3 DSP code is compiled (Phase 3a/3b).
 */
@RunWith(AndroidJUnit4::class)
class NativeAec3PingTest {

    @Test
    fun ping_returnsSentinelValue() {
        val nativeAec3 = NativeAec3()
        assertEquals(42, nativeAec3.ping())
    }
}
