package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MicrophoneDiagnosticTest {
    private class Platform : MicrophoneDiagnosticPlatform {
        var onPolicy: (() -> Unit)? = null
        val requests = CopyOnWriteArrayList<MicrophoneDiagnosticShape>()
        val events = CopyOnWriteArrayList<String>()
        var match = MicrophoneDiagnosticMatch.UNIQUE
        var policyReason = MicrophoneDiagnosticReason.NONE
        var silenced: Boolean? = false
        var drift = false
        var mismatch = false
        var preferredAccepted = true
        var releaseEntered: CountDownLatch? = null
        var allowRelease: CountDownLatch? = null
        val live = AtomicInteger()
        override fun inventory() = MicrophoneDiagnosticInventory(match, emptyList())
        override fun policy(): MicrophoneDiagnosticReason { onPolicy?.invoke();return policyReason }
        override fun open(shape: MicrophoneDiagnosticShape): MicrophoneDiagnosticRecord {
            assertEquals("one physical recorder",1,live.incrementAndGet())
            requests.add(shape);events.add("open")
            return object : MicrophoneDiagnosticRecord {
                var observations = 0
                override fun preferNamedInput(): Boolean { events.add("prefer");return preferredAccepted }
                override fun start() { events.add("start") }
                override fun observe(): MicrophoneDiagnosticObservation {
                    observations++
                    return MicrophoneDiagnosticObservation(
                        if (mismatch) shape.copy(channelCount=2) else shape,shape,
                        shape.copy(channelCount=2,channelMask=12),silenced,
                        if (drift && observations>1) 2 else 1,true)
                }
                override fun read(buffer: ShortArray): Int { buffer.fill(7);return buffer.size }
                override fun stop() { events.add("stop") }
                override fun release() {
                    releaseEntered?.countDown()
                    allowRelease?.let { latch ->
                        // Model an OEM call which does not retire on interruption.
                        while (latch.count>0) try { latch.await() } catch (_: InterruptedException) {}
                    }
                    events.add("release");live.decrementAndGet()
                }
            }
        }
    }
    private class Driver(platform: Platform, eligibility: () -> Boolean = { true },
        timing: MicrophoneDiagnosticTiming = MicrophoneDiagnosticTiming(40,300,1000,10)) {
        private val type = runCatching { Class.forName("com.shilapi.xcertplay.media.MicrophoneDiagnostic") }.getOrNull()
        init { assertNotNull("bounded diagnostic worker must exist",type) }
        val done = CountDownLatch(1)
        private val instance = type!!.getDeclaredConstructor(MicrophoneDiagnosticPlatform::class.java,
            kotlin.jvm.functions.Function0::class.java,kotlin.jvm.functions.Function1::class.java,
            MicrophoneDiagnosticTiming::class.java).newInstance(platform,eligibility,
                { state: MicrophoneDiagnosticState ->
                    if (state.phase==MicrophoneDiagnosticPhase.COMPLETE) {
                        assertEquals("result only after actual release",0,platform.live.get());done.countDown()
                    }
                },timing)
        val monitor get() = type!!.getDeclaredField("lock").apply { isAccessible=true }.get(instance)
        val state get() = type!!.getMethod("getState").invoke(instance) as MicrophoneDiagnosticState
        fun start(comparison: MicrophoneDiagnosticComparison) = type!!.getMethod("start",MicrophoneDiagnosticComparison::class.java).invoke(instance,comparison) as Boolean
        fun inspect() = type!!.getMethod("inspect").invoke(instance) as Boolean
        fun cancel() { type!!.getMethod("cancel").invoke(instance) }
        fun result(): MicrophoneDiagnosticResult {
            assertTrue("worker completes",done.await(3,TimeUnit.SECONDS))
            return state.result!!
        }
    }
    @Test fun cancellationAtFinalPublicationCannotPublishSuccessfulResult() {
        val p=Platform();val atFinal=CountDownLatch(1);val proceed=CountDownLatch(1)
        val worker=java.util.concurrent.atomic.AtomicReference<Thread>()
        p.onPolicy={
            if(p.requests.size==3 && p.live.get()==0) {
                worker.set(Thread.currentThread());atFinal.countDown();proceed.await(2,TimeUnit.SECONDS)
            }
        }
        val d=Driver(p,timing=MicrophoneDiagnosticTiming(20,1000,3000,10))
        assertTrue(d.start(MicrophoneDiagnosticComparison.CHANNELS))
        assertTrue(atFinal.await(2,TimeUnit.SECONDS))
        synchronized(d.monitor) {
            proceed.countDown()
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            while(worker.get().state!=Thread.State.BLOCKED && System.nanoTime()<until) Thread.sleep(1)
            assertEquals("worker reached terminal publication gate",Thread.State.BLOCKED,worker.get().state)
            assertTrue(worker.get().stackTrace.any { it.methodName=="finish" })
            d.cancel()
        }
        assertEquals("cancellation wins until result is published",MicrophoneDiagnosticReason.CANCELLED,d.result().reason)
    }
    @Test fun threeComparisonsKeepSourceAndRestoreControlOnlyAfterRelease() {
        for (comparison in MicrophoneDiagnosticComparison.values()) {
            val platform=Platform(); val owner=Driver(platform)
            assertTrue(owner.start(comparison))
            val result=owner.result()
            assertEquals(MicrophoneDiagnosticReason.NONE,result.reason)
            assertEquals(3,result.arms.size)
            assertEquals(listOf(6,6,6),platform.requests.map { it.source })
            assertEquals(if(comparison==MicrophoneDiagnosticComparison.RATE) listOf(24000,48000,24000)
                else listOf(24000,24000,24000),platform.requests.map { it.sampleRate })
            assertEquals(if(comparison==MicrophoneDiagnosticComparison.CHANNELS) listOf(1,2,1)
                else listOf(1,1,1),platform.requests.map { it.channelCount })
            assertEquals(if(comparison==MicrophoneDiagnosticComparison.PREFERRED) 1 else 0,platform.events.count { it=="prefer" })
            assertEquals(3,platform.events.count { it=="release" })
            assertTrue(result.arms.all { it.statistics.nonzeroFrames>0 && it.client!=null && it.device?.channelCount==2 })
            assertTrue(result.arms.all { it.routeStable==true && it.routeSameAsFirst==true })
        }
    }
    @Test fun cancellationAndDeadlineKeepFenceUntilStalledReleaseAcknowledges() {
        for (deadline in listOf(false,true)) {
            val p=Platform();p.releaseEntered=CountDownLatch(1);p.allowRelease=CountDownLatch(1)
            val d=Driver(p,timing=MicrophoneDiagnosticTiming(30,100,500,10))
            assertTrue(d.start(MicrophoneDiagnosticComparison.RATE))
            try {
                assertTrue(p.releaseEntered!!.await(1,TimeUnit.SECONDS))
                if(!deadline) d.cancel()
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
                while(d.state.phase!=MicrophoneDiagnosticPhase.STOPPING && System.nanoTime()<until) Thread.sleep(5)
                assertEquals(MicrophoneDiagnosticPhase.STOPPING,d.state.phase)
                assertNull(d.state.result);assertNull(MicrophoneCaptureFence.tryNormal())
                assertFalse(Driver(Platform()).start(MicrophoneDiagnosticComparison.CHANNELS))
                assertEquals(1,p.requests.size)
            } finally { p.allowRelease!!.countDown() }
            assertEquals(if(deadline) MicrophoneDiagnosticReason.DEADLINE else MicrophoneDiagnosticReason.CANCELLED,d.result().reason)
            MicrophoneCaptureFence.tryNormal()!!.release()
        }
    }
    @Test fun policyLossUnknownAndCapturedShapeMismatchAbortWithoutSuccessor() {
        for (reason in listOf(MicrophoneDiagnosticReason.PERMISSION,MicrophoneDiagnosticReason.MUTED,MicrophoneDiagnosticReason.MODE)) {
            val p=Platform();p.policyReason=reason;val d=Driver(p)
            assertTrue(d.start(MicrophoneDiagnosticComparison.CHANNELS));assertEquals(reason,d.result().reason)
            assertTrue(p.requests.isEmpty())
        }
        for(reason in listOf(MicrophoneDiagnosticReason.SILENCED,MicrophoneDiagnosticReason.POLICY_UNKNOWN,
            MicrophoneDiagnosticReason.SHAPE_MISMATCH,MicrophoneDiagnosticReason.ROUTE_CHANGED)) {
            val p=Platform()
            p.silenced=when(reason) { MicrophoneDiagnosticReason.SILENCED -> true;MicrophoneDiagnosticReason.POLICY_UNKNOWN -> null;else -> false }
            p.mismatch=reason==MicrophoneDiagnosticReason.SHAPE_MISMATCH;p.drift=reason==MicrophoneDiagnosticReason.ROUTE_CHANGED
            val d=Driver(p);assertTrue(d.start(MicrophoneDiagnosticComparison.CHANNELS))
            assertEquals(reason,d.result().reason);assertEquals(1,p.requests.size);assertEquals(0,p.live.get())
        }
        val p=Platform();val d=Driver(p,eligibility={false});assertTrue(d.start(MicrophoneDiagnosticComparison.RATE))
        assertEquals(MicrophoneDiagnosticReason.INELIGIBLE,d.result().reason);assertTrue(p.requests.isEmpty())
    }
    @Test fun livePolicyLossAndRejectedPreferenceStopWithoutRetry() {
        for(reason in listOf(MicrophoneDiagnosticReason.PERMISSION,MicrophoneDiagnosticReason.MODE,MicrophoneDiagnosticReason.MUTED)) {
            val p=Platform()
            p.onPolicy={ if(p.live.get()>0) p.policyReason=reason }
            val d=Driver(p);assertTrue(d.start(MicrophoneDiagnosticComparison.RATE))
            assertEquals(reason,d.result().reason);assertEquals(1,p.requests.size);assertEquals(0,p.live.get())
        }
        val p=Platform();p.preferredAccepted=false;val d=Driver(p)
        assertTrue(d.start(MicrophoneDiagnosticComparison.PREFERRED))
        val result=d.result()
        assertEquals(MicrophoneDiagnosticReason.PREFERRED_REJECTED,result.reason)
        assertEquals(2,p.requests.size);assertEquals(false,result.arms.last().preferredAccepted)
        assertEquals(1,p.events.count { it=="start" })
    }
    @Test fun passiveInventoryNeverCapturesAndAmbiguousPreferenceDoesNotProbe() {
        val p=Platform();val d=Driver(p);assertTrue(d.inspect());assertEquals(MicrophoneDiagnosticReason.NONE,d.result().reason)
        assertTrue(p.requests.isEmpty())
        for(match in listOf(MicrophoneDiagnosticMatch.ABSENT,MicrophoneDiagnosticMatch.MULTIPLE,MicrophoneDiagnosticMatch.TRUNCATED)) {
            val q=Platform();q.match=match;val owner=Driver(q);assertTrue(owner.start(MicrophoneDiagnosticComparison.PREFERRED))
            assertEquals(MicrophoneDiagnosticReason.PREFERRED_UNAVAILABLE,owner.result().reason);assertTrue(q.requests.isEmpty())
        }
    }
}
