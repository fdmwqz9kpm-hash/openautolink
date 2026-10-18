package com.openautolink.app.navigation

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ManeuverWireSafetyTest {
    @Test fun unspecifiedTurnSideCannotChooseLeft() {
        for (wire in listOf("turn", "slight_turn", "sharp_turn", "u_turn", "on_ramp", "off_ramp", "fork", "merge", "16", "17", "future_type"))
            assertEquals(wire, ManeuverType.UNKNOWN, ManeuverMapper.fromWire(wire))
    }
    @Test fun allModernNativeVocabularyIsRecognizedWithoutLosingAngleRequirements() {
        val source = File("src/main/cpp/jni_channel_handlers.cpp").readText()
        val mapper = source.substringAfter("static std::string maneuverTypeToString").substringBefore("// Helper: map LaneDirection")
        val values = Regex("case \\d+:\\s+return \"([^\"]+)\"").findAll(mapper).map { it.groupValues[1] }.toList()
        assertEquals(43, values.size)
        values.filter { it != "unknown" }.forEach { wire -> assertNotEquals(wire, ManeuverType.UNKNOWN, ManeuverMapper.fromWire(wire)) }
    }
    @Test fun nativeLegacyFerriesAreMappedAndCombinedRoundaboutCannotGuessRotation() {
        val source = File("src/main/cpp/jni_channel_handlers.cpp").readText()
            .substringAfter("void JniNavStatusHandler::onTurnEvent").substringBefore("void JniNavStatusHandler::onDistanceEvent")
        assertTrue(source.contains("case 16: maneuver = \"ferry\""))
        assertTrue(source.contains("case 17: maneuver = \"ferry_train\""))
        assertEquals(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT,ManeuverMapper.fromWire("roundabout_enter_and_exit"))
        assertTrue(source.contains("has_turn_number()"))
        assertTrue(source.contains("dispatchNavFullState("))
    }
}
