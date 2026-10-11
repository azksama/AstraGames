package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test

class WolfJoystickDirectionTest {
    @Test fun cardinalAndDiagonalSectorsMatchTheSameMovementKeysAsTheCross() {
        assertEquals(setOf("up"), WolfJoystickDirection.directions(.1f, -.9f))
        assertEquals(setOf("down"), WolfJoystickDirection.directions(-.1f, .9f))
        assertEquals(setOf("left"), WolfJoystickDirection.directions(-.9f, .1f))
        assertEquals(setOf("right"), WolfJoystickDirection.directions(.9f, -.1f))
        assertEquals(setOf("up", "left"), WolfJoystickDirection.directions(-.8f, -.8f))
        assertEquals(setOf("up", "right"), WolfJoystickDirection.directions(.8f, -.8f))
        assertEquals(setOf("down", "left"), WolfJoystickDirection.directions(-.8f, .8f))
        assertEquals(setOf("down", "right"), WolfJoystickDirection.directions(.8f, .8f))
    }
    @Test fun restingThumbAndInvalidCoordinatesNeverPressAKey() {
        listOf(0f to 0f, .1f to -.1f, Float.NaN to 1f, 1f to Float.POSITIVE_INFINITY).forEach { (x, y) ->
            assertTrue(WolfJoystickDirection.directions(x, y).isEmpty())
        }
    }
    @Test fun angularBoundaryGivesEqualSectorsAndNeverOppositeDirections() {
        assertEquals(setOf("right"), WolfJoystickDirection.directions(1f, .4f))
        assertEquals(setOf("right", "down"), WolfJoystickDirection.directions(1f, .42f))
        for (step in 0..720) {
            val angle = step * Math.PI / 360
            val result = WolfJoystickDirection.directions(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            assertTrue(result.size in 1..2)
            assertFalse("left" in result && "right" in result); assertFalse("up" in result && "down" in result)
        }
    }
}
