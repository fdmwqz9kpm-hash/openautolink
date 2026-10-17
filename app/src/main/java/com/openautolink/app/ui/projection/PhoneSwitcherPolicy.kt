package com.openautolink.app.ui.projection

import com.openautolink.app.data.AppPreferences

internal object PhoneSwitcherPolicy {
    // Menu capability is independent of the retired floating-button visibility preference.
    fun visible(transport: String, connectionMode: String): Boolean =
        transport == "hotspot" && connectionMode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT
}
