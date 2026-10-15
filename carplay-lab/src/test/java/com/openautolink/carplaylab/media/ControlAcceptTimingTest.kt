package com.openautolink.carplaylab.media

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class ControlAcceptTimingTest {
    @Test fun physicalAcceptTimestampPrecedesBlockedAsynchronousReporting() {
        val format=android.media.MediaFormat.createVideoFormat("video/avc",1280,720)
        val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format).setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                profile=android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level=android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("test.avc.decoder").setIsEncoder(false).setCapabilities(caps).build())
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val context=RuntimeEnvironment.getApplication() as Context
        java.io.File(context.noBackupFilesDir,"pairing-probe").deleteRecursively()
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val reported=CountDownLatch(1)
        val receipt=AtomicReference<String>()
        val receiver=ProjectionReceiver(context,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java),
            wired=true, onStatus={ message ->
                if (message.startsWith("BENCH_DIAG displayOffer")) { entered.countDown(); release.await(5,TimeUnit.SECONDS) }
                if (message.startsWith("CarPlay network stage=control-accepted")) { receipt.set(message); reported.countDown() }
            })
        // Measure API32 Robolectric's actual accepted peer; never relax production admission.
        val ip=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .first { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
        val peer=ServerSocket(0).use { probe -> Socket(ip,probe.localPort).use { client ->
            client.getOutputStream().write(42)
            probe.accept().use { accepted -> assertEquals(42,accepted.getInputStream().read()); accepted.inetAddress.hostAddress!! }
        } }
        try {
            receiver.setRuntimeIdentification(Iap2IdentificationConfig("test","test","test","private","1","1",1))
            receiver.start(peer)
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val before=System.nanoTime()
            Socket(ip,receiver.port).use { phone ->
                phone.soTimeout=3000
                // A real response certifies accept/session-start while status delivery is blocked.
                phone.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                assertEquals('R'.code,phone.getInputStream().read())
                val beforeRelease=System.nanoTime()
                assertNull(receipt.get())
                release.countDown()
                assertTrue(reported.await(3,TimeUnit.SECONDS))
                val stamp=Regex("acceptedMonoNs=(\\d+)").find(receipt.get())?.groupValues?.get(1)?.toLong()
                assertNotNull("physical accept must carry a monotonic timestamp, not reporting time",stamp)
                assertTrue(stamp!! in before..beforeRelease)
            }
        } finally { release.countDown(); receiver.close(); assertTrue(receiver.awaitClosed(10000)) }
    }
}
