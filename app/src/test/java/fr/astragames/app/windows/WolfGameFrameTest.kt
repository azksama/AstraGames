package fr.astragames.app.windows

import com.winlator.renderer.GameFrameReadiness
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class WolfGameFrameTest {
    @Test fun setupAndConsoleWindowsNeverUnlockGameControls() {
        for (name in listOf("explorer.exe", "wineconsole.exe", "conhost.exe", "cmd.exe", "wineboot.exe", "winedbg.exe", "Other.exe"))
            assertFalse(name, GameFrameReadiness.isGameWindow(name, "GamePro.exe"))
        assertTrue(GameFrameReadiness.isGameWindow("GAMEPRO.EXE", "GamePro.exe"))
        assertTrue(GameFrameReadiness.isGameWindow("WOLF_RPG_GAME", "GamePro.exe"))
    }
    @Test fun blackClientWithOpaqueAlphaIsStillLoading() {
        val pixels = ByteBuffer.allocate(320 * 240 * 4)
        for (i in 3 until pixels.limit() step 4) pixels.put(i, 255.toByte())
        assertFalse(GameFrameReadiness.hasVisiblePixels(pixels, 320, 240, 320))
        for (i in 0 until pixels.limit() step 4) pixels.put(i, 80)
        assertTrue(GameFrameReadiness.hasVisiblePixels(pixels, 320, 240, 320))
    }
    @Test fun pixelInspectionDoesNotConsumeTheBufferAndRejectsIncompleteData() {
        val pixels = ByteBuffer.allocate(8 * 8 * 4)
        pixels.put(0, 100); pixels.position(10)
        assertFalse(GameFrameReadiness.hasVisiblePixels(pixels, 8, 8, 8))
        assertEquals(10, pixels.position())
        assertFalse(GameFrameReadiness.hasVisiblePixels(null, 320, 240, 320))
        assertFalse(GameFrameReadiness.hasVisiblePixels(pixels, 320, 240, 320))
    }
}
