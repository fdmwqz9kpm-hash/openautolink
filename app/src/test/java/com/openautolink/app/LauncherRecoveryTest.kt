package com.openautolink.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Intent/state proof only: an OEM must actually deliver a launcher intent. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LauncherRecoveryTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private fun saved(visible: Boolean) = Bundle().apply {
        putBoolean(LauncherRecovery.SAVED_VISIBLE, visible)
    }

    @Test fun `fresh launch does not interrupt startup regardless of task flags`() {
        for (flags in listOf(0, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)) {
            assertFalse(LauncherRecovery.onCreate(launcher().setFlags(flags), null))
        }
    }

    @Test fun `both MAIN and LAUNCHER are required not component or extras`() {
        val others = listOf(null, Intent(), Intent(Intent.ACTION_MAIN),
            Intent().addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND),
            Intent("com.openautolink.app.IGNITION_ON"),
            Intent().setClassName("com.openautolink.app", "com.openautolink.app.MainActivity")
                .putExtra("launcher", true))
        for (intent in others) {
            assertFalse("cold ${intent?.action}", LauncherRecovery.onCreate(intent, null))
            assertFalse("reentry ${intent?.action}", LauncherRecovery.onNewIntent(false, intent))
        }
    }

    @Test fun `saved dismissal prevents replay of retained launcher intent`() {
        assertFalse(LauncherRecovery.onCreate(launcher(), saved(false)))
        assertFalse(LauncherRecovery.onCreate(launcher(), Bundle()))
    }

    @Test fun `saved pending recovery survives recreation including a later service intent`() {
        assertTrue(LauncherRecovery.onCreate(launcher(), saved(true)))
        assertTrue(LauncherRecovery.onCreate(Intent(), saved(true)))
    }

    @Test fun `each new launcher intent reopens after dismissal and coalesces while visible`() {
        assertTrue(LauncherRecovery.onNewIntent(false, launcher()))
        assertTrue(LauncherRecovery.onNewIntent(true, launcher()))
        assertTrue(LauncherRecovery.onNewIntent(false, launcher()))
    }

    @Test fun `USB and service reentry cannot close an outstanding user recovery request`() {
        assertTrue(LauncherRecovery.onNewIntent(true, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertTrue(LauncherRecovery.onNewIntent(true, Intent()))
        assertTrue(LauncherRecovery.onNewIntent(true, null))
    }
}
