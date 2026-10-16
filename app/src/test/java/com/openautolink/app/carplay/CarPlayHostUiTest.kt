package com.openautolink.app.carplay

import android.app.Activity
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class CarPlayHostUiTest {
    class HostActivity : Activity() {
        var minimizes = 0
        override fun moveTaskToBack(nonRoot: Boolean): Boolean {
            assertTrue(nonRoot)
            assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            minimizes++
            return true
        }
    }
    private class Harness(var rejectNext: Boolean = false) {
        val queue = ArrayDeque<Runnable>()
        val type = try { Class.forName("com.openautolink.app.carplay.CarPlayHostUiController") }
            catch (_: ClassNotFoundException) { fail("Return-to-car host controller is missing"); throw AssertionError() }
        val controller = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance({ task: Runnable ->
                if (rejectNext) { rejectNext = false; false } else { queue.addLast(task); true }
            })
        fun register(activity: Activity) { type.getDeclaredMethod("register", Activity::class.java).invoke(controller, activity) }
        fun unregister(activity: Activity) {
            val method = type.declaredMethods.singleOrNull { it.name == "unregister" }
            assertNotNull("Activity stop must retire only its exact registration", method)
            method!!.invoke(controller, activity)
        }
        fun request(current: () -> Boolean = { true }) {
            type.getDeclaredMethod("request", kotlin.jvm.functions.Function0::class.java).invoke(controller, current)
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    @Test fun rejectedMainPostDoesNotStrandTheNextRequest() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness(rejectNext = true)
        h.register(host.get())
        h.request()
        assertEquals(0, host.get().minimizes)
        assertTrue(h.queue.isEmpty())
        h.request()
        assertEquals("A later request must rearm scheduling after rejection", 1, h.queue.size)
        h.drain()
        assertEquals(1, host.get().minimizes)
        host.pause().stop().destroy()
    }

    @Test fun coalescingRetainsOnlyTheLatestRequestsExactPredicate() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        var latestCurrent = true
        h.register(host.get())
        h.request { fail("Superseded session predicate must not be adopted"); true }
        h.request { latestCurrent }
        latestCurrent = false
        assertEquals(1, h.queue.size)
        h.drain()
        assertEquals(0, host.get().minimizes)
        h.request()
        h.drain()
        assertEquals(1, host.get().minimizes)
        host.pause().stop().destroy()
    }

    @Test fun processDispatcherMarshalsWorkerRequestsToMain() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val type = try { Class.forName("com.openautolink.app.carplay.CarPlayHostUi") }
            catch (_: ClassNotFoundException) { fail("Process host dispatcher is missing"); throw AssertionError() }
        val dispatcher = type.getField("INSTANCE").get(null)
        val register = type.getDeclaredMethod("register", Activity::class.java)
        val unregister = type.getDeclaredMethod("unregister", Activity::class.java)
        val request = type.getDeclaredMethod("request", kotlin.jvm.functions.Function0::class.java)
        register.invoke(dispatcher, host.get())
        try {
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                worker.submit { repeat(1000) { request.invoke(dispatcher, { true }) } }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
            assertEquals(0, host.get().minimizes)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, host.get().minimizes)
        } finally { unregister.invoke(dispatcher, host.get()); host.pause().stop().destroy() }
    }

    @Test fun activityRegistrationRejectsWorkerThreadMutation() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val failure = worker.submit<Throwable?> {
                try { h.register(host.get()); null }
                catch (failure: java.lang.reflect.InvocationTargetException) { failure.targetException }
            }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Lifecycle mutation must be main-thread-only", failure is IllegalStateException)
        } finally { worker.shutdownNow(); host.pause().stop().destroy() }
    }

    @Test fun finishingOrDestroyedActivityCannotBeMinimized() {
        for (destroy in listOf(false, true)) {
            val host = Robolectric.buildActivity(HostActivity::class.java).setup()
            val h = Harness()
            h.register(host.get())
            h.request()
            if (destroy) host.pause().stop().destroy() else host.get().finish()
            h.drain()
            assertEquals("Ineligible Activity must not receive task operations", 0, host.get().minimizes)
            if (!destroy) host.pause().stop().destroy()
        }
    }

    @Test fun repeatedRequestsHaveOneQueuedDeliveryAndMinimizeOncePerForegroundRegistration() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        h.register(host.get())
        repeat(1000) { h.request() }
        assertEquals("Receiver flood must not grow the main queue", 1, h.queue.size)
        h.drain()
        assertEquals(1, host.get().minimizes)
        repeat(1000) { h.request() }
        h.drain()
        assertEquals("Do not minimize again while Activity stop is pending", 1, host.get().minimizes)
        h.unregister(host.get())
        h.register(host.get())
        h.request()
        h.drain()
        assertEquals("Explicit foreground reattachment permits another action", 2, host.get().minimizes)
        host.pause().stop().destroy()
    }

    @Test fun backgroundRequestsAreNotSavedForANewVisibleRegistration() {
        val first = Robolectric.buildActivity(HostActivity::class.java).setup()
        val second = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        h.register(first.get())
        h.request()
        h.unregister(first.get())
        h.drain()
        assertEquals(0, first.get().minimizes)
        h.request()
        assertTrue("No queued action while app is background", h.queue.isEmpty())
        h.register(second.get())
        h.unregister(first.get())
        h.drain()
        assertEquals(0, second.get().minimizes)
        h.request()
        h.drain()
        assertEquals("Old Activity stop must not retire replacement", 1, second.get().minimizes)
        first.pause().stop().destroy()
        second.pause().stop().destroy()
    }

    @Test fun registrationReplacementCannotAdoptAnEarlierRequestEvenForSameActivity() {
        for (sameActivity in listOf(false, true)) {
            val first = Robolectric.buildActivity(HostActivity::class.java).setup()
            val second = if (sameActivity) first else Robolectric.buildActivity(HostActivity::class.java).setup()
            val h = Harness()
            h.register(first.get())
            h.request()
            h.register(second.get())
            h.drain()
            assertEquals("Retired registration cannot minimize old Activity", 0, first.get().minimizes)
            assertEquals("Replacement cannot inherit queued work", 0, second.get().minimizes)
            h.request()
            h.drain()
            assertEquals(1, second.get().minimizes)
            first.pause().stop().destroy()
            if (!sameActivity) second.pause().stop().destroy()
        }
    }

    @Test fun sessionRevocationBeforeDeliveryCannotMinimize() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        var current = true
        h.register(host.get())
        h.request { current }
        current = false
        h.drain()
        assertEquals("Exact queued session must still be current on delivery", 0, host.get().minimizes)
        h.request { false }
        h.drain()
        assertEquals(0, host.get().minimizes)
        h.request { true }
        h.drain()
        assertEquals("A new valid session can still return to the car", 1, host.get().minimizes)
        host.pause().stop().destroy()
    }

    @Test fun currentRequestMinimizesOnDeliveryWithoutFinishingActivity() {
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        val h = Harness()
        h.register(host.get())
        h.request()
        assertEquals(0, host.get().minimizes)
        assertEquals(1, h.queue.size)
        h.drain()
        assertEquals(1, host.get().minimizes)
        assertFalse(host.get().isFinishing)
        host.pause().stop().destroy()
    }
}
