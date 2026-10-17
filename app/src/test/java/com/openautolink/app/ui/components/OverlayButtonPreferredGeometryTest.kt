package com.openautolink.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class OverlayButtonPreferredGeometryTest {
    @Test fun `preferred conversion defaults to pixel compatible density`() {
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f)
        val offset = OverlayButtonPoint(-300f, -200f)
        assertEquals(1f, geometry.density, 0f)
        assertEquals(OverlayButtonPoint(600f, 500f), geometry.preferredDpFromOffset(offset))
        assertEquals(offset, geometry.offsetFromPreferredDp(OverlayButtonPoint(600f, 500f)))
    }

    @Test fun `preferred conversion clamps one axis without proportional motion on the other`() {
        val geometry = OverlayButtonGeometry(800f, 700f, 700f, 600f, 64f, 56f, density = 2f)
        val preferred = OverlayButtonPoint(300f, 600f)
        assertEquals(OverlayButtonPoint(-100f, 44f), geometry.offsetFromPreferredDp(preferred))
        val taller = geometry.copy(parentHeight = 1600f, anchorY = 1500f)
        assertEquals(OverlayButtonPoint(-100f, -300f), taller.offsetFromPreferredDp(preferred))
    }

    @Test fun `preferred coordinates retain all available top left travel including zero travel`() {
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f, density = 2f)
        for (point in listOf(OverlayButtonPoint(0f, 0f), OverlayButtonPoint(450f, 350f))) {
            assertEquals(point, geometry.preferredDpFromOffset(geometry.offsetFromPreferredDp(point)))
        }
        val tiny = geometry.copy(parentWidth = 20f, parentHeight = 30f)
        assertTrue("measured zero travel is not an unmeasured layout", tiny.isMeasured)
        assertEquals(OverlayButtonPoint(-900f, -700f), tiny.offsetFromPreferredDp(OverlayButtonPoint(450f, 350f)))
    }

    @Test fun `shifted safe origin moves only the occluded axis then restores preferred window coordinates`() {
        val initial = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f, density = 2f)
        val preferred = initial.preferredDpFromOffset(OverlayButtonPoint(-800f, -500f))
        assertEquals(OverlayButtonPoint(50f, 100f), preferred)
        val inset = initial.copy(parentWidth = 920f, parentHeight = 550f, anchorX = 820f, anchorY = 450f,
            originX = 80f, originY = 250f)
        val shifted = inset.offsetFromPreferredDp(preferred)
        assertEquals(100f, inset.originX + inset.anchorX + shifted.x, 0f)
        assertEquals(250f, inset.originY + inset.anchorY + shifted.y, 0f)
        assertEquals(OverlayButtonPoint(-800f, -500f), initial.offsetFromPreferredDp(preferred))
    }

    @Test fun `saving from a shifted origin includes window coordinates rather than safe local coordinates`() {
        val inset = OverlayButtonGeometry(920f, 550f, 820f, 450f, 100f, 100f,
            density = 2f, originX = 80f, originY = 250f)
        val preferred = inset.preferredDpFromOffset(OverlayButtonPoint(-800f, -400f))
        assertEquals(OverlayButtonPoint(50f, 150f), preferred)
        val initial = inset.copy(parentWidth = 1000f, parentHeight = 800f, anchorX = 900f, anchorY = 700f,
            originX = 0f, originY = 0f)
        assertEquals(OverlayButtonPoint(-800f, -400f), initial.offsetFromPreferredDp(preferred))
    }

    @Test fun `overflowing preferred pixel conversion clamps to far edge not opposite edge`() {
        val geometry = OverlayButtonGeometry(1000f, 800f, 900f, 700f, 100f, 100f, density = 2f)
        assertEquals(OverlayButtonPoint(0f, 0f), geometry.offsetFromPreferredDp(OverlayButtonPoint(Float.MAX_VALUE, Float.MAX_VALUE)))
    }
}
