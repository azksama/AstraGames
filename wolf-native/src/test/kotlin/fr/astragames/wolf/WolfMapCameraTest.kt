package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfMapCameraTest {
    @Test fun centreCorrectionsShiftTheFollowThresholdOnBothAxes() {
        val camera = WolfMapCamera().apply { reset(0, 0, 1000, 1000) }
        // 96px viewport, 16px tile: original centre is40px. Sys131=8 shifts it to48px.
        camera.follow(160, 160, 176, 176, 96, 96, 16, 1000, 1000, centreOffsetX = 8, centreOffsetY = -8)
        assertEquals(0, camera.state.x); assertEquals(16, camera.state.y)
        camera.follow(176, 176, 200, 200, 96, 96, 16, 1000, 1000, centreOffsetX = 8, centreOffsetY = -8)
        assertEquals(24, camera.state.x); assertEquals(40, camera.state.y)
    }
    @Test fun loopingCameraWrapsItsNegativeEdgeAndKeepsPositiveScrollingUnbounded() {
        val camera = WolfMapCamera()
        camera.reset(-1, -641, 0, 0, 640, 640)
        assertEquals(639, camera.state.x); assertEquals(639, camera.state.y)
        camera.position(1281, 1281, 0, 0, 640, 640)
        assertEquals(1281, camera.state.x); assertEquals(1281, camera.state.y)
        camera.start(15, -2, 8, 16); camera.tick(0, 0, 640, 640)
        assertEquals(1296, camera.state.x); assertEquals(1279, camera.state.y)
        assertFalse(camera.scrolling)
    }

    @Test fun followAcrossALoopSeamUsesASingleStepInsteadOfOneMapWidth() {
        val camera = WolfMapCamera()
        camera.reset(324, 0, 0, 0, 640, 0)
        // Screen midpoint in quarter pixels: (96-16)*2=160.
        camera.follow(624, 0, 16, 0, 96, 96, 16, 0, 0, 640, 0)
        assertEquals(356, camera.state.x)
        assertEquals(0, camera.state.y)
    }

    @Test fun diagonalScrollHasIndependentNativeQuarterPixelSpeeds() {
        val camera = WolfMapCamera()
        camera.start(75, 50, 2, 16)
        assertEquals(6, camera.state.horizontal.step)
        assertEquals(4, camera.state.vertical.step)
        repeat(12) { camera.tick(1000, 1000) }
        assertEquals(72, camera.state.x); assertEquals(48, camera.state.y)
        assertTrue(camera.scrolling)
        camera.tick(1000, 1000)
        assertEquals(75, camera.state.x); assertEquals(50, camera.state.y)
        assertFalse(camera.scrolling)
    }

    @Test fun aClampedPanStillFinishesAndInstantSpeedHandlesThinDiagonal() {
        val camera = WolfMapCamera()
        camera.reset(64, 64, 64, 64)
        camera.start(75, -50, 2, 16)
        repeat(13) { camera.tick(64, 64) }
        assertEquals(64, camera.state.x); assertEquals(14, camera.state.y)
        assertFalse(camera.scrolling)
        camera.reset(0, 0, 10000, 10000)
        camera.start(1, 5000, 8, 16)
        camera.tick(10000, 10000)
        assertEquals(1, camera.state.x); assertEquals(5000, camera.state.y)
        assertFalse(camera.scrolling)
    }

    @Test fun reEnablingFollowKeepsTheManualOffsetUntilTheHeroMovesPastCentre() {
        val camera = WolfMapCamera()
        camera.reset(80, 40, 1000, 1000)
        camera.followsHero(false)
        camera.follow(680, 400, 700, 400, 320, 240, 16, 1000, 1000)
        assertEquals(80, camera.state.x)
        camera.followsHero(true)
        camera.follow(700, 400, 700, 400, 320, 240, 16, 1000, 1000)
        assertEquals(80, camera.state.x)
        camera.follow(700, 400, 720, 400, 320, 240, 16, 1000, 1000)
        assertEquals(100, camera.state.x)
        camera.follow(720, 400, 716, 400, 320, 240, 16, 1000, 1000)
        assertEquals(100, camera.state.x)
    }

    @Test fun saveRetainsAnInProgressPanAndRejectsCorruptMotionBeforeMutation() {
        val camera = WolfMapCamera()
        camera.start(75, -50, 2, 16); camera.tick(1000, 1000); camera.followsHero(false)
        val snapshot = WolfSnapshot.State("camera", 1, 0, 1f, 1f, 2, "", 1,
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), camera = camera.state)
        val saved = WolfSnapshot.decode(WolfSnapshot.encode(snapshot), "camera")
        val restored = WolfMapCamera().apply { restore(requireNotNull(saved.camera)) }
        repeat(12) { camera.tick(1000, 1000); restored.tick(1000, 1000) }
        assertEquals(camera.state, restored.state)
        val before = restored.state
        try { restored.restore(before.copy(horizontal = WolfMapCamera.Axis(-1))); fail("Negative distance accepted") }
        catch (_: IllegalArgumentException) { assertEquals(before, restored.state) }
    }
}
