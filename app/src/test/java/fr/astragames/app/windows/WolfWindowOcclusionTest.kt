package fr.astragames.app.windows

import com.winlator.renderer.WindowOcclusion
import org.junit.Assert.*
import org.junit.Test

class WolfWindowOcclusionTest {
    @Test fun completeOpaqueCoverageIncludesFullscreenAndNegativeCoordinates() {
        assertTrue(WindowOcclusion.covers(0, 0, 1280, 960, 0, 0, 1280, 960, false))
        assertTrue(WindowOcclusion.covers(10, 20, 300, 200, -10, -20, 1280, 960, false))
    }
    @Test fun transparentPartialOrEmptyWindowsNeverHideVisiblePixels() {
        assertFalse(WindowOcclusion.covers(0, 0, 1280, 960, 0, 0, 1280, 960, true))
        assertFalse(WindowOcclusion.covers(0, 0, 1280, 960, 1, 0, 1280, 960, false))
        assertFalse(WindowOcclusion.covers(0, 0, 1280, 960, 0, 0, 1279, 960, false))
        assertFalse(WindowOcclusion.covers(0, 0, 1280, 960, 0, 0, 1280, 959, false))
        assertFalse(WindowOcclusion.covers(0, 0, 1280, 960, 0, 0, 0, 0, false))
        assertFalse(WindowOcclusion.covers(Int.MAX_VALUE - 1, 0, 2, 10, Int.MAX_VALUE - 5, 0, 5, 10, false))
    }
}
