package com.openautolink.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openautolink.app.data.AppPreferences

/** Theme supplies a preference, not a root density override. */
internal val LocalOalUiScalePercent = compositionLocalOf { AppPreferences.DEFAULT_OAL_UI_SCALE_PERCENT }
private val LocalOalUiScaledDensity = compositionLocalOf<Density?> { null }

/**
 * Opt-in sizing for OAL-owned controls and text ONLY. Never wrap a projection
 * SurfaceView, video viewport, touch mapper, or physical safe-area geometry.
 * Nested wrappers are idempotent, including dialogs composed by scaled screens.
 */
@Composable
fun OalUiScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    // Dialog/Popup owners replace LocalDensity but retain custom locals. A Boolean
    // marker would incorrectly skip scaling inside that new AndroidComposeView.
    if (LocalOalUiScaledDensity.current === base) {
        content()
        return
    }
    val percent = AppPreferences.normalizeOalUiScalePercent(LocalOalUiScalePercent.current)
    val scaled = remember(base, percent) {
        if (percent == 100) base else object : Density {
            override val density = base.density * percent / 100f
            override val fontScale = base.fontScale
            // Preserve the OS font conversion, including nonlinear accessibility
            // scaling on newer Android. Density then scales both dp and sp pixels.
            override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
            override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
        }
    }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalOalUiScaledDensity provides scaled,
        content = content,
    )
}
