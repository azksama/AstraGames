package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfStraightRouteTest {
    @Test fun fourDirectionSteeringTriesTheGreaterAxisThenTheOtherAxis() {
        assertEquals(listOf(1 to 0, 0 to -1), WolfStraightRoute.candidates(5, -2, 4, false))
        assertEquals(listOf(0 to 1, -1 to 0), WolfStraightRoute.candidates(-2, 5, 4, true))
        assertEquals(listOf(-1 to 0), WolfStraightRoute.candidates(-1, 0, 4, false))
        assertEquals(emptyList<Pair<Int, Int>>(), WolfStraightRoute.candidates(0, 0, 4, true))
    }

    @Test fun equalAxesUseTheSuppliedNativeRandomBitAndEightDirectionsKeepDiagonals() {
        assertEquals(listOf(1 to 0, 0 to 1), WolfStraightRoute.candidates(2, 2, 4, true))
        assertEquals(listOf(0 to 1, 1 to 0), WolfStraightRoute.candidates(2, 2, 4, false))
        assertEquals(listOf(-1 to 1), WolfStraightRoute.candidates(-2, 3, 8, true))
    }

    @Test fun steeringWrapsOnlyEnabledAxesAndPreservesTheOriginalEqualDistanceTie() {
        assertEquals(-2, WolfStraightRoute.delta(0, 18, 20, true))
        assertEquals(2, WolfStraightRoute.delta(18, 0, 20, true))
        assertEquals(10, WolfStraightRoute.delta(0, 10, 20, true))
        assertEquals(-10, WolfStraightRoute.delta(10, 0, 20, true))
        assertEquals(18, WolfStraightRoute.delta(0, 18, 20, false))
    }
}
