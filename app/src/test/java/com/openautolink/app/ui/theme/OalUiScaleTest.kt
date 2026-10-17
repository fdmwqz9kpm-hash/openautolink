package com.openautolink.app.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.openautolink.app.ui.testing.createOalComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class OalUiScaleTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun `theme alone leaves physical siblings unchanged while wrapper scales dp and sp`() {
        val readings = mutableMapOf<String, Density>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                OpenAutoLinkTheme(oalUiScalePercent = 150) {
                    Column {
                        val unscaled = LocalDensity.current
                        SideEffect { readings["unscaled"] = unscaled }
                        Box(Modifier.size(40.dp).testTag("physicalVideo"))
                        OalUiScale {
                            val scaled = LocalDensity.current
                            SideEffect { readings["scaled"] = scaled }
                            Box(Modifier.size(40.dp).testTag("oalControl"))
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(2f, readings.getValue("unscaled").density, 0.001f)
            assertEquals(3f, readings.getValue("scaled").density, 0.001f)
            assertEquals(1.3f, readings.getValue("scaled").fontScale, 0.001f)
            assertEquals(with(readings.getValue("unscaled")) { 16.sp.toPx() } * 1.5f,
                with(readings.getValue("scaled")) { 16.sp.toPx() }, 0.001f)
        }
        assertEquals(80f, compose.onNodeWithTag("physicalVideo").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(120f, compose.onNodeWithTag("oalControl").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun `nested wrappers apply once and respond live to scale and OS density changes`() {
        val percent = mutableStateOf(100)
        val base = mutableStateOf(Density(2f, 1.3f))
        val readings = mutableMapOf<String, Density>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides base.value) {
                OpenAutoLinkTheme(oalUiScalePercent = percent.value) {
                    OalUiScale {
                        val outer = LocalDensity.current
                        SideEffect { readings["outer"] = outer }
                        OalUiScale {
                            val inner = LocalDensity.current
                            SideEffect { readings["inner"] = inner }
                        }
                    }
                }
            }
        }
        for (value in listOf(100, 125, 150, 100)) {
            compose.runOnIdle { percent.value = value }
            compose.runOnIdle {
                assertEquals(2f * value / 100f, readings.getValue("inner").density, 0.001f)
                assertEquals(readings["outer"], readings["inner"])
            }
        }
        compose.runOnIdle { percent.value = 150; base.value = Density(3f, 1.6f) }
        compose.runOnIdle {
            assertEquals(4.5f, readings.getValue("inner").density, 0.001f)
            assertEquals(1.6f, readings.getValue("inner").fontScale, 0.001f)
        }
    }
}
