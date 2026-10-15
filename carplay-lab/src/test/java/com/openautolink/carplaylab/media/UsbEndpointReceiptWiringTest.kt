package com.openautolink.carplaylab.media

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring-only guard; actual link completion/backpressure is exercised by Iap2RuntimeWrittenTest. */
class UsbEndpointReceiptWiringTest {
    @Test fun wiredStartupUsesExactWrittenSeamBeforeClaimingWrittenAndPublishesNumericTiming() {
        val source=File("../app/src/main/java/com/openautolink/app/carplay/OalUsbCarPlayRuntime.kt").readText()
        val endpoint=source.substringAfter("if(frame.messageId==0x4300) {").substringBefore("\n            }")
        assertTrue("endpoint must use existing exact written seam",endpoint.contains("csm.sendAndAwaitWritten("))
        assertFalse(endpoint.contains("result=sent"))
        assertTrue(endpoint.contains("result=written"))
        assertTrue(endpoint.contains("requestMonoNs="))
        assertTrue(endpoint.contains("writtenReceiptMonoNs="))
        assertTrue(endpoint.contains("writeAwaitElapsedUs="))
        assertTrue(source.contains("NcmUsbBridge.open(ncmConnection,function,onDiagnostic="))
    }
}
