package fr.astragames.app.windows

import kotlin.math.abs
import kotlin.math.hypot

/** Eight equal angular sectors, with a small central dead zone for resting the thumb. */
internal object WolfJoystickDirection {
    fun directions(x: Float, y: Float): Set<String> {
        if (!x.isFinite() || !y.isFinite() || hypot(x, y) < .18f) return emptySet()
        val result = mutableSetOf<String>()
        // tan(22.5 degrees): diagonals and cardinal directions each span 45 degrees.
        if (abs(x) > abs(y) * .41421356f) result += if (x < 0) "left" else "right"
        if (abs(y) > abs(x) * .41421356f) result += if (y < 0) "up" else "down"
        return result
    }
}
