package fr.astragames.wolf

/** W2 0044fa00 setter, 0044fb00 updater and 004500f0 pixel conversion. */
internal class WolfMapShake {
    data class Axis(val amplitude: Int = 0, val speed: Int = 0, val remaining: Int = 0,
        val phase: Int = 0, val direction: Int = 1)
    data class State(val horizontal: Axis = Axis(), val vertical: Axis = Axis())
    var state = State(); private set

    fun start(power: Int, speed: Int, direction: Int, duration: Int) {
        require(power in 0..15 && speed in 0..15 && direction in 0..1 && duration in 0..360_000)
        fun retarget(axis: Axis) = axis.copy(amplitude = 1 shl power, speed = 1 shl speed,
            remaining = duration + 1, direction = 1)
        state = if (direction == 0) state.copy(vertical = retarget(state.vertical))
            else state.copy(horizontal = retarget(state.horizontal))
    }
    // The Windows stop operation ends the timer; the next zero crossing removes the offset.
    fun stop() { state = state.copy(horizontal = state.horizontal.copy(remaining = 0), vertical = state.vertical.copy(remaining = 0)) }
    fun tick(fps: Int) {
        require(fps in setOf(30, 60))
        fun advance(axis: Axis): Axis {
            if (axis.remaining <= 0 && axis.phase == 0) return axis
            val delta = axis.amplitude * axis.speed * axis.direction * 64 / (60 / fps) / 10
            val phase = if (axis.remaining < 2 && (axis.phase + delta) * axis.phase < 1) 0 else axis.phase + delta
            val direction = when { phase > axis.amplitude * 128 -> -1; phase < axis.amplitude * -128 -> 1; else -> axis.direction }
            return axis.copy(remaining = (axis.remaining - 1).coerceAtLeast(0), phase = phase, direction = direction)
        }
        state = State(advance(state.horizontal), advance(state.vertical))
    }
    fun offsetX(tileSize: Int) = tileSize * state.horizontal.phase / 32 / 64
    fun offsetY(tileSize: Int) = tileSize * state.vertical.phase / 32 / 64
    fun reset() { state = State() }
    fun restore(value: State) {
        listOf(value.horizontal, value.vertical).forEach {
            require(it.amplitude in 0..32768 && it.speed in 0..32768 && it.remaining in 0..360_001 && it.direction in setOf(-1, 1))
            require(it.amplitude == 0 || it.amplitude.countOneBits() == 1)
            require(it.speed == 0 || it.speed.countOneBits() == 1)
        }
        state = value
    }
}

/** W2 0044f680 / 004500f0 store scroll distances in quarter pixels, independently on each axis. */
internal class WolfMapCamera {
    data class Axis(val remaining: Int = 0, val step: Int = 1, val direction: Int = 1)
    data class State(val x: Int = 0, val y: Int = 0, val followsHero: Boolean = true,
        val horizontal: Axis = Axis(), val vertical: Axis = Axis())
    var state = State(); private set
    val scrolling get() = state.horizontal.remaining > 0 || state.vertical.remaining > 0

    fun start(dx: Int, dy: Int, speed: Int, tile: Int) {
        require(dx != Int.MIN_VALUE && dy != Int.MIN_VALUE && speed in 0..15 && tile > 0)
        val distance = kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
        fun axis(delta: Int): Axis {
            val magnitude = kotlin.math.abs(delta)
            val velocity = if (speed == 8) minOf(magnitude, 2_000_000_000 / (tile * 2)) * tile * 2
                else 1 shl (speed + 1 - if (speed > 8) 1 else 0)
            val step = if (distance == 0.0) 1 else maxOf(1, (magnitude.toDouble() / distance * velocity).toInt())
            return Axis(magnitude, step, if (delta > 0) 1 else -1)
        }
        state = state.copy(horizontal = axis(dx), vertical = axis(dy))
    }

    fun tick(maxX: Int, maxY: Int, loopX: Int = 0, loopY: Int = 0) {
        fun axis(position: Int, axis: Axis, maximum: Int, period: Int): Pair<Int, Axis> {
            val distance = minOf(axis.remaining, axis.step)
            val next = bounded(position.toLong() + distance.toLong() * axis.direction, maximum, period)
            // Reaching a map boundary still consumes the requested distance and unblocks a waiting event.
            return next to axis.copy(remaining = axis.remaining - distance)
        }
        val horizontal = axis(state.x, state.horizontal, maxX, loopX)
        val vertical = axis(state.y, state.vertical, maxY, loopY)
        state = state.copy(x = horizontal.first, y = vertical.first,
            horizontal = horizontal.second, vertical = vertical.second)
    }

    fun follow(previousX: Int, previousY: Int, heroX: Int, heroY: Int,
        width: Int, height: Int, tile: Int, maxX: Int, maxY: Int, loopX: Int = 0, loopY: Int = 0, centreOffsetX: Int = 0, centreOffsetY: Int = 0) {
        if (!state.followsHero) return
        fun axis(position: Int, oldHero: Int, hero: Int, screen: Int, maximum: Int, period: Int, offset: Int): Int {
            val centre = (screen - tile) * 2 + offset * 4
            val delta = if (period == 0) hero - oldHero else Math.floorMod(hero - oldHero + period / 2, period) - period / 2
            val projected = if (period == 0) hero.toLong() else hero.toLong() +
                kotlin.math.round((position.toDouble() + centre - hero) / period).toLong() * period
            val screenHero = projected / 4 - position / 4
            val beyondCentre = if (delta < 0) screenHero * 4 < centre else screenHero * 4 > centre
            return if (delta != 0 && beyondCentre) bounded(position.toLong() + delta, maximum, period) else position
        }
        state = state.copy(x = axis(state.x, previousX, heroX, width, maxX, loopX, centreOffsetX),
            y = axis(state.y, previousY, heroY, height, maxY, loopY, centreOffsetY))
    }

    fun followsHero(value: Boolean) { state = state.copy(followsHero = value) }
    fun position(x: Int = state.x, y: Int = state.y, maxX: Int, maxY: Int, loopX: Int = 0, loopY: Int = 0) {
        state = state.copy(x = bounded(x.toLong(), maxX, loopX), y = bounded(y.toLong(), maxY, loopY))
    }
    fun reset(x: Int, y: Int, maxX: Int, maxY: Int, loopX: Int = 0, loopY: Int = 0) {
        state = State(bounded(x.toLong(), maxX, loopX), bounded(y.toLong(), maxY, loopY))
    }
    /** Native44e920 wraps a negative looping position;44e9e0 imposes no right/bottom edge. */
    private fun bounded(value: Long, maximum: Int, period: Int): Int {
        require(maximum >= 0 && period >= 0)
        return if (period == 0) value.coerceIn(0, maximum.toLong()).toInt()
        else (if (value < 0) Math.floorMod(value, period.toLong()) else value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun restore(value: State) {
        require(value.x >= 0 && value.y >= 0)
        listOf(value.horizontal, value.vertical).forEach {
            require(it.remaining >= 0 && it.step > 0 && it.direction in setOf(-1, 1))
        }
        state = value
    }
}
