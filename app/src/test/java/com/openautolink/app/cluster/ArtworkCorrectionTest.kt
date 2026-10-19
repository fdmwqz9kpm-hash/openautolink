package com.openautolink.app.cluster

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.car.app.CarContext
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.openautolink.app.R
import com.openautolink.app.navigation.*
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkCorrectionTest {
    private val context=mockk<CarContext>(relaxed=true).also { c ->
        val app=RuntimeEnvironment.getApplication()
        every { c.resources } returns app.resources
        every { c.packageName } returns app.packageName
    }
    private fun png(width:Int=12,height:Int=9):String {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val out=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
    private fun state(type:ManeuverType,image:String?=null,exit:Int?=null)=
        ManeuverState(type,100,"100 m","Road",null,navImageBase64=image,roundaboutExitNumber=exit)
    @Test fun currentArtworkPreservedWithoutReplacingNamedSemantics() {
        val model=buildManeuver(state(ManeuverType.TURN_LEFT,png()),context)
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT,model.type)
        val icon=requireNotNull(model.icon?.icon)
        assertEquals(IconCompat.TYPE_BITMAP,icon.type)
        val bitmap=requireNotNull(icon.bitmap)
        assertEquals(12,bitmap.width)
        assertEquals(9,bitmap.height)
        assertEquals(Color.CYAN,bitmap.getPixel(3,3))
    }
    @Test fun genericRoundaboutUsesNeutralFallbackAndUnknownHasNoFabricatedVector() {
        for(type in listOf(ManeuverType.ROUNDABOUT_ENTER,ManeuverType.ROUNDABOUT_EXIT,ManeuverType.ROUNDABOUT_ENTER_AND_EXIT)) {
            val model=buildManeuver(state(type),context)
            assertEquals(Maneuver.TYPE_UNKNOWN,model.type)
            assertEquals(R.drawable.nav_roundabout_neutral,model.icon!!.icon!!.resId)
        }
        assertNull(buildManeuver(state(ManeuverType.UNKNOWN),context).icon)
        assertEquals(IconCompat.TYPE_BITMAP,buildManeuver(state(ManeuverType.UNKNOWN,png()),context).icon!!.icon!!.type)
    }
    @Test fun bitmapDoesNotBypassCombinedRoundaboutRequirements() {
        for(exit in listOf(null,0,-1)) {
            val model=buildManeuver(state(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW,png(),exit),context)
            assertEquals(Maneuver.TYPE_UNKNOWN,model.type)
            assertEquals(0,model.roundaboutExitNumber)
            assertEquals(IconCompat.TYPE_BITMAP,model.icon!!.icon!!.type)
        }
    }
    @Test fun invalidAndOversizedArtworkFallBackToKnownVector() {
        for(image in listOf("not bitmap",Base64.encodeToString(byteArrayOf(1,2,3),Base64.NO_WRAP),"A".repeat(1500000),png(1025,2))) {
            val model=buildManeuver(state(ManeuverType.TURN_LEFT,image),context)
            assertEquals(R.drawable.nav_turn_left,model.icon!!.icon!!.resId)
        }
    }
}
