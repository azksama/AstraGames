package fr.astragames.wolf

import kotlin.math.abs

/** W3 9fbd50/9fbd90: autonomous decisions use six branches and a half-cell proximity threshold. */
internal object WolfAutonomousMotion {
    enum class Action { RANDOM, TOWARD, FORWARD, PAUSE }

    fun action(mode: Int, dx: Int, dy: Int, random: (Int) -> Int): Action = when (mode) {
        2 -> when (random(6)) {
            in 0..3 -> Action.RANDOM
            4 -> Action.FORWARD
            else -> Action.PAUSE
        }
        3 -> if (abs(dx.toLong()) + abs(dy.toLong()) >= 20) Action.RANDOM else when (random(6)) {
            in 0..3 -> Action.TOWARD
            4 -> Action.RANDOM
            else -> Action.FORWARD
        }
        else -> error("Mode autonome sans décision : $mode")
    }

    /** 9ff300 masks a fresh random word; diagonal order follows the native movement helpers. */
    fun randomDirection(directions: Int, random: (Int) -> Int): Int {
        require(directions in setOf(4, 8))
        return intArrayOf(2, 4, 6, 8, 1, 3, 7, 9)[random(directions)]
    }
}
