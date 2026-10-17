package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.projection.ProjectionUiState
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
class FlexibleOverlayTest {
    @get:Rule val compose = createOalComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
    @Before fun clear() {
        prefs.edit().clear().commit()
        // Position/action tests run after teaching; first-use behavior has its own suite.
        RuntimeEnvironment.getApplication().getSharedPreferences("overlay_onboarding", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("menu_drag_hint_dismissed", true).commit()
    }

    @Test fun `single movable menu can use top edge without a fixed recovery lane`() {
        prefs.edit().putFloat("overlay_menu_parent_x", 0f).putFloat("overlay_menu_parent_y", 0f)
            .putFloat("overlay_stats_parent_x", 0f).putFloat("overlay_stats_parent_y", 0f).commit()
        var settings = 0
        var stats = 0
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(overlayStatsButton = true), { settings++ }, { stats++ }, null)
            } }
        }
        compose.onNodeWithTag("overlayRecoveryButton").assertDoesNotExist()
        compose.onNodeWithTag("statsButton").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        val button = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("no reserved top lane: $button", button.top - viewport.top <= 4f)
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        compose.onNodeWithTag("overlayRecoveryAction:Settings").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settings); assertEquals(0, stats) }
    }

    @Test fun `menu long press drag survives resize and returns to preferred position`() {
        val height = mutableStateOf(700.dp)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            down(start)
            advanceEventTime(700)
            moveTo(start - Offset(300f, 150f), delayMillis = 500)
            up()
        }
        val preferred = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue(preferred.left < before.left && preferred.top < before.top)
        assertEquals("no forced edge docking", before.left - 300f, preferred.left, 1f)
        assertEquals("no forced edge docking", before.top - 150f, preferred.top, 1f)
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        val saved = prefs.all.toMap()
        compose.runOnIdle { height.value = 200.dp }
        val small = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("only necessary axis changes", preferred.left, small.left, 1f)
        assertTrue(small.bottom <= 200f)
        compose.runOnIdle { height.value = 700.dp }
        assertEquals(preferred, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        assertEquals(saved, prefs.all)
    }

    @Test fun `moving safe area origin preserves preferred window position without saving`() {
        prefs.edit().putFloat("overlay_menu_preferred_x_dp", 400f)
            .putFloat("overlay_menu_preferred_y_dp", 300f).commit()
        val saved = prefs.all.toMap()
        val inset = mutableStateOf(20.dp)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(1000.dp, 700.dp)) {
                Box(Modifier.absoluteOffset(x = inset.value, y = inset.value).size(700.dp, 500.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                }
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { inset.value = 100.dp }
        assertEquals(before, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        assertEquals(saved, prefs.all)
    }

    @Test fun `ordinary preference saves do not cancel an active long press drag`() {
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val start = before.center - viewport.topLeft
        compose.onNodeWithTag("viewport").performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start - Offset(100f, 100f), delayMillis = 100)
        }
        val during = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue(during.left < before.left)
        compose.runOnIdle {
            prefs.edit().putFloat("overlay_menu_preferred_x_dp", 400f)
                .putFloat("overlay_menu_preferred_y_dp", 300f).commit()
        }
        assertEquals(during, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("viewport").performTouchInput {
            moveTo(start - Offset(200f, 150f), delayMillis = 100)
            up()
        }
        val after = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left - 200f, after.left, 1f)
        assertEquals(before.top - 150f, after.top, 1f)
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        assertNotEquals(400f, prefs.getFloat("overlay_menu_preferred_x_dp", 0f))
    }
}
