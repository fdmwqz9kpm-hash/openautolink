package com.openautolink.app.ui.theme

import android.app.Application
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import androidx.compose.ui.unit.dp
import com.openautolink.app.input.findDialogWindowProvider
import com.openautolink.app.ui.components.OalAlertDialog
import com.openautolink.app.ui.settings.SupportOpenAutoLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class OalDialogSizingTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun `dialog slots scale exactly once and update live inside scaled screen`() {
        val percent = mutableStateOf(100)
        var confirmed = 0
        var dismissed = 0
        var dialogWindow: Window? = null
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = percent.value) {
                OalUiScale {
                    OalAlertDialog(
                        onDismissRequest = { dismissed++ },
                        title = { Box(Modifier.size(40.dp).testTag("titleProbe")) },
                        text = {
                            val view = LocalView.current
                            SideEffect { dialogWindow = findDialogWindowProvider(view)?.window }
                            OalUiScale { Box(Modifier.size(40.dp).testTag("textProbe")) }
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmed++ }, modifier = Modifier.testTag("confirm")) {
                                Box(Modifier.size(40.dp).testTag("confirmProbe"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { dismissed++ }, modifier = Modifier.testTag("dismiss")) {
                                Box(Modifier.size(40.dp).testTag("dismissProbe"))
                            }
                        },
                    )
                }
            }
        }
        for (scale in listOf(100, 125, 150, 100)) {
            compose.runOnIdle { percent.value = scale }
            for (tag in listOf("titleProbe", "textProbe", "confirmProbe", "dismissProbe")) {
                val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("$tag at $scale percent", 40f * scale / 100f, bounds.height, 0.01f)
                assertEquals("$tag at $scale percent", 40f * scale / 100f, bounds.width, 0.01f)
            }
        }
        compose.runOnIdle { assertNotNull("key capture must still find the actual dialog window", dialogWindow) }
        compose.onNodeWithTag("confirm").performTouchInput { click() }
        compose.onNodeWithTag("dismiss").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, dismissed) }
    }

    @Test fun `short scaled dialog scrolls title body and actions without enabling disabled controls`() {
        val clicked = mutableListOf<String>()
        var outsideDismissals = 0
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                OalUiScale {
                    OalAlertDialog(
                        // Constrain the actual dialog, not its unrelated Activity parent.
                        modifier = Modifier.heightIn(max = 100.dp),
                        onDismissRequest = { outsideDismissals++ },
                        title = {
                            Column {
                                TextButton(onClick = { clicked += "title" }, modifier = Modifier.heightIn(min = 48.dp).testTag("titleAction")) {
                                    Text("Title action")
                                }
                                Spacer(Modifier.height(160.dp))
                            }
                        },
                        text = {
                            Column {
                                TextButton(onClick = { clicked += "body" }, modifier = Modifier.heightIn(min = 48.dp).testTag("bodyAction")) {
                                    Text("Choose identity")
                                }
                                TextButton(enabled = false, onClick = { clicked += "disabled" },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("disabledAction")) { Text("Importing") }
                                Spacer(Modifier.height(160.dp))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { clicked += "dismiss" }, modifier = Modifier.heightIn(min = 48.dp).testTag("dismissAction")) {
                                Text("Cancel")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { clicked += "confirm" }, modifier = Modifier.heightIn(min = 48.dp).testTag("confirmAction")) {
                                Text("Assign")
                            }
                        },
                    )
                }
            }
        }
        val viewport = compose.onNodeWithTag("oalAlertDialogScroll").fetchSemanticsNode().boundsInRoot
        assertEquals("scaled 100dp viewport", 150f, viewport.height, 0.01f)
        for (tag in listOf("titleAction", "bodyAction", "disabledAction", "dismissAction", "confirmAction")) {
            scrollToAndTap(tag)
        }
        compose.onNodeWithTag("disabledAction").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("title", "body", "dismiss", "confirm"), clicked)
            assertEquals("slot callbacks must not implicitly dismiss the dialog", 0, outsideDismissals)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h240dp-mdpi")
    fun `support close stays full sized and reachable in a short window at 150 percent`() {
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                OalUiScale { SupportOpenAutoLink() }
            }
        }
        compose.onNodeWithTag("supportScan").performTouchInput { click() }
        val close = compose.onNodeWithTag("supportClose").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNodeWithTag("supportQrDialog").fetchSemanticsNode().boundsInRoot
        assertEquals("Close is scaled once, not left at platform density", 72f, close.height, 0.01f)
        assertTrue("Close remains inside the dialog", close.top >= dialog.top && close.bottom <= dialog.bottom)
        compose.onNodeWithTag("supportClose").performTouchInput { click() }
        compose.onNodeWithTag("supportQrDialog").assertDoesNotExist()
    }

    private fun scrollToAndTap(tag: String) {
        val action = compose.onNodeWithTag(tag)
        val viewport = compose.onNodeWithTag("oalAlertDialogScroll")
        repeat(60) {
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            if (bounds.height >= 72f && bounds.width > 0f && bounds.top >= visible.top && bounds.bottom <= visible.bottom) {
                action.assertIsDisplayed().performTouchInput { click() }
                return
            }
            // Physical scrolling and tapping proves reachability, unlike semantics performClick.
            viewport.performTouchInput {
                swipe(start = Offset(center.x, height - 8f), end = Offset(center.x, height - 56f), durationMillis = 500)
            }
        }
        fail("$tag never acquired a full scaled touch target in the short dialog")
    }
}
