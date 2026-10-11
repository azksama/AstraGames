package fr.astragames.wolf

import kotlin.math.abs

/** W3 9ea7f0/9eb000/9ead50: logical sprite cadence, independent of viewport rendering. */
internal class WolfCharacterAnimation(
    private val patterns: Int,
    private val cyclic: Boolean,
    private val compatibilityVersion: Int,
    initial: State,
) {
    data class State(
        val frequency: Int,
        val idlePattern: Int,
        val basePattern: Int,
        val phase: Int = 0,
        val accumulator: Double = 0.0,
        val idleTicks: Int = 0,
        val movementMemory: Int = 0,
        val animateOnStop: Boolean = false,
    )

    var state = validate(initial); private set

    init {
        require(patterns in setOf(3, 5)) { "Motifs d'animation non portés" }
        require(initial.idlePattern < patterns && initial.basePattern == patterns / 2) { "Motif initial d'animation invalide" }
    }

    fun restore(value: State) {
        require(value.idlePattern < patterns && value.basePattern == patterns / 2) { "Motif restauré d'animation invalide" }
        state = validate(value)
    }
    fun frequency(value: Int) { require(value in 0..6); state = state.copy(frequency = value) }
    fun idlePattern(value: Int) { require(value in 0 until patterns); state = state.copy(idlePattern = value) }
    fun animateOnStop(value: Boolean) { state = state.copy(animateOnStop = value) }
    fun resetIdleTicks() { state = state.copy(idleTicks = 0) }

    /** [moving] means pixel displacement. A jump skips both ordinary cadence helpers. */
    fun tick(pattern: Int, delta: Int, moving: Boolean, jumping: Boolean, moveAnimation: Boolean,
        idleAnimation: Boolean, idleCounterAllowed: Boolean = true, arrived: Boolean = false): Int {
        require(delta in 1..2) { "Cadence logique invalide" }
        var s = state
        if (!jumping) {
            val increment = if (moving) {
                if (moveAnimation) delta.toDouble() else if (idleAnimation) delta * .75 else 0.0
            } else {
                if (s.animateOnStop && moveAnimation) delta.toDouble()
                else if (idleAnimation) delta * .75
                else if (pattern != s.idlePattern) delta.toDouble() else 0.0
            }
            val idle = if (moving && !arrived && compatibilityVersion > 334) 0
                else if (moving && !arrived) s.idleTicks
                else if (idleCounterAllowed) (s.idleTicks + delta).coerceAtMost(1_000_000_000) else s.idleTicks
            s = s.copy(accumulator = s.accumulator + increment, idleTicks = idle)
        }
        var output = pattern
        val interval = (if (patterns == 5) FIVE_INTERVALS else THREE_INTERVALS)[s.frequency]
        // The binary tests strictly greater than the interval, and discards the excess.
        if (s.accumulator > interval) {
            if (!idleAnimation && s.idleTicks > s.movementMemory + delta && !s.animateOnStop) {
                output = s.idlePattern
                s = s.copy(phase = 0, accumulator = 0.0)
            } else {
                val phase = (s.phase + 1) % if (cyclic) patterns else (patterns - 1) * 2
                output = when {
                    cyclic -> (s.basePattern + phase) % patterns
                    patterns == 3 -> if (phase < 2) s.basePattern + phase else s.basePattern - phase + 2
                    phase < 5 -> s.basePattern - abs(2 - phase) + 2
                    else -> s.basePattern + abs(6 - phase) - 2
                }
                s = s.copy(phase = phase, accumulator = 0.0)
            }
        }
        val memory = if (compatibilityVersion > 330) {
            if (moving) 4 else maxOf(0, s.movementMemory - 1)
        } else s.movementMemory
        state = s.copy(movementMemory = memory)
        return output
    }

    companion object {
        private val THREE_INTERVALS = intArrayOf(0, 6, 10, 16, 24, 60, 84)
        // W3's one-time initialization speeds five-pattern characters relative to three-pattern ones.
        private val FIVE_INTERVALS = intArrayOf(0, 3, 5, 8, 12, 40, 63)
        fun validate(state: State): State {
            require(state.frequency in 0..6 && state.idlePattern in 0..4 && state.basePattern in 0..4 &&
                state.phase in 0..7 && state.accumulator.isFinite() && state.accumulator in 0.0..1_000_000_000.0 &&
                state.idleTicks in 0..1_000_000_000 && state.movementMemory in 0..1_000_000_000) {
                "État d'animation invalide"
            }
            return state
        }
    }
}
