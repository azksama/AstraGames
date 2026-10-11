package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfMapEffectsTest {
    @Test fun directionsAndQuarterPixelRoundingMatchTheWindowsStateMachine() {
        val effect = WolfMapShake()
        effect.start(2, 2, 1, 5)
        effect.tick(60)
        assertEquals(102, effect.state.horizontal.phase)
        assertEquals(0, effect.offsetX(16)) // Truncates rather than rounding the logical pixel.
        effect.tick(60); assertEquals(1, effect.offsetX(16))
        assertEquals(0, effect.offsetY(16))
        effect.start(2, 2, 0, 5); effect.tick(60)
        assertEquals(102, effect.state.vertical.phase)
    }
    @Test fun thirtyFpsUsesTheOriginalFrameRateDivisorAndStopSettlesAtZero() {
        val slow = WolfMapShake().apply { start(2, 2, 1, 50) }
        val fast = WolfMapShake().apply { start(2, 2, 1, 50) }
        slow.tick(30); fast.tick(60)
        assertEquals(51, slow.state.horizontal.phase)
        assertEquals(102, fast.state.horizontal.phase)
        fast.stop(); assertNotEquals(0, fast.state.horizontal.phase)
        repeat(40) { fast.tick(60) }
        assertEquals(0, fast.state.horizontal.phase)
        assertEquals(0, fast.state.horizontal.remaining)
    }
    @Test fun restoringMidCyclePreservesTheNextDirectionAndNaturalZeroCrossing() {
        val effect = WolfMapShake().apply { start(2, 2, 1, 12); repeat(7) { tick(60) } }
        assertEquals(-1, effect.state.horizontal.direction)
        val copy = WolfMapShake().apply { restore(effect.state) }
        repeat(40) { effect.tick(60); copy.tick(60); assertEquals(effect.state, copy.state) }
        assertEquals(0, effect.state.horizontal.phase)
    }
}
