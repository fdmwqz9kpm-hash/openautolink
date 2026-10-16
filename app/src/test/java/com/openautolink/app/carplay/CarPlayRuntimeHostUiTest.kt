package com.openautolink.app.carplay

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Looper
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayRuntimeHostUiTest {
    private fun runtime(usb: Boolean): CarPlayRuntime = if (usb)
        OalUsbCarPlayRuntime(mockk<Context>(), mockk<UsbDevice>(relaxed = true),
            CarPlayDisplayPreferences(), mockk<Surface>(), {})
    else OalCarPlayRuntime(mockk<Context>(), CarPlayRuntimeConfig("", "", "", false, "", "", 0,
        CarPlayDisplayPreferences()), mockk<Surface>(), {})

    private fun request(runtime: CarPlayRuntime, isCurrent: () -> Boolean) {
        val method = runtime.javaClass.declaredMethods.singleOrNull { it.name == "requestHostUi" }
        assertNotNull("Both transports need an exact-runtime return-to-car callback", method)
        method!!.apply { isAccessible = true }.invoke(runtime, isCurrent)
    }

    @Test fun bothTransportsFenceQueuedRequestWithTheirExactRuntimeCancellation() {
        for (usb in listOf(false, true)) {
            val host = Robolectric.buildActivity(CarPlayHostUiTest.HostActivity::class.java).setup()
            val old = runtime(usb)
            val replacement = runtime(usb)
            CarPlayHostUi.register(host.get())
            try {
                request(old) { true }
                old.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Canceled runtime must not hide Activity", 0, host.get().minimizes)
                request(replacement) { true }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("New runtime remains independently valid", 1, host.get().minimizes)
                assertFalse(host.get().isFinishing)
            } finally {
                CarPlayHostUi.unregister(host.get()); old.close(); replacement.close()
                host.pause().stop().destroy()
            }
        }
    }

    @Test fun bothTransportsRetainReceiverSuppliedSessionPredicateUntilMainDelivery() {
        for (usb in listOf(false, true)) {
            val host = Robolectric.buildActivity(CarPlayHostUiTest.HostActivity::class.java).setup()
            val runtime = runtime(usb)
            var current = true
            CarPlayHostUi.register(host.get())
            try {
                request(runtime) { current }
                current = false
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, host.get().minimizes)
                request(runtime) { true }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, host.get().minimizes)
                runtime.javaClass.getDeclaredMethod("active").apply { isAccessible = true }.invoke(runtime)
            } finally {
                CarPlayHostUi.unregister(host.get()); runtime.close(); host.pause().stop().destroy()
            }
        }
    }

    @Test fun receiverInstallationBindsExactRuntimeBeforeEitherTransportStarts() {
        for (name in listOf("OalCarPlayRuntime", "OalUsbCarPlayRuntime")) {
            val source = File("src/main/java/com/openautolink/app/carplay/$name.kt").readText()
            val installation = source.substringAfter("private fun ownReceiver(").substringBefore("private fun reconcilePresentation()")
            assertTrue("$name must bind the callback before returning a startable receiver",
                installation.contains("projection.setHostUiHandler(::requestHostUi)"))
            assertTrue(installation.indexOf("setHostUiHandler") < installation.indexOf("receiver = projection"))
            val callback = source.substringAfter("private fun requestHostUi(").substringBefore("private fun ownReceiver(")
            assertFalse(callback.contains("returnToAndroidAuto"))
            assertFalse(callback.contains("handleUserExit"))
            assertFalse(callback.contains("cancel()"))
            assertFalse(callback.contains("close()"))
        }
    }

    @Test fun activityRegistersOnlyWhileStartedWithoutChangingCarPlayBackgroundProtection() {
        val source = File("src/main/java/com/openautolink/app/MainActivity.kt").readText()
        val start = source.substringAfter("override fun onStart()").substringBefore("override fun onStop()")
        val stop = source.substringAfter("override fun onStop()").substringBefore("override fun onTopResumedActivityChanged")
        assertTrue("Started Activity must be discoverable", start.contains("CarPlayHostUi.register(this)"))
        assertTrue("Stopped Activity must retire its exact UI registration", stop.contains("CarPlayHostUi.unregister(this)"))
        assertFalse(stop.contains("stopCarPlay("))
        assertFalse(stop.contains("returnToAndroidAuto("))
        assertFalse(stop.contains("handleUserExit("))
    }
}
