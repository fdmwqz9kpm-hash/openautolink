package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneDiagnosticStatisticsTest {
    @Test fun signedInterleavedSamplesPreserveChannelsAndWideSumDifference() {
        val type = runCatching { Class.forName("com.shilapi.xcertplay.media.MicrophoneDiagnosticAccumulator") }.getOrNull()
        assertNotNull("numeric accumulator must exist", type)
        val accumulator = type!!.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(2)
        val add = type.getMethod("add", ShortArray::class.java, Int::class.javaPrimitiveType)
        // Split mid-frame: interleaving must survive read boundaries; no Int square overflow.
        add.invoke(accumulator, shortArrayOf(-32768,32767,-32768),3)
        add.invoke(accumulator, shortArrayOf(-32768,0,0),3)
        val s = type.getMethod("snapshot").invoke(accumulator) as MicrophoneDiagnosticStatistics
        assertEquals(3L,s.frames); assertEquals(2L,s.nonzeroFrames)
        assertEquals(32768,s.channels[0].peak);assertEquals(32768,s.channels[1].peak)
        assertEquals(2L,s.channels[0].nonzero)
        assertEquals(65536,s.sum!!.peak);assertEquals(65535,s.difference!!.peak)
        assertEquals(32768,s.mean!!.peak)
        assertEquals(kotlin.math.sqrt((1.0+65536.0*65536)/3),s.sum.rms,0.0001)
        assertEquals(kotlin.math.sqrt(65535.0*65535/3),s.difference.rms,0.0001)
    }
}
