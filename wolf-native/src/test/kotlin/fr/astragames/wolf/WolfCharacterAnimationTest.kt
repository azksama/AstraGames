package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfCharacterAnimationTest {
    @Test fun threePatternCadenceUsesStrictIntervalAndPingPong() {
        val animation = WolfCharacterAnimation(3, false, 372, WolfCharacterAnimation.State(1, 1, 1))
        var pattern = 1
        repeat(6) { pattern = animation.tick(pattern, 1, true, false, true, false) }
        assertEquals(1, pattern)
        pattern = animation.tick(pattern, 1, true, false, true, false)
        assertEquals(2, pattern)
        val phases = mutableListOf(pattern)
        repeat(3) { repeat(7) { pattern = animation.tick(pattern, 1, true, false, true, false) }; phases += pattern }
        assertEquals(listOf(2, 1, 0, 1), phases)
    }

    @Test fun autonomousAnimationKeepsLogicalMovementSeparateAndSurvivesRestore() {
        val first = WolfCharacterAnimation(3, false, 372, WolfCharacterAnimation.State(0, 1, 1))
        // Move-animation alone must not animate a resting character.
        assertEquals(1, first.tick(1, 1, false, false, true, false))
        first.animateOnStop(true)
        assertEquals(2, first.tick(1, 1, false, false, true, false))
        val second = WolfCharacterAnimation(3, false, 372, first.state)
        assertEquals(first.tick(2, 1, false, false, true, false), second.tick(2, 1, false, false, true, false))
        assertEquals(first.state, second.state)
        first.animateOnStop(false)
        assertEquals(1, first.tick(2, 1, false, false, true, false))
    }

    @Test fun idleCadenceUsesThreeQuartersAndFivePatternsHaveTheirOwnIntervals() {
        val idle = WolfCharacterAnimation(3, false, 372, WolfCharacterAnimation.State(1, 1, 1))
        var pattern = 1
        repeat(8) { pattern = idle.tick(pattern, 1, false, false, false, true) }
        assertEquals(1, pattern)
        assertEquals(2, idle.tick(pattern, 1, false, false, false, true))
        val five = WolfCharacterAnimation(5, false, 372, WolfCharacterAnimation.State(1, 2, 2))
        pattern = 2
        val phases = mutableListOf<Int>()
        repeat(8) { repeat(4) { pattern = five.tick(pattern, 1, true, false, true, false) }; phases += pattern }
        assertEquals(listOf(3, 4, 3, 2, 1, 0, 1, 2), phases)
    }

    @Test fun cyclicCadenceAndThirtyFpsUseLogicalDeltaWithoutRenderingClock() {
        val animation = WolfCharacterAnimation(3, true, 372, WolfCharacterAnimation.State(1, 1, 1))
        var pattern = 1
        repeat(3) { pattern = animation.tick(pattern, 2, true, false, true, false) }
        assertEquals(1, pattern)
        assertEquals(2, animation.tick(pattern, 2, true, false, true, false))
        val jump = WolfCharacterAnimation(3, false, 372, WolfCharacterAnimation.State(0, 1, 1))
        assertEquals(1, jump.tick(1, 2, true, true, true, true))
        assertEquals(0.0, jump.state.accumulator, 0.0)
    }
}
