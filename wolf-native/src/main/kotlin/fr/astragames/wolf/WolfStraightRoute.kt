package fr.astragames.wolf

import kotlin.math.abs

/** Route53/54 use direct steering (W3 9ec3d0), independently of the later A* route59..61. */
internal object WolfStraightRoute {
    fun delta(from: Int, to: Int, period: Int, loop: Boolean): Int {
        require(period > 0)
        var result = to - from
        if (loop) {
            result = Math.floorMod(to, period) - Math.floorMod(from, period)
            if (abs(result - period) < abs(result)) result -= period
            if (abs(result + period) < abs(result)) result += period
        }
        return result
    }

    /** One steering attempt; the runner advances after a successful step, retrying only a blocked step. */
    fun candidates(dx: Int, dy: Int, directions: Int, horizontalTie: Boolean): List<Pair<Int, Int>> {
        require(directions in setOf(4, 8))
        if (dx == 0 && dy == 0) return emptyList()
        val x = dx.compareTo(0); val y = dy.compareTo(0)
        if (directions == 8) return listOf(x to y)
        val horizontal = abs(dx) > abs(dy) || abs(dx) == abs(dy) && horizontalTie
        return if (horizontal) listOf(x to 0, 0 to y).filter { it != (0 to 0) }
        else listOf(0 to y, x to 0).filter { it != (0 to 0) }
    }
}
