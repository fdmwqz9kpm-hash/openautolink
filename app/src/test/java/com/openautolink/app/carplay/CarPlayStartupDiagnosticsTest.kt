package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayStartupDiagnosticsTest {
    private val lines=listOf(
        "NCM startup attempt=13 timeoutMs=20 transferred=31 blockBytes=31 ready=1 writeStartMonoNs=123456789 writeElapsedUs=20123 sinceOpenUs=123456",
        "CarPlay USB stage=endpoint result=written requestMonoNs=123456789 writtenReceiptMonoNs=123456999 writeAwaitElapsedUs=210",
        "CarPlay network stage=control-accepted authentication=pending acceptedMonoNs=123456789")

    @Test fun permitsOnlyTheNumericStartupReceiptSchemas() {
        lines.forEach { assertEquals("CARPLAY_AUDIO session=1 $it",CarPlayAudioDiagnostics.sanitize(1,it)) }
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,lines[0].replace("transferred=31","transferred=-1")))
    }
    @Test fun rejectsPayloadAddressKeysUnknownFieldsAndUnboundedNumbers() {
        lines.forEach { line ->
            assertNull(CarPlayAudioDiagnostics.sanitize(1,"$line key=secret"))
            assertNull(CarPlayAudioDiagnostics.sanitize(1,"$line\nsecret"))
            assertNull(CarPlayAudioDiagnostics.sanitize(1,line.replace("123456789","fe80::1")))
            assertNull(CarPlayAudioDiagnostics.sanitize(1,line.replace("123456789","1234567890123456789012345")))
        }
    }
}
