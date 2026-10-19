package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.projection.LogUploadState
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.LocalOalUiScalePercent
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
class ProjectionOverlayLayoutTest {
    @get:Rule val compose = createOalComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
    private val labels = listOf("Settings", "Switch Phone", "Stats for nerds", "Reconnect",
        "File Logging", "Upload Logs", "Simulate ignition cycle")

    @Before fun clearPositions() {
        prefs.edit().clear().commit()
        // Position/action tests run after teaching; first-use behavior has its own suite.
        RuntimeEnvironment.getApplication().getSharedPreferences("overlay_onboarding", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("menu_drag_hint_dismissed", true).commit()
    }

    private fun openMenu() { compose.onNodeWithTag("settingsButton").performTouchInput { click() } }
    private fun tapAction(label: String) {
        compose.onNodeWithTag("overlayRecoveryAction:$label").performScrollTo().performTouchInput { click() }
    }

    @Test fun `single menu ignores legacy visibility toggles and all actions work after resize`() {
        val height = mutableStateOf(700.dp)
        val clicked = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalOalUiScalePercent provides 100) {
                MaterialTheme {
                    Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                        ProjectionOverlayControls(
                            ProjectionUiState(overlayStatsButton = false, overlayPhoneSwitchButton = false,
                                overlayReconnectButton = false, simulateIgnitionButton = true,
                                fileLoggingEnabled = true, uploadEnabled = true),
                            onSettings = { clicked += "Settings" }, onStats = { clicked += "Stats for nerds" },
                            onReconnect = { clicked += "Reconnect" }, onSwitchPhone = { clicked += "Switch Phone" },
                            onFileLogging = { clicked += "File Logging" }, onUpload = { clicked += "Upload Logs" },
                            onSimulateIgnition = { clicked += "Simulate ignition cycle" },
                        )
                    }
                }
            }
        }
        for (next in listOf(240.dp, 700.dp, 100.dp)) {
            compose.runOnIdle { height.value = next; clicked.clear() }
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNodeWithTag("overlayRecoveryButton").assertDoesNotExist()
            compose.onNodeWithContentDescription("OAL controls", useUnmergedTree = true).assertIsDisplayed()
            for (label in labels) {
                openMenu()
                tapAction(label)
                compose.runOnIdle { assertEquals(label, clicked.lastOrNull()) }
                if (label == "Upload Logs") tapAction("Close")
            }
            compose.runOnIdle { assertEquals(labels, clicked) }
        }
    }

    @Test fun `ignition action follows maintainer enable flag with a supported callback`() {
        val enabled = mutableStateOf(false)
        var ignitionCycles = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 400.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(simulateIgnitionButton = enabled.value),
                        onSettings = {}, onStats = {}, onReconnect = null,
                        onSimulateIgnition = { ignitionCycles++ })
                }
            }
        }
        openMenu()
        compose.onNodeWithTag("overlayRecoveryAction:Simulate ignition cycle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, ignitionCycles); enabled.value = true }
        compose.onNodeWithTag("overlayRecoveryAction:Simulate ignition cycle")
            .performScrollTo().assertIsDisplayed()
        tapAction("Simulate ignition cycle")
        compose.runOnIdle { assertEquals(1, ignitionCycles) }
        openMenu()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("overlayRecoveryAction:Simulate ignition cycle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, ignitionCycles) }
    }

    @Test fun `ignition action stays absent without a callback even when enabled`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 400.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(simulateIgnitionButton = true),
                        onSettings = {}, onStats = {}, onReconnect = null,
                        onSimulateIgnition = null)
                }
            }
        }
        openMenu()
        compose.onNodeWithTag("overlayRecoveryAction:Simulate ignition cycle").assertDoesNotExist()
    }

    @Test fun `unsupported callbacks and actual logging upload flags gate menu actions`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 400.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(fileLoggingEnabled = false, uploadEnabled = false),
                        onSettings = {}, onStats = {}, onReconnect = null,
                        onFileLogging = {}, onUpload = {})
                }
            }
        }
        openMenu()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Stats for nerds").assertIsDisplayed()
        for (label in labels - setOf("Settings", "Stats for nerds")) {
            compose.onNodeWithTag("overlayRecoveryAction:$label").assertDoesNotExist()
        }
    }

    @Test fun `menu shows current stats logging switching and upload status`() {
        val upload = mutableStateOf(LogUploadState.UPLOADING)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 400.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(showStats = true, fileLoggingEnabled = true,
                        fileLoggingActive = true, uploadEnabled = true, uploadState = upload.value),
                        onSettings = {}, onStats = {}, onReconnect = null,
                        onSwitchPhone = {}, carHotspotSwitching = true, onFileLogging = {}, onUpload = {})
                }
            }
        }
        openMenu()
        for ((label, status) in listOf("Switch Phone" to "Switching phone", "Stats for nerds" to "Visible",
                "File Logging" to "Recording", "Upload Logs" to "Uploading")) {
            compose.onNodeWithTag("overlayRecoveryAction:$label").performScrollTo()
            compose.onNodeWithText(status, useUnmergedTree = true).assertIsDisplayed()
        }
        for ((state, status) in listOf(LogUploadState.SUCCESS to "Uploaded", LogUploadState.ERROR to "Upload failed")) {
            compose.runOnIdle { upload.value = state }
            compose.onNodeWithTag("overlayRecoveryAction:Upload Logs").performScrollTo()
            compose.onNodeWithText(status, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun `external reset refreshes live menu without clearing unrelated preferences`() {
        prefs.edit().putString("unrelated", "keep")
            .putFloat("overlay_menu_preferred_x_dp", 100f).putFloat("overlay_menu_preferred_y_dp", 100f)
            .putFloat("overlay_stats_parent_x", 0f).putFloat("overlay_stats_parent_y", 0f)
            .putFloat("overlay_upload_logs_x", -9000f).putFloat("overlay_upload_logs_y", 9000f).commit()
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { OverlayButtonPositionStore.resetAll(prefs) }
        val after = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left > before.left && after.top > before.top)
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("reset uses the safe area's center", viewport.center.x, after.center.x, 0.5f)
        assertEquals("reset uses the safe area's center", viewport.center.y, after.center.y, 0.5f)
        assertEquals(mapOf("unrelated" to "keep"), prefs.all)
        openMenu()
        tapAction("Restore button positions")
        assertEquals(mapOf("unrelated" to "keep"), prefs.all)
        assertEquals(after, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `physical long press drag persists across composition recreation without opening menu`() {
        val generation = mutableStateOf(0)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                androidx.compose.runtime.key(generation.value) {
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                }
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            down(start)
            advanceEventTime(700)
            moveTo(start - Offset(240f, 160f), delayMillis = 500)
            up()
        }
        val dragged = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue(dragged.left < before.left && dragged.top < before.top)
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        val saved = prefs.all.toMap()
        assertTrue(prefs.contains("overlay_menu_preferred_x_dp"))
        compose.runOnIdle { generation.value++ }
        assertEquals(dragged, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        assertEquals(saved, prefs.all)
        openMenu()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun `resize during physical long press drag cancels without saving stale geometry`() {
        prefs.edit().putFloat("overlay_menu_preferred_x_dp", 600f)
            .putFloat("overlay_menu_preferred_y_dp", 500f).commit()
        val saved = prefs.all.toMap()
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
            moveTo(start - Offset(160f, 96f), delayMillis = 100)
        }
        assertTrue(compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot.left < before.left)
        compose.runOnIdle { height.value = 240.dp }
        compose.onNodeWithTag("viewport").performTouchInput { up() }
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
        assertEquals(saved, prefs.all)
        assertInsideViewport()
        openMenu()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun `short swipe does not reposition button or save a placement`() {
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            swipe(start, start - Offset(200f, 100f), durationMillis = 200)
        }
        assertEquals(before, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        assertTrue(prefs.all.isEmpty())
        compose.onNodeWithTag("overlayRecoveryScroll").assertDoesNotExist()
    }

    @Test fun `physical position stays in bounds in RTL after repeated resizes`() {
        val height = mutableStateOf(700.dp)
        compose.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
                MaterialTheme { Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                } }
            }
        }
        repeat(3) {
            for (next in listOf(100.dp, 700.dp)) {
                compose.runOnIdle { height.value = next }
                assertInsideViewport()
            }
        }
    }

    private fun assertInsideViewport() {
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$button inside $viewport", button.left >= viewport.left && button.top >= viewport.top &&
            button.right <= viewport.right && button.bottom <= viewport.bottom)
    }

    @Test fun `idle transparent overlay space passes physical touches through to projection`() {
        var videoTaps = 0
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { videoTaps++ } })
                ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
            } }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(before, compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(8f, 8f)) }
        compose.runOnIdle { assertEquals(1, videoTaps) }
        openMenu()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, videoTaps) }
    }

    @Test fun `100dp dialog keeps every action restore and close physically reachable`() {
        val open = mutableStateOf(true)
        val clicked = mutableListOf<String>()
        var restores = 0
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(LocalOalUiScalePercent provides 100) {
                MaterialTheme {
                    if (open.value) ProjectionOverlayRecoveryDialog(
                        actions = labels.map { label -> OverlayControl(label,
                            androidx.compose.material.icons.Icons.Default.Info, { clicked += label }) },
                        onRestore = { restores++ },
                        onDismiss = { dismissals++; open.value = false },
                        maxHeight = 100.dp,
                    )
                }
            }
        }
        for (label in labels + listOf("Restore button positions", "Close")) {
            compose.runOnIdle { open.value = true }
            compose.onNodeWithTag("overlayRecoveryScroll").assertHeightIsEqualTo(100.dp)
            scrollRecoveryToAndTap(label)
            compose.runOnIdle { assertFalse("$label must dismiss the dialog", open.value) }
        }
        compose.runOnIdle {
            assertEquals(labels, clicked)
            assertEquals(1, restores)
            assertEquals(labels.size + 2, dismissals)
        }
    }

    private fun scrollRecoveryToAndTap(label: String) {
        val action = compose.onNodeWithTag("overlayRecoveryAction:$label")
        val viewport = compose.onNodeWithTag("overlayRecoveryScroll")
        for (attempt in 0 until 40) {
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            if (bounds.height >= 48f && bounds.width > 0f &&
                bounds.top >= visible.top && bounds.bottom <= visible.bottom) {
                action.assertIsDisplayed().performTouchInput { click() }
                return
            }
            viewport.performTouchInput {
                swipe(start = Offset(center.x, height - 8f), end = Offset(center.x, height - 40f), durationMillis = 500)
            }
        }
        fail("$label never acquired a full physical touch target in the 100dp dialog")
    }

    @Test fun `exact circular button sizes at 100 125 150 preserve physical viewport and preferred position`() {
        prefs.edit().putFloat("overlay_menu_preferred_x_dp", 120f)
            .putFloat("overlay_menu_preferred_y_dp", 100f).commit()
        val saved = prefs.all.toMap()
        val percent = mutableStateOf(100)
        compose.setContent {
            com.openautolink.app.ui.theme.OpenAutoLinkTheme(oalUiScalePercent = percent.value) {
                Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(), {}, {}, null)
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val original = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        for (scale in listOf(100, 125, 150, 100)) {
            compose.runOnIdle { percent.value = scale }
            val button = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(56f * scale / 100f, button.width, 0.01f)
            assertEquals(56f * scale / 100f, button.height, 0.01f)
            assertEquals(original.left, button.left, 0.01f)
            assertEquals(original.top, button.top, 0.01f)
            assertEquals(viewport, compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot)
            assertEquals(saved, prefs.all)
            openMenu()
            val action = compose.onNodeWithTag("overlayRecoveryAction:Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(action.height >= 48f * scale / 100f)
            tapAction("Close")
        }
    }
}
