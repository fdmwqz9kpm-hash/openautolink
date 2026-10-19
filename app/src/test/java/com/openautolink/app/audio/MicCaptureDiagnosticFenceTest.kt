package com.openautolink.app.audio

import com.shilapi.xcertplay.media.MicrophoneCaptureFence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=android.app.Application::class)
class MicCaptureDiagnosticFenceTest {
    @Test fun diagnosticReservationPreventsNormalAAWorkerAdmission() {
        val lease=MicrophoneCaptureFence.tryDiagnostic()!!
        val manager=MicCaptureManager {}
        val unblock=CountDownLatch(1)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                while(unblock.count>0) try { unblock.await() } catch (_: InterruptedException) {}
                return -1
            }
        })
        try {
            manager.start(16000)
            val capturing=MicCaptureManager::class.java.getDeclaredField("capturing").apply { isAccessible=true }.get(manager) as AtomicBoolean
            assertFalse("AA must refuse capture while diagnostic owns fence",capturing.get())
        } finally { manager.stop();unblock.countDown();lease.release();ShadowAudioRecord.clearSource() }
    }
    @Test fun interruptedAAReadKeepsReservationUntilActualRecorderRelease() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                entered.countDown()
                while(unblock.count>0) try { unblock.await() } catch (_: InterruptedException) {}
                return -1
            }
        })
        val manager=MicCaptureManager {}
        try {
            manager.start(16000);assertTrue(entered.await(3,TimeUnit.SECONDS));manager.stop()
            val wrong=MicrophoneCaptureFence.tryDiagnostic()
            try { assertNull("AA stop interrupt alone must not retire capture fence",wrong) }
            finally { wrong?.release() }
        } finally { manager.stop();unblock.countDown() }
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        var next: MicrophoneCaptureFence.Lease?=null
        while(next==null && System.nanoTime()<until) { next=MicrophoneCaptureFence.tryDiagnostic();if(next==null) Thread.sleep(5) }
        try { assertNotNull("AA recorder release acknowledges reservation",next) }
        finally { next?.release();ShadowAudioRecord.clearSource() }
    }
}
