package com.openautolink.carplaylab.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayReturnTileTest {
    private fun field(receiver: Any, name: String) = receiver.javaClass.getDeclaredField(name).apply { isAccessible=true }
    private fun receiver(): ProjectionReceiver {
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52 })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("tile.avc").setIsEncoder(false).setCapabilities(caps).build())
        val surface=mock(Surface::class.java)
        val app=RuntimeEnvironment.getApplication()
        java.io.File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        return ProjectionReceiver(app,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java),wired=true,onStatus={})
    }
    private fun admit(receiver: ProjectionReceiver, session: AirPlaySession) {
        `when`(session.controlAuthenticated).thenReturn(true)
        @Suppress("UNCHECKED_CAST")
        (field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        field(receiver,"active").set(receiver,session)
    }
    private fun listener(receiver: ProjectionReceiver) = field(receiver,"listener").get(receiver) as AirPlaySessionListener
    private fun install(receiver: ProjectionReceiver, handler: (() -> Boolean) -> Unit) {
        val method=receiver.javaClass.methods.firstOrNull { it.name=="setHostUiHandler" }
        assertNotNull("receiver must deliver exact-session host UI requests",method)
        method!!.invoke(receiver,handler)
    }
    @Test fun unauthenticatedForeignClosedAndRevokedSessionsCannotNavigate() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        var count=0;var receipt: (() -> Boolean)?=null
        try {
            install(receiver) { count++;receipt=it }
            listener(receiver).onHostUiRequested(mock(AirPlaySession::class.java))
            `when`(session.controlAuthenticated).thenReturn(false)
            listener(receiver).onHostUiRequested(session)
            `when`(session.controlAuthenticated).thenReturn(true)
            `when`(session.isClosed).thenReturn(true)
            listener(receiver).onHostUiRequested(session)
            assertEquals(0,count)
            `when`(session.isClosed).thenReturn(false)
            listener(receiver).onHostUiRequested(session)
            assertEquals(1,count);assertTrue(receipt!!.invoke())
            receiver.revokePendingAdmission()
            assertFalse(receipt!!.invoke())
            listener(receiver).onHostUiRequested(session)
            assertEquals(1,count)
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun productionReceiverPublishesOalPngAndAccurateDimensionsInInfo() {
        val receiver=receiver()
        try {
            val method=receiver.javaClass.declaredMethods.firstOrNull { it.name=="sessionConfig" }
            assertNotNull("production /info needs OAL branding",method)
            val config=method!!.apply { isAccessible=true }.invoke(receiver) as AirPlayConfig
            val info=AirPlayInfoPlist.build(config,emptySet())
            assertEquals(true,info["oemIconVisible"])
            assertEquals("OpenAutoLink",info["oemIconLabel"])
            val icon=(info["oemIcons"] as List<*>).single() as Map<*,*>
            val data=icon["imageData"] as ByteArray
            assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),data.copyOfRange(0,8))
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            assertEquals(192,bounds.outWidth);assertEquals(192,bounds.outHeight)
            assertEquals(bounds.outWidth,icon["widthPixels"]);assertEquals(bounds.outHeight,icon["heightPixels"])
            assertEquals(true,icon["prerendered"])
            val roundTrip=BplistCodec.decode(BplistCodec.encode(info)) as Map<*,*>
            val restored=(roundTrip["oemIcons"] as List<*>).single() as Map<*,*>
            assertArrayEquals(data,restored["imageData"] as ByteArray)
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun authenticatedCurrentRequestRetainsValidityUntilUiDeliveryWithoutClosingSession() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        var delivery: (() -> Boolean)?=null
        try {
            install(receiver) { delivery=it }
            listener(receiver).onHostUiRequested(session)
            assertNotNull("authenticated return tile must reach host",delivery)
            assertTrue(delivery!!.invoke())
            verify(session,never()).close()
            field(receiver,"active").set(receiver,mock(AirPlaySession::class.java))
            assertFalse("posted predecessor cannot minimize for successor",delivery!!.invoke())
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
}
