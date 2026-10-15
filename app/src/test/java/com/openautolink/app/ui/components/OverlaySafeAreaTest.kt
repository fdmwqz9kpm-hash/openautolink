package com.openautolink.app.ui.components

import android.app.Application
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import com.openautolink.app.ui.testing.createOalComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Geometry tests have no Android/Robolectric dependency at execution time. */
class OverlaySafeAreaTest {
    @Test fun `fullscreen controls avoid stable chrome plus edge margin`() {
        assertEquals(IntRect(38, 48, 972, 512), overlaySafeContentBounds(
            Offset.Zero, Rect(0f, 0f, 1000f, 600f), Rect(30f, 40f, 980f, 520f), 8f))
    }

    @Test fun `already inset parent does not pay for bars twice`() {
        assertEquals(IntRect(8, 8, 942, 472), overlaySafeContentBounds(
            Offset(30f, 40f), Rect(30f, 40f, 980f, 520f), Rect(30f, 40f, 980f, 520f), 8f))
    }

    @Test fun `clipped oversized parent is translated from its unmodified origin`() {
        assertEquals(IntRect(208, 108, 492, 392), overlaySafeContentBounds(
            Offset(-100f, -50f), Rect(100f, 50f, 400f, 350f), Rect(0f, 0f, 1000f, 600f), 8f))
    }

    @Test fun `pane away from window edges gets margin not repeated system insets`() {
        assertEquals(IntRect(8, 8, 292, 192), overlaySafeContentBounds(
            Offset(400f, 200f), Rect(400f, 200f, 700f, 400f), Rect(30f, 40f, 980f, 520f), 8f))
    }

    @Test fun `fractional edges round inward in local physical coordinates`() {
        assertEquals(IntRect(9, 9, 91, 91), overlaySafeContentBounds(
            Offset(0.25f, 0.25f), Rect(0.5f, 0.5f, 99.5f, 99.5f), Rect(0f, 0f, 100f, 100f), 8f))
    }

    @Test fun `no intersection has no content bounds`() {
        assertNull(overlaySafeContentBounds(Offset.Zero,
            Rect(0f, 0f, 20f, 20f), Rect(40f, 40f, 100f, 100f), 8f))
    }

    @Test fun `empty clipped region cannot grow back into a visible rectangle`() {
        assertNull(overlaySafeContentBounds(Offset.Zero,
            Rect.Zero, Rect(0f, 0f, 100f, 100f), 8f))
    }

    @Test fun `insufficient room for margin fails closed rather than producing negative size`() {
        assertNull(overlaySafeContentBounds(Offset.Zero,
            Rect(0f, 0f, 16f, 100f), Rect(0f, 0f, 100f, 100f), 8f))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w1200dp-h900dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlaySafeAreaLayoutTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun `outer fills parent while inner follows resized parent with margin`() {
        val height = mutableStateOf(300.dp)
        compose.setContent {
            Box(Modifier.padding(100.dp).size(400.dp, height.value).testTag("parent")) {
                OverlaySafeArea(Modifier.testTag("outer")) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }
        assertMarginInsideParent()
        compose.runOnIdle { height.value = 180.dp }
        assertMarginInsideParent()
    }

    @Test fun `clipped oversized RTL parent uses physical left not mirrored start`() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.padding(100.dp).size(300.dp, 200.dp).clipToBounds().testTag("clip"),
                    contentAlignment = AbsoluteAlignment.TopLeft) {
                    Box(Modifier.offset(x = 50.dp).requiredSize(500.dp, 400.dp)) {
                        OverlaySafeArea {
                            Box(Modifier.fillMaxSize().testTag("content"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val clip = compose.onNodeWithTag("clip").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(clip.left + 2f, content.left, 0.01f)
        assertEquals(clip.top + 2f, content.top, 0.01f)
        assertEquals(clip.right - 2f, content.right, 0.01f)
        assertEquals(clip.bottom - 2f, content.bottom, 0.01f)
    }

    @Test fun `visible inset updates move controls without changing the video parent`() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().testTag("parent")) {
                OverlaySafeArea(Modifier.testTag("outer")) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }
        compose.waitForIdle()
        val parentBefore = compose.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bars = WindowInsetsCompat.Type.systemBars()
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(bars, Insets.NONE)
                .setInsetsIgnoringVisibility(bars, Insets.of(120, 90, 70, 110))
                .setVisible(bars, false)
                .build())
        }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals("hidden bars must not reserve space", parentBefore.left + 2f, first.left, 0.01f)
        assertEquals(parentBefore.top + 2f, first.top, 0.01f)
        compose.runOnIdle {
            val bars = WindowInsetsCompat.Type.systemBars()
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(bars, Insets.of(180, 150, 70, 110))
                .setInsetsIgnoringVisibility(bars, Insets.of(180, 150, 70, 110))
                .setVisible(bars, true)
                .build())
        }
        compose.waitForIdle()
        val second = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left + 180f, second.left, 0.01f)
        assertEquals(first.top + 150f, second.top, 0.01f)
        assertEquals(parentBefore, compose.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `empty overlay space passes physical touches to video`() {
        var taps = 0
        compose.setContent {
            Box(Modifier.padding(100.dp).size(400.dp, 300.dp)) {
                Box(Modifier.fillMaxSize().clickable { taps++ }.testTag("video"))
                OverlaySafeArea {
                    Box(Modifier.size(48.dp).clickable {}.testTag("control"))
                }
            }
        }
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithTag("control").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    private fun assertMarginInsideParent() {
        compose.waitForIdle()
        val parent = compose.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot
        val outer = compose.onNodeWithTag("outer").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(parent, outer)
        assertEquals(parent.left + 2f, content.left, 0.01f)
        assertEquals(parent.top + 2f, content.top, 0.01f)
        assertEquals(parent.right - 2f, content.right, 0.01f)
        assertEquals(parent.bottom - 2f, content.bottom, 0.01f)
    }
}
