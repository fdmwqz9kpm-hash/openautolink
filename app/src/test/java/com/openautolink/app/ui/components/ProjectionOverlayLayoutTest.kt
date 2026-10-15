package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import androidx.compose.ui.unit.dp
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
class ProjectionOverlayLayoutTest {
    @get:Rule val compose = createOalComposeRule()

    @Before fun clearPositions() {
        RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private val tags = listOf("settingsButton", "switchPhoneButton", "statsButton", "reconnectButton",
        "fileLogButton", "uploadLogsButton", "simulateIgnitionButton")

    @Test fun `fixed recovery works even when saved buttons overlap and resets hidden controls only`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        prefs.edit().putString("unrelated", "keep")
            .putFloat("overlay_settings_parent_x", 0f).putFloat("overlay_settings_parent_y", 0f)
            .putFloat("overlay_stats_parent_x", 0f).putFloat("overlay_stats_parent_y", 0f)
            .putFloat("overlay_upload_logs_x", -9000f).putFloat("overlay_upload_logs_y", 9000f).commit()
        var settingsOpened = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 300.dp).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(overlayStatsButton = true),
                        onSettings = { settingsOpened++ }, onStats = {}, onReconnect = null)
                }
            }
        }
        // Physical pointer taps, not semantics performClick (which bypasses occlusion).
        compose.onNodeWithTag("overlayRecoveryButton").performTouchInput { click() }
        compose.onNodeWithText("Settings").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settingsOpened) }
        compose.onNodeWithTag("overlayRecoveryButton").performTouchInput { click() }
        compose.onNodeWithText("Restore button positions").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(mapOf("unrelated" to "keep"), prefs.all)
        }
        val settings = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val stats = compose.onNodeWithTag("statsButton").fetchSemanticsNode().boundsInRoot
        assertFalse("restored controls must not overlap", settings.overlaps(stats))
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, settingsOpened) }
    }

    @Test fun `physical drag saves position and restores it after composition recreation`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        val generation = mutableStateOf(0)
        var settingsOpened = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 700.dp).testTag("viewport")) {
                    androidx.compose.runtime.key(generation.value) {
                        ProjectionOverlayControls(ProjectionUiState(),
                            onSettings = { settingsOpened++ }, onStats = {}, onReconnect = null)
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            swipe(start, start - androidx.compose.ui.geometry.Offset(240f, 160f), durationMillis = 500)
        }
        val dragged = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue("physical drag must move the rendered control", dragged.left < before.left && dragged.top < before.top)
        val saved = prefs.all.toMap()
        compose.runOnIdle {
            assertEquals("drag must not activate Settings", 0, settingsOpened)
            assertTrue(prefs.contains("overlay_settings_parent_x"))
            assertTrue(prefs.contains("overlay_settings_parent_y"))
            generation.value++
        }
        val restored = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(dragged.left, restored.left, 1f)
        assertEquals(dragged.top, restored.top, 1f)
        assertEquals(saved, prefs.all)
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settingsOpened) }
    }

    @Test fun `resize during physical drag cancels gesture without saving stale geometry`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        prefs.edit().putFloat("overlay_settings_parent_x", 0.75f)
            .putFloat("overlay_settings_parent_y", 0.75f).commit()
        val saved = prefs.all.toMap()
        val height = mutableStateOf(700.dp)
        var settingsOpened = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(),
                        onSettings = { settingsOpened++ }, onStats = {}, onReconnect = null)
                }
            }
        }
        val before = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            val start = before.center - viewport.topLeft
            down(start)
            moveTo(start - androidx.compose.ui.geometry.Offset(160f, 96f), delayMillis = 100)
        }
        val duringDrag = compose.onNodeWithTag("settingsButton").fetchSemanticsNode().boundsInRoot
        assertTrue("gesture must really be dragging before resize", duringDrag.left < before.left)
        compose.runOnIdle { height.value = 240.dp }
        // Keep the pointer down across remeasurement, then release on the same root.
        compose.onNodeWithTag("viewport").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals("canceled drag must not activate Settings", 0, settingsOpened)
            assertEquals("canceled drag must not persist old geometry", saved, prefs.all)
        }
        val resizedViewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val resized = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(resized.left >= resizedViewport.left && resized.top >= resizedViewport.top &&
            resized.right <= resizedViewport.right && resized.bottom <= resizedViewport.bottom)
        compose.onNodeWithTag("overlayRecoveryButton").performTouchInput { click() }
        compose.onNodeWithText("Settings").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settingsOpened) }
    }

    @Test fun `physical positions stay in bounds in RTL after repeated resizes`() {
        val height = mutableStateOf(700.dp)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                        ProjectionOverlayControls(ProjectionUiState(overlayStatsButton = true),
                            onSettings = {}, onStats = {}, onReconnect = null)
                    }
                }
            }
        }
        repeat(3) {
            for (next in listOf(240.dp, 700.dp)) {
                compose.runOnIdle { height.value = next }
                val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
                for (tag in listOf("settingsButton", "statsButton", "overlayRecoveryButton")) {
                    val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("$tag $bounds is inside $viewport", bounds.left >= viewport.left &&
                        bounds.top >= viewport.top && bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
                }
            }
        }
    }

    @Test fun `each default control has a visible icon and receives physical taps after resize`() {
        val height = mutableStateOf(700.dp)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                    ProjectionOverlayControls(
                        ProjectionUiState(overlayStatsButton = true, overlayPhoneSwitchButton = true,
                            overlayReconnectButton = true, fileLoggingEnabled = true, uploadEnabled = true,
                            simulateIgnitionButton = true),
                        onSettings = { clicked += "settingsButton" }, onStats = { clicked += "statsButton" },
                        onReconnect = { clicked += "reconnectButton" }, onSwitchPhone = { clicked += "switchPhoneButton" },
                        onFileLogging = { clicked += "fileLogButton" }, onUpload = { clicked += "uploadLogsButton" },
                        onSimulateIgnition = { clicked += "simulateIgnitionButton" },
                    )
                }
            }
        }
        for (next in listOf(240.dp, 700.dp, 240.dp)) {
            compose.runOnIdle { height.value = next; clicked.clear() }
            for (tag in tags) {
                compose.onNodeWithTag(tag).performTouchInput { click() }
                compose.runOnIdle { assertEquals("physical tap must reach $tag", tag, clicked.lastOrNull()) }
            }
            for (label in listOf("Settings", "Switch Phone", "Stats for nerds", "Reconnect",
                    "File Logging", "Upload Logs", "Simulate ignition cycle")) {
                val bounds = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("$label icon width", 24f, bounds.width, 0.01f)
                assertEquals("$label icon height", 24f, bounds.height, 0.01f)
            }
        }
    }

    @Test fun `100dp dialog keeps every action restore and close physically reachable`() {
        val open = mutableStateOf(true)
        val clicked = mutableListOf<String>()
        val labels = listOf("Settings", "Switch Phone", "Stats for nerds", "Reconnect",
            "File Logging", "Upload Logs", "Simulate ignition cycle")
        var restores = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                if (open.value) ProjectionOverlayRecoveryDialog(
                    actions = labels.map { label -> label to { clicked += label; Unit } },
                    onRestore = { restores++ },
                    onDismiss = { dismissals++; open.value = false },
                    // Constrain the DIALOG itself, not the unrelated activity content Box.
                    maxHeight = 100.dp,
                )
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
        // Real swipes and taps: semantics performClick/performScrollTo can bypass
        // occlusion and would not establish that a user can recover in this window.
        for (attempt in 0 until 40) {
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            if (bounds.height >= 48f && bounds.width > 0f &&
                bounds.top >= visible.top && bounds.bottom <= visible.bottom) {
                action.assertIsDisplayed().performTouchInput { click() }
                return
            }
            viewport.performTouchInput {
                swipe(start = androidx.compose.ui.geometry.Offset(center.x, height - 8f),
                    end = androidx.compose.ui.geometry.Offset(center.x, height - 40f),
                    durationMillis = 500)
            }
        }
        fail("$label never acquired a full physical touch target in the 100dp dialog")
    }

    @Test fun `tiny projection area exposes fixed menu without shrunken buttons`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(180.dp, 100.dp)) {
                    ProjectionOverlayControls(ProjectionUiState(overlayStatsButton = true),
                        onSettings = {}, onStats = {}, onReconnect = null)
                }
            }
        }
        compose.onNodeWithTag("settingsButton").assertDoesNotExist()
        compose.onNodeWithTag("overlayRecoveryButton").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Stats for nerds").assertIsDisplayed()
        compose.onNodeWithText("Restore button positions").assertIsDisplayed()
    }

    @Test fun `live OAL sizing enlarges controls without changing physical viewport or losing recovery`() {
        val percent = mutableStateOf(100)
        compose.setContent {
            com.openautolink.app.ui.theme.OpenAutoLinkTheme(oalUiScalePercent = percent.value) {
                Box(Modifier.size(900.dp, 400.dp).testTag("viewport")) {
                    ProjectionOverlayControls(ProjectionUiState(overlayStatsButton = true),
                        onSettings = {}, onStats = {}, onReconnect = null)
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        for (scale in listOf(100, 125, 150, 100)) {
            compose.runOnIdle { percent.value = scale }
            val settings = compose.onNodeWithTag("settingsButton").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(64f * scale / 100f, settings.width, 1f)
            assertEquals(56f * scale / 100f, settings.height, 1f)
            assertEquals(viewport, compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("overlayRecoveryButton").assertIsDisplayed().performTouchInput { click() }
            val action = compose.onNodeWithTag("overlayRecoveryAction:Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("dialog action follows $scale percent: $action", action.height >= 48f * scale / 100f)
            compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        }
    }

    @Test fun `all enabled controls remain full sized and reachable after height shrinks`() {
        val height = mutableStateOf(700.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, height.value).testTag("viewport")) {
                    ProjectionOverlayControls(
                        ProjectionUiState(overlayStatsButton = true, overlayPhoneSwitchButton = true,
                            overlayReconnectButton = true, fileLoggingEnabled = true, uploadEnabled = true,
                            simulateIgnitionButton = true),
                        onSettings = {}, onStats = {}, onReconnect = {}, onSwitchPhone = {},
                        onFileLogging = {}, onUpload = {}, onSimulateIgnition = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { height.value = 240.dp }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("$tag has a full touch target, got $bounds", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("$tag inside $viewport, got $bounds", bounds.left >= viewport.left &&
                bounds.top >= viewport.top && bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
        }
    }
}
