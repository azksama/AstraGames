package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfAutonomousMotionTest {
    @Test fun randomMovementRetainsTheNativePauseAndForwardBranches() {
        val outcomes = (0..5).map { roll -> WolfAutonomousMotion.action(2, 0, 0) { assertEquals(6, it); roll } }
        assertEquals(listOf(WolfAutonomousMotion.Action.RANDOM, WolfAutonomousMotion.Action.RANDOM,
            WolfAutonomousMotion.Action.RANDOM, WolfAutonomousMotion.Action.RANDOM,
            WolfAutonomousMotion.Action.FORWARD, WolfAutonomousMotion.Action.PAUSE), outcomes)
    }
    @Test fun approachOnlyConsumesItsDecisionRollInsideTenFullCells() {
        assertEquals(WolfAutonomousMotion.Action.RANDOM, WolfAutonomousMotion.action(3, -15, 5) { fail("far actor consumes no decision RNG"); 0 })
        assertEquals(WolfAutonomousMotion.Action.TOWARD, WolfAutonomousMotion.action(3, -15, 4) { 3 })
        assertEquals(WolfAutonomousMotion.Action.RANDOM, WolfAutonomousMotion.action(3, 0, 0) { 4 })
        assertEquals(WolfAutonomousMotion.Action.FORWARD, WolfAutonomousMotion.action(3, 0, 0) { 5 })
        assertEquals(WolfAutonomousMotion.Action.RANDOM, WolfAutonomousMotion.action(3, Int.MIN_VALUE, 0) { fail("distance must not overflow"); 0 })
    }
    @Test fun eightDirectionRandomOrderMatchesTheShippedMovementHelpers() {
        assertEquals(listOf(2, 4, 6, 8, 1, 3, 7, 9), (0..7).map { roll -> WolfAutonomousMotion.randomDirection(8) { assertEquals(8, it); roll } })
        assertEquals(listOf(2, 4, 6, 8), (0..3).map { roll -> WolfAutonomousMotion.randomDirection(4) { assertEquals(4, it); roll } })
    }
}
