package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfVisibilityRuntimeTest {
    @Test fun resetMakesAnExistingCanvasVisibleWithoutClearingCharacterAndMapFlags() {
        val runtime = fixture(listOf(effect(4), effect(1, 20, 20), effect(7, -2, -2), effect(9),
            WolfCommand(180, intArrayOf(100), 0, emptyList())))
        runtime.tick()
        val hidden = runtime.frame()
        assertTrue(hidden.visibilityMask!!.pixels.all { it == (-1).toByte() })
        assertTrue(hidden.pictures.single().maskEnabled)
        assertTrue(hidden.map!!.maskEnabled)
        assertTrue(hidden.map.characters.single { it.id == -2 }.maskEnabled)
        val state = runtime.snapshotState()
        val decoded = WolfSnapshot.decode(runtime.snapshot(), "mask")
        assertArrayEquals(state.visibilityMask!!.pixels, decoded.visibilityMask!!.pixels)
        assertEquals(setOf(20), decoded.maskedPictures); assertEquals(setOf(-2), decoded.maskedCharacters)
        val restored = fixture(emptyList())
        restored.restoreState(decoded)
        assertTrue(restored.frame().pictures.single().maskEnabled)
        assertArrayEquals(hidden.visibilityMask.pixels, restored.frame().visibilityMask!!.pixels)

        val clear = fixture(listOf(effect(4), effect(1, 20, 20), effect(7, -2, -2), effect(9), effect(0)))
        clear.tick()
        val visible = clear.frame()
        assertTrue(visible.visibilityMask!!.pixels.all { it == 0.toByte() })
        assertFalse(visible.pictures.single().maskEnabled)
        assertTrue(visible.map!!.maskEnabled)
        assertTrue(visible.map.characters.single { it.id == -2 }.maskEnabled)
    }

    @Test fun imageImportSuspendsLaterEventsAndUsesRawBlueBytesForAndOr() {
        val runtime = fixture(listOf(effect(4), effect(5, x = 1, y = 2, path = "Mask.png"),
            WolfCommand(121, intArrayOf(2_000_000, 7, 0, 0), 0, emptyList())))
        runtime.tick()
        val request = runtime.drainEffects().filterIsInstance<NativeEffect.MaskingImage>().single()
        assertEquals("Data/Mask.png", request.path); assertEquals(0, request.mode)
        assertNull(runtime.snapshotState().numbers[0])
        val pixels = byteArrayOf(0, 1, 2, -1)
        runtime.completeMaskImage(request.requestId, NativeVisibilityMask(2, 2, pixels))
        pixels.fill(100) // The host's decoding buffer is never owned by a published frame.
        runtime.tick()
        assertEquals(7, runtime.snapshotState().numbers[0])
        val mask = runtime.frame().visibilityMask!!
        assertEquals(0, mask.pixels[2 * 96 + 1].toInt())
        assertEquals(1, mask.pixels[2 * 96 + 2].toInt())
        assertEquals(2, mask.pixels[3 * 96 + 1].toInt())
        assertEquals(-1, mask.pixels[3 * 96 + 2].toInt())
        val before = runtime.snapshot()
        try { runtime.restoreState(runtime.snapshotState().copy(visibilityMask = NativeVisibilityMask(2, 2, ByteArray(4)))); fail("Wrong canvas resolution accepted") }
        catch (_: IllegalArgumentException) { assertArrayEquals(before, runtime.snapshot()) }
    }

    private fun effect(operation: Int, first: Int = 0, last: Int = 0, x: Int = 0, y: Int = 0, path: String? = null) =
        WolfCommand(290, intArrayOf(operation * 16 + 3, 0, first, last, x, y, 0), 0, listOfNotNull(path))

    private fun fixture(commands: List<WolfCommand>): WolfRuntime {
        val source = object : WolfAssetSource {
            override fun read(path: String) = byteArrayOf()
            override fun list(path: String) = emptyList<String>()
            override fun exists(path: String) = true
        }
        val config = WolfGameConfig("mask", "", WolfEncoding.UTF8, 16, 96, 96, 60, "Hero.png", 4, 4, 3,
            "sans", emptyList(), ByteArray(40).also { it[8] = 1; it[9] = 1 }, IntArray(20).also { it[18] = 350 }, emptyList(), 0xc4)
        val page = WolfPage(0, -1, "", 2, 1, 255, 0, ByteArray(37).also { it[0] = 1 }, byteArrayOf(3, 3, 3, 0),
            3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
        val map = WolfMap("map", "map", 103, WolfEncoding.UTF8, 0, 6, 6, 3, IntArray(108) { 100000 },
            listOf(WolfEvent(0, "mask", 2, 2, listOf(page))), 0x69, 0)
        val data = WolfGameData(config, emptyList(), emptyMap(),
            listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
            listOf(WolfMapReference(0, "map", "map.mps")), WolfStartPosition(0, 2, 2))
        return WolfRuntime.fromData(source, data) { map }.also {
            it.restoreState(it.snapshotState().copy(pictures = listOf(NativePicture(20, path = "Data/Picture.png"))))
        }
    }
}
