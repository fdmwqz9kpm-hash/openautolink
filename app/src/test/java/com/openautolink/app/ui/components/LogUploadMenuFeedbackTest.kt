package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import java.io.File
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.projection.LogUploadState
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w1200dp-h900dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogUploadMenuFeedbackTest {
    @get:Rule val compose = createOalComposeRule()

    @org.junit.After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Before fun reset() {
        RuntimeEnvironment.setFontScale(1f)
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("overlay_onboarding", Context.MODE_PRIVATE).edit()
            .putBoolean("menu_drag_hint_dismissed", true).commit()
    }

    @Test fun `actual OAL theme renders amber progress green success and red safe failure text`() {
        val receipt = File("build/reports/upload-feedback/rendered-colors.txt")
        receipt.parentFile!!.mkdirs()
        receipt.writeText("")
        val state = mutableStateOf(LogUploadState.UPLOADING)
        val message = mutableStateOf<String?>(null)
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 100) { Box(Modifier.size(900.dp, 500.dp)) {
                ProjectionOverlayControls(
                    ProjectionUiState(uploadEnabled = true, uploadState = state.value,
                        uploadStatusMessage = message.value),
                    onSettings = {}, onStats = {}, onReconnect = null, onUpload = {},
                )
            } }
        }
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        val upload = compose.onNodeWithTag("overlayRecoveryAction:Upload Logs")
        for ((next, text, color) in listOf(
            Triple(LogUploadState.UPLOADING, "Uploading", Color(0xFFFFB74D)),
            Triple(LogUploadState.SUCCESS, "Uploaded: 2 files", Color(0xFF81C784)),
            Triple(LogUploadState.ERROR, "Upload failed (NETWORK). Source logs retained; check internet and retry.", Color(0xFFEF5350)),
        )) {
            compose.runOnIdle { state.value = next; message.value = text }
            upload.performScrollTo().assertIsDisplayed()
            val status = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            // TextLayoutResult is the composed glyph style, not the control model's tint.
            // Robolectric's PixelCopy captureToImage waits for an absent window redraw.
            for (node in listOf(status, compose.onNodeWithText("Upload Logs", useUnmergedTree = true))) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                val actual = layouts.single().layoutInput.style.color
                receipt.appendText("$next text=${layouts.single().layoutInput.text} actual=${actual.toArgb()} expected=${color.toArgb()}\n")
                assertEquals("$next composed text must not become disabled/typography gray", color, actual)
            }
        }
        compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        upload.performScrollTo()
        compose.onNodeWithText(message.value!!, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1200dp-h360dp-mdpi")
    fun `safe failure text remains complete and actionable at 150 percent with enlarged fonts`() {
        val message = "Upload failed (NETWORK). Source logs retained; check internet and retry."
        var uploads = 0
        RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) { Box(Modifier.size(900.dp, 320.dp)) {
                ProjectionOverlayControls(
                    ProjectionUiState(uploadEnabled = true, uploadState = LogUploadState.ERROR,
                        uploadStatusMessage = message),
                    onSettings = {}, onStats = {}, onReconnect = null, onUpload = { uploads++ },
                )
            } }
        }
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        val upload = compose.onNodeWithTag("overlayRecoveryAction:Upload Logs").performScrollTo().assertIsDisplayed()
        val text = compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val detail = "size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "constraints=${layout.layoutInput.constraints} lines=${layout.lineCount} " +
            (0 until layout.lineCount).joinToString { line ->
                "$line:left=${layout.getLineLeft(line)},right=${layout.getLineRight(line)},top=${layout.getLineTop(line)},bottom=${layout.getLineBottom(line)},end=${layout.getLineEnd(line)},ellipsized=${layout.isLineEllipsized(line)}"
            }
        val receipt = File("build/reports/upload-feedback/large-font-layout.txt")
        receipt.parentFile!!.mkdirs(); receipt.writeText(detail)
        assertEquals(1.5f, layout.layoutInput.density.fontScale, 0.01f)
        assertEquals(1.5f, layout.layoutInput.density.density, 0.01f)
        assertEquals(message, layout.layoutInput.text.text)
        assertEquals(message.length, layout.getLineEnd(layout.lineCount - 1))
        // Paragraph container width can exceed the tight glyph width without clipping.
        // Check every actual rendered line plus complete wording and the physical target.
        for (line in 0 until layout.lineCount) {
            assertTrue("Status must not ellipsize: $detail", !layout.isLineEllipsized(line))
            assertTrue("Glyph line must fit horizontally: $detail", layout.getLineLeft(line) >= -0.5f &&
                layout.getLineRight(line) <= layout.size.width + 0.5f)
            assertTrue("Glyph line must fit vertically: $detail", layout.getLineTop(line) >= -0.5f &&
                layout.getLineBottom(line) <= layout.size.height + 0.5f)
        }
        val bounds = upload.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("overlayRecoveryScroll").fetchSemanticsNode().boundsInRoot
        assertTrue("Full retry touch target must be reachable", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        upload.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, uploads) }
        compose.onNodeWithTag("overlayRecoveryScroll").assertExists()
    }

    @Test fun `upload stays open and disables physical duplicate taps until completion`() {
        val state = mutableStateOf(LogUploadState.IDLE)
        var uploads = 0
        compose.setContent {
            MaterialTheme { Box(Modifier.size(900.dp, 500.dp)) {
                ProjectionOverlayControls(
                    ProjectionUiState(uploadEnabled = true, uploadState = state.value),
                    onSettings = {}, onStats = {}, onReconnect = null,
                    onUpload = { uploads++; state.value = LogUploadState.UPLOADING },
                )
            } }
        }
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        val upload = compose.onNodeWithTag("overlayRecoveryAction:Upload Logs")
        upload.performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("overlayRecoveryScroll").assertExists()
        upload.assertIsNotEnabled()
        compose.onNodeWithText("Uploading", useUnmergedTree = true).assertIsDisplayed()
        upload.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, uploads); state.value = LogUploadState.SUCCESS }
        upload.assertIsEnabled()
        compose.onNodeWithText("Uploaded", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("overlayRecoveryAction:Close").performScrollTo().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        upload.performScrollTo()
        compose.onNodeWithText("Uploaded", useUnmergedTree = true).assertIsDisplayed()
    }
}
