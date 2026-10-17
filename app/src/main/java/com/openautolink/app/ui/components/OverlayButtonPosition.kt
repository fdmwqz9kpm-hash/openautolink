package com.openautolink.app.ui.components

import android.content.SharedPreferences

internal data class OverlayButtonPoint(val x: Float, val y: Float)

private fun Float.finiteOrZero() = if (isFinite()) this else 0f

internal data class OverlayButtonGeometry(
    val parentWidth: Float,
    val parentHeight: Float,
    val anchorX: Float,
    val anchorY: Float,
    val buttonWidth: Float,
    val buttonHeight: Float,
    // Physical/system density, never the app's percentage-scaled LocalDensity.
    val density: Float = 1f,
    // Physical position of the safe-area parent in a stable window coordinate space.
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    val isMeasured get() = parentWidth.isFinite() && parentWidth > 0f &&
        parentHeight.isFinite() && parentHeight > 0f &&
        buttonWidth.isFinite() && buttonWidth > 0f &&
        buttonHeight.isFinite() && buttonHeight > 0f &&
        anchorX.isFinite() && anchorY.isFinite() && density.isFinite() && density > 0f &&
        originX.isFinite() && originY.isFinite()

    private val safeAnchorX get() = anchorX.finiteOrZero()
    private val safeAnchorY get() = anchorY.finiteOrZero()
    private val safeDensity get() = if (density.isFinite() && density > 0f) density else 1f
    private val travelX get() = (parentWidth.finiteOrZero().coerceAtLeast(0f) -
        buttonWidth.finiteOrZero().coerceAtLeast(0f)).coerceAtLeast(0f)
    private val travelY get() = (parentHeight.finiteOrZero().coerceAtLeast(0f) -
        buttonHeight.finiteOrZero().coerceAtLeast(0f)).coerceAtLeast(0f)

    fun clampOffset(offset: OverlayButtonPoint) = OverlayButtonPoint(
        offset.x.finiteOrZero().coerceIn(-safeAnchorX, travelX - safeAnchorX),
        offset.y.finiteOrZero().coerceIn(-safeAnchorY, travelY - safeAnchorY),
    )

    fun fractionFromOffset(offset: OverlayButtonPoint): OverlayButtonPoint {
        val clamped = clampOffset(offset)
        return OverlayButtonPoint(
            if (travelX > 0f) (safeAnchorX + clamped.x) / travelX else 0f,
            if (travelY > 0f) (safeAnchorY + clamped.y) / travelY else 0f,
        )
    }

    fun offsetFromFraction(fraction: OverlayButtonPoint) = clampOffset(OverlayButtonPoint(
        fraction.x.finiteOrZero().coerceIn(0f, 1f) * travelX - safeAnchorX,
        fraction.y.finiteOrZero().coerceIn(0f, 1f) * travelY - safeAnchorY,
    ))

    /** Capture only a real drag (or one-time migration), never a relayout clamp. */
    fun preferredDpFromOffset(offset: OverlayButtonPoint): OverlayButtonPoint {
        val clamped = clampOffset(offset)
        fun toDp(pixel: Float, origin: Float) = ((pixel.toDouble() + origin.finiteOrZero()) / safeDensity)
            .coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
        return OverlayButtonPoint(
            toDp(safeAnchorX + clamped.x, originX),
            toDp(safeAnchorY + clamped.y, originY),
        )
    }

    /** Clamp the displayed rectangle, not the saved preference, independently per axis. */
    fun offsetFromPreferredDp(preferred: OverlayButtonPoint): OverlayButtonPoint {
        // Double arithmetic keeps an overflowing but finite saved dp value at
        // the nearest edge instead of turning infinity into an opposite-edge reset.
        fun toPixel(dp: Float, travel: Float, origin: Float) =
            (dp.finiteOrZero().toDouble() * safeDensity - origin.finiteOrZero())
            .coerceIn(0.0, travel.toDouble()).toFloat()
        return OverlayButtonPoint(
            toPixel(preferred.x, travelX, originX) - safeAnchorX,
            toPixel(preferred.y, travelY, originY) - safeAnchorY,
        )
    }
}

/**
 * V3 stores the preferred window-relative top-left in unscaled system dp. A
 * smaller viewport or larger button changes only the displayed clamp, so the
 * original placement returns when space becomes available again.
 *
 * V2 travel fractions and legacy moving-anchor pixel deltas migrate once on a
 * measured layout. Untouched defaults remain attached to their current stack slot.
 */
internal class OverlayButtonPositionStore(private val prefs: SharedPreferences, private val key: String) {
    companion object {
        private val keys = listOf("overlay_menu", "overlay_settings", "overlay_switch_phone", "overlay_stats", "overlay_reconnect",
            "overlay_file_log", "overlay_upload_logs", "overlay_simulate_ignition")

        /** Explicit recovery clears placements, including disabled controls, never other preferences. */
        fun resetAll(prefs: SharedPreferences) {
            prefs.edit().also { editor ->
                for (key in keys) for (suffix in listOf("_x", "_y", "_parent_x", "_parent_y",
                    "_preferred_x_dp", "_preferred_y_dp")) {
                    editor.remove(key + suffix)
                }
            }.apply()
        }
    }

    private fun readFloat(suffix: String): Float = try {
        prefs.getFloat(key + suffix, 0f).finiteOrZero()
    } catch (_: ClassCastException) {
        0f
    }

    fun readOffset(geometry: OverlayButtonGeometry, defaultOffset: OverlayButtonPoint): OverlayButtonPoint {
        // Transient/unmeasured layout cannot define a migration or a placement.
        if (!geometry.isMeasured) return geometry.clampOffset(defaultOffset)
        if (prefs.contains("${key}_preferred_x_dp") && prefs.contains("${key}_preferred_y_dp")) {
            return geometry.offsetFromPreferredDp(OverlayButtonPoint(
                readFloat("_preferred_x_dp"), readFloat("_preferred_y_dp"),
            ))
        }
        val recovered = if (prefs.contains("${key}_parent_x") && prefs.contains("${key}_parent_y")) {
            geometry.offsetFromFraction(OverlayButtonPoint(readFloat("_parent_x"), readFloat("_parent_y")))
        } else if (prefs.contains("${key}_x") || prefs.contains("${key}_y")) {
            // Preserve legacy deltas relative to the current measured column anchor.
            geometry.clampOffset(OverlayButtonPoint(readFloat("_x"), readFloat("_y")))
        } else {
            // Layout alone must never customize an untouched default.
            return geometry.clampOffset(defaultOffset)
        }
        saveOffset(geometry, recovered)
        return recovered
    }

    /** Call only after a real drag; readOffset handles one-time migration itself. */
    fun saveOffset(geometry: OverlayButtonGeometry, offset: OverlayButtonPoint) {
        if (!geometry.isMeasured) return
        val preferred = geometry.preferredDpFromOffset(offset)
        val fraction = geometry.fractionFromOffset(offset)
        prefs.edit()
            .putFloat("${key}_preferred_x_dp", preferred.x)
            .putFloat("${key}_preferred_y_dp", preferred.y)
            // Retain V2 for older builds; V3 is authoritative in this build.
            .putFloat("${key}_parent_x", fraction.x)
            .putFloat("${key}_parent_y", fraction.y)
            .apply()
    }
}
