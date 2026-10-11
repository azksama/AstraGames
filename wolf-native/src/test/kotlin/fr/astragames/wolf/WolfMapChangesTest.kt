package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfMapChangesTest {
    private fun tileset(id: Int = 0, autoSlots: Int = 32) = WolfTileset(id, "tiles$id", "base$id.png",
        List(autoSlots - 1) { "auto$it.png" }, ByteArray(64), IntArray(64))
    private fun map(tile: Int = 100000) = WolfMap("patch", "", 103, WolfEncoding.UTF8, 0, 5, 5, 3,
        IntArray(75) { tile }, emptyList(), 0x69, 0)

    @Test fun rectangleShapesTheEightEdgesAroundAHoleWithoutMutatingPublishedMaps() {
        val initial = map(204444)
        val changes = WolfMapChanges(initial, listOf(tileset()))
        val published = changes.map
        changes.rectangle(1, 2, 2, 1, 1, 32)
        assertEquals(0, changes.map.tile(2, 2, 1))
        // These quarter digits come directly from466240 and the 3x3 adjacency around the hole.
        assertEquals(202244, changes.map.tile(2, 3, 1))
        assertEquals(204422, changes.map.tile(2, 1, 1))
        assertEquals(204141, changes.map.tile(1, 2, 1))
        assertEquals(201414, changes.map.tile(3, 2, 1))
        assertEquals(204443, changes.map.tile(1, 1, 1))
        assertEquals(204434, changes.map.tile(3, 1, 1))
        assertEquals(204344, changes.map.tile(1, 3, 1))
        assertEquals(203444, changes.map.tile(3, 3, 1))
        assertTrue(published.tiles.all { it == 204444 })
        assertTrue(initial.tiles.all { it == 204444 })
        assertEquals(204444, changes.map.tile(2, 2, 0))
        assertEquals(204444, changes.map.tile(2, 2, 2))
    }

    @Test fun isolatedAutotileUsesOuterQuartersAndAFullMapConnectsPastItsBoundary() {
        val isolated = WolfMapChanges(map(0), listOf(tileset()))
        isolated.rectangle(0, 2, 2, 1, 1, 1)
        assertEquals(200000, isolated.map.tile(2, 2, 0))
        isolated.rectangle(0, 0, 0, Int.MAX_VALUE, Int.MAX_VALUE, 2)
        assertTrue(isolated.map.tiles.take(25).all { it == 304444 })
        assertTrue(isolated.map.tiles.drop(25).all { it == 0 })
        val saved = isolated.state()!!
        val fresh = WolfMapChanges(map(0), listOf(tileset()))
        fresh.restore(saved)
        assertArrayEquals(isolated.map.tiles, fresh.map.tiles)
    }

    @Test fun passageAndTilesetCopiesRetainThePublishedFlagsAndResetToTheSelectedDefaults() {
        val first = tileset().copy(passage = IntArray(64) { 15 })
        val second = tileset(1)
        val changes = WolfMapChanges(map(), listOf(first, second))
        val published = changes.tileset!!
        changes.passage(32, 0x240)
        assertEquals(0x240, changes.tileset!!.passage[32])
        assertEquals(15, published.passage[32])
        assertEquals(15, first.passage[32])
        changes.switchTileset(1)
        assertEquals(1, changes.map.tilesetId)
        assertEquals(0, changes.tileset!!.passage[32])
        changes.passage(32, 15); changes.resetPassage()
        assertEquals(0, changes.tileset!!.passage[32])
        val restored = WolfMapChanges(map(), listOf(first, second))
        restored.restore(changes.state())
        assertEquals(1, restored.map.tilesetId)
        assertArrayEquals(second.passage, restored.tileset!!.passage)
    }

    @Test fun malformedOverlaysFailBeforeAnyMapOrTilesetChange() {
        val changes = WolfMapChanges(map(), listOf(tileset()))
        val original = changes.map
        listOf(WolfMapChanges.State(5, emptyMap(), emptyMap()),
            WolfMapChanges.State(0, mapOf(75 to 0), emptyMap()),
            WolfMapChanges.State(0, mapOf(0 to 200555), emptyMap()),
            WolfMapChanges.State(0, emptyMap(), mapOf(64 to 0)),
            WolfMapChanges.State(0, emptyMap(), mapOf(0 to 0x400))).forEach { state ->
            assertThrows(RuntimeException::class.java) { changes.restore(state) }
            assertSame(original, changes.map)
            assertNull(changes.state())
        }
    }

    @Test fun editorLiteralChipIDsFollowBothNativeConversions() {
        assertEquals(16, WolfMapChanges.literalIndex(8, false))
        assertEquals(32, WolfMapChanges.literalIndex(8, true))
        assertEquals(15, WolfMapChanges.literalIndex(-18, false))
        assertEquals(15, WolfMapChanges.literalIndex(-18, true))
        assertEquals(31, WolfMapChanges.literalIndex(-34, true))
        assertEquals(988, WolfMapChanges.literalIndex(964, true))
        assertEquals(972, WolfMapChanges.literalIndex(964, false))
        assertThrows(IllegalStateException::class.java) { WolfMapChanges.literalIndex(-19, false) }
    }
}
