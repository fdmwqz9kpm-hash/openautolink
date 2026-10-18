package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.carplay.CarPlayDrivingRestrictionBridge
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class CarPlayGearSourceValidityTest {
    class Config { fun getChangeMode() = 1 }
    class Value(private val id: Int,private val value: Any?,private val status: Int=0) {
        fun getPropertyId()=id
        fun getAreaId()=0
        fun getValue()=value
        fun getTimestamp()=1_000_000_000L
        fun getStatus()=status
    }
    class Manager {
        var value: Any?=4
        var status=0
        var fail=false
        var wrongId=false
        var duringRead: (() -> Unit)?=null
        fun getCarPropertyConfig(id: Int): Any? = if(id==GEAR) Config() else null
        fun getProperty(id: Int,area: Int): Value {
            duringRead?.invoke();if(fail)error("read failure")
            return Value(if(wrongId) GEAR+1 else id,value,status)
        }
        fun subscribePropertyEvents(id: Int,rate: Float,cb: CarPropertyEventCallback)=true
    }
    @Test fun realSerializedOnChangeGearReadConfirmsOldParkWithoutRewritingEventTimeAndRevokesFailures() {
        mockkStatic(SystemClock::class)
        try {
            var now=60000L
            every {SystemClock.elapsedRealtime()} answers {now}
            val context=mockk<Context>(relaxed=true)
            every {context.checkSelfPermission(any())} returns PackageManager.PERMISSION_GRANTED
            val manager=Manager();val forwarder=VehicleDataForwarderImpl(context,{})
            fun set(name:String,value:Any)=forwarder.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(forwarder,value)
            set("desiredActive",true);set("isActive",true);set("propertyManager",manager)
            fun refresh()=forwarder.javaClass.getDeclaredMethod("registerProperties",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(forwarder,0L)
            val policy=CarPlayDrivingRestrictionBridge(forwarder.latestVehicleData,Dispatchers.IO,{now},15000)
            refresh()
            val o=forwarder.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION")
            assertEquals(1_000_000_000L,o.timestampElapsedNanos)
            // A synchronous initial read is not a fresh heartbeat; actual status read must be marked.
            now=66000;refresh()
            val reread=forwarder.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION")
            assertEquals(66000L,reread.statusReadElapsedMs)
            assertEquals(o.timestampElapsedNanos,reread.timestampElapsedNanos)
            assertEquals("actual_park",policy.restrictionReason(forwarder.latestVehicleData.value))
            for (bad in listOf<Any?>(null,4L,4f,"4",1,2,8)) {
                manager.value=bad;now+=6000;refresh()
                assertNotEquals("actual_park",policy.restrictionReason(forwarder.latestVehicleData.value))
            }
            manager.value=4;manager.status=1;now+=6000;refresh()
            assertNotEquals("actual_park",policy.restrictionReason(forwarder.latestVehicleData.value))
            manager.status=0;manager.fail=true;now+=6000;refresh()
            assertNotEquals("actual_park",policy.restrictionReason(forwarder.latestVehicleData.value))
            manager.fail=false;manager.wrongId=true;now+=6000;refresh()
            assertNotEquals("a mismatched Binder result must not be relabelled GEAR_SELECTION","actual_park",policy.restrictionReason(forwarder.latestVehicleData.value))
            kotlinx.coroutines.runBlocking {policy.close()}
        } finally {unmockkStatic(SystemClock::class)}
    }
    @Test fun authoritativeStatusReadUsesOneReceiptClockSampleEvenWhenClockAdvancesDuringObservation() {
        mockkStatic(SystemClock::class)
        var policy: CarPlayDrivingRestrictionBridge? = null
        try {
            var now = 60000L
            every { SystemClock.elapsedRealtime() } answers { now++ }
            val context = mockk<Context>(relaxed = true)
            every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
            val forwarder = VehicleDataForwarderImpl(context, {})
            for ((name, value) in mapOf("desiredActive" to true, "isActive" to true, "propertyManager" to Manager())) {
                forwarder.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(forwarder, value)
            }
            val refresh = forwarder.javaClass.getDeclaredMethod("registerProperties", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            refresh.invoke(forwarder, 0L)
            now = 66000L
            refresh.invoke(forwarder, 0L)
            val data = forwarder.latestVehicleData.value
            val observation = data.evObservationMetadata.getValue("GEAR_SELECTION")
            assertEquals("one actual read must have one receipt/authority timestamp",
                observation.receivedElapsedMs, observation.statusReadElapsedMs)
            assertEquals(1_000_000_000L, observation.timestampElapsedNanos)
            val bridge = CarPlayDrivingRestrictionBridge(forwarder.latestVehicleData, Dispatchers.IO, { now }, 15000)
            policy = bridge
            assertEquals("actual_park", bridge.restrictionReason(data))
        } finally {
            kotlinx.coroutines.runBlocking { policy?.close() }
            unmockkStatic(SystemClock::class)
        }
    }
    companion object {const val GEAR=0x11400400}
}
