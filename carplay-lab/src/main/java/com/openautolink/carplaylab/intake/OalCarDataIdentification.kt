package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

object OalCarDataIdentification {
    /** Transform only 1D01. Freeze [capabilities] for this link and pass the same value to its reporter. */
    fun apply(base: Iap2Frame, capabilities: OalCarDataCapabilities): Iap2Frame {
        require(base.messageId == 0x1D01)
        val reader = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(base)
        val vehicle = capabilities.range || capabilities.currentEnergy || capabilities.maximumEnergy || capabilities.outsideTemperature
        val planeSent = setOf(0xFFFB,0xA101)
        val planeReceived = setOf(0xFFFA,0xFFFC,0xA100,0xA102)
        val sent = reader.u16List(6).filterNot { it in planeSent }.toMutableList()
        val received = reader.u16List(7).filterNot { it in planeReceived }.toMutableList()
        if (capabilities.location) { sent += 0xFFFB; received += listOf(0xFFFA,0xFFFC) }
        if (vehicle) { sent += 0xA101; received += listOf(0xA100,0xA102) }
        return com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x1D01) {
            reader.list().filterNot { it.id in setOf(6,7,20,21,22) }.forEach { raw(it.id,it.payload) }
            u16List(6,sent.distinct()); u16List(7,received.distinct())
            if (vehicle) {
                group(20) {
                    u16(0,3); string(1,"OAL vehicle"); u8(2,2)
                    capabilities.vehicleDisplayName?.let { string(6,it); string(8,it) }
                    for (connector in listOf(0,1,2,4,5,6)) {
                        if (capabilities.chargingConnectorMask and (1 shl connector) != 0) u8(11,connector)
                    }
                }
                group(21) {
                    u16(0,4); string(1,"OAL vehicle")
                    if (capabilities.range) { void(3); void(11) }
                    if (capabilities.outsideTemperature) void(4)
                    if (capabilities.currentEnergy || capabilities.maximumEnergy) void(21)
                }
            }
            if (capabilities.location) {
                group(22) { u16(0,0); string(1,"OAL location"); void(17); void(18) }
            }
        }
    }
}
