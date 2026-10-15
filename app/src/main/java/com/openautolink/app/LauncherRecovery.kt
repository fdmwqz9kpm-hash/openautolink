package com.openautolink.app

import android.content.Intent
import android.os.Bundle

/**
 * Recovery is explicit user launcher entry, never a lifecycle resume/wake heuristic.
 * singleTask normally delivers a new MAIN + LAUNCHER intent when the icon is tapped.
 * An OEM that only brings the task forward without delivering an intent cannot be
 * distinguished from ignition/automatic resume here; that path needs device proof.
 */
internal object LauncherRecovery {
    const val SAVED_VISIBLE = "launcherRecoveryVisible"

    private fun isLauncherIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

    // Fresh MAIN launches can also come from automatic task restoration. Do not
    // interrupt startup: only restore an already-open menu, or handle re-entry below.
    @Suppress("UNUSED_PARAMETER")
    fun onCreate(intent: Intent?, savedInstanceState: Bundle?): Boolean =
        savedInstanceState?.getBoolean(SAVED_VISIBLE) ?: false

    // Coalesce repeated taps; unrelated USB/service intents do not steal an open dialog.
    fun onNewIntent(visible: Boolean, intent: Intent?): Boolean =
        visible || isLauncherIntent(intent)
}
