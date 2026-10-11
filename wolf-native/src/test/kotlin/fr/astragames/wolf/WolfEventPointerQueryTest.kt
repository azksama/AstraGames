package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfEventPointerQueryTest {
    private val foot = WolfEventPointerQuery.Actor(4, 32, 32, false)
    private fun find(actors: List<WolfEventPointerQuery.Actor>, x: Int, y: Int, extended: Boolean = false,
        loopX: Boolean = false, loopY: Boolean = false) = WolfEventPointerQuery.find(actors, x, y, 16, 96, 96, loopX, loopY, extended)

    @Test fun footIsLowerHalfWithInclusiveStartExclusiveEndAndSmallestEventPriority() {
        assertEquals(-1, find(listOf(foot), 40, 39)); assertEquals(4, find(listOf(foot), 32, 40))
        assertEquals(4, find(listOf(foot), 47, 47)); assertEquals(-1, find(listOf(foot), 48, 47))
        assertEquals(-1, find(listOf(foot), 47, 48))
        assertEquals(2, find(listOf(foot, foot.copy(id = 2)), 40, 44))
        assertEquals(4, find(listOf(foot.copy(square = true)), 40, 32))
    }
    @Test fun extendedContactsExpandByHalfTilesAndNormalCopiesKeepTheObservedNativeWrapPolicy() {
        val wide = foot.copy(reachX = 1, reachY = 1)
        assertEquals(-1, find(listOf(wide), 24, 32))
        assertEquals(4, find(listOf(wide), 24, 32, extended = true))
        assertEquals(-1, find(listOf(wide), 23, 32, extended = true))
        val boundary = foot.copy(x = 92)
        assertEquals(4, find(listOf(boundary), 0, 44))
        assertEquals(-1, find(listOf(boundary), 0, 44, extended = true))
        assertEquals(4, find(listOf(boundary), 0, 44, extended = true, loopX = true))
    }
}
