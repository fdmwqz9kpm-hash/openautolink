package com.openautolink.app.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManeuverVectorRenderTest {
    private fun render(type: ManeuverType): Bitmap {
        val icon = requireNotNull(ContextCompat.getDrawable(RuntimeEnvironment.getApplication(),
            ManeuverIconRenderer.drawableForManeuver(type)))
        return Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).also {
            icon.setBounds(0,0,128,128)
            icon.draw(Canvas(it))
        }
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }
    @Test fun allResourcesReallyRenderAndNamedDirectionalGraphicsAreDistinct() {
        val rendered = ManeuverType.entries.associateWith { render(it) }
        for ((type, bitmap) in rendered) assertTrue("Empty vector $type", pixels(bitmap).any { Color.alpha(it)>0 })
        val exceptions = setOf(ManeuverType.UNKNOWN, ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
            ManeuverType.ROUNDABOUT_ENTER_AND_EXIT)
        val hashes = rendered.filterKeys { it !in exceptions }.mapValues { pixels(it.value).contentHashCode() }
        assertEquals("Every supported named geometry is distinct", hashes.size, hashes.values.toSet().size)
        val sheet = Bitmap.createBitmap(1000, ((rendered.size + 4) / 5) * 178, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(18,24,32))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=10f }
        rendered.entries.forEachIndexed { i, entry ->
            val x = (i % 5) * 200
            val y = (i / 5) * 178
            canvas.drawBitmap(entry.value,x+36f,y+4f,null)
            canvas.drawText(entry.key.name,x+4f,y+152f,paint)
        }
        val output = File("build/reports/maneuver-contact-sheet.png")
        output.parentFile.mkdirs()
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun arrowheadsAndApproachStemsHaveTheNamedOrientation() {
        fun ink(type: ManeuverType,x: Int,y: Int) = Color.alpha(render(type).getPixel(x*2,y*2)) > 0
        assertTrue(ink(ManeuverType.TURN_LEFT,10,20))
        assertTrue(ink(ManeuverType.TURN_LEFT,44,55))
        assertTrue(ink(ManeuverType.TURN_RIGHT,54,20))
        assertTrue(ink(ManeuverType.TURN_RIGHT,20,55))
        assertTrue(ink(ManeuverType.U_TURN_LEFT,46,55))
        assertTrue(ink(ManeuverType.U_TURN_LEFT,18,43))
        assertTrue(ink(ManeuverType.U_TURN_RIGHT,18,55))
        assertTrue(ink(ManeuverType.U_TURN_RIGHT,46,43))
        assertTrue(ink(ManeuverType.ON_RAMP_LEFT,50,55))
        assertTrue(ink(ManeuverType.ON_RAMP_RIGHT,14,55))
        assertTrue(ink(ManeuverType.OFF_RAMP_LEFT,8,24))
        assertTrue(ink(ManeuverType.OFF_RAMP_RIGHT,56,24))
        assertTrue(ink(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW,54,24))
        assertFalse(ink(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW,10,24))
        assertTrue(ink(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW,10,24))
        assertFalse(ink(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW,54,24))
    }
    @Test fun mirroredPairsArePixelReflectionsNotIdenticalArrows() {
        val pairs = listOf("TURN", "TURN_SLIGHT", "TURN_SHARP", "U_TURN", "KEEP", "MERGE", "FORK",
            "ON_RAMP", "ON_RAMP_SLIGHT", "ON_RAMP_SHARP", "ON_RAMP_U_TURN", "OFF_RAMP", "OFF_RAMP_SLIGHT", "DESTINATION")
        for (prefix in pairs) {
            val left = render(ManeuverType.valueOf(prefix+"_LEFT"))
            val right = render(ManeuverType.valueOf(prefix+"_RIGHT"))
            assertFalse(prefix, pixels(left).contentEquals(pixels(right)))
            var alphaDifference = 0L
            for (y in 0 until 128) for (x in 0 until 128)
                alphaDifference += kotlin.math.abs(Color.alpha(left.getPixel(x,y))-Color.alpha(right.getPixel(127-x,y)))
            // Anti-aliasing differs by a few alpha units at the reflected edges.
            assertTrue("$prefix reflection mismatch: $alphaDifference",alphaDifference < 12000)
        }
    }
}
