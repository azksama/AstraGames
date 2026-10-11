package fr.astragames.wolf

/** W2 engine update430...: counters are host frames, with Sys15/16 divided by tick delta. */
internal class WolfKeyRepeats {
    private var accept = 0
    private var back = 0
    private var directionFrames = 0
    var direction = 0; private set
    fun reset() { accept = 0; back = 0; directionFrames = 0; direction = 0 }
    fun tick(acceptHeld: Boolean, backHeld: Boolean, nextDirection: Int) {
        accept = if (acceptHeld) accept + 1 else 0
        back = if (backHeld) back + 1 else 0
        directionFrames = if (direction != nextDirection || nextDirection == 0) 0 else directionFrames + 1
        direction = nextDirection
    }
    fun pulse(kind: Int, delay: Int, interval: Int, delta: Int): Boolean {
        require(delta in 1..2 && kind in 0..2)
        val initial = maxOf(1, delay / delta)
        val every = maxOf(1, interval / delta)
        val counter = when (kind) { 0 -> accept; 1 -> back; else -> directionFrames }
        return counter == initial || counter > initial && (counter - initial) % every == 0
    }
}
