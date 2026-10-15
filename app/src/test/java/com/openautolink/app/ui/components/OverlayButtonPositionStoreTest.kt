package com.openautolink.app.ui.components

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlayButtonPositionStoreTest {
    @Test fun applicationFixtureDoesNotBootProcessServices() {
        // Config.NONE is ignored by Robolectric's AGP Build System API. Check
        // the actual application, not just the annotation: OalApplication starts
        // process-owned IO work that can outlive this test's Android sandbox.
        assertEquals(
            "This fixture must not boot OalApplication process services",
            android.app.Application::class.java,
            org.robolectric.RuntimeEnvironment.getApplication().javaClass,
        )
    }

    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test fun `enabling Stats keeps untouched Settings in its nonoverlapping default stack slot`() {
        val prefs = preferences()
        val default = OverlayButtonPoint(0f, 0f)
        val settings = OverlayButtonPositionStore(prefs, "overlay_settings")
        val settingsOnly = OverlayButtonGeometry(1200f, 700f, 1120f, 628f, 64f, 56f)
        settings.readOffset(settingsOnly, default)

        // Actual Column layout: inserting Stats adds its 56px height plus 8px spacer.
        val settingsWithStats = settingsOnly.copy(anchorY = 564f)
        val settingsOffset = settings.readOffset(settingsWithStats, default)
        val statsOffset = OverlayButtonPositionStore(prefs, "overlay_stats").readOffset(settingsOnly, default)
        val settingsTop = settingsWithStats.anchorY + settingsOffset.y
        val statsTop = settingsOnly.anchorY + statsOffset.y
        assertTrue("Stats must not cover untouched Settings", settingsTop + settingsWithStats.buttonHeight <= statsTop)
        assertEquals("Settings follows its natural stack anchor", 564f, settingsTop, 0.001f)
        assertTrue("layout alone must not persist a customized position", prefs.all.isEmpty())
    }

    @Test fun `untouched offscreen defaults clamp without saving across all shared controls`() {
        val prefs = preferences()
        val system = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val full = OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f)
        for (key in listOf("overlay_settings", "overlay_switch_phone", "overlay_stats", "overlay_reconnect",
            "overlay_file_log", "overlay_upload_logs", "overlay_simulate_ignition")) {
            val store = OverlayButtonPositionStore(prefs, key)
            for (geometry in listOf(system, full, system)) {
                val offset = store.readOffset(geometry, OverlayButtonPoint(-1500f, -5000f))
                val left = geometry.anchorX + offset.x
                val top = geometry.anchorY + offset.y
                assertTrue(left >= 0f && left + geometry.buttonWidth <= geometry.parentWidth)
                assertTrue(top >= 0f && top + geometry.buttonHeight <= geometry.parentHeight)
            }
        }
        assertTrue("safety clamping must not customize untouched defaults", prefs.all.isEmpty())
    }

    @Test fun `real drag after untouched layout preserves preferred top left across modes and stack changes`() {
        val prefs = preferences()
        val default = OverlayButtonPoint(0f, 0f)
        val system = OverlayButtonGeometry(1200f, 700f, 1120f, 628f, 64f, 56f)
        val fullWithStats = OverlayButtonGeometry(1900f, 1100f, 1810f, 972f, 64f, 56f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.readOffset(system, default)
        assertTrue(prefs.all.isEmpty())
        val drag = OverlayButtonPoint(-550f, -200f)
        store.saveOffset(system, drag)
        val preferred = OverlayButtonPoint(system.anchorX + drag.x, system.anchorY + drag.y)
        val saved = prefs.all.toMap()
        for (geometry in listOf(fullWithStats, system.copy(anchorY = 564f), system)) {
            val restored = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, default)
            assertEquals(preferred.x, geometry.anchorX + restored.x, 0.001f)
            assertEquals(preferred.y, geometry.anchorY + restored.y, 0.001f)
        }
        assertEquals("mode changes must not rewrite the preferred position", saved, prefs.all)
    }

    @Test fun `legacy traps recover on first layout for settings info and upload without settings access`() {
        val prefs = preferences()
        prefs.edit().putString("unrelated", "preserve me").commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        for ((key, legacy) in listOf(
            "overlay_settings" to OverlayButtonPoint(1700f, 1400f),
            "overlay_stats" to OverlayButtonPoint(1700f, -1400f),
            "overlay_upload_logs" to OverlayButtonPoint(-1800f, -1400f),
        )) {
            prefs.edit().putFloat("${key}_x", legacy.x).putFloat("${key}_y", legacy.y).commit()
            val recovered = OverlayButtonPositionStore(prefs, key).readOffset(geometry, OverlayButtonPoint(0f, 0f))
            val left = geometry.anchorX + recovered.x
            val top = geometry.anchorY + recovered.y
            assertTrue(left >= 0f && left + geometry.buttonWidth <= geometry.parentWidth)
            assertTrue(top >= 0f && top + geometry.buttonHeight <= geometry.parentHeight)
            assertTrue(prefs.contains("${key}_parent_x"))
            assertTrue(prefs.contains("${key}_parent_y"))
        }
        assertEquals("preserve me", prefs.getString("unrelated", null))
    }

    @Test fun `edge drag remains reachable and stable across repeated mode inset size and anchor changes`() {
        val prefs = preferences()
        val full = OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f)
        val system = OverlayButtonGeometry(1500f, 850f, 1410f, 480f, 80f, 72f)
        for (key in listOf("overlay_settings", "overlay_stats", "overlay_upload_logs")) {
            val store = OverlayButtonPositionStore(prefs, key)
            store.saveOffset(system, OverlayButtonPoint(5000f, -5000f))
            val saved = prefs.all.toMap()
            repeat(10) {
                for (geometry in listOf(full, system, full.copy(anchorY = 990f))) {
                    val restored = OverlayButtonPositionStore(prefs, key).readOffset(geometry, OverlayButtonPoint(0f, 0f))
                    assertEquals(system.parentWidth - system.buttonWidth, geometry.anchorX + restored.x, 0.01f)
                    assertEquals(0f, geometry.anchorY + restored.y, 0.01f)
                }
            }
            assertEquals("relayout is not a preference reset or repeated write", saved, prefs.all)
        }
    }

    @Test fun `malformed normalized positions cannot trap controls`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_parent_x", Float.NaN)
            .putFloat("overlay_settings_parent_y", Float.NEGATIVE_INFINITY).commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val restored = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertTrue(restored.x.isFinite() && restored.y.isFinite())
    }

    @Test fun `reachable legacy custom placement is migrated without a default reset`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_x", -550f).putFloat("overlay_settings_y", -200f).commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val offset = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertEquals(OverlayButtonPoint(-550f, -200f), offset)
        val reopened = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertEquals(offset.x, reopened.x, 0.001f)
        assertEquals(offset.y, reopened.y, 0.001f)
    }

    private fun assertTopLeft(geometry: OverlayButtonGeometry, offset: OverlayButtonPoint, x: Float, y: Float) {
        assertEquals(x, geometry.anchorX + offset.x, 0.001f)
        assertEquals(y, geometry.anchorY + offset.y, 0.001f)
    }

    @Test fun `tall small tall restores preference with only necessary per axis correction`() {
        val prefs = preferences()
        val tall = OverlayButtonGeometry(1200f, 1600f, 1100f, 1500f, 64f, 56f)
        val small = tall.copy(parentWidth = 800f, parentHeight = 700f, anchorX = 700f, anchorY = 600f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(tall, OverlayButtonPoint(-500f, -300f))
        val saved = prefs.all.toMap()
        repeat(3) {
            assertTopLeft(small, store.readOffset(small, OverlayButtonPoint(0f, 0f)), 600f, 644f)
            assertTopLeft(tall, store.readOffset(tall, OverlayButtonPoint(0f, 0f)), 600f, 1200f)
        }
        assertEquals(saved, prefs.all)
    }

    @Test fun `real drag while constrained replaces old preferred location`() {
        val prefs = preferences()
        val tall = OverlayButtonGeometry(1200f, 1600f, 1100f, 1500f, 64f, 56f)
        val small = tall.copy(parentHeight = 700f, anchorY = 600f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(tall, OverlayButtonPoint(-500f, -300f))
        store.readOffset(small, OverlayButtonPoint(0f, 0f))
        store.saveOffset(small, OverlayButtonPoint(-700f, -100f))
        assertTopLeft(tall, store.readOffset(tall, OverlayButtonPoint(0f, 0f)), 400f, 500f)
    }

    @Test fun `safe origin changes preserve window preference without writes or unnecessary axis movement`() {
        val prefs = preferences()
        val initial = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f, density = 2f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(initial, OverlayButtonPoint(-800f, -500f))
        val saved = prefs.all.toMap()
        val inset = initial.copy(parentWidth = 920f, parentHeight = 550f, anchorX = 820f, anchorY = 450f,
            originX = 80f, originY = 250f)
        assertTopLeft(inset, store.readOffset(inset, OverlayButtonPoint(0f, 0f)), 20f, 0f)
        assertTopLeft(initial, store.readOffset(initial, OverlayButtonPoint(0f, 0f)), 100f, 200f)
        assertEquals(saved, prefs.all)
    }

    @Test fun `preferred dp follows base density but button sizing does not scale coordinates`() {
        val prefs = preferences()
        val geometry = OverlayButtonGeometry(1200f, 1600f, 1100f, 1500f, 64f, 56f, density = 2f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(geometry, OverlayButtonPoint(-500f, -300f))
        assertEquals(300f, prefs.getFloat("overlay_settings_preferred_x_dp", -1f), 0f)
        assertEquals(600f, prefs.getFloat("overlay_settings_preferred_y_dp", -1f), 0f)
        assertTrue("retain backward compatible V2 writes", prefs.contains("overlay_settings_parent_x"))
        val saved = prefs.all.toMap()
        val largerButtons = geometry.copy(buttonWidth = 180f, buttonHeight = 240f, anchorY = 1000f)
        assertTopLeft(largerButtons, store.readOffset(largerButtons, OverlayButtonPoint(0f, 0f)), 600f, 1200f)
        val densityChanged = geometry.copy(density = 1f, anchorX = 1000f, anchorY = 1100f)
        assertTopLeft(densityChanged, store.readOffset(densityChanged, OverlayButtonPoint(0f, 0f)), 300f, 600f)
        assertEquals(saved, prefs.all)
    }

    @Test fun `button growth clamps display only then shrinking restores preferred position`() {
        val prefs = preferences()
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 50f, 50f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(geometry, OverlayButtonPoint(40f, 40f))
        val saved = prefs.all.toMap()
        val larger = geometry.copy(buttonWidth = 200f, buttonHeight = 200f)
        assertTopLeft(larger, store.readOffset(larger, OverlayButtonPoint(0f, 0f)), 800f, 600f)
        assertTopLeft(geometry, store.readOffset(geometry, OverlayButtonPoint(0f, 0f)), 940f, 740f)
        assertEquals(saved, prefs.all)
    }

    @Test fun `V2 migrates once on first valid measured geometry then stops proportional relocation`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_parent_x", 0.5f)
            .putFloat("overlay_settings_parent_y", 0.75f).commit()
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        val measured = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f, density = 2f)
        val before = prefs.all.toMap()
        store.readOffset(measured.copy(parentWidth = 0f, parentHeight = 0f), OverlayButtonPoint(0f, 0f))
        assertEquals(before, prefs.all)
        assertTopLeft(measured, store.readOffset(measured, OverlayButtonPoint(0f, 0f)), 450f, 525f)
        assertEquals(225f, prefs.getFloat("overlay_settings_preferred_x_dp", -1f), 0f)
        assertEquals(262.5f, prefs.getFloat("overlay_settings_preferred_y_dp", -1f), 0f)
        val saved = prefs.all.toMap()
        val larger = measured.copy(parentWidth = 2000f, parentHeight = 1600f)
        assertTopLeft(larger, store.readOffset(larger, OverlayButtonPoint(0f, 0f)), 450f, 525f)
        assertEquals(saved, prefs.all)
    }

    @Test fun `legacy migration and real drag wait for measured finite geometry`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_x", -100f).putFloat("overlay_settings_y", -200f).commit()
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        val measured = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f)
        val before = prefs.all.toMap()
        for (invalid in listOf(measured.copy(parentWidth = 0f), measured.copy(parentHeight = 0f),
            measured.copy(buttonWidth = 0f), measured.copy(buttonHeight = 0f),
            measured.copy(parentWidth = Float.NaN), measured.copy(anchorY = Float.NaN),
            measured.copy(originX = Float.NaN), measured.copy(originY = Float.POSITIVE_INFINITY),
            measured.copy(density = 0f), measured.copy(density = Float.POSITIVE_INFINITY))) {
            val offset = store.readOffset(invalid, OverlayButtonPoint(0f, 0f))
            assertTrue(offset.x.isFinite() && offset.y.isFinite())
            store.saveOffset(invalid, OverlayButtonPoint(100f, 100f))
            assertEquals("transient layout cannot overwrite a placement", before, prefs.all)
        }
        assertTopLeft(measured, store.readOffset(measured, OverlayButtonPoint(0f, 0f)), 800f, 500f)
        assertTrue(prefs.contains("overlay_settings_preferred_x_dp"))
    }

    @Test fun `zero travel temporarily displays origin without destroying preferred location`() {
        val prefs = preferences()
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.saveOffset(geometry, OverlayButtonPoint(-300f, -200f))
        val saved = prefs.all.toMap()
        val tiny = geometry.copy(parentWidth = 20f, parentHeight = 30f)
        assertTopLeft(tiny, store.readOffset(tiny, OverlayButtonPoint(0f, 0f)), 0f, 0f)
        assertTopLeft(geometry, store.readOffset(geometry, OverlayButtonPoint(0f, 0f)), 600f, 500f)
        assertEquals(saved, prefs.all)
    }

    @Test fun `corrupt preferred and legacy preference types cannot crash or trap controls`() {
        val prefs = preferences()
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        for (suffixes in listOf("_preferred_x_dp" to "_preferred_y_dp", "_parent_x" to "_parent_y", "_x" to "_y")) {
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
                prefs.edit().clear().putFloat("overlay_settings${suffixes.first}", value)
                    .putString("overlay_settings${suffixes.second}", "not a float").commit()
                val offset = store.readOffset(geometry.copy(density = 2f), OverlayButtonPoint(0f, 0f))
                assertTrue(offset.x.isFinite() && offset.y.isFinite())
                assertTrue(geometry.anchorX + offset.x in 0f..900f)
                assertTrue(geometry.anchorY + offset.y in 0f..700f)
            }
        }
    }

    @Test fun `reset clears all known placement versions including hidden controls only`() {
        val prefs = preferences()
        val keys = listOf("overlay_menu", "overlay_settings", "overlay_switch_phone", "overlay_stats", "overlay_reconnect",
            "overlay_file_log", "overlay_upload_logs", "overlay_simulate_ignition")
        val editor = prefs.edit().putString("unrelated", "keep").putBoolean("overlay_stats_enabled", true)
            .putFloat("custom_preferred_x_dp", 123f)
        for (key in keys) for (suffix in listOf("_x", "_y", "_parent_x", "_parent_y", "_preferred_x_dp", "_preferred_y_dp")) {
            editor.putFloat(key + suffix, 0.5f)
        }
        editor.commit()
        OverlayButtonPositionStore.resetAll(prefs)
        assertEquals(mapOf("unrelated" to "keep", "overlay_stats_enabled" to true, "custom_preferred_x_dp" to 123f), prefs.all)
    }

    @Test fun `persisted drag uses parent basis rather than optional column anchor`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        val before = OverlayButtonGeometry(1200f, 700f, 1120f, 100f, 64f, 56f)
        store.saveOffset(before, OverlayButtonPoint(-500f, 200f))
        val after = before.copy(anchorY = 300f)
        val reopened = OverlayButtonPositionStore(prefs, "overlay_settings")
        val offset = reopened.readOffset(after, OverlayButtonPoint(0f, 0f))
        assertEquals("optional buttons must not move a saved button", 300f, after.anchorY + offset.y, 0.01f)
    }
}
