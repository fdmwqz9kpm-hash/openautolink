package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayParkRecordTest {
    @Test fun explicitIndependentCarPlayParkChoiceSurvivesRecordRoundTrip() {
        val record="{\"enabled\":true,\"alwaysInPark\":true,\"drivingRestrictionsEnabled\":true}"
        val encoded=CarPlaySettings.encode(CarPlaySettings.decode(record))
        assertTrue("explicit CarPlay Park choice lost: $encoded",encoded.contains("\"alwaysInPark\":true"))
        assertTrue(encoded.contains("\"drivingRestrictionsEnabled\":true"))
    }
}
