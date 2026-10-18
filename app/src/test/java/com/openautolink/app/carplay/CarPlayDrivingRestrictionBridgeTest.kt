package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarPlayDrivingRestrictionBridgeTest {
    private fun park(at: Long = 1000, raw: Int? = 4) = ControlMessage.VehicleData(
        gearRaw = raw, driving = false, vhalRegistrationGeneration = 7,
        evObservationMetadata = mapOf("GEAR_SELECTION" to VehiclePropertyObservation(
            at * 1_000_000, at, 0, registrationGeneration = 7, propertyId = 0x11400400,
            subscriptionActive = true, sequence = at)))
    @Test fun onlyFreshTypedActualParkFromExactAvailableRegistrationUnlocks() = runTest {
        var now = 1000L
        val b = CarPlayDrivingRestrictionBridge(null, StandardTestDispatcher(testScheduler), {now}, 500)
        val p = park(); val o = p.evObservationMetadata.getValue("GEAR_SELECTION")
        assertEquals("actual_park", b.restrictionReason(p))
        for (raw in listOf(null, 0, 1, 2, 8, 3, 5, -1)) assertEquals("not_park",b.restrictionReason(park(raw=raw)))
        val invalid = listOf(o.copy(source="tablet"), o.copy(propertyId=0x11400401),
            o.copy(status=null), o.copy(status=1), o.copy(status=2), o.copy(subscriptionActive=false),
            o.copy(registrationGeneration=6), o.copy(timestampElapsedNanos=null),
            o.copy(timestampElapsedNanos=0), o.copy(timestampElapsedNanos=1_001_000_000),
            o.copy(receivedElapsedMs=-1), o.copy(receivedElapsedMs=1001))
        for (bad in invalid) assertNotEquals(bad.toString(),"actual_park",b.restrictionReason(p.copy(evObservationMetadata=mapOf("GEAR_SELECTION" to bad))))
        assertNotEquals("actual_park",b.restrictionReason(p.copy(vhalRegistrationGeneration=null)))
        assertNotEquals("actual_park",b.restrictionReason(p.copy(vhalRegistrationGeneration=0)))
        assertNotEquals("actual_park",b.restrictionReason(p.copy(evObservationMetadata=emptyMap())))
        assertNotEquals("actual_park",b.restrictionReason(null))
        now=1501; assertEquals("stale",b.restrictionReason(p))
        // Old ONCHANGE event is not fresh merely because unrelated telemetry arrived.
        val read = p.copy(evObservationMetadata=mapOf("GEAR_SELECTION" to o.copy(
            receivedElapsedMs=1501,changeMode=1,statusReadElapsedMs=1501)))
        assertEquals("actual_park",b.restrictionReason(read))
        assertEquals("stale",b.restrictionReason(read.copy(evObservationMetadata=mapOf("GEAR_SELECTION" to
            read.evObservationMetadata.getValue("GEAR_SELECTION").copy(statusReadElapsedMs=null)))))
        b.close()
    }
    @Test fun readinessReplayExpirySilenceSourceLossAndLateRetirementAreFenced() = runTest {
        var now=1000L
        val flow=MutableStateFlow(park());val ready=MutableStateFlow(false);val sent=mutableListOf<Boolean>()
        val b=CarPlayDrivingRestrictionBridge(flow,StandardTestDispatcher(testScheduler),{now},500)
        val old=b.replace(ready,{sent+=it;true});runCurrent();assertTrue(sent.isEmpty())
        ready.value=true;runCurrent();assertEquals(listOf(false),sent)
        now=1501;advanceTimeBy(250);runCurrent();assertEquals(listOf(false,true),sent)
        now=1600;flow.value=park(now);runCurrent();assertEquals(false,sent.last())
        ready.value=false;runCurrent();ready.value=true;runCurrent();assertEquals(listOf(false,true,false,false),sent)
        flow.value=park(now,1);runCurrent();assertEquals(true,sent.last())
        val fresh=b.replace(ready,{sent+=it;true});runCurrent();b.retire(old)
        flow.value=park(now+1);now++;runCurrent();assertEquals(false,sent.last())
        b.revoke(fresh);assertFalse(b.permitsUnlock());flow.value=park(now,8);runCurrent();assertEquals(false,sent.last())
        b.retire(fresh);b.close()
    }
    @Test fun failedWritesAreNotAcknowledgedOrBusyRetriedAndReceiptsAreBounded() = runTest {
        var now=1000L;val source=MutableStateFlow(park());val ready=MutableStateFlow(true)
        val receipts=mutableListOf<String>();var calls=0
        val b=CarPlayDrivingRestrictionBridge(source,StandardTestDispatcher(testScheduler),{now},500)
        b.replace(ready,{calls++;false},receipts::add);runCurrent();assertEquals(1,calls)
        advanceTimeBy(250);runCurrent();assertEquals(1,calls)
        for (i in 1..100) { now++;source.value=park(now);runCurrent() }
        assertEquals(101,calls);assertEquals(64,receipts.size)
        assertTrue(receipts.all {it.endsWith("result=send_failed count=0")})
        b.close()
    }
    @Test fun blockedAdmittedWritePreventsFalseRetirementAndLateReceipt() = runBlocking {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val receipts=java.util.concurrent.CopyOnWriteArrayList<String>()
        val b=CarPlayDrivingRestrictionBridge(MutableStateFlow(park()),Dispatchers.IO,{1000},500)
        val owner=b.replace(MutableStateFlow(true),{entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));true},receipts::add)
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
        b.revoke(owner)
        val retiring=async(Dispatchers.IO) { b.retire(owner) }
        delay(75);assertFalse(retiring.isCompleted);assertTrue(receipts.isEmpty())
        release.countDown();withTimeout(2000){retiring.await()};assertTrue(receipts.isEmpty());b.close()
    }
    @Test fun absentProcessSourceActivelyRestrictsRatherThanAssumingPark() = runTest {
        val b=CarPlayDrivingRestrictionBridge(null,StandardTestDispatcher(testScheduler),{1000},500)
        val sent=mutableListOf<Boolean>();b.replace(MutableStateFlow(true),{sent+=it;true});runCurrent()
        assertEquals(listOf(true),sent);b.close()
    }
    @Test fun explicitCarPlayOverrideOnlyChangesUiPolicyAndNeverClaimsActualPark() = runTest {
        val source=MutableStateFlow(park(raw=8));val ready=MutableStateFlow(false)
        val sent=mutableListOf<Boolean>();val receipts=mutableListOf<String>()
        val bridge=CarPlayDrivingRestrictionBridge(source,StandardTestDispatcher(testScheduler),{1000},500,true)
        try {
        val owner=bridge.replace(ready,{sent+=it;true},receipts::add)
        runCurrent();assertTrue(sent.isEmpty());assertTrue(bridge.permitsUnlock())
        assertEquals("not_park",bridge.restrictionReason(source.value))
        ready.value=true;runCurrent();assertEquals(listOf(false),sent)
        assertTrue(receipts.single().contains("reason=user_override limited=false"))
        source.value=ControlMessage.VehicleData();runCurrent()
        assertEquals(listOf(false),sent);assertEquals("metadata",bridge.restrictionReason(source.value))
        ready.value=false;runCurrent();ready.value=true;runCurrent()
        assertEquals(listOf(false,false),sent)
        bridge.revoke(owner);assertFalse(bridge.permitsUnlock());bridge.retire(owner)
        } finally {bridge.close()}
        val real=CarPlayDrivingRestrictionBridge(source,StandardTestDispatcher(testScheduler),{1000},500)
        try {real.replace(ready,{sent+=it;true});runCurrent();assertEquals(true,sent.last())}
        finally {real.close()}
    }
}
