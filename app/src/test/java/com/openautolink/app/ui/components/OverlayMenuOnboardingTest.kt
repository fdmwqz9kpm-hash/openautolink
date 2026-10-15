package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w1200dp-h900dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlayMenuOnboardingTest {
    @get:Rule val compose = createOalComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
    private val onboarding get() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_onboarding", Context.MODE_PRIVATE)
    private val instruction = "Hold and drag me where you want me"

    @Before fun clear() {
        prefs.edit().clear().commit()
        onboarding.edit().clear().commit()
    }

    private fun logo() = compose.onNodeWithTag("settingsButton")
    private fun bounds() = logo().fetchSemanticsNode().boundsInRoot
    private fun assertCentered(area: Rect) {
        assertEquals(area.center.x, bounds().center.x, 0.5f)
        assertEquals(area.center.y, bounds().center.y, 0.5f)
    }

    @Test fun `untouched menu centers actual visible safe area at 100 125 150 despite legacy Settings positions`() {
        prefs.edit().putFloat("overlay_settings_x", -900f).putFloat("overlay_settings_y", 800f)
            .putFloat("overlay_settings_parent_x", 0f).putFloat("overlay_settings_parent_y", 1f)
            .putFloat("overlay_settings_preferred_x_dp", 8f).putFloat("overlay_settings_preferred_y_dp", 12f).commit()
        val saved = prefs.all.toMap()
        val percent = mutableStateOf(100)
        val generation = mutableStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            OpenAutoLinkTheme(oalUiScalePercent = percent.value) {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    key(generation.value) { ProjectionOverlayControls(ProjectionUiState(), {}, {}, null) }
                }
            }
        }
        compose.runOnIdle {
            val bars = WindowInsetsCompat.Type.systemBars()
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(bars, Insets.of(180, 150, 70, 110))
                .setInsetsIgnoringVisibility(bars, Insets.of(180, 150, 70, 110))
                .setVisible(bars, true).build())
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val safe = Rect(viewport.left + 182f, viewport.top + 152f, viewport.right - 72f, viewport.bottom - 112f)
        for (scale in listOf(100, 125, 150, 100)) {
            compose.runOnIdle { percent.value = scale; generation.value++ }
            assertCentered(safe)
            assertEquals(56f * scale / 100f, bounds().width, 0.01f)
            compose.onNodeWithText(instruction).assertIsDisplayed()
            assertEquals(saved, prefs.all)
            assertTrue("showing teaching is not an acknowledgement", onboarding.all.isEmpty())
        }
    }

    @Test fun `completed physical drag dismisses persists recreates and external reset recenters without reteaching`() {
        val generation = mutableStateOf(0)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                key(generation.value) { ProjectionOverlayControls(ProjectionUiState(), {}, {}, null) }
            } }
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertCentered(viewport)
        compose.onNodeWithText(instruction).assertIsDisplayed()
        val before = bounds()
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            down(start)
            advanceEventTime(700)
            moveTo(start - Offset(200f, 120f), delayMillis = 500)
            up()
        }
        val dragged = bounds()
        assertEquals(before.left - 200f, dragged.left, 1f)
        assertEquals(before.top - 120f, dragged.top, 1f)
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        assertTrue(prefs.contains("overlay_menu_preferred_x_dp"))
        assertFalse(prefs.contains("overlay_settings_preferred_x_dp"))
        assertTrue(onboarding.getBoolean("menu_drag_hint_dismissed", false))
        val saved = prefs.all.toMap()
        logo().performTouchInput { click() }
        compose.onNodeWithTag("overlayRecoveryScroll").assertIsDisplayed()
        compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { generation.value++ }
        assertEquals(dragged, bounds())
        assertEquals(saved, prefs.all)
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        compose.runOnIdle { OverlayButtonPositionStore.resetAll(prefs) }
        assertCentered(viewport)
        assertTrue(prefs.all.isEmpty())
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        compose.runOnIdle { generation.value++ }
        assertCentered(viewport)
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        assertTrue(onboarding.getBoolean("menu_drag_hint_dismissed", false))
    }

    @Test fun `Got it acknowledgement survives recreation reconnect and reset without customizing placement`() {
        val connected = mutableStateOf(true)
        val generation = mutableStateOf(0)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                if (connected.value) key(generation.value) { ProjectionOverlayControls(ProjectionUiState(), {}, {}, null) }
            } }
        }
        val centered = bounds()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText(instruction).assertIsDisplayed()
        compose.onNodeWithTag("overlayDragHintDismiss").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        assertTrue(onboarding.getBoolean("menu_drag_hint_dismissed", false))
        compose.runOnIdle { generation.value++ }
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        compose.runOnIdle { connected.value = false }
        logo().assertDoesNotExist()
        compose.runOnIdle { connected.value = true; OverlayButtonPositionStore.resetAll(prefs) }
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        assertEquals(centered, bounds())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `stationary long press never taps or acknowledges and next physical and semantic clicks work`() {
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = bounds()
        repeat(2) { attempt ->
            logo().performTouchInput { down(center); advanceEventTime(700); up() }
            compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
            compose.onNodeWithTag("overlayDragHint").assertIsDisplayed()
            assertEquals(before, bounds())
            assertTrue(prefs.all.isEmpty())
            assertTrue(onboarding.all.isEmpty())
            // No time-based cooldown and no stale "swallow next click" flag.
            // Semantics must also work without a new physical down to reset it.
            if (attempt == 0) logo().performClick() else logo().performTouchInput { click() }
            compose.onNodeWithTag("overlayRecoveryScroll").assertIsDisplayed()
            compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        }
        compose.onNodeWithTag("overlayDragHint").assertIsDisplayed()
        assertTrue(prefs.all.isEmpty())
        assertTrue(onboarding.all.isEmpty())
    }

    @Test fun `cancelled real movement restores saved preference without acknowledging and next tap works`() {
        prefs.edit().putFloat("overlay_menu_preferred_x_dp", 400f)
            .putFloat("overlay_menu_preferred_y_dp", 300f).commit()
        val saved = prefs.all.toMap()
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = bounds()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            down(start); advanceEventTime(700)
            moveTo(start - Offset(100f, 80f), delayMillis = 100)
        }
        assertEquals(before.left - 100f, bounds().left, 1f)
        assertEquals(before.top - 80f, bounds().top, 1f)
        assertEquals(saved, prefs.all)
        assertTrue(onboarding.all.isEmpty())
        compose.onNodeWithTag("viewport").performTouchInput { cancel() }
        assertEquals(before, bounds())
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        compose.onNodeWithTag("overlayDragHint").assertIsDisplayed()
        assertEquals(saved, prefs.all)
        assertTrue(onboarding.all.isEmpty())
        logo().performTouchInput { click() }
        compose.onNodeWithTag("overlayRecoveryScroll").assertIsDisplayed()
    }

    @Test fun `tiny safe window omits bubble preserves logo and offers acknowledgement in menu`() {
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                Box(Modifier.size(110.dp, 110.dp).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                }
            }
        }
        logo().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertCentered(viewport)
        assertEquals(84f, bounds().width, 0.01f)
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        assertTrue(onboarding.all.isEmpty())
        logo().performTouchInput { click() }
        compose.onNodeWithText(instruction).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("overlayMenuHintDismiss").performScrollTo().performTouchInput { click() }
        assertTrue(onboarding.getBoolean("menu_drag_hint_dismissed", false))
        compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        logo().assertIsDisplayed()
    }

    @Test fun `nearby teaching is bounded scrollable and never covers logo or intercepts empty video`() {
        var videoTaps = 0
        val height = mutableStateOf(170.dp)
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                Box(Modifier.size(700.dp, height.value).testTag("viewport")) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { videoTaps++ } })
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("overlayDragHint").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(hint.left >= viewport.left && hint.right <= viewport.right &&
            hint.top >= viewport.top && hint.bottom <= viewport.bottom)
        assertFalse("teaching cannot cover the first drag target", hint.overlaps(bounds()))
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(8f, 8f)) }
        compose.runOnIdle { assertEquals(1, videoTaps) }
        compose.onNodeWithTag("overlayDragHintDismiss").performScrollTo().assertIsDisplayed()
        // A smaller height must not squeeze a teaching bubble over the logo.
        compose.runOnIdle { height.value = 100.dp }
        compose.onNodeWithTag("overlayDragHint").assertDoesNotExist()
        logo().assertIsDisplayed()
        assertTrue(onboarding.all.isEmpty())
    }
}
