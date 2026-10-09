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
}
