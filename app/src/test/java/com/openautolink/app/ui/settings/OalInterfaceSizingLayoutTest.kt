package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.theme.OalUiScale
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class OalInterfaceSizingLayoutTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun `all rail tabs and all size choices remain reachable at 150 percent`() {
        val selected = mutableStateOf(SettingsTab.DISPLAY)
        val chosen = mutableListOf<Int>()
        var backs = 0
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                // Physical viewport stays outside the scale boundary.
                Box(Modifier.size(600.dp, 360.dp).testTag("viewport")) {
                    OalUiScale {
                        Row(Modifier.fillMaxSize()) {
                            SettingsTabRail(selectedTab = selected.value,
                                onSelectTab = { selected.value = it }, onBack = { backs++ })
                            Column(Modifier.weight(1f).fillMaxHeight()
                                .verticalScroll(rememberScrollState())) {
                                OalInterfaceSizeSetting(percent = 150, onPercentChange = { chosen += it })
                            }
                        }
                    }
                }
            }
        }
        for (tab in SettingsTab.entries.reversed()) {
            compose.onNodeWithTag("settingsTab_${tab.name}").performScrollTo()
                .assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(tab, selected.value) }
        }
        compose.onNodeWithContentDescription("Back").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backs) }
        for (percent in listOf(100, 125, 150)) {
            val node = compose.onNodeWithTag("oalUiScale_$percent").performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("scaled radio row retains a full touch target", bounds.height >= 72f)
            node.performTouchInput { click() }
            compose.runOnIdle { assertEquals(percent, chosen.lastOrNull()) }
        }
        compose.onNodeWithTag("oalUiScale_150").assertIsSelected()
    }

    @Test fun `narrow labeled control reflows instead of clipping its input`() {
        val value = mutableStateOf("")
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                Box(Modifier.size(300.dp, 300.dp).testTag("viewport")) {
                    OalUiScale {
                        SettingsLabeledControl(label = "Decoder additional depth") {
                            OutlinedTextField(value = value.value, onValueChange = { value.value = it },
                                modifier = Modifier.testTag("input"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("input").assertIsDisplayed().performTextInput("8")
        compose.runOnIdle { assertEquals("8", value.value) }
        val label = compose.onNodeWithText("Decoder additional depth").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("label stacks above field", label.bottom <= field.top)
        assertTrue("input stays inside viewport", field.right <= viewport.right && field.left >= viewport.left)
    }
}
