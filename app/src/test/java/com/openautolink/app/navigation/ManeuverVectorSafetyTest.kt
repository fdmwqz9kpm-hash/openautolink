package com.openautolink.app.navigation

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManeuverVectorSafetyTest {
    private val drawable = File("src/main/res/drawable")
    private fun geometry(id: Int): String {
        val fields = com.openautolink.app.R.drawable::class.java.fields
        val name = fields.single { it.getInt(null) == id }.name
        val file = File(drawable, "$name.xml")
        assertTrue("Missing $name", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        assertEquals("vector", doc.documentElement.tagName)
        val paths = doc.getElementsByTagName("path")
        assertTrue(name, paths.length > 0)
        return file.readText().replace(Regex("\\s+"), " ")
    }
    @Test fun directionalPairsNeverShareAnIcon() {
        val pairs = listOf(
            ManeuverType.U_TURN_LEFT to ManeuverType.U_TURN_RIGHT,
            ManeuverType.ON_RAMP_LEFT to ManeuverType.ON_RAMP_RIGHT,
            ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW to ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            ManeuverType.TURN_LEFT to ManeuverType.TURN_SLIGHT_LEFT,
            ManeuverType.TURN_LEFT to ManeuverType.TURN_SHARP_LEFT,
            ManeuverType.ON_RAMP_LEFT to ManeuverType.ON_RAMP_SHARP_LEFT,
            ManeuverType.OFF_RAMP_LEFT to ManeuverType.OFF_RAMP_SLIGHT_LEFT,
            ManeuverType.FERRY to ManeuverType.FERRY_TRAIN)
        for ((left, right) in pairs) {
            val a = ManeuverIconRenderer.drawableForManeuver(left)
            val b = ManeuverIconRenderer.drawableForManeuver(right)
            assertNotEquals("$left / $right", a, b)
            assertNotEquals(geometry(a), geometry(b))
        }
    }
    @Test fun everyNamedTypeHasAnExistingParseableVector() {
        for (type in ManeuverType.entries) geometry(ManeuverIconRenderer.drawableForManeuver(type))
    }
}
