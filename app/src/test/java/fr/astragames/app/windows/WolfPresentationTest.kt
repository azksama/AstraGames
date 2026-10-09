package fr.astragames.app.windows

import com.winlator.renderer.ViewTransformation
import org.junit.Assert.*
import org.junit.Test

class WolfPresentationTest {
    @Test fun wholeImageUsesTheGameRatioEvenOnAWidescreenDesktop() {
        val view = ViewTransformation()
        view.update(2400, 1080, 1280, 720, 800, 600, WolfImageMode.FIT.ordinal)
        assertEquals(480, view.viewOffsetX)
        assertEquals(0, view.viewOffsetY)
        assertEquals(1440, view.viewWidth)
        assertEquals(1080, view.viewHeight)
    }

    @Test fun portraitPreservesTheWholeImageWithoutASecondMargin() {
        val view = ViewTransformation()
        view.update(1080, 2400, 1280, 960, 800, 600, WolfImageMode.FIT.ordinal)
        assertEquals(0, view.viewOffsetX)
        assertEquals(795, view.viewOffsetY)
        assertEquals(1080, view.viewWidth)
        assertEquals(810, view.viewHeight)
    }

    @Test fun nativeWidescreenImageFillsMatchingScreen() {
        val view = ViewTransformation()
        view.update(1920, 1080, 1280, 960, 960, 540, WolfImageMode.FIT.ordinal)
        assertEquals(0, view.viewOffsetX)
        assertEquals(0, view.viewOffsetY)
        assertEquals(1920, view.viewWidth)
        assertEquals(1080, view.viewHeight)
    }

    @Test fun fillingCropsWithoutChangingProportions() {
        val view = ViewTransformation()
        view.update(1920, 1080, 1280, 720, 800, 600, WolfImageMode.FILL.ordinal)
        assertEquals(1920, view.viewWidth)
        assertEquals(1440, view.viewHeight)
        assertEquals(0, view.viewOffsetX)
        assertEquals(-180, view.viewOffsetY)
        view.update(1080, 2400, 1280, 960, 800, 600, WolfImageMode.FILL.ordinal)
        assertEquals(3200, view.viewWidth)
        assertEquals(-1060, view.viewOffsetX)
        assertEquals(0, view.viewOffsetY)
    }

    @Test fun stretchingFillsThePhysicalScreenInEitherOrientation() {
        val view = ViewTransformation()
        for ((width, height) in listOf(2400 to 1080, 1080 to 2400)) {
            view.update(width, height, 1280, 960, 800, 600, WolfImageMode.STRETCH.ordinal)
            assertEquals(width, view.viewWidth)
            assertEquals(height, view.viewHeight)
            assertEquals(0, view.viewOffsetX)
            assertEquals(0, view.viewOffsetY)
        }
    }

    @Test fun automaticDesktopDependsOnLaunchOrientationAndPreservesExplicitChoices() {
        assertEquals("1280x720", WolfOptions().windowsResolution(true))
        assertEquals("1280x960", WolfOptions().windowsResolution(false))
        assertEquals("1920x1080", WolfOptions(resolution = "1920x1080").windowsResolution(false))
        assertEquals("800x600", WolfOptions(resolution = "800x600").windowsResolution(true))
    }

    @Test fun zoomKeepsThePointUnderTheFingersAndPansWithinTheImage() {
        val view = ViewTransformation()
        fun update() = view.update(800, 600, 1280, 960, 800, 600, WolfImageMode.FIT.ordinal)
        update()
        view.zoomBy(2f, 400f, 300f, 0f, 0f); update()
        assertEquals(1600, view.viewWidth); assertEquals(1200, view.viewHeight)
        assertEquals(-400, view.viewOffsetX); assertEquals(-300, view.viewOffsetY)
        val before = WolfViewport(view.viewOffsetX, view.viewOffsetY, view.viewWidth, view.viewHeight, 1280, 960, 800, 600).point(250f, 200f)
        view.zoomBy(1.5f, 270f, 210f, 20f, 10f); update()
        val after = WolfViewport(view.viewOffsetX, view.viewOffsetY, view.viewWidth, view.viewHeight, 1280, 960, 800, 600).point(270f, 210f)
        assertEquals(before, after)
        view.zoomBy(10f, 400f, 300f, 10000f, -10000f); update()
        assertEquals(4f, view.zoom, 0f)
        assertTrue(view.viewOffsetX <= 0 && view.viewOffsetX + view.viewWidth >= 800)
        assertTrue(view.viewOffsetY <= 0 && view.viewOffsetY + view.viewHeight >= 600)
        view.resetZoom(); update()
        assertEquals(1f, view.zoom, 0f); assertEquals(800, view.viewWidth); assertEquals(0, view.viewOffsetX)
    }

    @Test fun pinchingCannotShrinkTheImageBelowItsSelectedFraming() {
        val view = ViewTransformation()
        view.update(1920, 1080, 1280, 720, 800, 600, WolfImageMode.FILL.ordinal)
        view.zoomBy(.1f, 500f, 300f, 0f, 0f)
        view.update(1920, 1080, 1280, 720, 800, 600, WolfImageMode.FILL.ordinal)
        assertEquals(1f, view.zoom, 0f); assertEquals(-180, view.viewOffsetY)
        view.zoomBy(Float.NaN, 0f, 0f, 0f, 0f)
        assertEquals(1f, view.zoom, 0f)
    }
}
