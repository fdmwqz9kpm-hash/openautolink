package com.openautolink.app

import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.compose.runtime.State
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Executes the real new-intent callback without booting process services or projection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivityLauncherEntryTest {
    @Test fun applicationFixtureDoesNotBootProcessServices() {
        assertEquals(android.app.Application::class.java,
            org.robolectric.RuntimeEnvironment.getApplication().javaClass)
    }

    @Test fun `ordinary launcher reentry delivers the intent without pending modal state`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        for (flags in listOf(0, Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT,
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setFlags(flags)
            controller.newIntent(intent)
            assertSame("Retain the latest launch intent", intent, activity.intent)
            assertNoPendingLauncherModal(activity)
        }
    }

    @Test fun `USB and service reentry retain ordinary intent handling`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        for (intent in listOf(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED), Intent(),
            Intent("com.openautolink.app.IGNITION_ON"))) {
            controller.newIntent(intent)
            assertSame(intent, controller.get().intent)
            assertNoPendingLauncherModal(controller.get())
        }
    }

    private fun assertNoPendingLauncherModal(activity: MainActivity) {
        // Also detects regressions in the retired implementation: its delegated
        // Compose state becomes true on MAIN+LAUNCHER before any UI is composed.
        for (field in MainActivity::class.java.declaredFields.filter { it.name.startsWith("launcherRecovery") }) {
            field.isAccessible = true
            val value = field.get(activity)
            assertNotEquals("Launcher reentry must not queue a modal", true,
                if (value is State<*>) value.value else value)
        }
    }
}
